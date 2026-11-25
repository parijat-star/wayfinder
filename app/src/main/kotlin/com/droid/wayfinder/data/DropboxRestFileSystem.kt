// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Native Dropbox [RemoteFileSystem] backed by Dropbox HTTP API v2 (`api.dropboxapi.com` + `content.dropboxapi.com`).
 *
 * Paths use human-readable path addressing:
 * - Root: `dropbox://<accountId>`
 * - Nested folder or file: `dropbox://<accountId>/Projects/2026/Design.fig`
 *
 * Security & protocol invariants:
 * - Every path segment is validated with [FsPath.isSafeName] and control-character checks so untrusted
 *   server listings or user paths can never traverse `..` or inject NUL/control bytes.
 * - `Authorization: Bearer ...` headers are never forwarded across HTTP redirects (`followRedirects = false`).
 * - JSON headers passed in `Dropbox-API-Arg` and `Dropbox-API-Path-Root` are pure 7-bit ASCII with
 *   `\uXXXX` escaping for non-ASCII characters (`> 0x7E`), satisfying HTTP header grammar for Unicode filenames.
 * - Uploads <= [simpleUploadLimitBytes] (default `150 MiB`) use single `POST /2/files/upload`; larger files up
 *   to `350 GB` stream via `upload_session/start` -> `append_v2` -> `finish` without loading chunks onto the heap.
 */
