// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.AesStrength
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.ZipCompressOptions
import com.droid.wayfinder.data.ZipCompressionLevel
import com.droid.wayfinder.data.ZipCrypto
import com.droid.wayfinder.data.ZipEntryEncryption
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class ZipAdvancedUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @Before fun setUp() { MainActivity.hasAccessOverride = { true } }

    @After fun tearDown() {
        MainActivity.hasAccessOverride = null
        h?.close()
    }

    private fun launch(setup: VmHarness.() -> Unit): VmHarness {
        val harness = VmHarness().also { h = it }
        harness.setup()
        harness.settle()
        rule.setContent { WayfinderTheme(false) { AppRoot(harness.vm) } }
        idle(harness)
        return harness
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))
    private fun dialogField() = rule.onNode(hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()))
    private fun dialogButton(label: String) = rule.onNode(
        hasText(label) and androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()),
    )

    @Test
    fun compressDialogAdvancedOptionsCreatesAesEncryptedZipExcludingHiddenFiles() {
        val harness = launch {
            file("proj/readme.txt", "hello world\n".repeat(100))
            file("proj/.env", "API_KEY=secret")
            file("proj/.git/HEAD", "ref: refs/heads/main")
            navigate(root.path)
            select("proj")
        }

        harness.vm.requestCompress()
        idle(harness)

        // Advanced options start collapsed so default quick compression stays uncluttered.
        rule.onNodeWithText("Compress to ZIP").assertIsDisplayed()
        rule.onNodeWithText("Advanced options").assertIsDisplayed()
        rule.onNodeWithText("Compression level").assertDoesNotExist()
        rule.onNodeWithText("Compress").assertIsEnabled()

        // Expand Advanced options: exact 4 compression levels, AES-256 password, exclude hidden; no Store or ZipCrypto toggle.
        rule.onNodeWithText("Advanced options").performClick()
        idle(harness)
        rule.onNodeWithText("None").assertIsDisplayed()
        rule.onNodeWithText("Fast").assertIsDisplayed()
        rule.onNodeWithText("Normal").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText("Maximum").assertIsDisplayed()
        rule.onNodeWithText("Store").assertDoesNotExist()
        rule.onNodeWithText("ZipCrypto", substring = true).assertDoesNotExist()

        rule.onNodeWithText("Maximum").performClick()
        idle(harness)
        rule.onNodeWithText("Maximum").assertIsSelected()

        // Mismatched confirm password shows inline error and disables Compress.
        val compressPwNode = field("Password (optional)").performScrollTo().fetchSemanticsNode()
        assertEquals(
            androidx.compose.ui.autofill.ContentDataType.None,
            compressPwNode.config.getOrNull(SemanticsProperties.ContentDataType),
        )
        assertEquals(true, compressPwNode.config.getOrNull(SemanticsProperties.IsSensitiveData))
        field("Password (optional)").performScrollTo().performTextInput("hunter2!")
        idle(harness)
        rule.onNodeWithText("Encrypted with AES-256. Filenames remain visible.").assertExists()
        dialogButton("Compress").assertIsNotEnabled()
        val confirmPwNode = field("Confirm password").performScrollTo().fetchSemanticsNode()
        assertEquals(
            androidx.compose.ui.autofill.ContentDataType.None,
            confirmPwNode.config.getOrNull(SemanticsProperties.ContentDataType),
        )
        assertEquals(true, confirmPwNode.config.getOrNull(SemanticsProperties.IsSensitiveData))
        field("Confirm password").performScrollTo().performTextInput("wrong")
        idle(harness)
        rule.onNodeWithText("Passwords don't match").assertExists()
        dialogButton("Compress").assertIsNotEnabled()

        // Matching password + exclude hidden files enables Compress.
        field("Confirm password").performTextReplacement("hunter2!")
        rule.onNodeWithText("Exclude hidden files").performScrollTo().performClick()
        idle(harness)
        dialogButton("Compress").assertIsEnabled().performClick()

        val zipFile = File(harness.root, "proj.zip")
        harness.until(what = "created encrypted zip") { zipFile.isFile && harness.s.ops.isEmpty() }
        idle(harness)
        assertNull(harness.s.dialog)

        // Verify created archive is AES-256 encrypted and skipped dotfiles/dot-folders.
        ZipCrypto.openZipFile(zipFile).use { z ->
            val names = z.entries.toList().map { ZipCrypto.decodeEntryName(it) }.toSet()
            assertTrue("proj/readme.txt" in names)
            assertFalse("proj/.env" in names)
            assertFalse("proj/.git/" in names)
            assertFalse("proj/.git/HEAD" in names)
            val entry = z.getEntry("proj/readme.txt")
            val enc = ZipCrypto.detectEncryption(entry) as ZipEntryEncryption.WinZipAes
            assertEquals(AesStrength.AES256, enc.strength)
            assertEquals(8, enc.actualMethod)
        }
    }

    @Test
    fun passwordProtectedZipBrowsesWithoutPromptAndPromptsLazilyOnOpen() {
        val pw = "vault-pass-42"
        val harness = launch {
            val src = dir("vault-src")
            File(src, "alpha.txt").writeText("alpha secret body")
            File(src, "beta.txt").writeText("beta secret body")
            val ops = FileOperations(FileRepository(LocalFileSystem(), LocalFileSystem(), null))
            runBlocking {
                ops.compress(
                    sources = listOf(File(src, "alpha.txt").path, File(src, "beta.txt").path),
                    zipPath = File(root, "vault.zip").path,
                    options = ZipCompressOptions(ZipCompressionLevel.Normal, password = pw),
                ) {}
            }
            navigate(root.path)
            select("vault.zip")
        }

        // 1. Quick Look previews the encrypted archive summary with lock badge and NO password prompt.
        harness.vm.toggleQuickLook()
        rule.waitUntil(8_000) { rule.onAllNodesWithText("Password-protected").fetchSemanticsNodes().isNotEmpty() }
        assertNull("Quick Look must not prompt for password", harness.s.dialog)
        harness.vm.closeQuickLook()
        idle(harness)

        // 2. Browsing into the encrypted archive lists entries without prompting for a password.
        val zipPath = harness.pathOf("vault.zip")
        harness.vm.browseArchive(zipPath)
        idle(harness)
        assertNull("Browsing an encrypted ZIP must not prompt for password", harness.s.dialog)
        assertEquals(listOf("alpha.txt", "beta.txt"), harness.names().sorted())
        assertTrue(harness.s.active.entries.all { it.isEncrypted })
        rule.onNodeWithText("alpha.txt").assertIsDisplayed()

        // 3. Opening an encrypted file prompts Dialog.ZipPassword.
        val alphaEntry = harness.s.active.entries.single { it.name == "alpha.txt" }
        harness.vm.open(alphaEntry)
        harness.until(what = "zip password dialog") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)
        rule.onNodeWithText("Password required").assertIsDisplayed()
        rule.onNodeWithText("“vault.zip” is password-protected.").assertIsDisplayed()

        // 4. Wrong password keeps dialog open and displays inline error; autofill is opted out and marked sensitive.
        val unlockPwNode = dialogField().fetchSemanticsNode()
        assertEquals(
            androidx.compose.ui.autofill.ContentDataType.None,
            unlockPwNode.config.getOrNull(SemanticsProperties.ContentDataType),
        )
        assertEquals(true, unlockPwNode.config.getOrNull(SemanticsProperties.IsSensitiveData))
        dialogField().performTextInput("wrong-pw")
        dialogButton("Unlock").assertIsEnabled().performClick()
        harness.until(what = "incorrect password error") { (harness.s.dialog as? Dialog.ZipPassword)?.error != null }
        idle(harness)
        rule.onNodeWithText("Incorrect password. Please try again.").assertIsDisplayed()

        // 5. Correct password unlocks, dismisses dialog, and emits OpenFile with decrypted content.
        harness.events.clear()
        dialogField().performTextReplacement(pw)
        dialogButton("Unlock").performClick()
        harness.until(what = "OpenFile event for alpha.txt") {
            harness.events.filterIsInstance<UiEvent.OpenFile>().any { it.path.endsWith("alpha.txt") }
        }
        idle(harness)
        assertNull(harness.s.dialog)
        val openedAlpha = harness.events.filterIsInstance<UiEvent.OpenFile>().first { it.path.endsWith("alpha.txt") }
        assertEquals("alpha secret body", File(openedAlpha.path).readText())

        // 6. Opening a second encrypted file in the same session reuses cached in-memory password without prompting.
        harness.events.clear()
        val betaEntry = harness.s.active.entries.single { it.name == "beta.txt" }
        harness.vm.open(betaEntry)
        harness.until(what = "OpenFile event for beta.txt") {
            harness.events.filterIsInstance<UiEvent.OpenFile>().any { it.path.endsWith("beta.txt") }
        }
        idle(harness)
        assertNull("Cached session password should avoid re-prompting", harness.s.dialog)
        val openedBeta = harness.events.filterIsInstance<UiEvent.OpenFile>().first { it.path.endsWith("beta.txt") }
        assertEquals("beta secret body", File(openedBeta.path).readText())
    }

    @Test
    fun copyingOutAndExtractingEncryptedZipPromptLazilyAndSucceed() {
        val pw = "copy-extract-99"
        val harness = launch {
            val src = dir("src-ce")
            File(src, "note.txt").writeText("decrypted note content")
            dir("copy-dest")
            val ops = FileOperations(FileRepository(LocalFileSystem(), LocalFileSystem(), null))
            runBlocking {
                ops.compress(
                    sources = listOf(File(src, "note.txt").path),
                    zipPath = File(root, "locked-copy.zip").path,
                    options = ZipCompressOptions(ZipCompressionLevel.Fast, password = pw),
                ) {}
                ops.compress(
                    sources = listOf(File(src, "note.txt").path),
                    zipPath = File(root, "locked-extract.zip").path,
                    options = ZipCompressOptions(ZipCompressionLevel.None, password = pw),
                ) {}
            }
            navigate(FsPath.zipRoot(File(root, "locked-copy.zip").path))
        }

        // 1. Copy out of locked-copy.zip into copy-dest -> prompts for password, then copies decrypted file.
        val noteEntry = harness.s.active.entries.single { it.name == "note.txt" }
        harness.vm.setSelection(setOf(noteEntry.path))
        harness.vm.copy()
        harness.navigate(harness.pathOf("copy-dest"))
        harness.vm.paste()
        harness.until(what = "password prompt on copy-out") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)
        dialogField().performTextInput(pw)
        dialogButton("Unlock").performClick()
        val copiedFile = File(harness.root, "copy-dest/note.txt")
        harness.until(what = "copied decrypted file") { copiedFile.isFile && harness.s.ops.isEmpty() }
        idle(harness)
        assertEquals("decrypted note content", copiedFile.readText())

        // 2. Extract locked-extract.zip -> prompts for password, then extracts decrypted folder.
        harness.navigate(harness.root.path)
        harness.select("locked-extract.zip")
        harness.vm.extractSelection()
        harness.until(what = "password prompt on extract") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)
        dialogField().performTextInput(pw)
        dialogButton("Unlock").performClick()
        val extractedFile = File(harness.root, "locked-extract/note.txt")
        harness.until(what = "extracted decrypted file") { extractedFile.isFile && harness.s.ops.isEmpty() }
        idle(harness)
        assertEquals("decrypted note content", extractedFile.readText())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-xhdpi")
    fun compressAndZipPasswordDialogsMeet48dpTouchTargetsInCompactPortrait() {
        var density: Density? = null
        val harness = VmHarness().also { h = it }
        harness.file("sample.txt", "hello")
        harness.navigate(harness.root.path)
        harness.select("sample.txt")
        harness.settle()

        rule.setContent {
            density = LocalDensity.current
            WayfinderTheme(false) { AppRoot(harness.vm) }
        }
        idle(harness)
        val d = density!!

        fun assertDialogControlsAtLeast48dp(label: String) {
            val minPx = with(d) { 47.5.dp.toPx() }
            val matcher = SemanticsMatcher("dialogInteractive") { node ->
                SemanticsActions.OnClick in node.config || SemanticsActions.OnLongClick in node.config
            }
            val nodes = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            val violations = nodes.mapNotNull { node ->
                val bounds = node.boundsInRoot
                if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
                val touchBounds = node.touchBoundsInRoot
                val w = maxOf(node.size.width.toFloat(), touchBounds.width)
                val hPx = maxOf(node.size.height.toFloat(), touchBounds.height)
                if (w < minPx || hPx < minPx) {
                    val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                        ?: "node#${node.id}"
                    "$label: '$desc' is ${with(d) { w.toDp() }} x ${with(d) { hPx.toDp() }}"
                } else null
            }
            assertTrue("Touch targets < 48dp:\n" + violations.joinToString("\n"), violations.isEmpty())
        }

        // Expanded CompressDialog with password + confirm password visible.
        harness.vm.requestCompress()
        idle(harness)
        rule.onNodeWithText("Advanced options").performClick()
        idle(harness)
        field("Password (optional)").performScrollTo().performTextInput("abc")
        idle(harness)
        rule.onNodeWithContentDescription("Show password").assertIsDisplayed()
        assertDialogControlsAtLeast48dp("CompressDialog expanded")
        harness.vm.dismissDialog()
        idle(harness)

        // ZipPasswordDialog.
        harness.vm.showDialog(Dialog.ZipPassword("/tmp/locked.zip", "locked.zip"))
        idle(harness)
        assertDialogControlsAtLeast48dp("ZipPasswordDialog")
    }
}
