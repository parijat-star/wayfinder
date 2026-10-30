// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.data.GoogleDriveManager
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.SystemDocumentsBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveDiscoveryTest {

    @Test
    fun discoverAccounts_returnsEmptyWithoutContextOrMounts_noHardcodedDeveloperAccounts() {
        val accounts = GoogleDriveManager.discoverAccounts(context = null, mounts = emptyList())
        assertTrue("Expected no hardcoded developer Gmail accounts", accounts.isEmpty())
    }

    @Test
    fun discoverAccounts_discoversAndBindsSafDriveMounts() {
        val mounts = listOf(
            SafMount(
                id = "drv1",
                label = "Drive (alice@example.com) · My Drive",
                uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded",
            ),
            SafMount(
                id = "other1",
                label = "Downloads",
                uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ),
        )
        val accounts = GoogleDriveManager.discoverAccounts(context = null, mounts = mounts)
        assertEquals(1, accounts.size)
        val acc = accounts.single()
        assertEquals("alice@example.com", acc.email)
        assertEquals("Drive", acc.title)
        assertEquals("saf://drv1", acc.mountPath)
        assertTrue(acc.isMounted)
    }

    @Test
    fun buildInitialDriveRootUri_returnsValidTwoSegmentRootUriNeverOneSegmentRootsUri() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val docsPkg = "com.google.android.apps.docs"
        val docsCls = "com.google.android.apps.docs.storagebackend.StorageBackendContentProvider"
        shadowPm.addOrUpdateProvider(
            android.content.pm.ProviderInfo().apply {
                packageName = docsPkg
                name = docsCls
                authority = GoogleDriveManager.DOCS_AUTHORITY
                exported = true
                grantUriPermissions = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = docsPkg }
            },
        )
        shadowPm.addIntentFilterForProvider(
            android.content.ComponentName(docsPkg, docsCls),
            android.content.IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
        )

        // Demonstrate why 1-segment buildRootsUri failed (isRootUri == false -> fallback to last folder),
        // whereas buildInitialDriveRootUri produces a valid 2-segment /root/1 URI (isRootUri == true).
        val brokenRootsUri = DocumentsContract.buildRootsUri(GoogleDriveManager.DOCS_AUTHORITY)
        org.junit.Assert.assertFalse(DocumentsContract.isRootUri(context, brokenRootsUri))

        val uri = GoogleDriveManager.buildInitialDriveRootUri(
            context = context,
            accountEmail = "alain@example.com",
            mounts = emptyList(),
        )
        assertEquals(GoogleDriveManager.DOCS_AUTHORITY, uri.authority)
        assertEquals(listOf("root", "1"), uri.pathSegments)
        assertTrue(
            "DocumentsUI PickActivity requires DocumentsContract.isRootUri == true (/root/<rootId>)",
            DocumentsContract.isRootUri(context, uri),
        )

        // When a first account already mounted acc=1;, a second account picks root/2 instead of colliding
        val existingMounts = listOf(
            SafMount(
                id = "drv1",
                label = "Drive (first@example.com) · Projects",
                uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3B1FolderA",
            ),
        )
        val secondUri = GoogleDriveManager.buildInitialDriveRootUri(
            context = null,
            accountEmail = "second@example.com",
            mounts = existingMounts,
        )
        assertEquals(listOf("root", "2"), secondUri.pathSegments)

        // Re-mounting another folder for the same account reuses its discovered acc=<id>; root
        val sameSecondMounts = existingMounts + SafMount(
            id = "drv2",
            label = "Drive (second@example.com) · Photos",
            uri = "content://com.google.android.apps.docs.storage/tree/acc%3D2%3B1FolderB",
        )
        val remountSecondUri = GoogleDriveManager.buildInitialDriveRootUri(
            context = null,
            accountEmail = "second@example.com",
            mounts = sameSecondMounts,
        )
        assertEquals(listOf("root", "2"), remountSecondUri.pathSegments)
    }

    @Test
    fun discoverAccounts_supportsMultipleMountedDriveFoldersPerAccountWithFolderTitles() {
        val mounts = listOf(
            SafMount(
                id = "drv_proj",
                label = "Drive (alice@example.com) · Projects",
                uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3B1Proj",
            ),
            SafMount(
                id = "drv_photos",
                label = "Drive (alice@example.com) · Photos",
                uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3B1Photos",
            ),
        )
        val accounts = GoogleDriveManager.discoverAccounts(context = null, mounts = mounts)
        assertEquals(2, accounts.size)
        assertEquals(listOf("Drive · Projects", "Drive · Photos"), accounts.map { it.title })
        assertEquals(listOf("saf://drv_proj", "saf://drv_photos"), accounts.map { it.mountPath })
        assertTrue(accounts.all { it.email == "alice@example.com" && it.isMounted })
    }

    @Test
    fun extractEmail_extractsValidAddressAndRejectsNonEmailStrings() {
        assertEquals("user.name+tag@gmail.com", GoogleDriveManager.extractEmail("Drive (user.name+tag@gmail.com) · My Drive"))
        assertEquals("corp@domain.co.uk", GoogleDriveManager.extractEmail("Account: corp@domain.co.uk"))
        assertNull(GoogleDriveManager.extractEmail("Internal storage"))
        assertNull(GoogleDriveManager.extractEmail(null))
    }

    class FakeLoadingDocumentsProvider : ContentProvider() {
        val attempts = AtomicInteger(0)
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val call = attempts.incrementAndGet()
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            return MatrixCursor(cols).apply {
                if (call < 2) {
                    extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                } else {
                    extras = Bundle.EMPTY
                    addRow(arrayOf("doc-1", "Quarterly Report.pdf"))
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun queryWithLoadingWait_retriesWhenCursorReportsExtraLoading() {
        val authority = "com.example.fake.docs"
        val provider = Robolectric.setupContentProvider(FakeLoadingDocumentsProvider::class.java, authority)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.parse("content://$authority/children")

        val rows = SystemDocumentsBridge.queryWithLoadingWait(
            cr = context.contentResolver,
            uri = uri,
            projection = null,
            maxAttempts = 3,
            waitMs = 50L,
        ) { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                names += cursor.getString(1)
            }
            names
        }

        assertEquals(2, provider.attempts.get())
        assertEquals(listOf("Quarterly Report.pdf"), rows)
    }
}
