// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.FocusCoordinator
import com.droid.wayfinder.ui.LocalFocusCoordinator
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
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class KeyboardFocusNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    @Test
    fun spacebarOnFocusedEntryOpensQuickLookWhileEnterOpensItem() {
        val harness = VmHarness().also { h = it }
        harness.dir("SubDir")
        harness.file("SubDir/note.txt", "hello")
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // Pressing Spacebar on the roving-focused folder opens Quick Look rather than navigating into it.
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        idle(harness)
        assertEquals(harness.pathOf("SubDir"), harness.s.quickLook)
        assertEquals(harness.root.path, harness.s.active.path)

        // Pressing Spacebar again closes Quick Look and keeps FileList focused; Enter then opens SubDir.
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        idle(harness)
        assertNull(harness.s.quickLook)

        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle(harness)
        assertEquals(harness.pathOf("SubDir"), harness.s.active.path)
    }

    @Test
    fun keyboardTabFocusTransfersFromFileListToSidebarAndOpensItemsWithEnter() {
        val harness = VmHarness().also { h = it }
        harness.dir("TargetSubfolder")
        harness.file("TargetSubfolder/inside.txt", "ok")
        harness.file("zeta.txt", "z")
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // 1. Initial focus is set upon load on the file list's first item
        val initialFocused = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals("Expected exactly 1 focused node on initial load", 1, initialFocused.size)

        // 2. Pressing Tab from the main file pane transfers focus directly to the left Sidebar ("Recents")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)
        val afterFirstTab = rule.onNode(isFocused()).fetchSemanticsNode()
        val focusedLabel = afterFirstTab.config.getOrNull(SemanticsActions.OnClick)?.label
            ?: afterFirstTab.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            .orEmpty()
        assertTrue("Expected Tab from FileList to focus Sidebar 'Recents', got '$focusedLabel'", focusedLabel.contains("Recents"))

        // 3. ArrowDown inside Sidebar moves focus to "Starred", and Enter navigates to Starred
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        idle(harness)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle(harness)
        assertEquals(FsPath.STARRED_ROOT, harness.s.active.path)

        // 4. Navigate back to root folder, press Escape from Sidebar to return focus to FileList, and press Enter to open subfolder
        harness.navigate(harness.root.path)
        idle(harness)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
        idle(harness)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle(harness)
        assertEquals("Enter on focused subfolder should open it", harness.pathOf("TargetSubfolder"), harness.s.active.path)
    }

    @Test
    fun reverseShiftTabReachesToolbarWithoutSidebarLoopAndEmptyViewButtonsHandleEnter() {
        val harness = VmHarness().also { h = it }
        harness.file("item.txt", "abc")
        val hiddenOnlyDir = harness.dir("HiddenVault")
        harness.file("HiddenVault/.secret_note", "classified")
        harness.navigate(harness.root.path)
        harness.vm.setViewMode(ViewMode.Grid)
        harness.settle()

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // 1. Shift+Tab from Grid FileList moves counter-clockwise into Toolbar ("More") rather than looping into Sidebar
        rule.onNode(isFocused()).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        idle(harness)
        val focusedAfterShiftTab = rule.onNode(isFocused()).fetchSemanticsNode()
        val desc = focusedAfterShiftTab.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertEquals("Shift+Tab from Grid FileList should focus Toolbar 'More' button (node=${focusedAfterShiftTab.config})", "More", desc)

        // Pressing Tab forward from Toolbar 'More' returns focus cleanly to FileList
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)

        // 2. Navigate to hidden-only folder: Tab enters 'Show hidden files' button, and Enter activates it natively
        harness.navigate(hiddenOnlyDir.path)
        idle(harness)
        assertFalse(harness.s.showHidden)

        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)
        val emptyBtnText = rule.onNode(isFocused()).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue("Tab from EmptyView should focus 'Show hidden files', got '$emptyBtnText'", emptyBtnText.contains("Show hidden files"))

        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle(harness)
        assertTrue("Enter on focused EmptyView button must invoke its onClick action", harness.s.showHidden)
    }

    @Test
    fun quickLookTrapsTabFocusActivatesHeaderButtonsWithEnterAndRestoresFileListFocus() {
        val harness = VmHarness().also { h = it }
        harness.file("ql_dir/first.txt", "first content")
        harness.file("ql_dir/second.txt", "second content")
        harness.navigate(harness.pathOf("ql_dir"))
        harness.vm.click(harness.pathOf("ql_dir/first.txt"), ctrl = false, shift = false)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // Open Quick Look with Spacebar on selected item
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        idle(harness)
        assertEquals(harness.pathOf("ql_dir/first.txt"), harness.s.quickLook)

        // Tab focuses Quick Look's first header button ("Previous"), and Tab again focuses "Next"
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)
        assertEquals(
            "Previous",
            rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)
        assertEquals(
            "Next",
            rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )

        // Pressing Enter on focused "Next" activates it natively (advancing Quick Look to second.txt without closing Quick Look)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        idle(harness)
        assertEquals(harness.pathOf("ql_dir/second.txt"), harness.s.quickLook)

        // Repeated Tab presses cycle through Quick Look ("Open", "Close", scrollable body) and wrap back to "Previous"
        var wrappedToPrevious = false
        for (step in 0 until 6) {
            rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(Key.Tab) }
            idle(harness)
            val mergedFocused = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
            val curDesc = mergedFocused.firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            assertFalse("Tab inside Quick Look must never escape to Toolbar/TitleBar, got '$curDesc'", curDesc == "More" || curDesc == "New tab")
            if (curDesc == "Previous") {
                wrappedToPrevious = true
                break
            }
        }
        assertTrue("Expected Tab cycle inside Quick Look to wrap back to 'Previous'", wrappedToPrevious)

        // Shift+Tab from "Previous" wraps to "Close", and Spacebar activates "Close" to dismiss Quick Look and restore FileList focus
        rule.onNode(isFocused()).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        idle(harness)
        assertEquals(
            "Close",
            rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        idle(harness)
        assertNull(harness.s.quickLook)
        assertEquals(1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
    }

    @Test
    fun touchInputHidesFocusRingsAndHardwareKeyRestoresKeyboardFocusMode() {
        val harness = VmHarness().also { h = it }
        harness.file("alpha.txt", "a")
        harness.file("beta.txt", "b")
        harness.navigate(harness.root.path)

        val testCoord = FocusCoordinator()
        rule.setContent {
            WayfinderTheme(false) {
                CompositionLocalProvider(LocalFocusCoordinator provides testCoord) {
                    AppRoot(harness.vm)
                }
            }
        }
        idle(harness)

        // Focus rings start hidden before any hardware key press, while semantic focus remains ready
        assertFalse(testCoord.keyboardFocusVisible)
        assertEquals(1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)

        // System phone keys (Back / Volume) must never trigger desktop keyboard focus rings
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Back) }
        idle(harness)
        assertFalse(testCoord.keyboardFocusVisible)

        // Pressing a keyboard navigation key reveals 2dp focus rings immediately
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        idle(harness)
        assertTrue(testCoord.keyboardFocusVisible)

        // Touch/pointer interaction hides keyboard focus rings again
        rule.onNodeWithText("Starred").performTouchInput { click() }
        idle(harness)
        assertFalse(testCoord.keyboardFocusVisible)

        // Pressing Tab re-enables visible keyboard focus rings
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        idle(harness)
        assertTrue(testCoord.keyboardFocusVisible)
    }
}
