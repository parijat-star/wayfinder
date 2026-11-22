// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.data.MarqueeMode
import com.droid.wayfinder.data.MarqueeRect
import com.droid.wayfinder.data.MarqueeSession
import com.droid.wayfinder.data.marqueeAutoScrollSpeedPxPerSec
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tracks whether the current pointer gesture came from a mouse or trackpad. Android reports
 * trackpad taps/clicks with a finger tool type, which Compose maps to [PointerType.Touch];
 * MainActivity feeds every MotionEvent here so desktop click semantics still apply.
 */
object PointerSource {
    @Volatile var precise: Boolean = false
        private set

    fun update(ev: android.view.MotionEvent) {
        if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN &&
            ev.actionMasked != android.view.MotionEvent.ACTION_BUTTON_PRESS
        ) {
            return
        }
        val swipe = android.os.Build.VERSION.SDK_INT >= 34 &&
            (ev.classification == android.view.MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE ||
                ev.classification == 4 /* CLASSIFICATION_MULTI_FINGER_SWIPE */)
        precise = !swipe && (
            ev.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_MOUSE ||
                ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
                ev.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)
            )
    }
}

/** True for mouse and trackpad input (desktop semantics), false for touchscreen/stylus. */
fun PointerInputChange.isPrecise(): Boolean = type == PointerType.Mouse || PointerSource.precise

/**
 * True when a mouse/trackpad press is a primary action (left button or trackpad tap-and-drag with
 * zero button mask), excluding secondary/tertiary/back/forward buttons.
 */
private fun PointerEvent.isPrimaryMouseAction(): Boolean {
    if (buttons.isSecondaryPressed || buttons.isTertiaryPressed || buttons.isBackPressed || buttons.isForwardPressed) {
        return false
    }
    return buttons.isPrimaryPressed || !buttons.areAnyPressed
}

/**
 * Unified mouse/trackpad/touch gestures for rows, sidebar locations, and tabs:
 * left click (+Ctrl/Shift), double click, right/two-finger click, middle click, and touch long-press.
 */
