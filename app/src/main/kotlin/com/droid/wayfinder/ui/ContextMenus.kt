// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.droid.wayfinder.R
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.RemoteUri
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
    /** True while the touch long-press gesture that opened this menu is still held down so finger release does not dismiss it. */
    var openingGestureActive by mutableStateOf(false)
    fun show(at: Offset, entries: List<MenuEntry>) { items = entries.trimDividers(); offset = at }
    fun dismiss() { offset = null; openingGestureActive = false }
}

private fun List<MenuEntry>.trimDividers(): List<MenuEntry> {
    val out = ArrayList<MenuEntry>()
    for (e in this) if (e !is MenuEntry.Divider || (out.isNotEmpty() && out.last() !is MenuEntry.Divider)) out += e
    while (out.lastOrNull() is MenuEntry.Divider) out.removeAt(out.lastIndex)
    return out
}

val LocalMenu = staticCompositionLocalOf { MenuController() }

internal const val CONTEXT_SUBMENU_CARD_TAG = "wayfinder_context_submenu_card"

private val FullWindowPopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
fun MenuHost(menu: MenuController) {
    val at = menu.offset ?: return
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var activeSubmenuIndex by remember(at, menu.items) { mutableStateOf<Int?>(null) }
    var pointerInsideSubmenu by remember(at, menu.items) { mutableStateOf(false) }
    var closeSubmenuJob by remember(at, menu.items) { mutableStateOf<Job?>(null) }
    val itemTopInColumn = remember(at, menu.items) { mutableStateMapOf<Int, Int>() }
    val parentScroll = rememberScrollState()

    val activeParentItem = activeSubmenuIndex?.let { idx ->
        (menu.items.getOrNull(idx) as? MenuEntry.Item)?.takeIf { it.enabled && it.submenu != null }
    }
    val activeSubmenuItems = remember(activeParentItem) {
        activeParentItem?.submenu?.invoke()
            ?.filterNot { it is MenuEntry.Item && it.submenu != null && it.icon == Icons.AutoMirrored.Outlined.ArrowBack }
            ?.trimDividers()
            .orEmpty()
    }

    Popup(
        popupPositionProvider = FullWindowPopupPositionProvider,
        onDismissRequest = { if (!menu.openingGestureActive) menu.dismiss() },
        properties = PopupProperties(focusable = true),
    ) {
        val marginPx = with(density) { 8.dp.roundToPx() }
        val gapPx = with(density) { 2.dp.roundToPx() }
        val menuShape = RoundedCornerShape(8.dp)

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        if (activeSubmenuIndex != null) {
                            closeSubmenuJob?.cancel()
                            activeSubmenuIndex = null
                        } else {
                            menu.dismiss()
                        }
                        true
                    } else {
                        false
                    }
                },
            content = {
                // 0: Transparent outside-tap / outside-click dismiss backdrop (no semantics role).
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(menu) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                waitForUpOrCancellation()?.consume()
                                if (!menu.openingGestureActive) menu.dismiss()
                            }
                        },
                )

                // 1: Primary context menu card.
                Surface(
                    shape = menuShape,
                    color = cs.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 204.dp, max = 320.dp)
                            .heightIn(max = 520.dp)
                            .verticalScroll(parentScroll)
                            .padding(vertical = 8.dp),
                    ) {
                        menu.items.forEachIndexed { idx, e ->
                            when (e) {
                                MenuEntry.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                is MenuEntry.Item -> {
                                    val isSubOpen = activeSubmenuIndex == idx
                                    DropdownMenuItem(
                                        modifier = Modifier
                                            .background(if (isSubOpen) cs.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                                            .onGloballyPositioned { coords ->
                                                itemTopInColumn[idx] = coords.positionInParent().y.roundToInt()
                                            }
                                            .pointerInput(idx, e.submenu != null, e.enabled) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val pev = awaitPointerEvent(PointerEventPass.Initial)
                                                        if (pev.type == PointerEventType.Enter || pev.type == PointerEventType.Move) {
                                                            if (e.submenu != null && e.enabled) {
                                                                closeSubmenuJob?.cancel()
                                                                activeSubmenuIndex = idx
                                                            } else if (activeSubmenuIndex != null && activeSubmenuIndex != idx) {
                                                                if (closeSubmenuJob?.isActive != true) {
                                                                    closeSubmenuJob = scope.launch {
                                                                        delay(180L)
                                                                        if (!pointerInsideSubmenu) {
                                                                            activeSubmenuIndex = null
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                        text = { Text(e.label) },
                                        leadingIcon = e.icon?.let {
                                            { Icon(it, null, Modifier.size(20.dp), tint = e.tint ?: androidx.compose.material3.LocalContentColor.current) }
                                        },
                                        trailingIcon = when {
                                            e.checked -> ({ Icon(Icons.Filled.Check, "On", Modifier.size(18.dp)) })
                                            e.submenu != null -> ({ Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant) })
                                            e.shortcut != null -> ({ Text(e.shortcut, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) })
                                            else -> null
                                        },
                                        enabled = e.enabled,
                                        onClick = {
                                            if (e.submenu != null) {
                                                closeSubmenuJob?.cancel()
                                                activeSubmenuIndex = idx
                                            } else {
                                                menu.dismiss()
                                                e.action()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 2: Chrome-style side-by-side cascading submenu card aligned directly next to the hovered parent row.
                if (activeSubmenuItems.isNotEmpty()) {
                    val subScroll = rememberScrollState()
                    Surface(
                        modifier = Modifier
                            .testTag(CONTEXT_SUBMENU_CARD_TAG)
                            .pointerInput(activeSubmenuIndex) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val pev = awaitPointerEvent(PointerEventPass.Initial)
                                        when (pev.type) {
                                            PointerEventType.Enter, PointerEventType.Move -> {
                                                pointerInsideSubmenu = true
                                                closeSubmenuJob?.cancel()
                                            }
                                            PointerEventType.Exit -> {
                                                pointerInsideSubmenu = false
                                            }
                                        }
                                    }
                                }
                            },
                        shape = menuShape,
                        color = cs.surfaceContainer,
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                    ) {
                        Column(
                            Modifier
                                .width(IntrinsicSize.Max)
                                .widthIn(min = 196.dp, max = 320.dp)
                                .heightIn(max = 460.dp)
                                .verticalScroll(subScroll)
                                .padding(vertical = 8.dp),
                        ) {
                            activeSubmenuItems.forEach { subEntry ->
                                when (subEntry) {
                                    MenuEntry.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    is MenuEntry.Item -> DropdownMenuItem(
                                        text = { Text(subEntry.label) },
                                        leadingIcon = subEntry.icon?.let {
                                            { Icon(it, null, Modifier.size(20.dp), tint = subEntry.tint ?: androidx.compose.material3.LocalContentColor.current) }
                                        },
                                        trailingIcon = when {
                                            subEntry.checked -> ({ Icon(Icons.Filled.Check, "On", Modifier.size(18.dp)) })
                                            subEntry.submenu != null -> ({ Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant) })
                                            subEntry.shortcut != null -> ({ Text(subEntry.shortcut, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) })
                                            else -> null
                                        },
                                        enabled = subEntry.enabled,
                                        onClick = {
                                            val nested = subEntry.submenu
                                            if (nested != null) {
                                                menu.items = nested().trimDividers()
                                                activeSubmenuIndex = null
                                            } else {
                                                menu.dismiss()
                                                subEntry.action()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val loose = Constraints(
                minWidth = 0,
                maxWidth = (w - marginPx * 2).coerceAtLeast(1),
                minHeight = 0,
                maxHeight = (h - marginPx * 2).coerceAtLeast(1),
            )
            val backdrop = measurables[0].measure(Constraints.fixed(w, h))
            val parent = measurables[1].measure(loose)
            val sub = measurables.getOrNull(2)?.measure(loose)

            val maxParentX = (w - parent.width - marginPx).coerceAtLeast(marginPx)
            val maxParentY = (h - parent.height - marginPx).coerceAtLeast(marginPx)
            val parentX = at.x.roundToInt().coerceIn(marginPx, maxParentX)
            val parentY = at.y.roundToInt().coerceIn(marginPx, maxParentY)

            var subX = 0
            var subY = 0
            if (sub != null) {
                val maxSubX = (w - sub.width - marginPx).coerceAtLeast(marginPx)
                val maxSubY = (h - sub.height - marginPx).coerceAtLeast(marginPx)
                val rightCandidate = parentX + parent.width + gapPx
                val leftCandidate = parentX - sub.width - gapPx
                subX = if (layoutDirection == LayoutDirection.Rtl) {
                    if (leftCandidate >= marginPx) leftCandidate else rightCandidate.coerceIn(marginPx, maxSubX)
                } else {
                    if (rightCandidate + sub.width <= w - marginPx) rightCandidate else leftCandidate.coerceIn(marginPx, maxSubX)
                }
                val itemTop = (itemTopInColumn[activeSubmenuIndex] ?: 0) - parentScroll.value
                subY = (parentY + itemTop).coerceIn(marginPx, maxSubY)
            }

            layout(w, h) {
                backdrop.placeRelative(0, 0)
                parent.place(parentX, parentY)
                sub?.place(subX, subY)
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
        if (all) vm.str(R.string.action_remove_from_starred) else vm.str(R.string.action_add_to_starred), if (all) Icons.Filled.Star else Icons.Outlined.StarOutline,
        enabled = paths.isNotEmpty(), tint = if (all) StarColor else null,
    ) { vm.toggleStar(paths) }
}

/** "Color tags ›" submenu that toggles tags on [paths]; a check marks tags every item already has. */
fun tagsItem(vm: BrowserViewModel, s: AppState, paths: List<String>, back: (() -> List<MenuEntry>)?): MenuEntry =
    MenuEntry.Item(vm.str(R.string.color_tags_section), Icons.AutoMirrored.Outlined.Label, enabled = paths.isNotEmpty(), submenu = {
        buildList {
            if (back != null) add(MenuEntry.Item(vm.str(R.string.action_back), Icons.AutoMirrored.Outlined.ArrowBack, submenu = back) {})
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
    val inTrash = vm.isInTrash(tab.path) || sel.any { vm.isInTrash(it.path) } || vm.isInTrash(entry.path)
    val single = sel.size == 1
    if (inTrash) return buildList {
        if (single && entry.isDir) {
            add(item(vm.str(R.string.action_open), Icons.AutoMirrored.Outlined.OpenInNew, "Enter") { vm.openSelection() })
            add(MenuEntry.Divider)
        }
        add(
            item(
                vm.str(R.string.action_restore),
                Icons.Outlined.Restore,
                enabled = sel.isNotEmpty() && sel.all { vm.canRestoreFromTrash(it.path) },
            ) { vm.restoreFromTrash() },
        )
        add(
            item(
                vm.str(R.string.action_delete_permanently),
                Icons.Outlined.DeleteForever,
                "Del",
                enabled = sel.isNotEmpty(),
            ) { vm.requestDelete(true) },
        )
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_copy), Icons.Outlined.ContentCopy, "Ctrl+C", enabled = sel.isNotEmpty()) { vm.copy() })
        add(item(vm.str(R.string.action_copy_path), Icons.Outlined.Link, "Ctrl+Shift+C") { vm.copyPathToClipboard() })
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_properties), Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() })
    }
    val dirs = sel.filter { it.isDir }
    val isArchive = single && vm.isBrowsableArchive(entry)
    val canExtract = sel.any { vm.isBrowsableArchive(it) }
    val inZip = FsPath.isZip(entry.path)
    val readOnlyHere = FsPath.isReadOnly(tab.path)
    val markable = sel.filter { !it.isServer && !it.isShare }.map { it.path }
    lateinit var self: () -> List<MenuEntry>
    val list = buildList {
        add(item(vm.str(R.string.action_open), Icons.AutoMirrored.Outlined.OpenInNew, "Enter") { vm.openSelection() })
        if (isArchive) add(item(vm.str(R.string.action_browse_archive), Icons.Outlined.FolderZip) { vm.browseArchive(entry.path) })
        if (dirs.isNotEmpty()) add(item(vm.str(R.string.action_open_in_new_tab), Icons.Outlined.Tab, "Ctrl+Enter") { vm.openSelection(newTab = true) })
        if (single && entry.isDir && !entry.isServer) add(item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web) { vm.newWindow(entry.path) })
        if (single && !entry.isDir && !entry.isServer && !entry.isShare) add(item(vm.str(R.string.action_open_with), Icons.Outlined.AppRegistration) { vm.openWith() })
        if (single && FsPath.isOneDrive(entry.path)) {
            add(item(vm.str(R.string.onedrive_action_open_web), Icons.AutoMirrored.Outlined.OpenInNew) { vm.openInOneDriveWeb(entry.path) })
        }
        if (single && (tab.searchResults != null || FsPath.isCollection(tab.path))) {
            add(item(vm.str(R.string.action_show_in_enclosing_folder), Icons.Outlined.FindInPage) { vm.showInEnclosingFolder(entry.path) })
        }
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_cut), Icons.Outlined.ContentCut, "Ctrl+X", enabled = !entry.isServer && !entry.isShare && !inZip) { vm.cut() })
        add(item(vm.str(R.string.action_copy), Icons.Outlined.ContentCopy, "Ctrl+C", enabled = !entry.isServer && !entry.isShare) { vm.copy() })
        if (entry.isDir && s.clipboard != null && !entry.isServer && !inZip) {
            add(item(vm.str(R.string.action_paste_into_folder), Icons.Outlined.ContentPaste) {
                vm.navigate(entry.path); vm.paste()
            })
        }
        add(item(vm.str(R.string.action_copy_path), Icons.Outlined.Link, "Ctrl+Shift+C") { vm.copyPathToClipboard() })
        val local = !entry.isServer && !entry.isShare
        if (local && !inZip) add(item(vm.str(R.string.action_duplicate), Icons.Outlined.CopyAll, "Ctrl+D") { vm.duplicate() })
        s.otherPane?.takeIf { local && !FsPath.isVirtual(it.path) && it.path != tab.path }?.let {
            add(item(vm.str(R.string.action_copy_to_other_pane), Icons.Outlined.ContentCopy, "F5") { vm.transferToOtherPane(false) })
            add(item(vm.str(R.string.action_move_to_other_pane), Icons.AutoMirrored.Outlined.DriveFileMove, "F6") { vm.transferToOtherPane(true) })
        }
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_rename), Icons.Outlined.Edit, "F2", enabled = single && local && !inZip) { vm.requestRename() })
        if (sel.size > 1 && local && !readOnlyHere) add(item(vm.icuStr(R.string.menu_batch_rename_plural, sel.size), Icons.Outlined.DriveFileRenameOutline, "Ctrl+Shift+R") { vm.requestBatchRename() })
        add(item(vm.str(R.string.action_compress_to_zip), Icons.Outlined.Archive, enabled = !entry.isServer && !entry.isShare && !readOnlyHere) { vm.requestCompress() })
        if (canExtract && !readOnlyHere) add(item(vm.str(R.string.action_extract_archive_here), Icons.Outlined.Unarchive) { vm.extractSelection() })
        if (FsPath.isLocalFile(entry.path)) add(item(vm.str(R.string.action_share), Icons.Outlined.Share, enabled = sel.any { !it.isDir }) { vm.share() })
        s.linuxRootPath?.takeIf { local && it != tab.path }?.let {
            add(item(vm.str(R.string.action_copy_to_linux_files), Icons.AutoMirrored.Outlined.DriveFileMove) { vm.shareWithLinux() })
        }
        add(MenuEntry.Divider)
        add(starItem(vm, s, markable))
        add(tagsItem(vm, s, markable) { self() })
        val pinnable = markable.filter { !FsPath.isVirtual(it) && !FsPath.isZip(it) }
        if (pinnable.isNotEmpty()) {
            val pinned = pinnable.all { it in s.pins }
            add(item(if (pinned) vm.str(R.string.action_unpin_from_sidebar) else vm.str(R.string.action_pin_to_sidebar), Icons.Outlined.PushPin, "Ctrl+B") { vm.togglePins(pinnable) })
        }
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_delete), Icons.Outlined.Delete, "Del", enabled = !entry.isServer && !entry.isShare && !inZip) { vm.requestDelete() })
        add(item(vm.str(R.string.action_properties), Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() })
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
        item(vm.str(R.string.action_select_all), Icons.Outlined.SelectAll, "Ctrl+A") { vm.selectAll() },
        item(vm.str(R.string.action_refresh), Icons.Outlined.Refresh, "F5") { vm.refresh() },
        item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web, "Ctrl+N") { vm.newWindow() },
    )
    if (tab.path == FsPath.TRASH_ROOT) return listOf(
        item(vm.str(R.string.action_empty_trash), Icons.Outlined.DeleteForever) { vm.emptyTrash() },
        item(vm.str(R.string.action_refresh), Icons.Outlined.Refresh, "F5") { vm.refresh() },
    )
    if (vm.isInTrash(tab.path)) return listOf(
        item(vm.str(R.string.action_select_all), Icons.Outlined.SelectAll, "Ctrl+A") { vm.selectAll() },
        item(vm.str(R.string.action_refresh), Icons.Outlined.Refresh, if (s.dualPane) "Ctrl+R" else "F5") { vm.refresh() },
        MenuEntry.Divider,
        item(vm.str(R.string.action_properties), Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() },
    )
    if (tab.path == FsPath.SMB_ROOT) return listOf(
        item(vm.str(R.string.action_connect_to_server_ellipsis), Icons.Outlined.Link, "Ctrl+Shift+K") { vm.showConnectDialog() },
        item(vm.str(R.string.action_rescan_network), Icons.Outlined.Refresh, "F5") { vm.refresh() },
    )
    return listOf(
        item(vm.str(R.string.action_new_folder), Icons.Outlined.CreateNewFolder, "Ctrl+Shift+N", enabled = !virtual) { vm.requestNewItem(true) },
        item(vm.str(R.string.action_new_text_file), Icons.AutoMirrored.Outlined.NoteAdd, "Ctrl+Shift+M", enabled = !virtual) { vm.requestNewItem(false) },
        MenuEntry.Divider,
        item(vm.str(R.string.action_paste), Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null && !virtual) { vm.paste() },
        item(vm.str(R.string.action_undo), Icons.AutoMirrored.Outlined.Undo, "Ctrl+Z", enabled = s.canUndo) { vm.undo() },
        item(vm.str(R.string.action_redo), Icons.AutoMirrored.Outlined.Redo, "Ctrl+Y", enabled = s.canRedo) { vm.redo() },
        item(vm.str(R.string.action_select_all), Icons.Outlined.SelectAll, "Ctrl+A") { vm.selectAll() },
        MenuEntry.Divider,
        item(vm.str(R.string.action_details_view), Icons.AutoMirrored.Outlined.ViewList, "Ctrl+1", enabled = tab.viewMode != ViewMode.Details) { vm.setViewMode(ViewMode.Details) },
        item(vm.str(R.string.action_list_view), Icons.AutoMirrored.Outlined.ViewList, enabled = tab.viewMode != ViewMode.List) { vm.setViewMode(ViewMode.List) },
        item(vm.str(R.string.action_grid_view), Icons.Outlined.GridView, "Ctrl+2", enabled = tab.viewMode != ViewMode.Grid) { vm.setViewMode(ViewMode.Grid) },
        item(vm.str(R.string.action_columns_view), Icons.Outlined.ViewColumn, "Ctrl+3", enabled = tab.viewMode != ViewMode.Columns) { vm.setViewMode(ViewMode.Columns) },
        item(if (s.dualPane) vm.str(R.string.action_close_dual_pane) else vm.str(R.string.action_dual_pane), Icons.Outlined.VerticalSplit, "Ctrl+4") { vm.toggleDualPane() },
        MenuEntry.Divider,
        item(vm.str(R.string.action_refresh), Icons.Outlined.Refresh, if (s.dualPane) "Ctrl+R" else "F5") { vm.refresh() },
        item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web, "Ctrl+N") { vm.newWindow() },
    ) + (if (FsPath.isOneDrive(tab.path)) {
        listOf(item(vm.str(R.string.onedrive_action_open_web), Icons.AutoMirrored.Outlined.OpenInNew) { vm.openInOneDriveWeb(tab.path) })
    } else {
        emptyList()
    }) + listOf(
        item(vm.str(R.string.action_properties), Icons.Outlined.Info, "Ctrl+I") { vm.showProperties() },
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
            add(item(vm.str(R.string.action_move_up), Icons.AutoMirrored.Outlined.DriveFileMove) { vm.movePin(path, -1) })
            add(item(vm.str(R.string.action_move_down), Icons.AutoMirrored.Outlined.DriveFileMove) { vm.movePin(path, 1) })
            add(item(vm.str(R.string.action_unpin_from_sidebar), Icons.Outlined.PushPin) { vm.unpin(path) })
        } else add(item(vm.str(R.string.action_pin_to_sidebar), Icons.Outlined.PushPin) { vm.pin(path) })
    }
    if (FsPath.isSmb(path) && FsPath.parseSmb(path).share == null) {
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_remove_server), Icons.Outlined.Close) { vm.forgetServer(RemoteUri.credKeyOf(path) ?: FsPath.parseSmb(path).host) })
    }
}

