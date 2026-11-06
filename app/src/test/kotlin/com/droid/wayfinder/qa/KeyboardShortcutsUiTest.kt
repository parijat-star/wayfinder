// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeysDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Every shortcut of the F1 list, sent as real key events to AppRoot's key handler. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class KeyboardShortcutsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(setup: VmHarness.() -> Unit) {
        h = VmHarness()
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        idle()
    }

    private fun idle() { rule.waitForIdle(); h.settle(); rule.waitForIdle() }

    private fun target(): SemanticsNodeInteraction {
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        return if (focused.isNotEmpty()) rule.onNode(isFocused()) else rule.onAllNodes(isRoot())[0]
    }

    private fun key(k: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) {
        val mods = listOfNotNull(Key.CtrlLeft.takeIf { ctrl }, Key.ShiftLeft.takeIf { shift }, Key.AltLeft.takeIf { alt })
        target().performKeyInput { withKeysDown(mods) { pressKey(k) } }
        idle()
    }

    private fun closeDialog() { h.vm.dismissDialog(); idle() }

    @Test fun tabAndWindowShortcuts() {
        launch { dir("A"); dir("B") }
        key(Key.T, ctrl = true); assertEquals("Ctrl+T", 2, h.s.tabs.size)
        h.vm.navigate(h.pathOf("A")); idle()
        key(Key.T, ctrl = true); h.vm.navigate(h.pathOf("B")); idle()
        assertEquals(3, h.s.tabs.size)
        key(Key.Tab, ctrl = true); assertEquals("Ctrl+Tab wraps", com.droid.wayfinder.data.FsPath.RECENTS_ROOT, h.s.active.path)
        key(Key.Tab, ctrl = true, shift = true); assertEquals("Ctrl+Shift+Tab", h.pathOf("B"), h.s.active.path)
        key(Key.PageUp, ctrl = true); assertEquals("Ctrl+PgUp", h.pathOf("A"), h.s.active.path)
        key(Key.PageDown, ctrl = true); assertEquals("Ctrl+PgDn", h.pathOf("B"), h.s.active.path)
        key(Key.One, alt = true); assertEquals("Alt+1", com.droid.wayfinder.data.FsPath.RECENTS_ROOT, h.s.active.path)
        key(Key.Nine, alt = true); assertEquals("Alt+9 = last", h.pathOf("B"), h.s.active.path)
        key(Key.W, ctrl = true); assertEquals("Ctrl+W", 2, h.s.tabs.size)
        key(Key.T, ctrl = true, shift = true); assertEquals("Ctrl+Shift+T", h.pathOf("B"), h.s.active.path)
        assertEquals(3, h.s.tabs.size)
        key(Key.N, ctrl = true); assertEquals("Ctrl+N", com.droid.wayfinder.data.FsPath.RECENTS_ROOT, h.events.filterIsInstance<UiEvent.NewWindow>().single().path)
        key(Key.K, ctrl = true); assertTrue("Ctrl+K", h.s.paletteOpen)
        key(Key.K, ctrl = true); assertFalse("Ctrl+K toggles", h.s.paletteOpen)
    }

    @Test fun navigationShortcuts() {
        launch { file("A/B/inner.txt"); navigate(pathOf("A")) }
        h.vm.typeAhead("B")
        key(Key.Enter); assertEquals("Enter opens", h.pathOf("A/B"), h.s.active.path)
        key(Key.Backspace); assertEquals("Backspace = back", h.pathOf("A"), h.s.active.path)
        key(Key.DirectionRight, alt = true); assertEquals("Alt+→", h.pathOf("A/B"), h.s.active.path)
        key(Key.DirectionLeft, alt = true); assertEquals("Alt+←", h.pathOf("A"), h.s.active.path)
        key(Key.DirectionUp, alt = true); assertEquals("Alt+↑", h.root.path, h.s.active.path)
        assertEquals("up selects the folder we came from", setOf(h.pathOf("A")), h.s.active.selection)
        key(Key.Enter, ctrl = true); assertEquals("Ctrl+Enter opens in new tab", 2, h.s.tabs.size)
        key(Key.L, ctrl = true); assertTrue("Ctrl+L", h.s.addressEditing)
        h.vm.setAddressEditing(false); h.vm.setTextFocus(false); idle()
        key(Key.D, alt = true); assertTrue("Alt+D", h.s.addressEditing)
        h.vm.setAddressEditing(false); h.vm.setTextFocus(false); idle()
        val f0 = h.s.focusSearch
        key(Key.F, ctrl = true); assertEquals("Ctrl+F", f0 + 1, h.s.focusSearch)
        h.vm.setTextFocus(false); idle()
        key(Key.E, ctrl = true); assertEquals("Ctrl+E", f0 + 2, h.s.focusSearch)
        h.vm.setTextFocus(false); idle()
        h.file("late.txt")
        key(Key.R, ctrl = true); assertTrue("Ctrl+R refresh", "late.txt" in h.names())
        h.file("later.txt")
        key(Key.F5); assertTrue("F5 refresh", "later.txt" in h.names())
        key(Key.K, ctrl = true, shift = true); assertTrue("Ctrl+Shift+K", h.s.dialog is Dialog.Connect)
    }

    @Test fun selectionShortcuts() {
        launch { listOf("a", "b", "c", "d", "e").forEach { file("s/$it.txt") }; navigate(pathOf("s")) }
        val p = { n: String -> h.pathOf("s/$n.txt") }
        key(Key.A, ctrl = true); assertEquals("Ctrl+A", 5, h.s.active.selection.size)
        key(Key.A, ctrl = true, shift = true); assertTrue("Ctrl+Shift+A", h.s.active.selection.isEmpty())
        key(Key.MoveHome); assertEquals("Home selects first", setOf(p("a")), h.s.active.selection)
        key(Key.DirectionDown); assertEquals("↓ moves selection", setOf(p("b")), h.s.active.selection)
        key(Key.DirectionUp); assertEquals("↑ moves selection", setOf(p("a")), h.s.active.selection)
        key(Key.DirectionDown, shift = true); assertEquals("Shift+↓ extends", setOf(p("a"), p("b")), h.s.active.selection)
        key(Key.I, ctrl = true, shift = true); assertEquals("Ctrl+Shift+I", setOf(p("c"), p("d"), p("e")), h.s.active.selection)
        key(Key.Escape); assertTrue("Esc clears", h.s.active.selection.isEmpty())
        key(Key.MoveEnd); assertEquals("End", p("e"), h.s.active.focused)
        key(Key.MoveHome, shift = true); assertEquals("Shift+Home", 5, h.s.active.selection.size)
        key(Key.MoveHome); key(Key.PageDown); assertEquals("PgDn", p("e"), h.s.active.focused)
        key(Key.PageUp); assertEquals("PgUp", p("a"), h.s.active.focused)
        key(Key.DirectionDown, ctrl = true); assertEquals("Ctrl+↓ moves focus only", setOf(p("a")), h.s.active.selection)
        assertEquals(p("b"), h.s.active.focused)
        key(Key.Spacebar, ctrl = true); assertEquals("Ctrl+Space toggles", setOf(p("a"), p("b")), h.s.active.selection)
        key(Key.D); assertEquals("type-ahead", setOf(p("d")), h.s.active.selection)
    }

    @Test fun editShortcuts() {
        launch { file("e/a.txt", "A"); dir("e/dst"); navigate(pathOf("e")) }
        h.vm.typeAhead("a")
        key(Key.C, ctrl = true); assertFalse("Ctrl+C", h.s.clipboard!!.cut)
        key(Key.X, ctrl = true); assertTrue("Ctrl+X", h.s.clipboard!!.cut)
        key(Key.C, ctrl = true)
        h.vm.navigate(h.pathOf("e/dst")); idle()
        key(Key.V, ctrl = true)
        h.until(what = "paste") { File(h.root, "e/dst/a.txt").exists() }; idle()
        key(Key.Z, ctrl = true)
        h.until(what = "undo") { !File(h.root, "e/dst/a.txt").exists() }; idle()
        key(Key.Y, ctrl = true)
        h.until(what = "redo") { File(h.root, "e/dst/a.txt").exists() }; idle()
        key(Key.Z, ctrl = true); h.until { !File(h.root, "e/dst/a.txt").exists() }; idle()
        key(Key.Z, ctrl = true, shift = true)
        h.until(what = "ctrl+shift+z") { File(h.root, "e/dst/a.txt").exists() }; idle()
        h.vm.typeAhead("a")
        key(Key.D, ctrl = true); h.until(what = "Ctrl+D") { File(h.root, "e/dst/a copy.txt").exists() }; idle()
        h.vm.typeAhead("a.txt")
        key(Key.F2); assertTrue("F2", h.s.dialog is Dialog.Rename); closeDialog()
        key(Key.R, ctrl = true, shift = true); assertTrue("Ctrl+Shift+R", h.s.dialog is Dialog.BatchRename); closeDialog()
        key(Key.C, ctrl = true, shift = true); assertEquals("Ctrl+Shift+C", h.pathOf("e/dst/a.txt"), h.events.filterIsInstance<UiEvent.CopyText>().last().text)
        key(Key.I, ctrl = true); assertTrue("Ctrl+I", h.s.dialog is Dialog.Properties); closeDialog()
        key(Key.Enter, alt = true); assertTrue("Alt+Enter", h.s.dialog is Dialog.Properties); closeDialog()
        key(Key.N, ctrl = true, shift = true); assertEquals("Ctrl+Shift+N", Dialog.NewItem(true), h.s.dialog); closeDialog()
        key(Key.M, ctrl = true, shift = true); assertEquals("Ctrl+Shift+M", Dialog.NewItem(false), h.s.dialog); closeDialog()
        key(Key.N, ctrl = true, alt = true); assertEquals("Ctrl+Alt+N", Dialog.NewItem(false), h.s.dialog); closeDialog()
        h.vm.typeAhead("a copy")
        key(Key.Delete, shift = true); assertTrue("Shift+Del asks", h.s.dialog is Dialog.Delete); closeDialog()
        key(Key.Delete); h.until(what = "Del") { !File(h.root, "e/dst/a copy.txt").exists() }; idle()
        assertTrue(h.toasts().last().contains("to Trash"))
    }

    @Test fun viewShortcuts() {
        launch { dir("v/sub"); file("v/.dot"); navigate(pathOf("v")) }
        key(Key.Two, ctrl = true); assertEquals("Ctrl+2", ViewMode.Grid, h.s.active.viewMode)
        key(Key.Three, ctrl = true); assertEquals("Ctrl+3", ViewMode.Columns, h.s.active.viewMode)
        key(Key.One, ctrl = true); assertEquals("Ctrl+1", ViewMode.Details, h.s.active.viewMode)
        key(Key.H, ctrl = true); assertTrue("Ctrl+H", h.s.showHidden && ".dot" in h.names())
        key(Key.H, ctrl = true); assertFalse(h.s.showHidden)
        key(Key.B, ctrl = true); assertTrue("Ctrl+B pins current folder", h.pathOf("v") in h.s.pins)
        h.vm.typeAhead("sub")
        key(Key.B, ctrl = true); assertTrue("Ctrl+B pins selected folder", h.pathOf("v/sub") in h.s.pins)
        val sb = h.s.sidebarVisible
        key(Key.Backslash, ctrl = true); assertEquals("Ctrl+\\", !sb, h.s.sidebarVisible)
        val pv = h.s.previewVisible
        key(Key.P, alt = true); assertEquals("Alt+P", !pv, h.s.previewVisible)
        val loc = h.s.treeLocate
        key(Key.L, alt = true, shift = true); assertEquals("Alt+Shift+L", loc + 1, h.s.treeLocate)
        assertTrue("locate re-shows the sidebar", h.s.sidebarVisible)
        key(Key.Equals, ctrl = true); assertEquals("Ctrl+=", 1.1f, h.s.zoom, 0.001f)
        key(Key.Minus, ctrl = true); key(Key.Minus, ctrl = true); assertEquals("Ctrl+-", 0.9f, h.s.zoom, 0.001f)
        key(Key.Zero, ctrl = true); assertEquals("Ctrl+0", 1f, h.s.zoom, 0.001f)
        key(Key.F1); assertEquals("F1", Dialog.Shortcuts, h.s.dialog); closeDialog()
        key(Key.Menu); assertTrue("Menu key opens a context menu", rule.onAllNodesWithText("Copy path").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun dualPaneShortcuts() {
        launch { file("l/x.txt"); file("l/y.txt"); dir("r"); navigate(pathOf("l")) }
        key(Key.Four, ctrl = true); assertTrue("Ctrl+4", h.s.dualPane)
        key(Key.Tab); assertTrue("Tab switches pane", h.s.rightActive)
        h.vm.navigate(h.pathOf("r")); idle()
        key(Key.Tab); assertFalse(h.s.rightActive)
        h.vm.typeAhead("x")
        key(Key.F5); h.until(what = "F5 copy") { File(h.root, "r/x.txt").exists() }; idle()
        assertTrue(File(h.root, "l/x.txt").exists())
        h.vm.typeAhead("y")
        key(Key.F6); h.until(what = "F6 move") { File(h.root, "r/y.txt").exists() }; idle()
        assertFalse(File(h.root, "l/y.txt").exists())
        key(Key.F3); assertFalse("F3 toggles off", h.s.dualPane)
        key(Key.F3); assertTrue("F3 toggles on", h.s.dualPane)
    }

    @Test fun quickLookKeys() {
        launch { listOf("1", "2", "3").forEach { file("q/$it.txt", "body $it") }; dir("q/zdir"); navigate(pathOf("q")) }
        h.vm.typeAhead("1")
        key(Key.Spacebar); assertEquals(h.pathOf("q/1.txt"), h.s.quickLook)
        key(Key.DirectionRight); assertEquals("→ next", h.pathOf("q/2.txt"), h.s.quickLook)
        key(Key.DirectionDown); assertEquals("↓ next row", h.pathOf("q/3.txt"), h.s.quickLook)
        key(Key.DirectionLeft); assertEquals("← previous", h.pathOf("q/2.txt"), h.s.quickLook)
        key(Key.W, ctrl = true); assertNull("Ctrl+W closes Quick Look, not the tab", h.s.quickLook)
        assertEquals(1, h.s.tabs.size)
        key(Key.Spacebar); key(Key.Spacebar); assertNull("Space toggles", h.s.quickLook)
        key(Key.Spacebar); key(Key.Enter)
        assertNull(h.s.quickLook)
        assertNotNull("Enter opens the previewed file", h.events.filterIsInstance<UiEvent.OpenFile>().lastOrNull())
    }
}
