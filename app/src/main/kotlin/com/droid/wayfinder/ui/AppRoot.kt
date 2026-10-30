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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ViewMode
import java.io.File

private fun Modifier.reclaimFocus(vm: BrowserViewModel, fm: FocusManager, root: FocusRequester): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (vm.state.value.textFocus) { fm.clearFocus(); root.requestFocus() }
        }
    }

@Composable
fun AppRoot(vm: BrowserViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val menu = remember { MenuController() }
    val drag = remember { DragController() }
    val snackbar = remember { SnackbarHostState() }
    val rootFocus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    var lastPointer by remember { mutableStateOf(Offset(400f, 300f)) }
    var typeBuffer by remember { mutableStateOf("") }
    var typeTime by remember { mutableStateOf(0L) }

    // "Mount Cloud / SAF…": the system document-tree picker; access is persisted by the view model.
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::mountSaf) }
    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            vm.onGoogleAccountPicked(res.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME))
        }
    }
    SideEffect { drag.onDrop = { t, p, copy -> vm.dropOn(t, p, copy) } }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(s.dialog == null, s.addressEditing, s.paletteOpen) { if (s.dialog == null && !s.addressEditing && !s.paletteOpen) runCatching { rootFocus.requestFocus() } }
    BackHandler(enabled = s.active.canGoBack) { vm.back() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.OpenFile -> openWithSystem(ctx, e.path, e.chooser)
                is UiEvent.Share -> shareFiles(ctx, e.paths)
                is UiEvent.CopyText -> {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(e.what, e.text))
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("${e.what} copied", duration = SnackbarDuration.Short)
                }
                is UiEvent.NewWindow -> openNewWindow(ctx, e.path)
                is UiEvent.Toast -> {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(e.message, if (e.undo) "Undo" else null, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo()
                }
                is UiEvent.PickDocumentTree -> try {
                    launchSystemDocumentTreePicker(ctx, e.initialUri) { treeLauncher.launch(e.initialUri) }
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar("No document provider is available", duration = SnackbarDuration.Short)
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
        if (st.dialog != null || st.textFocus) return false

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
                k == Key.Backslash -> { vm.toggleSidebar(); return true }
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

        val cols = if (tab.viewMode == ViewMode.Grid) st.gridColumns else 1
        when (k) {
            Key.F2 -> vm.requestRename()
            Key.F3 -> vm.toggleDualPane()
            Key.F5 -> if (st.dualPane) vm.transferToOtherPane(move = false) else vm.refresh()
            Key.F6 -> if (st.dualPane) {
                if (tab.selection.isNotEmpty()) vm.transferToOtherPane(move = true) else vm.switchPane()
            } else return fm.moveFocus(if (shift) FocusDirection.Previous else FocusDirection.Next)
            Key.Tab -> if (st.dualPane && !shift) vm.switchPane() else return fm.moveFocus(if (shift) FocusDirection.Previous else FocusDirection.Next)
            Key.Delete -> vm.requestDelete(permanent = shift)
            Key.Enter, Key.NumPadEnter -> vm.openSelection()
            Key.Spacebar -> vm.toggleQuickLook()
            Key.Backspace, Key.Back -> vm.back()
            Key.Forward -> vm.forward()
            Key.Escape -> if (tab.selection.isNotEmpty()) vm.clearSelection() else if (tab.query.isNotEmpty()) vm.setQuery("") else return false
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
    CompositionLocalProvider(LocalMenu provides menu, LocalDrag provides drag, LocalDensity provides zoomed) {
        Box(
            Modifier.fillMaxSize().background(cs.surfaceContainerLow)
                .focusRequester(rootFocus).focusable()
                .onPreviewKeyEvent(::onKey)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.firstOrNull()?.let { lastPointer = it.position }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                Box(Modifier.reclaimFocus(vm, fm, rootFocus)) {
                    TitleBar(vm, s) { off, items -> menu.show(off, items) }
                }
                s.picker?.let { PickerBanner(vm, s) }
                Toolbar(vm, s)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().reclaimFocus(vm, fm, rootFocus)) {
                    val showSidebar = s.sidebarVisible && maxWidth >= 600.dp
                    val showPreview = s.previewVisible && maxWidth >= 900.dp
                    Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
                        if (showSidebar) {
                            Sidebar(vm, s, Modifier.width(s.sidebarWidth.dp).fillMaxSize())
                            SidebarHandle(s.sidebarWidth) { vm.setSidebarWidth(it) }
                        } else Box(Modifier.width(8.dp))
                        ContentCard(vm, s, Modifier.weight(1f).fillMaxSize())
                        if (showPreview) {
                            Box(Modifier.width(8.dp))
                            PreviewPane(vm, s, Modifier.width(300.dp).fillMaxSize())
                        }
                    }
                }
            }
            QuickLook(vm, s)
            if (s.paletteOpen) CommandPalette(vm, s)
            MenuHost(menu)
            Dialogs(vm, s)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
            if (drag.active) DragGhost(drag)
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
@Composable
private fun PickerBanner(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val req = s.picker ?: return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.FileOpen, null, Modifier.size(20.dp))
            when (req.mode) {
                com.droid.wayfinder.data.PickerMode.OpenFile -> {
                    val pickable = remember(s.active.selection, s.active.entries) { vm.pickableSelection() }
                    Column(Modifier.weight(1f)) {
                        Text(if (req.allowMultiple) "Select files to open in the requesting app" else "Select a file to open in the requesting app", style = MaterialTheme.typography.titleSmall)
                        val hint = listOfNotNull(req.description?.let { "Accepts $it" }, if (req.allowMultiple) "Ctrl+click to select several" else null, "Double-click a file to choose it")
                        Text(hint.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton({ vm.cancelPick() }) { Text("Cancel") }
                    Button({ vm.pickSelection() }, enabled = pickable.isNotEmpty()) {
                        Text(if (pickable.size > 1) "Select ${pickable.size} Files" else "Select File")
                    }
                }
                com.droid.wayfinder.data.PickerMode.OpenTree -> {
                    val folderTarget = remember(s.active.path, s.active.selection, s.active.entries) { vm.targetTreeFolder() }
                    val canGrant = remember(folderTarget) { vm.canPickFolder(folderTarget) }
                    Column(Modifier.weight(1f)) {
                        Text("Grant access to folder", style = MaterialTheme.typography.titleSmall)
                        Text("Allow the requesting app to access ${FsPath.name(folderTarget)}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton({ vm.cancelPick() }) { Text("Cancel") }
                    Button({ vm.pickCurrentFolder() }, enabled = canGrant) {
                        Text("Use this folder")
                    }
                }
                com.droid.wayfinder.data.PickerMode.CreateDocument -> {
                    val validName = remember(s.createFileName) { FsPath.nameError(s.createFileName.trim()) == null }
                    Column(Modifier.weight(1f)) {
                        Text("Save file in ${FsPath.name(s.active.path)}", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = s.createFileName,
                            onValueChange = vm::setCreateFileName,
                            singleLine = true,
                            label = { Text("File name") },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    OutlinedButton({ vm.cancelPick() }) { Text("Cancel") }
                    Button({ vm.confirmCreateDocument() }, enabled = validName) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

/** Resolves [path] to a canonical file and rejects symbolic links so FileProvider cannot be bypassed via links. */
private fun safeFileProviderTarget(path: String): File {
    if (com.droid.wayfinder.data.FileInfo.isSymlink(path)) {
        throw IllegalArgumentException("Symbolic links cannot be shared with external apps")
    }
    return File(path).canonicalFile
}

private fun launchSystemDocumentTreePicker(ctx: Context, initialUri: android.net.Uri?, fallbackLaunch: () -> Unit) {
    val a = ctx as? Activity
    if (a != null) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (initialUri != null && android.os.Build.VERSION.SDK_INT >= 26) {
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        val candidates = runCatching { a.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val systemDocUi = candidates.firstOrNull { ri ->
            val pkg = ri.activityInfo?.packageName.orEmpty()
            pkg != a.packageName && (pkg.contains("documentsui") || pkg.contains("ScopedStorage"))
        } ?: candidates.firstOrNull { it.activityInfo?.packageName != a.packageName }
        if (systemDocUi != null) {
            fallbackLaunch()
            return
        }
    }
    fallbackLaunch()
}

internal fun hasSelfReadUriGrant(ctx: Context, uri: android.net.Uri): Boolean {
    val uriStr = uri.toString()
    val hasPersisted = runCatching {
        ctx.contentResolver.persistedUriPermissions.any { perm ->
            perm.isReadPermission && (uri == perm.uri || uriStr.startsWith(perm.uri.toString().trimEnd('/') + "/"))
        }
    }.getOrDefault(false)
    if (hasPersisted) return true
    if (uri.authority == com.droid.wayfinder.data.SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY) {
        return false
    }
    return ctx.checkUriPermission(
        uri,
        android.os.Process.myPid(),
        android.os.Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun resolveReturnUri(ctx: Context, path: String, preferredUri: android.net.Uri? = null): Pair<android.net.Uri, Boolean> {
    if (!path.startsWith("doc://") && !path.startsWith("saf://") && com.droid.wayfinder.data.FileInfo.isSymlink(path)) {
        throw IllegalArgumentException("Symbolic links cannot be shared with external apps")
    }
    val docUri = preferredUri ?: com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(path, tree = false)
    if (docUri != null && hasSelfReadUriGrant(ctx, docUri)) {
        return docUri to true
    }
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", safeFileProviderTarget(path)) to false
}

/** Returns picked files to the calling app as DocumentsContract URIs (or FileProvider fallback) with valid URI grants. */
internal fun returnPicked(ctx: Context, paths: List<String>) {
    val a = ctx.findActivity() ?: return
    if (paths.isEmpty()) return
    try {
        val resolved = paths.map { resolveReturnUri(ctx, it) }
        val uris = resolved.map { it.first }
        val allDocUris = resolved.all { it.second }
        val mime = if (paths.size == 1) MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(paths[0]).extension.lowercase()) ?: "application/octet-stream" else "*/*"
        val clip = ClipData(ClipDescription("Selected files", arrayOf(mime)), ClipData.Item(uris[0]))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (allDocUris) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        val result = Intent().setDataAndType(uris[0], mime).addFlags(flags)
        result.clipData = clip
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't share the selected file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    a.finish()
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
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (hasDocGrant) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        }
        val result = Intent().setData(treeUri).addFlags(flags)
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't grant folder access: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    a.finish()
}

internal fun returnCreatedDocument(ctx: Context, path: String, preferredUri: android.net.Uri?, mimeType: String) {
    val a = ctx.findActivity() ?: return
    try {
        val (uri, isDocUri) = resolveReturnUri(ctx, path, preferredUri)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (isDocUri) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        val result = Intent().setDataAndType(uri, mimeType).addFlags(flags)
        a.setResult(Activity.RESULT_OK, result)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't save file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        a.setResult(Activity.RESULT_CANCELED)
    }
    a.finish()
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

private fun openWithSystem(ctx: Context, path: String, chooser: Boolean = false) {
    val f = File(path)
    // The provider only serves shared storage and our download caches (see res/xml/file_paths.xml).
    val uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", safeFileProviderTarget(path)) } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "${f.name} can't be opened by other apps from this location", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(if (chooser) Intent.createChooser(i, "Open with") else i)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent.createChooser(i.setDataAndType(uri, "*/*"), "Open with"))
        } catch (_: Exception) {
            android.widget.Toast.makeText(ctx, "No app can open ${f.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

private fun shareFiles(ctx: Context, paths: List<String>) {
    val uris = try { ArrayList(paths.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", safeFileProviderTarget(it)) }) } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "These files can't be shared from this location", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    val mime = if (paths.size == 1) MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(paths[0]).extension.lowercase()) ?: "*/*" else "*/*"
    i.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, "Share")) } catch (_: ActivityNotFoundException) {}
}