/** Extra sidebar menu entries for a saved server or cloud / SAF mount. */
fun mountMenu(vm: BrowserViewModel, m: Mount): List<MenuEntry> = buildList {
    add(MenuEntry.Divider)
    val saved = if (m.saf) null else vm.state.value.savedServers.firstOrNull { it.key == m.key }
    if (saved != null) add(item(vm.str(R.string.action_edit_connection), Icons.Outlined.Link) { vm.showConnectDialog(vm.connectConfigFor(saved)) })
    add(item(if (m.saf) vm.str(R.string.action_unmount) else vm.str(R.string.action_disconnect), Icons.Outlined.Eject) { vm.eject(m) })
    add(item(vm.str(R.string.action_forget), Icons.Outlined.Close) { vm.forgetMount(m) })
}

fun tabMenu(
    vm: BrowserViewModel,
    tabId: Long,
    windowId: Int = 0,
    currentDisplayId: Int = android.view.Display.DEFAULT_DISPLAY,
    connectedDisplays: List<com.droid.wayfinder.data.DisplayTarget> = emptyList(),
): List<MenuEntry> {
    val s = vm.state.value
    val idx = s.tabs.indexOfFirst { it.id == tabId }
    val targetTab = s.tabs.getOrNull(idx)
    val canMoveLeft = idx > 0
    val canMoveRight = idx in 0 until s.tabs.lastIndex
    val otherWindows = if (s.picker == null) com.droid.wayfinder.data.TabTransferRegistry.otherMergeableWindows(windowId) else emptyList()
    val otherDisplays = if (s.picker == null) connectedDisplays.filter { it.displayId != currentDisplayId } else emptyList()
    val canDrainLastTab = s.tabs.size > 1 || s.ops.isEmpty()

    val entries = buildList {
        add(item(vm.str(R.string.action_new_tab_right), Icons.Outlined.Add) { vm.newTabAfter(tabId) })
        add(item(vm.str(R.string.action_duplicate_tab), Icons.Outlined.CopyAll) { vm.duplicateTab(tabId) })
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_move_tab_left), Icons.AutoMirrored.Outlined.ArrowBack, "Ctrl+Shift+PgUp", enabled = canMoveLeft) { vm.moveTabBy(tabId, -1) })
        add(item(vm.str(R.string.action_move_tab_right), Icons.AutoMirrored.Outlined.ArrowForward, "Ctrl+Shift+PgDn", enabled = canMoveRight) { vm.moveTabBy(tabId, 1) })
        if (s.tabs.size > 2) {
            add(item(vm.str(R.string.action_move_tab_start), Icons.AutoMirrored.Outlined.ArrowBack, enabled = canMoveLeft) { vm.moveTabToEdge(tabId, toEnd = false) })
            add(item(vm.str(R.string.action_move_tab_end), Icons.AutoMirrored.Outlined.ArrowForward, enabled = canMoveRight) { vm.moveTabToEdge(tabId, toEnd = true) })
        }
        if (s.picker == null) {
            add(MenuEntry.Divider)
            add(
                item(
                    vm.str(R.string.action_move_tab_new_window),
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    "Alt+Shift+N",
                    enabled = s.tabs.size > 1 && targetTab != null,
                ) { vm.moveTabToNewWindow(tabId) },
            )
            add(
                item(
                    vm.str(R.string.action_open_in_new_window),
                    Icons.Outlined.Web,
                    enabled = targetTab != null,
                ) { targetTab?.let { vm.newWindow(it.path) } },
            )
            if (otherWindows.isNotEmpty()) {
                val displayNames = connectedDisplays.associate { it.displayId to it.name }
                add(
                    MenuEntry.Item(
                        label = vm.str(R.string.action_move_tab_other_window),
                        icon = Icons.Outlined.Tab,
                        enabled = canDrainLastTab && targetTab != null,
                        submenu = {
                            otherWindows.map { win ->
                                val winLabel = if (win.displayId != currentDisplayId) {
                                    val dispName = displayNames[win.displayId] ?: "Display ${win.displayId}"
                                    vm.str(R.string.menu_window_target_on_display, win.title, dispName)
                                } else {
                                    win.title
                                }
                                item(winLabel, Icons.Outlined.Web) { vm.moveTabToWindow(tabId, win.windowId) }
                            }
                        },
                    ) {},
                )
            }
            if (otherDisplays.isNotEmpty()) {
                add(
                    MenuEntry.Item(
                        label = vm.str(R.string.action_move_tab_to_display),
                        icon = Icons.Outlined.DesktopWindows,
                        enabled = canDrainLastTab && targetTab != null,
                        submenu = {
                            otherDisplays.map { disp ->
                                item(disp.name, Icons.Outlined.DesktopWindows) { vm.moveTabToDisplay(tabId, disp.displayId) }
                            }
                        },
                    ) {},
                )
            }
        }
        add(MenuEntry.Divider)
        add(item(vm.str(R.string.action_close_tab), Icons.Outlined.Close, "Ctrl+W") { vm.closeTab(tabId) })
        add(item(vm.str(R.string.action_close_other_tabs), Icons.Outlined.Close, enabled = s.tabs.size > 1) { vm.closeOtherTabs(tabId) })
        add(item(vm.str(R.string.action_close_tabs_right), Icons.Outlined.Close, enabled = canMoveRight) { vm.closeTabsToRight(tabId) })
    }
    return entries.trimDividersPublic()
}

