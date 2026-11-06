// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
