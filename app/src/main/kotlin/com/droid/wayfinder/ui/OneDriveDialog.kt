// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droid.wayfinder.R
import com.droid.wayfinder.data.OneDriveAuthManager

private const val ENTRA_APP_REGISTRATIONS_URL =
    "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectOneDriveDialog(
    vm: BrowserViewModel,
    s: AppState,
    reauthAccountId: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val reauthAcc = remember(reauthAccountId, s.oneDriveAccounts) {
        reauthAccountId?.let { id -> s.oneDriveAccounts.firstOrNull { it.accountId == id } }
    }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = Browser PKCE, 1 = Device Code
    var clientId by rememberSaveable(s.oneDriveDefaultClientId) {
        mutableStateOf(reauthAcc?.clientId?.ifBlank { s.oneDriveDefaultClientId } ?: s.oneDriveDefaultClientId)
    }
    var aliasLabel by rememberSaveable(reauthAccountId) {
        mutableStateOf(reauthAcc?.aliasLabel.orEmpty())
    }
    var advancedExpanded by rememberSaveable {
        mutableStateOf(s.oneDriveDefaultClientId.isBlank())
    }
    val validClientId = OneDriveAuthManager.isValidClientId(clientId)
    val showSetupGuide = s.oneDriveDefaultClientId.isBlank() || advancedExpanded

    AlertDialog(
        onDismissRequest = vm::dismissOneDriveDialog,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OneDriveIcon(Modifier.size(32.dp))
                Column {
                    Text(
                        text = if (reauthAcc != null) {
                            "Sign in to ${reauthAcc.email}"
                        } else {
                            stringResource(R.string.onedrive_dialog_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.onedrive_dialog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { if (!s.oneDriveSigningIn) mode = 0 },
                        leadingIcon = { Icon(Icons.Outlined.Web, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.onedrive_tab_browser)) },
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { if (!s.oneDriveSigningIn) mode = 1 },
                        leadingIcon = { Icon(Icons.Outlined.QrCode2, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.onedrive_tab_device_code)) },
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.surfaceContainerLow,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                            tint = cs.primary,
                        )
                        Text(
                            text = if (mode == 0) {
                                "Signs in directly with Microsoft via OAuth 2.0 + PKCE in your browser. Mounts your full top-level OneDrive root, and encrypts your refresh token in Android hardware Keystore."
                            } else {
                                "Displays an 8-character Microsoft code you can approve at microsoft.com/devicelogin on your phone or laptop — ideal for hardware keys, passkeys, or enterprise Conditional Access."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }

                if (s.oneDriveDefaultClientId.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { advancedExpanded = !advancedExpanded }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (advancedExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Text(
                            text = "Advanced: Custom Entra Application (Client) ID",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                if (showSetupGuide) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cs.secondaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, cs.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "1-time Entra Client ID setup (free, 2 min)",
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.onSurface,
                            )
                            Text(
                                text = "1. Open Entra App Registrations → New registration → select Personal & organizational accounts.\n" +
                                    "2. Under Authentication → Mobile & desktop applications, add redirect URI http://localhost and enable Allow public client flows (for Device Code).\n" +
                                    "3. Paste the Application (client) ID UUID below — Wayfinder remembers it for future accounts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { vm.openExternalUrl(ENTRA_APP_REGISTRATIONS_URL) },
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Text("Open Entra Portal", Modifier.padding(start = 6.dp))
                                }
                                OutlinedButton(
                                    onClick = { vm.copyValue("http://localhost", "Redirect URI") },
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                    Text("Copy http://localhost", Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        enabled = !s.oneDriveSigningIn,
                        singleLine = true,
                        isError = clientId.isNotBlank() && !validClientId,
                        label = { Text(stringResource(R.string.onedrive_client_id_label)) },
                        placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                        supportingText = if (clientId.isNotBlank() && !validClientId) {
                            { Text("Must be a 36-character Microsoft Entra Application (Client) ID UUID") }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { vm.setTextFocus(it.isFocused) },
                    )
                }

                OutlinedTextField(
                    value = aliasLabel,
                    onValueChange = { aliasLabel = it },
                    enabled = !s.oneDriveSigningIn,
                    singleLine = true,
                    label = { Text(stringResource(R.string.onedrive_label_optional)) },
                    placeholder = { Text("OneDrive · Personal") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { vm.setTextFocus(it.isFocused) },
                )

                val session = s.oneDriveDeviceSession
                if (s.oneDriveSigningIn) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cs.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (session != null) {
                                Text(
                                    text = "Enter this code at ${session.verificationUri.removePrefix("https://")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                                Text(
                                    text = session.userCode,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp,
                                    letterSpacing = 3.sp,
                                    color = cs.primary,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { vm.copyValue(session.userCode, "Sign-in code") },
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                        Text("Copy code", Modifier.padding(start = 6.dp))
                                    }
                                    Button(
                                        onClick = { vm.openExternalUrl(session.verificationUri) },
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                        Text("Open sign-in page", Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = if (mode == 0) {
                                        "Waiting for Microsoft sign-in in your browser…"
                                    } else if (session != null) {
                                        "Waiting for approval…"
                                    } else {
                                        "Requesting sign-in code from Microsoft…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                )
                            }
                            TextButton(onClick = vm::cancelOneDriveSignIn) {
                                Text("Cancel sign-in")
                            }
                        }
                    }
                }

                s.oneDriveAuthError?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = cs.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mode == 0) {
                        vm.startOneDriveBrowserSignIn(clientId, aliasLabel)
                    } else {
                        vm.startOneDriveDeviceCodeSignIn(clientId, aliasLabel)
                    }
                },
                enabled = !s.oneDriveSigningIn && validClientId,
            ) {
                Text(
                    if (mode == 0) {
                        stringResource(R.string.onedrive_action_sign_in_browser)
                    } else {
                        stringResource(R.string.onedrive_action_start_device_code)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissOneDriveDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
