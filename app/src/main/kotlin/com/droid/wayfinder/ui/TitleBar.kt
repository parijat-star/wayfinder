// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import com.droid.wayfinder.R
import com.droid.wayfinder.data.DisplayTarget
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.TabDragSession
import com.droid.wayfinder.data.TabStripDragMath
import com.droid.wayfinder.data.TabTransferRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val minCaptionH = (48f * density.fontScale.coerceIn(1f, 1.35f)).dp
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
 * Resolves physical `(leftPx, rightPx)` horizontal clearance for desktop caption window controls (`- □ ×`)
 * across live freeform window resizing even when [rects] lag [widthPx] by a frame:
 *  - Only rects anchored within [edgeTolerancePx] of `x = 0` are treated as left-edge caption controls, so
 *    expanding from a narrow window (`Rect(680, 0, 1000, 65)`) to a wide window (`2880px`) never misclassifies
 *    a stale right-hand control rect as a left inset that pushes tabs toward the center.
 *  - Right-side control width uses `refRight = widthPx` when `widthPx` matches `maxRectRight` within
 *    [edgeTolerancePx], and falls back to `maxRectRight` when `widthPx` has resized ahead of [rects].
 */
internal fun resolveDesktopCaptionHorizontalInsets(
    rects: List<AndroidRect>,
    widthPx: Int,
    edgeTolerancePx: Int,
): Pair<Int, Int>? {
    val valid = rects.filter { !it.isEmpty && (widthPx <= 0 || it.width() <= widthPx * 0.45f) }
    if (valid.isEmpty()) return null

    var left = 0
    val rightRects = ArrayList<AndroidRect>(valid.size)
    for (r in valid) {
        if (r.left <= edgeTolerancePx) {
            left = maxOf(left, r.right)
        } else {
            rightRects += r
        }
    }

    var right = 0
    if (rightRects.isNotEmpty()) {
        val maxRectRight = rightRects.maxOf { it.right }
        val refRight = if (widthPx in maxRectRight..(maxRectRight + edgeTolerancePx)) {
            widthPx
        } else {
            maxRectRight
        }
        for (r in rightRects) {
            right = maxOf(right, (refRight - r.left).coerceAtLeast(0))
        }
    }

    if (widthPx > 0) {
        val maxCombined = (widthPx - edgeTolerancePx * 2).coerceAtLeast(0)
        if (left + right > maxCombined) {
            right = right.coerceAtMost(maxCombined)
            left = left.coerceAtMost((maxCombined - right).coerceAtLeast(0))
        }
    }
    return left to right
}

internal fun resolveCaptionHorizontalInsets(
    rects: List<AndroidRect>,
    widthPx: Int,
    isRtl: Boolean,
    density: Density,
): Pair<Int, Int> {
    val edgeTolerancePx = with(density) { 24.dp.roundToPx() }
    val maxAppIconReserve = with(density) { 140.dp.roundToPx() }
    val minControlsReserve = with(density) { 144.dp.roundToPx() }
    var (left, right) = resolveDesktopCaptionHorizontalInsets(rects, widthPx, edgeTolerancePx) ?: (0 to 0)
    if (widthPx > 0) {
        val maxAppIcon = minOf(maxAppIconReserve, (widthPx * 0.35f).toInt())
        val minControls = minOf(minControlsReserve, (widthPx * 0.45f).toInt())
        if (isRtl) {
            left = maxOf(left, minControls)
            right = right.coerceAtMost(maxAppIcon)
        } else {
            right = maxOf(right, minControls)
            left = left.coerceAtMost(maxAppIcon)
        }
    } else {
        if (isRtl) left = maxOf(left, minControlsReserve) else right = maxOf(right, minControlsReserve)
    }
    return (if (isRtl) right else left) to (if (isRtl) left else right)
}

private fun captionInsetsSnapshotHash(view: View): Int {
    var hash = view.width
    val rootInsets = view.rootWindowInsets ?: return hash
    if (Build.VERSION.SDK_INT >= 35) {
        val rects = rootInsets.getBoundingRects(android.view.WindowInsets.Type.captionBar())
        for (i in 0 until rects.size) {
            val r = rects[i]
            hash = 31 * hash + r.left
            hash = 31 * hash + r.right
            hash = 31 * hash + r.bottom
        }
    }
    val cutouts = rootInsets.displayCutout?.boundingRects
    if (cutouts != null) {
        for (i in 0 until cutouts.size) {
            val r = cutouts[i]
            hash = 31 * hash + r.left
            hash = 31 * hash + r.right
            hash = 31 * hash + r.top
        }
    }
    return hash
}

/**
 * Measures the system caption (title) bar so that our tab strip can live inside it, leaving room for
 * the system window controls. Subscribes to live layout and pre-draw inset changes on Android 15+ so
 * horizontal freeform window resizes immediately update caption bounding rects even when `capTop` is constant.
 */
