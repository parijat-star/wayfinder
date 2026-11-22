// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.HorizontalScrollbar
import com.droid.wayfinder.ui.SCROLLBAR_THUMB_TAG_PREFIX
import com.droid.wayfinder.ui.SCROLLBAR_TRACK_TAG_PREFIX
import com.droid.wayfinder.ui.TOOLTIP_TEST_TAG
import com.droid.wayfinder.ui.WayfinderDragShadowBuilder
import com.droid.wayfinder.ui.buildOutboundDragClipData
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class DesktopTooltipsScrollbarsAndDragTest {
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
    fun hoverTooltipsAppearOnIconButtonsAndDismissOnPointerExit() {
        val harness = VmHarness().also { h = it }
        harness.file("hover.txt", "hover")
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        val moreBtn = rule.onNodeWithContentDescription("More")
        moreBtn.performMouseInput {
            enter(center)
            moveTo(center)
        }
        rule.mainClock.advanceTimeBy(250L)
        rule.waitForIdle()
        rule.onNode(hasTestTag(TOOLTIP_TEST_TAG)).assertIsDisplayed()
        rule.onNode(hasText("More", substring = true) and hasAnyAncestor(hasTestTag(TOOLTIP_TEST_TAG)), useUnmergedTree = true)
            .assertIsDisplayed()

        moreBtn.performMouseInput { exit() }
        rule.mainClock.advanceTimeBy(100L)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(TOOLTIP_TEST_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun touchLongPressOpensContextMenuOnFileRowGridCellAndEmptyBackground() {
        val harness = VmHarness().also { h = it }
        harness.file("touch_target.txt", "hello")
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        rule.onNodeWithText("touch_target.txt").performTouchInput { longClick() }
        idle(harness)
        rule.onNodeWithText("Copy path").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertIsDisplayed()

        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
        idle(harness)

        harness.vm.setViewMode(ViewMode.Grid)
        idle(harness)
        rule.onNodeWithText("touch_target.txt").performTouchInput { longClick() }
        idle(harness)
        rule.onNodeWithText("Copy path").assertIsDisplayed()

        val emptyDir = harness.dir("EmptyLongPress")
        harness.navigate(emptyDir.path)
        idle(harness)
        rule.onNodeWithText("No files in this folder").performTouchInput { longClick() }
        idle(harness)
        rule.onNodeWithText("New folder").assertIsDisplayed()
    }

    @Test
    fun proportionalScrollbarsRenderAdvanceAndScrubOnTrackTap() {
        val harness = VmHarness().also { h = it }
        for (i in 1..60) {
            harness.file("scroll_dir/file_%02d.txt".format(i), "content $i")
        }
        harness.navigate(harness.pathOf("scroll_dir"))

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        val listThumbTag = SCROLLBAR_THUMB_TAG_PREFIX + "list"
        val listTrackTag = SCROLLBAR_TRACK_TAG_PREFIX + "list"
        val topBefore = rule.onAllNodesWithTag(listThumbTag, useUnmergedTree = true).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].fetchSemanticsNode().boundsInRoot.top
        }
        val scrollableLists = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        scrollableLists[scrollableLists.fetchSemanticsNodes().lastIndex].performScrollToIndex(45)
        idle(harness)
        val topAfter = rule.onAllNodesWithTag(listThumbTag, useUnmergedTree = true).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Expected scrollbar thumb to move downward on scroll (before=$topBefore, after=$topAfter)", topAfter > topBefore)

        val tracks = rule.onAllNodesWithTag(listTrackTag, useUnmergedTree = true)
        tracks[tracks.fetchSemanticsNodes().lastIndex].performTouchInput {
            click(Offset(width / 2f, 8f))
        }
        idle(harness)
        val topAfterTrackTap = rule.onAllNodesWithTag(listThumbTag, useUnmergedTree = true).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Expected vertical track tap near top to scrub upward (before=$topAfter, after=$topAfterTrackTap)", topAfterTrackTap < topAfter - 10f)

        harness.vm.setViewMode(ViewMode.Grid)
        idle(harness)
        rule.onNodeWithTag(SCROLLBAR_THUMB_TAG_PREFIX + "grid", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun horizontalScrollbarThumbRemainsInsideTrackInRtlAndScrubsOnTap() {
        rule.setContent {
            WayfinderTheme(false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(scrollState.maxValue) {
                        if (scrollState.maxValue > 0) {
                            scrollState.scrollTo(scrollState.maxValue / 2)
                        }
                    }
                    Box(Modifier.width(240.dp).height(80.dp).testTag("rtl_hscroll_host")) {
                        Row(Modifier.horizontalScroll(scrollState)) {
                            Box(Modifier.width(900.dp).height(60.dp))
                        }
                        HorizontalScrollbar(
                            state = scrollState,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag("rtl_hscroll_track"),
                            tag = "rtl_test",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val trackBounds = rule.onNodeWithTag("rtl_hscroll_track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val thumbBounds = rule.onNodeWithTag(SCROLLBAR_THUMB_TAG_PREFIX + "rtl_test", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("RTL horizontal thumb must have positive width", thumbBounds.width > 0f && thumbBounds.width < trackBounds.width)
        assertTrue(
            "RTL horizontal thumb [${thumbBounds.left}, ${thumbBounds.right}] must stay strictly inside track [${trackBounds.left}, ${trackBounds.right}]",
            thumbBounds.left >= trackBounds.left - 1f && thumbBounds.right <= trackBounds.right + 1f,
        )

        rule.onNodeWithTag("rtl_hscroll_track", useUnmergedTree = true).performTouchInput {
            click(Offset(12f, height / 2f))
        }
        rule.waitForIdle()
        val thumbAfterTap = rule.onNodeWithTag(SCROLLBAR_THUMB_TAG_PREFIX + "rtl_test", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected RTL track tap near left edge to scrub thumb leftward (before=${thumbBounds.left}, after=${thumbAfterTap.left})",
            thumbAfterTap.left < thumbBounds.left - 10f,
        )
    }

    @Test
    fun outboundCrossAppDragBuildsSingleAndBatchClipDataAndCountBadgeShadow() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dragDir = File(android.os.Environment.getExternalStorageDirectory(), "drag_audit_test").apply { mkdirs() }
        try {
            val f1 = File(dragDir, "alpha.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val f2 = File(dragDir, "beta.pdf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val f3 = File(dragDir, "gamma.txt").apply { writeText("hello") }

            val (singleClip, singleHasUris) = buildOutboundDragClipData(ctx, listOf(f1.path))!!
            assertTrue(singleHasUris)
            assertEquals(1, singleClip.itemCount)
            assertEquals("content", singleClip.getItemAt(0).uri.scheme)
            assertTrue(singleClip.description.hasMimeType("image/png"))

            val batchPaths = listOf(f1.path, f2.path, f3.path)
            val (batchClip, batchHasUris) = buildOutboundDragClipData(ctx, batchPaths)!!
            assertTrue(batchHasUris)
            assertEquals(3, batchClip.itemCount)
            for (i in 0 until 3) {
                val uri = batchClip.getItemAt(i).uri
                assertNotNull("Item $i must carry a grantable content:// URI", uri)
                assertEquals("content", uri.scheme)
            }
            assertTrue(batchClip.description.hasMimeType("image/png"))
            assertTrue(batchClip.description.hasMimeType("application/pdf"))
            assertTrue(batchClip.description.hasMimeType("text/plain"))

            val subFolder = File(dragDir, "ChildDir").apply { mkdirs() }
            val (mixedClip, mixedHasUris) = buildOutboundDragClipData(ctx, listOf(f1.path, subFolder.path))!!
            assertTrue(mixedHasUris)
            assertEquals("Mixed file+folder drag must only expose grantable file URIs externally", 1, mixedClip.itemCount)
            assertNotNull(mixedClip.getItemAt(0).uri)
            assertFalse("Mixed file+folder drag must not degrade ClipDescription to text/plain", mixedClip.description.hasMimeType("text/plain"))

            val (_, folderOnlyHasUris) = buildOutboundDragClipData(ctx, listOf(subFolder.path))!!
            assertFalse("Folder-only drag must report hasContentUris=false", folderOnlyHasUris)

            val shadow = WayfinderDragShadowBuilder(ctx, batchPaths)
            val size = Point()
            val touch = Point()
            shadow.onProvideShadowMetrics(size, touch)
            assertTrue(size.x > 0 && size.y > 0)
            val bmp = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
            shadow.onDrawShadow(Canvas(bmp))
            var nonEmptyPixels = 0
            for (y in 0 until bmp.height step 2) {
                for (x in 0 until bmp.width step 2) {
                    if (bmp.getPixel(x, y) != 0) nonEmptyPixels++
                }
            }
            assertTrue("Expected rendered drag shadow card + badge pixels, got $nonEmptyPixels", nonEmptyPixels > 20)
        } finally {
            dragDir.deleteRecursively()
        }
    }
}