class DropboxRestFileSystem(
    private val context: Context? = null,
    private val store: DropboxAccountStore,
    private val auth: DropboxAuthManager,
    private val apiBaseUrl: String = DropboxAuthManager.DEFAULT_API_BASE_URL,
    private val contentBaseUrl: String = DropboxAuthManager.DEFAULT_CONTENT_BASE_URL,
    private val simpleUploadLimitBytes: Long = DEFAULT_SIMPLE_UPLOAD_LIMIT_BYTES,
    private val uploadChunkBytes: Int = DEFAULT_UPLOAD_CHUNK_BYTES,
    clientOverride: OkHttpClient? = null,
) : RemoteFileSystem {

    companion object {
        const val DEFAULT_SIMPLE_UPLOAD_LIMIT_BYTES: Long = 150L * 1024 * 1024 // 150 MiB Dropbox single-upload limit
        const val DEFAULT_UPLOAD_CHUNK_BYTES: Int = 8 * 1024 * 1024 // 8 MiB (multiple of 4 MiB recommended by Dropbox)
        const val TEMP_UPLOAD_PREFIX = "wf-dropbox"
        const val TEMP_UPLOAD_SUFFIX = ".part"

        private val NAMESPACE_ID_REGEX = Regex("^[0-9A-Za-z_-]{1,64}$")

        /** Parses `dropbox://<accountId>[/<relPath>]` into `(accountId, segments)`, rejecting traversal or control segments. */
        fun parsePath(path: String): Pair<String, List<String>> {
            val prefix = "${RemoteUri.DROPBOX_SCHEME}://"
            if (!path.startsWith(prefix)) throw IOException("Not a Dropbox path: $path")
            val rest = path.removePrefix(prefix).trim('/')
            val slash = rest.indexOf('/')
            val rawAccount = if (slash >= 0) rest.substring(0, slash) else rest
            val accountId = DropboxAccountStore.sanitizeAccountKey(rawAccount)
            if (rawAccount.isEmpty() ||
                rawAccount.lowercase(Locale.ROOT).replace(':', '-') != accountId ||
                !DropboxAccountStore.isValidAccountKey(accountId)
            ) {
                throw IOException("Invalid Dropbox account in path")
            }
            if (slash < 0) return accountId to emptyList()
            val rel = rest.substring(slash + 1)
            val segments = rel.split('/').filter { it.isNotEmpty() }
            for (seg in segments) {
                if (!FsPath.isSafeName(seg) || seg.any { it < ' ' || it == '\u007f' }) {
                    throw IOException("Unsafe Dropbox path segment")
                }
            }
            return accountId to segments
        }

        /** Formats validated [segments] for Dropbox API v2 (`""` for root, `"/a/b"` for nested items). */
        fun toDropboxApiPath(segments: List<String>): String =
            if (segments.isEmpty()) "" else "/" + segments.joinToString("/")

        /**
         * Serializes [json] for `Dropbox-API-Arg` / `Dropbox-API-Path-Root` HTTP headers, escaping all
         * non-7-bit-printable characters (`< 0x20` or `> 0x7E`) as `\uXXXX` per Dropbox HTTP header rules.
         */
        fun encodeDropboxApiHeader(json: JSONObject): String {
            val raw = json.toString()
            if (raw.all { it.code in 0x20..0x7E }) return raw
            return buildString(raw.length + 16) {
                for (ch in raw) {
                    if (ch.code in 0x20..0x7E) {
                        append(ch)
                    } else {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    }
                }
            }
        }

        private fun encodeWebSegment(seg: String): String =
            URLEncoder.encode(seg, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
    }

    init {
        require(uploadChunkBytes > 0) {
            "Dropbox uploadChunkBytes ($uploadChunkBytes) must be positive"
        }
    }

    private val http: OkHttpClient = clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonMedia: MediaType = "application/json; charset=utf-8".toMediaType()
    private val binaryMedia: MediaType = "application/octet-stream".toMediaType()

    override fun disconnect(key: String) {
        val accountId = key.removePrefix("${RemoteUri.DROPBOX_SCHEME}://").substringBefore('/')
        if (accountId.isNotBlank()) auth.invalidateAccessToken(accountId)
        http.connectionPool.evictAll()
    }

    /** Resolves a canonical `https://www.dropbox.com/home/...` browser URL for [path]. */
    fun resolveWebUrl(path: String): String? {
        val norm = FsPath.normalize(path)
        val (_, segs) = runCatching { parsePath(norm) }.getOrNull() ?: return null
        if (segs.isEmpty()) return "https://www.dropbox.com/home"
        val entry = stat(norm)
        return if (entry == null || entry.isDir) {
            "https://www.dropbox.com/home/" + segs.joinToString("/") { encodeWebSegment(it) }
        } else {
            val parentSegs = segs.dropLast(1)
            val fileParam = encodeWebSegment(segs.last())
            if (parentSegs.isEmpty()) {
                "https://www.dropbox.com/home?preview=$fileParam"
            } else {
                "https://www.dropbox.com/home/" + parentSegs.joinToString("/") { encodeWebSegment(it) } + "?preview=$fileParam"
            }
        }
    }

    /** Downloads a server-rendered JPEG thumbnail via `/2/files/get_thumbnail_v2` into [destFile]. */
    fun downloadThumbnailToFile(path: String, destFile: File, maxBytes: Long = 4L * 1024 * 1024): Boolean {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = runCatching { parsePath(norm) }.getOrNull() ?: return false
        if (segs.isEmpty()) return false
        val arg = JSONObject().apply {
            put("resource", JSONObject().put(".tag", "path").put("path", toDropboxApiPath(segs)))
            put("format", JSONObject().put(".tag", "jpeg"))
            put("size", JSONObject().put(".tag", "w640h480"))
            put("mode", JSONObject().put(".tag", "fitone_bestfit"))
        }
        val url = "${contentBaseUrl.trimEnd('/')}/2/files/get_thumbnail_v2"
        return runCatching {
            executeDropbox(accountId) { token, pathRootHeader ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", encodeDropboxApiHeader(arg))
                    .applyPathRoot(pathRootHeader)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                body.byteStream().use { inp ->
                    destFile.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var total = 0L
                        while (total < maxBytes) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), maxBytes - total).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
                destFile.length() > 0L
            }
        }.getOrDefault(false)
    }

    override fun list(path: String): List<FileEntry> {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        val listUrl = "${apiBaseUrl.trimEnd('/')}/2/files/list_folder"
        val continueUrl = "${apiBaseUrl.trimEnd('/')}/2/files/list_folder/continue"

        val entries = ArrayList<FileEntry>()
        var cursor: String? = null
        var hasMore = true
        var pages = 0

        while (hasMore && pages < 50) {
            pages++
            val currentCursor = cursor
            val targetUrl = if (currentCursor == null) listUrl else continueUrl
            val payload = if (currentCursor == null) {
                JSONObject().apply {
                    put("path", toDropboxApiPath(segs))
                    put("recursive", false)
                    put("include_deleted", false)
                    put("include_non_downloadable_files", true)
                    put("limit", 2000)
                }
            } else {
                JSONObject().put("cursor", currentCursor)
            }

            executeDropbox(accountId) { token, pathRootHeader ->
                Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .applyPathRoot(pathRootHeader)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw dropboxHttpException("list Dropbox folder", accountId, resp.code, err)
                }
                val body = resp.body?.string() ?: throw IOException("Empty response from Dropbox")
                val json = JSONObject(body)
                val items = json.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    toFileEntry(norm, item)?.let { entries += it }
                }
                hasMore = json.optBoolean("has_more", false)
                cursor = if (hasMore) {
                    json.optString("cursor").takeIf { it.isNotBlank() }
                        ?: throw IOException("Missing pagination cursor from Dropbox")
                } else {
                    null
                }
            }
        }
        return entries
    }

    override fun stat(path: String): FileEntry? {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = runCatching { parsePath(norm) }.getOrNull() ?: return null
        if (segs.isEmpty()) {
            val label = store.getAccount(accountId)?.sidebarTitle ?: "Dropbox"
            return FileEntry(path = norm, name = label, isDir = true, modified = System.currentTimeMillis(), subtitle = "Dropbox")
        }
        val url = "${apiBaseUrl.trimEnd('/')}/2/files/get_metadata"
        val payload = JSONObject().apply {
            put("path", toDropboxApiPath(segs))
            put("include_deleted", false)
        }
        return runCatching {
            executeDropbox(accountId) { token, pathRootHeader ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .applyPathRoot(pathRootHeader)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) return@use null
                val item = JSONObject(resp.body?.string().orEmpty().ifBlank { return@use null })
                val parentPath = FsPath.parent(norm) ?: "${RemoteUri.DROPBOX_SCHEME}://$accountId"
                toFileEntry(parentPath, item)
            }
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot open Dropbox root as a file")
        val downloadUrl = "${contentBaseUrl.trimEnd('/')}/2/files/download"
        val arg = JSONObject().put("path", toDropboxApiPath(segs))

        val resp = executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", encodeDropboxApiHeader(arg))
                .applyPathRoot(pathRootHeader)
                .post(ByteArray(0).toRequestBody(null))
                .build()
        }

        if (resp.code in listOf(301, 302, 303, 307, 308)) {
            resp.close()
            throw IOException("Refusing unexpected redirect from Dropbox download endpoint")
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            val err = resp.use { it.body?.string().orEmpty() }
            throw dropboxHttpException("download from Dropbox", accountId, code, err)
        }
        val body = resp.body ?: run {
            resp.close()
            throw IOException("Empty download stream from Dropbox")
        }
        return object : java.io.FilterInputStream(body.byteStream().buffered(256 * 1024)) {
            override fun close() = try {
                super.close()
            } finally {
                resp.close()
            }
        }
    }

    override fun openOutput(path: String): OutputStream {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot write directly to Dropbox root")
        val fileName = segs.last()
        FsPath.nameError(fileName)?.let { throw IOException(it) }

        // Ensure credentials exist upfront before buffering data.
        auth.validAccessToken(accountId)

        val cacheDir = runCatching { context?.cacheDir }.getOrNull()
        val tmp = File.createTempFile(TEMP_UPLOAD_PREFIX, TEMP_UPLOAD_SUFFIX, cacheDir)
        val fos = try {
            FileOutputStream(tmp).buffered(256 * 1024)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }

        return object : FilterOutputStream(fos), AbortableOutputStream {
            private var closed = false
            private var aborted = false

            override fun abort() {
                aborted = true
            }

            override fun write(b: Int) {
                try {
                    out.write(b)
                } catch (e: IOException) {
                    aborted = true
                    tmp.delete()
                    throw e
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                try {
                    out.write(b, off, len)
                } catch (e: IOException) {
                    aborted = true
                    tmp.delete()
                    throw e
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    runCatching { super.close() }.onFailure { if (!aborted) throw it }
                    if (aborted) return
                    val totalBytes = tmp.length()
                    if (totalBytes <= simpleUploadLimitBytes) {
                        uploadSimple(accountId, segs, tmp, overwrite = true)
                    } else {
                        uploadChunkedSession(accountId, segs, tmp, totalBytes)
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun uploadSimple(accountId: String, segs: List<String>, tmp: File, overwrite: Boolean) {
        val uploadUrl = "${contentBaseUrl.trimEnd('/')}/2/files/upload"
        val arg = JSONObject().apply {
            put("path", toDropboxApiPath(segs))
            put("mode", JSONObject().put(".tag", if (overwrite) "overwrite" else "add"))
            put("autorename", false)
            put("mute", true)
        }
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", encodeDropboxApiHeader(arg))
                .applyPathRoot(pathRootHeader)
                .post(tmp.asRequestBody(binaryMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw dropboxHttpException("upload to Dropbox", accountId, resp.code, err)
            }
        }
    }

    private fun uploadChunkedSession(
        accountId: String,
        segs: List<String>,
        tmp: File,
        totalBytes: Long,
    ) {
        val base = contentBaseUrl.trimEnd('/')
        val startUrl = "$base/2/files/upload_session/start"
        val appendUrl = "$base/2/files/upload_session/append_v2"
        val finishUrl = "$base/2/files/upload_session/finish"

        val firstLen = minOf(uploadChunkBytes.toLong(), totalBytes).toInt()
        val startArg = JSONObject().put("close", firstLen.toLong() == totalBytes)
        val sessionId = executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(startUrl)
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", encodeDropboxApiHeader(startArg))
                .applyPathRoot(pathRootHeader)
                .post(FileSliceRequestBody(tmp, 0L, firstLen, binaryMedia))
                .build()
        }.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw dropboxHttpException("start Dropbox upload session", accountId, resp.code, raw)
            }
            JSONObject(raw).optString("session_id").takeIf { it.isNotBlank() }
                ?: throw IOException("Missing session_id in Dropbox upload_session/start response")
        }

        var offset = firstLen.toLong()
        while (offset + uploadChunkBytes < totalBytes) {
            val chunkLen = uploadChunkBytes
            val appendArg = JSONObject().apply {
                put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                put("close", false)
            }
            executeDropbox(accountId) { token, pathRootHeader ->
                Request.Builder()
                    .url(appendUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", encodeDropboxApiHeader(appendArg))
                    .applyPathRoot(pathRootHeader)
                    .post(FileSliceRequestBody(tmp, offset, chunkLen, binaryMedia))
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw dropboxHttpException("append Dropbox upload chunk", accountId, resp.code, err)
                }
            }
            offset += chunkLen
        }

        val finalLen = (totalBytes - offset).toInt().coerceAtLeast(0)
        val finishArg = JSONObject().apply {
            put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
            put(
                "commit",
                JSONObject().apply {
                    put("path", toDropboxApiPath(segs))
                    put("mode", JSONObject().put(".tag", "overwrite"))
                    put("autorename", false)
                    put("mute", true)
                },
            )
        }
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(finishUrl)
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", encodeDropboxApiHeader(finishArg))
                .applyPathRoot(pathRootHeader)
                .post(FileSliceRequestBody(tmp, offset, finalLen, binaryMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw dropboxHttpException("finish Dropbox upload session", accountId, resp.code, err)
            }
        }
    }

    override fun mkdir(path: String) {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) return
        val folderName = segs.last()
        FsPath.nameError(folderName)?.let { throw IOException(it) }

        val url = "${apiBaseUrl.trimEnd('/')}/2/files/create_folder_v2"
        val payload = JSONObject().apply {
            put("path", toDropboxApiPath(segs))
            put("autorename", false)
        }
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .applyPathRoot(pathRootHeader)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                if (resp.code == 409 && err.contains("conflict/folder")) return
                throw dropboxHttpException("create folder in Dropbox", accountId, resp.code, err)
            }
        }
    }

    override fun createFile(path: String) {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Invalid file name")
        val fileName = segs.last()
        FsPath.nameError(fileName)?.let { throw IOException(it) }
        if (exists(norm)) throw IOException("$fileName already exists")

        val uploadUrl = "${contentBaseUrl.trimEnd('/')}/2/files/upload"
        val arg = JSONObject().apply {
            put("path", toDropboxApiPath(segs))
            put("mode", JSONObject().put(".tag", "add"))
            put("autorename", false)
            put("mute", true)
        }
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", encodeDropboxApiHeader(arg))
                .applyPathRoot(pathRootHeader)
                .post(ByteArray(0).toRequestBody(binaryMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw dropboxHttpException("create file in Dropbox", accountId, resp.code, err)
            }
        }
    }

    override fun delete(path: String) {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot delete Dropbox root")
        val delUrl = "${apiBaseUrl.trimEnd('/')}/2/files/delete_v2"
        val payload = JSONObject().put("path", toDropboxApiPath(segs))
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(delUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .applyPathRoot(pathRootHeader)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                if (resp.code == 409 && err.contains("path_lookup/not_found")) return
                throw dropboxHttpException("delete item from Dropbox", accountId, resp.code, err)
            }
        }
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot rename Dropbox root")

        val parentPath = FsPath.parent(norm) ?: "${RemoteUri.DROPBOX_SCHEME}://$accountId"
        val dstPath = FsPath.normalize(FsPath.child(parentPath, newName))
        if (dstPath == norm) return dstPath
        if (!dstPath.equals(norm, ignoreCase = true) && exists(dstPath)) {
            throw IOException("\"$newName\" already exists")
        }

        val (_, dstSegs) = parsePath(dstPath)
        val fromLower = toDropboxApiPath(segs).lowercase(Locale.ROOT)
        val toLower = toDropboxApiPath(dstSegs).lowercase(Locale.ROOT)
        if (fromLower == toLower) {
            // Dropbox indexes paths case-insensitively (`path_lower`), rejecting direct case-only
            // `move_v2` calls (`notes.txt` -> `NOTES.txt`) with `409 to/conflict/file/`. Hop via a
            // short temporary sibling name and roll back if the second hop fails.
            val tmpName = ".wf-case-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
            val tmpSegs = dstSegs.dropLast(1) + tmpName
            executeMoveV2(accountId, segs, tmpSegs, "rename item in Dropbox")
            try {
                executeMoveV2(accountId, tmpSegs, dstSegs, "rename item in Dropbox")
            } catch (e: Throwable) {
                runCatching { executeMoveV2(accountId, tmpSegs, segs, "rollback rename in Dropbox") }
                throw e
            }
        } else {
            executeMoveV2(accountId, segs, dstSegs, "rename item in Dropbox")
        }
        return dstPath
    }

    override fun move(src: String, dstPath: String): Boolean {
        val srcNorm = FsPath.normalize(src)
        val dstNorm = FsPath.normalize(dstPath)
        if (srcNorm == dstNorm) return true
        val (srcAcc, srcSegs) = runCatching { parsePath(srcNorm) }.getOrNull() ?: return false
        val (dstAcc, dstSegs) = runCatching { parsePath(dstNorm) }.getOrNull() ?: return false
        if (srcAcc != dstAcc || srcSegs.isEmpty() || dstSegs.isEmpty()) return false
        val dstName = dstSegs.last()
        if (FsPath.nameError(dstName) != null) return false
        if (!dstNorm.equals(srcNorm, ignoreCase = true) && exists(dstNorm)) return false

        return runCatching {
            val fromLower = toDropboxApiPath(srcSegs).lowercase(Locale.ROOT)
            val toLower = toDropboxApiPath(dstSegs).lowercase(Locale.ROOT)
            if (fromLower == toLower) {
                val tmpName = ".wf-case-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
                val tmpSegs = dstSegs.dropLast(1) + tmpName
                executeMoveV2(srcAcc, srcSegs, tmpSegs, "move item in Dropbox")
                try {
                    executeMoveV2(srcAcc, tmpSegs, dstSegs, "move item in Dropbox")
                } catch (e: Throwable) {
                    runCatching { executeMoveV2(srcAcc, tmpSegs, srcSegs, "rollback move in Dropbox") }
                    throw e
                }
            } else {
                executeMoveV2(srcAcc, srcSegs, dstSegs, "move item in Dropbox")
            }
            true
        }.getOrDefault(false)
    }

    private fun executeMoveV2(
        accountId: String,
        fromSegs: List<String>,
        toSegs: List<String>,
        action: String,
    ) {
        val moveUrl = "${apiBaseUrl.trimEnd('/')}/2/files/move_v2"
        val payload = JSONObject().apply {
            put("from_path", toDropboxApiPath(fromSegs))
            put("to_path", toDropboxApiPath(toSegs))
            put("autorename", false)
            put("allow_ownership_transfer", false)
        }
        executeDropbox(accountId) { token, pathRootHeader ->
            Request.Builder()
                .url(moveUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .applyPathRoot(pathRootHeader)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw dropboxHttpException(action, accountId, resp.code, err)
            }
        }
    }

    private fun Request.Builder.applyPathRoot(pathRootHeader: String?): Request.Builder {
        if (!pathRootHeader.isNullOrBlank()) {
            header("Dropbox-API-Path-Root", pathRootHeader)
        }
        return this
    }

    private fun pathRootHeaderFor(accountId: String): String? {
        val ns = store.getAccount(accountId)?.rootNamespaceId?.trim().orEmpty()
        if (ns.isEmpty() || !NAMESPACE_ID_REGEX.matches(ns)) return null
        return encodeDropboxApiHeader(JSONObject().put(".tag", "root").put("root", ns))
    }

    private fun executeDropbox(
        accountId: String,
        buildRequest: (token: String, pathRootHeader: String?) -> Request,
    ): Response {
        val pathRoot = pathRootHeaderFor(accountId)
        val firstToken = auth.validAccessToken(accountId)
        val firstResp = http.newCall(buildRequest(firstToken, pathRoot)).execute()
        if (firstResp.code != 401) return firstResp
        firstResp.close()
        auth.invalidateAccessToken(accountId)
        val retryToken = auth.validAccessToken(accountId)
        return http.newCall(buildRequest(retryToken, pathRoot)).execute()
    }

    private fun toFileEntry(parentPath: String, item: JSONObject): FileEntry? {
        val tag = item.optString(".tag")
        if (tag == "deleted") return null
        val rawName = item.optString("name")
        if (!FsPath.isSafeName(rawName) || rawName.any { it < ' ' || it == '\u007f' }) return null
        val isDir = tag == "folder" || (!item.has(".tag") && !item.has("size"))
        val size = if (isDir) 0L else item.optLong("size", 0L).coerceAtLeast(0L)
        val modIso = item.optString("client_modified").ifBlank { item.optString("server_modified") }
        val modified = parseIsoMillis(modIso)
        val childPath = FsPath.normalize(FsPath.child(parentPath, rawName))
        return FileEntry(
            path = childPath,
            name = rawName,
            isDir = isDir,
            size = size,
            modified = modified,
            subtitle = "Dropbox",
        )
    }

    private fun dropboxHttpException(action: String, accountId: String, code: Int, rawBody: String): IOException {
        val summary = runCatching {
            val json = JSONObject(rawBody)
            json.optJSONObject("user_message")?.optString("text")?.takeIf { it.isNotBlank() }
                ?: json.optString("error_summary").takeIf { it.isNotBlank() }
        }.getOrNull() ?: rawBody.take(160).ifBlank { "HTTP $code" }
        val base = IOException("Couldn't $action: $summary")
        if (code == 401) {
            return RemoteAuthException("${RemoteUri.DROPBOX_SCHEME}://$accountId", base)
        }
        return base
    }

    private fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    /** Streams `[offset, offset + length)` from [file] in 64 KiB slices without loading the chunk into heap memory. */
    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Int,
        private val mediaType: MediaType,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = length.toLong()

        override fun writeTo(sink: BufferedSink) {
            if (length == 0) return
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) throw IOException("Unexpected EOF while streaming Dropbox upload chunk")
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }
}
