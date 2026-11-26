// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipShort
import org.apache.commons.compress.archivers.zip.ZipUtil

/** User-facing compression level options for creating ZIP archives. */
enum class ZipCompressionLevel(val deflateLevel: Int) {
    /** No compression (STORED method 0). */
    None(Deflater.NO_COMPRESSION),
    /** Fast deflate (level 1). */
    Fast(Deflater.BEST_SPEED),
    /** Balanced deflate (level 6, standard default). */
    Normal(6),
    /** Smallest output (level 9). */
    Maximum(Deflater.BEST_COMPRESSION),
}

/** Optional settings for creating a ZIP archive. */
data class ZipCompressOptions(
    val level: ZipCompressionLevel = ZipCompressionLevel.Normal,
    /** When non-null and non-empty, file entries are encrypted using WinZip AES-256 (AE-2). */
    val password: String? = null,
    /** When true, skips files and folders that are hidden or start with `.`. */
    val excludeHidden: Boolean = false,
) {
    val effectivePassword: String? get() = password?.takeIf { it.isNotEmpty() }

    override fun toString(): String =
        "ZipCompressOptions(level=$level, password=${if (effectivePassword != null) "***" else "null"}, excludeHidden=$excludeHidden)"
}

/**
 * Thrown when reading or extracting an encrypted ZIP entry without a valid password.
 * Extends [IOException] so untrusted-archive fuzzing and callers expecting only [IOException] remain safe.
 */
class ZipPasswordRequiredException(
    val archivePath: String,
    val wrongPassword: Boolean = false,
    message: String = if (wrongPassword) "Incorrect password" else "Password required to read this archive",
) : IOException(message)

/** WinZip AES strength codes from extra field `0x9901`. */
enum class AesStrength(val code: Int, val keyBytes: Int, val saltBytes: Int) {
    AES128(1, 16, 8),
    AES192(2, 24, 12),
    AES256(3, 32, 16);

    companion object {
        fun fromCode(code: Int): AesStrength? = entries.firstOrNull { it.code == code }
    }
}

/** Classification of a ZIP entry's encryption scheme. */
sealed interface ZipEntryEncryption {
    data object None : ZipEntryEncryption
    data class WinZipAes(
        val version: Int, // 1 = AE-1 (CRC kept), 2 = AE-2 (CRC zeroed)
        val strength: AesStrength,
        val actualMethod: Int, // 0 = STORED, 8 = DEFLATED
    ) : ZipEntryEncryption
    data object ZipCrypto : ZipEntryEncryption
    data class Unsupported(val description: String) : ZipEntryEncryption
}

/**
 * WinZip AES-CTR keystream generator using standard `AES/ECB/NoPadding`.
 *
 * Per the WinZip AES specification, the 16-byte counter is **little-endian** and starts at `1`
 * (`[1, 0, ..., 0]`), unlike standard NIST big-endian CTR mode. Counter blocks are generated in
 * 4 KiB batches for high throughput without extra library dependencies.
 */
internal class WinZipAesCtrCipher(key: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = ByteArray(BLOCK_SIZE).apply { this[0] = 1 }
    private val counterBatch = ByteArray(BATCH_BYTES)
    private val keystream = ByteArray(BATCH_BYTES)
    private var ksPos = BATCH_BYTES

    /** XORs [len] bytes of [buf] starting at [off] in place (identical for encrypt and decrypt). */
    fun processInPlace(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (ksPos >= BATCH_BYTES) refill()
            val n = minOf(end - i, BATCH_BYTES - ksPos)
            var j = 0
            while (j < n) {
                buf[i + j] = (buf[i + j].toInt() xor keystream[ksPos + j].toInt()).toByte()
                j++
            }
            i += n
            ksPos += n
        }
    }

    private fun refill() {
        var pos = 0
        while (pos < BATCH_BYTES) {
            System.arraycopy(counter, 0, counterBatch, pos, BLOCK_SIZE)
            incrementCounterLe()
            pos += BLOCK_SIZE
        }
        cipher.update(counterBatch, 0, BATCH_BYTES, keystream, 0)
        ksPos = 0
    }

    private fun incrementCounterLe() {
        for (i in 0 until BLOCK_SIZE) {
            val next = (counter[i].toInt() and 0xFF) + 1
            counter[i] = next.toByte()
            if (next <= 0xFF) break
        }
    }

    fun clear() {
        counter.fill(0)
        counterBatch.fill(0)
        keystream.fill(0)
        ksPos = BATCH_BYTES
    }

    companion object {
        private const val BLOCK_SIZE = 16
        private const val BATCH_BLOCKS = 256
        private const val BATCH_BYTES = BLOCK_SIZE * BATCH_BLOCKS
    }
}

