// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AppRegistration
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ViewMode

sealed interface MenuEntry {
    data class Item(
        val label: String,
        val icon: ImageVector? = null,
        val shortcut: String? = null,
        val enabled: Boolean = true,
        val tint: Color? = null,
        /** When set, clicking swaps the menu's items for this submenu instead of running [action]. */
        val submenu: (() -> List<MenuEntry>)? = null,
        /** Trailing check mark (toggle items). */
        val checked: Boolean = false,
        val action: () -> Unit,
    ) : MenuEntry

    data object Divider : MenuEntry
}

class MenuController {
    var offset by mutableStateOf<Offset?>(null)
    var items by mutableStateOf<List<MenuEntry>>(emptyList())
    fun show(at: Offset, entries: List<MenuEntry>) { items = entries.trimDividers(); offset = at }
    fun dismiss() { offset = null }
}

private fun List<MenuEntry>.trimDividers(): List<MenuEntry> {
    val out = ArrayList<MenuEntry>()
    for (e in this) if (e !is MenuEntry.Divider || (out.isNotEmpty() && out.last() !is MenuEntry.Divider)) out += e
    while (out.lastOrNull() is MenuEntry.Divider) out.removeAt(out.lastIndex)
    return out
}

val LocalMenu = staticCompositionLocalOf { MenuController() }

