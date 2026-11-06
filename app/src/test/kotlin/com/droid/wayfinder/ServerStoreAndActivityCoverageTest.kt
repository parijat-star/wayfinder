// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.accounts.Account
import android.accounts.AccountManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.data.GoogleDriveManager
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.SavedServer
import com.droid.wayfinder.data.SecretBox
import com.droid.wayfinder.data.ServerStore
import com.droid.wayfinder.data.SmbCred
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.PointerSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerStoreAndActivityCoverageTest {

    class FakeAndroidKeyStoreSpi : KeyStoreSpi() {
        companion object {
            val keys = HashMap<String, SecretKey>()
            fun ensureKey(alias: String): SecretKey = keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }

        override fun engineGetKey(alias: String, password: CharArray?): Key? = ensureKey(alias)
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            if (key is SecretKey) keys[alias] = key
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
        override fun engineDeleteEntry(alias: String?) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String?): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String?): Boolean = true
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    private class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "Fake AndroidKeyStore for JVM unit tests") {
        init {
            put("KeyStore.AndroidKeyStore", FakeAndroidKeyStoreSpi::class.java.name)
        }
    }

    @Before
    fun installKeyStoreProvider() {
        FakeAndroidKeyStoreSpi.keys.clear()
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(FakeAndroidKeyStoreProvider())
        }
    }

    @After
    fun removeKeyStoreProvider() {
        Security.removeProvider("AndroidKeyStore")
    }

    @Test
    fun secretBoxAndServerStoreFullLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val box = SecretBox("test_wayfinder_key")

        // AES-GCM round-trip and randomized IV check
        val ct1 = box.encrypt("s3cr3t-p@ss")
        val ct2 = box.encrypt("s3cr3t-p@ss")
        assertNotEquals("GCM IV must be randomized per encryption", ct1, ct2)
        assertEquals("s3cr3t-p@ss", box.decrypt(ct1))
        assertEquals("s3cr3t-p@ss", box.decrypt(ct2))

        // Corrupted or truncated ciphertext returns null
        assertNull(box.decrypt("not-base64!!!"))
        assertNull(box.decrypt("AAAA"))

        // ServerStore CRUD: save, get (session & persisted), put, setMounted, putSession, forget
        val store = ServerStore(context, box)
        val srv = SavedServer(
            key = "nas.local",
            user = "alice",
            domain = "CORP",
            hasPassword = true,
            protocol = Protocol.SMB,
            host = "nas.local",
            port = 445,
            path = "media/movies",
            alias = "",
            guest = false,
            mounted = true,
        )
        assertEquals("smb://nas.local/media/movies", srv.rootPath)
        assertEquals("movies on nas.local", srv.label)

        store.save(srv, SmbCred("alice", "hunter2", "CORP"), remember = true)
        assertEquals(SmbCred("alice", "hunter2", "CORP"), store.get("nas.local"))

        // Unmounting clears in-memory session credential, so subsequent get() decrypts from SharedPreferences
        store.setMounted("nas.local", false)
        val savedAfterUnmount = store.saved().single { it.key == "nas.local" }
        assertFalse(savedAfterUnmount.mounted)
        assertEquals(SmbCred("alice", "hunter2", "CORP"), store.get("nas.local"))

        // Remount and update credentials via put()
        store.setMounted("nas.local", true)
        store.put("nas.local", SmbCred("bob", "newpass", "HOME"), remember = true)
        assertEquals(SmbCred("bob", "newpass", "HOME"), store.get("nas.local"))

        // put() for a brand-new WebDAV server key
        store.put("davs://cloud.example.com:8443", SmbCred("carol", "davpass"), remember = false)
        assertEquals(SmbCred("carol", "davpass"), store.get("davs://cloud.example.com:8443"))

        // Session-only credential and forget()
        store.putSession("ftp://ftp.example.com:21", SmbCred("anon", ""))
        assertEquals(SmbCred("anon", ""), store.get("ftp://ftp.example.com:21"))

        store.forget("nas.local")
        assertNull(store.get("nas.local"))
        assertFalse(store.saved().any { it.key == "nas.local" })
    }

    @Test
    fun googleDriveManagerDiscoversAccountsAndMatchesSafMounts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val am = AccountManager.get(context)
        shadowOf(am).addAccount(Account("team.user@gmail.com", "com.google"))

        val mounts = listOf(
            SafMount(
                id = "drive_1",
                label = "Google Drive - team.user@gmail.com",
                uri = "content://com.google.android.apps.docs.storage/tree/root",
            ),
        )
        val accounts = GoogleDriveManager.discoverAccounts(context, mounts)
        assertTrue(accounts.any { it.email == "team.user@gmail.com" && it.isMounted && it.mountPath == "saf://drive_1" })
    }

    @Test
    @Config(sdk = [34])
    fun mainActivityIntentPreparationPermissionScreenAndTouchDispatch() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        MainActivity.hasAccessOverride = { false }
        try {
            // Verify IntentGuard sign() preserves internal extras while untrusted sanitize() strips them
            val signedIntent = IntentGuard.sign(
                app,
                Intent(app, MainActivity::class.java).putExtra(BrowserViewModel.EXTRA_PATH, "/storage/emulated/0"),
            )
            assertEquals("/storage/emulated/0", IntentGuard.sanitize(app, signedIntent).getStringExtra(BrowserViewModel.EXTRA_PATH))

            // 1. Picker intent (ACTION_GET_CONTENT) with MIME types and allow-multiple
            val pickerIntent = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_GET_CONTENT
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/pdf"))
                // Untrusted internal extra from external caller should be stripped by IntentGuard first
                putExtra(BrowserViewModel.EXTRA_PATH, "/data/data/com.droid.wayfinder")
            }

            Robolectric.buildActivity(MainActivity::class.java, pickerIntent).setup().use { controller ->
                val activity = controller.get()
                val sanitized = activity.intent
                assertTrue(sanitized.getBooleanExtra(BrowserViewModel.EXTRA_PICKER, false))
                assertTrue(sanitized.getBooleanExtra(BrowserViewModel.EXTRA_PICKER_MULTIPLE, false))
                assertArrayEquals(
                    arrayOf("image/png", "application/pdf"),
                    sanitized.getStringArrayExtra(BrowserViewModel.EXTRA_PICKER_MIME),
                )
                assertNull(sanitized.getStringExtra(BrowserViewModel.EXTRA_PATH))

                // Dispatch mouse MotionEvent to cover dispatchTouchEvent -> PointerSource.update
                val now = SystemClock.uptimeMillis()
                val mouseEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                activity.dispatchTouchEvent(mouseEvent)
                assertTrue(PointerSource.precise)
                mouseEvent.recycle()

                // Invoke private requestAccess() and onRequestPermissionsResult()
                val reqAccess = MainActivity::class.java.getDeclaredMethod("requestAccess").apply { isAccessible = true }
                reqAccess.invoke(activity)
                val started = shadowOf(activity).nextStartedActivity
                assertNotNull(started)
                assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)

                activity.onRequestPermissionsResult(1, emptyArray(), IntArray(0))
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }

            // 2. ACTION_VIEW_DOWNLOADS intent with storage access granted
            MainActivity.hasAccessOverride = { true }
            java.io.File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).mkdirs()
            val downloadsIntent = Intent(app, MainActivity::class.java).apply {
                action = DownloadManager.ACTION_VIEW_DOWNLOADS
            }
            Robolectric.buildActivity(MainActivity::class.java, downloadsIntent).setup().use { controller ->
                val activity = controller.get()
                assertTrue(activity.intent.getBooleanExtra(BrowserViewModel.EXTRA_NEW_WINDOW, false))
                val expectedDownloads = java.io.File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).path
                assertEquals(expectedDownloads, activity.intent.getStringExtra(BrowserViewModel.EXTRA_PATH))
            }
        } finally {
            MainActivity.hasAccessOverride = null
        }
    }

    @Test
    @Config(sdk = [34])
    fun mainActivityRequestAccessApi30PlusLaunchesManageAppAllFilesSettings() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val reqAccess = MainActivity::class.java.getDeclaredMethod("requestAccess").apply { isAccessible = true }
        reqAccess.invoke(activity)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)
    }
}
