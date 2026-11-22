// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.content.UriPermission
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Resolves local shared-storage and removable-volume files to canonical [MediaStore] or
 * [DocumentsContract] URIs when Wayfinder can grant access to the target app, before falling back
 * to `WayfinderFileProvider`.
 *
 * Why this matters:
 * - System and third-party image/media viewers rely on `MediaStore` (`content://media/...`) or
 *   `ExternalStorageProvider` (`com.android.externalstorage.documents`) URIs to discover sibling
 *   files for image carousels, display modification timestamps, and support in-viewer rename or
 *   trash actions. Generic `FileProvider` URIs hide the parent folder and disable carouseling.
 * - On retail Android builds without `MANAGE_DOCUMENTS`, a third-party file manager cannot grant
 *   un-owned `com.android.externalstorage.documents` single-document URIs (`SecurityException`),
 *   unless covered by a persisted SAF tree grant (`buildDocumentUriUsingTree`).
 * - By contrast, `MediaProvider` (`authority = "media"`) honors `MANAGE_EXTERNAL_STORAGE` in
 *   `checkUriPermission`, allowing Wayfinder to grant read/write `content://media/...` URIs for
 *   local media files (`image/`, `video/`, `audio/`) across internal and removable storage.
 * - Non-media documents (e.g. `.docx`, `.xlsx`, `.pdf`, `.txt`) without a persisted SAF tree grant
 *   continue to fall back to `WayfinderFileProvider`, because `MediaProvider` omits
 *   `DocumentsContract.Document.COLUMN_FLAGS` (`FLAG_SUPPORTS_WRITE`), which Office editors inspect
 *   before enabling in-place saving.
 */
object LocalUriResolver {

    private const val MEDIA_SCAN_TIMEOUT_MS = 1200L

    /** Optional test hook overriding asynchronous [MediaScannerConnection.scanFile] on cache miss. */
    internal var mediaScannerOverride: (suspend (Context, String, String?) -> Uri?)? = null

    /** Optional test hook overriding [canGrantMediaUri] permission checks in unit tests. */
    internal var mediaGrantCheckOverride: ((Context, Uri, Boolean) -> Boolean)? = null

