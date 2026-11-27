// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Intent
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
import com.droid.wayfinder.data.WinZipAesCtrCipher
import com.droid.wayfinder.data.ZipCompressOptions
import com.droid.wayfinder.data.ZipCompressionLevel
import com.droid.wayfinder.data.ZipCrypto
import com.droid.wayfinder.data.ZipEntryEncryption
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.returnPicked
import com.droid.wayfinder.ui.theme.WayfinderTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class ZipAdvancedUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

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
        rule.onNodeWithText("Entry: alpha.txt").assertIsDisplayed()

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
        assertTrue("Decrypted opens must stage under zip-open/enc/", openedAlpha.path.contains("zip-open/enc/"))
        assertTrue("Decrypted opens must stage under zip-open/enc/", openedBeta.path.contains("zip-open/enc/"))

        // 7. Closing the ViewModel session (onCleared) wipes decrypted staged files from cacheDir.
        harness.close()
        h = null
        assertFalse("Decrypted alpha.txt must be wiped on session exit", File(openedAlpha.path).exists())
        assertFalse("Decrypted beta.txt must be wiped on session exit", File(openedBeta.path).exists())
    }

    @Test
    fun pickerModeEncryptedZipPromptsForPasswordAndReturnsDecryptedFile() {
        val pw = "picker-secret-77"
        val harness = VmHarness(
            mapOf(
                com.droid.wayfinder.ui.BrowserViewModel.EXTRA_PICKER to true,
                com.droid.wayfinder.ui.BrowserViewModel.EXTRA_PICKER_MIME to arrayOf("*/*"),
            ),
        ).also { h = it }
        val src = harness.dir("picker-src")
        File(src, "contract.txt").writeText("picked secret contract")
        val ops = FileOperations(FileRepository(LocalFileSystem(), LocalFileSystem(), null))
        runBlocking {
            ops.compress(
                sources = listOf(File(src, "contract.txt").path),
                zipPath = File(harness.root, "picker-vault.zip").path,
                options = ZipCompressOptions(ZipCompressionLevel.Normal, password = pw),
            ) {}
        }
        harness.navigate(FsPath.zipRoot(File(harness.root, "picker-vault.zip").path))
        rule.setContent { WayfinderTheme(false) { AppRoot(harness.vm) } }
        idle(harness)

        val entry = harness.s.active.entries.single { it.name == "contract.txt" }
        harness.vm.pick(listOf(entry))
        harness.until(what = "password prompt in picker mode") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)

        dialogField().performTextInput(pw)
        dialogButton("Unlock").performClick()
        harness.until(what = "PickResult event") { harness.events.filterIsInstance<UiEvent.PickResult>().isNotEmpty() }
        idle(harness)

        val pickedPath = harness.events.filterIsInstance<UiEvent.PickResult>().single().paths.single()
        assertTrue("Encrypted picker file must stage under picker/enc/", pickedPath.contains("picker/enc/"))
        assertEquals("picked secret contract", File(pickedPath).readText())

        // Returned picker intent for staged cache files must be strictly read-only, and WayfinderFileProvider
        // must reject writable modes ("rw") on `picker/` URIs.
        val pickActivity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        returnPicked(pickActivity, listOf(pickedPath))
        val resultIntent = shadowOf(pickActivity).resultIntent
        assertNotNull("Expected picker result intent", resultIntent)
        val resultUri = resultIntent!!.data!!
        assertTrue((resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(
            "picker/enc staged files must NOT grant FLAG_GRANT_WRITE_URI_PERMISSION",
            0,
            resultIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        try {
            pickActivity.contentResolver.openFileDescriptor(resultUri, "rw")?.close()
            fail("WayfinderFileProvider must reject writable mode on picker/ URIs")
        } catch (_: SecurityException) {
        }
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

        // Expanded CompressDialog with password + confirm password visible (toggling FLAG_SECURE must not stall Compose).
        harness.vm.requestCompress()
        idle(harness)
        rule.onNodeWithText("Advanced options").performClick()
        idle(harness)
        field("Password (optional)").performScrollTo().performTextInput("abc")
        idle(harness)
        rule.onNodeWithContentDescription("Show password").assertIsDisplayed()
        assertDialogControlsAtLeast48dp("CompressDialog expanded")

        // Passwords over 99 UTF-8 bytes display the 7-Zip compatibility warning note.
        field("Password (optional)").performScrollTo().performTextReplacement("p".repeat(100))
        idle(harness)
        rule.onNodeWithText("Encrypted with AES-256. Passwords over 99 bytes may not open in 7-Zip.").assertExists()
        harness.vm.dismissDialog()
        idle(harness)

        // ZipPasswordDialog.
        harness.vm.showDialog(Dialog.ZipPassword("/tmp/locked.zip", "locked.zip", targetInnerPath = "secret/report.txt"))
        idle(harness)
        assertDialogControlsAtLeast48dp("ZipPasswordDialog")
    }

    @Test
    fun multiWindowSessionIsolationAndMultiPasswordPromptUx() {
        val pw1 = "window1-alpha-pw"
        val pw2 = "window1-beta-pw"
        val harness = launch {
            val multiZip = File(root, "multi-vault.zip").apply {
                writeBytes(
                    buildMultiPasswordAesZip(
                        "one.txt" to ("one-secret".toByteArray(Charsets.UTF_8) to pw1),
                        "two.txt" to ("two-secret".toByteArray(Charsets.UTF_8) to pw2),
                    ),
                )
            }
            navigate(FsPath.zipRoot(multiZip.path))
        }

        // 1. Unlock `one.txt` in Window 1.
        val oneEntry = harness.s.active.entries.single { it.name == "one.txt" }
        harness.vm.open(oneEntry)
        harness.until(what = "prompt for one.txt") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)
        rule.onNodeWithText("Entry: one.txt").assertIsDisplayed()
        assertNull((harness.s.dialog as Dialog.ZipPassword).error)

        harness.events.clear()
        dialogField().performTextInput(pw1)
        dialogButton("Unlock").performClick()
        harness.until(what = "OpenFile one.txt") {
            harness.events.filterIsInstance<UiEvent.OpenFile>().any { it.path.endsWith("one.txt") }
        }
        idle(harness)
        val openedOne = harness.events.filterIsInstance<UiEvent.OpenFile>().single { it.path.endsWith("one.txt") }
        assertTrue(openedOne.path.contains("zip-open/enc/${harness.vm.vmSessionId}/"))
        assertTrue(File(openedOne.path).exists())

        // 2. Opening and closing a sibling Window 2 (`VmHarness`) must NOT clobber Window 1's active decrypted file.
        val siblingWindow = VmHarness()
        siblingWindow.settle()
        siblingWindow.close()
        assertTrue(
            "Sibling window init/onCleared must not delete active Window 1 decrypted staging",
            File(openedOne.path).exists(),
        )

        // 3. Opening `two.txt` (encrypted with a different password `pw2`) prompts cleanly for `two.txt`
        // WITHOUT a false "Incorrect password" error banner.
        harness.events.clear()
        val twoEntry = harness.s.active.entries.single { it.name == "two.txt" }
        harness.vm.open(twoEntry)
        harness.until(what = "prompt for two.txt") { harness.s.dialog is Dialog.ZipPassword }
        idle(harness)
        val twoDialog = harness.s.dialog as Dialog.ZipPassword
        assertEquals("two.txt", twoDialog.targetInnerPath)
        assertNull("Untouched second entry in multi-password ZIP must not show false wrong-password error", twoDialog.error)
        rule.onNodeWithText("Entry: two.txt").assertIsDisplayed()

        dialogField().performTextInput(pw2)
        dialogButton("Unlock").performClick()
        harness.until(what = "OpenFile two.txt") {
            harness.events.filterIsInstance<UiEvent.OpenFile>().any { it.path.endsWith("two.txt") }
        }
        idle(harness)
        val openedTwo = harness.events.filterIsInstance<UiEvent.OpenFile>().single { it.path.endsWith("two.txt") }
        assertEquals("two-secret", File(openedTwo.path).readText())
    }

    private fun buildMultiPasswordAesZip(vararg entries: Pair<String, Pair<ByteArray, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
        val extra = ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2)
        entries.forEachIndexed { idx, (name, pair) ->
            val (plain, pw) = pair
            val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            def.setInput(plain)
            def.finish()
            val compOut = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n > 0) compOut.write(buf, 0, n)
            }
            def.end()
            val ct = compOut.toByteArray()
            val salt = ByteArray(16) { ((idx + 1) * 17 + it).toByte() }
            val derived = ZipCrypto.pbkdf2HmacSha1(pw.toByteArray(Charsets.UTF_8), salt, 1000, 66)
            WinZipAesCtrCipher(derived.copyOfRange(0, 32)).processInPlace(ct, 0, ct.size)
            val tag = Mac.getInstance("HmacSHA1")
                .apply { init(SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA1")) }
                .doFinal(ct)
                .copyOf(10)
            val payload = salt + derived.copyOfRange(64, 66) + ct + tag
            val offset = out.size().toLong()
            val nameBytes = name.toByteArray(Charsets.UTF_8)

            out.write(u32(0x04034B50)); out.write(u16(51)); out.write(u16(0x0801)); out.write(u16(99))
            out.write(u16(0x4000)); out.write(u16(0x5A21)); out.write(u32(0L)); out.write(u32(payload.size.toLong()))
            out.write(u32(plain.size.toLong())); out.write(u16(nameBytes.size)); out.write(u16(extra.size))
            out.write(nameBytes); out.write(extra); out.write(payload)

            cd.write(u32(0x02014B50)); cd.write(u16((3 shl 8) or 51)); cd.write(u16(51))
            cd.write(u16(0x0801)); cd.write(u16(99)); cd.write(u16(0x4000)); cd.write(u16(0x5A21))
            cd.write(u32(0L)); cd.write(u32(payload.size.toLong())); cd.write(u32(plain.size.toLong()))
            cd.write(u16(nameBytes.size)); cd.write(u16(extra.size)); cd.write(u16(0))
            cd.write(u16(0)); cd.write(u16(0)); cd.write(u32(0x81A40000L)); cd.write(u32(offset))
            cd.write(nameBytes); cd.write(extra)
        }
        val cdOffset = out.size().toLong()
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)
        out.write(u32(0x06054B50)); out.write(u16(0)); out.write(u16(0))
        out.write(u16(entries.size)); out.write(u16(entries.size))
        out.write(u32(cdBytes.size.toLong())); out.write(u32(cdOffset)); out.write(u16(0))
        return out.toByteArray()
    }
}
