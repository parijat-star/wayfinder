// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.R
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.paletteCommands
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
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class AboutDialogAndIconTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    @Test
    fun aboutDialogAccessibleFromOverflowAndPaletteAndLoadsBundledNotices() {
        val harness = VmHarness().also { h = it }

        // Verify Command Palette includes About Wayfinder
        val aboutCmd = paletteCommands(harness.vm, harness.s).firstOrNull { it.name == "About Wayfinder" }
        assertNotNull("Command palette should include About Wayfinder", aboutCmd)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        rule.waitForIdle()

        // Open OverflowMenu -> About Wayfinder
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("About Wayfinder").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Dialog.About, harness.s.dialog)

        rule.onNodeWithText("Version 2.0").assertIsDisplayed()
        rule.onNodeWithText("Apache License, Version 2.0", substring = true).assertIsDisplayed()

        // Toggle Open-source licenses view and verify bundled asset text loads
        rule.onNodeWithText("Open-source licenses").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                rule.onNodeWithText("BSD-3-Clause", substring = true).assertExists()
                true
            }.getOrDefault(false)
        }
        rule.onNodeWithText("Bouncy Castle", substring = true).assertExists()

        // Close dialog
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()
        assertEquals(null, harness.s.dialog)
    }

    @Test
    fun dedicatedMonochromeIconAndUnqualifiedAnydpiAdaptiveIconsAreConfigured() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val monoDrawable = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_monochrome)
        assertNotNull("Dedicated monochrome icon drawable must resolve", monoDrawable)

        val noticesAsset = ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
        assertTrue(noticesAsset.contains("SMBJ"))
        assertTrue(noticesAsset.contains("MIT"))
        assertTrue(noticesAsset.contains("BSD-3-Clause"))

        val projectDir = sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/src/main/res").isDirectory }
        val launcherXml = File(projectDir, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        val roundXml = File(projectDir, "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        assertTrue(launcherXml.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(roundXml.contains("@drawable/ic_launcher_monochrome"))
        assertFalse(launcherXml.contains("monochrome android:drawable=\"@drawable/ic_launcher_foreground\""))
    }
}
