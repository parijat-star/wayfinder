// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.Display
import android.view.DragEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.data.ConnectedDisplays
import com.droid.wayfinder.data.DisplayTarget
import com.droid.wayfinder.data.EXTRA_TEAR_SESSION_ID
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.TabTransferRegistry
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.CONTEXT_SUBMENU_CARD_TAG
import com.droid.wayfinder.ui.DETACHED_TAB_GHOST_TAG
import com.droid.wayfinder.ui.MERGE_PREVIEW_TAB_TAG
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.WayfinderTabDragShadowBuilder
import com.droid.wayfinder.ui.buildTabClipDescription
import com.droid.wayfinder.ui.openTransferredTabWindow
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.tabMenu
import com.droid.wayfinder.ui.theme.WayfinderTheme
import kotlin.math.abs
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
class TabTearingAndMovingUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val harnesses = mutableListOf<VmHarness>()

    private fun newHarness(extras: Map<String, Any?> = emptyMap()): VmHarness =
        VmHarness(extras).also { harnesses += it }

    @After
    fun tearDown() {
        ConnectedDisplays.overrideForTest = null
        TabTransferRegistry.resetForTest()
        harnesses.forEach { it.close() }
        harnesses.clear()
    }

    private fun idle(vararg hs: VmHarness) {
        rule.waitForIdle()
        hs.forEach { it.settle() }
        rule.waitForIdle()
    }

    @Test
    fun inStripMouseDragSmoothlyMovesTabBeforeThresholdAndSwapsAcrossNeighbors() {
        val h = newHarness()
        val alpha = h.dir("AlphaTab")
        val beta = h.dir("BetaTab")
        val gamma = h.dir("GammaTab")
        h.navigate(alpha.path)
        h.vm.newTab(beta.path, activate = false)
        h.vm.newTab(gamma.path, activate = false)
        idle(h)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(h.vm)
            }
        }
        idle(h)

        val alphaNode = rule.onAllNodes(hasText("AlphaTab"))[0]
        val initialAlphaBounds = alphaNode.fetchSemanticsNode().boundsInRoot
        val betaBounds = rule.onAllNodes(hasText("BetaTab"))[0].fetchSemanticsNode().boundsInRoot
        val tabW = initialAlphaBounds.width

        // Start dragging AlphaTab rightward by 35% of tab width (below swap threshold) — AlphaTab MUST visibly move!
        alphaNode.performMouseInput {
            moveTo(Offset(width * 0.35f, height * 0.5f))
            press()
            moveBy(Offset(tabW * 0.35f, 0f))
        }
        rule.waitForIdle()

        val midDragBounds = rule.onAllNodes(hasText("AlphaTab"))[0].fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected dragged tab to visibly translate rightward during sub-threshold drag (before=${initialAlphaBounds.left}, mid=${midDragBounds.left})",
            midDragBounds.left > initialAlphaBounds.left + tabW * 0.2f,
        )
        assertEquals(listOf(alpha.path, beta.path, gamma.path), h.s.tabs.map { it.path })

        // Continue dragging past BetaTab's midpoint -> tabs swap live to [BetaTab, AlphaTab, GammaTab]!
        alphaNode.performMouseInput {
            moveBy(Offset(tabW * 0.85f, 0f))
        }
        idle(h)
        assertEquals(listOf(beta.path, alpha.path, gamma.path), h.s.tabs.map { it.path })

        alphaNode.performMouseInput {
            release()
        }
        idle(h)
        assertEquals(listOf(beta.path, alpha.path, gamma.path), h.s.tabs.map { it.path })

        // Clicking Close ('x') on AlphaTab closes it cleanly instead of initiating a drag.
        val closeAlphaMatcher = SemanticsMatcher("Close tab AlphaTab") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Close tab AlphaTab"
        }
        rule.onNode(closeAlphaMatcher, useUnmergedTree = true).performMouseInput { click() }
        idle(h)
        assertEquals(listOf(beta.path, gamma.path), h.s.tabs.map { it.path })
    }

    @Test
    fun tearingTabOutShowsDetachedGhostRestoresOnEscapeAndPreservesFullStateOnSpawn() {
        val h = newHarness()
        val keepDir = h.dir("KeepFolder")
        val rootSub = h.dir("ParentHistory")
        val tearDir = h.dir("ParentHistory/TearFolder")
        val tearFile = h.file("ParentHistory/TearFolder/selected.txt", "important")

        h.navigate(keepDir.path)
        h.vm.newTab(rootSub.path, activate = true)
        h.navigate(tearDir.path)
        h.vm.setViewMode(ViewMode.Grid)
        h.vm.setSort(SortKey.Modified, asc = false)
        h.vm.click(tearFile.path, ctrl = false, shift = false)
        idle(h)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(h.vm)
            }
        }
        idle(h)

        // 1. Pull TearFolder downward out of the tab strip, then press Escape -> must restore cleanly!
        val tearTabNode = rule.onAllNodes(hasText("TearFolder"))[0]
        tearTabNode.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(12f, 0f))
            moveBy(Offset(0f, 160f))
        }
        rule.waitForIdle()

        assertEquals("Detached tab should hide from visibleTabs mid-drag", 1, h.s.visibleTabs.size)
        rule.onNodeWithTag(DETACHED_TAB_GHOST_TAG).assertIsDisplayed()

        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
        idle(h)
        assertNull("Escape must clear detachedTabId", h.s.detachedTabId)
        assertEquals(2, h.s.visibleTabs.size)
        assertEquals(listOf(keepDir.path, tearDir.path), h.s.tabs.map { it.path })

        rule.onAllNodes(isRoot())[0].performMouseInput { release() }
        idle(h)

        // 2. Pull TearFolder downward out of the strip and release -> commits tear-off and spawns new window!
        h.events.clear()
        val tearTabAgain = rule.onAllNodes(hasText("TearFolder"))[0]
        tearTabAgain.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(14f, 0f))
            moveBy(Offset(20f, 180f))
            release()
        }
        idle(h)

        assertEquals("Source window should retain only KeepFolder", listOf(keepDir.path), h.s.tabs.map { it.path })
        val launchEv = h.events.filterIsInstance<UiEvent.LaunchTransferredTabWindow>().single()
        assertEquals(tearDir.path, launchEv.fallbackPath)

        // Spawned window claims the session and preserves back-history, Grid view, Modified desc sort, and selection!
        val spawned = newHarness(
            mapOf(
                BrowserViewModel.EXTRA_NEW_WINDOW to true,
                BrowserViewModel.EXTRA_PATH to launchEv.fallbackPath,
                EXTRA_TEAR_SESSION_ID to launchEv.sessionId,
            ),
        )
        idle(spawned)
        assertEquals(tearDir.path, spawned.s.active.path)
        assertTrue("Back history preserved across tear-off", spawned.s.active.canGoBack)
        assertEquals(rootSub.path, spawned.s.active.back.last())
        assertEquals(ViewMode.Grid, spawned.s.active.viewMode)
        assertEquals(SortKey.Modified, spawned.s.active.sortKey)
        assertFalse(spawned.s.active.sortAsc)
        assertEquals(setOf(tearFile.path), spawned.s.active.selection)

        // Replay from Android Recents (session already claimed) falls back safely to EXTRA_PATH without crashing!
        val recentsReplay = newHarness(
            mapOf(
                BrowserViewModel.EXTRA_NEW_WINDOW to true,
                BrowserViewModel.EXTRA_PATH to launchEv.fallbackPath,
                EXTRA_TEAR_SESSION_ID to launchEv.sessionId,
            ),
        )
        idle(recentsReplay)
        assertEquals(tearDir.path, recentsReplay.s.active.path)
    }

    @Test
    fun draggingTabsBetweenTwoWindowsShowsMergeSlotPreviewAndPromotesPrimaryOnLastTabMerge() {
        val winA = newHarness() // Primary window (secondaryWindow == false)
        val dirA1 = winA.dir("PrimaryOne")
        val dirA2 = winA.dir("PrimaryTwo")
        winA.navigate(dirA1.path)
        winA.vm.newTab(dirA2.path, activate = true)
        idle(winA)

        val dirB1 = winA.dir("SecondaryOne")
        val winB = newHarness(
            mapOf(
                BrowserViewModel.EXTRA_NEW_WINDOW to true,
                BrowserViewModel.EXTRA_PATH to dirB1.path,
            ),
        )
        assertTrue(winB.s.secondaryWindow)
        idle(winB)

        rule.setContent {
            WayfinderTheme(false) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { AppRoot(winA.vm) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { AppRoot(winB.vm) }
                }
            }
        }
        idle(winA, winB)

        val primaryTwoNode = rule.onAllNodes(hasText("PrimaryTwo"))[0]
        val secondaryOneBounds = rule.onAllNodes(hasText("SecondaryOne"))[0].fetchSemanticsNode().boundsInRoot
        val primaryTwoBounds = primaryTwoNode.fetchSemanticsNode().boundsInRoot

        // Drag PrimaryTwo out of winA downward, then across into winB's tab strip before SecondaryOne!
        val dxToWinBStart = (secondaryOneBounds.left + 12f) - primaryTwoBounds.center.x
        primaryTwoNode.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 120f))
            moveBy(Offset(dxToWinBStart, -120f))
        }
        rule.waitForIdle()
        assertTrue(
            "Expected live merge preview slot inside target window's tab strip",
            rule.onAllNodesWithTag(MERGE_PREVIEW_TAB_TAG).fetchSemanticsNodes().isNotEmpty(),
        )

        rule.onAllNodes(isRoot())[0].performMouseInput { release() }
        idle(winA, winB)

        assertEquals(listOf(dirA1.path), winA.s.tabs.map { it.path })
        assertEquals("PrimaryTwo inserted at slot 0 ahead of SecondaryOne", listOf(dirA2.path, dirB1.path), winB.s.tabs.map { it.path })

        // Edge case: Drag winA's LAST remaining tab (PrimaryOne) into winB -> winA finishes and winB promotes to Primary!
        winA.events.clear()
        val primaryOneNode = rule.onAllNodes(hasText("PrimaryOne"))[0]
        val p1Bounds = primaryOneNode.fetchSemanticsNode().boundsInRoot
        val secBoundsAfter = rule.onAllNodes(hasText("SecondaryOne"))[0].fetchSemanticsNode().boundsInRoot
        val dxLast = (secBoundsAfter.right - 8f) - p1Bounds.center.x
        primaryOneNode.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 120f))
            moveBy(Offset(dxLast, -120f))
            release()
        }
        idle(winA, winB)

        assertTrue("Drained 1-tab source window must emit UiEvent.Finish", winA.events.any { it is UiEvent.Finish })
        assertFalse("Target secondary window must promote to primary session owner", winB.s.secondaryWindow)
        assertEquals(listOf(dirA2.path, dirB1.path, dirA1.path), winB.s.tabs.map { it.path })
        assertEquals(
            "Promoted window must persist all merged tabs into Settings",
            listOf(dirA2.path, dirB1.path, dirA1.path),
            Settings(winB.app).tabs,
        )
    }

    @Test
    fun contextMenuKeyboardShortcutsConnectedDisplaysAndDisconnectedMonitorRollbackWorkCleanly() {
        val displays = mutableListOf(
            DisplayTarget(Display.DEFAULT_DISPLAY, "Built-in display", 2560, 1600, 2.0f, isDefault = true),
            DisplayTarget(2, "DELL U2723QE", 1920, 1080, 1.0f, isDefault = false),
        )
        ConnectedDisplays.overrideForTest = { displays.toList() }

        val h = newHarness()
        val d1 = h.dir("TabOne")
        val d2 = h.dir("TabTwo")
        val d3 = h.dir("TabThree")
        h.navigate(d1.path)
        h.vm.newTab(d2.path, activate = true)
        h.vm.newTab(d3.path, activate = true)
        idle(h)

        // Register a second window residing on External Display 2.
        val mergedFromMenu = mutableListOf<String>()
        TabTransferRegistry.registerWindow(
            windowId = 777,
            displayId = 2,
            title = "Projects",
            tabCount = 2,
            isPicker = false,
            isPrimaryWindow = false,
            onMergeTab = { snap, _ ->
                mergedFromMenu += snap.path
                true
            },
        )

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(h.vm)
            }
        }
        idle(h)

        // Keyboard Ctrl+Shift+PageUp moves active TabThree left (index 2 -> 1); Ctrl+Shift+PageDown moves it back (1 -> 2).
        rule.onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    pressKey(Key.PageUp)
                }
            }
        }
        idle(h)
        assertEquals(listOf(d1.path, d3.path, d2.path), h.s.tabs.map { it.path })

        rule.onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    pressKey(Key.PageDown)
                }
            }
        }
        idle(h)
        assertEquals(listOf(d1.path, d2.path, d3.path), h.s.tabs.map { it.path })

        // Verify tabMenu items & submenus across connected displays and other windows.
        val tabThreeId = h.s.tabs[2].id
        val menuItems = tabMenu(h.vm, tabThreeId, windowId = 111, currentDisplayId = Display.DEFAULT_DISPLAY, connectedDisplays = displays)
            .filterIsInstance<MenuEntry.Item>()
        val labels = menuItems.map { it.label }
        assertTrue(labels.containsAll(listOf(
            "New tab to the right",
            "Duplicate tab",
            "Move tab left",
            "Move tab right",
            "Move tab to start",
            "Move tab to end",
            "Move tab to new window",
            "Open in new window",
            "Move tab to another window",
            "Move tab to display",
            "Close tab",
            "Close other tabs",
            "Close tabs to the right",
        )))

        // Move tab to start via menu item.
        menuItems.single { it.label == "Move tab to start" }.action()
        idle(h)
        assertEquals(listOf(d3.path, d1.path, d2.path), h.s.tabs.map { it.path })

        // Verify "Move tab to another window" submenu includes display disambiguation ("Projects (DELL U2723QE)").
        val otherWinSub = menuItems.single { it.label == "Move tab to another window" }.submenu!!.invoke()
            .filterIsInstance<MenuEntry.Item>()
        val targetWinItem = otherWinSub.single { it.label == "Projects (DELL U2723QE)" }
        targetWinItem.action()
        idle(h)
        assertEquals(listOf(d3.path), mergedFromMenu)
        assertEquals(listOf(d1.path, d2.path), h.s.tabs.map { it.path })

        // Verify Command Palette also exposes connected-display & cross-window commands.
        val palCmds = paletteCommands(h.vm, h.s, windowId = 111, currentDisplayId = Display.DEFAULT_DISPLAY, connectedDisplays = displays)
        assertTrue(palCmds.any { it.name == "Move tab to display: DELL U2723QE" })
        assertTrue(palCmds.any { it.name == "Move tab to window: Projects (DELL U2723QE)" })

        // Edge case: Unplugging DELL U2723QE right before openTransferredTabWindow executes rolls back tab with zero loss!
        val hRollback = newHarness(mapOf(BrowserViewModel.EXTRA_NEW_WINDOW to true))
        val rA = hRollback.dir("RollA")
        val rB = hRollback.dir("RollB")
        hRollback.navigate(rA.path)
        hRollback.vm.newTab(rB.path, activate = true)
        idle(hRollback)
        assertEquals(2, hRollback.s.tabs.size)
        hRollback.events.clear()
        val tabBId = hRollback.s.activeId
        assertTrue(hRollback.vm.moveTabToDisplay(tabBId, targetDisplayId = 2))
        val pendingEv = hRollback.events.filterIsInstance<UiEvent.LaunchTransferredTabWindow>().single()
        assertEquals(1, hRollback.s.tabs.size) // Provisionally detached

        // Yank Display 2 before Activity start!
        displays.removeAll { it.displayId == 2 }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(openTransferredTabWindow(ctx, pendingEv))
        idle(hRollback)
        assertEquals("Disconnected target display must restore tab to source window", listOf(rA.path, rB.path), hRollback.s.tabs.map { it.path })

        // Security check: Unsigned external intent carrying EXTRA_TEAR_SESSION_ID is sanitized by IntentGuard.
        val spoofed = Intent(Intent.ACTION_MAIN).putExtra(EXTRA_TEAR_SESSION_ID, "0123456789abcdef0123456789abcdef")
        val sanitized = IntentGuard.sanitize(ctx, spoofed)
        assertNull(sanitized.getStringExtra(EXTRA_TEAR_SESSION_ID))

        // Shadow check: WayfinderTabDragShadowBuilder renders valid mini-window preview pixels.
        val shadow = WayfinderTabDragShadowBuilder(ctx, "Downloads")
        val size = Point()
        val touch = Point()
        shadow.onProvideShadowMetrics(size, touch)
        assertTrue(size.x > 0 && size.y > 0)
        val bmp = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        shadow.onDrawShadow(Canvas(bmp))
        var drawnPixels = 0
        for (y in 0 until bmp.height step 3) {
            for (x in 0 until bmp.width step 3) {
                if (bmp.getPixel(x, y) != 0) drawnPixels++
            }
        }
        assertTrue("Expected mini-window tab drag shadow pixels, got $drawnPixels", drawnPixels > 50)
    }

    private fun obtainCrossActivityDragEvent(
        action: Int,
        x: Float,
        y: Float,
        description: ClipDescription?,
        clipData: ClipData?,
        result: Boolean = false,
    ): DragEvent {
        // Construct an Android DragEvent with localState = null (exact cross-Activity WindowManager behavior).
        val m = DragEvent::class.java.declaredMethods
            .filter { it.name == "obtain" && it.parameterCount >= 6 }
            .maxBy { it.parameterCount }
        m.isAccessible = true
        val args = arrayOfNulls<Any>(m.parameterCount)
        m.parameterTypes.forEachIndexed { idx, p ->
            args[idx] = when {
                idx == 0 && p == Int::class.javaPrimitiveType -> action
                idx == 1 && p == Float::class.javaPrimitiveType -> x
                idx == 2 && p == Float::class.javaPrimitiveType -> y
                p == Float::class.javaPrimitiveType -> 0f
                p == Int::class.javaPrimitiveType -> 0
                p == ClipDescription::class.java -> description
                p == ClipData::class.java -> clipData
                p == Boolean::class.javaPrimitiveType -> result
                else -> null // localState = null, surface/permissions = null
            }
        }
        return m.invoke(null, *args) as DragEvent
    }

    @Test
    fun touchDragAvoidsMenuFlashCrossActivityDragMergesWithNullLocalStateAndHoverExpandsAlignedSubmenu() {
        val targetWin = newHarness()
        val dTarget1 = targetWin.dir("WinOne")
        val dTarget2 = targetWin.dir("WinTwo")
        targetWin.navigate(dTarget1.path)
        targetWin.vm.newTab(dTarget2.path, activate = true)
        idle(targetWin)

        val sourceWin = newHarness(mapOf(BrowserViewModel.EXTRA_NEW_WINDOW to true))
        val dIncoming = sourceWin.dir("IncomingTab")
        val dRemaining = sourceWin.dir("RemainingTab")
        sourceWin.navigate(dIncoming.path)
        sourceWin.vm.newTab(dRemaining.path, activate = true)
        idle(sourceWin)

        var hostView: View? = null
        rule.setContent {
            hostView = LocalView.current
            WayfinderTheme(false) {
                AppRoot(targetWin.vm)
            }
        }
        idle(targetWin)

        // 1. Touch press-and-drag well below 400ms longPressTimeoutMillis reorders tabs WITHOUT opening the context menu!
        val winOneNode = rule.onAllNodes(hasText("WinOne"))[0]
        val winOneWidth = winOneNode.fetchSemanticsNode().boundsInRoot.width
        winOneNode.performTouchInput {
            down(center)
            advanceEventTime(160L)
            moveBy(Offset(winOneWidth * 1.15f, 0f))
        }
        rule.waitForIdle()
        assertTrue(
            "Touch tab drag must NOT flash the tab context menu before longPressTimeoutMillis",
            rule.onAllNodes(hasText("New tab to the right")).fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(listOf(dTarget2.path, dTarget1.path), targetWin.s.tabs.map { it.path })
        rule.onAllNodes(isRoot())[0].performTouchInput { up() }
        idle(targetWin)

        // 2. Real Android cross-Activity DragEvent stream (with localState == null!) merges into existing window!
        val incomingTabId = sourceWin.s.tabs.first { it.path == dIncoming.path }.id
        val snap = sourceWin.vm.snapshotTabForTransfer(incomingTabId, willDrainWindow = false)!!
        sourceWin.vm.setDetachedTab(incomingTabId)
        val session = TabTransferRegistry.startDragSession(
            sourceWindowId = 999_001,
            sourceTabId = incomingTabId,
            tabTitle = "IncomingTab",
            snapshot = snap,
            canSpawnStandaloneWindow = true,
            sourceDisplayId = Display.DEFAULT_DISPLAY,
            onCommitSource = { sourceWin.vm.commitDetachedTab(incomingTabId) },
            onRestoreSource = { sourceWin.vm.setDetachedTab(null) },
        )
        val desc = buildTabClipDescription("IncomingTab", session.sessionId, includeIntentMime = true)
        val clip = ClipData(desc, ClipData.Item("IncomingTab"))
        val view = hostView!!

        rule.runOnUiThread {
            assertTrue(
                "Target window AppRoot must subscribe to cross-Activity tab DragEvent even when localState == null",
                view.dispatchDragEvent(obtainCrossActivityDragEvent(DragEvent.ACTION_DRAG_STARTED, 0f, 0f, desc, null)),
            )
            // Hover at x = 16px, y = 180px (inside existing window body!) -> previews slot 0 in target tab strip!
            view.dispatchDragEvent(obtainCrossActivityDragEvent(DragEvent.ACTION_DRAG_LOCATION, 16f, 180f, desc, null))
        }
        rule.waitForIdle()
        assertTrue(
            "Hovering cross-Activity tab drag over existing window must display MergePreviewTab",
            rule.onAllNodesWithTag(MERGE_PREVIEW_TAB_TAG).fetchSemanticsNodes().isNotEmpty(),
        )

        var dropAccepted = false
        rule.runOnUiThread {
            dropAccepted = view.dispatchDragEvent(obtainCrossActivityDragEvent(DragEvent.ACTION_DROP, 16f, 180f, desc, clip))
            view.dispatchDragEvent(obtainCrossActivityDragEvent(DragEvent.ACTION_DRAG_ENDED, 0f, 0f, null, null, result = dropAccepted))
        }
        idle(targetWin, sourceWin)
        assertTrue("Existing window must accept cross-Activity tab ACTION_DROP", dropAccepted)
        assertEquals(listOf(dIncoming.path, dTarget2.path, dTarget1.path), targetWin.s.tabs.map { it.path })
        assertEquals(listOf(dRemaining.path), sourceWin.s.tabs.map { it.path })

        // 3. Hovering over "Move tab to another window" expands the side-by-side submenu aligned with the parent item!
        val mergedIntoOther = mutableListOf<String>()
        TabTransferRegistry.registerWindow(
            windowId = 888_002,
            displayId = Display.DEFAULT_DISPLAY,
            title = "DownloadsWindow",
            tabCount = 2,
            isPicker = false,
            isPrimaryWindow = false,
            onMergeTab = { transferred, _ ->
                mergedIntoOther += transferred.path
                true
            },
        )

        rule.onAllNodes(hasText("IncomingTab"))[0].performMouseInput { rightClick(center) }
        idle(targetWin)

        val moveOtherRow = rule.onNodeWithText("Move tab to another window").assertIsDisplayed()
        val parentRowBounds = moveOtherRow.fetchSemanticsNode().boundsInRoot
        // Hover over "Move tab to another window" WITHOUT clicking!
        moveOtherRow.performMouseInput { moveTo(center) }
        idle(targetWin)

        val subCardBounds = rule.onNodeWithTag(CONTEXT_SUBMENU_CARD_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val childItemNode = rule.onNodeWithText("DownloadsWindow").assertIsDisplayed()
        val childRowBounds = childItemNode.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Submenu first item top (${childRowBounds.top}) should align vertically with parent row top (${parentRowBounds.top})",
            abs(childRowBounds.top - parentRowBounds.top) <= 24f,
        )
        assertTrue(
            "Submenu (${subCardBounds.left}) should expand horizontally beside parent item (${parentRowBounds.right})",
            subCardBounds.left >= parentRowBounds.right - 16f || subCardBounds.right <= parentRowBounds.left + 16f,
        )

        childItemNode.performClick()
        idle(targetWin)
        assertEquals(listOf(dIncoming.path), mergedIntoOther)
        assertEquals(listOf(dTarget2.path, dTarget1.path), targetWin.s.tabs.map { it.path })
    }
}
