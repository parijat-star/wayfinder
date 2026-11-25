// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.R
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ViewMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class LiveSnackbarVisuals(
    initialMessage: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val key: String? = null,
) : SnackbarVisuals {
    var liveMessage by mutableStateOf(initialMessage)
    override val message: String get() = liveMessage
}

/**
 * Coordinates snackbar presentation without blocking the `UiEvent` collector.
 *
 * When a new toast shares a non-null [key] and [actionLabel] with the currently active snackbar,
 * its message is updated in place and its auto-dismiss timer is reset—preventing queueing and
 * crossfade flicker during rapid updates such as repeated zoom changes.
 */
internal class TransientSnackbarController(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
    private val resolveTimeoutMs: (durationMs: Long, hasAction: Boolean) -> Long = { ms, _ -> ms },
) {
    private var snackbarJob: Job? = null
    private var dismissJob: Job? = null
    private var activeVisuals: LiveSnackbarVisuals? = null

    fun show(
        message: String,
        actionLabel: String? = null,
        key: String? = null,
        durationMs: Long? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val current = activeVisuals
        if (key != null &&
            current != null &&
            current.key == key &&
            current.actionLabel == actionLabel &&
            snackbarJob?.isActive == true
        ) {
            current.liveMessage = message
            scheduleDismiss(current, durationMs)
            return
        }

        // Preserve active actionable snackbars (e.g. "Moved to Trash [Undo]") when a low-priority
        // ephemeral status toast (zoom / theme) fires, so the user never loses Undo affordance.
        if (current != null &&
            current.actionLabel != null &&
            snackbarJob?.isActive == true &&
            actionLabel == null &&
            durationMs != null
        ) {
            return
        }

        dismissJob?.cancel()
        snackbarJob?.cancel()
        hostState.currentSnackbarData?.dismiss()

        val visuals = LiveSnackbarVisuals(
            initialMessage = message,
            actionLabel = actionLabel,
            duration = if (durationMs != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
            key = key,
        )
        activeVisuals = visuals
        snackbarJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = hostState.showSnackbar(visuals)
                if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
            } finally {
                if (activeVisuals === visuals) {
                    activeVisuals = null
                    dismissJob?.cancel()
                    dismissJob = null
                }
            }
        }
        scheduleDismiss(visuals, durationMs)
    }

    private fun scheduleDismiss(visuals: LiveSnackbarVisuals, durationMs: Long?) {
        dismissJob?.cancel()
        dismissJob = if (durationMs != null) {
            scope.launch {
                if (hostState.currentSnackbarData?.visuals !== visuals) {
                    snapshotFlow { hostState.currentSnackbarData?.visuals }.first { it === visuals }
                }
                delay(resolveTimeoutMs(durationMs, visuals.actionLabel != null))
                if (hostState.currentSnackbarData?.visuals === visuals) {
                    hostState.currentSnackbarData?.dismiss()
                }
            }
        } else {
            null
        }
    }
}

private fun Modifier.reclaimFocus(vm: BrowserViewModel, fm: FocusManager, focusCoord: FocusCoordinator, root: FocusRequester): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (vm.state.value.textFocus) {
                fm.clearFocus()
                if (!focusCoord.focusFileList()) runCatching { root.requestFocus() }
            }
        }
    }

private fun moveFocusWithWrap(
    fm: FocusManager,
    focusCoord: FocusCoordinator,
    shift: Boolean,
    showSidebar: Boolean,
    fromFileList: Boolean,
): Boolean {
    if (!shift && fromFileList) {
        if (showSidebar && focusCoord.requestSidebarFirst()) return true
        if (focusCoord.requestTitleBarFirst()) return true
    }
    if (fm.moveFocus(if (shift) FocusDirection.Previous else FocusDirection.Next)) return true
    return if (shift) focusCoord.focusFileList() else focusCoord.requestTitleBarFirst()
}

