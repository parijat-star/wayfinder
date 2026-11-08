// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.CRC32

/** POSIX metadata from lstat(2) (local files only). */
data class PosixInfo(val mode: Int, val inode: Long, val links: Long, val uid: Int, val gid: Int)

/** Free / total bytes of the file system holding a path (statvfs). */
data class FsSpace(val free: Long, val total: Long)

data class Checksums(val sha256: String, val crc32: String)

object FileInfo {
    private const val S_IFMT = 0xF000

    fun typeChar(mode: Int): Char = when (mode and S_IFMT) {
        0x4000 -> 'd'
        0xA000 -> 'l'
        0x2000 -> 'c'
        0x6000 -> 'b'
        0x1000 -> 'p'
        0xC000 -> 's'
        else -> '-'
    }

    /** `ls -l` style mode string, e.g. `drwxr-xr-x` (with s/S, t/T for setuid/setgid/sticky). */
    fun posixString(mode: Int): String {
        val sb = StringBuilder().append(typeChar(mode))
        val special = (mode shr 9) and 7
        for (i in 2 downTo 0) {
            val bits = (mode shr (i * 3)) and 7
            sb.append(if (bits and 4 != 0) 'r' else '-')
            sb.append(if (bits and 2 != 0) 'w' else '-')
            val x = bits and 1 != 0
            val sp = special and (1 shl i) != 0
            sb.append(
                when {
                    sp && i == 0 -> if (x) 't' else 'T'
                    sp -> if (x) 's' else 'S'
                    x -> 'x'
                    else -> '-'
                },
            )
        }
        return sb.toString()
    }

    /** Permission bits in octal, e.g. `0755`. */
    fun octal(mode: Int): String = "%04o".format(mode and 0xFFF)

    /** lstat(2) via android.system.Os; null off-device or on error. */
    fun lstat(path: String): PosixInfo? = runCatching {
        val st = android.system.Os.lstat(path)
        PosixInfo(st.st_mode, st.st_ino, st.st_nlink, st.st_uid, st.st_gid)
    }.getOrNull()

    fun mode(path: String): Int = runCatching { android.system.Os.lstat(path).st_mode }.getOrDefault(0)

    fun space(path: String): FsSpace? = runCatching {
        val st = android.system.Os.statvfs(path)
        FsSpace(st.f_bavail * st.f_frsize, st.f_blocks * st.f_frsize)
    }.getOrNull()

    /** Streams [input] once, computing SHA-256 and CRC32; [onProgress] receives bytes read so far. Cancellable. */
    suspend fun checksums(input: InputStream, onProgress: (Long) -> Unit = {}): Checksums {
        val sha = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var last = 0L
        input.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n); crc.update(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - last > 100_000_000L) { onProgress(done); last = now }
            }
        }
        onProgress(done)
        return Checksums(sha.digest().joinToString("") { "%02x".format(it) }, "%08x".format(crc.value))
    }

    /**
     * Sanitises a zip entry name into a relative path (`a/b/c`), or null when it would escape the target
     * folder (zip-slip: `..` segments) or has no usable segments. Backslashes are treated as separators.
     * Entries nested deeper than [FsPath.MAX_DEPTH] are rejected too (they can't be extracted and would make
     * recursive folder synthesis expensive).
     */
    fun safeZipEntryPath(name: String): String? {
        val segs = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty() || segs.size > FsPath.MAX_DEPTH || segs.any { it == ".." || it.contains('\u0000') }) return null
        return segs.joinToString("/")
    }

    /** True if [path] is a symbolic link (never follows it). */
    fun isSymlink(path: String): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(path)) }.getOrDefault(false)

    /**
     * Deletes [f] and everything below it **without following symbolic links** (a link is removed, never the
     * folder it points to), unlike Kotlin's `File.deleteRecursively` which descends into linked folders.
     * Returns true when [f] no longer exists.
     */
    fun deleteTree(f: File): Boolean {
        val root = f.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * Copies [src] to [dst] recursively without following or recreating symbolic links.
     * [dst] must not exist.
     */
    fun copyTree(src: File, dst: File) {
        val from = src.toPath()
        val to = dst.toPath()
        if (Files.isSymbolicLink(from)) return
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                val target = to.resolve(from.relativize(file).toString())
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Turns an untrusted display name (drag and drop, content providers) into a safe single file name:
     * separators, NUL and control characters become `_`, leading/trailing whitespace is trimmed, `.`/`..` and
     * empty names fall back to [fallback], and the result is shortened to [FsPath.MAX_NAME_BYTES] UTF-8 bytes
     * (keeping the extension when possible).
     */
    fun sanitizeFileName(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().map { c -> if (c == '/' || c == '\\' || c < ' ' || c == '\u007f') '_' else c }
            .joinToString("").trim()
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return fallback
        if (cleaned.toByteArray(Charsets.UTF_8).size <= FsPath.MAX_NAME_BYTES) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        val maxBaseChars = (FsPath.MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        var base = cleaned.dropLast(ext.length).let {
            if (it.length > maxBaseChars) it.take(maxBaseChars) else it
        }
        if (base.isNotEmpty() && base.last().isHighSurrogate()) base = base.dropLast(1)
        while (base.isNotEmpty() && (base + ext).toByteArray(Charsets.UTF_8).size > FsPath.MAX_NAME_BYTES) {
            base = base.dropLast(1)
            // Never cut a surrogate pair in half.
            if (base.isNotEmpty() && base.last().isHighSurrogate()) base = base.dropLast(1)
        }
        return (base + ext).ifEmpty { fallback }
    }
}

/** Fails with an [IOException] once more than [limit] bytes have been read (zip bombs, endless responses). */
class BoundedInputStream(input: InputStream, private val limit: Long, private val what: String = "Data") : FilterInputStream(input) {
    private var count = 0L

    private fun add(n: Long) {
        if (n > 0) count += n
        if (count > limit) throw IOException("$what is larger than expected")
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    override fun markSupported() = false
}
