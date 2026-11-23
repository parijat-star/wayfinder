// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.graphics.Rect as AndroidRect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.CaptionMetrics
import com.droid.wayfinder.ui.TitleBar
import com.droid.wayfinder.ui.Toolbar
import com.droid.wayfinder.ui.resolveDesktopCaptionHorizontalInsets
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlin.math.abs
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
class CaptionAndTouchTargetsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    @Test
    fun desktopCaptionResizeInsetsStayAlignedOnShrinkExpandAndRtl() {
        val tol = 48

        assertEquals(
            0 to 320,
            resolveDesktopCaptionHorizontalInsets(listOf(AndroidRect(2560, 0, 2880, 96)), widthPx = 2880, edgeTolerancePx = tol),
        )
        assertEquals(
            0 to 320,
            resolveDesktopCaptionHorizontalInsets(listOf(AndroidRect(720, 0, 1040, 96)), widthPx = 1040, edgeTolerancePx = tol),
        )
        // Shrink race: view.width shrunk to 1040px while rootWindowInsets still holds stale 2880px rect
        assertEquals(
            0 to 320,
            resolveDesktopCaptionHorizontalInsets(listOf(AndroidRect(2560, 0, 2880, 96)), widthPx = 1040, edgeTolerancePx = tol),
        )
        // Expand race: view.width grew to 2880px while rootWindowInsets still holds stale narrow rect (680..1000)
        assertEquals(
            0 to 320,
            resolveDesktopCaptionHorizontalInsets(listOf(AndroidRect(680, 0, 1000, 96)), widthPx = 2880, edgeTolerancePx = tol),
        )
        // Left-side caption controls (RTL / macOS style) and dual-sided caption bars
        assertEquals(
            320 to 0,
            resolveDesktopCaptionHorizontalInsets(listOf(AndroidRect(0, 0, 320, 96)), widthPx = 2880, edgeTolerancePx = tol),
        )
        assertEquals(
            88 to 320,
            resolveDesktopCaptionHorizontalInsets(
                listOf(AndroidRect(0, 0, 88, 96), AndroidRect(2560, 0, 2880, 96)),
                widthPx = 2880,
                edgeTolerancePx = tol,
            ),
        )
        assertNull(resolveDesktopCaptionHorizontalInsets(emptyList(), widthPx = 2880, edgeTolerancePx = tol))

        val harness = VmHarness().also { h = it }
        val docs = harness.dir("Docs")
        harness.navigate(docs.path)
        harness.vm.newTab(docs.path)
        harness.settle()

        val windowWidthDp = mutableStateOf(520.dp)
        val caption = CaptionMetrics(height = 48.dp, start = 0.dp, end = 140.dp, hasDesktopCaption = true)
        var density: Density? = null
        rule.setContent {
            density = LocalDensity.current
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.width(windowWidthDp.value)) {
                    TitleBar(vm = harness.vm, s = s, captionMetricsOverride = caption, onShowMenu = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        val d = density!!

        val firstTabMatcher = SemanticsMatcher("firstDocsTab") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Select tab Docs"
        }
        val narrowFirstTab = rule.onAllNodes(firstTabMatcher, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val narrowAddBtn = rule.onNodeWithContentDescription("New tab").fetchSemanticsNode().boundsInRoot
        assertTrue("First tab must stay anchored near start on narrow window, got ${narrowFirstTab.left}", narrowFirstTab.left <= with(d) { 24.dp.toPx() })
        assertTrue(
            "New tab '+' (${narrowAddBtn.right}) must never overlap right 140dp caption controls on 520dp window (${with(d) { (520.dp - 140.dp).toPx() }})",
            narrowAddBtn.right <= with(d) { (520.dp - 140.dp).toPx() } + 2f,
        )

        windowWidthDp.value = 1360.dp
        rule.waitForIdle()
        val wideFirstTab = rule.onAllNodes(firstTabMatcher, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertTrue("First tab must remain anchored near start after expanding to 1360dp, got ${wideFirstTab.left}", wideFirstTab.left <= with(d) { 24.dp.toPx() })
        assertEquals("Tab start x-coordinate must remain invariant across narrow -> wide resize", narrowFirstTab.left, wideFirstTab.left, 0.5f)
    }

    @Test
    fun allInteractiveNodesMeet48dpAccessibilityTouchTargetMinimum() {
        val harness = VmHarness().also { h = it }
        val sub = harness.dir("WorkFolder")
        harness.file("WorkFolder/alpha.txt", "hello")
        harness.file("WorkFolder/beta.txt", "world")
        harness.vm.pin(sub.path)
        harness.navigate(sub.path)
        harness.vm.click(harness.pathOf("WorkFolder/alpha.txt"), ctrl = false, shift = false)
        if (!harness.s.previewVisible) harness.vm.togglePreview()
        harness.settle()

        var density: Density? = null
        rule.setContent {
            density = LocalDensity.current
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)
        val d = density!!
        val minPx = with(d) { 47.5.dp.toPx() }

        fun assertAllInteractiveTargetsAtLeast48dp(modeLabel: String) {
            val interactiveMatcher = SemanticsMatcher("hasClickOrLongClick") { node ->
                SemanticsActions.OnClick in node.config || SemanticsActions.OnLongClick in node.config
            }
            val nodes = rule.onAllNodes(interactiveMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Expected interactive nodes in $modeLabel", nodes.isNotEmpty())
            val violations = nodes.mapNotNull { node ->
                val b = node.boundsInRoot
                if (b.width <= 0f || b.height <= 0f) return@mapNotNull null
                val wPx = node.size.width.toFloat()
                val hPx = node.size.height.toFloat()
                if (wPx < minPx || hPx < minPx) {
                    val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                        ?: node.config.getOrNull(SemanticsActions.OnClick)?.label
                        ?: "node#${node.id}"
                    "$modeLabel: '$desc' size=${with(d) { wPx.toDp() }} x ${with(d) { hPx.toDp() }}"
                } else {
                    null
                }
            }
            assertTrue("Touch target (< 48dp) violations:\n" + violations.joinToString("\n"), violations.isEmpty())
        }

        assertAllInteractiveTargetsAtLeast48dp("Details + PreviewPane")

        harness.vm.setViewMode(ViewMode.List)
        idle(harness)
        assertAllInteractiveTargetsAtLeast48dp("List")

        harness.vm.setViewMode(ViewMode.Grid)
        idle(harness)
        assertAllInteractiveTargetsAtLeast48dp("Grid")

        harness.vm.setViewMode(ViewMode.Columns)
        idle(harness)
        assertAllInteractiveTargetsAtLeast48dp("MillerColumns")

        harness.vm.setViewMode(ViewMode.Details)
        harness.vm.toggleDualPane()
        idle(harness)
        assertAllInteractiveTargetsAtLeast48dp("DualPane")

        harness.vm.toggleDualPane()
        harness.vm.setQuery("alpha")
        idle(harness)
        assertAllInteractiveTargetsAtLeast48dp("ActiveSearch")
    }

    @Test
    fun mediumLandscapeToolbarPreservesReadableAddressBarWidth() {
        val harness = VmHarness().also { h = it }
        harness.file("sample.txt", "hello")
        harness.navigate(harness.root.path)

        val widthState = mutableStateOf(940.dp)
        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.width(widthState.value)) {
                    Toolbar(harness.vm, s, hasDesktopCaption = false)
                }
            }
        }
        idle(harness)

        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(180.dp)
            .assertHeightIsAtLeast(48.dp)
        val searchNode = rule.onNodeWithContentDescription("Search files")
        searchNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // Verify empty placeholder uses 16sp (bodyLarge), is vertically centered, and is not ellipsized
        val placeholderLayouts = mutableListOf<TextLayoutResult>()
        val placeholderNode = rule.onNodeWithText("Search files", useUnmergedTree = true).fetchSemanticsNode()
        val searchBoxBounds = searchNode.fetchSemanticsNode().boundsInRoot
        assertEquals(true, placeholderNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(placeholderLayouts))
        assertEquals(16.sp, placeholderLayouts.single().layoutInput.style.fontSize)
        assertFalse(placeholderLayouts.single().isLineEllipsized(0))
        assertTrue(abs(placeholderNode.boundsInRoot.center.y - searchBoxBounds.center.y) <= 2f)

        // Verify typed text uses 16sp and BasicTextField retains >= 48dp width alongside the 48dp Clear button
        searchNode.performTextInput("sample")
        idle(harness)
        searchNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val searchSemantics = searchNode.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val fetched = searchSemantics.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals(true, fetched)
        assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)

        widthState.value = 680.dp
        idle(harness)
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(180.dp)
            .assertHeightIsAtLeast(48.dp)
        searchNode.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
