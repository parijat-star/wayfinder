// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.PhoneQuickAccessBanner
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compact phone-portrait layout regression tests (360dp x 640dp) ensuring dialog buttons,
 * banners, and headers never squeeze into vertical character-by-character columns or clip.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-xxhdpi")
class PhonePortraitUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(extras: Map<String, Any?> = emptyMap(), setup: VmHarness.() -> Unit = {}) {
        h = VmHarness(extras)
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        idle()
    }

    private fun idle() { rule.waitForIdle(); h.settle(); rule.waitForIdle() }

    private fun SemanticsNodeInteraction.assertSingleLineHorizontal(label: String) {
        assertIsDisplayed()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("$label width (${bounds.width}) should exceed height (${bounds.height})", bounds.width > bounds.height)
        assertTrue("$label height (${bounds.height}) should be <= 56.dp", bounds.height <= 56.dp)
    }

    @Test fun defaultHandlerDialogButtonsDoNotWrapVerticallyOnPhone() {
        launch()
        h.vm.showDialog(Dialog.DefaultHandler); idle()
        rule.onNodeWithText("Not now").assertSingleLineHorizontal("Not now")
        rule.onNodeWithText("Test Open-With Prompt").assertSingleLineHorizontal("Test Open-With Prompt")
        rule.onNodeWithText("App Default Settings").assertSingleLineHorizontal("App Default Settings")
    }

    @Test fun conflictDialogButtonsDoNotWrapVerticallyOnPhone() {
        launch { file("src/a.txt", "new"); file("dst/a.txt", "old"); navigate(pathOf("src")); select("src/a.txt") }
        h.vm.copy(); h.navigate(h.pathOf("dst")); h.vm.paste()
        h.until { h.s.dialog is Dialog.ConflictPrompt }; rule.waitForIdle()
        rule.onNodeWithText("Cancel").assertSingleLineHorizontal("Cancel")
        rule.onNodeWithText("Keep both").assertSingleLineHorizontal("Keep both")
        rule.onNodeWithText("Skip").assertSingleLineHorizontal("Skip")
        rule.onNodeWithText("Replace").assertSingleLineHorizontal("Replace")
    }

    @Test fun batchRenameDialogFitsPhonePortraitWithoutCrushing() {
        launch { file("br/a.txt"); file("br/b.txt"); navigate(pathOf("br")); select("br/a.txt", "br/b.txt") }
        h.vm.requestBatchRename(); idle()
        rule.onNodeWithText("Batch rename 2 items").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertSingleLineHorizontal("Cancel")
        rule.onNodeWithText("Apply").assertSingleLineHorizontal("Apply")
    }

    @Test fun connectDialogFitsPhonePortraitWithoutCrushing() {
        launch()
        h.vm.showDialog(Dialog.Connect(null)); idle()
        rule.onNodeWithText("Connect to Server").assertIsDisplayed()
        rule.onNodeWithText("Test connection").performScrollTo().assertSingleLineHorizontal("Test connection")
        rule.onNodeWithText("Cancel").assertSingleLineHorizontal("Cancel")
        rule.onNodeWithText("Connect & Mount").assertSingleLineHorizontal("Connect & Mount")
    }

    @Test fun quickLookHeaderFitsPhonePortraitWithoutCrushingFilename() {
        launch { file("ql/document-with-long-name.txt", "hello world"); navigate(pathOf("ql")); select("ql/document-with-long-name.txt") }
        h.vm.toggleQuickLook(); idle()
        val titleBounds = rule.onAllNodesWithText("document-with-long-name.txt").onLast().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("QuickLook title width (${titleBounds.width}) should be >= 100.dp", titleBounds.width >= 100.dp)
        rule.onNodeWithText("Open").assertSingleLineHorizontal("Open")
        rule.onNodeWithContentDescription("Close (Space)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Previous (←)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next (→)").assertIsDisplayed()
    }

    @Test fun pickerBannerButtonsDoNotWrapVerticallyOnPhone() {
        launch(mapOf(BrowserViewModel.EXTRA_PICKER to true)) {
            file("pk/sample.txt"); navigate(pathOf("pk"))
        }
        rule.onNodeWithText("Cancel").assertSingleLineHorizontal("Cancel")
        rule.onNodeWithText("Select File").assertSingleLineHorizontal("Select File")
    }

    @Test fun phoneQuickAccessBannerButtonsDoNotWrapVerticallyOnPhone() {
        h = VmHarness()
        h.settle()
        rule.setContent { WayfinderTheme(false) { PhoneQuickAccessBanner(h.vm, phone = null, quickAccessAvailable = true) } }
        idle()
        rule.onNodeWithText("Browse in Files").assertSingleLineHorizontal("Browse in Files")
        rule.onNodeWithText("Quick Share").assertSingleLineHorizontal("Quick Share")
        rule.onNodeWithText("Phone Hub").assertSingleLineHorizontal("Phone Hub")
    }
}