fun driveAccountMenu(vm: BrowserViewModel, acc: com.droid.wayfinder.data.DriveAccount, mount: Mount?): List<MenuEntry> = buildList {
    if (acc.isMounted && acc.mountPath != null) {
        add(item(vm.str(R.string.action_open), Icons.Outlined.FolderOpen) { vm.navigate(acc.mountPath) })
        add(item(vm.str(R.string.action_open_in_new_tab), Icons.Outlined.Tab) { vm.newTab(acc.mountPath, activate = true) })
        add(item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web) { vm.newWindow(acc.mountPath) })
        if (mount != null) {
            add(MenuEntry.Divider)
            add(item(vm.str(R.string.action_unmount), Icons.Outlined.Eject) { vm.eject(mount) })
            add(item(vm.str(R.string.action_forget), Icons.Outlined.Close) { vm.forgetMount(mount) })
        }
    } else {
        add(item(vm.str(R.string.action_browse_drive_rest), Icons.Outlined.FolderOpen) { vm.navigate("gdrive://${acc.email}/root") })
        add(item(vm.str(R.string.action_mount_drive_saf), Icons.Outlined.AddToDrive) { vm.requestMountSaf(acc.email) })
    }
}

fun oneDriveAccountMenu(vm: BrowserViewModel, acc: com.droid.wayfinder.data.OneDriveAccount): List<MenuEntry> = buildList {
    add(item(vm.str(R.string.action_open), Icons.Outlined.FolderOpen) { vm.navigate(acc.rootPath) })
    add(item(vm.str(R.string.action_open_in_new_tab), Icons.Outlined.Tab) { vm.newTab(acc.rootPath, activate = true) })
    add(item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web) { vm.newWindow(acc.rootPath) })
    add(item(vm.str(R.string.onedrive_action_open_web), Icons.AutoMirrored.Outlined.OpenInNew) { vm.openInOneDriveWeb(acc.rootPath) })
    add(MenuEntry.Divider)
    add(item(vm.str(R.string.onedrive_action_refresh_quota), Icons.Outlined.Refresh) { vm.refreshOneDriveQuota(acc.accountId) })
    add(item(vm.str(R.string.onedrive_add_account), Icons.Outlined.Add) { vm.showConnectOneDriveDialog() })
    add(MenuEntry.Divider)
    add(item(vm.str(R.string.onedrive_action_sign_out), Icons.Outlined.Eject) { vm.signOutOneDrive(acc.accountId) })
}

