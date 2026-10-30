// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.droid.wayfinder.data.ColorTag
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.data.FsPath

private data class TreeRowData(
    val path: String, val label: String, val icon: ImageVector, val tint: Color?, val depth: Int,
    val expanded: Boolean, val loading: Boolean, val expandable: Boolean,
    val subtitle: String? = null,
    val driveAccount: com.droid.wayfinder.data.DriveAccount? = null,
    val pairedPhone: com.droid.wayfinder.data.PairedPhone? = null,
)

private fun flatten(
    path: String, label: String, icon: ImageVector, tint: Color?, depth: Int, s: AppState, out: MutableList<TreeRowData>,
    subtitle: String? = null,
    driveAccount: com.droid.wayfinder.data.DriveAccount? = null,
    pairedPhone: com.droid.wayfinder.data.PairedPhone? = null,
) {
    val expanded = path in s.tree.expanded
    val kids = s.tree.children[path]
    out += TreeRowData(path, label, icon, tint, depth, expanded, path in s.tree.loading, kids == null || kids.isNotEmpty(), subtitle, driveAccount, pairedPhone)
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
    val activePath = s.active.path
    // Inside an archive the tree highlights the folder that holds the .zip.
    val treePath = FsPath.zipSplit(activePath)?.first?.let { FsPath.parent(it) } ?: activePath
    val locations = remember(s.roots, s.tree, s.showHidden) {
        buildList { s.roots.forEach { flatten(it.path, it.label, rootIcon(it.removable), null, 0, s, this, it.subtitle) } }
    }
    val driveRows = remember(s.driveAccounts, s.tree, s.showHidden) {
        buildList {
            s.driveAccounts.forEach { acc ->
                val rootPath = acc.mountPath ?: "gdrive://${acc.email}/root"
                if (acc.isMounted && acc.mountPath != null) {
                    flatten(acc.mountPath, "Drive", Icons.Outlined.Cloud, null, 0, s, this, acc.email, driveAccount = acc)
                } else {
                    add(TreeRowData(rootPath, "Drive", Icons.Outlined.Cloud, null, 0, false, false, false, acc.email, driveAccount = acc))
                }
            }
        }
    }
    val phoneRows = remember(s.pairedPhone, s.quickAccessAvailable, s.tree, s.showHidden) {
        buildList {
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
                    flatten(phone.path, phone.name, Icons.Outlined.Smartphone, null, 0, s, this, phone.subtitle, pairedPhone = phone)
                } else {
                    add(TreeRowData(FsPath.PHONE_ROOT, phone.name, Icons.Outlined.Smartphone, null, 0, false, false, false, phone.subtitle, pairedPhone = phone))
                }
            }
        }
    }
    val specialMountPaths = remember(s.driveAccounts, s.pairedPhone) {
        s.driveAccounts.mapNotNull { it.mountPath }.toSet() +
            setOfNotNull(s.pairedPhone?.path?.takeIf { it != FsPath.PHONE_ROOT })
    }
    // Saved servers and cloud / SAF mounts first (with eject buttons), filtering out those mapped to Drive accounts or Phone
    val mountRows = remember(s.mounts, s.tree, s.showHidden, specialMountPaths) {
        buildList {
            s.mounts.filter { it.path !in specialMountPaths }.forEach { m ->
                flatten(m.path, m.label, if (m.saf) Icons.Outlined.Cloud else Icons.Outlined.Dns, null, 0, s, this)
            }
        }
    }
    val network = remember(s.network, s.mounts, s.tree, s.showHidden) {
        val mounted = s.mounts.mapTo(HashSet()) { it.path }
        buildList { s.network.filter { it.path !in mounted }.forEach { flatten(it.path, it.name, it.icon(), null, 0, s, this) } }
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
    LazyColumn(modifier.padding(horizontal = 8.dp), listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        var n = 0
        item { SectionTitle("Quick access") }; n++
        item {
            SidebarItem(
                "Recents", Icons.Outlined.History, 0, activePath == FsPath.RECENTS_ROOT, null,
                onClick = { vm.navigate(FsPath.RECENTS_ROOT) }, onMiddle = { vm.newTab(FsPath.RECENTS_ROOT, activate = false) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.RECENTS_ROOT, false)) },
            )
        }; n++
        item {
            SidebarItem(
                "Starred", Icons.Outlined.StarOutline, 0, activePath == FsPath.STARRED_ROOT, FsPath.STARRED_ROOT,
                tint = if (s.marks.starred.isNotEmpty()) StarColor else null,
                onClick = { vm.navigate(FsPath.STARRED_ROOT) }, onMiddle = { vm.newTab(FsPath.STARRED_ROOT, activate = false) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.STARRED_ROOT, false)) },
            )
        }; n++
        item { Divider() }; n++
        item { SectionTitle("Pinned") }; n++
        if (s.pins.isEmpty()) {
            item {
                Text("Right-click a file or folder and choose “Pin to sidebar”", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            n++
        }
        items(s.pins, key = { "pin$it" }) { p ->
            val isLocalFile = FsPath.isLocalFile(p) && java.io.File(p).isFile
            SidebarItem(
                label = s.titleOf(p), icon = pinIcon(p), depth = 0,
                selected = p == activePath || (isLocalFile && p in s.active.selection),
                dropPath = p.takeIf { !isLocalFile },
                trailing = { HeaderAction(Icons.Filled.PushPin, "Unpin ${s.titleOf(p)}") { vm.unpin(p) } },
                onClick = { vm.openPinned(p) }, onMiddle = { vm.openPinned(p, newTab = true) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, p, true)) },
            )
        }
        n += s.pins.size
        item { Divider() }; n++
        item {
            SectionTitle("Locations", trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeaderAction(Icons.Outlined.MyLocation, "Locate active folder (Alt+Shift+L)") { vm.locateInTree() }
                    HeaderAction(Icons.Outlined.UnfoldLess, "Collapse folder tree") { vm.collapseTree() }
                }
            })
        }; n++
        locStart[0] = n
        items(locations, key = { "loc${it.path}" }) { r -> TreeItem(vm, s, r, treePath, menu) }
        if (s.linuxProviderAuthority != null && s.linuxRootPath == null) {
            item(key = "linux_mount_prompt") {
                SidebarItem(
                    label = "Linux files",
                    icon = Icons.Outlined.Dns,
                    depth = 0,
                    selected = false,
                    dropPath = null,
                    subtitle = "Click to mount Linux folder",
                    onClick = { vm.requestMountLinuxFiles() },
                )
            }
        }

        // Paired phone (e.g. Pixel 9 · Internal storage) matching the system Files app layout
        items(phoneRows, key = { "phone${it.depth}${it.path}" }) { r ->
            val mount = if (r.depth == 0) s.mounts.firstOrNull { it.path == r.path } else null
            TreeItem(vm, s, r, activePath, menu, mount = mount)
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
        item { Divider() }
        item {
            SectionTitle("Google Drive", trailing = {
                HeaderAction(Icons.Outlined.AddToDrive, "Mount Google Drive folder") {
                    if (s.driveAccounts.isEmpty()) vm.requestGoogleAccountSignIn() else vm.requestMountSaf()
                }
            })
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
                )
            }
        } else {
            items(driveRows, key = { "drive${it.depth}${it.path}" }) { r ->
                val mount = if (r.depth == 0) s.mounts.firstOrNull { it.path == r.path } else null
                TreeItem(vm, s, r, activePath, menu, mount = mount)
            }
        }
        item { Divider() }
        item {
            SectionTitle("Network & cloud", trailing = {
                if (s.scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, "Rescan", Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                    .mouseGestures("rescan", onClick = { _, _, _ -> vm.navigate(FsPath.SMB_ROOT); vm.refresh() }), tint = cs.onSurfaceVariant)
            })
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
        item { Divider() }
        item {
            SidebarItem(
                "Trash", Icons.Outlined.Delete, 0, activePath == FsPath.TRASH_ROOT, FsPath.TRASH_ROOT,
                onClick = { vm.navigate(FsPath.TRASH_ROOT) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, FsPath.TRASH_ROOT, false)) },
            )
        }
        item { Divider() }
        item { SectionTitle("Color tags") }
        items(ColorTag.entries, key = { "tag${it.name}" }) { t ->
            val path = FsPath.tagPath(t)
            val count = s.marks.tags.count { t in it.value }
            SidebarItem(
                t.label, Icons.Filled.Circle, 0, activePath == path, path, tint = Color(t.argb),
                trailing = if (count > 0) ({ Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }) else null,
                onClick = { vm.navigate(path) }, onMiddle = { vm.newTab(path, activate = false) },
                onSecondary = { menu.show(it, sidebarMenu(vm, s, path, false)) },
            )
        }
        item { Box(Modifier.height(12.dp)) }
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(RoundedCornerShape(14.dp)).mouseGestures(desc, role = Role.Button, onClickLabel = desc, onClick = { _, _, _ -> onClick() }),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun TreeItem(vm: BrowserViewModel, s: AppState, r: TreeRowData, activePath: String, menu: MenuController, mount: Mount? = null) {
    val driveAcc = r.driveAccount
    val phone = r.pairedPhone
    val selected = when {
        driveAcc != null && !r.expanded ->
            (driveAcc.mountPath != null && (driveAcc.mountPath == activePath || FsPath.isWithin(driveAcc.mountPath, activePath))) ||
                activePath.startsWith("gdrive://${driveAcc.email}")
        phone != null && !r.expanded ->
            activePath == phone.path || FsPath.isWithin(phone.path, activePath) || activePath == FsPath.PHONE_ROOT
        else -> r.path == activePath
    }
    SidebarItem(
        label = r.label, icon = r.icon, depth = r.depth, selected = selected,
        dropPath = r.path.takeIf { !FsPath.isVirtual(it) && (!FsPath.isSmb(it) || FsPath.parseSmb(it).share != null) },
        subtitle = r.subtitle,
        iconComposable = if (driveAcc != null) ({ GoogleDriveIcon(Modifier.size(20.dp)) }) else null,
        expandable = r.expandable, expanded = r.expanded, loading = r.loading, onToggle = { vm.toggleTree(r.path) },
        trailing = when {
            driveAcc != null && driveAcc.isMounted && mount != null -> ({
                HeaderAction(Icons.Outlined.Eject, "Unmount ${driveAcc.email}") { vm.eject(mount) }
            })
            phone != null && phone.isMounted && mount != null -> ({
                HeaderAction(Icons.Outlined.Eject, "Unmount ${phone.name}") { vm.eject(mount) }
            })
            mount != null -> ({ HeaderAction(Icons.Outlined.Eject, if (mount.saf) "Unmount ${mount.label}" else "Disconnect ${mount.label}") { vm.eject(mount) } })
            r.path in s.pins -> ({ HeaderAction(Icons.Filled.PushPin, "Unpin ${r.label}") { vm.unpin(r.path) } })
            else -> null
        },
        onClick = {
            when {
                driveAcc != null -> vm.openDriveAccount(driveAcc)
                phone != null -> vm.openPairedPhone(phone)
                else -> vm.navigate(r.path)
            }
        },
        onMiddle = {
            when {
                driveAcc != null -> driveAcc.mountPath?.let { vm.newTab(it, activate = false) }
                phone != null -> vm.newTab(phone.path, activate = false)
                else -> vm.newTab(r.path, activate = false)
            }
        },
        onSecondary = {
            when {
                driveAcc != null -> menu.show(it, driveAccountMenu(vm, driveAcc, mount))
                phone != null -> menu.show(it, phoneMenu(vm, phone, mount))
                else -> {
                    val base = sidebarMenu(vm, s, r.path, r.path in s.pins)
                    menu.show(it, if (mount == null) base else base + mountMenu(vm, mount))
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
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
    iconComposable: (@Composable () -> Unit)? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    loading: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onToggle: () -> Unit = {},
    onClick: () -> Unit,
    onMiddle: () -> Unit = {},
    onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
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
    val itemHeight = if (subtitle != null) 44.dp else 36.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = itemHeight).clip(RoundedCornerShape(18.dp)).background(bg).hoverable(hover)
            .dropTarget(dropPath)
            .mouseGestures(
                label + depth + dropPath + subtitle.orEmpty(),
                role = Role.Button,
                selected = selected,
                onClickLabel = "Open $label",
                onClick = { _, _, _ -> onClick() }, onMiddle = onMiddle,
                onSecondary = onSecondary, onLongPress = onSecondary,
            )
            .padding(start = 6.dp + 14.dp * depth, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(RoundedCornerShape(14.dp)).then(
                if (expandable) {
                    Modifier.mouseGestures(
                        "toggle$label$depth",
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse $label" else "Expand $label",
                        onClick = { _, _, _ -> onToggle() },
                    )
                } else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                expandable -> Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, if (expanded) "Collapse" else "Expand",
                    Modifier.size(18.dp), tint = cs.onSurfaceVariant,
                )
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
            Text(
                label, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) cs.onSecondaryContainer else cs.onSurface,
            )
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
