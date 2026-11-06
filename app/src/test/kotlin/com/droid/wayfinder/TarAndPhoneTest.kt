// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.ZipFileSystem
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class TarAndPhoneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testFsPathArchiveSplitting() {
        val gzSplit = FsPath.zipSplit("/home/user/archive.tar.gz!/nested/document.txt")
        assertNotNull(gzSplit)
        assertEquals("/home/user/archive.tar.gz", gzSplit!!.first)
        assertEquals("nested/document.txt", gzSplit.second)

        val tgzSplit = FsPath.zipSplit("/data/backup.tgz!")
        assertNotNull(tgzSplit)
        assertEquals("/data/backup.tgz", tgzSplit!!.first)
        assertEquals("", tgzSplit.second)

        val tarSplit = FsPath.zipSplit("/storage/emulated/10/files.tar!/hello.txt")
        assertNotNull(tarSplit)
        assertEquals("/storage/emulated/10/files.tar", tarSplit!!.first)
        assertEquals("hello.txt", tarSplit.second)

        val zipSplit = FsPath.zipSplit("/data/archive.zip!/a/b")
        assertNotNull(zipSplit)
        assertEquals("/data/archive.zip", zipSplit!!.first)
        assertEquals("a/b", zipSplit.second)

        // Non-archive path
        assertEquals(null, FsPath.zipSplit("/home/user/archive.tar.gz"))
    }

    @Test
    fun testFsPathNameAndCrumbs() {
        assertEquals("archive.tar.gz", FsPath.name("/home/user/archive.tar.gz!"))
        assertEquals("document.txt", FsPath.name("/home/user/archive.tar.gz!/nested/document.txt"))
        assertEquals("Phone", FsPath.name(FsPath.PHONE_ROOT))
        assertTrue(FsPath.isPhone(FsPath.PHONE_ROOT))
        assertTrue(FsPath.isVirtual(FsPath.PHONE_ROOT))
        assertTrue(FsPath.isReadOnly(FsPath.PHONE_ROOT))

        val crumbs = FsPath.breadcrumbs(FsPath.PHONE_ROOT, emptyMap())
        assertEquals(1, crumbs.size)
        assertEquals("Phone" to FsPath.PHONE_ROOT, crumbs[0])

        // Subpath navigation under phone://
        assertTrue(FsPath.isPhone("phone://Download"))
        assertTrue(FsPath.isPhone("phone://Pictures/Camera"))
        org.junit.Assert.assertFalse(FsPath.isVirtual("phone://Download"))
        org.junit.Assert.assertFalse(FsPath.isReadOnly("phone://Download"))
        assertEquals("Download", FsPath.name("phone://Download"))
        assertEquals("Camera", FsPath.name("phone://Pictures/Camera"))
        assertEquals(FsPath.PHONE_ROOT, FsPath.parent("phone://Download"))
        assertEquals("phone://Pictures", FsPath.parent("phone://Pictures/Camera"))
        assertTrue(FsPath.isWithin(FsPath.PHONE_ROOT, "phone://Download"))
        assertTrue(FsPath.isWithin("phone://Pictures", "phone://Pictures/Camera"))

        val subCrumbs = FsPath.breadcrumbs("phone://Pictures/Camera", mapOf(FsPath.PHONE_ROOT to "Pixel 9"))
        assertEquals(
            listOf(
                "Pixel 9" to FsPath.PHONE_ROOT,
                "Pictures" to "phone://Pictures",
                "Camera" to "phone://Pictures/Camera",
            ),
            subCrumbs,
        )
    }

    @Test
    fun testPhoneFileSystemFoldersAndCrud() {
        val sandbox = tmp.newFolder("phone_host")
        val pfs = com.droid.wayfinder.data.PhoneFileSystem(null, sandbox)

        // Root lists the 13 standard folders with no "Folder" subtitle underneath
        val rootEntries = pfs.list(FsPath.PHONE_ROOT)
        assertEquals(13, rootEntries.size)
        assertTrue(rootEntries.all { it.isDir && it.subtitle == null })
        val names = rootEntries.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("Download", "Pictures", "DCIM", "Documents", "Music", "Movies", "Android")))

        // Create folder and file inside phone://Documents
        pfs.mkdir("phone://Documents/Notes")
        pfs.createFile("phone://Documents/Notes/todo.txt")
        pfs.openOutput("phone://Documents/Notes/todo.txt").use { it.write("Buy milk".toByteArray()) }

        val notesEntries = pfs.list("phone://Documents/Notes")
        assertEquals(1, notesEntries.size)
        assertEquals("todo.txt", notesEntries[0].name)
        assertEquals(null, notesEntries[0].subtitle)
        assertEquals("Buy milk", pfs.openInput("phone://Documents/Notes/todo.txt").bufferedReader().readText())

        // Rename and delete inside phone://Documents/Notes
        val renamed = pfs.rename("phone://Documents/Notes/todo.txt", "done.txt")
        assertEquals("phone://Documents/Notes/done.txt", renamed)
        assertNotNull(pfs.stat(renamed))
        pfs.delete(renamed)
        assertEquals(null, pfs.stat(renamed))
    }

    @Test
    fun testPhoneFileSystemSecurityInvariants() {
        val sandbox = tmp.newFolder("phone_sec")
        val pfs = com.droid.wayfinder.data.PhoneFileSystem(null, sandbox)

        // Path traversal attempts must throw IOException
        val traversalPaths = listOf(
            "phone://../secret",
            "phone://Download/../../etc/passwd",
            "phone://Pictures/..",
            "phone://Download/a\\b",
            "phone://Download/a\u0000b",
        )
        for (bad in traversalPaths) {
            try {
                pfs.list(bad)
                org.junit.Assert.fail("Expected IOException for $bad")
            } catch (_: java.io.IOException) {}
            assertEquals(null, pfs.stat(bad))
        }

        // Cannot delete or rename system top-level phone folders or PHONE_ROOT
        try {
            pfs.delete(FsPath.PHONE_ROOT)
            org.junit.Assert.fail("Expected IOException deleting PHONE_ROOT")
        } catch (_: java.io.IOException) {}
        try {
            pfs.delete("phone://Download")
            org.junit.Assert.fail("Expected IOException deleting phone://Download")
        } catch (_: java.io.IOException) {}

        // Cannot rename with path traversal name
        pfs.mkdir("phone://Documents/Sub")
        try {
            pfs.rename("phone://Documents/Sub", "../escape")
            org.junit.Assert.fail("Expected IOException on traversal rename")
        } catch (_: java.io.IOException) {}
    }

    @Test
    fun testDriveIdValidationAndSecurity() {
        assertTrue(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId("root"))
        assertTrue(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId("root' or name contains 'secret"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId("../etc/passwd"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId(".."))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId(""))
    }

    @Test
    fun testPhoneDetectorReturnsNullWhenNoPhoneConnected() {
        val pfs = com.droid.wayfinder.data.PhoneFileSystem(null, null)
        assertEquals(null, pfs.resolveRealDirectory())
        // In production mode with no phone connected, PhoneFileSystem must NEVER map to local host storage
        assertTrue(pfs.list(FsPath.PHONE_ROOT).isEmpty())
        try {
            pfs.list("phone://Download")
            org.junit.Assert.fail("Expected IOException when phone storage is disconnected")
        } catch (_: java.io.IOException) {}

        org.junit.Assert.assertFalse(com.droid.wayfinder.data.PhoneDetector.isPhoneMount("Internal Storage"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.PhoneDetector.isPhoneMount("USB 10/100/1G/2.5G LAN"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.PhoneDetector.isPhoneMount("4-Port USB 3.0 Hub"))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.PhoneDetector.isPhoneMount("Dell Pro TBT4 Smart Dock"))
        assertTrue(com.droid.wayfinder.data.PhoneDetector.isPhoneMount("Pixel 9 MTP"))
        assertTrue(com.droid.wayfinder.data.PhoneDetector.isPhoneAuthority(com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY))
        assertTrue(com.droid.wayfinder.data.PhoneDetector.isPhoneAuthority(com.droid.wayfinder.data.PhoneDetector.MTP_AUTHORITY))
        org.junit.Assert.assertFalse(com.droid.wayfinder.data.PhoneDetector.isPhoneAuthority("com.google.android.apps.docs.storage"))

        // PHONE_ROOT virtual placeholder is read-only whereas QuickAccess and MTP document providers support read-write
        assertTrue(FsPath.isReadOnly(FsPath.PHONE_ROOT))
        org.junit.Assert.assertFalse(FsPath.isReadOnly("doc://${com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY}/device123//Download"))
        org.junit.Assert.assertFalse(FsPath.isReadOnly("doc://${com.droid.wayfinder.data.PhoneDetector.MTP_AUTHORITY}/root1/Download"))

        // QuickAccess double-slash documentId preservation and parent resolution
        val qaAuth = com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY
        val qaRoot = "doc://$qaAuth/7e95d900-726a-46d4-ab90-111dd666ba85"
        val qaDownload = "doc://$qaAuth/7e95d900-726a-46d4-ab90-111dd666ba85//Download"
        val qaSub = "doc://$qaAuth/7e95d900-726a-46d4-ab90-111dd666ba85//Download/Nearby Share"
        assertEquals(qaDownload, FsPath.normalize(qaDownload))
        assertEquals(qaDownload, FsPath.parent(qaSub))
        assertEquals(qaRoot, FsPath.parent(qaDownload))
        assertEquals(null, FsPath.parent(qaRoot))
        assertEquals(
            "7e95d900-726a-46d4-ab90-111dd666ba85//Download",
            com.droid.wayfinder.data.SystemDocumentsBridge.normalizeDocId(qaAuth, "7e95d900-726a-46d4-ab90-111dd666ba85/Download")
        )
    }

    @Test
    fun testTarSecurityRejectsTraversalAndSymlinks() = runBlocking {
        val tarGzFile = tmp.newFile("malicious.tar.gz")
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    // 1. Symlink entry pointing outside
                    val symEntry = TarArchiveEntry("evil_link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
                    symEntry.linkName = "/etc/passwd"
                    tos.putArchiveEntry(symEntry)
                    tos.closeArchiveEntry()

                    // 2. Normal file entry
                    val data = "Safe content".toByteArray()
                    val normal = TarArchiveEntry("safe/ok.txt")
                    normal.size = data.size.toLong()
                    tos.putArchiveEntry(normal)
                    tos.write(data)
                    tos.closeArchiveEntry()
                }
            }
        }

        // Browsing ignores the symlink entry
        val zfs = ZipFileSystem()
        val rootPath = FsPath.zipRoot(tarGzFile.path)
        val rootEntries = zfs.list(rootPath)
        assertEquals(listOf("safe"), rootEntries.map { it.name })

        // Extracting ignores the symlink entry and extracts only safe/ok.txt
        val outParent = tmp.newFolder("tar_sec_out")
        val localFs = LocalFileSystem()
        val repo = FileRepository(localFs, localFs, null)
        val ops = FileOperations(repo)
        val extractedDir = ops.extract(tarGzFile.path, outParent.path, onProgress = {})
        org.junit.Assert.assertFalse(File(extractedDir, "evil_link").exists())
        assertTrue(File(extractedDir, "safe/ok.txt").exists())

        // Tar with ../ traversal aborts extraction with IOException
        val slipTar = tmp.newFile("slip.tar.gz")
        FileOutputStream(slipTar).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val data = "pwn".toByteArray()
                    val escape = TarArchiveEntry("../../escape.txt")
                    escape.size = data.size.toLong()
                    tos.putArchiveEntry(escape)
                    tos.write(data)
                    tos.closeArchiveEntry()
                }
            }
        }
        try {
            ops.extract(slipTar.path, outParent.path, onProgress = {})
            org.junit.Assert.fail("Expected IOException for tar-slip")
        } catch (_: java.io.IOException) {}
    }

    @Test
    fun testTarGzBrowsingAndReading() {
        val tarGzFile = tmp.newFile("sample.tar.gz")
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    // Add directory entry
                    val dirEntry = TarArchiveEntry("docs/")
                    tos.putArchiveEntry(dirEntry)
                    tos.closeArchiveEntry()

                    // Add file 1 inside directory
                    val content1 = "Hello, TarGz world!".toByteArray()
                    val fileEntry1 = TarArchiveEntry("docs/readme.txt")
                    fileEntry1.size = content1.size.toLong()
                    tos.putArchiveEntry(fileEntry1)
                    tos.write(content1)
                    tos.closeArchiveEntry()

                    // Add file 2 at root
                    val content2 = "Root file content".toByteArray()
                    val fileEntry2 = TarArchiveEntry("info.log")
                    fileEntry2.size = content2.size.toLong()
                    tos.putArchiveEntry(fileEntry2)
                    tos.write(content2)
                    tos.closeArchiveEntry()
                }
            }
        }

        val zfs = ZipFileSystem()
        val rootPath = FsPath.zipRoot(tarGzFile.path)

        // List root
        val rootEntries = zfs.list(rootPath)
        val names = rootEntries.map { it.name }
        assertTrue(names.contains("docs"))
        assertTrue(names.contains("info.log"))

        // List docs/
        val docsPath = FsPath.child(rootPath, "docs")
        val docsEntries = zfs.list(docsPath)
        assertEquals(1, docsEntries.size)
        assertEquals("readme.txt", docsEntries[0].name)

        // Read docs/readme.txt
        val readmePath = FsPath.child(rootPath, "docs/readme.txt")
        val content = zfs.openInput(readmePath).bufferedReader().readText()
        assertEquals("Hello, TarGz world!", content)

        // Summary
        val summary = ZipFileSystem.summary(tarGzFile)
        assertEquals(3, summary.total)
    }

    @Test
    fun testTarGzExtraction() = runBlocking {
        val tarGzFile = tmp.newFile("package.tar.gz")
        val fileData = "Extract me please!".toByteArray()
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val fileEntry = TarArchiveEntry("subfolder/data.txt")
                    fileEntry.size = fileData.size.toLong()
                    tos.putArchiveEntry(fileEntry)
                    tos.write(fileData)
                    tos.closeArchiveEntry()
                }
            }
        }

        val outParent = tmp.newFolder("output")
        val localFs = LocalFileSystem()
        val repo = FileRepository(localFs, localFs, null)
        val ops = FileOperations(repo)

        val extractedDir = ops.extract(tarGzFile.path, outParent.path, onProgress = {})
        val extractedFile = File(extractedDir, "subfolder/data.txt")
        assertTrue(extractedFile.exists())
        assertEquals("Extract me please!", extractedFile.readText())
    }

    @Test
    fun testFriendlyPhoneAddressBarAndResolution() {
        val phoneRoot = "doc://com.google.ambient.streaming.multidevicesync.quickaccess/7e95d900-726a-46d4-ab90-111dd666ba85"
        val phoneDocs = "$phoneRoot//Documents"
        val phoneNested = "$phoneRoot//Documents/HomeMate"

        assertTrue(com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(phoneRoot))
        assertTrue(com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(phoneDocs))
        assertTrue(com.droid.wayfinder.data.SystemDocumentsBridge.isPhonePath(FsPath.PHONE_ROOT))
        assertEquals("Phone", com.droid.wayfinder.data.SystemDocumentsBridge.fallbackNameOf(phoneRoot))
        assertEquals("Documents", com.droid.wayfinder.data.SystemDocumentsBridge.fallbackNameOf(phoneDocs))
        assertEquals("HomeMate", com.droid.wayfinder.data.SystemDocumentsBridge.fallbackNameOf(phoneNested))
        assertEquals(phoneNested, com.droid.wayfinder.data.SystemDocumentsBridge.childPath(phoneRoot, "Documents/HomeMate"))

        val state = com.droid.wayfinder.ui.AppState(
            roots = listOf(com.droid.wayfinder.ui.RootVolume("/storage/emulated/10", "Internal storage", removable = false)),
            pairedPhone = com.droid.wayfinder.data.PairedPhone(
                name = "Pixel 8a",
                subtitle = "Internal Storage",
                path = phoneRoot,
                isConnected = true,
                authority = com.droid.wayfinder.data.PhoneDetector.QUICK_ACCESS_AUTHORITY,
                isMounted = true,
            ),
        )

        assertEquals("Pixel 8a", state.displayPath(phoneRoot, "/"))
        assertEquals("Pixel 8a/Documents", state.displayPath(phoneDocs, "/"))
        assertEquals("Pixel 8a / Documents / HomeMate", state.displayPath(phoneNested, " / "))
        assertEquals("Internal storage / Download", state.displayPath("/storage/emulated/10/Download", " / "))

        assertEquals(phoneRoot, com.droid.wayfinder.ui.resolveAddress("Pixel 8a", state))
        assertEquals(phoneDocs, com.droid.wayfinder.ui.resolveAddress("Pixel 8a/Documents", state))
        assertEquals(phoneNested, com.droid.wayfinder.ui.resolveAddress("Phone/Documents/HomeMate", state))
        assertEquals("/storage/emulated/10/Download", com.droid.wayfinder.ui.resolveAddress("Internal storage/Download", state))
    }
}
