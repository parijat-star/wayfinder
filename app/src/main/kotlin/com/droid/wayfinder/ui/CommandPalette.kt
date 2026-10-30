// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.AppRegistration
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.data.fuzzyScore

/** One entry in the command palette: a command or a "jump to folder" target. */
data class PaletteItem(
    val name: String,
    val description: String,
    val shortcut: String? = null,
    val icon: ImageVector,
    val action: () -> Unit,
)

/** Every command the palette offers (labels mirror the menus and the F1 list). */
fun paletteCommands(vm: BrowserViewModel, s: AppState): List<PaletteItem> {
    val tab = s.active
    fun c(name: String, desc: String, shortcut: String?, icon: ImageVector, action: () -> Unit) = PaletteItem(name, desc, shortcut, icon, action)
    return buildList {
        add(c("New tab", "Open a new tab", "Ctrl+T", Icons.Outlined.Tab) { vm.newTab() })
        add(c("Close tab", "Close the current tab", "Ctrl+W", Icons.Outlined.Tab) { vm.closeTab(s.activeId) })
        add(c("Reopen closed tab", "Bring back the last closed tab", "Ctrl+Shift+T", Icons.Outlined.History) { vm.reopenClosedTab() })
        add(c("New folder", "Create a folder here", "Ctrl+Shift+N", Icons.Outlined.CreateNewFolder) { vm.requestNewItem(true) })
        add(c("New text file", "Create an empty file here", "Ctrl+Shift+M", Icons.AutoMirrored.Outlined.NoteAdd) { vm.requestNewItem(false) })
        add(c("Copy", "Copy the selection to the clipboard", "Ctrl+C", Icons.Outlined.ContentCopy) { vm.copy() })
        add(c("Cut", "Cut the selection", "Ctrl+X", Icons.Outlined.ContentCut) { vm.cut() })
        add(c("Paste", "Paste into the current folder", "Ctrl+V", Icons.Outlined.ContentPaste) { vm.paste() })
        add(c("Duplicate", "Make a copy next to the original", "Ctrl+D", Icons.Outlined.CopyAll) { vm.duplicate() })
        add(c("Rename", "Rename the selected item", "F2", Icons.Outlined.Edit) { vm.requestRename() })
        add(c("Batch rename", "Rename several items at once", "Ctrl+Shift+R", Icons.Outlined.DriveFileRenameOutline) { vm.requestBatchRename() })
        add(c("Move to Trash", "Delete the selection", "Del", Icons.Outlined.Delete) { vm.requestDelete() })
        add(c("Delete permanently", "Delete without using the Trash", "Shift+Del", Icons.Outlined.DeleteForever) { vm.requestDelete(true) })
        add(c("Compress to ZIP", "Create an archive from the selection", null, Icons.Outlined.Archive) { vm.requestCompress() })
        add(c("Copy path", "Copy the path of the selection", "Ctrl+Shift+C", Icons.Outlined.Link) { vm.copyPathToClipboard() })
        add(c("Properties", "Show size and details", "Ctrl+I", Icons.Outlined.Info) { vm.showProperties() })
        add(c("Undo", "Undo the last file operation", "Ctrl+Z", Icons.AutoMirrored.Outlined.Undo) { vm.undo() })
        add(c("Redo", "Redo the last undone operation", "Ctrl+Y", Icons.AutoMirrored.Outlined.Redo) { vm.redo() })
        add(c("Select all", "Select every item", "Ctrl+A", Icons.Outlined.SelectAll) { vm.selectAll() })
        add(c("Invert selection", "Select everything that isn't selected", "Ctrl+Shift+I", Icons.Outlined.SelectAll) { vm.invertSelection() })
        add(c("Clear selection", "Deselect everything", "Ctrl+Shift+A", Icons.Outlined.SelectAll) { vm.clearSelection() })
        add(c("Details view", "Show items in a table", "Ctrl+1", Icons.AutoMirrored.Outlined.ViewList) { vm.setViewMode(ViewMode.Details) })
        add(c("Grid view", "Show items as icons", "Ctrl+2", Icons.Outlined.GridView) { vm.setViewMode(ViewMode.Grid) })
        add(c("Columns view", "Browse with Miller columns", "Ctrl+3", Icons.Outlined.ViewColumn) { vm.setViewMode(ViewMode.Columns) })
        add(c("List view", "Show items in a compact list", null, Icons.Outlined.ViewHeadline) { vm.setViewMode(ViewMode.List) })
        add(c(if (s.dualPane) "Close dual pane" else "Dual pane", "Two folders side by side", "Ctrl+4 / F3", Icons.Outlined.VerticalSplit) { vm.toggleDualPane() })
        if (s.dualPane) {
            add(c("Switch pane", "Focus the other pane", "Tab", Icons.Outlined.SwapHoriz) { vm.switchPane() })
            add(c("Copy to other pane", "Copy the selection to the other pane's folder", "F5", Icons.Outlined.ContentCopy) { vm.transferToOtherPane(false) })
            add(c("Move to other pane", "Move the selection to the other pane's folder", "F6", Icons.AutoMirrored.Outlined.DriveFileMove) { vm.transferToOtherPane(true) })
        }
        add(c("Show hidden files", if (s.showHidden) "Currently shown" else "Currently hidden", "Ctrl+H", Icons.Outlined.Visibility) { vm.toggleHidden() })
        add(c("Toggle sidebar", "Show or hide the sidebar", "Ctrl+\\", Icons.AutoMirrored.Outlined.ViewSidebar) { vm.toggleSidebar() })
        add(c("Toggle details pane", "Show or hide the preview pane", "Alt+P", Icons.AutoMirrored.Outlined.ViewSidebar) { vm.togglePreview() })
        add(c("Back", "Go to the previous folder", "Alt+←", Icons.AutoMirrored.Outlined.ArrowBack) { vm.back() })
        add(c("Forward", "Go to the next folder", "Alt+→", Icons.AutoMirrored.Outlined.ArrowForward) { vm.forward() })
        add(c("Up one level", "Go to the parent folder", "Alt+↑", Icons.Outlined.ArrowUpward) { vm.up() })
        add(c("Refresh", "Reload the current folder", if (s.dualPane) "Ctrl+R" else "F5 / Ctrl+R", Icons.Outlined.Refresh) { vm.refresh() })
        add(c("Edit address", "Type a path", "Ctrl+L", Icons.Outlined.Edit) { vm.setAddressEditing(true) })
        add(c("Search", "Search this folder and subfolders", "Ctrl+F", Icons.Outlined.Search) { vm.requestSearchFocus() })
        val pinTargets = tab.selectedEntries(s.showHidden).filter { !it.isServer && !it.isShare && !FsPath.isZip(it.path) && !FsPath.isVirtual(it.path) }.map { it.path }
        val effectivePinTargets = pinTargets.ifEmpty { listOf(tab.path).filter { !FsPath.isVirtual(it) && !FsPath.isZip(it) } }
        val pinned = effectivePinTargets.isNotEmpty() && effectivePinTargets.all { it in s.pins }
        val pinLabel = when {
            pinTargets.size > 1 -> if (pinned) "Unpin selection" else "Pin selection"
            pinTargets.size == 1 && !tab.selectedEntries(s.showHidden).first().isDir -> if (pinned) "Unpin file" else "Pin file"
            else -> if (pinned) "Unpin folder" else "Pin folder"
        }
        add(c(pinLabel, "Pin or unpin in the sidebar", "Ctrl+B", Icons.Outlined.PushPin) { vm.togglePins(effectivePinTargets) })
        add(c("Connect to server", "SMB, WebDAV or FTP location", "Ctrl+Shift+K", Icons.Outlined.Dns) { vm.showConnectDialog() })
        add(c("Mount cloud / storage provider", "Google Drive, USB, … via the system picker", null, Icons.Outlined.Dns) { vm.requestMountSaf() })
        add(c("Network", "Browse servers on your network", null, Icons.Outlined.Dns) { vm.navigate(FsPath.SMB_ROOT) })
        add(c("Trash", "Open the Trash", null, Icons.Outlined.Delete) { vm.navigate(FsPath.TRASH_ROOT) })
        add(c("Empty Trash", "Permanently delete everything in the Trash", null, Icons.Outlined.DeleteForever) { vm.emptyTrash() })
        listOf(SortKey.Name to "name", SortKey.Modified to "date modified", SortKey.Type to "type", SortKey.Size to "size").forEach { (k, l) ->
            add(c("Sort by $l", "Order items by $l", null, Icons.Outlined.SortByAlpha) { vm.setSort(k, if (tab.sortKey == k) tab.sortAsc else true) })
        }
        add(c("New window", "Open another independent window", "Ctrl+N", Icons.Outlined.Web) { vm.newWindow() })
        add(c("Zoom in", "Make the interface larger", "Ctrl+=", Icons.Outlined.ZoomIn) { vm.zoomBy(1) })
        add(c("Zoom out", "Make the interface smaller", "Ctrl+-", Icons.Outlined.ZoomOut) { vm.zoomBy(-1) })
        add(c("Reset zoom", "Interface zoom back to 100% (now ${(s.zoom * 100).toInt()}%)", "Ctrl+0", Icons.Outlined.ZoomIn) { vm.zoomBy(0) })
        add(c("Cycle Light / Dark / System theme", "Currently ${s.theme.label}", null, Icons.Outlined.Contrast) { vm.cycleTheme() })
        add(c("Locate active folder", "Reveal the current folder in the sidebar tree", "Alt+Shift+L", Icons.Outlined.MyLocation) { vm.locateInTree() })
        add(c("Collapse folder tree", "Collapse every folder in the sidebar", null, Icons.Outlined.UnfoldLess) { vm.collapseTree() })
        add(c("Recents", "Files changed in the last 30 days", null, Icons.Outlined.History) { vm.navigate(FsPath.RECENTS_ROOT) })
        add(c("Starred", "Open your starred items", null, Icons.Outlined.StarOutline) { vm.navigate(FsPath.STARRED_ROOT) })
        if (s.phoneConnected) {
            add(c("Phone (Quick Access)", "Browse files from connected phone and Quick Share", null, Icons.Outlined.Smartphone) { vm.navigate(FsPath.PHONE_ROOT) })
        }
        s.linuxRootPath?.let { linuxPath ->
            add(c("Linux files", "Open the Googlebook Linux development environment", null, Icons.Outlined.Dns) { vm.navigate(linuxPath) })
            if (tab.selection.isNotEmpty() && tab.path != linuxPath) {
                add(c("Copy to Linux files", "Copy selected items to the Linux development environment", null, Icons.AutoMirrored.Outlined.DriveFileMove) { vm.shareWithLinux() })
            }
        }
        s.workProfiles.forEach { wp ->
            add(c(wp.label, "Switch to managed profile (${wp.label})", null, Icons.Outlined.Folder) { vm.openWorkProfile(wp) })
        }
        add(c("Add to / remove from Starred", "Star the selection (or the current folder)", null, Icons.Outlined.StarOutline) { vm.toggleStar() })
        ColorTag.entries.forEach { t ->
            add(c("Tag: ${t.label}", "Toggle the ${t.label} color tag on the selection", null, Icons.AutoMirrored.Outlined.Label) { vm.toggleTag(t) })
            add(c("Show ${t.label} items", "Open the ${t.label} color tag view", null, Icons.Filled.Circle) { vm.navigate(FsPath.tagPath(t)) })
        }
        add(c(if (s.tagsColumn) "Hide Tags column" else "Show Tags column", "Details view column", null, Icons.AutoMirrored.Outlined.Label) { vm.toggleTagsColumn() })
        add(c(if (s.permsColumn) "Hide Permissions column" else "Show Permissions column", "Details view column", null, Icons.Outlined.Lock) { vm.togglePermsColumn() })
        val one = tab.selectedEntries(s.showHidden).singleOrNull()
        if (one != null && !one.isDir) add(c("Open with…", "Choose an app for ${one.name}", null, Icons.Outlined.AppRegistration) { vm.openWith() })
        if (one != null && vm.isBrowsableArchive(one)) {
            add(c("Browse archive", "Open ${one.name} as a folder", null, Icons.Outlined.FolderZip) { vm.browseArchive(one.path) })
            add(c("Extract archive here", "Unpack ${one.name} into this folder", null, Icons.Outlined.Unarchive) { vm.extractSelection() })
        } else if (tab.selectedEntries(s.showHidden).any { vm.isBrowsableArchive(it) }) {
            add(c("Extract archive here", "Unpack selected archives into this folder", null, Icons.Outlined.Unarchive) { vm.extractSelection() })
        }
        if (one != null && (tab.searchResults != null || FsPath.isCollection(tab.path))) {
            add(c("Show in enclosing folder", "Go to the folder that contains ${one.name}", null, Icons.Outlined.FindInPage) { vm.showInEnclosingFolder(one.path) })
        }
        add(c("Keyboard shortcuts", "Show all shortcuts", "F1", Icons.Outlined.Keyboard) { vm.showDialog(Dialog.Shortcuts) })
        add(c("About Wayfinder", "Version and open-source licenses", null, Icons.Outlined.Info) { vm.showDialog(Dialog.About) })
    }
}

