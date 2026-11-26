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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.R
import com.droid.wayfinder.data.DropboxAuthManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectDropboxDialog(
    vm: BrowserViewModel,
    s: AppState,
    reauthAccountId: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val reauthAcc = remember(reauthAccountId, s.dropboxAccounts) {
        reauthAccountId?.let { id -> s.dropboxAccounts.firstOrNull { it.accountId == id } }
    }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = Browser Redirect PKCE, 1 = Code Copy/Paste PKCE
    var appKey by rememberSaveable(reauthAccountId, s.dropboxDefaultAppKey) {
        mutableStateOf(reauthAcc?.appKey?.ifBlank { s.dropboxDefaultAppKey } ?: s.dropboxDefaultAppKey)
    }
    var aliasLabel by rememberSaveable(reauthAccountId) {
        mutableStateOf(reauthAcc?.aliasLabel.orEmpty())
    }
    var pastedCode by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable {
        mutableStateOf(s.dropboxDefaultAppKey.isBlank())
    }
    val validAppKey = DropboxAuthManager.isValidAppKey(appKey)
    val validCode = DropboxAuthManager.isValidAuthorizationCode(pastedCode)
    val showSetupGuide = s.dropboxDefaultAppKey.isBlank() || advancedExpanded
    val codeSession = s.dropboxCodeSession

    AlertDialog(
        onDismissRequest = vm::dismissDropboxDialog,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DropboxIcon(Modifier.size(32.dp))
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (reauthAcc != null) {
                                "Sign in to ${reauthAcc.email}"
                            } else {
                                stringResource(R.string.dropbox_dialog_title)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        BetaBadge(stringResource(R.string.badge_beta))
                    }
                    Text(
                        text = stringResource(R.string.dropbox_dialog_subtitle),
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
                        onClick = { if (!s.dropboxSigningIn) mode = 0 },
                        leadingIcon = { Icon(Icons.Outlined.Web, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.dropbox_tab_browser)) },
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { if (!s.dropboxSigningIn) mode = 1 },
                        leadingIcon = { Icon(Icons.Outlined.Key, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.dropbox_tab_code_paste)) },
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (mode == 0) {
                                    "Signs in directly with Dropbox via OAuth 2.0 + PKCE in your browser and returns via ${DropboxAuthManager.REDIRECT_URI}. Refresh tokens are encrypted in Android hardware Keystore."
                                } else {
                                    "Opens Dropbox's no-redirect authorization page so you can approve on this device or another phone/laptop and paste the displayed code — no Redirect URI setup required."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            if (s.dropboxDefaultAppKey.isNotBlank() && appKey.trim() == DropboxAuthManager.BUNDLED_PUBLIC_APP_KEY) {
                                Text(
                                    text = stringResource(R.string.dropbox_beta_cap_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (s.dropboxDefaultAppKey.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { advancedExpanded = !advancedExpanded }
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
                            text = "Advanced: Custom Dropbox App Key",
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
                                text = "1-time Dropbox App Key setup (free, 2 min)",
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.onSurface,
                            )
                            Text(
                                text = "1. Open Dropbox App Console → Create app → Scoped access → Full Dropbox.\n" +
                                    "2. Under Permissions, enable files.metadata.read, files.content.read, files.content.write, and account_info.read (click Submit).\n" +
                                    "3. Under Settings → Redirect URIs, add ${DropboxAuthManager.REDIRECT_URI} (optional if using Paste Code mode), then paste your App key below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { vm.openExternalUrl(DropboxAuthManager.APP_CONSOLE_URL) },
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Text("Open App Console", Modifier.padding(start = 6.dp))
                                }
                                OutlinedButton(
                                    onClick = { vm.copyValue(DropboxAuthManager.REDIRECT_URI, "Redirect URI") },
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                    Text("Copy Redirect URI", Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = appKey,
                        onValueChange = { appKey = it },
                        enabled = !s.dropboxSigningIn,
                        singleLine = true,
                        isError = appKey.isNotBlank() && !validAppKey,
                        label = { Text(stringResource(R.string.dropbox_app_key_label)) },
                        placeholder = { Text("a1b2c3d4e5f6g7h") },
                        supportingText = if (appKey.isNotBlank() && !validAppKey) {
                            { Text("Must be an 8–64 character alphanumeric Dropbox App key") }
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
                    enabled = !s.dropboxSigningIn,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dropbox_label_optional)) },
                    placeholder = { Text("Dropbox · Personal") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { vm.setTextFocus(it.isFocused) },
                )

                if (mode == 1 && codeSession != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cs.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Approve access in your browser, then paste the authorization code shown by Dropbox:",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { vm.openExternalUrl(codeSession.authorizeUrl) },
                                    enabled = !s.dropboxSigningIn,
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Text("Reopen Sign-In Page", Modifier.padding(start = 6.dp))
                                }
                                OutlinedButton(
                                    onClick = { vm.copyValue(codeSession.authorizeUrl, "Sign-in URL") },
                                    enabled = !s.dropboxSigningIn,
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                    Text("Copy Sign-In Link", Modifier.padding(start = 6.dp))
                                }
                            }
                            OutlinedTextField(
                                value = pastedCode,
                                onValueChange = { pastedCode = it },
                                enabled = !s.dropboxSigningIn,
                                singleLine = true,
                                label = { Text(stringResource(R.string.dropbox_auth_code_label)) },
                                placeholder = { Text("Paste authorization code here") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { vm.setTextFocus(it.isFocused) },
                            )
                        }
                    }
                }

                if (s.dropboxSigningIn) {
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = if (mode == 0) {
                                        "Waiting for Dropbox sign-in in your browser…"
                                    } else {
                                        "Verifying authorization code with Dropbox…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                )
                            }
                            TextButton(onClick = vm::cancelDropboxSignIn) {
                                Text("Cancel sign-in")
                            }
                        }
                    }
                }

                s.dropboxAuthError?.let { err ->
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
                        vm.startDropboxBrowserSignIn(appKey, aliasLabel)
                    } else if (codeSession == null || codeSession.appKey != appKey.trim()) {
                        pastedCode = ""
                        vm.startDropboxCodePasteSession(appKey)
                    } else {
                        vm.completeDropboxCodePasteSignIn(pastedCode, aliasLabel)
                    }
                },
                enabled = !s.dropboxSigningIn && validAppKey && (mode == 0 || codeSession == null || codeSession.appKey != appKey.trim() || validCode),
            ) {
                Text(
                    when {
                        mode == 0 -> stringResource(R.string.dropbox_action_sign_in_browser)
                        codeSession == null || codeSession.appKey != appKey.trim() -> stringResource(R.string.dropbox_action_get_code)
                        else -> stringResource(R.string.dropbox_action_complete_code)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissDropboxDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
