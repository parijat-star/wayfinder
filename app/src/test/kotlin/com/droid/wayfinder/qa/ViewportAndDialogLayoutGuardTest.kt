// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.AudioTrackInfo
import com.droid.wayfinder.data.Checksums
import com.droid.wayfinder.data.MediaInfo
import com.droid.wayfinder.data.MediaTags
import com.droid.wayfinder.data.SubtitleTrackInfo
import com.droid.wayfinder.data.VideoTrackInfo
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.MenuController
import com.droid.wayfinder.ui.MenuHost
import com.droid.wayfinder.ui.fileMenu
import com.droid.wayfinder.ui.theme.WayfinderTheme
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1440dp-h900dp-land-xhdpi")
class ViewportAndDialogLayoutGuardTest {
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

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    private fun assertAllInteractiveTouchTargetsMeet48dp(contextLabel: String, effectiveDensity: Density) {
        val minPx = with(effectiveDensity) { 47.5.dp.toPx() }
        val matcher = SemanticsMatcher("hasClickOrLongClick") { node ->
            SemanticsActions.OnClick in node.config || SemanticsActions.OnLongClick in node.config
        }
        val nodes = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Expected interactive nodes in $contextLabel", nodes.isNotEmpty())
        val violations = nodes.mapNotNull { node ->
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            val touchBounds = node.touchBoundsInRoot
            val widthPx = maxOf(node.size.width.toFloat(), touchBounds.width)
            val heightPx = maxOf(node.size.height.toFloat(), touchBounds.height)
            if (widthPx < minPx || heightPx < minPx) {
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    ?: node.children.firstNotNullOfOrNull { child ->
                        child.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                            ?: child.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    }
                    ?: node.config.getOrNull(SemanticsActions.OnClick)?.label
                    ?: "node#${node.id}"
                "$contextLabel: '$label' touch target measured ${with(effectiveDensity) { widthPx.toDp() }} x ${with(effectiveDensity) { heightPx.toDp() }}"
            } else {
                null
            }
        }
        assertTrue(
            "Interactive controls below 48dp touch target minimum:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun assertSearchFieldTypographyAndCentering(contextLabel: String, effectiveDensity: Density) {
        val searchFieldNode = rule.onNodeWithContentDescription("Search files").fetchSemanticsNode()
        val searchFieldBounds = searchFieldNode.boundsInRoot
        val maxCenterOffsetPx = with(effectiveDensity) { 3.dp.toPx() }

        val placeholderNodes = rule.onAllNodes(
            SemanticsMatcher("searchPlaceholderText") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Search files" } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()

        if (placeholderNodes.isNotEmpty()) {
            val pNode = placeholderNodes.first()
            val layouts = mutableListOf<TextLayoutResult>()
            assertEquals(
                "$contextLabel: placeholder should expose TextLayoutResult",
                true,
                pNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts),
            )
            val layout = layouts.single()
            assertTrue(
                "$contextLabel: placeholder fontSize must be >= 14.sp, got ${layout.layoutInput.style.fontSize}",
                layout.layoutInput.style.fontSize.value >= 14f,
            )
            assertFalse("$contextLabel: placeholder must not ellipsize", layout.isLineEllipsized(0))
            assertTrue(
                "$contextLabel: placeholder must be vertically centered within 3dp (delta=${abs(pNode.boundsInRoot.center.y - searchFieldBounds.center.y)}px)",
                abs(pNode.boundsInRoot.center.y - searchFieldBounds.center.y) <= maxCenterOffsetPx,
            )
        } else {
            val editableLayouts = mutableListOf<TextLayoutResult>()
            assertEquals(
                "$contextLabel: typed SearchField should expose TextLayoutResult",
                true,
                searchFieldNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(editableLayouts),
            )
            val layout = editableLayouts.single()
            assertEquals(16.sp, layout.layoutInput.style.fontSize)
            val textTopInField = (searchFieldBounds.height - layout.size.height) / 2f
            assertTrue(
                "$contextLabel: SearchField height (${searchFieldBounds.height}px) must comfortably fit 16sp line (${layout.size.height}px)",
                textTopInField >= 0f,
            )
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-xhdpi")
    fun compactPhonePortraitPreservesAddressBarSearchTypographyAndTouchTargets() {
        val harness = VmHarness().also { h = it }
        harness.dir("Workspace")
        harness.file("Workspace/alpha.txt", "alpha")
        harness.file("Workspace/beta.txt", "beta")
        harness.navigate(harness.pathOf("Workspace"))

        var baseDensity: Density? = null
        rule.setContent {
            baseDensity = LocalDensity.current
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)
        val density = baseDensity!!

        // 0 selected on ultra-narrow 360dp phone: Address bar stays >= 70.dp (never crushed) and all targets >= 48dp
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(70.dp)
            .assertHeightIsAtLeast(48.dp)
        assertAllInteractiveTouchTargetsMeet48dp("360dp portrait (0 selected)", density)

        // 2 selected on <= 960dp: CompactSelectionBar replaces toolbar row cleanly with >= 48dp targets,
        // and Address bar retains >= 70.dp whenever address editing takes precedence.
        harness.select("Workspace/alpha.txt", "Workspace/beta.txt")
        idle(harness)
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
        assertAllInteractiveTouchTargetsMeet48dp("360dp portrait (2 selected)", density)
        harness.vm.setAddressEditing(true)
        idle(harness)
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(70.dp)
            .assertHeightIsAtLeast(48.dp)
        harness.vm.setAddressEditing(false)
        idle(harness)

        // Expand compact search: placeholder centered & un-ellipsized, typed query retains >= 48dp input + Clear button
        harness.vm.clearSelection()
        idle(harness)
        rule.onNodeWithContentDescription("Search files").performClick()
        idle(harness)
        assertSearchFieldTypographyAndCentering("360dp portrait expanded search (empty)", density)
        assertAllInteractiveTouchTargetsMeet48dp("360dp portrait expanded search (empty)", density)

        rule.onNodeWithContentDescription("Search files").performTextInput("alpha")
        idle(harness)
        rule.onNodeWithContentDescription("Search files").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Clear search").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertSearchFieldTypographyAndCentering("360dp portrait expanded search (typed)", density)
        assertAllInteractiveTouchTargetsMeet48dp("360dp portrait expanded search (typed)", density)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
    fun standardPhonePortraitLargeFontScaleKeepsAddressBarAtLeast110dp() {
        val harness = VmHarness().also { h = it }
        harness.dir("Downloads")
        harness.file("Downloads/invoice.pdf", "pdf")
        harness.file("Downloads/notes.txt", "notes")
        harness.navigate(harness.pathOf("Downloads"))

        var scaledDensity: Density? = null
        rule.setContent {
            val base = LocalDensity.current
            val fontScaled = Density(base.density, fontScale = 1.3f)
            scaledDensity = fontScaled
            CompositionLocalProvider(LocalDensity provides fontScaled) {
                WayfinderTheme(false) {
                    AppRoot(harness.vm)
                }
            }
        }
        idle(harness)
        val d = scaledDensity!!

        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(110.dp)
            .assertHeightIsAtLeast(48.dp)
        harness.select("Downloads/invoice.pdf", "Downloads/notes.txt")
        idle(harness)
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
        assertAllInteractiveTouchTargetsMeet48dp("412dp portrait @ 1.3x fontScale (2 selected)", d)
        harness.vm.setAddressEditing(true)
        idle(harness)
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(110.dp)
            .assertHeightIsAtLeast(48.dp)
        assertAllInteractiveTouchTargetsMeet48dp("412dp portrait @ 1.3x fontScale (address editing)", d)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w890dp-h412dp-land-xhdpi")
    fun phoneLandscapePreservesAddressBarWidthUnderMultiSelectionAndSearch() {
        val harness = VmHarness().also { h = it }
        harness.dir("Camera")
        harness.file("Camera/clip_one.mp4", "video1")
        harness.file("Camera/clip_two.mp4", "video2")
        harness.navigate(harness.pathOf("Camera"))

        var baseDensity: Density? = null
        rule.setContent {
            baseDensity = LocalDensity.current
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)
        val d = baseDensity!!

        for (stateLabel in listOf("0 selected", "2 selected", "search active")) {
            when (stateLabel) {
                "2 selected" -> harness.select("Camera/clip_one.mp4", "Camera/clip_two.mp4")
                "search active" -> harness.vm.setQuery("clip")
            }
            idle(harness)
            if (stateLabel == "2 selected") {
                rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
            } else {
                rule.onNodeWithContentDescription("Address bar")
                    .assertWidthIsAtLeast(180.dp)
                    .assertHeightIsAtLeast(48.dp)
                assertSearchFieldTypographyAndCentering("890x412 landscape ($stateLabel)", d)
            }
            assertAllInteractiveTouchTargetsMeet48dp("890x412 landscape ($stateLabel)", d)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w680dp-h900dp-port-xhdpi")
    fun splitScreenViewportMaintainsToolbarGeometryAndTouchTargets() {
        val harness = VmHarness().also { h = it }
        harness.dir("SplitDocs")
        harness.file("SplitDocs/one.md", "# 1")
        harness.file("SplitDocs/two.md", "# 2")
        harness.navigate(harness.pathOf("SplitDocs"))

        var baseDensity: Density? = null
        rule.setContent {
            baseDensity = LocalDensity.current
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)
        val d = baseDensity!!

        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(140.dp)
            .assertHeightIsAtLeast(48.dp)
        assertSearchFieldTypographyAndCentering("680x900 split-screen (0 selected)", d)
        assertAllInteractiveTouchTargetsMeet48dp("680x900 split-screen (0 selected)", d)

        harness.select("SplitDocs/one.md", "SplitDocs/two.md")
        idle(harness)
        rule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
        assertAllInteractiveTouchTargetsMeet48dp("680x900 split-screen (2 selected)", d)
        harness.vm.setAddressEditing(true)
        idle(harness)
        rule.onNodeWithContentDescription("Address bar")
            .assertWidthIsAtLeast(140.dp)
            .assertHeightIsAtLeast(48.dp)
        assertAllInteractiveTouchTargetsMeet48dp("680x900 split-screen (address editing)", d)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1440dp-h900dp-land-xhdpi")
    fun desktopZoomSweepAndOpenOverlaysMeetTouchTargetAndTypographyInvariants() {
        val harness = VmHarness().also { h = it }
        harness.dir("Studio")
        harness.file("Studio/render.mp4", "mp4-bytes")
        harness.file("Studio/readme.txt", "hello studio")
        harness.navigate(harness.pathOf("Studio"))

        val menuController = MenuController()
        var baseDensity: Density? = null
        rule.setContent {
            baseDensity = LocalDensity.current
            WayfinderTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(harness.vm)
                    MenuHost(menuController)
                }
            }
        }
        idle(harness)
        val base = baseDensity!!

        // 1. Zoom sweep: 70% (-3 steps), 100% (reset), 130% (+3 steps)
        for ((steps, expectedZoom) in listOf(-3 to 0.7f, 0 to 1.0f, 3 to 1.3f)) {
            harness.vm.zoomBy(steps)
            idle(harness)
            assertEquals(expectedZoom, harness.s.zoom, 0.01f)
            val zoomedDensity = Density(base.density * harness.s.zoom, base.fontScale)
            val minAddressPx = with(zoomedDensity) { 180.dp.toPx() }
            val addressWidthPx = rule.onNodeWithContentDescription("Address bar").fetchSemanticsNode().size.width.toFloat()
            assertTrue(
                "Desktop @ ${(expectedZoom * 100).toInt()}% zoom: Address bar width ($addressWidthPx px) < $minAddressPx px",
                addressWidthPx >= minAddressPx,
            )
            assertSearchFieldTypographyAndCentering("1440x900 desktop @ ${(expectedZoom * 100).toInt()}% zoom", zoomedDensity)
            assertAllInteractiveTouchTargetsMeet48dp("1440x900 desktop @ ${(expectedZoom * 100).toInt()}% zoom", zoomedDensity)
        }
        harness.vm.zoomBy(0)
        idle(harness)

        // 2. Properties dialog with populated MediaInfo + Checksums (verifies Copy Media & Copy field buttons >= 48dp)
        val mediaEntry = harness.s.active.entries.first { it.name == "render.mp4" }
        val richMedia = MediaInfo(
            container = "MP4",
            durationUs = 125_000_000L,
            overallBitrateBps = 12_500_000L,
            videoTracks = listOf(
                VideoTrackInfo(
                    codec = "HEVC (H.265)",
                    codecProfile = "Main 10",
                    width = 3840,
                    height = 2160,
                    frameRate = 60f,
                    bitrateBps = 12_000_000L,
                    bitDepth = 10,
                    colorSpace = "BT.2020",
                    colorTransfer = "HLG",
                ),
            ),
            audioTracks = listOf(
                AudioTrackInfo(
                    codec = "AAC",
                    codecProfile = "LC",
                    channelCount = 2,
                    sampleRateHz = 48_000,
                    bitrateBps = 256_000L,
                    language = "English",
                ),
            ),
            subtitleTracks = listOf(SubtitleTrackInfo(codec = "WebVTT", language = "English")),
            tags = MediaTags(title = "Studio Render", artist = "Wayfinder QA"),
        )
        harness.vm.showDialog(
            Dialog.Properties(
                entries = listOf(mediaEntry),
                bytes = mediaEntry.size,
                checksums = Checksums(sha256 = "0123456789abcdef", crc32 = "deadbeef"),
                mediaInfo = richMedia,
            ),
        )
        idle(harness)
        rule.onNodeWithContentDescription(harness.app.getString(com.droid.wayfinder.R.string.properties_media_copy)).assertIsDisplayed()
        assertAllInteractiveTouchTargetsMeet48dp("PropertiesDialog with MediaInfo", base)
        harness.vm.dismissDialog()
        idle(harness)

        // 3. QuickLook overlay open on text file
        harness.select("Studio/readme.txt")
        harness.vm.toggleQuickLook()
        idle(harness)
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        assertAllInteractiveTouchTargetsMeet48dp("QuickLook overlay", base)
        harness.vm.closeQuickLook()
        idle(harness)

        // 4. Context menu (MenuHost) open on file
        menuController.show(Offset(200f, 200f), fileMenu(harness.vm, harness.s, mediaEntry))
        idle(harness)
        assertAllInteractiveTouchTargetsMeet48dp("MenuHost fileMenu", base)
        menuController.dismiss()
        idle(harness)

        // 5. Undo Snackbar active after moving a file to Trash (verifies Undo action button >= 48dp height/width or Material3 spec)
        harness.select("Studio/readme.txt")
        harness.vm.requestDelete(permanent = false)
        idle(harness)
        val undoNodes = rule.onAllNodes(
            SemanticsMatcher("undoAction") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals("Undo", ignoreCase = true) } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Expected Undo snackbar action after moving file to Trash", undoNodes.isNotEmpty())
        assertAllInteractiveTouchTargetsMeet48dp("Undo Snackbar active", base)
        val minButtonHeight: Dp = 36.dp
        rule.onNodeWithText("Undo").assertHeightIsAtLeast(minButtonHeight)
    }
}
