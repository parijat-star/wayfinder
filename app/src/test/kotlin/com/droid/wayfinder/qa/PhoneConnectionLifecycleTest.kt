// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.PhoneDetector
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.ui.Mount
import com.droid.wayfinder.ui.UiEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneConnectionLifecycleTest {
    private val harnesses = mutableListOf<VmHarness>()

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Settings(app).safMounts = emptyList()
        harnesses.forEach { it.close() }
    }

    class ToggleablePhoneDocumentsProvider : ContentProvider() {
        val online = AtomicBoolean(false)

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (!online.get()) return null
            val path = uri.path.orEmpty()
            return if (path.contains("/children")) {
                MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                ).apply {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (docId == "root") {
                        addRow(arrayOf<Any?>("root/DCIM", "DCIM", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1_000L))
                    } else if (docId == "root/DCIM") {
                        addRow(arrayOf<Any?>("root/DCIM/IMG_001.jpg", "IMG_001.jpg", "image/jpeg", 4096L, 2_000L))
                    }
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("root")
                val name = docId.substringAfterLast('/').ifEmpty { "Pixel 9" }
                val mime = if (name.endsWith(".jpg")) "image/jpeg" else DocumentsContract.Document.MIME_TYPE_DIR
                MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                ).apply {
                    addRow(arrayOf<Any?>(docId, if (docId == "root") "Pixel 9" else name, mime, 0L, 1_000L))
                }
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun detectPairedPhoneChecksLiveSafProviderReachability() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authority = PhoneDetector.QUICK_ACCESS_AUTHORITY
        val provider = Robolectric.setupContentProvider(ToggleablePhoneDocumentsProvider::class.java, authority)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val safMount = SafMount("ph1", "Connected Phone · Pixel 9", treeUri.toString())
        val mirrorMount = Mount(safMount.path, safMount.label, "Cloud / storage provider", safMount.id, saf = true)

        // 1. Offline provider -> must NOT report stale SAF mount as connected/mounted
        provider.online.set(false)
        assertFalse(PhoneDetector.isSafPhoneMountReachable(context, safMount))
        assertNull(PhoneDetector.detectPairedPhone(context, listOf(mirrorMount), listOf(safMount)))

        // 2. Provider comes online -> reports connected & mounted at saf://ph1
        provider.online.set(true)
        assertTrue(PhoneDetector.isSafPhoneMountReachable(context, safMount))
        val detected = PhoneDetector.detectPairedPhone(context, listOf(mirrorMount), listOf(safMount))
        assertNotNull(detected)
        assertTrue(detected!!.isMounted)
        assertEquals("saf://ph1", detected.path)
        assertEquals("Pixel 9", detected.name)

        // 3. Provider goes offline again -> transitions back to null
        provider.online.set(false)
        assertNull(PhoneDetector.detectPairedPhone(context, listOf(mirrorMount), listOf(safMount)))
    }

    @Test
    fun crossDeviceConnectAndDisconnectStagedRetryTransitionsTabsCleanly() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val authority = PhoneDetector.QUICK_ACCESS_AUTHORITY
        val provider = Robolectric.setupContentProvider(ToggleablePhoneDocumentsProvider::class.java, authority)
        provider.online.set(false)

        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val phoneMount = SafMount("phlive", "Connected Phone · Pixel 9", treeUri.toString())
        Settings(app).safMounts = listOf(phoneMount)

        val h = VmHarness().also { harnesses += it }
        h.vm.phoneConnectRetryDelaysMs = longArrayOf(0L, 50L, 150L)
        h.vm.phoneDisconnectRetryDelaysMs = longArrayOf(0L, 40L)

        // Initially offline: not mounted, and offline phone mount does not leak into sidebar Network & cloud mounts
        assertNull(h.s.pairedPhone)
        assertFalse(h.s.mounts.any { it.path == "saf://phlive" })

        // User opens phone:// while phone is connecting
        h.navigate(FsPath.PHONE_ROOT)
        assertEquals(FsPath.PHONE_ROOT, h.s.active.path)

        // Simulate Cross-Device connection where provider becomes online slightly after ACL broadcast
        h.vm.refreshPhoneStatus(stagedRetry = true)
        Thread {
            Thread.sleep(20L)
            provider.online.set(true)
        }.start()

        h.until(what = "staged retry auto-mounts phone and navigates tab") {
            h.s.pairedPhone?.isMounted == true && h.s.active.path == "saf://phlive"
        }
        h.settle()
        assertEquals(listOf("DCIM"), h.names())

        // Navigate deeper into phone subfolder
        h.navigate("saf://phlive/DCIM")
        assertEquals(listOf("IMG_001.jpg"), h.names())

        // Phone walks out of range / disconnects -> falls back cleanly to phone:// while preserving persisted grant
        provider.online.set(false)
        h.vm.refreshPhoneStatus(disconnectRetry = true)
        h.until(what = "disconnect transitions tab back to phone://") {
            h.s.pairedPhone == null && h.s.active.path == FsPath.PHONE_ROOT
        }
        h.settle()
        assertFalse("Offline phone mount hidden from generic mounts", h.s.mounts.any { it.path == "saf://phlive" })
        assertTrue("Persisted SAF grant kept for future reconnects", Settings(app).safMounts.any { it.id == "phlive" })

        // Phone comes back into range again -> auto-remounts and navigates phone:// tab back to saf://phlive
        provider.online.set(true)
        h.vm.refreshPhoneStatus(stagedRetry = true)
        h.until(what = "reconnect auto-remounts phone") {
            h.s.pairedPhone?.isMounted == true && h.s.active.path == "saf://phlive"
        }
    }

    @Test
    fun bluetoothConnectRuntimePermissionGatesDiscoveryAndEmitsRequestEvent() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertFalse(PhoneDetector.hasBluetoothConnectPermission(app))
        val h = VmHarness().also { harnesses += it }
        assertFalse(h.s.bluetoothPermissionGranted)

        h.vm.requestBluetoothPermission()
        assertTrue(h.events.any { it is UiEvent.RequestBluetoothPermission })

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        h.vm.onBluetoothPermissionResult(true)
        h.until(what = "bluetoothPermissionGranted updated") { h.s.bluetoothPermissionGranted }
        assertTrue(PhoneDetector.hasBluetoothConnectPermission(app))
    }
}