/**
 * Traditional PKWARE ZipCrypto stream cipher (APPNOTE.TXT §6.1).
 * Used for reading legacy password-protected ZIPs created by Windows Explorer, macOS `zip -e`, etc.
 */
internal class ZipCryptoEngine(passwordBytes: ByteArray) {
    private var key0: Int = 0x12345678
    private var key1: Int = 0x23456789
    private var key2: Int = 0x34567890

    init {
        for (b in passwordBytes) {
            updateKeys(b.toInt() and 0xFF)
        }
    }

    private fun updateKeys(b: Int) {
        key0 = crc32Update(key0, b)
        key1 = (key1 + (key0 and 0xFF)) * 134775813 + 1
        key2 = crc32Update(key2, key1 ushr 24)
    }

    private fun streamByte(): Int {
        val temp = (key2 and 0xFFFF) or 2
        return ((temp * (temp xor 1)) ushr 8) and 0xFF
    }

    fun decryptByte(c: Int): Int {
        val plain = (c and 0xFF) xor streamByte()
        updateKeys(plain)
        return plain
    }

    fun decryptInPlace(buf: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val plain = (buf[i].toInt() and 0xFF) xor streamByte()
            updateKeys(plain)
            buf[i] = plain.toByte()
        }
    }

    /** Used by unit tests to synthesize compliant ZipCrypto test archives. */
    internal fun encryptInPlace(buf: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val plain = buf[i].toInt() and 0xFF
            val cipher = plain xor streamByte()
            updateKeys(plain)
            buf[i] = cipher.toByte()
        }
    }

    fun clear() {
        key0 = 0
        key1 = 0
        key2 = 0
    }

    companion object {
        private val CRC_TABLE = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else (c ushr 1)
            }
            c
        }

        private fun crc32Update(crc: Int, b: Int): Int =
            (crc ushr 8) xor CRC_TABLE[(crc xor b) and 0xFF]
    }
}

/**
 * Pure-Kotlin ZIP encryption/decryption and safe archive reading helpers built on the JDK/Android
 * standard library (`javax.crypto`, `java.util.zip`) and `commons-compress` `ZipFile` container parsing.
 */
object ZipCrypto {
    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8
    const val METHOD_WINZIP_AES = 99
    const val AES_EXTRA_HEADER_ID = 0x9901
    const val AES_ITERATIONS = 1000
    const val AES_PVV_BYTES = 2
    const val AES_HMAC_BYTES = 10
    const val ZIPCRYPTO_HEADER_BYTES = 12
    private const val MAX_VERIFY_DECRYPT_BYTES = 4L shl 20

    private val AES_EXTRA_ID = ZipShort(AES_EXTRA_HEADER_ID)
    private val CP437: Charset = Charset.forName("IBM437")