fun Modifier.mouseGestures(
    key: Any?,
    role: Role? = null,
    selected: Boolean? = null,
    contentDescription: String? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onClick: (ctrl: Boolean, shift: Boolean, touch: Boolean) -> Unit = { _, _, _ -> },
    onDoubleClick: () -> Unit = {},
    onSecondary: (rootOffset: Offset) -> Unit = {},
    onMiddle: () -> Unit = {},
    onLongPress: ((rootOffset: Offset) -> Unit)? = null,
): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val click by rememberUpdatedState(onClick)
    val dbl by rememberUpdatedState(onDoubleClick)
    val secondary by rememberUpdatedState(onSecondary)
    val middle by rememberUpdatedState(onMiddle)
    val longPress by rememberUpdatedState(onLongPress)
    val vc = LocalViewConfiguration.current
    val menu = LocalMenu.current

    this
        .semantics {
            role?.let { this.role = it }
            selected?.let { this.selected = it }
            contentDescription?.let { this.contentDescription = it }
            this.onClick(label = onClickLabel) {
                click(false, false, true)
                true
            }
            if (longPress != null) {
                this.onLongClick(label = onLongClickLabel) {
                    val coords = holder[0]
                    val center = if (coords != null) Offset(coords.size.width / 2f, coords.size.height / 2f) else Offset.Zero
                    val rootPos = coords?.localToRoot(center) ?: Offset.Zero
                    longPress?.invoke(rootPos)
                    true
                }
            }
        }
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(key) {
            var lastUpTime = 0L
            var lastUpPos = Offset.Zero
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val ev = currentEvent
                val mouse = down.isPrecise()
                val rootPos = holder[0]?.localToRoot(down.position) ?: down.position
                if (ev.buttons.isSecondaryPressed) {
                    down.consume()
                    secondary(rootPos)
                    waitForUpOrCancellation()?.consume()
                    return@awaitEachGesture
                }
                if (mouse && ev.buttons.isTertiaryPressed) {
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        middle()
                    }
                    return@awaitEachGesture
                }
                val ctrl = ev.keyboardModifiers.isCtrlPressed || ev.keyboardModifiers.isMetaPressed
                val shift = ev.keyboardModifiers.isShiftPressed
                if (!mouse) {
                    val lpHandler = longPress
                    if (lpHandler == null) {
                        // Control has no long-press menu: invoke onClick cleanly on touch release even after >400ms hold.
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                            if (nextEvent.buttons.isSecondaryPressed) {
                                nextEvent.changes.forEach { it.consume() }
                                secondary(rootPos)
                                waitForUpOrCancellation()?.consume()
                                return@awaitEachGesture
                            }
                            val ch = nextEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (ch.isConsumed || (ch.position - down.position).getDistance() > vc.touchSlop) {
                                return@awaitEachGesture
                            }
                            val stillPressed = ch.pressed
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            val finalCh = finalEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (finalCh.isConsumed) return@awaitEachGesture
                            if (!stillPressed) {
                                finalCh.consume()
                                click(false, false, true)
                                return@awaitEachGesture
                            }
                        }
                    }

                    val deadlineMs = down.uptimeMillis + vc.longPressTimeoutMillis
                    var upChange: PointerInputChange? = null
                    var triggeredLongPress = false
                    var fingerStillDownOnLongPress = true
                    var latestPos = down.position
                    while (true) {
                        val curUptime = currentEvent.changes.firstOrNull { it.id == down.id }?.uptimeMillis ?: down.uptimeMillis
                        val remainingMs = deadlineMs - curUptime
                        if (remainingMs <= 0L) {
                            triggeredLongPress = true
                            break
                        }
                        val nextEvent = withTimeoutOrNull(remainingMs) { awaitPointerEvent(PointerEventPass.Main) }
                        if (nextEvent == null) {
                            triggeredLongPress = true
                            break
                        }
                        if (nextEvent.buttons.isSecondaryPressed) {
                            nextEvent.changes.forEach { it.consume() }
                            secondary(rootPos)
                            waitForUpOrCancellation()?.consume()
                            return@awaitEachGesture
                        }
                        val change = nextEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (change.isConsumed) return@awaitEachGesture
                        latestPos = change.position
                        if ((change.position - down.position).getDistance() > vc.touchSlop) {
                            // Finger moved before long-press timeout -> allow LazyColumn / Grid scrolling.
                            return@awaitEachGesture
                        }
                        if (change.uptimeMillis >= deadlineMs) {
                            triggeredLongPress = true
                            fingerStillDownOnLongPress = change.pressed
                            change.consume()
                            break
                        }
                        if (!change.pressed) {
                            upChange = change
                            break
                        }
                    }
                    if (triggeredLongPress) {
                        currentEvent.changes.forEach { it.consume() }
                        val lpRootPos = holder[0]?.localToRoot(latestPos) ?: rootPos
                        if (fingerStillDownOnLongPress) {
                            try {
                                menu.openingGestureActive = true
                                lpHandler(lpRootPos)
                                while (true) {
                                    val postEvent = awaitPointerEvent(PointerEventPass.Main)
                                    val ch = postEvent.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.isConsumed) break
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                            } finally {
                                menu.openingGestureActive = false
                            }
                        } else {
                            lpHandler(lpRootPos)
                        }
                        return@awaitEachGesture
                    }
                    val up = upChange ?: return@awaitEachGesture
                    up.consume()
                    click(false, false, true)
                    return@awaitEachGesture
                }

                // Precise pointer (mouse / trackpad): monitor for mid-gesture two-finger secondary click as well,
                // while checking PointerEventPass.Final so outer dragSource / scroll gestures aren't preempted on release.
                var up: PointerInputChange?
                while (true) {
                    val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                    if (nextEvent.buttons.isSecondaryPressed) {
                        val latestPos = nextEvent.changes.firstOrNull { it.id == down.id }?.position ?: down.position
                        val secRoot = holder[0]?.localToRoot(latestPos) ?: rootPos
                        nextEvent.changes.forEach { it.consume() }
                        secondary(secRoot)
                        waitForUpOrCancellation()?.consume()
                        return@awaitEachGesture
                    }
                    val ch = nextEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (ch.isConsumed) return@awaitEachGesture
                    val stillPressed = ch.pressed
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    val finalCh = finalEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (finalCh.isConsumed) return@awaitEachGesture
                    if (!stillPressed) {
                        up = finalCh
                        break
                    }
                }
                val resolvedUp = up
                resolvedUp.consume()
                val isDouble = down.uptimeMillis - lastUpTime < vc.doubleTapTimeoutMillis &&
                    (down.position - lastUpPos).getDistance() < 48f
                click(ctrl, shift, false)
                if (isDouble) {
                    dbl()
                    lastUpTime = 0L
                } else {
                    lastUpTime = resolvedUp.uptimeMillis
                    lastUpPos = resolvedUp.position
                }
            }
        }
}

