// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import okhttp3.HttpUrl
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
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Native Microsoft OneDrive [RemoteFileSystem] backed by Microsoft Graph REST API v1.0.
 *
 * Paths use human-readable path addressing:
 * - Root: `onedrive://<accountId>`
 * - Nested folder or file: `onedrive://<accountId>/Projects/2026/Q4-Forecast.xlsx`
 *
 * Security invariants:
 * - Every path segment is validated with [FsPath.isSafeName] and RFC 3986 percent-encoded (including `:` -> `%3A`)
 *   so untrusted paths can never escape `/me/drive/root:/...:` or traverse `..`.
 * - Graph `Authorization: Bearer ...` headers are never forwarded across redirects (`followRedirects = false`);
 *   pre-authenticated OneDrive/SharePoint CDN download URLs and `createUploadSession` URLs are called without
 *   the Graph `Authorization` header.
 * - Uploads <= [simpleUploadLimitBytes] use single `PUT .../content`; larger files up to 250 GB use
 *   `POST .../createUploadSession` with `320 KiB`-aligned streaming range `PUT` chunks.
 */
class OneDriveRestFileSystem(
    private val context: Context? = null,
    private val auth: OneDriveAuthManager,
    private val graphBaseUrl: String = OneDriveAuthManager.DEFAULT_GRAPH_BASE_URL,
    private val simpleUploadLimitBytes: Long = DEFAULT_SIMPLE_UPLOAD_LIMIT_BYTES,
    private val uploadChunkBytes: Int = DEFAULT_UPLOAD_CHUNK_BYTES,
    clientOverride: OkHttpClient? = null,
) : RemoteFileSystem {

    companion object {
        const val DEFAULT_SIMPLE_UPLOAD_LIMIT_BYTES: Long = 4L * 1024 * 1024 // 4 MiB
        const val DEFAULT_UPLOAD_CHUNK_BYTES: Int = 5 * 1024 * 1024 // 5 MiB (16 * 320 KiB)
        const val TEMP_UPLOAD_PREFIX = "wf-onedrive"
        const val TEMP_UPLOAD_SUFFIX = ".part"
        private const val METADATA_CACHE_LIMIT = 4096

        /** Parses `onedrive://<accountId>[/<relPath>]` into `(accountId, segments)`, rejecting traversal or NUL segments. */
        fun parsePath(path: String): Pair<String, List<String>> {
            val prefix = "${RemoteUri.ONEDRIVE_SCHEME}://"
            if (!path.startsWith(prefix)) throw IOException("Not a OneDrive path: $path")
            val rest = path.removePrefix(prefix).trim('/')
            val slash = rest.indexOf('/')
            val rawAccount = if (slash >= 0) rest.substring(0, slash) else rest
            val accountId = OneDriveAccountStore.sanitizeAccountKey(rawAccount)
            if (rawAccount.isEmpty() || rawAccount.lowercase(Locale.ROOT) != accountId || !OneDriveAccountStore.isValidAccountKey(accountId)) {
                throw IOException("Invalid OneDrive account in path")
            }
            if (slash < 0) return accountId to emptyList()
            val rel = rest.substring(slash + 1)
            val segments = rel.split('/').filter { it.isNotEmpty() }
            for (seg in segments) {
                if (!FsPath.isSafeName(seg) || seg.any { it < ' ' || it == '\u007f' }) {
                    throw IOException("Unsafe OneDrive path segment")
                }
            }
            return accountId to segments
        }

        /** Percent-encodes validated path segments for Graph `/me/drive/root:/<encoded>:` URLs. */
        fun encodeGraphSegments(segments: List<String>): String =
            segments.joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
            }

        /** Percent-encodes a single validated Graph path segment. */
        fun encodeGraphSegment(segment: String): String = encodeGraphSegments(listOf(segment))

        /** Percent-encodes a `/` separated relative Graph path, preserving `/` separators. */
        fun encodeGraphPath(relPath: String): String =
            encodeGraphSegments(relPath.split('/').filter { it.isNotEmpty() })
    }

    init {
        require(uploadChunkBytes > 0 && uploadChunkBytes % (320 * 1024) == 0) {
            "OneDrive uploadChunkBytes ($uploadChunkBytes) must be a positive multiple of 320 KiB"
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

    private val webUrls: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > METADATA_CACHE_LIMIT
        },
    )
    private val thumbnailUrls: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > METADATA_CACHE_LIMIT
        },
    )

    private val allowLoopbackUrls: Boolean by lazy {
        val u = graphBaseUrl.toHttpUrlOrNull()
        u != null && u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost")
    }

    override fun disconnect(key: String) {
        val accountId = key.removePrefix("${RemoteUri.ONEDRIVE_SCHEME}://").substringBefore('/')
        if (accountId.isNotBlank()) auth.invalidateAccessToken(accountId)
        http.connectionPool.evictAll()
    }

    fun webUrlOf(path: String): String? = webUrls[FsPath.normalize(path)]

    fun thumbnailUrlOf(path: String): String? = thumbnailUrls[FsPath.normalize(path)]

    /** Resolves (or queries Graph for) the browser `webUrl` of [path] so users can open items in OneDrive Web. */
    fun resolveWebUrl(path: String): String? {
        val norm = FsPath.normalize(path)
        webUrls[norm]?.let { return it }
        val (accountId, segs) = parsePath(norm)
        val endpoint = if (segs.isEmpty()) {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root?\$select=webUrl"
        } else {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:?\$select=webUrl"
        }
        return runCatching {
            executeGraph(accountId) { token ->
                Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) return@use null
                val url = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" }).optString("webUrl")
                url.takeIf { it.startsWith("https://") || (allowLoopbackUrls && it.startsWith("http://")) }
                    ?.also { webUrls[norm] = it }
            }
        }.getOrNull()
    }

    /** Downloads a pre-rendered Microsoft Graph thumbnail for [path] into [destFile], returning true on success. */
    fun downloadThumbnailToFile(path: String, destFile: File, maxBytes: Long = 4L * 1024 * 1024): Boolean {
        val thumbUrl = thumbnailUrlOf(path) ?: return false
        val validated = validateExternalCdnUrl(thumbUrl) ?: return false
        val req = Request.Builder().url(validated).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
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
        val query = "\$select=id,name,size,lastModifiedDateTime,folder,file,webUrl&\$expand=thumbnails(\$select=medium,large)&\$top=200"
        var nextUrl: String? = if (segs.isEmpty()) {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root/children?$query"
        } else {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:/children?$query"
        }

        val entries = ArrayList<FileEntry>()
        var pages = 0
        while (nextUrl != null && pages < 50) {
            pages++
            val currentUrl = nextUrl
            executeGraph(accountId) { token ->
                Request.Builder()
                    .url(currentUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw graphHttpException("list OneDrive folder", resp.code, err)
                }
                val body = resp.body?.string() ?: throw IOException("Empty response from Microsoft OneDrive")
                val json = JSONObject(body)
                val items = json.optJSONArray("value") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    toFileEntry(norm, item)?.let { entries += it }
                }
                val rawNext = json.optString("@odata.nextLink")
                nextUrl = when {
                    rawNext.isBlank() -> null
                    isSameGraphOrigin(rawNext) -> rawNext
                    else -> throw IOException("Cross-origin @odata.nextLink refused from OneDrive")
                }
            }
        }
        return entries
    }

    override fun stat(path: String): FileEntry? {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = runCatching { parsePath(norm) }.getOrNull() ?: return null
        if (segs.isEmpty()) {
            val label = auth.effectiveClientId().let { "OneDrive" }
            return FileEntry(path = norm, name = label, isDir = true, modified = System.currentTimeMillis(), subtitle = "OneDrive")
        }
        val url = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:?\$select=id,name,size,lastModifiedDateTime,folder,file,webUrl"
        return runCatching {
            executeGraph(accountId) { token ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            }.use { resp ->
                if (!resp.isSuccessful) return@use null
                val item = JSONObject(resp.body?.string().orEmpty().ifBlank { return@use null })
                val parentPath = FsPath.parent(norm) ?: "${RemoteUri.ONEDRIVE_SCHEME}://$accountId"
                toFileEntry(parentPath, item)
            }
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot open OneDrive root as a file")
        val contentUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:/content"

        val resp = executeGraph(accountId) { token ->
            Request.Builder()
                .url(contentUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }

        if (resp.code in listOf(301, 302, 303, 307, 308)) {
            val location = resp.header("Location").orEmpty()
            resp.close()
            val cdnUrl = validateExternalCdnUrl(location)
                ?: throw IOException("Refusing insecure or invalid OneDrive download redirect")
            // Pre-authenticated OneDrive/SharePoint CDN URLs must NOT receive the Graph Authorization header.
            val cdnResp = http.newCall(Request.Builder().url(cdnUrl).get().build()).execute()
            if (!cdnResp.isSuccessful) {
                val code = cdnResp.code
                cdnResp.close()
                throw IOException("Failed to download file from OneDrive CDN (HTTP $code)")
            }
            return cdnResp.body?.byteStream() ?: run {
                cdnResp.close()
                throw IOException("Empty download stream from OneDrive")
            }
        }

        if (!resp.isSuccessful) {
            val code = resp.code
            val err = resp.body?.string().orEmpty()
            resp.close()
            throw graphHttpException("download from OneDrive", code, err)
        }
        return resp.body?.byteStream() ?: run {
            resp.close()
            throw IOException("Empty download stream from OneDrive")
        }
    }

    override fun openOutput(path: String): OutputStream {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot write directly to OneDrive root")
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

        return object : FilterOutputStream(fos) {
            private var closed = false

            override fun write(b: Int) {
                try {
                    out.write(b)
                } catch (e: IOException) {
                    tmp.delete()
                    throw e
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                try {
                    out.write(b, off, len)
                } catch (e: IOException) {
                    tmp.delete()
                    throw e
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                    val totalBytes = tmp.length()
                    if (totalBytes <= simpleUploadLimitBytes) {
                        uploadSimple(accountId, segs, tmp)
                    } else {
                        uploadChunkedSession(accountId, segs, fileName, tmp, totalBytes)
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun uploadSimple(accountId: String, segs: List<String>, tmp: File) {
        val putUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:/content"
        executeGraph(accountId) { token ->
            Request.Builder()
                .url(putUrl)
                .header("Authorization", "Bearer $token")
                .put(tmp.asRequestBody(binaryMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw graphHttpException("upload to OneDrive", resp.code, err)
            }
        }
    }

    private fun uploadChunkedSession(
        accountId: String,
        segs: List<String>,
        fileName: String,
        tmp: File,
        totalBytes: Long,
    ) {
        val sessionUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:/createUploadSession"
        val payload = JSONObject().apply {
            put("item", JSONObject().apply {
                put("@microsoft.graph.conflictBehavior", "replace")
                put("name", fileName)
            })
        }
        val uploadUrlStr = executeGraph(accountId) { token ->
            Request.Builder()
                .url(sessionUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw graphHttpException("create OneDrive upload session", resp.code, raw)
            }
            JSONObject(raw).optString("uploadUrl")
        }

        val uploadUrl = validateExternalCdnUrl(uploadUrlStr)
            ?: throw IOException("Refusing insecure or invalid OneDrive uploadSession URL")

        var offset = 0L
        while (offset < totalBytes) {
            val chunkLen = minOf(uploadChunkBytes.toLong(), totalBytes - offset).toInt()
            val endInclusive = offset + chunkLen - 1L
            val body = FileSliceRequestBody(tmp, offset, chunkLen, binaryMedia)
            // Pre-authenticated uploadSession URLs must NOT include the Graph Authorization header.
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Content-Range", "bytes $offset-$endInclusive/$totalBytes")
                .put(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code !in listOf(200, 201, 202)) {
                    val err = resp.body?.string().orEmpty()
                    runCatching { http.newCall(Request.Builder().url(uploadUrl).delete().build()).execute().close() }
                    throw graphHttpException("upload chunk to OneDrive", resp.code, err)
                }
            }
            offset += chunkLen
        }
    }

    override fun mkdir(path: String) {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) return
        val folderName = segs.last()
        FsPath.nameError(folderName)?.let { throw IOException(it) }
        val parentSegs = segs.dropLast(1)
        val childrenUrl = if (parentSegs.isEmpty()) {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root/children"
        } else {
            "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(parentSegs)}:/children"
        }
        val payload = JSONObject().apply {
            put("name", folderName)
            put("folder", JSONObject())
            put("@microsoft.graph.conflictBehavior", "fail")
        }
        executeGraph(accountId) { token ->
            Request.Builder()
                .url(childrenUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful && resp.code != 409) {
                val err = resp.body?.string().orEmpty()
                throw graphHttpException("create folder in OneDrive", resp.code, err)
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

        val putUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:/content?@microsoft.graph.conflictBehavior=fail"
        executeGraph(accountId) { token ->
            Request.Builder()
                .url(putUrl)
                .header("Authorization", "Bearer $token")
                .put(ByteArray(0).toRequestBody(binaryMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw graphHttpException("create file in OneDrive", resp.code, err)
            }
        }
    }

    override fun delete(path: String) {
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot delete OneDrive root")
        val delUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:"
        executeGraph(accountId) { token ->
            Request.Builder()
                .url(delUrl)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        }.use { resp ->
            if (!resp.isSuccessful && resp.code != 404) {
                val err = resp.body?.string().orEmpty()
                throw graphHttpException("delete item from OneDrive", resp.code, err)
            }
        }
        webUrls.remove(norm)
        thumbnailUrls.remove(norm)
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val norm = FsPath.normalize(path)
        val (accountId, segs) = parsePath(norm)
        if (segs.isEmpty()) throw IOException("Cannot rename OneDrive root")

        val parentPath = FsPath.parent(norm) ?: "${RemoteUri.ONEDRIVE_SCHEME}://$accountId"
        val dstPath = FsPath.child(parentPath, newName)
        if (!dstPath.equals(norm, ignoreCase = false) && exists(dstPath)) {
            throw IOException("\"$newName\" already exists")
        }

        val patchUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(segs)}:"
        val payload = JSONObject().apply { put("name", newName) }
        executeGraph(accountId) { token ->
            Request.Builder()
                .url(patchUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .patch(payload.toString().toRequestBody(jsonMedia))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw graphHttpException("rename item in OneDrive", resp.code, err)
            }
        }
        webUrls.remove(norm)
        thumbnailUrls.remove(norm)
        return dstPath
    }

    override fun move(src: String, dstPath: String): Boolean {
        val srcNorm = FsPath.normalize(src)
        val dstNorm = FsPath.normalize(dstPath)
        val (srcAcc, srcSegs) = runCatching { parsePath(srcNorm) }.getOrNull() ?: return false
        val (dstAcc, dstSegs) = runCatching { parsePath(dstNorm) }.getOrNull() ?: return false
        if (srcAcc != dstAcc || srcSegs.isEmpty() || dstSegs.isEmpty()) return false
        val dstName = dstSegs.last()
        if (FsPath.nameError(dstName) != null) return false
        if (exists(dstNorm)) return false

        val dstParentSegs = dstSegs.dropLast(1)
        val graphParentPath = if (dstParentSegs.isEmpty()) {
            "/drive/root:"
        } else {
            "/drive/root:/${encodeGraphSegments(dstParentSegs)}"
        }
        val payload = JSONObject().apply {
            put("name", dstName)
            put("parentReference", JSONObject().put("path", graphParentPath))
        }
        val patchUrl = "${graphBaseUrl.trimEnd('/')}/me/drive/root:/${encodeGraphSegments(srcSegs)}:"
        return runCatching {
            executeGraph(srcAcc) { token ->
                Request.Builder()
                    .url(patchUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .patch(payload.toString().toRequestBody(jsonMedia))
                    .build()
            }.use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    private fun executeGraph(accountId: String, buildRequest: (token: String) -> Request): Response {
        val firstToken = auth.validAccessToken(accountId)
        val firstResp = http.newCall(buildRequest(firstToken)).execute()
        if (firstResp.code != 401) return firstResp
        firstResp.close()
        auth.invalidateAccessToken(accountId)
        val retryToken = auth.validAccessToken(accountId)
        return http.newCall(buildRequest(retryToken)).execute()
    }

    private fun toFileEntry(parentPath: String, item: JSONObject): FileEntry? {
        val rawName = item.optString("name")
        if (!FsPath.isSafeName(rawName) || rawName.any { it < ' ' || it == '\u007f' }) return null
        val isDir = item.optJSONObject("folder") != null
        val size = if (isDir) 0L else item.optLong("size", 0L).coerceAtLeast(0L)
        val modified = parseIsoMillis(item.optString("lastModifiedDateTime"))
        val childPath = FsPath.normalize(FsPath.child(parentPath, rawName))

        val webUrl = item.optString("webUrl")
        if (webUrl.startsWith("https://") || (allowLoopbackUrls && webUrl.startsWith("http://"))) {
            webUrls[childPath] = webUrl
        }
        val thumbs = item.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            val t0 = thumbs.optJSONObject(0)
            val bestThumb = t0?.optJSONObject("large")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: t0?.optJSONObject("medium")?.optString("url")?.takeIf { it.isNotBlank() }
            if (bestThumb != null && validateExternalCdnUrl(bestThumb) != null) {
                thumbnailUrls[childPath] = bestThumb
            }
        }

        return FileEntry(
            path = childPath,
            name = rawName,
            isDir = isDir,
            size = size,
            modified = modified,
            subtitle = "OneDrive",
        )
    }

    private fun isSameGraphOrigin(candidate: String): Boolean {
        val base = graphBaseUrl.toHttpUrlOrNull() ?: return false
        val next = candidate.toHttpUrlOrNull() ?: return false
        return base.scheme == next.scheme && base.host.equals(next.host, ignoreCase = true) && base.port == next.port
    }

    private fun validateExternalCdnUrl(raw: String): HttpUrl? {
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (parsed.scheme == "https") return parsed
        if (allowLoopbackUrls && parsed.scheme == "http" && (parsed.host == "127.0.0.1" || parsed.host == "localhost")) {
            return parsed
        }
        return null
    }

    private fun graphHttpException(action: String, code: Int, rawBody: String): IOException {
        val msg = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "HTTP $code"
        return IOException("Couldn't $action: $msg")
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
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) throw IOException("Unexpected EOF while streaming OneDrive upload chunk")
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }
}
