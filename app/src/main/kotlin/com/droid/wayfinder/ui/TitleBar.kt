// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.droid.wayfinder.data.FsPath

internal val DESKTOP_CAPTION_DRAG_RESERVE = 48.dp

data class CaptionMetrics(
    val height: Dp,
    val start: Dp,
    val end: Dp,
    val topInset: Dp = 0.dp,
    val hasDesktopCaption: Boolean = false,
)

internal fun computeCaptionMetrics(
    capTopPx: Int,
    statusTopPx: Int,
    cutoutTopPx: Int = 0,
    startPx: Int,
    endPx: Int,
    density: Density,
): CaptionMetrics = with(density) {
    val minCaptionH = (44f * density.fontScale.coerceIn(1f, 1.35f)).dp
    // When no desktop caption bar is active (e.g. phone/tablet fullscreen with DeX disabled) and the
    // system status bar is visible, reserve max(statusTopPx, cutoutTopPx) above the tab strip so clock,
    // notification icons, and top camera cutouts never overlap the tabs. When statusBars are hidden
    // (statusTopPx == 0), topInset collapses to 0.dp so the tab strip reclaims the top edge.
    val topInset = if (capTopPx == 0 && statusTopPx > 0) maxOf(statusTopPx, cutoutTopPx).toDp() else 0.dp
    val height = if (capTopPx == 0 && statusTopPx > 0) topInset + minCaptionH else max(minCaptionH, capTopPx.toDp())
    CaptionMetrics(
        height = height,
        start = startPx.toDp(),
        end = endPx.toDp(),
        topInset = topInset,
        hasDesktopCaption = capTopPx > 0,
    )
}

/**
 * Measures the system caption (title) bar so that our tab strip can live inside it, leaving room for
 * the system window controls. Uses caption bounding rects on Android 15+.
 */
