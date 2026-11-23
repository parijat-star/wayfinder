// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
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
import androidx.compose.ui.platform.LocalDensity
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
fun Toolbar(
    vm: BrowserViewModel,
    s: AppState,
    hasDesktopCaption: Boolean = WindowInsets.captionBar.getTop(LocalDensity.current) > 0,
) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val focusManager = LocalFocusManager.current
    var compactSearchOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().background(cs.surfaceContainerLow)) {
        val wide = maxWidth
        val isCompact = wide < 600.dp
        val isNarrowCompact = wide < 480.dp
        LaunchedEffect(s.focusSearch, isCompact) {
            if (isCompact && s.focusSearch > 0) compactSearchOpen = true
        }
        LaunchedEffect(isCompact) {
            if (!isCompact && compactSearchOpen) compactSearchOpen = false
        }
        val showExpandedCompactSearch = isCompact && (compactSearchOpen || tab.query.isNotEmpty())
        BackHandler(enabled = showExpandedCompactSearch) {
            vm.setQuery("")
            focusManager.clearFocus()
            compactSearchOpen = false
        }
        val iconBtnColors = IconButtonDefaults.iconButtonColors(
            contentColor = cs.onSurfaceVariant,
            disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
        )
        Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showExpandedCompactSearch) {
                ActionTooltip(stringResource(R.string.action_close_search)) {
                    IconButton(
                        {
                            vm.setQuery("")
                            focusManager.clearFocus()
                            compactSearchOpen = false
                        },
                        Modifier.size(48.dp).focusRing(CircleShape),
                        colors = iconBtnColors,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_close_search))
                    }
                }
                SearchField(
                    vm = vm,
                    s = s,
                    modifier = Modifier.weight(1f),
                    autoFocusOnMount = compactSearchOpen,
                    onEscapeCollapse = { compactSearchOpen = false },
                )
                OverflowMenu(
                    vm = vm,
                    s = s,
                    showViewItems = true,
                    showSortItems = true,
                    hasDesktopCaption = hasDesktopCaption,
                    isCompactViewport = true,
                    showCompactNavItems = isNarrowCompact,
                )
            } else {
                if (isCompact) {
                    ActionTooltip(stringResource(R.string.nav_open_sidebar)) {
                        IconButton(
                            { vm.toggleCompactSidebar() },
                            Modifier.size(48.dp).focusRing(CircleShape),
                            colors = iconBtnColors,
                        ) {
                            Icon(Icons.Outlined.Menu, stringResource(R.string.nav_open_sidebar))
                        }
                    }
                }
                NavButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    tooltip = stringResource(R.string.tooltip_back),
                    contentDesc = stringResource(R.string.action_back),
                    enabled = tab.canGoBack,
                ) { vm.back() }
                if (!isNarrowCompact) {
                    NavButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                        tooltip = stringResource(R.string.tooltip_forward),
                        contentDesc = stringResource(R.string.palette_forward),
                        enabled = tab.canGoForward,
                    ) { vm.forward() }
                }
                NavButton(
                    icon = Icons.Outlined.ArrowUpward,
                    tooltip = stringResource(R.string.tooltip_up),
                    contentDesc = stringResource(R.string.action_up),
                    enabled = FsPath.parent(tab.path) != null,
                ) { vm.up() }
                PathBar(vm, s, Modifier.weight(1f))
                if (!isNarrowCompact) {
                    val refreshTooltip = stringResource(if (s.dualPane) R.string.tooltip_refresh_ctrl_r else R.string.tooltip_refresh_f5)
                    val refreshDesc = stringResource(R.string.action_refresh)
                    ActionTooltip(refreshTooltip) {
                        IconButton({ vm.refresh() }, Modifier.size(48.dp).focusRing(CircleShape), colors = iconBtnColors) {
                            Icon(Icons.Outlined.Refresh, refreshDesc)
                        }
                    }
                }
                if (wide > 1120.dp) {
                    ActionTooltip(stringResource(R.string.tooltip_undo)) {
                        IconButton({ vm.undo() }, Modifier.size(48.dp).focusRing(CircleShape), enabled = s.canUndo, colors = iconBtnColors) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo))
                        }
                    }
                    ActionTooltip(stringResource(R.string.tooltip_redo)) {
                        IconButton({ vm.redo() }, Modifier.size(48.dp).focusRing(CircleShape), enabled = s.canRedo, colors = iconBtnColors) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.action_redo))
                        }
                    }
                }
                if (isCompact) {
                    ActionTooltip(stringResource(R.string.search_placeholder)) {
                        IconButton(
                            {
                                compactSearchOpen = true
                                vm.requestSearchFocus()
                            },
                            Modifier.size(48.dp).focusRing(CircleShape),
                            colors = iconBtnColors,
                        ) {
                            Icon(Icons.Outlined.Search, stringResource(R.string.search_placeholder))
                        }
                    }
                } else {
                    SearchField(vm, s, Modifier.width(if (wide > 1160.dp) 260.dp else if (wide > 1000.dp) 200.dp else 176.dp))
                }
                if (wide > 960.dp) ViewToggle(vm, tab.viewMode, s.dualPane)
                if (wide > 760.dp) SortButton(vm, s)
                OverflowMenu(
                    vm = vm,
                    s = s,
                    showViewItems = wide <= 960.dp,
                    showSortItems = wide <= 760.dp,
                    hasDesktopCaption = hasDesktopCaption,
                    isCompactViewport = isCompact,
                    showCompactNavItems = isNarrowCompact,
                )
            }
        }
        if (tab.query.isNotBlank()) SearchFilterRow(vm, tab)
        }
        if (tab.loading || tab.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun NavButton(icon: ImageVector, tooltip: String, contentDesc: String, enabled: Boolean, onClick: () -> Unit) {
    ActionTooltip(tooltip) {
        FilledTonalIconButton(onClick, Modifier.size(48.dp).focusRing(CircleShape), enabled = enabled) { Icon(icon, contentDesc, Modifier.size(20.dp)) }
    }
}

internal fun resolveAddress(input: String, s: AppState? = null): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    when {
        t.startsWith("/") -> return FsPath.normalize(t)
        t.equals("trash", true) -> return FsPath.TRASH_ROOT
        t.equals("network", true) -> return FsPath.SMB_ROOT
        t.equals("recents", true) || t == FsPath.RECENTS_ROOT -> return FsPath.RECENTS_ROOT
        t.equals("starred", true) || t == FsPath.STARRED_ROOT -> return FsPath.STARRED_ROOT
        t.startsWith(FsPath.TAG_PREFIX) && FsPath.tagOf(t) != null -> return t
        FsPath.isDoc(t) || FsPath.isPhone(t) || FsPath.isOneDrive(t) -> return FsPath.normalize(t)
    }
    if (t.equals("phone", ignoreCase = true) || t.startsWith("phone/", ignoreCase = true)) {
        val sub = if (t.length > 5) t.substring(6).trim('/') else ""
        val phoneRoot = s?.pairedPhone?.takeIf { it.isMounted && it.path != FsPath.PHONE_ROOT }?.path
        return when {
            phoneRoot != null && FsPath.isDoc(phoneRoot) -> com.droid.wayfinder.data.SystemDocumentsBridge.childPath(phoneRoot, sub)
            phoneRoot != null -> if (sub.isEmpty()) FsPath.normalize(phoneRoot) else FsPath.normalize("$phoneRoot/$sub")
            sub.isEmpty() -> FsPath.PHONE_ROOT
            else -> FsPath.normalize("${FsPath.PHONE_ROOT}$sub")
        }
    }
    if (s != null) {
        val match = s.rootLabels.entries
            .sortedByDescending { it.value.length }
            .firstOrNull { (_, label) ->
                label.isNotBlank() && (t.equals(label, ignoreCase = true) || t.startsWith("$label/", ignoreCase = true))
            }
        if (match != null) {
            val (rootPath, label) = match
            val sub = if (t.length > label.length) t.substring(label.length + 1).trim('/') else ""
            return when {
                sub.isEmpty() -> FsPath.normalize(rootPath)
                FsPath.isDoc(rootPath) -> com.droid.wayfinder.data.SystemDocumentsBridge.childPath(rootPath, sub)
                else -> FsPath.normalize("$rootPath/$sub")
            }
        }
    }
    // Network addresses (smb://, \\server\share, dav(s)://, ftp(s)://, saf://) go through BrowserViewModel.openAddress.
    return null
}