// ------------------------------------------------------------------ drag & drop (in-app + cross-app)

class DropTarget(val path: String, var bounds: Rect)

/** Shared state for in-app drag & drop of files onto folders, sidebar items and tabs. */
class DragController {
    var paths by mutableStateOf<List<String>>(emptyList())
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var hover by mutableStateOf<String?>(null)
        private set
    var copyMode by mutableStateOf(false)
        private set
    val active get() = paths.isNotEmpty()

    private val targets = LinkedHashMap<Any, DropTarget>()
    var onDrop: (target: String, paths: List<String>, copy: Boolean) -> Unit = { _, _, _ -> }

    fun register(k: Any, t: DropTarget) { targets[k] = t }
    fun unregister(k: Any) { targets.remove(k) }

    fun start(p: List<String>, at: Offset, copy: Boolean = false) {
        paths = p
        pointer = at
        copyMode = copy
        hover = hit(at)
    }

    fun updateCopyMode(copy: Boolean) {
        copyMode = copy
    }

    fun move(at: Offset, copy: Boolean) { pointer = at; copyMode = copy; hover = hit(at) }
    fun cancel() { paths = emptyList(); hover = null; copyMode = false }

    fun finish() {
        val h = hover
        val p = paths
        val copy = copyMode
        cancel()
        if (h != null && p.isNotEmpty()) onDrop(h, p, copy)
    }

    private fun hit(at: Offset): String? =
        targets.values.filter { it.bounds.contains(at) && it.path !in paths }
            .minByOrNull { it.bounds.width * it.bounds.height }?.path
}

val LocalDrag = staticCompositionLocalOf { DragController() }

/** Registers this element as a drop target for [path] (null = not a target). */
@Composable
fun Modifier.dropTarget(path: String?): Modifier {
    val drag = LocalDrag.current
    val k = remember { Any() }
    DisposableEffect(path) { onDispose { drag.unregister(k) } }
    return if (path == null) this else this.onGloballyPositioned { drag.register(k, DropTarget(path, it.boundsInRoot())) }
}

/**
 * Starts a mouse/trackpad drag (immediate on touchSlop, gated by [canDrag] so empty row whitespace
 * can start marquee selection instead) or touch/stylus hold-and-drag (after long-press hold + slop)
 * of [paths], initiating system cross-app [android.view.View.startDragAndDrop] with seamless in-window fallback.
 */
