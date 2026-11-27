// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.PrivilegedDocumentsAlias
import com.droid.wayfinder.data.SystemFilesHandoff
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ApkOpenDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var harness: VmHarness? = null

    @After fun tearDown() { harness?.close(); harness = null }

    private fun launch(setup: VmHarness.() -> Unit = {}): VmHarness {
        val h = VmHarness().apply(setup); harness = h
        rule.setContent { WayfinderTheme(dark = false) { AppRoot(h.vm) } }
        rule.waitForIdle(); h.settle(); rule.waitForIdle()
        return h
    }

    private fun idle() { rule.waitForIdle(); harness?.settle(); rule.waitForIdle() }

    private fun registerDirHandler(
        pkg: String,
        activityName: String,
        grantManageDocuments: Boolean,
        action: String = Intent.ACTION_VIEW,
        mimeType: String? = DocumentsContract.Document.MIME_TYPE_DIR,
    ) {
        val pm = shadowOf(rule.activity.packageManager)
        val appInfo = ApplicationInfo().apply { packageName = pkg }
        val actInfo = ActivityInfo().apply {
            packageName = pkg
            name = activityName
            exported = true
            applicationInfo = appInfo
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
            activities = arrayOf(actInfo)
            requestedPermissions = if (grantManageDocuments) arrayOf(Manifest.permission.MANAGE_DOCUMENTS) else emptyArray()
            requestedPermissionsFlags = if (grantManageDocuments) intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED) else IntArray(0)
        }
        pm.installPackage(pkgInfo)
        val component = android.content.ComponentName(pkg, activityName)
        pm.addOrUpdateActivity(actInfo)
        val filter = IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("content")
            if (mimeType != null) addDataType(mimeType)
        }
        pm.addIntentFilterForActivity(component, filter)
    }

    @Test fun openingAndOpenWithOnLocalApkShowDisclosureDialogAndLaunchSystemFilesFolder() {
        val h = launch {
            file("Download/app-release.apk", "apk")
            navigate(pathOf("Download"))
            select("Download/app-release.apk")
        }
        val apkEntry = h.s.active.entries.single { it.name == "app-release.apk" }

        // Double-clicking / pressing Enter on an APK in its folder opens the disclosure dialog (no silent OpenFile).
        h.vm.open(apkEntry); idle()
        val dialog = h.s.dialog as? Dialog.ApkInstallUnsupported
        assertNotNull("Expected ApkInstallUnsupported dialog", dialog)
        assertEquals("app-release.apk", dialog!!.fileName)
        assertNotNull("Local APK should provide containing folder URI", dialog.folderUri)
        assertTrue(h.events.none { it is UiEvent.OpenFile })

        rule.onNodeWithText("App installation isn\u2019t supported").assertIsDisplayed()
        rule.onNodeWithText("Open in Files").assertIsDisplayed()

        // Clicking "Close" dismisses the dialog cleanly.
        rule.onNodeWithText("Close").performClick(); idle()
        assertNull(h.s.dialog)

        // "Open with..." on an APK also displays the disclosure dialog instead of silently failing.
        h.vm.openWith(); idle()
        assertTrue(h.s.dialog is Dialog.ApkInstallUnsupported)
        assertTrue(h.events.none { it is UiEvent.OpenFile })

        // Register both an unprivileged 3P manager and privileged system Files app.
        registerDirHandler("com.thirdparty.files", "com.thirdparty.files.DirActivity", grantManageDocuments = false)
        registerDirHandler("com.google.android.filesapp", "com.google.android.filesapp.FilesActivity", grantManageDocuments = true)

        val shadowActivity = shadowOf(rule.activity)
        while (shadowActivity.nextStartedActivity != null) { /* clear previous intents */ }

        // Clicking "Open in Files" dismisses the dialog and launches ONLY the MANAGE_DOCUMENTS system Files app.
        rule.onNodeWithText("Open in Files").performClick(); idle()
        assertNull(h.s.dialog)
        val launched = shadowActivity.nextStartedActivity
        assertNotNull("Expected system Files activity to launch", launched)
        assertEquals(Intent.ACTION_VIEW, launched!!.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, launched.type)
        assertEquals("com.google.android.filesapp", launched.component?.packageName)
        assertEquals("com.google.android.filesapp.FilesActivity", launched.component?.className)
        assertEquals(0, launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun buildOpenFolderIntentNeverTargetsUnprivilegedThirdPartyAppsOrSelf() {
        launch()
        val folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")

        // Only a 3P file manager without MANAGE_DOCUMENTS -> must return null.
        registerDirHandler("com.thirdparty.manager", "com.thirdparty.manager.Main", grantManageDocuments = false)
        assertNull(SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri))

        // Wayfinder itself with MANAGE_DOCUMENTS -> still excluded.
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        PrivilegedDocumentsAlias.sync(rule.activity)
        assertNull(SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri))
        shadowOf(rule.activity.application).denyPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        PrivilegedDocumentsAlias.sync(rule.activity)

        // Privileged DocumentsUI registered only for ACTION_BROWSE -> resolved via fallback probe.
        registerDirHandler(
            "com.google.android.documentsui",
            "com.android.documentsui.files.FilesActivity",
            grantManageDocuments = true,
            action = SystemFilesHandoff.ACTION_BROWSE,
            mimeType = null,
        )
        val browseIntent = SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri)
        assertNotNull(browseIntent)
        assertEquals(SystemFilesHandoff.ACTION_BROWSE, browseIntent!!.action)
        assertEquals("com.google.android.documentsui", browseIntent.component?.packageName)
        assertFalse((browseIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test fun remoteOrNonLocalApkShowsDisclosureWithoutOpenInFilesButton() {
        val h = launch()
        h.vm.showDialog(Dialog.ApkInstallUnsupported(fileName = "remote.apk", folderUri = null))
        idle()
        rule.onNodeWithText("App installation isn\u2019t supported").assertIsDisplayed()
        rule.onNodeWithText("Open in Files").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick(); idle()
        assertNull(h.s.dialog)
    }
}
