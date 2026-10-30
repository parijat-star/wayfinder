// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.data.DocFileSystem
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.PickerMode
import com.droid.wayfinder.data.SystemDocumentsBridge
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.UiEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/**
 * Self-contained tests for Phase 2 DocumentsContract & SAF platform integration:
 *  - Bidirectional DocumentsContract URI <-> Wayfinder path resolution (file, tree, doc://, saf://)
 *  - Opaque documentId preservation in SystemDocumentsBridge.splitParentAndChild & DocFileSystem
 *  - ACTION_OPEN_DOCUMENT_TREE (PickerMode.OpenTree) folder selection & UiEvent.PickTreeResult
 *  - ACTION_CREATE_DOCUMENT (PickerMode.CreateDocument) file creation & UiEvent.CreateDocumentResult
 *  - IntentGuard sanitization of EXTRA_PICKER_MODE and EXTRA_PICKER_TITLE
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DocumentsContractPickerTest {
    private val harnesses = mutableListOf<VmHarness>()
    private fun harness(extras: Map<String, Any?> = emptyMap()) = VmHarness(extras).also { harnesses += it }

    @After fun tearDown() { harnesses.forEach { it.close() } }

    @Test fun documentsContractUriRoundTripForPrimarySecondaryAndDocPaths() {
        val primaryRoot = Environment.getExternalStorageDirectory().canonicalPath

        // 1. Primary storage file -> single Document URI -> back to Wayfinder path
        val fileUri = SystemDocumentsBridge.toDocumentsContractUri("$primaryRoot/Download/report.pdf", tree = false, primaryStorageRoot = primaryRoot)
        assertNotNull(fileUri)
        assertEquals(SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY, fileUri!!.authority)
        assertEquals("primary:Download/report.pdf", DocumentsContract.getDocumentId(fileUri))
        assertEquals("$primaryRoot/Download/report.pdf", SystemDocumentsBridge.toWayfinderPath(fileUri, primaryStorageRoot = primaryRoot))

        // 2. Primary storage folder -> Tree Document URI -> back to Wayfinder path
        val treeUri = SystemDocumentsBridge.toDocumentsContractUri("$primaryRoot/Documents/Projects", tree = true, primaryStorageRoot = primaryRoot)
        assertNotNull(treeUri)
        assertTrue(DocumentsContract.isTreeUri(treeUri!!))
        assertEquals("primary:Documents/Projects", DocumentsContract.getTreeDocumentId(treeUri))
        assertEquals("$primaryRoot/Documents/Projects", SystemDocumentsBridge.toWayfinderPath(treeUri, primaryStorageRoot = primaryRoot))

        // 3. Secondary volume (/storage/ABCD-1234/DCIM/Camera) -> ExternalStorageProvider URI -> back to Wayfinder path
        val sdUri = SystemDocumentsBridge.toDocumentsContractUri("/storage/ABCD-1234/DCIM/Camera", tree = true, primaryStorageRoot = primaryRoot)
        assertNotNull(sdUri)
        assertEquals("ABCD-1234:DCIM/Camera", DocumentsContract.getTreeDocumentId(sdUri!!))
        assertEquals("/storage/ABCD-1234/DCIM/Camera", SystemDocumentsBridge.toWayfinderPath(sdUri, primaryStorageRoot = primaryRoot))

        // 4. doc:// opaque provider path -> DocumentsContract URI -> back to doc:// path
        val docPath = "doc://com.google.android.apps.docs.storage/opaque_folder_99"
        val docTreeUri = SystemDocumentsBridge.toDocumentsContractUri(docPath, tree = true, primaryStorageRoot = primaryRoot)
        assertNotNull(docTreeUri)
        assertEquals("com.google.android.apps.docs.storage", docTreeUri!!.authority)
        assertEquals("opaque_folder_99", DocumentsContract.getTreeDocumentId(docTreeUri))
        assertEquals(
            "doc://com.google.android.apps.docs.storage/opaque_folder_99",
            SystemDocumentsBridge.toWayfinderPath(docTreeUri, primaryStorageRoot = primaryRoot),
        )
    }

    @Test fun splitParentAndChildHandlesSlashSeparatedAndOpaqueDocumentIds() {
        // Slash-separated ExternalStorageProvider documentIds split into authority, parentDocId, and child name
        val extSplit = SystemDocumentsBridge.splitParentAndChild(
            "doc://com.android.externalstorage.documents/primary:Download/notes.txt",
        )
        assertNotNull(extSplit)
        assertEquals(Triple("com.android.externalstorage.documents", "primary:Download", "notes.txt"), extSplit)

        // Opaque provider documentId with an appended child name splits cleanly into the opaque parent ID
        val opaqueSplit = SystemDocumentsBridge.splitParentAndChild(
            "doc://com.android.mtp.documents/opaque_doc_42/new_folder",
        )
        assertNotNull(opaqueSplit)
        assertEquals(Triple("com.android.mtp.documents", "opaque_doc_42", "new_folder"), opaqueSplit)
    }

    @Test fun docFileSystemMkdirAndCreateFilePreserveOpaqueParentDocumentId() {
        val capturedCalls = mutableListOf<Triple<String?, String?, String?>>()
        val authority = "com.android.mtp.documents.test"
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor = MatrixCursor(emptyArray())
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
                if (method == "android:createDocument") {
                    val docUri = extras?.getParcelable("uri", Uri::class.java)
                    val parentDocId = docUri?.let { DocumentsContract.getDocumentId(it) }
                    val mime = extras?.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val name = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    capturedCalls += Triple(parentDocId, mime, name)
                    return Bundle().apply {
                        putParcelable(
                            "uri",
                            DocumentsContract.buildDocumentUri(authority, "created_${name}"),
                        )
                    }
                }
                return null
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        provider.attachInfo(ctx, android.content.pm.ProviderInfo().apply { this.authority = authority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)

        val docFs = DocFileSystem(ctx)
        val opaqueFolder = "doc://$authority/opaque_parent_77"

        docFs.mkdir("$opaqueFolder/Subfolder")
        val createdFilePath = docFs.createFileWithMime("$opaqueFolder/report.csv", "text/csv")

        assertEquals(2, capturedCalls.size)
        assertEquals(Triple("opaque_parent_77", DocumentsContract.Document.MIME_TYPE_DIR, "Subfolder"), capturedCalls[0])
        assertEquals(Triple("opaque_parent_77", "text/csv", "report.csv"), capturedCalls[1])
        assertEquals("doc://$authority/created_report.csv", createdFilePath)
    }

    @Test fun openDocumentTreePickerSelectsCurrentOrHighlightedSubfolder() {
        val h = harness(
            mapOf(
                BrowserViewModel.EXTRA_PICKER to true,
                BrowserViewModel.EXTRA_PICKER_MODE to PickerMode.OpenTree.name,
            ),
        )
        assertEquals(PickerMode.OpenTree, h.s.picker?.mode)
        h.dir("projects/alpha"); h.file("projects/readme.md")
        h.navigate(h.pathOf("projects"))

        // With no folder selected, targetTreeFolder defaults to the active folder ("projects")
        assertTrue(h.vm.canPickFolder())
        assertEquals(h.pathOf("projects"), h.vm.targetTreeFolder())

        // Selecting a single subfolder targets that subfolder ("projects/alpha")
        h.select("projects/alpha")
        assertEquals(h.pathOf("projects/alpha"), h.vm.targetTreeFolder())
        h.vm.pickCurrentFolder()
        assertEquals(
            h.pathOf("projects/alpha"),
            h.events.filterIsInstance<UiEvent.PickTreeResult>().last().path,
        )

        // Virtual locations (e.g. Recents) cannot be granted as document trees
        h.navigate(FsPath.RECENTS_ROOT)
        assertFalse(h.vm.canPickFolder())
    }

    @Test fun createDocumentPickerCreatesFileWithSuggestedNameAndEmitsResult() {
        val h = harness(
            mapOf(
                BrowserViewModel.EXTRA_PICKER to true,
                BrowserViewModel.EXTRA_PICKER_MODE to PickerMode.CreateDocument.name,
                BrowserViewModel.EXTRA_PICKER_TITLE to "export.json",
                BrowserViewModel.EXTRA_PICKER_MIME to arrayOf("application/json"),
            ),
        )
        assertEquals(PickerMode.CreateDocument, h.s.picker?.mode)
        assertEquals("export.json", h.s.createFileName)

        h.dir("exports")
        h.navigate(h.pathOf("exports"))

        // Rejects path separators in file name
        h.vm.setCreateFileName("../escape.json")
        h.vm.confirmCreateDocument(); h.settle()
        assertTrue(h.toasts().last().isNotBlank())
        assertTrue(h.events.filterIsInstance<UiEvent.CreateDocumentResult>().isEmpty())

        // Valid file name creates the file on disk and emits CreateDocumentResult
        h.vm.setCreateFileName("summary.json")
        h.vm.confirmCreateDocument()
        h.until(what = "CreateDocumentResult") { h.events.any { it is UiEvent.CreateDocumentResult } }
        val result = h.events.filterIsInstance<UiEvent.CreateDocumentResult>().single()
        assertEquals(h.pathOf("exports/summary.json"), result.path)
        assertTrue(File(result.path).exists())
    }

    @Test fun intentGuardStripsExternalPickerModeAndTitleExtras() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val externalIntent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(BrowserViewModel.EXTRA_PICKER, true)
            putExtra(BrowserViewModel.EXTRA_PICKER_MODE, PickerMode.CreateDocument.name)
            putExtra(BrowserViewModel.EXTRA_PICKER_TITLE, "spoofed.sh")
        }
        val clean = IntentGuard.sanitize(ctx, externalIntent)
        assertFalse(clean.hasExtra(BrowserViewModel.EXTRA_PICKER))
        assertFalse(clean.hasExtra(BrowserViewModel.EXTRA_PICKER_MODE))
        assertFalse(clean.hasExtra(BrowserViewModel.EXTRA_PICKER_TITLE))
    }

    @Test fun buildSystemDocumentTreePickerIntentTargetsSystemDocumentsUiInsteadOfSelf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initialUri = DocumentsContract.buildRootsUri("com.google.android.apps.docs.storage")
        val spm = org.robolectric.Shadows.shadowOf(ctx.packageManager)

        val selfRi = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = ctx.packageName
                name = "com.droid.wayfinder.MainActivity"
            }
        }
        val sysRi = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.google.android.documentsui"
                name = "com.android.documentsui.picker.PickActivity"
            }
        }
        val baseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        @Suppress("DEPRECATION")
        spm.addResolveInfoForIntent(baseIntent, selfRi)
        @Suppress("DEPRECATION")
        spm.addResolveInfoForIntent(baseIntent, sysRi)

        val targeted = com.droid.wayfinder.ui.buildSystemDocumentTreePickerIntent(ctx, initialUri)
        assertNotNull(targeted)
        assertEquals("com.google.android.documentsui", targeted!!.component?.packageName)
        assertEquals("com.android.documentsui.picker.PickActivity", targeted.component?.className)
        @Suppress("DEPRECATION")
        assertEquals(initialUri, targeted.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }
}
