// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Quick Look summary of an archive: the first entries (name, size, dir), the entry count, total size and lock flag. */
data class ZipSummary(
    val entries: List<Triple<String, Long, Boolean>>,
    val total: Int,
    val bytes: Long,
    val encrypted: Boolean = false,
)

/**
 * Read-only view of a local archive (`.zip`, `.tar.gz`, `.tgz`, `.tar`) as a folder tree. Paths look like `/dir/a.zip!/inner/file`
 * or `/dir/a.tar.gz!/inner/file` (see [FsPath.zipSplit]). Entry names are sanitised with [FileInfo.safeZipEntryPath], so unsafe
 * entries (`../x`) are hidden; implicit parent folders are synthesised. Corrupt archives only ever surface as [IOException]s;
 * entry streams never inflate more than the entry's declared size (zip bombs).
 *
 * Password-protected ZIP entries can be browsed without a password (filenames are unencrypted); passwords are held only in
 * memory for the active session and requested lazily when reading, extracting, or copying encrypted content.
 */
class ZipFileSystem : FileSystem {
    private class Index(
        val stamp: Long,
        val dirs: Map<String, List<FileEntry>>,
        val all: Map<String, FileEntry>,
        /** Sanitised inner path → original entry name in the archive (used for TAR lookups / ZIP fallback). */
        val names: Map<String, String>,
        /** Sanitised inner path → local header offset in the ZIP (disambiguates duplicate names). */
        val offsets: Map<String, Long>,
        /** Sanitised inner paths of encrypted entries. */
        val encrypted: Set<String>,
    )

    private data class CachedPassword(val stamp: Long, val password: String)

    private val cache = LinkedHashMap<String, Index>()
    private val passwords = ConcurrentHashMap<String, CachedPassword>()

    private fun split(path: String): Pair<String, String> = FsPath.zipSplit(path) ?: throw IOException("Not an archive path")

    private fun archiveKey(archivePath: String): String =
        if (FsPath.isLocal(archivePath)) runCatching { File(archivePath).canonicalPath }.getOrDefault(FsPath.normalize(archivePath))
        else FsPath.normalize(archivePath)

    private fun stampOf(archivePath: String): Long =
        if (FsPath.isLocal(archivePath)) File(archivePath).takeIf { it.isFile }?.let { it.lastModified() * 31 + it.length() } ?: -1L
        else 0L

    /** Stores [password] in memory for [archivePath] (keyed to its current file stamp; never persisted). */
    fun setPassword(archivePath: String, password: String) {
        passwords[archiveKey(archivePath)] = CachedPassword(stampOf(archivePath), password)
    }

    /** Returns the in-memory session password for [archivePath], or null if unset or if the file changed on disk. */
    fun passwordFor(archivePath: String): String? {
        val key = archiveKey(archivePath)
        val cached = passwords[key] ?: return null
        val curStamp = stampOf(archivePath)
        if (cached.stamp != curStamp) {
            passwords.remove(key, cached)
            return null
        }
        return cached.password
    }

    /** Evicts any cached session password for [archivePath]. */
    fun clearPassword(archivePath: String) {
        passwords.remove(archiveKey(archivePath))
    }

    /** Evicts all cached session passwords. */
    fun clearAllPasswords() {
        passwords.clear()
    }

    /**
     * Returns true if [path] (either an archive file `/dir/a.zip` or an inner archive file/folder
     * `/dir/a.zip!/sub`) contains at least one encrypted entry.
     */
    fun hasEncryptedEntry(path: String): Boolean {
        val split = FsPath.zipSplit(path)
        val archive = split?.first ?: path
        val inner = split?.second ?: ""
        val f = File(archive)
        if (!f.isFile || isTar(f)) return false
        val idx = try { index(archive) } catch (_: Exception) { return false }
        if (inner.isEmpty()) return idx.encrypted.isNotEmpty()
        if (inner in idx.encrypted) return true
        val entry = idx.all[inner] ?: return false
        if (!entry.isDir) return false
        val prefix = "$inner/"
        return idx.encrypted.any { it.startsWith(prefix) }
    }

    /** Returns true if reading or extracting [path] requires a password that is not yet cached in memory. */
    fun needsPassword(path: String): Boolean {
        val archive = FsPath.zipSplit(path)?.first ?: path
        return hasEncryptedEntry(path) && passwordFor(archive) == null
    }

