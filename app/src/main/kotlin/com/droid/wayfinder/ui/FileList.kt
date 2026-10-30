// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import com.droid.wayfinder.data.formatItemCount
import com.droid.wayfinder.data.formatSelectedCount
import com.droid.wayfinder.data.sortEntries
import android.app.Activity
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.text.font.FontFamily
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.SearchScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.data.formatSize
import java.io.File

/** Holds per-tab scroll state so switching tabs keeps your place. */
class ScrollStates {
    val lists = HashMap<Long, LazyListState>()
    val grids = HashMap<Long, LazyGridState>()
    val lastPath = HashMap<Long, String>()
}

@Composable
fun ContentCard(vm: BrowserViewModel, s: AppState, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize()) {
            val sec = s.secondary
            if (s.dualPane && sec != null) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pane(vm, s, s.mainTab, right = false, Modifier.weight(1f).fillMaxSize())
                    Pane(vm, s, sec, right = true, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) { FolderView(vm, s, s.active, active = true) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StatusBar(vm, s)
        }
    }
}

/** One side of the dual-pane commander: a path header plus an independent folder view. */
@Composable
private fun Pane(vm: BrowserViewModel, s: AppState, tab: TabState, right: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = s.rightActive == right
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape)
            .border(if (active) 2.dp else 1.dp, if (active) cs.primary else cs.outlineVariant, shape)
            // Any press inside a pane focuses it first, so the click acts on that pane.
            .pointerInput(right) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vm.activatePane(right)
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).background(if (active) cs.secondaryContainer else cs.surfaceContainerLow)
                .dropTarget(tab.path.takeIf { !FsPath.isVirtual(it) }).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant)
            Text(
                if (FsPath.isVirtual(tab.path)) s.titleOf(tab.path) else tab.path, Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                color = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant,
            )
            if (tab.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { FolderView(vm, s, tab, active) }
    }
}

@Composable
private fun FolderView(vm: BrowserViewModel, s: AppState, tab: TabState, active: Boolean) {
    val visible = remember(tab.entries, tab.searchResults, tab.sortKey, tab.sortAsc, s.showHidden, s.marks) { tab.visible(s.showHidden) }
    Column(Modifier.fillMaxSize().externalDrop(vm, tab.path)) {
        if (tab.path == FsPath.PHONE_ROOT && s.phoneConnected) {
            PhoneQuickAccessBanner(vm, s.pairedPhone)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab.viewMode == ViewMode.Columns && tab.searchResults == null && tab.error == null -> MillerColumns(vm, s, tab, visible, active)
                tab.error != null && visible.isEmpty() -> ErrorView(vm, tab)
                tab.loading && tab.entries.isEmpty() && tab.searchResults == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                visible.isEmpty() -> EmptyView(vm, s, tab)
                else -> EntryList(vm, s, tab, visible, active)
            }
        }
    }
}

