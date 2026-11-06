// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.GooglebookEcosystem
import com.droid.wayfinder.data.PairedPhone
import com.droid.wayfinder.data.PhoneDetector
import com.droid.wayfinder.data.PhoneTransport
import com.droid.wayfinder.data.SafLabels
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.buildSystemDocumentOpenFilePickerIntent
import com.droid.wayfinder.ui.buildSystemPhoneFilesWindowIntent
import com.droid.wayfinder.ui.sanitizeImportedFileName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UserBuildCompatibilityTest {
    private val harnesses = mutableListOf<VmHarness>()

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Settings(app).apply {
            safMounts = emptyList()
            lastQuickAccessRootId = null
        }
        PhoneDetector.clearActiveBluetoothPhones()
        harnesses.forEach { it.close() }
    }

    class FakeImportDocumentProvider : ContentProvider() {
        private val files = LinkedHashMap<String, Pair<String, ByteArray>>()

        fun put(id: String, displayName: String, content: ByteArray) {
            files[id] = displayName to content
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
            val (name, bytes) = files[id] ?: return null
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(name, bytes.size.toLong()))
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val id = uri.lastPathSegment ?: return null
            val (_, bytes) = files[id] ?: return null
            val tmp = File.createTempFile("wf-import-", ".tmp")
            tmp.deleteOnExit()
            tmp.writeBytes(bytes)
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun sourceTreeContainsZeroHiddenApiReflectionOrInvalidGattProfileQueries() {
        val candidates = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
        val srcRoot = candidates.firstOrNull { it.isDirectory }
        assertNotNull("Expected main Kotlin source directory", srcRoot)

        val forbiddenPatterns = listOf(
            Regex("""\.getMethod\s*\("""),
            Regex("""\.getDeclaredMethod\s*\("""),
            Regex("""\.getDeclaredField\s*\("""),
            Regex("""Class\.forName\s*\(\s*"android\."""),
            Regex("""getConnectedDevices\s*\(\s*BluetoothProfile\.(HEADSET|A2DP)\s*\)"""),
        )
        val violations = mutableListOf<String>()
        srcRoot!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { ktFile ->
            val content = ktFile.readText()
            for (pattern in forbiddenPatterns) {
                pattern.find(content)?.let { match ->
                    violations += "${ktFile.path}: ${match.value}"
                }
            }
        }
        assertTrue("Expected zero hidden-API reflection or invalid GATT profile calls, found: $violations", violations.isEmpty())
    }

    @Test
    fun linuxGuestDocumentsAuthorityAndPersistedSafMountDiscoveryOnUserBuilds() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val guestAuth = "com.android.virtualization.terminal.guestdocuments"

        assertTrue(GooglebookEcosystem.isLinuxAuthority(guestAuth))
        assertTrue(GooglebookEcosystem.isLinuxAuthority("10@$guestAuth"))
        assertEquals("Linux files", SafLabels.providerLabel(guestAuth, "root"))

        val safMount = SafMount(
            id = "linuxsaf",
            label = "Linux files",
            uri = DocumentsContract.buildTreeDocumentUri(guestAuth, "root").toString(),
        )
        val discovered = GooglebookEcosystem.discoverLinuxRoot(
            context = app,
            primaryStorage = "/nonexistent-primary",
            discoveredRoots = emptyList(),
            safMounts = listOf(safMount),
            candidateDirs = emptyList(),
        )
        assertNotNull(discovered)
        assertEquals("saf://linuxsaf", discovered!!.path)
        assertEquals("Linux files", discovered.label)

        // Register AVF Terminal's GuestDocumentsProvider in PackageManager and verify 1-click mount prompt intent.
        val shadowPm = shadowOf(app.packageManager)
        val guestComp = ComponentName("com.android.virtualization.terminal", "com.android.virtualization.terminal.GuestDocumentsProvider")
        shadowPm.addOrUpdateProvider(
            ProviderInfo().apply {
                packageName = guestComp.packageName
                name = guestComp.className
                authority = guestAuth
                exported = true
                grantUriPermissions = true
                applicationInfo = ApplicationInfo().apply {
                    packageName = guestComp.packageName
                }
            },
        )
        shadowPm.addIntentFilterForProvider(guestComp, android.content.IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        assertEquals(guestAuth, GooglebookEcosystem.findInstalledLinuxProviderAuthority(app))

        val h = VmHarness().also { harnesses += it }
        assertEquals(guestAuth, h.s.linuxProviderAuthority)
        h.events.clear()
        h.vm.requestMountLinuxFiles()
        val pickEvent = h.events.filterIsInstance<UiEvent.PickDocumentTree>().lastOrNull()
        assertNotNull(pickEvent)
        assertEquals(
            DocumentsContract.buildRootUri(guestAuth, GooglebookEcosystem.AVF_GUEST_ROOT_ID),
            pickEvent!!.initialUri,
        )
        assertFalse(DocumentsContract.isRootUri(app, DocumentsContract.buildRootsUri(guestAuth)))
        assertTrue(DocumentsContract.isRootUri(app, pickEvent.initialUri))
    }

    @Test
    fun phoneTreeMountTargetsMtpWhileWirelessImportTargetsSystemDocumentsUi() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val docUiPkg = "com.google.android.documentsui"
        val docUiCls = "com.android.documentsui.picker.PickActivity"
        val docUiComp = ComponentName(docUiPkg, docUiCls)
        val shadowPm = shadowOf(app.packageManager)
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = docUiPkg
                name = docUiCls
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = docUiPkg }
            },
        )
        shadowPm.addIntentFilterForActivity(
            docUiComp,
            android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            },
        )
        val qaComp = ComponentName("com.google.ambient.streaming", "com.google.ambient.streaming.QuickAccessDocumentsProvider")
        shadowPm.addOrUpdateProvider(
            ProviderInfo().apply {
                packageName = qaComp.packageName
                name = qaComp.className
                authority = PhoneDetector.QUICK_ACCESS_AUTHORITY
                exported = true
                grantUriPermissions = true
                applicationInfo = ApplicationInfo().apply { packageName = qaComp.packageName }
            },
        )
        shadowPm.addIntentFilterForProvider(qaComp, android.content.IntentFilter(DocumentsContract.PROVIDER_INTERFACE))

        val h = VmHarness().also { harnesses += it }
        h.events.clear()

        // When no concrete MTP rootId is accessible yet, never emit a broken 1-segment /root URI
        // (which causes DocumentsUI to fall back to its last-accessed folder, such as Movies).
        val wirelessPhone = PairedPhone(
            name = "Pixel 8a",
            path = FsPath.PHONE_ROOT,
            subtitle = "Connected via Bluetooth · Tap to mount storage",
            isMounted = false,
            authority = PhoneDetector.QUICK_ACCESS_AUTHORITY,
            transport = PhoneTransport.BLUETOOTH_UNMOUNTED,
        )
        h.vm.requestMountPhoneStorage(wirelessPhone)
        val treeEvent = h.events.filterIsInstance<UiEvent.PickDocumentTree>().lastOrNull()
        assertNotNull(treeEvent)
        assertNull(treeEvent!!.initialUri)

        // Before a QuickAccess device UUID is known, initialUri is null (never 1-segment /root).
        h.events.clear()
        h.vm.requestImportFromPhone()
        val firstImportEvent = h.events.filterIsInstance<UiEvent.ImportPhoneDocuments>().lastOrNull()
        assertNotNull(firstImportEvent)
        assertNull(firstImportEvent!!.initialUri)

        // Once a Cross-Device URI is observed, its <deviceId> rootId is extracted and remembered as a valid 2-segment root URI.
        val sampleDeviceId = "7e95d900-726a-46d4-ab90-111dd666ba85"
        val sampleQaDocUri = DocumentsContract.buildDocumentUri(
            PhoneDetector.QUICK_ACCESS_AUTHORITY,
            "$sampleDeviceId/Download/report.pdf",
        )
        assertEquals(sampleDeviceId, PhoneDetector.extractQuickAccessRootId(sampleQaDocUri))
        Settings(app).lastQuickAccessRootId = sampleDeviceId

        h.events.clear()
        h.vm.requestImportFromPhone()
        val importEvent = h.events.filterIsInstance<UiEvent.ImportPhoneDocuments>().lastOrNull()
        assertNotNull(importEvent)
        assertEquals(
            DocumentsContract.buildRootUri(PhoneDetector.QUICK_ACCESS_AUTHORITY, sampleDeviceId),
            importEvent!!.initialUri,
        )
        assertTrue(DocumentsContract.isRootUri(app, importEvent.initialUri))

        val openIntent = buildSystemDocumentOpenFilePickerIntent(app, importEvent.initialUri)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, openIntent.action)
        assertTrue(openIntent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertTrue(openIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(docUiPkg, openIntent.component?.packageName)
        assertNotEquals(app.packageName, openIntent.component?.packageName)
    }

    @Test
    fun crossDeviceQuickAccessProviderDetectionAndSideBySideSystemFilesIntent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)
        assertFalse(PhoneDetector.isQuickAccessProviderAvailable(app))

        val qaComp = ComponentName("com.google.ambient.streaming", "com.google.ambient.streaming.QuickAccessDocumentsProvider")
        shadowPm.addOrUpdateProvider(
            ProviderInfo().apply {
                packageName = qaComp.packageName
                name = qaComp.className
                authority = PhoneDetector.QUICK_ACCESS_AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
                applicationInfo = ApplicationInfo().apply { packageName = qaComp.packageName }
            },
        )
        assertTrue(PhoneDetector.isQuickAccessProviderAvailable(app))

        val docUiPkg = "com.google.android.documentsui"
        val filesCls = "com.android.documentsui.files.FilesActivity"
        val filesComp = ComponentName(docUiPkg, filesCls)
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = docUiPkg
                name = filesCls
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = docUiPkg }
            },
        )
        shadowPm.addIntentFilterForActivity(
            filesComp,
            android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("content")
                addDataType(DocumentsContract.Root.MIME_TYPE_ITEM)
            },
        )

        val h = VmHarness().also { harnesses += it }
        h.until(what = "QuickAccess provider detected in AppState") { h.s.quickAccessAvailable }
        assertTrue(h.s.quickAccessAvailable)

        h.events.clear()
        h.vm.openCrossDevicePhoneInSystemFiles()
        assertTrue(h.events.contains(UiEvent.OpenSystemPhoneFiles))

        val windowIntent = buildSystemPhoneFilesWindowIntent(app)
        assertNotNull(windowIntent)
        assertEquals(Intent.ACTION_VIEW, windowIntent!!.action)
        assertEquals(
            DocumentsContract.buildRootsUri(PhoneDetector.QUICK_ACCESS_AUTHORITY),
            windowIntent.data,
        )
        assertEquals(DocumentsContract.Root.MIME_TYPE_ITEM, windowIntent.type)
        assertEquals(filesComp, windowIntent.component)
        assertTrue((windowIntent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0)
        assertTrue((windowIntent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0)
    }

    @Test
    fun importExternalUrisToDownloadsStreamsFilesAndSanitizesHostileNames() {
        assertEquals("passwd", sanitizeImportedFileName("../../etc/passwd"))
        assertEquals("bad.exe", sanitizeImportedFileName("..\\..\\win\\bad.exe"))
        assertEquals("imported-file", sanitizeImportedFileName(".."))
        assertEquals("imported-file", sanitizeImportedFileName("   "))
        val overlong = "a".repeat(320) + ".pdf"
        val sanitizedLong = sanitizeImportedFileName(overlong)
        assertNull(FsPath.nameError(sanitizedLong))
        assertTrue(sanitizedLong.endsWith(".pdf"))

        val app = ApplicationProvider.getApplicationContext<Application>()
        val auth = "com.test.phone.quickaccess.import"
        val provider = Robolectric.setupContentProvider(FakeImportDocumentProvider::class.java, auth)
        provider.put("doc1", "vacation_photo.jpg", "jpeg-bytes-123".encodeToByteArray())
        provider.put("doc2", "../../escape\u0000attempt.txt", "safe-payload".encodeToByteArray())

        val downloadsDir = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).apply {
            mkdirs()
            File(this, "vacation_photo.jpg").delete()
            File(this, "escape_attempt.txt").delete()
        }

        val h = VmHarness().also { harnesses += it }
        h.navigate(FsPath.PHONE_ROOT)
        assertEquals(FsPath.PHONE_ROOT, h.s.active.path)

        val uri1 = Uri.parse("content://$auth/doc/doc1")
        val uri2 = Uri.parse("content://$auth/doc/doc2")
        h.vm.importExternalUrisToDownloads(listOf(uri1, uri2))

        h.until(what = "imported files copied to Downloads and tab navigated") {
            h.s.ops.isEmpty() && h.s.active.path == downloadsDir.path
        }
        h.settle()

        val imported1 = File(downloadsDir, "vacation_photo.jpg")
        val imported2 = File(downloadsDir, "escape_attempt.txt")
        assertTrue(imported1.isFile)
        assertEquals("jpeg-bytes-123", imported1.readText())
        assertTrue(imported2.isFile)
        assertEquals("safe-payload", imported2.readText())
        assertTrue(h.s.active.selection.containsAll(listOf(imported1.path, imported2.path)))
    }
}
