// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DropboxAuthAndRestTest {

    private lateinit var ctx: Context
    private var server: HttpServer? = null

    private object TestCipher : TokenCipher {
        override fun encrypt(plain: String): String = "DBX_ENC:$plain"
        override fun decrypt(encoded: String): String? =
            encoded.removePrefix("DBX_ENC:").takeIf { encoded.startsWith("DBX_ENC:") }
    }

    private object FailingCipher : TokenCipher {
        override fun encrypt(plain: String): String? = null
        override fun decrypt(encoded: String): String? = null
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(DropboxAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun accountStoreEncryptsRefreshTokensAndNeverPersistsPlaintextWhenKeystoreUnavailable() {
        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        val acc = DropboxAccount(
            accountId = "dbid:AAH12345",
            email = "alex@example.com",
            displayName = "Alex",
            accountType = "pro",
            quotaUsed = 1024L,
            quotaTotal = 2_199_023_255_552L,
            appKey = "testappkey12345",
        )
        store.save(acc, DropboxTokens("AT_1", "RT_SECRET_1", 999_999L))

        val rawPrefs = ctx.getSharedPreferences(DropboxAccountStore.PREFS_NAME, Context.MODE_PRIVATE)
        val rawTok = rawPrefs.getString("tok_dbid-aah12345", null)
        assertEquals("DBX_ENC:RT_SECRET_1", rawTok)

        // Fresh store instance rehydrates refresh token from encrypted prefs without keeping access token on disk
        val reloaded = DropboxAccountStore(ctx, cipher = TestCipher)
        val hydrated = reloaded.getTokens("dbid:AAH12345")
        assertNotNull(hydrated)
        assertEquals("", hydrated!!.accessToken)
        assertEquals("RT_SECRET_1", hydrated.refreshToken)

        // Failing cipher never writes plaintext refresh tokens to SharedPreferences
        val brokenStore = DropboxAccountStore(ctx, cipher = FailingCipher)
        brokenStore.save(
            acc.copy(accountId = "broken@example.com"),
            DropboxTokens("AT_2", "SUPER_SECRET_REFRESH", 999_999L),
        )
        assertNull(rawPrefs.getString("tok_broken@example.com", null))
    }

    @Test
    fun redirectAndCodePastePkceFlowsAuthenticateAndPreserveNonRotatingRefreshToken() = runBlocking {
        val tokenCalls = AtomicInteger(0)
        val lastFormBodies = CopyOnWriteArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv

        srv.createContext("/oauth2/token") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            lastFormBodies += body
            val callNum = tokenCalls.incrementAndGet()
            val json = if (body.contains("grant_type=refresh_token")) {
                // Dropbox refresh responses omit refresh_token because the initial offline refresh_token does not rotate
                """{"access_token":"AT_REFRESHED_$callNum","token_type":"bearer","expires_in":14400}"""
            } else {
                """{"access_token":"AT_INITIAL_$callNum","refresh_token":"RT_OFFLINE_1","token_type":"bearer","expires_in":14400}"""
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/2/users/get_current_account") { ex ->
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            assertEquals("null", reqBody)
            val json = """
                {
                  "account_id": "dbid:AAH999",
                  "email": "teamlead@acme.io",
                  "name": {"display_name": "Team Lead"},
                  "account_type": {".tag": "business"},
                  "root_info": {
                    ".tag": "team",
                    "root_namespace_id": "987654321",
                    "home_namespace_id": "111222333"
                  }
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/2/users/get_space_usage") { ex ->
            val json = """
                {
                  "used": 53687091200,
                  "allocation": {".tag": "team", "allocated": 5497558138880}
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        var nowMs = 1_000_000L
        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        val auth = DropboxAuthManager(
            store = store,
            authorizeBaseUrl = "https://www.dropbox.com/oauth2/authorize",
            apiBaseUrl = base,
            clockMillis = { nowMs },
            clientOverride = OkHttpClient(),
        )

        // 1. Browser redirect PKCE flow
        var capturedAuthUrl = ""
        val redirectDeferred = async {
            auth.authorizeViaRedirect(
                appKeyInput = "wayfinderappkey1",
                aliasLabel = "Acme Team",
                onLaunchBrowser = { url ->
                    capturedAuthUrl = url
                    val state = Uri.parse(url).getQueryParameter("state")
                    DropboxOAuthCallbackBus.deliver(
                        Uri.parse("${DropboxAuthManager.REDIRECT_URI}?code=AUTH_CODE_ABC12345&state=$state"),
                    )
                },
            )
        }
        val acc = redirectDeferred.await()
        assertTrue(capturedAuthUrl.contains("token_access_type=offline"))
        assertTrue(capturedAuthUrl.contains("code_challenge_method=S256"))
        assertEquals("teamlead@acme.io", acc.accountId)
        assertEquals("987654321", acc.rootNamespaceId)
        assertTrue(acc.isBusiness)
        assertEquals("Acme Team", acc.sidebarTitle)
        assertEquals(53_687_091_200L, acc.quotaUsed)
        assertEquals(5_497_558_138_880L, acc.quotaTotal)

        // 2. Advance clock past 4h expiration -> refreshes access_token while keeping RT_OFFLINE_1
        nowMs += 15_000_000L
        val refreshedToken = auth.validAccessToken("teamlead@acme.io")
        assertTrue(refreshedToken.startsWith("AT_REFRESHED_"))
        assertEquals("RT_OFFLINE_1", store.getTokens("teamlead@acme.io")?.refreshToken)

        // 3. No-redirect Code Paste PKCE flow (must omit redirect_uri & state on authorizeUrl and omit redirect_uri on token POST)
        val codeSession = auth.startCodePasteSession("wayfinderappkey1")
        assertFalse(codeSession.authorizeUrl.contains("redirect_uri="))
        assertFalse(codeSession.authorizeUrl.contains("state="))
        assertTrue(codeSession.authorizeUrl.contains("token_access_type=offline"))

        val pastedAcc = auth.completeCodePasteSignIn(codeSession, "PASTED_CODE_987654321", "Personal Vault")
        assertEquals("Personal Vault", pastedAcc.sidebarTitle)
        val lastForm = URLDecoder.decode(lastFormBodies.last(), "UTF-8")
        assertFalse("No-redirect token exchange must omit redirect_uri", lastForm.contains("redirect_uri="))
        assertTrue(lastForm.contains("code_verifier=${codeSession.codeVerifier}"))
    }

    @Test
    fun restFileSystemSupportsPaginationTeamNamespaceUnicodeHeadersAndChunkedUploads() {
        val uploadedSimple = ByteArrayOutputStream()
        val uploadedSession = ByteArrayOutputStream()
        val seenPathRoots = CopyOnWriteArrayList<String>()
        val seenApiArgs = CopyOnWriteArrayList<String>()
        val listCalls = AtomicInteger(0)

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv

        srv.createContext("/2/files/list_folder") { ex ->
            if (ex.requestURI.path.endsWith("/continue")) {
                seenPathRoots += ex.requestHeaders.getFirst("Dropbox-API-Path-Root").orEmpty()
                val page2 = """
                    {
                      "entries": [
                        {".tag": "file", "name": "Café 東京 🚀.txt", "size": 42, "client_modified": "2026-11-20T12:00:00Z"}
                      ],
                      "has_more": false
                    }
                """.trimIndent().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, page2.size.toLong())
                ex.responseBody.use { it.write(page2) }
            } else {
                listCalls.incrementAndGet()
                seenPathRoots += ex.requestHeaders.getFirst("Dropbox-API-Path-Root").orEmpty()
                val reqJson = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                // Root folder in Dropbox MUST be "" (empty string), never "/"
                assertEquals("", reqJson.getString("path"))
                val page1 = """
                    {
                      "entries": [
                        {".tag": "folder", "name": "Projects"}
                      ],
                      "cursor": "CUR_PAGE_2",
                      "has_more": true
                    }
                """.trimIndent().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, page1.size.toLong())
                ex.responseBody.use { it.write(page1) }
            }
        }

        srv.createContext("/2/files/get_metadata") { ex ->
            val reqJson = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val path = reqJson.getString("path")
            if (path == "/Café 東京 🚀.txt") {
                val meta = """{".tag": "file", "name": "Café 東京 🚀.txt", "size": 42, "server_modified": "2026-11-20T12:00:00Z"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, meta.size.toLong())
                ex.responseBody.use { it.write(meta) }
            } else {
                val err = """{"error_summary": "path/not_found/.."}""".toByteArray()
                ex.sendResponseHeaders(409, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        srv.createContext("/2/files/download") { ex ->
            val argHeader = ex.requestHeaders.getFirst("Dropbox-API-Arg").orEmpty()
            seenApiArgs += argHeader
            // Verify HTTP header is strictly 7-bit printable ASCII even for Unicode + emoji filename
            assertTrue(argHeader.all { it.code in 0x20..0x7E })
            assertEquals("/Café 東京 🚀.txt", JSONObject(argHeader).getString("path"))
            val payload = "Hello Dropbox Unicode!".toByteArray()
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }

        srv.createContext("/2/files/upload") { ex ->
            val argHeader = ex.requestHeaders.getFirst("Dropbox-API-Arg").orEmpty()
            seenApiArgs += argHeader
            uploadedSimple.reset()
            uploadedSimple.write(ex.requestBody.readBytes())
            val meta = """{".tag": "file", "name": "small.bin", "size": ${uploadedSimple.size()}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, meta.size.toLong())
            ex.responseBody.use { it.write(meta) }
        }

        srv.createContext("/2/files/upload_session/start") { ex ->
            uploadedSession.reset()
            uploadedSession.write(ex.requestBody.readBytes())
            val resp = """{"session_id": "SESS_42"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }

        srv.createContext("/2/files/upload_session/append_v2") { ex ->
            val arg = JSONObject(ex.requestHeaders.getFirst("Dropbox-API-Arg").orEmpty())
            assertEquals("SESS_42", arg.getJSONObject("cursor").getString("session_id"))
            assertEquals(uploadedSession.size().toLong(), arg.getJSONObject("cursor").getLong("offset"))
            uploadedSession.write(ex.requestBody.readBytes())
            val resp = "null".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }

        srv.createContext("/2/files/upload_session/finish") { ex ->
            val arg = JSONObject(ex.requestHeaders.getFirst("Dropbox-API-Arg").orEmpty())
            assertEquals("SESS_42", arg.getJSONObject("cursor").getString("session_id"))
            assertEquals(uploadedSession.size().toLong(), arg.getJSONObject("cursor").getLong("offset"))
            assertEquals("/Projects/large.bin", arg.getJSONObject("commit").getString("path"))
            uploadedSession.write(ex.requestBody.readBytes())
            val resp = """{".tag": "file", "name": "large.bin", "size": ${uploadedSession.size()}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }

        srv.createContext("/2/files/get_thumbnail_v2") { ex ->
            val jpegStub = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            ex.sendResponseHeaders(200, jpegStub.size.toLong())
            ex.responseBody.use { it.write(jpegStub) }
        }

        val lastMovePayload = CopyOnWriteArrayList<JSONObject>()
        srv.createContext("/2/files/move_v2") { ex ->
            val req = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val fromLower = req.getString("from_path").lowercase(java.util.Locale.ROOT)
            val toLower = req.getString("to_path").lowercase(java.util.Locale.ROOT)
            if (fromLower == toLower) {
                // Real Dropbox servers reject direct case-only move_v2 with 409 to/conflict/file/
                val err = """{"error_summary": "to/conflict/file/..."}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(409, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            lastMovePayload += req
            val resp = """{"metadata": {".tag": "file", "name": "moved"}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }

        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        store.save(
            DropboxAccount(
                accountId = "alex@acme.io",
                email = "alex@acme.io",
                accountType = "business",
                rootNamespaceId = "987654321",
                appKey = "wayfinderappkey1",
            ),
            DropboxTokens("AT_VALID", "RT_VALID", System.currentTimeMillis() + 3_600_000L),
        )
        val auth = DropboxAuthManager(store = store, apiBaseUrl = base, clientOverride = OkHttpClient())
        val fs = DropboxRestFileSystem(
            context = ctx,
            store = store,
            auth = auth,
            apiBaseUrl = base,
            contentBaseUrl = base,
            simpleUploadLimitBytes = 32L,
            uploadChunkBytes = 20,
            clientOverride = OkHttpClient(),
        )

        // Paginated listing + Team Space header check
        val entries = fs.list("dropbox://alex@acme.io")
        assertEquals(listOf("Projects", "Café 東京 🚀.txt"), entries.map { it.name })
        assertTrue(seenPathRoots.all { JSONObject(it).getString("root") == "987654321" })

        // Unicode download with 7-bit ASCII escaped Dropbox-API-Arg header
        val downloaded = fs.openInput("dropbox://alex@acme.io/Café 東京 🚀.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("Hello Dropbox Unicode!", downloaded)

        // Simple upload (<= 32 bytes)
        val smallBytes = ByteArray(16) { (it + 1).toByte() }
        fs.openOutput("dropbox://alex@acme.io/small.bin").use { it.write(smallBytes) }
        assertArrayEquals(smallBytes, uploadedSimple.toByteArray())

        // Multi-chunk upload session (> 32 bytes -> 20B start + 20B append_v2 + 15B finish = 55B)
        val largeBytes = ByteArray(55) { (it * 3).toByte() }
        fs.openOutput("dropbox://alex@acme.io/Projects/large.bin").use { it.write(largeBytes) }
        assertArrayEquals(largeBytes, uploadedSession.toByteArray())

        // Server-rendered thumbnail download
        val thumbFile = File(ctx.cacheDir, "dbx_thumb_test.jpg")
        try {
            assertTrue(fs.downloadThumbnailToFile("dropbox://alex@acme.io/Café 東京 🚀.txt", thumbFile))
            assertEquals(4L, thumbFile.length())
        } finally {
            thumbFile.delete()
        }

        // Case-only rename hops via a temporary sibling so Dropbox's case-insensitive move_v2 never fails with 409 to/conflict/file
        val renamed = fs.rename("dropbox://alex@acme.io/Café 東京 🚀.txt", "CAFÉ 東京 🚀.txt")
        assertEquals("dropbox://alex@acme.io/CAFÉ 東京 🚀.txt", renamed)
        assertEquals(2, lastMovePayload.size)
        assertEquals("/Café 東京 🚀.txt", lastMovePayload[0].getString("from_path"))
        assertTrue(lastMovePayload[0].getString("to_path").startsWith("/.wf-case-"))
        assertEquals(lastMovePayload[0].getString("to_path"), lastMovePayload[1].getString("from_path"))
        assertEquals("/CAFÉ 東京 🚀.txt", lastMovePayload[1].getString("to_path"))

        // Web URL resolution for root, folder, and file
        assertEquals("https://www.dropbox.com/home", fs.resolveWebUrl("dropbox://alex@acme.io"))
        assertEquals("https://www.dropbox.com/home/Projects", fs.resolveWebUrl("dropbox://alex@acme.io/Projects"))
        val fileWebUrl = fs.resolveWebUrl("dropbox://alex@acme.io/Café 東京 🚀.txt").orEmpty()
        assertTrue(fileWebUrl.startsWith("https://www.dropbox.com/home?preview="))

        // Aborted output stream (e.g. cancelled copy or mid-stream read error) must NEVER upload truncated overwrite
        uploadedSimple.reset()
        try {
            fs.openOutput("dropbox://alex@acme.io/aborted.bin").useCommit { out ->
                out.write(byteArrayOf(1, 2, 3, 4))
                throw IOException("Simulated source read failure mid-copy")
            }
            fail("Expected IOException to propagate from useCommit")
        } catch (_: IOException) {}
        assertEquals("Aborted upload must not send partial bytes to Dropbox", 0, uploadedSimple.size())
    }

    @Test
    fun authCodeValidationPerMemberTeamQuotaAndBrowserTimeoutWorkUnderR8SafePaths() = runBlocking {
        // RFC 6749 visible ASCII codes (including '.', '=', '+', '/' and >512 chars) must be accepted
        val longCode = "sl.B0+" + "A".repeat(600) + "/=="
        assertTrue(DropboxAuthManager.isValidAuthorizationCode(longCode))
        assertFalse(DropboxAuthManager.isValidAuthorizationCode("has space"))
        assertFalse(DropboxAuthManager.isValidAuthorizationCode("short"))

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv
        srv.createContext("/2/users/get_current_account") { ex ->
            val json = """
                {
                  "account_id": "dbid:MEM1",
                  "email": "member@acme.io",
                  "account_type": {".tag": "business"},
                  "root_info": {".tag": "team", "root_namespace_id": "777", "home_namespace_id": "888"}
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/2/users/get_space_usage") { ex ->
            val json = """
                {
                  "used": 1000,
                  "allocation": {
                    ".tag": "team",
                    "used": 900000,
                    "allocated": 5000000,
                    "user_within_team_space_allocated": 50000,
                    "user_within_team_space_used_cached": 12500
                  }
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        store.save(
            DropboxAccount(
                accountId = "member@acme.io",
                email = "member@acme.io",
                appKey = "wayfinderappkey1",
            ),
            DropboxTokens("AT_MEM", "RT_MEM", System.currentTimeMillis() + 3_600_000L),
        )
        val auth = DropboxAuthManager(store = store, apiBaseUrl = base, clientOverride = OkHttpClient())
        val updated = auth.refreshAccountQuota("member@acme.io")
        assertNotNull(updated)
        assertEquals(12_500L, updated!!.quotaUsed)
        assertEquals(50_000L, updated.quotaTotal)
    }

    @Test
    fun userLimitHintAppendsOnlyForBundledAppKeyLimitErrorsNotUserCancel() {
        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        val auth = DropboxAuthManager(store = store, clientOverride = OkHttpClient())

        val cancelled = auth.appendUserLimitHint(
            DropboxAuthManager.BUNDLED_PUBLIC_APP_KEY,
            "The user chose not to give your app access",
        )
        org.junit.Assert.assertFalse(cancelled.contains("Advanced: Custom Dropbox App Key"))

        val capReached = auth.appendUserLimitHint(
            DropboxAuthManager.BUNDLED_PUBLIC_APP_KEY,
            "This app has reached its user limit.",
        )
        org.junit.Assert.assertTrue(capReached.contains("Advanced: Custom Dropbox App Key"))

        val customKeyCap = auth.appendUserLimitHint(
            "customappkey1234",
            "This app has reached its user limit.",
        )
        org.junit.Assert.assertFalse(customKeyCap.contains("Advanced: Custom Dropbox App Key"))
    }
}
