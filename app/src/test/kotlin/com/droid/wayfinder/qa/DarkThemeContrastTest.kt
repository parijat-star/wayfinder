// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.tintForTheme
import com.droid.wayfinder.ui.theme.LocalIsDarkTheme
import com.droid.wayfinder.ui.theme.WayfinderTheme
import com.droid.wayfinder.ui.theme.wayfinderColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

/**
 * Verifies Dark Theme and Light Theme Material 3 guidelines:
 * - Root [LocalContentColor] inside [WayfinderTheme] is bound to [MaterialTheme.colorScheme.onSurface]
 *   (not the default [Color.Black]), so toolbar controls (`Refresh`, `Undo`, `Redo`, `Sort`, `More`)
 *   remain high-contrast in dark mode.
 * - All surface/content token pairs and [FileKind.tintForTheme] icon colors meet WCAG contrast ratios.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class DarkThemeContrastTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun contrastRatio(fg: Color, bg: Color): Float {
        val l1 = fg.luminance()
        val l2 = bg.luminance()
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    @Test
    fun wayfinderThemeProvidesHighContrastContentColorAndDarkFlagInDarkMode() {
        var capturedContentColor = Color.Unspecified
        var capturedSurfaceLow = Color.Unspecified
        var capturedSurfaceHigh = Color.Unspecified
        var capturedOnSurfaceVariant = Color.Unspecified
        var capturedIsDark = false

        rule.setContent {
            WayfinderTheme(dark = true) {
                capturedContentColor = LocalContentColor.current
                capturedSurfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
                capturedSurfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
                capturedOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                capturedIsDark = LocalIsDarkTheme.current
            }
        }
        rule.waitForIdle()

        assertTrue("Expected LocalIsDarkTheme to be true in dark mode", capturedIsDark)
        assertNotEquals(
            "LocalContentColor must not default to Color.Black in dark mode",
            Color.Black,
            capturedContentColor,
        )
        assertTrue(
            "LocalContentColor vs surfaceContainerLow contrast (${contrastRatio(capturedContentColor, capturedSurfaceLow)}) must be >= 7.0",
            contrastRatio(capturedContentColor, capturedSurfaceLow) >= 7.0f,
        )
        assertTrue(
            "LocalContentColor vs surfaceContainerHigh contrast (${contrastRatio(capturedContentColor, capturedSurfaceHigh)}) must be >= 7.0",
            contrastRatio(capturedContentColor, capturedSurfaceHigh) >= 7.0f,
        )
        assertTrue(
            "onSurfaceVariant vs surfaceContainerLow contrast (${contrastRatio(capturedOnSurfaceVariant, capturedSurfaceLow)}) must be >= 4.5",
            contrastRatio(capturedOnSurfaceVariant, capturedSurfaceLow) >= 4.5f,
        )
    }

    @Test
    fun darkAndLightColorSchemesAndFileIconTintsMeetContrastGuidelines() {
        for (dark in listOf(false, true)) {
            val cs = wayfinderColorScheme(dark)
            val surfaces = listOf(
                "surface" to cs.surface,
                "surfaceContainerLowest" to cs.surfaceContainerLowest,
                "surfaceContainerLow" to cs.surfaceContainerLow,
                "surfaceContainer" to cs.surfaceContainer,
                "surfaceContainerHigh" to cs.surfaceContainerHigh,
                "surfaceContainerHighest" to cs.surfaceContainerHighest,
            )
            for ((name, bg) in surfaces) {
                val onSurfaceRatio = contrastRatio(cs.onSurface, bg)
                val onVariantRatio = contrastRatio(cs.onSurfaceVariant, bg)
                assertTrue("dark=$dark onSurface vs $name ratio=$onSurfaceRatio < 7.0", onSurfaceRatio >= 7.0f)
                assertTrue("dark=$dark onSurfaceVariant vs $name ratio=$onVariantRatio < 4.5", onVariantRatio >= 4.5f)
            }

            val containerPairs = listOf(
                Triple("primaryContainer", cs.onPrimaryContainer, cs.primaryContainer),
                Triple("secondaryContainer", cs.onSecondaryContainer, cs.secondaryContainer),
                Triple("tertiaryContainer", cs.onTertiaryContainer, cs.tertiaryContainer),
                Triple("errorContainer", cs.onErrorContainer, cs.errorContainer),
            )
            for ((name, fg, bg) in containerPairs) {
                val ratio = contrastRatio(fg, bg)
                assertTrue("dark=$dark $name contrast ratio=$ratio < 4.5", ratio >= 4.5f)
            }

            for (kind in FileKind.entries) {
                val tint = kind.tintForTheme(dark)
                for ((surfaceName, bg) in surfaces) {
                    val ratio = contrastRatio(tint, bg)
                    assertTrue(
                        "dark=$dark FileKind.$kind tint vs $surfaceName contrast ratio=$ratio < 3.5",
                        ratio >= 3.5f,
                    )
                }
            }
        }
    }

    @Test
    fun toolbarControlsRenderAndOperateInDarkMode() {
        h = VmHarness()
        h.file("alpha.txt", "a")
        h.file("beta.txt", "b")
        h.navigate(h.root.path)

        rule.setContent {
            WayfinderTheme(dark = true) {
                AppRoot(h.vm)
            }
        }
        rule.waitForIdle()
        h.settle()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        rule.onNodeWithContentDescription("Undo").assertIsDisplayed()
        rule.onNodeWithContentDescription("Redo").assertIsDisplayed()
        rule.onNodeWithContentDescription("More").assertIsDisplayed()
        rule.onNodeWithText("Sort").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Descending").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(false, h.s.active.sortAsc)
        assertEquals(2, h.s.active.entries.size)
    }
}
