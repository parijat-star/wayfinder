// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OneDriveAuthAndStoreTest {

    private lateinit var ctx: Context
    private var server: HttpServer? = null

    private class FakeXorCipher : TokenCipher {
        override fun encrypt(plain: String): String =
            "ENC_TEST_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                plain.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
            )

        override fun decrypt(encoded: String): String? {
            if (!encoded.startsWith("ENC_TEST_")) return null
            val raw = Base64.getUrlDecoder().decode(encoded.removePrefix("ENC_TEST_"))
            return String(raw.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), Charsets.UTF_8)
        }
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(OneDriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun pkceS256MatchesRfc7636AppendixBTestVector() {
        // RFC 7636 Appendix B canonical test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, OneDriveAuthManager.computeCodeChallengeS256(verifier))

        val generated = OneDriveAuthManager.generateCodeVerifier()
        assertTrue("Verifier length=${generated.length}", generated.length in 43..128)
        assertTrue(generated.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun accountStoreEncryptsRefreshTokensAndBackupXmlExcludesPrefs() {
        val store = OneDriveAccountStore(ctx, cipher = FakeXorCipher())
        val clientId = "11111111-2222-3333-4444-555555555555"
        store.defaultClientId = clientId

        val secretRefreshToken = "M.R3_BAY.super_secret_refresh_token_987654"
        val secretAccessToken = "EwB4A8l6BAAU_super_secret_access_token_123456"
        val acc = OneDriveAccount(
            accountId = "alex@outlook.com",
            email = "alex@outlook.com",
            displayName = "Alex Mercer",
            aliasLabel = "OneDrive · Personal",
            driveType = "personal",
            quotaUsed = 45_526_653_337L,
            quotaTotal = 1_099_511_627_776L,
            clientId = clientId,
        )
        store.save(
            acc,
            OneDriveTokens(
                accessToken = secretAccessToken,
                refreshToken = secretRefreshToken,
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )

        val loaded = store.getAccount("alex@outlook.com")
        assertNotNull(loaded)
        assertEquals("OneDrive · Personal", loaded!!.sidebarTitle)
        assertEquals(secretRefreshToken, store.getTokens("alex@outlook.com")?.refreshToken)
        assertEquals(secretAccessToken, store.getTokens("alex@outlook.com")?.accessToken)

        // Inspect raw SharedPreferences values to prove neither token is persisted in plaintext
        val rawPrefs = ctx.getSharedPreferences(OneDriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE)
        val rawDump = rawPrefs.all.entries.joinToString("\n") { "${it.key}=${it.value}" }
        assertFalse("Raw prefs leaked plaintext refresh token!", rawDump.contains(secretRefreshToken))
        assertFalse("Raw prefs leaked plaintext access token!", rawDump.contains(secretAccessToken))

        // Verify backup_rules.xml and data_extraction_rules.xml exclude onedrive_accounts.xml
        val root = sequenceOf(File("."), File("..")).map { it.canonicalFile }
            .first { File(it, "app/src/main/res/xml/backup_rules.xml").isFile }
        val backupXml = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        val extractionXml = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(backupXml.contains("""path="onedrive_accounts.xml""""))
        assertEquals(
            "Expected exclusion in both <cloud-backup> and <device-transfer>",
            2,
            Regex("""path="onedrive_accounts\.xml"""").findAll(extractionXml).count(),
        )

        store.remove("alex@outlook.com")
        assertNull(store.getAccount("alex@outlook.com"))
        assertNull(store.getTokens("alex@outlook.com"))
    }

    @Test
    fun loopbackPkceFlowCompletesAndRejectsStateMismatch() = runBlocking {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv
        var capturedCodeVerifier: String? = null
        srv.createContext("/common/oauth2/v2.0/token") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            val params = body.split('&').associate {
                val idx = it.indexOf('=')
                URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }
            capturedCodeVerifier = params["code_verifier"]
            val resp = """{"access_token":"AT_LOOP_1","refresh_token":"RT_LOOP_1","expires_in":3600}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        srv.createContext("/v1.0/me") { ex ->
            val resp = """{"displayName":"Alex Personal","userPrincipalName":"alex@outlook.com"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        srv.createContext("/v1.0/me/drive") { ex ->
            val resp = """{"id":"DRV123","driveType":"personal","quota":{"total":107374182400,"used":10737418240}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = OneDriveAccountStore(ctx, cipher = FakeXorCipher())
        val auth = OneDriveAuthManager(
            store = store,
            authBaseUrl = "$base/common/oauth2/v2.0",
            graphBaseUrl = "$base/v1.0",
            clientOverride = OkHttpClient(),
        )
        val clientId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        var launchedChallenge: String? = null
        val account = auth.authorizeViaLoopback(
            clientIdInput = clientId,
            aliasLabel = "OneDrive · Home",
            timeoutMillis = 10_000L,
            onLaunchBrowser = { authUrl ->
                val query = URL(authUrl).query.split('&').associate {
                    val idx = it.indexOf('=')
                    URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                }
                val redirectUri = query.getValue("redirect_uri")
                val state = query.getValue("state")
                launchedChallenge = query.getValue("code_challenge")
                Thread {
                    val cb = URL("$redirectUri?code=AUTH_CODE_XYZ&state=$state").openConnection() as HttpURLConnection
                    cb.responseCode
                    cb.disconnect()
                }.start()
            },
        )

        assertEquals("alex@outlook.com", account.accountId)
        assertEquals("OneDrive · Home", account.sidebarTitle)
        assertNotNull(capturedCodeVerifier)
        assertEquals(launchedChallenge, OneDriveAuthManager.computeCodeChallengeS256(capturedCodeVerifier!!))

        // Verify CSRF state mismatch is strictly rejected
        try {
            auth.authorizeViaLoopback(
                clientIdInput = clientId,
                timeoutMillis = 10_000L,
                onLaunchBrowser = { authUrl ->
                    val query = URL(authUrl).query.split('&').associate {
                        val idx = it.indexOf('=')
                        URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                    }
                    val redirectUri = query.getValue("redirect_uri")
                    Thread {
                        val cb = URL("$redirectUri?code=AUTH_CODE_XYZ&state=FORGED_STATE").openConnection() as HttpURLConnection
                        assertEquals(400, cb.responseCode)
                        cb.disconnect()
                    }.start()
                },
            )
            fail("Expected IOException on OAuth state mismatch")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("state mismatch", ignoreCase = true))
        }
    }

    @Test
    fun deviceCodePollingAndSingleFlightConcurrentRefresh() = runBlocking {
        val pollCounter = AtomicInteger(0)
        val refreshCounter = AtomicInteger(0)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv

        srv.createContext("/common/oauth2/v2.0/devicecode") { ex ->
            val resp = """
                {
                  "device_code": "DEV_CODE_1",
                  "user_code": "ABCD-EFGH",
                  "verification_uri": "https://microsoft.com/devicelogin",
                  "expires_in": 600,
                  "interval": 1,
                  "message": "Enter ABCD-EFGH"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        srv.createContext("/common/oauth2/v2.0/token") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            val (code, json) = if (body.contains("device_code")) {
                when (pollCounter.incrementAndGet()) {
                    1 -> 400 to """{"error":"authorization_pending"}"""
                    else -> 200 to """{"access_token":"AT_DEV_1","refresh_token":"RT_DEV_1","expires_in":3600}"""
                }
            } else if (body.contains("refresh_token=REVOKED_RT")) {
                400 to """{"error":"invalid_grant","error_description":"Token has been revoked"}"""
            } else {
                Thread.sleep(60) // widen race window so all 10 threads arrive concurrently
                val n = refreshCounter.incrementAndGet()
                200 to """{"access_token":"AT_REFRESHED_$n","refresh_token":"RT_DEV_2","expires_in":3600}"""
            }
            val bytes = json.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.createContext("/v1.0/me") { ex ->
            val bytes = """{"displayName":"Contoso Work","mail":"alex@contoso.com"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.createContext("/v1.0/me/drive") { ex ->
            val bytes = """{"id":"DRV_BIZ","driveType":"business","quota":{"total":5497558138880,"used":137438953472}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = OneDriveAccountStore(ctx, cipher = FakeXorCipher())
        val auth = OneDriveAuthManager(
            store = store,
            authBaseUrl = "$base/common/oauth2/v2.0",
            graphBaseUrl = "$base/v1.0",
            clientOverride = OkHttpClient(),
        )
        val clientId = "12345678-1234-1234-1234-1234567890ab"
        val session = auth.startDeviceCode(clientId)
        assertEquals("ABCD-EFGH", session.userCode)
        val acc = auth.pollDeviceCode(session, aliasLabel = "", pollIntervalOverrideMillis = 50L)
        assertEquals("alex@contoso.com", acc.accountId)
        assertEquals("OneDrive · Work", acc.sidebarTitle)
        assertTrue(pollCounter.get() >= 2)

        // Expire the access token and fire 10 concurrent threads requesting validAccessToken
        store.updateTokens(
            "alex@contoso.com",
            OneDriveTokens("EXPIRED_AT", "RT_DEV_1", expiresAtMillis = 1_000L),
        )
        val pool = Executors.newFixedThreadPool(10)
        try {
            val results = pool.invokeAll((1..10).map {
                Callable { auth.validAccessToken("alex@contoso.com") }
            }).map { it.get() }
            assertEquals("Expected single-flight token refresh", 1, refreshCounter.get())
            assertTrue(results.all { it == "AT_REFRESHED_1" })
        } finally {
            pool.shutdownNow()
        }

        // Revoked refresh token must throw RemoteAuthException
        store.updateTokens(
            "alex@contoso.com",
            OneDriveTokens("EXPIRED_AT", "REVOKED_RT", expiresAtMillis = 1_000L),
        )
        try {
            auth.invalidateAccessToken("alex@contoso.com")
            auth.validAccessToken("alex@contoso.com")
            fail("Expected RemoteAuthException on invalid_grant")
        } catch (e: RemoteAuthException) {
            assertEquals("onedrive://alex@contoso.com", e.host)
        }
    }
}
