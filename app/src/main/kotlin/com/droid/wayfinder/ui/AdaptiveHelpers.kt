// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FsPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

internal const val TOOLTIP_TEST_TAG = "wayfinder_hover_tooltip"
internal const val SCROLLBAR_THUMB_TAG_PREFIX = "wayfinder_scrollbar_"
internal const val SCROLLBAR_TRACK_TAG_PREFIX = "wayfinder_scrollbar_track_"

/** Unique window identifier for distinguishing same-window drags from cross-window / external drags. */
internal val LocalWindowDragId = staticCompositionLocalOf { 0 }

/** Local state attached to [View.startDragAndDrop] so same-window drop targets continue to work seamlessly. */
internal data class WayfinderDragLocalState(
    val windowId: Int,
    val paths: List<String>,
    val initialCopyMode: Boolean = false,
)

/** Bridges Android [View.startDragAndDrop] sessions originating in the same window back into [DragController]. */
@Composable
internal fun Modifier.sameWindowDragBridge(drag: DragController, windowDragId: Int): Modifier {
    val target = remember(drag, windowDragId) {
        object : DragAndDropTarget {
            private var sessionPaths: List<String> = emptyList()
            private var lastCopyMode: Boolean = false

            override fun onStarted(event: DragAndDropEvent) {
                val de = event.toAndroidDragEvent()
                val local = de.localState as? WayfinderDragLocalState ?: return
                if (local.windowId == windowDragId) {
                    sessionPaths = local.paths
                    lastCopyMode = local.initialCopyMode
                    drag.start(local.paths, Offset(de.x, de.y), local.initialCopyMode)
                }
            }

            override fun onEntered(event: DragAndDropEvent) {
                val de = event.toAndroidDragEvent()
                if (!drag.active && sessionPaths.isNotEmpty()) {
                    drag.start(sessionPaths, Offset(de.x, de.y), lastCopyMode)
                }
            }

            override fun onMoved(event: DragAndDropEvent) {
                val de = event.toAndroidDragEvent()
                if (!drag.active && sessionPaths.isNotEmpty()) {
                    drag.start(sessionPaths, Offset(de.x, de.y), lastCopyMode)
                } else {
                    lastCopyMode = drag.copyMode
                    drag.move(Offset(de.x, de.y), drag.copyMode)
                }
            }

            override fun onExited(event: DragAndDropEvent) {
                if (drag.active) {
                    lastCopyMode = drag.copyMode
                    drag.cancel()
                }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val de = event.toAndroidDragEvent()
                if (!drag.active && sessionPaths.isNotEmpty()) {
                    drag.start(sessionPaths, Offset(de.x, de.y), lastCopyMode)
                } else {
                    drag.move(Offset(de.x, de.y), drag.copyMode)
                }
                val hadHover = drag.hover != null
                sessionPaths = emptyList()
                drag.finish()
                return hadHover
            }

            override fun onEnded(event: DragAndDropEvent) {
                sessionPaths = emptyList()
                lastCopyMode = false
                drag.cancel()
            }
        }
    }
    return this
        .onPreviewKeyEvent { e ->
            if (drag.active) {
                drag.updateCopyMode(e.isCtrlPressed || e.isMetaPressed)
            }
            false
        }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { e ->
                val local = runCatching { e.toAndroidDragEvent().localState as? WayfinderDragLocalState }.getOrNull()
                local != null && local.windowId == windowDragId
            },
            target = target,
        )
}

/**
 * Wraps an icon-only or compact control with a high-contrast desktop hover tooltip that clamps inside
 * the window edges and dismisses immediately on pointer exit, press, or unmount.
 */