/** "Jump to" targets: pinned, recent and storage roots, plus sub-folders of the current folder. */
fun paletteFolders(vm: BrowserViewModel, s: AppState): List<PaletteItem> {
    val seen = HashSet<String>()
    val out = ArrayList<PaletteItem>()
    fun add(path: String, kind: String) {
        if (!seen.add(path)) return
        out += PaletteItem(s.titleOf(path), "$kind • $path", null, Icons.Outlined.Folder) { vm.navigate(path) }
    }
    s.pins.forEach { add(it, "Pinned") }
    s.roots.forEach { add(it.path, "Storage") }
    if (s.phoneConnected) add(FsPath.PHONE_ROOT, "Quick access")
    s.recentFolders.forEach { add(it, "Recent") }
    s.active.visible(s.showHidden).filter { it.isDir && !it.isServer }.forEach { add(it.path, "In this folder") }
    return out
}

@Composable
fun CommandPalette(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val commands = remember(s.active.path, s.active.entries, s.active.selection, s.dualPane, s.pins, s.recentFolders, s.showHidden, s.theme, s.zoom, s.tagsColumn, s.permsColumn) {
        paletteCommands(vm, s) to paletteFolders(vm, s)
    }
    val results = remember(query, commands) {
        val (cmds, folders) = commands
        if (query.isBlank()) cmds + folders.take(12)
        else (cmds + folders).mapNotNull { item ->
            val score = fuzzyScore(query, item.name)?.plus(100) ?: fuzzyScore(query, item.description)?.minus(200)
            score?.let { item to it }
        }.sortedByDescending { it.second }.map { it.first }.take(60)
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(results) { index = 0; if (results.isNotEmpty()) listState.scrollToItem(0) }
    LaunchedEffect(index) {
        val info = listState.layoutInfo.visibleItemsInfo
        if (info.isNotEmpty() && (index <= info.first().index || index >= info.last().index)) listState.animateScrollToItem((index - 3).coerceAtLeast(0))
    }
    fun run(item: PaletteItem) { vm.closePalette(); item.action() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { vm.closePalette() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            Modifier.padding(top = 72.dp, start = 24.dp, end = 24.dp).widthIn(max = 640.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, shadowElevation = 12.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                    Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Type a command or jump to folder…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        BasicTextField(
                            query, { query = it },
                            Modifier.fillMaxWidth().focusRequester(fr)
                                .onFocusChanged { vm.setTextFocus(it.isFocused) }
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionDown -> { if (results.isNotEmpty()) index = (index + 1) % results.size; true }
                                        Key.DirectionUp -> { if (results.isNotEmpty()) index = (index - 1).mod(results.size); true }
                                        Key.PageDown -> { index = (index + 8).coerceAtMost((results.size - 1).coerceAtLeast(0)); true }
                                        Key.PageUp -> { index = (index - 8).coerceAtLeast(0); true }
                                        Key.Enter, Key.NumPadEnter -> { results.getOrNull(index)?.let { run(it) }; true }
                                        Key.Escape -> { vm.closePalette(); true }
                                        Key.Tab -> true
                                        else -> false
                                    }
                                },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                        )
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)
                if (results.isEmpty()) {
                    Text("No matching commands or folders", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                } else LazyColumn(Modifier.heightIn(max = 420.dp).fillMaxWidth(), listState) {
                    itemsIndexed(results) { i, item ->
                        val sel = i == index
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) cs.secondaryContainer else Color.Transparent)
                                .clickable { run(item) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(item.icon, null, Modifier.size(20.dp), tint = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (sel) cs.onSecondaryContainer else cs.onSurface)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                            }
                            if (item.shortcut != null) Text(item.shortcut, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
