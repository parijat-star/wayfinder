// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * [FileProvider] subclass that exposes [DocumentsContract.Document] metadata columns
 * (`COLUMN_FLAGS` with [DocumentsContract.Document.FLAG_SUPPORTS_WRITE], `COLUMN_MIME_TYPE`,
 * and `COLUMN_LAST_MODIFIED`) alongside standard [OpenableColumns] (`_display_name`, `_size`).
 *
 * Third-party document editors (such as Microsoft Word and PDF editors) inspect both
 * [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] and [DocumentsContract.Document.COLUMN_FLAGS]
 * before enabling in-place saving; standard [FileProvider.query] drops `COLUMN_FLAGS`,
 * causing those editors to fall back to Read-Only mode.
 *
 * Temporary staged files under `cacheDir` (`remote-open/`, `zip-open/`, `picker/`) never
 * advertise [DocumentsContract.Document.FLAG_SUPPORTS_WRITE] because modifications to staged
 * cache copies do not propagate back to the originating archive or remote server.
 */
class WayfinderFileProvider : FileProvider() {

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // Delegate path validation and OpenableColumns resolution to AndroidX FileProvider first.
        val base = super.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            selection,
            selectionArgs,
            sortOrder,
        )

        var displayName = uri.lastPathSegment.orEmpty()
        var size = 0L
        base.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }

        val backingFile = resolveConfiguredFile(uri)
        val rootSegment = uri.pathSegments.firstOrNull()
        val isStagedCacheCopy = rootSegment in STAGED_CACHE_SEGMENTS
        val callerHasWriteGrant = if (Binder.getCallingUid() == Process.myUid()) {
            true
        } else {
            context?.checkCallingUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }
        val writable = !isStagedCacheCopy && callerHasWriteGrant && (backingFile?.canWrite() == true)
        val docFlags = if (writable) DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0

        val requested = projection ?: DEFAULT_COLUMNS
        val cols = ArrayList<String>(requested.size)
        val vals = ArrayList<Any?>(requested.size)
        for (col in requested) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> {
                    cols += OpenableColumns.DISPLAY_NAME
                    vals += displayName
                }
                OpenableColumns.SIZE -> {
                    cols += OpenableColumns.SIZE
                    vals += size
                }
                DocumentsContract.Document.COLUMN_MIME_TYPE -> {
                    cols += DocumentsContract.Document.COLUMN_MIME_TYPE
                    vals += (getType(uri) ?: "application/octet-stream")
                }
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> {
                    cols += DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    vals += (backingFile?.lastModified() ?: 0L)
                }
                DocumentsContract.Document.COLUMN_FLAGS -> {
                    cols += DocumentsContract.Document.COLUMN_FLAGS
                    vals += docFlags
                }
            }
        }
        return MatrixCursor(cols.toTypedArray(), 1).apply {
            addRow(vals.toTypedArray())
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" && uri.pathSegments.firstOrNull() in READ_ONLY_OPEN_SEGMENTS) {
            throw SecurityException("Temporary staged files are read-only")
        }
        return super.openFile(uri, mode)
    }

    private fun resolveConfiguredFile(uri: Uri): File? {
        val ctx = context ?: return null
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val rootName = segs[0]
        val relPath = segs.drop(1).joinToString(File.separator)
        val baseDir = when (rootName) {
            "external" -> Environment.getExternalStorageDirectory()
            "external_files" -> ctx.getExternalFilesDir(null)
            "remote" -> File(ctx.cacheDir, "remote-open")
            "zip" -> File(ctx.cacheDir, "zip-open")
            "picker" -> File(ctx.cacheDir, "picker")
            else -> null
        } ?: return null
        return runCatching { File(baseDir, relPath).canonicalFile }.getOrNull()
    }

    companion object {
        private val STAGED_CACHE_SEGMENTS = setOf("remote", "zip", "picker")
        private val READ_ONLY_OPEN_SEGMENTS = setOf("remote", "zip")
        private val DEFAULT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
