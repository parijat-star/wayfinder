// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.theme.WayfinderTheme

class MainActivity : ComponentActivity() {
    private var vmOrNull: BrowserViewModel? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.droid.wayfinder.ui.PointerSource.update(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        persistIncomingTreeGrant(intent)
        prepareIntentExtras()
        // Video frame decoding for thumbnails and Quick Look.
        coil3.SingletonImageLoader.setSafe { ctx ->
            coil3.ImageLoader.Builder(ctx).components { add(coil3.video.VideoFrameDecoder.Factory()) }.build()
        }
        enableEdgeToEdge()
        SharedUi.init(this)
        setContent {
            // Light / Dark / System setting (shared by all windows) drives both the theme and the caption bar.
            val mode by SharedUi.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
            // Draw our tab strip into the desktop-mode caption bar instead of a separate system title bar.
            SideEffect {
                window.insetsController?.setSystemBarsAppearance(
                    if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
                if (Build.VERSION.SDK_INT >= 35) {
                    val c = window.insetsController
                    c?.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    )
                    c?.setSystemBarsAppearance(
                        if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
                    )
                }
            }
            WayfinderTheme(dark) {
                var granted by remember { mutableStateOf(hasAccess()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    granted = hasAccess()
                    vmOrNull?.refreshAll()
                }
                if (granted) {
                    val vm: BrowserViewModel by viewModels()
                    vmOrNull = vm
                    AppRoot(vm)
                } else PermissionScreen(onGrant = ::requestAccess)
            }
        }
    }

    /**
     * The view model reads its options from the intent extras (SavedStateHandle), so picker requests
     * (GET_CONTENT / OPEN_DOCUMENT / PICK) and VIEW_DOWNLOADS are translated into those extras here.
     */
    private fun prepareIntentExtras() {
        // Exported activity: other apps may send anything. Internal extras need our token (see IntentGuard).
        val i = IntentGuard.sanitize(this, intent ?: return).also { if (it !== intent) intent = it }
        try {
            when (i.action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_PICK -> {
                    val mimes = (runCatching { i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES) }.getOrNull())
                        ?.filter { !it.isNullOrBlank() }?.take(64)?.map { it.take(255) }
                        ?.takeIf { it.isNotEmpty() } ?: listOfNotNull(i.type)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER, true)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MODE, com.droid.wayfinder.data.PickerMode.OpenFile.name)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MULTIPLE, runCatching { i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) }.getOrDefault(false))
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MIME, mimes.toTypedArray())
                }
                Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                    i.putExtra(BrowserViewModel.EXTRA_PICKER, true)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MODE, com.droid.wayfinder.data.PickerMode.OpenTree.name)
                }
                Intent.ACTION_CREATE_DOCUMENT -> {
                    val title = runCatching { i.getStringExtra(Intent.EXTRA_TITLE) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.take(255) ?: "Untitled"
                    val mimes = listOfNotNull(i.type?.takeIf { it.isNotBlank() })
                    i.putExtra(BrowserViewModel.EXTRA_PICKER, true)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MODE, com.droid.wayfinder.data.PickerMode.CreateDocument.name)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_TITLE, title)
                    i.putExtra(BrowserViewModel.EXTRA_PICKER_MIME, mimes.toTypedArray())
                }
                "android.provider.action.BROWSE", "android.provider.action.MANAGE_DOCUMENT" -> {
                    val targetPath = com.droid.wayfinder.data.SystemDocumentsBridge.toWayfinderPath(i.data)
                    if (targetPath != null) {
                        i.putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
                        i.putExtra(BrowserViewModel.EXTRA_PATH, targetPath)
                    }
                }
                android.app.DownloadManager.ACTION_VIEW_DOWNLOADS -> {
                    // Opened like an extra window so the main window's saved tabs stay untouched.
                    i.putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
                    i.putExtra(BrowserViewModel.EXTRA_PATH, java.io.File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).path)
                }
            }
        } catch (_: Exception) {
            intent = Intent(i.action)
        }
    }

    private fun hasAccess(): Boolean =
        hasAccessOverride?.invoke() ?: runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    companion object {
        internal var hasAccessOverride: (() -> Boolean)? = null
    }

    private fun requestAccess() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val treeUri = persistIncomingTreeGrant(intent)
        if (treeUri != null) {
            vmOrNull?.mountSaf(treeUri)
        } else {
            vmOrNull?.refreshAll()
        }
    }

    private fun persistIncomingTreeGrant(incoming: Intent?): Uri? {
        if (incoming == null) return null
        val uris = buildList {
            incoming.data?.let { add(it) }
            incoming.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { add(it) }
                }
            }
        }
        if (uris.isEmpty()) return null
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var mountedTreeUri: Uri? = null
        for (rawUri in uris) {
            val auth = rawUri.authority.orEmpty()
            val cleanUri = if (auth.contains('@')) {
                rawUri.buildUpon().authority(auth.substringAfter('@')).build()
            } else {
                rawUri
            }
            val persisted = listOf(cleanUri, rawUri).distinct().any { candidate ->
                runCatching {
                    contentResolver.takePersistableUriPermission(candidate, rw)
                    true
                }.getOrElse {
                    runCatching {
                        contentResolver.takePersistableUriPermission(candidate, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        true
                    }.getOrDefault(false)
                }
            }
            val isTree = runCatching { android.provider.DocumentsContract.isTreeUri(cleanUri) }.getOrDefault(false)
            if (persisted && isTree && !com.droid.wayfinder.data.PhoneDetector.isPhoneAuthority(cleanUri.authority)) {
                mountedTreeUri = cleanUri
            }
        }
        return mountedTreeUri
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        vmOrNull?.refreshAll()
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(32.dp),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "Allow access to all files",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Wayfinder needs “All files access” to browse, copy and organise folders on this device. " +
                "Nothing leaves your device except files you explicitly copy to a network share.",
            Modifier.padding(top = 8.dp, bottom = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onGrant) { Text("Open settings") }
    }
}
