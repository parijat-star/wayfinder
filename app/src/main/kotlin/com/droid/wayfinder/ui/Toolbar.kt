// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.res.stringResource
import com.droid.wayfinder.R
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ModifiedRange
import com.droid.wayfinder.data.SearchCategory
import com.droid.wayfinder.data.SearchScope
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode

@Composable
fun Toolbar(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    BoxWithConstraints(Modifier.fillMaxWidth().background(cs.surfaceContainerLow)) {
        val wide = maxWidth
        Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NavButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back (Alt+←)", tab.canGoBack) { vm.back() }
            NavButton(Icons.AutoMirrored.Outlined.ArrowForward, "Forward (Alt+→)", tab.canGoForward) { vm.forward() }
            NavButton(Icons.Outlined.ArrowUpward, "Up (Alt+↑)", FsPath.parent(tab.path) != null) { vm.up() }
            PathBar(vm, s, Modifier.weight(1f))
            val iconBtnColors = IconButtonDefaults.iconButtonColors(
                contentColor = cs.onSurfaceVariant,
                disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
            )
            IconButton({ vm.refresh() }, Modifier.size(40.dp), colors = iconBtnColors) {
                Icon(Icons.Outlined.Refresh, if (s.dualPane) "Refresh (Ctrl+R)" else "Refresh (F5)")
            }
            if (wide > 560.dp) {
                IconButton({ vm.undo() }, Modifier.size(40.dp), enabled = s.canUndo, colors = iconBtnColors) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo (Ctrl+Z)")
                }
                IconButton({ vm.redo() }, Modifier.size(40.dp), enabled = s.canRedo, colors = iconBtnColors) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, "Redo (Ctrl+Y)")
                }
            }
            SearchField(vm, s, Modifier.width(if (wide > 960.dp) 260.dp else if (wide > 720.dp) 190.dp else 140.dp))
            if (wide > 640.dp) ViewToggle(vm, tab.viewMode, s.dualPane)
            if (wide > 760.dp) SortButton(vm, s)
            OverflowMenu(vm, s)
        }
        if (tab.query.isNotBlank()) SearchFilterRow(vm, tab)
        }
        if (tab.loading || tab.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun NavButton(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, Modifier.size(40.dp), enabled = enabled) { Icon(icon, desc, Modifier.size(20.dp)) }
}

private fun resolveAddress(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    return when {
        t.startsWith("/") -> FsPath.normalize(t)
        t.equals("trash", true) -> FsPath.TRASH_ROOT
        t.equals("network", true) -> FsPath.SMB_ROOT
        t.equals("recents", true) || t == FsPath.RECENTS_ROOT -> FsPath.RECENTS_ROOT
        t.equals("starred", true) || t == FsPath.STARRED_ROOT -> FsPath.STARRED_ROOT
        t.startsWith(FsPath.TAG_PREFIX) && FsPath.tagOf(t) != null -> t
        // Network addresses (smb://, \\server\share, dav(s)://, ftp(s)://, saf://) go through BrowserViewModel.openAddress.
        else -> null
    }
}

