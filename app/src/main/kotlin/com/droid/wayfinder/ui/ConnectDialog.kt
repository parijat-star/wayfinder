// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.data.ConnectConfig
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.data.RemoteUri
import com.droid.wayfinder.data.SavedServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Connect to Server" (Ctrl+Shift+K): protocol, address (URL or UNC path, auto-parsed into the fields), port,
 * share / base path, credentials or guest, sidebar alias, Test connection and Connect & Mount.
 */
@Composable
fun ConnectDialog(vm: BrowserViewModel, initial: ConnectConfig?, saved: List<SavedServer>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var protocol by remember { mutableStateOf(initial?.protocol ?: Protocol.SMB) }
    var address by remember { mutableStateOf(initial?.address?.rootPath.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: Protocol.SMB.defaultPort).toString()) }
    var path by remember { mutableStateOf(initial?.path.orEmpty()) }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var guest by remember { mutableStateOf(initial?.guest ?: false) }
    var alias by remember { mutableStateOf(initial?.alias.orEmpty()) }
    var rememberPw by remember { mutableStateOf(initial?.remember ?: true) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    fun config() = ConnectConfig(
        protocol, host.trim(), port.toIntOrNull()?.takeIf { it in 1..65535 } ?: protocol.defaultPort, path,
        user, pass, domain, guest, alias, rememberPw,
    )

    fun fill(c: ConnectConfig) {
        protocol = c.protocol; host = c.host; port = c.port.toString(); path = c.path; user = c.user; pass = c.password
        domain = c.domain; guest = c.guest; alias = c.alias; rememberPw = c.remember; address = c.address.rootPath; result = null
    }

    fun setProtocol(p: Protocol) {
        if (port.isBlank() || port.toIntOrNull() == protocol.defaultPort) port = p.defaultPort.toString()
        protocol = p
        result = null
    }

    fun onAddress(text: String) {
        address = text
        result = null
        val explicit = text.contains("://") || text.trimStart().startsWith("\\\\") || text.trimStart().startsWith("//")
        val a = RemoteUri.parse(text, protocol) ?: return
        if (explicit) protocol = a.protocol
        host = a.host
        port = a.port.toString()
        path = a.path
        a.user?.let { user = it; guest = false }
        a.password?.let { pass = it }
    }

    val canGo = host.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text("Connect to Server") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Protocol.entries.forEach { p -> FilterChip(p == protocol, { setProtocol(p) }, label = { Text(p.label) }) }
                }
                if ((protocol == Protocol.FTP || protocol == Protocol.DAV) && !guest) {
                    val secureAlt = if (protocol == Protocol.FTP) Protocol.FTPS.label else Protocol.DAVS.label
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = cs.error)
                        Text(
                            "Warning: Credentials and files are sent unencrypted over ${protocol.label}. Prefer $secureAlt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.error,
                        )
                    }
                }
                OutlinedTextField(
                    address, ::onAddress, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                    label = { Text("Server address or UNC path") },
                    placeholder = { Text("smb://nas/media  •  \\\\nas\\media  •  davs://cloud/dav  •  ftp://user@host:21/pub") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim(); result = null }, Modifier.weight(1f), singleLine = true, label = { Text("Host") })
                    OutlinedTextField(
                        port, { v -> port = v.filter { it.isDigit() }.take(5); result = null }, Modifier.width(110.dp), singleLine = true,
                        label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    path, { path = it; result = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (protocol == Protocol.SMB) "Share / folder (optional)" else "Base path (optional)") },
                    placeholder = { Text(if (protocol == Protocol.SMB) "media" else if (protocol.isWebDav) "remote.php/dav/files/me" else "pub") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(guest, { guest = it; result = null })
                    Text(if (protocol == Protocol.SMB) "  Guest" else "  Anonymous", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        user, { user = it; result = null }, Modifier.weight(1f), enabled = !guest, singleLine = true, label = { Text("User name") },
                    )
                    OutlinedTextField(
                        pass, { pass = it; result = null }, Modifier.weight(1f), enabled = !guest, singleLine = true,
                        label = { Text(if (initial?.user?.isNotEmpty() == true && initial?.remember == true) "Password (saved)" else "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                if (protocol == Protocol.SMB) OutlinedTextField(
                    domain, { domain = it }, Modifier.fillMaxWidth(), enabled = !guest, singleLine = true, label = { Text("Domain / workgroup (optional)") },
                )
                OutlinedTextField(
                    alias, { alias = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Sidebar name (optional)") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberPw, { rememberPw = it }, enabled = !guest)
                    Text("Remember credentials (stored encrypted)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        {
                            testJob?.cancel()
                            testJob = scope.launch {
                                testing = true
                                result = null
                                val r = vm.testConnection(config())
                                result = r.fold({ "Connected ($it ms)" to true }, { (it.message ?: "Connection failed") to false })
                                testing = false
                            }
                        },
                        enabled = canGo && !testing,
                    ) { Text("Test connection") }
                    when {
                        testing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        result != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            val (msg, ok) = result!!
                            val okColor = com.droid.wayfinder.data.FileKind.Image.tint()
                            Icon(
                                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp),
                                tint = if (ok) okColor else cs.error,
                            )
                            Text(msg, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium, color = if (ok) cs.onSurface else cs.error)
                        }
                    }
                }
                if (saved.isNotEmpty()) {
                    Text("Saved servers", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        saved.forEach { sv ->
                            AssistChip({ fill(vm.connectConfigFor(sv)) }, label = { Text("${sv.label} (${sv.protocol.label})") })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.connectMount(config()) }, enabled = canGo) { Text("Connect & Mount") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