@Composable
fun AppRoot(vm: BrowserViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val menu = remember { MenuController() }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onBluetoothPermissionResult(granted)
    }
    val drag = remember { DragController() }
    val windowDragId = remember(drag) { System.identityHashCode(drag) }
    CompositionLocalProvider(LocalWindowDragId provides windowDragId) {
    Box(Modifier.fillMaxSize().sameWindowDragBridge(drag, windowDragId)) {
    val snackbar = remember { SnackbarHostState() }
    val accessibilityManager = LocalAccessibilityManager.current
    val rootFocus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    var lastPointer by remember { mutableStateOf(Offset(400f, 300f)) }
    var typeBuffer by remember { mutableStateOf("") }
    var isCompactViewport by remember { mutableStateOf(false) }
    var isPreviewViewport by remember { mutableStateOf(false) }
    var typeTime by remember { mutableStateOf(0L) }
    val providedFocusCoord = LocalFocusCoordinator.current
    val focusCoord = remember(providedFocusCoord) {
        if (providedFocusCoord !== DefaultFocusCoordinator) providedFocusCoord else FocusCoordinator()
    }
    val inputModeManager = LocalInputModeManager.current
    var titleZoneFocused by remember { mutableStateOf(false) }
    var pickerZoneFocused by remember { mutableStateOf(false) }
    var toolbarZoneFocused by remember { mutableStateOf(false) }
    var sidebarZoneFocused by remember { mutableStateOf(false) }
    var previewZoneFocused by remember { mutableStateOf(false) }
    var drawerZoneFocused by remember { mutableStateOf(false) }
    var quickLookZoneFocused by remember { mutableStateOf(false) }
    var contentZoneFocused by remember { mutableStateOf(false) }

    val nonListFocused = titleZoneFocused ||
        pickerZoneFocused ||
        toolbarZoneFocused ||
        sidebarZoneFocused ||
        previewZoneFocused ||
        drawerZoneFocused ||
        quickLookZoneFocused ||
        focusCoord.contentAuxControlsFocused
    val anyChildFocused = nonListFocused || contentZoneFocused
    SideEffect { focusCoord.nonListZoneFocused = nonListFocused }
    var wasQuickLookOpen by remember { mutableStateOf(s.quickLook != null) }
    LaunchedEffect(s.quickLook != null) {
        val open = s.quickLook != null
        if (wasQuickLookOpen && !open) {
            quickLookZoneFocused = false
            focusCoord.focusFileList()
        }
        wasQuickLookOpen = open
    }

    // "Mount Cloud / SAF…": the system document-tree picker; access is persisted by the view model.
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::mountSaf) }
    val systemTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.data?.let(vm::mountSaf)
        }
    }
    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            vm.onGoogleAccountPicked(res.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME))
        }
    }
    val phoneImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data
            val uris = buildList {
                data?.data?.let { add(it) }
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { add(it) }
                    }
                }
            }.distinct()
            if (uris.isNotEmpty()) vm.importExternalUrisToDownloads(uris)
        }
    }
    SideEffect { drag.onDrop = { t, p, copy -> vm.dropOn(t, p, copy) } }
    LaunchedEffect(anyChildFocused, s.dialog == null, s.addressEditing, s.paletteOpen) {
        if (!anyChildFocused && s.dialog == null && !s.addressEditing && !s.paletteOpen) {
            if (!focusCoord.focusFileList()) runCatching { rootFocus.requestFocus() }
        }
    }
    BackHandler(enabled = s.active.canGoBack) { vm.back() }

    val currentAccessibilityManager by rememberUpdatedState(accessibilityManager)

    LaunchedEffect(Unit) {
        val toasts = TransientSnackbarController(snackbar, this) { durationMs, hasAction ->
            currentAccessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = durationMs,
                containsIcons = false,
                containsText = true,
                containsControls = hasAction,
            ) ?: durationMs
        }

        vm.events.collect { e ->
            when (e) {
                is UiEvent.OpenFile -> openWithSystem(ctx, e.path, e.chooser, e.uri)
                is UiEvent.Share -> shareFiles(ctx, e.paths, e.uris)
                is UiEvent.CopyText -> {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(e.what, e.text))
                    toasts.show("${e.what} copied")
                }
                is UiEvent.NewWindow -> openNewWindow(ctx, e.path)
                is UiEvent.OpenExternalUrl -> {
                    val uri = runCatching { android.net.Uri.parse(e.url) }.getOrNull()
                    val scheme = uri?.scheme?.lowercase()
                    if (uri != null && (scheme == "https" || scheme == "http")) {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            toasts.show("No browser available to open ${e.url}")
                        }
                    }
                }
                UiEvent.RequestBluetoothPermission -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    vm.onBluetoothPermissionResult(true)
                }
                is UiEvent.Toast -> {
                    toasts.show(
                        message = e.message,
                        actionLabel = if (e.undo) ctx.getString(R.string.action_undo) else null,
                        key = e.key,
                        durationMs = e.durationMs,
                        onAction = if (e.undo) vm::undo else null,
                    )
                }
                is UiEvent.PickDocumentTree -> try {
                    launchSystemDocumentTreePicker(
                        ctx = ctx,
                        initialUri = e.initialUri,
                        launchTargeted = { systemTreeLauncher.launch(it) },
                        fallbackLaunch = { treeLauncher.launch(e.initialUri) },
                    )
                } catch (_: ActivityNotFoundException) {
                    toasts.show("No document provider is available")
                }
                UiEvent.PickGoogleAccount -> try {
                    @Suppress("DEPRECATION")
                    val intent = android.accounts.AccountManager.newChooseAccountIntent(
                        null, null, arrayOf("com.google"), null, null, null, null,
                    )
                    accountLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.requestMountSaf()
                }
                is UiEvent.ImportPhoneDocuments -> try {
                    phoneImportLauncher.launch(buildSystemDocumentOpenFilePickerIntent(ctx, e.initialUri))
                } catch (_: ActivityNotFoundException) {
                    toasts.show("No document provider is available")
                }
                UiEvent.OpenSystemPhoneFiles -> {
                    val sysIntent = buildSystemPhoneFilesWindowIntent(ctx)
                    if (sysIntent != null) {
                        try {
                            ctx.startActivity(sysIntent)
                        } catch (_: ActivityNotFoundException) {
                            vm.requestImportFromPhone()
                        }
                    } else {
                        vm.requestImportFromPhone()
                    }
                }
                is UiEvent.PickResult -> returnPicked(ctx, e.paths)
                is UiEvent.PickTreeResult -> returnPickedTree(ctx, e.path, e.uri)
                is UiEvent.CreateDocumentResult -> returnCreatedDocument(ctx, e.path, e.uri, e.mimeType)
                UiEvent.PickCancel -> ctx.findActivity()?.let { a -> a.setResult(Activity.RESULT_CANCELED); a.finish() }
                UiEvent.Finish -> ctx.findActivity()?.let { a -> if (vm.state.value.secondaryWindow) a.finishAndRemoveTask() else a.finish() }
            }
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val isSystemPhoneKey = e.key == Key.Back || e.key == Key.VolumeUp || e.key == Key.VolumeDown || e.key == Key.VolumeMute
        if (!isSystemPhoneKey) {
            focusCoord.keyboardFocusVisible = true
            if (inputModeManager.inputMode != InputMode.Keyboard) {
                runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
            }
        }
        val st = vm.state.value
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val k = e.key
        val tab = st.active

        // The command palette handles its own keys (Ctrl+K toggles it closed).
        if (st.paletteOpen) {
            if (ctrl && !shift && k == Key.K) { vm.closePalette(); return true }
            return false
        }

        // Quick Look overlay owns the keyboard while open.
        if (st.quickLook != null) {
            if (!ctrl && !alt && k == Key.Tab) {
                if (!shift) {
                    if (!quickLookZoneFocused) {
                        focusCoord.requestQuickLookFirst()
                    } else {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (!moved || !quickLookZoneFocused) {
                            focusCoord.requestQuickLookFirst()
                        }
                    }
                } else {
                    if (!quickLookZoneFocused) {
                        focusCoord.requestQuickLookLast()
                    } else {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !quickLookZoneFocused) {
                            focusCoord.requestQuickLookLast()
                        }
                    }
                }
                return true
            }
            if (quickLookZoneFocused && !ctrl && !alt && (k == Key.Enter || k == Key.NumPadEnter || k == Key.Spacebar)) {
                return false
            }
            val cols = if (tab.viewMode == ViewMode.Grid) st.gridColumns else 1
            when (k) {
                Key.Spacebar, Key.Escape -> vm.closeQuickLook()
                Key.Enter, Key.NumPadEnter -> { vm.closeQuickLook(); vm.openSelection() }
                Key.DirectionLeft -> vm.quickLookMove(-1)
                Key.DirectionRight -> vm.quickLookMove(1)
                Key.DirectionUp -> vm.quickLookMove(-cols)
                Key.DirectionDown -> vm.quickLookMove(cols)
                else -> if (ctrl && k == Key.W) vm.closeQuickLook()
            }
            return true
        }

        // Shortcuts that work everywhere, even while typing.
        if (ctrl && !alt) {
            when {
                k == Key.T && !shift -> { vm.newTab(); return true }
                k == Key.T && shift -> { vm.reopenClosedTab(); return true }
                k == Key.W -> { vm.closeTab(st.activeId); return true }
                k == Key.Tab -> { vm.cycleTab(if (shift) -1 else 1); return true }
                k == Key.PageDown -> { vm.cycleTab(1); return true }
                k == Key.PageUp -> { vm.cycleTab(-1); return true }
                k == Key.L -> { vm.setAddressEditing(true); return true }
                k == Key.F || k == Key.E -> { vm.requestSearchFocus(); return true }
                k == Key.K && shift -> { vm.showConnectDialog(); return true }
                k == Key.K -> { vm.openPalette(); return true }
                k == Key.N && !shift -> { vm.newWindow(); return true }
                k == Key.Equals || k == Key.Plus || k == Key.NumPadAdd -> { vm.zoomBy(1); return true }
                k == Key.Minus || k == Key.NumPadSubtract -> { vm.zoomBy(-1); return true }
                k == Key.Zero || k == Key.NumPad0 -> { vm.zoomBy(0); return true }
            }
        }
        if (alt && !ctrl && !shift && digitIndex(k) >= 0) { vm.selectTabIndex(digitIndex(k)); return true }
        if (alt && !ctrl && k == Key.D) { vm.setAddressEditing(true); return true }
        if (k == Key.F1) { vm.showDialog(Dialog.Shortcuts); return true }
        if (st.dialog != null) return false

        val showSidebarNow = st.sidebarVisible && !isCompactViewport
        val showPreviewNow = st.previewVisible && isPreviewViewport
        val showDrawerNow = st.compactSidebarOpen && isCompactViewport

        // Handle Tab / Shift+Tab before textFocus early-return so single-line SearchField / AddressBar never trap Tab.
        if (!ctrl && !alt && k == Key.Tab) {
            if (showDrawerNow) {
                if (!shift) {
                    val moved = fm.moveFocus(FocusDirection.Next)
                    if (!moved || !drawerZoneFocused) {
                        if (!focusCoord.requestDrawerFirst()) focusCoord.requestSidebarFirst()
                    }
                } else {
                    val moved = fm.moveFocus(FocusDirection.Previous)
                    if (!moved || !drawerZoneFocused) {
                        if (!focusCoord.requestSidebarLast()) focusCoord.requestDrawerFirst()
                    }
                }
                return true
            }
            if (st.dualPane && !shift && !focusCoord.nonListZoneFocused && !st.textFocus) {
                vm.switchPane()
                return true
            }
            if (st.textFocus) {
                if (st.addressEditing) vm.setAddressEditing(false)
                vm.setTextFocus(false)
            }
            val wasSidebar = sidebarZoneFocused
            val wasTitle = titleZoneFocused
            val wasPicker = pickerZoneFocused
            val wasToolbar = toolbarZoneFocused
            val wasPreview = previewZoneFocused
            val wasContentAux = focusCoord.contentAuxControlsFocused
            val wasFileList = !focusCoord.nonListZoneFocused

            fun advanceAfterContentCard() {
                if (showPreviewNow && focusCoord.requestPreviewFirst()) return
                if (showSidebarNow && focusCoord.requestSidebarFirst()) return
                focusCoord.requestTitleBarFirst()
            }

            if (!shift) {
                when {
                    wasFileList -> {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (moved && focusCoord.contentAuxControlsFocused) return true
                        if (moved && showPreviewNow && previewZoneFocused) return true
                        advanceAfterContentCard()
                    }
                    wasContentAux -> {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (!moved || (!focusCoord.contentAuxControlsFocused && !contentZoneFocused)) {
                            if (!(moved && showPreviewNow && previewZoneFocused)) {
                                advanceAfterContentCard()
                            }
                        }
                    }
                    wasPreview -> {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (!moved || !previewZoneFocused) {
                            if (!showSidebarNow || !focusCoord.requestSidebarFirst()) {
                                focusCoord.requestTitleBarFirst()
                            }
                        }
                    }
                    wasSidebar -> {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (!moved || !sidebarZoneFocused) {
                            focusCoord.requestTitleBarFirst()
                        }
                    }
                    wasToolbar -> {
                        val moved = fm.moveFocus(FocusDirection.Next)
                        if (!moved || !toolbarZoneFocused) {
                            if (!focusCoord.focusFileList()) {
                                focusCoord.requestTitleBarFirst()
                            }
                        }
                    }
                    else -> {
                        if (!fm.moveFocus(FocusDirection.Next)) {
                            focusCoord.focusFileList()
                        }
                    }
                }
            } else {
                when {
                    wasFileList -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !focusCoord.contentAuxControlsFocused) {
                            if (!focusCoord.requestToolbarLast()) {
                                focusCoord.requestTitleBarLast()
                            }
                        }
                    }
                    wasContentAux -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || (!focusCoord.contentAuxControlsFocused && !contentZoneFocused)) {
                            if (!focusCoord.requestToolbarLast()) {
                                focusCoord.requestTitleBarLast()
                            }
                        }
                    }
                    wasToolbar -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || (!toolbarZoneFocused && !pickerZoneFocused)) {
                            if (!focusCoord.requestTitleBarLast()) {
                                focusCoord.focusFileList()
                            }
                        }
                    }
                    wasPicker -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !pickerZoneFocused) {
                            if (!focusCoord.requestTitleBarLast()) {
                                focusCoord.focusFileList()
                            }
                        }
                    }
                    wasTitle -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !titleZoneFocused) {
                            when {
                                showSidebarNow && focusCoord.requestSidebarLast() -> Unit
                                showPreviewNow && focusCoord.requestPreviewFirst() -> Unit
                                else -> focusCoord.focusFileList()
                            }
                        }
                    }
                    wasSidebar -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !sidebarZoneFocused) {
                            if (!showPreviewNow || !focusCoord.requestPreviewFirst()) {
                                focusCoord.focusFileList()
                            }
                        }
                    }
                    wasPreview -> {
                        val moved = fm.moveFocus(FocusDirection.Previous)
                        if (!moved || !previewZoneFocused) {
                            focusCoord.focusFileList()
                        }
                    }
                    else -> {
                        if (!fm.moveFocus(FocusDirection.Previous)) {
                            focusCoord.requestTitleBarLast()
                        }
                    }
                }
            }
            return true
        }

        if (st.textFocus) return false

        val columns = tab.viewMode == ViewMode.Columns
        if (ctrl && !alt) {
            val di = digitIndex(k)
            when {
                !shift && di == 0 -> { vm.setViewMode(ViewMode.Details); return true }
                !shift && di == 1 -> { vm.setViewMode(ViewMode.Grid); return true }
                !shift && di == 2 -> { vm.setViewMode(ViewMode.Columns); return true }
                !shift && di == 3 -> { vm.toggleDualPane(); return true }
                k == Key.A && !shift -> { vm.selectAll(); return true }
                k == Key.A -> { vm.clearSelection(); return true }
                k == Key.C && shift -> { vm.copyPathToClipboard(); return true }
                k == Key.C -> { vm.copy(); return true }
                k == Key.X -> { vm.cut(); return true }
                k == Key.V -> { vm.paste(); return true }
                k == Key.Z && shift -> { vm.redo(); return true }
                k == Key.Z -> { vm.undo(); return true }
                k == Key.Y -> { vm.redo(); return true }
                k == Key.I && shift -> { vm.invertSelection(); return true }
                k == Key.I -> { vm.showProperties(); return true }
                k == Key.H -> { vm.toggleHidden(); return true }
                k == Key.R && shift -> { vm.requestBatchRename(); return true }
                k == Key.R -> { vm.refresh(); return true }
                k == Key.Backslash -> { if (isCompactViewport) vm.toggleCompactSidebar() else vm.toggleSidebar(); return true }
                k == Key.B -> {
                    val targets = tab.selectedEntries(st.showHidden).filter { !it.isServer && !it.isShare && !FsPath.isZip(it.path) }.map { it.path }
                    if (targets.isNotEmpty()) vm.togglePins(targets) else vm.togglePin(tab.path)
                    return true
                }
                k == Key.D && !shift -> { vm.duplicate(); return true }
                k == Key.N && shift -> { vm.requestNewItem(true); return true }
                k == Key.M && shift -> { vm.requestNewItem(false); return true }
                k == Key.Enter || k == Key.NumPadEnter -> { vm.openSelection(newTab = true); return true }
                k == Key.Spacebar -> { vm.toggleFocusedSelection(); return true }
                k == Key.DirectionDown || k == Key.DirectionUp || k == Key.DirectionLeft || k == Key.DirectionRight -> {
                    if (focusCoord.nonListZoneFocused) return false
                    val cols = if (tab.viewMode == ViewMode.Grid) st.gridColumns else 1
                    val d = when (k) { Key.DirectionDown -> cols; Key.DirectionUp -> -cols; Key.DirectionRight -> 1; else -> -1 }
                    if (cols == 1 && (k == Key.DirectionLeft || k == Key.DirectionRight)) return false
                    vm.moveFocus(d, extend = shift, keepSelection = true); return true
                }
            }
            return false
        }
        if (alt && ctrl) {
            if (k == Key.N) { vm.requestNewItem(false); return true }
            return false
        }
        if (alt) {
            when (k) {
                Key.DirectionLeft -> vm.back()
                Key.DirectionRight -> vm.forward()
                Key.DirectionUp -> vm.up()
                Key.P -> vm.togglePreview()
                Key.L -> if (shift) vm.locateInTree() else return false
                Key.Enter, Key.NumPadEnter -> vm.showProperties()
                else -> return false
            }
            return true
        }

        // When keyboard focus is inside TitleBar, Toolbar, Sidebar, PreviewPane, or CompactSidebarDrawer,
        // let focused controls receive Enter, Spacebar, Arrow keys, and Menu natively.
        if (focusCoord.nonListZoneFocused) {
            when (k) {
                Key.F2 -> { vm.requestRename(); return true }
                Key.F3 -> { vm.toggleDualPane(); return true }
                Key.F5 -> { if (st.dualPane) vm.transferToOtherPane(move = false) else vm.refresh(); return true }
                Key.F6 -> {
                    when {
                        sidebarZoneFocused -> focusCoord.requestTitleBarFirst()
                        titleZoneFocused -> fm.moveFocus(FocusDirection.Down)
                        else -> focusCoord.focusFileList()
                    }
                    return true
                }
                Key.Escape -> {
                    if (st.compactSidebarOpen) vm.setCompactSidebarOpen(false) else focusCoord.focusFileList()
                    return true
                }
                else -> return false
            }
        }

        val cols = if (tab.viewMode == ViewMode.Grid) st.gridColumns else 1
        when (k) {
            Key.F2 -> vm.requestRename()
            Key.F3 -> vm.toggleDualPane()
            Key.F5 -> if (st.dualPane) vm.transferToOtherPane(move = false) else vm.refresh()
            Key.F6 -> if (st.dualPane) {
                if (tab.selection.isNotEmpty()) vm.transferToOtherPane(move = true) else vm.switchPane()
            } else {
                return moveFocusWithWrap(fm, focusCoord, shift, showSidebarNow, fromFileList = true)
            }
            Key.Delete -> vm.requestDelete(permanent = shift)
            Key.Enter, Key.NumPadEnter -> vm.openSelection()
            Key.Spacebar -> vm.toggleQuickLook()
            Key.Backspace, Key.Back -> vm.back()
            Key.Forward -> vm.forward()
            Key.Escape -> if (st.compactSidebarOpen) vm.setCompactSidebarOpen(false) else if (tab.selection.isNotEmpty()) vm.clearSelection() else if (tab.query.isNotEmpty()) vm.setQuery("") else return false
            Key.DirectionDown -> vm.moveFocus(cols, shift)
            Key.DirectionUp -> vm.moveFocus(-cols, shift)
            Key.DirectionRight -> if (columns) vm.columnEnter() else if (cols > 1) vm.moveFocus(1, shift) else return false
            Key.DirectionLeft -> if (columns) vm.up() else if (cols > 1) vm.moveFocus(-1, shift) else return false
            Key.PageDown -> vm.moveFocus(st.pageSize.coerceAtLeast(1) * cols, shift)
            Key.PageUp -> vm.moveFocus(-st.pageSize.coerceAtLeast(1) * cols, shift)
            Key.MoveHome -> vm.focusEdge(false, shift)
            Key.MoveEnd -> vm.focusEdge(true, shift)
            Key.Menu -> {
                menu.show(lastPointer, tab.selectedEntries(st.showHidden).firstOrNull()?.let { fileMenu(vm, st, it) } ?: backgroundMenu(vm, st))
            }
            else -> {
                val cp = e.utf16CodePoint
                if (cp > 32 && !Character.isISOControl(cp)) {
                    val now = System.currentTimeMillis()
                    typeBuffer = if (now - typeTime > 900) cp.toChar().toString() else typeBuffer + cp.toChar()
                    typeTime = now
                    vm.typeAhead(typeBuffer)
                } else return false
            }
        }
        return true
    }

    // Interface zoom (Ctrl+= / Ctrl+- / Ctrl+0) scales everything by overriding the density.
    val baseDensity = LocalDensity.current
    val zoomed = remember(baseDensity, s.zoom) { Density(baseDensity.density * s.zoom, baseDensity.fontScale) }
    CompositionLocalProvider(LocalMenu provides menu, LocalDrag provides drag, LocalFocusCoordinator provides focusCoord, LocalDensity provides zoomed) {
        Box(
            Modifier.fillMaxSize().background(cs.surfaceContainerLow)
                .onPreviewKeyEvent(::onKey)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.firstOrNull()?.let { ch ->
                                lastPointer = ch.position
                                if (ch.pressed && focusCoord.keyboardFocusVisible) {
                                    focusCoord.keyboardFocusVisible = false
                                }
                            }
                        }
                    }
                },
        ) {
            // Leaf fallback focus target active only before a child zone mounts, never visited during Tab traversal.
            Box(
                Modifier.size(0.dp)
                    .focusRequester(rootFocus)
                    .focusProperties { canFocus = !anyChildFocused }
                    .focusable(),
            )
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ),
            ) {
                Box(Modifier.onFocusChanged { titleZoneFocused = it.hasFocus }.reclaimFocus(vm, fm, focusCoord, rootFocus)) {
                    TitleBar(vm, s) { off, items -> menu.show(off, items) }
                }
                s.picker?.let {
                    Box(Modifier.onFocusChanged { pickerZoneFocused = it.hasFocus }) {
                        PickerBanner(vm, s)
                    }
                }
                Box(Modifier.onFocusChanged { toolbarZoneFocused = it.hasFocus }) {
                    Toolbar(vm, s)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().reclaimFocus(vm, fm, focusCoord, rootFocus)) {
                    val compact = maxWidth < 600.dp
                    val previewFits = maxWidth >= 900.dp
                    SideEffect {
                        isCompactViewport = compact
                        isPreviewViewport = previewFits
                    }
                    LaunchedEffect(compact) {
                        if (!compact && s.compactSidebarOpen) vm.setCompactSidebarOpen(false)
                    }
                    val showSidebar = s.sidebarVisible && !compact
                    val showPreview = s.previewVisible && previewFits
                    val hasDesktopCaption = WindowInsets.captionBar.getTop(LocalDensity.current) > 0
                    val touchLongPressSelectsOnly = compact && !hasDesktopCaption
                    LaunchedEffect(showSidebar) { if (!showSidebar) sidebarZoneFocused = false }
                    LaunchedEffect(showPreview) { if (!showPreview) previewZoneFocused = false }
                    CompositionLocalProvider(LocalTouchLongPressSelectsOnly provides touchLongPressSelectsOnly) {
                    Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
                        if (showSidebar) {
                            Box(Modifier.onFocusChanged { sidebarZoneFocused = it.hasFocus }) {
                                Sidebar(vm, s, Modifier.width(s.sidebarWidth.dp).fillMaxSize())
                            }
                            SidebarHandle(s.sidebarWidth) { vm.setSidebarWidth(it) }
                        } else Box(Modifier.width(8.dp))
                        ContentCard(vm, s, Modifier.weight(1f).fillMaxSize().onFocusChanged { contentZoneFocused = it.hasFocus })
                        if (showPreview) {
                            Box(Modifier.width(8.dp))
                            Box(Modifier.onFocusChanged { previewZoneFocused = it.hasFocus }) {
                                PreviewPane(vm, s, Modifier.width(300.dp).fillMaxSize())
                            }
                        }
                    }
                    }
                }
            }
            if (isCompactViewport && s.compactSidebarOpen) {
                Box(Modifier.onFocusChanged { drawerZoneFocused = it.hasFocus }) {
                    CompactSidebarDrawer(vm, s)
                }
            } else if (drawerZoneFocused) {
                SideEffect { drawerZoneFocused = false }
            }
            Box(Modifier.onFocusChanged { quickLookZoneFocused = it.hasFocus }) {
                QuickLook(vm, s)
            }
            if (s.paletteOpen) CommandPalette(vm, s)
            MenuHost(menu)
            Dialogs(vm, s)
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.ime)
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(start = 16.dp, end = 16.dp, bottom = 44.dp),
            ) { data ->
                val hasAction = data.visuals.actionLabel != null
                Surface(
                    shape = SnackbarDefaults.shape,
                    color = SnackbarDefaults.color,
                    contentColor = SnackbarDefaults.contentColor,
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 420.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = if (hasAction) 8.dp else 16.dp,
                            top = if (hasAction) 4.dp else 12.dp,
                            bottom = if (hasAction) 4.dp else 12.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(vertical = if (hasAction) 8.dp else 0.dp),
                        )
                        data.visuals.actionLabel?.let { label ->
                            TextButton(
                                onClick = { data.performAction() },
                                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            if (drag.active) DragGhost(drag)
        }
    }
    }
    }
}

