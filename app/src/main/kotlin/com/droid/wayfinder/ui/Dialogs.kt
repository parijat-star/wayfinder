// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.droid.wayfinder.R
import com.droid.wayfinder.data.RenameItem
import com.droid.wayfinder.data.RenamePlan
import com.droid.wayfinder.data.RenameRule
import com.droid.wayfinder.data.planBatchRename
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.formatItemCount
import com.droid.wayfinder.data.formatSize

@Composable
fun Dialogs(vm: BrowserViewModel, s: AppState) {
    when (val d = s.dialog) {
        null -> {}
        is Dialog.Rename -> NameDialog(stringResource(R.string.action_rename), d.name, stringResource(R.string.action_rename), selectBase = true, onDismiss = vm::dismissDialog) { vm.rename(d.path, it) }
        is Dialog.NewItem -> NameDialog(
            if (d.folder) stringResource(R.string.action_new_folder) else stringResource(R.string.dialog_new_file_title),
            if (d.folder) stringResource(R.string.action_new_folder) else stringResource(R.string.dialog_new_file_default),
            stringResource(R.string.action_create),
            selectBase = true, onDismiss = vm::dismissDialog,
        ) { vm.createItem(it, d.folder) }
        is Dialog.Compress -> NameDialog(stringResource(R.string.action_compress_to_zip), d.suggested, stringResource(R.string.action_compress), selectBase = false, onDismiss = vm::dismissDialog, suffix = ".zip") { vm.compress(d.sources, it) }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text(stringResource(R.string.dialog_delete_permanent_title)) },
            text = { Text(if (d.paths.size == 1) stringResource(R.string.dialog_delete_single_message, FsPath.name(d.paths[0])) else icuStringResource(R.string.dialog_delete_many_plural, d.paths.size)) },
            confirmButton = { TextButton({ vm.confirmDelete(d.paths) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(vm::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        is Dialog.ConflictPrompt -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text(if (d.names.size == 1) stringResource(R.string.dialog_conflict_single_title, d.names[0]) else icuStringResource(R.string.dialog_conflict_many_plural, d.names.size)) },
            text = { Text(stringResource(R.string.dialog_conflict_message)) },
            confirmButton = {
                Row {
                    TextButton({ vm.resolveConflict(d, Conflict.Replace) }) { Text(stringResource(R.string.action_replace)) }
                    TextButton({ vm.resolveConflict(d, Conflict.KeepBoth) }) { Text(stringResource(R.string.action_keep_both)) }
                    TextButton({ vm.resolveConflict(d, Conflict.Skip) }) { Text(stringResource(R.string.action_skip)) }
                }
            },
            dismissButton = { TextButton(vm::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        is Dialog.Properties -> PropertiesDialog(d, vm::dismissDialog) { text, what -> vm.copyValue(text, what) }
        is Dialog.Connect -> ConnectDialog(vm, d.initial, s.savedServers, vm::dismissDialog)
        is Dialog.SmbAuth -> SmbDialog(
            stringResource(R.string.dialog_smb_sign_in_title, d.host), stringResource(R.string.dialog_smb_sign_in_hint),
            d.host, d.user, d.domain, askAddress = false, confirm = stringResource(R.string.action_sign_in), onDismiss = vm::dismissDialog,
            // Domains only exist for SMB; other protocols use `scheme://host` credential keys.
            askDomain = !d.host.contains("://"),
        ) { _, u, p, dom, r -> vm.submitAuth(d.host, u, p, dom, r) }
        Dialog.Shortcuts -> ShortcutsDialog(vm::dismissDialog)
        is Dialog.SafMountTip -> SafMountTipDialog(accountEmail = d.accountEmail, onDismiss = vm::dismissDialog, onProceed = { vm.confirmSafMountTip(it, d.accountEmail) })
        is Dialog.BatchRename -> BatchRenameDialog(d, vm::dismissDialog) { plan -> vm.batchRename(d.dir, plan) }
    }
}

@Composable
private fun NameDialog(
    title: String, initial: String, confirm: String, selectBase: Boolean, onDismiss: () -> Unit,
    suffix: String = "", onConfirm: (String) -> Unit,
) {
    val fr = remember { FocusRequester() }
    var tf by remember {
        val dot = initial.lastIndexOf('.')
        mutableStateOf(TextFieldValue(initial, if (selectBase && dot > 0) TextRange(0, dot) else TextRange(0, initial.length)))
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(
                tf, { tf = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(tf.text) }),
            )
        },
        confirmButton = { TextButton({ onConfirm(tf.text) }, enabled = tf.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PropertiesDialog(d: Dialog.Properties, onDismiss: () -> Unit, onCopy: (text: String, what: String) -> Unit) {
    val e = d.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 480.dp),
        title = { Text(if (e.size == 1) e[0].name else formatItemCount(e.size)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e.size == 1) {
                    Row2("Name", e[0].name)
                    Row2("Kind", e[0].typeLabel)
                    Row2("Path", e[0].path, mono = true) { onCopy(e[0].path, "Path") }
                    Row2("Modified", formatDateFull(e[0].modified))
                }
                Row2(stringResource(R.string.properties_size_label), d.bytes?.let { "${formatSize(it)} (${icuStringResource(R.string.byte_count_plural, it)})" } ?: stringResource(R.string.properties_calculating))
                d.items?.let { if (e.size > 1 || e[0].isDir) Row2(stringResource(R.string.properties_files_label), formatLocalizedNumber(it.toLong())) }
                if (e.size > 1) Row2(
                    stringResource(R.string.properties_contains_label),
                    stringResource(
                        R.string.properties_contains_format,
                        icuStringResource(R.string.folder_count_plural, e.count { it.isDir }),
                        icuStringResource(R.string.file_count_plural, e.count { !it.isDir }),
                    ),
                )
                d.posix?.let { p ->
                    Text("POSIX", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row2(stringResource(R.string.properties_permissions_label), "${FileInfo.posixString(p.mode)}  (${FileInfo.octal(p.mode)})", mono = true)
                    Row2(stringResource(R.string.properties_inode_label), p.inode.toString(), mono = true)
                    Row2(stringResource(R.string.properties_links_label), p.links.toString(), mono = true)
                    Row2(stringResource(R.string.properties_owner_label), "uid ${p.uid}  •  gid ${p.gid}", mono = true)
                }
                d.space?.let { sp ->
                    Row2(stringResource(R.string.properties_volume_label), stringResource(R.string.properties_volume_free_of_total, formatSize(sp.free), formatSize(sp.total)))
                }
                if (d.checksums != null || d.hashProgress != null) {
                    Text(stringResource(R.string.properties_checksums_section), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    d.hashProgress?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    d.checksums?.let { c ->
                        Row2("SHA-256", c.sha256, mono = true) { onCopy(c.sha256, "SHA-256") }
                        Row2("CRC32", c.crc32, mono = true) { onCopy(c.crc32, "CRC32") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun Row2(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        if (onCopy != null) IconButton(onCopy, Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy $label", Modifier.size(16.dp)) }
    }
}

@Composable
private fun SmbDialog(
    title: String, hint: String, address0: String, user0: String, domain0: String,
    askAddress: Boolean, confirm: String, onDismiss: () -> Unit, askDomain: Boolean = true,
    onConfirm: (address: String, user: String, password: String, domain: String, remember: Boolean) -> Unit,
) {
    var address by remember { mutableStateOf(address0) }
    var user by remember { mutableStateOf(user0) }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(domain0) }
    var rememberPw by remember { mutableStateOf(true) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    val go = { onConfirm(address, user, pass, domain, rememberPw) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (askAddress) OutlinedTextField(
                    address, { address = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true, label = { Text(stringResource(R.string.dialog_server_address_label)) },
                    placeholder = { Text("smb://server/share") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth().then(if (!askAddress) Modifier.focusRequester(fr) else Modifier),
                    singleLine = true, label = { Text(stringResource(R.string.dialog_username_guest_label)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dialog_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                )
                if (askDomain) OutlinedTextField(
                    domain, { domain = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dialog_domain_optional_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberPw, { rememberPw = it })
                    Text(stringResource(R.string.dialog_remember_password), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton({ go() }, enabled = !askAddress || address.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val shortcuts = listOf(
    "Tabs & windows" to listOf(
        "New tab" to "Ctrl+T", "Close tab" to "Ctrl+W", "Next / previous tab" to "Ctrl+Tab / Ctrl+Shift+Tab",
        "Go to tab 1–9" to "Alt+1 … Alt+9", "Reopen closed tab" to "Ctrl+Shift+T", "Open folder in new tab" to "Ctrl+Enter / middle-click",
        "Command palette" to "Ctrl+K", "New window" to "Ctrl+N",
    ),
    "Navigate" to listOf(
        "Back / forward" to "Alt+← / Alt+→ or Backspace", "Up one level" to "Alt+↑", "Open" to "Enter / double-click", "Quick Look preview" to "Space",
        "Address bar" to "Ctrl+L or Alt+D", "Search" to "Ctrl+F or Ctrl+E", "Refresh" to "F5 / Ctrl+R", "Connect to server" to "Ctrl+Shift+K",
    ),
    "Select & edit" to listOf(
        "Select all / invert / none" to "Ctrl+A / Ctrl+Shift+I / Ctrl+Shift+A", "Extend selection" to "Shift+↑↓←→ / Shift+click", "Toggle item" to "Ctrl+click / Ctrl+Space",
        "Cut / copy / paste" to "Ctrl+X / Ctrl+C / Ctrl+V", "Undo / redo" to "Ctrl+Z / Ctrl+Y or Ctrl+Shift+Z", "Duplicate" to "Ctrl+D",
        "Rename / batch rename" to "F2 / Ctrl+Shift+R", "Copy path" to "Ctrl+Shift+C",
        "Delete / delete permanently" to "Del / Shift+Del", "New folder / text file" to "Ctrl+Shift+N / Ctrl+Shift+M", "Properties" to "Ctrl+I / Alt+Enter",
    ),
    "View" to listOf(
        "Details / grid / columns" to "Ctrl+1 / Ctrl+2 / Ctrl+3", "Zoom grid" to "Ctrl+scroll", "Hidden files" to "Ctrl+H",
        "Pin / unpin folder" to "Ctrl+B", "Sidebar" to "Ctrl+\\", "Details pane" to "Alt+P",
        "Locate folder in tree" to "Alt+Shift+L", "Interface zoom in / out / reset" to "Ctrl+= / Ctrl+- / Ctrl+0", "This help" to "F1",
    ),
    "Dual pane" to listOf(
        "Toggle dual pane" to "Ctrl+4 / F3", "Switch active pane" to "Tab", "Copy / move to other pane" to "F5 / F6",
    ),
)

@Composable
private fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Keyboard & trackpad shortcuts") },
        modifier = Modifier.widthIn(min = 560.dp),
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                shortcuts.forEach { (group, items) ->
                    Text(group, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    items.forEach { (a, k) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(k, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.shortcuts_trackpad_heading), Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.shortcuts_trackpad_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private enum class RenameMode(@param:androidx.annotation.StringRes val labelRes: Int) {
    FindReplace(R.string.batch_rename_mode_find_replace),
    Affix(R.string.batch_rename_mode_affix),
    Numbered(R.string.batch_rename_mode_numbered),
}

@Composable
private fun BatchRenameDialog(d: Dialog.BatchRename, onDismiss: () -> Unit, onApply: (List<RenamePlan>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(RenameMode.FindReplace) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(FsPath.name(d.dir).ifEmpty { "File" }) }
    var start by remember { mutableStateOf("1") }
    val rule = when (mode) {
        RenameMode.FindReplace -> RenameRule.FindReplace(find, replace, matchCase)
        RenameMode.Affix -> RenameRule.PrefixSuffix(prefix, suffix)
        RenameMode.Numbered -> RenameRule.Numbered(base.trim(), start.toIntOrNull() ?: 1)
    }
    val plan = remember(rule, d) { planBatchRename(d.items.map { RenameItem(it.name, it.isDir) }, rule, d.existing) }
    val conflicts = plan.count { it.conflict != null }
    val changes = plan.count { it.changed }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp),
        title = { Text(if (d.items.size == 1) "Rename 1 item" else "Batch rename ${d.items.size} items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RenameMode.entries.forEach { m -> FilterChip(m == mode, { mode = m }, label = { Text(stringResource(m.labelRes)) }) }
                }
                when (mode) {
                    RenameMode.FindReplace -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(find, { find = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.batch_rename_find_label)) })
                            OutlinedTextField(replace, { replace = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.batch_rename_replace_label)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(matchCase, { matchCase = it })
                            Text(stringResource(R.string.batch_rename_match_case), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    RenameMode.Affix -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(prefix, { prefix = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.batch_rename_prefix_label)) })
                        OutlinedTextField(suffix, { suffix = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.batch_rename_suffix_label)) })
                    }
                    RenameMode.Numbered -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(base, { base = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.batch_rename_base_name_label)) })
                        OutlinedTextField(
                            start, { v -> start = v.filter { it.isDigit() }.take(6) }, Modifier.width(120.dp), singleLine = true,
                            label = { Text(stringResource(R.string.batch_rename_start_num_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                Text(
                    when {
                        conflicts > 0 -> icuStringResource(R.string.batch_rename_conflicts_plural, conflicts)
                        changes == 0 -> stringResource(R.string.batch_rename_no_changes)
                        else -> icuStringResource(R.string.batch_rename_changes_plural, changes)
                    },
                    style = MaterialTheme.typography.labelLarge, color = if (conflicts > 0) cs.error else cs.onSurfaceVariant,
                )
                Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerLow) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(vertical = 4.dp)) {
                        items(plan) { p ->
                            val bad = p.conflict != null
                            Row(
                                Modifier.fillMaxWidth().background(if (bad) cs.errorContainer else Color.Transparent).padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(p.old, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (bad) cs.onErrorContainer else cs.onSurfaceVariant)
                                Text("→", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                Text(
                                    p.new + (p.conflict?.let { "  ($it)" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = if (bad) cs.onErrorContainer else if (p.changed) cs.onSurface else cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onApply(plan) }, enabled = conflicts == 0 && changes > 0) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SafMountTipDialog(accountEmail: String? = null, onDismiss: () -> Unit, onProceed: (dontShowAgain: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var dontShowAgain by remember { mutableStateOf(false) }
    val isDrive = accountEmail != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (isDrive) GoogleDriveIcon(Modifier.size(32.dp))
            else Icon(Icons.Outlined.Cloud, null, Modifier.size(28.dp), tint = cs.primary)
        },
        title = {
            Text(if (isDrive) stringResource(R.string.saf_tip_drive_title) else stringResource(R.string.saf_tip_generic_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (isDrive) {
                        stringResource(R.string.saf_tip_drive_intro, accountEmail.orEmpty())
                    } else {
                        stringResource(R.string.saf_tip_generic_intro)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.surfaceContainerHigh,
                    border = BorderStroke(1.dp, cs.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = cs.primary)
                            Text(if (isDrive) stringResource(R.string.saf_tip_drive_callout_title) else stringResource(R.string.saf_tip_generic_callout_title), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                        }
                        Text(
                            if (isDrive) {
                                "In the file picker, select your account ($accountEmail) and open any folder inside your Drive (e.g. My Drive > cloudtest or create a new folder), then tap \"Use this folder\".\n\nAndroid privacy guidelines restrict selecting the top-level root directly, but any folder inside is fully accessible."
                            } else {
                                "In the file picker, open any folder inside your Drive or storage (e.g. Work, Documents, or create a new folder).\n\nAndroid blocks selecting the top-level root (\"My Drive\") for privacy, but any subfolder is fully allowed."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { dontShowAgain = !dontShowAgain },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text(stringResource(R.string.saf_tip_dont_show_again), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onProceed(dontShowAgain) }) {
                Text(if (isDrive) stringResource(R.string.saf_tip_open_drive_picker) else stringResource(R.string.saf_tip_open_folder_picker))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}


