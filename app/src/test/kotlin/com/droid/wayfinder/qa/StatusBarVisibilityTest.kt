// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.droid.wayfinder.ui.computeCaptionMetrics
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        h?.close()
    }

    @Test
    fun computeCaptionMetricsAddsStatusBarTopInsetOnlyWhenDesktopCaptionAbsent() {
        val density = Density(density = 2f, fontScale = 1f)

        // Non-DeX fullscreen mode (capTop == 0, statusTop == 56px = 28.dp):
        // reserves 28.dp topInset above the 44.dp tab strip (total height = 72.dp).
        val fullscreen = computeCaptionMetrics(capTopPx = 0, statusTopPx = 56, startPx = 0, endPx = 0, density = density)
        assertEquals(28.dp, fullscreen.topInset)
        assertEquals(72.dp, fullscreen.height)

        // Hidden status bar in fullscreen mode (capTop == 0, statusTop == 0):
        // collapses topInset to 0.dp and keeps 44.dp tab strip height.
        val hiddenStatus = computeCaptionMetrics(capTopPx = 0, statusTopPx = 0, startPx = 0, endPx = 0, density = density)
        assertEquals(0.dp, hiddenStatus.topInset)
        assertEquals(44.dp, hiddenStatus.height)

        // Desktop / DeX caption bar mode (capTop == 96px = 48.dp, statusTop == 56px):
        // tabs live inside the caption bar with topInset = 0.dp and end inset for window controls.
        val desktop = computeCaptionMetrics(capTopPx = 96, statusTopPx = 56, startPx = 0, endPx = 280, density = density)
        assertEquals(0.dp, desktop.topInset)
        assertEquals(48.dp, desktop.height)
        assertEquals(140.dp, desktop.end)
    }

    @Test
    fun hideStatusBarToggleWorksFromOverflowAndPaletteAndPersistsPreference() {
        val harness = VmHarness().also { h = it }
        assertFalse(harness.s.hideStatusBar)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        // Command palette initially offers "Hide status bar"
        val hideCmd = paletteCommands(harness.vm, harness.s).firstOrNull { it.name == "Hide status bar" }
        assertNotNull("Expected 'Hide status bar' in Command Palette", hideCmd)

        // Toggle ON via Overflow menu ("More" -> "Hide status bar")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Hide status bar").performScrollTo().performClick()
        harness.settle()
        rule.waitForIdle()

        assertTrue(harness.s.hideStatusBar)
        assertTrue(Settings(ApplicationProvider.getApplicationContext()).hideStatusBar)
        assertTrue(harness.toasts().contains("Status bar hidden"))

        // Command palette now offers "Show status bar"; invoking it restores visibility
        val showCmd = paletteCommands(harness.vm, harness.s).firstOrNull { it.name == "Show status bar" }
        assertNotNull("Expected 'Show status bar' in Command Palette when hidden", showCmd)
        showCmd!!.action()
        harness.settle()
        rule.waitForIdle()

        assertFalse(harness.s.hideStatusBar)
        assertFalse(Settings(ApplicationProvider.getApplicationContext()).hideStatusBar)
        assertTrue(harness.toasts().contains("Status bar shown"))

        // Real MainActivity applies both hidden and visible status bar states cleanly
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.applyStatusBarVisibility(true)
                activity.applyStatusBarVisibility(false)
            }
        }
    }
}
