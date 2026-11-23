// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Headless UI tests for click-and-drag (marquee / rubber-band) multi-selection across
 * Details, List, Grid, Columns, Dual-Pane, tabs, modifier keys, auto-scroll, and drag-and-drop coexistence.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class MarqueeSelectionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(setup: VmHarness.() -> Unit = {}) {
        h = VmHarness()
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        idle()
    }

    private fun idle() {
        rule.waitForIdle()
        h.settle()
        rule.waitForIdle()
    }

    private fun rootNode() = rule.onAllNodes(isRoot())[0]

    private fun boundsOf(text: String) = rule.onAllNodesWithText(text)[0].fetchSemanticsNode().boundsInRoot

    @Test fun detailsViewDragUpFromEmptySpaceSelectsHighlightedRows() {
        launch {
            file("a.txt")
            file("b.txt")
            file("c.txt")
            file("d.txt")
            file("e.txt")
            navigate(root.path)
        }
        val bC = boundsOf("c.txt")
        val bE = boundsOf("e.txt")
        val start = Offset(bE.right + 160f, bE.bottom + 140f)
        val end = Offset(bC.center.x, bC.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y - 30f))
            moveTo(end)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("marquee_selection_box").assertIsDisplayed()
        assertEquals(
            setOf(h.pathOf("c.txt"), h.pathOf("d.txt"), h.pathOf("e.txt")),
            h.s.active.selection,
        )

        rootNode().performMouseInput { release(MouseButton.Primary) }
        idle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("marquee_selection_box")).fetchSemanticsNodes().isEmpty())
        assertEquals(
            setOf(h.pathOf("c.txt"), h.pathOf("d.txt"), h.pathOf("e.txt")),
            h.s.active.selection,
        )
    }

    @Test fun detailsViewDragDownFromRowWhitespaceSelectsMultipleRowsEvenWhenViewportIsFull() {
        launch {
            (1..24).forEach { i -> file("item_%02d.txt".format(i), "data-$i") }
            navigate(root.path)
        }
        val b2 = boundsOf("item_02.txt")
        val b5 = boundsOf("item_05.txt")
        // Start in blank whitespace well to the right of item_02's filename (metadata/empty area).
        val start = Offset(b2.right + 280f, b2.center.y)
        val end = Offset(b5.right + 220f, b5.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y + 30f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertEquals(
            setOf(
                h.pathOf("item_02.txt"),
                h.pathOf("item_03.txt"),
                h.pathOf("item_04.txt"),
                h.pathOf("item_05.txt"),
            ),
            h.s.active.selection,
        )
    }

    @Test fun detailsViewClickOnRowWhitespaceStillSelectsAndEmptyClickClears() {
        launch {
            file("alpha.txt")
            file("beta.txt")
            navigate(root.path)
        }
        val bAlpha = boundsOf("alpha.txt")
        val bBeta = boundsOf("beta.txt")

        // Single-click to the right of alpha.txt (inside row whitespace) still selects alpha.txt.
        rootNode().performMouseInput { click(Offset(bAlpha.right + 260f, bAlpha.center.y)) }
        idle()
        assertEquals(setOf(h.pathOf("alpha.txt")), h.s.active.selection)

        // Single-click in empty area below rows clears selection.
        rootNode().performMouseInput { click(Offset(bBeta.right + 260f, bBeta.bottom + 140f)) }
        idle()
        assertTrue(h.s.active.selection.isEmpty())
    }

    @Test fun detailsViewDraggingFilenameOntoFolderStillMovesFile() {
        launch {
            dir("Dest")
            file("move_me.txt", "payload")
            navigate(root.path)
        }
        val bFile = boundsOf("move_me.txt")
        val bDest = boundsOf("Dest")

        rootNode().performMouseInput {
            dragAndDrop(bFile.center, bDest.center, durationMillis = 200)
        }
        idle()

        assertTrue("file dragged by its name must move into Dest", java.io.File(h.root, "Dest/move_me.txt").exists())
        assertFalse(java.io.File(h.root, "move_me.txt").exists())
    }

    @Test fun gridViewMarqueeDragUpLeftSelectsIntersectingTiles() {
        launch {
            (1..6).forEach { i -> file("tile_%02d.txt".format(i)) }
            navigate(root.path)
            vm.setViewMode(ViewMode.Grid)
        }
        val b2 = boundsOf("tile_02.txt")
        val b4 = boundsOf("tile_04.txt")
        // Start in empty space well below the grid row and drag up-left across tile_04..tile_02.
        val start = Offset(b4.right + 80f, b4.bottom + 160f)
        val end = Offset(b2.center.x, b2.top - 40f)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x - 20f, start.y - 30f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertTrue(h.pathOf("tile_02.txt") in h.s.active.selection)
        assertTrue(h.pathOf("tile_03.txt") in h.s.active.selection)
        assertTrue(h.pathOf("tile_04.txt") in h.s.active.selection)
        assertFalse(h.pathOf("tile_01.txt") in h.s.active.selection)
    }

    @Test fun listViewMarqueeDragSelectsMultipleItems() {
        launch {
            file("01_one.txt")
            file("02_two.txt")
            file("03_three.txt")
            file("04_four.txt")
            navigate(root.path)
            vm.setViewMode(ViewMode.List)
        }
        val b1 = boundsOf("01_one.txt")
        val b3 = boundsOf("03_three.txt")
        val start = Offset(b1.right + 250f, b1.center.y)
        val end = Offset(b3.right + 250f, b3.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y + 25f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertEquals(
            setOf(h.pathOf("01_one.txt"), h.pathOf("02_two.txt"), h.pathOf("03_three.txt")),
            h.s.active.selection,
        )
    }

    @Test fun columnsViewMarqueeDragSelectsItemsWithoutOpeningFolder() {
        launch {
            dir("SubFolder")
            file("doc1.txt")
            file("doc2.txt")
            navigate(root.path)
            vm.setViewMode(ViewMode.Columns)
        }
        val bDir = boundsOf("SubFolder")
        val bDoc2 = boundsOf("doc2.txt")
        val start = Offset(bDoc2.right + 60f, bDoc2.bottom + 120f)
        val end = Offset(bDir.center.x, bDir.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y - 30f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertEquals("marquee drag over a folder must not navigate into it", h.root.path, h.s.active.path)
        assertEquals(
            setOf(h.pathOf("SubFolder"), h.pathOf("doc1.txt"), h.pathOf("doc2.txt")),
            h.s.active.selection,
        )
    }

    @Test fun marqueeWorksAfterSwitchingToNewTab() {
        launch {
            file("tab1_file.txt")
            file("sub/t2_a.txt")
            file("sub/t2_b.txt")
            file("sub/t2_c.txt")
            navigate(root.path)
            vm.newTab(pathOf("sub"))
        }
        val bA = boundsOf("t2_a.txt")
        val bC = boundsOf("t2_c.txt")
        val start = Offset(bC.right + 180f, bC.bottom + 140f)
        val end = Offset(bA.center.x, bA.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y - 30f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertEquals(
            setOf(h.pathOf("sub/t2_a.txt"), h.pathOf("sub/t2_b.txt"), h.pathOf("sub/t2_c.txt")),
            h.s.active.selection,
        )
    }

    @Test fun shrinkingMarqueeBeforeReleaseDeselectsItemsOutsideFinalBox() {
        launch {
            file("a.txt")
            file("b.txt")
            file("c.txt")
            file("d.txt")
            navigate(root.path)
        }
        val bA = boundsOf("a.txt")
        val bB = boundsOf("b.txt")
        val bD = boundsOf("d.txt")
        val start = Offset(bA.right + 260f, bA.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            // Drag down through d.txt
            moveTo(Offset(start.x, bD.center.y))
        }
        rule.waitForIdle()
        assertEquals(
            setOf(h.pathOf("a.txt"), h.pathOf("b.txt"), h.pathOf("c.txt"), h.pathOf("d.txt")),
            h.s.active.selection,
        )

        // Shrink back up to b.txt before releasing
        rootNode().performMouseInput {
            moveTo(Offset(start.x, bB.center.y))
            release(MouseButton.Primary)
        }
        idle()
        assertEquals(
            setOf(h.pathOf("a.txt"), h.pathOf("b.txt")),
            h.s.active.selection,
        )
    }

    @Test fun dualPaneMarqueeSelectsOnlyTargetPaneAndActivatesIt() {
        launch {
            file("left/l1.txt")
            file("left/l2.txt")
            file("right/r1.txt")
            file("right/r2.txt")
            file("right/r3.txt")
            navigate(pathOf("left"))
            vm.toggleDualPane()
            vm.activatePane(true)
            vm.navigate(pathOf("right"))
            vm.activatePane(false)
            vm.setSelection(setOf(pathOf("left/l1.txt")))
        }
        assertFalse(h.s.rightActive)
        val bR1 = boundsOf("r1.txt")
        val bR3 = boundsOf("r3.txt")
        val start = Offset(bR3.right + 80f, bR3.bottom + 140f)
        val end = Offset(bR1.center.x, bR1.center.y)

        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(Offset(start.x, start.y - 30f))
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        assertTrue("dragging in the right pane must activate it", h.s.rightActive)
        assertEquals(
            setOf(h.pathOf("right/r1.txt"), h.pathOf("right/r2.txt"), h.pathOf("right/r3.txt")),
            h.s.secondary?.selection,
        )
        assertEquals(
            "left pane selection must remain untouched",
            setOf(h.pathOf("left/l1.txt")),
            h.s.mainTab.selection,
        )
    }

    @Test fun touchSwipeScrollsListWithoutTriggeringMarqueeSelection() {
        launch {
            (1..30).forEach { i -> file("touch_%02d.txt".format(i)) }
            navigate(root.path)
        }
        val b2 = boundsOf("touch_02.txt")
        rootNode().performTouchInput {
            swipeUp(startY = b2.center.y + 300f, endY = b2.center.y - 100f)
        }
        idle()
        assertTrue("touch drag must scroll, not rubber-band select", h.s.active.selection.isEmpty())
    }

    @Test fun marqueeSelectThenDragSelectedRowMovesAllSelectedFiles() {
        launch {
            dir("Target")
            file("f1.txt", "one")
            file("f2.txt", "two")
            file("f3.txt", "three")
            navigate(root.path)
        }
        val bTarget = boundsOf("Target")
        val b1 = boundsOf("f1.txt")
        val b2 = boundsOf("f2.txt")

        // First marquee-select f1.txt and f2.txt starting in row whitespace.
        rootNode().performMouseInput {
            moveTo(Offset(b1.right + 240f, b1.center.y))
            press(MouseButton.Primary)
            moveTo(Offset(b2.right + 240f, b2.center.y))
            release(MouseButton.Primary)
        }
        idle()
        assertEquals(setOf(h.pathOf("f1.txt"), h.pathOf("f2.txt")), h.s.active.selection)

        // Now drag from the whitespace of already-selected f1.txt onto Target -> moves BOTH files!
        rootNode().performMouseInput {
            dragAndDrop(Offset(b1.right + 240f, b1.center.y), bTarget.center, durationMillis = 200)
        }
        idle()
        assertTrue(java.io.File(h.root, "Target/f1.txt").exists())
        assertTrue(java.io.File(h.root, "Target/f2.txt").exists())
        assertFalse(java.io.File(h.root, "f1.txt").exists())
        assertFalse(java.io.File(h.root, "f2.txt").exists())
        assertTrue(java.io.File(h.root, "f3.txt").exists())
    }

    @Test fun shiftAndCtrlMarqueePreserveAndToggleInitialSelectionSnapshot() {
        launch {
            file("a.txt")
            file("b.txt")
            file("c.txt")
            file("d.txt")
            navigate(root.path)
            vm.setSelection(setOf(pathOf("a.txt"), pathOf("b.txt")))
        }
        val bB = boundsOf("b.txt")
        val bC = boundsOf("c.txt")
        val bD = boundsOf("d.txt")

        // Shift+drag across c.txt and d.txt, then shrink back to c.txt -> keeps {a, b} + {c}
        rootNode().performMultiModalInput {
            key { keyDown(Key.ShiftLeft) }
            mouse {
                moveTo(Offset(bC.right + 260f, bC.top + 4f))
                press(MouseButton.Primary)
                moveTo(Offset(bD.right + 260f, bD.center.y))
                moveTo(Offset(bC.right + 260f, bC.bottom - 4f))
                release(MouseButton.Primary)
            }
            key { keyUp(Key.ShiftLeft) }
        }
        idle()
        assertEquals(setOf(h.pathOf("a.txt"), h.pathOf("b.txt"), h.pathOf("c.txt")), h.s.active.selection)

        // Ctrl+drag over d.txt from empty space -> toggles d.txt ON while keeping {a, b, c}
        rootNode().performMultiModalInput {
            key { keyDown(Key.CtrlLeft) }
            mouse {
                moveTo(Offset(bD.right + 260f, bD.bottom + 120f))
                press(MouseButton.Primary)
                moveTo(Offset(bD.right + 260f, bD.center.y))
                release(MouseButton.Primary)
            }
            key { keyUp(Key.CtrlLeft) }
        }
        idle()
        assertEquals(
            setOf(h.pathOf("a.txt"), h.pathOf("b.txt"), h.pathOf("c.txt"), h.pathOf("d.txt")),
            h.s.active.selection,
        )

        // Ctrl+drag over b.txt, c.txt, d.txt from empty space below -> toggles d, c, b OFF -> only {a} left!
        rootNode().performMultiModalInput {
            key { keyDown(Key.CtrlLeft) }
            mouse {
                moveTo(Offset(bD.right + 260f, bD.bottom + 120f))
                press(MouseButton.Primary)
                moveTo(Offset(bB.right + 260f, bB.center.y))
                release(MouseButton.Primary)
            }
            key { keyUp(Key.CtrlLeft) }
        }
        idle()
        assertEquals(setOf(h.pathOf("a.txt")), h.s.active.selection)
    }

    @Test fun marqueeAutoScrollsAndKeepsScrolledOffItemsSelected() {
        launch {
            (1..40).forEach { i -> file("row_%02d.txt".format(i)) }
            navigate(root.path)
        }
        val b1 = boundsOf("row_01.txt")
        val rootBounds = rootNode().fetchSemanticsNode().boundsInRoot
        // Drag from row_01 whitespace down into the bottom auto-scroll edge zone of the list.
        val start = Offset(b1.right + 260f, b1.center.y)
        val nearBottom = Offset(b1.right + 240f, rootBounds.bottom - 90f)

        rule.mainClock.autoAdvance = false
        try {
            rootNode().performMouseInput {
                moveTo(start)
                press(MouseButton.Primary)
                moveTo(Offset(start.x, start.y + 30f))
                moveTo(nearBottom)
            }
            // Advance ~20 frames (~320ms) while cursor stays in the bottom edge zone.
            repeat(25) { rule.mainClock.advanceTimeByFrame() }
            rootNode().performMouseInput { release(MouseButton.Primary) }
            rule.mainClock.advanceTimeByFrame()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        idle()

        val selected = h.s.active.selection
        assertTrue("row_01.txt (which started at top) must remain selected after auto-scroll; selected=${selected.size}", h.pathOf("row_01.txt") in selected)
        assertTrue("auto-scroll must select items beyond the initial viewport; selected=${selected.size}", selected.size >= 14)
        assertTrue("releasing marquee after downward auto-scroll must not snap viewport back to row_01.txt", rule.onAllNodesWithText("row_01.txt").fetchSemanticsNodes().isEmpty())
    }

    @Test fun scrollbarMouseDragDoesNotTriggerMarqueeSelection() {
        launch {
            (1..40).forEach { i -> file("scroll_%02d.txt".format(i)) }
            navigate(root.path)
        }
        val b1 = boundsOf("scroll_01.txt")
        val rootBounds = rootNode().fetchSemanticsNode().boundsInRoot
        // Rightmost edge inside the ContentCard (8.dp window padding + scrollbar right edge)
        val scrollbarX = rootBounds.right - 22f

        rootNode().performMouseInput {
            moveTo(Offset(scrollbarX, b1.center.y))
            press(MouseButton.Primary)
            moveTo(Offset(scrollbarX, b1.center.y + 300f))
            release(MouseButton.Primary)
        }
        idle()

        assertTrue("dragging the scrollbar must not select rows", h.s.active.selection.isEmpty())
    }

    @Test fun deepMillerColumnsMarqueeDoesNotShiftColumnHorizontallyMidDragAndSyncsFocus() {
        launch {
            var cur = root
            repeat(5) { idx ->
                cur = java.io.File(cur, "level_${idx + 1}").apply { mkdirs() }
            }
            java.io.File(cur, "focus_first.txt").writeText("0")
            java.io.File(cur, "pick_a.txt").writeText("a")
            java.io.File(cur, "pick_b.txt").writeText("b")
            navigate(cur.path)
            vm.setViewMode(ViewMode.Columns)
            vm.click(java.io.File(cur, "focus_first.txt").path, ctrl = false, shift = false)
            vm.clearSelection()
        }

        val initialPickABounds = boundsOf("pick_a.txt")
        val initialPickBBounds = boundsOf("pick_b.txt")
        val start = Offset(initialPickABounds.right + 40f, initialPickABounds.center.y)
        val mid = Offset(initialPickABounds.right + 35f, initialPickABounds.center.y + 8f)
        val end = Offset(initialPickBBounds.right + 35f, initialPickBBounds.center.y)

        // Touching the first file during marquee (selection size == 1) must NOT mount MillerPreview and scroll horizontally.
        rootNode().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(mid)
        }
        idle()
        val midDragPickABounds = boundsOf("pick_a.txt")
        assertEquals(
            "CurrentColumn must not jump horizontally while marquee drag is active",
            initialPickABounds.left,
            midDragPickABounds.left,
            1.0f,
        )

        rootNode().performMouseInput {
            moveTo(end)
            release(MouseButton.Primary)
        }
        idle()

        val selected = h.s.active.selection
        assertEquals(2, selected.size)
        assertTrue("tab.focused must move into marquee selection on drag completion", h.s.active.focused in selected)
    }

    @Test fun pointerSourceMarksPinchAndMultiFingerEventsNonPrecise() {
        val now = android.os.SystemClock.uptimeMillis()
        val mouseDown = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHPAD
        }
        try {
            com.droid.wayfinder.ui.PointerSource.update(mouseDown)
            assertTrue(com.droid.wayfinder.ui.PointerSource.precise)
        } finally {
            mouseDown.recycle()
        }

        val props = arrayOf(
            android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
            android.view.MotionEvent.PointerProperties().apply { id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            android.view.MotionEvent.PointerCoords().apply { x = 10f; y = 10f },
            android.view.MotionEvent.PointerCoords().apply { x = 40f; y = 40f },
        )
        val multiMove = android.view.MotionEvent.obtain(
            now, now + 10L, android.view.MotionEvent.ACTION_MOVE,
            2, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHPAD, 0,
        )
        try {
            com.droid.wayfinder.ui.PointerSource.update(multiMove)
            assertFalse("Multi-finger trackpad gesture must clear PointerSource.precise", com.droid.wayfinder.ui.PointerSource.precise)
        } finally {
            multiMove.recycle()
            com.droid.wayfinder.ui.PointerSource.resetForTest()
        }
    }
}
