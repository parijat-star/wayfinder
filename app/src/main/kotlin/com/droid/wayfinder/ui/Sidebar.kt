// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.droid.wayfinder.data.ColorTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.R
import com.droid.wayfinder.data.FsPath

private data class TreeRowData(
    val path: String, val label: String, val icon: ImageVector, val tint: Color?, val depth: Int,
    val expanded: Boolean, val loading: Boolean, val expandable: Boolean,
    val subtitle: String? = null,
    val isBeta: Boolean = false,
    val driveAccount: com.droid.wayfinder.data.DriveAccount? = null,
    val oneDriveAccount: com.droid.wayfinder.data.OneDriveAccount? = null,
    val dropboxAccount: com.droid.wayfinder.data.DropboxAccount? = null,
    val pairedPhone: com.droid.wayfinder.data.PairedPhone? = null,
)

private fun flatten(
    path: String, label: String, icon: ImageVector, tint: Color?, depth: Int, s: AppState, out: MutableList<TreeRowData>,
    subtitle: String? = null,
    isBeta: Boolean = false,
    driveAccount: com.droid.wayfinder.data.DriveAccount? = null,
    oneDriveAccount: com.droid.wayfinder.data.OneDriveAccount? = null,
    dropboxAccount: com.droid.wayfinder.data.DropboxAccount? = null,
    pairedPhone: com.droid.wayfinder.data.PairedPhone? = null,
) {
    val expanded = path in s.tree.expanded
    val kids = s.tree.children[path]
    out += TreeRowData(path, label, icon, tint, depth, expanded, path in s.tree.loading, kids == null || kids.isNotEmpty(), subtitle, isBeta, driveAccount, oneDriveAccount, dropboxAccount, pairedPhone)
    if (expanded && kids != null) {
        kids.filter { s.showHidden || !it.isHidden }.forEach {
            flatten(it.path, it.name, if (it.isShare) Icons.Outlined.FolderShared else Icons.Outlined.Folder, null, depth + 1, s, out)
        }
    }
}