@Composable
fun rememberCaptionMetrics(): CaptionMetrics {
    val density = LocalDensity.current
    val view = LocalView.current
    val layoutDir = LocalLayoutDirection.current
    val capTop = WindowInsets.captionBar.getTop(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    var insetsSnapshot by remember(view) { mutableIntStateOf(captionInsetsSnapshotHash(view)) }

    DisposableEffect(view) {
        var lastWidth = -1
        var lastInsets: android.view.WindowInsets? = null
        fun syncSnapshot() {
            val curWidth = view.width
            val curInsets = view.rootWindowInsets
            if (curWidth == lastWidth && curInsets === lastInsets) return
            lastWidth = curWidth
            lastInsets = curInsets
            val cur = captionInsetsSnapshotHash(view)
            if (cur != insetsSnapshot) {
                insetsSnapshot = cur
            }
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncSnapshot() }
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            syncSnapshot()
            true
        }
        view.addOnLayoutChangeListener(layoutListener)
        val vto = view.viewTreeObserver
        if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
        onDispose {
            view.removeOnLayoutChangeListener(layoutListener)
            if (vto.isAlive) vto.removeOnPreDrawListener(preDrawListener)
            val currentVto = view.viewTreeObserver
            if (currentVto !== vto && currentVto.isAlive) currentVto.removeOnPreDrawListener(preDrawListener)
        }
    }

    val widthPx = if (view.width > 0) view.width else containerWidthPx
    return remember(capTop, statusTop, cutoutTop, widthPx, layoutDir, insetsSnapshot, density) {
        val edgeTolerancePx = with(density) { 24.dp.roundToPx() }
        val isRtl = layoutDir == LayoutDirection.Rtl
        val (startPx, endPx) = if (capTop > 0) {
            val rects = if (Build.VERSION.SDK_INT >= 35) {
                view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()
            } else {
                emptyList()
            }
            resolveCaptionHorizontalInsets(rects, widthPx, isRtl, density)
        } else if (statusTop == 0 && widthPx > 0) {
            // When the system status bar is hidden in fullscreen mode, clear any corner camera cutout
            // horizontally so tabs on the top edge never overlap a left/right punch-hole.
            val topBandPx = with(density) { 48.dp.roundToPx() }
            val cutoutRects = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
                .filter { !it.isEmpty && it.top < topBandPx }
            val (left, right) = resolveDesktopCaptionHorizontalInsets(cutoutRects, widthPx, edgeTolerancePx) ?: (0 to 0)
            (if (isRtl) right else left) to (if (isRtl) left else right)
        } else {
            0 to 0
        }
        computeCaptionMetrics(capTop, statusTop, cutoutTop, startPx, endPx, density)
    }
}