@Composable
fun rememberCaptionMetrics(): CaptionMetrics {
    val density = LocalDensity.current
    val view = LocalView.current
    val layoutDir = LocalLayoutDirection.current
    val capTop = WindowInsets.captionBar.getTop(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val widthPx = LocalWindowInfo.current.containerSize.width
    return remember(capTop, statusTop, cutoutTop, widthPx, layoutDir) {
        var left = 0
        var right = 0
        if (capTop > 0) {
            var found = false
            if (Build.VERSION.SDK_INT >= 35) {
                val rects = view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()
                for (r in rects) {
                    if (r.isEmpty) continue
                    if (r.left < widthPx * 0.25f) { left = maxOf(left, r.right); found = true }
                    else if (r.right > widthPx * 0.75f) { right = maxOf(right, widthPx - r.left); found = true }
                }
            }
            if (!found) {
                val fallbackPx = with(density) { 140.dp.roundToPx() }
                if (layoutDir == LayoutDirection.Rtl) left = fallbackPx else right = fallbackPx
            }
        } else if (statusTop == 0 && widthPx > 0) {
            // When the system status bar is hidden in fullscreen mode, clear any corner camera cutout
            // horizontally so tabs on the top edge never overlap a left/right punch-hole.
            val topBandPx = with(density) { 44.dp.roundToPx() }
            val cutoutRects = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
            for (r in cutoutRects) {
                if (r.isEmpty || r.top >= topBandPx) continue
                if (r.left < widthPx * 0.25f) left = maxOf(left, r.right)
                else if (r.right > widthPx * 0.75f) right = maxOf(right, widthPx - r.left)
            }
        }
        val startPx = if (layoutDir == LayoutDirection.Rtl) right else left
        val endPx = if (layoutDir == LayoutDirection.Rtl) left else right
        computeCaptionMetrics(capTop, statusTop, cutoutTop, startPx, endPx, density)
    }
}

@Composable
fun TitleBar(
    vm: BrowserViewModel,
    s: AppState,
    captionMetricsOverride: CaptionMetrics? = null,
    onShowMenu: (androidx.compose.ui.geometry.Offset, List<MenuEntry>) -> Unit,
) {
    val cap = captionMetricsOverride ?: rememberCaptionMetrics()
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(cap.height).background(cs.surfaceContainerHighest)
            .padding(top = cap.topInset, start = maxOf(cap.start, 8.dp), end = cap.end),
        verticalAlignment = Alignment.Bottom,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // Reserve a dedicated un-excluded drag-handle zone before the desktop window controls so opening
            // 5+ tabs never lets the tab strip consume 100% of the caption bar and starve window dragging.
            val dragReserve = if (cap.hasDesktopCaption) (maxWidth - 96.dp).coerceIn(0.dp, DESKTOP_CAPTION_DRAG_RESERVE) else 0.dp
            val stripMaxWidth = (maxWidth - dragReserve).coerceAtLeast(0.dp)
            val addBtn = 40.dp
            val n = s.tabs.size
            val tabW = ((stripMaxWidth - addBtn - 8.dp) / n).coerceIn(52.dp, 220.dp)
            // Register the interactive tab strip and "+" button as a system-gesture exclusion rect
            // so Android desktop / Googlebook WM Shell treats clicks here as app header gestures
            // rather than pilfering primary mouse-down events for caption drag/maximize, while
            // leaving empty titlebar space to the right un-excluded for window drag-to-move.
            Row(
                Modifier.widthIn(max = stripMaxWidth).systemGestureExclusion().fillMaxHeight().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.tabs.forEach { tab ->
                    androidx.compose.runtime.key(tab.id) {
                        TabItem(vm, s, tab, tabW, onShowMenu)
                    }
                }
                Box(
                    Modifier.padding(start = 4.dp, bottom = 4.dp).size(32.dp).clip(CircleShape)
                        .mouseGestures("add", role = Role.Button, onClickLabel = "New tab", onClick = { _, _, _ -> vm.newTab() }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "New tab (Ctrl+T)", Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun TabItem(
    vm: BrowserViewModel, s: AppState, tab: TabState, width: Dp,
    onShowMenu: (androidx.compose.ui.geometry.Offset, List<MenuEntry>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val selected = tab.id == s.activeId
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val dropHover = drag.hover == tab.path && drag.active
    val bg = when {
        dropHover -> cs.primaryContainer
        selected -> cs.surfaceContainerLow
        hovered -> cs.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (selected) 0.dp else 8.dp, bottomEnd = if (selected) 0.dp else 8.dp)
    var accum by remember { mutableFloatStateOf(0f) }
    val tabWidthPx = with(LocalDensity.current) { width.toPx() }
    val moveBy by rememberUpdatedState { delta: Int -> vm.moveTabBy(tab.id, delta) }
    Box(
        Modifier.width(width).fillMaxHeight().padding(end = if (selected) 0.dp else 2.dp, bottom = if (selected) 0.dp else 4.dp)
            .clip(shape).background(bg).hoverable(hover)
            .dropTarget(tab.path)
            .pointerInput(tab.id) {
                detectHorizontalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = { accum = 0f },
                    onDragCancel = { accum = 0f },
                ) { change, dx ->
                    change.consume()
                    accum += dx
                    if (accum > tabWidthPx * 0.6f) { moveBy(1); accum -= tabWidthPx }
                    else if (accum < -tabWidthPx * 0.6f) { moveBy(-1); accum += tabWidthPx }
                }
            }
            .mouseGestures(
                tab.id,
                role = Role.Tab,
                selected = selected,
                onClickLabel = "Select tab ${s.titleOf(tab.path)}",
                onClick = { _, _, _ -> vm.selectTab(tab.id) },
                onMiddle = { vm.closeTab(tab.id) },
                onSecondary = { onShowMenu(it, tabMenu(vm, tab.id)) },
                onLongPress = { onShowMenu(it, tabMenu(vm, tab.id)) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(tab.path) -> if (s.phoneConnected || FsPath.isDoc(tab.path)) Icons.Outlined.Smartphone else pinIcon(tab.path)
                tab.path == FsPath.SMB_ROOT || FsPath.isRemote(tab.path) -> Icons.Outlined.Dns
                tab.path == FsPath.TRASH_ROOT -> Icons.Outlined.Delete
                tab.path == FsPath.RECENTS_ROOT -> Icons.Outlined.History
                tab.path == FsPath.STARRED_ROOT -> Icons.Outlined.StarOutline
                FsPath.tagOf(tab.path) != null -> Icons.AutoMirrored.Outlined.Label
                FsPath.isZip(tab.path) -> Icons.Outlined.FolderZip
                else -> pinIcon(tab.path)
            }
            if (width > 72.dp) Icon(icon, null, Modifier.size(18.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
            Text(
                s.titleOf(tab.path), Modifier.weight(1f).padding(horizontal = if (width > 72.dp) 8.dp else 0.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            )
            if (selected || hovered || width > 140.dp) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).mouseGestures(
                        "close${tab.id}",
                        role = Role.Button,
                        onClickLabel = "Close tab ${s.titleOf(tab.path)}",
                        onClick = { _, _, _ -> vm.closeTab(tab.id) },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(14.dp), tint = cs.onSurfaceVariant) }
            } else Box(Modifier.size(28.dp))
        }
    }
}