@Composable
fun Sidebar(vm: BrowserViewModel, s: AppState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val menu = LocalMenu.current
    val fm = LocalFocusManager.current
    val focusCoord = LocalFocusCoordinator.current
    val scope = rememberCoroutineScope()
    val activePath = s.active.path
    // Inside an archive the tree highlights the folder that holds the .zip.
    val treePath = FsPath.zipSplit(activePath)?.first?.let { FsPath.parent(it) } ?: activePath
    val vis = s.sidebarVisibility
    val locations = remember(s.roots, s.linuxRootPath, s.tree, s.showHidden, vis.linuxFiles) {
        buildList {
            s.roots.forEach {
                val isLinux = com.droid.wayfinder.data.GooglebookEcosystem.isLinuxLocation(it.path, it.label, s.linuxRootPath)
                if (!isLinux || vis.linuxFiles) {
                    flatten(it.path, it.label, rootIcon(it.removable), null, 0, s, this, it.subtitle, isBeta = isLinux)
                }
            }
        }
    }
    val driveRows = remember(s.driveAccounts, s.tree, s.showHidden, vis.googleDrive) {
        if (!vis.googleDrive) emptyList() else buildList {
            s.driveAccounts.forEach { acc ->
                val rootPath = acc.mountPath ?: "gdrive://${acc.email}/root"
                if (acc.isMounted && acc.mountPath != null) {
                    flatten(acc.mountPath, acc.title, Icons.Outlined.Cloud, null, 0, s, this, acc.email, driveAccount = acc)
                } else {
                    add(TreeRowData(rootPath, acc.title, Icons.Outlined.Cloud, null, 0, false, false, false, acc.email, driveAccount = acc))
                }
            }
        }
    }
    val oneDriveRows = remember(s.oneDriveAccounts, s.tree, s.showHidden, vis.oneDrive) {
        if (!vis.oneDrive) emptyList() else buildList {
            s.oneDriveAccounts.forEach { acc ->
                flatten(
                    acc.rootPath,
                    acc.sidebarTitle,
                    Icons.Outlined.Cloud,
                    null,
                    0,
                    s,
                    this,
                    acc.sidebarSubtitle,
                    oneDriveAccount = acc,
                )
            }
        }
    }
    val dropboxRows = remember(s.dropboxAccounts, s.tree, s.showHidden, vis.dropbox) {
        if (!vis.dropbox) emptyList() else buildList {
            s.dropboxAccounts.forEach { acc ->
                flatten(
                    acc.rootPath,
                    acc.sidebarTitle,
                    Icons.Outlined.Cloud,
                    null,
                    0,
                    s,
                    this,
                    acc.sidebarSubtitle,
                    dropboxAccount = acc,
                )
            }
        }
    }
    val phoneRows = remember(s.pairedPhone, s.quickAccessAvailable, s.tree, s.showHidden, vis.phone) {
        if (!vis.phone) emptyList() else buildList {
            val phone = s.pairedPhone ?: if (s.quickAccessAvailable) {
                com.droid.wayfinder.data.PairedPhone(
                    name = "Phone",
                    subtitle = "Cross-Device · Browse or import",
                    path = FsPath.PHONE_ROOT,
                    isConnected = true,
                    authority = com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY,
                    isMounted = false,
                    transport = com.droid.wayfinder.data.PhoneTransport.QUICK_ACCESS,
                )
            } else {
                null
            }
            if (phone != null) {
                if (phone.isMounted && phone.path != FsPath.PHONE_ROOT) {
                    flatten(phone.path, phone.name, Icons.Outlined.Smartphone, null, 0, s, this, phone.subtitle, isBeta = true, pairedPhone = phone)
                } else {
                    add(TreeRowData(FsPath.PHONE_ROOT, phone.name, Icons.Outlined.Smartphone, null, 0, false, false, false, phone.subtitle, isBeta = true, pairedPhone = phone))
                }
            }
        }
    }
    val specialMountPaths = remember(s.driveAccounts, s.pairedPhone) {
        s.driveAccounts.mapNotNull { it.mountPath }.toSet() +
            setOfNotNull(s.pairedPhone?.path?.takeIf { it != FsPath.PHONE_ROOT })
    }
    // Saved servers and cloud / SAF mounts first (with eject buttons), filtering out those mapped to Drive accounts or Phone
    val mountRows = remember(s.mounts, s.linuxRootPath, s.tree, s.showHidden, specialMountPaths, vis.networkAndCloud, vis.linuxFiles) {
        if (!vis.networkAndCloud) emptyList() else buildList {
            s.mounts.filter { it.path !in specialMountPaths }.forEach { m ->
                val isLinux = com.droid.wayfinder.data.GooglebookEcosystem.isLinuxLocation(m.path, m.label, s.linuxRootPath)
                if (!isLinux || vis.linuxFiles) {
                    flatten(m.path, m.label, if (m.saf) Icons.Outlined.Cloud else Icons.Outlined.Dns, null, 0, s, this, isBeta = isLinux)
                }
            }
        }
    }
    val network = remember(s.network, s.mounts, s.tree, s.showHidden, vis.networkAndCloud) {
        if (!vis.networkAndCloud) emptyList() else {
            val mounted = s.mounts.mapTo(HashSet()) { it.path }
            buildList { s.network.filter { it.path !in mounted }.forEach { flatten(it.path, it.name, it.icon(), null, 0, s, this) } }
        }
    }
    val listState = rememberLazyListState()
    val locStart = remember { IntArray(1) }
    val currentLocations by rememberUpdatedState(locations)
    val currentTarget by rememberUpdatedState(treePath)
    // Alt+Shift+L: wait for the ancestors to load, then scroll the located folder into view.
    LaunchedEffect(s.treeLocate) {
        if (s.treeLocate == 0) return@LaunchedEffect
        repeat(40) {
            val i = currentLocations.indexOfFirst { it.path == currentTarget }
            if (i >= 0) { listState.animateScrollToItem((locStart[0] + i - 3).coerceAtLeast(0)); return@LaunchedEffect }
            delay(100)
        }
    }
    Box(
        modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown -> {
                    val moved = fm.moveFocus(FocusDirection.Down)
                    if (!moved && listState.canScrollForward) {
                        scope.launch {
                            listState.scrollBy(96f)
                            fm.moveFocus(FocusDirection.Down)
                        }
                    }
                    true
                }
                Key.DirectionUp -> {
                    if (focusCoord.firstSidebarItemFocused) {
                        true
                    } else {
                        val moved = fm.moveFocus(FocusDirection.Up)
                        if (!moved && listState.canScrollBackward) {
                            scope.launch {
                                listState.scrollBy(-96f)
                                fm.moveFocus(FocusDirection.Up)
                            }
                        }
                        true
                    }
                }
                else -> false
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        var n = 0
        item { SectionTitle(stringResource(R.string.quick_access_section)) }; n++
        item {
            val recentsFocus = remember { FocusRequester() }
            androidx.compose.runtime.DisposableEffect(recentsFocus) {
                focusCoord.activeSidebarRequester = recentsFocus
                onDispose {
                    if (focusCoord.activeSidebarRequester === recentsFocus) {
                        focusCoord.activeSidebarRequester = null
                    }
                }
            }
            SidebarItem(
                stringResource(R.string.nav_recents), Icons.Outlined.History, 0, activePath == FsPath.RECENTS_ROOT, null,
                focusRequester = recentsFocus,
                onFocusChanged = { focusCoord.firstSidebarItemFocused = it },
                onClick = { vm.navigate(FsPath.RECENTS_ROOT) }, onMiddle = { vm.newTab(FsPath.RECENTS_ROOT, activate = false) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.RECENTS_ROOT, false)) },
            )
        }; n++
        item {
            SidebarItem(
                stringResource(R.string.nav_starred), Icons.Outlined.StarOutline, 0, activePath == FsPath.STARRED_ROOT, FsPath.STARRED_ROOT,
                tint = if (s.marks.starred.isNotEmpty()) StarColor else null,
                onClick = { vm.navigate(FsPath.STARRED_ROOT) }, onMiddle = { vm.newTab(FsPath.STARRED_ROOT, activate = false) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.STARRED_ROOT, false)) },
            )
        }; n++
        item { Divider() }; n++
        item { SectionTitle(stringResource(R.string.pinned_section)) }; n++
        if (s.pins.isEmpty()) {
            item {
                Text("Right-click a file or folder and choose “Pin to sidebar”", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            n++
        }
        items(s.pins, key = { "pin$it" }) { p ->
            val isLocalFile by androidx.compose.runtime.produceState(initialValue = false, key1 = p) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    FsPath.isLocalFile(p) && java.io.File(p).isFile
                }
            }
            SidebarItem(
                label = s.titleOf(p), icon = pinIcon(p, isKnownFile = isLocalFile), depth = 0,
                selected = p == activePath || (isLocalFile && p in s.active.selection),
                dropPath = p.takeIf { !isLocalFile },
                trailing = { HeaderAction(Icons.Filled.PushPin, stringResource(R.string.action_unpin_item, s.titleOf(p))) { vm.unpin(p) } },
                onClick = { vm.openPinned(p) }, onMiddle = { vm.openPinned(p, newTab = true) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, p, true)) },
            )
        }
        n += s.pins.size
        item { Divider() }; n++
        item {
            SectionTitle(stringResource(R.string.locations_section), trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeaderAction(
                        icon = Icons.Outlined.MyLocation,
                        tooltip = stringResource(R.string.tooltip_locate_active_folder),
                        contentDesc = stringResource(R.string.palette_locate_folder),
                    ) { vm.locateInTree() }
                    HeaderAction(Icons.Outlined.UnfoldLess, stringResource(R.string.palette_collapse_tree)) { vm.collapseTree() }
                }
            })
        }; n++
        locStart[0] = n
        items(locations, key = { "loc${it.path}" }) { r -> TreeItem(vm, s, r, treePath, menu) }
        if (vis.linuxFiles && s.linuxProviderAuthority != null && s.linuxRootPath == null) {
            item(key = "linux_mount_prompt") {
                SidebarItem(
                    label = "Linux files",
                    icon = Icons.Outlined.Dns,
                    depth = 0,
                    selected = false,
                    dropPath = null,
                    subtitle = "Click to mount Linux folder",
                    badge = stringResource(R.string.badge_beta),
                    onClick = { vm.requestMountLinuxFiles() },
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.LinuxFiles)) },
                )
            }
        }

        // Paired phone (e.g. Pixel 9 · Internal storage) matching the system Files app layout
        if (vis.phone) {
            items(phoneRows, key = { "phone${it.depth}${it.path}" }) { r ->
                val mount = if (r.depth == 0) s.mounts.firstOrNull { it.path == r.path } else null
                TreeItem(vm, s, r, activePath, menu, mount = mount)
            }
        }

        // Enterprise Work Profile switching via CrossProfileApps
        if (s.workProfiles.isNotEmpty()) {
            items(s.workProfiles, key = { "work${it.id}" }) { wp ->
                SidebarItem(
                    label = wp.label,
                    icon = Icons.Outlined.FolderShared,
                    depth = 0,
                    selected = false,
                    dropPath = null,
                    subtitle = "Managed profile",
                    onClick = { vm.openWorkProfile(wp) },
                )
            }
        }

        // Google Drive integration matching system Files app
        if (vis.googleDrive) {
            item { Divider() }
            item {
                SectionTitle(
                    text = stringResource(R.string.google_drive_section),
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.GoogleDrive)) },
                    trailing = {
                        HeaderAction(Icons.Outlined.AddToDrive, stringResource(R.string.action_mount_drive_folder)) {
                            if (s.driveAccounts.isEmpty()) vm.requestGoogleAccountSignIn() else vm.requestMountSaf(s.driveAccounts.first().email)
                        }
                    },
                )
            }
            if (driveRows.isEmpty()) {
                item {
                    SidebarItem(
                        label = "Sign in to Google Drive…",
                        icon = Icons.Outlined.Cloud,
                        depth = 0,
                        selected = false,
                        dropPath = null,
                        subtitle = "Connect account or mount Drive folder",
                        iconComposable = { GoogleDriveIcon(Modifier.size(20.dp)) },
                        onClick = { vm.requestGoogleAccountSignIn() },
                        onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.GoogleDrive)) },
                    )
                }
            } else {
                items(driveRows, key = { "drive${it.depth}${it.path}" }) { r ->
                    val mount = if (r.depth == 0) s.mounts.firstOrNull { it.path == r.path } else null
                    TreeItem(vm, s, r, activePath, menu, mount = mount)
                }
            }
        }

        // Microsoft OneDrive (Personal + Microsoft 365) via Graph v1.0
        if (vis.oneDrive) {
            item { Divider() }
            item {
                val addOneDriveDesc = stringResource(R.string.onedrive_add_account)
                SectionTitle(
                    text = stringResource(R.string.onedrive_section),
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.OneDrive)) },
                    trailing = {
                        HeaderAction(Icons.Outlined.Add, addOneDriveDesc) { vm.showConnectOneDriveDialog() }
                    },
                )
            }
            if (oneDriveRows.isEmpty()) {
                item(key = "onedrive_empty") {
                    SidebarItem(
                        label = stringResource(R.string.onedrive_sign_in_prompt),
                        icon = Icons.Outlined.Cloud,
                        depth = 0,
                        selected = false,
                        dropPath = null,
                        subtitle = stringResource(R.string.onedrive_sign_in_subtitle),
                        iconComposable = { OneDriveIcon(Modifier.size(20.dp)) },
                        onClick = { vm.showConnectOneDriveDialog() },
                        onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.OneDrive)) },
                    )
                }
            } else {
                items(oneDriveRows, key = { "onedrive${it.depth}${it.path}" }) { r ->
                    TreeItem(vm, s, r, activePath, menu)
                }
            }
        }

        // Dropbox (Personal + Business / Team Space) via HTTP API v2
        if (vis.dropbox) {
            item { Divider() }
            item {
                val addDropboxDesc = stringResource(R.string.dropbox_add_account)
                SectionTitle(
                    text = stringResource(R.string.dropbox_section),
                    badge = stringResource(R.string.badge_beta),
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.Dropbox)) },
                    trailing = {
                        HeaderAction(Icons.Outlined.Add, addDropboxDesc) { vm.showConnectDropboxDialog() }
                    },
                )
            }
            if (dropboxRows.isEmpty()) {
                item(key = "dropbox_empty") {
                    SidebarItem(
                        label = stringResource(R.string.dropbox_sign_in_prompt),
                        icon = Icons.Outlined.Cloud,
                        depth = 0,
                        selected = false,
                        dropPath = null,
                        subtitle = stringResource(R.string.dropbox_sign_in_subtitle),
                        iconComposable = { DropboxIcon(Modifier.size(20.dp)) },
                        onClick = { vm.showConnectDropboxDialog() },
                        onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.Dropbox)) },
                    )
                }
            } else {
                items(dropboxRows, key = { "dropbox${it.depth}${it.path}" }) { r ->
                    TreeItem(vm, s, r, activePath, menu)
                }
            }
        }

        if (vis.networkAndCloud) {
            item { Divider() }
            item {
                SectionTitle(
                    text = stringResource(R.string.network_and_cloud_section),
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.NetworkAndCloud)) },
                    trailing = {
                        if (s.scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else HeaderAction(Icons.Outlined.Refresh, stringResource(R.string.action_rescan_network)) { vm.navigate(FsPath.SMB_ROOT); vm.refresh() }
                    },
                )
            }
            items(mountRows, key = { "mnt${it.depth}${it.path}" }) { r ->
                TreeItem(vm, s, r, activePath, menu, mount = if (r.depth == 0) s.mounts.firstOrNull { it.path == r.path } else null)
            }
            items(network, key = { "net${it.path}" }) { r -> TreeItem(vm, s, r, activePath, menu) }
            item {
                SidebarItem("Connect to Server…", Icons.Outlined.Add, 0, false, null, null, onClick = { vm.showConnectDialog() })
            }
            item {
                SidebarItem("Mount Cloud / SAF…", Icons.Outlined.AddToDrive, 0, false, null, null, onClick = { vm.requestMountSaf() })
            }
        }

        item { Divider() }
        item {
            SidebarItem(
                stringResource(R.string.nav_trash), Icons.Outlined.Delete, 0, activePath == FsPath.TRASH_ROOT, FsPath.TRASH_ROOT,
                onClick = { vm.navigate(FsPath.TRASH_ROOT) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.TRASH_ROOT, false)) },
            )
        }
        if (vis.colorTags) {
            item { Divider() }
            item {
                SectionTitle(
                    text = stringResource(R.string.color_tags_section),
                    onSecondary = { menu.show(it, sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.ColorTags)) },
                )
            }
            items(ColorTag.entries, key = { "tag${it.name}" }) { t ->
                val path = FsPath.tagPath(t)
                val count = s.marks.tags.count { t in it.value }
                SidebarItem(
                    t.label, Icons.Filled.Circle, 0, activePath == path, path, tint = Color(t.argb),
                    trailing = if (count > 0) ({ Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }) else null,
                    onClick = { vm.navigate(path) }, onMiddle = { vm.newTab(path, activate = false) },
                    onSecondary = { menu.show(it, sidebarMenu(vm, s, path, false) + sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.ColorTags)) },
                )
            }
        }
        item { Divider() }
        item(key = "customize_sidebar") {
            SidebarItem(
                label = stringResource(R.string.action_customize_sidebar),
                icon = Icons.Outlined.Settings,
                depth = 0,
                selected = false,
                dropPath = null,
                onClick = { vm.showSettings(com.droid.wayfinder.data.SettingsSection.Integrations) },
            )
        }
        item { Box(Modifier.height(12.dp)) }
        }
        LazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    tooltip: String,
    contentDesc: String = tooltip,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    ActionTooltip(tooltip) {
        Box(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .keyboardFocusableItem(shape = CircleShape, onActivate = { _, _ -> onClick() })
                .clip(RoundedCornerShape(24.dp))
                .background(if (hovered) cs.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                .hoverable(hover)
                .mouseGestures(contentDesc, role = Role.Button, contentDescription = contentDesc, onClickLabel = contentDesc, onClick = { _, _, _ -> onClick() }),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
    }
}

@Composable
private fun TreeItem(vm: BrowserViewModel, s: AppState, r: TreeRowData, activePath: String, menu: MenuController, mount: Mount? = null) {
    val driveAcc = r.driveAccount
    val oneDriveAcc = r.oneDriveAccount
    val dropboxAcc = r.dropboxAccount
    val phone = r.pairedPhone
    val selected = when {
        driveAcc != null && !r.expanded ->
            (driveAcc.mountPath != null && (driveAcc.mountPath == activePath || FsPath.isWithin(driveAcc.mountPath, activePath))) ||
                activePath.startsWith("gdrive://${driveAcc.email}")
        oneDriveAcc != null && !r.expanded ->
            activePath == oneDriveAcc.rootPath || FsPath.isWithin(oneDriveAcc.rootPath, activePath)
        dropboxAcc != null && !r.expanded ->
            activePath == dropboxAcc.rootPath || FsPath.isWithin(dropboxAcc.rootPath, activePath)
        phone != null && !r.expanded ->
            activePath == phone.path || FsPath.isWithin(phone.path, activePath) || activePath == FsPath.PHONE_ROOT
        else -> r.path == activePath
    }
    SidebarItem(
        label = r.label, icon = r.icon, depth = r.depth, selected = selected,
        dropPath = r.path.takeIf { !FsPath.isVirtual(it) && (!FsPath.isSmb(it) || FsPath.parseSmb(it).share != null) },
        subtitle = r.subtitle,
        badge = if (r.isBeta) stringResource(R.string.badge_beta) else null,
        iconComposable = when {
            driveAcc != null -> ({ GoogleDriveIcon(Modifier.size(20.dp)) })
            oneDriveAcc != null -> ({ OneDriveIcon(Modifier.size(20.dp)) })
            dropboxAcc != null -> ({ DropboxIcon(Modifier.size(20.dp)) })
            else -> null
        },
        expandable = r.expandable, expanded = r.expanded, loading = r.loading, onToggle = { vm.toggleTree(r.path) },
        trailing = when {
            driveAcc != null && driveAcc.isMounted && mount != null -> ({
                HeaderAction(Icons.Outlined.Eject, stringResource(R.string.action_unmount_item, driveAcc.email)) { vm.eject(mount) }
            })
            oneDriveAcc != null -> ({
                HeaderAction(Icons.Outlined.Eject, stringResource(R.string.action_sign_out_item, oneDriveAcc.email)) { vm.signOutOneDrive(oneDriveAcc.accountId) }
            })
            dropboxAcc != null -> ({
                HeaderAction(Icons.Outlined.Eject, stringResource(R.string.action_sign_out_item, dropboxAcc.email)) { vm.signOutDropbox(dropboxAcc.accountId) }
            })
            phone != null && phone.isMounted && mount != null -> ({
                HeaderAction(Icons.Outlined.Eject, stringResource(R.string.action_unmount_item, phone.name)) { vm.eject(mount) }
            })
            mount != null -> ({
                val ejectDesc = if (mount.saf) {
                    stringResource(R.string.action_unmount_item, mount.label)
                } else {
                    stringResource(R.string.action_disconnect_item, mount.label)
                }
                HeaderAction(Icons.Outlined.Eject, ejectDesc) { vm.eject(mount) }
            })
            r.path in s.pins -> ({ HeaderAction(Icons.Filled.PushPin, stringResource(R.string.action_unpin_item, r.label)) { vm.unpin(r.path) } })
            else -> null
        },
        onClick = {
            when {
                driveAcc != null -> vm.openDriveAccount(driveAcc)
                oneDriveAcc != null -> vm.navigate(oneDriveAcc.rootPath)
                dropboxAcc != null -> vm.navigate(dropboxAcc.rootPath)
                phone != null -> vm.openPairedPhone(phone)
                else -> vm.navigate(r.path)
            }
        },
        onMiddle = {
            when {
                driveAcc != null -> driveAcc.mountPath?.let { vm.newTab(it, activate = false) }
                oneDriveAcc != null -> vm.newTab(oneDriveAcc.rootPath, activate = false)
                dropboxAcc != null -> vm.newTab(dropboxAcc.rootPath, activate = false)
                phone != null -> vm.newTab(phone.path, activate = false)
                else -> vm.newTab(r.path, activate = false)
            }
        },
        onSecondary = {
            when {
                driveAcc != null -> menu.show(it, driveAccountMenu(vm, driveAcc, mount))
                oneDriveAcc != null -> menu.show(it, oneDriveAccountMenu(vm, oneDriveAcc))
                dropboxAcc != null -> menu.show(it, dropboxAccountMenu(vm, dropboxAcc))
                phone != null -> menu.show(it, phoneMenu(vm, phone, mount))
                else -> {
                    val base = sidebarMenu(vm, s, r.path, r.path in s.pins)
                    val withMount = if (mount == null) base else base + mountMenu(vm, mount)
                    val isLinux = r.depth == 0 && com.droid.wayfinder.data.GooglebookEcosystem.isLinuxLocation(r.path, r.label, s.linuxRootPath)
                    menu.show(
                        it,
                        if (isLinux) withMount + sidebarSectionMenu(vm, com.droid.wayfinder.data.SidebarModule.LinuxFiles) else withMount,
                    )
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(
    text: String,
    badge: String? = null,
    onSecondary: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var rootOrigin by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val ctxMod = if (onSecondary != null) {
        Modifier
            .onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
            .pointerInput(onSecondary) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.buttons.isSecondaryPressed && !change.previousPressed && change.pressed) {
                            change.consume()
                            onSecondary(rootOrigin + change.position)
                        }
                    }
                }
            }
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(ctxMod)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (badge != null) {
                BetaBadge(badge)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    depth: Int,
    selected: Boolean,
    dropPath: String?,
    tint: Color? = null,
    subtitle: String? = null,
    badge: String? = null,
    iconComposable: (@Composable () -> Unit)? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    loading: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onToggle: () -> Unit = {},
    onClick: () -> Unit,
    onMiddle: () -> Unit = {},
    onSecondary: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val focusCoord = LocalFocusCoordinator.current
    val effectiveRequester = focusRequester ?: remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(effectiveRequester) {
        onDispose { focusCoord.removeSidebarItem(effectiveRequester) }
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val drag = LocalDrag.current
    val dropHover = drag.active && dropPath != null && drag.hover == dropPath
    val bg = when {
        dropHover -> cs.primaryContainer
        selected -> cs.secondaryContainer
        hovered -> cs.onSurface.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val itemHeight = 48.dp
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = itemHeight)
            .onGloballyPositioned { coords ->
                focusCoord.updateSidebarItem(effectiveRequester, coords.boundsInRoot().top)
            }
            .keyboardFocusableItem(
                shape = shape,
                focusRequester = effectiveRequester,
                onFocusChanged = onFocusChanged,
                onKeyDown = { e ->
                    when {
                        expandable && e.key == Key.DirectionRight && !expanded -> { onToggle(); true }
                        expandable && e.key == Key.DirectionLeft && expanded -> { onToggle(); true }
                        else -> false
                    }
                },
                onActivate = { _, _ -> onClick() },
                onContextMenu = onSecondary,
            )
            .clip(shape).background(bg)
            .hoverable(hover)
            .dropTarget(dropPath)
            .mouseGestures(
                label + depth + dropPath + subtitle.orEmpty(),
                role = Role.Button,
                selected = selected,
                onClickLabel = "Open $label",
                onClick = { _, _, _ -> onClick() }, onMiddle = onMiddle,
                onSecondary = onSecondary ?: {}, onLongPress = onSecondary ?: {},
            )
            .padding(start = 4.dp + 14.dp * depth, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable) {
            val toggleDesc = stringResource(if (expanded) R.string.action_collapse_folder else R.string.action_expand_folder, label)
            val toggleIconDesc = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand)
            ActionTooltip(toggleDesc) {
                Box(
                    Modifier.size(48.dp)
                        .keyboardFocusableItem(shape = CircleShape, onActivate = { _, _ -> onToggle() })
                        .clip(RoundedCornerShape(24.dp))
                        .mouseGestures(
                            "toggle$label$depth",
                            role = Role.Button,
                            contentDescription = toggleIconDesc,
                            onClickLabel = toggleDesc,
                            onClick = { _, _, _ -> onToggle() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null,
                            Modifier.size(18.dp), tint = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
        }
        if (iconComposable != null) {
            Box(Modifier.padding(horizontal = 6.dp).size(20.dp), contentAlignment = Alignment.Center) {
                iconComposable()
            }
        } else {
            Icon(icon, null, Modifier.padding(horizontal = 6.dp).size(20.dp), tint = tint ?: if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) cs.onSecondaryContainer else cs.onSurface,
                )
                if (badge != null) {
                    BetaBadge(badge, selected = selected)
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun BetaBadge(text: String, selected: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val containerColor = if (selected) cs.tertiaryContainer else cs.tertiaryContainer.copy(alpha = 0.85f)
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = cs.onTertiaryContainer,
        )
    }
}

/** Draggable divider that resizes the sidebar. */
@Composable
fun SidebarHandle(width: Float, onWidth: (Float) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val cur = remember(ctx) { PointerIcon(AndroidPointerIcon.getSystemIcon(ctx, AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)) }
    val current by androidx.compose.runtime.rememberUpdatedState(width)
    var w = width
    Box(
        Modifier.width(6.dp).fillMaxHeight().pointerHoverIcon(cur).pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { w = current }) { change, dx ->
                change.consume()
                w = (w + dx / density.density).coerceIn(160f, 420f)
                onWidth(w)
            }
        },
    )
}