    /**
     * RFC 2898 PBKDF2-HMAC-SHA1 implemented directly over [Mac] with explicit UTF-8 password bytes so
     * non-ASCII passwords behave identically across JVM, Robolectric, and Android API levels.
     */
    fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations > 0) { "iterations must be > 0" }
        require(keyLength >= 0) { "keyLength must be >= 0" }
        if (keyLength == 0) return ByteArray(0)

        val mac = Mac.getInstance("HmacSHA1")
        // Per RFC 2104, an empty key is valid and equivalent to an all-zero block; SecretKeySpec rejects 0-length arrays.
        val keyBytes = if (password.isEmpty()) ByteArray(1) else password
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))

        val hLen = mac.macLength // 20 bytes for SHA-1
        val blocks = (keyLength + hLen - 1) / hLen
        val out = ByteArray(keyLength)
        val intBlock = ByteArray(4)
        var outPos = 0

        for (block in 1..blocks) {
            intBlock[0] = (block ushr 24).toByte()
            intBlock[1] = (block ushr 16).toByte()
            intBlock[2] = (block ushr 8).toByte()
            intBlock[3] = block.toByte()

            mac.update(salt)
            mac.update(intBlock)
            var u = mac.doFinal()
            val t = u.copyOf()

            for (iter in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            val toCopy = minOf(hLen, keyLength - outPos)
            System.arraycopy(t, 0, out, outPos, toCopy)
            outPos += toCopy
            t.fill(0)
            u.fill(0)
        }
        return out
    }

    /** Encodes a 7-byte WinZip AES (`0x9901`) extra field payload (without the 4-byte header). */
    fun buildAesExtraData(strength: AesStrength, actualMethod: Int, version: Int = 2): ByteArray =
        ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(version.toShort())
            .put('A'.code.toByte())
            .put('E'.code.toByte())
            .put(strength.code.toByte())
            .putShort(actualMethod.toShort())
            .array()

    /** Encodes a complete 11-byte WinZip AES (`0x9901`) extra field (header ID + length + 7-byte payload). */
    fun buildAesExtraField(strength: AesStrength, actualMethod: Int, version: Int = 2): ByteArray {
        val data = buildAesExtraData(strength, actualMethod, version)
        return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(AES_EXTRA_HEADER_ID.toShort())
            .putShort(data.size.toShort())
            .put(data)
            .array()
    }

    /** Opens a local ZIP archive via `commons-compress` [ZipFile], normalizing all failures to [IOException]. */
    fun openZipFile(file: File): ZipFile = wrapZipIo {
        ZipFile.builder()
            .setFile(file)
            .setCharset(CP437)
            .get()
    }

    /**
     * Decodes a [ZipArchiveEntry]'s filename cleanly:
     * - If the UTF-8 bit (bit 11) is set, decodes raw bytes as UTF-8.
     * - Otherwise tries strict UTF-8 first and falls back to IBM437 for legacy archives.
     */
    fun decodeEntryName(entry: ZipArchiveEntry): String {
        val raw = entry.rawName ?: return entry.name
        if (entry.generalPurposeBit.usesUTF8ForNames()) {
            return String(raw, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (_: Exception) {
            String(raw, CP437)
        }
    }

    /** Classifies the encryption method used on [entry]. */
    fun detectEncryption(entry: ZipArchiveEntry): ZipEntryEncryption {
        val gpb = entry.generalPurposeBit
        if (!gpb.usesEncryption()) return ZipEntryEncryption.None
        if (gpb.usesStrongEncryption()) {
            return ZipEntryEncryption.Unsupported("This archive uses PKWARE strong encryption, which isn't supported")
        }
        if (entry.method == METHOD_WINZIP_AES) {
            return parseAesExtraField(entry)
                ?: ZipEntryEncryption.Unsupported("This archive has a malformed AES encryption header")
        }
        return when (entry.method) {
            METHOD_STORED, METHOD_DEFLATED -> ZipEntryEncryption.ZipCrypto
            else -> ZipEntryEncryption.Unsupported("This archive uses an unsupported encryption or compression method (${entry.method})")
        }
    }

    /** Parses the WinZip AES `0x9901` extra field from [entry], checking both parsed and raw extra bytes. */
    fun parseAesExtraField(entry: ZipArchiveEntry): ZipEntryEncryption.WinZipAes? {
        val data = entry.getExtraField(AES_EXTRA_ID)?.let { ef ->
            ef.centralDirectoryData?.takeIf { it.size >= 7 } ?: ef.localFileDataData?.takeIf { it.size >= 7 }
        } ?: findExtraFieldBytes(entry.centralDirectoryExtra, AES_EXTRA_HEADER_ID)
            ?: findExtraFieldBytes(entry.localFileDataExtra, AES_EXTRA_HEADER_ID)
            ?: return null
        if (data.size < 7) return null
        val version = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (version != 1 && version != 2) return null
        if (data[2].toInt() != 'A'.code || data[3].toInt() != 'E'.code) return null
        val strength = AesStrength.fromCode(data[4].toInt() and 0xFF) ?: return null
        val actualMethod = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
        if (actualMethod != METHOD_STORED && actualMethod != METHOD_DEFLATED) return null
        return ZipEntryEncryption.WinZipAes(version, strength, actualMethod)
    }

    private fun findExtraFieldBytes(extra: ByteArray?, headerId: Int): ByteArray? {
        if (extra == null || extra.size < 4) return null
        var off = 0
        while (off + 4 <= extra.size) {
            val id = (extra[off].toInt() and 0xFF) or ((extra[off + 1].toInt() and 0xFF) shl 8)
            val len = (extra[off + 2].toInt() and 0xFF) or ((extra[off + 3].toInt() and 0xFF) shl 8)
            off += 4
            if (len < 0 || off + len > extra.size) return null
            if (id == headerId) return extra.copyOfRange(off, off + len)
            off += len
        }
        return null
    }

    /** Returns true if any non-directory entry in [file] is encrypted. */
    fun isArchiveEncrypted(file: File): Boolean = wrapZipIo {
        openZipFile(file).use { z ->
            val en = z.entries
            var count = 0
            while (en.hasMoreElements()) {
                if (++count > ZipFileSystem.MAX_ENTRIES) return@use false
                val e = en.nextElement()
                if (!e.isDirectory && e.generalPurposeBit.usesEncryption()) return@use true
            }
            false
        }
    }

    /**
     * Verifies [password] against [file] before starting extraction or file transfer.
     * Throws [ZipPasswordRequiredException] (`wrongPassword = true`) if the password is incorrect,
     * or another [IOException] if the archive is unreadable or uses an unsupported encryption method.
     */
    fun verifyPassword(file: File, archivePath: String, password: String) {
        wrapZipIo {
            openZipFile(file).use { z ->
                var chosen: ZipArchiveEntry? = null
                var chosenIsAes = false
                val en = z.entries
                var count = 0
                while (en.hasMoreElements()) {
                    if (++count > ZipFileSystem.MAX_ENTRIES) break
                    val e = en.nextElement()
                    if (e.isDirectory || !e.generalPurposeBit.usesEncryption()) continue
                    val enc = detectEncryption(e)
                    if (enc is ZipEntryEncryption.Unsupported) throw IOException(enc.description)
                    val isAes = enc is ZipEntryEncryption.WinZipAes
                    // Prefer a small entry so we can validate HMAC / CRC completely, and prefer AES over ZipCrypto.
                    val better = chosen == null ||
                        (isAes && !chosenIsAes) ||
                        (isAes == chosenIsAes && e.compressedSize in 0 until chosen.compressedSize)
                    if (better) {
                        chosen = e
                        chosenIsAes = isAes
                    }
                }
                val entry = chosen ?: return@use
                val enc = detectEncryption(entry)
                when (enc) {
                    is ZipEntryEncryption.WinZipAes -> verifyAesEntry(z, entry, enc, archivePath, password)
                    is ZipEntryEncryption.ZipCrypto -> verifyZipCryptoEntry(z, file, entry, archivePath, password)
                    is ZipEntryEncryption.Unsupported -> throw IOException(enc.description)
                    ZipEntryEncryption.None -> Unit
                }
            }
        }
    }

    private fun verifyAesEntry(
        z: ZipFile,
        entry: ZipArchiveEntry,
        enc: ZipEntryEncryption.WinZipAes,
        archivePath: String,
        password: String,
    ) {
        val rawSize = entry.compressedSize
        val overhead = enc.strength.saltBytes + AES_PVV_BYTES + AES_HMAC_BYTES
        if (rawSize < overhead) throw IOException("Encrypted archive entry is truncated")
        val cipherLen = rawSize - overhead
        val raw = z.getRawInputStream(entry) ?: throw IOException("Can't read encrypted archive entry")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        var derived: ByteArray? = null
        try {
            raw.use { inp ->
                val salt = readExact(inp, enc.strength.saltBytes)
                val filePvv = readExact(inp, AES_PVV_BYTES)
                val d = pbkdf2HmacSha1(passwordBytes, salt, AES_ITERATIONS, enc.strength.keyBytes * 2 + AES_PVV_BYTES)
                derived = d
                val expectedPvv = d.copyOfRange(enc.strength.keyBytes * 2, enc.strength.keyBytes * 2 + AES_PVV_BYTES)
                if (!MessageDigest.isEqual(expectedPvv, filePvv)) {
                    throw ZipPasswordRequiredException(archivePath, wrongPassword = true)
                }
                // For entries up to 4 MiB, also verify the 10-byte HMAC-SHA1-80 tag to eliminate the 1-in-65536 PVV collision.
                if (cipherLen <= MAX_VERIFY_DECRYPT_BYTES) {
                    val hmacKey = d.copyOfRange(enc.strength.keyBytes, enc.strength.keyBytes * 2)
                    try {
                        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(hmacKey, "HmacSHA1")) }
                        val buf = ByteArray(16 * 1024)
                        var left = cipherLen
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("Encrypted archive entry is truncated")
                            mac.update(buf, 0, n)
                            left -= n
                        }
                        val storedTag = readExact(inp, AES_HMAC_BYTES)
                        val computedTag = mac.doFinal().copyOf(AES_HMAC_BYTES)
                        if (!MessageDigest.isEqual(computedTag, storedTag)) {
                            throw ZipPasswordRequiredException(archivePath, wrongPassword = true)
                        }
                    } finally {
                        hmacKey.fill(0)
                    }
                }
            }
        } finally {
            passwordBytes.fill(0)
            derived?.fill(0)
        }
    }

    private fun verifyZipCryptoEntry(
        z: ZipFile,
        file: File,
        entry: ZipArchiveEntry,
        archivePath: String,
        password: String,
    ) {
        // Opening performs the 12-byte header check immediately; for entries <= 4 MiB also drain to confirm full CRC-32.
        openEntryInputStream(z, file, entry, archivePath, password, verifyCrc = true).use { stream ->
            if (entry.size in 0..MAX_VERIFY_DECRYPT_BYTES) {
                val buf = ByteArray(16 * 1024)
                var read = 0L
                val max = entry.size.coerceAtLeast(0L) + 1
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > max) throw ZipPasswordRequiredException(archivePath, wrongPassword = true)
                }
            }
        }
    }

    /**
     * Opens a decrypted, decompressed [InputStream] for [entry] from [zipFile].
     *
     * Note: The caller remains responsible for closing [zipFile] after closing the returned stream.
     * Throws [ZipPasswordRequiredException] if [entry] is encrypted and [password] is missing or invalid.
     */
    fun openEntryInputStream(
        zipFile: ZipFile,
        file: File,
        entry: ZipArchiveEntry,
        archivePath: String,
        password: String?,
        verifyCrc: Boolean = true,
    ): InputStream = wrapZipIo {
        when (val enc = detectEncryption(entry)) {
            ZipEntryEncryption.None -> {
                if (!zipFile.canReadEntryData(entry)) {
                    throw IOException("Unsupported compression method (${entry.method}) for ${decodeEntryName(entry)}")
                }
                val raw = zipFile.getInputStream(entry)
                    ?: throw IOException("Can't read ${decodeEntryName(entry)} from the archive")
                if (verifyCrc) {
                    VerifyingInputStream(
                        source = raw,
                        expectedCrc = entry.crc.takeIf { it >= 0 },
                        expectedSize = entry.size.takeIf { it >= 0 },
                        archivePath = archivePath,
                        isEncrypted = false,
                    )
                } else {
                    raw
                }
            }
            is ZipEntryEncryption.Unsupported -> throw IOException(enc.description)
            is ZipEntryEncryption.WinZipAes -> {
                val pw = password ?: throw ZipPasswordRequiredException(archivePath, wrongPassword = false)
                openWinZipAesStream(zipFile, entry, enc, archivePath, pw, verifyCrc)
            }
            ZipEntryEncryption.ZipCrypto -> {
                val pw = password ?: throw ZipPasswordRequiredException(archivePath, wrongPassword = false)
                openZipCryptoStream(zipFile, file, entry, archivePath, pw, verifyCrc)
            }
        }
    }

    private fun openWinZipAesStream(
        zipFile: ZipFile,
        entry: ZipArchiveEntry,
        enc: ZipEntryEncryption.WinZipAes,
        archivePath: String,
        password: String,
        verifyCrc: Boolean,
    ): InputStream {
        val rawSize = entry.compressedSize
        val overhead = enc.strength.saltBytes + AES_PVV_BYTES + AES_HMAC_BYTES
        if (rawSize < overhead) throw IOException("Encrypted archive entry is truncated")
        val cipherLen = rawSize - overhead

        val raw = zipFile.getRawInputStream(entry)
            ?: throw IOException("Can't read encrypted entry from the archive")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        var derived: ByteArray? = null
        var aesKey: ByteArray? = null
        var hmacKey: ByteArray? = null
        try {
            val salt = readExact(raw, enc.strength.saltBytes)
            val filePvv = readExact(raw, AES_PVV_BYTES)
            derived = pbkdf2HmacSha1(passwordBytes, salt, AES_ITERATIONS, enc.strength.keyBytes * 2 + AES_PVV_BYTES)
            aesKey = derived.copyOfRange(0, enc.strength.keyBytes)
            hmacKey = derived.copyOfRange(enc.strength.keyBytes, enc.strength.keyBytes * 2)
            val expectedPvv = derived.copyOfRange(enc.strength.keyBytes * 2, enc.strength.keyBytes * 2 + AES_PVV_BYTES)
            if (!MessageDigest.isEqual(expectedPvv, filePvv)) {
                runCatching { raw.close() }
                throw ZipPasswordRequiredException(archivePath, wrongPassword = true)
            }
            val aesStream = WinZipAesInputStream(raw, cipherLen, aesKey, hmacKey, archivePath)
            val decoded: InputStream = when (enc.actualMethod) {
                METHOD_STORED -> aesStream
                METHOD_DEFLATED -> RawDeflateInputStream(
                    source = aesStream,
                    archivePath = archivePath,
                    isEncrypted = true,
                    onBeforeErrorOrEof = { aesStream.drainAndVerifyMac() },
                )
                else -> {
                    runCatching { aesStream.close() }
                    throw IOException("Unsupported AES compression method (${enc.actualMethod})")
                }
            }
            // Per WinZip spec: AE-1 stores CRC-32; AE-2 zeroes CRC-32 and authenticates via HMAC-SHA1-80.
            val checkCrc = if (verifyCrc && enc.version == 1) entry.crc.takeIf { it >= 0 } else null
            val checkSize = if (verifyCrc) entry.size.takeIf { it >= 0 } else null
            return if (checkCrc != null || checkSize != null) {
                VerifyingInputStream(
                    source = decoded,
                    expectedCrc = checkCrc,
                    expectedSize = checkSize,
                    archivePath = archivePath,
                    isEncrypted = true,
                    onBeforeErrorOrEof = { aesStream.drainAndVerifyMac() },
                )
            } else {
                decoded
            }
        } catch (t: Throwable) {
            runCatching { raw.close() }
            throw t
        } finally {
            passwordBytes.fill(0)
            derived?.fill(0)
            aesKey?.fill(0)
            hmacKey?.fill(0)
        }
    }

    private fun openZipCryptoStream(
        zipFile: ZipFile,
        file: File,
        entry: ZipArchiveEntry,
        archivePath: String,
        password: String,
        verifyCrc: Boolean,
    ): InputStream {
        val rawSize = entry.compressedSize
        if (rawSize < ZIPCRYPTO_HEADER_BYTES) throw IOException("Encrypted archive entry is truncated")
        val payloadLen = rawSize - ZIPCRYPTO_HEADER_BYTES

        val raw = zipFile.getRawInputStream(entry)
            ?: throw IOException("Can't read encrypted entry from the archive")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val header = readExact(raw, ZIPCRYPTO_HEADER_BYTES)
            val engine = ZipCryptoEngine(passwordBytes)
            engine.decryptInPlace(header, 0, ZIPCRYPTO_HEADER_BYTES)
            val checkByte = header[ZIPCRYPTO_HEADER_BYTES - 1].toInt() and 0xFF

            val usesDesc = entry.generalPurposeBit.usesDataDescriptor()
            val crcHighByte = ((entry.crc ushr 24) and 0xFF).toInt()
            val validCheck = if (usesDesc) {
                val dosHighByte = readRawDosTimeHighByte(file, entry)
                    ?: (ZipUtil.toDosTime(entry.time)[1].toInt() and 0xFF)
                checkByte == dosHighByte || (entry.crc >= 0 && checkByte == crcHighByte)
            } else {
                entry.crc >= 0 && checkByte == crcHighByte
            }
            if (!validCheck) {
                engine.clear()
                runCatching { raw.close() }
                throw ZipPasswordRequiredException(archivePath, wrongPassword = true)
            }

            val cryptoStream = ZipCryptoInputStream(raw, payloadLen, engine)
            val decoded: InputStream = when (entry.method) {
                METHOD_STORED -> cryptoStream
                METHOD_DEFLATED -> RawDeflateInputStream(
                    source = cryptoStream,
                    archivePath = archivePath,
                    isEncrypted = true,
                )
                else -> {
                    runCatching { cryptoStream.close() }
                    throw IOException("Unsupported compression method (${entry.method})")
                }
            }
            val checkCrc = if (verifyCrc) entry.crc.takeIf { it >= 0 } else null
            val checkSize = if (verifyCrc) entry.size.takeIf { it >= 0 } else null
            return if (checkCrc != null || checkSize != null) {
                VerifyingInputStream(
                    source = decoded,
                    expectedCrc = checkCrc,
                    expectedSize = checkSize,
                    archivePath = archivePath,
                    isEncrypted = true,
                )
            } else {
                decoded
            }
        } catch (t: Throwable) {
            runCatching { raw.close() }
            throw t
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * Reads the exact raw high byte of the 16-bit MS-DOS modification time from the Local File Header
     * (offset +11 from `localHeaderOffset`), unaffected by timezone or `X5455` extended timestamps.
     */
    internal fun readRawDosTimeHighByte(file: File, entry: ZipArchiveEntry): Int? {
        val offset = entry.localHeaderOffset
        if (offset < 0) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (offset + 12 > raf.length()) return null
                raf.seek(offset + 11)
                val b = raf.read()
                if (b < 0) null else (b and 0xFF)
            }
        }.getOrNull()
    }

    internal fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var pos = 0
        while (pos < count) {
            val n = input.read(out, pos, count - pos)
            if (n < 0) throw EOFException("Unexpected end of archive entry")
            pos += n
        }
        return out
    }

    internal inline fun <T> wrapZipIo(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Can't read this archive: ${e.message}", e)
    }
}

