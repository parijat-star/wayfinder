// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Coverage for the network thumbnails, Drive mount explanation, hidden-path and APK-folder features:
 * real network transfers (embedded FTP server standing in for a NAS), activity intent routing and UI.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class NetworkThumbnailsAndIntentRoutingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val harnesses = mutableListOf<VmHarness>()
    private var server: FtpServer? = null
    private val temps = mutableListOf<File>()

    private fun harness(extras: Map<String, Any?> = emptyMap()) = VmHarness(extras).also { harnesses += it }

    @After fun tearDown() {
        harnesses.forEach { it.close() }
        server?.stop()
        temps.forEach { it.deleteRecursively() }
        MainActivity.hasAccessOverride = null
    }

    /** Starts an embedded FTP server (stand-in for a NAS share) and returns its `ftp://` address. */
    private fun startNas(home: File): String {
        val port = ServerSocket(0).use { it.localPort }
        val sf = FtpServerFactory()
        sf.addListener("default", ListenerFactory().apply { this.port = port; serverAddress = "127.0.0.1" }.createListener())
        val um = PropertiesUserManagerFactory().createUserManager()
        um.save(BaseUser().apply {
            name = "qa"; password = "pw"; homeDirectory = home.path; enabled = true
            authorities = listOf<Authority>(WritePermission(), ConcurrentLoginPermission(20, 20), TransferRatePermission(0, 0))
        })
        sf.userManager = um
        server = sf.createServer().also { it.start() }
        return "ftp://127.0.0.1:$port"
    }

    @Test fun nasImagesAndVideosAreFetchedOnceIntoTheThumbnailCache() {
        val home = Files.createTempDirectory("wf-nas-").toFile().also { temps += it }
        val photoBytes = ByteArray(300_000) { (it % 251).toByte() }
        File(home, "media").mkdirs()
        File(home, "media/photo.jpg").writeBytes(photoBytes)
        val bigVideo = BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES + 3 * 1024 * 1024
        File(home, "media/movie.mp4").outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024) { 7 }
            var left = bigVideo
            while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); out.write(chunk, 0, n); left -= n }
        }
        File(home, "media/readme.txt").writeText("not media")
        val root = startNas(home)

        val h = harness()
        assertTrue(h.vm.openAddress("ftp://qa:pw@${root.removePrefix("ftp://")}/media"))
        h.until(what = "NAS listing") { h.s.active.entries.size == 3 }
        val photo = h.s.active.entries.first { it.name == "photo.jpg" }
        val movie = h.s.active.entries.first { it.name == "movie.mp4" }
        val text = h.s.active.entries.first { it.name == "readme.txt" }
        assertTrue(h.vm.canThumbnail(photo))
        assertTrue(h.vm.canThumbnail(movie))
        assertTrue(!h.vm.canThumbnail(text))

        // Several grid cells asking at once share one download.
        val results = runBlocking { (1..6).map { async { h.vm.thumbnailSource(photo) } }.awaitAll() }
        val staged = results.first()
        assertNotNull(staged)
        assertTrue(results.all { it == staged })
        assertTrue(staged!!.path.startsWith(File(h.app.cacheDir, BrowserViewModel.THUMBS_DIR).path))
        assertArrayEquals(photoBytes, staged.readBytes())

        // Served from cache afterwards, even if the NAS goes away.
        server?.stop()
        assertEquals(staged, runBlocking { h.vm.thumbnailSource(photo) })

        // A large video only fetched its leading bytes before the server stopped.
        val stagedVideoName = File(h.app.cacheDir, BrowserViewModel.THUMBS_DIR).listFiles().orEmpty().map { it.name }
        assertTrue(stagedVideoName.none { it.endsWith(".part") })
        assertNull("non-media files never get thumbnails", runBlocking { h.vm.thumbnailSource(text) })
    }

    @Test fun nasVideoThumbnailFetchesOnlyALeadingPrefix() {
        val home = Files.createTempDirectory("wf-nas-").toFile().also { temps += it }
        val size = BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES + 2 * 1024 * 1024
        File(home, "clip.mkv").outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024) { 3 }
            var left = size
            while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); out.write(chunk, 0, n); left -= n }
        }
        val root = startNas(home)
        val h = harness()
        h.vm.openAddress("ftp://qa:pw@${root.removePrefix("ftp://")}/")
        h.until(what = "NAS listing") { h.s.active.entries.any { it.name == "clip.mkv" } }
        val clip = h.s.active.entries.first { it.name == "clip.mkv" }
        val staged = runBlocking { h.vm.thumbnailSource(clip) }
        assertNotNull(staged)
        assertEquals(BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES, staged!!.length())
    }

    @Test fun driveExplanationIsAlwaysShownWhileGenericTipCanBeSilenced() {
        val h = harness()
        h.vm.confirmSafMountTip(dontShowAgain = true, accountEmail = null)
        h.settle()
        // Generic tip now skipped: goes straight to the system picker.
        h.events.clear()
        h.vm.requestMountSaf(null); h.settle()
        assertNull(h.s.dialog)
        assertTrue(h.events.any { it is UiEvent.PickDocumentTree })
        // Google Drive keeps explaining why "My Drive" can't be mounted.
        h.vm.requestMountSaf("alex@example.com"); h.settle()
        assertEquals(Dialog.SafMountTip("alex@example.com"), h.s.dialog)
    }

    @Test fun phoneOptionShowsCompanionPrivilegeNote() {
        val h = harness()
        h.navigate(FsPath.PHONE_ROOT)
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        rule.waitForIdle()
        rule.onNodeWithText(
            "Please note: Wayfinder lacks necessary system privileges to directly mount companion phones.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test fun hiddenOnlyFolderShowsExplanationAndRevealButton() {
        val h = harness()
        h.file("dots/.env", "x"); h.file("dots/.git/config", "x")
        h.navigate(h.pathOf("dots"))
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        rule.waitForIdle()
        rule.onNodeWithText("Only hidden files here").assertIsDisplayed()
        rule.onNodeWithText("This folder contains 2 hidden items.").assertIsDisplayed()
        rule.onNodeWithText("Show hidden files").performClick()
        rule.waitForIdle()
        assertTrue(h.s.showHidden)
        assertEquals(listOf(".env", ".git").sorted(), h.names().sorted())
    }

    @Test fun apkOpenedFromStarredRevealsItsFolder() {
        val h = harness()
        val apk = h.file("builds/out/app.apk", "apk")
        SharedUi.updateMarks { Marks(starred = setOf(apk.path)) }
        h.navigate(FsPath.STARRED_ROOT)
        val entry = h.s.active.entries.single { it.path == apk.path }
        h.vm.open(entry); h.settle()
        assertEquals(apk.parent, h.s.active.path)
        assertEquals(setOf(apk.path), h.s.active.selection)
        assertTrue("never hands the APK to an installer", h.events.none { it is UiEvent.OpenFile })
    }

    class DataColumnProvider : ContentProvider() {
        var data: String? = null
        var name: String? = null
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA, OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(data, name)) }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test fun contentUrisFromOtherAppsResolveToSharedStorageFiles() {
        val h = harness()
        val apk = h.file("Download/shared.apk", "apk")
        val provider = Robolectric.setupContentProvider(DataColumnProvider::class.java, "com.example.share")
        // MediaStore-style _data column.
        provider.data = apk.path
        val viaData = Uri.parse("content://com.example.share/item/42")
        assertEquals(apk.path, MainActivity.resolveViewTargetPath(h.app, viaData, MainActivity.APK_MIME, h.root.path))
        // Only a display name: falls back to the Download folder.
        provider.data = null; provider.name = "shared.apk"
        assertEquals(apk.path, MainActivity.resolveViewTargetPath(h.app, viaData, MainActivity.APK_MIME, h.root.path))
        // Traversal or private-app paths are never accepted.
        provider.data = h.root.path + "/../../data/data/com.droid.wayfinder/x.apk"; provider.name = null
        assertNull(MainActivity.localPathForContentUri(h.app, viaData, h.root.path))
        provider.name = "../secret.apk"
        assertNull(MainActivity.localPathForContentUri(h.app, viaData, h.root.path))
    }
}
