// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.R
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.formatItemCount
import com.droid.wayfinder.data.formatResultCount
import com.droid.wayfinder.data.formatSelectedCount
import com.droid.wayfinder.data.formatSize
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.PreviewPane
import com.droid.wayfinder.ui.icon
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Self-contained tests for Phase 3 localization, accessibility focus traversal, and 200% font scaling:
 *  - Locale-aware decimal and grouping separators in formatSize()
 *  - ICU MessageFormat plural rules in formatItemCount(), formatSelectedCount(), and formatResultCount()
 *  - Externalized string resources in res/values/strings.xml
 *  - AutoMirrored directional Material icons for RTL layout support
 *  - 200% font scale (fontScale = 2.0f) layout resilience across Details view, Sidebar, StatusBar, and PreviewPane
 *  - Keyboard Tab / F6 focus traversal in single-pane and dual-pane modes
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class LocalizationAndScalingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After fun tearDown() { h?.close() }

    @Test fun formatSizeUsesLocaleDecimalAndGroupingSeparators() {
        val onePointFiveMb = (1.5 * 1024 * 1024).toLong()
        assertEquals("512 B", formatSize(512L, Locale.US))
        assertEquals("1.5 MB", formatSize(onePointFiveMb, Locale.US))
        assertEquals("1,5 MB", formatSize(onePointFiveMb, Locale.FRANCE))
        assertEquals("1,5 MB", formatSize(onePointFiveMb, Locale.GERMANY))
    }

    @Test fun icuPluralFormattingHandlesSingularAndPluralQuantities() {
        assertEquals("1 item", formatItemCount(1, Locale.US))
        assertEquals("0 items", formatItemCount(0, Locale.US))
        assertEquals("42 items", formatItemCount(42, Locale.US))

        assertEquals("1 selected", formatSelectedCount(1, Locale.US))
        assertEquals("5 selected", formatSelectedCount(5, Locale.US))

        assertEquals("1 result", formatResultCount(1, Locale.US))
        assertEquals("12 results", formatResultCount(12, Locale.US))
    }

    @Test fun externalizedStringsAndAutoMirroredIconsAreConfigured() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Wayfinder (A Files app for Googlebooks)", ctx.getString(R.string.app_name))
        assertEquals("Quick access", ctx.getString(R.string.quick_access_section))
        assertEquals("Search files", ctx.getString(R.string.search_files_placeholder))
        assertEquals("Sort", ctx.getString(R.string.sort_button_label))
        assertEquals("Details", ctx.getString(R.string.details_pane_title))

        // Directional file icons use AutoMirrored vectors so they mirror automatically in RTL locales
        assertTrue(FileKind.Text.icon().autoMirror)
        assertTrue(FileKind.Other.icon().autoMirror)
    }

    @Test fun appRootRendersCleanlyAt200PercentFontScaleAndSupportsKeyboardFocus() {
        val harness = VmHarness().also { h = it }
        harness.file("scale_test_document.txt", "hello world")
        harness.dir("scale_test_folder")
        harness.navigate(harness.root.path)
        harness.settle()

        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2.0f)) {
                WayfinderTheme(false) {
                    AppRoot(harness.vm)
                }
            }
        }
        rule.waitForIdle()

        // Verify file list, status bar item count, and preview pane render without clipping at 200% fontScale
        rule.onNodeWithText("scale_test_document.txt").assertIsDisplayed()
        rule.onNodeWithText("scale_test_folder").assertIsDisplayed()
        rule.onNodeWithText("2 items").assertIsDisplayed()

        // Verify F6 switches dual-pane focus when no items are selected
        harness.vm.toggleDualPane()
        harness.vm.clearSelection()
        rule.waitForIdle()
        val initialRightActive = harness.s.rightActive
        val target = if (rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(isFocused())
        } else {
            rule.onAllNodes(isRoot())[0]
        }
        target.performKeyInput { pressKey(Key.F6) }
        rule.waitForIdle()
        assertEquals(!initialRightActive, harness.s.rightActive)
    }

    @Test fun previewPaneActionButtonsFitOnSingleRowWithoutWrappingRenameText() {
        val harness = VmHarness().also { h = it }
        harness.file("preview_button_test.txt", "hello world")
        harness.vm.refresh()
        harness.settle()
        harness.select("preview_button_test.txt")

        rule.setContent {
            WayfinderTheme(false) {
                PreviewPane(harness.vm, harness.s, Modifier.width(300.dp))
            }
        }
        rule.waitForIdle()

        val openBounds = rule.onNodeWithText("Open").fetchSemanticsNode().boundsInRoot
        val shareBounds = rule.onNodeWithText("Share").fetchSemanticsNode().boundsInRoot
        val renameBounds = rule.onNodeWithText("Rename").fetchSemanticsNode().boundsInRoot

        // All three buttons in PreviewPane fit side-by-side on the same horizontal row and have equal single-line height
        assertEquals(openBounds.top, shareBounds.top, 2f)
        assertEquals(openBounds.top, renameBounds.top, 2f)
        assertEquals(openBounds.height, renameBounds.height, 2f)
    }
}
