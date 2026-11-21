// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.ClipData
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.droid.wayfinder.data.FsPath
import kotlinx.coroutines.launch
import java.io.File

/** Wraps an icon-only or compact action control in a Material 3 hover/long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

/** Vertical scrollbar overlay for [LazyListState] supporting mouse/trackpad click and drag. */
@Composable
fun LazyListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val layout = state.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (total <= 0 || visible.isEmpty() || (!state.canScrollForward && !state.canScrollBackward)) return
    val first = visible.first()
    val avgItemPx = visible.sumOf { it.size }.toFloat() / visible.size
    val estimatedTotalPx = (total * avgItemPx).coerceAtLeast(1f)
    val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat().coerceAtLeast(1f)
    val scrollPx = first.index * avgItemPx - first.offset
    val fraction = (viewportPx / estimatedTotalPx).coerceIn(0.08f, 1f)
    val offsetFraction = (scrollPx / (estimatedTotalPx - viewportPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()
    ScrollbarTrack(
        fraction = fraction,
        offsetFraction = offsetFraction,
        onScrub = { targetFrac ->
            val targetIdx = ((total - visible.size).coerceAtLeast(0) * targetFrac).toInt().coerceIn(0, total - 1)
            scope.launch { state.scrollToItem(targetIdx) }
        },
        modifier = modifier,
    )
}

/** Vertical scrollbar overlay for [LazyGridState] supporting mouse/trackpad click and drag. */
@Composable
fun LazyGridScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val layout = state.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (total <= 0 || visible.isEmpty() || (!state.canScrollForward && !state.canScrollBackward)) return
    val first = visible.first()
    val last = visible.last()
    val spanCount = (visible.count { it.row == first.row }).coerceAtLeast(1)
    val totalRows = ((total + spanCount - 1) / spanCount).coerceAtLeast(1)
    val visibleRows = (last.row - first.row + 1).coerceAtLeast(1)
    val fraction = (visibleRows.toFloat() / totalRows).coerceIn(0.08f, 1f)
    val offsetFraction = (first.row.toFloat() / (totalRows - visibleRows).coerceAtLeast(1)).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()
    ScrollbarTrack(
        fraction = fraction,
        offsetFraction = offsetFraction,
        onScrub = { targetFrac ->
            val targetIdx = ((total - visible.size).coerceAtLeast(0) * targetFrac).toInt().coerceIn(0, total - 1)
            scope.launch { state.scrollToItem(targetIdx) }
        },
        modifier = modifier,
    )
}

/** Vertical scrollbar overlay for [ScrollState] supporting mouse/trackpad click and drag. */
@Composable
fun ColumnScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    val max = state.maxValue
    if (max <= 0) return
    val viewport = state.viewportSize.toFloat().coerceAtLeast(1f)
    val total = viewport + max
    val fraction = (viewport / total).coerceIn(0.08f, 1f)
    val offsetFraction = (state.value.toFloat() / max).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()
    ScrollbarTrack(
        fraction = fraction,
        offsetFraction = offsetFraction,
        onScrub = { targetFrac ->
            scope.launch { state.scrollTo((max * targetFrac).toInt().coerceIn(0, max)) }
        },
        modifier = modifier,
    )
}

@Composable
private fun ScrollbarTrack(
    fraction: Float,
    offsetFraction: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thumbColor = if (hovered) cs.onSurfaceVariant.copy(alpha = 0.72f) else cs.onSurfaceVariant.copy(alpha = 0.42f)
    val trackColor = if (hovered) cs.onSurface.copy(alpha = 0.08f) else cs.onSurface.copy(alpha = 0.03f)
    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .hoverable(hover)
            .pointerInput(fraction) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    onScrub((offset.y / h).coerceIn(0f, 1f))
                }
            }
            .pointerInput(fraction) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    onScrub((change.position.y / h).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
            val thumbH = (h * fraction).coerceIn(24.dp.toPx(), h)
            val maxOffset = (h - thumbH).coerceAtLeast(0f)
            val thumbY = maxOffset * offsetFraction
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(0f, thumbY),
                size = Size(w, thumbH),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** Builds a cross-app [ClipData] with FileProvider URIs for local files so external apps can accept drops. */
fun buildCrossAppClipData(context: Context, paths: List<String>): ClipData? {
    val uris = paths.mapNotNull { path ->
        if (FsPath.isVirtual(path)) return@mapNotNull null
        val file = File(path)
        if (!file.exists()) return@mapNotNull null
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
    if (uris.isEmpty()) return null
    val firstFile = File(paths.first())
    val mime = if (firstFile.isDirectory) {
        "*/*"
    } else {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(firstFile.extension.lowercase()) ?: "*/*"
    }
    val clip = ClipData(
        firstFile.name,
        arrayOf(mime, "*/*"),
        ClipData.Item(uris.first()),
    )
    for (i in 1 until uris.size) {
        clip.addItem(ClipData.Item(uris[i]))
    }
    return clip
}
