// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabTransferTest {

    @After
    fun tearDown() {
        TabTransferRegistry.resetForTest()
        ConnectedDisplays.overrideForTest = null
    }

    @Test
    fun inStripDragMathTracksContinuouslyWithZeroSwapJumpInLtrAndRtl() {
        val tabW = 160f
        val count = 4

        // Grabbing tab 1 at grabOffset = 40px inside tab 1 (so pointer starts at 160 + 40 = 200px).
        val startLeft = TabStripDragMath.clampedDraggedLeftPx(
            pointerContentX = 200f,
            grabOffsetPx = 40f,
            tabWidthPx = tabW,
            tabCount = count,
        )
        assertEquals(160f, startLeft, 0.01f)
        assertEquals(0f, TabStripDragMath.draggedTabTranslationPx(startLeft, slotIndex = 1, tabWidthPx = tabW, tabCount = count, isRtl = false), 0.01f)

        // Dragging right by +96px (pointer = 296px -> clampedLeft = 256px, +96px > 0.54 * 160 = 86.4px).
        val draggedLeft = TabStripDragMath.clampedDraggedLeftPx(296f, 40f, tabW, count)
        assertEquals(256f, draggedLeft, 0.01f)
        val transBeforeSwap = TabStripDragMath.draggedTabTranslationPx(draggedLeft, slotIndex = 1, tabWidthPx = tabW, tabCount = count, isRtl = false)
        assertEquals(96f, transBeforeSwap, 0.01f)

        val newSlot = TabStripDragMath.targetReorderIndex(
            clampedLeftPx = draggedLeft,
            tabWidthPx = tabW,
            tabCount = count,
            currentIndex = 1,
            isRtl = false,
        )
        assertEquals(2, newSlot)

        // After swapping from slot 1 -> slot 2, slotLeftPx(2) + transAfterSwap MUST equal slotLeftPx(1) + transBeforeSwap (zero visual jump!).
        val transAfterSwap = TabStripDragMath.draggedTabTranslationPx(draggedLeft, slotIndex = 2, tabWidthPx = tabW, tabCount = count, isRtl = false)
        assertEquals(-64f, transAfterSwap, 0.01f)
        val visualBefore = TabStripDragMath.slotLeftPx(1, tabW, count, isRtl = false) + transBeforeSwap
        val visualAfter = TabStripDragMath.slotLeftPx(2, tabW, count, isRtl = false) + transAfterSwap
        assertEquals(visualBefore, visualAfter, 0.01f)

        // Clamping prevents overshooting past index 0 or index (count - 1).
        assertEquals(0f, TabStripDragMath.clampedDraggedLeftPx(-500f, 40f, tabW, count), 0.01f)
        assertEquals(480f, TabStripDragMath.clampedDraggedLeftPx(2000f, 40f, tabW, count), 0.01f)

        // RTL layout: index 0 is on the right ((4 - 1 - 0) * 160 = 480px), index 3 is on the left (0px).
        assertEquals(480f, TabStripDragMath.slotLeftPx(0, tabW, count, isRtl = true), 0.01f)
        assertEquals(0f, TabStripDragMath.slotLeftPx(3, tabW, count, isRtl = true), 0.01f)
        // Dragging leftward in RTL (toward x = 0) advances toward higher tab indices!
        assertEquals(
            3,
            TabStripDragMath.targetReorderIndex(clampedLeftPx = 10f, tabWidthPx = tabW, tabCount = count, currentIndex = 1, isRtl = true),
        )
        val rtlTrans1 = TabStripDragMath.draggedTabTranslationPx(200f, slotIndex = 1, tabWidthPx = tabW, tabCount = count, isRtl = true)
        val rtlTrans2 = TabStripDragMath.draggedTabTranslationPx(200f, slotIndex = 2, tabWidthPx = tabW, tabCount = count, isRtl = true)
        assertEquals(
            TabStripDragMath.slotLeftPx(1, tabW, count, isRtl = true) + rtlTrans1,
            TabStripDragMath.slotLeftPx(2, tabW, count, isRtl = true) + rtlTrans2,
            0.01f,
        )
    }

    @Test
    fun mergeInsertionIndexCoversStartMiddleAndEndInLtrAndRtl() {
        val tabW = 150f
        // 3 existing tabs -> valid insertion slots are 0, 1, 2, 3.
        assertEquals(0, TabStripDragMath.mergeInsertionIndex(pointerContentX = 10f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = false))
        assertEquals(1, TabStripDragMath.mergeInsertionIndex(pointerContentX = 140f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = false))
        assertEquals(2, TabStripDragMath.mergeInsertionIndex(pointerContentX = 290f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = false))
        assertEquals(3, TabStripDragMath.mergeInsertionIndex(pointerContentX = 500f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = false))
        assertEquals(0, TabStripDragMath.mergeInsertionIndex(pointerContentX = 100f, tabWidthPx = tabW, visibleTabCount = 0, isRtl = false))

        // RTL: near right edge (x = 440 out of 450) is slot 0; near left edge (x = 10) is slot 3.
        assertEquals(0, TabStripDragMath.mergeInsertionIndex(pointerContentX = 440f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = true))
        assertEquals(3, TabStripDragMath.mergeInsertionIndex(pointerContentX = 10f, tabWidthPx = tabW, visibleTabCount = 3, isRtl = true))
    }

    @Test
    fun tearPolicyBlocksPickerSingleWindowSelfTearAndActiveOpsDrain() {
        // Picker windows can never tear tabs out.
        assertFalse(TabStripDragMath.canTearTabOut(tabCount = 3, isPickerWindow = true, hasActiveFileOps = false, otherWindowCount = 2, connectedDisplayCount = 2))

        // Multi-tab normal window can always tear out.
        assertTrue(TabStripDragMath.canTearTabOut(tabCount = 2, isPickerWindow = false, hasActiveFileOps = true, otherWindowCount = 0, connectedDisplayCount = 1))

        // Single-tab normal window on 1 display with 0 other windows CANNOT pointlessly tear into a duplicate window.
        assertFalse(TabStripDragMath.canTearTabOut(tabCount = 1, isPickerWindow = false, hasActiveFileOps = false, otherWindowCount = 0, connectedDisplayCount = 1))

        // Single-tab normal window CAN tear when another Wayfinder window is open (to merge) OR when a second display is connected!
        assertTrue(TabStripDragMath.canTearTabOut(tabCount = 1, isPickerWindow = false, hasActiveFileOps = false, otherWindowCount = 1, connectedDisplayCount = 1))
        assertTrue(TabStripDragMath.canTearTabOut(tabCount = 1, isPickerWindow = false, hasActiveFileOps = false, otherWindowCount = 0, connectedDisplayCount = 2))

        // Single-tab window with active file operations CANNOT close itself by tearing its only tab away.
        assertFalse(TabStripDragMath.canTearTabOut(tabCount = 1, isPickerWindow = false, hasActiveFileOps = true, otherWindowCount = 2, connectedDisplayCount = 2))
    }

    @Test
    fun detachThresholdAndEdgeAutoScrollBehaveAccurately() {
        // Inside strip bounds -> false
        assertFalse(TabStripDragMath.shouldDetachFromStrip(120f, 24f, stripViewportWidthPx = 600f, stripHeightPx = 48f, verticalSlopPx = 32f, horizontalSlopPx = 56f))
        // Pulled downward past 48 + 32 = 80px -> true
        assertTrue(TabStripDragMath.shouldDetachFromStrip(120f, 88f, stripViewportWidthPx = 600f, stripHeightPx = 48f, verticalSlopPx = 32f, horizontalSlopPx = 56f))
        // Pulled upward above -32px -> true
        assertTrue(TabStripDragMath.shouldDetachFromStrip(120f, -40f, stripViewportWidthPx = 600f, stripHeightPx = 48f, verticalSlopPx = 32f, horizontalSlopPx = 56f))
        // Pulled far horizontally past strip ends -> true
        assertTrue(TabStripDragMath.shouldDetachFromStrip(670f, 24f, stripViewportWidthPx = 600f, stripHeightPx = 48f, verticalSlopPx = 32f, horizontalSlopPx = 56f))

        // Auto-scroll speed: 0 in center, negative near left edge, positive near right edge.
        assertEquals(0f, TabStripDragMath.autoScrollSpeedPxPerSec(250f, viewportWidthPx = 500f, edgeZonePx = 40f, maxSpeedPxPerSec = 600f), 0.01f)
        assertTrue(TabStripDragMath.autoScrollSpeedPxPerSec(8f, viewportWidthPx = 500f, edgeZonePx = 40f, maxSpeedPxPerSec = 600f) < -100f)
        assertTrue(TabStripDragMath.autoScrollSpeedPxPerSec(492f, viewportWidthPx = 500f, edgeZonePx = 40f, maxSpeedPxPerSec = 600f) > 100f)
    }

    @Test
    fun computeLaunchGeometryScalesPerTargetDisplayDensityAndClampsToWorkArea() {
        // External 1080p monitor at density = 1.0f (smaller DPI than 2.5x laptop panel)
        val extGeom = TabStripDragMath.computeLaunchGeometry(
            dropX = 1850,
            dropY = 1020,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            density = 1.0f,
        )
        assertNotNull(extGeom)
        assertTrue("Window must stay inside 1920x1080 display: $extGeom", extGeom!!.left >= 24 && extGeom.right <= 1920 - 24)
        assertTrue("Window must stay vertically inside 1080p display: $extGeom", extGeom.top >= 24 && extGeom.bottom <= 1080 - 24)
        assertEquals(1120, extGeom.width)
        assertEquals(780, extGeom.height)

        // Compact phone display (360x800 @ 1.0f -> narrower than 520dp minWidth) returns null so OS uses fullscreen.
        val phoneGeom = TabStripDragMath.computeLaunchGeometry(
            dropX = 180,
            dropY = 200,
            displayWidthPx = 360,
            displayHeightPx = 800,
            density = 1.0f,
        )
        assertNull(phoneGeom)
    }

    @Test
    fun registryCoordinatesMergeSingleUseClaimTtlCancelAndPrimaryPromotion() {
        var sourceCommitted = 0
        var sourceRestored = 0
        val mergedIntoWin2 = mutableListOf<Pair<TransferredTab, Int?>>()
        var win2BroughtToFront = 0

        TabTransferRegistry.registerWindow(
            windowId = 101,
            displayId = 0,
            title = "Downloads",
            tabCount = 1,
            isPicker = false,
            isPrimaryWindow = true,
            onMergeTab = { _, _ -> false },
        )
        TabTransferRegistry.registerWindow(
            windowId = 202,
            displayId = 2,
            title = "Work",
            tabCount = 2,
            isPicker = false,
            isPrimaryWindow = false,
            onMergeTab = { tab, idx ->
                mergedIntoWin2 += tab to idx
                true
            },
            onBringToFront = { win2BroughtToFront++ },
        )
        TabTransferRegistry.registerWindow(
            windowId = 303,
            displayId = 0,
            title = "Picker",
            tabCount = 1,
            isPicker = true,
            isPrimaryWindow = false,
            onMergeTab = { _, _ -> true },
        )

        // Picker window 303 must never appear in otherMergeableWindows.
        val candidates = TabTransferRegistry.otherMergeableWindows(excludeWindowId = 101)
        assertEquals(listOf(202), candidates.map { it.windowId })
        assertEquals(2, candidates.single().displayId)

        // Scenario A: Drag cancelled (e.g. Escape) restores source window and never commits removal.
        val snapA = TransferredTab(path = "/storage/emulated/0/Download", back = listOf("/storage/emulated/0"))
        val sessA = TabTransferRegistry.startDragSession(
            sourceWindowId = 101,
            sourceTabId = 1L,
            tabTitle = "Download",
            snapshot = snapA,
            canSpawnStandaloneWindow = false,
            sourceDisplayId = 0,
            nowMs = 1_000L,
            onCommitSource = { sourceCommitted++ },
            onRestoreSource = { sourceRestored++ },
        )
        assertTrue(TabTransferRegistry.cancelDragSession(sessA.sessionId))
        assertEquals(0, sourceCommitted)
        assertEquals(1, sourceRestored)
        assertNull(TabTransferRegistry.claimForNewWindow(sessA.sessionId, nowMs = 1_050L))

        // Scenario B: Merging last tab of primary window 101 into secondary window 202 promotes target to primary!
        val snapB = TransferredTab(path = "/storage/emulated/0/Download", promoteTargetToPrimary = true)
        val sessB = TabTransferRegistry.startDragSession(
            sourceWindowId = 101,
            sourceTabId = 1L,
            tabTitle = "Download",
            snapshot = snapB,
            canSpawnStandaloneWindow = false,
            sourceDisplayId = 0,
            nowMs = 2_000L,
            onCommitSource = { sourceCommitted++ },
            onRestoreSource = { sourceRestored++ },
        )
        assertTrue(TabTransferRegistry.completeMergeIntoWindow(sessB.sessionId, targetWindowId = 202, insertIndex = 1, nowMs = 2_100L))
        assertEquals(1, sourceCommitted)
        assertEquals(1, sourceRestored)
        assertEquals(1, mergedIntoWin2.size)
        assertTrue(mergedIntoWin2.single().first.promoteTargetToPrimary)
        assertEquals(1, mergedIntoWin2.single().second)
        assertEquals(1, win2BroughtToFront)
        // Double-claim after merge must return null!
        assertNull(TabTransferRegistry.claimForNewWindow(sessB.sessionId, nowMs = 2_200L))

        // Scenario C: Tear-off into new window is single-use and enforces TTL expiry.
        val snapC = TransferredTab(path = "/storage/emulated/0/Pictures", viewMode = ViewMode.Grid, sortKey = SortKey.Modified, sortAsc = false)
        val directId = TabTransferRegistry.createDirectNewWindowSession(snapC, nowMs = 5_000L)
        // Expired claim fails:
        val expiredId = TabTransferRegistry.createDirectNewWindowSession(snapC, nowMs = 1_000L)
        assertNull(TabTransferRegistry.claimForNewWindow(expiredId, nowMs = 1_000L + TAB_TRANSFER_SESSION_TTL_MS + 1L))
        // Valid claim succeeds once, then second claim returns null (e.g. Recents relaunch):
        val claimed = TabTransferRegistry.claimForNewWindow(directId, nowMs = 5_500L)
        assertEquals(snapC, claimed)
        assertNull(TabTransferRegistry.claimForNewWindow(directId, nowMs = 5_501L))
    }
}