/** Decrypts a WinZip AES ciphertext stream and verifies the trailing 10-byte HMAC-SHA1-80 tag. */
private class WinZipAesInputStream(
    private val raw: InputStream,
    private val cipherLen: Long,
    aesKey: ByteArray,
    hmacKey: ByteArray,
    private val archivePath: String,
) : InputStream() {
    private val ctr = WinZipAesCtrCipher(aesKey)
    private val mac: Mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(hmacKey, "HmacSHA1"))
    }
    private var remaining: Long = cipherLen
    private var macVerified = false
    private var closed = false
    private val oneByte = ByteArray(1)

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0
        if (remaining <= 0L) {
            verifyMacOnce()
            return -1
        }
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = raw.read(b, off, toRead)
        if (n < 0) throw IOException("Encrypted archive entry is truncated")
        mac.update(b, off, n)
        ctr.processInPlace(b, off, n)
        remaining -= n
        if (remaining == 0L) {
            verifyMacOnce()
        }
        return n
    }

    /** Drains any unconsumed ciphertext bytes into HMAC and verifies the 10-byte authentication tag. */
    fun drainAndVerifyMac() {
        if (macVerified || closed) return
        if (remaining > 0L) {
            val discard = ByteArray(8 * 1024)
            while (remaining > 0L) {
                val n = raw.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Encrypted archive entry is truncated")
                mac.update(discard, 0, n)
                remaining -= n
            }
        }
        verifyMacOnce()
    }

    private fun verifyMacOnce() {
        if (macVerified) return
        macVerified = true
        val storedTag = try {
            ZipCrypto.readExact(raw, ZipCrypto.AES_HMAC_BYTES)
        } catch (e: EOFException) {
            throw IOException("Encrypted archive entry is truncated", e)
        }
        val computedTag = mac.doFinal().copyOf(ZipCrypto.AES_HMAC_BYTES)
        if (!MessageDigest.isEqual(computedTag, storedTag)) {
            throw ZipPasswordRequiredException(
                archivePath = archivePath,
                wrongPassword = true,
                message = "Incorrect password or corrupted archive",
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ctr.clear()
        raw.close()
    }
}

/** Decrypts a bounded PKWARE ZipCrypto payload stream after the 12-byte header has been validated. */
private class ZipCryptoInputStream(
    private val raw: InputStream,
    private var remaining: Long,
    private val engine: ZipCryptoEngine,
) : InputStream() {
    private var closed = false
    private val oneByte = ByteArray(1)

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = raw.read(b, off, toRead)
        if (n < 0) throw IOException("Encrypted archive entry is truncated")
        engine.decryptInPlace(b, off, n)
        remaining -= n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.clear()
        raw.close()
    }
}

