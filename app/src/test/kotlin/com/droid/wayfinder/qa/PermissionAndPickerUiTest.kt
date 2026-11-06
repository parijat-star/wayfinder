// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.ui.SharedUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** MainActivity: All-files-access gate, granting while running, and picker intents (GET_CONTENT). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class PermissionAndPickerUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Before fun reset() {
        SharedUi.init(ApplicationProvider.getApplicationContext())
        SharedUi.updateMarks { Marks() }
        MainActivity.hasAccessOverride = { false }
    }

    @After fun tearDown() {
        MainActivity.hasAccessOverride = null
    }

    private fun allFilesAccess(granted: Boolean) {
        MainActivity.hasAccessOverride = { granted }
    }

    @Test fun permissionScreenShownUntilAccessIsGranted() {
        allFilesAccess(false)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            rule.onNodeWithText("Allow access to all files").assertIsDisplayed()
            rule.onNodeWithText("Open settings").performClick()
            sc.onActivity { a ->
                val started = shadowOf(a).nextStartedActivity
                org.junit.Assert.assertNotNull("Open settings launches all-files-access settings", started)
                assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)
            }
            // User grants access in Settings and comes back: the browser appears without a restart.
            allFilesAccess(true)
            sc.moveToState(Lifecycle.State.STARTED)
            sc.moveToState(Lifecycle.State.RESUMED)
            rule.waitForIdle()
            rule.onNodeWithContentDescription("New tab (Ctrl+T)").assertIsDisplayed()
        }
    }

    @Test fun getContentIntentOpensPickerMode() {
        allFilesAccess(true)
        File(Environment.getExternalStorageDirectory(), "Download").mkdirs()
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitForIdle()
            rule.onNodeWithText("Select a file to open in the requesting app").assertIsDisplayed()
            rule.onNodeWithText("Accepts image", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.waitForIdle()
            it.onActivity { a -> assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(a).resultCode) }
        }
    }
}
