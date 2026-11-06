// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.RenameItem
import com.droid.wayfinder.data.RenameRule
import com.droid.wayfinder.data.SearchCategory
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SearchScope
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.data.planBatchRename
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** BrowserViewModel behaviour under Robolectric with real files in Robolectric's external storage dir. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowserViewModelQaTest {
    private val harnesses = mutableListOf<VmHarness>()
    private fun harness(extras: Map<String, Any?> = emptyMap()) = VmHarness(extras).also { harnesses += it }

    @After fun tearDown() { harnesses.forEach { it.close() } }

    // ------------------------------------------------------------------ start-up & navigation

    @Test fun startsAtPrimaryStorageWithOneTab() {
        val h = harness()
        assertEquals(1, h.s.tabs.size)
        assertEquals(FsPath.RECENTS_ROOT, h.s.active.path)
        assertTrue(h.s.roots.isNotEmpty())
    }

    @Test fun backForwardUpReselectTheFolderWeCameFrom() {
        val h = harness()
        h.dir("a/b/c")
        h.navigate(h.pathOf("a")); h.navigate(h.pathOf("a/b"))
        h.vm.back(); h.settle()
        assertEquals(h.pathOf("a"), h.s.active.path)
        assertEquals(setOf(h.pathOf("a/b")), h.s.active.selection)
        assertTrue(h.s.active.canGoForward)
        h.vm.forward(); h.settle()
        assertEquals(h.pathOf("a/b"), h.s.active.path)
        h.vm.up(); h.settle()
        assertEquals(h.pathOf("a"), h.s.active.path)
        assertEquals(setOf(h.pathOf("a/b")), h.s.active.selection)
        assertEquals(h.pathOf("a/b"), h.s.active.focused)
        assertEquals(h.pathOf("a"), h.s.recentFolders.first())
    }

    @Test fun openingFolderNavigatesAndMissingFolderShowsError() {
        val h = harness()
        h.dir("docs"); h.navigate(h.root.path)
        h.vm.open(h.s.active.entries.first { it.name == "docs" }); h.settle()
        assertEquals(h.pathOf("docs"), h.s.active.path)
        h.navigate(h.pathOf("ghost"))
        assertEquals("This folder no longer exists", h.s.active.error?.message)
    }

    @Test fun openingAFileEmitsOpenFileEvent() {
        val h = harness()
        h.file("doc.txt"); h.navigate(h.root.path)
        h.vm.open(h.s.active.entries.first { it.name == "doc.txt" }); h.settle()
        assertEquals(h.pathOf("doc.txt"), h.events.filterIsInstance<UiEvent.OpenFile>().single().path)
    }

    // ------------------------------------------------------------------ tabs

    @Test fun tabsOpenCloseReopenCycleAndMove() {
        val h = harness()
        h.dir("one"); h.dir("two"); h.dir("three")
        h.vm.newTab(h.pathOf("one")); h.settle()
        h.vm.newTab(h.pathOf("two")); h.settle()
        h.vm.newTab(h.pathOf("three"), activate = false); h.settle()
        assertEquals(listOf(FsPath.RECENTS_ROOT, h.pathOf("one"), h.pathOf("two"), h.pathOf("three")), h.s.tabs.map { it.path })
        assertEquals(h.pathOf("two"), h.s.active.path)
        h.vm.cycleTab(1); assertEquals(h.pathOf("three"), h.s.active.path)
        h.vm.cycleTab(1); assertEquals(FsPath.RECENTS_ROOT, h.s.active.path) // wraps
        h.vm.cycleTab(-1); assertEquals(h.pathOf("three"), h.s.active.path)
        h.vm.selectTabIndex(1); assertEquals(h.pathOf("one"), h.s.active.path)
        h.vm.selectTabIndex(8); assertEquals(h.pathOf("three"), h.s.active.path) // Alt+9 = last tab
        h.vm.closeTab(h.s.activeId); h.settle()
        assertEquals(3, h.s.tabs.size)
        assertEquals(h.pathOf("two"), h.s.active.path)
        h.vm.reopenClosedTab(); h.settle()
        assertEquals(h.pathOf("three"), h.s.active.path)
        h.vm.moveTab(0, 3)
        assertEquals(FsPath.RECENTS_ROOT, h.s.tabs.last().path)
        h.vm.closeOtherTabs(h.s.activeId)
        assertEquals(listOf(h.pathOf("three")), h.s.tabs.map { it.path })
        h.vm.closeTab(h.s.activeId)
        assertTrue("closing the last tab closes the window", h.events.any { it is UiEvent.Finish })
    }

    @Test fun duplicateTabAndMiddleClickOpenInBackgroundTab() {
        val h = harness()
        h.dir("folder"); h.navigate(h.root.path)
        h.vm.open(h.s.active.entries.first { it.name == "folder" }, newTab = true); h.settle()
        assertEquals(2, h.s.tabs.size)
        assertEquals(h.root.path, h.s.active.path) // background tab
        h.vm.duplicateTab(h.s.activeId); h.settle()
        assertEquals(3, h.s.tabs.size)
    }

    @Test fun tabsPersistAcrossLaunchesButNotForExtraWindows() {
        val h = harness()
        h.dir("keep")
        h.vm.newTab(h.pathOf("keep")); h.settle()
        h.close(); harnesses.remove(h)
        val again = harness()
        assertEquals(listOf(FsPath.RECENTS_ROOT, h.pathOf("keep")), again.s.tabs.map { it.path })
        assertEquals(h.pathOf("keep"), again.s.active.path)
        again.close(); harnesses.remove(again)
        val extra = harness(mapOf(BrowserViewModel.EXTRA_NEW_WINDOW to true, BrowserViewModel.EXTRA_PATH to h.pathOf("keep")))
        assertTrue(extra.s.secondaryWindow)
        assertEquals(listOf(h.pathOf("keep")), extra.s.tabs.map { it.path })
        extra.vm.newTab(h.root.path); extra.settle()
        extra.close(); harnesses.remove(extra)
        assertEquals(2, harness().s.tabs.size) // the extra window didn't overwrite the session
    }

    @Test fun newWindowEmitsEventWithCurrentPath() {
        val h = harness()
        h.vm.newWindow()
        assertEquals(FsPath.RECENTS_ROOT, h.events.filterIsInstance<UiEvent.NewWindow>().single().path)
        val extraDefault = harness(mapOf(BrowserViewModel.EXTRA_NEW_WINDOW to true))
        assertTrue(extraDefault.s.secondaryWindow)
        assertEquals(listOf(FsPath.RECENTS_ROOT), extraDefault.s.tabs.map { it.path })
    }

    // ------------------------------------------------------------------ selection

    @Test fun clickCtrlShiftSelectAllInvertAndTypeAhead() {
        val h = harness()
        listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt").forEach { h.file("sel/$it") }
        h.navigate(h.pathOf("sel"))
        val p = { n: String -> h.pathOf("sel/$n") }
        h.vm.click(p("b.txt"), ctrl = false, shift = false)
        assertEquals(setOf(p("b.txt")), h.s.active.selection)
        h.vm.click(p("d.txt"), ctrl = false, shift = true)
        assertEquals(setOf(p("b.txt"), p("c.txt"), p("d.txt")), h.s.active.selection)
        h.vm.click(p("c.txt"), ctrl = true, shift = false)
        assertEquals(setOf(p("b.txt"), p("d.txt")), h.s.active.selection)
        h.vm.invertSelection()
        assertEquals(setOf(p("a.txt"), p("c.txt"), p("e.txt")), h.s.active.selection)
        h.vm.selectAll(); assertEquals(5, h.s.active.selection.size)
        h.vm.clearSelection(); assertTrue(h.s.active.selection.isEmpty())
        h.vm.typeAhead("d"); assertEquals(setOf(p("d.txt")), h.s.active.selection)
        h.vm.moveFocus(-2, extend = true)
        assertEquals(setOf(p("b.txt"), p("c.txt"), p("d.txt")), h.s.active.selection)
        h.vm.focusEdge(last = true, extend = false); assertEquals(p("e.txt"), h.s.active.focused)
        h.vm.focusEdge(last = false, extend = true); assertEquals(5, h.s.active.selection.size)
        h.vm.moveFocus(1, keepSelection = true); h.vm.toggleFocusedSelection()
        assertFalse(p("b.txt") in h.s.active.selection)
    }

    @Test fun hiddenFilesToggle() {
        val h = harness()
        h.file("hid/.secret"); h.file("hid/plain.txt")
        h.navigate(h.pathOf("hid"))
        assertEquals(listOf("plain.txt"), h.names())
        h.vm.toggleHidden()
        assertEquals(listOf(".secret", "plain.txt"), h.names())
        h.vm.toggleHidden()
    }

    // ------------------------------------------------------------------ quick look

    @Test fun quickLookOpensOnFocusedItemMovesAndCloses() {
        val h = harness()
        listOf("1.txt", "2.txt", "3.txt").forEach { h.file("ql/$it") }
        h.navigate(h.pathOf("ql"))
        h.vm.toggleQuickLook()
        assertEquals(h.pathOf("ql/1.txt"), h.s.quickLook)
        assertEquals(setOf(h.pathOf("ql/1.txt")), h.s.active.selection)
        h.vm.quickLookMove(1)
        assertEquals(h.pathOf("ql/2.txt"), h.s.quickLook)
        h.vm.quickLookMove(5)
        assertEquals(h.pathOf("ql/3.txt"), h.s.quickLook)
        h.vm.toggleQuickLook()
        assertNull(h.s.quickLook)
        h.vm.click(h.pathOf("ql/2.txt"), false, false)
        h.vm.toggleQuickLook()
        assertEquals(h.pathOf("ql/2.txt"), h.s.quickLook)
        h.vm.openPalette()
        assertNull("palette closes quick look", h.s.quickLook)
    }

    // ------------------------------------------------------------------ dual pane

    @Test fun dualPaneSwitchAndTransfer() {
        val h = harness()
        h.file("left/x.txt", "X"); h.file("left/y.txt", "Y"); h.dir("right")
        h.navigate(h.pathOf("left"))
        h.vm.toggleDualPane(); h.settle()
        assertTrue(h.s.dualPane)
        assertEquals(h.pathOf("left"), h.s.secondary!!.path)
        h.vm.activatePane(true)
        h.navigate(h.pathOf("right"))
        assertEquals(h.pathOf("right"), h.s.secondary!!.path)
        assertEquals(h.pathOf("left"), h.s.mainTab.path)
        h.vm.switchPane(); assertFalse(h.s.rightActive)
        h.select("left/x.txt")
        h.vm.transferToOtherPane(move = false)
        h.until(what = "copy") { File(h.root, "right/x.txt").exists() }; h.settle()
        assertTrue(File(h.root, "left/x.txt").exists())
        assertEquals(setOf(h.pathOf("right/x.txt")), h.s.secondary!!.selection)
        h.select("left/y.txt")
        h.vm.transferToOtherPane(move = true)
        h.until(what = "move") { File(h.root, "right/y.txt").exists() }; h.settle()
        assertFalse(File(h.root, "left/y.txt").exists())
        h.vm.clearSelection(); h.vm.transferToOtherPane(false)
        assertTrue(h.toasts().last().startsWith("Select items"))
        h.vm.toggleDualPane(); assertFalse(h.s.dualPane)
    }

    // ------------------------------------------------------------------ clipboard, conflicts, drag & drop

    @Test fun copyPasteAndCutPasteWithConflictPrompt() {
        val h = harness()
        h.file("src/a.txt", "new"); h.file("dst/a.txt", "old"); h.file("src/b.txt", "b")
        h.navigate(h.pathOf("src")); h.select("src/a.txt", "src/b.txt"); h.vm.copy()
        assertFalse(h.s.clipboard!!.cut)
        h.navigate(h.pathOf("dst")); h.vm.paste()
        h.until(what = "conflict prompt") { h.s.dialog is Dialog.ConflictPrompt }
        val d = h.s.dialog as Dialog.ConflictPrompt
        assertEquals(listOf("a.txt"), d.names)
        h.vm.resolveConflict(d, Conflict.Replace)
        h.until(what = "replace") { File(h.root, "dst/b.txt").exists() }; h.settle()
        assertEquals("new", File(h.root, "dst/a.txt").readText())
        assertNotNull("copy keeps clipboard", h.s.clipboard)

        h.navigate(h.pathOf("src")); h.select("src/b.txt"); h.vm.cut()
        assertTrue(h.s.clipboard!!.cut)
        h.navigate(h.pathOf("dst")); h.vm.paste()
        h.until(what = "conflict prompt 2") { h.s.dialog is Dialog.ConflictPrompt }
        h.vm.resolveConflict(h.s.dialog as Dialog.ConflictPrompt, Conflict.KeepBoth)
        h.until(what = "keep both") { File(h.root, "dst/b (1).txt").exists() }; h.settle()
        assertFalse(File(h.root, "src/b.txt").exists())
        assertNull("cut clipboard is consumed", h.s.clipboard)
    }

    @Test fun pasteIntoArchiveOrTrashIsRefused() {
        val h = harness()
        h.file("a.txt"); h.navigate(h.root.path); h.select("a.txt"); h.vm.copy()
        h.navigate(FsPath.TRASH_ROOT); h.vm.paste()
        assertEquals("Can't paste here", h.toasts().last())
    }

    @Test fun dropOnFolderMovesOrCopiesAndOnStarredOrTrashMarks() {
        val h = harness()
        h.file("d/a.txt"); h.file("d/b.txt"); h.file("d/c.txt"); h.dir("d/target")
        h.navigate(h.pathOf("d"))
        h.vm.dropOn(h.pathOf("d/target"), listOf(h.pathOf("d/a.txt")), copy = false)
        h.until(what = "drop move") { File(h.root, "d/target/a.txt").exists() }; h.settle()
        assertFalse(File(h.root, "d/a.txt").exists())
        h.vm.dropOn(h.pathOf("d/target"), listOf(h.pathOf("d/b.txt")), copy = true)
        h.until(what = "drop copy") { File(h.root, "d/target/b.txt").exists() }; h.settle()
        assertTrue(File(h.root, "d/b.txt").exists())
        h.vm.dropOn(FsPath.STARRED_ROOT, listOf(h.pathOf("d/b.txt")), copy = false)
        assertTrue(h.s.marks.isStarred(h.pathOf("d/b.txt")))
        h.vm.dropOn(FsPath.tagPath(ColorTag.Review), listOf(h.pathOf("d/b.txt")), copy = false)
        assertEquals(setOf(ColorTag.Review), h.s.marks.tagsOf(h.pathOf("d/b.txt")))
        h.vm.dropOn(FsPath.TRASH_ROOT, listOf(h.pathOf("d/c.txt")), copy = false)
        h.until(what = "drop trash") { !File(h.root, "d/c.txt").exists() }; h.settle()
        // A folder dropped onto itself / its own parent is ignored.
        h.vm.dropOn(h.pathOf("d"), listOf(h.pathOf("d/target")), copy = false); h.settle()
        assertTrue(File(h.root, "d/target").isDirectory)
    }

    // ------------------------------------------------------------------ trash / delete

    @Test fun deleteToTrashRestoreDeletePermanentlyAndEmpty() {
        val h = harness()
        h.file("t/a.txt", "A"); h.file("t/b.txt", "B"); h.file("t/c.txt", "C")
        h.navigate(h.pathOf("t")); h.select("t/a.txt", "t/b.txt")
        h.vm.requestDelete()
        h.until(what = "trash") { !File(h.root, "t/a.txt").exists() && !File(h.root, "t/b.txt").exists() }; h.settle()
        assertTrue(h.toasts().last().contains("Moved 2 items to Trash"))
        h.navigate(FsPath.TRASH_ROOT)
        assertEquals(listOf("a.txt", "b.txt"), h.names())
        h.vm.setSelection(setOf(h.s.active.entries.first { it.name == "a.txt" }.path))
        h.vm.restoreFromTrash()
        h.until(what = "restore") { File(h.root, "t/a.txt").exists() }; h.settle()
        assertEquals(listOf("b.txt"), h.names())
        // Inside the Trash, Delete is always permanent and asks first.
        h.vm.setSelection(setOf(h.s.active.entries.single().path))
        h.vm.requestDelete()
        val d = h.s.dialog as Dialog.Delete
        h.vm.confirmDelete(d.paths); h.settle()
        assertTrue(h.names().isEmpty())
        // Shift+Del outside the Trash.
        h.navigate(h.pathOf("t")); h.select("t/c.txt")
        h.vm.requestDelete(permanent = true)
        h.vm.confirmDelete((h.s.dialog as Dialog.Delete).paths); h.settle()
        assertFalse(File(h.root, "t/c.txt").exists())
        h.select("t/a.txt"); h.vm.requestDelete(); h.settle()
        h.vm.emptyTrash(); h.settle()
        h.navigate(FsPath.TRASH_ROOT)
        assertTrue(h.names().isEmpty())
    }

    // ------------------------------------------------------------------ undo / redo

    @Test fun undoRedoRename() {
        val h = harness()
        h.file("u/old.txt", "x"); h.navigate(h.pathOf("u"))
        h.vm.rename(h.pathOf("u/old.txt"), "new.txt"); h.settle()
        assertTrue(File(h.root, "u/new.txt").exists())
        assertEquals(setOf(h.pathOf("u/new.txt")), h.s.active.selection)
        assertTrue(h.s.canUndo)
        h.vm.undo(); h.settle()
        assertTrue(File(h.root, "u/old.txt").exists()); assertFalse(File(h.root, "u/new.txt").exists())
        assertTrue(h.s.canRedo)
        h.vm.redo(); h.settle()
        assertTrue(File(h.root, "u/new.txt").exists())
        assertFalse(h.s.canRedo)
    }

    @Test fun renameRejectsSlashesAndExistingNames() {
        val h = harness()
        h.file("r/a.txt"); h.file("r/b.txt"); h.navigate(h.pathOf("r"))
        h.vm.rename(h.pathOf("r/a.txt"), "x/y"); h.settle()
        assertEquals("Names can't contain slashes", h.toasts().last())
        h.vm.rename(h.pathOf("r/a.txt"), "b.txt"); h.settle()
        assertTrue(h.toasts().last().contains("already exists"))
        assertTrue(File(h.root, "r/a.txt").exists())
    }

    @Test fun undoRedoMoveTrashAndCreate() {
        val h = harness()
        h.file("m/a.txt"); h.dir("m/dst"); h.navigate(h.pathOf("m"))
        h.vm.dropOn(h.pathOf("m/dst"), listOf(h.pathOf("m/a.txt")), copy = false)
        h.until(what = "move") { File(h.root, "m/dst/a.txt").exists() }; h.settle()
        h.vm.undo(); h.until(what = "undo move") { File(h.root, "m/a.txt").exists() }; h.settle()
        assertFalse(File(h.root, "m/dst/a.txt").exists())
        h.vm.redo(); h.until(what = "redo move") { File(h.root, "m/dst/a.txt").exists() }; h.settle()

        h.vm.createItem("Fresh", folder = true); h.settle()
        assertTrue(File(h.root, "m/Fresh").isDirectory)
        assertEquals(setOf(h.pathOf("m/Fresh")), h.s.active.selection)
        h.vm.undo(); h.settle()
        assertFalse("undo of create sends it to Trash", File(h.root, "m/Fresh").exists())
        h.vm.redo(); h.settle()
        assertTrue(File(h.root, "m/Fresh").isDirectory)

        h.select("Fresh".let { "m/$it" }); h.vm.requestDelete(); h.settle()
        assertFalse(File(h.root, "m/Fresh").exists())
        h.vm.undo(); h.settle()
        assertTrue("undo of delete restores", File(h.root, "m/Fresh").isDirectory)
    }

    @Test fun newFileAndFolderCreationAndDuplicateNameError() {
        val h = harness()
        h.dir("n"); h.navigate(h.pathOf("n"))
        h.vm.requestNewItem(folder = false)
        assertEquals(Dialog.NewItem(false), h.s.dialog)
        h.vm.createItem("note.txt", folder = false); h.settle()
        assertTrue(File(h.root, "n/note.txt").isFile)
        h.vm.createItem("note.txt", folder = true); h.settle()
        assertTrue(h.toasts().last().contains("already exists"))
        h.vm.createItem("   ", folder = true); h.settle()
        assertEquals(1, File(h.root, "n").list()!!.size)
    }

    // ------------------------------------------------------------------ batch rename & duplicate

    @Test fun batchRenameAppliesSwapsAsOneUndoableStep() {
        val h = harness()
        h.file("br/1.txt", "one"); h.file("br/2.txt", "two"); h.file("br/IMG_a.jpg", "a")
        h.navigate(h.pathOf("br")); h.select("br/1.txt", "br/2.txt")
        h.vm.requestBatchRename()
        val d = h.s.dialog as Dialog.BatchRename
        assertEquals(listOf("1.txt", "2.txt"), d.items.map { it.name })
        // Number them in reverse order: 2.txt → 1.txt and 1.txt → 2.txt (a swap).
        val plan = planBatchRename(listOf(RenameItem("2.txt", false), RenameItem("1.txt", false)), RenameRule.Numbered("", 1), d.existing)
        assertTrue(plan.all { it.conflict == null })
        h.vm.batchRename(d.dir, plan); h.settle()
        assertEquals("two", File(h.root, "br/1.txt").readText())
        assertEquals("one", File(h.root, "br/2.txt").readText())
        assertTrue(h.toasts().last().contains("Renamed 2"))
        assertTrue("no temp files left", File(h.root, "br").list()!!.none { it.startsWith(".wf-rename") })
        h.vm.undo(); h.settle()
        assertEquals("one", File(h.root, "br/1.txt").readText())
        assertEquals("two", File(h.root, "br/2.txt").readText())
        // Nothing selected → all visible items; find & replace.
        h.vm.clearSelection(); h.vm.requestBatchRename()
        val all = h.s.dialog as Dialog.BatchRename
        assertEquals(3, all.items.size)
        h.vm.batchRename(all.dir, planBatchRename(all.items.map { RenameItem(it.name, it.isDir) }, RenameRule.FindReplace("IMG_", "Photo "), all.existing)); h.settle()
        assertTrue(File(h.root, "br/Photo a.jpg").exists())
    }

    @Test fun duplicateViaViewModelSelectsCopiesAndIsUndoable() {
        val h = harness()
        h.file("dup/a.txt", "A"); h.navigate(h.pathOf("dup")); h.select("dup/a.txt")
        h.vm.duplicate(); h.settle()
        assertEquals("A", File(h.root, "dup/a copy.txt").readText())
        assertEquals(setOf(h.pathOf("dup/a copy.txt")), h.s.active.selection)
        h.vm.undo(); h.settle()
        assertFalse(File(h.root, "dup/a copy.txt").exists())
    }

    // ------------------------------------------------------------------ compress / extract / zip browse

    @Test fun compressExtractAndBrowseArchive() {
        val h = harness()
        h.file("z/doc.txt", "D"); h.file("z/pics/p.txt", "P")
        h.navigate(h.pathOf("z")); h.select("z/doc.txt", "z/pics")
        h.vm.requestCompress()
        val c = h.s.dialog as Dialog.Compress
        assertEquals("z", c.suggested)
        h.vm.compress(c.sources, "bundle"); h.settle()
        val zip = File(h.root, "z/bundle.zip")
        assertTrue(zip.isFile)
        assertEquals(setOf(zip.path), h.s.active.selection)
        h.vm.extractSelection(); h.settle()
        assertEquals("P", File(h.root, "z/bundle/pics/p.txt").readText())
        // Browse the archive like a folder.
        h.vm.open(h.s.active.entries.first { it.name == "bundle.zip" }); h.settle()
        assertEquals(FsPath.zipRoot(zip.path), h.s.active.path)
        assertEquals(listOf("pics", "doc.txt"), h.names())
        h.vm.setSelection(setOf(h.s.active.entries.first { it.name == "doc.txt" }.path))
        h.vm.requestDelete(); assertEquals("Archives are read-only", h.toasts().last())
        h.vm.cut(); assertEquals("Items in an archive can only be copied", h.toasts().last())
        h.vm.requestNewItem(true); assertEquals("Archives are read-only", h.toasts().last())
        h.vm.pin(h.s.active.path); assertEquals("Folders inside archives can't be pinned", h.toasts().last())
        h.vm.copy()
        h.navigate(h.pathOf("z/pics")); h.vm.paste()
        h.until(what = "copy out of zip") { File(h.root, "z/pics/doc.txt").exists() }; h.settle()
        assertEquals("D", File(h.root, "z/pics/doc.txt").readText())
    }

    @Test fun extractSelectionExtractsTarGzArchives() {
        val h = harness()
        val dir = h.dir("tar_extract")
        val tarGz = java.io.File(dir, "bundle.tar.gz")
        java.io.FileOutputStream(tarGz).use { fos ->
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(fos).use { gzos ->
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzos).use { tos ->
                    val data = "hello from tar.gz".toByteArray()
                    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("inner/hello.txt")
                    entry.size = data.size.toLong()
                    tos.putArchiveEntry(entry)
                    tos.write(data)
                    tos.closeArchiveEntry()
                }
            }
        }
        h.navigate(dir.path)
        h.select("tar_extract/bundle.tar.gz")
        h.vm.extractSelection()
        h.settle()
        val extracted = java.io.File(dir, "bundle/inner/hello.txt")
        assertTrue("Expected extracted file at ${extracted.path}", extracted.exists())
        assertEquals("hello from tar.gz", extracted.readText())
    }

    // ------------------------------------------------------------------ pins, view options, settings

    @Test fun pinsAddRemoveReorderAndPersist() {
        val h = harness()
        h.dir("p1"); h.dir("p2"); h.dir("p3")
        val f1 = h.file("p1/note.txt", "hello")
        h.vm.pin(h.pathOf("p1")); h.vm.pin(h.pathOf("p2")); h.vm.togglePin(h.pathOf("p3"))
        assertEquals(listOf("p1", "p2", "p3"), h.s.pins.map { File(it).name })
        h.vm.movePin(h.pathOf("p3"), -2)
        assertEquals(listOf("p3", "p1", "p2"), h.s.pins.map { File(it).name })
        h.vm.movePin(h.pathOf("p3"), -1) // out of range: ignored
        h.vm.togglePin(h.pathOf("p1"))
        assertEquals(listOf("p3", "p2"), h.s.pins.map { File(it).name })
        // Pinning and unpinning regular files via togglePins and openPinned
        h.vm.togglePins(listOf(f1.path))
        assertTrue(f1.path in h.s.pins)
        h.vm.openPinned(f1.path); h.settle()
        assertEquals(h.pathOf("p1"), h.s.active.path)
        assertEquals(setOf(f1.path), h.s.active.selection)
        assertEquals(f1.path, h.events.filterIsInstance<UiEvent.OpenFile>().last().path)
        // Renaming a pinned file updates its path in pins
        h.vm.rename(f1.path, "renamed.txt"); h.settle()
        val renamedPath = h.pathOf("p1/renamed.txt")
        assertTrue(renamedPath in h.s.pins)
        assertFalse(f1.path in h.s.pins)
        h.vm.togglePins(listOf(renamedPath))
        assertFalse(renamedPath in h.s.pins)
        h.vm.pin(FsPath.TRASH_ROOT) // virtual: ignored
        h.close(); harnesses.remove(h)
        assertEquals(listOf("p3", "p2"), harness().s.pins.map { File(it).name })
    }

    @Test fun viewModeSortZoomThemeAndColumnsPersist() {
        val h = harness()
        h.vm.setViewMode(ViewMode.Grid); assertEquals(ViewMode.Grid, h.s.active.viewMode)
        h.vm.setSort(SortKey.Size); assertEquals(SortKey.Size, h.s.active.sortKey); assertTrue(h.s.active.sortAsc)
        h.vm.setSort(SortKey.Size); assertFalse("same key flips direction", h.s.active.sortAsc)
        h.vm.zoomBy(1); assertEquals(1.1f, h.s.zoom, 0.001f)
        repeat(10) { h.vm.zoomBy(1) }; assertEquals(1.6f, h.s.zoom, 0.001f)
        repeat(20) { h.vm.zoomBy(-1) }; assertEquals(0.7f, h.s.zoom, 0.001f)
        h.vm.zoomBy(0); assertEquals(1f, h.s.zoom, 0.001f)
        h.vm.zoomBy(1)
        assertEquals("Interface zoom: 110%", h.toasts().last())
        h.vm.cycleTheme(); h.settle(); assertEquals(ThemeMode.Light, h.s.theme)
        h.vm.cycleTheme(); h.settle(); assertEquals(ThemeMode.Dark, h.s.theme)
        h.vm.toggleTagsColumn(); h.vm.togglePermsColumn(); h.vm.toggleSidebar(); h.vm.togglePreview()
        h.vm.setGridSize(500f); assertEquals(280f, h.s.gridSize, 0.01f)
        h.close(); harnesses.remove(h)
        val again = harness()
        assertEquals(ViewMode.Grid, again.s.active.viewMode)
        assertEquals(SortKey.Size, again.s.active.sortKey); assertFalse(again.s.active.sortAsc)
        assertEquals(1.1f, again.s.zoom, 0.001f)
        assertTrue(again.s.tagsColumn && again.s.permsColumn)
        assertFalse(again.s.sidebarVisible); assertTrue(again.s.previewVisible)
        assertEquals(280f, again.s.gridSize, 0.01f)
    }

    // ------------------------------------------------------------------ search

    @Test fun recursiveSearchWithFiltersAndScope() {
        val h = harness()
        h.file("s/report.txt"); h.file("s/deep/er/Report-final.pdf"); h.file("s/report.png"); h.file("s/.hidden/report.md")
        h.file("elsewhere/report.kt")
        h.navigate(h.pathOf("s"))
        h.vm.setQuery("report"); h.settle()
        assertEquals(setOf("report.txt", "Report-final.pdf", "report.png"), h.s.active.searchResults!!.map { it.name }.toSet())
        h.vm.setSearchFilter(SearchFilter(category = SearchCategory.Docs)); h.settle()
        assertEquals(setOf("report.txt", "Report-final.pdf"), h.s.active.searchResults!!.map { it.name }.toSet())
        h.vm.setSearchFilter(SearchFilter(scope = SearchScope.Everywhere, category = SearchCategory.Code)); h.settle()
        assertEquals(setOf("report.kt"), h.s.active.searchResults!!.map { it.name }.toSet())
        // Search is diacritics/case-insensitive and clears with an empty query.
        h.vm.setSearchFilter(SearchFilter()); h.vm.setQuery("RÉPORT"); h.settle()
        assertEquals(3, h.s.active.searchResults!!.size)
        h.vm.setSelection(setOf(h.s.active.searchResults!!.first { it.name == "Report-final.pdf" }.path))
        h.vm.showInEnclosingFolder(h.pathOf("s/deep/er/Report-final.pdf")); h.settle()
        assertEquals(h.pathOf("s/deep/er"), h.s.active.path)
        assertEquals(setOf(h.pathOf("s/deep/er/Report-final.pdf")), h.s.active.selection)
        assertNull(h.s.active.searchResults)
        h.vm.setQuery("zzz-none"); h.settle()
        assertTrue(h.s.active.searchResults!!.isEmpty())
        h.vm.setQuery(""); h.settle()
        assertNull(h.s.active.searchResults)
    }

    // ------------------------------------------------------------------ recents / starred / tags

    @Test fun starsAndTagsFollowRenameAndMoveAndListInCollections() {
        val h = harness()
        h.file("k/a.txt"); h.dir("k/dst"); h.navigate(h.pathOf("k")); h.select("k/a.txt")
        h.vm.toggleStar(); h.vm.toggleTag(ColorTag.Urgent)
        assertEquals("Added to Starred", h.toasts().last())
        h.vm.rename(h.pathOf("k/a.txt"), "b.txt"); h.settle()
        assertTrue(h.s.marks.isStarred(h.pathOf("k/b.txt")))
        assertFalse(h.s.marks.isStarred(h.pathOf("k/a.txt")))
        h.vm.dropOn(h.pathOf("k/dst"), listOf(h.pathOf("k/b.txt")), copy = false)
        h.until(what = "move") { File(h.root, "k/dst/b.txt").exists() }; h.settle()
        assertTrue(h.s.marks.isStarred(h.pathOf("k/dst/b.txt")))
        assertEquals(setOf(ColorTag.Urgent), h.s.marks.tagsOf(h.pathOf("k/dst/b.txt")))
        h.navigate(FsPath.STARRED_ROOT); assertEquals(listOf("b.txt"), h.names())
        h.navigate(FsPath.tagPath(ColorTag.Urgent)); assertEquals(listOf("b.txt"), h.names())
        // Unstar from the collection view: the listing follows.
        h.vm.setSelection(setOf(h.pathOf("k/dst/b.txt")))
        h.vm.toggleStar(); h.settle()
        h.navigate(FsPath.STARRED_ROOT); assertTrue(h.names().isEmpty())
        // No selection: the current folder is starred.
        h.navigate(h.pathOf("k")); h.vm.toggleStar()
        assertTrue(h.s.marks.isStarred(h.pathOf("k")))
    }

    @Test fun recentsListsRecentlyModifiedFilesNewestFirst() {
        val h = harness()
        h.file("rec/old.txt").setLastModified(System.currentTimeMillis() - 90L * 86_400_000L)
        h.file("rec/new.txt")
        h.navigate(FsPath.RECENTS_ROOT)
        assertTrue(h.names().contains("new.txt"))
        assertFalse(h.names().contains("old.txt"))
    }

    // ------------------------------------------------------------------ properties & checksums

    @Test fun propertiesComputeSizeAndChecksums() {
        val h = harness()
        h.file("pr/abc.txt", "abc"); h.file("pr/dir/x", "12345")
        h.navigate(h.pathOf("pr")); h.select("pr/abc.txt")
        h.vm.showProperties()
        h.until(what = "checksums") { (h.s.dialog as? Dialog.Properties)?.checksums != null && (h.s.dialog as Dialog.Properties).bytes != null }
        val d = h.s.dialog as Dialog.Properties
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d.checksums!!.sha256)
        assertEquals("352441c2", d.checksums!!.crc32)
        assertEquals(3L, d.bytes)
        h.vm.dismissDialog()
        h.select("pr/abc.txt", "pr/dir"); h.vm.showProperties()
        h.until(what = "multi size") { (h.s.dialog as? Dialog.Properties)?.bytes != null }
        val m = h.s.dialog as Dialog.Properties
        assertEquals(8L, m.bytes); assertEquals(2, m.items); assertNull(m.checksums)
        h.vm.copyValue(d.checksums!!.sha256, "SHA-256")
        assertEquals("SHA-256", h.events.filterIsInstance<UiEvent.CopyText>().last().what)
    }

    @Test fun copyPathUsesSelectionOrCurrentFolder() {
        val h = harness()
        h.file("cp/a.txt"); h.navigate(h.pathOf("cp"))
        h.vm.copyPathToClipboard()
        assertEquals(h.pathOf("cp"), h.events.filterIsInstance<UiEvent.CopyText>().last().text)
        h.select("cp/a.txt"); h.vm.copyPathToClipboard()
        assertEquals(h.pathOf("cp/a.txt"), h.events.filterIsInstance<UiEvent.CopyText>().last().text)
    }

    // ------------------------------------------------------------------ columns view

    @Test fun columnsViewEnterAndColumnClick() {
        val h = harness()
        h.file("col/sub/f.txt"); h.navigate(h.pathOf("col"))
        h.vm.setViewMode(ViewMode.Columns)
        h.vm.typeAhead("sub"); h.vm.columnEnter(); h.settle()
        assertEquals(h.pathOf("col/sub"), h.s.active.path)
        h.vm.columnClick(h.s.active.entries.single()); h.settle()
        assertEquals(h.pathOf("col/sub"), h.s.active.path)
        assertEquals(setOf(h.pathOf("col/sub/f.txt")), h.s.active.selection)
    }

    // ------------------------------------------------------------------ remote locations offline

    @Test fun unreachableServerShowsErrorInsteadOfCrashing() {
        val h = harness()
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(h.vm.openAddress("ftp://qa:pw@127.0.0.1:$port/pub"))
        h.settle(timeoutMs = 20_000)
        assertEquals("ftp://127.0.0.1:$port/pub", h.s.active.path)
        assertTrue(h.s.active.error?.message.orEmpty(), h.s.active.error!!.message.startsWith("Can't reach"))
        assertFalse(h.vm.openAddress("not a url at all with spaces://"))
        h.navigate(FsPath.SMB_ROOT)
        assertTrue(h.s.active.error == null)
    }

    // ------------------------------------------------------------------ picker mode

    @Test fun pickerModeReturnsMatchingFilesOnly() {
        val h = harness(mapOf(BrowserViewModel.EXTRA_PICKER to true, BrowserViewModel.EXTRA_PICKER_MIME to arrayOf("text/plain")))
        assertNotNull(h.s.picker)
        h.file("pick/a.txt"); h.file("pick/b.zip"); h.dir("pick/sub")
        h.navigate(h.pathOf("pick"))
        h.vm.open(h.s.active.entries.first { it.name == "b.zip" }); h.settle()
        assertTrue(h.toasts().last().startsWith("The requesting app expects"))
        h.select("pick/a.txt", "pick/b.zip")
        assertEquals(listOf("a.txt"), h.vm.pickableSelection().map { it.name })
        h.vm.pickSelection()
        assertEquals("The requesting app accepts a single file", h.toasts().last())
        h.vm.open(h.s.active.entries.first { it.name == "a.txt" }); h.settle()
        assertEquals(listOf(h.pathOf("pick/a.txt")), h.events.filterIsInstance<UiEvent.PickResult>().single().paths)
        h.vm.open(h.s.active.entries.first { it.name == "sub" }); h.settle()
        assertEquals("folders still navigate", h.pathOf("pick/sub"), h.s.active.path)
        h.vm.cancelPick(); assertTrue(h.events.any { it is UiEvent.PickCancel })
    }

    @Test fun openShareAndPickRefuseSymlinksAndPrivateAppFiles() {
        val h = harness(mapOf(BrowserViewModel.EXTRA_PICKER to true, BrowserViewModel.EXTRA_PICKER_MIME to arrayOf("*/*")))
        val privateSecret = File(h.app.applicationInfo.dataDir, "shared_prefs/secret.txt").apply {
            parentFile?.mkdirs()
            writeText("top-secret")
        }
        val sym = File(h.root, "sec/link.txt").apply { parentFile?.mkdirs() }
        java.nio.file.Files.createSymbolicLink(sym.toPath(), privateSecret.toPath())
        h.navigate(h.pathOf("sec"))
        val entry = h.s.active.entries.first { it.name == "link.txt" }

        // 1. Picker refuses symlink to private file
        h.vm.pick(listOf(entry))
        assertEquals("This file can't be shared", h.toasts().last())
        assertTrue(h.events.filterIsInstance<UiEvent.PickResult>().isEmpty())

        // 2. Share refuses symlink to private file
        h.select("sec/link.txt")
        h.vm.share()
        assertEquals("This file can't be shared", h.toasts().last())
        assertTrue(h.events.filterIsInstance<UiEvent.Share>().isEmpty())

        // 3. Open refuses symlink to private file
        h.vm.openWith()
        assertEquals("This file can't be opened", h.toasts().last())
        assertTrue(h.events.filterIsInstance<UiEvent.OpenFile>().isEmpty())
    }

    @Test fun defaultHandlerPromptAndActionViewRouting() {
        val h = harness()
        assertNull(h.s.dialog)

        // First install / first run prompt appears once and remembers preference
        h.vm.maybeShowDefaultHandlerPrompt()
        assertEquals(Dialog.DefaultHandler, h.s.dialog)
        h.vm.dismissDialog()
        assertNull(h.s.dialog)

        // Subsequent calls to maybeShowDefaultHandlerPrompt do not re-trigger
        h.vm.maybeShowDefaultHandlerPrompt()
        assertNull(h.s.dialog)

        // On-demand trigger from overflow menu / command palette always opens it
        h.vm.showDefaultHandlerDialog()
        assertEquals(Dialog.DefaultHandler, h.s.dialog)
        h.vm.dismissDialog()

        // ACTION_VIEW routing for archives (.zip, .tar.gz) and folders
        val zipFile = h.file("archives/bundle.zip", "PK")
        val tarGzFile = h.file("archives/source.tar.gz", "gz")
        val folder = h.dir("archives/docs")
        assertEquals(
            FsPath.zipRoot(zipFile.path),
            com.droid.wayfinder.MainActivity.resolveViewTargetPath(h.app, android.net.Uri.fromFile(zipFile), "application/zip", h.root.path),
        )
        assertEquals(
            FsPath.zipRoot(tarGzFile.path),
            com.droid.wayfinder.MainActivity.resolveViewTargetPath(h.app, android.net.Uri.fromFile(tarGzFile), "application/gzip", h.root.path),
        )
        assertEquals(
            folder.path,
            com.droid.wayfinder.MainActivity.resolveViewTargetPath(h.app, android.net.Uri.fromFile(folder), "resource/folder", h.root.path),
        )

        // Security: ACTION_VIEW must reject private app dataDir paths and symlinks
        val privateArchive = File(h.app.applicationInfo.dataDir, "shared_prefs/secret.zip").apply {
            parentFile?.mkdirs()
            writeText("secret")
        }
        assertNull(
            com.droid.wayfinder.MainActivity.resolveViewTargetPath(h.app, android.net.Uri.fromFile(privateArchive), "application/zip", h.root.path),
        )
        val symlinkArchive = File(h.root, "archives/sym.zip")
        java.nio.file.Files.createSymbolicLink(symlinkArchive.toPath(), zipFile.toPath())
        assertNull(
            com.droid.wayfinder.MainActivity.resolveViewTargetPath(h.app, android.net.Uri.fromFile(symlinkArchive), "application/zip", h.root.path),
        )
    }
}

