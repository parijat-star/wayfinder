// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.R
import com.droid.wayfinder.data.CacheJanitor
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SystemDocumentsBridge
import com.droid.wayfinder.data.ZipFileSystem
import com.droid.wayfinder.ui.BrowserViewModel
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opening paths that go through hidden folders, opening .apk files (their enclosing folder opens with the
 * APK selected), and thumbnails for network/cloud images.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenPathApkAndThumbnailTest {
    private val harnesses = mutableListOf<VmHarness>()
    private fun harness(extras: Map<String, Any?> = emptyMap()) = VmHarness(extras).also { harnesses += it }

    @After fun tearDown() { harnesses.forEach { it.close() } }

    @Test fun navigatingToApkOpensEnclosingFolderWithApkSelected() {
        val h = harness()
        h.file("Download/app-release.apk", "apk")
        h.file("Download/notes.txt")
        h.navigate(h.pathOf("Download/app-release.apk"))
        assertEquals(h.pathOf("Download"), h.s.active.path)
        assertNull(h.s.active.error)
        assertEquals(setOf(h.pathOf("Download/app-release.apk")), h.s.active.selection)
        assertEquals(h.pathOf("Download/app-release.apk"), h.s.active.focused)
    }

    @Test fun navigatingToAnyRegularFileOpensItsFolder() {
        val h = harness()
        h.file("docs/report.pdf", "pdf")
        h.navigate(h.pathOf("docs/report.pdf"))
        assertEquals(h.pathOf("docs"), h.s.active.path)
        assertEquals(setOf(h.pathOf("docs/report.pdf")), h.s.active.selection)
    }

    @Test fun apkInsideHiddenFolderOpensAndShowsHiddenFiles() {
        val h = harness()
        assertFalse(h.s.showHidden)
        h.file(".gemini/antigravity/scratch/build/app-debug.apk", "apk")
        h.navigate(h.pathOf(".gemini/antigravity/scratch/build/app-debug.apk"))
        assertEquals(h.pathOf(".gemini/antigravity/scratch/build"), h.s.active.path)
        assertTrue("Hidden files are shown when the requested path goes through a hidden folder", h.s.showHidden)
        assertTrue(h.names().contains("app-debug.apk"))
        assertEquals(setOf(h.pathOf(".gemini/antigravity/scratch/build/app-debug.apk")), h.s.active.selection)
    }

    @Test fun openingHiddenFileSelectsItAndHiddenFolderShowsItsContents() {
        val h = harness()
        h.file("cfg/.bashrc", "x")
        h.file("cfg/visible.txt", "x")
        h.navigate(h.pathOf("cfg/.bashrc"))
        assertEquals(h.pathOf("cfg"), h.s.active.path)
        assertTrue(h.s.showHidden)
        assertTrue(h.names().contains(".bashrc"))
        assertEquals(setOf(h.pathOf("cfg/.bashrc")), h.s.active.selection)
    }

    @Test fun startPathExtraWithApkInHiddenFolderOpensEnclosingFolder() {
        val root = android.os.Environment.getExternalStorageDirectory()
        val apk = File(root, ".hidden/out/tool.apk").apply { parentFile?.mkdirs(); writeText("apk") }
        val h = harness(mapOf(BrowserViewModel.EXTRA_NEW_WINDOW to true, BrowserViewModel.EXTRA_PATH to apk.path))
        h.settle()
        assertEquals(apk.parent, h.s.active.path)
        assertTrue(h.s.showHidden)
        assertEquals(setOf(apk.path), h.s.active.selection)
    }

    @Test fun hiddenOnlyFolderOffersToShowHiddenFiles() {
        val h = harness()
        h.file("onlyhidden/.a", "x")
        h.vm.navigate(h.pathOf("onlyhidden")); h.settle()
        // A plain (non-hidden) path doesn't flip the preference by itself...
        assertFalse(h.s.showHidden)
        assertTrue(h.names().isEmpty())
        assertEquals(1, h.s.active.entries.count { it.isHidden })
        // ...and toggling reveals the hidden entries.
        h.vm.toggleHidden()
        assertTrue(h.names().contains(".a"))
    }

    @Test fun viewIntentResolvesApkAndRawDownloadsUris() {
        val h = harness()
        val apk = h.file("Download/game.apk", "apk")
        // file:// .apk resolves to the file; the view model then opens the enclosing folder.
        assertEquals(apk.path, MainActivity.resolveViewTargetPath(h.app, Uri.fromFile(apk), MainActivity.APK_MIME, h.root.path))
        // .apk is never treated as a browsable archive.
        assertFalse(MainActivity.resolveViewTargetPath(h.app, Uri.fromFile(apk), null, h.root.path)!!.endsWith("!"))
        // DownloadsProvider raw: document IDs map to local files.
        val rawUri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "raw:${apk.path}")
        assertEquals(apk.path, SystemDocumentsBridge.toWayfinderPath(rawUri, h.root.path))
        assertEquals(apk.path, MainActivity.resolveViewTargetPath(h.app, rawUri, MainActivity.APK_MIME, h.root.path))
        // ExternalStorageProvider URI for an apk inside a hidden folder.
        val hidden = h.file(".gemini/x/app-debug.apk", "apk")
        val extUri = SystemDocumentsBridge.toDocumentsContractUri(hidden.path, tree = false, primaryStorageRoot = h.root.path)
        assertEquals(hidden.path, MainActivity.resolveViewTargetPath(h.app, extUri, null, h.root.path))
    }

    @Test fun antigravityProviderLinksOpenTheSharedStorageFolderWithApkSelected() {
        val h = harness()
        val apk = h.file("Documents/.gemini/antigravity/scratch/wayfinder/app/build/outputs/apk/debug/Wayfinder-debug.apk", "apk")
        val auth = "com.google.android.apps.antigravity.documents"
        val folderUri = DocumentsContract.buildDocumentUri(auth, "antigravity/scratch/wayfinder/app/build/outputs/apk/debug")
        val apkUri = DocumentsContract.buildDocumentUri(auth, "antigravity/scratch/wayfinder/app/build/outputs/apk/debug/Wayfinder-debug.apk")

        // The intent is resolved to the real shared-storage location instead of an unreadable doc:// path.
        assertEquals(apk.parent, MainActivity.resolveViewTargetPath(h.app, folderUri, null, h.root.path))
        assertEquals(apk.path, MainActivity.resolveViewTargetPath(h.app, apkUri, MainActivity.APK_MIME, h.root.path))

        // Opening the APK link shows its folder, with the APK selected and hidden files (.gemini) visible.
        h.vm.openExternalPath("doc://$auth/antigravity/scratch/wayfinder/app/build/outputs/apk/debug/Wayfinder-debug.apk"); h.settle()
        assertEquals(apk.parent, h.s.active.path)
        assertNull(h.s.active.error)
        assertTrue(h.s.showHidden)
        assertEquals(setOf(apk.path), h.s.active.selection)

        // A tab stuck on the doc:// folder link recovers too.
        h.navigate("doc://$auth/antigravity/scratch/wayfinder/app/build/outputs/apk/debug")
        assertEquals(apk.parent, h.s.active.path)
        assertTrue(h.names().contains("Wayfinder-debug.apk"))

        // Opaque IDs (no relative path) are left alone.
        assertNull(SystemDocumentsBridge.localPathForRelativeDocId("acc=1;0", h.root.path))
        assertNull(SystemDocumentsBridge.localPathForRelativeDocId("a/../../etc", h.root.path))
    }

    @Test fun mainActivityDoesNotInterceptApkViewIntents() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (uri in listOf(Uri.parse("content://x/app.apk"), Uri.parse("file:///storage/emulated/0/Download/app.apk"))) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MainActivity.APK_MIME)
            val resolved = ctx.packageManager.queryIntentActivities(intent, 0)
            assertFalse(
                "Wayfinder must not intercept ACTION_VIEW for APK installs ($uri)",
                resolved.any { it.activityInfo?.packageName == ctx.packageName },
            )
        }
    }

    class OpaqueArchiveProvider : ContentProvider() {
        private val items = LinkedHashMap<String, Pair<String?, ByteArray>>()

        fun put(id: String, displayName: String?, bytes: ByteArray) {
            items[id] = displayName to bytes
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val id = uri.lastPathSegment ?: return null
            val (displayName, bytes) = items[id] ?: return null
            if (displayName == null) return null
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(displayName, bytes.size.toLong()))
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val id = uri.lastPathSegment ?: return null
            val (_, bytes) = items[id] ?: return null
            val tmp = File.createTempFile("wf-opaque-archive-", ".bin")
            tmp.deleteOnExit()
            tmp.writeBytes(bytes)
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun buildSampleTarGzBytes(entryName: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val data = content.toByteArray(Charsets.UTF_8)
                val entry = TarArchiveEntry(entryName).apply { size = data.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildPlainGzipBytes(content: String): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz -> gz.write(content.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test fun opaqueContentUriStagesTgzArchiveAndSkipsPlainGzipFile() {
        val h = harness()
        val auth = "com.example.opaque.archive"
        val provider = Robolectric.setupContentProvider(OpaqueArchiveProvider::class.java, auth)
        val tgzBytes = buildSampleTarGzBytes("README.txt", "hello from tgz")
        val plainGzBytes = buildPlainGzipBytes("2026-11-11 INFO started\n")

        val stagedDir = File(h.app.cacheDir, "view-archive")
        stagedDir.deleteRecursively()

        // 1. Opaque URI with MIME application/x-gtar-compressed + DISPLAY_NAME = "release.tgz" stages as release.tgz!
        provider.put("1", "release.tgz", tgzBytes)
        val stagedNamedTgz = MainActivity.resolveViewTargetPath(
            h.app,
            Uri.parse("content://$auth/blob/1"),
            "application/x-gtar-compressed",
            h.root.path,
        )
        assertEquals(FsPath.zipRoot(File(stagedDir, "release.tgz").path), stagedNamedTgz)
        assertEquals(listOf("README.txt"), ZipFileSystem().list(stagedNamedTgz!!).map { it.name })

        // 2. Opaque URI with MIME application/x-gtar-compressed and no DISPLAY_NAME falls back to archive.tgz!
        provider.put("2", null, tgzBytes)
        val stagedAnonTgz = MainActivity.resolveViewTargetPath(
            h.app,
            Uri.parse("content://$auth/blob/2"),
            "application/x-gtar-compressed",
            h.root.path,
        )
        assertEquals(FsPath.zipRoot(File(stagedDir, "archive.tgz").path), stagedAnonTgz)
        assertEquals(listOf("README.txt"), ZipFileSystem().list(stagedAnonTgz!!).map { it.name })

        // 3. Opaque URI with MIME application/gzip + DISPLAY_NAME = "source.tar.gz" stages as source.tar.gz!
        provider.put("3", "source.tar.gz", tgzBytes)
        val stagedTarGz = MainActivity.resolveViewTargetPath(
            h.app,
            Uri.parse("content://$auth/blob/3"),
            "application/gzip",
            h.root.path,
        )
        assertEquals(FsPath.zipRoot(File(stagedDir, "source.tar.gz").path), stagedTarGz)

        // 4. Plain non-tar .gz files (e.g. server.log.gz) must return null rather than fabricating archive.tar.gz
        provider.put("4", "server.log.gz", plainGzBytes)
        assertNull(
            MainActivity.resolveViewTargetPath(
                h.app,
                Uri.parse("content://$auth/blob/4"),
                "application/gzip",
                h.root.path,
            ),
        )
        assertFalse(File(stagedDir, "archive.tar.gz").exists())

        provider.put("5", "ACCESS.LOG.GZ", plainGzBytes)
        assertNull(
            MainActivity.resolveViewTargetPath(
                h.app,
                Uri.parse("content://$auth/blob/5"),
                "application/x-gzip",
                h.root.path,
            ),
        )

        // 5. Cold launch on opaque plain .gz shows a clear toast and finishes without opening a stray window
        ShadowToast.reset()
        val coldPlainGzIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://$auth/blob/4"), "application/gzip")
        Robolectric.buildActivity(MainActivity::class.java, coldPlainGzIntent).create().use { controller ->
            assertTrue(
                "MainActivity should finish immediately when launched solely for an unsupported plain .gz stream",
                controller.get().isFinishing,
            )
            assertEquals(
                h.app.getString(R.string.toast_plain_gzip_unsupported),
                ShadowToast.getTextOfLatestToast(),
            )
        }

        // 6. Running window (routeNewIntent) on opaque plain .gz (including fallback segment name) shows toast and keeps current tab
        ShadowToast.reset()
        provider.put("raw.log.gz", null, plainGzBytes)
        val beforePath = h.s.active.path
        val warmPlainGzIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://$auth/attachments/raw.log.gz"), "application/gzip")
        assertTrue(MainActivity.routeNewIntent(h.app, warmPlainGzIntent, h.vm))
        assertEquals(beforePath, h.s.active.path)
        assertEquals(
            h.app.getString(R.string.toast_plain_gzip_unsupported),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun remoteImagesAreThumbnailEligibleAndLocalOnesUseTheFileItself() {
        val h = harness()
        val img = h.file("pics/a.jpg", "jpeg")
        val local = FileEntry(path = img.path, name = "a.jpg", isDir = false, size = 4, modified = 1L)
        assertTrue(h.vm.canThumbnail(local))
        assertEquals(img, runBlocking { h.vm.thumbnailSource(local) })

        val smb = FileEntry(path = "smb://nas/media/photo.jpg", name = "photo.jpg", isDir = false, size = 1024, modified = 1L)
        val dav = FileEntry(path = "davs://cloud/dav/clip.mp4", name = "clip.mp4", isDir = false, size = 4L shl 30, modified = 1L)
        val huge = FileEntry(path = "smb://nas/media/huge.png", name = "huge.png", isDir = false, size = 500L shl 20, modified = 1L)
        val doc = FileEntry(path = "smb://nas/media/notes.txt", name = "notes.txt", isDir = false, size = 10, modified = 1L)
        assertTrue("SMB/NAS images get thumbnails", h.vm.canThumbnail(smb))
        assertTrue("Network videos get thumbnails (from a prefix of the file)", h.vm.canThumbnail(dav))
        assertFalse("Very large remote images are skipped", h.vm.canThumbnail(huge))
        assertFalse(h.vm.canThumbnail(doc))
    }

    @Test fun thumbsCacheIsPrunedByCacheJanitor() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val thumbs = File(ctx.cacheDir, BrowserViewModel.THUMBS_DIR).apply { mkdirs() }
        val old = File(thumbs, "abc.jpg").apply { writeText("x"); setLastModified(1_000L) }
        CacheJanitor.pruneStaleCaches(ctx.cacheDir, nowMillis = 10L * 24 * 3600 * 1000)
        assertFalse(old.exists())
        assertNotNull(thumbs)
    }
}