    private val FALLBACK_MEDIA_MIMES = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "dng" to "image/x-adobe-dng",
        "ico" to "image/x-icon",
        "svg" to "image/svg+xml",
        "wbmp" to "image/vnd.wap.wbmp",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
    )

    fun mimeTypeForPath(path: String): String? {
        val leaf = path.substringAfterLast('/')
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0 || dot == leaf.length - 1) return null
        val ext = leaf.substring(dot + 1).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: FALLBACK_MEDIA_MIMES[ext]
    }

    fun isMediaMimeType(mimeType: String?): Boolean {
        val lower = mimeType?.lowercase() ?: return false
        return lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")
    }

    fun isMediaUri(uri: Uri?): Boolean {
        if (uri == null || !uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        val auth = uri.authority?.substringAfterLast('@') ?: return false
        return auth == MediaStore.AUTHORITY
    }

    fun isMediaStoreAvailable(ctx: Context): Boolean = runCatching {
        if (ctx.packageManager.resolveContentProvider(MediaStore.AUTHORITY, 0) != null) {
            true
        } else {
            ctx.contentResolver.acquireUnstableContentProviderClient(MediaStore.AUTHORITY)?.use { true } == true
        }
    }.getOrDefault(false)

    /**
     * Zero-disk-I/O UI-thread precheck indicating whether [path] (or any file in [paths]) might
     * resolve to a [MediaStore] URI or a persisted `ExternalStorageProvider` SAF tree URI on `IO`.
     */
    fun anyMayResolveExternalLocalUri(ctx: Context, paths: List<String>): Boolean {
        val localPaths = paths.filter { FsPath.isLocalFile(it) && !it.contains('\u0000') }
        if (localPaths.isEmpty()) return false
        val hasExternalSafTree = runCatching {
            ctx.contentResolver.persistedUriPermissions.any { perm ->
                perm.isReadPermission && perm.uri.authority == SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY
            }
        }.getOrDefault(false)
        if (hasExternalSafTree) return true
        val mediaAvailable = isMediaStoreAvailable(ctx) || mediaScannerOverride != null
        if (!mediaAvailable) return false
        return localPaths.any { isMediaMimeType(mimeTypeForPath(it)) }
    }

    /**
     * Synchronous lookup (no media scan wait) for a grantable [MediaStore] or persisted SAF tree
     * [DocumentsContract] URI representing [path].
     */
    fun resolvePreferredLocalUriSync(
        ctx: Context,
        path: String,
        primaryStorageRoot: String = defaultPrimaryRoot(),
    ): Uri? {
        val (canonical, mime) = validateCandidate(ctx, path) ?: return null
        if (isMediaMimeType(mime) && mime != null && !hasNoMediaOrHiddenSegment(canonical, primaryStorageRoot)) {
            queryMediaStoreUri(ctx, canonical, path, mime)?.let { return it }
        }
        return findPersistedTreeDocumentUriFromCanonical(
            ctx = ctx,
            canonical = canonical,
            rawPath = path,
            primaryStorageRoot = primaryStorageRoot,
            requireWrite = false,
            cachedPerms = null,
        )
    }

    /**
     * Background-safe lookup for a grantable [MediaStore] or persisted SAF tree [DocumentsContract]
     * URI representing [path]. Triggers an on-demand media scan when [allowScan] is true and a
     * newly created media file is not yet indexed in [MediaStore].
     */
    suspend fun resolvePreferredLocalUri(
        ctx: Context,
        path: String,
        primaryStorageRoot: String = defaultPrimaryRoot(),
        allowScan: Boolean = true,
        cachedPerms: List<UriPermission>? = null,
    ): Uri? {
        val (canonical, mime) = validateCandidate(ctx, path) ?: return null
        if (isMediaMimeType(mime) && mime != null && !hasNoMediaOrHiddenSegment(canonical, primaryStorageRoot)) {
            queryMediaStoreUri(ctx, canonical, path, mime)?.let { return it }
            if (allowScan) {
                scanAndResolveMediaStoreUri(ctx, canonical, path, mime, primaryStorageRoot)?.let { return it }
            }
        }
        return findPersistedTreeDocumentUriFromCanonical(
            ctx = ctx,
            canonical = canonical,
            rawPath = path,
            primaryStorageRoot = primaryStorageRoot,
            requireWrite = false,
            cachedPerms = cachedPerms,
        )
    }

    /**
     * Returns a tree-backed [DocumentsContract] URI (`buildDocumentUriUsingTree`) when the user has
     * granted Wayfinder a persisted SAF tree permission covering [path] on external storage.
     */
    fun findPersistedTreeDocumentUri(
        ctx: Context,
        path: String,
        primaryStorageRoot: String = defaultPrimaryRoot(),
        requireWrite: Boolean = false,
        cachedPerms: List<UriPermission>? = null,
    ): Uri? {
        if (!FsPath.isLocalFile(path) || path.contains('\u0000') || FileInfo.isSymlink(path)) return null
        val canonical = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (isExcludedFromExternalResolution(ctx, canonical.path, path)) return null
        return findPersistedTreeDocumentUriFromCanonical(
            ctx = ctx,
            canonical = canonical,
            rawPath = path,
            primaryStorageRoot = primaryStorageRoot,
            requireWrite = requireWrite,
            cachedPerms = cachedPerms,
        )
    }

    private fun findPersistedTreeDocumentUriFromCanonical(
        ctx: Context,
        canonical: File,
        rawPath: String,
        primaryStorageRoot: String,
        requireWrite: Boolean,
        cachedPerms: List<UriPermission>?,
    ): Uri? {
        val perms = cachedPerms
            ?: runCatching { ctx.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        if (perms.isEmpty()) return null

        val canonicalPrimary = runCatching { File(primaryStorageRoot).canonicalPath }.getOrDefault(primaryStorageRoot)
        val singleDocUri = SystemDocumentsBridge.toDocumentsContractUri(
            path = canonical.path,
            tree = false,
            primaryStorageRoot = canonicalPrimary,
        ) ?: SystemDocumentsBridge.toDocumentsContractUri(
            path = rawPath,
            tree = false,
            primaryStorageRoot = primaryStorageRoot,
        ) ?: return null

        if (singleDocUri.authority != SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getDocumentId(singleDocUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null

        val matching = perms.filter { perm ->
            perm.isReadPermission &&
                (!requireWrite || perm.isWritePermission) &&
                perm.uri.authority == SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY &&
                runCatching { DocumentsContract.isTreeUri(perm.uri) }.getOrDefault(false) &&
                runCatching {
                    val treeDocId = DocumentsContract.getTreeDocumentId(perm.uri)
                    isDocIdWithinTree(treeDocId, docId)
                }.getOrDefault(false)
        }
        val best = matching.firstOrNull { it.isWritePermission } ?: matching.firstOrNull() ?: return null
        return runCatching { DocumentsContract.buildDocumentUriUsingTree(best.uri, docId) }.getOrNull()
    }

    internal fun isDocIdWithinTree(treeDocId: String, docId: String): Boolean {
        if (treeDocId.isBlank() || docId.isBlank()) return false
        if (docId == treeDocId) return true
        if (treeDocId.endsWith(':')) return docId.startsWith(treeDocId)
        return docId.startsWith("$treeDocId/")
    }

    /**
     * Verifies whether this app process is permitted to grant read (or read+write when [write] is
     * true) URI access on [uri] (`content://media/...`) to an external activity.
     */
    fun canGrantMediaUri(ctx: Context, uri: Uri, write: Boolean): Boolean {
        if (!isMediaUri(uri)) return false
        mediaGrantCheckOverride?.let { return it(ctx, uri, write) }
        if (Build.FINGERPRINT == "robolectric") return true
        val modeFlag = if (write) {
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (Build.VERSION.SDK_INT >= 34) {
            return runCatching {
                ctx.checkContentUriPermissionFull(uri, Process.myPid(), Process.myUid(), modeFlag) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
        val isAllFilesManager = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        if (isAllFilesManager) return true
        val hasExplicitGrant = runCatching {
            ctx.checkUriPermission(uri, Process.myPid(), Process.myUid(), modeFlag) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (hasExplicitGrant) return true
        if (write) return false
        return runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    private fun validateCandidate(ctx: Context, path: String): Pair<File, String?>? {
        if (!FsPath.isLocalFile(path) || path.contains('\u0000') || FileInfo.isSymlink(path)) return null
        val rawFile = File(path)
        val canonical = runCatching { rawFile.canonicalFile }.getOrNull() ?: return null
        if (!canonical.exists() || !canonical.isFile) return null
        if (isExcludedFromExternalResolution(ctx, canonical.path, path)) return null
        val mime = mimeTypeForPath(canonical.name) ?: mimeTypeForPath(path)
        return canonical to mime
    }

    private fun isExcludedFromExternalResolution(ctx: Context, canonicalPath: String, rawPath: String): Boolean {
        val dataDir = ctx.applicationInfo?.dataDir
        if (!dataDir.isNullOrBlank()) {
            val canonicalData = runCatching { File(dataDir).canonicalPath }.getOrDefault(dataDir)
            if (FsPath.isWithin(canonicalData, canonicalPath) || FsPath.isWithin(dataDir, rawPath)) {
                return true
            }
        }
        val cacheDir = ctx.cacheDir
        val canonicalCache = runCatching { cacheDir.canonicalPath }.getOrDefault(cacheDir.path)
        if (FsPath.isWithin(canonicalCache, canonicalPath) || FsPath.isWithin(cacheDir.path, rawPath)) {
            return true
        }
        val norm = FsPath.normalize(canonicalPath)
        return norm.contains("/Android/data/") ||
            norm.endsWith("/Android/data") ||
            norm.contains("/Android/obb/") ||
            norm.endsWith("/Android/obb")
    }

    internal fun hasNoMediaOrHiddenSegment(file: File, primaryStorageRoot: String): Boolean {
        if (file.name.startsWith(".")) return true
        val canonicalRoot = runCatching { File(primaryStorageRoot).canonicalPath }.getOrDefault(primaryStorageRoot)
        var dir = file.parentFile
        while (dir != null) {
            val p = dir.path
            if (p == "/" || p == "/storage" || p == "/storage/emulated" || p == canonicalRoot || p == primaryStorageRoot) {
                break
            }
            if (p.startsWith("/storage/") && p.removePrefix("/storage/").count { it == '/' } == 0) {
                break
            }
            if (dir.name.startsWith(".")) return true
            if (runCatching { File(dir, ".nomedia").exists() }.getOrDefault(false)) return true
            dir = dir.parentFile
        }
        return false
    }

    private fun queryMediaStoreUri(
        ctx: Context,
        canonicalFile: File,
        rawPath: String,
        mimeType: String,
    ): Uri? {
        if (!isMediaStoreAvailable(ctx)) return null
        val candidatePaths = listOf(canonicalFile.path, File(rawPath).absolutePath).distinct()
        for (volumeName in candidateMediaVolumes(ctx, canonicalFile)) {
            val collectionUri = mediaCollectionUri(volumeName, mimeType) ?: continue
            for (queryPath in candidatePaths) {
                val found = runCatching {
                    ctx.contentResolver.query(
                        collectionUri,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DATA} = ?",
                        arrayOf(queryPath),
                        null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                            if (idIdx >= 0 && !c.isNull(idIdx)) {
                                ContentUris.withAppendedId(collectionUri, c.getLong(idIdx))
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                if (found != null && canGrantMediaUri(ctx, found, write = false)) {
                    return found
                }
            }
        }
        // Fallback: primary volume RELATIVE_PATH + DISPLAY_NAME (handles mount-prefix aliases).
        val extDir = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (extDir != null) {
            val rootPaths = LocalMediaStoreUri.buildDataCandidates(
                extDir.absolutePath,
                runCatching { extDir.canonicalPath }.getOrNull(),
            )
            val rel = candidatePaths.firstNotNullOfOrNull { candidate ->
                rootPaths.firstNotNullOfOrNull { root ->
                    LocalMediaStoreUri.relativeLocationInPrimaryStorage(candidate, root)
                }
            }
            val collectionUri = mediaCollectionUri(MediaStore.VOLUME_EXTERNAL, mimeType)
            if (rel != null && collectionUri != null) {
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
                val found = runCatching {
                    ctx.contentResolver.query(
                        collectionUri,
                        arrayOf(MediaStore.MediaColumns._ID),
                        relSelection,
                        relArgs,
                        null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                            if (idIdx >= 0 && !c.isNull(idIdx)) {
                                ContentUris.withAppendedId(collectionUri, c.getLong(idIdx))
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                if (found != null && canGrantMediaUri(ctx, found, write = false)) {
                    return found
                }
            }
        }
        return null
    }

    private suspend fun scanAndResolveMediaStoreUri(
        ctx: Context,
        canonicalFile: File,
        rawPath: String,
        mimeType: String,
        primaryStorageRoot: String,
    ): Uri? {
        if (hasNoMediaOrHiddenSegment(canonicalFile, primaryStorageRoot)) return null
        val scanner = mediaScannerOverride ?: ::defaultMediaScanner
        val scanned = try {
            scanner(ctx, canonicalFile.path, mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (scanned != null && isTypedMediaStoreUri(scanned, mimeType)) {
            val normalized = normalizePrimaryMediaVolumeUri(scanned)
            if (canGrantMediaUri(ctx, normalized, write = false)) {
                return normalized
            }
        }
        return queryMediaStoreUri(ctx, canonicalFile, rawPath, mimeType)
    }

    private suspend fun defaultMediaScanner(ctx: Context, path: String, mimeType: String?): Uri? {
        // Robolectric's ShadowMediaScannerConnection records paths without invoking the callback.
        if (Build.FINGERPRINT == "robolectric" || !isMediaStoreAvailable(ctx)) return null
        return withTimeoutOrNull(MEDIA_SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(
                    ctx.applicationContext,
                    arrayOf(path),
                    if (mimeType != null) arrayOf(mimeType) else null,
                ) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }
        }
    }

    private fun candidateMediaVolumes(ctx: Context, canonicalFile: File): List<String> {
        val detected = runCatching {
            ctx.getSystemService(StorageManager::class.java)
                ?.getStorageVolume(canonicalFile)
                ?.mediaStoreVolumeName
        }.getOrNull()
        val normalizedDetected = when (detected) {
            null, "", MediaStore.VOLUME_EXTERNAL_PRIMARY -> MediaStore.VOLUME_EXTERNAL
            else -> detected
        }
        return listOf(normalizedDetected, MediaStore.VOLUME_EXTERNAL).distinct()
    }

    private fun mediaCollectionUri(volumeName: String, mimeType: String): Uri? {
        val lower = mimeType.lowercase()
        return when {
            lower.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volumeName)
            lower.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volumeName)
            lower.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volumeName)
            else -> null
        }
    }

    private fun isTypedMediaStoreUri(uri: Uri, mimeType: String): Boolean {
        if (!isMediaUri(uri)) return false
        val p = uri.path.orEmpty()
        val lower = mimeType.lowercase()
        return when {
            lower.startsWith("image/") -> p.contains("/images/media/")
            lower.startsWith("video/") -> p.contains("/video/media/")
            lower.startsWith("audio/") -> p.contains("/audio/media/")
            else -> false
        }
    }

    private fun normalizePrimaryMediaVolumeUri(uri: Uri): Uri {
        val segs = uri.pathSegments ?: return uri
        if (segs.firstOrNull() != MediaStore.VOLUME_EXTERNAL_PRIMARY) return uri
        val rebuiltPath = "/" + (listOf(MediaStore.VOLUME_EXTERNAL) + segs.drop(1)).joinToString("/")
        return uri.buildUpon().path(rebuiltPath).build()
    }

    private fun defaultPrimaryRoot(): String =
        runCatching { Environment.getExternalStorageDirectory().path }.getOrDefault("/storage/emulated/0")
}