@Composable
fun MenuHost(menu: MenuController) {
    val at = menu.offset ?: return
    Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = menu::dismiss, offset = DpOffset(0.dp, 0.dp)) {
            menu.items.forEach { e ->
                when (e) {
                    MenuEntry.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    is MenuEntry.Item -> DropdownMenuItem(
                        text = { Text(e.label) },
                        leadingIcon = e.icon?.let { { Icon(it, null, Modifier.size(20.dp), tint = e.tint ?: androidx.compose.material3.LocalContentColor.current) } },
                        trailingIcon = when {
                            e.checked -> ({ Icon(Icons.Filled.Check, "On", Modifier.size(18.dp)) })
                            e.submenu != null -> ({ Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                            e.shortcut != null -> ({ Text(e.shortcut, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                            else -> null
                        },
                        enabled = e.enabled,
                        onClick = {
                            val sub = e.submenu
                            if (sub != null) menu.items = sub() else { menu.dismiss(); e.action() }
                        },
                    )
                }
            }
        }
    }
}

private fun item(label: String, icon: ImageVector?, shortcut: String? = null, enabled: Boolean = true, action: () -> Unit) =
    MenuEntry.Item(label, icon, shortcut, enabled, action = action)

/** Star toggle for [paths] ("Add to Starred" / "Remove from Starred"). */
fun starItem(vm: BrowserViewModel, s: AppState, paths: List<String>): MenuEntry {
    val all = paths.isNotEmpty() && paths.all { s.marks.isStarred(it) }
    return MenuEntry.Item(
        if (all) "Remove from Starred" else "Add to Starred", if (all) Icons.Filled.Star else Icons.Outlined.StarOutline,
        enabled = paths.isNotEmpty(), tint = if (all) StarColor else null,
    ) { vm.toggleStar(paths) }
}

/** "Color tags ›" submenu that toggles tags on [paths]; a check marks tags every item already has. */
fun tagsItem(vm: BrowserViewModel, s: AppState, paths: List<String>, back: (() -> List<MenuEntry>)?): MenuEntry =
    MenuEntry.Item("Color tags", Icons.AutoMirrored.Outlined.Label, enabled = paths.isNotEmpty(), submenu = {
        buildList {
            if (back != null) add(MenuEntry.Item("Back", Icons.AutoMirrored.Outlined.ArrowBack, submenu = back) {})
            ColorTag.entries.forEach { t ->
                add(MenuEntry.Item(t.label, Icons.Filled.Circle, tint = Color(t.argb), checked = paths.all { t in s.marks.tagsOf(it) }) { vm.toggleTag(t, paths) })
            }
        }
    }) {}

val StarColor = Color(0xFFF4B400)

/** Context menu for a file/folder row (the selection has already been updated to include [entry]). */
fun fileMenu(vm: BrowserViewModel, s: AppState, entry: FileEntry): List<MenuEntry> {
    val tab = s.active
    val sel = tab.selectedEntries(s.showHidden)
    val inTrash = FsPath.isWithin(FsPath.TRASH_ROOT, tab.path) || tab.path == FsPath.TRASH_ROOT
    val single = sel.size == 1
    if (inTrash) return listOf(
        item("Restore", Icons.Outlined.Restore) { vm.restoreFromTrash() },
        item("Delete permanently", Icons.Outlined.DeleteForever, "Del") { vm.requestDelete(true) },
        MenuEntry.Divider,
        item("Properties", Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() },
    )
    val dirs = sel.filter { it.isDir }
    val isArchive = single && vm.isBrowsableArchive(entry)
    val canExtract = sel.any { vm.isBrowsableArchive(it) }
    val inZip = FsPath.isZip(entry.path)
    val readOnlyHere = FsPath.isReadOnly(tab.path)
    val markable = sel.filter { !it.isServer && !it.isShare }.map { it.path }
    lateinit var self: () -> List<MenuEntry>
    val list = buildList {
        add(item("Open", Icons.AutoMirrored.Outlined.OpenInNew, "Enter") { vm.openSelection() })
        if (isArchive) add(item("Browse archive", Icons.Outlined.FolderZip) { vm.browseArchive(entry.path) })
        if (dirs.isNotEmpty()) add(item("Open in new tab", Icons.Outlined.Tab, "Ctrl+Enter") { vm.openSelection(newTab = true) })
        if (single && entry.isDir && !entry.isServer) add(item("Open in new window", Icons.Outlined.Web) { vm.newWindow(entry.path) })
        if (single && !entry.isDir && !entry.isServer && !entry.isShare) add(item("Open with…", Icons.Outlined.AppRegistration) { vm.openWith() })
        if (single && (tab.searchResults != null || FsPath.isCollection(tab.path))) {
            add(item("Show in enclosing folder", Icons.Outlined.FindInPage) { vm.showInEnclosingFolder(entry.path) })
        }
        add(MenuEntry.Divider)
        add(item("Cut", Icons.Outlined.ContentCut, "Ctrl+X", enabled = !entry.isServer && !entry.isShare && !inZip) { vm.cut() })
        add(item("Copy", Icons.Outlined.ContentCopy, "Ctrl+C", enabled = !entry.isServer && !entry.isShare) { vm.copy() })
        if (entry.isDir && s.clipboard != null && !entry.isServer && !inZip) {
            add(item("Paste into folder", Icons.Outlined.ContentPaste) {
                vm.navigate(entry.path); vm.paste()
            })
        }
        add(item("Copy path", Icons.Outlined.Link, "Ctrl+Shift+C") { vm.copyPathToClipboard() })
        val local = !entry.isServer && !entry.isShare
        if (local && !inZip) add(item("Duplicate", Icons.Outlined.CopyAll, "Ctrl+D") { vm.duplicate() })
        s.otherPane?.takeIf { local && !FsPath.isVirtual(it.path) && it.path != tab.path }?.let {
            add(item("Copy to other pane", Icons.Outlined.ContentCopy, "F5") { vm.transferToOtherPane(false) })
            add(item("Move to other pane", Icons.AutoMirrored.Outlined.DriveFileMove, "F6") { vm.transferToOtherPane(true) })
        }
        add(MenuEntry.Divider)
        add(item("Rename", Icons.Outlined.Edit, "F2", enabled = single && local && !inZip) { vm.requestRename() })
        if (sel.size > 1 && local && !readOnlyHere) add(item("Batch rename ${sel.size} items…", Icons.Outlined.DriveFileRenameOutline, "Ctrl+Shift+R") { vm.requestBatchRename() })
        add(item("Compress to ZIP", Icons.Outlined.Archive, enabled = !entry.isServer && !entry.isShare && !readOnlyHere) { vm.requestCompress() })
        if (canExtract && !readOnlyHere) add(item("Extract archive here", Icons.Outlined.Unarchive) { vm.extractSelection() })
        if (FsPath.isLocalFile(entry.path)) add(item("Share", Icons.Outlined.Share, enabled = sel.any { !it.isDir }) { vm.share() })
        s.linuxRootPath?.takeIf { local && it != tab.path }?.let {
            add(item("Copy to Linux files", Icons.AutoMirrored.Outlined.DriveFileMove) { vm.shareWithLinux() })
        }
        add(MenuEntry.Divider)
        add(starItem(vm, s, markable))
        add(tagsItem(vm, s, markable) { self() })
        val pinnable = markable.filter { !FsPath.isVirtual(it) && !FsPath.isZip(it) }
        if (pinnable.isNotEmpty()) {
            val pinned = pinnable.all { it in s.pins }
            add(item(if (pinned) "Unpin from sidebar" else "Pin to sidebar", Icons.Outlined.PushPin, "Ctrl+B") { vm.togglePins(pinnable) })
        }
        add(MenuEntry.Divider)
        add(item("Delete", Icons.Outlined.Delete, "Del", enabled = !entry.isServer && !entry.isShare && !inZip) { vm.requestDelete() })
        add(item("Properties", Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() })
    }
    self = { list.trimDividersPublic() }
    return list
}

internal fun List<MenuEntry>.trimDividersPublic(): List<MenuEntry> = trimDividers()

/** Context menu for the empty area of a folder. */
fun backgroundMenu(vm: BrowserViewModel, s: AppState): List<MenuEntry> {
    val tab = s.active
    val virtual = FsPath.isReadOnly(tab.path)
    if (FsPath.isCollection(tab.path)) return listOf(
        item("Select all", Icons.Outlined.SelectAll, "Ctrl+A") { vm.selectAll() },
        item("Refresh", Icons.Outlined.Refresh, "F5") { vm.refresh() },
        item("Open in new window", Icons.Outlined.Web, "Ctrl+N") { vm.newWindow() },
    )
    if (tab.path == FsPath.TRASH_ROOT) return listOf(
        item("Empty Trash", Icons.Outlined.DeleteForever) { vm.emptyTrash() },
        item("Refresh", Icons.Outlined.Refresh, "F5") { vm.refresh() },
    )
    if (tab.path == FsPath.SMB_ROOT) return listOf(
        item("Connect to server…", Icons.Outlined.Link, "Ctrl+Shift+K") { vm.showConnectDialog() },
        item("Rescan network", Icons.Outlined.Refresh, "F5") { vm.refresh() },
    )
    return listOf(
        item("New folder", Icons.Outlined.CreateNewFolder, "Ctrl+Shift+N", enabled = !virtual) { vm.requestNewItem(true) },
        item("New text file", Icons.AutoMirrored.Outlined.NoteAdd, "Ctrl+Shift+M", enabled = !virtual) { vm.requestNewItem(false) },
        MenuEntry.Divider,
        item("Paste", Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null && !virtual) { vm.paste() },
        item("Undo", Icons.AutoMirrored.Outlined.Undo, "Ctrl+Z", enabled = s.canUndo) { vm.undo() },
        item("Redo", Icons.AutoMirrored.Outlined.Redo, "Ctrl+Y", enabled = s.canRedo) { vm.redo() },
        item("Select all", Icons.Outlined.SelectAll, "Ctrl+A") { vm.selectAll() },
        MenuEntry.Divider,
        item("Details view", Icons.AutoMirrored.Outlined.ViewList, "Ctrl+1", enabled = tab.viewMode != ViewMode.Details) { vm.setViewMode(ViewMode.Details) },
        item("List view", Icons.AutoMirrored.Outlined.ViewList, enabled = tab.viewMode != ViewMode.List) { vm.setViewMode(ViewMode.List) },
        item("Grid view", Icons.Outlined.GridView, "Ctrl+2", enabled = tab.viewMode != ViewMode.Grid) { vm.setViewMode(ViewMode.Grid) },
        item("Columns view", Icons.Outlined.ViewColumn, "Ctrl+3", enabled = tab.viewMode != ViewMode.Columns) { vm.setViewMode(ViewMode.Columns) },
        item(if (s.dualPane) "Close dual pane" else "Dual pane", Icons.Outlined.VerticalSplit, "Ctrl+4") { vm.toggleDualPane() },
        MenuEntry.Divider,
        item("Refresh", Icons.Outlined.Refresh, if (s.dualPane) "Ctrl+R" else "F5") { vm.refresh() },
        item("Open in new window", Icons.Outlined.Web, "Ctrl+N") { vm.newWindow() },
        item("Properties", Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() },
    )
}

fun sidebarMenu(vm: BrowserViewModel, s: AppState, path: String, pinned: Boolean): List<MenuEntry> = buildList {
    val isLocalFile = FsPath.isLocalFile(path) && java.io.File(path).isFile
    add(item("Open", if (isLocalFile) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.FolderOpen) { if (pinned) vm.openPinned(path) else vm.navigate(path) })
    if (isLocalFile) {
        add(item("Show in enclosing folder", Icons.Outlined.FindInPage) { vm.showInEnclosingFolder(path) })
    } else {
        add(item("Open in new tab", Icons.Outlined.Tab) { if (pinned) vm.openPinned(path, newTab = true) else vm.newTab(path, activate = true) })
        add(item("Open in new window", Icons.Outlined.Web) { vm.newWindow(path) })
    }
    if (!FsPath.isVirtual(path) && !FsPath.isZip(path)) {
        if (pinned) {
            add(MenuEntry.Divider)
            add(item("Move up", Icons.AutoMirrored.Outlined.DriveFileMove) { vm.movePin(path, -1) })
            add(item("Move down", Icons.AutoMirrored.Outlined.DriveFileMove) { vm.movePin(path, 1) })
            add(item("Unpin from sidebar", Icons.Outlined.PushPin) { vm.unpin(path) })
        } else add(item("Pin to sidebar", Icons.Outlined.PushPin) { vm.pin(path) })
    }
    if (FsPath.isSmb(path) && FsPath.parseSmb(path).share == null) {
        add(MenuEntry.Divider)
        add(item("Remove server", Icons.Outlined.Close) { vm.forgetServer(FsPath.parseSmb(path).host) })
    }
}

/** Extra sidebar menu entries for a saved server or cloud / SAF mount. */
fun mountMenu(vm: BrowserViewModel, m: Mount): List<MenuEntry> = buildList {
    add(MenuEntry.Divider)
    val saved = if (m.saf) null else vm.state.value.savedServers.firstOrNull { it.key == m.key }
    if (saved != null) add(item("Edit connection…", Icons.Outlined.Link) { vm.showConnectDialog(vm.connectConfigFor(saved)) })
    add(item(if (m.saf) "Unmount" else "Disconnect", Icons.Outlined.Eject) { vm.eject(m) })
    add(item("Forget", Icons.Outlined.Close) { vm.forgetMount(m) })
}

fun tabMenu(vm: BrowserViewModel, tabId: Long): List<MenuEntry> = listOf(
    item("Duplicate tab", Icons.Outlined.CopyAll) { vm.duplicateTab(tabId) },
    item("Close tab", Icons.Outlined.Close, "Ctrl+W") { vm.closeTab(tabId) },
    item("Close other tabs", Icons.Outlined.Close) { vm.closeOtherTabs(tabId) },
)

fun driveAccountMenu(vm: BrowserViewModel, acc: com.droid.wayfinder.data.DriveAccount, mount: Mount?): List<MenuEntry> = buildList {
    if (acc.isMounted && acc.mountPath != null) {
        add(item("Open", Icons.Outlined.FolderOpen) { vm.navigate(acc.mountPath) })
        add(item("Open in new tab", Icons.Outlined.Tab) { vm.newTab(acc.mountPath, activate = true) })
        add(item("Open in new window", Icons.Outlined.Web) { vm.newWindow(acc.mountPath) })
        if (mount != null) {
            add(MenuEntry.Divider)
            add(item("Unmount", Icons.Outlined.Eject) { vm.eject(mount) })
            add(item("Forget", Icons.Outlined.Close) { vm.forgetMount(mount) })
        }
    } else {
        add(item("Browse My Drive (REST API)", Icons.Outlined.FolderOpen) { vm.navigate("gdrive://${acc.email}/root") })
        add(item("Mount folder via picker (SAF)…", Icons.Outlined.AddToDrive) { vm.requestMountSaf(acc.email) })
    }
}

fun phoneMenu(vm: BrowserViewModel, phone: com.droid.wayfinder.data.PairedPhone, mount: Mount? = null): List<MenuEntry> = buildList {
    add(item("Open", Icons.Outlined.Smartphone) { vm.openPairedPhone(phone) })
    if (phone.isMounted && phone.path != FsPath.PHONE_ROOT) {
        add(item("Open in new tab", Icons.Outlined.Tab) { vm.newTab(phone.path, activate = true) })
        add(item("Open in new window", Icons.Outlined.Web) { vm.newWindow(phone.path) })
        if (mount != null) {
            add(MenuEntry.Divider)
            add(item("Unmount", Icons.Outlined.Eject) { vm.eject(mount) })
            add(item("Forget", Icons.Outlined.Close) { vm.forgetMount(mount) })
        }
    } else {
        add(item("Connect phone storage (SAF)…", Icons.Outlined.AddToDrive) { vm.requestMountPhoneStorage(phone) })
    }
    add(MenuEntry.Divider)
    add(item("Quick Share folder", Icons.Outlined.Folder) { vm.openQuickShareFolder() })
    add(item("Phone Hub settings", Icons.Outlined.Settings) { vm.openPhoneHubSettings() })
}

