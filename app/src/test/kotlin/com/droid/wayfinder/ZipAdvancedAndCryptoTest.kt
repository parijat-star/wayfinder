// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.AesStrength
import com.droid.wayfinder.data.CacheJanitor
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FileSystem
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.WinZipAesCtrCipher
import com.droid.wayfinder.data.ZipCompressOptions
import com.droid.wayfinder.data.ZipCompressionLevel
import com.droid.wayfinder.data.ZipCrypto
import com.droid.wayfinder.data.ZipCryptoEngine
import com.droid.wayfinder.data.ZipEntryEncryption
import com.droid.wayfinder.data.ZipFileSystem
import com.droid.wayfinder.data.ZipPasswordRequiredException
import com.droid.wayfinder.data.ZipWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile as JdkZipFile
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ZipAdvancedAndCryptoTest {
    private lateinit var sandbox: File
    private lateinit var repo: FileRepository
    private lateinit var ops: FileOperations

    @Before
    fun setUp() {
        sandbox = Files.createTempDirectory("wf-zip-adv").toFile().canonicalFile
        repo = FileRepository(LocalFileSystem(), LocalFileSystem(), null)
        ops = FileOperations(repo)
    }

    @After
    fun tearDown() {
        FileInfo.deleteTree(sandbox)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ PBKDF2 RFC 6070 vectors

    @Test
    fun pbkdf2HmacSha1MatchesRfc6070Vectors() {
        assertEquals(
            "0c60c80f961f0e71f3a9b524af6012062fe037a6",
            hex(ZipCrypto.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 1, 20)),
        )
        assertEquals(
            "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957",
            hex(ZipCrypto.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 2, 20)),
        )
        assertEquals(
            "4b007901b765489abead49d926f721d065a429c1",
            hex(ZipCrypto.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 4096, 20)),
        )
        assertEquals(
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(
                ZipCrypto.pbkdf2HmacSha1(
                    "passwordPASSWORDpassword".toByteArray(),
                    "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(),
                    4096,
                    25,
                ),
            ),
        )
        // Long password (> 64-byte SHA-1 block size) & multi-block output (66 bytes for AES-256).
        val derived = ZipCrypto.pbkdf2HmacSha1(("long-🔑-" + "a".repeat(80)).toByteArray(Charsets.UTF_8), ByteArray(16) { it.toByte() }, 1000, 66)
        assertEquals(66, derived.size)
    }

    // ------------------------------------------------------------------ Compression levels (unencrypted)

    @Test
    fun compressionLevelsProduceExpectedMethodsSizesAndStandardCompatibility() = runBlocking {
        val srcDir = File(sandbox, "src").apply { mkdirs() }
        val compressible = buildString {
            repeat(2000) { i -> append("Wayfinder compression test line #$i — the quick brown fox jumps over the lazy dog.\n") }
        }.toByteArray(Charsets.UTF_8)
        val binary = ByteArray(16_384) { ((it * 31 + 17) and 0xFF).toByte() }
        File(srcDir, "text.txt").writeBytes(compressible)
        File(srcDir, "data.bin").writeBytes(binary)
        File(srcDir, "empty.txt").writeBytes(ByteArray(0))

        val sizes = mutableMapOf<ZipCompressionLevel, Long>()
        for (level in ZipCompressionLevel.entries) {
            val zipFile = File(sandbox, "out-${level.name}.zip")
            ops.compress(
                sources = listOf(srcDir.path),
                zipPath = zipFile.path,
                onProgress = {},
                options = ZipCompressOptions(level = level),
            )
            sizes[level] = zipFile.length()

            // Verify standard java.util.zip.ZipFile reads every entry cleanly.
            JdkZipFile(zipFile).use { jf ->
                val textEntry = jf.getEntry("src/text.txt")
                assertEquals(
                    if (level == ZipCompressionLevel.None) java.util.zip.ZipEntry.STORED else java.util.zip.ZipEntry.DEFLATED,
                    textEntry.method,
                )
                if (level == ZipCompressionLevel.None) {
                    assertEquals(compressible.size.toLong(), textEntry.compressedSize)
                }
                assertArrayEquals(compressible, jf.getInputStream(textEntry).readBytes())
                assertArrayEquals(binary, jf.getInputStream(jf.getEntry("src/data.bin")).readBytes())
                assertEquals(0, jf.getInputStream(jf.getEntry("src/empty.txt")).readBytes().size)
            }

            // Verify java.util.zip.ZipInputStream reads STORED & DEFLATED cleanly (catches bit-3 STORED bugs).
            val streamed = mutableMapOf<String, ByteArray>()
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) streamed[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertArrayEquals(compressible, streamed["src/text.txt"])
            assertArrayEquals(binary, streamed["src/data.bin"])
            assertEquals(0, streamed["src/empty.txt"]!!.size)

            // Verify Wayfinder's own extract matches byte-for-byte.
            val outParent = File(sandbox, "ext-${level.name}").apply { mkdirs() }
            val outDir = ops.extract(zipFile.path, outParent.path, {})
            assertArrayEquals(compressible, File(outDir, "src/text.txt").readBytes())
            assertArrayEquals(binary, File(outDir, "src/data.bin").readBytes())
            assertEquals(0, File(outDir, "src/empty.txt").readBytes().size)
        }

        // Uncompressed must be noticeably larger than deflated, and Maximum <= Fast.
        assertTrue("None (${sizes[ZipCompressionLevel.None]}) > Fast (${sizes[ZipCompressionLevel.Fast]})", sizes[ZipCompressionLevel.None]!! > sizes[ZipCompressionLevel.Fast]!!)
        assertTrue("Maximum (${sizes[ZipCompressionLevel.Maximum]}) <= Fast (${sizes[ZipCompressionLevel.Fast]})", sizes[ZipCompressionLevel.Maximum]!! <= sizes[ZipCompressionLevel.Fast]!!)
    }

    // ------------------------------------------------------------------ WinZip AES-256 creation & extraction

    @Test
    fun aes256RoundTripAllLevelsBoundarySizesAndUnicode() = runBlocking {
        val srcDir = File(sandbox, "secret-src").apply { mkdirs() }
        val payloads = linkedMapOf(
            "empty.bin" to ByteArray(0),
            "one.bin" to byteArrayOf(0x42),
            "block-15.bin" to ByteArray(15) { (it + 1).toByte() },
            "block-16.bin" to ByteArray(16) { (it + 10).toByte() },
            "block-17.bin" to ByteArray(17) { (it + 20).toByte() },
            "nested/日本語/résumé.txt" to ("Secret résumé content! 🔐\n".repeat(300)).toByteArray(Charsets.UTF_8),
            "large.bin" to ByteArray(120_000) { ((it * 73 + 5) and 0xFF).toByte() },
            "dup1.txt" to "identical-plaintext".toByteArray(),
            "dup2.txt" to "identical-plaintext".toByteArray(),
        )
        for ((rel, data) in payloads) {
            File(srcDir, rel).apply { parentFile!!.mkdirs(); writeBytes(data) }
        }

        val password = "pässwörd-🔐-" + "Z".repeat(70)

        for (level in ZipCompressionLevel.entries) {
            val zipFile = File(sandbox, "aes-${level.name}.zip")
            ops.compress(
                sources = listOf(srcDir.path),
                zipPath = zipFile.path,
                onProgress = {},
                options = ZipCompressOptions(level = level, password = password),
            )

            // Inspect ZIP headers: must be WinZip AES-256 AE-2 with fresh random salts per entry.
            ZipCrypto.openZipFile(zipFile).use { z ->
                val e1 = z.getEntry("secret-src/dup1.txt")
                val e2 = z.getEntry("secret-src/dup2.txt")
                val enc1 = ZipCrypto.detectEncryption(e1) as ZipEntryEncryption.WinZipAes
                assertEquals(2, enc1.version)
                assertEquals(AesStrength.AES256, enc1.strength)
                assertEquals(if (level == ZipCompressionLevel.None) 0 else 8, enc1.actualMethod)
                assertEquals(0L, e1.crc)

                val raw1 = z.getRawInputStream(e1).readBytes()
                val raw2 = z.getRawInputStream(e2).readBytes()
                assertFalse("Identical files must use unique random salts", raw1.copyOf(16).contentEquals(raw2.copyOf(16)))
                assertFalse("Identical files must produce different ciphertexts", raw1.contentEquals(raw2))
            }

            // Missing password -> ZipPasswordRequiredException(wrongPassword = false), no output left behind.
            val destMissing = File(sandbox, "ext-missing-${level.name}").apply { mkdirs() }
            try {
                ops.extract(zipFile.path, destMissing.path, {})
                fail("Expected ZipPasswordRequiredException")
            } catch (e: ZipPasswordRequiredException) {
                assertFalse(e.wrongPassword)
                assertEquals(zipFile.path, e.archivePath)
            }
            assertEquals(0, destMissing.list()!!.size)

            // Wrong password -> ZipPasswordRequiredException(wrongPassword = true), no output left behind.
            val destWrong = File(sandbox, "ext-wrong-${level.name}").apply { mkdirs() }
            try {
                ops.extract(zipFile.path, destWrong.path, {}, password = "wrong-password")
                fail("Expected ZipPasswordRequiredException")
            } catch (e: ZipPasswordRequiredException) {
                assertTrue(e.wrongPassword)
            }
            assertEquals(0, destWrong.list()!!.size)

            // verifyPassword accepts right password and rejects wrong password.
            ZipCrypto.verifyPassword(zipFile, zipFile.path, password)
            try {
                ZipCrypto.verifyPassword(zipFile, zipFile.path, "wrong-password")
                fail("Expected verifyPassword to reject wrong password")
            } catch (e: ZipPasswordRequiredException) {
                assertTrue(e.wrongPassword)
            }

            // Correct password extracts every single file identically.
            val destOk = File(sandbox, "ext-ok-${level.name}").apply { mkdirs() }
            val outDir = ops.extract(zipFile.path, destOk.path, {}, password = password)
            for ((rel, expected) in payloads) {
                assertArrayEquals("Mismatch for $rel at level $level", expected, File(outDir, "secret-src/$rel").readBytes())
            }
        }
    }

    @Test
    fun tamperedAesCiphertextAndAuthTagAreRejected() = runBlocking {
        val src = File(sandbox, "plain.txt").apply { writeText("Sensitive content ".repeat(500)) }
        val zipFile = File(sandbox, "tamper.zip")
        ops.compress(listOf(src.path), zipFile.path, ZipCompressOptions(ZipCompressionLevel.Normal, password = "correct-horse")) {}

        val (dataOffset, compSize) = ZipCrypto.openZipFile(zipFile).use { z ->
            val e = z.getEntry("plain.txt")
            e.dataOffset to e.compressedSize
        }

        // 1. Flip a bit in the ciphertext (after 16-byte salt + 2-byte PVV).
        val badCipher = File(sandbox, "tamper-cipher.zip").apply { writeBytes(zipFile.readBytes()) }
        val bytes1 = badCipher.readBytes()
        val cipherIdx = (dataOffset + 18 + 5).toInt()
        bytes1[cipherIdx] = (bytes1[cipherIdx].toInt() xor 0x01).toByte()
        badCipher.writeBytes(bytes1)

        val dest1 = File(sandbox, "tamper-dest-1").apply { mkdirs() }
        try {
            ops.extract(badCipher.path, dest1.path, {}, password = "correct-horse")
            fail("Tampered ciphertext must fail authentication")
        } catch (_: IOException) {
            // Expected (ZipPasswordRequiredException or IOException).
        }
        assertEquals("No unauthenticated output may remain", 0, dest1.list()!!.size)

        // 2. Flip a bit in the trailing 10-byte HMAC tag.
        val badTag = File(sandbox, "tamper-tag.zip").apply { writeBytes(zipFile.readBytes()) }
        val bytes2 = badTag.readBytes()
        val tagIdx = (dataOffset + compSize - 1).toInt()
        bytes2[tagIdx] = (bytes2[tagIdx].toInt() xor 0x80).toByte()
        badTag.writeBytes(bytes2)

        val dest2 = File(sandbox, "tamper-dest-2").apply { mkdirs() }
        try {
            ops.extract(badTag.path, dest2.path, {}, password = "correct-horse")
            fail("Tampered HMAC tag must fail authentication")
        } catch (_: IOException) {
            // Expected.
        }
        assertEquals("No unauthenticated output may remain", 0, dest2.list()!!.size)
    }

    // ------------------------------------------------------------------ AES-128/192 & AE-1 decryption

    @Test
    fun winZipAes128Ae1AndAes192Ae2DecryptCorrectly() = runBlocking {
        val plain = "Hello from AES-128 AE-1 and AES-192 AE-2!\n".repeat(40).toByteArray(Charsets.UTF_8)
        val pw = "s3cr3t!"
        val zip128 = File(sandbox, "aes128-ae1.zip").apply {
            writeBytes(buildHandcraftedWinZipAes("a.txt", plain, pw, AesStrength.AES128, version = 1, deflate = true))
        }
        val zip192 = File(sandbox, "aes192-ae2.zip").apply {
            writeBytes(buildHandcraftedWinZipAes("b.txt", plain, pw, AesStrength.AES192, version = 2, deflate = false))
        }

        val out128 = ops.extract(zip128.path, File(sandbox, "o128").apply { mkdirs() }.path, {}, password = pw)
        assertArrayEquals(plain, File(out128, "a.txt").readBytes())

        val out192 = ops.extract(zip192.path, File(sandbox, "o192").apply { mkdirs() }.path, {}, password = pw)
        assertArrayEquals(plain, File(out192, "b.txt").readBytes())

        // Corrupt CRC in AE-1 (which validates CRC-32 in addition to HMAC) must fail.
        val badCrcAe1 = File(sandbox, "aes128-ae1-badcrc.zip").apply {
            writeBytes(buildHandcraftedWinZipAes("a.txt", plain, pw, AesStrength.AES128, version = 1, deflate = true, corruptCrc = true))
        }
        try {
            ops.extract(badCrcAe1.path, File(sandbox, "obadcrc").apply { mkdirs() }.path, {}, password = pw)
            fail("AE-1 entry with bad CRC must fail")
        } catch (_: IOException) {
        }
    }

    // ------------------------------------------------------------------ Legacy PKWARE ZipCrypto decryption

    @Test
    fun legacyZipCryptoDecryptsWithCrcAndDataDescriptorChecks() = runBlocking {
        val plain = "Legacy ZipCrypto compatibility test payload!\n".repeat(30).toByteArray(Charsets.UTF_8)
        val pw = "classic-pass"

        // Case A: standard CRC check byte (no data descriptor), both STORED and DEFLATED.
        for (deflate in listOf(false, true)) {
            val zf = File(sandbox, "zipcrypto-crc-$deflate.zip").apply {
                writeBytes(buildHandcraftedZipCrypto("legacy.txt", plain, pw, deflate = deflate, useDataDescriptor = false))
            }
            ZipCrypto.verifyPassword(zf, zf.path, pw)
            try {
                ZipCrypto.verifyPassword(zf, zf.path, "nope")
                fail("Wrong ZipCrypto password must fail")
            } catch (e: ZipPasswordRequiredException) {
                assertTrue(e.wrongPassword)
            }
            val out = ops.extract(zf.path, File(sandbox, "zc-out-$deflate").apply { mkdirs() }.path, {}, password = pw)
            assertArrayEquals(plain, File(out, "legacy.txt").readBytes())
        }

        // Case B: bit 3 data descriptor set, check byte = raw DOS time high byte, WITH conflicting X5455 UTC timestamp!
        val zfDesc = File(sandbox, "zipcrypto-desc.zip").apply {
            writeBytes(buildHandcraftedZipCrypto("desc.txt", plain, pw, deflate = true, useDataDescriptor = true, addConflictingX5455 = true))
        }
        ZipCrypto.verifyPassword(zfDesc, zfDesc.path, pw)
        val outDesc = ops.extract(zfDesc.path, File(sandbox, "zc-desc-out").apply { mkdirs() }.path, {}, password = pw)
        assertArrayEquals(plain, File(outDesc, "desc.txt").readBytes())
    }

    // ------------------------------------------------------------------ Exclude hidden files

    @Test
    fun excludeHiddenSkipsDotfilesAndDotFoldersAtAllDepths() = runBlocking {
        val project = File(sandbox, "proj").apply { mkdirs() }
        File(project, "README.md").writeText("hello")
        File(project, ".env").writeText("SECRET=1")
        File(project, ".git").apply { mkdirs(); File(this, "HEAD").writeText("ref: refs/heads/main") }
        File(project, "src").apply {
            mkdirs()
            File(this, "App.kt").writeText("fun main() {}")
            File(this, ".DS_Store").writeText("junk")
        }
        val topDot = File(sandbox, ".top-hidden").apply { writeText("hidden at top level") }

        val zipExcluded = File(sandbox, "no-hidden.zip")
        ops.compress(
            sources = listOf(project.path, topDot.path),
            zipPath = zipExcluded.path,
            onProgress = {},
            options = ZipCompressOptions(excludeHidden = true),
        )
        val excludedNames = JdkZipFile(zipExcluded).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertTrue("proj/README.md" in excludedNames)
        assertTrue("proj/src/App.kt" in excludedNames)
        assertFalse(".top-hidden" in excludedNames)
        assertFalse("proj/.env" in excludedNames)
        assertFalse("proj/.git/" in excludedNames)
        assertFalse("proj/.git/HEAD" in excludedNames)
        assertFalse("proj/src/.DS_Store" in excludedNames)

        val zipIncluded = File(sandbox, "with-hidden.zip")
        ops.compress(
            sources = listOf(project.path, topDot.path),
            zipPath = zipIncluded.path,
            onProgress = {},
            options = ZipCompressOptions(excludeHidden = false),
        )
        val includedNames = JdkZipFile(zipIncluded).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertTrue(".top-hidden" in includedNames)
        assertTrue("proj/.env" in includedNames)
        assertTrue("proj/.git/HEAD" in includedNames)
        assertTrue("proj/src/.DS_Store" in includedNames)
    }

    // ------------------------------------------------------------------ ZipFileSystem lazy password behavior

    @Test
    fun zipFileSystemBrowsesWithoutPasswordAndRequiresPasswordOnlyForEncryptedContent() = runBlocking {
        // Build a mixed archive: public.txt (unencrypted) + sub/secret.txt (AES-256 encrypted).
        val zipFile = File(sandbox, "mixed.zip")
        val pw = "open-sesame"
        zipFile.outputStream().use { fos ->
            ZipWriter(fos, level = ZipCompressionLevel.Normal, password = null).use { _ -> }
        }
        // Create a real mixed zip by combining an unencrypted entry and an AES entry via a pure test builder.
        val encDir = File(sandbox, "enc-dir").apply { mkdirs() }
        File(encDir, "secret.txt").writeText("top secret")
        val lockedZip = File(sandbox, "locked.zip")
        ops.compress(listOf(encDir.path), lockedZip.path, ZipCompressOptions(password = pw)) {}

        val zfs = repo.zip
        val root = FsPath.zipRoot(lockedZip.path)

        // 1. Browsing & Quick Look summary succeed without any password!
        val topEntries = zfs.list(root)
        assertEquals(listOf("enc-dir"), topEntries.map { it.name })
        val innerEntries = zfs.list(FsPath.child(root, "enc-dir"))
        assertEquals(1, innerEntries.size)
        assertEquals("secret.txt", innerEntries[0].name)
        assertTrue("Encrypted file must be flagged isEncrypted", innerEntries[0].isEncrypted)

        val summary = ZipFileSystem.summary(lockedZip)
        assertTrue(summary.encrypted)
        assertTrue(summary.total >= 1)

        // 2. needsPassword reports true for archive, folder containing encrypted file, and encrypted file.
        val secretPath = innerEntries[0].path
        assertTrue(zfs.needsPassword(lockedZip.path))
        assertTrue(zfs.needsPassword(FsPath.child(root, "enc-dir")))
        assertTrue(zfs.needsPassword(secretPath))

        // 3. Reading without password throws ZipPasswordRequiredException(wrongPassword = false).
        try {
            zfs.openInput(secretPath).use { it.readBytes() }
            fail("Expected ZipPasswordRequiredException")
        } catch (e: ZipPasswordRequiredException) {
            assertFalse(e.wrongPassword)
        }

        // 4. Setting wrong password throws ZipPasswordRequiredException(wrongPassword = true) and evicts it.
        zfs.setPassword(lockedZip.path, "wrong")
        try {
            zfs.openInput(secretPath).use { it.readBytes() }
            fail("Expected ZipPasswordRequiredException for wrong password")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
        assertTrue("Bad password must be evicted from cache", zfs.needsPassword(secretPath))

        // 5. Setting correct password unlocks reads without re-prompting.
        zfs.setPassword(lockedZip.path, pw)
        assertFalse(zfs.needsPassword(secretPath))
        assertEquals("top secret", zfs.openInput(secretPath).use { it.readBytes().decodeToString() })

        // 6. Replacing the archive on disk invalidates the cached password (stamp check).
        File(encDir, "extra.txt").writeText("more content to change file length")
        ops.compress(listOf(encDir.path), lockedZip.path, ZipCompressOptions(password = "new-password")) {}
        assertTrue("Replaced archive must invalidate old cached password", zfs.needsPassword(lockedZip.path))
    }

    // ------------------------------------------------------------------ Remote encrypted ZIP extraction

    @Test
    fun remoteEncryptedZipPromptsAndExtractsCleanly() = runBlocking {
        val src = File(sandbox, "remote-doc.txt").apply { writeText("Remote encrypted document") }
        val backingZip = File(sandbox, "remote-backing.zip")
        val pw = "cloud-secret"
        ops.compress(listOf(src.path), backingZip.path, ZipCompressOptions(password = pw)) {}

        val fakeRemoteFs = object : FileSystem {
            override fun list(path: String) = emptyList<FileEntry>()
            override fun stat(path: String): FileEntry? =
                if (path == "dav://nas/remote-backing.zip") FileEntry(path, "remote-backing.zip", false, backingZip.length()) else null
            override fun mkdir(path: String) = throw IOException("ro")
            override fun createFile(path: String) = throw IOException("ro")
            override fun delete(path: String) = throw IOException("ro")
            override fun rename(path: String, newName: String) = throw IOException("ro")
            override fun openInput(path: String): InputStream = backingZip.inputStream()
            override fun openOutput(path: String): OutputStream = throw IOException("ro")
        }
        val remoteRepo = FileRepository(LocalFileSystem(), LocalFileSystem(), null, byScheme = mapOf("dav" to fakeRemoteFs))
        val remoteOps = FileOperations(remoteRepo)

        // Missing password -> immediate ZipPasswordRequiredException(wrongPassword = false).
        val outFail = File(sandbox, "remote-out-fail").apply { mkdirs() }
        try {
            remoteOps.extract("dav://nas/remote-backing.zip", outFail.path, {})
            fail("Expected ZipPasswordRequiredException")
        } catch (e: ZipPasswordRequiredException) {
            assertFalse(e.wrongPassword)
            assertEquals("dav://nas/remote-backing.zip", e.archivePath)
        }
        assertEquals(0, outFail.list()!!.size)

        // Wrong password -> ZipPasswordRequiredException(wrongPassword = true).
        try {
            remoteOps.extract("dav://nas/remote-backing.zip", outFail.path, {}, password = "bad")
            fail("Expected ZipPasswordRequiredException")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
        assertEquals(0, outFail.list()!!.size)

        // Correct password -> succeeds.
        val outOk = File(sandbox, "remote-out-ok").apply { mkdirs() }
        val extracted = remoteOps.extract("dav://nas/remote-backing.zip", outOk.path, {}, password = pw)
        assertEquals("Remote encrypted document", File(extracted, "remote-doc.txt").readText())
    }

    @Test
    fun mixedArchiveOnlyRequiresPasswordForEncryptedEntries() {
        val plainBytes = "public readme".toByteArray(Charsets.UTF_8)
        val secretBytes = "classified payload".toByteArray(Charsets.UTF_8)
        val pw = "mix-pass"
        val mixedZip = File(sandbox, "mixed-real.zip").apply {
            writeBytes(buildMixedZip(plainBytes, secretBytes, pw))
        }

        val zfs = repo.zip
        val root = FsPath.zipRoot(mixedZip.path)
        val pubPath = FsPath.child(root, "public.txt")
        val secPath = FsPath.child(root, "vault/secret.txt")

        assertFalse("Unencrypted entry must not require password", zfs.needsPassword(pubPath))
        assertTrue("Encrypted entry must require password", zfs.needsPassword(secPath))
        assertTrue("Folder containing encrypted entry must require password", zfs.needsPassword(FsPath.child(root, "vault")))
        assertEquals("public readme", zfs.openInput(pubPath).use { it.readBytes().decodeToString() })

        try {
            zfs.openInput(secPath).use { it.readBytes() }
            fail("Encrypted entry must require password")
        } catch (e: ZipPasswordRequiredException) {
            assertFalse(e.wrongPassword)
        }

        zfs.setPassword(mixedZip.path, pw)
        assertEquals("classified payload", zfs.openInput(secPath).use { it.readBytes().decodeToString() })
    }

    @Test
    fun pythonZipfileInteropVerifiesUnencryptedLevelsAndZipCryptoStandard() = runBlocking {
        val pyCheck = runCatching { ProcessBuilder("python3", "--version").start().waitFor() }.getOrNull()
        if (pyCheck != 0) return@runBlocking

        // 1. All 4 compression levels created by ZipWriter pass independent python3 zipfile.testzip().
        val src = File(sandbox, "py-src.txt").apply { writeText("Interoperability check line\n".repeat(200)) }
        for (level in ZipCompressionLevel.entries) {
            val zf = File(sandbox, "py-${level.name}.zip")
            ops.compress(listOf(src.path), zf.path, ZipCompressOptions(level = level)) {}
            val proc = ProcessBuilder(
                "python3", "-c",
                "import sys, zipfile; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; assert len(z.read('py-src.txt')) == int(sys.argv[2])",
                zf.path, src.length().toString(),
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.readBytes().decodeToString()
            assertEquals("python3 zipfile check failed for $level: $out", 0, proc.waitFor())
        }

        // 2. Our ZipCrypto test archive is independently decrypted by Python's standard zipfile implementation.
        val payload = "Verified by Python stdlib ZipCrypto!\n".repeat(20).toByteArray(Charsets.UTF_8)
        val zcFile = File(sandbox, "py-zipcrypto.zip").apply {
            writeBytes(buildHandcraftedZipCrypto("check.txt", payload, "py-pass", deflate = true, useDataDescriptor = false))
        }
        val proc = ProcessBuilder(
            "python3", "-c",
            "import sys, zipfile; z=zipfile.ZipFile(sys.argv[1]); data=z.read('check.txt', pwd=b'py-pass'); assert data == sys.argv[2].encode('utf-8')",
            zcFile.path, payload.decodeToString(),
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        assertEquals("python3 ZipCrypto interop failed: $out", 0, proc.waitFor())
    }

    // ------------------------------------------------------------------ Independent reference vectors & security regressions

    private fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun committedOpenSslWinZipAe1AndAe2ReferenceVectorsDecryptAndReverseVerify() = runBlocking {
        // Independent WinZip AE-2 (AES-256, DEFLATED) & AE-1 (AES-128, STORED) reference vectors
        // generated outside Wayfinder via OpenSSL (`openssl enc -aes-*-ecb`) + Python stdlib PBKDF2/HMAC/zlib.
        val ae2Hex =
            "504b03043300010863000040215a00000000550000003a0000000e000b00766563746f722d6165322e7478740199070002004145030800" +
                "00112233445566778899aabbccddeeffaed94b6d90c7ea199aa0722b5318ad95513bae648a83889188912144588985aefd0adb11a847474fd741a157a5e02f3619aacff46414229f35389e210a2ab3634f0f91a6f9" +
                "504b010233033300010863000040215a00000000550000003a0000000e000b000000000000000000a48100000000766563746f722d6165322e7478740199070002004145030800" +
                "504b05060000000001000100470000008c0000000000"
        val ae1Hex =
            "504b03043300010863000040215a082fd662400000002c0000000e000b00766563746f722d6165312e7478740199070001004145010000" +
                "fedcba987654321000ddc4321f8a6f1988e7e27f9499e58b33011f71ef876c7801d4c6c0845a573c061c825730ac9c693738e0a38c93fa654769f66c4bb08000" +
                "504b010233033300010863000040215a082fd662400000002c0000000e000b000000000000000000a48100000000766563746f722d6165312e7478740199070001004145010000" +
                "504b0506000000000100010047000000770000000000"

        val ae2File = File(sandbox, "ref-ae2.zip").apply { writeBytes(fromHex(ae2Hex)) }
        val ae1File = File(sandbox, "ref-ae1.zip").apply { writeBytes(fromHex(ae1Hex)) }

        val outAe2 = ops.extract(ae2File.path, File(sandbox, "ext-ref-ae2").apply { mkdirs() }.path, {}, password = "RefVector#256")
        assertEquals("WinZip AES-256 AE-2 reference vector verified via OpenSSL!", File(outAe2, "vector-ae2.txt").readText())

        val outAe1 = ops.extract(ae1File.path, File(sandbox, "ext-ref-ae1").apply { mkdirs() }.path, {}, password = "RefVector#128")
        assertEquals("WinZip AES-128 AE-1 stored reference vector!", File(outAe1, "vector-ae1.txt").readText())

        // Reverse check: verify ZipWriter AES-256 output independently using python3 + openssl CLI when available.
        val hasPy = runCatching { ProcessBuilder("python3", "--version").start().waitFor() }.getOrNull() == 0
        val hasSsl = runCatching { ProcessBuilder("openssl", "version").start().waitFor() }.getOrNull() == 0
        if (hasPy && hasSsl) {
            val plain = "Independent OpenSSL decryption of ZipWriter AES-256 output!\n".repeat(40)
            val src = File(sandbox, "ssl-check.txt").apply { writeText(plain) }
            val zipOut = File(sandbox, "ssl-check.zip")
            ops.compress(listOf(src.path), zipOut.path, ZipCompressOptions(ZipCompressionLevel.Normal, password = "SslVerify!99")) {}

            val pyScript = """
                import hashlib, hmac, struct, subprocess, sys, zipfile, zlib
                zpath, pw, expected = sys.argv[1], sys.argv[2], sys.argv[3].encode('utf-8')
                with zipfile.ZipFile(zpath) as zf:
                    info = zf.getinfo('ssl-check.txt')
                    assert info.compress_type == 99
                    with open(zpath, 'rb') as f:
                        f.seek(info.header_offset)
                        sig, _, _, _, _, _, _, _, _, nlen, elen = struct.unpack('<IHHHHHIIIHH', f.read(30))
                        assert sig == 0x04034B50
                        f.seek(nlen + elen, 1)
                        raw = f.read(info.compress_size)
                salt, pvv, ct, tag = raw[:16], raw[16:18], raw[18:-10], raw[-10:]
                d = hashlib.pbkdf2_hmac('sha1', pw.encode('utf-8'), salt, 1000, 66)
                aes_k, hmac_k, exp_pvv = d[:32], d[32:64], d[64:66]
                assert pvv == exp_pvv, 'PVV mismatch'
                assert hmac.new(hmac_k, ct, 'sha1').digest()[:10] == tag, 'HMAC mismatch'
                nblocks = (len(ct) + 15) // 16
                counters = b''.join(struct.pack('<QQ', i + 1, 0) for i in range(nblocks))
                ks = subprocess.run(['openssl', 'enc', '-aes-256-ecb', '-K', aes_k.hex(), '-nopad'], input=counters, capture_output=True, check=True).stdout[:len(ct)]
                comp = bytes(a ^ b for a, b in zip(ct, ks))
                dec = zlib.decompress(comp, -15)
                assert dec == expected, 'Plaintext mismatch'
            """.trimIndent()
            val p = ProcessBuilder("python3", "-c", pyScript, zipOut.path, "SslVerify!99", plain).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            assertEquals("Independent OpenSSL/Python verification of ZipWriter AES-256 failed: $out", 0, p.waitFor())
        }

        // Bidirectional 7-Zip CLI interop test whenever `7z` is installed (e.g. GitHub Actions ubuntu-latest).
        val has7z = runCatching { ProcessBuilder("7z").start().waitFor() }.getOrNull() != null
        if (has7z) {
            val src = File(sandbox, "sevenz-src.txt").apply { writeText("Roundtrip with 7-Zip CLI!\n".repeat(30)) }
            val wfZip = File(sandbox, "from-wayfinder.zip")
            ops.compress(listOf(src.path), wfZip.path, ZipCompressOptions(ZipCompressionLevel.Normal, password = "7z-Interop!")) {}
            val out7z = File(sandbox, "7z-extracted").apply { mkdirs() }
            val extProc = ProcessBuilder("7z", "x", "-y", "-p7z-Interop!", "-o${out7z.path}", wfZip.path).redirectErrorStream(true).start()
            val extLog = extProc.inputStream.readBytes().decodeToString()
            assertEquals("7z failed to extract Wayfinder AES-256 ZIP: $extLog", 0, extProc.waitFor())
            assertEquals(src.readText(), File(out7z, "sevenz-src.txt").readText())

            val sevenZipFile = File(sandbox, "from-7z.zip")
            val createProc = ProcessBuilder("7z", "a", "-tzip", "-mem=AES256", "-p7z-Interop!", sevenZipFile.path, src.path).redirectErrorStream(true).start()
            val createLog = createProc.inputStream.readBytes().decodeToString()
            assertEquals("7z failed to create AES-256 ZIP: $createLog", 0, createProc.waitFor())
            val wfOut = ops.extract(sevenZipFile.path, File(sandbox, "wf-from-7z").apply { mkdirs() }.path, {}, password = "7z-Interop!")
            assertEquals(src.readText(), File(wfOut, "sevenz-src.txt").readText())
        }
    }

    @Test
    fun duplicateNormalizedEntryPathsAbortExtractionWithoutOverwritingAesEntry() = runBlocking {
        // Build a ZIP with an authenticated AES-256 "secret.txt" followed by an unencrypted "./secret.txt"
        // that normalizes to the same relative path. Extraction MUST reject it and leave no output.
        val pw = "dup-guard-pw"
        val secretBytes = "authenticated content".toByteArray(Charsets.UTF_8)
        val spoofBytes = "unauthenticated attacker payload".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16) { (0x30 + it).toByte() }
        val derived = ZipCrypto.pbkdf2HmacSha1(pw.toByteArray(Charsets.UTF_8), salt, 1000, 66)
        val aesKey = derived.copyOfRange(0, 32)
        val hmacKey = derived.copyOfRange(32, 64)
        val pvv = derived.copyOfRange(64, 66)
        val ct = deflateRaw(secretBytes).also { WinZipAesCtrCipher(aesKey).processInPlace(it, 0, it.size) }
        val tag = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(hmacKey, "HmacSHA1")) }.doFinal(ct).copyOf(10)
        val encPayload = salt + pvv + ct + tag

        val zipBytes = buildMultiEntryZip(
            listOf(
                RawZipSpec("secret.txt", 0x0801, 99, 0L, encPayload, secretBytes.size.toLong(), ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2), 51),
                RawZipSpec("./secret.txt", 0x0800, 0, CRC32().apply { update(spoofBytes) }.value, spoofBytes, spoofBytes.size.toLong(), ByteArray(0), 20),
            ),
        )
        val dupZip = File(sandbox, "dup-collision.zip").apply { writeBytes(zipBytes) }
        val dest = File(sandbox, "dup-dest").apply { mkdirs() }
        try {
            ops.extract(dupZip.path, dest.path, {}, password = pw)
            fail("Duplicate normalized entry path (./secret.txt) must be rejected")
        } catch (e: IOException) {
            assertTrue("Expected duplicate error, got: ${e.message}", e.message.orEmpty().contains("Duplicate", ignoreCase = true))
        }
        assertEquals("Failed extraction must leave destination empty", 0, dest.list()!!.size)
    }

    @Test
    fun spoofedEntrySizeAndLongMaxValueZipBombAreStoppedByZipFileSystemOpenInput() {
        // 1. Entry claiming Long.MAX_VALUE with small real data must not overflow or read 1 byte at a time.
        val smallData = "tiny legitimate bytes".toByteArray(Charsets.UTF_8)
        val spoofLongMax = File(sandbox, "spoof-maxlong.zip").apply {
            writeBytes(
                buildSingleEntryZipWithZip64Size(
                    name = "lie.txt",
                    compressed = deflateRaw(smallData),
                    declaredUncompSize = Long.MAX_VALUE,
                    crc = CRC32().apply { update(smallData) }.value,
                ),
            )
        }
        val zfs = ZipFileSystem()
        try {
            zfs.openInput(FsPath.child(FsPath.zipRoot(spoofLongMax.path), "lie.txt")).use { it.readBytes() }
            fail("Size mismatch against spoofed Long.MAX_VALUE must fail cleanly")
        } catch (_: IOException) {
            // Expected size mismatch IOException (and importantly, NO ArithmeticException or hang).
        }

        // 2. 40 MiB all-zero DEFLATE bomb claiming Long.MAX_VALUE (1000:1 compression ratio) must be stopped
        // by ZipFileSystem.openInput's BoundedInputStream ratio guard (~32 MiB floor).
        val bombPlain = ByteArray(40 shl 20)
        val bombCompressed = deflateRaw(bombPlain)
        assertTrue("Bomb must compress tightly", bombCompressed.size < 128 * 1024)
        val bombZip = File(sandbox, "bomb-maxlong.zip").apply {
            writeBytes(
                buildSingleEntryZipWithZip64Size(
                    name = "bomb.bin",
                    compressed = bombCompressed,
                    declaredUncompSize = Long.MAX_VALUE,
                    crc = CRC32().apply { update(bombPlain) }.value,
                ),
            )
        }
        var totalRead = 0L
        try {
            zfs.openInput(FsPath.child(FsPath.zipRoot(bombZip.path), "bomb.bin")).use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    totalRead += n
                }
            }
            fail("Zip bomb with spoofed Long.MAX_VALUE size must be aborted by ZipFileSystem.openInput")
        } catch (e: IOException) {
            assertTrue("Expected zip bomb rejection, got: ${e.message}", e.message.orEmpty().contains("zip bomb", ignoreCase = true))
        }
        assertTrue("Must abort promptly near ratio floor, read $totalRead bytes", totalRead < (34L shl 20))
    }

    @Test
    fun conflictReplacePreservesExistingFileOnWrongPasswordAndTamperedHmac() = runBlocking {
        val src = File(sandbox, "replace-src.txt").apply { writeText("new decrypted contents") }
        val zipFile = File(sandbox, "replace.zip")
        ops.compress(listOf(src.path), zipFile.path, ZipCompressOptions(ZipCompressionLevel.Normal, password = "good-pw")) {}

        val destDir = File(sandbox, "replace-dest").apply { mkdirs() }
        val existing = File(destDir, "replace-src.txt").apply { writeText("precious existing file") }

        val entryPath = FsPath.child(FsPath.zipRoot(zipFile.path), "replace-src.txt")

        // 1. Wrong password with Conflict.Replace must preserve existing destination file.
        repo.zip.setPassword(zipFile.path, "wrong-pw")
        try {
            ops.transfer(listOf(entryPath), destDir.path, move = false, conflict = Conflict.Replace) {}
            fail("Expected ZipPasswordRequiredException")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
        assertEquals("Existing file must remain intact on wrong password", "precious existing file", existing.readText())
        assertEquals("No temp staging files may linger", listOf("replace-src.txt"), destDir.list()!!.toList())

        // 2. Tampered HMAC with Conflict.Replace must also preserve existing destination file.
        val tampered = File(sandbox, "replace-tampered.zip").apply { writeBytes(zipFile.readBytes()) }
        val (dataOffset, compSize) = ZipCrypto.openZipFile(tampered).use { z ->
            val e = z.getEntry("replace-src.txt")
            e.dataOffset to e.compressedSize
        }
        val rawBytes = tampered.readBytes()
        rawBytes[(dataOffset + compSize - 1).toInt()] = (rawBytes[(dataOffset + compSize - 1).toInt()].toInt() xor 0xFF).toByte()
        tampered.writeBytes(rawBytes)
        repo.zip.setPassword(tampered.path, "good-pw")
        try {
            ops.transfer(
                listOf(FsPath.child(FsPath.zipRoot(tampered.path), "replace-src.txt")),
                destDir.path,
                move = false,
                conflict = Conflict.Replace,
            ) {}
            fail("Expected authentication failure for tampered HMAC")
        } catch (_: IOException) {
        }
        assertEquals("Existing file must remain intact on tampered HMAC", "precious existing file", existing.readText())
        assertEquals("No temp staging files may linger", listOf("replace-src.txt"), destDir.list()!!.toList())

        // 3. Correct password atomically replaces the existing file.
        repo.zip.setPassword(zipFile.path, "good-pw")
        ops.transfer(listOf(entryPath), destDir.path, move = false, conflict = Conflict.Replace) {}
        assertEquals("new decrypted contents", existing.readText())
    }

    @Test
    fun partialReadOfTamperedAesCiphertextIsRejectedBeforeReturningAnyPlaintext() = runBlocking {
        val src = File(sandbox, "prefix.txt").apply { writeText("0123456789ABCDEF".repeat(200)) }
        val zipFile = File(sandbox, "prefix.zip")
        ops.compress(listOf(src.path), zipFile.path, ZipCompressOptions(ZipCompressionLevel.None, password = "auth-first")) {}

        val (dataOffset, compSize) = ZipCrypto.openZipFile(zipFile).use { z ->
            val e = z.getEntry("prefix.txt")
            e.dataOffset to e.compressedSize
        }
        // Flip a bit near the END of the ciphertext (well beyond the first 8 bytes).
        val bytes = zipFile.readBytes()
        val flipIdx = (dataOffset + compSize - ZipCrypto.AES_HMAC_BYTES - 2).toInt()
        bytes[flipIdx] = (bytes[flipIdx].toInt() xor 0x01).toByte()
        val tampered = File(sandbox, "prefix-tampered.zip").apply { writeBytes(bytes) }

        val zfs = ZipFileSystem().apply { setPassword(tampered.path, "auth-first") }
        val innerPath = FsPath.child(FsPath.zipRoot(tampered.path), "prefix.txt")
        // Verify-before-decrypt must reject during openInput BEFORE returning even 8 bytes of plaintext!
        try {
            zfs.openInput(innerPath).use { inp ->
                val prefix = ByteArray(8)
                val n = inp.read(prefix)
                fail("Partial read must not receive unauthenticated plaintext (got $n bytes)")
            }
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
    }

    @Test
    fun compressSkipsFileSymlinksAndTopLevelDirectorySymlinks() = runBlocking {
        val outside = File(sandbox, "outside-secret").apply {
            mkdirs()
            File(this, "secret.txt").writeText("top secret outside")
        }
        val work = File(sandbox, "sym-work").apply { mkdirs() }
        File(work, "real.txt").writeText("real")
        val nestedFileLink = Files.createSymbolicLink(File(work, "link-file.txt").toPath(), File(outside, "secret.txt").toPath()).toFile()
        val topDirLink = Files.createSymbolicLink(File(sandbox, "top-dir-link").toPath(), outside.toPath()).toFile()
        val topFileLink = Files.createSymbolicLink(File(sandbox, "top-file-link.txt").toPath(), File(outside, "secret.txt").toPath()).toFile()

        assertTrue(FileInfo.isSymlink(nestedFileLink.path))
        val zipFile = File(sandbox, "no-symlinks.zip")
        ops.compress(listOf(work.path, topDirLink.path, topFileLink.path), zipFile.path) {}

        val names = JdkZipFile(zipFile).use { z -> z.entries().toList().map { it.name } }
        assertTrue("Real file must be included: $names", "sym-work/real.txt" in names)
        assertFalse("Nested file symlink must be skipped: $names", names.any { it.contains("link-file") })
        assertFalse("Top-level dir symlink must be skipped: $names", names.any { it.contains("top-dir-link") || it.contains("secret.txt") })
        assertFalse("Top-level file symlink must be skipped: $names", names.any { it.contains("top-file-link") })
    }

    @Test
    fun unixSymlinkAndSpecialModeZipEntriesAreIgnoredInBrowseSummaryAndExtract() = runBlocking {
        val okData = "safe content".toByteArray(Charsets.UTF_8)
        val linkTarget = "../../etc/passwd".toByteArray(Charsets.UTF_8)
        val zipBytes = buildMultiEntryZip(
            listOf(
                RawZipSpec("ok.txt", 0x0800, 0, CRC32().apply { update(okData) }.value, okData, okData.size.toLong(), ByteArray(0), 20, externalAttrs = 0x81A40000L),
                RawZipSpec("sym_escape", 0x0800, 0, CRC32().apply { update(linkTarget) }.value, linkTarget, linkTarget.size.toLong(), ByteArray(0), 20, externalAttrs = 0xA1FF0000L),
                RawZipSpec("fifo_node", 0x0800, 0, 0L, ByteArray(0), 0L, ByteArray(0), 20, externalAttrs = 0x11A40000L),
            ),
        )
        val specialZip = File(sandbox, "unix-special.zip").apply { writeBytes(zipBytes) }

        val zfs = ZipFileSystem()
        val listed = zfs.list(FsPath.zipRoot(specialZip.path)).map { it.name }
        assertEquals(listOf("ok.txt"), listed)

        val summary = ZipFileSystem.summary(specialZip)
        assertEquals(1, summary.total)
        assertEquals(listOf("ok.txt"), summary.entries.map { it.first })

        val outDir = ops.extract(specialZip.path, File(sandbox, "special-out").apply { mkdirs() }.path, {})
        assertEquals(listOf("ok.txt"), File(outDir).list()!!.toList())
        assertEquals("safe content", File(outDir, "ok.txt").readText())
    }

    @Test
    fun zipWriterForceZip64ProducesCompliantArchivesReadableByJdkAndCommonsCompress() = runBlocking {
        val payload = "ZIP64 compliance test payload!\n".repeat(120).toByteArray(Charsets.UTF_8)
        val rawBytes = ByteArray(1024) { (it * 13).toByte() }

        // 1. Unencrypted STORED & DEFLATED with forceZip64 = true.
        for (level in listOf(ZipCompressionLevel.None, ZipCompressionLevel.Normal)) {
            val zf = File(sandbox, "z64-${level.name}.zip")
            zf.outputStream().use { fos ->
                ZipWriter(fos, level = level, password = null, forceZip64 = true).use { zw ->
                    zw.addDirectory("sub/", 1_700_000_000_000L)
                    zw.addFile("sub/text.txt", 1_700_000_000_000L, payload.size.toLong(), { payload.inputStream() })
                    zw.addFile("sub/raw.bin", 1_700_000_000_000L, rawBytes.size.toLong(), { rawBytes.inputStream() })
                    zw.finish()
                }
            }

            // Readable by java.util.zip.ZipInputStream (validates LFH + DD).
            val streamed = HashMap<String, ByteArray>()
            ZipInputStream(zf.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) streamed[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertArrayEquals("ZipInputStream text ($level)", payload, streamed["sub/text.txt"])
            assertArrayEquals("ZipInputStream raw ($level)", rawBytes, streamed["sub/raw.bin"])

            // Readable by java.util.zip.ZipFile (validates ZIP64 EOCD + Locator + CDH).
            JdkZipFile(zf).use { jf ->
                assertArrayEquals("JdkZipFile text ($level)", payload, jf.getInputStream(jf.getEntry("sub/text.txt")).readBytes())
                assertArrayEquals("JdkZipFile raw ($level)", rawBytes, jf.getInputStream(jf.getEntry("sub/raw.bin")).readBytes())
            }

            // Readable by commons-compress ZipFile + FileOperations.extract.
            val outDir = ops.extract(zf.path, File(sandbox, "ext-z64-${level.name}").apply { mkdirs() }.path, {})
            assertArrayEquals(payload, File(outDir, "sub/text.txt").readBytes())
            assertArrayEquals(rawBytes, File(outDir, "sub/raw.bin").readBytes())
        }

        // 2. AES-256 encrypted with forceZip64 = true.
        val aesZ64 = File(sandbox, "z64-aes.zip")
        aesZ64.outputStream().use { fos ->
            ZipWriter(fos, level = ZipCompressionLevel.Normal, password = "z64-secret", forceZip64 = true).use { zw ->
                zw.addFile("vault.txt", 1_700_000_000_000L, payload.size.toLong(), { payload.inputStream() })
                zw.finish()
            }
        }
        val outAes = ops.extract(aesZ64.path, File(sandbox, "ext-z64-aes").apply { mkdirs() }.path, {}, password = "z64-secret")
        assertArrayEquals(payload, File(outAes, "vault.txt").readBytes())

        // 3. Exact 0xFFFFFFFFL (4 GiB - 1) Data Descriptor boundary matches OpenJDK 21 / Android ZipInputStream.readEnd (`> 0xFFFFFFFFL`),
        // and encodeDataDescriptor emits exact 16-byte (32-bit) vs 24-byte (64-bit) little-endian descriptors.
        assertFalse(ZipWriter.shouldWriteZip64DataDescriptor(0xFFFFFFFFL, 0xFFFFFFFFL))
        assertTrue(ZipWriter.shouldWriteZip64DataDescriptor(0x1_0000_0000L, 10L))
        assertTrue(ZipWriter.shouldWriteZip64DataDescriptor(10L, 0x1_0000_0000L))

        val dd32 = ZipWriter.encodeDataDescriptor(0x89ABCDEF, 0xFFFFFFFFL, 0xFFFFFFFFL, zip64 = false)
        assertEquals(16, dd32.size)
        val bb32 = ByteBuffer.wrap(dd32).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x08074B50, bb32.int)
        assertEquals(0x89ABCDEF.toInt(), bb32.int)
        assertEquals(0xFFFFFFFFL, bb32.int.toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, bb32.int.toLong() and 0xFFFFFFFFL)

        val dd64 = ZipWriter.encodeDataDescriptor(0x12345678L, 0x1_0000_0005L, 0x2_0000_0009L, zip64 = true)
        assertEquals(24, dd64.size)
        val bb64 = ByteBuffer.wrap(dd64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x08074B50, bb64.int)
        assertEquals(0x12345678, bb64.int)
        assertEquals(0x1_0000_0005L, bb64.long)
        assertEquals(0x2_0000_0009L, bb64.long)
    }

    @Test
    fun zipWriterRejectsEntryNamesExceeding65535Bytes() = runBlocking {
        val tooLong = "a".repeat(65_536)
        val out = ByteArrayOutputStream()
        ZipWriter(out).use { zw ->
            try {
                zw.addDirectory(tooLong, 0L)
                fail("addDirectory must reject > 65,535-byte names")
            } catch (_: IOException) {
            }
            try {
                zw.addFile(tooLong, 0L, 1L, { byteArrayOf(1).inputStream() })
                fail("addFile must reject > 65,535-byte names")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun multiPasswordZipUnlocksAndExtractsEntriesWithDistinctPasswords() = runBlocking {
        val p1 = "alpha content".toByteArray(Charsets.UTF_8)
        val p2 = "beta content".toByteArray(Charsets.UTF_8)
        val pw1 = "first-secret"
        val pw2 = "second-secret"

        fun makeAesSpec(name: String, plain: ByteArray, pw: String, saltSeed: Int): RawZipSpec {
            val salt = ByteArray(16) { (saltSeed + it).toByte() }
            val d = ZipCrypto.pbkdf2HmacSha1(pw.toByteArray(Charsets.UTF_8), salt, 1000, 66)
            val ct = deflateRaw(plain).also { WinZipAesCtrCipher(d.copyOfRange(0, 32)).processInPlace(it, 0, it.size) }
            val tag = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(d.copyOfRange(32, 64), "HmacSHA1")) }.doFinal(ct).copyOf(10)
            return RawZipSpec(name, 0x0801, 99, 0L, salt + d.copyOfRange(64, 66) + ct + tag, plain.size.toLong(), ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2), 51)
        }

        val multiZip = File(sandbox, "multi-pw.zip").apply {
            writeBytes(buildMultiEntryZip(listOf(makeAesSpec("one.txt", p1, pw1, 0x10), makeAesSpec("two.txt", p2, pw2, 0x60))))
        }

        // Per-entry password verification targets the requested entry.
        ZipCrypto.verifyPassword(multiZip, multiZip.path, pw1, targetInnerPath = "one.txt")
        ZipCrypto.verifyPassword(multiZip, multiZip.path, pw2, targetInnerPath = "two.txt")
        try {
            ZipCrypto.verifyPassword(multiZip, multiZip.path, pw1, targetInnerPath = "two.txt")
            fail("pw1 must not validate two.txt")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
            assertEquals("two.txt", e.targetInnerPath)
        }

        // Open one.txt with pw1, then hit wrong password on two.txt (must preserve one.txt's per-entry password),
        // then set pw2 for two.txt and verify BOTH entries open and full archive extracts cleanly!
        val zfs = repo.zip
        val root = FsPath.zipRoot(multiZip.path)
        zfs.setPassword(multiZip.path, pw1, innerPath = "one.txt")
        assertArrayEquals(p1, zfs.openInput(FsPath.child(root, "one.txt")).use { it.readBytes() })

        try {
            zfs.openInput(FsPath.child(root, "two.txt")).use { it.readBytes() }
            fail("two.txt must reject pw1")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
            assertEquals("two.txt", e.targetInnerPath)
        }

        zfs.setPassword(multiZip.path, pw2, innerPath = "two.txt")
        assertArrayEquals(p2, zfs.openInput(FsPath.child(root, "two.txt")).use { it.readBytes() })
        assertArrayEquals(p1, zfs.openInput(FsPath.child(root, "one.txt")).use { it.readBytes() })

        val outDir = ops.extract(multiZip.path, File(sandbox, "multi-out").apply { mkdirs() }.path, {})
        assertArrayEquals(p1, File(outDir, "one.txt").readBytes())
        assertArrayEquals(p2, File(outDir, "two.txt").readBytes())
    }

    @Test
    fun wrongZipCryptoPasswordExpansionThrowsZipPasswordRequiredException() {
        // Synthesize an encrypted ZipCrypto entry whose declared uncompressed size is smaller than the
        // actual inflated stream (mirroring a wrong password that collides on the 1-in-256 check byte
        // and expands past declared size): VerifyingInputStream must raise ZipPasswordRequiredException(wrongPassword = true).
        val realPlain = ByteArray(4096) { 0x41 }
        val pw = "zc-expand"
        val crc = CRC32().apply { update(realPlain) }.value
        val checkByte = ((crc ushr 24) and 0xFF).toInt()
        val header = ByteArray(12) { i -> if (i == 11) checkByte.toByte() else (0x10 + i).toByte() }
        val compressed = deflateRaw(realPlain)
        val engine = ZipCryptoEngine(pw.toByteArray(Charsets.UTF_8))
        engine.encryptInPlace(header, 0, header.size)
        engine.encryptInPlace(compressed, 0, compressed.size)
        val payload = header + compressed
        val zcFile = File(sandbox, "zc-expand.zip").apply {
            writeBytes(
                buildSingleEntryZip(
                    name = "expand.txt",
                    flags = 0x0801,
                    method = 8,
                    dosTime = 0x4000,
                    dosDate = 0x5A21,
                    crc = crc,
                    compSize = payload.size.toLong(),
                    uncompSize = 10L, // smaller than inflated length
                    extra = ByteArray(0),
                    payload = payload,
                    versionNeeded = 20,
                ),
            )
        }
        val zfs = ZipFileSystem().apply { setPassword(zcFile.path, pw) }
        try {
            zfs.openInput(FsPath.child(FsPath.zipRoot(zcFile.path), "expand.txt")).use { it.readBytes() }
            fail("Expected ZipPasswordRequiredException on encrypted entry expansion")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
    }

    @Test
    fun remoteArchiveStagingRespectsSizeCapAndCacheJanitorWipesEncryptedStaging() = runBlocking {
        // 1. stageRemoteArchive caps both declared oversized archives and lying-size streams, and stages inside stagingRoot.
        val stagingRoot = File(sandbox, "cache/zip-stage")
        val bigBytes = ByteArray(4096) { it.toByte() }
        var remoteModified = 1000L
        var reportDeclaredSize = 4096L
        val fakeRemote = object : FileSystem {
            override fun list(path: String) = emptyList<FileEntry>()
            override fun stat(path: String) = FileEntry(path, "big.zip", false, size = reportDeclaredSize, modified = remoteModified)
            override fun mkdir(path: String) = throw IOException("ro")
            override fun createFile(path: String) = throw IOException("ro")
            override fun delete(path: String) = throw IOException("ro")
            override fun rename(path: String, newName: String) = throw IOException("ro")
            override fun openInput(path: String): InputStream = bigBytes.inputStream()
            override fun openOutput(path: String): OutputStream = throw IOException("ro")
        }
        val remoteRepo = FileRepository(LocalFileSystem(), LocalFileSystem(), null, byScheme = mapOf("dav" to fakeRemote))
        val remoteOps = FileOperations(remoteRepo, stagingRoot = stagingRoot)

        // Declared size > maxBytes -> rejected before writing.
        try {
            remoteOps.stageRemoteArchive("dav://nas/big.zip", maxBytes = 1024L)
            fail("Oversized declared remote archive must be rejected")
        } catch (_: IOException) {
        }
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // Lying declared size (100 bytes) with 4096-byte stream -> rejected and partial file deleted.
        reportDeclaredSize = 100L
        try {
            remoteOps.stageRemoteArchive("dav://nas/big.zip", maxBytes = 1024L)
            fail("Lying oversized remote stream must be rejected")
        } catch (_: IOException) {
        }
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // Remote password stamp invalidates when remote modified/size changes.
        remoteRepo.zip.setPassword("dav://nas/big.zip", "pw-v1", remoteStamp = 1000L * 31 + 4096L)
        assertEquals("pw-v1", remoteRepo.zip.passwordFor("dav://nas/big.zip", remoteStamp = 1000L * 31 + 4096L))
        assertEquals(null, remoteRepo.zip.passwordFor("dav://nas/big.zip", remoteStamp = 2000L * 31 + 4096L))

        // 2. CacheJanitor.wipeEncryptedStaging wipes zip-open/enc, zip-stage, and (optionally) picker/enc,
        // without touching regular zip-open entries or unrelated caches.
        val cacheDir = File(sandbox, "cache-janitor-test").apply { mkdirs() }
        val encOpen = File(cacheDir, "zip-open/enc/h1/secret.txt").apply { parentFile!!.mkdirs(); writeText("s1") }
        val plainOpen = File(cacheDir, "zip-open/h2/public.txt").apply { parentFile!!.mkdirs(); writeText("p1") }
        val encPicker = File(cacheDir, "picker/enc/123/picked.txt").apply { parentFile!!.mkdirs(); writeText("s2") }
        val zipStage = File(cacheDir, "zip-stage/wf-extract-1.zip").apply { parentFile!!.mkdirs(); writeText("s3") }

        CacheJanitor.wipeEncryptedStaging(cacheDir, includePicker = false)
        assertFalse(encOpen.exists())
        assertFalse(zipStage.exists())
        assertTrue("picker/enc preserved when includePicker=false", encPicker.exists())
        assertTrue("Unencrypted zip-open preserved", plainOpen.exists())

        CacheJanitor.wipeEncryptedStaging(cacheDir, includePicker = true)
        assertFalse(encPicker.exists())
        assertTrue(plainOpen.exists())
    }

    @Test
    fun conflictReplacePreservesTargetAndMimeOnSafAndProvidersWithoutRename() = runBlocking {
        val srcPlain = File(sandbox, "report.pdf").apply { writeText("updated-pdf-payload") }
        val zipFile = File(sandbox, "report-enc.zip")
        ops.compress(
            sources = listOf(srcPlain.path),
            zipPath = zipFile.path,
            options = ZipCompressOptions(ZipCompressionLevel.Normal, password = "pdf-pw"),
        ) {}
        val zipEntryPath = FsPath.child(FsPath.zipRoot(zipFile.path), "report.pdf")

        // 1. SAF-like provider where `rename` throws UnsupportedOperationException and MIME is stamped on creation.
        val safFiles = LinkedHashMap<String, ByteArray>().apply {
            put("saf://tree1/report.pdf", "original-pdf".toByteArray(Charsets.UTF_8))
        }
        val safMimes = LinkedHashMap<String, String>().apply {
            put("saf://tree1/report.pdf", "application/pdf")
        }
        var safDeletes = 0
        val fakeSaf = object : FileSystem {
            override fun list(path: String): List<FileEntry> = safFiles.keys
                .filter { FsPath.parent(it) == path }
                .map { FileEntry(it, FsPath.name(it), false, size = safFiles[it]!!.size.toLong(), modified = 1000L) }
            override fun stat(path: String): FileEntry? =
                if (path == "saf://tree1") FileEntry(path, "tree1", true, modified = 1000L)
                else safFiles[path]?.let { FileEntry(path, FsPath.name(path), false, size = it.size.toLong(), modified = 1000L) }
            override fun mkdir(path: String) {}
            override fun createFile(path: String) {
                safFiles[path] = ByteArray(0)
                safMimes.putIfAbsent(path, if (path.endsWith(".pdf")) "application/pdf" else "application/octet-stream")
            }
            override fun delete(path: String) {
                safDeletes++
                safFiles.remove(path)
                safMimes.remove(path)
            }
            override fun rename(path: String, newName: String): String =
                throw UnsupportedOperationException("DocumentsProvider does not support renameDocument")
            override fun openInput(path: String): InputStream =
                (safFiles[path] ?: throw IOException("404")).inputStream()
            override fun openOutput(path: String): OutputStream {
                safMimes.putIfAbsent(path, if (path.endsWith(".pdf")) "application/pdf" else "application/octet-stream")
                return object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        safFiles[path] = toByteArray()
                    }
                }
            }
        }

        // 2. Path-addressed WebDAV-like provider where `rename` throws UnsupportedOperationException on one share
        // and succeeds on another, recording created staging filenames so we verify extension preservation.
        val davFiles = LinkedHashMap<String, ByteArray>().apply {
            put("dav://nas/no-rename/report.pdf", "dav-original".toByteArray(Charsets.UTF_8))
            put("dav://nas/with-rename/report.pdf", "dav-rename-orig".toByteArray(Charsets.UTF_8))
        }
        val davCreatedNames = ArrayList<String>()
        val fakeDav = object : FileSystem {
            override fun list(path: String): List<FileEntry> = emptyList()
            override fun stat(path: String): FileEntry? = when {
                path in setOf("dav://nas", "dav://nas/no-rename", "dav://nas/with-rename") ->
                    FileEntry(path, FsPath.name(path), true, modified = 1000L)
                davFiles.containsKey(path) ->
                    FileEntry(path, FsPath.name(path), false, size = davFiles[path]!!.size.toLong(), modified = 1000L)
                else -> null
            }
            override fun mkdir(path: String) {}
            override fun createFile(path: String) { davFiles[path] = ByteArray(0) }
            override fun delete(path: String) { davFiles.remove(path) }
            override fun rename(path: String, newName: String): String {
                if (path.startsWith("dav://nas/no-rename/")) {
                    throw UnsupportedOperationException("rename disabled")
                }
                val bytes = davFiles.remove(path) ?: throw IOException("missing $path")
                val dst = FsPath.child(FsPath.parent(path)!!, newName)
                if (davFiles.containsKey(dst)) throw IOException("exists $dst")
                davFiles[dst] = bytes
                return dst
            }
            override fun openInput(path: String): InputStream =
                (davFiles[path] ?: throw IOException("404 $path")).inputStream()
            override fun openOutput(path: String): OutputStream {
                if (!davFiles.containsKey(path)) davCreatedNames += FsPath.name(path)
                return object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        davFiles[path] = toByteArray()
                    }
                }
            }
        }

        val customRepo = FileRepository(
            LocalFileSystem(),
            LocalFileSystem(),
            null,
            byScheme = mapOf("saf" to fakeSaf, "dav" to fakeDav),
        )
        val customOps = FileOperations(customRepo, stagingRoot = File(sandbox, "replace-stage"))
        val entryOnCustom = FsPath.child(FsPath.zipRoot(zipFile.path), "report.pdf")

        // Wrong password on SAF Replace must NOT delete or corrupt the existing SAF document.
        customRepo.zip.setPassword(zipFile.path, "wrong-pw")
        try {
            customOps.transfer(listOf(entryOnCustom), "saf://tree1", move = false, conflict = Conflict.Replace) {}
            fail("Expected wrong password failure")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
        }
        assertEquals("original-pdf", String(safFiles["saf://tree1/report.pdf"]!!, Charsets.UTF_8))
        assertEquals("application/pdf", safMimes["saf://tree1/report.pdf"])
        assertEquals("SAF target must never be deleted on Replace", 0, safDeletes)

        // Valid password on SAF Replace overwrites in place, preserving MIME and never calling rename/delete.
        customRepo.zip.setPassword(zipFile.path, "pdf-pw")
        customOps.transfer(listOf(entryOnCustom), "saf://tree1", move = false, conflict = Conflict.Replace) {}
        assertEquals("updated-pdf-payload", String(safFiles["saf://tree1/report.pdf"]!!, Charsets.UTF_8))
        assertEquals("application/pdf", safMimes["saf://tree1/report.pdf"])
        assertEquals(0, safDeletes)

        // Path-addressed provider WITHOUT rename: wrong password leaves target intact; valid password replaces without data loss.
        customRepo.zip.setPassword(zipFile.path, "wrong-pw")
        try {
            customOps.transfer(listOf(entryOnCustom), "dav://nas/no-rename", move = false, conflict = Conflict.Replace) {}
            fail("Expected wrong password failure")
        } catch (_: ZipPasswordRequiredException) {
        }
        assertEquals("dav-original", String(davFiles["dav://nas/no-rename/report.pdf"]!!, Charsets.UTF_8))

        customRepo.zip.setPassword(zipFile.path, "pdf-pw")
        customOps.transfer(listOf(entryOnCustom), "dav://nas/no-rename", move = false, conflict = Conflict.Replace) {}
        assertEquals("updated-pdf-payload", String(davFiles["dav://nas/no-rename/report.pdf"]!!, Charsets.UTF_8))
        assertEquals(setOf("dav://nas/no-rename/report.pdf"), davFiles.keys.filter { it.startsWith("dav://nas/no-rename/") }.toSet())

        // Path-addressed provider WITH rename: staged sibling name preserves `.pdf` extension and swaps cleanly.
        customOps.transfer(listOf(entryOnCustom), "dav://nas/with-rename", move = false, conflict = Conflict.Replace) {}
        assertEquals("updated-pdf-payload", String(davFiles["dav://nas/with-rename/report.pdf"]!!, Charsets.UTF_8))
        assertTrue("Staged sibling names must preserve .pdf extension: $davCreatedNames", davCreatedNames.all { it.endsWith(".pdf") })

        // Long Unicode filename clamping preserves extension and stays <= 255 UTF-8 bytes.
        val longName = "𐐷".repeat(90) + ".tar.gz"
        val clamped = FileOperations.buildTempSiblingName("wf-stage", longName)
        assertTrue(clamped.endsWith(".tar.gz"))
        assertTrue(clamped.toByteArray(Charsets.UTF_8).size <= FsPath.MAX_NAME_BYTES)
    }

    @Test
    fun caseInsensitiveDuplicateEntryPathsAbortExtractionWithoutOverwritingEncryptedEntry() = runBlocking {
        val secretBytes = "trusted-authenticated-script".toByteArray(Charsets.UTF_8)
        val attackerBytes = "unauthenticated-overwrite".toByteArray(Charsets.UTF_8)
        val pw = "case-secret"
        val salt = ByteArray(16) { (0x33 + it).toByte() }
        val derived = ZipCrypto.pbkdf2HmacSha1(pw.toByteArray(Charsets.UTF_8), salt, 1000, 66)
        val ct = deflateRaw(secretBytes).also { WinZipAesCtrCipher(derived.copyOfRange(0, 32)).processInPlace(it, 0, it.size) }
        val tag = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA1")) }.doFinal(ct).copyOf(10)

        val collidingZip = File(sandbox, "case-collide.zip").apply {
            writeBytes(
                buildMultiEntryZip(
                    listOf(
                        RawZipSpec(
                            name = "bin/deploy.sh",
                            flags = 0x0801,
                            method = 99,
                            crc = 0L,
                            payload = salt + derived.copyOfRange(64, 66) + ct + tag,
                            uncompSize = secretBytes.size.toLong(),
                            extra = ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2),
                            versionNeeded = 51,
                        ),
                        RawZipSpec(
                            name = "BIN/deploy.sh",
                            flags = 0x0800,
                            method = 0,
                            crc = CRC32().apply { update(attackerBytes) }.value,
                            payload = attackerBytes,
                            uncompSize = attackerBytes.size.toLong(),
                            extra = ByteArray(0),
                            versionNeeded = 20,
                        ),
                    ),
                ),
            )
        }

        // Case-insensitive remote/provider FS (`dav://`) must reject `BIN/deploy.sh` colliding with `bin/deploy.sh`
        // and clean up the extraction target so attacker bytes never survive.
        val ciFiles = LinkedHashMap<String, ByteArray>()
        val ciDirs = LinkedHashSet<String>().apply { add("dav://ci") }
        fun ciKey(p: String) = p.lowercase(java.util.Locale.ROOT)
        val caseInsensitiveFs = object : FileSystem {
            override fun list(path: String): List<FileEntry> = emptyList()
            override fun stat(path: String): FileEntry? {
                val k = ciKey(path)
                if (k in ciDirs) return FileEntry(path, FsPath.name(path), true, modified = 1000L)
                return ciFiles[k]?.let { FileEntry(path, FsPath.name(path), false, size = it.size.toLong(), modified = 1000L) }
            }
            override fun mkdir(path: String) { ciDirs += ciKey(path) }
            override fun createFile(path: String) { ciFiles[ciKey(path)] = ByteArray(0) }
            override fun delete(path: String) {
                val k = ciKey(path)
                ciDirs.removeIf { it == k || it.startsWith("$k/") }
                ciFiles.keys.removeIf { it == k || it.startsWith("$k/") }
            }
            override fun rename(path: String, newName: String): String = throw IOException("ro")
            override fun openInput(path: String): InputStream = (ciFiles[ciKey(path)] ?: throw IOException("404")).inputStream()
            override fun openOutput(path: String): OutputStream = object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    ciFiles[ciKey(path)] = toByteArray()
                }
            }
        }
        val ciRepo = FileRepository(LocalFileSystem(), LocalFileSystem(), null, byScheme = mapOf("dav" to caseInsensitiveFs))
        val ciOps = FileOperations(ciRepo, stagingRoot = File(sandbox, "ci-stage"))
        try {
            ciOps.extract(collidingZip.path, "dav://ci", {}, password = pw)
            fail("Expected case-colliding entry paths to abort extraction")
        } catch (e: IOException) {
            assertTrue("Unexpected error: ${e.message}", e.message.orEmpty().contains("Duplicate", ignoreCase = true))
        }
        assertTrue("Partial extracted files must be cleaned up on case collision", ciFiles.isEmpty())
    }

    @Test
    fun cachedPasswordConcurrentLookupAndWipeNeverReturnsZeroedNulString() {
        val zfs = ZipFileSystem()
        val zipFile = File(sandbox, "race.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)) }
        val validPasswords = setOf("alpha-secret-1234", "bravo-secret-5678", "charlie-secret-9012")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val writers = (0 until 3).map { idx ->
            Thread {
                val pws = validPasswords.toList()
                var i = 0
                while (!stop.get() && failure.get() == null) {
                    val pw = pws[(i + idx) % pws.size]
                    zfs.setPassword(zipFile.path, pw, innerPath = "a.txt")
                    if (i % 4 == 0) zfs.clearPassword(zipFile.path, innerPath = "a.txt")
                    if (i % 11 == 0) zfs.clearAllPasswords()
                    i++
                }
            }.apply { start() }
        }
        val readers = (0 until 6).map {
            Thread {
                repeat(4_000) {
                    if (failure.get() != null) return@Thread
                    val got = zfs.passwordFor(zipFile.path, innerPath = "a.txt")
                    if (got != null && (got.contains('\u0000') || got !in validPasswords)) {
                        failure.compareAndSet(null, AssertionError("Corrupted concurrent password read: '$got'"))
                        return@Thread
                    }
                }
            }.apply { start() }
        }
        readers.forEach { it.join() }
        stop.set(true)
        writers.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    @Test
    fun multiPasswordFallbackFlagsFallbackFailureAndRemoteFirstEncryptedInnerPathFollowsPhysicalOrder() = runBlocking {
        val largePlain = ByteArray(1024) { (0x41 + (it % 26)).toByte() }
        val tinyPlain = "tiny".toByteArray(Charsets.UTF_8)
        val pw1 = "entry-one-pw"
        val pw2 = "entry-two-pw"

        fun makeAesSpec(name: String, plain: ByteArray, pw: String, saltSeed: Int): RawZipSpec {
            val salt = ByteArray(16) { (saltSeed + it).toByte() }
            val d = ZipCrypto.pbkdf2HmacSha1(pw.toByteArray(Charsets.UTF_8), salt, 1000, 66)
            val ct = deflateRaw(plain).also { WinZipAesCtrCipher(d.copyOfRange(0, 32)).processInPlace(it, 0, it.size) }
            val tag = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(d.copyOfRange(32, 64), "HmacSHA1")) }.doFinal(ct).copyOf(10)
            return RawZipSpec(name, 0x0801, 99, 0L, salt + d.copyOfRange(64, 66) + ct + tag, plain.size.toLong(), ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2), 51)
        }

        // Physical order: `first-large.bin` (larger!) comes BEFORE `second-tiny.txt` (smaller!).
        val multiZip = File(sandbox, "ordered-multi.zip").apply {
            writeBytes(
                buildMultiEntryZip(
                    listOf(
                        makeAesSpec("first-large.bin", largePlain, pw1, 0x20),
                        makeAesSpec("second-tiny.txt", tinyPlain, pw2, 0x70),
                    ),
                ),
            )
        }

        val zfs = ZipFileSystem()
        // 1. firstEncryptedInnerPath picks `first-large.bin` first despite `second-tiny.txt` being smaller.
        val firstInner = ZipCrypto.firstEncryptedInnerPath(multiZip) { inner -> zfs.passwordFor(multiZip.path, innerPath = inner) == null }
        assertEquals("first-large.bin", firstInner)
        ZipCrypto.verifyPassword(multiZip, multiZip.path, pw1, targetInnerPath = firstInner)
        zfs.setPassword(multiZip.path, pw1, innerPath = firstInner)

        // 2. Opening `second-tiny.txt` tries the archive-wide fallback `pw1`, fails, and marks `fromArchiveFallback = true`
        // so the UI prompts cleanly for `second-tiny.txt` without showing a false "Incorrect password" error.
        try {
            zfs.openInput(FsPath.child(FsPath.zipRoot(multiZip.path), "second-tiny.txt")).use { it.readBytes() }
            fail("Expected password prompt for second-tiny.txt")
        } catch (e: ZipPasswordRequiredException) {
            assertTrue(e.wrongPassword)
            assertTrue("Expected fromArchiveFallback=true on untouched second entry", e.fromArchiveFallback)
            assertEquals("second-tiny.txt", e.targetInnerPath)
        }

        // 3. Now `firstEncryptedInnerPath` advances to `second-tiny.txt` while `first-large.bin` stays unlocked.
        val secondInner = ZipCrypto.firstEncryptedInnerPath(multiZip) { inner -> zfs.passwordFor(multiZip.path, innerPath = inner) == null }
        assertEquals("second-tiny.txt", secondInner)
    }

    @Test
    fun verifyPasswordHonorsInterruptionAndRejectsMalformedOrMismatchedAesHeaders() = runBlocking {
        val src = File(sandbox, "interrupt.bin").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        val aesZip = File(sandbox, "interrupt.zip")
        ops.compress(listOf(src.path), aesZip.path, ZipCompressOptions(ZipCompressionLevel.None, password = "int-pw")) {}

        // 1a. Pre-interrupted thread aborts verifyPassword immediately (either via FileChannel ClosedByInterruptException
        // or verifyAesHmac InterruptedIOException) without hashing the full archive.
        val caught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread {
            Thread.currentThread().interrupt()
            try {
                ZipCrypto.verifyPassword(aesZip, aesZip.path, "int-pw")
            } catch (t: Throwable) {
                caught.set(t)
            }
        }
        worker.start()
        worker.join()
        assertTrue(
            "Expected interruption IOException when thread is interrupted, got: ${caught.get()}",
            caught.get() is java.io.InterruptedIOException || caught.get() is java.nio.channels.ClosedByInterruptException,
        )

        // 1b. Direct verifyAesHmac over a non-channel InputStream aborts immediately with InterruptedIOException.
        val hmacCaught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val hmacWorker = Thread {
            Thread.currentThread().interrupt()
            try {
                ZipCrypto.verifyAesHmac(
                    inp = ByteArray(128 * 1024).inputStream(),
                    cipherLen = 128L * 1024L - 10L,
                    hmacKey = ByteArray(32) { 1 },
                    archivePath = aesZip.path,
                )
            } catch (t: Throwable) {
                hmacCaught.set(t)
            }
        }
        hmacWorker.start()
        hmacWorker.join()
        assertTrue(
            "Expected InterruptedIOException from verifyAesHmac, got: ${hmacCaught.get()}",
            hmacCaught.get() is java.io.InterruptedIOException,
        )

        // 2. Archive with method=99 (WinZip AES) but GPB bit 0 cleared is flagged encrypted and rejected as malformed.
        val missingBit0Zip = File(sandbox, "aes-no-bit0.zip").apply {
            writeBytes(
                buildSingleEntryZip(
                    name = "bad.txt",
                    flags = 0x0800, // bit 0 NOT set!
                    method = 99,
                    dosTime = 0x4000,
                    dosDate = 0x5A21,
                    crc = 0L,
                    compSize = 32L,
                    uncompSize = 4L,
                    extra = ZipCrypto.buildAesExtraField(AesStrength.AES256, 0, 2),
                    payload = ByteArray(32),
                    versionNeeded = 51,
                ),
            )
        }
        assertTrue(ZipCrypto.isArchiveEncrypted(missingBit0Zip))
        ZipCrypto.openZipFile(missingBit0Zip).use { z ->
            val enc = ZipCrypto.detectEncryption(z.getEntry("bad.txt"))
            assertTrue("Expected Unsupported for method=99 without bit 0, got $enc", enc is ZipEntryEncryption.Unsupported)
        }

        // 3. Archive where Central Directory `0x9901` disagrees with Local File Header `0x9901` is rejected.
        val validBytes = aesZip.readBytes()
        // Mutate the LFH `0x9901` version from 2 (AE-2) to 1 (AE-1) while leaving CD `0x9901` at 2.
        val lfhAesHeader = byteArrayOf(0x01, 0x99.toByte(), 0x07, 0x00, 0x02, 0x00, 'A'.code.toByte(), 'E'.code.toByte())
        val firstIdx = indexOfBytes(validBytes, lfhAesHeader)
        assertTrue(firstIdx >= 0)
        val mismatched = validBytes.copyOf()
        mismatched[firstIdx + 4] = 0x01 // LFH says AE-1, CD still says AE-2
        val mismatchFile = File(sandbox, "aes-mismatch.zip").apply { writeBytes(mismatched) }
        ZipCrypto.openZipFile(mismatchFile).use { z ->
            val enc = ZipCrypto.detectEncryption(z.getEntry("interrupt.bin"))
            assertTrue("Expected Unsupported for mismatched CD vs LFH 0x9901, got $enc", enc is ZipEntryEncryption.Unsupported)
        }
    }

    @Test
    fun sessionScopedCacheJanitorProtectsActiveSiblingWindowsAndExpiresPickerEnc() {
        val cacheDir = File(sandbox, "multi-win-cache").apply { mkdirs() }
        val now = 1_800_000_000_000L
        CacheJanitor.registerSession("win-active-1")
        CacheJanitor.registerSession("win-active-2")
        try {
            val activeOpen1 = File(cacheDir, "zip-open/enc/win-active-1/h1/secret1.txt").apply { parentFile!!.mkdirs(); writeText("w1") }
            val activeStage1 = File(cacheDir, "zip-stage/win-active-1/remote1.zip").apply { parentFile!!.mkdirs(); writeText("s1") }
            val activeOpen2 = File(cacheDir, "zip-open/enc/win-active-2/h2/secret2.txt").apply { parentFile!!.mkdirs(); writeText("w2") }
            val orphanOpen = File(cacheDir, "zip-open/enc/crashed-session/h3/old.txt").apply { parentFile!!.mkdirs(); writeText("old") }
            val freshPicker = File(cacheDir, "picker/enc/fresh/picked.txt").apply {
                parentFile!!.mkdirs()
                writeText("fresh")
                setLastModified(now - 2 * 60_000L)
                parentFile!!.setLastModified(now - 2 * 60_000L)
            }
            val stalePicker = File(cacheDir, "picker/enc/stale/picked.txt").apply {
                parentFile!!.mkdirs()
                writeText("stale")
                setLastModified(now - CacheJanitor.PICKER_ENC_TTL_MS - 60_000L)
                parentFile!!.setLastModified(now - CacheJanitor.PICKER_ENC_TTL_MS - 60_000L)
            }

            // Simulates a new window starting up (`sweepOrphanedEncryptedStaging`): orphans & expired picker/enc are
            // deleted, while active sibling windows and fresh picker/enc remain untouched.
            CacheJanitor.sweepOrphanedEncryptedStaging(cacheDir, nowMillis = now)
            assertTrue(activeOpen1.exists())
            assertTrue(activeStage1.exists())
            assertTrue(activeOpen2.exists())
            assertFalse("Orphaned session dir must be swept", orphanOpen.exists())
            assertTrue("Fresh picker/enc must survive", freshPicker.exists())
            assertFalse("Expired picker/enc (>15 min) must be evicted", stalePicker.exists())

            // Closing window 2 (`unregisterSession`) deletes ONLY window 2's staging, keeping window 1 intact.
            CacheJanitor.unregisterSession(cacheDir, "win-active-2")
            assertFalse(activeOpen2.exists())
            assertTrue("Sibling window 1 decrypted open must survive window 2 closing", activeOpen1.exists())
            assertTrue("Sibling window 1 stage dir must survive window 2 closing", activeStage1.exists())
            assertTrue(freshPicker.exists())
        } finally {
            CacheJanitor.unregisterSession(cacheDir, "win-active-1")
            CacheJanitor.unregisterSession(cacheDir, "win-active-2")
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private data class RawZipSpec(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val payload: ByteArray,
        val uncompSize: Long,
        val extra: ByteArray,
        val versionNeeded: Int,
        val externalAttrs: Long = 0x81A40000L,
    )

    private fun buildMultiEntryZip(specs: List<RawZipSpec>): ByteArray {
        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
        for (s in specs) {
            val offset = out.size().toLong()
            val nameBytes = s.name.toByteArray(Charsets.UTF_8)
            out.write(u32(0x04034B50)); out.write(u16(s.versionNeeded)); out.write(u16(s.flags)); out.write(u16(s.method))
            out.write(u16(0x4000)); out.write(u16(0x5A21)); out.write(u32(s.crc)); out.write(u32(s.payload.size.toLong()))
            out.write(u32(s.uncompSize)); out.write(u16(nameBytes.size)); out.write(u16(s.extra.size))
            out.write(nameBytes); out.write(s.extra); out.write(s.payload)

            cd.write(u32(0x02014B50)); cd.write(u16((3 shl 8) or s.versionNeeded)); cd.write(u16(s.versionNeeded))
            cd.write(u16(s.flags)); cd.write(u16(s.method)); cd.write(u16(0x4000)); cd.write(u16(0x5A21))
            cd.write(u32(s.crc)); cd.write(u32(s.payload.size.toLong())); cd.write(u32(s.uncompSize))
            cd.write(u16(nameBytes.size)); cd.write(u16(s.extra.size)); cd.write(u16(0))
            cd.write(u16(0)); cd.write(u16(0)); cd.write(u32(s.externalAttrs)); cd.write(u32(offset))
            cd.write(nameBytes); cd.write(s.extra)
        }
        val cdOffset = out.size().toLong()
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)
        out.write(u32(0x06054B50)); out.write(u16(0)); out.write(u16(0))
        out.write(u16(specs.size)); out.write(u16(specs.size))
        out.write(u32(cdBytes.size.toLong())); out.write(u32(cdOffset)); out.write(u16(0))
        return out.toByteArray()
    }

    private fun buildSingleEntryZipWithZip64Size(
        name: String,
        compressed: ByteArray,
        declaredUncompSize: Long,
        crc: Long,
    ): ByteArray {
        val z64Extra = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0001)
            .putShort(16)
            .putLong(declaredUncompSize)
            .putLong(compressed.size.toLong())
            .array()
        return buildSingleEntryZip(
            name = name,
            flags = 0x0800,
            method = 8,
            dosTime = 0x4000,
            dosDate = 0x5A21,
            crc = crc,
            compSize = 0xFFFFFFFFL,
            uncompSize = 0xFFFFFFFFL,
            extra = z64Extra,
            payload = compressed,
            versionNeeded = 45,
        )
    }

    private fun buildMixedZip(publicData: ByteArray, secretData: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

        fun addEntry(name: String, flags: Int, method: Int, crc: Long, payload: ByteArray, uncomp: Long, extra: ByteArray, ver: Int) {
            val offset = out.size().toLong()
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.write(u32(0x04034B50)); out.write(u16(ver)); out.write(u16(flags)); out.write(u16(method))
            out.write(u16(0x4000)); out.write(u16(0x5A21)); out.write(u32(crc)); out.write(u32(payload.size.toLong()))
            out.write(u32(uncomp)); out.write(u16(nameBytes.size)); out.write(u16(extra.size)); out.write(nameBytes); out.write(extra); out.write(payload)

            cd.write(u32(0x02014B50)); cd.write(u16(ver)); cd.write(u16(ver)); cd.write(u16(flags)); cd.write(u16(method))
            cd.write(u16(0x4000)); cd.write(u16(0x5A21)); cd.write(u32(crc)); cd.write(u32(payload.size.toLong()))
            cd.write(u32(uncomp)); cd.write(u16(nameBytes.size)); cd.write(u16(extra.size)); cd.write(u16(0))
            cd.write(u16(0)); cd.write(u16(0)); cd.write(u32(0)); cd.write(u32(offset)); cd.write(nameBytes); cd.write(extra)
        }

        // 1. Unencrypted entry
        val pubCrc = CRC32().apply { update(publicData) }.value
        addEntry("public.txt", 0x0800, 0, pubCrc, publicData, publicData.size.toLong(), ByteArray(0), 20)

        // 2. AES-256 encrypted entry
        val salt = ByteArray(16) { (0x50 + it).toByte() }
        val derived = ZipCrypto.pbkdf2HmacSha1(password.toByteArray(Charsets.UTF_8), salt, 1000, 66)
        val aesKey = derived.copyOfRange(0, 32)
        val hmacKey = derived.copyOfRange(32, 64)
        val pvv = derived.copyOfRange(64, 66)
        val comp = deflateRaw(secretData)
        WinZipAesCtrCipher(aesKey).processInPlace(comp, 0, comp.size)
        val tag = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(hmacKey, "HmacSHA1")) }.doFinal(comp).copyOf(10)
        val encPayload = salt + pvv + comp + tag
        addEntry(
            "vault/secret.txt", 0x0801, 99, 0L, encPayload, secretData.size.toLong(),
            ZipCrypto.buildAesExtraField(AesStrength.AES256, 8, 2), 51,
        )

        val cdOffset = out.size().toLong()
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)
        out.write(u32(0x06054B50)); out.write(u16(0)); out.write(u16(0)); out.write(u16(2)); out.write(u16(2))
        out.write(u32(cdBytes.size.toLong())); out.write(u32(cdOffset)); out.write(u16(0))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ Hand-crafted test archive helpers

    private fun deflateRaw(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun buildHandcraftedWinZipAes(
        name: String,
        plain: ByteArray,
        password: String,
        strength: AesStrength,
        version: Int,
        deflate: Boolean,
        corruptCrc: Boolean = false,
    ): ByteArray {
        val actualMethod = if (deflate) 8 else 0
        val compressed = if (deflate) deflateRaw(plain) else plain.copyOf()
        val salt = ByteArray(strength.saltBytes) { (0xA0 + it).toByte() }
        val derived = ZipCrypto.pbkdf2HmacSha1(password.toByteArray(Charsets.UTF_8), salt, 1000, strength.keyBytes * 2 + 2)
        val aesKey = derived.copyOfRange(0, strength.keyBytes)
        val hmacKey = derived.copyOfRange(strength.keyBytes, strength.keyBytes * 2)
        val pvv = derived.copyOfRange(strength.keyBytes * 2, strength.keyBytes * 2 + 2)

        val cipherText = compressed.copyOf()
        WinZipAesCtrCipher(aesKey).processInPlace(cipherText, 0, cipherText.size)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(hmacKey, "HmacSHA1")) }
        val tag = mac.doFinal(cipherText).copyOf(10)
        val payload = salt + pvv + cipherText + tag
        val crc = if (version == 1) {
            CRC32().apply { update(plain) }.value.let { if (corruptCrc) it xor 0xFF else it }
        } else 0L
        val extra = ZipCrypto.buildAesExtraField(strength, actualMethod, version)
        return buildSingleEntryZip(
            name = name,
            flags = 0x0801,
            method = 99,
            dosTime = 0x5234,
            dosDate = 0x5A21,
            crc = crc,
            compSize = payload.size.toLong(),
            uncompSize = plain.size.toLong(),
            extra = extra,
            payload = payload,
            versionNeeded = 51,
        )
    }

    private fun buildHandcraftedZipCrypto(
        name: String,
        plain: ByteArray,
        password: String,
        deflate: Boolean,
        useDataDescriptor: Boolean,
        addConflictingX5455: Boolean = false,
    ): ByteArray {
        val crc = CRC32().apply { update(plain) }.value
        val dosTime = 0x7A42 // high byte = 0x7A
        val dosDate = 0x5A21
        val checkByte = if (useDataDescriptor) (dosTime ushr 8) and 0xFF else ((crc ushr 24) and 0xFF).toInt()

        val header = ByteArray(12) { i -> if (i == 11) checkByte.toByte() else (0x11 + i).toByte() }
        val compressed = if (deflate) deflateRaw(plain) else plain.copyOf()
        val engine = ZipCryptoEngine(password.toByteArray(Charsets.UTF_8))
        engine.encryptInPlace(header, 0, header.size)
        engine.encryptInPlace(compressed, 0, compressed.size)
        val payload = header + compressed

        // Optional X5455 Extended Timestamp with a completely different UTC epoch so entry.time != dosTime.
        val x5455 = if (addConflictingX5455) {
            ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x5455)
                .putShort(5)
                .put(1) // mtime present
                .putInt(1_700_000_123)
                .array()
        } else ByteArray(0)

        val flags = 0x0801 or (if (useDataDescriptor) 0x0008 else 0)
        return buildSingleEntryZip(
            name = name,
            flags = flags,
            method = if (deflate) 8 else 0,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = crc,
            compSize = payload.size.toLong(),
            uncompSize = plain.size.toLong(),
            extra = x5455,
            payload = payload,
            versionNeeded = 20,
            lfhZeroSizes = useDataDescriptor,
        )
    }

    private fun buildSingleEntryZip(
        name: String,
        flags: Int,
        method: Int,
        dosTime: Int,
        dosDate: Int,
        crc: Long,
        compSize: Long,
        uncompSize: Long,
        extra: ByteArray,
        payload: ByteArray,
        versionNeeded: Int,
        lfhZeroSizes: Boolean = false,
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

        // Local File Header
        out.write(u32(0x04034B50))
        out.write(u16(versionNeeded))
        out.write(u16(flags))
        out.write(u16(method))
        out.write(u16(dosTime))
        out.write(u16(dosDate))
        out.write(u32(if (lfhZeroSizes) 0L else crc))
        out.write(u32(if (lfhZeroSizes) 0L else compSize))
        out.write(u32(if (lfhZeroSizes) 0L else uncompSize))
        out.write(u16(nameBytes.size))
        out.write(u16(extra.size))
        out.write(nameBytes)
        out.write(extra)
        out.write(payload)
        if (lfhZeroSizes) {
            out.write(u32(0x08074B50))
            out.write(u32(crc))
            out.write(u32(compSize))
            out.write(u32(uncompSize))
        }
        val cdOffset = out.size().toLong()
        val cd = ByteArrayOutputStream()
        cd.write(u32(0x02014B50))
        cd.write(u16(versionNeeded))
        cd.write(u16(versionNeeded))
        cd.write(u16(flags))
        cd.write(u16(method))
        cd.write(u16(dosTime))
        cd.write(u16(dosDate))
        cd.write(u32(crc))
        cd.write(u32(compSize))
        cd.write(u32(uncompSize))
        cd.write(u16(nameBytes.size))
        cd.write(u16(extra.size))
        cd.write(u16(0)) // comment
        cd.write(u16(0)) // disk
        cd.write(u16(0)) // internal attrs
        cd.write(u32(0)) // external attrs
        cd.write(u32(0)) // local header offset
        cd.write(nameBytes)
        cd.write(extra)
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)
        out.write(u32(0x06054B50))
        out.write(u16(0))
        out.write(u16(0))
        out.write(u16(1))
        out.write(u16(1))
        out.write(u32(cdBytes.size.toLong()))
        out.write(u32(cdOffset))
        out.write(u16(0))
        return out.toByteArray()
    }
}