    @Synchronized
    private fun index(archive: String): Index {
        val f = File(archive)
        if (!f.isFile) throw IOException("This archive no longer exists")
        val stamp = f.lastModified() * 31 + f.length()
        cache[archive]?.takeIf { it.stamp == stamp }?.let { return it }
        val idx = if (isTar(f)) {
            ZipCrypto.wrapZipIo { buildTar(f, stamp) }
        } else {
            ZipCrypto.wrapZipIo { buildZip(f, stamp) }
        }
        cache[archive] = idx
        while (cache.size > 4) cache.remove(cache.keys.first())
        return idx
    }

    private fun buildZip(f: File, stamp: Long): Index {
        val root = FsPath.zipRoot(f.path)
        val dirs = HashMap<String, MutableList<FileEntry>>().apply { put("", mutableListOf()) }
        val all = HashMap<String, FileEntry>()
        val names = HashMap<String, String>()
        val offsets = HashMap<String, Long>()
        val encrypted = HashSet<String>()
        fun ensureDir(inner: String, time: Long) {
            // Iterative: entry names may be nested hundreds of levels deep.
            val missing = ArrayList<String>()
            var cur = inner
            while (cur.isNotEmpty() && cur !in all) { missing += cur; cur = cur.substringBeforeLast('/', "") }
            for (d in missing.asReversed()) {
                val parent = d.substringBeforeLast('/', "")
                val e = FileEntry(FsPath.child(root, d), d.substringAfterLast('/'), true, modified = time.coerceAtLeast(0))
                all[d] = e
                dirs.getOrPut(parent) { mutableListOf() }.add(e)
                dirs.getOrPut(d) { mutableListOf() }
            }
        }
        ZipCrypto.openZipFile(f).use { z ->
            var count = 0
            val en = z.entries
            while (en.hasMoreElements()) {
                if (++count > MAX_ENTRIES) throw IOException("This archive has too many entries to browse ($count)")
                val ze = en.nextElement()
                val decoded = ZipCrypto.decodeEntryName(ze)
                val inner = FileInfo.safeZipEntryPath(decoded) ?: continue
                if (ze.isDirectory) { ensureDir(inner, ze.time); continue }
                // A file whose path is already taken (duplicate entry, or a folder of that name) is hidden.
                if (inner in all) continue
                val parent = inner.substringBeforeLast('/', "")
                ensureDir(parent, ze.time)
                // A file shadowing a synthesised folder path segment ("a" file + "a/b"): keep the folder.
                if (all[parent]?.isDir == false) continue
                val isEnc = ze.generalPurposeBit.usesEncryption()
                val e = FileEntry(
                    path = FsPath.child(root, inner),
                    name = inner.substringAfterLast('/'),
                    isDir = false,
                    size = ze.size.coerceAtLeast(0),
                    modified = ze.time.coerceAtLeast(0),
                    isEncrypted = isEnc,
                )
                all[inner] = e
                names[inner] = ze.name
                offsets[inner] = ze.localHeaderOffset
                if (isEnc) encrypted += inner
                dirs.getOrPut(parent) { mutableListOf() }.add(e)
            }
        }
        return Index(stamp, dirs, all, names, offsets, encrypted)
    }

