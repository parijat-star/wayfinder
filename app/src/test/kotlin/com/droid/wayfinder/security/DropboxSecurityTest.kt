// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.DropboxOAuthCallbackActivity
import com.droid.wayfinder.data.DropboxAccount
import com.droid.wayfinder.data.DropboxAccountStore
import com.droid.wayfinder.data.DropboxAuthManager
import com.droid.wayfinder.data.DropboxOAuthCallbackBus
import com.droid.wayfinder.data.DropboxRestFileSystem
import com.droid.wayfinder.data.DropboxTokens
import com.droid.wayfinder.data.FsPath
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
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DropboxSecurityTest {

    private lateinit var ctx: Context
    private var server: HttpServer? = null

    private object TestCipher : TokenCipher {
        override fun encrypt(plain: String): String = "ENC:$plain"
        override fun decrypt(encoded: String): String? = encoded.removePrefix("ENC:").takeIf { encoded.startsWith("ENC:") }
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
    fun hostileEntriesDeletedTagsTraversalSegmentsAndDownloadRedirectsAreBlocked() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = srv
        srv.createContext("/2/files/list_folder") { ex ->
            val json = """
                {
                  "entries": [
                    {".tag": "file", "name": "..", "size": 10},
                    {".tag": "file", "name": ".", "size": 10},
                    {".tag": "file", "name": "evil/../../etc/passwd", "size": 10},
                    {".tag": "file", "name": "bad\u0000name.txt", "size": 10},
                    {".tag": "deleted", "name": "removed.txt"},
                    {".tag": "file", "name": "Clean Spec (2026).pdf", "size": 8192}
                  ],
                  "has_more": false
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/2/files/download") { ex ->
            ex.responseHeaders.add("Location", "http://evil.attacker.example/steal-token")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"

        val store = DropboxAccountStore(ctx, cipher = TestCipher)
        store.save(
            DropboxAccount(
                accountId = "sec@dropbox.example",
                email = "sec@dropbox.example",
                appKey = "wayfinderappkey1",
            ),
            DropboxTokens("AT_SEC", "RT_SEC", System.currentTimeMillis() + 3_600_000L),
        )
        val auth = DropboxAuthManager(store = store, apiBaseUrl = base, clientOverride = OkHttpClient())
        val fs = DropboxRestFileSystem(
            context = ctx,
            store = store,
            auth = auth,
            apiBaseUrl = base,
            contentBaseUrl = base,
        )

        val listed = fs.list("dropbox://sec@dropbox.example")
        assertEquals(listOf("Clean Spec (2026).pdf"), listed.map { it.name })

        assertNull(fs.stat("dropbox://sec@dropbox.example/../etc/passwd"))
        try {
            fs.openInput("dropbox://sec@dropbox.example/a/../b")
            fail("Expected IOException on dot-dot segment")
        } catch (_: IOException) {}

        try {
            fs.openInput("dropbox://sec@dropbox.example/Clean Spec (2026).pdf")
            fail("Expected IOException on unexpected HTTP redirect")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Refusing unexpected redirect", ignoreCase = true))
        }

        // Parent of Dropbox root is null (never escapes into virtual Network SMB_ROOT)
        assertNull(FsPath.parent("dropbox://sec@dropbox.example"))
        assertEquals("dropbox://sec@dropbox.example", FsPath.parent("dropbox://sec@dropbox.example/Projects"))
    }

    @Test
    fun unsolicitedOAuthCallbackIntentsAreRejectedAndFinishImmediately() {
        assertFalse(
            DropboxOAuthCallbackBus.deliver(
                Uri.parse("com.droid.wayfinder.dropbox://oauth?code=INJECTED&state=SPOOFED"),
            ),
        )
        assertFalse(
            DropboxOAuthCallbackBus.deliver(
                Uri.parse("https://evil.example/oauth?code=INJECTED&state=SPOOFED"),
            ),
        )

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("com.droid.wayfinder.dropbox://oauth?code=EVIL&state=NONE"))
        Robolectric.buildActivity(DropboxOAuthCallbackActivity::class.java, intent).use { controller ->
            controller.create()
            assertTrue("Callback activity must finish immediately without UI", controller.get().isFinishing)
        }
    }

    @Test
    fun dropboxSourcesCarryApacheHeaderNeverEmbedClientSecretAndExcludeBackups() {
        val root = sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/build.gradle.kts").isFile }
        val dropboxSources = listOf(
            File(root, "app/src/main/kotlin/com/droid/wayfinder/DropboxOAuthCallbackActivity.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/DropboxAccountStore.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/DropboxAuthManager.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/DropboxRestFileSystem.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/ui/DropboxDialog.kt"),
        )
        for (src in dropboxSources) {
            val text = src.readText()
            assertTrue("${src.name} must carry Apache-2.0 SPDX header", text.contains("SPDX-License-Identifier: Apache-2.0"))
            assertFalse("${src.name} must never embed an OAuth client_secret", text.contains("client_secret", ignoreCase = true))
        }
        assertTrue(File(root, "app/src/main/res/xml/backup_rules.xml").readText().contains("dropbox_accounts.xml"))
        assertTrue(File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText().contains("dropbox_accounts.xml"))
        assertTrue(File(root, "PRIVACY.md").readText().contains("Dropbox"))
        assertTrue(File(root, "docs/privacy.html").readText().contains("Dropbox"))
    }
}
