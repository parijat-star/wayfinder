// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.buildOpenWithSystemIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenFileWriteGrantTest {
    private val harnesses = mutableListOf<VmHarness>()
    private val tempDirs = mutableListOf<File>()

    private fun harness() = VmHarness().also { harnesses += it }

    @Before
    fun resetFileProviderCache() {
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
    fun writableLocalFileGrantsReadAndWriteFlagsAndExposesFlagSupportsWrite() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val docsDir = File(Environment.getExternalStorageDirectory(), "Documents").apply { mkdirs() }
        val docxFile = File(docsDir, "contract.docx").apply { writeText("original-docx") }

        try {
            val intent = buildOpenWithSystemIntent(ctx, docxFile.path)
            assertNotNull(intent)
            intent!!
            assertEquals(Intent.ACTION_VIEW, intent.action)
            val uri = intent.data
            assertNotNull(uri)
            uri!!
            assertEquals("${ctx.packageName}.fileprovider", uri.authority)
            assertTrue("Expected READ grant", (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertTrue("Expected WRITE grant", (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            assertNotNull("Expected ClipData for chooser grant propagation", intent.clipData)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)

            // Chooser must retain both READ and WRITE URI permission flags for selected editors
            val chooser = Intent.createChooser(intent, "Open with")
            assertTrue((chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertTrue((chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            assertNotNull(chooser.clipData)

            // WayfinderFileProvider must expose COLUMN_FLAGS with FLAG_SUPPORTS_WRITE for Office/PDF editors
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_LAST_MODIFIED,
                    Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            ).use { cursor ->
                assertNotNull(cursor)
                cursor!!
                assertTrue(cursor.moveToFirst())
                assertEquals("contract.docx", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertEquals("original-docx".length.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
                val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
                assertTrue(
                    "Expected FLAG_SUPPORTS_WRITE on writable local file",
                    (flags and Document.FLAG_SUPPORTS_WRITE) != 0,
                )
            }

            // Overwriting through the granted content:// URI updates the original file in place
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                out.write("edited-in-place".toByteArray(Charsets.UTF_8))
            }
            assertEquals("edited-in-place", docxFile.readText())
        } finally {
            docxFile.delete()
        }
    }

    @Test
    fun readOnlyAndStagedCacheFilesGrantReadOnlyAndOmitWriteFlag() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val docsDir = File(Environment.getExternalStorageDirectory(), "Documents").apply { mkdirs() }
        val readOnlyPdf = File(docsDir, "locked.pdf").apply {
            writeText("readonly-pdf")
            setWritable(false)
        }
        val zipCacheDir = File(ctx.cacheDir, "zip-open/test-entry").apply { mkdirs() }
        val stagedZipFile = File(zipCacheDir, "inside-archive.pdf").apply { writeText("from-zip") }
        val remoteCacheDir = File(ctx.cacheDir, "remote-open/test-entry").apply { mkdirs() }
        val stagedRemoteFile = File(remoteCacheDir, "from-smb.docx").apply { writeText("from-smb") }

        try {
            if (!readOnlyPdf.canWrite()) {
                val roIntent = buildOpenWithSystemIntent(ctx, readOnlyPdf.path)!!
                assertTrue((roIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                assertEquals(
                    "Read-only file on disk must not advertise write URI grant",
                    0,
                    roIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                ctx.contentResolver.query(roIntent.data!!, arrayOf(Document.COLUMN_FLAGS), null, null, null)!!.use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0) and Document.FLAG_SUPPORTS_WRITE)
                }
            }

            for (staged in listOf(stagedZipFile, stagedRemoteFile)) {
                val stagedIntent = buildOpenWithSystemIntent(ctx, staged.path)!!
                assertTrue((stagedIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                assertEquals(
                    "Staged cache copy (${staged.path}) must never grant write permission",
                    0,
                    stagedIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                val uri = stagedIntent.data!!
                ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)!!.use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0) and Document.FLAG_SUPPORTS_WRITE)
                }
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.close()
                    fail("Expected SecurityException writing to staged cache copy $uri")
                } catch (_: SecurityException) {
                    // Expected: staged copies in zip-open / remote-open are strictly read-only.
                }
            }
        } finally {
            readOnlyPdf.setWritable(true)
            readOnlyPdf.delete()
            FileInfo.deleteTree(zipCacheDir)
            FileInfo.deleteTree(remoteCacheDir)
        }
    }

    @Test
    fun safMountedDocumentsOpenLiveDocumentUriInPlaceInsteadOfCacheCopy() {
        val backingRoot = Files.createTempDirectory("wf-saf-open-").toFile().also { tempDirs += it }
        val editableBacking = File(backingRoot, "notes.docx").apply { writeText("cloud-v1") }
        val lockedBacking = File(backingRoot, "statement.pdf").apply { writeText("bank-pdf") }

        val authority = "com.example.cloud.open44"
        val provider = Robolectric.setupContentProvider(OpaqueIdSafProvider::class.java, authority)
        provider.seed(
            rootId = "root-opaque",
            entries = listOf(
                OpaqueEntry("doc-opaque-101", "notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Document.FLAG_SUPPORTS_WRITE, editableBacking),
                OpaqueEntry("doc-opaque-202", "statement.pdf", "application/pdf", 0, lockedBacking),
            ),
        )

        val app = ApplicationProvider.getApplicationContext<Context>()
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "root-opaque")
        app.grantUriPermission(
            app.packageName,
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        app.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        val h = harness()
        h.vm.mountSaf(treeUri)
        h.settle()

        val mountPath = h.s.mounts.single { it.saf }.path
        h.navigate(mountPath)
        assertTrue(h.names().containsAll(listOf("notes.docx", "statement.pdf")))

        // 1. Editable SAF document opens its live DocumentsContract URI directly with READ | WRITE
        h.events.clear()
        val editableEntry = h.s.active.entries.first { it.name == "notes.docx" }
        h.vm.open(editableEntry)
        h.until(what = "direct SAF OpenFile") { h.events.any { it is UiEvent.OpenFile } }

        val openEditable = h.events.filterIsInstance<UiEvent.OpenFile>().single()
        assertFalse(
            "Mounted SAF document must open in place rather than staging into cacheDir",
            openEditable.path.startsWith(h.app.cacheDir.path),
        )
        assertNotNull("Expected resolved DocumentsContract URI", openEditable.uri)
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-opaque-101"),
            openEditable.uri,
        )

        val editableIntent = buildOpenWithSystemIntent(h.app, openEditable.path, openEditable.uri)!!
        assertEquals(openEditable.uri, editableIntent.data)
        assertTrue((editableIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue(
            "Writable SAF document must include FLAG_GRANT_WRITE_URI_PERMISSION",
            (editableIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
        )
        h.app.contentResolver.openOutputStream(editableIntent.data!!, "wt")!!.use { out ->
            out.write("cloud-v2-saved".toByteArray(Charsets.UTF_8))
        }
        assertEquals("cloud-v2-saved", editableBacking.readText())

        // 2. Provider document lacking FLAG_SUPPORTS_WRITE opens directly with READ-only flags
        h.events.clear()
        val lockedEntry = h.s.active.entries.first { it.name == "statement.pdf" }
        h.vm.open(lockedEntry)
        h.until(what = "locked SAF OpenFile") { h.events.any { it is UiEvent.OpenFile } }

        val openLocked = h.events.filterIsInstance<UiEvent.OpenFile>().single()
        val lockedIntent = buildOpenWithSystemIntent(h.app, openLocked.path, openLocked.uri)!!
        assertTrue((lockedIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(
            "Provider document without FLAG_SUPPORTS_WRITE must omit write grant",
            0,
            lockedIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    data class OpaqueEntry(
        val id: String,
        val displayName: String,
        val mimeType: String,
        val flags: Int,
        val file: File,
    )

    class OpaqueIdSafProvider : ContentProvider() {
        private var rootId: String = "root-opaque"
        private var byId: Map<String, OpaqueEntry> = emptyMap()

        fun seed(rootId: String, entries: List<OpaqueEntry>) {
            this.rootId = rootId
            this.byId = entries.associateBy { it.id }
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )
            val cursor = MatrixCursor(cols)
            if (uri.pathSegments.lastOrNull() == "children") {
                val parentId = DocumentsContract.getDocumentId(uri)
                if (parentId == rootId) {
                    for (e in byId.values) {
                        cursor.addRow(arrayOf<Any?>(e.id, e.displayName, e.mimeType, e.file.length(), e.file.lastModified(), e.flags))
                    }
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                if (docId == rootId) {
                    cursor.addRow(arrayOf<Any?>(rootId, "Cloud Root", Document.MIME_TYPE_DIR, 0L, 0L, Document.FLAG_DIR_SUPPORTS_CREATE))
                } else {
                    byId[docId]?.let { e ->
                        cursor.addRow(arrayOf<Any?>(e.id, e.displayName, e.mimeType, e.file.length(), e.file.lastModified(), e.flags))
                    }
                }
            }
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val docId = DocumentsContract.getDocumentId(uri)
            val entry = byId[docId] ?: throw FileNotFoundException(docId)
            val pfdMode = if (mode.contains('w')) {
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(entry.file, pfdMode)
        }

        override fun getType(uri: Uri): String? {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            return byId[docId]?.mimeType
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