@Composable
fun Modifier.dragSource(
    key: Any?,
    paths: () -> List<String>,
    onStart: () -> Unit,
    canDrag: (rootOffset: Offset) -> Boolean = { true },
): Modifier {
    val drag = LocalDrag.current
    val menu = LocalMenu.current
    val view = LocalView.current
    val windowDragId = LocalWindowDragId.current
    val vc = LocalViewConfiguration.current
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentPaths by rememberUpdatedState(paths)
    val start by rememberUpdatedState(onStart)
    val allowDrag by rememberUpdatedState(canDrag)
    return this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val mouse = down.isPrecise()
                if (mouse && !currentEvent.isPrimaryMouseAction()) return@awaitEachGesture
                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
                if (mouse && !allowDrag(origin + down.position)) return@awaitEachGesture

                val dragStartChange: PointerInputChange
                if (mouse) {
                    dragStartChange = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                } else {
                    // Touch / stylus: allow normal list scrolling if moved before longPressTimeoutMillis;
                    // once held stationary past longPressTimeoutMillis, dragging past touchSlop initiates file drag.
                    val holdDeadline = down.uptimeMillis + vc.longPressTimeoutMillis
                    val crossedAfterHold: PointerInputChange
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!ch.pressed) return@awaitEachGesture
                        val dist = (ch.position - down.position).getDistance()
                        if (ch.uptimeMillis < holdDeadline) {
                            if (dist > vc.touchSlop) return@awaitEachGesture
                        } else if (dist > vc.touchSlop) {
                            ch.consume()
                            crossedAfterHold = ch
                            break
                        }
                    }
                    dragStartChange = crossedAfterHold
                    menu.dismiss()
                }

                start()
                val p = currentPaths()
                if (p.isEmpty()) return@awaitEachGesture

                val initCopy = currentEvent.keyboardModifiers.isCtrlPressed || currentEvent.keyboardModifiers.isMetaPressed
                val startedSystemDrag = startSystemFileDrag(view, windowDragId, p, initCopy)
                if (startedSystemDrag) {
                    // Android WindowManager now owns the drag stream and dispatches DragEvents to AppRoot / external apps.
                    return@awaitEachGesture
                }

                val startPos = origin + dragStartChange.position
                drag.start(p, startPos, initCopy)
                var lastCtrl = initCopy
                val completed = drag(dragStartChange.id) { change ->
                    lastCtrl = currentEvent.keyboardModifiers.isCtrlPressed || currentEvent.keyboardModifiers.isMetaPressed
                    drag.move(origin + change.position, lastCtrl)
                    change.consume()
                }
                if (completed) drag.finish() else drag.cancel()
            }
        }
}

/** Rubber-band (marquee) selection state; items report their full and drag-handle bounds in root coordinates. */
class RubberBandState {
    /** Active normalized & viewport-clamped marquee box in viewport-local pixels, or null when idle. */
    var overlayRect by mutableStateOf<MarqueeRect?>(null)
    val active: Boolean get() = overlayRect != null
    val itemBounds = HashMap<String, Rect>()
    val dragHandleBounds = HashMap<String, Rect>()

    fun isOnDragHandle(path: String, rootPos: Offset): Boolean {
        val handle = dragHandleBounds[path] ?: itemBounds[path] ?: return false
        return handle.contains(rootPos)
    }

    /** True if pressing at [rootPos] should start file drag-and-drop instead of marquee selection. */
    fun isDraggableHit(rootPos: Offset, selection: Set<String>, canDragPath: (String) -> Boolean): Boolean {
        for ((path, bounds) in itemBounds) {
            if (!bounds.contains(rootPos)) continue
            if (!canDragPath(path)) continue
            if (path in selection) return true
            val handle = dragHandleBounds[path] ?: bounds
            if (handle.contains(rootPos)) return true
        }
        return false
    }

    fun clear() {
        overlayRect = null
    }

    /**
     * Updates [itemBounds] for [path] from [coords]. Items pinned off-screen (e.g. holding keyboard
     * focus in a [androidx.compose.foundation.lazy.LazyColumn]) have empty clipped [boundsInRoot] and
     * are removed from live viewport bounds so stale coordinates never overwrite [MarqueeSession];
     * visible items record their unclipped root bounds so partial edge clipping preserves content height.
     */
    fun recordItemCoordinates(path: String, coords: LayoutCoordinates): Rect? {
        if (!coords.isAttached) {
            itemBounds.remove(path)
            dragHandleBounds.remove(path)
            return null
        }
        val clipped = coords.boundsInRoot()
        if (clipped.isEmpty) {
            itemBounds.remove(path)
            dragHandleBounds.remove(path)
            return null
        }
        val pos = coords.positionInRoot()
        val unclipped = Rect(pos.x, pos.y, pos.x + coords.size.width.toFloat(), pos.y + coords.size.height.toFloat())
        itemBounds[path] = unclipped
        return unclipped
    }
}