@Composable
fun ActionTooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    delayMs: Long = 120L,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var pointerInside by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(pointerInside, enabled, text) {
        if (pointerInside && enabled && text.isNotBlank()) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            pointerInside = false
            visible = false
        }
    }

    val positionProvider = remember(density) { ClampedTooltipPositionProvider(density) }
    Box(
        modifier.pointerInput(text, enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> {
                            val isMouseOrStylus = event.changes.any {
                                it.type == androidx.compose.ui.input.pointer.PointerType.Mouse ||
                                    it.type == androidx.compose.ui.input.pointer.PointerType.Stylus ||
                                    PointerSource.precise
                            }
                            if (isMouseOrStylus && event.changes.none { it.pressed }) {
                                pointerInside = true
                            }
                        }
                        PointerEventType.Exit, PointerEventType.Press, PointerEventType.Release -> {
                            pointerInside = false
                        }
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (visible && text.isNotBlank()) {
            Popup(
                popupPositionProvider = positionProvider,
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = true,
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .testTag(TOOLTIP_TEST_TAG)
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(6.dp)),
                    shape = RoundedCornerShape(6.dp),
                    color = cs.inverseSurface,
                    contentColor = cs.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal class ClampedTooltipPositionProvider(private val density: Density) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val marginPx = with(density) { 8.dp.roundToPx() }
        val gapPx = with(density) { 6.dp.roundToPx() }
        val idealX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val clampedX = idealX.coerceIn(marginPx, maxX)

        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.top - popupContentSize.height - gapPx
        val clampedY = if (belowY + popupContentSize.height + marginPx <= windowSize.height || aboveY < marginPx) {
            belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        } else {
            aboveY
        }
        return IntOffset(clampedX, clampedY)
    }
}

// ------------------------------------------------------------------ Proportional Desktop Scrollbars

internal data class ScrollbarMetrics(
    val visible: Boolean,
    val thumbFraction: Float,
    val offsetFraction: Float,
)

internal fun computeLazyScrollbarMetrics(
    totalItemsCount: Int,
    viewportMainAxisPx: Int,
    firstIndex: Int,
    firstScrollOffsetPx: Int,
    visibleItemsMainAxisSumPx: Int,
    visibleItemsCount: Int,
    spanCount: Int = 1,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): ScrollbarMetrics {
    if ((!canScrollForward && !canScrollBackward) || totalItemsCount <= 0 || viewportMainAxisPx <= 0 || visibleItemsCount <= 0) {
        return ScrollbarMetrics(visible = false, thumbFraction = 1f, offsetFraction = 0f)
    }
    val effectiveSpan = spanCount.coerceAtLeast(1)
    val visibleRows = ((visibleItemsCount + effectiveSpan - 1) / effectiveSpan).coerceAtLeast(1)
    val totalRows = ((totalItemsCount + effectiveSpan - 1) / effectiveSpan).coerceAtLeast(1)
    val avgRowPx = (visibleItemsMainAxisSumPx.toFloat() / visibleItemsCount.toFloat()).coerceAtLeast(1f)
    val estimatedContentPx = (totalRows * avgRowPx).coerceAtLeast(viewportMainAxisPx.toFloat() + 1f)
    val currentRow = firstIndex / effectiveSpan
    val estimatedScrolledPx = (currentRow * avgRowPx + firstScrollOffsetPx).coerceAtLeast(0f)
    val maxScrollPx = (estimatedContentPx - viewportMainAxisPx).coerceAtLeast(1f)
    val thumbFraction = (viewportMainAxisPx / estimatedContentPx).coerceIn(0.06f, 0.92f)
    val offsetFraction = (estimatedScrolledPx / maxScrollPx).coerceIn(0f, 1f)
    return ScrollbarMetrics(visible = true, thumbFraction = thumbFraction, offsetFraction = offsetFraction)
}

@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    tag: String = "list",
) {
    val scope = rememberCoroutineScope()
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
            computeLazyScrollbarMetrics(
                totalItemsCount = info.totalItemsCount,
                viewportMainAxisPx = viewportPx,
                firstIndex = state.firstVisibleItemIndex,
                firstScrollOffsetPx = state.firstVisibleItemScrollOffset,
                visibleItemsMainAxisSumPx = visible.sumOf { it.size },
                visibleItemsCount = visible.size,
                canScrollForward = state.canScrollForward,
                canScrollBackward = state.canScrollBackward,
            )
        }
    }
    VerticalScrollbarTrack(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        modifier = modifier,
        tag = tag,
        onScrubToFraction = { targetFraction ->
            val total = state.layoutInfo.totalItemsCount
            if (total > 0) {
                val targetIndex = (targetFraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
                scope.launch { state.scrollToItem(targetIndex) }
            }
        },
    )
}

