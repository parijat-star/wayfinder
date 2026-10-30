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
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeout

/**
 * Tracks whether the current pointer gesture came from a mouse or trackpad. Android reports
 * trackpad taps/clicks with a finger tool type, which Compose maps to [PointerType.Touch];
 * MainActivity feeds every MotionEvent here so desktop click semantics still apply.
 */
object PointerSource {
    @Volatile var precise: Boolean = false
        private set

    fun update(ev: android.view.MotionEvent) {
        if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return
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
 * Unified mouse/trackpad/touch gestures for rows and tabs:
 * left click (+Ctrl/Shift), double click, right click, middle click, touch long press.
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
    onLongPress: (rootOffset: Offset) -> Unit = {},
): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val click by rememberUpdatedState(onClick)
    val dbl by rememberUpdatedState(onDoubleClick)
    val secondary by rememberUpdatedState(onSecondary)
    val middle by rememberUpdatedState(onMiddle)
    val longPress by rememberUpdatedState(onLongPress)
    val vc = LocalViewConfiguration.current
    this
        .semantics {
            role?.let { this.role = it }
            selected?.let { this.selected = it }
            contentDescription?.let { this.contentDescription = it }
            this.onClick(label = onClickLabel) {
                click(false, false, true)
                true
            }
            this.onLongClick(label = onLongClickLabel) {
                val rootPos = holder[0]?.localToRoot(Offset.Zero) ?: Offset.Zero
                longPress(rootPos)
                true
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
                if (mouse && ev.buttons.isSecondaryPressed) {
                    down.consume(); secondary(rootPos); waitForUpOrCancellation(); return@awaitEachGesture
                }
                if (mouse && ev.buttons.isTertiaryPressed) {
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) { up.consume(); middle() }
                    return@awaitEachGesture
                }
                val ctrl = ev.keyboardModifiers.isCtrlPressed || ev.keyboardModifiers.isMetaPressed
                val shift = ev.keyboardModifiers.isShiftPressed
                var up: PointerInputChange?
                if (!mouse) {
                    try {
                        up = withTimeout(vc.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        if (up == null) return@awaitEachGesture
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPress(rootPos); waitForUpOrCancellation(); return@awaitEachGesture
                    }
                } else {
                    up = waitForUpOrCancellation() ?: return@awaitEachGesture
                }
                up.consume()
                if (!mouse) { click(false, false, true); return@awaitEachGesture }
                val isDouble = down.uptimeMillis - lastUpTime < vc.doubleTapTimeoutMillis &&
                    (down.position - lastUpPos).getDistance() < 48f
                click(ctrl, shift, false)
                if (isDouble) { dbl(); lastUpTime = 0L } else { lastUpTime = up.uptimeMillis; lastUpPos = up.position }
            }
        }
}

// ------------------------------------------------------------------ drag & drop (in-app)

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

    fun start(p: List<String>, at: Offset) { paths = p; pointer = at; hover = hit(at) }
    fun move(at: Offset, copy: Boolean) { pointer = at; copyMode = copy; hover = hit(at) }
    fun cancel() { paths = emptyList(); hover = null }

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

/** Starts a mouse drag of [paths]; call-back [onStart] lets the caller fix up the selection first. */
@Composable
fun Modifier.dragSource(key: Any?, paths: () -> List<String>, onStart: () -> Unit): Modifier {
    val drag = LocalDrag.current
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentPaths by rememberUpdatedState(paths)
    val start by rememberUpdatedState(onStart)
    return this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!down.isPrecise() || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                start()
                val p = currentPaths()
                if (p.isEmpty()) return@awaitEachGesture
                drag.start(p, origin + slop.position)
                var lastCtrl = false
                val completed = drag(slop.id) { change ->
                    lastCtrl = currentEvent.keyboardModifiers.isCtrlPressed
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
): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val select by rememberUpdatedState(onSelect)
    val empty by rememberUpdatedState(onEmptyClick)
    val sec by rememberUpdatedState(onSecondary)
    this
        .onGloballyPositioned { holder[0] = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                if (!down.isPrecise()) return@awaitEachGesture
                val origin = holder[0]?.positionInRoot() ?: Offset.Zero
                if (currentEvent.buttons.isSecondaryPressed) {
                    down.consume(); sec(origin + down.position); waitForUpOrCancellation(); return@awaitEachGesture
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
