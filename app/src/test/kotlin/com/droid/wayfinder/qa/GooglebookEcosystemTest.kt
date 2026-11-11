// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.os.UserManager
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.data.DiscoveredRoot
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.GooglebookEcosystem
import com.droid.wayfinder.data.SafLabels
import com.droid.wayfinder.data.SecretBox
import com.droid.wayfinder.data.WorkProfileTarget
import com.droid.wayfinder.data.foldForSearch
import com.droid.wayfinder.ui.AppState
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.TabState
import com.droid.wayfinder.ui.fileMenu
import com.droid.wayfinder.ui.paletteCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GooglebookEcosystemTest {

    @Test
    fun matchesSearchQuerySearchesNameAiSummaryAndLocalTextContent() {
        val dir = Files.createTempDirectory("wf-ai-search").toFile()
        try {
            val invoicePdf = FileEntry(
                path = File(dir, "scan_0042.pdf").absolutePath,
                name = "scan_0042.pdf",
                isDir = false,
                size = 120_000L,
                modified = 1_700_000_000_000L,
                subtitle = "Acme Corp Q3 cloud infrastructure invoice ($4,250)",
            )
            // Matches by filename
            assertTrue(GooglebookEcosystem.matchesSearchQuery(invoicePdf, foldForSearch("scan_0042")))
            // Matches by Files AI / COLUMN_SUMMARY subtitle
            assertTrue(GooglebookEcosystem.matchesSearchQuery(invoicePdf, foldForSearch("acme infrastructure")))
            // Multi-token match spanning filename + AI summary
            assertTrue(GooglebookEcosystem.matchesSearchQuery(invoicePdf, foldForSearch("scan invoice")))
            assertFalse(GooglebookEcosystem.matchesSearchQuery(invoicePdf, foldForSearch("unmentioned keyword")))

            // Local text/markdown file content indexing
            val notesMd = File(dir, "architecture_notes.md").apply {
                writeText("# Aluminium Kernel Notes\nVirtio-fs shared directory mount for Crostini container.\n")
            }
            val notesEntry = FileEntry(
                path = notesMd.absolutePath,
                name = notesMd.name,
                isDir = false,
                size = notesMd.length(),
                modified = notesMd.lastModified(),
            )
            assertTrue(GooglebookEcosystem.matchesSearchQuery(notesEntry, foldForSearch("virtio-fs crostini")))
            assertFalse(GooglebookEcosystem.matchesSearchQuery(notesEntry, foldForSearch("nonexistent_token")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun discoverLinuxRootFindsTerminalDocumentsProviderAndLocalLinuxFiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempPrimary = Files.createTempDirectory("wf-primary").toFile()
        try {
            val providerRoot = GooglebookEcosystem.discoverLinuxRoot(
                context = app,
                primaryStorage = tempPrimary.absolutePath,
                discoveredRoots = listOf(
                    DiscoveredRoot(
                        authority = "com.android.virtualization.terminal.documents",
                        rootId = "linux",
                        title = "Linux files",
                        documentId = "linux_home",
                        summary = "Debian bookworm container",
                    ),
                ),
                candidateDirs = emptyList(),
            )
            assertNotNull(providerRoot)
            assertEquals("doc://com.android.virtualization.terminal.documents/linux_home", providerRoot!!.path)
            assertEquals("Linux files", providerRoot.label)
            assertEquals("Debian bookworm container", providerRoot.subtitle)
            assertEquals("Linux files", SafLabels.providerLabel("com.android.virtualization.terminal.documents", "linux_home"))

            // Fallback to local LinuxFiles directory when no DocumentsProvider root is present
            val localLinuxDir = File(tempPrimary, "LinuxFiles").apply { mkdirs() }
            val fallbackRoot = GooglebookEcosystem.discoverLinuxRoot(
                context = app,
                primaryStorage = tempPrimary.absolutePath,
                discoveredRoots = emptyList(),
                candidateDirs = listOf(localLinuxDir),
            )
            assertNotNull(fallbackRoot)
            assertEquals(localLinuxDir.absolutePath, fallbackRoot!!.path)
            assertEquals("Linux files", fallbackRoot.label)
            assertTrue(GooglebookEcosystem.isLinuxLocation(providerRoot.path, providerRoot.label, providerRoot.path))
            assertTrue(GooglebookEcosystem.isLinuxLocation(fallbackRoot.path, fallbackRoot.label, fallbackRoot.path))
            assertTrue(GooglebookEcosystem.isLinuxLocation("saf://linux1", "Linux files · Linux VM", null))
            assertTrue(GooglebookEcosystem.isLinuxLocation("doc://com.android.virtualization.terminal.guestdocuments/guest_root", "Linux VM", null))
            assertFalse(GooglebookEcosystem.isLinuxLocation(tempPrimary.absolutePath, "Internal storage", providerRoot.path))
        } finally {
            tempPrimary.deleteRecursively()
        }
    }

    @Test
    fun enterpriseUserManagerRestrictionsAreEnforced() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val um = app.getSystemService(UserManager::class.java)
        val shadowUm = shadowOf(um)
        val userHandle = android.os.Process.myUserHandle()

        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_USB_FILE_TRANSFER, false)
        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, false)
        assertFalse(GooglebookEcosystem.isUsbFileTransferDisallowed(app))
        assertFalse(GooglebookEcosystem.isPhysicalMediaMountDisallowed(app))

        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_USB_FILE_TRANSFER, true)
        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, true)
        assertTrue(GooglebookEcosystem.isUsbFileTransferDisallowed(app))
        assertTrue(GooglebookEcosystem.isPhysicalMediaMountDisallowed(app))

        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_USB_FILE_TRANSFER, false)
        shadowUm.setUserRestriction(userHandle, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, false)
    }

    @Test
    fun contextMenuAndCommandPaletteSurfaceLinuxFilesAndWorkProfiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = BrowserViewModel(app, SavedStateHandle())
        val entry = FileEntry("/storage/emulated/0/Download/report.txt", "report.txt", false, 512L, 1_700_000_000_000L)
        val tab = TabState(
            id = 1L,
            path = "/storage/emulated/0/Download",
            entries = listOf(entry),
            selection = setOf(entry.path),
        )
        val state = AppState(
            tabs = listOf(tab),
            activeId = 1L,
            linuxRootPath = "doc://com.android.virtualization.terminal.documents/root",
            workProfiles = listOf(WorkProfileTarget(id = "work-10", label = "Work profile")),
        )

        val menuLabels = fileMenu(vm, state, entry).filterIsInstance<MenuEntry.Item>().map { it.label }
        assertTrue("Expected 'Copy to Linux files' in file context menu, got: $menuLabels", "Copy to Linux files" in menuLabels)

        val paletteNames = paletteCommands(vm, state).map { it.name }
        assertTrue("Expected 'Linux files' in command palette", "Linux files" in paletteNames)
        assertTrue("Expected 'Copy to Linux files' in command palette", "Copy to Linux files" in paletteNames)
        assertTrue("Expected 'Work profile' in command palette", "Work profile" in paletteNames)
    }

    @Test
    fun secretBoxRejectsTruncatedCiphertextsAndApacheLicenseFileExists() {
        val box = SecretBox("test_alias")
        // Empty, non-base64, and <= 12-byte truncated payloads must safely return null
        assertNull(box.decrypt(""))
        assertNull(box.decrypt("not-valid-base64!!!"))
        assertNull(box.decrypt(android.util.Base64.encodeToString(ByteArray(8), android.util.Base64.NO_WRAP)))
        assertNull(box.decrypt(android.util.Base64.encodeToString(ByteArray(12), android.util.Base64.NO_WRAP)))

        // Verify the repository root LICENSE file exists and contains the Apache 2.0 header
        val candidates = listOf(File("../LICENSE"), File("LICENSE"))
        val licenseFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Root LICENSE file must exist", licenseFile)
        val licenseText = licenseFile!!.readText()
        assertTrue(licenseText.contains("Apache License"))
        assertTrue(licenseText.contains("Version 2.0, January 2004"))
    }
}