    private fun buildTar(f: File, stamp: Long): Index {
        val root = FsPath.zipRoot(f.path)
        val dirs = HashMap<String, MutableList<FileEntry>>().apply { put("", mutableListOf()) }
        val all = HashMap<String, FileEntry>()
        val names = HashMap<String, String>()
        fun ensureDir(inner: String, time: Long) {
            val missing = ArrayList<String>()
            var cur = inner
            while (cur.isNotEmpty() && cur !in all) { missing += cur; cur = cur.substringBeforeLast('/', "") }
            for (d in missing.asReversed()) {
                val parent = d.substringBeforeLast('/', "")
                val e = FileEntry(FsPath.child(root, d), d.substringAfterLast('/'), true, modified = time.coerceAtLeast(0))
                all[d] = e
                dirs.getOrPut(parent) { mutableListOf() }.add(e)
                dirs.getOrPut(d) { mutableListOf() }
            }
        }
        var entryCount = 0
        openTar(f).use { tin ->
            var ze = tin.nextEntry
            while (ze != null) {
                if (++entryCount > MAX_ENTRIES) throw IOException("This archive has too many entries to browse ($entryCount)")
                if (isSafeTarEntry(ze)) {
                    val inner = FileInfo.safeZipEntryPath(ze.name)
                    if (inner != null) {
                        val time = ze.modTime?.time ?: 0L
                        if (ze.isDirectory) {
                            ensureDir(inner, time)
                        } else if (inner !in all) {
                            val parent = inner.substringBeforeLast('/', "")
                            ensureDir(parent, time)
                            if (all[parent]?.isDir != false) {
                                val e = FileEntry(FsPath.child(root, inner), inner.substringAfterLast('/'), false, size = ze.size.coerceAtLeast(0), modified = time.coerceAtLeast(0))
                                all[inner] = e
                                names[inner] = ze.name
                                dirs.getOrPut(parent) { mutableListOf() }.add(e)
                            }
                        }
                    }
                }
                ze = tin.nextEntry
            }
        }
        return Index(stamp, dirs, all, names, emptyMap(), emptySet())
    }

    override fun list(path: String): List<FileEntry> {
        val (archive, inner) = split(path)
        return index(archive).dirs[inner] ?: throw IOException("This folder doesn't exist in the archive")
    }

    override fun stat(path: String): FileEntry? {
        val (archive, inner) = split(path)
        val f = File(archive)
        if (inner.isEmpty()) return if (f.isFile) FileEntry(FsPath.zipRoot(archive), f.name, true, modified = f.lastModified()) else null
        return runCatching { index(archive).all[inner] }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val (archive, inner) = split(path)
        val f = File(archive)
        val idx = index(archive)
        val name = idx.names[inner] ?: throw IOException("${FsPath.name(path)} isn't a file in the archive")
        if (isTar(f)) {
            val entrySize = idx.all[inner]?.size ?: -1L
            val limit = if (entrySize >= 0) entrySize else MAX_UNKNOWN_SIZE
            val tin = ZipCrypto.wrapZipIo { openTar(f) }
            try {
                var ze = tin.nextEntry
                while (ze != null) {
                    if (ze.name == name) {
                        return object : FilterInputStream(BoundedInputStream(tin, limit, FsPath.name(path)).buffered(64 * 1024)) {
                            override fun read(): Int = ZipCrypto.wrapZipIo { super.read() }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = ZipCrypto.wrapZipIo { super.read(b, off, len) }
                            override fun close() { try { super.close() } finally { tin.close() } }
                        }
                    }
                    ze = tin.nextEntry
                }
                tin.close()
                throw IOException("${FsPath.name(path)} is missing from the archive")
            } catch (e: Exception) {
                runCatching { tin.close() }
                throw if (e is IOException) e else IOException("Can't read ${FsPath.name(path)}: ${e.message}", e)
            }
        }
        val targetOffset = idx.offsets[inner]
        val z = ZipCrypto.openZipFile(f)
        val entry: ZipArchiveEntry = try {
            findEntry(z, name, targetOffset)
                ?: throw IOException("${FsPath.name(path)} is missing from the archive")
        } catch (t: Throwable) {
            runCatching { z.close() }
            throw t
        }
        // Never inflate more than the entry claims to contain (zip bombs with lying headers).
        val limit = if (entry.size >= 0) entry.size else MAX_UNKNOWN_SIZE
        val pw = passwordFor(archive)
        val raw = try {
            ZipCrypto.openEntryInputStream(z, f, entry, archive, pw, verifyCrc = true)
        } catch (e: ZipPasswordRequiredException) {
            runCatching { z.close() }
            if (e.wrongPassword) clearPassword(archive)
            throw e
        } catch (e: Exception) {
            runCatching { z.close() }
            throw if (e is IOException) e else IOException("Can't read ${FsPath.name(path)}: ${e.message}", e)
        }
        return object : FilterInputStream(BoundedInputStream(raw, limit, FsPath.name(path)).buffered(64 * 1024)) {
            override fun read(): Int = try {
                super.read()
            } catch (e: ZipPasswordRequiredException) {
                if (e.wrongPassword) clearPassword(archive)
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Can't read ${FsPath.name(path)}: ${e.message}", e)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                super.read(b, off, len)
            } catch (e: ZipPasswordRequiredException) {
                if (e.wrongPassword) clearPassword(archive)
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Can't read ${FsPath.name(path)}: ${e.message}", e)
            }

            override fun close() { try { super.close() } finally { z.close() } }
        }
    }

