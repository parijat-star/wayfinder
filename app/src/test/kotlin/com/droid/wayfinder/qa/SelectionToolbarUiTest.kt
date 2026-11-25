// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Clipboard
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.Toolbar
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Headless Robolectric + Compose UI tests for contextual selection actions:
 * - Wide toolbars (`> 960.dp`): icon-only actions replace the 5 view-mode buttons in-place with zero layout shift.
 * - Medium & compact toolbars (`<= 960.dp`): a compact contextual selection bar renders below the toolbar row
 *   so the Address bar is never crushed and touch multi-selection remains directly actionable.
 * - Trash & trashed subfolders: delegates to `TrashManager.isInTrash`, guards compression, and surfaces
 *   Restore + Delete permanently instead of disabled Cut/Copy/Compress/Share buttons.
 * - Accessibility & localization: `Icon` `contentDescription` uses plain localized labels without shortcut hints.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class SelectionToolbarUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private val viewButtonDescs = listOf(
        "Details view",
        "List view",
        "Grid view",
        "Columns view",
        "Dual pane",
    )

    private val actionButtonDescs = listOf(
        "Cut",
        "Copy",
        "Compress to ZIP",
        "Share",
        "Delete",
    )

    private fun launch(dark: Boolean = false, setup: VmHarness.() -> Unit = {}) {
        h = VmHarness()
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(dark) { AppRoot(h.vm) } }
        idle()
    }

    private fun idle() {
        rule.waitForIdle()
        h.settle()
        rule.waitForIdle()
    }

    private fun assertViewButtonsVisible() {
        viewButtonDescs.forEach { desc ->
            rule.onNodeWithContentDescription(desc).assertIsDisplayed()
        }
        actionButtonDescs.forEach { desc ->
            rule.onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
    }

    private fun assertActionButtonsVisibleAndIconOnly() {
        viewButtonDescs.forEach { desc ->
            rule.onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
        actionButtonDescs.forEach { desc ->
            rule.onNodeWithContentDescription(desc)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        // Shortcut hints belong in ActionTooltip, never concatenated into Icon contentDescription.
        listOf("Cut (Ctrl+X)", "Copy (Ctrl+C)", "Delete (Del)", "Delete permanently (Del)").forEach { badDesc ->
            rule.onAllNodesWithContentDescription(badDesc).assertCountEquals(0)
        }
        // Icon-only: no visible action label text in the toolbar.
        listOf("Cut", "Copy", "Compress to ZIP", "Share", "Delete").forEach { label ->
            rule.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun selectingFilesReplacesFiveViewButtonsWithoutLayoutShiftAndClearingRestoresThem() {
        launch {
            file("alpha.txt", "a")
            file("beta.txt", "b")
            navigate(root.path)
        }

        assertViewButtonsVisible()
        val addressBoundsBefore = rule.onNodeWithContentDescription("Address bar").getUnclippedBoundsInRoot()
        val moreBoundsBefore = rule.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()

        // Selecting a single file swaps the 5 view buttons for the 5 icon-only action buttons
        rule.onAllNodesWithText("alpha.txt")[0].performMouseInput { click() }
        idle()

        assertActionButtonsVisibleAndIconOnly()
        actionButtonDescs.forEach { desc ->
            rule.onNodeWithContentDescription(desc).assertIsEnabled()
        }

        // Exact same 5-button footprint keeps neighboring toolbar controls from shifting
        val addressBoundsAfter = rule.onNodeWithContentDescription("Address bar").getUnclippedBoundsInRoot()
        val moreBoundsAfter = rule.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
        assertEquals(addressBoundsBefore.left.value, addressBoundsAfter.left.value, 1f)
        assertEquals(addressBoundsBefore.right.value, addressBoundsAfter.right.value, 1f)
        assertEquals(moreBoundsBefore.left.value, moreBoundsAfter.left.value, 1f)

        // Selecting multiple files keeps the 5 action buttons displayed
        h.select("alpha.txt", "beta.txt")
        idle()
        assertActionButtonsVisibleAndIconOnly()

        // Clearing selection restores the 5 view buttons
        h.vm.clearSelection()
        idle()
        assertViewButtonsVisible()
    }

    @Test
    fun clickingEachSelectionToolbarButtonTriggersExpectedFileAction() {
        launch {
            file("one.txt", "hello")
            file("two.txt", "world")
            navigate(root.path)
        }
        val onePath = h.pathOf("one.txt")
        val twoPath = h.pathOf("two.txt")
        h.select("one.txt", "two.txt")
        idle()

        // 1. Cut
        rule.onNodeWithContentDescription("Cut").performClick()
        idle()
        assertEquals(Clipboard(listOf(onePath, twoPath), cut = true), h.s.clipboard)

        // 2. Copy
        rule.onNodeWithContentDescription("Copy").performClick()
        idle()
        assertEquals(Clipboard(listOf(onePath, twoPath), cut = false), h.s.clipboard)

        // 3. Compress to ZIP
        rule.onNodeWithContentDescription("Compress to ZIP").performClick()
        idle()
        val compressDialog = h.s.dialog as? Dialog.Compress
        assertTrue("Expected Compress dialog to open", compressDialog != null)
        assertEquals(listOf(onePath, twoPath), compressDialog!!.sources)
        h.vm.dismissDialog()
        idle()

        // 4. Share
        h.events.clear()
        rule.onNodeWithContentDescription("Share").performClick()
        idle()
        val shareEvent = h.events.filterIsInstance<UiEvent.Share>().lastOrNull()
        assertTrue("Expected UiEvent.Share to be emitted", shareEvent != null)
        assertEquals(listOf(onePath, twoPath), shareEvent!!.paths)

        // 5. Delete (moves to Trash and restores view buttons once selection clears on refresh)
        rule.onNodeWithContentDescription("Delete").performClick()
        idle()
        assertFalse(File(onePath).exists())
        assertFalse(File(twoPath).exists())
        assertViewButtonsVisible()
    }

    @Test
    fun folderOnlySelectionDisablesShareWhileEnablingOtherFourActions() {
        launch {
            dir("Projects")
            navigate(root.path)
        }
        h.select("Projects")
        idle()

        rule.onNodeWithContentDescription("Cut").assertIsEnabled()
        rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        rule.onNodeWithContentDescription("Compress to ZIP").assertIsEnabled()
        rule.onNodeWithContentDescription("Share").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Delete").assertIsEnabled()
    }

    @Test
    fun trashRootSurfacesRestoreAndDeletePermanentlyWithoutLayoutShift() {
        launch {
            file("restore_me.txt", "keep")
            file("delete_me.txt", "bye")
            navigate(root.path)
            select("restore_me.txt", "delete_me.txt")
            vm.requestDelete()
            settle()
        }
        val restoreOriginalPath = h.pathOf("restore_me.txt")
        assertFalse(File(restoreOriginalPath).exists())

        h.navigate(FsPath.TRASH_ROOT)
        idle()
        val addressBoundsBefore = rule.onNodeWithContentDescription("Address bar").getUnclippedBoundsInRoot()
        val moreBoundsBefore = rule.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()

        val restoreEntry = h.s.active.visible(h.s.showHidden).first { it.name == "restore_me.txt" }
        h.vm.setSelection(setOf(restoreEntry.path))
        idle()

        // Non-Trash actions are omitted in Trash rather than shown as 4 disabled icons
        actionButtonDescs.forEach { desc ->
            rule.onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
        rule.onNodeWithContentDescription("Restore")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Delete permanently")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Neighboring toolbar controls stay fixed (exact 244dp slot width preserved)
        val addressBoundsAfter = rule.onNodeWithContentDescription("Address bar").getUnclippedBoundsInRoot()
        val moreBoundsAfter = rule.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
        assertEquals(addressBoundsBefore.left.value, addressBoundsAfter.left.value, 1f)
        assertEquals(addressBoundsBefore.right.value, addressBoundsAfter.right.value, 1f)
        assertEquals(moreBoundsBefore.left.value, moreBoundsAfter.left.value, 1f)

        // Clicking Restore brings the item back to its original location
        rule.onNodeWithContentDescription("Restore").performClick()
        idle()
        assertTrue("Expected Restore button to restore trashed file", File(restoreOriginalPath).exists())

        // Clicking Delete permanently opens permanent delete confirmation
        val deleteEntry = h.s.active.visible(h.s.showHidden).first { it.name == "delete_me.txt" }
        h.vm.setSelection(setOf(deleteEntry.path))
        idle()
        rule.onNodeWithContentDescription("Delete permanently").performClick()
        idle()
        assertTrue("Expected permanent delete confirmation dialog in Trash", h.s.dialog is Dialog.Delete)
        h.vm.dismissDialog()
        idle()
    }

    @Test
    fun navigatingInsideTrashedSubfolderKeepsTrashModeAndBlocksCompress() {
        launch {
            dir("OldFolder")
            file("OldFolder/nested.txt", "inside trashed folder")
            navigate(root.path)
            select("OldFolder")
            vm.requestDelete()
            settle()
        }

        h.navigate(FsPath.TRASH_ROOT)
        idle()
        val trashedFolder = h.s.active.visible(h.s.showHidden).first { it.name == "OldFolder" }
        assertTrue("Expected trashed folder backing path under .Wayfinder-Trash", h.vm.isInTrash(trashedFolder.path))

        // Open the trashed folder (tab.path is now the backing /.../.Wayfinder-Trash/<slot>/OldFolder path)
        h.navigate(trashedFolder.path)
        idle()
        val nestedEntry = h.s.active.visible(h.s.showHidden).first { it.name == "nested.txt" }
        h.vm.setSelection(setOf(nestedEntry.path))
        idle()

        // Must stay in Trash mode: Cut/Copy/Compress/Share/Restore absent, Delete permanently active
        actionButtonDescs.forEach { desc ->
            rule.onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
        rule.onAllNodesWithContentDescription("Restore").assertCountEquals(0)
        rule.onNodeWithContentDescription("Delete permanently")
            .assertIsDisplayed()
            .assertIsEnabled()

        // Guarding BrowserViewModel.requestCompress() prevents writing a .zip into .Wayfinder-Trash/
        h.vm.requestCompress()
        idle()
        assertNull("requestCompress() must be blocked inside a trashed subfolder", h.s.dialog)

        // Clicking Delete permanently opens permanent delete dialog
        rule.onNodeWithContentDescription("Delete permanently").performClick()
        idle()
        assertTrue("Expected permanent delete confirmation dialog in trashed subfolder", h.s.dialog is Dialog.Delete)
        h.vm.dismissDialog()
        idle()
    }

    @Test
    fun zipAndStarredRespectActionEnablement() {
        launch {
            val starred = file("fav.txt", "keep")
            val zipFile = File(root, "pack.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("inside.txt"))
                zos.write("zipped".toByteArray())
                zos.closeEntry()
            }
            navigate(root.path)
            vm.toggleStar(listOf(starred.path))
            settle()
        }

        // Inside a ZIP archive: only Copy is enabled
        val zipRoot = FsPath.zipRoot(h.pathOf("pack.zip"))
        h.navigate(zipRoot)
        val zipEntry = h.s.active.visible(h.s.showHidden).first { it.name == "inside.txt" }
        h.vm.setSelection(setOf(zipEntry.path))
        idle()

        rule.onNodeWithContentDescription("Cut").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        rule.onNodeWithContentDescription("Compress to ZIP").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Delete").assertIsNotEnabled()

        // Inside Starred collection: Compress is disabled while Cut, Copy, Share, Delete are enabled
        h.navigate(FsPath.STARRED_ROOT)
        val starredEntry = h.s.active.visible(h.s.showHidden).first { it.name == "fav.txt" }
        h.vm.setSelection(setOf(starredEntry.path))
        idle()

        rule.onNodeWithContentDescription("Cut").assertIsEnabled()
        rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        rule.onNodeWithContentDescription("Compress to ZIP").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share").assertIsEnabled()
        rule.onNodeWithContentDescription("Delete").assertIsEnabled()
    }

    @Test
    fun overflowMenuExposesViewSwitchingWhileSelectionActionsReplaceViewToggle() {
        launch {
            file("sample.txt", "content")
            navigate(root.path)
        }
        h.select("sample.txt")
        idle()

        assertActionButtonsVisibleAndIconOnly()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Grid view").performScrollTo().performClick()
        idle()
        assertEquals(ViewMode.Grid, h.s.active.viewMode)
    }

    @Test
    fun landscapePhoneAndMediumWindowsReplaceToolbarInPlaceWithoutVerticalShift() {
        h = VmHarness()
        h.dir("SubFolder")
        h.file("first.txt", "1")
        h.file("second.txt", "2")
        h.navigate(h.root.path)
        h.settle()

        val widthState = mutableStateOf(890.dp)
        rule.setContent {
            WayfinderTheme(dark = false) {
                Box(Modifier.size(width = widthState.value, height = 640.dp)) {
                    AppRoot(h.vm)
                }
            }
        }
        idle()

        // Unselected at 890dp: ViewToggle is hidden and Address bar stays wide & readable
        viewButtonDescs.forEach { desc ->
            rule.onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(180.dp)
            .assertHeightIsAtLeast(48.dp)

        // Capture vertical top position of the first row before selecting
        val rowTopBefore = rule.onNodeWithText("first.txt").getUnclippedBoundsInRoot().top

        // Mouse single-click selects the item and replaces the toolbar row IN-PLACE (zero vertical shift!)
        rule.onNodeWithText("first.txt").performMouseInput { click() }
        idle()

        val rowTopAfter = rule.onNodeWithText("first.txt").getUnclippedBoundsInRoot().top
        assertEquals("Selecting on <= 960dp must not push content down", rowTopBefore.value, rowTopAfter.value, 1f)

        // Main navigation controls are replaced by the contextual selection bar
        rule.onAllNodesWithContentDescription("Address bar").assertCountEquals(0)
        rule.onNodeWithContentDescription("Clear selection")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        actionButtonDescs.forEach { desc ->
            rule.onNodeWithContentDescription(desc)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithContentDescription("More")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Mouse right-click still opens the context menu on narrow resized desktop windows
        rule.onNodeWithText("first.txt").performMouseInput { rightClick() }
        idle()
        rule.onNodeWithText("Rename").assertIsDisplayed()

        // Dismiss context menu and clear selection -> normal toolbar restores without shifting content
        rule.onNodeWithContentDescription("Clear selection").performClick()
        idle()
        assertTrue(h.s.active.selection.isEmpty())
        rule.onAllNodesWithContentDescription("Clear selection").assertCountEquals(0)
        rule.onNodeWithContentDescription("Address bar").assertIsDisplayed().assertWidthIsAtLeast(180.dp)
        val rowTopRestored = rule.onNodeWithText("first.txt").getUnclippedBoundsInRoot().top
        assertEquals("Clearing selection must keep file list vertically stationary", rowTopBefore.value, rowTopRestored.value, 1f)

        // Double-clicking a folder on a narrow window opens it reliably without shifting under the cursor
        rule.onNodeWithText("SubFolder").performMouseInput { doubleClick() }
        idle()
        assertEquals(h.pathOf("SubFolder"), h.s.active.path)
    }

    @Test
    fun portraitPhone360dpLongPressEntersSelectionModeWithoutContextMenuAndMoreOpensFullMenu() {
        h = VmHarness()
        h.file("a.txt", "a")
        h.file("b.txt", "b")
        h.file("c.txt", "c")
        h.navigate(h.root.path)
        h.settle()

        rule.setContent {
            WayfinderTheme(dark = true) {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    AppRoot(h.vm)
                }
            }
        }
        idle()

        val rowTopBefore = rule.onNodeWithText("a.txt").getUnclippedBoundsInRoot().top

        // Touch long-press on a phone selects the file and shows the replacement selection bar,
        // WITHOUT popping up the floating context menu.
        rule.onNodeWithText("a.txt").performTouchInput { longClick() }
        idle()
        assertEquals(setOf(h.pathOf("a.txt")), h.s.active.selection)
        rule.onAllNodesWithText("Rename").assertCountEquals(0)
        rule.onAllNodesWithText("Properties").assertCountEquals(0)

        // Zero vertical shift when the contextual bar replaces the main toolbar row
        val rowTopAfter = rule.onNodeWithText("a.txt").getUnclippedBoundsInRoot().top
        assertEquals("Replacement bar on 360dp phone must not shift file rows", rowTopBefore.value, rowTopAfter.value, 1f)

        // Touch-tapping a second file adds it to the selection smoothly
        rule.onNodeWithText("b.txt").performTouchInput { click() }
        idle()
        assertEquals(setOf(h.pathOf("a.txt"), h.pathOf("b.txt")), h.s.active.selection)

        // Primary actions + Clear + More fit cleanly at 360dp with >= 48dp touch targets
        rule.onNodeWithContentDescription("Clear selection")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val countNode = rule.onNodeWithContentDescription("2 selected").assertIsDisplayed()
        assertTrue("Compact count should stay single-line", countNode.getUnclippedBoundsInRoot().height <= 40.dp)
        listOf("Cut", "Copy", "Share", "Delete", "More").forEach { desc ->
            rule.onNodeWithContentDescription(desc)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        // Tapping More (three-dot menu) opens the full context menu (including Compress to ZIP, Batch rename, Select all)
        rule.onNodeWithContentDescription("More").performClick()
        idle()
        rule.onNodeWithText("Compress to ZIP").assertIsDisplayed()
        rule.onNodeWithText("Batch rename 2 items…").assertIsDisplayed()
        rule.onNodeWithText("Select all").performScrollTo().performClick()
        idle()
        assertEquals(3, h.s.active.selection.size)

        // Copy works directly from the compact contextual bar
        rule.onNodeWithContentDescription("Copy").performClick()
        idle()
        assertEquals(3, h.s.clipboard?.paths?.size)

        // Delete moves to Trash; selecting in Trash on 360dp shows Restore + Delete permanently + More
        rule.onNodeWithContentDescription("Delete").performClick()
        idle()
        h.navigate(FsPath.TRASH_ROOT)
        idle()
        val trashed = h.s.active.visible(h.s.showHidden).first { it.name == "a.txt" }
        h.vm.setSelection(setOf(trashed.path))
        idle()

        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Restore")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Delete permanently")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("More")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun narrowWindowKeyboardShortcutsForAddressAndSearchTakePrecedenceOverSelectionBar() {
        h = VmHarness()
        h.file("alpha.txt", "a")
        h.file("beta.txt", "b")
        h.navigate(h.root.path)
        h.settle()

        rule.setContent {
            WayfinderTheme(dark = false) {
                Box(Modifier.size(width = 800.dp, height = 640.dp)) {
                    AppRoot(h.vm)
                }
            }
        }
        idle()

        // Select a file on an 800dp window -> CompactSelectionBar replaces toolbar
        rule.onNodeWithText("alpha.txt").performMouseInput { click() }
        idle()
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Address bar").assertCountEquals(0)

        // Ctrl+L reveals and focuses PathBar even while an item is selected
        rule.onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.L) }
        }
        idle()
        assertTrue(h.s.addressEditing)
        rule.onNodeWithContentDescription("Address bar").assertIsDisplayed().assertIsFocused()
        rule.onAllNodesWithContentDescription("Clear selection").assertCountEquals(0)

        // Pressing Esc exits address editing and returns to CompactSelectionBar
        rule.onNodeWithContentDescription("Address bar").performKeyInput { pressKey(Key.Escape) }
        idle()
        assertFalse(h.s.addressEditing)
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()

        // Ctrl+F reveals and focuses SearchField even while an item is selected
        rule.onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.F) }
        }
        idle()
        rule.onNodeWithContentDescription("Search files").assertIsDisplayed().assertIsFocused()
        rule.onAllNodesWithContentDescription("Clear selection").assertCountEquals(0)

        // Typing a query and pressing Down selects the first result while keeping SearchField visible
        rule.onNodeWithContentDescription("Search files").performTextInput("alpha")
        idle()
        rule.onNodeWithContentDescription("Search files").performKeyInput { pressKey(Key.DirectionDown) }
        idle()
        assertEquals("alpha", h.s.active.query)
        assertTrue("Down from SearchField should select a search result", h.s.active.selection.isNotEmpty())
        rule.onNodeWithContentDescription("Search files").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Clear selection").assertCountEquals(0)

        // Clearing search returns cleanly to CompactSelectionBar (focusSearch counter does not stay latched)
        rule.onNodeWithContentDescription("Clear search").performClick()
        idle()
        assertEquals("", h.s.active.query)
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()

        // Alt+D after a prior Ctrl+F still focuses PathBar cleanly without SearchField stealing focus
        rule.onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(Key.AltLeft) { pressKey(Key.D) }
        }
        idle()
        assertTrue(h.s.addressEditing)
        rule.onNodeWithContentDescription("Address bar").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun portraitPhoneSearchResultsKeepSearchBarAndLongPressOpensContextMenu() {
        h = VmHarness()
        h.file("alpha.txt", "a")
        h.file("beta.txt", "b")
        h.navigate(h.root.path)
        h.vm.setQuery("alpha")
        h.settle()

        rule.setContent {
            WayfinderTheme(dark = false) {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    AppRoot(h.vm)
                }
            }
        }
        idle()

        rule.onNodeWithContentDescription("Search files").assertIsDisplayed()
        rule.onNodeWithText("alpha.txt").performTouchInput { longClick() }
        idle()
        // While search is active on a phone, long-press still offers the context menu directly
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search files").assertIsDisplayed()
    }

    @Test
    fun darkThemeDisplaysAllFiveSelectionActionButtonsWhenSelected() {
        launch(dark = true) {
            file("night.txt", "moon")
            navigate(root.path)
        }
        h.select("night.txt")
        idle()
        assertActionButtonsVisibleAndIconOnly()
    }

    @Test
    fun dualPaneTracksActivePaneSelectionState() {
        launch {
            file("left/l.txt", "left")
            file("right/r.txt", "right")
            navigate(pathOf("left"))
        }
        h.vm.toggleDualPane()
        idle()
        h.vm.activatePane(true)
        h.vm.navigate(h.pathOf("right"))
        idle()
        h.vm.activatePane(false)
        idle()

        // Selecting a file in the active left pane shows the 5 action buttons
        h.select("left/l.txt")
        idle()
        assertActionButtonsVisibleAndIconOnly()

        // Switching focus to the right pane (empty selection) restores the 5 view buttons
        h.vm.activatePane(true)
        idle()
        assertViewButtonsVisible()

        // Switching back to the left pane restores the 5 action buttons
        h.vm.activatePane(false)
        idle()
        assertActionButtonsVisibleAndIconOnly()
    }
}
