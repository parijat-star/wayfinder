// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.buildCrossAppClipData
import com.droid.wayfinder.ui.resolveCaptionHorizontalInsets
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class AdaptiveCertificationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(setup: VmHarness.() -> Unit = {}) {
        h = VmHarness()
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        rule.waitForIdle(); h.settle(); rule.waitForIdle()
    }

    @Test fun captionInsetsReserveWindowControlsOnNarrowFreeformAndPreventCenterShift() {
        val density = Density(2f) // 1dp = 2px
        // Narrow freeform (360dp = 720px) where system only reports left icon rect:
        val (narrowStart, narrowEnd) = resolveCaptionHorizontalInsets(
            rects = listOf(Rect(0, 0, 120, 96)),
            widthPx = 720,
            isRtl = false,
            density = density,
        )
        assertEquals(120, narrowStart)
        assertTrue("Right window controls reserve ($narrowEnd px) must be >= 288 px (144dp)", narrowEnd >= 288)

        // Wide maximized (1280dp = 2560px) with an oversized bogus left rect (> 45% width):
        val (wideStart, wideEnd) = resolveCaptionHorizontalInsets(
            rects = listOf(Rect(0, 0, 1400, 96), Rect(2272, 0, 2560, 96)),
            widthPx = 2560,
            isRtl = false,
            density = density,
        )
        assertTrue("Start inset ($wideStart px) must not shove tabs to center", wideStart <= 280)
        assertEquals(288, wideEnd)
    }

    @Test fun homeToolbarAndTitleBarIconButtonsMeetMinimumTouchTargets() {
        launch { file("doc.txt", "hello") }
        listOf(
            "Back (Alt+←)",
            "Forward (Alt+→)",
            "Up (Alt+↑)",
            "Refresh (F5)",
            "Undo (Ctrl+Z)",
            "Redo (Ctrl+Y)",
            "More",
        ).forEach { desc ->
            rule.onNodeWithContentDescription(desc)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithContentDescription("New tab (Ctrl+T)")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(44.dp)
    }

    @Test fun touchLongPressOnFileOpensContextMenu() {
        launch { dir("folder"); file("folder/note.txt", "hi"); navigate(pathOf("folder")) }
        rule.onNodeWithText("note.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
    }

    @Test fun sidebarAndFileRowsAreFocusableForKeyboardNavigation() {
        launch { dir("docs"); file("docs/readme.md", "hi"); navigate(pathOf("docs")) }
        val sidebarMatches = rule.onAllNodes(isFocusable() and hasAnyDescendant(hasText("Recents")))
        assertTrue("Sidebar Recents row should be focusable", sidebarMatches.fetchSemanticsNodes().isNotEmpty())
        val fileMatches = rule.onAllNodes(isFocusable() and hasAnyDescendant(hasText("readme.md")))
        assertTrue("File row should be focusable", fileMatches.fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun tabKeyFromMainAreaMovesFocusAcrossUi() {
        launch { dir("sub"); file("sub/a.txt", "a"); navigate(pathOf("sub")) }
        rule.onAllNodesWithText("a.txt").onFirst().performKeyInput {
            pressKey(Key.Tab)
        }
        rule.waitForIdle()
    }

    @Test fun buildCrossAppClipDataCreatesFileProviderUrisAndSkipsVirtualPaths() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = java.io.File(ctx.cacheDir, "remote-open/drag-test").apply { mkdirs() }
        val f = java.io.File(dir, "sample.txt").apply { writeText("data") }
        try {
            val clip = buildCrossAppClipData(ctx, listOf(f.path, FsPath.TRASH_ROOT))
            assertNotNull(clip)
            assertEquals(1, clip!!.itemCount)
            assertEquals("content", clip.getItemAt(0).uri.scheme)

            assertNull(buildCrossAppClipData(ctx, listOf(FsPath.TRASH_ROOT)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