fun phoneMenu(vm: BrowserViewModel, phone: com.droid.wayfinder.data.PairedPhone, mount: Mount? = null): List<MenuEntry> = buildList {
    add(item(vm.str(R.string.action_open), Icons.Outlined.Smartphone) { vm.openPairedPhone(phone) })
    if (phone.isMounted && phone.path != FsPath.PHONE_ROOT) {
        add(item(vm.str(R.string.action_open_in_new_tab), Icons.Outlined.Tab) { vm.newTab(phone.path, activate = true) })
        add(item(vm.str(R.string.action_open_in_new_window), Icons.Outlined.Web) { vm.newWindow(phone.path) })
        if (mount != null) {
            add(MenuEntry.Divider)
            add(item(vm.str(R.string.action_unmount), Icons.Outlined.Eject) { vm.eject(mount) })
            add(item(vm.str(R.string.action_forget), Icons.Outlined.Close) { vm.forgetMount(mount) })
        }
    } else {
        add(item(vm.str(R.string.action_connect_phone_saf), Icons.Outlined.AddToDrive) { vm.requestMountPhoneStorage(phone) })
    }
    add(MenuEntry.Divider)
    add(item(vm.str(R.string.action_quick_share_folder), Icons.Outlined.Folder) { vm.openQuickShareFolder() })
    add(item(vm.str(R.string.action_phone_hub_settings), Icons.Outlined.Settings) { vm.openPhoneHubSettings() })
}

