// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.SafAndDocumentsProviderCoverageTest
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.QuickLook
import com.droid.wayfinder.ui.Toolbar
import com.droid.wayfinder.ui.icon
import com.droid.wayfinder.ui.pinIcon
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class ResponsiveUiAndQuickLookTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    @Test
    fun openPinnedInNewTabForNonLocalRegularFileOpensParentDirectoryTab() {
        val harness = VmHarness().also { h = it }
        val auth = "test.pinned.docs"
        val provider = Robolectric.buildContentProvider(SafAndDocumentsProviderCoverageTest.InMemorySafProvider::class.java)
            .create(auth)
            .get()
        provider.seed(File(harness.root, "saf_backing").apply { mkdirs() })

        val docFilePath = "doc://$auth/primary:Work/hello.txt"
        val docParentPath = "doc://$auth/primary:Work"

        val initialTabCount = harness.s.tabs.size
        harness.vm.openPinned(docFilePath, newTab = true)
        harness.settle()

        assertEquals(initialTabCount + 1, harness.s.tabs.size)
        assertEquals(docParentPath, harness.s.active.path)
    }

    @Test
    fun compactToolbarExposesViewAndSortActionsInOverflowMenu() {
        val harness = VmHarness().also { h = it }
        harness.file("alpha.txt", "abc")
        harness.file("beta.txt", "abcdef")
        harness.navigate(harness.root.path)

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = 560.dp, height = 220.dp)) {
                    Toolbar(harness.vm, s)
                }
            }
        }
        rule.waitForIdle()

        // Switch to Grid view from compact OverflowMenu
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Grid view").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ViewMode.Grid, harness.s.active.viewMode)

        // Switch Sort key to Size from compact OverflowMenu
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Sort by Size").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(SortKey.Size, harness.s.active.sortKey)

        // Toggle sort direction to descending from compact OverflowMenu
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Sort descending").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(harness.s.active.sortAsc)
    }

    @Test
    fun compactQuickLookAdaptsPaddingAndPinIconAvoidsSynchronousDiskChecks() {
        val harness = VmHarness().also { h = it }
        val note = harness.file("compact_note.txt", "hello split screen")
        harness.navigate(harness.root.path)
        harness.select("compact_note.txt")
        harness.vm.toggleQuickLook()
        harness.settle()

        // Verify pinIcon uses isKnownFile parameter cleanly for extensionless files vs folders
        val extensionlessPath = File(harness.root, "Makefile").path
        assertEquals(Icons.Outlined.Folder, pinIcon(extensionlessPath, isKnownFile = false))
        assertEquals(FileKind.fromName("Makefile").icon(), pinIcon(extensionlessPath, isKnownFile = true))

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = 560.dp, height = 420.dp)) {
                    QuickLook(harness.vm, s)
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithText("hello split screen", substring = true).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        assertEquals(note.path, harness.s.quickLook)
    }
}