@Composable
private fun PhoneQuickAccessBanner(vm: BrowserViewModel, phone: com.droid.wayfinder.data.PairedPhone?) {
    val cs = MaterialTheme.colorScheme
    val phoneName = phone?.name ?: "Pixel 9"
    val subtitle = phone?.subtitle ?: "Internal Storage"
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                Icon(Icons.Outlined.Smartphone, null, Modifier.size(24.dp), tint = cs.primary)
                Column(Modifier.padding(start = 12.dp, end = 12.dp)) {
                    Text("$phoneName · $subtitle", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                    Text("Paired with laptop via Cross-Device Services / Phone Hub", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.openQuickShareFolder() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp))
                    Text("Quick Share", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { vm.openPhoneHubSettings() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                    Text("Phone Hub", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Accepts files dragged in from other apps (system drag and drop) and copies them into [dest].
 * In-app drags use [DragController] instead, so they never reach this target.
 */
@Composable
private fun Modifier.externalDrop(vm: BrowserViewModel, dest: String): Modifier {
    val activity = LocalContext.current as? Activity
    val cs = MaterialTheme.colorScheme
    val hovering = remember { mutableStateOf(false) }
    val target = remember(dest, activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering.value = false
                val de = event.toAndroidDragEvent()
                val clip = de.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                val perms = activity?.requestDragAndDropPermissions(de)
                vm.importUris(uris, dest) { perms?.release() }
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { hovering.value = true }
            override fun onExited(event: DragAndDropEvent) { hovering.value = false }
            override fun onEnded(event: DragAndDropEvent) { hovering.value = false }
        }
    }
    return this
        .dragAndDropTarget(shouldStartDragAndDrop = { e -> !FsPath.isReadOnly(dest) && e.mimeTypes().isNotEmpty() }, target = target)
        .then(if (hovering.value) Modifier.border(2.dp, cs.primary, RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.06f)) else Modifier)
}

/** Pin, star, and color-tag badges shown next to an item's name. */
@Composable
fun MarkBadges(s: AppState, path: String, modifier: Modifier = Modifier, dot: Dp = 8.dp, showStar: Boolean = true, showTags: Boolean = true) {
    val starred = showStar && s.marks.isStarred(path)
    val pinned = showStar && path in s.pins
    val tags = if (showTags) s.marks.tagsOf(path).sortedBy { it.ordinal } else emptyList()
    if (!starred && !pinned && tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pinned) Icon(androidx.compose.material.icons.Icons.Filled.PushPin, "Pinned", Modifier.size(dot + 6.dp), tint = MaterialTheme.colorScheme.primary)
        if (starred) Icon(Icons.Filled.Star, "Starred", Modifier.size(dot + 6.dp), tint = StarColor)
        tags.forEach { t ->
            Box(
                Modifier.size(dot)
                    .semantics { contentDescription = "Tagged ${t.label}" }
                    .clip(CircleShape)
                    .background(Color(t.argb)),
            )
        }
    }
}

@Composable
private fun CenterMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String?, actions: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun ErrorView(vm: BrowserViewModel, tab: TabState) {
    val e = tab.error!!
    CenterMessage(Icons.Outlined.ErrorOutline, "Can't open this location", e.message) {
        if (FsPath.isGdrive(tab.path)) {
            val email = tab.path.removePrefix("gdrive://").substringBefore('/')
            Button({ vm.requestMountSaf(email) }) { Text("Mount via File Picker (SAF)") }
        }
        if (e.authHost != null) Button({ vm.showDialog(Dialog.SmbAuth(e.authHost)) }) { Text("Sign in") }
        OutlinedButton({ vm.refresh() }) { Text("Retry") }
        if (tab.canGoBack) OutlinedButton({ vm.back() }) { Text("Go back") }
    }
}

@Composable
private fun EmptyView(vm: BrowserViewModel, s: AppState, tab: TabState) {
    val menu = LocalMenu.current
    var origin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (currentEvent.buttons.isSecondaryPressed) {
                    down.consume(); menu.show(origin + down.position, backgroundMenu(vm, vm.state.value))
                }
            }
        },
    ) {
        when {
            tab.query.isNotBlank() -> if (tab.searching) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else CenterMessage(
                Icons.Outlined.SearchOff, "No results",
                "Nothing matches “${tab.query}” " + (if (tab.searchFilter.scope == SearchScope.Everywhere) "on this device" else "in this folder or its subfolders") +
                    (if (tab.searchFilter.category.ordinal != 0 || tab.searchFilter.modified.ordinal != 0) " with the current filters." else "."),
            )
            tab.path == FsPath.RECENTS_ROOT -> CenterMessage(Icons.Outlined.History, "No recent files", "Files changed in the last 30 days appear here.")
            tab.path == FsPath.STARRED_ROOT -> CenterMessage(Icons.Outlined.StarOutline, "No starred items", "Right-click a file or folder and choose “Add to Starred”.")
            tab.path == FsPath.PHONE_ROOT -> CenterMessage(
                Icons.Outlined.Smartphone,
                s.pairedPhone?.name ?: "Connect Phone Storage",
                if (s.pairedPhone != null && !s.pairedPhone.isMounted) {
                    "Grant access to browse ${s.pairedPhone.name}'s files, or enable File access in Cross-Device Services (Phone Hub) or USB File Transfer (MTP)."
                } else {
                    "No phone storage is currently mounted. Link your phone in Cross-Device Services (Phone Hub) or connect via USB File Transfer (MTP)."
                },
            ) {
                Button({ vm.requestMountPhoneStorage() }) {
                    Icon(Icons.Outlined.Smartphone, null, Modifier.size(18.dp))
                    Text("Connect phone storage…", Modifier.padding(start = 6.dp))
                }
                if (!s.bluetoothPermissionGranted) {
                    OutlinedButton({ vm.requestBluetoothPermission() }) { Text("Allow Bluetooth detection") }
                }
                OutlinedButton({ vm.openPhoneHubSettings() }) { Text("Phone Hub settings") }
                OutlinedButton({ vm.openQuickShareFolder() }) { Text("Quick Share folder") }
            }
            FsPath.tagOf(tab.path) != null -> CenterMessage(Icons.AutoMirrored.Outlined.Label, "Nothing tagged “${FsPath.name(tab.path)}”", "Right-click items and choose Color tags to label them.")
            FsPath.isZip(tab.path) -> CenterMessage(Icons.Outlined.FolderOpen, "This folder in the archive is empty", null)
            tab.path == FsPath.SMB_ROOT -> CenterMessage(
                Icons.Outlined.WifiFind, if (s.scanning) "Looking for servers…" else "No servers found",
                "Connect to a server by address, e.g. smb://nas/media, davs://cloud/dav or ftp://host",
            ) { Button({ vm.showConnectDialog() }) { Text("Connect to server") }; OutlinedButton({ vm.refresh() }) { Text("Rescan") } }
            tab.path == FsPath.TRASH_ROOT -> CenterMessage(Icons.Outlined.FolderOpen, "Trash is empty", "Deleted files appear here until you empty the Trash.")
            else -> CenterMessage(Icons.Outlined.FolderOpen, "No files in this folder", "Right-click or press Ctrl+Shift+N to create a new folder.")
        }
    }
}