/**
 * Inflates a raw DEFLATE stream (`nowrap = true`), handling the zlib trailing dummy-byte requirement,
 * invoking [onBeforeErrorOrEof] (for AES HMAC verification) before returning EOF or surfacing a
 * [DataFormatException], and guaranteeing [Inflater.end] on close.
 */
private class RawDeflateInputStream(
    private val source: InputStream,
    private val archivePath: String,
    private val isEncrypted: Boolean,
    private val onBeforeErrorOrEof: () -> Unit = {},
) : InputStream() {
    private val inflater = Inflater(true)
    private val inBuf = ByteArray(16 * 1024)
    private val oneByte = ByteArray(1)
    private var eofReached = false
    private var dummyFed = false
    private var closed = false

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0
        if (eofReached) return -1
        try {
            while (true) {
                val n = inflater.inflate(b, off, len)
                if (n > 0) return n
                if (inflater.finished() || inflater.needsDictionary()) {
                    eofReached = true
                    onBeforeErrorOrEof()
                    return -1
                }
                if (inflater.needsInput()) {
                    val read = source.read(inBuf)
                    if (read < 0) {
                        if (!dummyFed) {
                            dummyFed = true
                            inflater.setInput(DUMMY_BYTE, 0, 1)
                        } else {
                            onBeforeErrorOrEof()
                            if (isEncrypted) {
                                throw ZipPasswordRequiredException(
                                    archivePath = archivePath,
                                    wrongPassword = true,
                                    message = "Incorrect password or truncated archive",
                                )
                            }
                            throw IOException("Unexpected end of compressed data")
                        }
                    } else if (read > 0) {
                        inflater.setInput(inBuf, 0, read)
                    }
                }
            }
        } catch (e: DataFormatException) {
            onBeforeErrorOrEof()
            if (isEncrypted) {
                throw ZipPasswordRequiredException(
                    archivePath = archivePath,
                    wrongPassword = true,
                    message = "Incorrect password or corrupted archive",
                )
            }
            throw IOException("Corrupted compressed data: ${e.message}", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            source.close()
        } finally {
            inflater.end()
        }
    }

    companion object {
        private val DUMMY_BYTE = byteArrayOf(0)
    }
}

