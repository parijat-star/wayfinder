// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.ui.BrowserViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** An already-running window (onNewIntent) handles new "open this location" requests. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunningWindowIntentTest {
    private var h: VmHarness? = null

    @After fun tearDown() { h?.close() }

    @Test fun newViewIntentForApkShowsItsFolderInTheRunningWindow() {
        val harness = VmHarness().also { h = it }
        val apk = harness.file(".gemini/run/app-debug.apk", "apk")
        val incoming = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(apk), MainActivity.APK_MIME)
        assertTrue(MainActivity.routeNewIntent(harness.app, incoming, harness.vm))
        harness.settle()
        assertEquals(apk.parent, harness.s.active.path)
        assertTrue(harness.s.showHidden)
        assertEquals(setOf(apk.path), harness.s.active.selection)
    }

    @Test fun spoofedInternalExtrasAndUnrelatedIntentsAreIgnored() {
        val harness = VmHarness().also { h = it }
        val before = harness.s.active.path
        // Another app can't steer the window through Wayfinder's internal EXTRA_PATH.
        val spoofed = Intent(Intent.ACTION_MAIN).putExtra(BrowserViewModel.EXTRA_PATH, "/data/data/com.droid.wayfinder")
        assertFalse(MainActivity.routeNewIntent(harness.app, spoofed, harness.vm))
        // Private app data is never a target.
        val privateUri = Uri.fromFile(java.io.File(harness.app.applicationInfo.dataDir, "shared_prefs"))
        assertFalse(MainActivity.routeNewIntent(harness.app, Intent(Intent.ACTION_VIEW, privateUri), harness.vm))
        harness.settle()
        assertEquals(before, harness.s.active.path)
    }
}