@Composable
private fun CompactSidebarDrawer(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val focusCoord = LocalFocusCoordinator.current
    val closeDrawerRequester = remember { FocusRequester() }
    val dismissInteraction = remember { MutableInteractionSource() }
    val drawerA11yLabel = stringResource(R.string.nav_drawer_label)
    val closeDrawerDesc = stringResource(R.string.nav_close_sidebar)
    DisposableEffect(closeDrawerRequester) {
        focusCoord.activeDrawerFirstRequester = closeDrawerRequester
        onDispose {
            if (focusCoord.activeDrawerFirstRequester === closeDrawerRequester) {
                focusCoord.activeDrawerFirstRequester = null
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!focusCoord.requestDrawerFirst()) focusCoord.requestSidebarFirst()
    }
    BackHandler(enabled = true) { vm.setCompactSidebarOpen(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(304.dp, maxWidth * 0.86f)
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = dismissInteraction, indication = null) {
                    vm.setCompactSidebarOpen(false)
                },
        )
        Surface(
            modifier = Modifier.width(drawerWidth).fillMaxHeight().semantics { contentDescription = drawerA11yLabel },
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            color = cs.surfaceContainerLow,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.drawer_app_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                    )
                    ActionTooltip(closeDrawerDesc) {
                        IconButton(
                            { vm.setCompactSidebarOpen(false) },
                            Modifier.size(48.dp).focusRequester(closeDrawerRequester).focusRing(CircleShape),
                        ) {
                            Icon(Icons.Outlined.Close, closeDrawerDesc)
                        }
                    }
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                Sidebar(vm, s, Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp))
            }
        }
    }
}

