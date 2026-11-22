// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SelectionToolbarState
import com.droid.wayfinder.data.selectionToolbarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {

    private fun entry(
        path: String,
        isDir: Boolean = false,
        isServer: Boolean = false,
        isShare: Boolean = false,
    ) = FileEntry(
        path = path,
        name = FsPath.name(path),
        isDir = isDir,
        size = if (isDir) 0L else 128L,
        modified = 1_700_000_000_000L,
        isHidden = false,
        isServer = isServer,
        isShare = isShare,
    )

    @Test
    fun emptySelectionHidesToolbarActions() {
        val state = selectionToolbarState("/storage/emulated/0/Download", emptyList())
        assertEquals(SelectionToolbarState(), state)
        assertFalse(state.showToolbarActions)
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun localFilesEnableAllFiveActions() {
        val state = selectionToolbarState(
            tabPath = "/storage/emulated/0/Download",
            selectedEntries = listOf(
                entry("/storage/emulated/0/Download/report.pdf"),
                entry("/storage/emulated/0/Download/photo.jpg"),
            ),
        )
        assertTrue(state.showToolbarActions)
        assertEquals(2, state.selectedCount)
        assertFalse(state.inTrash)
        assertFalse(state.canRestore)
        assertTrue(state.canCut)
        assertTrue(state.canCopy)
        assertTrue(state.canCompress)
        assertTrue(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun localFolderOnlyDisablesShare() {
        val state = selectionToolbarState(
            tabPath = "/storage/emulated/0/Download",
            selectedEntries = listOf(entry("/storage/emulated/0/Download/Projects", isDir = true)),
        )
        assertTrue(state.showToolbarActions)
        assertTrue(state.canCut)
        assertTrue(state.canCopy)
        assertTrue(state.canCompress)
        assertFalse(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun mixedLocalFileAndFolderEnablesShare() {
        val state = selectionToolbarState(
            tabPath = "/storage/emulated/0/Download",
            selectedEntries = listOf(
                entry("/storage/emulated/0/Download/Projects", isDir = true),
                entry("/storage/emulated/0/Download/notes.txt", isDir = false),
            ),
        )
        assertTrue(state.showToolbarActions)
        assertTrue(state.canCut)
        assertTrue(state.canCopy)
        assertTrue(state.canCompress)
        assertTrue(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun remoteFilesEnableCutCopyCompressDeleteAndDisableShare() {
        val state = selectionToolbarState(
            tabPath = "smb://nas.local/docs",
            selectedEntries = listOf(entry("smb://nas.local/docs/budget.xlsx")),
        )
        assertTrue(state.showToolbarActions)
        assertTrue(state.canCut)
        assertTrue(state.canCopy)
        assertTrue(state.canCompress)
        assertFalse(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun virtualCollectionsDisableCompressButAllowCutCopyShareDelete() {
        for (collection in listOf(FsPath.RECENTS_ROOT, FsPath.STARRED_ROOT, FsPath.tagPath(ColorTag.Spec))) {
            val fileState = selectionToolbarState(
                tabPath = collection,
                selectedEntries = listOf(entry("/storage/emulated/0/Documents/spec.md")),
            )
            assertTrue("Expected selection actions in $collection", fileState.showToolbarActions)
            assertTrue(fileState.canCut)
            assertTrue(fileState.canCopy)
            assertFalse("Cannot create zip directly inside virtual collection $collection", fileState.canCompress)
            assertTrue(fileState.canShare)
            assertTrue(fileState.canDelete)

            val dirState = selectionToolbarState(
                tabPath = collection,
                selectedEntries = listOf(entry("/storage/emulated/0/Documents/Sub", isDir = true)),
            )
            assertTrue(dirState.showToolbarActions)
            assertTrue(dirState.canCut)
            assertTrue(dirState.canCopy)
            assertFalse(dirState.canCompress)
            assertFalse(dirState.canShare)
            assertTrue(dirState.canDelete)
        }
    }

    @Test
    fun insideZipArchiveOnlyAllowsCopy() {
        val state = selectionToolbarState(
            tabPath = "/storage/emulated/0/backup.zip!",
            selectedEntries = listOf(entry("/storage/emulated/0/backup.zip!/readme.txt")),
        )
        assertTrue(state.showToolbarActions)
        assertFalse(state.inTrash)
        assertFalse(state.canCut)
        assertTrue(state.canCopy)
        assertFalse(state.canCompress)
        assertFalse(state.canShare)
        assertFalse(state.canDelete)
    }

    @Test
    fun insideTrashRootAllowsRestoreAndPermanentDeleteOnly() {
        val state = selectionToolbarState(
            tabPath = FsPath.TRASH_ROOT,
            selectedEntries = listOf(entry("/storage/emulated/0/.Wayfinder-Trash/123_0/old.txt")),
            inTrash = true,
        )
        assertTrue(state.showToolbarActions)
        assertEquals(1, state.selectedCount)
        assertTrue(state.inTrash)
        assertTrue(state.canRestore)
        assertFalse(state.canCut)
        assertFalse(state.canCopy)
        assertFalse(state.canCompress)
        assertFalse(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun insideTrashedSubfolderTreatsPathAsTrashAndDisablesNonPermanentActions() {
        val state = selectionToolbarState(
            tabPath = "/storage/emulated/0/.Wayfinder-Trash/123_0/DeletedFolder",
            selectedEntries = listOf(entry("/storage/emulated/0/.Wayfinder-Trash/123_0/DeletedFolder/nested.txt")),
            inTrash = true,
        )
        assertTrue(state.showToolbarActions)
        assertEquals(1, state.selectedCount)
        assertTrue(state.inTrash)
        assertFalse("Nested items inside a trashed folder cannot be individually restored", state.canRestore)
        assertFalse(state.canCut)
        assertFalse(state.canCopy)
        assertFalse(state.canCompress)
        assertFalse(state.canShare)
        assertTrue(state.canDelete)
    }

    @Test
    fun serverShareAndVirtualEntriesDoNotShowFileActionToolbar() {
        val serverState = selectionToolbarState(
            tabPath = FsPath.SMB_ROOT,
            selectedEntries = listOf(entry("smb://nas.local", isDir = true, isServer = true)),
        )
        assertFalse(serverState.showToolbarActions)

        val shareState = selectionToolbarState(
            tabPath = "smb://nas.local",
            selectedEntries = listOf(entry("smb://nas.local/public", isDir = true, isShare = true)),
        )
        assertFalse(shareState.showToolbarActions)

        val virtualState = selectionToolbarState(
            tabPath = FsPath.SMB_ROOT,
            selectedEntries = listOf(entry(FsPath.TRASH_ROOT, isDir = true)),
        )
        assertFalse(virtualState.showToolbarActions)
    }
}
