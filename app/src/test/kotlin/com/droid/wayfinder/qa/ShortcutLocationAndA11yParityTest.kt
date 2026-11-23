// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.R
import com.droid.wayfinder.data.AudioTrackInfo
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.KeyboardShortcuts
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.MediaInfo
import com.droid.wayfinder.data.RenamePlan
import com.droid.wayfinder.data.VideoTrackInfo
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Clipboard
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.FocusCoordinator
import com.droid.wayfinder.ui.LocalFocusCoordinator
import com.droid.wayfinder.ui.MenuController
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.MenuHost
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.backgroundMenu
import com.droid.wayfinder.ui.fileMenu
import com.droid.wayfinder.ui.theme.WayfinderTheme
import java.io.File
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
class ShortcutLocationAndA11yParityTest {
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

    private fun normalizeAndroidMetaState(modifiers: Int): Int {
        var meta = modifiers
        if ((meta and KeyEvent.META_CTRL_ON) != 0) meta = meta or KeyEvent.META_CTRL_LEFT_ON
        if ((meta and KeyEvent.META_SHIFT_ON) != 0) meta = meta or KeyEvent.META_SHIFT_LEFT_ON
        if ((meta and KeyEvent.META_ALT_ON) != 0) meta = meta or KeyEvent.META_ALT_LEFT_ON
        return meta
    }

