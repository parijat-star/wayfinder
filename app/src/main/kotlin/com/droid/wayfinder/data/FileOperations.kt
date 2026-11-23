// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Routes paths to the right [FileSystem] (local, SMB, read-only zip, and by URI scheme the WebDAV / FTP / SAF
 * systems in [byScheme]) and resolves virtual roots (Network, Trash).
 */
class FileRepository(
    private val local: FileSystem,
    private val smb: FileSystem,
    private val trash: TrashManager?,
    private val networkRoot: () -> List<FileEntry> = { emptyList() },
    /** Extra file systems keyed by path scheme: `dav`, `davs`, `ftp`, `ftps`, `saf`. */
    private val byScheme: Map<String, FileSystem> = emptyMap(),
) {
    private val zip = ZipFileSystem()
    internal var localOverride: FileSystem? = null
    internal val schemeOverrides = java.util.concurrent.ConcurrentHashMap<String, FileSystem>()

    fun fs(path: String): FileSystem = when {
        FsPath.isSmb(path) -> schemeOverrides["smb"] ?: smb
        FsPath.isZip(path) -> zip
        FsPath.isRemote(path) -> RemoteUri.schemeOf(path)?.let { schemeOverrides[it] ?: byScheme[it] }
            ?: throw java.io.IOException("Unsupported location: ${path.substringBefore("://")}://")
        else -> localOverride ?: local
    }

    fun list(path: String): List<FileEntry> = when (path) {
        FsPath.SMB_ROOT -> networkRoot()
        FsPath.TRASH_ROOT -> trash?.list() ?: emptyList()
        else -> fs(path).list(path)
    }
}

enum class Conflict { Replace, KeepBoth, Skip }

data class Progress(
    val title: String,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val current: String = "",
) {
    val fraction: Float? get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    else if (totalItems > 0) doneItems.toFloat() / totalItems else null
}

/** Result of a copy/move: the destination path created for each source. */
data class Transfer(val source: String, val target: String)

class FileOperations(private val repo: FileRepository) {

    private suspend fun check() = currentCoroutineContext().ensureActive()

    fun uniqueName(dir: String, name: String): String {
        val fs = repo.fs(dir)
        if (!fs.exists(FsPath.child(dir, name))) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (fs.exists(FsPath.child(dir, "$base ($n)$ext"))) {
            if (++n > MAX_NAME_ATTEMPTS) throw IOException("Couldn't find a free name for $name")
        }
        return "$base ($n)$ext"
    }

    /** Names in [sources] that already exist in [destDir]. */
    fun conflicts(sources: List<String>, destDir: String): List<String> {
        val fs = repo.fs(destDir)
        return sources.map { FsPath.name(it) }.filter { fs.exists(FsPath.child(destDir, it)) }
    }

    private fun sizeOf(path: String, depth: Int = 0): Pair<Long, Int> {
        val fs = repo.fs(path)
        val st = fs.stat(path) ?: return 0L to 0
        if (!st.isDir) return st.size to 1
        if (depth > 0 && st.isLink) return 0L to 1 // linked folders are not followed below the top level
        if (depth >= FsPath.MAX_DEPTH) throw IOException("Folders are nested too deeply")
        var bytes = 0L
        var items = 0
        for (c in fs.list(path)) {
            val (b, i) = if (c.isDir && !c.isLink) sizeOf(c.path, depth + 1) else (if (c.isDir) 0L else c.size) to 1
            bytes += b; items += i
        }
        return bytes to items
    }

    suspend fun measure(paths: List<String>): Pair<Long, Int> {
        var bytes = 0L
        var items = 0
        for (p in paths) {
            check()
            val (b, i) = sizeOf(p); bytes += b; items += i
        }
        return bytes to items
    }

    suspend fun transfer(
        sources: List<String>,
        destDir: String,
        move: Boolean,
        conflict: Conflict,
        onProgress: (Progress) -> Unit,
    ): List<Transfer> {
        val title = if (move) "Moving" else "Copying"
        val results = mutableListOf<Transfer>()
        val (totalBytes, totalItems) = if (move && sources.all { repo.fs(it) === repo.fs(destDir) }) 0L to sources.size
        else measure(sources)
        var state = Progress(title, 0, totalBytes, 0, totalItems)
        onProgress(state)

        for (src in sources) {
            check()
            val name = FsPath.name(src)
            var target = FsPath.child(destDir, name)
            val destFs = repo.fs(destDir)
            val samePlace = FsPath.normalize(src) == FsPath.normalize(target)
            if (FsPath.isWithin(src, destDir) && repo.fs(src) === destFs && repo.fs(src).stat(src)?.isDir == true) {
                throw IOException("Can't ${if (move) "move" else "copy"} \"$name\" into itself")
            }
            if (destFs.exists(target)) {
                when {
                    samePlace && move -> continue
                    samePlace || conflict == Conflict.KeepBoth -> target = FsPath.child(destDir, uniqueName(destDir, name))
                    conflict == Conflict.Skip -> continue
                    conflict == Conflict.Replace -> destFs.delete(target)
                }
            }
            state = state.copy(current = name)
            onProgress(state)
            var done = false
            if (move && repo.fs(src) === destFs) {
                done = destFs.move(src, target)
            }
            if (!done) {
                state = copyTree(src, target, state, onProgress)
                if (move) repo.fs(src).delete(src)
            }
            state = state.copy(doneItems = state.doneItems + if (done) 1 else 0)
            results += Transfer(src, target)
            onProgress(state)
        }
        return results
    }

    /** Copies each source next to itself as "name copy.ext" ("name copy 2.ext", …); returns the created paths. */
    suspend fun duplicate(sources: List<String>, onProgress: (Progress) -> Unit): List<String> {
        val (totalBytes, totalItems) = measure(sources)
        var state = Progress("Duplicating", 0, totalBytes, 0, totalItems)
        onProgress(state)
        return sources.map { src ->
            val dir = FsPath.parent(src) ?: throw IOException("Can't duplicate ${FsPath.name(src)}")
            val fs = repo.fs(dir)
            val isDir = repo.fs(src).stat(src)?.isDir ?: throw IOException("${FsPath.name(src)} no longer exists")
            val target = FsPath.child(dir, duplicateName(FsPath.name(src), isDir) { fs.exists(FsPath.child(dir, it)) })
            state = copyTree(src, target, state.copy(current = FsPath.name(src)), onProgress)
            target
        }
    }

    private suspend fun copyTree(src: String, dst: String, start: Progress, onProgress: (Progress) -> Unit, depth: Int = 0): Progress {
        check()
        var state = start
        val sfs = repo.fs(src)
        val dfs = repo.fs(dst)
        val st = sfs.stat(src) ?: throw IOException("${FsPath.name(src)} no longer exists")
        if (st.isDir) {
            // Linked folders are only followed when selected directly: nested links could loop or escape the tree.
            if (depth > 0 && st.isLink) return state
            if (depth >= FsPath.MAX_DEPTH) throw IOException("Folders are nested too deeply")
            dfs.mkdir(dst)
            for (c in sfs.list(src)) {
                // Names come from (possibly hostile) servers: never let them climb out of the destination.
                if (!FsPath.isSafeName(c.name)) continue
                state = copyTree(c.path, FsPath.child(dst, c.name), state, onProgress, depth + 1)
            }
            return state
        }
        sfs.openInput(src).use { input ->
            dfs.openOutput(dst).use { output ->
                val buf = ByteArray(256 * 1024)
                var lastReport = 0L
                while (true) {
                    check()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    state = state.copy(doneBytes = state.doneBytes + n)
                    val now = System.nanoTime()
                    if (now - lastReport > 80_000_000L) { onProgress(state); lastReport = now }
                }
            }
        }
        state = state.copy(doneItems = state.doneItems + 1, current = st.name)
        onProgress(state)
        return state
    }

    suspend fun deletePermanently(paths: List<String>, onProgress: (Progress) -> Unit) {
        var state = Progress("Deleting", totalItems = paths.size)
        for (p in paths) {
            check()
            state = state.copy(current = FsPath.name(p))
            onProgress(state)
            repo.fs(p).delete(p)
            state = state.copy(doneItems = state.doneItems + 1)
        }
        onProgress(state)
    }

    private fun ensureDir(path: String) {
        val fs = repo.fs(path)
        if (fs.exists(path)) return
        FsPath.parent(path)?.let { if (!FsPath.isVirtual(it) && it != path) ensureDir(it) }
        fs.mkdir(path)
    }

    suspend fun compress(sources: List<String>, zipPath: String, onProgress: (Progress) -> Unit): String {
        val (total, items) = measure(sources)
        var state = Progress("Compressing", 0, total, 0, items)
        onProgress(state)
        val self = FsPath.normalize(zipPath)
        try {
            ZipOutputStream(repo.fs(zipPath).openOutput(zipPath)).use { zip ->
                suspend fun add(path: String, entryName: String, depth: Int) {
                    check()
                    // The archive may be created inside a folder being compressed: never read it back into itself.
                    if (FsPath.normalize(path) == self) return
                    val fs = repo.fs(path)
                    val st = fs.stat(path) ?: return
                    if (st.isDir) {
                        if (depth > 0 && st.isLink) return
                        if (depth >= FsPath.MAX_DEPTH) throw IOException("Folders are nested too deeply")
                        zip.putNextEntry(ZipEntry("$entryName/")); zip.closeEntry()
                        for (c in fs.list(path)) if (FsPath.isSafeName(c.name)) add(c.path, "$entryName/${c.name}", depth + 1)
                    } else {
                        zip.putNextEntry(ZipEntry(entryName).apply { time = st.modified })
                        fs.openInput(path).use { input ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                check()
                                val n = input.read(buf)
                                if (n < 0) break
                                zip.write(buf, 0, n)
                                state = state.copy(doneBytes = state.doneBytes + n, current = st.name)
                                onProgress(state)
                            }
                        }
                        zip.closeEntry()
                        state = state.copy(doneItems = state.doneItems + 1)
                    }
                }
                for (s in sources) add(s, FsPath.name(s), 0)
            }
        } catch (e: Throwable) {
            runCatching { repo.fs(zipPath).delete(zipPath) }
            throw e
        }
        return zipPath
    }

    /**
     * Extracts [zipPath] into a new folder inside [destDir]; returns that folder. Entries that would escape the
     * folder (zip-slip) abort the extraction, and [limits] stop zip bombs (too many entries / too much output).
     */
    suspend fun extract(zipPath: String, destDir: String, onProgress: (Progress) -> Unit, limits: ExtractLimits = ExtractLimits()): String {
        val archiveName = FsPath.name(zipPath)
        val base = when {
            archiveName.endsWith(".tar.gz", ignoreCase = true) -> archiveName.dropLast(7)
            archiveName.endsWith(".tgz", ignoreCase = true) -> archiveName.dropLast(4)
            archiveName.endsWith(".tar", ignoreCase = true) -> archiveName.dropLast(4)
            archiveName.endsWith(".zip", ignoreCase = true) -> archiveName.dropLast(4)
            else -> archiveName.substringBeforeLast('.', archiveName)
        }
        val outDir = FsPath.child(destDir, uniqueName(destDir, base))
        val fs = repo.fs(outDir)
        val total = repo.fs(zipPath).stat(zipPath)?.size ?: 0L
        ensureDir(outDir)
        val outCanonical = if (FsPath.isLocal(outDir)) java.io.File(outDir).canonicalPath + "/" else null

        val isTar = archiveName.endsWith(".tar.gz", ignoreCase = true) ||
            archiveName.endsWith(".tgz", ignoreCase = true) ||
            archiveName.endsWith(".tar", ignoreCase = true)

        if (isTar) {
            try {
                extractTar(zipPath, outDir, outCanonical, total, limits, onProgress)
                return outDir
            } catch (t: Throwable) {
                runCatching { fs.delete(outDir) }
                throw t
            }
        }

        // Entry names are UTF-8 in modern archives; old ones use CP437, which the UTF-8 decoder rejects with an
        // IllegalArgumentException (or a ZipException wrapping IllegalArgumentException on JDK 24+): start over with
        // CP437 in that case.
        val charsets = listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("IBM437"))
        for ((attempt, cs) in charsets.withIndex()) {
            try {
                extractOnce(zipPath, outDir, outCanonical, cs, total, limits, onProgress)
                return outDir
            } catch (t: Throwable) {
                runCatching { fs.delete(outDir) }
                val isCharsetError = t is IllegalArgumentException ||
                    (t is java.util.zip.ZipException && t.cause is IllegalArgumentException)
                if (isCharsetError) {
                    if (attempt == charsets.lastIndex) throw IOException("Can't read this archive: ${t.message}", t)
                    ensureDir(outDir)
                } else {
                    throw t
                }
            }
        }
        return outDir
    }

    private suspend fun extractTar(
        archivePath: String, outDir: String, outCanonical: String?, total: Long,
        limits: ExtractLimits, onProgress: (Progress) -> Unit,
    ) {
        val fs = repo.fs(outDir)
        var state = Progress("Extracting", 0, total)
        var entries = 0
        var written = 0L
        val isGz = archivePath.endsWith(".tar.gz", ignoreCase = true) || archivePath.endsWith(".tgz", ignoreCase = true)
        try {
            val raw = repo.fs(archivePath).openInput(archivePath).buffered(64 * 1024)
            val inStream: java.io.InputStream = if (isGz) org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(raw, true) else raw
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(inStream, "UTF-8").use { tin ->
                var e = tin.nextEntry
                while (e != null) {
                    check()
                    if (++entries > limits.maxEntries) throw IOException("This archive has too many entries (over ${limits.maxEntries})")
                    val rel = FileInfo.safeZipEntryPath(e.name)
                    if (rel == null && e.name.replace('\\', '/').split('/').any { it == ".." }) throw IOException("Unsafe path in archive: ${e.name}")
                    if (ZipFileSystem.isSafeTarEntry(e)) {
                        val segs = rel?.split('/').orEmpty()
                        val target = FsPath.child(outDir, segs.joinToString("/"))
                        if (segs.isNotEmpty() && outCanonical != null &&
                            !java.io.File(target).canonicalPath.startsWith(outCanonical)
                        ) throw IOException("Unsafe path in archive: ${e.name}")
                        if (segs.isNotEmpty()) {
                            if (e.isDirectory) ensureDir(target) else {
                                FsPath.parent(target)?.let { ensureDir(it) }
                                fs.openOutput(target).use { out ->
                                    val buf = ByteArray(128 * 1024)
                                    while (true) {
                                        check()
                                        val n = tin.read(buf)
                                        if (n < 0) break
                                        written += n
                                        limits.check(written, total)
                                        out.write(buf, 0, n)
                                        state = state.copy(doneBytes = (state.doneBytes + n / 2).coerceAtMost(total), current = segs.last())
                                        onProgress(state)
                                    }
                                }
                            }
                        }
                    }
                    e = tin.nextEntry
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Can't read this archive: ${e.message}", e)
        }
    }

    private suspend fun extractOnce(
        zipPath: String, outDir: String, outCanonical: String?, cs: java.nio.charset.Charset, total: Long,
        limits: ExtractLimits, onProgress: (Progress) -> Unit,
    ) {
        val fs = repo.fs(outDir)
        var state = Progress("Extracting", 0, total)
        var entries = 0
        var written = 0L
        run {
            ZipInputStream(repo.fs(zipPath).openInput(zipPath), cs).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    check()
                    if (++entries > limits.maxEntries) throw IOException("This archive has too many entries (over ${limits.maxEntries})")
                    // Zip-slip protection: reject entries that would land outside outDir.
                    val rel = FileInfo.safeZipEntryPath(e.name)
                    if (rel == null && e.name.replace('\\', '/').split('/').any { it == ".." }) throw IOException("Unsafe path in archive: ${e.name}")
                    val segs = rel?.split('/').orEmpty()
                    val target = FsPath.child(outDir, segs.joinToString("/"))
                    if (segs.isNotEmpty() && outCanonical != null &&
                        !java.io.File(target).canonicalPath.startsWith(outCanonical)
                    ) throw IOException("Unsafe path in archive: ${e.name}")
                    if (segs.isNotEmpty()) {
                        if (e.isDirectory) ensureDir(target) else {
                            FsPath.parent(target)?.let { ensureDir(it) }
                            fs.openOutput(target).use { out ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    check()
                                    val n = zin.read(buf)
                                    if (n < 0) break
                                    written += n
                                    limits.check(written, total)
                                    out.write(buf, 0, n)
                                    state = state.copy(doneBytes = (state.doneBytes + n / 2).coerceAtMost(total), current = segs.last())
                                    onProgress(state)
                                }
                            }
                        }
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
    }
}

/**
 * Zip-bomb guards for extraction: at most [maxEntries] entries and [maxTotalBytes] of output, and once more than
 * [ratioFloorBytes] have been written the output may not exceed [maxRatio] × the archive size.
 */
data class ExtractLimits(
    val maxEntries: Int = 100_000,
    val maxTotalBytes: Long = 64L shl 30,
    val maxRatio: Long = 200,
    val ratioFloorBytes: Long = 1L shl 30,
) {
    fun check(written: Long, archiveSize: Long) {
        if (written > maxTotalBytes) throw IOException("This archive expands to more than ${maxTotalBytes shr 20} MB")
        if (written > ratioFloorBytes && archiveSize > 0 && written / archiveSize > maxRatio) {
            throw IOException("This archive looks like a zip bomb (compression ratio over $maxRatio:1)")
        }
    }
}
