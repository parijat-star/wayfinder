// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.PickerMode
import com.droid.wayfinder.data.SystemDocumentsBridge
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.findActivity
import com.droid.wayfinder.ui.resolveReturnUri
import com.droid.wayfinder.ui.returnCreatedDocument
import com.droid.wayfinder.ui.returnPicked
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PickerUriAndExternalVolumeTest {
    private val harnesses = mutableListOf<VmHarness>()
    private val tempDirs = mutableListOf<File>()

    private fun harness(extras: Map<String, Any?> = emptyMap()) = VmHarness(extras).also { harnesses += it }

    @org.junit.Before
    fun resetFileProviderCache() {
        // androidx.core.content.FileProvider caches per-test temp root paths in static sCache across Robolectric tests.
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
        tempDirs.forEach { FileInfo.deleteTree(it) }
    }

    @Test
    fun resolveReturnUriFallsBackToFileProviderWithoutSelfGrantAndOmitsPersistableFlag() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadDir = File(Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        val localFile = File(downloadDir, "picker-3p.pdf").apply { writeText("pdf-data") }

        try {
            // Standalone 3P app without MANAGE_DOCUMENTS does not hold URI grant on ExternalStorageProvider:
            // resolveReturnUri must fall back to FileProvider instead of returning an ungrantable docUri.
            val (fallbackUri, isDocUri) = resolveReturnUri(ctx, localFile.path)
            assertFalse(isDocUri)
            assertEquals("${ctx.packageName}.fileprovider", fallbackUri.authority)

            // Activity result via ContextWrapper must omit FLAG_GRANT_PERSISTABLE_URI_PERMISSION on FileProvider URIs
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val wrapped = ContextWrapper(ContextWrapper(activity))
            assertSame(activity, wrapped.findActivity())

            returnPicked(wrapped, listOf(localFile.path))
            val sa = shadowOf(activity)
            assertEquals(Activity.RESULT_OK, sa.resultCode)
            val resultIntent = sa.resultIntent
            assertNotNull(resultIntent)
            val flags = resultIntent.flags
            assertTrue((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertTrue((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            assertEquals(
                "FileProvider URIs must not include FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
                0,
                flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

            // Same guarantee for ACTION_CREATE_DOCUMENT on local storage without self-grant
            val createActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
            returnCreatedDocument(ContextWrapper(createActivity), localFile.path, null, "application/pdf")
            val createShadow = shadowOf(createActivity)
            assertEquals(Activity.RESULT_OK, createShadow.resultCode)
            assertEquals(
                0,
                createShadow.resultIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

            // When Wayfinder actively holds a read URI grant on a DocumentsContract URI, return it with persistable grant
            val grantedDocUri = DocumentsContract.buildDocumentUri("com.example.cloud.documents", "doc-42")
            ctx.grantUriPermission(
                ctx.packageName,
                grantedDocUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val (docUri, resolvedAsDoc) = resolveReturnUri(ctx, localFile.path, preferredUri = grantedDocUri)
            assertTrue(resolvedAsDoc)
            assertEquals(grantedDocUri, docUri)

            val docActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
            returnCreatedDocument(docActivity, localFile.path, grantedDocUri, "application/pdf")
            assertTrue(
                (shadowOf(docActivity).resultIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0,
            )
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun openShareAndPickStageLocalVolumesOutsideFileProviderRootsIntoCache() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val usbRoot = Files.createTempDirectory("wayfinder-usb-vol-").toFile().also { tempDirs += it }
        val usbFile = File(usbRoot, "camera-roll.jpg").apply { writeText("jpeg-bytes") }

        val h = harness(
            mapOf(
                BrowserViewModel.EXTRA_PICKER to true,
                BrowserViewModel.EXTRA_PICKER_MODE to PickerMode.OpenFile.name,
            ),
        )
        assertFalse(
            "External USB/Linux directory outside external-path should not be directly servable",
            h.vm.isDirectlyServableByFileProvider(usbFile.path),
        )

        h.navigate(usbRoot.canonicalPath)
        h.vm.setSelection(setOf(usbFile.canonicalPath))

        // 1. Open with... stages into cacheDir/remote-open so FileProvider can serve a URI
        h.vm.openWith()
        h.until(what = "OpenFile staged") { h.events.any { it is UiEvent.OpenFile } }
        val openedPath = h.events.filterIsInstance<UiEvent.OpenFile>().last().path
        assertTrue(openedPath.startsWith(ctx.cacheDir.canonicalPath))
        assertEquals("jpeg-bytes", File(openedPath).readText())
        assertNotNull(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(openedPath)))

        // 2. Share stages into cacheDir/remote-open/share-* so FileProvider can serve a URI
        h.vm.share()
        h.until(what = "Share staged") { h.events.any { it is UiEvent.Share } }
        val sharedPath = h.events.filterIsInstance<UiEvent.Share>().last().paths.single()
        assertTrue(sharedPath.startsWith(ctx.cacheDir.canonicalPath))
        assertEquals("jpeg-bytes", File(sharedPath).readText())
        assertNotNull(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(sharedPath)))

        // 3. Picker stages into cacheDir/picker/* so returnPicked succeeds cleanly
        h.vm.pickSelection()
        h.until(what = "PickResult staged") { h.events.any { it is UiEvent.PickResult } }
        val pickedPath = h.events.filterIsInstance<UiEvent.PickResult>().last().paths.single()
        assertTrue(pickedPath.startsWith(ctx.cacheDir.canonicalPath))
        assertEquals("jpeg-bytes", File(pickedPath).readText())
        assertNotNull(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(pickedPath)))
    }
}
