// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.R
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.RenamePlan
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Clipboard
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.backgroundMenu
import com.droid.wayfinder.ui.fileMenu
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.theme.WayfinderTheme
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class TrashSubfolderGuardsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
    }

    @After
    fun tearDown() {
        MainActivity.hasAccessOverride = null
        h?.close()
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    private fun createZipArchive(targetZip: File, entries: Map<String, String>) {
        targetZip.parentFile?.mkdirs()
        ZipOutputStream(targetZip.outputStream()).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    @Test
    fun trashedSubfoldersEnforceTrashMenusBlockMutatorsHideSlotsAndRefuseSymlinkTraversal() {
        val harness = VmHarness().also { h = it }
        val projectDir = harness.dir("OldProject/nested")
        val nestedNote = harness.file("OldProject/nested/notes.txt", "keep safe")
        val nestedZip = File(projectDir, "inner.zip")
        createZipArchive(nestedZip, mapOf("doc.txt" to "zip-doc"))
        val topArchive = File(harness.root, "top_bundle.zip")
        createZipArchive(topArchive, mapOf("sub/archived.txt" to "inside-archive"))
        val topFolder = harness.pathOf("OldProject")
        val siblingFolder = harness.dir("Destination")

        if (!harness.s.previewVisible) harness.vm.togglePreview()

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // Move OldProject and top_bundle.zip to Trash
        harness.navigate(harness.root.path)
        harness.vm.setSelection(setOf(topFolder, topArchive.path))
        harness.vm.requestDelete(permanent = false)
        idle(harness)
        assertFalse("OldProject moved to Trash", File(topFolder).exists())
        assertFalse("top_bundle.zip moved to Trash", topArchive.exists())

        // 1. At Trash root (trash://): Restore is enabled on top-level trashed folder; Open enters it
        harness.navigate(FsPath.TRASH_ROOT)
        idle(harness)
        val trashedProjectEntry = harness.s.active.entries.single { it.name == "OldProject" }
        val trashedTopZipEntry = harness.s.active.entries.single { it.name == "top_bundle.zip" }
        assertTrue(harness.vm.isInTrash(trashedProjectEntry.path))
        assertTrue(harness.vm.canRestoreFromTrash(trashedProjectEntry.path))

        harness.vm.setSelection(setOf(trashedProjectEntry.path))
        val rootTrashMenu = fileMenu(harness.vm, harness.s, trashedProjectEntry).filterIsInstance<MenuEntry.Item>()
        assertTrue("Top-level trashed folder menu must offer Open", rootTrashMenu.any { it.label == "Open" && it.enabled })
        assertTrue("Top-level trashed folder menu must enable Restore", rootTrashMenu.single { it.label == "Restore" }.enabled)
        assertTrue("Top-level trashed folder menu must enable Delete permanently", rootTrashMenu.single { it.label == "Delete permanently" }.enabled)
        assertEquals(listOf("Empty Trash", "Refresh"), backgroundMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>().map { it.label })

        // Top-level trashed archive: opening top_bundle.zip! hides slot in breadcrumbs and Up returns to trash://
        harness.vm.open(trashedTopZipEntry)
        idle(harness)
        assertTrue(FsPath.isZip(harness.s.active.path))
        assertTrue(harness.vm.isInTrash(harness.s.active.path))
        assertEquals(listOf("Trash", "top_bundle.zip"), harness.vm.trashBreadcrumbs(harness.s.active.path)?.map { it.first })
        harness.vm.up()
        idle(harness)
        assertEquals("Up from top-level trashed archive root returns to trash://", FsPath.TRASH_ROOT, harness.s.active.path)
        assertEquals(setOf(trashedTopZipEntry.path), harness.s.active.selection)

        // 2. Navigate inside trashed folder -> nested subfolder
        harness.vm.open(trashedProjectEntry)
        idle(harness)
        val trashedProjectPath = harness.s.active.path
        assertEquals(trashedProjectEntry.path, trashedProjectPath)
        assertTrue(harness.vm.isInTrash(trashedProjectPath))

        val nestedDirEntry = harness.s.active.entries.single { it.name == "nested" }
        harness.vm.open(nestedDirEntry)
        idle(harness)
        val trashedNestedPath = harness.s.active.path
        assertTrue(harness.vm.isInTrash(trashedNestedPath))
        assertFalse("Nested item inside trashed folder has no standalone .origin slot", harness.vm.canRestoreFromTrash(trashedNestedPath))

        // Breadcrumbs in both Toolbar and FsPath.breadcrumbs hide internal .Wayfinder-Trash/<slot> directory
        val crumbs = harness.vm.trashBreadcrumbs(trashedNestedPath)
        assertNotNull(crumbs)
        assertEquals(listOf("Trash", "OldProject", "nested"), crumbs!!.map { it.first })
        assertEquals(FsPath.TRASH_ROOT, crumbs.first().second)
        assertEquals(listOf("Trash", "OldProject", "nested"), FsPath.breadcrumbs(trashedNestedPath, harness.s.rootLabels).map { it.first })

        // 3. Context menus, PreviewPane, and CommandPalette inside trashed subfolder forbid mutations and soft-Delete
        val trashedNoteEntry = harness.s.active.entries.single { it.name == "notes.txt" }
        val trashedZipEntry = harness.s.active.entries.single { it.name == "inner.zip" }
        harness.vm.setSelection(setOf(trashedNoteEntry.path))
        idle(harness)

        // PreviewPane hides Rename/Pin/Share buttons when inspecting a trashed file
        rule.onNodeWithText("notes.txt")
        rule.onAllNodesWithText("Rename").assertCountEquals(0)
        rule.onAllNodesWithText("Share").assertCountEquals(0)
        rule.onNodeWithText("Trash / OldProject / nested").assertIsDisplayed()

        val nestedFileItems = fileMenu(harness.vm, harness.s, trashedNoteEntry).filterIsInstance<MenuEntry.Item>()
        val nestedLabels = nestedFileItems.map { it.label }.toSet()
        val forbiddenInTrash = setOf(
            harness.app.getString(R.string.action_cut),
            harness.app.getString(R.string.action_paste_into_folder),
            harness.app.getString(R.string.action_duplicate),
            harness.app.getString(R.string.action_rename),
            harness.app.getString(R.string.action_compress_to_zip),
            harness.app.getString(R.string.action_extract_archive_here),
            harness.app.getString(R.string.action_add_to_starred),
            harness.app.getString(R.string.action_pin_to_sidebar),
            harness.app.getString(R.string.action_delete),
        )
        assertTrue(
            "Trashed subfolder fileMenu leaked forbidden actions: ${nestedLabels intersect forbiddenInTrash}",
            (nestedLabels intersect forbiddenInTrash).isEmpty(),
        )
        assertFalse("Nested item Restore must be disabled", nestedFileItems.single { it.label == "Restore" }.enabled)
        assertTrue("Nested item Delete permanently must be enabled", nestedFileItems.single { it.label == "Delete permanently" }.enabled)

        val nestedBgLabels = backgroundMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>().map { it.label }.toSet()
        assertEquals(setOf("Select all", "Refresh", "Properties"), nestedBgLabels)

        // Command palette Pin action does not pin trashed paths
        paletteCommands(harness.vm, harness.s).first { it.shortcut == "Ctrl+B" }.action()
        assertTrue("Command palette Ctrl+B must not pin trashed entries", harness.s.pins.none { it.contains(".Wayfinder-Trash") })

        // 4. All ViewModel mutators are blocked inside trashed subfolders
        harness.vm.setSelection(setOf(trashedZipEntry.path))
        harness.vm.cut()
        assertNull("Cut must be blocked inside Trash", harness.s.clipboard)

        harness.vm.requestNewItem(folder = true)
        assertNull("requestNewItem must be blocked inside trashed subfolder", harness.s.dialog)
        harness.vm.createItem("intruder", folder = true)
        idle(harness)
        assertFalse("createItem must not create folders inside Trash", File(trashedNestedPath, "intruder").exists())

        harness.vm.requestRename()
        assertNull("requestRename must be blocked inside Trash", harness.s.dialog)
        harness.vm.rename(trashedNoteEntry.path, "renamed.txt")
        idle(harness)
        assertTrue(File(trashedNoteEntry.path).exists())

        harness.vm.requestBatchRename()
        assertNull("requestBatchRename must be blocked inside Trash", harness.s.dialog)
        harness.vm.batchRename(trashedNestedPath, listOf(RenamePlan("notes.txt", "hacked.txt", conflict = null)))
        idle(harness)
        assertTrue(File(trashedNoteEntry.path).exists())

        harness.vm.requestCompress()
        assertNull("requestCompress must be blocked inside Trash", harness.s.dialog)
        harness.vm.compress(listOf(trashedNoteEntry.path), "out.zip")
        idle(harness)
        assertFalse(File(trashedNestedPath, "out.zip").exists())

        harness.vm.extractSelection()
        idle(harness)
        assertFalse("extractSelection must be blocked inside Trash", File(trashedNestedPath, "doc.txt").exists())

        harness.vm.duplicate()
        idle(harness)
        assertEquals(2, harness.s.active.entries.size)

        harness.vm.pin(trashedNestedPath)
        harness.vm.togglePins(listOf(trashedNestedPath))
        assertFalse("Trashed paths must never be pinned to Quick Access", trashedNestedPath in harness.s.pins)
        harness.vm.toggleStar(listOf(trashedNoteEntry.path))
        assertFalse("Explicit toggleStar on trashed item must be ignored", harness.s.marks.isStarred(trashedNoteEntry.path))
        harness.vm.dropOn(FsPath.STARRED_ROOT, listOf(trashedNoteEntry.path), copy = false)
        assertFalse("Dragging trashed item onto Starred must be ignored", harness.s.marks.isStarred(trashedNoteEntry.path))
        assertFalse("canPickFolder must reject trashed folders", harness.vm.canPickFolder(trashedNestedPath))
        assertTrue("recentFolders must exclude .Wayfinder-Trash paths", harness.s.recentFolders.none { it.contains(".Wayfinder-Trash") })
        harness.vm.locateInTree()
        assertTrue("Sidebar tree must never expand .Wayfinder-Trash or slot dirs", harness.s.tree.expanded.none { it.contains(".Wayfinder-Trash") })

        // Navigating to raw .Wayfinder-Trash or calling showInEnclosingFolder on top-level Trash item redirects to trash://
        harness.vm.showInEnclosingFolder(trashedProjectPath)
        idle(harness)
        assertEquals("showInEnclosingFolder on top-level Trash item routes to trash://", FsPath.TRASH_ROOT, harness.s.active.path)
        assertEquals(setOf(trashedProjectPath), harness.s.active.selection)
        harness.vm.navigate(File(harness.root, ".Wayfinder-Trash").path)
        idle(harness)
        assertEquals("Direct navigation to .Wayfinder-Trash redirects to trash://", FsPath.TRASH_ROOT, harness.s.active.path)
        harness.vm.navigate(trashedNestedPath)
        idle(harness)

        // Dragging an item out of Trash onto a normal folder copies rather than moving (preserving .origin slot & source)
        harness.vm.dropOn(siblingFolder.path, listOf(trashedNoteEntry.path), copy = false)
        idle(harness)
        assertTrue("Drop out of Trash copies file to destination", File(siblingFolder, "notes.txt").exists())
        assertTrue("Original file inside trashed folder remains untouched", File(trashedNoteEntry.path).exists())

        // Symlink ancestor inside a trashed folder must never allow deletePermanently to traverse outside Trash
        val protectedOutsideDir = harness.dir("ProtectedOutside")
        val protectedVictim = harness.file("ProtectedOutside/keep.txt", "precious")
        val symlinkInsideTrash = File(trashedNestedPath, "evil_link")
        Files.createSymbolicLink(symlinkInsideTrash.toPath(), protectedOutsideDir.toPath())
        harness.vm.confirmDelete(listOf(File(symlinkInsideTrash, "keep.txt").path))
        idle(harness)
        assertTrue("deletePermanently must refuse symlink traversal outside Trash", protectedVictim.exists())
        symlinkInsideTrash.delete()

        // Paste into trashed subfolder is blocked
        val outsideFile = harness.file("Destination/outside.txt", "outside")
        harness.navigate(siblingFolder.path)
        harness.vm.setSelection(setOf(outsideFile.path))
        harness.vm.copy()
        assertEquals(Clipboard(listOf(outsideFile.path), cut = false), harness.s.clipboard)
        harness.navigate(trashedNestedPath)
        harness.vm.paste()
        idle(harness)
        assertFalse("Paste into trashed subfolder must be blocked", File(trashedNestedPath, "outside.txt").exists())

        // Permanent deletion of a single nested item inside a trashed folder works via Dialog.Delete
        harness.vm.setSelection(setOf(trashedZipEntry.path))
        harness.vm.requestDelete(permanent = false)
        assertTrue("Deleting inside trashed subfolder must open permanent Dialog.Delete", harness.s.dialog is Dialog.Delete)
        harness.vm.confirmDelete(listOf(trashedZipEntry.path))
        idle(harness)
        assertFalse(File(trashedZipEntry.path).exists())
        assertTrue("Sibling file in trashed folder remains intact", File(trashedNoteEntry.path).exists())

        // 5. Up navigation from nested -> OldProject -> trash:// (never exposing internal slot directory!)
        harness.vm.up()
        idle(harness)
        assertEquals(trashedProjectPath, harness.s.active.path)
        harness.vm.up()
        idle(harness)
        assertEquals("Up from top-level trashed folder must return to trash://", FsPath.TRASH_ROOT, harness.s.active.path)
        assertEquals(setOf(trashedProjectPath), harness.s.active.selection)

        // Restore top-level OldProject back to original path
        harness.vm.restoreFromTrash()
        idle(harness)
        assertTrue("Restored nested notes.txt at original location", nestedNote.exists())
    }
}
