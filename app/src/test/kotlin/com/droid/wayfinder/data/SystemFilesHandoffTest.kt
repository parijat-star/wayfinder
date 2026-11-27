// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SystemFilesHandoffTest {

    @Test
    fun isApkFileDetectsApkExtensionsOnly() {
        assertTrue(SystemFilesHandoff.isApkFile("/storage/emulated/0/Download/app.apk"))
        assertTrue(SystemFilesHandoff.isApkFile("Wayfinder-debug.APK"))
        assertTrue(SystemFilesHandoff.isApkFile("smb://nas/share/tool.apk"))
        assertFalse(SystemFilesHandoff.isApkFile(".apk"))
        assertFalse(SystemFilesHandoff.isApkFile("/storage/emulated/0/.apk"))
        assertFalse(SystemFilesHandoff.isApkFile("bundle.apks"))
        assertFalse(SystemFilesHandoff.isApkFile("bundle.xapk"))
        assertFalse(SystemFilesHandoff.isApkFile("archive.zip"))
        assertFalse(SystemFilesHandoff.isApkFile(""))
        assertFalse(SystemFilesHandoff.isApkFile("app.apk\u0000"))
    }

    @Test
    fun eligibleParentFolderAcceptsSharedRemovableSafAndDocAndRejectsRemoteArchivesAndPrivatePaths() {
        val primary = "/storage/emulated/10"
        assertEquals(
            "/storage/emulated/10/Download",
            SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/Download/app.apk", primary),
        )
        assertEquals(
            "/storage/emulated/10",
            SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/app.apk", primary),
        )
        assertEquals(
            "/storage/ABCD-1234/apks",
            SystemFilesHandoff.eligibleParentFolder("/storage/ABCD-1234/apks/app.apk", primary),
        )
        assertEquals(
            "saf://mount1/builds",
            SystemFilesHandoff.eligibleParentFolder("saf://mount1/builds/app.apk", primary),
        )
        assertEquals(
            "doc://com.example.docs/folder",
            SystemFilesHandoff.eligibleParentFolder("doc://com.example.docs/folder/app.apk", primary),
        )

        // Rejected locations:
        assertNull(SystemFilesHandoff.eligibleParentFolder("smb://nas/share/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("webdav://host/files/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("ftp://host/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("onedrive://acc/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/bundle.zip!/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/data/user/10/com.droid.wayfinder/files/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/0/Download/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/self/primary/app.apk", primary))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/Download/app.apk\u0000", primary))
    }

    @Test
    fun selectPrivilegedFilesHandlerRequiresManageDocumentsExcludesSelfAndPrefersSystemFiles() {
        val selfPkg = "com.droid.wayfinder"

        // Unprivileged 3P apps without MANAGE_DOCUMENTS are never chosen.
        val unprivilegedOnly = listOf(
            FilesActivityCandidate("com.thirdparty.explorer", "com.thirdparty.explorer.Main", holdsManageDocuments = false),
            FilesActivityCandidate("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", holdsManageDocuments = false),
        )
        assertNull(SystemFilesHandoff.selectPrivilegedFilesHandler(unprivilegedOnly, selfPkg))

        // Wayfinder itself is excluded even if MANAGE_DOCUMENTS is granted (system/dev install).
        val selfOnly = listOf(
            FilesActivityCandidate(selfPkg, "com.droid.wayfinder.DocumentsBrowseAlias", holdsManageDocuments = true),
        )
        assertNull(SystemFilesHandoff.selectPrivilegedFilesHandler(selfOnly, selfPkg))

        // Prefers com.google.android.documentsui over com.android.documentsui over generic OEM package.
        val mixed = listOf(
            FilesActivityCandidate(selfPkg, "com.droid.wayfinder.DocumentsBrowseAlias", holdsManageDocuments = true),
            FilesActivityCandidate("com.thirdparty.files", "com.thirdparty.files.Browse", holdsManageDocuments = false),
            FilesActivityCandidate("com.oem.systemfiles", "com.oem.systemfiles.Main", holdsManageDocuments = true),
            FilesActivityCandidate("com.android.documentsui", "com.android.documentsui.files.FilesActivity", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", holdsManageDocuments = true),
        )
        val chosen = SystemFilesHandoff.selectPrivilegedFilesHandler(mixed, selfPkg)
        assertNotNull(chosen)
        assertEquals("com.google.android.documentsui", chosen!!.packageName)

        // Without Google DocumentsUI, prefers AOSP com.android.documentsui over generic OEM package.
        val withoutGoogleDocUi = mixed.filterNot { it.packageName == "com.google.android.documentsui" }
        assertEquals(
            "com.android.documentsui",
            SystemFilesHandoff.selectPrivilegedFilesHandler(withoutGoogleDocUi, selfPkg)?.packageName,
        )

        // Blank or NUL-injected names are rejected even with MANAGE_DOCUMENTS.
        val malformed = listOf(
            FilesActivityCandidate("", "com.example.Main", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui", "", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui\u0000", "com.example.Main", holdsManageDocuments = true),
        )
        assertNull(SystemFilesHandoff.selectPrivilegedFilesHandler(malformed, selfPkg))
    }

    @Test
    fun fuzzPrivilegedHandlerSelectionAndPathEligibility() {
        val rng = Random(2026112602L)
        val selfPkg = "com.droid.wayfinder"
        val samplePkgs = listOf(
            selfPkg,
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.thirdparty.manager",
            "com.oem.files",
            "",
            "bad\u0000pkg",
        )
        repeat(1_000) {
            val candidates = List(rng.nextInt(0, 8)) {
                FilesActivityCandidate(
                    packageName = samplePkgs[rng.nextInt(samplePkgs.size)],
                    activityName = if (rng.nextInt(5) == 0) "" else "Activity${rng.nextInt(10)}",
                    holdsManageDocuments = rng.nextBoolean(),
                )
            }
            val selected = SystemFilesHandoff.selectPrivilegedFilesHandler(candidates, selfPkg)
            if (selected != null) {
                assertTrue(selected.holdsManageDocuments)
                assertTrue(selected.packageName != selfPkg)
                assertTrue(selected.packageName.isNotBlank())
                assertTrue(selected.activityName.isNotBlank())
                assertFalse(selected.packageName.contains('\u0000'))
            }
        }
    }
}
