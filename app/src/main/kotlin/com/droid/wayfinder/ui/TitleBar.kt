// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.droid.wayfinder.R
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
            val addBtn = 48.dp
            val n = s.tabs.size.coerceAtLeast(1)
            val tabW = ((stripMaxWidth - addBtn - 4.dp) / n).coerceIn(76.dp, 220.dp)
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
            Row(
                Modifier.widthIn(max = stripMaxWidth).systemGestureExclusion().fillMaxHeight().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.tabs.forEachIndexed { idx, tab ->
                    androidx.compose.runtime.key(tab.id) {
                        TabItem(vm, s, tab, tabW, isFirstTab = idx == 0, onShowMenu = onShowMenu)
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
private fun TabItem(
    vm: BrowserViewModel,
    s: AppState,
    tab: TabState,
    width: Dp,
    isFirstTab: Boolean = false,
    onShowMenu: (androidx.compose.ui.geometry.Offset, List<MenuEntry>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
        selected -> cs.surfaceContainerLow
        hovered -> cs.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (selected) 0.dp else 8.dp, bottomEnd = if (selected) 0.dp else 8.dp)
    var accum by remember { mutableFloatStateOf(0f) }
    val tabWidthPx = with(LocalDensity.current) { width.toPx() }
    val moveBy by rememberUpdatedState { delta: Int -> vm.moveTabBy(tab.id, delta) }
    val showClose = selected || width >= 116.dp
    val showIcon = width > if (showClose) 104.dp else 72.dp
    val tabTitle = s.titleOf(tab.path)
    val selectTabLabel = stringResource(R.string.action_select_tab_named, tabTitle)
    val closeTabTooltip = stringResource(R.string.tooltip_close_tab)
    val closeTabDesc = stringResource(R.string.action_close_tab)
    val closeTabNamedLabel = stringResource(R.string.action_close_tab_named, tabTitle)
    Box(
        Modifier.width(width).heightIn(min = 48.dp).fillMaxHeight()
            .keyboardFocusableItem(
                shape = shape,
                focusRequester = if (isFirstTab) tabRequester else null,
                onActivate = { _, _ -> vm.selectTab(tab.id) },
                onContextMenu = { off -> onShowMenu(off, tabMenu(vm, tab.id)) },
            )
            .hoverable(hover)
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
                onClickLabel = selectTabLabel,
                onClick = { _, _, _ -> vm.selectTab(tab.id) },
                onMiddle = { vm.closeTab(tab.id) },
                onSecondary = { onShowMenu(it, tabMenu(vm, tab.id)) },
                onLongPress = { onShowMenu(it, tabMenu(vm, tab.id)) },
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            Modifier.fillMaxSize().padding(top = 6.dp, end = if (selected) 0.dp else 2.dp, bottom = if (selected) 0.dp else 4.dp)
                .clip(shape).background(bg),
        )
        Row(
            Modifier.fillMaxSize().padding(start = if (width >= 96.dp) 12.dp else 8.dp, end = if (showClose) 0.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                if (FsPath.isOneDrive(tab.path)) {
                    OneDriveIcon(Modifier.size(18.dp))
                } else if (FsPath.isDropbox(tab.path)) {
                    DropboxIcon(Modifier.size(18.dp))
                } else {
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
                    Icon(icon, null, Modifier.size(18.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
                }
            }
            Text(
                tabTitle, Modifier.weight(1f).padding(start = if (showIcon) 8.dp else 0.dp, end = if (showClose) 0.dp else 4.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            )
            if (showClose) {
                val closeHover = remember { MutableInteractionSource() }
                val closeHovered by closeHover.collectIsHoveredAsState()
                ActionTooltip(closeTabTooltip) {
                    Box(
                        Modifier.size(48.dp)
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