@Composable
fun VerticalScrollbar(
    state: LazyGridState,
    spanCount: Int,
    modifier: Modifier = Modifier,
    tag: String = "grid",
) {
    val scope = rememberCoroutineScope()
    val currentSpan by rememberUpdatedState(spanCount.coerceAtLeast(1))
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
            computeLazyScrollbarMetrics(
                totalItemsCount = info.totalItemsCount,
                viewportMainAxisPx = viewportPx,
                firstIndex = state.firstVisibleItemIndex,
                firstScrollOffsetPx = state.firstVisibleItemScrollOffset,
                visibleItemsMainAxisSumPx = visible.sumOf { it.size.height },
                visibleItemsCount = visible.size,
                spanCount = currentSpan,
                canScrollForward = state.canScrollForward,
                canScrollBackward = state.canScrollBackward,
            )
        }
    }
    VerticalScrollbarTrack(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        modifier = modifier,
        tag = tag,
        onScrubToFraction = { targetFraction ->
            val total = state.layoutInfo.totalItemsCount
            if (total > 0) {
                val targetIndex = (targetFraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
                scope.launch { state.scrollToItem(targetIndex) }
            }
        },
    )
}

@Composable
fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    tag: String = "scroll",
) {
    val scope = rememberCoroutineScope()
    val metrics by remember(state) {
        derivedStateOf {
            val max = state.maxValue
            val vp = state.viewportSize
            if (max <= 0 || vp <= 0) {
                ScrollbarMetrics(visible = false, thumbFraction = 1f, offsetFraction = 0f)
            } else {
                val total = (vp + max).toFloat()
                ScrollbarMetrics(
                    visible = true,
                    thumbFraction = (vp / total).coerceIn(0.08f, 0.92f),
                    offsetFraction = (state.value.toFloat() / max.toFloat()).coerceIn(0f, 1f),
                )
            }
        }
    }
    VerticalScrollbarTrack(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        modifier = modifier,
        tag = tag,
        onScrubToFraction = { targetFraction ->
            val targetPx = (targetFraction * state.maxValue).roundToInt().coerceIn(0, state.maxValue)
            scope.launch { state.scrollTo(targetPx) }
        },
    )
}