@Composable
private fun EntryList(vm: BrowserViewModel, s: AppState, tab: TabState, entries: List<FileEntry>, active: Boolean) {
    val menu = LocalMenu.current
    val density = LocalDensity.current
    val rb = remember(tab.id) { RubberBandState() }
    val scrolls = remember { ScrollStates() }
    val listState = scrolls.lists.getOrPut(tab.id) { LazyListState() }
    val gridState = scrolls.grids.getOrPut(tab.id) { LazyGridState() }
    var origin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val cs = MaterialTheme.colorScheme

    // Reset scroll when navigating within a tab (but not when switching tabs).
    LaunchedEffect(tab.id, tab.path) {
        if (scrolls.lastPath[tab.id] != tab.path) {
            scrolls.lastPath[tab.id] = tab.path
            listState.scrollToItem(0); gridState.scrollToItem(0)
        }
    }
    // Keep the keyboard-focused item visible.
    LaunchedEffect(tab.focused, tab.viewMode) {
        val idx = entries.indexOfFirst { it.path == tab.focused }
        if (idx < 0) return@LaunchedEffect
        if (tab.viewMode == ViewMode.Grid) {
            val info = gridState.layoutInfo.visibleItemsInfo
            val first = info.firstOrNull()?.index ?: 0
            val last = info.lastOrNull()?.index ?: 0
            if (idx < first) gridState.animateScrollToItem(idx)
            else if (idx > last - 1) gridState.animateScrollToItem((idx - (last - first) + s.gridColumns).coerceAtLeast(0))
        } else {
            val info = listState.layoutInfo.visibleItemsInfo
            val first = info.firstOrNull()?.index ?: 0
            val last = info.lastOrNull()?.index ?: 0
            if (idx <= first) listState.animateScrollToItem(idx)
            else if (idx >= last) listState.animateScrollToItem((idx - (last - first) + 2).coerceAtLeast(0))
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                // Ctrl + scroll zooms the grid.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Scroll && e.keyboardModifiers.isCtrlPressed && vm.state.value.active.viewMode == ViewMode.Grid) {
                            val dy = e.changes.first().scrollDelta.y
                            vm.setGridSize(vm.state.value.gridSize - dy * 12f)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .rubberBand(
                rb,
                onSelect = { set, additive -> vm.setSelection(if (additive) tab.selection + set else set) },
                onEmptyClick = { vm.clearSelection() },
                onSecondary = { vm.clearSelection(); menu.show(it, backgroundMenu(vm, vm.state.value)) },
            ),
    ) {
        val width = maxWidth
        when (tab.viewMode) {
            ViewMode.Grid -> {
                val cell = s.gridSize.dp + 12.dp
                val cols = (with(density) { constraints.maxWidth.toDp() } / cell).toInt().coerceAtLeast(1)
                LaunchedEffect(cols, constraints.maxHeight, active) { if (active) vm.setGridColumns(cols, (constraints.maxHeight / density.density / 120f).toInt().coerceAtLeast(3)) }
                LazyVerticalGrid(
                    GridCells.Adaptive(cell), Modifier.fillMaxSize(), gridState,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(entries, key = { it.path }) { e -> GridCell(vm, s, tab, e, rb) }
                }
            }
            else -> {
                val details = tab.viewMode == ViewMode.Details
                LaunchedEffect(active, details) { if (active) vm.setGridColumns(1, (constraints.maxHeight / density.density / (if (details) 40f else 32f)).toInt().coerceAtLeast(3)) }
                Column(Modifier.fillMaxSize()) {
                    if (details) DetailsHeader(vm, s, tab, width)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(entries, key = { it.path }) { e -> EntryRow(vm, s, tab, e, rb, details, width) }
                        item("filler") { Box(Modifier.fillParentMaxHeightCompat().fillMaxWidth()) }
                    }
                }
            }
        }
        val r = rb.rect
        if (r != null) {
            val tl = r.topLeft - origin
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(tl.x.toInt(), tl.y.toInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                    .background(cs.primary.copy(alpha = 0.14f)).border(1.dp, cs.primary.copy(alpha = 0.6f)),
            )
        }
    }
}

