// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Quick Look summary of an archive: the first entries (name, size, dir), the entry count and total size. */
data class ZipSummary(val entries: List<Triple<String, Long, Boolean>>, val total: Int, val bytes: Long)

/**
 * Read-only view of a local archive (`.zip`, `.tar.gz`, `.tgz`, `.tar`) as a folder tree. Paths look like `/dir/a.zip!/inner/file`
 * or `/dir/a.tar.gz!/inner/file` (see [FsPath.zipSplit]). Entry names are sanitised with [FileInfo.safeZipEntryPath], so unsafe
 * entries (`../x`) are hidden; implicit parent folders are synthesised. Corrupt archives only ever surface as [IOException]s;
 * entry streams never inflate more than the entry's declared size (zip bombs).
 */
class ZipFileSystem : FileSystem {
    private class Index(
        val stamp: Long,
        val charset: Charset,
        val dirs: Map<String, List<FileEntry>>,
        val all: Map<String, FileEntry>,
        /** Sanitised inner path → original entry name in the archive. */
        val names: Map<String, String>,
    )

    private val cache = LinkedHashMap<String, Index>()

    private fun split(path: String): Pair<String, String> = FsPath.zipSplit(path) ?: throw IOException("Not an archive path")

    private fun open(f: File, cs: Charset) = ZipFile(f, cs)

    @Synchronized
    private fun index(archive: String): Index {
        val f = File(archive)
        if (!f.isFile) throw IOException("This archive no longer exists")
        val stamp = f.lastModified() * 31 + f.length()
        cache[archive]?.takeIf { it.stamp == stamp }?.let { return it }
        val idx = if (isTar(f)) {
            try {
                buildTar(f, stamp)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Can't read this archive: ${e.message}", e)
            }
        } else {
            try { build(f, stamp, Charsets.UTF_8) } catch (e: IOException) { throw e } catch (_: Exception) {
                // Old archives often use CP437 names, which the UTF-8 decoder rejects.
                try {
                    build(f, stamp, Charset.forName("IBM437"))
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Can't read this archive: ${e.message}", e)
                }
            }
        }
        cache[archive] = idx
        while (cache.size > 4) cache.remove(cache.keys.first())
        return idx
    }

    private fun build(f: File, stamp: Long, cs: Charset): Index {
        val root = FsPath.zipRoot(f.path)
        val dirs = HashMap<String, MutableList<FileEntry>>().apply { put("", mutableListOf()) }
        val all = HashMap<String, FileEntry>()
        val names = HashMap<String, String>()
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
        try {
            open(f, cs).use { z ->
                if (z.size() > MAX_ENTRIES) throw IOException("This archive has too many entries to browse (${z.size()})")
                for (ze in z.entries()) {
                    val inner = FileInfo.safeZipEntryPath(ze.name) ?: continue
                    if (ze.isDirectory) { ensureDir(inner, ze.time); continue }
                    // A file whose path is already taken (duplicate entry, or a folder of that name) is hidden.
                    if (inner in all) continue
                    val parent = inner.substringBeforeLast('/', "")
                    ensureDir(parent, ze.time)
                    // A file shadowing a synthesised folder path segment ("a" file + "a/b"): keep the folder.
                    if (all[parent]?.isDir == false) continue
                    val e = FileEntry(FsPath.child(root, inner), inner.substringAfterLast('/'), false, size = ze.size.coerceAtLeast(0), modified = ze.time.coerceAtLeast(0))
                    all[inner] = e
                    names[inner] = ze.name
                    dirs.getOrPut(parent) { mutableListOf() }.add(e)
                }
            }
        } catch (e: java.util.zip.ZipException) {
            if (cs == Charsets.UTF_8) throw IllegalArgumentException(e) // retry with another charset
            throw IOException("Can't read this archive: ${e.message}")
        }
        return Index(stamp, cs, dirs, all, names)
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
        return Index(stamp, Charsets.UTF_8, dirs, all, names)
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
            val tin = try { openTar(f) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Can't read this archive: ${e.message}", e) }
            try {
                var ze = tin.nextEntry
                while (ze != null) {
                    if (ze.name == name) {
                        return object : FilterInputStream(BoundedInputStream(tin, limit, FsPath.name(path)).buffered(64 * 1024)) {
                            override fun read(): Int = try { super.read() } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Can't read ${FsPath.name(path)}: ${e.message}", e) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = try { super.read(b, off, len) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Can't read ${FsPath.name(path)}: ${e.message}", e) }
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
        val z = try { open(f, idx.charset) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Can't read this archive: ${e.message}", e) }
        val entry = z.getEntry(name) ?: run { z.close(); throw IOException("${FsPath.name(path)} is missing from the archive") }
        // Never inflate more than the entry claims to contain (zip bombs with lying headers).
        val limit = if (entry.size >= 0) entry.size else MAX_UNKNOWN_SIZE
        val raw = try { z.getInputStream(entry) } catch (e: Exception) { z.close(); throw IOException("Can't read ${FsPath.name(path)}: ${e.message}", e) }
        return object : FilterInputStream(BoundedInputStream(raw, limit, FsPath.name(path)).buffered(64 * 1024)) {
            override fun close() { try { super.close() } finally { z.close() } }
        }
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
         * Entry count, the first [maxListed] entries (name, size, dir) and the saturating total size of [f].
         * Throws only [IOException] for unreadable archives.
         */
        fun summary(f: File, maxListed: Int = 3000): ZipSummary {
            if (isTar(f)) {
                return try {
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
                    ZipSummary(listed, count, bytes)
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Can't read this archive: ${e.message}", e)
                }
            }
            fun scan(cs: Charset): ZipSummary {
                val listed = ArrayList<Triple<String, Long, Boolean>>()
                var count = 0
                var bytes = 0L
                ZipFile(f, cs).use { z ->
                    val en = z.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        count++
                        val s = e.size.coerceAtLeast(0)
                        bytes = if (Long.MAX_VALUE - bytes < s) Long.MAX_VALUE else bytes + s
                        if (listed.size < maxListed) listed += Triple(e.name, e.size, e.isDirectory)
                    }
                }
                return ZipSummary(listed, count, bytes)
            }
            return try {
                scan(Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    scan(Charset.forName("IBM437"))
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Can't read this archive: ${e.message}", e)
                }
            }
        }
    }
}