@Composable
fun HorizontalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    tag: String = "hscroll",
) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentIsRtl by rememberUpdatedState(isRtl)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val metrics by remember(state) {
        derivedStateOf {
            val max = state.maxValue
            val vp = state.viewportSize
            if (max <= 0 || vp <= 0) {
                ScrollbarMetrics(visible = false, thumbFraction = 1f, offsetFraction = 0f)
            } else {
                val total = (vp + max).toFloat()
                ScrollbarMetrics(
                    visible = true,
                    thumbFraction = (vp / total).coerceIn(0.08f, 0.92f),
                    offsetFraction = (state.value.toFloat() / max.toFloat()).coerceIn(0f, 1f),
                )
            }
        }
    }

    LaunchedEffect(state.isScrollInProgress, state.value) {
        if (state.isScrollInProgress) {
            recentlyScrolled = true
        } else if (recentlyScrolled) {
            delay(1_200L)
            recentlyScrolled = false
        }
    }

    if (!metrics.visible) return
    val active = hovered || dragging || state.isScrollInProgress || recentlyScrolled
    val alpha by animateFloatAsState(if (active) 0.90f else 0.45f, animationSpec = tween(160), label = "hScrollbarAlpha")
    val minThumbPx = with(density) { 32.dp.roundToPx() }
    val thumbWidthPx = if (trackWidthPx > 0) {
        (trackWidthPx * metrics.thumbFraction).roundToInt().coerceIn(minThumbPx.coerceAtMost(trackWidthPx), trackWidthPx)
    } else {
        minThumbPx
    }
    val travelPx = (trackWidthPx - thumbWidthPx).coerceAtLeast(0)
    // Note: Modifier.offset { IntOffset(x, y) } is already RTL-aware (shifts Start -> End in both LTR and RTL),
    // whereas pointer drag deltas in detectHorizontalDragGestures are physical screen pixels (+x = rightward).
    val thumbOffsetX = (travelPx * metrics.offsetFraction).roundToInt()
    val currentOffsetFraction by rememberUpdatedState(metrics.offsetFraction)

    Box(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onSizeChanged { trackWidthPx = it.width }
            .hoverable(hover)
            .pointerInput(tag, "tap") {
                detectTapGestures { offset ->
                    val denom = (trackWidthPx - thumbWidthPx).coerceAtLeast(1)
                    val physX = if (currentIsRtl) trackWidthPx - offset.x else offset.x
                    val frac = ((physX - thumbWidthPx / 2f) / denom).coerceIn(0f, 1f)
                    val targetPx = (frac * state.maxValue).roundToInt().coerceIn(0, state.maxValue)
                    scope.launch { state.scrollTo(targetPx) }
                }
            }
            .pointerInput(tag) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = currentOffsetFraction
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    val denom = (trackWidthPx - thumbWidthPx).coerceAtLeast(1)
                    val signedDrag = if (currentIsRtl) -dragAmount else dragAmount
                    dragFraction = (dragFraction + signedDrag / denom).coerceIn(0f, 1f)
                    val targetPx = (dragFraction * state.maxValue).roundToInt().coerceIn(0, state.maxValue)
                    scope.launch { state.scrollTo(targetPx) }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffsetX, 0) }
                .width(with(density) { thumbWidthPx.toDp() })
                .height(if (hovered || dragging) 8.dp else 6.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered || dragging) cs.primary else cs.onSurfaceVariant)
                .testTag(SCROLLBAR_THUMB_TAG_PREFIX + tag),
        )
    }
}

@Composable
private fun VerticalScrollbarTrack(
    metrics: ScrollbarMetrics,
    isScrollInProgress: Boolean,
    modifier: Modifier,
    tag: String,
    onScrubToFraction: (Float) -> Unit,
) {
    if (!metrics.visible) return
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrollInProgress, metrics.offsetFraction) {
        if (isScrollInProgress) {
            recentlyScrolled = true
        } else if (recentlyScrolled) {
            delay(1_200L)
            recentlyScrolled = false
        }
    }

    val active = hovered || dragging || isScrollInProgress || recentlyScrolled
    val alpha by animateFloatAsState(if (active) 0.90f else 0.45f, animationSpec = tween(160), label = "vScrollbarAlpha")
    val minThumbPx = with(density) { 32.dp.roundToPx() }
    val thumbHeightPx = if (trackHeightPx > 0) {
        (trackHeightPx * metrics.thumbFraction).roundToInt().coerceIn(minThumbPx.coerceAtMost(trackHeightPx), trackHeightPx)
    } else {
        minThumbPx
    }
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0)
    val thumbOffsetY = (travelPx * metrics.offsetFraction).roundToInt()
    val currentOffsetFraction by rememberUpdatedState(metrics.offsetFraction)
    val onScrub by rememberUpdatedState(onScrubToFraction)

    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .testTag(SCROLLBAR_TRACK_TAG_PREFIX + tag)
            .hoverable(hover)
            .pointerInput(tag, "tap") {
                detectTapGestures { offset ->
                    val denom = (trackHeightPx - thumbHeightPx).coerceAtLeast(1)
                    val frac = ((offset.y - thumbHeightPx / 2f) / denom).coerceIn(0f, 1f)
                    onScrub(frac)
                }
            }
            .pointerInput(tag) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = currentOffsetFraction
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    val denom = (trackHeightPx - thumbHeightPx).coerceAtLeast(1)
                    dragFraction = (dragFraction + dragAmount / denom).coerceIn(0f, 1f)
                    onScrub(dragFraction)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, thumbOffsetY) }
                .width(if (hovered || dragging) 8.dp else 6.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .alpha(alpha)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered || dragging) cs.primary else cs.onSurfaceVariant)
                .testTag(SCROLLBAR_THUMB_TAG_PREFIX + tag),
        )
    }
}