/** Draws the translucent Material 3 marquee rectangle overlay without forcing layout passes on drag moves. */
@Composable
fun MarqueeOverlay(state: RubberBandState) {
    val rect = state.overlayRect ?: return
    val cs = MaterialTheme.colorScheme
    val fill = cs.primary.copy(alpha = 0.14f)
    val stroke = cs.primary.copy(alpha = 0.72f)
    Canvas(
        Modifier.fillMaxSize().semantics { testTag = "marquee_selection_box" },
    ) {
        if (rect.width <= 0f && rect.height <= 0f) return@Canvas
        val minPx = 1.dp.toPx()
        val w = maxOf(rect.width, minPx)
        val h = maxOf(rect.height, minPx)
        val left = rect.left.coerceIn(0f, (size.width - w).coerceAtLeast(0f))
        val top = rect.top.coerceIn(0f, (size.height - h).coerceAtLeast(0f))
        val radius = CornerRadius(4.dp.toPx())
        val topLeft = Offset(left, top)
        val rectSize = Size(w, h)
        drawRoundRect(color = fill, topLeft = topLeft, size = rectSize, cornerRadius = radius)
        drawRoundRect(
            color = stroke,
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * Click-and-drag (marquee / rubber-band) multi-selection for scrollable file lists and grids.
 *
 * Intercepts mouse/trackpad drags in [PointerEventPass.Initial] when starting outside file drag
 * handles so [LazyColumn]/[LazyVerticalGrid] vertical scroll never steals the drag, while still
 * allowing clicks on row whitespace, right-clicks, touch scrolling, and file drag-and-drop.
 */
fun Modifier.rubberBand(
    state: RubberBandState,
    currentSelection: () -> Set<String>,
    validPaths: () -> Set<String>,
    canDragPath: (String) -> Boolean,
    scrollBy: suspend (Float) -> Float,
    canScrollUp: () -> Boolean,
    canScrollDown: () -> Boolean,
    onSelect: (Set<String>) -> Unit,
    onEmptyClick: () -> Unit,
    onSecondary: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit = onSecondary,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val selectionProvider by rememberUpdatedState(currentSelection)
    val validPathsProvider by rememberUpdatedState(validPaths)
    val canDragProvider by rememberUpdatedState(canDragPath)
    val scrollByProvider by rememberUpdatedState(scrollBy)
    val canScrollUpProvider by rememberUpdatedState(canScrollUp)
    val canScrollDownProvider by rememberUpdatedState(canScrollDown)
    val select by rememberUpdatedState(onSelect)
    val empty by rememberUpdatedState(onEmptyClick)
    val sec by rememberUpdatedState(onSecondary)
    val lp by rememberUpdatedState(onLongPress)
    val menu = LocalMenu.current
    val vc = LocalViewConfiguration.current
    this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
                val downRoot = origin + down.position

                if (!down.isPrecise()) {
                    // Touch on empty folder area (outside rendered items): tap clears selection, long-press opens background menu.
                    if (state.itemBounds.values.any { it.contains(downRoot) }) return@awaitEachGesture
                    val initFinal = awaitPointerEvent(PointerEventPass.Final)
                    if (initFinal.changes.any { it.isConsumed }) return@awaitEachGesture
                    val deadlineMs = down.uptimeMillis + vc.longPressTimeoutMillis + 16L
                    while (true) {
                        if (down.isConsumed || currentEvent.changes.any { it.isConsumed } || menu.openingGestureActive) return@awaitEachGesture
                        val now = currentEvent.changes.firstOrNull { it.id == down.id }?.uptimeMillis ?: down.uptimeMillis
                        val remaining = deadlineMs - now
                        if (remaining <= 0L) {
                            if (down.isConsumed || currentEvent.changes.any { it.isConsumed } || menu.openingGestureActive) return@awaitEachGesture
                            try {
                                menu.openingGestureActive = true
                                lp(downRoot)
                                waitForUpOrCancellation()?.consume()
                            } finally {
                                menu.openingGestureActive = false
                            }
                            return@awaitEachGesture
                        }
                        val nextEvent = withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Final) }
                        if (nextEvent == null) {
                            if (down.isConsumed || currentEvent.changes.any { it.isConsumed } || menu.openingGestureActive) return@awaitEachGesture
                            try {
                                menu.openingGestureActive = true
                                lp(downRoot)
                                waitForUpOrCancellation()?.consume()
                            } finally {
                                menu.openingGestureActive = false
                            }
                            return@awaitEachGesture
                        }
                        if (nextEvent.buttons.isSecondaryPressed) {
                            nextEvent.changes.forEach { it.consume() }
                            sec(downRoot)
                            waitForUpOrCancellation()?.consume()
                            return@awaitEachGesture
                        }
                        val ch = nextEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (ch.isConsumed || menu.openingGestureActive || (ch.position - down.position).getDistance() > vc.touchSlop) {
                            return@awaitEachGesture
                        }
                        if (ch.uptimeMillis >= deadlineMs) {
                            if (!ch.pressed) {
                                ch.consume()
                                lp(origin + ch.position)
                            } else {
                                try {
                                    menu.openingGestureActive = true
                                    lp(origin + ch.position)
                                    waitForUpOrCancellation()?.consume()
                                } finally {
                                    menu.openingGestureActive = false
                                }
                            }
                            return@awaitEachGesture
                        }
                        if (!ch.pressed) {
                            ch.consume()
                            empty()
                            return@awaitEachGesture
                        }
                    }
                }

                if (currentEvent.buttons.isSecondaryPressed) {
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    val finalDown = finalEvent.changes.firstOrNull { it.id == down.id }
                    if (finalDown != null && !finalDown.isConsumed) {
                        finalDown.consume()
                        sec(downRoot)
                        waitForUpOrCancellation()?.consume()
                    }
                    return@awaitEachGesture
                }
                if (!currentEvent.isPrimaryMouseAction()) return@awaitEachGesture

                val initialSelection = selectionProvider()
                if (state.isDraggableHit(downRoot, initialSelection, canDragProvider)) {
                    return@awaitEachGesture
                }

                val mods = currentEvent.keyboardModifiers
                val mode = when {
                    mods.isCtrlPressed || mods.isMetaPressed -> MarqueeMode.Toggle
                    mods.isShiftPressed -> MarqueeMode.Add
                    else -> MarqueeMode.Replace
                }
                val slopPx = minOf(viewConfiguration.touchSlop, 4.dp.toPx())
                val edgeZonePx = 36.dp.toPx()
                val maxSpeedPxPerSec = 720.dp.toPx()

                var dragging = false
                var session: MarqueeSession? = null
                var autoScrollJob: Job? = null
                var pendingLayout = false
                var lastPointerLocal = down.position

                fun syncSelection(sess: MarqueeSession, ingestVisible: Boolean) {
                    val coords = holder[0]
                    val curOrigin = coords?.positionInRoot() ?: origin
                    val vpSize = coords?.size ?: size
                    if (ingestVisible) {
                        val visibleLocal = HashMap<String, MarqueeRect>(state.itemBounds.size)
                        for ((path, rootRect) in state.itemBounds) {
                            visibleLocal[path] = MarqueeRect(
                                left = rootRect.left - curOrigin.x,
                                top = rootRect.top - curOrigin.y,
                                right = rootRect.right - curOrigin.x,
                                bottom = rootRect.bottom - curOrigin.y,
                            )
                        }
                        sess.updateVisibleItems(visibleLocal)
                    }
                    state.overlayRect = sess.overlayRect(vpSize.width.toFloat(), vpSize.height.toFloat())
                    select(sess.computeSelection(validPathsProvider()))
                }

                fun ensureAutoScrollLoop(sess: MarqueeSession) {
                    if (autoScrollJob?.isActive == true) return
                    val vpHeight = (holder[0]?.size?.height ?: size.height).toFloat()
                    val speed = marqueeAutoScrollSpeedPxPerSec(lastPointerLocal.y, vpHeight, edgeZonePx, maxSpeedPxPerSec)
                    val shouldScroll = (speed < 0f && canScrollUpProvider()) || (speed > 0f && canScrollDownProvider())
                    if (!shouldScroll && !pendingLayout) return
                    autoScrollJob = scope.launch {
                        var lastFrameNanos = withFrameNanos { it }
                        while (isActive) {
                            val curVpHeight = (holder[0]?.size?.height ?: size.height).toFloat()
                            val curSpeed = marqueeAutoScrollSpeedPxPerSec(lastPointerLocal.y, curVpHeight, edgeZonePx, maxSpeedPxPerSec)
                            val canStep = (curSpeed < 0f && canScrollUpProvider()) || (curSpeed > 0f && canScrollDownProvider())
                            if (!canStep && !pendingLayout) break
                            val nowNanos = withFrameNanos { it }
                            val dtSec = ((nowNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                            lastFrameNanos = nowNanos
                            if (pendingLayout) {
                                pendingLayout = false
                                syncSelection(sess, ingestVisible = true)
                            }
                            val stepVpHeight = (holder[0]?.size?.height ?: size.height).toFloat()
                            val stepSpeed = marqueeAutoScrollSpeedPxPerSec(lastPointerLocal.y, stepVpHeight, edgeZonePx, maxSpeedPxPerSec)
                            val stepCanScroll = (stepSpeed < 0f && canScrollUpProvider()) || (stepSpeed > 0f && canScrollDownProvider())
                            if (!stepCanScroll) break
                            if (dtSec > 0f) {
                                val consumed = scrollByProvider(stepSpeed * dtSec)
                                if (consumed == 0f) break
                                sess.onScrolled(consumed)
                                pendingLayout = true
                                syncSelection(sess, ingestVisible = false)
                            }
                        }
                    }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (dragging && event.type == PointerEventType.Scroll) {
                            val sess = session
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (sess != null && dy != 0f) {
                                event.changes.forEach { it.consume() }
                                val stepPx = dy * 64.dp.toPx()
                                scope.launch {
                                    val consumed = scrollByProvider(stepPx)
                                    if (consumed != 0f && state.active) {
                                        sess.onScrolled(consumed)
                                        pendingLayout = true
                                        syncSelection(sess, ingestVisible = false)
                                        ensureAutoScrollLoop(sess)
                                    }
                                }
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                change.consume()
                            } else {
                                val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                                val finalUp = finalEvent.changes.firstOrNull { it.id == down.id }
                                val handledByChild = finalUp?.isConsumed == true
                                if (!handledByChild && mode == MarqueeMode.Replace) {
                                    empty()
                                }
                            }
                            break
                        }
                        lastPointerLocal = change.position
                        if (!dragging) {
                            if ((change.position - down.position).getDistance() >= slopPx) {
                                dragging = true
                                val sess = MarqueeSession(
                                    startContentX = down.position.x,
                                    startContentY = down.position.y,
                                    initialSelection = initialSelection,
                                    mode = mode,
                                )
                                sess.moveTo(change.position.x, change.position.y)
                                session = sess
                                change.consume()
                                syncSelection(sess, ingestVisible = true)
                                ensureAutoScrollLoop(sess)
                            }
                        } else {
                            change.consume()
                            val sess = session!!
                            sess.moveTo(change.position.x, change.position.y)
                            syncSelection(sess, ingestVisible = !pendingLayout)
                            ensureAutoScrollLoop(sess)
                        }
                    }
                } finally {
                    autoScrollJob?.cancel()
                    state.clear()
                }
            }
        }
}