// Spacer item that gives rubber-band/context-click room below the last row.
private fun Modifier.fillParentMaxHeightCompat(): Modifier = this.height(160.dp)

private const val DATE_W = 168
private const val TYPE_W = 132
private const val SIZE_W = 88
private const val TAGS_W = 84
private const val PERMS_W = 100

private fun showTagsCol(s: AppState, effectiveWidth: Dp) = s.tagsColumn && effectiveWidth > 440.dp
private fun showPermsCol(s: AppState, effectiveWidth: Dp) = s.permsColumn && effectiveWidth > 480.dp

/** Right-click menu of the Details header: toggles the optional columns. */
fun columnsMenu(vm: BrowserViewModel, s: AppState): List<MenuEntry> = listOf(
    MenuEntry.Item("Tags column", Icons.AutoMirrored.Outlined.Label, checked = s.tagsColumn) { vm.toggleTagsColumn() },
    MenuEntry.Item("Permissions column", Icons.Outlined.Lock, checked = s.permsColumn) { vm.togglePermsColumn() },
)

@Composable
private fun DetailsHeader(vm: BrowserViewModel, s: AppState, tab: TabState, width: Dp) {
    val cs = MaterialTheme.colorScheme
    val menu = LocalMenu.current
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
    val effectiveWidth = width / fontScale
    var origin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (currentEvent.buttons.isSecondaryPressed) {
                            down.consume(); menu.show(origin + down.position, columnsMenu(vm, vm.state.value))
                        }
                    }
                }
                .padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun cell(label: String, key: SortKey, mod: Modifier, end: Boolean = false) {
                Row(
                    mod.fillMaxHeightCompat().clickable(role = Role.Button, onClickLabel = "Sort by $label") { vm.setSort(key) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tab.sortKey == key) Icon(
                        if (tab.sortAsc) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                        if (tab.sortAsc) "Sorted ascending" else "Sorted descending", Modifier.padding(start = 4.dp).size(14.dp), tint = cs.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.width(34.dp))
            cell("Name", SortKey.Name, Modifier.weight(1f))
            if (showTagsCol(s, effectiveWidth)) cell("Tags", SortKey.Tags, Modifier.width((TAGS_W * fontScale).dp))
            if (showPermsCol(s, effectiveWidth)) cell("Permissions", SortKey.Permissions, Modifier.width((PERMS_W * fontScale).dp))
            if (effectiveWidth > 520.dp) cell("Date modified", SortKey.Modified, Modifier.width((DATE_W * fontScale).dp))
            if (effectiveWidth > 760.dp) cell("Type", SortKey.Type, Modifier.width((TYPE_W * fontScale).dp))
            cell("Size", SortKey.Size, Modifier.width((SIZE_W * fontScale).dp), end = true)
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
    }
}

