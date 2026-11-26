// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

internal enum class QuickLookMediaMode { None, Media, AnimatedImage }

/** Keyboard playback hooks exposed by the currently active Quick Look media/animation preview. */
internal class QuickLookMediaActions(
    val mode: QuickLookMediaMode,
    val togglePlayPause: () -> Unit,
    val seekBy: ((deltaMs: Long) -> Unit)? = null,
    val toggleMute: (() -> Unit)? = null,
    val isSliderFocused: () -> Boolean = { false },
)

/** Carries focus-zone state between [AppRoot], [Sidebar], [TitleBar], [Toolbar], [PreviewPane], [QuickLook], and [FileList]. */
internal class FocusCoordinator {
    var activeSidebarRequester: FocusRequester? = null
    var activeTitleBarRequester: FocusRequester? = null
    var activeTitleBarLastRequester: FocusRequester? = null
    var activeToolbarLastRequester: FocusRequester? = null
    var activePreviewFirstRequester: FocusRequester? = null
    var activeDrawerFirstRequester: FocusRequester? = null
    var activeQuickLookFirstRequester: FocusRequester? = null
    var activeQuickLookLastRequester: FocusRequester? = null
    var activeQuickLookBodyLastRequester: FocusRequester? = null
    var activeQuickLookMediaActions by mutableStateOf<QuickLookMediaActions?>(null)
    var activeFileListRequester: FocusRequester? = null
    private val sidebarItems = LinkedHashMap<FocusRequester, Float>()
    private val focusedAuxZones = HashSet<Any>()
    var nonListZoneFocused by mutableStateOf(false)
    var contentAuxControlsFocused by mutableStateOf(false)
        private set
    var firstSidebarItemFocused by mutableStateOf(false)
    var keyboardFocusVisible by mutableStateOf(false)

    fun setAuxZoneFocused(zoneKey: Any, hasFocus: Boolean) {
        val changed = if (hasFocus) focusedAuxZones.add(zoneKey) else focusedAuxZones.remove(zoneKey)
        if (changed) contentAuxControlsFocused = focusedAuxZones.isNotEmpty()
    }

    fun updateSidebarItem(requester: FocusRequester, topPx: Float) {
        sidebarItems[requester] = topPx
    }

    fun removeSidebarItem(requester: FocusRequester) {
        sidebarItems.remove(requester)
    }

    fun requestSidebarFirst(): Boolean =
        activeSidebarRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestSidebarLast(): Boolean {
        val bottomMost = sidebarItems.entries.maxByOrNull { it.value }?.key
        if (bottomMost != null && runCatching { bottomMost.requestFocus() }.getOrDefault(false)) {
            return true
        }
        return requestSidebarFirst()
    }

    fun requestTitleBarFirst(): Boolean =
        activeTitleBarRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestTitleBarLast(): Boolean {
        if (activeTitleBarLastRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true) {
            return true
        }
        return requestTitleBarFirst()
    }

    fun requestToolbarLast(): Boolean =
        activeToolbarLastRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestPreviewFirst(): Boolean =
        activePreviewFirstRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestDrawerFirst(): Boolean =
        activeDrawerFirstRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestQuickLookFirst(): Boolean =
        activeQuickLookFirstRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true

    fun requestQuickLookLast(): Boolean {
        if (activeQuickLookBodyLastRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true) {
            return true
        }
        if (activeQuickLookLastRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true) {
            return true
        }
        return requestQuickLookFirst()
    }

    fun focusFileList(): Boolean =
        activeFileListRequester?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } == true
}

internal val DefaultFocusCoordinator = FocusCoordinator()
internal val LocalFocusCoordinator = staticCompositionLocalOf { DefaultFocusCoordinator }

@Composable
internal fun Modifier.trackContentAuxFocus(key: String): Modifier {
    val focusCoord = LocalFocusCoordinator.current
    val token = remember(key) { Any() }
    DisposableEffect(token) {
        onDispose { focusCoord.setAuxZoneFocused(token, false) }
    }
    return this.onFocusChanged { focusCoord.setAuxZoneFocused(token, it.hasFocus) }
}

/** Adds a high-contrast `2.dp` focus ring when a Compose control receives keyboard focus (`:focus-visible`). */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(14.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val keyboardMode = LocalFocusCoordinator.current.keyboardFocusVisible
    val cs = MaterialTheme.colorScheme
    this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused && keyboardMode) Modifier.border(2.dp, cs.primary, shape) else Modifier)
}

/**
 * Attaches keyboard focusability, a `2.dp` keyboard-mode focus ring, optional [FocusRequester] registration,
 * and `Enter` / `Spacebar` / `Menu` (`Shift+F10`) activation to custom controls built with [mouseGestures].
 */
fun Modifier.keyboardFocusableItem(
    shape: Shape = RoundedCornerShape(14.dp),
    canFocus: Boolean = true,
    allowSpacebar: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onKeyDown: ((KeyEvent) -> Boolean)? = null,
    onActivate: ((ctrl: Boolean, shift: Boolean) -> Unit)? = null,
    onContextMenu: ((Offset) -> Unit)? = null,
): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    val keyboardMode = LocalFocusCoordinator.current.keyboardFocusVisible
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val focusCb by rememberUpdatedState(onFocusChanged)
    val keyCb by rememberUpdatedState(onKeyDown)
    val activateCb by rememberUpdatedState(onActivate)
    val contextCb by rememberUpdatedState(onContextMenu)
    var isFocused by remember { mutableStateOf(false) }

    this
        .onGloballyPositioned { holder[0] = it }
        .then(if (isFocused && keyboardMode) Modifier.border(2.dp, cs.primary, shape) else Modifier)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .focusProperties { this.canFocus = canFocus }
        .onFocusChanged {
            isFocused = it.isFocused
            focusCb?.invoke(it.isFocused)
        }
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (keyCb?.invoke(e) == true) return@onKeyEvent true
            val ctrl = e.isCtrlPressed || e.isMetaPressed
            val shift = e.isShiftPressed
            when {
                (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter) && activateCb != null -> {
                    activateCb?.invoke(ctrl, shift)
                    true
                }
                allowSpacebar && e.key == Key.Spacebar && !ctrl && !e.isAltPressed && activateCb != null -> {
                    activateCb?.invoke(false, shift)
                    true
                }
                (e.key == Key.Menu || (e.key == Key.F10 && shift)) && contextCb != null -> {
                    val coords = holder[0]
                    val center = if (coords != null) Offset(coords.size.width / 2f, coords.size.height / 2f) else Offset.Zero
                    val rootPos = coords?.localToRoot(center) ?: Offset.Zero
                    contextCb?.invoke(rootPos)
                    true
                }
                else -> false
            }
        }
        .focusable()
}