// ------------------------------------------------------------------ Cross-App Outbound Drag & Drop

private const val MAX_OUTBOUND_DRAG_URIS = 128

/**
 * Builds outbound [ClipData] for single or multi-file cross-app drag-and-drop.
 * Returns `(clipData, hasGrantableContentUris)`.
 *
 * When one or more grantable `content://` URIs exist, non-URI items (such as directories or remote virtual paths)
 * are excluded from the cross-app [ClipData] so external drop targets never encounter null-URI items or
 * `text/plain` MIME degradation, while [WayfinderDragLocalState] retains the full in-window [paths] list.
 */
internal fun buildOutboundDragClipData(ctx: Context, paths: List<String>): Pair<ClipData, Boolean>? {
    if (paths.isEmpty()) return null
    val uriItems = ArrayList<Pair<ClipData.Item, String>>(paths.size.coerceAtMost(MAX_OUTBOUND_DRAG_URIS))
    val persistedReadPrefixes by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            ctx.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { perm -> perm.uri to (perm.uri.toString().trimEnd('/') + "/") }
        }.getOrDefault(emptyList())
    }

    for (path in paths) {
        if (uriItems.size >= MAX_OUTBOUND_DRAG_URIS) break
        val uriAndMime = resolveDragUriAndMime(ctx, path) { docUri ->
            hasSnapshotReadUriGrant(ctx, docUri, persistedReadPrefixes)
        }
        if (uriAndMime != null) {
            uriItems += ClipData.Item(uriAndMime.first) to uriAndMime.second
        }
    }

    val label = if (paths.size == 1) FsPath.name(paths[0]).ifEmpty { "File" } else "${paths.size} items"
    if (uriItems.isEmpty()) {
        return ClipData.newPlainText(label, label) to false
    }

    val mimeTypes = uriItems.map { it.second }.distinct().toTypedArray()
    val clip = ClipData(ClipDescription(label, mimeTypes), uriItems[0].first)
    for (i in 1 until uriItems.size) {
        clip.addItem(uriItems[i].first)
    }
    return clip to true
}