/** Verifies uncompressed byte count and CRC-32 at EOF against the archive header's declared values. */
private class VerifyingInputStream(
    private val source: InputStream,
    private val expectedCrc: Long?,
    private val expectedSize: Long?,
    private val archivePath: String,
    private val isEncrypted: Boolean,
    private val onBeforeErrorOrEof: () -> Unit = {},
) : InputStream() {
    private val crc = CRC32()
    private var totalRead = 0L
    private var verified = false
    private val oneByte = ByteArray(1)

    override fun read(): Int {
        val n = read(oneByte, 0, 1)
        return if (n < 0) -1 else (oneByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val maxRead = if (expectedSize != null) {
            minOf(len.toLong(), (expectedSize - totalRead + 1L).coerceAtLeast(1L)).toInt()
        } else {
            len
        }
        val n = source.read(b, off, maxRead)
        if (n < 0) {
            if (!verified) {
                verified = true
                onBeforeErrorOrEof()
                if (expectedSize != null && totalRead != expectedSize) {
                    if (isEncrypted) {
                        throw ZipPasswordRequiredException(
                            archivePath = archivePath,
                            wrongPassword = true,
                            message = "Incorrect password or corrupted archive",
                        )
                    }
                    throw IOException("Archive entry size mismatch (expected $expectedSize, got $totalRead)")
                }
                if (expectedCrc != null && crc.value != (expectedCrc and 0xFFFFFFFFL)) {
                    if (isEncrypted) {
                        throw ZipPasswordRequiredException(
                            archivePath = archivePath,
                            wrongPassword = true,
                            message = "Incorrect password or corrupted archive",
                        )
                    }
                    throw IOException("Archive entry CRC mismatch")
                }
            }
            return -1
        }
        totalRead += n
        if (expectedSize != null && totalRead > expectedSize) {
            onBeforeErrorOrEof()
            throw IOException("Archive entry expanded past its declared size ($expectedSize bytes)")
        }
        if (expectedCrc != null) crc.update(b, off, n)
        return n
    }

    override fun close() = source.close()
}