@Composable
private fun PathBar(vm: BrowserViewModel, s: AppState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val shape = RoundedCornerShape(24.dp)
    if (s.addressEditing) {
        val fr = remember { FocusRequester() }
        val initialAddress = remember(tab.path, s.rootLabels) {
            if (tab.path == FsPath.TRASH_ROOT) "" else s.displayPath(tab.path, "/")
        }
        var tf by remember(tab.path) { mutableStateOf(TextFieldValue(initialAddress, TextRange(0, initialAddress.length))) }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { fr.requestFocus() }
        Row(modifier.heightIn(min = 48.dp).clip(shape).background(cs.surfaceContainerLowest).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                tf, { tf = it }, Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRequester(fr)
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
                    val p = resolveAddress(tf.text, s)
                    if (p != null) vm.navigate(p) else if (!vm.openAddress(tf.text)) vm.setAddressEditing(false)
                }),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        innerTextField()
                    }
                },
            )
        }
    } else {
        val crumbs = FsPath.breadcrumbs(tab.path, s.rootLabels)
        val scroll = rememberScrollState()
        LaunchedEffect(tab.path, crumbs.size) { scroll.scrollTo(scroll.maxValue) }
        Row(
            modifier.heightIn(min = 48.dp).focusRing(shape).clip(shape).background(cs.surfaceContainerHigh)
                .semantics { contentDescription = "Address bar" }
                .clickable { vm.setAddressEditing(true) }.padding(horizontal = 4.dp).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, (label, path) ->
                if (i > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                val last = i == crumbs.lastIndex
                Box(
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).dropTarget(path)
                        .clickable { if (!last) vm.navigate(path) else vm.setAddressEditing(true) }.padding(horizontal = 10.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0) {
                            if (FsPath.isOneDrive(path)) {
                                OneDriveIcon(Modifier.size(18.dp))
                            } else {
                                val ic = when {
                                    path == FsPath.SMB_ROOT -> Icons.Outlined.Dns
                                    path == FsPath.TRASH_ROOT -> Icons.Outlined.Delete
                                    path == FsPath.RECENTS_ROOT -> Icons.Outlined.History
                                    path == FsPath.STARRED_ROOT -> Icons.Outlined.StarOutline
                                    com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(path) -> Icons.Outlined.Smartphone
                                    FsPath.tagOf(path) != null -> Icons.Filled.Circle
                                    else -> rootIcon(s.roots.firstOrNull { it.path == path }?.removable == true)
                                }
                                Icon(ic, null, Modifier.size(18.dp).padding(end = 0.dp), tint = FsPath.tagOf(path)?.let { androidx.compose.ui.graphics.Color(it.argb) } ?: cs.onSurfaceVariant)
                            }
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
private fun SearchField(
    vm: BrowserViewModel,
    s: AppState,
    modifier: Modifier,
    autoFocusOnMount: Boolean = false,
    onEscapeCollapse: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val focusCoord = LocalFocusCoordinator.current
    val searchA11yLabel = stringResource(R.string.search_placeholder)
    val clearSearchLabel = stringResource(R.string.tooltip_clear_search)
    var fieldFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    LaunchedEffect(s.focusSearch, autoFocusOnMount) {
        if (s.focusSearch > 0 || autoFocusOnMount) fr.requestFocus()
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).background(cs.surfaceContainerLowest)
            .then(if (fieldFocused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .padding(start = 12.dp, end = if (tab.query.isNotEmpty()) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 8.dp, end = if (tab.query.isNotEmpty()) 0.dp else 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                tab.query, { vm.setQuery(it) },
                Modifier.fillMaxWidth().sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRequester(fr)
                    .semantics { contentDescription = searchA11yLabel }
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        vm.setTextFocus(it.isFocused)
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            vm.setQuery("")
                            focus.clearFocus()
                            onEscapeCollapse?.invoke()
                            focusCoord.focusFileList()
                            true
                        }
                        else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                            focus.clearFocus()
                            vm.moveFocus(1)
                            focusCoord.focusFileList()
                            true
                        }
                        else false
                    },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); focusCoord.focusFileList() }),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        if (tab.query.isEmpty()) {
                            Text(
                                stringResource(R.string.search_files_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        if (tab.query.isNotEmpty()) {
            ActionTooltip(clearSearchLabel) {
                Box(
                    Modifier.size(48.dp).focusRing(CircleShape).clip(CircleShape).clickable(role = Role.Button, onClickLabel = clearSearchLabel) { vm.setQuery("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Clear, clearSearchLabel, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                }
            }
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
            FilterChip(
                selected = f.scope == sc,
                onClick = { vm.setSearchFilter(f.copy(scope = sc)) },
                label = { Text(sc.label) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(8.dp)),
            )
        }
        VerticalDivider(Modifier.height(20.dp), color = cs.outlineVariant)
        ChipMenu(SearchCategory.entries, f.category, { it.label }, active = f.category != SearchCategory.All) { vm.setSearchFilter(f.copy(category = it)) }
        ChipMenu(ModifiedRange.entries, f.modified, { it.label }, active = f.modified != ModifiedRange.Any) { vm.setSearchFilter(f.copy(modified = it)) }
        if (f.category != SearchCategory.All || f.modified != ModifiedRange.Any) {
            TextButton(
                onClick = { vm.setSearchFilter(f.copy(category = SearchCategory.All, modified = ModifiedRange.Any)) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(20.dp)),
            ) { Text("Clear filters") }
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
            selected = active,
            onClick = { open = true },
            label = { Text(label(selected)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(8.dp)),
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

private data class ViewToggleOption(
    val mode: ViewMode,
    val icon: ImageVector,
    val tooltip: String,
    val contentDesc: String,
)

@Composable
private fun ViewToggle(vm: BrowserViewModel, mode: ViewMode, dual: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dualTooltip = stringResource(R.string.tooltip_dual_pane)
    val dualDesc = stringResource(R.string.action_dual_pane)
    val toggleDualDesc = stringResource(R.string.action_toggle_dual_pane)
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(2.dp)) {
        listOf(
            ViewToggleOption(ViewMode.Details, Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.tooltip_details_view), stringResource(R.string.action_details_view)),
            ViewToggleOption(ViewMode.List, Icons.Outlined.ViewHeadline, stringResource(R.string.action_list_view), stringResource(R.string.action_list_view)),
            ViewToggleOption(ViewMode.Grid, Icons.Outlined.GridView, stringResource(R.string.tooltip_grid_view), stringResource(R.string.action_grid_view)),
            ViewToggleOption(ViewMode.Columns, Icons.Outlined.ViewColumn, stringResource(R.string.tooltip_columns_view), stringResource(R.string.action_columns_view)),
        ).forEach { opt ->
            val sel = opt.mode == mode
            ActionTooltip(opt.tooltip) {
                Box(
                    Modifier.size(width = 48.dp, height = 48.dp)
                        .focusRing(RoundedCornerShape(24.dp))
                        .semantics {
                            role = Role.Tab
                            this.selected = sel
                        }
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (sel) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Tab, onClickLabel = opt.contentDesc) { vm.setViewMode(opt.mode) },
                    contentAlignment = Alignment.Center,
                ) { Icon(opt.icon, opt.contentDesc, Modifier.size(20.dp), tint = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant) }
            }
        }
        ActionTooltip(dualTooltip) {
            Box(
                Modifier.size(width = 48.dp, height = 48.dp)
                    .focusRing(RoundedCornerShape(24.dp))
                    .semantics {
                        role = Role.Checkbox
                        this.selected = dual
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (dual) cs.tertiaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Checkbox, onClickLabel = toggleDualDesc) { vm.toggleDualPane() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.VerticalSplit, dualDesc, Modifier.size(20.dp), tint = if (dual) cs.onTertiaryContainer else cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SortButton(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val tab = s.active
    Box {
        ActionTooltip(stringResource(R.string.sort_button_label)) {
            Row(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusRing(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh)
                    .clickable { open = true }.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Text(stringResource(R.string.sort_button_label), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
            }
        }
        DropdownMenu(open, { open = false }) {
            listOf(
                SortKey.Name to "Name", SortKey.Modified to "Date modified", SortKey.Type to "Type", SortKey.Size to "Size",
                SortKey.Tags to "Tags", SortKey.Permissions to "Permissions",
            ).filter { (k, _) -> s.isSortKeyAvailable(k, tab.viewMode) }.forEach { (k, label) ->
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
private fun OverflowMenu(
    vm: BrowserViewModel,
    s: AppState,
    showViewItems: Boolean = false,
    showSortItems: Boolean = false,
    hasDesktopCaption: Boolean = false,
    isCompactViewport: Boolean = false,
    showCompactNavItems: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val virtual = FsPath.isReadOnly(s.active.path)
    val moreTooltip = stringResource(R.string.tooltip_more_options)
    val moreDesc = stringResource(R.string.action_more_options)
    val focusCoord = LocalFocusCoordinator.current
    val moreRequester = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(moreRequester) {
        focusCoord.activeToolbarLastRequester = moreRequester
        onDispose {
            if (focusCoord.activeToolbarLastRequester === moreRequester) {
                focusCoord.activeToolbarLastRequester = null
            }
        }
    }
    fun check(on: Boolean): @Composable () -> Unit = { if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) }
    Box {
        ActionTooltip(moreTooltip) {
            IconButton(
                { open = true },
                Modifier.size(48.dp).focusRequester(moreRequester).focusRing(CircleShape),
                colors = IconButtonDefaults.iconButtonColors(contentColor = cs.onSurfaceVariant),
            ) { Icon(Icons.Outlined.MoreVert, moreDesc) }
        }
        DropdownMenu(open, { open = false }) {
            if (showCompactNavItems) {
                DropdownMenuItem(
                    { Text("Forward") },
                    { open = false; vm.forward() },
                    enabled = s.active.canGoForward,
                    trailingIcon = { Hint("Alt+→") },
                )
                DropdownMenuItem(
                    { Text("Refresh") },
                    { open = false; vm.refresh() },
                    trailingIcon = { Hint(if (s.dualPane) "Ctrl+R" else "F5") },
                )
                HorizontalDivider()
            }
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
            DropdownMenuItem(
                { Text("Sidebar") },
                { open = false; if (isCompactViewport) vm.toggleCompactSidebar() else vm.toggleSidebar() },
                leadingIcon = check(if (isCompactViewport) s.compactSidebarOpen else s.sidebarVisible),
                trailingIcon = { Hint("Ctrl+\\") },
            )
            DropdownMenuItem({ Text(stringResource(R.string.menu_status_bar)) }, { open = false; vm.toggleStatusBar() }, leadingIcon = check(s.statusBarVisible))
            DropdownMenuItem({ Text("Dual pane") }, { open = false; vm.toggleDualPane() }, leadingIcon = check(s.dualPane), trailingIcon = { Hint("Ctrl+4") })
            if (s.active.viewMode == ViewMode.Details) {
                DropdownMenuItem({ Text(stringResource(R.string.menu_modified_column)) }, { open = false; vm.toggleModifiedColumn() }, leadingIcon = check(s.modifiedColumn))
                DropdownMenuItem({ Text(stringResource(R.string.menu_type_column)) }, { open = false; vm.toggleTypeColumn() }, leadingIcon = check(s.typeColumn))
                DropdownMenuItem({ Text(stringResource(R.string.menu_size_column)) }, { open = false; vm.toggleSizeColumn() }, leadingIcon = check(s.sizeColumn))
                DropdownMenuItem({ Text(stringResource(R.string.menu_tags_column)) }, { open = false; vm.toggleTagsColumn() }, leadingIcon = check(s.tagsColumn))
                DropdownMenuItem({ Text(stringResource(R.string.menu_perms_column)) }, { open = false; vm.togglePermsColumn() }, leadingIcon = check(s.permsColumn))
            }
            if (!hasDesktopCaption) {
                DropdownMenuItem({ Text(stringResource(R.string.menu_hide_status_bar)) }, { open = false; vm.toggleHideStatusBar() }, leadingIcon = check(s.hideStatusBar))
            }
            if (showViewItems) {
                HorizontalDivider()
                listOf(
                    ViewMode.Details to "Details view",
                    ViewMode.List to "List view",
                    ViewMode.Grid to "Grid view",
                    ViewMode.Columns to "Columns view",
                ).forEach { (m, label) ->
                    DropdownMenuItem(
                        { Text(label) },
                        { open = false; vm.setViewMode(m) },
                        leadingIcon = check(s.active.viewMode == m),
                    )
                }
            }
            if (showSortItems) {
                HorizontalDivider()
                listOf(
                    SortKey.Name to "Sort by Name",
                    SortKey.Modified to "Sort by Date modified",
                    SortKey.Type to "Sort by Type",
                    SortKey.Size to "Sort by Size",
                    SortKey.Tags to "Sort by Tags",
                    SortKey.Permissions to "Sort by Permissions",
                ).filter { (k, _) -> s.isSortKeyAvailable(k, s.active.viewMode) }.forEach { (k, label) ->
                    DropdownMenuItem(
                        { Text(label) },
                        { open = false; vm.setSort(k, if (s.active.sortKey == k) s.active.sortAsc else true) },
                        leadingIcon = check(s.active.sortKey == k),
                    )
                }
                DropdownMenuItem(
                    { Text(if (s.active.sortAsc) "Sort descending" else "Sort ascending") },
                    { open = false; vm.setSort(s.active.sortKey, !s.active.sortAsc) },
                    leadingIcon = { Box(Modifier.size(18.dp)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Theme: ${s.theme.label}") }, { vm.cycleTheme() }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Light / Dark / System") })
            DropdownMenuItem({ Text("Zoom in") }, { vm.zoomBy(1) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+=") })
            DropdownMenuItem({ Text("Zoom out") }, { vm.zoomBy(-1) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+-") })
            DropdownMenuItem({ Text("Reset zoom (${(s.zoom * 100).toInt()}%)") }, { open = false; vm.zoomBy(0) }, leadingIcon = { Box(Modifier.size(18.dp)) }, trailingIcon = { Hint("Ctrl+0") })
            HorizontalDivider()
            DropdownMenuItem({ Text("New window") }, { open = false; vm.newWindow() }, trailingIcon = { Hint("Ctrl+N") })
            DropdownMenuItem({ Text("Command palette…") }, { open = false; vm.openPalette() }, trailingIcon = { Hint("Ctrl+K") })
            DropdownMenuItem({ Text("Connect to server…") }, { open = false; vm.showConnectDialog() }, trailingIcon = { Hint("Ctrl+Shift+K") })
            DropdownMenuItem({ Text(stringResource(R.string.palette_connect_onedrive)) }, { open = false; vm.showConnectOneDriveDialog() })
            DropdownMenuItem({ Text(stringResource(R.string.action_default_file_handler)) }, { open = false; vm.showDefaultHandlerDialog() })
            DropdownMenuItem({ Text("Keyboard shortcuts") }, { open = false; vm.showDialog(Dialog.Shortcuts) }, trailingIcon = { Hint("F1") })
            DropdownMenuItem({ Text("About Wayfinder") }, { open = false; vm.showDialog(Dialog.About) })
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
