// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.CredentialProvider
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.RemoteAuthException
import com.droid.wayfinder.data.SmbCred
import com.droid.wayfinder.data.WebDavFileSystem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * WebDAV integration against an in-process fake DAV server (OkHttp MockWebServer with a small stateful
 * dispatcher). Runs under Robolectric so the platform XmlPullParser is the one used on devices.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebDavIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val files: MutableMap<String, ByteArray> = ConcurrentHashMap<String, ByteArray>()   // "/base/a.txt" -> bytes
    private val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()      // "/base", "/base/sub"
    private val requests = mutableListOf<RecordedRequest>()
    private var user = "qa"
    private var password = "secret"
    private lateinit var root: String
    private lateinit var fs: WebDavFileSystem

    private fun decode(p: String) = java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8").trimEnd('/').ifEmpty { "/" }

    private fun propEntry(path: String, dir: Boolean, size: Long) = """
        <d:response><d:href>${path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}${if (dir) "/" else ""}</d:href>
        <d:propstat><d:prop>
          <d:resourcetype>${if (dir) "<d:collection/>" else ""}</d:resourcetype>
          ${if (!dir) "<d:getcontentlength>$size</d:getcontentlength>" else ""}
          <d:getlastmodified>Mon, 12 Jan 2026 10:00:00 GMT</d:getlastmodified>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun multistatus(body: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">$body</d:multistatus>""")

    private fun parent(p: String) = p.substringBeforeLast('/').ifEmpty { "/" }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            synchronized(requests) { requests += request }
            val auth = request.getHeader("Authorization")
            val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
            if (auth != expected) return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"qa\"")
            val path = decode(request.requestUrl!!.encodedPath)
            return when (request.method) {
                "PROPFIND" -> {
                    val depth = request.getHeader("Depth")
                    when {
                        path in dirs -> {
                            val self = propEntry(path, true, 0)
                            val kids = if (depth == "1") {
                                dirs.filter { parent(it) == path && it != path }.joinToString("") { propEntry(it, true, 0) } +
                                    files.filterKeys { parent(it) == path }.entries.joinToString("") { propEntry(it.key, false, it.value.size.toLong()) }
                            } else ""
                            multistatus(self + kids)
                        }
                        path in files -> multistatus(propEntry(path, false, files[path]!!.size.toLong()))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                "GET" -> files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    if (request.getHeader("If-None-Match") == "*" && (path in files || path in dirs)) return MockResponse().setResponseCode(412)
                    if (parent(path) !in dirs) return MockResponse().setResponseCode(409)
                    val created = path !in files
                    files[path] = request.body.readByteArray()
                    MockResponse().setResponseCode(if (created) 201 else 204)
                }
                "MKCOL" -> when {
                    path in dirs || path in files -> MockResponse().setResponseCode(405)
                    parent(path) !in dirs -> MockResponse().setResponseCode(409)
                    else -> { dirs += path; MockResponse().setResponseCode(201) }
                }
                "DELETE" -> {
                    val existed = files.remove(path) != null || dirs.remove(path)
                    files.keys.removeIf { it.startsWith("$path/") }; dirs.removeIf { it.startsWith("$path/") }
                    MockResponse().setResponseCode(if (existed) 204 else 404)
                }
                "MOVE" -> {
                    val dest = decode(okhttp3.HttpUrl.Builder().scheme("http").host("x").build().resolve(request.getHeader("Destination")!!)!!.encodedPath)
                    if (request.getHeader("Overwrite") == "F" && (dest in files || dest in dirs)) return MockResponse().setResponseCode(412)
                    when {
                        path in files -> { files[dest] = files.remove(path)!! }
                        path in dirs -> {
                            dirs.remove(path); dirs += dest
                            files.keys.filter { it.startsWith("$path/") }.forEach { k -> files[dest + k.removePrefix(path)] = files.remove(k)!! }
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    MockResponse().setResponseCode(201)
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Before fun setUp() {
        dirs += "/"; dirs += "/base"; dirs += "/base/sub"
        files["/base/a.txt"] = "hello dav".toByteArray()
        files["/base/My File #1.txt"] = "spaces".toByteArray()
        files["/base/sub/deep.bin"] = ByteArray(300_000) { (it % 251).toByte() }
        server.dispatcher = dispatcher
        server.start()
        root = "dav://127.0.0.1:${server.port}/base"
        fs = WebDavFileSystem(CredentialProvider { SmbCred(user, password) })
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun listReturnsChildrenWithoutSelfAndDecodesNames() {
        val list = fs.list(root).sortedBy { it.name }
        assertEquals(listOf("My File #1.txt", "a.txt", "sub"), list.map { it.name })
        val a = list.first { it.name == "a.txt" }
        assertEquals("$root/a.txt", a.path); assertEquals(9L, a.size); assertFalse(a.isDir)
        assertTrue(a.modified > 0)
        assertTrue(list.first { it.name == "sub" }.isDir)
        val req = requests.last()
        assertEquals("PROPFIND", req.method); assertEquals("1", req.getHeader("Depth"))
        assertTrue("collections are requested with a trailing slash", req.requestUrl!!.encodedPath.endsWith("/base/"))
    }

    @Test fun statFileFolderRootAndMissing() {
        val st = fs.stat("$root/a.txt")!!
        assertFalse(st.isDir); assertEquals(9L, st.size)
        assertTrue(fs.stat("$root/sub")!!.isDir)
        assertNull(fs.stat("$root/nope.txt"))
        val server = fs.stat("dav://127.0.0.1:${this.server.port}")!!
        assertTrue(server.isDir && server.isServer)
        assertTrue(fs.exists("$root/My File #1.txt"))
    }

    @Test fun getStreamsBodyWithBasicAuth() {
        assertEquals("hello dav", fs.openInput("$root/a.txt").use { it.readBytes().decodeToString() })
        assertEquals("spaces", fs.openInput("$root/My File #1.txt").use { it.readBytes().decodeToString() })
        assertTrue(requests.last().getHeader("Authorization")!!.startsWith("Basic "))
        val e = assertThrows(IOException::class.java) { fs.openInput("$root/missing.txt").close() }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test fun putUploadsWithKnownLength() {
        fs.openOutput("$root/up load.txt").use { it.write("uploaded!".toByteArray()) }
        assertEquals("uploaded!", files["/base/up load.txt"]!!.decodeToString())
        val put = requests.last { it.method == "PUT" }
        assertEquals("9", put.getHeader("Content-Length"))
        assertTrue(put.getHeader("Transfer-Encoding") == null)
    }

    @Test fun putIntoMissingFolderReportsParentError() {
        val e = assertThrows(IOException::class.java) { fs.openOutput("$root/ghost/x.txt").use { it.write(1) } }
        assertTrue(e.message, e.message!!.contains("parent folder"))
    }

    @Test fun createFileRefusesToOverwrite() {
        fs.createFile("$root/empty.txt")
        assertEquals(0, files["/base/empty.txt"]!!.size)
        val e = assertThrows(IOException::class.java) { fs.createFile("$root/a.txt") }
        assertTrue(e.message!!.contains("already exists"))
        assertEquals("hello dav", files["/base/a.txt"]!!.decodeToString())
    }

    @Test fun mkcolCreatesFolderAndIsIdempotent() {
        fs.mkdir("$root/new dir")
        assertTrue("/base/new dir" in dirs)
        fs.mkdir("$root/new dir") // 405 + exists as folder = OK
        assertThrows(IOException::class.java) { fs.mkdir("$root/a.txt") } // 405 but a file
        assertThrows(IOException::class.java) { fs.mkdir("$root/x/y") }   // 409
    }

    @Test fun deleteRemovesAndToleratesMissing() {
        fs.delete("$root/sub")
        assertFalse("/base/sub" in dirs); assertFalse("/base/sub/deep.bin" in files)
        fs.delete("$root/sub") // 404 is fine
    }

    @Test fun moveAndRenameUseDestinationHeaderAndNeverOverwrite() {
        assertTrue(fs.move("$root/a.txt", "$root/sub/a.txt"))
        assertTrue("/base/sub/a.txt" in files)
        val mv = requests.last { it.method == "MOVE" }
        assertEquals("F", mv.getHeader("Overwrite"))
        assertTrue(mv.getHeader("Destination")!!.startsWith("http://127.0.0.1:${server.port}/base/sub/a.txt"))
        assertEquals("$root/sub/renamed.txt", fs.rename("$root/sub/a.txt", "renamed.txt"))
        assertTrue("/base/sub/renamed.txt" in files)
        // Existing destination: MOVE answers 412 → false / rename error.
        assertFalse(fs.move("$root/My File #1.txt", "$root/sub/renamed.txt"))
        assertThrows(IOException::class.java) { fs.rename("$root/My File #1.txt", "sub") }
        // Across servers the caller must copy.
        assertFalse(fs.move("$root/sub/renamed.txt", "dav://other:80/x"))
    }

    @Test fun authFailureRaisesRemoteAuthExceptionWithCredentialKey() {
        val bad = WebDavFileSystem(CredentialProvider { SmbCred("qa", "wrong") })
        val e = assertThrows(RemoteAuthException::class.java) { bad.list(root) }
        assertEquals("dav://127.0.0.1:${server.port}", e.host)
        val anon = WebDavFileSystem(CredentialProvider { null })
        assertThrows(RemoteAuthException::class.java) { anon.stat("$root/a.txt") }
        assertTrue(requests.last().getHeader("Authorization") == null)
    }

    @Test fun serverErrorsBecomeReadableMessages() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        val e = assertThrows(IOException::class.java) { fs.list(root) }
        assertTrue(e.message!!.contains("access denied"))
    }

    @Test fun unreachableServerIsAClearError() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val e = assertThrows(IOException::class.java) { fs.list("dav://127.0.0.1:$port/base") }
        assertTrue(e.message, e.message!!.startsWith("Can't reach"))
    }

    @Test fun copyTreeBetweenLocalAndWebDavBothWays() = runBlocking {
        val local = tmp.newFolder("local")
        val repo = FileRepository(LocalFileSystem(), LocalFileSystem(), null, byScheme = mapOf("dav" to fs))
        val ops = FileOperations(repo)
        // Download a remote folder.
        ops.transfer(listOf("$root/sub"), local.path, move = false, conflict = Conflict.KeepBoth) {}
        assertEquals(300_000L, File(local, "sub/deep.bin").length())
        // Upload a local folder and move it remotely.
        File(local, "up/inner").mkdirs(); File(local, "up/inner/x.txt").writeText("X")
        ops.transfer(listOf(File(local, "up").path), root, move = true, conflict = Conflict.KeepBoth) {}
        assertEquals("X", files["/base/up/inner/x.txt"]!!.decodeToString())
        assertFalse(File(local, "up").exists())
        // Conflict detection works on the remote side too.
        assertEquals(listOf("a.txt"), ops.conflicts(listOf(File(local, "a.txt").path), root))
    }

    @Test fun crossOriginRedirectIsRefusedAndDoesNotLeakAuthHeader() {
        val otherServer = MockWebServer()
        val otherRequests = mutableListOf<RecordedRequest>()
        otherServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(otherRequests) { otherRequests += request }
                return MockResponse().setResponseCode(200).setBody("pwned")
            }
        }
        otherServer.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "http://127.0.0.1:${otherServer.port}/steal")
                }
            }
            val e = assertThrows(IOException::class.java) {
                fs.openInput("$root/a.txt").use { it.readBytes() }
            }
            assertTrue(e.message, e.message!!.contains("cross-origin redirect"))
            assertTrue("Cross-origin redirect target must never be contacted", otherRequests.isEmpty())
        } finally {
            runCatching { otherServer.shutdown() }
        }
    }
}