@Composable
private fun PathBar(vm: BrowserViewModel, s: AppState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val shape = RoundedCornerShape(24.dp)
    if (s.addressEditing) {
        val fr = remember { FocusRequester() }
        var tf by remember(tab.path) { mutableStateOf(TextFieldValue(tab.path.removePrefix("trash://"), TextRange(0, tab.path.length))) }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { fr.requestFocus() }
        Row(modifier.heightIn(min = 40.dp).clip(shape).background(cs.surfaceContainerLowest).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                tf, { tf = it }, Modifier.weight(1f).focusRequester(fr)
                    .semantics { contentDescription = "Address bar" }
                    .onFocusChanged {
                        // The initial (unfocused) event arrives before requestFocus(); only a real blur ends editing.
                        if (it.isFocused) { hadFocus = true; vm.setTextFocus(true) }
                        else if (hadFocus && s.addressEditing) { vm.setAddressEditing(false); vm.setTextFocus(false) }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { vm.setAddressEditing(false); true } else false
                    },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val p = resolveAddress(tf.text)
                    if (p != null) vm.navigate(p) else if (!vm.openAddress(tf.text)) vm.setAddressEditing(false)
                }),
            )
        }
    } else {
        val crumbs = FsPath.breadcrumbs(tab.path, s.rootLabels)
        val scroll = rememberScrollState()
        LaunchedEffect(tab.path, crumbs.size) { scroll.scrollTo(scroll.maxValue) }
        Row(
            modifier.heightIn(min = 40.dp).clip(shape).background(cs.surfaceContainerHigh)
                .clickable { vm.setAddressEditing(true) }.padding(horizontal = 6.dp, vertical = 4.dp).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, (label, path) ->
                if (i > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                val last = i == crumbs.lastIndex
                Box(
                    Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).dropTarget(path)
                        .clickable { if (!last) vm.navigate(path) else vm.setAddressEditing(true) }.padding(horizontal = 10.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0) {
                            val ic = when {
                                path == FsPath.SMB_ROOT -> Icons.Outlined.Dns
                                path == FsPath.TRASH_ROOT -> Icons.Outlined.Delete
                                path == FsPath.RECENTS_ROOT -> Icons.Outlined.History
                                path == FsPath.STARRED_ROOT -> Icons.Outlined.StarOutline
                                FsPath.tagOf(path) != null -> Icons.Filled.Circle
                                else -> rootIcon(s.roots.firstOrNull { it.path == path }?.removable == true)
                            }
                            Icon(ic, null, Modifier.size(18.dp).padding(end = 0.dp), tint = FsPath.tagOf(path)?.let { androidx.compose.ui.graphics.Color(it.argb) } ?: cs.onSurfaceVariant)
                            Box(Modifier.width(6.dp))
                        }
                        Text(
                            label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Clip,
                            color = if (last) cs.onSurface else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(vm: BrowserViewModel, s: AppState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val searchA11yLabel = stringResource(R.string.search_placeholder)
    LaunchedEffect(s.focusSearch) { if (s.focusSearch > 0) fr.requestFocus() }
    Row(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (tab.query.isEmpty()) Text(stringResource(R.string.search_files_placeholder), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                tab.query, { vm.setQuery(it) },
                Modifier.fillMaxWidth().focusRequester(fr)
                    .semantics { contentDescription = searchA11yLabel }
                    .onFocusChanged { vm.setTextFocus(it.isFocused) }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { vm.setQuery(""); focus.clearFocus(); true }
                        else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { focus.clearFocus(); vm.moveFocus(1); true }
                        else false
                    },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
        }
        if (tab.query.isNotEmpty()) {
            Icon(Icons.Outlined.Clear, "Clear search", Modifier.size(18.dp).clip(CircleShape).clickable { vm.setQuery("") }, tint = cs.onSurfaceVariant)
        }
    }
}

/** Chip row under the toolbar while searching: scope, category and modified-date filters. */
@Composable
private fun SearchFilterRow(vm: BrowserViewModel, tab: TabState) {
    val cs = MaterialTheme.colorScheme
    val f = tab.searchFilter
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchScope.entries.forEach { sc ->
            FilterChip(f.scope == sc, { vm.setSearchFilter(f.copy(scope = sc)) }, label = { Text(sc.label) })
        }
        VerticalDivider(Modifier.height(20.dp), color = cs.outlineVariant)
        ChipMenu(SearchCategory.entries, f.category, { it.label }, active = f.category != SearchCategory.All) { vm.setSearchFilter(f.copy(category = it)) }
        ChipMenu(ModifiedRange.entries, f.modified, { it.label }, active = f.modified != ModifiedRange.Any) { vm.setSearchFilter(f.copy(modified = it)) }
        if (f.category != SearchCategory.All || f.modified != ModifiedRange.Any) {
            TextButton({ vm.setSearchFilter(f.copy(category = SearchCategory.All, modified = ModifiedRange.Any)) }) { Text("Clear filters") }
        }
        val n = tab.searchResults?.size
        if (n != null) Text(
            if (tab.searching) "${formatLocalizedNumber(n.toLong())} found so far…" else icuStringResource(R.string.search_result_count_plural, n),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ChipMenu(options: List<T>, selected: T, label: (T) -> String, active: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            active, { open = true }, label = { Text(label(selected)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    { Text(label(o)) }, { open = false; onPick(o) },
                    leadingIcon = { if (o == selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun ViewToggle(vm: BrowserViewModel, mode: ViewMode, dual: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(2.dp)) {
        listOf(
            Triple(ViewMode.Details, Icons.AutoMirrored.Outlined.ViewList, "Details (Ctrl+1)"),
            Triple(ViewMode.List, Icons.Outlined.ViewHeadline, "List"),
            Triple(ViewMode.Grid, Icons.Outlined.GridView, "Grid (Ctrl+2)"),
            Triple(ViewMode.Columns, Icons.Outlined.ViewColumn, "Columns (Ctrl+3)"),
        ).forEach { (m, icon, desc) ->
            val sel = m == mode
            Box(
                Modifier.size(width = 40.dp, height = 36.dp)
                    .semantics {
                        role = Role.Tab
                        this.selected = sel
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sel) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = desc) { vm.setViewMode(m) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, desc, Modifier.size(20.dp), tint = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant) }
        }
        Box(
            Modifier.size(width = 40.dp, height = 36.dp)
                .semantics {
                    role = Role.Checkbox
                    this.selected = dual
                }
                .clip(RoundedCornerShape(18.dp))
                .background(if (dual) cs.tertiaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = Role.Checkbox, onClickLabel = "Toggle dual pane") { vm.toggleDualPane() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.VerticalSplit, "Dual pane (Ctrl+4 / F3)", Modifier.size(20.dp), tint = if (dual) cs.onTertiaryContainer else cs.onSurfaceVariant) }
    }
}

@Composable
private fun SortButton(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val tab = s.active
    Box {
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)
                .clickable { open = true }.padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            Text(stringResource(R.string.sort_button_label), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
        }
        DropdownMenu(open, { open = false }) {
            listOf(
                SortKey.Name to "Name", SortKey.Modified to "Date modified", SortKey.Type to "Type", SortKey.Size to "Size",
                SortKey.Tags to "Tags", SortKey.Permissions to "Permissions",
            ).forEach { (k, label) ->
                DropdownMenuItem(
                    { Text(label) },
                    { vm.setSort(k, if (tab.sortKey == k) tab.sortAsc else true); open = false },
                    leadingIcon = { if (tab.sortKey == k) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                { Text("Ascending") }, { vm.setSort(tab.sortKey, true); open = false },
                leadingIcon = { if (tab.sortAsc) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) },
            )
            DropdownMenuItem(
                { Text("Descending") }, { vm.setSort(tab.sortKey, false); open = false },
                leadingIcon = { if (!tab.sortAsc) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun OverflowMenu(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val virtual = FsPath.isReadOnly(s.active.path)
    fun check(on: Boolean): @Composable () -> Unit = { if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) }
    Box {
        IconButton(
            { open = true },
            Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = cs.onSurfaceVariant),
        ) { Icon(Icons.Outlined.MoreVert, "More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("New folder") }, { open = false; vm.requestNewItem(true) }, enabled = !virtual, trailingIcon = { Hint("Ctrl+Shift+N") })
            DropdownMenuItem({ Text("New text file") }, { open = false; vm.requestNewItem(false) }, enabled = !virtual, trailingIcon = { Hint("Ctrl+Shift+M") })
            DropdownMenuItem({ Text("Select all") }, { open = false; vm.selectAll() }, trailingIcon = { Hint("Ctrl+A") })
            val pinTargets = s.active.selectedEntries(s.showHidden).filter { !it.isServer && !it.isShare && !FsPath.isZip(it.path) && !FsPath.isVirtual(it.path) }.map { it.path }
                .ifEmpty { listOf(s.active.path).filter { !FsPath.isVirtual(it) && !FsPath.isZip(it) } }
            if (pinTargets.isNotEmpty()) {
                val pinned = pinTargets.all { it in s.pins }
                DropdownMenuItem(
                    { Text(if (pinned) "Unpin from sidebar" else "Pin to sidebar") },
                    { open = false; vm.togglePins(pinTargets) },
                    trailingIcon = { Hint("Ctrl+B") },
                )
            }
            DropdownMenuItem({ Text("Batch rename…") }, { open = false; vm.requestBatchRename() }, enabled = !virtual, trailingIcon = { Hint("Ctrl+Shift+R") })
            DropdownMenuItem({ Text("Undo") }, { open = false; vm.undo() }, enabled = s.canUndo, trailingIcon = { Hint("Ctrl+Z") })
            DropdownMenuItem({ Text("Redo") }, { open = false; vm.redo() }, enabled = s.canRedo, trailingIcon = { Hint("Ctrl+Y") })
            HorizontalDivider()
            DropdownMenuItem({ Text("Show hidden files") }, { open = false; vm.toggleHidden() }, leadingIcon = check(s.showHidden), trailingIcon = { Hint("Ctrl+H") })
            DropdownMenuItem({ Text("Preview pane") }, { open = false; vm.togglePreview() }, leadingIcon = check(s.previewVisible), trailingIcon = { Hint("Alt+P") })
            DropdownMenuItem({ Text("Sidebar") }, { open = false; vm.toggleSidebar() }, leadingIcon = check(s.sidebarVisible), trailingIcon = { Hint("Ctrl+\\") })
            DropdownMenuItem({ Text("Dual pane") }, { open = false; vm.toggleDualPane() }, leadingIcon = check(s.dualPane), trailingIcon = { Hint("Ctrl+4") })
            DropdownMenuItem({ Text("Tags column") }, { open = false; vm.toggleTagsColumn() }, leadingIcon = check(s.tagsColumn))
            DropdownMenuItem({ Text("Permissions column") }, { open = false; vm.togglePermsColumn() }, leadingIcon = check(s.permsColumn))
            HorizontalDivider()
            DropdownMenuItem({ Text("Theme: ${s.theme.label}") }, { vm.cycleTheme() }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Light / Dark / System") })
            DropdownMenuItem({ Text("Zoom in") }, { vm.zoomBy(1) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+=") })
            DropdownMenuItem({ Text("Zoom out") }, { vm.zoomBy(-1) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+-") })
            DropdownMenuItem({ Text("Reset zoom (${(s.zoom * 100).toInt()}%)") }, { open = false; vm.zoomBy(0) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+0") })
            HorizontalDivider()
            DropdownMenuItem({ Text("New window") }, { open = false; vm.newWindow() }, trailingIcon = { Hint("Ctrl+N") })
            DropdownMenuItem({ Text("Command palette…") }, { open = false; vm.openPalette() }, trailingIcon = { Hint("Ctrl+K") })
            DropdownMenuItem({ Text("Connect to server…") }, { open = false; vm.showConnectDialog() }, trailingIcon = { Hint("Ctrl+Shift+K") })
            DropdownMenuItem({ Text("Keyboard shortcuts") }, { open = false; vm.showDialog(Dialog.Shortcuts) }, trailingIcon = { Hint("F1") })
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