private fun hasSnapshotReadUriGrant(
    ctx: Context,
    uri: Uri,
    persistedReadPrefixes: List<Pair<Uri, String>>,
): Boolean {
    val uriStr = uri.toString()
    if (persistedReadPrefixes.any { (permUri, prefix) -> uri == permUri || uriStr.startsWith(prefix) }) {
        return true
    }
    if (uri.authority == com.droid.wayfinder.data.SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY &&
        !DocumentsContract.isTreeUri(uri)
    ) {
        return false
    }
    return ctx.checkUriPermission(
        uri,
        Process.myPid(),
        Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
}

private inline fun resolveDragUriAndMime(
    ctx: Context,
    path: String,
    hasReadGrant: (Uri) -> Boolean,
): Pair<Uri, String>? {
    if (FsPath.isSaf(path) || FsPath.isDoc(path)) {
        val docUri = com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(path, tree = false)
            ?.takeIf(hasReadGrant)
            ?: return null
        val ext = FsPath.name(path).substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        return docUri to mime
    }
    if (!FsPath.isLocalFile(path) || FileInfo.isSymlink(path)) return null
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (!file.isFile) return null
    val uri = runCatching {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }.getOrNull() ?: return null
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    return uri to mime
}

/** Custom [View.DragShadowBuilder] rendering a crisp pill card plus a count badge when dragging multiple items. */
internal class WayfinderDragShadowBuilder(
    context: Context,
    private val paths: List<String>,
) : View.DragShadowBuilder() {
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val title: String = when {
        paths.isEmpty() -> "File"
        paths.size == 1 -> FsPath.name(paths[0]).ifEmpty { paths[0] }.let { if (it.length > 28) it.take(25) + "…" else it }
        else -> "${FsPath.name(paths[0]).take(20)} + ${paths.size - 1} more"
    }
    private val countBadge: String? = paths.size.takeIf { it > 1 }?.toString()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F2F7.toInt()
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE622252A.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A73E8.toInt()
        style = Paint.Style.FILL
    }

    val shadowWidthPx: Int
    val shadowHeightPx: Int

    init {
        val textW = textPaint.measureText(title)
        val badgeExtra = if (countBadge != null) 28f * density else 0f
        shadowWidthPx = (textW + 28f * density + badgeExtra).roundToInt().coerceIn((96f * density).roundToInt(), (320f * density).roundToInt())
        shadowHeightPx = (40f * density).roundToInt()
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(shadowWidthPx, shadowHeightPx)
        outShadowTouchPoint.set((20f * density).roundToInt().coerceAtMost(shadowWidthPx / 2), shadowHeightPx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val pad = 2f * density
        val radius = 18f * density
        val rect = RectF(pad, pad, shadowWidthPx - pad, shadowHeightPx - pad)
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val baseline = shadowHeightPx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(title, 14f * density, baseline, textPaint)

        if (countBadge != null) {
            val badgeR = 10f * density
            val cx = shadowWidthPx - pad - 14f * density
            val cy = shadowHeightPx / 2f
            canvas.drawCircle(cx, cy, badgeR, badgePaint)
            val badgeBaseline = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(countBadge, cx, badgeBaseline, badgeTextPaint)
        }
    }
}

/**
 * Starts a system-level [View.startDragAndDrop] session carrying `content://` URIs for cross-app drops
 * and [WayfinderDragLocalState] for same-window drops. Returns `true` if Android accepted the drag.
 */
internal fun startSystemFileDrag(
    view: View,
    windowId: Int,
    paths: List<String>,
    initialCopyMode: Boolean = false,
): Boolean {
    val built = buildOutboundDragClipData(view.context, paths) ?: return false
    val (clip, hasContentUris) = built
    val shadow = WayfinderDragShadowBuilder(view.context, paths)
    val flags = if (hasContentUris) {
        View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
    } else {
        0
    }
    return runCatching {
        view.startDragAndDrop(
            clip,
            shadow,
            WayfinderDragLocalState(windowId = windowId, paths = paths, initialCopyMode = initialCopyMode),
            flags,
        )
    }.getOrDefault(false)
}

/** Convenience wrapper around [VerticalScrollbar] for [LazyListState] containers. */
@Composable
fun LazyListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    VerticalScrollbar(state = state, modifier = modifier, tag = "list")
}

/** Convenience wrapper around [VerticalScrollbar] for [LazyGridState] containers. */
@Composable
fun LazyGridScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val span = remember(state) {
        derivedStateOf {
            val vis = state.layoutInfo.visibleItemsInfo
            if (vis.isEmpty()) 1 else vis.count { it.row == vis.first().row }.coerceAtLeast(1)
        }
    }
    VerticalScrollbar(state = state, spanCount = span.value, modifier = modifier, tag = "grid")
}

/** Convenience wrapper around [VerticalScrollbar] for [ScrollState] containers. */
@Composable
fun ColumnScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    VerticalScrollbar(state = state, modifier = modifier, tag = "scroll")
}

/** Builds a cross-app [ClipData] with grantable `content://` URIs, or `null` when no path is externally shareable. */
fun buildCrossAppClipData(context: Context, paths: List<String>): ClipData? =
    buildOutboundDragClipData(context, paths)?.takeIf { it.second }?.first
