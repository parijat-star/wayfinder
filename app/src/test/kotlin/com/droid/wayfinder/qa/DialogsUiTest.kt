// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.DownloadManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ConnectConfig
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Dialog flows (batch rename, connect, new folder, conflicts, delete, properties) and the picker banner. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class DialogsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: VmHarness

    @After fun tearDown() { if (::h.isInitialized) h.close() }

    private fun launch(extras: Map<String, Any?> = emptyMap(), setup: VmHarness.() -> Unit) {
        h = VmHarness(extras)
        h.setup()
        h.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(h.vm) } }
        idle()
    }

    private fun idle() { rule.waitForIdle(); h.settle(); rule.waitForIdle() }

    private fun dialogField() = rule.onNode(hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()))
    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test fun batchRenamePreviewFlagsConflictsAndDisablesApply() {
        launch { file("br/a.txt"); file("br/b.txt"); file("br/c.txt"); navigate(pathOf("br")); select("br/a.txt", "br/b.txt") }
        h.vm.requestBatchRename(); idle()
        rule.onNodeWithText("Batch rename 2 items").assertIsDisplayed()
        rule.onNodeWithText("No changes yet").assertIsDisplayed()
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        field("Find").performTextInput("a")
        field("Replace with").performTextInput("c")
        idle()
        // a.txt → c.txt clashes with the existing, unselected c.txt.
        rule.onNodeWithText("1 conflict — fix it to continue").assertIsDisplayed()
        rule.onNodeWithText("c.txt  (Already exists)").assertIsDisplayed()
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        field("Replace with").performTextReplacement("z")
        idle()
        rule.onNodeWithText("1 item will be renamed").assertIsDisplayed()
        rule.onNodeWithText("Apply").assertIsEnabled().performClick()
        idle()
        assertTrue(File(h.root, "br/z.txt").exists())
        assertFalse(File(h.root, "br/a.txt").exists())
        assertNull(h.s.dialog)
    }

    @Test fun batchRenameNumberedAndPrefixModes() {
        launch { file("n/x.jpg"); file("n/y.jpg"); navigate(pathOf("n")); select("n/x.jpg", "n/y.jpg") }
        h.vm.requestBatchRename(); idle()
        rule.onNodeWithText("Numbered sequence").performClick(); idle()
        field("Base name").performTextReplacement("Trip")
        idle()
        rule.onNodeWithText("Trip 1.jpg").assertIsDisplayed()
        rule.onNodeWithText("Trip 2.jpg").assertIsDisplayed()
        rule.onNodeWithText("Add prefix / suffix").performClick(); idle()
        field("Prefix").performTextInput("2026-")
        idle()
        rule.onNodeWithText("2026-x.jpg").assertIsDisplayed()
        rule.onNodeWithText("Apply").performClick(); idle()
        assertTrue(File(h.root, "n/2026-x.jpg").exists() && File(h.root, "n/2026-y.jpg").exists())
    }

    @Test fun connectDialogAutofillsFieldsFromUri() {
        launch { }
        h.vm.showConnectDialog(); idle()
        rule.onNodeWithText("Connect to Server").assertIsDisplayed()
        rule.onNodeWithText("Connect & Mount").assertIsNotEnabled()
        field("Server address or UNC path").performTextInput("ftp://bob:pa%40ss@nas.local:2121/pub/music")
        idle()
        field("Host").assert(hasText("nas.local"))
        field("Port").assert(hasText("2121"))
        field("Base path (optional)").assert(hasText("pub/music"))
        field("User name").assert(hasText("bob"))
        rule.onNodeWithText("FTP").assertIsSelected()
        rule.onNodeWithText("Connect & Mount").assertIsEnabled()
        // UNC path switches to SMB and shows the domain field.
        field("Server address or UNC path").performTextReplacement("\\\\fileserver\\media\\films")
        idle()
        rule.onNodeWithText("SMB2/3").assertIsSelected()
        field("Host").assert(hasText("fileserver"))
        field("Share / folder (optional)").assert(hasText("media/films"))
        field("Port").assert(hasText("445"))
        rule.onNodeWithText("Domain / workgroup (optional)").assertExists()
        // Switching protocol updates the default port.
        rule.onNodeWithText("WebDAVS").performClick(); idle()
        field("Port").assert(hasText("443"))
    }

    @Test fun connectDialogPrefillsSavedServer() {
        launch { }
        h.vm.showConnectDialog(ConnectConfig(Protocol.DAVS, "cloud.example", 8443, "remote.php/dav", "me", alias = "My cloud")); idle()
        field("Host").assert(hasText("cloud.example"))
        field("Port").assert(hasText("8443"))
        field("Sidebar name (optional)").assert(hasText("My cloud"))
        rule.onNodeWithText("WebDAVS").assertIsSelected()
        rule.onNodeWithText("Cancel").performClick(); idle()
        assertNull(h.s.dialog)
    }

    @Test fun connectDialogTestConnectionReportsUnreachableServer() {
        launch { }
        val port = java.net.ServerSocket(0).use { it.localPort }
        h.vm.showConnectDialog(ConnectConfig(Protocol.FTP, "127.0.0.1", port, guest = true)); idle()
        rule.onNodeWithText("Test connection").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Can't reach", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun newFolderDialogCreatesFolder() {
        launch { dir("nf"); navigate(pathOf("nf")) }
        h.vm.requestNewItem(folder = true); idle()
        rule.onNodeWithText("Create").assertIsEnabled()
        dialogField().performTextReplacement("Invoices 2026")
        rule.onNodeWithText("Create").performClick(); idle()
        assertTrue(File(h.root, "nf/Invoices 2026").isDirectory)
        assertEquals(setOf(h.pathOf("nf/Invoices 2026")), h.s.active.selection)
        h.vm.requestNewItem(folder = true); idle()
        dialogField().performTextReplacement("   ")
        rule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test fun renameDialogPreselectsBaseNameAndRenames() {
        launch { file("rn/photo.jpg"); navigate(pathOf("rn")); select("rn/photo.jpg") }
        h.vm.requestRename(); idle()
        dialogField().performTextReplacement("holiday.jpg")
        rule.onNode(hasText("Rename") and androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick(); idle()
        assertTrue(File(h.root, "rn/holiday.jpg").exists())
    }

    @Test fun conflictPromptButtons() {
        launch { file("src/a.txt", "new"); file("dst/a.txt", "old"); navigate(pathOf("src")); select("src/a.txt") }
        h.vm.copy(); h.navigate(h.pathOf("dst")); h.vm.paste()
        h.until { h.s.dialog is Dialog.ConflictPrompt }; rule.waitForIdle()
        rule.onNodeWithText("“a.txt” already exists").assertIsDisplayed()
        rule.onNodeWithText("Skip").assertExists(); rule.onNodeWithText("Replace").assertExists()
        rule.onNodeWithText("Keep both").performClick()
        h.until { File(h.root, "dst/a (1).txt").exists() }; idle()
        assertEquals("old", File(h.root, "dst/a.txt").readText())
    }

    @Test fun permanentDeleteDialogConfirms() {
        launch { file("del/x.txt"); navigate(pathOf("del")); select("del/x.txt") }
        h.vm.requestDelete(permanent = true); idle()
        rule.onNodeWithText("Delete permanently?").assertIsDisplayed()
        rule.onNodeWithText("“x.txt” will be deleted and can't be recovered.").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick(); idle()
        assertFalse(File(h.root, "del/x.txt").exists())
    }

    @Test fun propertiesDialogShowsChecksums() {
        launch { file("pp/abc.txt", "abc"); navigate(pathOf("pp")); select("pp/abc.txt") }
        h.vm.showProperties()
        h.until { (h.s.dialog as? Dialog.Properties)?.checksums != null }; idle()
        rule.onNodeWithText("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad").assertExists()
        rule.onNodeWithText("352441c2").assertExists()
        rule.onNodeWithText("Close").performClick(); idle()
        assertNull(h.s.dialog)
    }

    @Test fun pickerBannerEnablesSelectOnlyForAcceptedFiles() {
        launch(mapOf(BrowserViewModel.EXTRA_PICKER to true, BrowserViewModel.EXTRA_PICKER_MIME to arrayOf("text/plain"))) {
            file("pk/a.txt"); file("pk/b.zip"); navigate(pathOf("pk"))
        }
        rule.onNodeWithText("Select a file to open in the requesting app").assertIsDisplayed()
        rule.onNodeWithText("Select File").assertIsNotEnabled()
        h.select("pk/b.zip"); idle()
        rule.onNodeWithText("Select File").assertIsNotEnabled()
        h.select("pk/a.txt"); idle()
        rule.onNodeWithText("Select File").assertIsEnabled().performClick(); idle()
        assertEquals(listOf(h.pathOf("pk/a.txt")), h.events.filterIsInstance<UiEvent.PickResult>().single().paths)
    }

    @Test fun defaultHandlerDialogClarifiesSystemPickerAndTriggersViewDownloads() {
        launch { }
        val shadowActivity = shadowOf(rule.activity)
        while (shadowActivity.nextStartedActivity != null) { /* clear any launch intents */ }

        // Open via Command Palette command to verify keyboard-accessible entry point as well.
        val paletteCmd = paletteCommands(h.vm, h.s).single { it.name == "Default file handler…" }
        assertFalse(paletteCmd.description.contains("file picking", ignoreCase = true))
        paletteCmd.action()
        idle()
        assertEquals(Dialog.DefaultHandler, h.s.dialog)

        // Renders default-eligible actions and clarified system File Picker note (no "Save As" takeover claim).
        rule.onNodeWithText("Make Wayfinder your default file handler?").assertIsDisplayed()
        rule.onNodeWithText("Actions Wayfinder can handle by default").assertIsDisplayed()
        rule.onNodeWithText("Folders & Downloads", substring = true).assertExists()
        rule.onNodeWithText("Archives (.zip, .tar.gz, .tgz, .tar)", substring = true).assertExists()
        rule.onNodeWithText("Android reserves default Open and Save As file picker prompts for the system Files app", substring = true).assertExists()
        rule.onNodeWithText("left sidebar", substring = true).assertExists()
        rule.onNodeWithText("Attach or save files when prompted by browsers", substring = true).assertDoesNotExist()
        rule.onNodeWithText("separately for each action and format", substring = true).assertExists()
        rule.onNodeWithText("first time you open one from another app", substring = true).assertExists()

        // On wide viewports, all three buttons align on a single horizontal row from left to right.
        val wideNotNow = rule.onNodeWithText("Not now").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val wideTestPrompt = rule.onNodeWithText("Test Open-With Prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val wideAppSettings = rule.onNodeWithText("App Default Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(wideNotNow.center.y, wideTestPrompt.center.y, 4f * rule.density.density)
        assertEquals(wideTestPrompt.center.y, wideAppSettings.center.y, 4f * rule.density.density)
        assertTrue(wideNotNow.right < wideTestPrompt.left)
        assertTrue(wideTestPrompt.right < wideAppSettings.left)

        // "Not now" dismisses cleanly without launching any external intent.
        rule.onNodeWithText("Not now").performClick()
        idle()
        assertNull(h.s.dialog)
        assertNull(shadowActivity.nextStartedActivity)

        // "Test Open-With Prompt" fires DownloadManager.ACTION_VIEW_DOWNLOADS and dismisses the dialog.
        h.vm.showDialog(Dialog.DefaultHandler)
        idle()
        rule.onNodeWithText("Test Open-With Prompt").performClick()
        idle()
        assertNull(h.s.dialog)
        val started = shadowActivity.nextStartedActivity
        assertNotNull(started)
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, started.action)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-xhdpi")
    fun defaultHandlerDialogButtonsStackCleanlyWithoutVerticalWrappingInCompactPortrait() {
        launch { }
        val shadowActivity = shadowOf(rule.activity)
        while (shadowActivity.nextStartedActivity != null) { /* clear any launch intents */ }

        h.vm.showDialog(Dialog.DefaultHandler)
        idle()

        val densityPx = rule.density.density
        val appSettingsBounds = rule.onNodeWithText("App Default Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val testPromptBounds = rule.onNodeWithText("Test Open-With Prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val notNowBounds = rule.onNodeWithText("Not now").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        // None of the buttons may squish into a multi-line vertical strip or overlap each other.
        for ((label, rect) in listOf(
            "App Default Settings" to appSettingsBounds,
            "Test Open-With Prompt" to testPromptBounds,
            "Not now" to notNowBounds,
        )) {
            assertTrue("$label width (${rect.width / densityPx}dp) should stay wide enough for a single-line label", rect.width >= 80f * densityPx)
            assertTrue("$label height (${rect.height / densityPx}dp) should stay single-line (<= 64dp)", rect.height <= 64f * densityPx)
        }
        assertFalse("App Default Settings and Test Open-With Prompt must not overlap", appSettingsBounds.overlaps(testPromptBounds))
        assertFalse("Test Open-With Prompt and Not now must not overlap", testPromptBounds.overlaps(notNowBounds))
        assertFalse("App Default Settings and Not now must not overlap", appSettingsBounds.overlaps(notNowBounds))
        assertTrue("Primary button sits above secondary button in stacked compact layout", appSettingsBounds.top < testPromptBounds.top)
        assertTrue("Secondary button sits above dismiss button in stacked compact layout", testPromptBounds.top < notNowBounds.top)

        // Clicking "App Default Settings" opens application details settings and dismisses the dialog.
        rule.onNodeWithText("App Default Settings").performClick()
        idle()
        assertNull(h.s.dialog)
        val started = shadowActivity.nextStartedActivity
        assertNotNull(started)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w890dp-h412dp-land-xhdpi")
    fun defaultHandlerDialogExpandsToSingleRowFooterInPhoneLandscape() {
        launch { }
        h.vm.showDialog(Dialog.DefaultHandler)
        idle()

        val densityPx = rule.density.density
        rule.onNodeWithText("Actions Wayfinder can handle by default").assertIsDisplayed()
        val notNowBounds = rule.onNodeWithText("Not now").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val testPromptBounds = rule.onNodeWithText("Test Open-With Prompt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val appSettingsBounds = rule.onNodeWithText("App Default Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertEquals(notNowBounds.center.y, testPromptBounds.center.y, 4f * densityPx)
        assertEquals(testPromptBounds.center.y, appSettingsBounds.center.y, 4f * densityPx)
        assertTrue(notNowBounds.right < testPromptBounds.left)
        assertTrue(testPromptBounds.right < appSettingsBounds.left)
    }
}