@Composable
fun TitleBar(
    vm: BrowserViewModel,
    s: AppState,
    captionMetricsOverride: CaptionMetrics? = null,
    windowId: Int = LocalWindowDragId.current,
    onShowMenu: (Offset, List<MenuEntry>) -> Unit,
) {
    val cap = captionMetricsOverride ?: rememberCaptionMetrics()
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val (currentDisplayId, connectedDisplays) = rememberConnectedDisplays(view)
    // Observe TabTransferRegistry changes so cross-window / mergeable window counts stay live.
    val registryRev by TabTransferRegistry.revision.collectAsState()
    val otherWindowCount = remember(windowId, registryRev, s.picker) {
        if (s.picker != null) 0 else TabTransferRegistry.otherMergeableWindows(windowId).size
    }

    val visibleTabs = s.visibleTabs
    val stripController = LocalTabStripDropController.current
    val mergePreview = stripController.mergePreview
    var activeDragTabId by remember { mutableStateOf<Long?>(null) }
    var activeClampedLeftPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(s.tabs, s.detachedTabId) {
        if (activeDragTabId != null && (s.detachedTabId == activeDragTabId || s.tabs.none { it.id == activeDragTabId })) {
            activeDragTabId = null
        }
    }

    var stripViewportOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var stripViewportWidthPx by remember { mutableFloatStateOf(0f) }
    var stripViewportHeightPx by remember { mutableFloatStateOf(0f) }
    var stripContentOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var latestTabWidthPx by remember { mutableFloatStateOf(160f) }

    val currentVm by rememberUpdatedState(vm)
    val isPickerWindow = s.picker != null

    SideEffect {
        stripController.visibleTabCount = visibleTabs.size
        stripController.isRtl = isRtl
        stripController.tabWidthPx = latestTabWidthPx
        stripController.stripContentOriginInRoot = stripContentOriginInRoot
    }

    val onMergeDropRootX: (Float, TabDragSession) -> Boolean = { rootX, session ->
        val slot = stripController.computeInsertionIndex(rootX)
        stripController.clearPreview()
        if (session.sourceWindowId == windowId) {
            TabTransferRegistry.reattachInSourceWindow(session.sessionId) {
                currentVm.moveTabToInsertionSlot(session.sourceTabId, slot)
            }
        } else {
            TabTransferRegistry.completeMergeIntoWindow(
                rawSessionId = session.sessionId,
                targetWindowId = windowId,
                insertIndex = slot,
            )
        }
    }

    val busTarget = remember(windowId, stripController) {
        InProcessTabDragBus.StripTarget(
            windowId = windowId,
            stripScreenBounds = Rect.Zero,
            windowScreenBounds = Rect.Zero,
            onHoverScreen = { screenPos, session ->
                val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                stripController.updatePreview(screenPos.x - loc[0], session)
            },
            onExit = { stripController.clearPreview() },
            onDropScreen = { screenPos, session ->
                val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                onMergeDropRootX(screenPos.x - loc[0], session)
            },
        )
    }
    SideEffect {
        busTarget.windowScreenBounds = stripController.windowScreenBounds
    }
    DisposableEffect(windowId, isPickerWindow, busTarget) {
        if (!isPickerWindow) {
            InProcessTabDragBus.register(busTarget)
        } else {
            InProcessTabDragBus.unregister(windowId)
        }
        onDispose {
            stripController.clearPreview()
            InProcessTabDragBus.unregister(windowId)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(cap.height)
            .background(cs.surfaceContainerHighest)
            .onGloballyPositioned { coords ->
                val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                val rootBounds = coords.boundsInRoot()
                stripController.stripBottomInRoot = rootBounds.bottom
                val padPx = with(density) { 12.dp.toPx() }
                busTarget.stripScreenBounds = Rect(
                    left = loc[0] + rootBounds.left,
                    top = loc[1] + rootBounds.top - padPx,
                    right = loc[0] + rootBounds.right,
                    bottom = loc[1] + rootBounds.bottom + padPx,
                )
                busTarget.windowScreenBounds = stripController.windowScreenBounds
            }
            .padding(top = cap.topInset, start = maxOf(cap.start, 8.dp), end = cap.end),
        verticalAlignment = Alignment.Bottom,
    ) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned { coords ->
                    stripViewportOriginInRoot = coords.positionInRoot()
                    stripViewportWidthPx = coords.size.width.toFloat()
                    stripViewportHeightPx = coords.size.height.toFloat()
                },
        ) {
            // Reserve a dedicated un-excluded drag-handle zone before the desktop window controls so opening
            // 5+ tabs never lets the tab strip consume 100% of the caption bar and starve window dragging.
            val dragReserve = if (cap.hasDesktopCaption) (maxWidth - 96.dp).coerceIn(0.dp, DESKTOP_CAPTION_DRAG_RESERVE) else 0.dp
            val stripMaxWidth = (maxWidth - dragReserve).coerceAtLeast(0.dp)
            val addBtn = 48.dp
            val preview = mergePreview
            val slotCount = (visibleTabs.size + (if (preview != null) 1 else 0)).coerceAtLeast(1)
            val tabW = ((stripMaxWidth - addBtn - 4.dp) / slotCount).coerceIn(76.dp, 220.dp)
            latestTabWidthPx = with(density) { tabW.toPx() }

            // Register the interactive tab strip and "+" button as a system-gesture exclusion rect
            // so Android desktop / Googlebook WM Shell treats clicks here as app header gestures
            // rather than pilfering primary mouse-down events for caption drag/maximize, while
            // leaving empty titlebar space to the right un-excluded for window drag-to-move.
            val focusCoord = LocalFocusCoordinator.current
            val addTabRequester = remember { FocusRequester() }
            DisposableEffect(addTabRequester) {
                focusCoord.activeTitleBarLastRequester = addTabRequester
                onDispose {
                    if (focusCoord.activeTitleBarLastRequester === addTabRequester) {
                        focusCoord.activeTitleBarLastRequester = null
                    }
                }
            }
            val scrollState = rememberScrollState()
            Row(
                Modifier
                    .widthIn(max = stripMaxWidth)
                    .systemGestureExclusion()
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
                    .onGloballyPositioned { coords ->
                        stripContentOriginInRoot = coords.positionInRoot()
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                var visIdx = 0
                s.tabs.forEach { tab ->
                    val isDetached = tab.id == s.detachedTabId
                    if (!isDetached && preview != null && preview.insertionIndex == visIdx) {
                        androidx.compose.runtime.key("merge_preview_${preview.sessionId}") {
                            MergePreviewTab(s = s, preview = preview, width = tabW)
                        }
                    }
                    val myVisIdx = visIdx
                    val renderedVisualIndex = if (!isDetached && preview != null && myVisIdx >= preview.insertionIndex) {
                        myVisIdx + 1
                    } else {
                        myVisIdx
                    }
                    androidx.compose.runtime.key(tab.id) {
                        TabItem(
                            vm = vm,
                            s = s,
                            tab = tab,
                            width = if (isDetached) 0.dp else tabW,
                            isDetached = isDetached,
                            visualIndex = renderedVisualIndex,
                            logicalIndex = myVisIdx,
                            visibleTabCount = visibleTabs.size,
                            isFirstTab = !isDetached && myVisIdx == 0,
                            isBeingDraggedInStrip = !isDetached && activeDragTabId == tab.id,
                            activeClampedLeftPx = activeClampedLeftPx,
                            windowId = windowId,
                            currentDisplayId = currentDisplayId,
                            connectedDisplays = connectedDisplays,
                            otherWindowCount = otherWindowCount,
                            scrollState = scrollState,
                            stripViewportOriginInRoot = { stripViewportOriginInRoot },
                            stripViewportWidthPx = { stripViewportWidthPx },
                            stripViewportHeightPx = { stripViewportHeightPx },
                            stripContentOriginInRoot = { stripContentOriginInRoot },
                            onInStripDragUpdate = { draggedId, clampedLeft ->
                                activeDragTabId = draggedId
                                if (draggedId != null) activeClampedLeftPx = clampedLeft
                            },
                            onShowMenu = onShowMenu,
                        )
                    }
                    if (!isDetached) {
                        visIdx++
                    }
                }
                if (preview != null && preview.insertionIndex >= visibleTabs.size) {
                    androidx.compose.runtime.key("merge_preview_${preview.sessionId}") {
                        MergePreviewTab(s = s, preview = preview, width = tabW)
                    }
                }
                val addHover = remember { MutableInteractionSource() }
                val addHovered by addHover.collectIsHoveredAsState()
                val newTabDesc = stringResource(R.string.palette_new_tab)
                val newTabTooltip = stringResource(R.string.tooltip_new_tab)
                ActionTooltip(newTabTooltip) {
                    Box(
                        Modifier.size(48.dp)
                            .keyboardFocusableItem(
                                shape = CircleShape,
                                focusRequester = addTabRequester,
                                onActivate = { _, _ -> vm.newTab() },
                            )
                            .clip(CircleShape)
                            .hoverable(addHover)
                            .mouseGestures(
                                "add",
                                role = Role.Button,
                                contentDescription = newTabDesc,
                                onClickLabel = newTabDesc,
                                onClick = { _, _, _ -> vm.newTab() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(if (addHovered) cs.onSurface.copy(alpha = 0.12f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergePreviewTab(
    s: AppState,
    preview: TabMergePreview,
    width: Dp,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    val showIcon = width > 72.dp
    Box(
        Modifier
            .width(width)
            .heightIn(min = 48.dp)
            .fillMaxHeight()
            .testTag(MERGE_PREVIEW_TAB_TAG),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 6.dp)
                .clip(shape)
                .background(cs.primaryContainer.copy(alpha = 0.55f))
                .border(1.5.dp, cs.primary.copy(alpha = 0.75f), shape),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = if (width >= 96.dp) 12.dp else 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                TabPathIcon(path = preview.path, s = s, selected = true)
            }
            Text(
                preview.title,
                Modifier.weight(1f).padding(start = if (showIcon) 8.dp else 0.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = cs.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun TabPathIcon(path: String, s: AppState, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (FsPath.isOneDrive(path)) {
        OneDriveIcon(Modifier.size(18.dp))
    } else {
        val icon = when {
            com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(path) ->
                if (s.phoneConnected || FsPath.isDoc(path)) Icons.Outlined.Smartphone else pinIcon(path)
            path == FsPath.SMB_ROOT || FsPath.isRemote(path) -> Icons.Outlined.Dns
            path == FsPath.TRASH_ROOT -> Icons.Outlined.Delete
            path == FsPath.RECENTS_ROOT -> Icons.Outlined.History
            path == FsPath.STARRED_ROOT -> Icons.Outlined.StarOutline
            FsPath.tagOf(path) != null -> Icons.AutoMirrored.Outlined.Label
            FsPath.isZip(path) -> Icons.Outlined.FolderZip
            else -> pinIcon(path)
        }
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
    }
}

@Composable
private fun TabItem(
    vm: BrowserViewModel,
    s: AppState,
    tab: TabState,
    width: Dp,
    isDetached: Boolean = false,
    visualIndex: Int,
    logicalIndex: Int,
    visibleTabCount: Int,
    isFirstTab: Boolean = false,
    isBeingDraggedInStrip: Boolean = false,
    activeClampedLeftPx: Float = 0f,
    windowId: Int,
    currentDisplayId: Int,
    connectedDisplays: List<DisplayTarget>,
    otherWindowCount: Int,
    scrollState: androidx.compose.foundation.ScrollState,
    stripViewportOriginInRoot: () -> Offset,
    stripViewportWidthPx: () -> Float,
    stripViewportHeightPx: () -> Float,
    stripContentOriginInRoot: () -> Offset,
    onInStripDragUpdate: (Long?, Float) -> Unit,
    onShowMenu: (Offset, List<MenuEntry>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val vc = LocalViewConfiguration.current
    val menu = LocalMenu.current
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val focusCoord = LocalFocusCoordinator.current
    val tabRequester = remember { FocusRequester() }
    DisposableEffect(isFirstTab, tabRequester) {
        if (isFirstTab) focusCoord.activeTitleBarRequester = tabRequester
        onDispose {
            if (focusCoord.activeTitleBarRequester === tabRequester) {
                focusCoord.activeTitleBarRequester = null
            }
        }
    }

    val selected = tab.id == s.activeId
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val dropHover = drag.hover == tab.path && drag.active
    val bg = when {
        dropHover -> cs.primaryContainer
        selected || isBeingDraggedInStrip -> cs.surfaceContainerLow
        hovered -> cs.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (selected || isBeingDraggedInStrip) 0.dp else 8.dp,
        bottomEnd = if (selected || isBeingDraggedInStrip) 0.dp else 8.dp,
    )
    val tabWidthPx = with(density) { width.toPx() }
    val signedTabWidthPx = if (isRtl) -tabWidthPx else tabWidthPx

    // Sub-frame-locked sibling slide animation: when another tab swaps across us, immediately compensate
    // in the same composition pass so there is zero 1-frame snap, then spring smoothly to 0f.
    val slideTracker = remember(tab.id) {
        object {
            var lastIdx: Int = visualIndex
            var pendingDeltaPx: Float = 0f
        }
    }
    val slideAnim = remember(tab.id) { Animatable(0f) }
    if (slideTracker.lastIdx != visualIndex) {
        if (!isBeingDraggedInStrip && !isDetached && tabWidthPx > 0f) {
            slideTracker.pendingDeltaPx += (slideTracker.lastIdx - visualIndex) * signedTabWidthPx
        } else {
            slideTracker.pendingDeltaPx = 0f
        }
        slideTracker.lastIdx = visualIndex
    }
    LaunchedEffect(visualIndex, isBeingDraggedInStrip, isDetached) {
        val delta = slideTracker.pendingDeltaPx
        if (isBeingDraggedInStrip || isDetached) {
            slideTracker.pendingDeltaPx = 0f
            slideAnim.snapTo(0f)
        } else if (delta != 0f) {
            val start = (slideAnim.value + delta).coerceIn(-tabWidthPx * 4f, tabWidthPx * 4f)
            slideTracker.pendingDeltaPx = 0f
            slideAnim.snapTo(start)
            slideAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    val currentVm by rememberUpdatedState(vm)
    val currentAppState by rememberUpdatedState(s)
    val currentTabWidthPx by rememberUpdatedState(tabWidthPx)
    val currentLogicalIndex by rememberUpdatedState(logicalIndex)
    val currentVisibleTabCount by rememberUpdatedState(visibleTabCount)
    val currentIsRtl by rememberUpdatedState(isRtl)
    val currentWindowId by rememberUpdatedState(windowId)
    val currentDisplay by rememberUpdatedState(currentDisplayId)
    val currentDisplays by rememberUpdatedState(connectedDisplays)
    val currentOtherWindowCount by rememberUpdatedState(otherWindowCount)
    val currentDragCallback by rememberUpdatedState(onInStripDragUpdate)

    var itemCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var itemOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var closeBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    val showClose = !isDetached && (selected || width >= 116.dp)
    val showIcon = !isDetached && (width > if (showClose) 104.dp else 72.dp)
    val tabTitle = s.titleOf(tab.path)
    val selectTabLabel = stringResource(R.string.action_select_tab_named, tabTitle)
    val closeTabTooltip = stringResource(R.string.tooltip_close_tab)
    val closeTabDesc = stringResource(R.string.action_close_tab)
    val closeTabNamedLabel = stringResource(R.string.action_close_tab_named, tabTitle)

    val buildMenu: () -> List<MenuEntry> = {
        tabMenu(
            vm = currentVm,
            tabId = tab.id,
            windowId = currentWindowId,
            currentDisplayId = currentDisplay,
            connectedDisplays = currentDisplays,
        )
    }

    val interactiveTail = if (isDetached) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier
            .keyboardFocusableItem(
                shape = shape,
                focusRequester = if (isFirstTab) tabRequester else null,
                onKeyDown = { e ->
                    val ctrl = e.isCtrlPressed || e.isMetaPressed
                    if (ctrl && e.isShiftPressed && !e.isAltPressed) {
                        val dirDelta = when (e.key) {
                            Key.DirectionLeft -> if (currentIsRtl) 1 else -1
                            Key.DirectionRight -> if (currentIsRtl) -1 else 1
                            else -> 0
                        }
                        if (dirDelta != 0) {
                            currentVm.moveTabBy(tab.id, dirDelta)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
                onActivate = { _, _ -> vm.selectTab(tab.id) },
                onContextMenu = { off -> onShowMenu(off, buildMenu()) },
            )
            .hoverable(hover)
            .dropTarget(tab.path)
            .mouseGestures(
                tab.id,
                role = Role.Tab,
                selected = selected,
                onClickLabel = selectTabLabel,
                onClick = { _, _, _ -> vm.selectTab(tab.id) },
                onMiddle = { vm.closeTab(tab.id) },
                onSecondary = { onShowMenu(it, buildMenu()) },
                onLongPress = { onShowMenu(it, buildMenu()) },
            )
    }

    Box(
        Modifier
            .width(width)
            .heightIn(min = 48.dp)
            .fillMaxHeight()
            .zIndex(if (isBeingDraggedInStrip) 10f else if (slideAnim.isRunning) 2f else 0f)
            // Keep layout-coordinate recording & pointerInput BEFORE graphicsLayer so pointer tracking is
            // 100% invariant to live translationX adjustments (zero feedback oscillation) and stays mounted
            // even while `isDetached` collapses visual width to 0.dp.
            .onGloballyPositioned { coords ->
                itemCoords = coords
                itemOriginInRoot = coords.positionInRoot()
            }
            .pointerInput(tab.id) {
                val verticalDetachSlopPx = 32.dp.toPx()
                val horizontalDetachSlopPx = 56.dp.toPx()
                val edgeZonePx = 36.dp.toPx()
                val maxScrollSpeedPx = 640.dp.toPx()

                fun toRoot(localPos: Offset): Offset {
                    val c = itemCoords
                    return if (c != null && c.isAttached) c.localToRoot(localPos) else itemOriginInRoot + localPos
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downRoot = toRoot(down.position)
                    if (closeBoundsInRoot?.contains(downRoot) == true) return@awaitEachGesture

                    val mouse = down.isPrecise()
                    val firstDragChange: PointerInputChange
                    if (mouse) {
                        val btns = currentEvent.buttons
                        if (btns.isSecondaryPressed || btns.isTertiaryPressed || btns.isBackPressed || btns.isForwardPressed) {
                            return@awaitEachGesture
                        }
                        if (!btns.isPrimaryPressed && btns.areAnyPressed) return@awaitEachGesture
                        val slopPx = minOf(vc.touchSlop, 6.dp.toPx())
                        val crossed: PointerInputChange
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            if (ev.buttons.isSecondaryPressed || ev.buttons.isTertiaryPressed) return@awaitEachGesture
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (ch.isConsumed || !ch.pressed) return@awaitEachGesture
                            if ((ch.position - down.position).getDistance() > slopPx) {
                                ch.consume()
                                crossed = ch
                                break
                            }
                        }
                        firstDragChange = crossed
                    } else {
                        // Touch / stylus: start tab drag well before longPressTimeoutMillis (~400ms) so pressing and
                        // sliding a tab never pops up the long-press context menu first. When the strip does not
                        // overflow (or on a downward/upward tear gesture), drag starts immediately past touchDragSlopPx;
                        // when the strip overflows, fast horizontal flicks (< 130ms) still scroll the tab strip cleanly.
                        val holdMs = if (scrollState.maxValue > 0) {
                            minOf(130L, (vc.longPressTimeoutMillis * 0.32f).toLong())
                        } else {
                            0L
                        }
                        val holdDeadline = down.uptimeMillis + holdMs
                        val touchDragSlopPx = (vc.touchSlop * 0.65f).coerceAtLeast(4.dp.toPx())
                        val crossed: PointerInputChange
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!ch.pressed) return@awaitEachGesture
                            val delta = ch.position - down.position
                            val dist = delta.getDistance()
                            val verticalPull = kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x) * 1.15f
                            if (ch.uptimeMillis < holdDeadline && !verticalPull) {
                                if (dist > vc.touchSlop) return@awaitEachGesture
                            } else if (dist > touchDragSlopPx) {
                                ch.consume()
                                crossed = ch
                                break
                            }
                        }
                        firstDragChange = crossed
                    }

                    menu.dismiss()
                    if (currentAppState.activeId != tab.id) {
                        currentVm.selectTab(tab.id)
                    }
                    val grabOffsetInTabPx = down.position.x.coerceIn(0f, currentTabWidthPx.coerceAtLeast(1f))
                    var lastPointerRoot = toRoot(firstDragChange.position)
                    var autoScrollJob: Job? = null
                    var activeFallbackSession: TabDragSession? = null

                    fun applyInStripPosition(pointerRoot: Offset) {
                        val contentX = pointerRoot.x - stripContentOriginInRoot().x
                        val count = currentAppState.tabs.size
                        val clampedLeft = TabStripDragMath.clampedDraggedLeftPx(
                            pointerContentX = contentX,
                            grabOffsetPx = grabOffsetInTabPx,
                            tabWidthPx = currentTabWidthPx,
                            tabCount = count,
                        )
                        currentDragCallback(tab.id, clampedLeft)
                        val curIdx = currentAppState.tabs.indexOfFirst { it.id == tab.id }
                        if (curIdx >= 0 && count > 1) {
                            val targetIdx = TabStripDragMath.targetReorderIndex(
                                clampedLeftPx = clampedLeft,
                                tabWidthPx = currentTabWidthPx,
                                tabCount = count,
                                currentIndex = curIdx,
                                isRtl = currentIsRtl,
                            )
                            if (targetIdx != curIdx) {
                                currentVm.moveTab(curIdx, targetIdx)
                            }
                        }
                    }

                    fun ensureStripAutoScroll() {
                        if (scrollState.maxValue <= 0) return
                        if (autoScrollJob?.isActive == true) return
                        val vpLocalX = lastPointerRoot.x - stripViewportOriginInRoot().x
                        val speed = TabStripDragMath.autoScrollSpeedPxPerSec(vpLocalX, stripViewportWidthPx(), edgeZonePx, maxScrollSpeedPx)
                        if (speed == 0f) return
                        autoScrollJob = scope.launch {
                            var lastNanos = withFrameNanos { it }
                            while (isActive && activeFallbackSession == null) {
                                val nowNanos = withFrameNanos { it }
                                val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                lastNanos = nowNanos
                                val curVpX = lastPointerRoot.x - stripViewportOriginInRoot().x
                                val curSpeed = TabStripDragMath.autoScrollSpeedPxPerSec(curVpX, stripViewportWidthPx(), edgeZonePx, maxScrollSpeedPx)
                                if (curSpeed == 0f) break
                                val signedScroll = if (currentIsRtl) -curSpeed * dt else curSpeed * dt
                                val consumed = scrollState.scrollBy(signedScroll)
                                if (consumed == 0f) break
                                applyInStripPosition(lastPointerRoot)
                            }
                        }
                    }

                    try {
                        applyInStripPosition(lastPointerRoot)
                        while (true) {
                            val curRoot = lastPointerRoot
                            val existingFb = activeFallbackSession
                            if (existingFb == null) {
                                val stNow = currentAppState
                                val canTear = TabStripDragMath.canTearTabOut(
                                    tabCount = stNow.tabs.size,
                                    isPickerWindow = stNow.picker != null,
                                    hasActiveFileOps = stNow.ops.isNotEmpty(),
                                    otherWindowCount = currentOtherWindowCount,
                                    connectedDisplayCount = currentDisplays.size,
                                )
                                val stripLocal = curRoot - stripViewportOriginInRoot()
                                if (canTear && TabStripDragMath.shouldDetachFromStrip(
                                        pointerStripX = stripLocal.x,
                                        pointerStripY = stripLocal.y,
                                        stripViewportWidthPx = stripViewportWidthPx(),
                                        stripHeightPx = stripViewportHeightPx(),
                                        verticalSlopPx = verticalDetachSlopPx,
                                        horizontalSlopPx = horizontalDetachSlopPx,
                                    )
                                ) {
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                    currentDragCallback(null, 0f)
                                    val snap = currentVm.snapshotTabForTransfer(
                                        id = tab.id,
                                        willDrainWindow = stNow.tabs.size <= 1,
                                    )
                                    if (snap != null) {
                                        currentVm.setDetachedTab(tab.id)
                                        val titleForDrag = stNow.titleOf(tab.path)
                                        val session = TabTransferRegistry.startDragSession(
                                            sourceWindowId = currentWindowId,
                                            sourceTabId = tab.id,
                                            tabTitle = titleForDrag,
                                            snapshot = snap,
                                            canSpawnStandaloneWindow = stNow.tabs.size > 1 && stNow.picker == null,
                                            sourceDisplayId = currentDisplay,
                                            onCommitSource = { currentVm.commitDetachedTab(tab.id) },
                                            onRestoreSource = { currentVm.setDetachedTab(null) },
                                        )
                                        if (startSystemTabDrag(view, currentWindowId, session)) {
                                            // System WindowManager now drives DragEvents across windows & connected displays.
                                            return@awaitEachGesture
                                        }
                                        activeFallbackSession = session
                                        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                                        InProcessTabDragBus.onMove(
                                            session = session,
                                            screenPos = Offset(loc[0] + curRoot.x, loc[1] + curRoot.y),
                                            sourceRootPos = curRoot,
                                        )
                                    }
                                } else {
                                    applyInStripPosition(curRoot)
                                    ensureStripAutoScroll()
                                }
                            } else {
                                if (TabTransferRegistry.activeSession(existingFb.sessionId) == null) {
                                    // Cancelled externally (e.g. Escape key).
                                    break
                                }
                                val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                                InProcessTabDragBus.onMove(
                                    session = existingFb,
                                    screenPos = Offset(loc[0] + curRoot.x, loc[1] + curRoot.y),
                                    sourceRootPos = curRoot,
                                )
                            }

                            val nextEv = awaitPointerEvent(PointerEventPass.Main)
                            val ch = nextEv.changes.firstOrNull { it.id == down.id }
                            if (ch == null) {
                                activeFallbackSession?.let { InProcessTabDragBus.onCancel(it.sessionId) }
                                break
                            }
                            ch.consume()
                            lastPointerRoot = toRoot(ch.position)
                            if (!ch.pressed) {
                                val fbSession = activeFallbackSession
                                if (fbSession != null) {
                                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                                    val releaseScreen = Offset(loc[0] + lastPointerRoot.x, loc[1] + lastPointerRoot.y)
                                    InProcessTabDragBus.onRelease(fbSession, releaseScreen) { sx, sy ->
                                        currentVm.launchDetachedSessionWindow(
                                            sessionId = fbSession.sessionId,
                                            fallbackPath = fbSession.snapshot.path,
                                            promoteToPrimary = fbSession.snapshot.promoteTargetToPrimary,
                                            targetDisplayId = currentDisplay,
                                            dropX = sx,
                                            dropY = sy,
                                        )
                                    }
                                }
                                break
                            }
                        }
                    } finally {
                        autoScrollJob?.cancel()
                        currentDragCallback(null, 0f)
                        activeFallbackSession?.let { fb ->
                            if (TabTransferRegistry.activeSession(fb.sessionId) != null) {
                                InProcessTabDragBus.onCancel(fb.sessionId)
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                alpha = if (isDetached) 0f else 1f
                translationX = if (isDetached) {
                    0f
                } else if (isBeingDraggedInStrip) {
                    TabStripDragMath.draggedTabTranslationPx(
                        clampedLeftPx = activeClampedLeftPx,
                        slotIndex = currentLogicalIndex,
                        tabWidthPx = currentTabWidthPx,
                        tabCount = currentVisibleTabCount,
                        isRtl = currentIsRtl,
                    )
                } else {
                    slideAnim.value + slideTracker.pendingDeltaPx
                }
            }
            .then(interactiveTail),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!isDetached) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp, end = if (selected || isBeingDraggedInStrip) 0.dp else 2.dp, bottom = if (selected || isBeingDraggedInStrip) 0.dp else 4.dp)
                    .clip(shape)
                    .background(bg),
            )
            Row(
                Modifier.fillMaxSize().padding(start = if (width >= 96.dp) 12.dp else 8.dp, end = if (showClose) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    TabPathIcon(path = tab.path, s = s, selected = selected || isBeingDraggedInStrip)
                }
                Text(
                    tabTitle, Modifier.weight(1f).padding(start = if (showIcon) 8.dp else 0.dp, end = if (showClose) 0.dp else 4.dp),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected || isBeingDraggedInStrip) cs.onSurface else cs.onSurfaceVariant,
                )
                if (showClose) {
                    val closeHover = remember { MutableInteractionSource() }
                    val closeHovered by closeHover.collectIsHoveredAsState()
                    DisposableEffect(Unit) {
                        onDispose { closeBoundsInRoot = null }
                    }
                    ActionTooltip(closeTabTooltip) {
                        Box(
                            Modifier.size(48.dp)
                                .onGloballyPositioned { closeBoundsInRoot = it.boundsInRoot() }
                                .keyboardFocusableItem(
                                    shape = CircleShape,
                                    onActivate = { _, _ -> vm.closeTab(tab.id) },
                                )
                                .clip(CircleShape)
                                .hoverable(closeHover)
                                .mouseGestures(
                                    "close${tab.id}",
                                    role = Role.Button,
                                    contentDescription = closeTabDesc,
                                    onClickLabel = closeTabNamedLabel,
                                    onClick = { _, _, _ -> vm.closeTab(tab.id) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .background(if (closeHovered) cs.onSurface.copy(alpha = 0.14f) else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
