// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OneDriveIntegrationTest {

    private data class FakeNode(
        var id: String,
        var parentPath: String, // "" for root, or "Projects/2026"
        var name: String,
        val isDir: Boolean,
        var bytes: ByteArray = ByteArray(0),
        var modifiedIso: String = "2026-11-10T10:00:00Z",
    ) {
        val fullRelPath: String get() = if (parentPath.isEmpty()) name else "$parentPath/$name"
    }

    private lateinit var ctx: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var store: OneDriveAccountStore
    private lateinit var auth: OneDriveAuthManager
    private lateinit var fs: OneDriveRestFileSystem

    private val nodes = ConcurrentHashMap<String, FakeNode>() // keyed by fullRelPath
    private val nextId = AtomicInteger(100)
    private val cdnAuthHeadersSeen = Collections.synchronizedList(mutableListOf<String?>())
    private val uploadSessionAuthHeadersSeen = Collections.synchronizedList(mutableListOf<String?>())
    private val uploadChunksSeen = AtomicInteger(0)
    private val simulateFirst401 = AtomicBoolean(false)
    private val uploadBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private class PlainTestCipher : TokenCipher {
        override fun encrypt(plain: String): String = "T:$plain"
        override fun decrypt(encoded: String): String? = encoded.removePrefix("T:").takeIf { encoded.startsWith("T:") }
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(OneDriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        // Pre-populate 3 items at root (to test @odata.nextLink pagination across pages of size 2)
        nodes["Projects"] = FakeNode("ID_PROJECTS", "", "Projects", isDir = true)
        nodes["Notes.txt"] = FakeNode("ID_NOTES", "", "Notes.txt", isDir = false, bytes = "Hello OneDrive!".toByteArray())
        nodes["Photo.jpg"] = FakeNode("ID_PHOTO", "", "Photo.jpg", isDir = false, bytes = ByteArray(1024) { (it % 251).toByte() })

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/common/oauth2/v2.0/token") { ex ->
            sendJson(ex, 200, """{"access_token":"AT_FRESH","refresh_token":"RT_1","expires_in":3600}""")
        }
        server.createContext("/cdn/download/") { ex ->
            cdnAuthHeadersSeen += ex.requestHeaders.getFirst("Authorization")
            val rel = URLDecoder.decode(ex.requestURI.rawPath.removePrefix("/cdn/download/"), "UTF-8")
            val node = nodes[rel]
            if (node == null) {
                sendJson(ex, 404, """{"error":{"message":"not found"}}""")
            } else {
                ex.sendResponseHeaders(200, node.bytes.size.toLong())
                ex.responseBody.use { it.write(node.bytes) }
            }
        }
        server.createContext("/cdn/thumb/") { ex ->
            cdnAuthHeadersSeen += ex.requestHeaders.getFirst("Authorization")
            val thumbBytes = "JPEG_THUMB_PREVIEW_BYTES".toByteArray()
            ex.sendResponseHeaders(200, thumbBytes.size.toLong())
            ex.responseBody.use { it.write(thumbBytes) }
        }
        server.createContext("/upload-session/") { ex ->
            uploadSessionAuthHeadersSeen += ex.requestHeaders.getFirst("Authorization")
            val sessionKey = URLDecoder.decode(ex.requestURI.rawPath.removePrefix("/upload-session/"), "UTF-8")
            if (ex.requestMethod == "DELETE") {
                uploadBuffers.remove(sessionKey)
                ex.sendResponseHeaders(204, -1)
                ex.close()
                return@createContext
            }
            val rangeHeader = ex.requestHeaders.getFirst("Content-Range").orEmpty()
            // format: bytes start-end/total
            val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(rangeHeader)
            if (m == null) {
                sendJson(ex, 400, """{"error":{"message":"Missing Content-Range"}}""")
                return@createContext
            }
            val end = m.groupValues[2].toLong()
            val total = m.groupValues[3].toLong()
            val chunk = ex.requestBody.readBytes()
            val buf = uploadBuffers.computeIfAbsent(sessionKey) { ByteArrayOutputStream() }
            buf.write(chunk)
            uploadChunksSeen.incrementAndGet()
            if (end + 1 >= total) {
                val data = buf.toByteArray()
                uploadBuffers.remove(sessionKey)
                val parent = sessionKey.substringBeforeLast('/', "")
                val name = sessionKey.substringAfterLast('/')
                val node = FakeNode("ID_${nextId.incrementAndGet()}", parent, name, isDir = false, bytes = data)
                nodes[sessionKey] = node
                sendJson(ex, 201, nodeJson(node).toString())
            } else {
                sendJson(ex, 202, """{"nextExpectedRanges":["${end + 1}-"]}""")
            }
        }
        server.createContext("/v1.0/me/drive") { ex ->
            handleGraphDrive(ex)
        }
        server.start()

        store = OneDriveAccountStore(ctx, cipher = PlainTestCipher())
        store.save(
            OneDriveAccount(
                accountId = "alex@outlook.com",
                email = "alex@outlook.com",
                displayName = "Alex",
                aliasLabel = "OneDrive · Personal",
                driveType = "personal",
                quotaUsed = 100_000_000L,
                quotaTotal = 1_000_000_000L,
                clientId = "11111111-2222-3333-4444-555555555555",
            ),
            OneDriveTokens("AT_INITIAL", "RT_1", System.currentTimeMillis() + 3_600_000L),
        )
        auth = OneDriveAuthManager(
            store = store,
            authBaseUrl = "$baseUrl/common/oauth2/v2.0",
            graphBaseUrl = "$baseUrl/v1.0",
            clientOverride = OkHttpClient(),
        )
        // Set simpleUploadLimitBytes = 400 KiB so multi-chunk 320 KiB resumable uploads run fast in tests!
        fs = OneDriveRestFileSystem(
            context = ctx,
            auth = auth,
            graphBaseUrl = "$baseUrl/v1.0",
            simpleUploadLimitBytes = 400L * 1024L,
            uploadChunkBytes = 320 * 1024,
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun handleGraphDrive(ex: HttpExchange) {
        val authHeader = ex.requestHeaders.getFirst("Authorization").orEmpty()
        if (simulateFirst401.compareAndSet(true, false) || !authHeader.startsWith("Bearer AT_")) {
            sendJson(ex, 401, """{"error":{"code":"InvalidAuthenticationToken","message":"Expired"}}""")
            return
        }
        val rawPath = ex.requestURI.rawPath.removePrefix("/v1.0/me/drive")
        val query = ex.requestURI.rawQuery.orEmpty()
        val method = ex.requestMethod

        // 1. Root metadata: GET /root
        if (method == "GET" && rawPath == "/root") {
            val obj = JSONObject()
                .put("id", "ID_ROOT")
                .put("name", "root")
                .put("size", 0L)
                .put("webUrl", "https://onedrive.live.com/?id=root")
                .put("folder", JSONObject().put("childCount", nodes.values.count { it.parentPath.isEmpty() }))
            sendJson(ex, 200, obj.toString())
            return
        }

        // 2. Root children: GET/POST /root/children
        if (rawPath == "/root/children") {
            if (method == "GET") {
                sendChildrenPage(ex, parentRel = "", query = query)
                return
            }
            if (method == "POST") {
                createFolderIn(ex, parentRel = "")
                return
            }
        }

        // 3. Item by ID: PATCH /items/{id}
        if (rawPath.startsWith("/items/") && method == "PATCH") {
            val itemId = URLDecoder.decode(rawPath.removePrefix("/items/"), "UTF-8")
            val node = nodes.values.firstOrNull { it.id == itemId }
            if (node == null) {
                sendJson(ex, 404, """{"error":{"message":"Item $itemId not found"}}""")
                return
            }
            val body = JSONObject(ex.requestBody.bufferedReader().readText())
            val oldKey = node.fullRelPath
            val newName = body.optString("name", node.name)
            val newParent = body.optJSONObject("parentReference")?.optString("id")?.let { pid ->
                if (pid == "ID_ROOT") "" else nodes.values.firstOrNull { it.id == pid }?.fullRelPath
            } ?: node.parentPath
            val targetKey = if (newParent.isEmpty()) newName else "$newParent/$newName"
            if (targetKey != oldKey && nodes.containsKey(targetKey)) {
                sendJson(ex, 409, """{"error":{"code":"nameAlreadyExists","message":"Conflict"}}""")
                return
            }
            nodes.remove(oldKey)
            // Update descendant paths if moving/renaming a folder
            if (node.isDir) {
                val descendants = nodes.values.filter { it.parentPath == oldKey || it.parentPath.startsWith("$oldKey/") }
                for (d in descendants) {
                    nodes.remove(d.fullRelPath)
                    d.parentPath = targetKey + d.parentPath.removePrefix(oldKey)
                    nodes[d.fullRelPath] = d
                }
            }
            node.name = newName
            node.parentPath = newParent
            nodes[node.fullRelPath] = node
            sendJson(ex, 200, nodeJson(node).toString())
            return
        }

        // 4. Path-addressed endpoints: /root:/{encodedRel}...
        if (rawPath.startsWith("/root:/")) {
            val afterPrefix = rawPath.removePrefix("/root:/")
            val colonIdx = afterPrefix.indexOf(':')
            val encodedRel = if (colonIdx >= 0) afterPrefix.substring(0, colonIdx) else afterPrefix
            val suffix = if (colonIdx >= 0) afterPrefix.substring(colonIdx + 1) else ""
            val rel = encodedRel.split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }

            when {
                suffix.isEmpty() && method == "GET" -> {
                    val node = nodes[rel] ?: return sendJson(ex, 404, """{"error":{"message":"Not found"}}""")
                    sendJson(ex, 200, nodeJson(node).toString())
                }
                suffix.isEmpty() && method == "PATCH" -> {
                    val node = nodes[rel] ?: return sendJson(ex, 404, """{"error":{"message":"Not found"}}""")
                    val body = JSONObject(ex.requestBody.bufferedReader().readText())
                    val oldKey = node.fullRelPath
                    val newName = body.optString("name", node.name)
                    val newParent = body.optJSONObject("parentReference")?.optString("path")?.takeIf { it.isNotBlank() }?.let { pRef ->
                        when {
                            pRef == "/drive/root:" -> ""
                            pRef.startsWith("/drive/root:/") ->
                                pRef.removePrefix("/drive/root:/").split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }
                            else -> node.parentPath
                        }
                    } ?: node.parentPath
                    val targetKey = if (newParent.isEmpty()) newName else "$newParent/$newName"
                    if (targetKey != oldKey && nodes.containsKey(targetKey)) {
                        return sendJson(ex, 409, """{"error":{"code":"nameAlreadyExists","message":"Conflict"}}""")
                    }
                    nodes.remove(oldKey)
                    if (node.isDir) {
                        val descendants = nodes.values.filter { it.parentPath == oldKey || it.parentPath.startsWith("$oldKey/") }
                        for (d in descendants) {
                            nodes.remove(d.fullRelPath)
                            d.parentPath = targetKey + d.parentPath.removePrefix(oldKey)
                            nodes[d.fullRelPath] = d
                        }
                    }
                    node.name = newName
                    node.parentPath = newParent
                    nodes[node.fullRelPath] = node
                    sendJson(ex, 200, nodeJson(node).toString())
                }
                suffix.isEmpty() && method == "DELETE" -> {
                    val removed = nodes.remove(rel) ?: return sendJson(ex, 404, """{"error":{"message":"Not found"}}""")
                    if (removed.isDir) {
                        nodes.keys.filter { it.startsWith("$rel/") }.forEach { nodes.remove(it) }
                    }
                    ex.sendResponseHeaders(204, -1)
                    ex.close()
                }
                suffix == "/children" && method == "GET" -> {
                    if (!nodes.containsKey(rel)) return sendJson(ex, 404, """{"error":{"message":"Not found"}}""")
                    sendChildrenPage(ex, parentRel = rel, query = query)
                }
                suffix == "/children" && method == "POST" -> {
                    createFolderIn(ex, parentRel = rel)
                }
                suffix == "/content" && method == "GET" -> {
                    val node = nodes[rel] ?: return sendJson(ex, 404, """{"error":{"message":"Not found"}}""")
                    ex.responseHeaders.add("Location", "$baseUrl/cdn/download/${OneDriveRestFileSystem.encodeGraphPath(rel)}")
                    ex.sendResponseHeaders(302, -1)
                    ex.close()
                }
                suffix == "/content" && method == "PUT" -> {
                    if (query.contains("conflictBehavior=fail") && nodes.containsKey(rel)) {
                        return sendJson(ex, 409, """{"error":{"code":"nameAlreadyExists","message":"Already exists"}}""")
                    }
                    val data = ex.requestBody.readBytes()
                    val parent = rel.substringBeforeLast('/', "")
                    val name = rel.substringAfterLast('/')
                    val node = FakeNode("ID_${nextId.incrementAndGet()}", parent, name, isDir = false, bytes = data)
                    nodes[rel] = node
                    sendJson(ex, 201, nodeJson(node).toString())
                }
                suffix == "/createUploadSession" && method == "POST" -> {
                    val uploadUrl = "$baseUrl/upload-session/${OneDriveRestFileSystem.encodeGraphPath(rel)}"
                    sendJson(ex, 200, JSONObject().put("uploadUrl", uploadUrl).toString())
                }
                else -> sendJson(ex, 404, """{"error":{"message":"Unhandled suffix=$suffix method=$method"}}""")
            }
            return
        }

        sendJson(ex, 404, """{"error":{"message":"Unhandled path=$rawPath"}}""")
    }

    private fun sendChildrenPage(ex: HttpExchange, parentRel: String, query: String) {
        val all = nodes.values.filter { it.parentPath == parentRel }.sortedBy { it.name }
        val skip = Regex("""skip=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val pageSize = 2
        val slice = all.drop(skip).take(pageSize)
        val arr = JSONArray()
        slice.forEach { arr.put(nodeJson(it)) }
        val resp = JSONObject().put("value", arr)
        if (skip + pageSize < all.size) {
            val nextPath = if (parentRel.isEmpty()) {
                "$baseUrl/v1.0/me/drive/root/children?skip=${skip + pageSize}"
            } else {
                "$baseUrl/v1.0/me/drive/root:/${OneDriveRestFileSystem.encodeGraphPath(parentRel)}:/children?skip=${skip + pageSize}"
            }
            resp.put("@odata.nextLink", nextPath)
        }
        sendJson(ex, 200, resp.toString())
    }

    private fun createFolderIn(ex: HttpExchange, parentRel: String) {
        val body = JSONObject(ex.requestBody.bufferedReader().readText())
        val name = body.getString("name")
        val full = if (parentRel.isEmpty()) name else "$parentRel/$name"
        if (nodes.containsKey(full)) {
            sendJson(ex, 409, """{"error":{"code":"nameAlreadyExists","message":"Folder exists"}}""")
            return
        }
        val created = FakeNode("ID_${nextId.incrementAndGet()}", parentRel, name, isDir = true)
        nodes[full] = created
        sendJson(ex, 201, nodeJson(created).toString())
    }

    private fun nodeJson(n: FakeNode): JSONObject {
        val obj = JSONObject()
            .put("id", n.id)
            .put("name", n.name)
            .put("size", if (n.isDir) 0L else n.bytes.size.toLong())
            .put("lastModifiedDateTime", n.modifiedIso)
            .put("webUrl", "https://onedrive.live.com/?id=${n.id}")
        if (n.isDir) {
            obj.put("folder", JSONObject().put("childCount", nodes.values.count { it.parentPath == n.fullRelPath }))
        } else {
            obj.put("file", JSONObject().put("mimeType", "application/octet-stream"))
            if (n.name.endsWith(".jpg")) {
                val thumbSet = JSONObject().put(
                    "medium",
                    JSONObject().put("url", "$baseUrl/cdn/thumb/${n.id}"),
                )
                obj.put("thumbnails", JSONArray().put(thumbSet))
            }
        }
        return obj
    }

    private fun sendJson(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun listPaginationThumbnailsWebUrlAndTokenRefreshRetry() {
        // Trigger a 401 on the first Graph call to verify automatic single-flight refresh & transparent retry
        simulateFirst401.set(true)

        val rootItems = fs.list("onedrive://alex@outlook.com")
        assertEquals(listOf("Notes.txt", "Photo.jpg", "Projects"), rootItems.map { it.name })

        // Verify Graph thumbnail download strips Authorization header when hitting CDN
        val thumbDest = File(ctx.cacheDir, "test-od-thumb.jpg")
        try {
            assertTrue(fs.downloadThumbnailToFile("onedrive://alex@outlook.com/Photo.jpg", thumbDest))
            assertEquals("JPEG_THUMB_PREVIEW_BYTES", thumbDest.readText())
            assertTrue("CDN must never receive Graph Bearer token", cdnAuthHeadersSeen.all { it == null })
        } finally {
            thumbDest.delete()
        }

        // Verify webUrl resolution from cache and stat fallback
        assertEquals("https://onedrive.live.com/?id=ID_NOTES", fs.resolveWebUrl("onedrive://alex@outlook.com/Notes.txt"))
        assertEquals("https://onedrive.live.com/?id=root", fs.resolveWebUrl("onedrive://alex@outlook.com"))
    }

    @Test
    fun smallPutLargeResumableChunkedUploadAndCrudOperations() {
        // 1. mkdir + createFile (refuses overwrite)
        fs.mkdir("onedrive://alex@outlook.com/Projects/2026")
        val emptyDoc = "onedrive://alex@outlook.com/Projects/2026/Readme.md"
        fs.createFile(emptyDoc)
        assertEquals(0L, fs.stat(emptyDoc)!!.size)
        try {
            fs.createFile(emptyDoc)
            fail("createFile must refuse overwriting an existing file")
        } catch (_: IOException) {}

        // 2. Small upload (<= 400 KiB simple PUT) with colons & special chars in filename
        val trickyPath = "onedrive://alex@outlook.com/Projects/2026/Q4: Forecast #1 & 100%.txt"
        val smallPayload = "Quarterly revenue: $1,250,000".toByteArray(Charsets.UTF_8)
        fs.openOutput(trickyPath).use { it.write(smallPayload) }
        val readSmall = fs.openInput(trickyPath).use { it.readBytes() }
        assertArrayEquals(smallPayload, readSmall)
        assertTrue("CDN redirect must strip Authorization header", cdnAuthHeadersSeen.all { it == null })

        // 3. Large multi-chunk resumable upload (> 400 KiB -> 750 KiB across 3 chunks: 320 KiB + 320 KiB + 110 KiB)
        uploadChunksSeen.set(0)
        val largePayload = ByteArray(750 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val largePath = "onedrive://alex@outlook.com/Projects/2026/Dataset.bin"
        fs.openOutput(largePath).use { it.write(largePayload) }
        assertEquals("Expected 3 resumable chunks (320K + 320K + 110K)", 3, uploadChunksSeen.get())
        assertTrue("uploadUrl must NOT receive Authorization header", uploadSessionAuthHeadersSeen.all { it == null })
        val downloadedLarge = fs.openInput(largePath).use { it.readBytes() }
        assertArrayEquals(largePayload, downloadedLarge)

        // 4. Server-side rename & move via PATCH /items/{id}
        val renamedPath = fs.rename(largePath, "Dataset-v2.bin")
        assertEquals("onedrive://alex@outlook.com/Projects/2026/Dataset-v2.bin", renamedPath)
        assertNull(fs.stat(largePath))
        assertNotNull(fs.stat(renamedPath))

        fs.mkdir("onedrive://alex@outlook.com/Archive")
        val movedPath = "onedrive://alex@outlook.com/Archive/Dataset-v2.bin"
        fs.move(renamedPath, movedPath)
        assertNull(fs.stat(renamedPath))
        assertEquals(largePayload.size.toLong(), fs.stat(movedPath)!!.size)

        // 5. Delete
        fs.delete(movedPath)
        assertNull(fs.stat(movedPath))
    }

    @Test
    fun fileOperationsCrossProviderCopyAndCacheJanitorPartCleanup() = runBlocking {
        val localRoot = File(ctx.cacheDir, "wf-od-local-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val sub = File(localRoot, "ExportBundle").apply { mkdirs() }
            File(sub, "summary.txt").writeText("Cross-provider copy works!")

            val repo = FileRepository(
                local = LocalFileSystem(),
                smb = SmbFileSystem(CredentialProvider { null }),
                trash = TrashManager(File(localRoot, ".trash")),
                byScheme = mapOf(RemoteUri.ONEDRIVE_SCHEME to fs),
            )
            val ops = FileOperations(repo)

            // Copy Local -> OneDrive
            ops.transfer(
                sources = listOf(sub.path),
                destDir = "onedrive://alex@outlook.com/Projects",
                move = false,
                conflict = Conflict.Replace,
                onProgress = {},
            )
            val remoteFile = "onedrive://alex@outlook.com/Projects/ExportBundle/summary.txt"
            assertEquals("Cross-provider copy works!", fs.openInput(remoteFile).bufferedReader().readText())

            // Copy OneDrive -> Local
            val roundTripDir = File(localRoot, "RoundTrip").apply { mkdirs() }
            ops.transfer(
                sources = listOf("onedrive://alex@outlook.com/Projects/ExportBundle"),
                destDir = roundTripDir.path,
                move = false,
                conflict = Conflict.Replace,
                onProgress = {},
            )
            assertEquals(
                "Cross-provider copy works!",
                File(roundTripDir, "ExportBundle/summary.txt").readText(),
            )

            // Verify CacheJanitor prunes stale wf-onedrive*.part files
            val stalePart = File(ctx.cacheDir, "wf-onedrive-stale123.part").apply {
                writeText("orphaned upload buffer")
                setLastModified(1_000L)
            }
            CacheJanitor.pruneStaleCaches(ctx.cacheDir, nowMillis = System.currentTimeMillis())
            assertFalse("CacheJanitor should delete stale wf-onedrive*.part", stalePart.exists())
        } finally {
            localRoot.deleteRecursively()
        }
    }
}
