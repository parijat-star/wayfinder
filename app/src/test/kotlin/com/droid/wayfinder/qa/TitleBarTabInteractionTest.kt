// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class TitleBarTabInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    @Test
    fun titleBarRegistersCaptionExclusionRectAndHandlesMouseAndTouchTabActions() {
        val harness = VmHarness().also { h = it }
        val folderA = harness.dir("AlphaTab")
        val folderB = harness.dir("BetaTab")
        harness.navigate(folderA.path)

        var composeView: View? = null
        rule.setContent {
            composeView = LocalView.current
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        val view = composeView!!
        val rectsSingle = view.systemGestureExclusionRects
        assertTrue(
            "Expected non-empty systemGestureExclusionRects for transparent desktop caption bar",
            rectsSingle.isNotEmpty(),
        )
        val tabRect = rectsSingle.first()
        assertTrue("Exclusion rect should have positive width and height, got $tabRect", tabRect.width() > 0 && tabRect.height() > 0)
        assertTrue(
            "Exclusion rect (${tabRect.right}) must leave empty caption space draggable across window width (${view.width})",
            tabRect.right < view.width / 2,
        )

        // Mouse left-click on '+' creates and activates a second tab.
        val firstTabId = harness.s.activeId
        rule.onNodeWithContentDescription("New tab (Ctrl+T)").performMouseInput { click() }
        harness.settle()
        rule.waitForIdle()
        assertEquals(2, harness.s.tabs.size)
        val secondTabId = harness.s.activeId
        assertTrue(secondTabId != firstTabId)

        harness.navigate(folderB.path)
        rule.waitForIdle()

        // Mouse left-click on inactive first tab ("AlphaTab") switches activeId back to firstTabId.
        rule.onAllNodes(hasText("AlphaTab"))[0].performMouseInput { click() }
        harness.settle()
        rule.waitForIdle()
        assertEquals(firstTabId, harness.s.activeId)

        // Mouse right-click on second tab ("BetaTab") opens the tab context menu.
        rule.onAllNodes(hasText("BetaTab"))[0].performMouseInput { rightClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Duplicate tab").performTouchInput { click() }
        harness.settle()
        rule.waitForIdle()
        assertEquals(3, harness.s.tabs.size)

        // Mouse left-click on 'Close tab' ('x') closes one tab cleanly.
        rule.onAllNodesWithContentDescription("Close tab")[2].performMouseInput { click() }
        harness.settle()
        rule.waitForIdle()
        assertEquals(2, harness.s.tabs.size)

        // Even with many tabs open, horizontalScroll keeps the exclusion rect bounded inside the tab strip viewport.
        repeat(12) { harness.vm.newTab(folderA.path) }
        harness.settle()
        rule.waitForIdle()
        val rectsMany = view.systemGestureExclusionRects
        assertTrue(rectsMany.isNotEmpty())
        assertTrue(
            "Scrollable tab strip exclusion rect (${rectsMany.first().right}) must not exceed window width (${view.width})",
            rectsMany.first().right <= view.width,
        )
    }
}
