// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.PrivilegedDocumentsAlias
import com.droid.wayfinder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestAndPrivacyPolicyTest {

    @Test
    fun manifestOmitsRestrictedAndLegacyPermissions_andDeclaresBluetoothNeverForLocation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertFalse("READ_CONTACTS must not be requested", Manifest.permission.READ_CONTACTS in requested)
        assertFalse("GET_ACCOUNTS must not be requested", Manifest.permission.GET_ACCOUNTS in requested)
        assertFalse("REQUEST_INSTALL_PACKAGES must not be requested", Manifest.permission.REQUEST_INSTALL_PACKAGES in requested)
        assertFalse("Legacy READ_EXTERNAL_STORAGE must not be requested", Manifest.permission.READ_EXTERNAL_STORAGE in requested)
        assertFalse("Legacy WRITE_EXTERNAL_STORAGE must not be requested", Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
        assertTrue("MANAGE_EXTERNAL_STORAGE must remain declared", Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested)
        assertTrue("BLUETOOTH_CONNECT must remain declared", Manifest.permission.BLUETOOTH_CONNECT in requested)
        assertTrue(
            "MANAGE_DOCUMENTS must remain declared for optional ADB direct-mount grants",
            Manifest.permission.MANAGE_DOCUMENTS in requested,
        )

        val manifestFile = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val manifestText = manifestFile.readText()
        assertTrue(
            "BLUETOOTH_CONNECT must declare neverForLocation",
            Regex("""<uses-permission[^>]*BLUETOOTH_CONNECT[^>]*neverForLocation""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(manifestText),
        )
        assertTrue(
            "android.hardware.bluetooth must be marked android:required=\"false\" so Play Store device catalog does not require Bluetooth hardware",
            Regex("""<uses-feature[^>]*android\.hardware\.bluetooth[^>]*android:required="false"""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(manifestText),
        )
        assertTrue(
            "Manifest <queries> must include DocumentsUI visibility",
            manifestText.contains("com.google.android.documentsui") &&
                manifestText.contains("android.intent.action.OPEN_DOCUMENT_TREE"),
        )
        assertFalse(
            "requestLegacyExternalStorage should be removed",
            manifestText.contains("requestLegacyExternalStorage"),
        )
    }

    @Test
    fun mainActivityDoesNotAdvertiseOpenDocumentTreeIntentFilter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolved = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
            0,
        )
        assertFalse(
            "Standalone 3P app without DocumentsProvider should not intercept ACTION_OPEN_DOCUMENT_TREE",
            resolved.any { it.activityInfo?.packageName == context.packageName },
        )
    }

    @Test
    fun privilegedDocumentsIntentsDisabledByDefault_andEnabledOnlyWithManageDocuments() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val docUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        val privilegedIntents = listOf(
            Intent("android.provider.action.BROWSE", docUri),
            Intent("android.provider.action.MANAGE_DOCUMENT", docUri),
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, "vnd.android.document/directory"),
        )

        // Default install (Google Play / user build without MANAGE_DOCUMENTS):
        // Wayfinder must NOT intercept privileged DocumentsContract intents.
        assertFalse(PrivilegedDocumentsAlias.hasManageDocuments(app))
        for (intent in privilegedIntents) {
            val resolved = app.packageManager.queryIntentActivities(intent, 0)
            assertFalse(
                "Unprivileged install must not intercept ${intent.action} (${intent.type})",
                resolved.any { it.activityInfo?.packageName == app.packageName },
            )
        }
        assertFalse(PrivilegedDocumentsAlias.sync(app))

        // Non-privileged folder viewing intents still resolve to MainActivity on Play installs.
        val safeFolderIntents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/Download"), "resource/folder"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/Download"), "vnd.android.document/directory"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://example/folder"), "resource/folder"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://example/folder"), "inode/directory"),
        )
        for (intent in safeFolderIntents) {
            val resolved = app.packageManager.queryIntentActivities(intent, 0)
            assertTrue(
                "MainActivity must still resolve safe folder intent ${intent.data} (${intent.type})",
                resolved.any {
                    it.activityInfo?.packageName == app.packageName &&
                        it.activityInfo?.name == MainActivity::class.java.name
                },
            )
        }

        // System app / ADB dev install with MANAGE_DOCUMENTS granted:
        // syncing enables the alias so Wayfinder handles ACTION_BROWSE / MANAGE_DOCUMENT / content dir VIEW.
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        assertTrue(PrivilegedDocumentsAlias.hasManageDocuments(app))
        assertTrue(PrivilegedDocumentsAlias.sync(app))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            app.packageManager.getComponentEnabledSetting(PrivilegedDocumentsAlias.componentName(app)),
        )
        for (intent in privilegedIntents) {
            val resolved = app.packageManager.queryIntentActivities(intent, 0)
            assertTrue(
                "Privileged install with MANAGE_DOCUMENTS must resolve ${intent.action} (${intent.type})",
                resolved.any {
                    it.activityInfo?.packageName == app.packageName &&
                        it.activityInfo?.name == PrivilegedDocumentsAlias.ALIAS_CLASS_NAME
                },
            )
        }

        // Revoking MANAGE_DOCUMENTS disables the alias again.
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        assertFalse(PrivilegedDocumentsAlias.sync(app))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            app.packageManager.getComponentEnabledSetting(PrivilegedDocumentsAlias.componentName(app)),
        )
        for (intent in privilegedIntents) {
            val resolved = app.packageManager.queryIntentActivities(intent, 0)
            assertFalse(
                "Revoking MANAGE_DOCUMENTS must stop intercepting ${intent.action} (${intent.type})",
                resolved.any { it.activityInfo?.packageName == app.packageName },
            )
        }
    }

    @Test
    fun mainActivityDoesNotInterceptApkViewIntents_butKeepsQueriesVisibility() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (uri in listOf(Uri.parse("content://downloads/app.apk"), Uri.parse("file:///storage/emulated/0/Download/app.apk"))) {
            val resolved = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, MainActivity.APK_MIME),
                0,
            )
            assertFalse(
                "MainActivity must not register ACTION_VIEW for application/vnd.android.package-archive ($uri)",
                resolved.any { it.activityInfo?.packageName == context.packageName },
            )
        }

        val manifestFile = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val manifestText = manifestFile.readText()
        assertTrue(
            "Manifest <queries> must retain application/vnd.android.package-archive visibility for launching external installers",
            Regex(
                """<queries>.*?application/vnd\.android\.package-archive.*?</queries>""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(manifestText),
        )
    }

    @Test
    fun mainActivityResolvesTgzAndGzipArchiveViewIntents() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mainActivityName = MainActivity::class.java.name
        val archiveIntents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://downloads/release.tgz"), "application/x-gtar-compressed"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/Download/release.tgz"), "application/x-gtar-compressed"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://downloads/source.tar.gz"), "application/gzip"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/Download/source.tar.gz"), "application/gzip"),
        )
        for (intent in archiveIntents) {
            val resolved = context.packageManager.queryIntentActivities(intent, 0)
            assertTrue(
                "MainActivity must resolve ACTION_VIEW for ${intent.type} (${intent.data})",
                resolved.any {
                    it.activityInfo?.packageName == context.packageName &&
                        it.activityInfo?.name == mainActivityName
                },
            )
        }
    }

    @Test
    fun backupAndDataExtractionRulesExcludeSettingsSharedPrefs_andPrivacyPolicyExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backupXml = readXmlResourceText(context, R.xml.backup_rules)
        val extractionXml = readXmlResourceText(context, R.xml.data_extraction_rules)

        assertTrue("backup_rules.xml must exclude settings.xml", backupXml.contains("settings.xml"))
        assertTrue("data_extraction_rules.xml must exclude settings.xml", extractionXml.contains("settings.xml"))

        val privacyFile = sequenceOf(File("PRIVACY.md"), File("../PRIVACY.md")).firstOrNull { it.isFile }
        assertTrue("PRIVACY.md must exist at repository root", privacyFile != null)
        val content = privacyFile!!.readText()
        assertTrue(content.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(content.contains("BLUETOOTH_CONNECT"))
    }

    private fun readXmlResourceText(context: android.content.Context, resId: Int): String {
        val sb = StringBuilder()
        context.resources.getXml(resId).use { parser ->
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                for (i in 0 until parser.attributeCount) {
                    sb.append(parser.getAttributeName(i)).append('=').append(parser.getAttributeValue(i)).append(' ')
                }
                event = parser.next()
            }
        }
        return sb.toString()
    }
}
