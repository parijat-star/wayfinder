// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
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
                            if (!ch.pressed) {
                                ch.consume()
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

                // Precise pointer (mouse / trackpad): monitor for mid-gesture two-finger secondary click as well.
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
                    if (!ch.pressed) {
                        up = ch
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
 * Starts a mouse/trackpad drag (immediate on touchSlop) or touch/stylus hold-and-drag (after long-press hold + slop)
 * of [paths], initiating system cross-app [android.view.View.startDragAndDrop] with seamless in-window fallback.
 */
@Composable
fun Modifier.dragSource(key: Any?, paths: () -> List<String>, onStart: () -> Unit): Modifier {
    val drag = LocalDrag.current
    val menu = LocalMenu.current
    val view = LocalView.current
    val windowDragId = LocalWindowDragId.current
    val vc = LocalViewConfiguration.current
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentPaths by rememberUpdatedState(paths)
    val start by rememberUpdatedState(onStart)
    return this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val mouse = down.isPrecise()
                if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture

                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
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

/** Rubber-band (marquee) selection on empty space; items report their bounds in root coordinates. */
class RubberBandState {
    var start by mutableStateOf<Offset?>(null)
    var current by mutableStateOf(Offset.Zero)
    val itemBounds = HashMap<String, Rect>()
    val rect: Rect? get() = start?.let { Rect(it, current) }
}

fun Modifier.rubberBand(
    state: RubberBandState,
    onSelect: (Set<String>, additive: Boolean) -> Unit,
    onEmptyClick: () -> Unit,
    onSecondary: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit = onSecondary,
): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val select by rememberUpdatedState(onSelect)
    val empty by rememberUpdatedState(onEmptyClick)
    val sec by rememberUpdatedState(onSecondary)
    val lp by rememberUpdatedState(onLongPress)
    val menu = LocalMenu.current
    val vc = LocalViewConfiguration.current
    this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
                if (currentEvent.buttons.isSecondaryPressed) {
                    down.consume()
                    sec(origin + down.position)
                    waitForUpOrCancellation()?.consume()
                    return@awaitEachGesture
                }
                if (!down.isPrecise()) {
                    // Touch on empty folder area: tap clears selection, long-press opens folder background menu.
                    // Offset by +16ms after child row longPressTimeoutMillis so a child item always consumes `down` first.
                    val deadlineMs = down.uptimeMillis + vc.longPressTimeoutMillis + 16L
                    while (true) {
                        if (down.isConsumed || currentEvent.changes.any { it.isConsumed }) return@awaitEachGesture
                        val now = currentEvent.changes.firstOrNull { it.id == down.id }?.uptimeMillis ?: down.uptimeMillis
                        val remaining = deadlineMs - now
                        if (remaining <= 0L) {
                            if (down.isConsumed || currentEvent.changes.any { it.isConsumed }) return@awaitEachGesture
                            try {
                                menu.openingGestureActive = true
                                lp(origin + down.position)
                                waitForUpOrCancellation()?.consume()
                            } finally {
                                menu.openingGestureActive = false
                            }
                            return@awaitEachGesture
                        }
                        val nextEvent = withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) }
                        if (nextEvent == null) {
                            if (down.isConsumed || currentEvent.changes.any { it.isConsumed }) return@awaitEachGesture
                            try {
                                menu.openingGestureActive = true
                                lp(origin + down.position)
                                waitForUpOrCancellation()?.consume()
                            } finally {
                                menu.openingGestureActive = false
                            }
                            return@awaitEachGesture
                        }
                        if (nextEvent.buttons.isSecondaryPressed) {
                            nextEvent.changes.forEach { it.consume() }
                            sec(origin + down.position)
                            waitForUpOrCancellation()?.consume()
                            return@awaitEachGesture
                        }
                        val ch = nextEvent.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (ch.isConsumed || (ch.position - down.position).getDistance() > vc.touchSlop) {
                            return@awaitEachGesture
                        }
                        if (ch.uptimeMillis >= deadlineMs) {
                            val stillDown = ch.pressed
                            if (!stillDown) {
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
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val additive = currentEvent.keyboardModifiers.isCtrlPressed
                val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                if (slop == null) {
                    // A child (row / grid cell / drag source) already handled this click or drag.
                    val handledByChild = currentEvent.changes.any { it.isConsumed }
                    if (!additive && !handledByChild) empty()
                    return@awaitEachGesture
                }
                state.start = origin + down.position
                state.current = origin + slop.position
                drag(slop.id) { c ->
                    state.current = origin + c.position
                    val r = state.rect!!
                    select(state.itemBounds.filterValues { it.overlaps(r) }.keys, additive)
                    c.consume()
                }
                state.start = null
            }
        }
}