private fun Modifier.fillMaxHeightCompat(): Modifier = this.heightIn(min = 28.dp)

/** Selection, drag-and-drop, click and context-menu behaviour shared by rows and grid cells. */
@Composable
private fun Modifier.entryInteractions(vm: BrowserViewModel, tab: TabState, e: FileEntry, rb: RubberBandState): Modifier {
    val menu = LocalMenu.current
    val selected = e.path in tab.selection
    DisposableEffect(e.path) { onDispose { rb.itemBounds.remove(e.path) } }
    val dropPath = if (e.isDir && !selected && !e.isServer) e.path else null
    return this
        .onGloballyPositioned { rb.itemBounds[e.path] = it.boundsInRoot() }
        .dropTarget(dropPath)
        .dragSource(
            e.path,
            paths = { vm.state.value.active.let { t -> if (e.path in t.selection) t.selection.toList() else listOf(e.path) }.filter { !FsPath.isVirtual(it) && !(vm.state.value.active.path == FsPath.SMB_ROOT) } },
            onStart = { vm.setSelectionIfNotSelected(e.path) },
        )
        .mouseGestures(
            e.path,
            role = Role.Button,
            selected = selected,
            onClickLabel = "Open ${e.name}",
            onClick = { ctrl, shift, touch ->
                if (touch) {
                    if (vm.state.value.active.selection.isNotEmpty()) vm.click(e.path, ctrl = true, shift = false) else vm.open(e)
                } else vm.click(e.path, ctrl, shift)
            },
            onDoubleClick = { vm.open(e) },
            onSecondary = { off ->
                vm.setSelectionIfNotSelected(e.path)
                menu.show(off, fileMenu(vm, vm.state.value, e))
            },
            onMiddle = { if (e.isDir) vm.open(e, newTab = true) },
            onLongPress = { vm.click(e.path, ctrl = true, shift = false) },
        )
}

