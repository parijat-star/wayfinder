// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xxhdpi")
class CompactPortraitLayoutTest {
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

    @Test
    fun compactPortraitCollapsesSearchToKeepPathBarReadableAndExpandsOnDemand() {
        val harness = VmHarness().also { h = it }
        harness.dir("Projects")
        harness.file("Projects/report_final.txt", "hello")
        harness.navigate(harness.pathOf("Projects"))

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        // PathBar breadcrumb text ("Projects") is displayed inside a generous compact PathBar (>= 120.dp)
        rule.onNodeWithContentDescription("Address bar").assertWidthIsAtLeast(120.dp)
        rule.onNode(hasText("Projects") and hasAnyAncestor(hasContentDescription("Address bar")), useUnmergedTree = true)
            .assertIsDisplayed()

        // Tap the collapsed "Search files" icon button to expand the full-width search bar
        rule.onNodeWithContentDescription("Search files").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Close search").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search files").performTextInput("report")
        harness.settle()
        rule.waitForIdle()
        assertEquals("report", harness.s.active.query)

        // Closing compact search clears the query and restores the PathBar + drawer menu button
        rule.onNodeWithContentDescription("Close search").performClick()
        harness.settle()
        rule.waitForIdle()
        assertEquals("", harness.s.active.query)
        rule.onNodeWithContentDescription("Open navigation drawer").assertIsDisplayed()
        rule.onNodeWithContentDescription("Address bar").assertWidthIsAtLeast(120.dp)
    }

    @Test
    fun compactPortraitModalNavigationDrawerOpensNavigatesAndAutoCloses() {
        val harness = VmHarness().also { h = it }
        val pinnedDir = harness.dir("PinnedDocs")
        harness.file("PinnedDocs/notes.md", "# Notes")
        harness.vm.pin(pinnedDir.path)
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        // Sidebar is hidden inline on < 600.dp viewports until the modal drawer is opened
        assertFalse(harness.s.compactSidebarOpen)
        rule.onAllNodesWithText("Quick access").assertCountEquals(0)

        // Open the modal navigation drawer via the toolbar menu button
        rule.onNodeWithContentDescription("Open navigation drawer").performClick()
        rule.waitForIdle()

        assertTrue(harness.s.compactSidebarOpen)
        rule.onNodeWithContentDescription("Navigation drawer").assertIsDisplayed()
        rule.onNodeWithText("Quick access").assertIsDisplayed()
        val drawerPinNode = rule.onNode(
            hasText("PinnedDocs") and hasAnyAncestor(hasContentDescription("Navigation drawer")),
        )
        drawerPinNode.assertIsDisplayed()

        // Selecting a pinned folder navigates to it and automatically dismisses the modal drawer
        drawerPinNode.performClick()
        harness.settle()
        rule.waitForIdle()

        assertEquals(pinnedDir.path, harness.s.active.path)
        assertFalse(harness.s.compactSidebarOpen)
        rule.onAllNodesWithContentDescription("Navigation drawer").assertCountEquals(0)
    }
}
