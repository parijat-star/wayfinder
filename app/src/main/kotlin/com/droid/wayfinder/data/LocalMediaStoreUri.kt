// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Resolves local shared-storage media files to their indexed [MediaStore] content URIs
 * (`content://media/external/<collection>/media/<id>`).
 *
 * External image/media viewers (such as Android Desktop Viewer, Google Photos, and Gallery apps)
 * treat third-party `FileProvider` URIs as isolated single-file attachments with no folder context,
 * disabling arrow-key / swipe navigation between images in the same folder. Passing the indexed
 * [MediaStore] URI lets the viewer resolve the image's containing folder (`BUCKET_ID` /
 * `RELATIVE_PATH`) just like Android's system Files app (`DocumentsUI`).
 */
object LocalMediaStoreUri {

    enum class MediaCollection { Images, Video, Audio }

    data class RelativeMediaLocation(
        val relativePath: String,
        val displayName: String,
    )

    data class ResolvedMediaUri(
        val uri: Uri,
        val mimeType: String? = null,
    )

    /** Returns the [MediaStore] collection for [fileName] / [mimeType], or `null` for non-media files. */
    fun collectionFor(fileName: String, mimeType: String? = null): MediaCollection? {
        val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
        when {
            normalizedMime.startsWith("image/") -> return MediaCollection.Images
            normalizedMime.startsWith("video/") -> return MediaCollection.Video
            normalizedMime.startsWith("audio/") -> return MediaCollection.Audio
        }
        return when (FileKind.fromName(fileName)) {
            FileKind.Image -> MediaCollection.Images
            FileKind.Video -> MediaCollection.Video
            FileKind.Audio -> MediaCollection.Audio
            else -> null
        }
    }

    /** Returns distinct non-blank path candidates for `_data` matching. */
    fun buildDataCandidates(absolutePath: String, canonicalPath: String?): List<String> =
        listOfNotNull(
            absolutePath.trim().takeIf { it.isNotEmpty() },
            canonicalPath?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()

    /**
     * Computes the `MediaStore.MediaColumns.RELATIVE_PATH` and `DISPLAY_NAME` for [filePath] inside
     * [externalStorageRoot], or `null` if [filePath] lies outside [externalStorageRoot] or contains
     * traversal segments (`.` / `..`).
     */
    fun relativeLocationInPrimaryStorage(
        filePath: String,
        externalStorageRoot: String,
    ): RelativeMediaLocation? {
        val root = externalStorageRoot.trimEnd('/')
        val cleanPath = filePath.trimEnd('/')
        if (root.isEmpty() || cleanPath.isEmpty()) return null
        val prefix = "$root/"
        if (!cleanPath.startsWith(prefix)) return null
        val rel = cleanPath.removePrefix(prefix)
        if (rel.isEmpty()) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val displayName = segments.last()
        val relativePath = if (segments.size == 1) {
            "/"
        } else {
            segments.dropLast(1).joinToString("/") + "/"
        }
        return RelativeMediaLocation(relativePath = relativePath, displayName = displayName)
    }

    /**
     * Looks up [file] in [MediaStore] and returns its `content://media/external/<collection>/media/<id>`
     * URI if indexed, or `null` if [file] is not a media file or is not indexed in [MediaStore].
     */
    fun resolve(ctx: Context, file: File, mimeType: String? = null): ResolvedMediaUri? {
        val collection = collectionFor(file.name, mimeType) ?: return null
        val collectionUri = when (collection) {
            MediaCollection.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaCollection.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaCollection.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        // 1. Primary lookup: exact _data path match (covers primary storage and removable volumes).
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        val candidates = buildDataCandidates(file.absolutePath, canonical)
        if (candidates.isNotEmpty()) {
            val selection = if (candidates.size == 1) {
                "${MediaStore.MediaColumns.DATA} = ?"
            } else {
                "${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} = ?"
            }
            querySingle(ctx, collectionUri, projection, selection, candidates.toTypedArray())?.let { return it }
        }

        // 2. Fallback lookup: primary volume RELATIVE_PATH + DISPLAY_NAME (handles mount-prefix aliases).
        val extDir = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        val rootPaths = buildDataCandidates(extDir.absolutePath, runCatching { extDir.canonicalPath }.getOrNull())
        val rel = candidates.firstNotNullOfOrNull { candidate ->
            rootPaths.firstNotNullOfOrNull { root -> relativeLocationInPrimaryStorage(candidate, root) }
        } ?: return null

        val (relSelection, relArgs) = if (rel.relativePath == "/") {
            "${MediaStore.MediaColumns.VOLUME_NAME} = ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" to
                arrayOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, "/", "", rel.displayName)
        } else {
            "${MediaStore.MediaColumns.VOLUME_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" to
                arrayOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, rel.relativePath, rel.displayName)
        }
        return querySingle(ctx, collectionUri, projection, relSelection, relArgs)
    }

    private fun querySingle(
        ctx: Context,
        collectionUri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
    ): ResolvedMediaUri? = runCatching {
        ctx.contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            if (idIdx < 0 || c.isNull(idIdx)) return@use null
            val id = c.getLong(idIdx)
            if (id < 0L) return@use null
            val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx)?.takeIf { it.isNotBlank() } else null
            ResolvedMediaUri(
                uri = ContentUris.withAppendedId(collectionUri, id),
                mimeType = mime,
            )
        }
    }.getOrNull()
}
