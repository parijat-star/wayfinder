// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {
    private val primary = "/storage/emulated/0"
    private val trashRoot = "/storage/emulated/0/.Wayfinder-Trash"

    @Test
    fun defaultFiveFoldersMapLabelsIconsRanksAndExposeLocalPath() {
        val pins = listOf(
            "$primary/Download",
            "$primary/Documents",
            "$primary/Pictures",
            "$primary/Music",
            "$primary/Movies",
        )
        val specs = AppShortcuts.planShortcuts(
            pins = pins,
            maxCount = 5,
            primaryStorageRoot = primary,
            trashRoot = trashRoot,
        )

        assertEquals(5, specs.size)
        assertEquals(listOf("Downloads", "Documents", "Pictures", "Music", "Movies"), specs.map { it.shortLabel })
        assertEquals(listOf("Downloads", "Documents", "Pictures", "Music", "Movies"), specs.map { it.longLabel })
        assertEquals(
            listOf(
                ShortcutIconKind.Downloads,
                ShortcutIconKind.Documents,
                ShortcutIconKind.Pictures,
                ShortcutIconKind.Music,
                ShortcutIconKind.Movies,
            ),
            specs.map { it.icon },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), specs.map { it.rank })
        assertTrue(specs.all { it.exposePathExtra })
    }

    @Test
    fun mirrorsCustomSidebarPinsInOrderAndClampsToDeviceLimit() {
        val pins = listOf(
            "$primary/Projects",
            "$primary/Work/Invoices",
            "$primary/Download",
            "$primary/Documents",
            "$primary/Pictures",
            "$primary/Music",
        )

        val top4 = AppShortcuts.planShortcuts(pins = pins, maxCount = 4, primaryStorageRoot = primary)
        assertEquals(listOf("Projects", "Invoices", "Downloads", "Documents"), top4.map { it.shortLabel })
        assertEquals(listOf(0, 1, 2, 3), top4.map { it.rank })
        assertEquals(ShortcutIconKind.Folder, top4[0].icon)
        assertEquals(ShortcutIconKind.Folder, top4[1].icon)
        assertEquals(ShortcutIconKind.Downloads, top4[2].icon)

        val cappedAtMax = AppShortcuts.planShortcuts(pins = pins, maxCount = 15, primaryStorageRoot = primary)
        assertEquals(AppShortcuts.MAX_SHORTCUTS, cappedAtMax.size)

        val zero = AppShortcuts.planShortcuts(pins = pins, maxCount = 0, primaryStorageRoot = primary)
        assertTrue(zero.isEmpty())
    }

    @Test
    fun skipsIneligibleVirtualArchiveTrashDuplicateAndUnsafePins() {
        val pins = listOf(
            FsPath.RECENTS_ROOT,
            "$primary/archive.zip!/inner",
            "$trashRoot/deleted",
            "$primary/Documents/notes.txt",
            "$primary/Projects",
            "$primary/Projects/",
            "$primary/../etc",
            "$primary/bad\u0000dir",
            "$primary/Download",
        )
        val dirs = setOf("$primary/Projects", "$primary/Download")
        val specs = AppShortcuts.planShortcuts(
            pins = pins,
            maxCount = 5,
            primaryStorageRoot = primary,
            trashRoot = trashRoot,
            isPinEligible = { it in dirs },
        )

        assertEquals(listOf("$primary/Projects", "$primary/Download"), specs.map { it.path })
        assertEquals(listOf(0, 1), specs.map { it.rank })
    }

    @Test
    fun disambiguatesDuplicateFolderNamesInLongLabelAndProducesDistinctIds() {
        val pins = listOf(
            "$primary/Work/Reports",
            "$primary/Personal/Reports",
            "$primary/Download",
        )
        val specs = AppShortcuts.planShortcuts(
            pins = pins,
            maxCount = 5,
            primaryStorageRoot = primary,
        )

        assertEquals(3, specs.size)
        assertNotEquals(specs[0].id, specs[1].id)
        assertEquals("Reports", specs[0].shortLabel)
        assertEquals("Reports (Work)", specs[0].longLabel)
        assertEquals("Reports", specs[1].shortLabel)
        assertEquals("Reports (Personal)", specs[1].longLabel)
        assertEquals("Downloads", specs[2].longLabel)
    }

    @Test
    fun remotePinsHidePathExtraAndClassifyCloudAndNetworkIcons() {
        val pins = listOf(
            "onedrive://acct123/Work",
            "smb://nas.local/media",
            "saf://mount1/Books",
            "gdrive://user@example.com/Docs",
        )
        val specs = AppShortcuts.planShortcuts(
            pins = pins,
            maxCount = 5,
            primaryStorageRoot = primary,
            rootLabels = mapOf("saf://mount1" to "USB Drive"),
        )

        assertEquals(4, specs.size)
        assertTrue("Remote shortcuts must not expose raw URI in intent extras", specs.none { it.exposePathExtra })
        assertEquals(
            listOf(ShortcutIconKind.Cloud, ShortcutIconKind.Network, ShortcutIconKind.Cloud, ShortcutIconKind.Cloud),
            specs.map { it.icon },
        )
        assertTrue("Shortcut ID must not embed user email", specs.none { it.id.contains("example.com") })
        assertTrue("Shortcut ID must not embed server host", specs.none { it.id.contains("nas.local") })
    }

    @Test
    fun resolveTargetPathMatchesPinnedRemoteByIdAndRejectsUnpinnedRemoteFallback() {
        val pinned = listOf("onedrive://acct123/Work", "$primary/Download")
        val remoteId = AppShortcuts.shortcutId("onedrive://acct123/Work")

        val resolvedRemote = AppShortcuts.resolveTargetPath(
            shortcutId = remoteId,
            pathExtra = null,
            pinnedPaths = pinned,
            trashRoot = trashRoot,
            isSafeLocalDirectory = { true },
            isValidRemotePath = { true },
        )
        assertEquals("onedrive://acct123/Work", resolvedRemote)

        val spoofedRemote = AppShortcuts.resolveTargetPath(
            shortcutId = "pin_unknown",
            pathExtra = "smb://attacker/share",
            pinnedPaths = pinned,
            trashRoot = trashRoot,
            isSafeLocalDirectory = { true },
            isValidRemotePath = { true },
        )
        assertNull("Unpinned remote path must never resolve via pathExtra fallback", spoofedRemote)
    }

    @Test
    fun resolveTargetPathSupportsUnpinnedLocalDesktopShortcutWhileBlockingTraversalAndTrash() {
        val unpinnedLocal = "$primary/CustomFolder"
        val resolved = AppShortcuts.resolveTargetPath(
            shortcutId = AppShortcuts.shortcutId(unpinnedLocal),
            pathExtra = unpinnedLocal,
            pinnedPaths = listOf("$primary/Download"),
            trashRoot = trashRoot,
            isSafeLocalDirectory = { it == unpinnedLocal },
        )
        assertEquals(unpinnedLocal, resolved)

        assertNull(
            AppShortcuts.resolveTargetPath(
                shortcutId = null,
                pathExtra = "$primary/Download/../../etc",
                pinnedPaths = emptyList(),
                trashRoot = trashRoot,
                isSafeLocalDirectory = { true },
            ),
        )
        assertNull(
            AppShortcuts.resolveTargetPath(
                shortcutId = null,
                pathExtra = "$trashRoot/item",
                pinnedPaths = emptyList(),
                trashRoot = trashRoot,
                isSafeLocalDirectory = { true },
            ),
        )
    }

    @Test
    fun standardDisplayLabelUsesLocalizedStringsOnlyForPrimaryRootChildren() {
        val fr = StandardFolderLabels(
            downloads = "Téléchargements",
            documents = "Documents",
            pictures = "Images",
            music = "Musique",
            movies = "Films",
        )
        assertEquals("Téléchargements", AppShortcuts.standardDisplayLabel("$primary/Download", primary, fr))
        assertEquals("Images", AppShortcuts.standardDisplayLabel("$primary/Pictures", primary, fr))
        assertNull(AppShortcuts.standardDisplayLabel("$primary/Work/Download", primary, fr))
        assertFalse(AppShortcuts.isSafeLocalPathSyntax("relative/Download"))
    }
}