    private fun findEntry(
        z: org.apache.commons.compress.archivers.zip.ZipFile,
        rawName: String,
        targetOffset: Long?,
    ): ZipArchiveEntry? {
        if (targetOffset != null && targetOffset >= 0) {
            val byName = z.getEntries(rawName)
            for (cand in byName) {
                if (cand.localHeaderOffset == targetOffset) return cand
            }
            val all = z.entries
            while (all.hasMoreElements()) {
                val cand = all.nextElement()
                if (cand.localHeaderOffset == targetOffset) return cand
            }
        }
        return z.getEntry(rawName)
    }

    private fun readOnly(): Nothing = throw IOException("Archives are read-only — extract it first")
    override fun mkdir(path: String) = readOnly()
    override fun createFile(path: String) = readOnly()
    override fun delete(path: String) = readOnly()
    override fun rename(path: String, newName: String): String = readOnly()
    override fun openOutput(path: String): OutputStream = readOnly()

    companion object {
        /** Archives with more entries than this are not indexed (memory). */
        const val MAX_ENTRIES = 500_000
        private const val MAX_UNKNOWN_SIZE = 4L shl 30

        fun isSafeTarEntry(ze: org.apache.commons.compress.archivers.tar.TarArchiveEntry): Boolean =
            !ze.isSymbolicLink && !ze.isLink && !ze.isCharacterDevice && !ze.isBlockDevice && !ze.isFIFO && (ze.isDirectory || ze.isFile)

        fun isTar(f: File): Boolean {
            val n = f.name.lowercase()
            return n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar")
        }

        fun isGz(f: File): Boolean {
            val n = f.name.lowercase()
            return n.endsWith(".tar.gz") || n.endsWith(".tgz")
        }

        fun openTar(f: File): TarArchiveInputStream {
            val raw = FileInputStream(f).buffered(64 * 1024)
            val stream: InputStream = if (isGz(f)) GzipCompressorInputStream(raw, true) else raw
            return TarArchiveInputStream(stream, "UTF-8")
        }

        /**
         * Entry count, the first [maxListed] entries (name, size, dir), the saturating total size, and
         * encryption flag of [f]. Throws only [IOException] for unreadable archives.
         */
        fun summary(f: File, maxListed: Int = 3000): ZipSummary {
            if (isTar(f)) {
                return ZipCrypto.wrapZipIo {
                    val listed = ArrayList<Triple<String, Long, Boolean>>()
                    var count = 0
                    var bytes = 0L
                    openTar(f).use { tin ->
                        var ze = tin.nextEntry
                        while (ze != null) {
                            if (++count > MAX_ENTRIES) throw IOException("This archive has too many entries to browse ($count)")
                            if (isSafeTarEntry(ze)) {
                                val s = ze.size.coerceAtLeast(0)
                                bytes = if (Long.MAX_VALUE - bytes < s) Long.MAX_VALUE else bytes + s
                                if (listed.size < maxListed) listed += Triple(ze.name, ze.size, ze.isDirectory)
                            }
                            ze = tin.nextEntry
                        }
                    }
                    ZipSummary(listed, count, bytes, encrypted = false)
                }
            }
            return ZipCrypto.wrapZipIo {
                val listed = ArrayList<Triple<String, Long, Boolean>>()
                var count = 0
                var bytes = 0L
                var encrypted = false
                ZipCrypto.openZipFile(f).use { z ->
                    val en = z.entries
                    while (en.hasMoreElements()) {
                        if (++count > MAX_ENTRIES) throw IOException("This archive has too many entries to browse ($count)")
                        val e = en.nextElement()
                        if (!e.isDirectory && e.generalPurposeBit.usesEncryption()) encrypted = true
                        val s = e.size.coerceAtLeast(0)
                        bytes = if (Long.MAX_VALUE - bytes < s) Long.MAX_VALUE else bytes + s
                        if (listed.size < maxListed) listed += Triple(ZipCrypto.decodeEntryName(e), e.size, e.isDirectory)
                    }
                }
                ZipSummary(listed, count, bytes, encrypted = encrypted)
            }
        }
    }
}