@Composable
private fun EntryRow(vm: BrowserViewModel, s: AppState, tab: TabState, e: FileEntry, rb: RubberBandState, details: Boolean, width: Dp) {
    val cs = MaterialTheme.colorScheme
    val selected = e.path in tab.selection
    val focused = e.path == tab.focused
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val dropHover = drag.active && drag.hover == e.path
    val cut = s.clipboard?.cut == true && e.path in s.clipboard.paths
    val bg = when {
        dropHover -> cs.primaryContainer
        selected -> cs.secondaryContainer
        hovered -> cs.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(if (details) 20.dp else 16.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = if (details) 40.dp else 34.dp)
            .alpha(if (cut) 0.5f else 1f)
            .clip(shape).background(bg)
            .then(if (focused) Modifier.border(2.dp, cs.primary.copy(alpha = 0.7f), shape) else Modifier)
            .hoverable(hover).entryInteractions(vm, tab, e, rb)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(e.icon(), null, Modifier.size(22.dp), tint = e.tint())
        val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
        val effectiveWidth = width / fontScale
        val tagsCol = details && showTagsCol(s, effectiveWidth)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
                MarkBadges(s, e.path, Modifier.padding(start = 8.dp), showTags = !tagsCol)
            }
            val sub = e.subtitle ?: if (tab.searchResults != null || (FsPath.isCollection(tab.path) && tab.path != FsPath.RECENTS_ROOT)) FsPath.parent(e.path) else null
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (details) {
            if (tagsCol) Box(Modifier.width((TAGS_W * fontScale).dp)) { MarkBadges(s, e.path, showStar = false) }
            if (showPermsCol(s, effectiveWidth)) Text(
                if (e.mode != 0) FileInfo.posixString(e.mode) else "—", Modifier.width((PERMS_W * fontScale).dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant, maxLines = 1,
            )
            if (effectiveWidth > 520.dp) Text(formatDate(e.modified), Modifier.width((DATE_W * fontScale).dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (effectiveWidth > 760.dp) Text(e.typeLabel, Modifier.width((TYPE_W * fontScale).dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (e.isDir) "—" else formatSize(e.size), Modifier.width((SIZE_W * fontScale).dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun GridCell(vm: BrowserViewModel, s: AppState, tab: TabState, e: FileEntry, rb: RubberBandState) {
    val cs = MaterialTheme.colorScheme
    val selected = e.path in tab.selection
    val focused = e.path == tab.focused
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val dropHover = drag.active && drag.hover == e.path
    val cut = s.clipboard?.cut == true && e.path in s.clipboard.paths
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        dropHover -> cs.primaryContainer
        selected -> cs.secondaryContainer
        hovered -> cs.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Column(
        Modifier.padding(3.dp).alpha(if (cut) 0.5f else 1f).clip(shape).background(bg)
            .then(if (focused) Modifier.border(2.dp, cs.primary.copy(alpha = 0.7f), shape) else Modifier)
            .hoverable(hover).entryInteractions(vm, tab, e, rb).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = !e.isDir && FsPath.isLocalFile(e.path) && (e.kind == FileKind.Image || e.kind == FileKind.Video)
            if (thumb) {
                val ctx = LocalContext.current
                AsyncImage(
                    model = remember(e.path, e.modified) { ImageRequest.Builder(ctx).data(File(e.path)).size(360).build() },
                    contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else Icon(e.icon(), null, Modifier.size(44.dp), tint = e.tint())
            MarkBadges(s, e.path, Modifier.align(Alignment.TopEnd).padding(6.dp), dot = 10.dp)
        }
        Text(
            e.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = if (selected) cs.onSecondaryContainer else cs.onSurface,
        )
    }
}

@Composable
private fun StatusBar(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val tab = s.active
    val visible = remember(tab.entries, tab.searchResults, s.showHidden) { tab.visible(s.showHidden) }
    val sel = remember(visible, tab.selection) { visible.filter { it.path in tab.selection } }
    val text = buildString {
        append(formatItemCount(visible.size))
        if (sel.isNotEmpty()) {
            append("  •  ${formatSelectedCount(sel.size)}")
            val bytes = sel.filter { !it.isDir }.sumOf { it.size }
            if (sel.any { !it.isDir }) append(" (${formatSize(bytes)})")
        }
        if (tab.searching) append("  •  Searching…")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val roomForHint = maxWidth > 720.dp && LocalDensity.current.fontScale <= 1.3f
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        val op = s.ops.firstOrNull()
        if (op == null && sel.isEmpty() && roomForHint) {
            Text(
                "Space: Quick Look • F3: Split view • Ctrl+K: Commands • F1: Shortcuts",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (op != null) {
            val f = op.progress.fraction
            Text(
                buildString {
                    append(op.progress.title)
                    if (op.progress.current.isNotEmpty()) append(" ${op.progress.current}")
                    if (s.ops.size > 1) append("  (+${s.ops.size - 1})")
                },
                Modifier.widthIn(max = 280.dp).padding(end = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (f != null) LinearProgressIndicator({ f }, Modifier.width(120.dp)) else LinearProgressIndicator(Modifier.width(120.dp))
            Text(if (f != null) "  ${(f * 100).toInt()}%" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
            IconButton({ vm.cancelOp(op.id) }, Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "Cancel", Modifier.size(16.dp)) }
        }
    }
    }
}

// ------------------------------------------------------------------ Miller columns (Finder-style)

private val MILLER_W = 240.dp

/**
 * Columns view: one list per ancestor folder (from the volume/share root down to the current folder),
 * scrolling horizontally. The last column is the current folder; a selected file gets a preview column.
 */
@Composable
private fun MillerColumns(vm: BrowserViewModel, s: AppState, tab: TabState, entries: List<FileEntry>, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    val chain = remember(tab.path, s.roots) { FsPath.breadcrumbs(tab.path, s.rootLabels).map { it.second }.takeLast(8) }
    val cache = remember(tab.id) { HashMap<String, List<FileEntry>>() }
    val scroll = rememberScrollState()
    val preview = tab.selectedEntries(s.showHidden).singleOrNull()?.takeIf { !it.isDir }
    LaunchedEffect(tab.path, preview?.path) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(active) { if (active) vm.setGridColumns(1, 12) }
    Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
        chain.dropLast(1).forEachIndexed { i, dir ->
            AncestorColumn(vm, s, tab, dir, chain[i + 1], cache)
            VerticalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        }
        CurrentColumn(vm, s, tab, entries)
        if (preview != null) {
            VerticalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            MillerPreview(preview)
        }
    }
}

@Composable
private fun AncestorColumn(vm: BrowserViewModel, s: AppState, tab: TabState, dir: String, next: String, cache: HashMap<String, List<FileEntry>>) {
    val menu = LocalMenu.current
    val list by produceState(cache[dir] ?: emptyList(), dir, tab.entries) { value = vm.listForColumn(dir).also { cache[dir] = it } }
    val visible = remember(list, tab.sortKey, tab.sortAsc, s.showHidden) { sortEntries(list.filter { s.showHidden || !it.isHidden }, tab.sortKey, tab.sortAsc) }
    val state = rememberLazyListState()
    LaunchedEffect(visible.size, next) {
        val i = visible.indexOfFirst { it.path == next }
        val info = state.layoutInfo.visibleItemsInfo
        if (i >= 0 && (info.isEmpty() || i < info.first().index || i > info.last().index)) state.scrollToItem((i - 3).coerceAtLeast(0))
    }
    LazyColumn(Modifier.width(MILLER_W).fillMaxHeight(), state, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(visible, key = { it.path }) { e ->
            MillerRow(
                s, e, highlighted = e.path == next, selected = false, focused = false,
                Modifier.dropTarget(if (e.isDir && !e.isServer) e.path else null).mouseGestures(
                    e.path,
                    onClick = { _, _, _ -> vm.columnClick(e) },
                    onDoubleClick = { if (!e.isDir) vm.open(e) },
                    onSecondary = { off -> if (e.isDir) menu.show(off, sidebarMenu(vm, vm.state.value, e.path, e.path in vm.state.value.pins)) },
                    onMiddle = { if (e.isDir) vm.open(e, newTab = true) },
                ),
            )
        }
    }
}

@Composable
private fun CurrentColumn(vm: BrowserViewModel, s: AppState, tab: TabState, entries: List<FileEntry>) {
    val menu = LocalMenu.current
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val rb = remember(tab.id) { RubberBandState() }
    val state = rememberLazyListState()
    var origin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    LaunchedEffect(tab.path) { state.scrollToItem(0) }
    LaunchedEffect(tab.focused) {
        val idx = entries.indexOfFirst { it.path == tab.focused }
        val info = state.layoutInfo.visibleItemsInfo
        if (idx >= 0 && info.isNotEmpty() && (idx <= info.first().index || idx >= info.last().index)) state.animateScrollToItem((idx - 3).coerceAtLeast(0))
    }
    Box(
        Modifier.width(MILLER_W + 20.dp).fillMaxHeight()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .rubberBand(
                rb,
                onSelect = { set, additive -> vm.setSelection(if (additive) tab.selection + set else set) },
                onEmptyClick = { vm.clearSelection() },
                onSecondary = { vm.clearSelection(); menu.show(it, backgroundMenu(vm, vm.state.value)) },
            ),
    ) {
        when {
            tab.loading && tab.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            entries.isEmpty() -> Text("Empty folder", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(vertical = 4.dp)) {
                items(entries, key = { it.path }) { e ->
                    val selected = e.path in tab.selection
                    DisposableEffect(e.path) { onDispose { rb.itemBounds.remove(e.path) } }
                    MillerRow(
                        s, e, highlighted = false, selected = selected, focused = e.path == tab.focused,
                        Modifier.onGloballyPositioned { rb.itemBounds[e.path] = it.boundsInRoot() }
                            .dropTarget(if (e.isDir && !selected && !e.isServer) e.path else null)
                            .dragSource(
                                e.path,
                                paths = { vm.state.value.active.let { t -> if (e.path in t.selection) t.selection.toList() else listOf(e.path) }.filter { !FsPath.isVirtual(it) && vm.state.value.active.path != FsPath.SMB_ROOT } },
                                onStart = { vm.setSelectionIfNotSelected(e.path) },
                            )
                            .mouseGestures(
                                e.path,
                                onClick = { ctrl, shift, touch ->
                                    when {
                                        touch && vm.state.value.active.selection.isNotEmpty() -> vm.click(e.path, ctrl = true, shift = false)
                                        e.isDir && !ctrl && !shift -> vm.navigate(e.path)
                                        touch -> vm.open(e)
                                        else -> vm.click(e.path, ctrl, shift)
                                    }
                                },
                                onDoubleClick = { if (!e.isDir) vm.open(e) },
                                onSecondary = { off -> vm.setSelectionIfNotSelected(e.path); menu.show(off, fileMenu(vm, vm.state.value, e)) },
                                onMiddle = { if (e.isDir) vm.open(e, newTab = true) },
                                onLongPress = { vm.click(e.path, ctrl = true, shift = false) },
                            ),
                    )
                }
                item("filler") { Box(Modifier.fillParentMaxHeightCompat().fillMaxWidth()) }
            }
        }
        val r = rb.rect
        if (r != null) {
            val tl = r.topLeft - origin
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(tl.x.toInt(), tl.y.toInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                    .background(cs.primary.copy(alpha = 0.14f)).border(1.dp, cs.primary.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun MillerRow(s: AppState, e: FileEntry, highlighted: Boolean, selected: Boolean, focused: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val cut = s.clipboard?.cut == true && e.path in s.clipboard.paths
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        drag.active && drag.hover == e.path -> cs.primaryContainer
        selected -> cs.secondaryContainer
        highlighted -> cs.surfaceContainerHighest
        hovered -> cs.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 32.dp).alpha(if (cut) 0.5f else 1f)
            .clip(shape).background(bg)
            .then(if (focused) Modifier.border(2.dp, cs.primary.copy(alpha = 0.7f), shape) else Modifier)
            .hoverable(hover).then(modifier).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(e.icon(), null, Modifier.size(18.dp), tint = e.tint())
        Text(
            e.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = if (selected) cs.onSecondaryContainer else cs.onSurface,
        )
        MarkBadges(s, e.path, Modifier.padding(horizontal = 4.dp), dot = 7.dp)
        if (e.isDir) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
    }
}

/** Small preview/details column shown after the current column when a single file is selected. */
@Composable
private fun MillerPreview(e: FileEntry) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(280.dp).fillMaxHeight().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            if (FsPath.isLocalFile(e.path) && (e.kind == FileKind.Image || e.kind == FileKind.Video)) {
                val ctx = LocalContext.current
                AsyncImage(
                    model = remember(e.path, e.modified) { ImageRequest.Builder(ctx).data(File(e.path)).size(560).build() },
                    contentDescription = e.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            } else Icon(e.icon(), null, Modifier.size(64.dp), tint = e.tint())
        }
        Text(e.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("${e.typeLabel} • ${formatSize(e.size)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text("Modified ${formatDate(e.modified)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}
