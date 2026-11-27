// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin streaming ZIP archive writer supporting:
 * - Compression levels [ZipCompressionLevel.None] (STORED), [ZipCompressionLevel.Fast],
 *   [ZipCompressionLevel.Normal], and [ZipCompressionLevel.Maximum]
 * - Optional WinZip AES-256 (AE-2) authenticated encryption using standard JCA primitives
 * - UTF-8 filenames, Unix attributes, and ZIP64 extensions for entries or archives >= 4 GiB
 *
 * Unencrypted `None` (STORED) entries pre-compute CRC-32 and write exact sizes directly in the Local
 * File Header (without a data descriptor) for full compatibility with `java.util.zip.ZipInputStream`
 * and Windows Explorer.
 */
internal class ZipWriter(
    rawOut: OutputStream,
    private val level: ZipCompressionLevel = ZipCompressionLevel.Normal,
    password: String? = null,
    private val forceZip64: Boolean = false,
) : Closeable {

    private class CdEntry(
        val nameBytes: ByteArray,
        val localHeaderOffset: Long,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val externalAttrs: Long,
        val aesExtra: ByteArray,
    )

    private val out = BufferedOutputStream(rawOut, 64 * 1024)
    private val passwordBytes: ByteArray? = password?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
    private val entries = ArrayList<CdEntry>()
    private var bytesWritten = 0L
    private var inEntry = false
    private var finished = false
    private var closed = false

    private fun writeBytes(b: ByteArray, off: Int = 0, len: Int = b.size) {
        out.write(b, off, len)
        bytesWritten += len
    }

    /** Writes a directory entry (always unencrypted, method STORED, trailing `/`). */
    fun addDirectory(name: String, modifiedMs: Long) {
        check(!finished && !closed) { "ZipWriter already finished" }
        val normalized = if (name.endsWith("/")) name else "$name/"
        val nameBytes = normalized.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES) {
            throw IOException("Archive entry name exceeds ZIP 65,535-byte limit")
        }
        val (dosTime, dosDate) = toDosDateTime(modifiedMs)
        val offset = bytesWritten
        val flags = FLAG_UTF8
        val versionNeeded = if (forceZip64) VERSION_ZIP64 else VERSION_STANDARD

        writeLocalFileHeader(
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_STORED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = 0L,
            compSize = 0L,
            uncompSize = 0L,
            nameBytes = nameBytes,
            extra = EMPTY_BYTES,
        )
        entries += CdEntry(
            nameBytes = nameBytes,
            localHeaderOffset = offset,
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_STORED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = 0L,
            compressedSize = 0L,
            uncompressedSize = 0L,
            externalAttrs = UNIX_DIR_ATTRS,
            aesExtra = EMPTY_BYTES,
        )
    }

    /**
     * Writes a regular file entry. [openInput] may be called twice only for unencrypted
     * [ZipCompressionLevel.None] entries (first pass to compute exact CRC-32 and size).
     */
    suspend fun addFile(
        name: String,
        modifiedMs: Long,
        expectedSize: Long = -1L,
        openInput: () -> InputStream,
        checkCancel: suspend () -> Unit = {},
        onBytesRead: (Int) -> Unit = {},
    ) {
        check(!finished && !closed) { "ZipWriter already finished" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES) {
            throw IOException("Archive entry name exceeds ZIP 65,535-byte limit")
        }
        val (dosTime, dosDate) = toDosDateTime(modifiedMs)
        val pw = passwordBytes

        inEntry = true
        when {
            pw != null -> addAesEncryptedFile(nameBytes, dosTime, dosDate, expectedSize, pw, openInput, checkCancel, onBytesRead)
            level == ZipCompressionLevel.None -> addUnencryptedStoredFile(name, nameBytes, dosTime, dosDate, openInput, checkCancel, onBytesRead)
            else -> addUnencryptedDeflatedFile(nameBytes, dosTime, dosDate, expectedSize, openInput, checkCancel, onBytesRead)
        }
        inEntry = false
    }

    private suspend fun addUnencryptedStoredFile(
        displayName: String,
        nameBytes: ByteArray,
        dosTime: Int,
        dosDate: Int,
        openInput: () -> InputStream,
        checkCancel: suspend () -> Unit,
        onBytesRead: (Int) -> Unit,
    ) {
        // Pre-pass: exact byte count & CRC-32 so STORED entries need no bit-3 data descriptor.
        val preCrc = CRC32()
        var exactSize = 0L
        val buf = ByteArray(IO_BUF_SIZE)
        openInput().use { inp ->
            while (true) {
                checkCancel()
                val n = inp.read(buf)
                if (n < 0) break
                preCrc.update(buf, 0, n)
                exactSize += n
            }
        }
        val crcVal = preCrc.value
        val useZip64 = forceZip64 || exactSize >= ZIP64_LIMIT
        val versionNeeded = if (useZip64) VERSION_ZIP64 else VERSION_STANDARD
        val flags = FLAG_UTF8
        val extra = if (useZip64) buildZip64Extra(exactSize, exactSize, null) else EMPTY_BYTES
        val offset = bytesWritten

        writeLocalFileHeader(
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_STORED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = crcVal,
            compSize = if (useZip64) ZIP64_LIMIT else exactSize,
            uncompSize = if (useZip64) ZIP64_LIMIT else exactSize,
            nameBytes = nameBytes,
            extra = extra,
        )

        val verifyCrc = CRC32()
        var copied = 0L
        openInput().use { inp ->
            while (true) {
                checkCancel()
                val n = inp.read(buf)
                if (n < 0) break
                copied += n
                if (copied > exactSize) {
                    throw IOException("File changed while compressing: $displayName")
                }
                verifyCrc.update(buf, 0, n)
                writeBytes(buf, 0, n)
                onBytesRead(n)
            }
        }
        if (copied != exactSize || verifyCrc.value != crcVal) {
            throw IOException("File changed while compressing: $displayName")
        }

        entries += CdEntry(
            nameBytes = nameBytes,
            localHeaderOffset = offset,
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_STORED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = crcVal,
            compressedSize = exactSize,
            uncompressedSize = exactSize,
            externalAttrs = UNIX_FILE_ATTRS,
            aesExtra = EMPTY_BYTES,
        )
    }

    private suspend fun addUnencryptedDeflatedFile(
        nameBytes: ByteArray,
        dosTime: Int,
        dosDate: Int,
        expectedSize: Long,
        openInput: () -> InputStream,
        checkCancel: suspend () -> Unit,
        onBytesRead: (Int) -> Unit,
    ) {
        // Following OpenJDK ZipOutputStream / ZipInputStream and APPNOTE.TXT §4.5.3:
        // Bit-3 streaming entries omit the `0x0001` ZIP64 extra from the Local File Header (LFH sizes
        // stay 0) because JDK 21 and Android `libcore` `ZipInputStream.readEnd` select a 24-byte 64-bit
        // Data Descriptor iff `inf.getBytesWritten() > 0xFFFFFFFFL || inf.getBytesRead() > 0xFFFFFFFFL`
        // (strictly `>`). Emitting a 24-byte descriptor when sizes are <= 0xFFFFFFFFL breaks JDK 21
        // `ZipInputStream` with `ZipException: invalid entry size`.
        val lfhVersion = if (forceZip64 || expectedSize >= ZIP64_PRE_LIMIT) VERSION_ZIP64 else VERSION_STANDARD
        val flags = FLAG_UTF8 or FLAG_DATA_DESCRIPTOR
        val offset = bytesWritten

        writeLocalFileHeader(
            versionNeeded = lfhVersion,
            flags = flags,
            method = ZipCrypto.METHOD_DEFLATED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = 0L,
            compSize = 0L,
            uncompSize = 0L,
            nameBytes = nameBytes,
            extra = EMPTY_BYTES,
        )

        val crc = CRC32()
        var uncompSize = 0L
        var compSize = 0L
        val deflater = Deflater(level.deflateLevel, true)
        val inBuf = ByteArray(IO_BUF_SIZE)
        val defBuf = ByteArray(DEFLATE_BUF_SIZE)
        try {
            openInput().use { inp ->
                while (true) {
                    checkCancel()
                    val n = inp.read(inBuf)
                    if (n < 0) break
                    crc.update(inBuf, 0, n)
                    uncompSize += n
                    deflater.setInput(inBuf, 0, n)
                    while (!deflater.needsInput()) {
                        val d = deflater.deflate(defBuf)
                        if (d > 0) {
                            writeBytes(defBuf, 0, d)
                            compSize += d
                        }
                    }
                    onBytesRead(n)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val d = deflater.deflate(defBuf)
                if (d > 0) {
                    writeBytes(defBuf, 0, d)
                    compSize += d
                }
            }
        } finally {
            deflater.end()
        }

        val useZip64Descriptor = shouldWriteZip64DataDescriptor(compSize, uncompSize)
        val versionNeeded = if (forceZip64 || compSize >= ZIP64_LIMIT || uncompSize >= ZIP64_LIMIT) VERSION_ZIP64 else lfhVersion
        val crcVal = crc.value
        writeDataDescriptor(crcVal, compSize, uncompSize, useZip64Descriptor)

        entries += CdEntry(
            nameBytes = nameBytes,
            localHeaderOffset = offset,
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_DEFLATED,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = crcVal,
            compressedSize = compSize,
            uncompressedSize = uncompSize,
            externalAttrs = UNIX_FILE_ATTRS,
            aesExtra = EMPTY_BYTES,
        )
    }

    private suspend fun addAesEncryptedFile(
        nameBytes: ByteArray,
        dosTime: Int,
        dosDate: Int,
        expectedSize: Long,
        passwordUtf8: ByteArray,
        openInput: () -> InputStream,
        checkCancel: suspend () -> Unit,
        onBytesRead: (Int) -> Unit,
    ) {
        val actualMethod = if (level == ZipCompressionLevel.None) ZipCrypto.METHOD_STORED else ZipCrypto.METHOD_DEFLATED
        val versionNeeded = VERSION_WINZIP_AES
        val flags = FLAG_ENCRYPTED or FLAG_DATA_DESCRIPTOR or FLAG_UTF8
        val aesExtra = ZipCrypto.buildAesExtraField(AesStrength.AES256, actualMethod, version = 2)
        val offset = bytesWritten

        // Bit-3 streaming entry: LFH carries only the 0x9901 AES extra field (no 0x0001 placeholder)
        // and emits a 64-bit Data Descriptor iff sizes strictly exceed 0xFFFFFFFFL.
        writeLocalFileHeader(
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_WINZIP_AES,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = 0L,
            compSize = 0L,
            uncompSize = 0L,
            nameBytes = nameBytes,
            extra = aesExtra,
        )

        val salt = ByteArray(AesStrength.AES256.saltBytes).also { SECURE_RANDOM.nextBytes(it) }
        val derived = ZipCrypto.pbkdf2HmacSha1(
            password = passwordUtf8,
            salt = salt,
            iterations = ZipCrypto.AES_ITERATIONS,
            keyLength = AesStrength.AES256.keyBytes * 2 + ZipCrypto.AES_PVV_BYTES,
        )
        val aesKey = derived.copyOfRange(0, AesStrength.AES256.keyBytes)
        val hmacKey = derived.copyOfRange(AesStrength.AES256.keyBytes, AesStrength.AES256.keyBytes * 2)
        val pvv = derived.copyOfRange(AesStrength.AES256.keyBytes * 2, AesStrength.AES256.keyBytes * 2 + ZipCrypto.AES_PVV_BYTES)
        val ctr = WinZipAesCtrCipher(aesKey)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(hmacKey, "HmacSHA1")) }

        var uncompSize = 0L
        var compSize = 0L
        val inBuf = ByteArray(IO_BUF_SIZE)
        var fullMac: ByteArray? = null
        try {
            writeBytes(salt)
            writeBytes(pvv)
            compSize += salt.size + pvv.size

            fun encryptAndWrite(buf: ByteArray, len: Int) {
                ctr.processInPlace(buf, 0, len)
                mac.update(buf, 0, len)
                writeBytes(buf, 0, len)
                compSize += len
            }

            if (actualMethod == ZipCrypto.METHOD_STORED) {
                openInput().use { inp ->
                    while (true) {
                        checkCancel()
                        val n = inp.read(inBuf)
                        if (n < 0) break
                        uncompSize += n
                        encryptAndWrite(inBuf, n)
                        onBytesRead(n)
                    }
                }
            } else {
                val deflater = Deflater(level.deflateLevel, true)
                val defBuf = ByteArray(DEFLATE_BUF_SIZE)
                try {
                    openInput().use { inp ->
                        while (true) {
                            checkCancel()
                            val n = inp.read(inBuf)
                            if (n < 0) break
                            uncompSize += n
                            deflater.setInput(inBuf, 0, n)
                            while (!deflater.needsInput()) {
                                val d = deflater.deflate(defBuf)
                                if (d > 0) encryptAndWrite(defBuf, d)
                            }
                            onBytesRead(n)
                        }
                    }
                    deflater.finish()
                    while (!deflater.finished()) {
                        val d = deflater.deflate(defBuf)
                        if (d > 0) encryptAndWrite(defBuf, d)
                    }
                } finally {
                    defBuf.fill(0)
                    deflater.end()
                }
            }

            val rawMac = mac.doFinal().also { fullMac = it }
            val tag = rawMac.copyOf(ZipCrypto.AES_HMAC_BYTES)
            try {
                writeBytes(tag)
                compSize += tag.size
            } finally {
                tag.fill(0)
            }
        } finally {
            inBuf.fill(0)
            fullMac?.fill(0)
            ctr.clear()
            derived.fill(0)
            aesKey.fill(0)
            hmacKey.fill(0)
            pvv.fill(0)
        }

        val useZip64Descriptor = shouldWriteZip64DataDescriptor(compSize, uncompSize)
        // Per WinZip AE-2 spec, CRC-32 in standard ZIP headers and data descriptor is always 0.
        writeDataDescriptor(0L, compSize, uncompSize, useZip64Descriptor)

        entries += CdEntry(
            nameBytes = nameBytes,
            localHeaderOffset = offset,
            versionNeeded = versionNeeded,
            flags = flags,
            method = ZipCrypto.METHOD_WINZIP_AES,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = 0L,
            compressedSize = compSize,
            uncompressedSize = uncompSize,
            externalAttrs = UNIX_FILE_ATTRS,
            aesExtra = aesExtra,
        )
    }

    /** Writes the Central Directory and EOCD (plus ZIP64 EOCD/Locator if needed) and flushes the stream. */
    fun finish() {
        if (finished) return
        finished = true
        val cdOffset = bytesWritten

        for (e in entries) {
            val needUncomp64 = forceZip64 || e.uncompressedSize >= ZIP64_LIMIT
            val needComp64 = forceZip64 || e.compressedSize >= ZIP64_LIMIT
            val needOffset64 = forceZip64 || e.localHeaderOffset >= ZIP64_LIMIT
            val zip64Extra = if (needUncomp64 || needComp64 || needOffset64) {
                buildZip64Extra(
                    uncomp = if (needUncomp64) e.uncompressedSize else null,
                    comp = if (needComp64) e.compressedSize else null,
                    offset = if (needOffset64) e.localHeaderOffset else null,
                )
            } else {
                EMPTY_BYTES
            }
            val extra = if (zip64Extra.isEmpty()) e.aesExtra else zip64Extra + e.aesExtra
            val versionNeeded = if (zip64Extra.isNotEmpty()) maxOf(e.versionNeeded, VERSION_ZIP64) else e.versionNeeded
            val versionMadeBy = (HOST_UNIX shl 8) or versionNeeded

            val hdr = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SIG_CDH)
                .putShort(versionMadeBy.toShort())
                .putShort(versionNeeded.toShort())
                .putShort(e.flags.toShort())
                .putShort(e.method.toShort())
                .putShort(e.dosTime.toShort())
                .putShort(e.dosDate.toShort())
                .putInt(e.crc.toInt())
                .putInt((if (needComp64) ZIP64_LIMIT else e.compressedSize).toInt())
                .putInt((if (needUncomp64) ZIP64_LIMIT else e.uncompressedSize).toInt())
                .putShort(e.nameBytes.size.toShort())
                .putShort(extra.size.toShort())
                .putShort(0) // comment length
                .putShort(0) // disk number start
                .putShort(0) // internal file attributes
                .putInt(e.externalAttrs.toInt())
                .putInt((if (needOffset64) ZIP64_LIMIT else e.localHeaderOffset).toInt())
                .array()
            writeBytes(hdr)
            writeBytes(e.nameBytes)
            if (extra.isNotEmpty()) writeBytes(extra)
        }

        val cdSize = bytesWritten - cdOffset
        val count = entries.size.toLong()
        val needZip64Eocd = forceZip64 || count >= ZIP64_COUNT_LIMIT || cdSize >= ZIP64_LIMIT || cdOffset >= ZIP64_LIMIT

        if (needZip64Eocd) {
            val zip64EocdOffset = bytesWritten
            val z64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SIG_ZIP64_EOCD)
                .putLong(44L) // remaining size of ZIP64 EOCD record
                .putShort(((HOST_UNIX shl 8) or VERSION_ZIP64).toShort())
                .putShort(VERSION_ZIP64.toShort())
                .putInt(0) // disk number
                .putInt(0) // disk with start of CD
                .putLong(count)
                .putLong(count)
                .putLong(cdSize)
                .putLong(cdOffset)
                .array()
            writeBytes(z64)

            val loc = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SIG_ZIP64_LOCATOR)
                .putInt(0) // disk with ZIP64 EOCD
                .putLong(zip64EocdOffset)
                .putInt(1) // total disks
                .array()
            writeBytes(loc)
        }

        val eocdCount = if (forceZip64 || count >= ZIP64_COUNT_LIMIT) ZIP64_COUNT_LIMIT else count
        val eocdCdSize = if (forceZip64 || cdSize >= ZIP64_LIMIT) ZIP64_LIMIT else cdSize
        val eocdCdOffset = if (forceZip64 || cdOffset >= ZIP64_LIMIT) ZIP64_LIMIT else cdOffset
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SIG_EOCD)
            .putShort(0) // disk number
            .putShort(0) // disk with start of CD
            .putShort(eocdCount.toInt().toShort())
            .putShort(eocdCount.toInt().toShort())
            .putInt(eocdCdSize.toInt())
            .putInt(eocdCdOffset.toInt())
            .putShort(0) // comment length
            .array()
        writeBytes(eocd)
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!finished && !inEntry) finish()
        } finally {
            passwordBytes?.fill(0)
            out.close()
        }
    }

    private fun writeLocalFileHeader(
        versionNeeded: Int,
        flags: Int,
        method: Int,
        dosTime: Int,
        dosDate: Int,
        crc: Long,
        compSize: Long,
        uncompSize: Long,
        nameBytes: ByteArray,
        extra: ByteArray,
    ) {
        val hdr = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SIG_LFH)
            .putShort(versionNeeded.toShort())
            .putShort(flags.toShort())
            .putShort(method.toShort())
            .putShort(dosTime.toShort())
            .putShort(dosDate.toShort())
            .putInt(crc.toInt())
            .putInt(compSize.toInt())
            .putInt(uncompSize.toInt())
            .putShort(nameBytes.size.toShort())
            .putShort(extra.size.toShort())
            .array()
        writeBytes(hdr)
        writeBytes(nameBytes)
        if (extra.isNotEmpty()) writeBytes(extra)
    }

    private fun writeDataDescriptor(crc: Long, compSize: Long, uncompSize: Long, zip64: Boolean) {
        writeBytes(encodeDataDescriptor(crc, compSize, uncompSize, zip64))
    }

    companion object {
        private const val SIG_LFH = 0x04034B50
        private const val SIG_DATA_DESC = 0x08074B50
        private const val SIG_CDH = 0x02014B50
        private const val SIG_ZIP64_EOCD = 0x06064B50

        internal fun encodeDataDescriptor(crc: Long, compSize: Long, uncompSize: Long, zip64: Boolean): ByteArray =
            if (zip64) {
                ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(SIG_DATA_DESC)
                    .putInt(crc.toInt())
                    .putLong(compSize)
                    .putLong(uncompSize)
                    .array()
            } else {
                ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(SIG_DATA_DESC)
                    .putInt(crc.toInt())
                    .putInt(compSize.toInt())
                    .putInt(uncompSize.toInt())
                    .array()
            }
        private const val SIG_ZIP64_LOCATOR = 0x07064B50
        private const val SIG_EOCD = 0x06054B50

        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_DATA_DESCRIPTOR = 0x0008
        private const val FLAG_UTF8 = 0x0800

        private const val VERSION_STANDARD = 20 // 2.0
        private const val VERSION_ZIP64 = 45 // 4.5
        private const val VERSION_WINZIP_AES = 51 // 5.1
        private const val HOST_UNIX = 3

        private const val UNIX_DIR_ATTRS = 0x41ED0010L // drwxr-xr-x + MS-DOS directory bit
        private const val UNIX_FILE_ATTRS = 0x81A40000L // -rw-r--r--

        private const val ZIP64_EXTRA_ID = 0x0001
        internal const val ZIP64_LIMIT = 0xFFFFFFFFL
        private const val ZIP64_PRE_LIMIT = 0xFFF00000L // 4 GiB - 1 MiB
        private const val ZIP64_COUNT_LIMIT = 0xFFFFL
        private const val MAX_NAME_BYTES = 0xFFFF

        private const val IO_BUF_SIZE = 128 * 1024
        private const val DEFLATE_BUF_SIZE = 64 * 1024
        private val EMPTY_BYTES = ByteArray(0)
        private val SECURE_RANDOM = SecureRandom()

        /**
         * Returns true when a bit-3 streaming entry must emit a 24-byte 64-bit Data Descriptor.
         * Because LFH omits `0x0001`, OpenJDK 21 and Android `libcore` `ZipInputStream.readEnd`
         * read a 64-bit Data Descriptor iff `compSize > 0xFFFFFFFFL || uncompSize > 0xFFFFFFFFL`
         * (strictly `>`), as `0xFFFFFFFF` fits in an unsigned 32-bit descriptor field.
         */
        internal fun shouldWriteZip64DataDescriptor(
            compSize: Long,
            uncompSize: Long,
        ): Boolean = compSize > ZIP64_LIMIT || uncompSize > ZIP64_LIMIT

        private fun buildZip64Extra(uncomp: Long?, comp: Long?, offset: Long?): ByteArray {
            val payload = ByteArrayOutputStream(24)
            fun putLongLe(v: Long) {
                val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
                payload.write(b)
            }
            if (uncomp != null) putLongLe(uncomp)
            if (comp != null) putLongLe(comp)
            if (offset != null) putLongLe(offset)
            val data = payload.toByteArray()
            return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(ZIP64_EXTRA_ID.toShort())
                .putShort(data.size.toShort())
                .put(data)
                .array()
        }

        private fun toDosDateTime(epochMs: Long): Pair<Int, Int> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = if (epochMs > 0L) epochMs else System.currentTimeMillis()
            }
            val year = cal.get(Calendar.YEAR)
            if (year < 1980) {
                return 0 to ((1 shl 5) or 1) // 1980-01-01 00:00:00
            }
            val clampedYear = year.coerceAtMost(2107)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            val second = cal.get(Calendar.SECOND)
            val dosTime = (hour shl 11) or (minute shl 5) or (second ushr 1)
            val dosDate = ((clampedYear - 1980) shl 9) or (month shl 5) or day
            return dosTime to dosDate
        }
    }
}