private fun digitIndex(k: Key): Int = when (k) {
    Key.One -> 0; Key.Two -> 1; Key.Three -> 2; Key.Four -> 3; Key.Five -> 4
    Key.Six -> 5; Key.Seven -> 6; Key.Eight -> 7; Key.Nine -> 8
    else -> -1
}

@Composable
private fun DragGhost(drag: DragController) {
    val cs = MaterialTheme.colorScheme
    val target = drag.hover
    Surface(
        Modifier.offset { IntOffset(drag.pointer.x.toInt() + 16, drag.pointer.y.toInt() + 16) },
        shape = RoundedCornerShape(20.dp), color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (drag.copyMode) Icons.Outlined.ContentCopy else Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(18.dp))
            val n = drag.paths.size
            val what = if (n == 1) FsPath.name(drag.paths[0]) else "$n items"
            val verb = if (target == FsPath.TRASH_ROOT) "Move to Trash" else if (drag.copyMode) "Copy" else "Move"
            Text(if (target != null) "$verb $what to ${FsPath.name(target)}" else what, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Picker mode banner: supports OpenFile, OpenTree (ACTION_OPEN_DOCUMENT_TREE), and CreateDocument (ACTION_CREATE_DOCUMENT). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerBanner(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val req = s.picker ?: return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 560.dp
            val btnMod = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(20.dp))
            val buttons: @Composable () -> Unit = {
                when (req.mode) {
                    com.droid.wayfinder.data.PickerMode.OpenFile -> {
                        val pickable = remember(s.active.selection, s.active.entries) { vm.pickableSelection() }
                        OutlinedButton({ vm.cancelPick() }, modifier = btnMod) { Text("Cancel", maxLines = 1, softWrap = false) }
                        Button({ vm.pickSelection() }, modifier = btnMod, enabled = pickable.isNotEmpty()) {
                            Text(if (pickable.size > 1) "Select ${pickable.size} Files" else "Select File", maxLines = 1, softWrap = false)
                        }
                    }
                    com.droid.wayfinder.data.PickerMode.OpenTree -> {
                        val folderTarget = remember(s.active.path, s.active.selection, s.active.entries) { vm.targetTreeFolder() }
                        val canGrant = remember(folderTarget) { vm.canPickFolder(folderTarget) }
                        OutlinedButton({ vm.cancelPick() }, modifier = btnMod) { Text("Cancel", maxLines = 1, softWrap = false) }
                        Button({ vm.pickCurrentFolder() }, modifier = btnMod, enabled = canGrant) {
                            Text("Use this folder", maxLines = 1, softWrap = false)
                        }
                    }
                    com.droid.wayfinder.data.PickerMode.CreateDocument -> {
                        val validName = remember(s.createFileName) { FsPath.nameError(s.createFileName.trim()) == null }
                        OutlinedButton({ vm.cancelPick() }, modifier = btnMod) { Text("Cancel", maxLines = 1, softWrap = false) }
                        Button({ vm.confirmCreateDocument() }, modifier = btnMod, enabled = validName) {
                            Text("Save", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            val body: @Composable (Modifier) -> Unit = { mod ->
                when (req.mode) {
                    com.droid.wayfinder.data.PickerMode.OpenFile -> Column(mod) {
                        Text(if (req.allowMultiple) "Select files to open in the requesting app" else "Select a file to open in the requesting app", style = MaterialTheme.typography.titleSmall)
                        val hint = listOfNotNull(req.description?.let { "Accepts $it" }, if (req.allowMultiple) "Ctrl+click to select several" else null, "Double-click a file to choose it")
                        Text(hint.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                    }
                    com.droid.wayfinder.data.PickerMode.OpenTree -> {
                        val folderTarget = remember(s.active.path, s.active.selection, s.active.entries) { vm.targetTreeFolder() }
                        Column(mod) {
                            Text("Grant access to folder", style = MaterialTheme.typography.titleSmall)
                            Text("Allow the requesting app to access ${FsPath.name(folderTarget)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    com.droid.wayfinder.data.PickerMode.CreateDocument -> Column(mod) {
                        Text("Save file in ${FsPath.name(s.active.path)}", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = s.createFileName,
                            onValueChange = vm::setCreateFileName,
                            singleLine = true,
                            label = { Text("File name") },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(20.dp))
                        body(Modifier.weight(1f))
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { buttons() }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(20.dp))
                    body(Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}

/**
 * Builds an `ACTION_OPEN_DOCUMENT` picker intent routed explicitly to system `DocumentsUI`
 * (never re-entering Wayfinder itself) so 3P installs on `user` builds can select and import
 * files from `QuickAccessDocumentsProvider` even when the provider omits `FLAG_SUPPORTS_IS_CHILD`.
 */
internal fun buildSystemDocumentOpenFilePickerIntent(ctx: Context, initialUri: android.net.Uri?): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (initialUri != null && android.os.Build.VERSION.SDK_INT >= 26) {
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
    }
    val candidates = runCatching { ctx.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val systemDocUi = candidates.firstOrNull { ri ->
        val pkg = ri.activityInfo?.packageName.orEmpty()
        pkg != ctx.packageName && (pkg.contains("documentsui", ignoreCase = true) || pkg.contains("ScopedStorage", ignoreCase = true))
    } ?: candidates.firstOrNull { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
    val ai = systemDocUi?.activityInfo
    if (ai != null && ai.packageName.isNotBlank() && ai.name.isNotBlank()) {
        intent.setClassName(ai.packageName, ai.name)
    }
    return intent
}

/**
 * Builds an `ACTION_VIEW` intent targeting system `DocumentsUI` (`FilesActivity`) on the wireless
 * Cross-Device `QuickAccessDocumentsProvider` roots URI (`vnd.android.document/root`) in a side-by-side
 * window. Unlike `PickActivity`, `FilesActivity.ActionHandler` explicitly supports 1-segment `isRootsUri`
 * via `loadFirstRoot(uri)`, opening directly inside the connected phone even when its `rootId` UUID rotates.
 */
internal fun buildSystemPhoneFilesWindowIntent(ctx: Context): Intent? {
    val rootsUri = android.provider.DocumentsContract.buildRootsUri(
        com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(rootsUri, android.provider.DocumentsContract.Root.MIME_TYPE_ITEM)
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    val candidates = runCatching { ctx.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val systemDocUi = candidates.firstOrNull { ri ->
        val pkg = ri.activityInfo?.packageName.orEmpty()
        pkg != ctx.packageName && (pkg.contains("documentsui", ignoreCase = true) || pkg.contains("ScopedStorage", ignoreCase = true))
    } ?: candidates.firstOrNull { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
    val ai = systemDocUi?.activityInfo ?: return null
    if (ai.packageName.isBlank() || ai.name.isBlank()) return null
    intent.setClassName(ai.packageName, ai.name)
    return intent
}

/** Resolves [path] to a canonical file and rejects symbolic links so FileProvider cannot be bypassed via links. */
private fun safeFileProviderTarget(path: String): File {
    if (com.droid.wayfinder.data.FileInfo.isSymlink(path)) {
        throw IllegalArgumentException("Symbolic links cannot be shared with external apps")
    }
    return File(path).canonicalFile
}

internal fun buildSystemDocumentTreePickerIntent(ctx: Context, initialUri: android.net.Uri?): Intent? {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (initialUri != null && android.os.Build.VERSION.SDK_INT >= 26) {
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
    }
    val candidates = runCatching { ctx.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val systemDocUi = candidates.firstOrNull { ri ->
        val pkg = ri.activityInfo?.packageName.orEmpty()
        pkg != ctx.packageName && (pkg.contains("documentsui", ignoreCase = true) || pkg.contains("ScopedStorage", ignoreCase = true))
    } ?: candidates.firstOrNull { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
    val ai = systemDocUi?.activityInfo
    if (ai != null && ai.packageName.isNotBlank() && ai.name.isNotBlank()) {
        intent.setClassName(ai.packageName, ai.name)
        return intent
    }
    return null
}

private fun launchSystemDocumentTreePicker(
    ctx: Context,
    initialUri: android.net.Uri?,
    launchTargeted: (Intent) -> Unit,
    fallbackLaunch: () -> Unit,
) {
    val targeted = buildSystemDocumentTreePickerIntent(ctx, initialUri)
    if (targeted != null) {
        launchTargeted(targeted)
    } else {
        fallbackLaunch()
    }
}

internal fun hasSelfReadUriGrant(
    ctx: Context,
    uri: android.net.Uri,
    requirePersistable: Boolean = false,
): Boolean = hasSelfUriGrant(ctx, uri, write = false, requirePersistable = requirePersistable)

internal fun hasSelfWriteUriGrant(
    ctx: Context,
    uri: android.net.Uri,
    requirePersistable: Boolean = false,
): Boolean = hasSelfUriGrant(ctx, uri, write = true, requirePersistable = requirePersistable)

private fun isPathSegmentPrefix(prefix: List<String>, target: List<String>): Boolean {
    if (prefix.isEmpty() || target.size < prefix.size) return false
    for (i in prefix.indices) {
        if (prefix[i] != target[i]) return false
    }
    return true
}

private fun hasSelfUriGrant(
    ctx: Context,
    uri: android.net.Uri,
    write: Boolean,
    requirePersistable: Boolean = false,
): Boolean {
    val matchingPersisted = runCatching {
        ctx.contentResolver.persistedUriPermissions.filter { perm ->
            uri == perm.uri || (
                uri.scheme == perm.uri.scheme &&
                    uri.authority == perm.uri.authority &&
                    runCatching { android.provider.DocumentsContract.isTreeUri(perm.uri) }.getOrDefault(false) &&
                    isPathSegmentPrefix(perm.uri.pathSegments, uri.pathSegments)
                )
        }
    }.getOrDefault(emptyList())
    if (matchingPersisted.any { perm -> if (write) perm.isWritePermission else perm.isReadPermission }) {
        return true
    }
    if (matchingPersisted.isNotEmpty() && android.os.Build.FINGERPRINT == "robolectric") {
        return false
    }
    if (uri.authority == com.droid.wayfinder.data.SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY &&
        !android.provider.DocumentsContract.isTreeUri(uri)
    ) {
        return false
    }
    var modeFlag = if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
    if (requirePersistable && android.os.Build.FINGERPRINT != "robolectric") {
        modeFlag = modeFlag or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
    return ctx.checkUriPermission(
        uri,
        android.os.Process.myPid(),
        android.os.Process.myUid(),
        modeFlag,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun isStagedCacheFile(ctx: Context, file: File): Boolean {
    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.path)
    val cacheRoot = runCatching { ctx.cacheDir.canonicalPath }.getOrDefault(ctx.cacheDir.path)
    return FsPath.isWithin(cacheRoot, canonical) || FsPath.isWithin(ctx.cacheDir.path, file.path)
}

internal fun resolveReturnUri(
    ctx: Context,
    path: String,
    preferredUri: android.net.Uri? = null,
    requireWrite: Boolean = false,
): Pair<android.net.Uri, Boolean> {
    if (!path.startsWith("doc://") && !path.startsWith("saf://") && com.droid.wayfinder.data.FileInfo.isSymlink(path)) {
        throw IllegalArgumentException("Symbolic links cannot be shared with external apps")
    }
    fun hasRequiredGrant(uri: android.net.Uri): Boolean =
        hasSelfReadUriGrant(ctx, uri) && (!requireWrite || hasSelfWriteUriGrant(ctx, uri))

    if (preferredUri != null && hasRequiredGrant(preferredUri)) {
        return preferredUri to true
    }
    if (FsPath.isLocalFile(path)) {
        val treeDocUri = com.droid.wayfinder.data.LocalUriResolver.findPersistedTreeDocumentUri(
            ctx = ctx,
            path = path,
            requireWrite = requireWrite,
        )
        if (treeDocUri != null && hasRequiredGrant(treeDocUri)) {
            return treeDocUri to true
        }
    }
    val docUri = com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(path, tree = false)
    if (docUri != null && hasRequiredGrant(docUri)) {
        return docUri to true
    }
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", safeFileProviderTarget(path)) to false
}

private fun finishPickerSafely(ctx: Context, activity: Activity, errorPrefix: String) {
    try {
        activity.finish()
    } catch (e: SecurityException) {
        android.widget.Toast.makeText(ctx, "$errorPrefix: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        activity.setResult(Activity.RESULT_CANCELED)
        runCatching { activity.finish() }
    }
}

/** Returns picked files to the calling app as DocumentsContract URIs (or FileProvider fallback) with valid URI grants. */
internal fun returnPicked(ctx: Context, paths: List<String>) {
    val a = ctx.findActivity() ?: return
    if (paths.isEmpty()) return
    try {
        val resolved = paths.map { it to resolveReturnUri(ctx, it) }
        val uris = resolved.map { it.second.first }
        val allDocUris = resolved.all { it.second.second }
        val allWritable = resolved.all { (p, pair) ->
            val (uri, isDoc) = pair
            val diskWritable = !FsPath.isLocalFile(p) || File(p).canWrite()
            diskWritable && (!isDoc || hasSelfWriteUriGrant(ctx, uri))
        }
        val mime = if (paths.size == 1) {
            com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(paths[0]) ?: "application/octet-stream"
        } else {
            "*/*"
        }
        val clip = ClipData(ClipDescription("Selected files", arrayOf(mime)), ClipData.Item(uris[0]))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (allWritable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        val allPersistable = allDocUris && resolved.all { (_, pair) ->
            val (u, _) = pair
            hasSelfReadUriGrant(ctx, u, requirePersistable = true) &&
                (!allWritable || hasSelfWriteUriGrant(ctx, u, requirePersistable = true))
        }
        if (allPersistable) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        val result = Intent().setDataAndType(uris[0], mime).addFlags(flags)
        result.clipData = clip
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't share the selected file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    finishPickerSafely(ctx, a, "Couldn't share the selected file")
}

internal fun returnPickedTree(ctx: Context, path: String, preferredUri: android.net.Uri?) {
    val a = ctx.findActivity() ?: return
    try {
        val candidateDocUri = preferredUri
            ?: com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(path, tree = true)
        val hasDocGrant = candidateDocUri != null && hasSelfReadUriGrant(ctx, candidateDocUri)
        val treeUri = if (candidateDocUri != null && hasDocGrant) {
            candidateDocUri
        } else {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", safeFileProviderTarget(path))
        }
        val writable = if (hasDocGrant) hasSelfWriteUriGrant(ctx, treeUri) else (!FsPath.isLocalFile(path) || File(path).canWrite())
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        val persistable = hasDocGrant &&
            hasSelfReadUriGrant(ctx, treeUri, requirePersistable = true) &&
            (!writable || hasSelfWriteUriGrant(ctx, treeUri, requirePersistable = true))
        if (hasDocGrant) {
            flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                (if (persistable) Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else 0)
        }
        val result = Intent().setData(treeUri).addFlags(flags)
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't grant folder access: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    finishPickerSafely(ctx, a, "Couldn't grant folder access")
}

internal fun returnCreatedDocument(ctx: Context, path: String, preferredUri: android.net.Uri?, mimeType: String) {
    val a = ctx.findActivity() ?: return
    try {
        val (uri, isDocUri) = resolveReturnUri(ctx, path, preferredUri, requireWrite = true)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (isDocUri &&
            hasSelfReadUriGrant(ctx, uri, requirePersistable = true) &&
            hasSelfWriteUriGrant(ctx, uri, requirePersistable = true)
        ) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        val result = Intent().setDataAndType(uri, mimeType).addFlags(flags)
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't save file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    finishPickerSafely(ctx, a, "Couldn't save file")
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext?.findActivity()
    else -> null
}

/** Opens a new independent window (own task and view model), optionally showing [path]. */
private fun openNewWindow(ctx: Context, path: String?) {
    val i = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
    if (path != null) i.putExtra(BrowserViewModel.EXTRA_PATH, path)
    com.droid.wayfinder.IntentGuard.sign(ctx, i)
    try {
        ctx.startActivity(i)
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't open a new window", android.widget.Toast.LENGTH_SHORT).show()
    }
}

internal fun buildOpenWithSystemIntent(
    ctx: Context,
    path: String,
    preferredUri: android.net.Uri? = null,
    forceFileProvider: Boolean = false,
    allowSyncLookup: Boolean = true,
    allowMediaStore: Boolean = true,
): Intent? {
    val localTarget: File? = if (FsPath.isLocalFile(path)) {
        try {
            safeFileProviderTarget(path)
        } catch (_: Exception) {
            return null
        }
    } else {
        null
    }

    val candidateUri: android.net.Uri? = when {
        forceFileProvider -> null
        preferredUri != null -> preferredUri.takeUnless { !allowMediaStore && com.droid.wayfinder.data.LocalUriResolver.isMediaUri(it) }
        allowSyncLookup && localTarget != null && !isStagedCacheFile(ctx, localTarget) ->
            com.droid.wayfinder.data.LocalUriResolver.resolvePreferredLocalUriSync(ctx, localTarget.path)
                ?.takeUnless { !allowMediaStore && com.droid.wayfinder.data.LocalUriResolver.isMediaUri(it) }
        FsPath.isSaf(path) || FsPath.isDoc(path) ->
            com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(path, tree = false)
        else -> null
    }

    if (candidateUri != null && com.droid.wayfinder.data.LocalUriResolver.isMediaUri(candidateUri)) {
        if (com.droid.wayfinder.data.LocalUriResolver.canGrantMediaUri(ctx, candidateUri, write = false)) {
            val queriedMime = runCatching { ctx.contentResolver.getType(candidateUri) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "*/*" }
            val mime = queriedMime
                ?: com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(localTarget?.name ?: path)
                ?: "*/*"
            val writable = (localTarget == null || (localTarget.canWrite() && !isStagedCacheFile(ctx, localTarget))) &&
                mime != MainActivity.APK_MIME &&
                !MainActivity.isApkName(localTarget?.name ?: FsPath.name(path)) &&
                com.droid.wayfinder.data.LocalUriResolver.canGrantMediaUri(ctx, candidateUri, write = true)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(candidateUri, mime)
                .addFlags(flags)
                .apply { clipData = ClipData.newRawUri("", candidateUri) }
        }
        if (!FsPath.isLocalFile(path)) return null
    } else if (candidateUri != null || FsPath.isSaf(path) || FsPath.isDoc(path)) {
        val docUri = candidateUri
        if (docUri != null && hasSelfReadUriGrant(ctx, docUri)) {
            var queriedMime: String? = null
            var providerSupportsWrite = true
            runCatching {
                ctx.contentResolver.query(
                    docUri,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                        android.provider.DocumentsContract.Document.COLUMN_FLAGS,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val mimeIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                        if (mimeIdx >= 0 && !c.isNull(mimeIdx)) {
                            queriedMime = c.getString(mimeIdx)
                                ?.takeIf { it.isNotBlank() && it != android.provider.DocumentsContract.Document.MIME_TYPE_DIR }
                        }
                        val flagsIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_FLAGS)
                        if (flagsIdx >= 0 && !c.isNull(flagsIdx)) {
                            providerSupportsWrite =
                                (c.getInt(flagsIdx) and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                        }
                    }
                }
            }
            val fileName = localTarget?.name ?: FsPath.name(path)
            val mime = queriedMime
                ?: com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(fileName)
                ?: "*/*"
            val diskWritable = localTarget == null || (localTarget.canWrite() && !isStagedCacheFile(ctx, localTarget))
            val isApk = mime == MainActivity.APK_MIME || MainActivity.isApkName(fileName)
            val writable = diskWritable && !isApk && hasSelfWriteUriGrant(ctx, docUri) && providerSupportsWrite
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri, mime)
                .addFlags(flags)
                .apply { clipData = ClipData.newRawUri("", docUri) }
        }
        if (!FsPath.isLocalFile(path)) return null
    }

    // Local file fallback: FileProvider only serves shared storage and our download caches (see res/xml/file_paths.xml).
    val target = localTarget ?: return null
    val uri = try {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", target)
    } catch (_: Exception) {
        return null
    }
    val mime = com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(target.name) ?: "*/*"
    val isApk = mime == MainActivity.APK_MIME || MainActivity.isApkName(target.name)
    val writable = target.canWrite() && !isStagedCacheFile(ctx, target) && !isApk
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(flags)
        .apply { clipData = ClipData.newRawUri("", uri) }
}

private fun openWithSystem(
    ctx: Context,
    path: String,
    chooser: Boolean = false,
    preferredUri: android.net.Uri? = null,
) {
    val displayName = FsPath.name(path).ifEmpty { File(path).name }
    val i = buildOpenWithSystemIntent(ctx, path, preferredUri, allowSyncLookup = false)
        ?: buildOpenWithSystemIntent(ctx, path, preferredUri = null, forceFileProvider = true, allowSyncLookup = false)
    if (i == null) {
        android.widget.Toast.makeText(ctx, "$displayName can't be opened by other apps from this location", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val selfComponent = android.content.ComponentName(ctx, MainActivity::class.java)
    fun chooserExcludingSelf(target: Intent): Intent = Intent.createChooser(target, "Open with").apply {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(selfComponent))
        }
    }
    fun selectLaunchIntent(base: Intent): Intent = if (chooser) {
        chooserExcludingSelf(base)
    } else {
        val resolved = runCatching { ctx.packageManager.queryIntentActivities(base, 0) }.getOrDefault(emptyList())
        val matchesSelf = resolved.any { it.activityInfo?.packageName == ctx.packageName }
        if (!matchesSelf) {
            base
        } else {
            val external = resolved.mapNotNull { it.activityInfo }
                .filter { it.packageName != ctx.packageName && it.packageName.isNotBlank() && it.name.isNotBlank() }
            if (external.size == 1) {
                Intent(base).setClassName(external[0].packageName, external[0].name)
            } else {
                chooserExcludingSelf(base)
            }
        }
    }
    val uri = i.data ?: return
    try {
        ctx.startActivity(selectLaunchIntent(i))
    } catch (_: SecurityException) {
        val fpFallback = if (uri.authority != "${ctx.packageName}.fileprovider") {
            buildOpenWithSystemIntent(ctx, path, preferredUri = null, forceFileProvider = true, allowSyncLookup = false)
        } else {
            null
        }
        if (fpFallback != null) {
            try {
                ctx.startActivity(selectLaunchIntent(fpFallback))
                return
            } catch (_: Exception) {}
        }
        android.widget.Toast.makeText(ctx, "$displayName can't be opened by other apps from this location", android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: ActivityNotFoundException) {
        try {
            val fallback = Intent(i).setDataAndType(uri, "*/*").apply {
                clipData = ClipData.newRawUri("", uri)
            }
            ctx.startActivity(chooserExcludingSelf(fallback))
        } catch (_: Exception) {
            android.widget.Toast.makeText(ctx, "No app can open $displayName", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun buildShareFilesIntent(
    ctx: Context,
    paths: List<String>,
    preResolvedUris: Map<String, android.net.Uri> = emptyMap(),
    forceFileProvider: Boolean = false,
    allowSyncLookup: Boolean = true,
): Intent? {
    if (paths.isEmpty()) return null
    val uris = ArrayList<android.net.Uri>(paths.size)
    for (p in paths) {
        val target = try {
            safeFileProviderTarget(p)
        } catch (_: Exception) {
            return null
        }
        val preferred = if (forceFileProvider) {
            null
        } else {
            preResolvedUris[p]?.takeIf { candidate ->
                if (com.droid.wayfinder.data.LocalUriResolver.isMediaUri(candidate)) {
                    com.droid.wayfinder.data.LocalUriResolver.canGrantMediaUri(ctx, candidate, write = false)
                } else {
                    hasSelfReadUriGrant(ctx, candidate)
                }
            }
        }
        val resolved = preferred
            ?: (if (!forceFileProvider && allowSyncLookup && !isStagedCacheFile(ctx, target)) {
                com.droid.wayfinder.data.LocalUriResolver.resolvePreferredLocalUriSync(ctx, target.path)
            } else {
                null
            })
            ?: try {
                FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", target)
            } catch (_: Exception) {
                return null
            }
        uris += resolved
    }
    val mime = if (paths.size == 1) {
        com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(paths[0]) ?: "*/*"
    } else {
        val mimes = paths.map { com.droid.wayfinder.data.LocalUriResolver.mimeTypeForPath(it) ?: "*/*" }
        val topTypes = mimes.map { it.substringBefore('/') }.distinct()
        if (topTypes.size == 1 && topTypes[0] != "*") "${topTypes[0]}/*" else "*/*"
    }
    val clip = ClipData(ClipDescription("Shared files", arrayOf(mime)), ClipData.Item(uris[0])).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    return if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.setType(mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = clip }
}

private fun shareFiles(
    ctx: Context,
    paths: List<String>,
    preResolvedUris: Map<String, android.net.Uri> = emptyMap(),
) {
    val i = buildShareFilesIntent(ctx, paths, preResolvedUris, allowSyncLookup = false)
        ?: buildShareFilesIntent(ctx, paths, emptyMap(), forceFileProvider = true, allowSyncLookup = false)
    if (i == null) {
        android.widget.Toast.makeText(ctx, "These files can't be shared from this location", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        ctx.startActivity(Intent.createChooser(i, "Share"))
    } catch (_: SecurityException) {
        val fpFallback = buildShareFilesIntent(ctx, paths, emptyMap(), forceFileProvider = true, allowSyncLookup = false)
        if (fpFallback != null) {
            try {
                ctx.startActivity(Intent.createChooser(fpFallback, "Share"))
                return
            } catch (_: Exception) {}
        }
        android.widget.Toast.makeText(ctx, "These files can't be shared from this location", android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: ActivityNotFoundException) {}
}
