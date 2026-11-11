// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Headless UI tests of the full AppRoot (title-bar tabs, file list, Quick Look, command palette) on a
 * large landscape laptop-like screen, driven with real mouse and keyboard input events.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class AppRootUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(dark: Boolean = false, setup: VmHarness.() -> Unit = {}) {
        h = VmHarness()
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(dark) { AppRoot(h.vm) } }
        idle()
    }

    /** Lets VM background work land on the main thread and Compose recompose. */
    private fun idle() { rule.waitForIdle(); h.settle(); rule.waitForIdle() }

    private fun root(): SemanticsNodeInteraction = rule.onNode(isFocused()).let { n ->
        // AppRoot's key handler lives on the focused root box.
        runCatching { n.assertExists() }.fold({ n }, { rule.onAllNodes(androidx.compose.ui.test.isRoot())[0] })
    }

    private fun row(name: String) = rule.onAllNodesWithText(name).onFirst()

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]

    @Test fun tabsAreDrawnInTheTitleBarAndNewTabButtonWorks() {
        launch { dir("Alpha"); dir("Beta"); vm.newTab(pathOf("Alpha")); vm.newTab(pathOf("Beta")) }
        rule.onAllNodesWithText("Alpha")[0].assertIsDisplayed()
        rule.onAllNodesWithText("Beta")[0].assertIsDisplayed()
        rule.onNodeWithContentDescription("New tab (Ctrl+T)").performClick()
        idle()
        assertEquals(4, h.s.tabs.size)
        // Click a tab to activate it, middle-click to close it.
        rule.onAllNodesWithText("Alpha")[0].performMouseInput { click() }
        idle()
        assertEquals(h.pathOf("Alpha"), h.s.active.path)
        rule.onAllNodesWithText("Alpha")[0].performMouseInput { press(MouseButton.Tertiary); release(MouseButton.Tertiary) }
        idle()
        assertEquals(3, h.s.tabs.size)
        assertTrue(h.s.tabs.none { it.path == h.pathOf("Alpha") })
    }

    @Test fun singleClickSelectsDoubleClickOpens() {
        launch { file("Docs/readme.txt"); file("notes.txt"); navigate(root.path) }
        row("notes.txt").performMouseInput { click() }
        idle()
        assertEquals(setOf(h.pathOf("notes.txt")), h.s.active.selection)
        assertEquals("single click must not navigate", h.root.path, h.s.active.path)
        row("Docs").performMouseInput { doubleClick() }
        idle()
        assertEquals(h.pathOf("Docs"), h.s.active.path)
        rule.onNodeWithText("readme.txt").assertIsDisplayed()
    }

    @Test fun rightClickShowsContextMenuAndRenameOpensDialog() {
        launch { file("a.txt"); file("b.txt"); navigate(root.path) }
        row("b.txt").performMouseInput { rightClick() }
        idle()
        assertEquals("right-click selects the item", setOf(h.pathOf("b.txt")), h.s.active.selection)
        rule.onNodeWithText("Open with…").assertIsDisplayed()
        rule.onNodeWithText("Copy path").assertIsDisplayed()
        rule.onNodeWithText("Rename").performClick()
        idle()
        assertEquals(Dialog.Rename(h.pathOf("b.txt"), "b.txt"), h.s.dialog)
    }

    @Test fun rightClickOnBackgroundShowsFolderMenu() {
        launch { file("only.txt"); navigate(root.path) }
        // Bottom of the list area: empty background below the single row.
        rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].performMouseInput { rightClick(androidx.compose.ui.geometry.Offset(width * 0.6f, height * 0.85f)) }
        idle()
        assertTrue(rule.onAllNodesWithText("New folder").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun middleClickOnFolderOpensBackgroundTab() {
        launch { dir("Tunes"); navigate(root.path) }
        row("Tunes").performMouseInput { click(button = MouseButton.Tertiary) }
        idle()
        assertEquals(2, h.s.tabs.size)
        assertEquals(h.pathOf("Tunes"), h.s.tabs.last().path)
        assertEquals(h.root.path, h.s.active.path)
    }

    @Test fun spaceOpensQuickLookAndEscapeCloses() {
        launch { file("story.txt", "Once upon a time"); navigate(root.path) }
        row("story.txt").performMouseInput { click() }
        idle()
        root().performKeyInput { pressKey(Key.Spacebar) }
        idle()
        assertEquals(h.pathOf("story.txt"), h.s.quickLook)
        rule.onNodeWithContentDescription("Close (Space)").assertIsDisplayed()
        rule.onAllNodesWithText("Once upon a time", substring = true)[0].assertIsDisplayed()
        root().performKeyInput { pressKey(Key.Escape) }
        idle()
        assertNull(h.s.quickLook)
    }

    @Test fun commandPaletteFiltersAndRunsCommand() {
        launch { navigate(root.path) }
        root().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        idle()
        assertTrue(h.s.paletteOpen)
        rule.onNodeWithText("Type a command or jump to folder…").assertIsDisplayed()
        rule.onNode(isFocused()).performTextInput("dual")
        idle()
        rule.onNodeWithText("Dual pane").assertIsDisplayed()
        assertTrue("non-matching commands are filtered out", rule.onAllNodesWithText("Empty Trash").fetchSemanticsNodes().isEmpty())
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle()
        assertTrue(h.s.dualPane)
        assertTrue(!h.s.paletteOpen)
    }

    @Test fun commandPaletteJumpsToFolderAndEscapeCloses() {
        launch { dir("Projects"); navigate(root.path) }
        root().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        idle()
        rule.onNode(isFocused()).performTextInput("projects")
        idle()
        rule.onAllNodesWithText("In this folder", substring = true)[0].performClick()
        idle()
        assertEquals(h.pathOf("Projects"), h.s.active.path)
        root().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        idle()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
        idle()
        assertTrue(!h.s.paletteOpen)
    }

    @Test fun darkThemeAndZoomRender() {
        launch(dark = true) { file("z.txt"); navigate(root.path) }
        h.vm.zoomBy(3); idle()
        assertEquals(1.3f, h.s.zoom, 0.001f)
        row("z.txt").assertIsDisplayed()
        h.vm.cycleTheme(); idle()
        assertEquals(ThemeMode.Light, h.s.theme)
    }

    @Test fun dualPaneRendersBothPanes() {
        launch { file("left/l.txt"); file("right/r.txt"); navigate(pathOf("left")) }
        h.vm.toggleDualPane(); idle()
        h.vm.activatePane(true); h.vm.navigate(h.pathOf("right")); idle()
        rule.onNodeWithText("l.txt").assertIsDisplayed()
        rule.onNodeWithText("r.txt").assertIsDisplayed()
        row("l.txt").performMouseInput { click() }
        idle()
        assertTrue("clicking in the left pane focuses it", !h.s.rightActive)
    }

    @Test fun f1ShowsShortcutsDialog() {
        launch { navigate(root.path) }
        root().performKeyInput { pressKey(Key.F1) }
        idle()
        assertEquals(Dialog.Shortcuts, h.s.dialog)
        rule.onNodeWithText("Keyboard & trackpad shortcuts").assertIsDisplayed()
        rule.onNodeWithText("Ctrl+K").assertExists()
    }

    @Test fun errorViewForMissingFolder() {
        launch { navigate(pathOf("does-not-exist")) }
        rule.onAllNodesWithText("This folder no longer exists", substring = true)[0].assertIsDisplayed()
    }

    @Test fun emptyFolderShowsPlaceholderAndStatusBarCounts() {
        launch { dir("void"); navigate(pathOf("void")) }
        assertTrue(rule.onAllNodesWithText("No files in this folder").fetchSemanticsNodes().isNotEmpty())
        h.file("void/a.txt"); h.file("void/b.txt"); h.vm.refresh(); idle()
        assertTrue(rule.onAllNodesWithText("2 items", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertNotNull(h.s.active.entries.firstOrNull())
    }

    @Test fun accessibilitySemanticsExposeClickAndSelectionState() {
        launch { file("doc.txt"); navigate(root.path) }
        row("doc.txt").performMouseInput { click() }
        idle()
        val clickableNodes = rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Expected semantics nodes with OnClick action for accessibility", clickableNodes.isNotEmpty())
        val selectedNodes = clickableNodes.filter {
            it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Selected) { null } == true
        }
        assertTrue("Expected selected file row and active tab/sidebar item to expose Selected=true", selectedNodes.isNotEmpty())
    }

    @Test fun sidebarDisplaysBetaBadgeOnLinuxVmAndPhoneItems() {
        h = VmHarness()
        val linuxPath = "doc://com.android.virtualization.terminal.guestdocuments/guest_root"
        val phone = com.droid.wayfinder.data.PairedPhone(
            name = "Pixel 9",
            subtitle = "Cross-Device · Browse or import",
            path = com.droid.wayfinder.data.FsPath.PHONE_ROOT,
            isConnected = true,
            authority = com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY,
            isMounted = false,
            transport = com.droid.wayfinder.data.PhoneTransport.QUICK_ACCESS,
        )
        val sWithBeta = h.s.copy(
            roots = h.s.roots + com.droid.wayfinder.ui.RootVolume(
                path = linuxPath,
                label = "Linux VM",
                removable = false,
                subtitle = "Linux development environment",
            ),
            linuxRootPath = linuxPath,
            pairedPhone = phone,
            phoneConnected = true,
            quickAccessAvailable = true,
        )
        rule.setContent {
            WayfinderTheme(false) {
                com.droid.wayfinder.ui.Sidebar(h.vm, sWithBeta, androidx.compose.ui.Modifier)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Linux VM", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Pixel 9", useUnmergedTree = true).assertIsDisplayed()
        val betaNodes = rule.onAllNodesWithText("Beta", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("Expected Beta badge on both Linux VM and Phone sidebar rows", 2, betaNodes.size)
    }
}
