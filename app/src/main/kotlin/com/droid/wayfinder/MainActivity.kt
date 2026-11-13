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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
        if (!prepareIntentExtras(isInitialLaunch = savedInstanceState == null)) {
            finish()
            return
        }
        // Video frame decoding for thumbnails and Quick Look.
        coil3.SingletonImageLoader.setSafe { ctx ->
            coil3.ImageLoader.Builder(ctx).components { add(coil3.video.VideoFrameDecoder.Factory()) }.build()
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        SharedUi.init(this)
        setContent {
            // Light / Dark / System setting (shared by all windows) drives both the theme and the caption bar.
            val mode by SharedUi.theme.collectAsState()
            val hideStatusBar by SharedUi.hideStatusBar.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
            // Draw our tab strip into the desktop-mode caption bar instead of a separate system title bar.
            SideEffect {
                applyStatusBarVisibility(hideStatusBar)
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
                    applyStatusBarVisibility(SharedUi.hideStatusBar.value)
                    vmOrNull?.refreshAll()
                }
                if (granted) {
                    val vm: BrowserViewModel by viewModels()
                    vmOrNull = vm
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (hasAccessOverride == null) {
                            vm.maybeShowDefaultHandlerPrompt()
                        }
                    }
                    AppRoot(vm)
                } else PermissionScreen(onGrant = ::requestAccess)
            }
        }
    }

    /**
     * The view model reads its options from the intent extras (SavedStateHandle), so picker requests
     * (GET_CONTENT / OPEN_DOCUMENT / PICK), VIEW_DOWNLOADS, and ACTION_VIEW are translated into those extras here.
     */
    private fun prepareIntentExtras(isInitialLaunch: Boolean = true): Boolean {
        // Exported activity: other apps may send anything. Internal extras need our token (see IntentGuard).
        val i = IntentGuard.sanitize(this, intent ?: return true).also { if (it !== intent) intent = it }
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
                "android.provider.action.BROWSE", "android.provider.action.MANAGE_DOCUMENT",
                Intent.ACTION_VIEW, android.app.DownloadManager.ACTION_VIEW_DOWNLOADS -> {
                    // Opened like an extra window so the main window's saved tabs stay untouched.
                    // File targets (e.g. an .apk) open their enclosing folder with the file selected (see BrowserViewModel).
                    val targetPath = externalTargetPath(i)
                    if (targetPath != null) {
                        i.putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
                        i.putExtra(BrowserViewModel.EXTRA_PATH, targetPath)
                    } else if (isInitialLaunch && isUnsupportedPlainGzipViewIntent(this, i)) {
                        showUnsupportedPlainGzipToast(this)
                        return false
                    }
                }
                Intent.ACTION_MAIN -> {
                    if ((i.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0) {
                        i.putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
                    }
                }
            }
        } catch (_: Exception) {
            intent = Intent(i.action)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyStatusBarVisibility(SharedUi.hideStatusBar.value)
        }
    }

    internal fun applyStatusBarVisibility(hide: Boolean) {
        val hasDesktopCaption = window.decorView.rootWindowInsets
            ?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.captionBar())
            ?.top?.let { it > 0 } == true
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hide && !hasDesktopCaption) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun hasAccess(): Boolean =
        hasAccessOverride?.invoke() ?: runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Location requested by an external "show/open this" intent, or null if [i] isn't one. */
    private fun externalTargetPath(i: Intent): String? = externalTargetPath(this, i)

    companion object {
        internal var hasAccessOverride: (() -> Boolean)? = null

        private val ARCHIVE_MIMES = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/x-gtar",
            "application/x-gtar-compressed",
        )

        internal fun isSupportedArchiveName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar")
        }

        internal const val APK_MIME = "application/vnd.android.package-archive"

        internal fun isApkName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm")
        }

        /**
         * Resolves an incoming [Intent.ACTION_VIEW] / BROWSE data URI into a navigable Wayfinder path:
         * a folder, an inline archive root `path!`, or a file (e.g. an .apk). File paths are opened by
         * [BrowserViewModel] as their enclosing folder with the file selected.
         */
        internal fun resolveViewTargetPath(
            context: android.content.Context,
            uri: Uri?,
            mimeType: String? = null,
            primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }.getOrDefault("/storage/emulated/0"),
        ): String? {
            if (uri == null) return null
            val rawTarget = when (uri.scheme?.lowercase()) {
                "file" -> uri.path?.takeIf { it.isNotBlank() && !it.contains('\u0000') }
                "content" -> com.droid.wayfinder.data.SystemDocumentsBridge.toWayfinderPath(uri, primaryStorageRoot)
                    ?: localPathForContentUri(context, uri, primaryStorageRoot)
                else -> null
            }
            // Providers that mirror shared storage with relative-path document IDs (e.g. Antigravity's
            // `doc://…antigravity.documents/antigravity/scratch/…/apk/debug`) can't be listed without a grant:
            // open the same folder (or file) on shared storage instead.
            val mappedTarget = rawTarget?.takeIf { com.droid.wayfinder.data.FsPath.isDoc(it) }?.let { doc ->
                val docId = doc.removePrefix("doc://").substringAfter('/', "")
                com.droid.wayfinder.data.SystemDocumentsBridge.localPathForRelativeDocId(docId, primaryStorageRoot)
            } ?: rawTarget
            return resolveTarget(context, mappedTarget, uri, mimeType)?.takeIf { !it.contains('\u0000') }
        }

        /**
         * Routes a "show/open this location" intent (folder, file, .apk) delivered to an already-running window.
         * Returns true when [vm] was navigated.
         */
        internal fun externalTargetPath(context: android.content.Context, i: Intent): String? = when (i.action) {
            "android.provider.action.BROWSE", "android.provider.action.MANAGE_DOCUMENT", Intent.ACTION_VIEW ->
                resolveViewTargetPath(context, i.data, i.type)
            android.app.DownloadManager.ACTION_VIEW_DOWNLOADS ->
                java.io.File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).path
            else -> null
        }

        internal fun routeNewIntent(context: android.content.Context, intent: Intent, vm: BrowserViewModel?): Boolean {
            val i = runCatching { IntentGuard.sanitize(context, intent) }.getOrNull() ?: return false
            val target = runCatching { externalTargetPath(context, i) }.getOrNull()
            if (target == null) {
                if (isUnsupportedPlainGzipViewIntent(context, i)) {
                    showUnsupportedPlainGzipToast(context)
                    return true
                }
                return false
            }
            if (vm == null) return false
            vm.openExternalPath(target)
            return true
        }

        internal fun isUnsupportedPlainGzipViewIntent(context: android.content.Context, intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_VIEW) return false
            val uri = intent.data ?: return false
            if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority == "${context.packageName}.fileprovider") {
                return false
            }
            val segName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
            if (isSupportedArchiveName(segName)) return false
            val candidateName = queryDisplayName(context, uri) ?: segName
            return isPlainGzipName(candidateName)
        }

        private fun showUnsupportedPlainGzipToast(context: android.content.Context) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.toast_plain_gzip_unsupported),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        private fun resolveTarget(context: android.content.Context, rawTarget: String?, uri: Uri, mimeType: String?): String? {
            if (rawTarget != null) {
                if (com.droid.wayfinder.data.FsPath.isLocalFile(rawTarget)) {
                    if (com.droid.wayfinder.data.FileInfo.isSymlink(rawTarget) || isPrivateAppPath(context, rawTarget)) {
                        return null
                    }
                    val f = java.io.File(rawTarget)
                    val apk = isApkName(f.name) || mimeType.equals(APK_MIME, ignoreCase = true)
                    return when {
                        !apk && isSupportedArchiveName(f.name) && !f.isDirectory -> com.droid.wayfinder.data.FsPath.zipRoot(f.path)
                        else -> f.path
                    }
                }
                return rawTarget
            }
            // Fallback for non-DocumentsContract content:// URIs pointing to an archive (e.g. browser downloads / FileProvider).
            if (uri.scheme.equals("content", ignoreCase = true) && uri.authority != "${context.packageName}.fileprovider") {
                val segName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
                val isArchive = isSupportedArchiveName(segName) || mimeType?.lowercase() in ARCHIVE_MIMES
                if (isArchive) {
                    return stageExternalArchiveToCache(context, uri, segName, mimeType)
                }
            }
            return null
        }

        /**
         * Best-effort mapping of a non-DocumentsContract `content://` URI (another app's FileProvider,
         * MediaStore, DownloadsProvider) to the shared-storage file it refers to. Only returns paths
         * of files that actually exist on shared storage.
         */
        internal fun localPathForContentUri(context: android.content.Context, uri: Uri, primaryStorageRoot: String): String? {
            if (uri.authority == "${context.packageName}.fileprovider") return null
            fun ok(p: String?): String? = p?.takeIf {
                (it.startsWith("/storage/") || com.droid.wayfinder.data.FsPath.isWithin(primaryStorageRoot, it)) &&
                    !it.contains('\u0000') && ".." !in it.split('/') && java.io.File(it).exists()
            }?.let { com.droid.wayfinder.data.FsPath.normalize(it) }
            var displayName: String? = null
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val dataIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (dataIdx >= 0 && !c.isNull(dataIdx)) ok(c.getString(dataIdx))?.let { return it }
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                    }
                }
            }
            // FileProvider-style URIs: content://<auth>/<root-name>/<path relative to shared storage>
            val segs = uri.pathSegments.orEmpty()
            val decoded = uri.path.orEmpty()
            ok(decoded.substringAfter("/storage/", "").takeIf { it.isNotEmpty() }?.let { "/storage/$it" })?.let { return it }
            if (segs.size >= 2) ok(primaryStorageRoot + "/" + segs.drop(1).joinToString("/"))?.let { return it }
            // Downloads: fall back to the Download folder by display name.
            val name = displayName?.takeIf { !it.contains('/') && it.isNotBlank() }
            if (name != null) ok("$primaryStorageRoot/${Environment.DIRECTORY_DOWNLOADS}/$name")?.let { return it }
            return null
        }

        private fun isPrivateAppPath(context: android.content.Context, path: String): Boolean {
            val rawData = context.applicationInfo?.dataDir ?: return false
            val canonical = runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
            val data = runCatching { java.io.File(rawData).canonicalPath }.getOrDefault(rawData)
            return com.droid.wayfinder.data.FsPath.isWithin(data, canonical) ||
                com.droid.wayfinder.data.FsPath.isWithin(rawData, path)
        }

        private fun isPlainGzipName(name: String): Boolean {
            val lower = name.trim().lowercase()
            return lower.endsWith(".gz") && !lower.endsWith(".tar.gz")
        }

        private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx)?.trim()?.takeIf { it.isNotEmpty() } else null
                } else {
                    null
                }
            }
        }.getOrNull()

        private fun stageExternalArchiveToCache(
            context: android.content.Context,
            uri: Uri,
            fallbackSeg: String,
            mimeType: String?,
        ): String? = runCatching {
            val defaultExt = when (mimeType?.lowercase()) {
                "application/x-gtar-compressed" -> ".tgz"
                "application/gzip", "application/x-gzip", "application/x-gtar" -> ".tar.gz"
                "application/x-tar" -> ".tar"
                else -> ".zip"
            }
            val fallbackArchiveName = "archive$defaultExt"
            val rawName = if (isSupportedArchiveName(fallbackSeg)) {
                fallbackSeg
            } else {
                val displayName = queryDisplayName(context, uri)
                if (displayName != null) {
                    if (isPlainGzipName(displayName)) return null
                    displayName.takeIf { isSupportedArchiveName(it) } ?: fallbackArchiveName
                } else {
                    if (isPlainGzipName(fallbackSeg)) return null
                    fallbackArchiveName
                }
            }
            val safeName = com.droid.wayfinder.data.FileInfo.sanitizeFileName(rawName, fallbackArchiveName)
                .let { if (isSupportedArchiveName(it)) it else fallbackArchiveName }
            val dir = java.io.File(context.cacheDir, "view-archive").apply { mkdirs() }
            val dstFile = java.io.File(dir, safeName)
            val maxBytes = 512L * 1024 * 1024 // 512 MB cap for synchronous staging
            context.contentResolver.openInputStream(uri)?.use { inp ->
                dstFile.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("Archive too large to stage")
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            com.droid.wayfinder.data.FsPath.zipRoot(dstFile.path)
        }.getOrNull()
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
            return
        }
        // Already running: a "show/open this location" request (folder, file, .apk) navigates this window.
        if (!routeNewIntent(this, intent, vmOrNull)) vmOrNull?.refreshAll()
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
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
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