    private fun dispatchShortcut(harness: VmHarness, keyCode: Int, modifiers: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val meta = normalizeAndroidMetaState(modifiers)
        var downHandled = false
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            downHandled = decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            decor.dispatchKeyEvent(KeyEvent(now, now + 5, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        idle(harness)
        return downHandled
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
    fun everyAdvertisedKeyboardShortcutFiresRealAppRootHandler() {
        val harness = VmHarness().also { h = it }
        harness.dir("Work/SubFolder")
        harness.file("Work/alpha.txt", "alpha")
        harness.file("Work/beta.txt", "beta")
        harness.file("Work/gamma.txt", "gamma")
        harness.dir("OtherPane")

        val focusCoord = FocusCoordinator()
        var focusManager: FocusManager? = null
        rule.setContent {
            focusManager = LocalFocusManager.current
            CompositionLocalProvider(LocalFocusCoordinator provides focusCoord) {
                WayfinderTheme(false) {
                    AppRoot(harness.vm)
                }
            }
        }
        idle(harness)

        val workPath = harness.pathOf("Work")
        val subPath = harness.pathOf("Work/SubFolder")
        val alphaPath = harness.pathOf("Work/alpha.txt")
        val betaPath = harness.pathOf("Work/beta.txt")
        val otherPath = harness.pathOf("OtherPane")

        val allEntries = KeyboardShortcuts.sections.flatMap { section ->
            section.entries.map { entry -> section.titleRes to entry }
        }
        assertTrue("Expected at least 45 advertised shortcuts", allEntries.size >= 45)

        for ((sectionTitleRes, entry) in allEntries) {
            val label = harness.app.getString(entry.labelRes)
            // Reset clean baseline before each shortcut
            harness.vm.dismissDialog()
            harness.vm.closeQuickLook()
            harness.vm.closePalette()
            harness.vm.setAddressEditing(false)
            harness.vm.setTextFocus(false)
            rule.runOnUiThread {
                focusManager?.clearFocus(force = true)
                focusCoord.focusFileList()
            }
            if (harness.s.dualPane) harness.vm.toggleDualPane()
            while (harness.s.tabs.size > 1) harness.vm.closeTab(harness.s.tabs.last().id)
            if (!File(alphaPath).exists()) harness.file("Work/alpha.txt", "alpha")
            if (!File(betaPath).exists()) harness.file("Work/beta.txt", "beta")
            harness.navigate(workPath)
            harness.vm.setViewMode(ViewMode.Details)
            harness.vm.zoomBy(0)
            harness.events.clear()
            idle(harness)

            // Prepare precondition specific to stateful shortcuts
            when (entry.labelRes) {
                R.string.shortcut_close_tab,
                R.string.shortcut_next_tab,
                R.string.shortcut_previous_tab,
                R.string.shortcut_go_to_tab,
                -> {
                    harness.vm.newTab(otherPath, activate = true)
                    idle(harness)
                }
                R.string.shortcut_reopen_closed_tab -> {
                    harness.vm.newTab(otherPath, activate = true)
                    idle(harness)
                    harness.vm.closeTab(harness.s.activeId)
                    idle(harness)
                }
                R.string.shortcut_open_folder_new_tab -> {
                    harness.vm.setSelection(setOf(subPath))
                    idle(harness)
                }
                R.string.shortcut_back -> {
                    harness.navigate(subPath)
                    idle(harness)
                }
                R.string.shortcut_forward -> {
                    harness.navigate(subPath)
                    idle(harness)
                    harness.vm.back()
                    idle(harness)
                }
                R.string.shortcut_open -> {
                    harness.vm.setSelection(setOf(subPath))
                    idle(harness)
                }
                R.string.shortcut_quick_look,
                R.string.shortcut_cut,
                R.string.shortcut_copy,
                R.string.shortcut_duplicate,
                R.string.shortcut_rename,
                R.string.shortcut_batch_rename,
                R.string.shortcut_copy_path,
                R.string.shortcut_delete,
                R.string.shortcut_delete_permanently,
                R.string.shortcut_properties,
                R.string.shortcut_invert_selection,
                R.string.shortcut_clear_selection,
                -> {
                    harness.vm.click(alphaPath, ctrl = false, shift = false)
                    idle(harness)
                }
                R.string.shortcut_toggle_item -> {
                    harness.vm.click(alphaPath, ctrl = false, shift = false)
                    harness.vm.clearSelection()
                    idle(harness)
                }
                R.string.shortcut_paste -> {
                    harness.vm.setSelection(setOf(alphaPath))
                    harness.vm.copy()
                    harness.navigate(otherPath)
                    File(otherPath, "alpha.txt").delete()
                    idle(harness)
                }
                R.string.shortcut_undo -> {
                    val temp = harness.file("Work/undo_me.txt", "u")
                    harness.vm.setSelection(setOf(temp.path))
                    harness.vm.requestDelete(permanent = false)
                    idle(harness)
                }
                R.string.shortcut_redo -> {
                    val temp = harness.file("Work/redo_me.txt", "r")
                    harness.vm.setSelection(setOf(temp.path))
                    harness.vm.requestDelete(permanent = false)
                    idle(harness)
                    harness.vm.undo()
                    idle(harness)
                }
                R.string.shortcut_view_details -> {
                    harness.vm.setViewMode(ViewMode.Grid)
                    idle(harness)
                }
                R.string.shortcut_zoom_reset -> {
                    harness.vm.zoomBy(2)
                    idle(harness)
                }
                R.string.shortcut_refresh -> {
                    // Create a file directly on disk without telling VM so Refresh discovers it
                    File(workPath, "disk_fresh_${entry.keyCode}.txt").writeText("fresh")
                }
            }
            if (entry.keyCode == KeyEvent.KEYCODE_F5 && sectionTitleRes == R.string.shortcut_group_navigate) {
                File(workPath, "disk_fresh_f5.txt").writeText("fresh")
            }

            if (sectionTitleRes == R.string.shortcut_group_dual_pane && entry.labelRes != R.string.shortcut_toggle_dual_pane) {
                harness.vm.toggleDualPane()
                idle(harness)
                harness.vm.activatePane(right = true)
                harness.vm.navigate(otherPath)
                idle(harness)
                harness.vm.activatePane(right = false)
                File(otherPath, "alpha.txt").delete()
                harness.vm.click(alphaPath, ctrl = false, shift = false)
                idle(harness)
            }

            val beforeState = harness.s
            val beforeEvents = harness.events.toList()

            val handled = dispatchShortcut(harness, entry.keyCode, entry.modifiers)
            val afterState = harness.s
            val afterEvents = harness.events.toList()

            assertTrue(
                "Shortcut '$label' (keyCode=${entry.keyCode}, modifiers=${entry.modifiers}) was neither handled nor produced any state/event change",
                handled && (afterState != beforeState || afterEvents.size > beforeEvents.size),
            )

            // Clean up side-effect files created during edit/refresh checks
            File(workPath).listFiles()?.filter { it.name.startsWith("alpha copy") || it.name.startsWith("disk_fresh_") }?.forEach { it.delete() }
        }
    }

    @Test
    fun trashedSubfoldersArchivesCollectionsAndNetworkEnforceLocationParity() {
        val harness = VmHarness().also { h = it }
        val projectDir = harness.dir("OldProject/nested")
        val nestedNote = harness.file("OldProject/nested/notes.txt", "keep safe")
        val nestedZip = File(projectDir, "inner.zip")
        createZipArchive(nestedZip, mapOf("doc.txt" to "zip-doc"))
        val topFolder = harness.pathOf("OldProject")
        val siblingFolder = harness.dir("Destination")

        // Move OldProject to Trash
        harness.navigate(harness.root.path)
        harness.vm.setSelection(setOf(topFolder))
        harness.vm.requestDelete(permanent = false)
        idle(harness)
        assertFalse("OldProject moved to Trash", File(topFolder).exists())

        // 1. At Trash root (trash://): Restore is enabled on top-level trashed folder; Open enters it
        harness.navigate(FsPath.TRASH_ROOT)
        val trashedProjectEntry = harness.s.active.entries.single { it.name == "OldProject" }
        assertTrue(harness.vm.isInTrash(trashedProjectEntry.path))
        assertTrue(harness.vm.canRestoreFromTrash(trashedProjectEntry.path))

        harness.vm.setSelection(setOf(trashedProjectEntry.path))
        val rootTrashMenu = fileMenu(harness.vm, harness.s, trashedProjectEntry).filterIsInstance<MenuEntry.Item>()
        assertTrue("Top-level trashed folder menu must offer Open", rootTrashMenu.any { it.label == "Open" && it.enabled })
        assertTrue("Top-level trashed folder menu must enable Restore", rootTrashMenu.single { it.label == "Restore" }.enabled)
        assertTrue("Top-level trashed folder menu must enable Delete permanently", rootTrashMenu.single { it.label == "Delete permanently" }.enabled)
        assertEquals(listOf("Empty Trash", "Refresh"), backgroundMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>().map { it.label })

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

        // Breadcrumbs inside trashed subfolder hide internal .Wayfinder-Trash/<slot> directory
        val crumbs = harness.vm.trashBreadcrumbs(trashedNestedPath)
        assertNotNull(crumbs)
        assertEquals(listOf("Trash", "OldProject", "nested"), crumbs!!.map { it.first })
        assertEquals(FsPath.TRASH_ROOT, crumbs.first().second)

        // 3. Context menus inside trashed subfolder forbid mutations and soft-Delete
        val trashedNoteEntry = harness.s.active.entries.single { it.name == "notes.txt" }
        val trashedZipEntry = harness.s.active.entries.single { it.name == "inner.zip" }
        harness.vm.setSelection(setOf(trashedNoteEntry.path))
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
        assertEquals(
            listOf("Trash", "OldProject", "nested"),
            FsPath.breadcrumbs(trashedNestedPath, harness.s.rootLabels).map { it.first },
        )

        // Dragging an item out of Trash onto a normal folder copies rather than moving (preserving .origin slot & source)
        harness.vm.dropOn(siblingFolder.path, listOf(trashedNoteEntry.path), copy = false)
        idle(harness)
        assertTrue("Drop out of Trash copies file to destination", File(siblingFolder, "notes.txt").exists())
        assertTrue("Original file inside trashed folder remains untouched", File(trashedNoteEntry.path).exists())

        // Symlink ancestor inside a trashed folder must never allow deletePermanently to traverse outside Trash
        val protectedOutsideDir = harness.dir("ProtectedOutside")
        val protectedVictim = harness.file("ProtectedOutside/keep.txt", "precious")
        val symlinkInsideTrash = File(trashedNestedPath, "evil_link")
        java.nio.file.Files.createSymbolicLink(symlinkInsideTrash.toPath(), protectedOutsideDir.toPath())
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

        // 6. Read-only .zip archive subfolder & virtual collection parity
        val realZip = File(siblingFolder, "archive.zip")
        createZipArchive(realZip, mapOf("sub/inside.txt" to "zip-content"))
        harness.navigate(FsPath.zipRoot(realZip.path) + "/sub")
        val zipChild = harness.s.active.entries.single { it.name == "inside.txt" }
        harness.vm.setSelection(setOf(zipChild.path))
        val zipMenuItems = fileMenu(harness.vm, harness.s, zipChild).filterIsInstance<MenuEntry.Item>()
        assertFalse("Cut disabled inside .zip", zipMenuItems.single { it.label == harness.app.getString(R.string.action_cut) }.enabled)
        assertFalse("Rename disabled inside .zip", zipMenuItems.single { it.label == harness.app.getString(R.string.action_rename) }.enabled)
        assertFalse("Compress disabled inside .zip", zipMenuItems.single { it.label == harness.app.getString(R.string.action_compress_to_zip) }.enabled)
        assertFalse("Delete disabled inside .zip", zipMenuItems.single { it.label == harness.app.getString(R.string.action_delete) }.enabled)
        val zipBgItems = backgroundMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>()
        assertFalse("New folder disabled inside .zip", zipBgItems.single { it.label == "New folder" }.enabled)
        assertFalse("Paste disabled inside .zip", zipBgItems.single { it.label == "Paste" }.enabled)

        // Collection views (Starred & Recents) and SMB_ROOT
        SharedUi.updateMarks { Marks(starred = setOf(outsideFile.path)) }
        for (collectionRoot in listOf(FsPath.STARRED_ROOT, FsPath.RECENTS_ROOT)) {
            harness.navigate(collectionRoot)
            val bgLabels = backgroundMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>().map { it.label }
            assertEquals(listOf("Select all", "Refresh", "Open in new window"), bgLabels)
            harness.vm.requestNewItem(folder = true)
            assertNull("New folder blocked in $collectionRoot", harness.s.dialog)
        }
        val smbState = harness.s.copy(tabs = harness.s.tabs.map { if (it.id == harness.s.activeId) it.copy(path = FsPath.SMB_ROOT) else it })
        assertEquals(
            listOf("Connect to server…", "Rescan network"),
            backgroundMenu(harness.vm, smbState).filterIsInstance<MenuEntry.Item>().map { it.label },
        )
    }

    @Test
    fun liveSemanticsTreesHaveNonEmptyAccessibleLabelsWithoutShortcutClutter() {
        val harness = VmHarness().also { h = it }
        val folder = harness.dir("A11yFolder")
        val txt = harness.file("A11yFolder/sample.txt", "accessible text")
        val vid = harness.file("A11yFolder/movie.mp4", "video")
        harness.vm.pin(folder.path)
        harness.navigate(folder.path)
        harness.vm.click(txt.path, ctrl = false, shift = false)
        if (!harness.s.previewVisible) harness.vm.togglePreview()
        harness.settle()

        val menuController = MenuController()
        rule.setContent {
            WayfinderTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(harness.vm)
                    MenuHost(menuController)
                }
            }
        }
        idle(harness)

        val shortcutClutterRegex = Regex("""\((?:Ctrl|Alt|Shift|Meta|Del|Esc|Enter|Space|Tab|F(?:[1-9]|1[0-2]))\b[^)]*\)""", RegexOption.IGNORE_CASE)

        fun assertSemanticsClean(surfaceLabel: String) {
            val allNodes = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            val clutterViolations = mutableListOf<String>()
            val unlabeledViolations = mutableListOf<String>()

            for (node in allNodes) {
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                for (desc in descriptions) {
                    if (shortcutClutterRegex.containsMatchIn(desc)) {
                        clutterViolations += "$surfaceLabel: contentDescription '$desc' embeds raw keyboard shortcut hint"
                    }
                }

                val bounds = node.boundsInRoot
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                val role = node.config.getOrNull(SemanticsProperties.Role)
                val hasClick = SemanticsActions.OnClick in node.config
                if (hasClick && role in setOf(Role.Button, Role.Tab, Role.Checkbox, Role.Switch, Role.RadioButton)) {
                    val ownOrChildText = buildList {
                        addAll(descriptions)
                        node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
                        node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
                        node.config.getOrNull(SemanticsActions.OnClick)?.label?.let { add(it) }
                        for (child in node.children) {
                            child.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
                            child.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
                        }
                    }.any { it.isNotBlank() }
                    if (!ownOrChildText) {
                        unlabeledViolations += "$surfaceLabel: clickable $role node#${node.id} at $bounds has no accessible label"
                    }
                }
            }

            assertTrue(
                "Shortcut parentheticals leaked into contentDescription:\n" + clutterViolations.joinToString("\n"),
                clutterViolations.isEmpty(),
            )
            assertTrue(
                "Unlabeled interactive controls found:\n" + unlabeledViolations.joinToString("\n"),
                unlabeledViolations.isEmpty(),
            )
        }

        for (mode in ViewMode.entries) {
            harness.vm.setViewMode(mode)
            idle(harness)
            assertSemanticsClean("Browser($mode)")
        }

        harness.vm.setViewMode(ViewMode.Details)
        harness.vm.toggleDualPane()
        idle(harness)
        assertSemanticsClean("Browser(DualPane)")
        harness.vm.toggleDualPane()
        idle(harness)

        // Open QuickLook
        harness.vm.toggleQuickLook()
        idle(harness)
        assertSemanticsClean("QuickLook")
        harness.vm.closeQuickLook()
        idle(harness)

        // Open Properties with MediaInfo
        val vidEntry = harness.s.active.entries.single { it.path == vid.path }
        harness.vm.showDialog(
            Dialog.Properties(
                entries = listOf(vidEntry),
                bytes = vidEntry.size,
                mediaInfo = MediaInfo(
                    container = "MP4",
                    durationUs = 10_000_000L,
                    videoTracks = listOf(VideoTrackInfo(codec = "AV1", width = 1920, height = 1080)),
                    audioTracks = listOf(AudioTrackInfo(codec = "Opus", channelCount = 2, sampleRateHz = 48_000)),
                ),
            ),
        )
        idle(harness)
        assertSemanticsClean("Dialog.Properties")
        harness.vm.dismissDialog()
        idle(harness)

        // Open ShortcutsDialog & MenuHost
        harness.vm.showDialog(Dialog.Shortcuts)
        idle(harness)
        assertSemanticsClean("Dialog.Shortcuts")
        harness.vm.dismissDialog()
        idle(harness)

        menuController.show(Offset(180f, 180f), fileMenu(harness.vm, harness.s, vidEntry))
        idle(harness)
        assertSemanticsClean("MenuHost(fileMenu)")
        menuController.dismiss()
        idle(harness)
    }
}
