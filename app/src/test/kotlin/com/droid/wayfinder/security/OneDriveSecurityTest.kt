// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.OneDriveAccount
import com.droid.wayfinder.data.OneDriveAccountStore
import com.droid.wayfinder.data.OneDriveAuthManager
import com.droid.wayfinder.data.OneDriveRestFileSystem
import com.droid.wayfinder.data.OneDriveTokens
import com.droid.wayfinder.data.TokenCipher
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OneDriveSecurityTest {

    private lateinit var ctx: Context
    private var server: HttpServer? = null

    private object NoopTestCipher : TokenCipher {
        override fun encrypt(plain: String): String = "ENC:$plain"
        override fun decrypt(encoded: String): String? = encoded.removePrefix("ENC:").takeIf { encoded.startsWith("ENC:") }
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
    fun hostileDriveItemNamesAndTraversalSegmentsAreRejected() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv
        srv.createContext("/v1.0/me/drive/root/children") { ex ->
            // Hostile Graph listing containing traversal, slashes, NUL, and one legitimate file
            val json = """
                {
                  "value": [
                    {"id": "1", "name": "..", "size": 10},
                    {"id": "2", "name": ".", "size": 10},
                    {"id": "3", "name": "evil/../../etc/passwd", "size": 10},
                    {"id": "4", "name": "bad\u0000name.txt", "size": 10},
                    {"id": "5", "name": "Safe Report (2026).pdf", "size": 4096}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = OneDriveAccountStore(ctx, cipher = NoopTestCipher)
        store.save(
            OneDriveAccount(
                accountId = "sec@outlook.com",
                email = "sec@outlook.com",
                displayName = "Sec",
                driveType = "personal",
                quotaUsed = 10L,
                quotaTotal = 100L,
                clientId = "11111111-2222-3333-4444-555555555555",
            ),
            OneDriveTokens("AT_SEC", "RT_SEC", System.currentTimeMillis() + 3_600_000L),
        )
        val auth = OneDriveAuthManager(
            store = store,
            authBaseUrl = "$base/oauth",
            graphBaseUrl = "$base/v1.0",
            clientOverride = OkHttpClient(),
        )
        val fs = OneDriveRestFileSystem(ctx, auth, graphBaseUrl = "$base/v1.0")

        val listed = fs.list("onedrive://sec@outlook.com")
        assertEquals(listOf("Safe Report (2026).pdf"), listed.map { it.name })

        // Traversal paths in stat/openInput must fail validation cleanly
        assertNull(fs.stat("onedrive://sec@outlook.com/../etc/passwd"))
        try {
            fs.openInput("onedrive://sec@outlook.com/a/../b")
            fail("Expected IOException for dot-dot segment")
        } catch (_: IOException) {}
    }

    @Test
    fun insecureExternalHttpRedirectsUploadUrlsAndCrossOriginNextLinksAreBlocked() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv
        srv.createContext("/v1.0/me/drive/root/children") { ex ->
            // Malicious cross-origin @odata.nextLink attempting SSRF / token exfiltration
            val json = """
                {
                  "value": [{"id": "1", "name": "ok.txt", "size": 1}],
                  "@odata.nextLink": "https://evil.attacker.example/steal-bearer-token"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/v1.0/me/drive/root:/leak.txt:/content") { ex ->
            // Cleartext non-loopback HTTP redirect must be refused
            ex.responseHeaders.add("Location", "http://evil.attacker.example/cleartext.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        srv.createContext("/v1.0/me/drive/root:/big.bin:/createUploadSession") { ex ->
            val json = """{"uploadUrl": "http://evil.attacker.example/upload"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = OneDriveAccountStore(ctx, cipher = NoopTestCipher)
        store.save(
            OneDriveAccount(
                accountId = "sec@outlook.com",
                email = "sec@outlook.com",
                displayName = "Sec",
                driveType = "personal",
                quotaUsed = 10L,
                quotaTotal = 100L,
                clientId = "11111111-2222-3333-4444-555555555555",
            ),
            OneDriveTokens("AT_SEC", "RT_SEC", System.currentTimeMillis() + 3_600_000L),
        )
        val auth = OneDriveAuthManager(
            store = store,
            authBaseUrl = "$base/oauth",
            graphBaseUrl = "$base/v1.0",
            clientOverride = OkHttpClient(),
        )
        val fs = OneDriveRestFileSystem(
            ctx, auth, graphBaseUrl = "$base/v1.0",
            simpleUploadLimitBytes = 10L,
            uploadChunkBytes = 320 * 1024,
        )

        try {
            fs.list("onedrive://sec@outlook.com")
            fail("Expected IOException on cross-origin @odata.nextLink")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Cross-origin", ignoreCase = true))
        }

        try {
            fs.openInput("onedrive://sec@outlook.com/leak.txt")
            fail("Expected IOException on cleartext external download redirect")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Refusing insecure", ignoreCase = true))
        }

        try {
            fs.openOutput("onedrive://sec@outlook.com/big.bin").use {
                it.write(ByteArray(64) { 1 })
            }
            fail("Expected IOException on cleartext external uploadUrl")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Refusing insecure", ignoreCase = true))
        }
    }

    @Test
    fun specialCharactersEncodeWithoutEscapingGraphColonGrammar() {
        val tricky = "Q4: Revenue:/content?overwrite=true#frag"
        val encoded = OneDriveRestFileSystem.encodeGraphSegment(tricky)
        assertFalse(encoded.contains(':'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('?'))
        assertFalse(encoded.contains('#'))
        assertTrue(encoded.contains("%3A"))

        // Parent of OneDrive root is null (never escapes into virtual Network SMB_ROOT)
        assertNull(FsPath.parent("onedrive://alex@outlook.com"))
        assertEquals("onedrive://alex@outlook.com", FsPath.parent("onedrive://alex@outlook.com/Projects"))
    }
}
