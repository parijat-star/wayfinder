// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.buildOpenWithSystemIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenLocalMediaStoreUriTest {
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
    fun indexedLocalImageOpensWithMediaStoreUriAndRespectsFallbackAndCacheExclusion() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val galleryDir = File(Environment.getExternalStorageDirectory(), "Pictures/WayfinderMediaStoreTest").apply {
            mkdirs()
            tempDirs += this
        }
        val indexedByData = File(galleryDir, "img1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val indexedByRel = File(galleryDir, "img2.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val unindexed = File(galleryDir, "unindexed.webp").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val stagedDir = File(ctx.cacheDir, "remote-open/media-test").apply {
            mkdirs()
            tempDirs += this
        }
        val stagedImage = File(stagedDir, "cached.jpg").apply { writeBytes(byteArrayOf(10, 11)) }

        val provider = Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, MediaStore.AUTHORITY)
        provider.seed(
            listOf(
                MediaRow(
                    id = 101L,
                    data = indexedByData.canonicalPath,
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    relativePath = "Pictures/WayfinderMediaStoreTest/",
                    displayName = indexedByData.name,
                    mimeType = "image/jpeg",
                ),
                MediaRow(
                    id = 202L,
                    data = "/alias/path/img2.png",
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    relativePath = "Pictures/WayfinderMediaStoreTest/",
                    displayName = indexedByRel.name,
                    mimeType = "image/png",
                ),
                MediaRow(
                    id = 303L,
                    data = stagedImage.canonicalPath,
                    volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    relativePath = "cache/",
                    displayName = stagedImage.name,
                    mimeType = "image/jpeg",
                ),
            ),
        )

        // 1. Direct _data lookup resolves to content://media/external/images/media/101
        val expectedUri1 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 101L)
        val intent1 = buildOpenWithSystemIntent(ctx, indexedByData.path)!!
        assertEquals(Intent.ACTION_VIEW, intent1.action)
        assertEquals(expectedUri1, intent1.data)
        assertEquals("image/jpeg", intent1.type)
        assertTrue((intent1.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(expectedUri1, intent1.clipData!!.getItemAt(0).uri)

        // 2. RELATIVE_PATH + DISPLAY_NAME fallback resolves to content://media/external/images/media/202
        val expectedUri2 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 202L)
        val intent2 = buildOpenWithSystemIntent(ctx, indexedByRel.path)!!
        assertEquals(expectedUri2, intent2.data)
        assertEquals("image/png", intent2.type)

        // 3. allowMediaStore = false forces WayfinderFileProvider fallback
        val fpFallback = buildOpenWithSystemIntent(ctx, indexedByData.path, allowMediaStore = false)!!
        assertEquals("${ctx.packageName}.fileprovider", fpFallback.data!!.authority)

        // 4. Unindexed image falls back to WayfinderFileProvider
        val unindexedIntent = buildOpenWithSystemIntent(ctx, unindexed.path)!!
        assertEquals("${ctx.packageName}.fileprovider", unindexedIntent.data!!.authority)

        // 5. Staged cache copy never resolves to MediaStore and stays read-only via FileProvider
        val stagedIntent = buildOpenWithSystemIntent(ctx, stagedImage.path)!!
        assertEquals("${ctx.packageName}.fileprovider", stagedIntent.data!!.authority)
        assertEquals(0, stagedIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        // 6. BrowserViewModel.open emits the resolved MediaStore URI asynchronously on IO
        val h = harness()
        h.navigate(galleryDir.path)
        h.events.clear()
        val entry = h.s.active.entries.first { it.name == indexedByData.name }
        h.vm.open(entry)
        h.until(what = "local media OpenFile") { h.events.any { it is UiEvent.OpenFile } }
        val openEvent = h.events.filterIsInstance<UiEvent.OpenFile>().single()
        assertEquals(expectedUri1, openEvent.uri)
        val fromEvent = buildOpenWithSystemIntent(ctx, openEvent.path, openEvent.uri)!!
        assertEquals(expectedUri1, fromEvent.data)
        assertNotNull(fromEvent.clipData)
    }

    data class MediaRow(
        val id: Long,
        val data: String,
        val volumeName: String,
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
    )

    class FakeMediaStoreProvider : ContentProvider() {
        private var rows: List<MediaRow> = emptyList()

        fun seed(rows: List<MediaRow>) {
            this.rows = rows
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE)
            val cursor = MatrixCursor(cols)
            if (uri != MediaStore.Images.Media.EXTERNAL_CONTENT_URI) return cursor
            val args = selectionArgs.orEmpty().toList()
            val match = when {
                selection != null && selection.contains(MediaStore.MediaColumns.DATA) ->
                    rows.firstOrNull { it.data in args }
                selection != null && selection.contains(MediaStore.MediaColumns.RELATIVE_PATH) && args.size >= 3 ->
                    rows.firstOrNull {
                        it.volumeName == args[0] &&
                            it.relativePath == args[1] &&
                            it.displayName == args.last()
                    }
                else -> null
            }
            if (match != null) {
                val values: Array<Any?> = Array(cols.size) { i ->
                    when (cols[i]) {
                        MediaStore.MediaColumns._ID -> match.id
                        MediaStore.MediaColumns.MIME_TYPE -> match.mimeType
                        else -> null
                    }
                }
                cursor.addRow(values)
            }
            return cursor
        }

        override fun getType(uri: Uri): String? {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
            return rows.firstOrNull { it.id == id }?.mimeType
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
