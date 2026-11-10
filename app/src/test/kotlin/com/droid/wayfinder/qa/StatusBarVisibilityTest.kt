// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.Toolbar
import com.droid.wayfinder.ui.computeCaptionMetrics
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.theme.WayfinderTheme
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
class StatusBarVisibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
    }

    @After
    fun tearDown() {
        MainActivity.hasAccessOverride = null
        SharedUi.setHideStatusBar(false)
        Settings(ApplicationProvider.getApplicationContext()).statusBarVisible = true
        h?.close()
    }

    @Test
    fun computeCaptionMetricsHandlesStatusBarCutoutAndDesktopCaption() {
        val density = Density(density = 2f, fontScale = 1f)

        // Non-DeX fullscreen mode (capTop == 0, statusTop == 56px = 28.dp):
        // reserves 28.dp topInset above the 44.dp tab strip (total height = 72.dp).
        val fullscreen = computeCaptionMetrics(capTopPx = 0, statusTopPx = 56, cutoutTopPx = 0, startPx = 0, endPx = 0, density = density)
        assertEquals(28.dp, fullscreen.topInset)
        assertEquals(72.dp, fullscreen.height)

        // Portrait punch-hole taller than status bar (statusTop == 56px, cutoutTop == 113px = 56.5.dp):
        // reserves max(statusTopPx, cutoutTopPx) so camera cutout never overlaps tabs.
        val cutout = computeCaptionMetrics(capTopPx = 0, statusTopPx = 56, cutoutTopPx = 113, startPx = 0, endPx = 0, density = density)
        assertEquals(56.5.dp, cutout.topInset)
        assertEquals(100.5.dp, cutout.height)

        // Hidden system status bar in fullscreen mode (capTop == 0, statusTop == 0, cutoutTop == 113px):
        // collapses topInset to 0.dp so the tab strip reclaims the top edge.
        val hiddenStatus = computeCaptionMetrics(capTopPx = 0, statusTopPx = 0, cutoutTopPx = 113, startPx = 0, endPx = 0, density = density)
        assertEquals(0.dp, hiddenStatus.topInset)
        assertEquals(44.dp, hiddenStatus.height)

        // Desktop / DeX caption bar mode (capTop == 96px = 48.dp, statusTop == 56px):
        // tabs live inside the caption bar with topInset = 0.dp and end inset for window controls.
        val desktop = computeCaptionMetrics(capTopPx = 96, statusTopPx = 56, cutoutTopPx = 0, startPx = 0, endPx = 280, density = density)
        assertEquals(0.dp, desktop.topInset)
        assertEquals(48.dp, desktop.height)
        assertEquals(140.dp, desktop.end)
    }

    @Test
    fun systemStatusBarAndBottomStatusBarTogglesWorkIndependently() {
        val harness = VmHarness().also { h = it }
        harness.dir("sb_test")
        harness.file("sb_test/alpha.txt", "hello")
        harness.navigate(harness.pathOf("sb_test"))
        assertFalse(harness.s.hideStatusBar)
        assertTrue(harness.s.statusBarVisible)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        // Bottom status bar starts visible ("1 item")
        rule.onNodeWithText("1 item").assertIsDisplayed()

        // Toggle bottom "Status bar" OFF via Overflow menu ("More" -> "Status bar")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Status bar").performScrollTo().performClick()
        harness.settle()
        rule.waitForIdle()

        assertFalse(harness.s.statusBarVisible)
        assertFalse(Settings(ApplicationProvider.getApplicationContext()).statusBarVisible)
        rule.onAllNodesWithText("1 item").assertCountEquals(0)

        // Restore bottom "Status bar" via Command Palette ("Toggle status bar")
        val toggleBottomBar = paletteCommands(harness.vm, harness.s).firstOrNull { it.name == "Toggle status bar" }
        assertNotNull("Expected 'Toggle status bar' in Command Palette", toggleBottomBar)
        toggleBottomBar!!.action()
        harness.settle()
        rule.waitForIdle()

        assertTrue(harness.s.statusBarVisible)
        assertTrue(Settings(ApplicationProvider.getApplicationContext()).statusBarVisible)
        rule.onNodeWithText("1 item").assertIsDisplayed()

        // Fullscreen mode offers "Hide system status bar" in both Overflow and Command Palette
        val hideCmd = paletteCommands(harness.vm, harness.s, hasDesktopCaption = false).firstOrNull { it.name == "Hide system status bar" }
        assertNotNull("Expected 'Hide system status bar' in Command Palette when no desktop caption", hideCmd)
        assertNull(
            "Desktop caption windows must omit 'Hide system status bar'",
            paletteCommands(harness.vm, harness.s, hasDesktopCaption = true).firstOrNull { it.name == "Hide system status bar" },
        )

        // Toggle ON via Overflow menu ("More" -> "Hide system status bar")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Hide system status bar").performScrollTo().performClick()
        harness.settle()
        rule.waitForIdle()

        assertTrue(harness.s.hideStatusBar)
        assertTrue(Settings(ApplicationProvider.getApplicationContext()).hideStatusBar)
        assertTrue(harness.toasts().contains("System status bar hidden"))

        // Command palette now offers "Show system status bar"; invoking it restores visibility
        val showCmd = paletteCommands(harness.vm, harness.s, hasDesktopCaption = false).firstOrNull { it.name == "Show system status bar" }
        assertNotNull("Expected 'Show system status bar' in Command Palette when hidden", showCmd)
        showCmd!!.action()
        harness.settle()
        rule.waitForIdle()

        assertFalse(harness.s.hideStatusBar)
        assertFalse(Settings(ApplicationProvider.getApplicationContext()).hideStatusBar)
        assertTrue(harness.toasts().contains("System status bar shown"))

        // Real MainActivity applies both hidden and visible status bar states cleanly
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.applyStatusBarVisibility(true)
                activity.applyStatusBarVisibility(false)
            }
        }
    }

    @Test
    fun desktopCaptionToolbarOmitsHideSystemStatusBarOption() {
        val harness = VmHarness().also { h = it }
        rule.setContent {
            WayfinderTheme(false) {
                Toolbar(harness.vm, harness.s, hasDesktopCaption = true)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("More").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Status bar").assertIsDisplayed()
        rule.onAllNodesWithText("Hide system status bar").assertCountEquals(0)
    }
}
