// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import com.droid.wayfinder.R
import com.droid.wayfinder.data.SettingsSection
import com.droid.wayfinder.data.SidebarModule
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.data.headerLabel
import com.droid.wayfinder.data.normalizeColumnOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val PRIVACY_POLICY_URL = "https://parijat-star.github.io/wayfinder/privacy.html"

@Composable
private fun SettingsSection.label(): String = when (this) {
    SettingsSection.Integrations -> stringResource(R.string.settings_section_integrations)
    SettingsSection.Appearance -> stringResource(R.string.settings_section_appearance)
    SettingsSection.FilesAndView -> stringResource(R.string.settings_section_files_view)
    SettingsSection.SystemAndAbout -> stringResource(R.string.settings_section_system_about)
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.Integrations -> Icons.Outlined.Extension
    SettingsSection.Appearance -> Icons.Outlined.Contrast
    SettingsSection.FilesAndView -> Icons.Outlined.ViewHeadline
    SettingsSection.SystemAndAbout -> Icons.Outlined.Info
}

@Composable
fun SettingsDialog(
    vm: BrowserViewModel,
    s: AppState,
    initialSection: SettingsSection = SettingsSection.Integrations,
    hasDesktopCaption: Boolean = WindowInsets.captionBar.getTop(LocalDensity.current) > 0,
) {
    val cs = MaterialTheme.colorScheme
    val activeSection = (s.dialog as? Dialog.Settings)?.initialSection ?: initialSection

    ComposeDialog(
        onDismissRequest = vm::closeSettings,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
        ) {
            val isTwoPane = maxWidth >= 720.dp
            val scrollState = rememberScrollState(0)

            if (isTwoPane) {
                Surface(
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(max = 840.dp)
                        .fillMaxWidth(0.92f)
                        .heightIn(min = 460.dp, max = 660.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = cs.surface,
                    tonalElevation = 3.dp,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(236.dp)
                                .fillMaxHeight()
                                .background(cs.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = cs.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            SettingsSection.entries.forEach { section ->
                                val selected = section == activeSection
                                val itemShape = RoundedCornerShape(16.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .focusRing(itemShape)
                                        .clip(itemShape)
                                        .background(if (selected) cs.secondaryContainer else Color.Transparent)
                                        .selectable(
                                            selected = selected,
                                            role = Role.Tab,
                                            onClick = { vm.updateSettingsSection(section) },
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        section.icon(),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                                    )
                                    Text(
                                        text = section.label(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) cs.onSecondaryContainer else cs.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        VerticalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = activeSection.label(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = cs.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = vm::closeSettings,
                                    modifier = Modifier.size(48.dp).focusRing(CircleShape),
                                ) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SettingsSectionBody(vm, s, activeSection, hasDesktopCaption)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = cs.surface,
                ) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = vm::closeSettings,
                                modifier = Modifier.size(48.dp).focusRing(CircleShape),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_close))
                            }
                            Text(
                                text = stringResource(R.string.settings_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = cs.onSurface,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SettingsSection.entries.forEach { section ->
                                FilterChip(
                                    selected = section == activeSection,
                                    onClick = { vm.updateSettingsSection(section) },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    leadingIcon = { Icon(section.icon(), null, Modifier.size(16.dp)) },
                                    label = { Text(section.label(), maxLines = 1) },
                                )
                            }
                        }
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SettingsSectionBody(vm, s, activeSection, hasDesktopCaption)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionBody(
    vm: BrowserViewModel,
    s: AppState,
    section: SettingsSection,
    hasDesktopCaption: Boolean,
) {
    when (section) {
        SettingsSection.Integrations -> IntegrationsSettingsContent(vm, s)
        SettingsSection.Appearance -> AppearanceSettingsContent(vm, s, hasDesktopCaption)
        SettingsSection.FilesAndView -> FilesAndViewSettingsContent(vm, s)
        SettingsSection.SystemAndAbout -> SystemAndAboutSettingsContent(vm, s)
    }
}

@Composable
private fun SettingsCard(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = cs.primary)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    checked: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .focusRing(rowShape)
            .clip(rowShape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                leading()
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    BetaBadge(badge)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntegrationsSettingsContent(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val vis = s.sidebarVisibility
    val btnMod = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

    Text(
        text = "Choose which cloud accounts, devices, and sections appear in the sidebar. Hiding a section keeps saved sign-ins and mounts intact.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )

    // 1. Google Drive
    SettingsCard {
        val driveSub = if (s.driveAccounts.isEmpty()) {
            "Connect Google Account or mount a Drive folder"
        } else {
            s.driveAccounts.joinToString { it.email }
        }
        SettingsToggleRow(
            title = stringResource(R.string.google_drive_section),
            subtitle = driveSub,
            checked = vis.googleDrive,
            leading = { GoogleDriveIcon(Modifier.size(22.dp)) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.GoogleDrive, it) },
        )
        FlowRow(
            modifier = Modifier.padding(start = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    if (s.driveAccounts.isEmpty()) vm.requestGoogleAccountSignIn()
                    else vm.requestMountSaf(s.driveAccounts.first().email)
                },
                modifier = btnMod,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text(stringResource(R.string.action_mount_drive_folder), Modifier.padding(start = 6.dp))
            }
        }
    }

    // 2. Microsoft OneDrive
    SettingsCard {
        val oneDriveSub = when (val count = s.oneDriveAccounts.size) {
            0 -> stringResource(R.string.onedrive_sign_in_subtitle)
            1 -> "1 account connected"
            else -> "$count accounts connected"
        }
        SettingsToggleRow(
            title = stringResource(R.string.onedrive_section),
            subtitle = oneDriveSub,
            checked = vis.oneDrive,
            leading = { OneDriveIcon(Modifier.size(22.dp)) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.OneDrive, it) },
        )
        s.oneDriveAccounts.forEach { acc ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(acc.sidebarTitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    Text(acc.sidebarSubtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                TextButton(
                    onClick = { vm.signOutOneDrive(acc.accountId) },
                    modifier = btnMod,
                ) {
                    Text(stringResource(R.string.onedrive_action_sign_out))
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(start = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { vm.showConnectOneDriveDialog() },
                modifier = btnMod,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text(stringResource(R.string.palette_connect_onedrive), Modifier.padding(start = 6.dp))
            }
        }
    }

    // 3. Dropbox (Beta)
    SettingsCard {
        val dropboxSub = when (val count = s.dropboxAccounts.size) {
            0 -> stringResource(R.string.dropbox_sign_in_subtitle)
            1 -> "1 account connected"
            else -> "$count accounts connected"
        }
        SettingsToggleRow(
            title = stringResource(R.string.dropbox_section),
            subtitle = dropboxSub,
            badge = stringResource(R.string.badge_beta),
            checked = vis.dropbox,
            leading = { DropboxIcon(Modifier.size(22.dp)) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.Dropbox, it) },
        )
        s.dropboxAccounts.forEach { acc ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(acc.sidebarTitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    Text(acc.sidebarSubtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                TextButton(
                    onClick = { vm.signOutDropbox(acc.accountId) },
                    modifier = btnMod,
                ) {
                    Text(stringResource(R.string.dropbox_action_sign_out))
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(start = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { vm.showConnectDropboxDialog() },
                modifier = btnMod,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text(stringResource(R.string.palette_connect_dropbox), Modifier.padding(start = 6.dp))
            }
        }
    }

    // 4. Network & Cloud (SMB / SFTP / WebDAV / FTP / SAF)
    SettingsCard {
        val serverCountText = when (val count = s.savedServers.size) {
            1 -> "1 saved server"
            else -> "$count saved servers"
        }
        val netSub = if (vis.networkAndCloud) {
            "SMB, WebDAV, FTP & SAF mounts · $serverCountText"
        } else {
            "Hidden · Background LAN discovery paused ($serverCountText)"
        }
        SettingsToggleRow(
            title = stringResource(R.string.network_and_cloud_section),
            subtitle = netSub,
            checked = vis.networkAndCloud,
            leading = { Icon(Icons.Outlined.Dns, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.NetworkAndCloud, it) },
        )
        FlowRow(
            modifier = Modifier.padding(start = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { vm.showConnectDialog() }, modifier = btnMod) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text(stringResource(R.string.palette_connect_server), Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = { vm.requestMountSaf() }, modifier = btnMod) {
                Text(stringResource(R.string.palette_mount_provider))
            }
        }
    }

    // 5. Phone (Beta)
    SettingsCard {
        val phoneSub = when {
            !vis.phone -> "Hidden · Background Bluetooth & Cross-Device polling paused"
            s.pairedPhone != null -> "${s.pairedPhone.name} · ${s.pairedPhone.subtitle}"
            else -> "Cross-Device & USB MTP companion phone integration"
        }
        SettingsToggleRow(
            title = "Phone",
            subtitle = phoneSub,
            badge = stringResource(R.string.badge_beta),
            checked = vis.phone,
            leading = { Icon(Icons.Outlined.Smartphone, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.Phone, it) },
        )
    }

    // 6. Linux files (Beta)
    SettingsCard {
        val linuxSub = when {
            s.linuxRootPath != null -> "Mounted (${s.linuxRootPath})"
            s.linuxProviderAuthority != null -> "Linux development environment available"
            else -> "Android Linux development environment"
        }
        SettingsToggleRow(
            title = "Linux files",
            subtitle = linuxSub,
            badge = stringResource(R.string.badge_beta),
            checked = vis.linuxFiles,
            leading = { Icon(Icons.Outlined.Dns, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.LinuxFiles, it) },
        )
    }

    // 7. Color tags
    SettingsCard {
        SettingsToggleRow(
            title = stringResource(R.string.color_tags_section),
            subtitle = icuStringResource(R.string.item_count_plural, s.marks.tags.size),
            checked = vis.colorTags,
            leading = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant) },
            onCheckedChange = { vm.setSidebarModuleVisible(SidebarModule.ColorTags, it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettingsContent(
    vm: BrowserViewModel,
    s: AppState,
    hasDesktopCaption: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val btnMod = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

    SettingsCard(
        title = "Theme",
        subtitle = "Choose light, dark, or match Android's system theme across all Wayfinder windows.",
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = s.theme == mode,
                    onClick = { vm.setThemeMode(mode) },
                    modifier = btnMod,
                    label = { Text(mode.label) },
                )
            }
        }
    }

    SettingsCard(
        title = "Interface zoom (${(s.zoom * 100).roundToInt()}%)",
        subtitle = "Scale text, icons, and rows across the browser (70%–160%).",
    ) {
        Slider(
            value = s.zoom,
            onValueChange = { vm.setZoom(it) },
            valueRange = 0.7f..1.6f,
            steps = 17,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Interface zoom" },
        )
        val zoomOutDesc = stringResource(R.string.palette_zoom_out)
        val zoomInDesc = stringResource(R.string.palette_zoom_in)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionTooltip(zoomOutDesc) {
                OutlinedIconButton(
                    onClick = { vm.zoomBy(-1) },
                    modifier = Modifier.size(48.dp).focusRing(CircleShape),
                    enabled = s.zoom > 0.71f,
                ) {
                    Icon(Icons.Outlined.Remove, zoomOutDesc, Modifier.size(18.dp))
                }
            }
            OutlinedButton(
                onClick = { vm.zoomBy(0) },
                modifier = btnMod.weight(1f, fill = false),
                enabled = s.zoom != 1f,
            ) {
                Text("Reset (100%)", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ActionTooltip(zoomInDesc) {
                OutlinedIconButton(
                    onClick = { vm.zoomBy(1) },
                    modifier = Modifier.size(48.dp).focusRing(CircleShape),
                    enabled = s.zoom < 1.59f,
                ) {
                    Icon(Icons.Outlined.Add, zoomInDesc, Modifier.size(18.dp))
                }
            }
        }
    }

    SettingsCard(title = "Status bars") {
        SettingsToggleRow(
            title = stringResource(R.string.menu_status_bar),
            subtitle = stringResource(R.string.palette_app_status_bar_desc),
            checked = s.statusBarVisible,
            onCheckedChange = { if (s.statusBarVisible != it) vm.toggleStatusBar() },
        )
        if (!hasDesktopCaption) {
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
            SettingsToggleRow(
                title = stringResource(R.string.menu_hide_status_bar),
                subtitle = stringResource(R.string.palette_status_bar_desc),
                checked = s.hideStatusBar,
                onCheckedChange = { if (s.hideStatusBar != it) vm.toggleHideStatusBar() },
            )
        }
    }
}

@Composable
private fun FilesAndViewSettingsContent(vm: BrowserViewModel, s: AppState) {
    val cs = MaterialTheme.colorScheme
    val btnMod = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

    SettingsCard(title = "Browsing") {
        SettingsToggleRow(
            title = "Show hidden files",
            subtitle = "Display dotfiles and folders starting with '.'",
            checked = s.showHidden,
            onCheckedChange = { if (s.showHidden != it) vm.toggleHidden() },
        )
    }

    SettingsCard(
        title = "Grid thumbnail size (${s.gridSize.roundToInt()} dp)",
        subtitle = "Adjust tile size used in Grid view (80–280 dp).",
    ) {
        Slider(
            value = s.gridSize,
            onValueChange = { vm.setGridSize(it) },
            valueRange = 80f..280f,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Grid thumbnail size" },
        )
        OutlinedButton(
            onClick = { vm.setGridSize(128f) },
            modifier = btnMod,
            enabled = s.gridSize.roundToInt() != 128,
        ) {
            Text("Reset (128 dp)")
        }
    }

    val orderedColumns = remember(s.columnOrder) { normalizeColumnOrder(s.columnOrder) }
    SettingsCard(
        title = "Details view columns",
        subtitle = "Toggle visible metadata columns and change their left-to-right display order.",
    ) {
        orderedColumns.forEachIndexed { idx, col ->
            val colTitle = when (col) {
                SortKey.Modified -> stringResource(R.string.menu_modified_column)
                SortKey.Type -> stringResource(R.string.menu_type_column)
                SortKey.Size -> stringResource(R.string.menu_size_column)
                SortKey.Tags -> stringResource(R.string.menu_tags_column)
                SortKey.Permissions -> stringResource(R.string.menu_perms_column)
                SortKey.Name -> col.headerLabel
            }
            if (idx > 0) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SettingsToggleRow(
                        title = colTitle,
                        checked = s.isColumnVisible(col),
                        onCheckedChange = { vm.setColumnVisible(col, it) },
                    )
                }
                IconButton(
                    onClick = { vm.moveColumn(col, -1, scope = orderedColumns) },
                    enabled = idx > 0,
                    modifier = Modifier.size(48.dp).focusRing(CircleShape),
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Move ${col.headerLabel} up")
                }
                IconButton(
                    onClick = { vm.moveColumn(col, 1, scope = orderedColumns) },
                    enabled = idx < orderedColumns.lastIndex,
                    modifier = Modifier.size(48.dp).focusRing(CircleShape),
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Move ${col.headerLabel} down")
                }
            }
        }
        OutlinedButton(
            onClick = { vm.resetColumns() },
            modifier = btnMod,
        ) {
            Text(stringResource(R.string.action_reset_columns))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemAndAboutSettingsContent(vm: BrowserViewModel, s: AppState) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val btnMod = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    val versionName = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val noticesText by produceState<String?>(initialValue = null, key1 = showLicenses) {
        if (showLicenses && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                }.getOrDefault("Open-source notices unavailable.")
            }
        }
    }

    SettingsCard(title = "System & prompts") {
        SettingsToggleRow(
            title = "Show folder-picker mount tip",
            subtitle = "Display setup guidance before opening Android's folder picker for cloud or external storage",
            checked = s.showSafMountTip,
            onCheckedChange = { vm.setShowSafMountTip(it) },
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.showDefaultHandlerDialog() },
                modifier = btnMod,
            ) {
                Text(stringResource(R.string.action_default_file_handler))
            }
            OutlinedButton(
                onClick = { vm.showDialog(Dialog.Shortcuts) },
                modifier = btnMod,
            ) {
                Icon(Icons.Outlined.Keyboard, null, Modifier.size(16.dp))
                Text("Keyboard shortcuts", Modifier.padding(start = 6.dp))
            }
        }
    }

    SettingsCard(title = "About Wayfinder") {
        if (versionName != null) {
            Text("Version $versionName", style = MaterialTheme.typography.titleSmall, color = cs.primary)
        }
        Text(
            text = "Desktop-class file manager for Android laptops, tablets, and phones with multi-tab browsing, dual-pane transfers, archive support, and cloud / LAN storage.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
        )
        Text(
            text = "Copyright 2026 The Wayfinder Authors. Licensed under the Apache License, Version 2.0.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.openExternalUrl(PRIVACY_POLICY_URL) },
                modifier = btnMod,
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                Text("Privacy policy", Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { showLicenses = !showLicenses },
                modifier = btnMod,
            ) {
                Text(if (showLicenses) "Hide open-source licenses" else "Open-source licenses")
            }
        }
        if (showLicenses) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cs.surface,
                border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)),
            ) {
                SelectionContainer {
                    Text(
                        text = noticesText ?: "Loading notices…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}
