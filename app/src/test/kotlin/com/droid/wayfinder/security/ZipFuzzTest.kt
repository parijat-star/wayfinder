// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.data.ExtractLimits
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.ZipFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants

/**
 * Hostile-archive tests and byte-level mutation fuzzing for archive handling: [FileOperations.extract]
 * (zip-slip, zip bombs, charset fallback) and [ZipFileSystem] (browse / Quick Look / open). The only
 * acceptable failure for any malformed archive is an [IOException], and nothing may ever be written outside
 * the extraction folder.
 */
class ZipFuzzTest {
    private val k = FuzzKit
    private lateinit var sandbox: File
    private lateinit var dest: File
    private lateinit var archives: File
    private lateinit var outside: File
    private val ops = FileOperations(FileRepository(LocalFileSystem(), LocalFileSystem(), null))

    @Before fun setUp() {
        sandbox = Files.createTempDirectory("wf-zipfuzz").toFile().canonicalFile
        dest = File(sandbox, "dest").apply { mkdirs() }
        archives = File(sandbox, "archives").apply { mkdirs() }
        outside = File(sandbox, "outside").apply { mkdirs(); File(this, "sentinel.txt").writeText("keep") }
    }

    @After fun tearDown() { FileInfo.deleteTree(sandbox) }

    // ------------------------------------------------------------------ raw zip writer

    /** An entry written verbatim: any name bytes, optional lying sizes / CRC, stored or deflated. */
    class Raw(
        val name: ByteArray,
        val data: ByteArray = ByteArray(0),
        val deflate: Boolean = false,
        val declaredSize: Long? = null,
        val badCrc: Boolean = false,
        val utf8: Boolean = true,
    ) {
        constructor(name: String, data: ByteArray = ByteArray(0), deflate: Boolean = false, declaredSize: Long? = null) :
            this(name.toByteArray(Charsets.UTF_8), data, deflate, declaredSize)
    }

    private fun deflateRaw(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Builds a zip by hand so that duplicates, traversal names, NULs and lying headers are possible. */
    fun rawZip(entries: List<Raw>): ByteArray {
        val body = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun le(n: Int, v: Long) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (n == 2) putShort(v.toInt().toShort()) else putInt(v.toInt())
        }.array()
        for (e in entries) {
            val offset = body.size().toLong()
            val payload = if (e.deflate) deflateRaw(e.data) else e.data
            val crc = CRC32().apply { update(e.data) }.value.let { if (e.badCrc) it xor 0x5A5A5A5A else it }
            val size = e.declaredSize ?: e.data.size.toLong()
            val flags = if (e.utf8) 0x800L else 0L
            val method = if (e.deflate) 8L else 0L
            val common = le(2, 20) + le(2, flags) + le(2, method) + le(2, 0) + le(2, 0x21) + le(4, crc) +
                le(4, payload.size.toLong()) + le(4, size) + le(2, e.name.size.toLong()) + le(2, 0)
            body.write(le(4, 0x04034b50)); body.write(common); body.write(e.name); body.write(payload)
            central.write(le(4, 0x02014b50)); central.write(le(2, 20)); central.write(common)
            central.write(le(2, 0) + le(2, 0) + le(2, 0) + le(4, 0) + le(4, offset)); central.write(e.name)
        }
        val cdOffset = body.size().toLong()
        val cd = central.toByteArray()
        body.write(cd)
        body.write(le(4, 0x06054b50) + le(2, 0) + le(2, 0) + le(2, entries.size.toLong()) + le(2, entries.size.toLong()) +
            le(4, cd.size.toLong()) + le(4, cdOffset) + le(2, 0))
        return body.toByteArray()
    }

    private var counter = 0
    private fun file(bytes: ByteArray, name: String = "a${counter++}.zip") = File(archives, name).apply { writeBytes(bytes) }

    /** Everything under the sandbox except the archives folder, the extraction destination and the sentinel. */
    private fun assertNothingEscaped(what: String) {
        assertEquals("sentinel intact ($what)", "keep", File(outside, "sentinel.txt").readText())
        assertEquals("outside untouched ($what)", listOf("sentinel.txt"), outside.list()!!.toList())
        assertEquals("sandbox top level ($what)", setOf("archives", "dest", "outside"), sandbox.list()!!.toSet())
        Files.walk(sandbox.toPath()).use { s ->
            s.forEach { p ->
                val c = p.toFile()
                if (c.isFile && !FileInfo.isSymlink(c.path)) {
                    val ok = c.canonicalPath.startsWith(dest.path + "/") || c.canonicalPath.startsWith(archives.path + "/") ||
                        c.canonicalPath == File(outside, "sentinel.txt").path
                    assertTrue("file escaped: $c ($what)", ok)
                }
            }
        }
    }

    private fun extract(zip: File, limits: ExtractLimits = ExtractLimits()): String =
        runBlocking { ops.extract(zip.path, dest.path, {}, limits) }

    // ------------------------------------------------------------------ hand-written hostile archives

    private val hostileNames = listOf(
        "../evil.txt", "../../evil.txt", "../outside/evil.txt", "a/../../evil.txt", "a/b/../../../evil.txt",
        "/abs/evil.txt", "//evil.txt", "..\\evil.txt", "a\\..\\..\\evil.txt", "C:\\evil.txt", "C:/evil.txt",
        "a\u0000b.txt", "..\u0000/x", "./ok.txt", ".", "..", "", "/", "a//b", "dir/", "dir/../../x/",
        "%2e%2e/evil.txt", "..%2Fevil.txt", "\u202Etxt.exe", "a".repeat(300), "d/".repeat(FsPath.MAX_DEPTH + 5) + "f",
        "d/".repeat(FsPath.MAX_DEPTH - 1) + "f", "😀/..😀/f",
    )

    @Test
    fun hostileEntryNamesNeverEscape() {
        for (name in hostileNames) {
            val zip = file(rawZip(listOf(Raw("ok.txt", "fine".toByteArray()), Raw(name, "pwned".toByteArray()))))
            val out = try { extract(zip) } catch (e: IOException) { null }
            assertNothingEscaped(k.escape(name))
            if (out != null) {
                assertTrue(FsPath.isWithin(dest.path, out))
                // An entry that sanitises to the same name (e.g. "./ok.txt") legitimately replaces it.
                if (FileInfo.safeZipEntryPath(name) != "ok.txt") assertEquals("fine", File(out, "ok.txt").readText())
            }
            FileInfo.deleteTree(dest); dest.mkdirs()
        }
    }

    @Test
    fun traversalAbortsAndCleansUp() {
        val zip = file(rawZip(listOf(Raw("first.txt", "x".toByteArray()), Raw("../../outside/evil.txt", "pwned".toByteArray()))))
        try { extract(zip); fail("zip-slip archive must be refused") } catch (_: IOException) {}
        assertNothingEscaped("slip")
        assertEquals("partial output removed", 0, dest.list()!!.size)
    }

    @Test
    fun linkPlantedInsideDestinationIsNotFollowed() {
        // A folder link in the destination that points outside: extraction always creates a fresh folder, and the
        // canonical-path check refuses anything that would resolve through a link.
        Files.createSymbolicLink(File(dest, "a").toPath(), outside.toPath())
        val zip = file(rawZip(listOf(Raw("x.txt", "1".toByteArray()))), "a.zip")
        val out = extract(zip)
        assertFalse("fresh folder, not the link", FileInfo.isSymlink(out))
        assertNothingEscaped("planted link")
    }

    @Test
    fun duplicateAndShadowingEntries() {
        val zip = file(rawZip(listOf(
            Raw("a", "file".toByteArray()), Raw("a/b", "nested".toByteArray()), Raw("a", "again".toByteArray()),
            Raw("x/", ByteArray(0)), Raw("x", "file-over-dir".toByteArray()), Raw("y/z", "1".toByteArray()), Raw("y", "2".toByteArray()),
        )))
        val zfs = ZipFileSystem()
        val root = FsPath.zipRoot(zip.path)
        val top = zfs.list(root)
        assertEquals("unique names in listing", top.map { it.name }.distinct().size, top.size)
        walk(zfs, root, 0)
        // Extraction may fail on the file/folder clash, but only with an IOException and without escaping.
        try { extract(zip) } catch (_: IOException) {}
        assertNothingEscaped("duplicates")
    }

    @Test
    fun cp437NamesFallBack() {
        // Not valid UTF-8 (0x82 = 'é' in CP437) and no UTF-8 flag: must extract with the CP437 fallback.
        val name = byteArrayOf('r'.code.toByte(), 0x82.toByte(), 's'.code.toByte(), 'u'.code.toByte(), 'm'.code.toByte(), 0x82.toByte(), '.'.code.toByte(), 't'.code.toByte())
        val zip = file(rawZip(listOf(Raw(name, "cv".toByteArray(), utf8 = false))))
        val out = extract(zip)
        assertEquals(listOf("résumé.t"), File(out).list()!!.toList())
        assertEquals(1, ZipFileSystem().list(FsPath.zipRoot(zip.path)).size)
        assertEquals(1, ZipFileSystem.summary(zip).total)
    }

    // ------------------------------------------------------------------ zip bombs

    @Test
    fun bombsAreStopped() {
        val zeros = ByteArray(8 shl 20)
        val bomb = file(rawZip(listOf(Raw("zeros.bin", zeros, deflate = true))))
        assertTrue("compressed well", bomb.length() < 64 * 1024)
        // Absolute output cap.
        try { extract(bomb, ExtractLimits(maxTotalBytes = 1 shl 20)); fail("size cap") } catch (_: IOException) {}
        assertEquals(0, dest.list()!!.size)
        // Compression ratio cap.
        try { extract(bomb, ExtractLimits(maxRatio = 50, ratioFloorBytes = 0)); fail("ratio cap") } catch (_: IOException) {}
        assertEquals(0, dest.list()!!.size)
        // Entry count cap.
        val many = file(rawZip((1..50).map { Raw("f$it", ByteArray(1)) }))
        try { extract(many, ExtractLimits(maxEntries = 10)); fail("entry cap") } catch (_: IOException) {}
        assertEquals(0, dest.list()!!.size)
        // Defaults accept the ordinary archive.
        extract(bomb)
        assertNothingEscaped("bomb")
    }

    @Test
    fun lyingSizesNeverInflateMoreThanDeclared() {
        val big = ByteArray(4 shl 20) { (it % 7).toByte() }
        val zip = file(rawZip(listOf(Raw("small.txt", big, deflate = true, declaredSize = 10))))
        val zfs = ZipFileSystem()
        val path = FsPath.child(FsPath.zipRoot(zip.path), "small.txt")
        assertEquals(10L, zfs.stat(path)!!.size)
        var read = 0L
        try {
            zfs.openInput(path).use { s -> val b = ByteArray(8192); while (true) { val n = s.read(b); if (n < 0) break; read += n } }
        } catch (_: IOException) {}
        assertTrue("inflated $read bytes for a 10-byte entry", read <= 10)
        // Streaming extraction checks the size / CRC at the end of the entry and must fail cleanly.
        try { extract(zip) } catch (_: IOException) {}
        assertNothingEscaped("lying size")
    }

    @Test
    fun deeplyNestedArchiveIsHandled() {
        val deep = "d/".repeat(FsPath.MAX_DEPTH - 1) + "f.txt"
        val tooDeep = "e/".repeat(5_000) + "f.txt"
        val zip = file(rawZip(listOf(Raw(deep, "x".toByteArray()), Raw(tooDeep, "y".toByteArray()))))
        val zfs = ZipFileSystem()
        walk(zfs, FsPath.zipRoot(zip.path), 0)
        try { extract(zip) } catch (_: IOException) {}
        assertNothingEscaped("deep")
    }

    // ------------------------------------------------------------------ mutation fuzzing

    private fun walk(zfs: ZipFileSystem, dir: String, depth: Int, budget: IntArray = intArrayOf(5_000)) {
        if (depth > FsPath.MAX_DEPTH + 2 || budget[0]-- <= 0) return
        for (e in zfs.list(dir)) {
            assertTrue("hostile name listed: ${k.escape(e.name)}", FsPath.isSafeName(e.name))
            assertTrue(FsPath.isWithin(dir, e.path))
            zfs.stat(e.path)
            if (e.isDir) walk(zfs, e.path, depth + 1, budget)
            else zfs.openInput(e.path).use { s ->
                val b = ByteArray(16 * 1024); var total = 0L
                while (true) { val n = s.read(b); if (n < 0) break; total += n; if (total > (8 shl 20)) break }
            }
        }
    }

    private val seeds: List<ByteArray> by lazy {
        val normal = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("docs/")); z.closeEntry()
                z.putNextEntry(ZipEntry("docs/readme.txt")); z.write("hello wayfinder\n".repeat(50).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("img/p.png")); z.write(ByteArray(300) { it.toByte() }); z.closeEntry()
                z.putNextEntry(ZipEntry("empty")); z.closeEntry()
            }
        }.toByteArray()
        listOf(
            normal,
            rawZip(listOf(Raw("a.txt", "abc".toByteArray()), Raw("b/c.txt", "defdefdef".toByteArray(), deflate = true))),
            rawZip(listOf(Raw("../x", "1".toByteArray()), Raw("a", "2".toByteArray()), Raw("a/b", "3".toByteArray()))),
            rawZip(listOf(Raw("z.bin", ByteArray(20_000), deflate = true))),
        )
    }

    private fun mutateBytes(r: Random, src: ByteArray): ByteArray {
        var b = src.copyOf()
        repeat(1 + r.nextInt(6)) {
            if (b.isEmpty()) return@repeat
            when (r.nextInt(7)) {
                0 -> b[r.nextInt(b.size)] = r.nextInt(256).toByte()
                1 -> { val i = r.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl r.nextInt(8))).toByte() }
                2 -> b = b.copyOf(r.nextInt(b.size)) // truncate
                3 -> { // overwrite a 2/4-byte field with an interesting value
                    val v = listOf(0, -1, 0x7FFFFFFF, 0x80000000.toInt(), 0xFFFF, 1, 0x10000)[r.nextInt(7)]
                    val i = r.nextInt(b.size)
                    for (j in 0 until (if (r.nextBoolean()) 2 else 4)) if (i + j < b.size) b[i + j] = (v ushr (8 * j)).toByte()
                }
                4 -> { val i = r.nextInt(b.size); val n = r.nextInt(64); b = b.copyOfRange(0, i) + ByteArray(n) { r.nextInt(256).toByte() } + b.copyOfRange(i, b.size) }
                5 -> { val a = r.nextInt(b.size); val e = (a + r.nextInt(64)).coerceAtMost(b.size); b = b + b.copyOfRange(a, e) }
                else -> { val o = seeds[r.nextInt(seeds.size)]; val i = r.nextInt(b.size); b = b.copyOfRange(0, i) + o.copyOfRange(r.nextInt(o.size), o.size) }
            }
        }
        return b
    }

    private fun hex(b: ByteArray) = "${b.size} bytes: " + b.take(64).joinToString("") { "%02x".format(it) }

    @Test
    fun mutatedArchivesOnlyFailWithIOException() {
        val limits = ExtractLimits(maxEntries = 2_000, maxTotalBytes = 16L shl 20, maxRatio = 1_000, ratioFloorBytes = 4L shl 20)
        k.run(
            "zip.mutation", 10_000, { r -> mutateBytes(r, seeds[r.nextInt(seeds.size)]) }, seeds,
            budgetMs = 240_000, show = ::hex, allowed = { it is IOException },
        ) { bytes ->
            val zip = file(bytes, "m.zip")
            try {
                runCatching { ZipFileSystem.summary(zip) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                val zfs = ZipFileSystem()
                runCatching { walk(zfs, FsPath.zipRoot(zip.path), 0) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                runCatching { extract(zip, limits) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                assertNothingEscaped("mutation")
            } finally {
                FileInfo.deleteTree(dest); dest.mkdirs(); zip.delete()
            }
        }
    }

    @Test
    fun generatedEntryNamesOnlyFailWithIOException() {
        k.run(
            "zip.names", 5_000, { r -> List(1 + r.nextInt(4)) { k.input(r, 40) } }, k.corpus.chunked(4),
            budgetMs = 240_000, show = { l -> l.joinToString(" | ") { k.escape(it) } },
        ) { names ->
            val zip = file(rawZip(names.mapIndexed { i, n -> Raw(n, "v$i".toByteArray(), deflate = i % 2 == 0) }), "n.zip")
            try {
                runCatching { walk(ZipFileSystem(), FsPath.zipRoot(zip.path), 0) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                val out = try { extract(zip) } catch (_: IOException) { null }
                assertNothingEscaped("names")
                if (out != null) Files.walk(File(out).toPath()).use { s ->
                    s.forEach { p -> assertTrue(FsPath.isSafeName(p.toFile().name)) }
                }
            } finally {
                FileInfo.deleteTree(dest); dest.mkdirs(); zip.delete()
            }
        }
    }

    // ------------------------------------------------------------------ .tar and .tar.gz hostile & mutation fuzzing

    private fun buildTarBytes(gzip: Boolean, writer: (TarArchiveOutputStream) -> Unit): ByteArray {
        val raw = ByteArrayOutputStream()
        val stream = if (gzip) GZIPOutputStream(raw) else raw
        TarArchiveOutputStream(stream, "UTF-8").apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }.use(writer)
        return raw.toByteArray()
    }

    @Test
    fun hostileAndMutatedTarAndTarGzArchivesOnlyFailWithIOException() {
        // 1. Hand-written hostile .tar and .tar.gz archives (symlinks, hardlinks, device nodes, traversal names)
        for (gzip in listOf(false, true)) {
            val ext = if (gzip) ".tar.gz" else ".tar"
            for (name in hostileNames) {
                val bytes = buildTarBytes(gzip) { tos ->
                    val ok = TarArchiveEntry("ok.txt").apply { size = 4 }
                    tos.putArchiveEntry(ok); tos.write("fine".toByteArray()); tos.closeArchiveEntry()
                    val isDirName = name.endsWith("/")
                    val bad = TarArchiveEntry(name).apply { if (!isDirName) size = 5 }
                    tos.putArchiveEntry(bad)
                    if (!isDirName) tos.write("pwned".toByteArray())
                    tos.closeArchiveEntry()
                }
                val tar = file(bytes, "hostile-${counter++}$ext")
                try {
                    runCatching { walk(ZipFileSystem(), FsPath.zipRoot(tar.path), 0) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                    val out = try { extract(tar) } catch (_: IOException) { null }
                    assertNothingEscaped("tar(${k.escape(name)})")
                    if (out != null) assertTrue(FsPath.isWithin(dest.path, out))
                } finally {
                    FileInfo.deleteTree(dest); dest.mkdirs(); tar.delete()
                }
            }

            // Symlink, hardlink, character device, block device, FIFO must never be listed or extracted.
            val linkBytes = buildTarBytes(gzip) { tos ->
                val ok = TarArchiveEntry("safe/ok.txt").apply { size = 4 }
                tos.putArchiveEntry(ok); tos.write("good".toByteArray()); tos.closeArchiveEntry()
                val sym = TarArchiveEntry("safe/sym_escape", TarConstants.LF_SYMLINK).apply { linkName = "../../outside/sentinel.txt" }
                tos.putArchiveEntry(sym); tos.closeArchiveEntry()
                val hard = TarArchiveEntry("safe/hard_escape", TarConstants.LF_LINK).apply { linkName = "../outside/sentinel.txt" }
                tos.putArchiveEntry(hard); tos.closeArchiveEntry()
                val fifo = TarArchiveEntry("safe/fifo_node", TarConstants.LF_FIFO)
                tos.putArchiveEntry(fifo); tos.closeArchiveEntry()
            }
            val linkTar = file(linkBytes, "links-${counter++}$ext")
            try {
                val zfs = ZipFileSystem()
                val listed = zfs.list(FsPath.child(FsPath.zipRoot(linkTar.path), "safe"))
                assertEquals(listOf("ok.txt"), listed.map { it.name })
                val out = extract(linkTar)
                assertNothingEscaped("tar-links($ext)")
                assertEquals(listOf("ok.txt"), File(out, "safe").list()!!.toList())
            } finally {
                FileInfo.deleteTree(dest); dest.mkdirs(); linkTar.delete()
            }
        }

        // 2. Byte-level mutation fuzzing on .tar and .tar.gz seeds
        val tarSeedPlain = buildTarBytes(gzip = false) { tos ->
            tos.putArchiveEntry(TarArchiveEntry("docs/")); tos.closeArchiveEntry()
            val readme = "hello tar wayfinder\n".repeat(25).toByteArray()
            tos.putArchiveEntry(TarArchiveEntry("docs/readme.txt").apply { size = readme.size.toLong() })
            tos.write(readme); tos.closeArchiveEntry()
            val sym = TarArchiveEntry("docs/link", TarConstants.LF_SYMLINK).apply { linkName = "../outside/sentinel.txt" }
            tos.putArchiveEntry(sym); tos.closeArchiveEntry()
        }
        val tarSeedGz = buildTarBytes(gzip = true) { tos ->
            val payload = ByteArray(1024) { (it % 13).toByte() }
            tos.putArchiveEntry(TarArchiveEntry("nested/data.bin").apply { size = payload.size.toLong() })
            tos.write(payload); tos.closeArchiveEntry()
        }
        val tarSeeds = listOf(tarSeedPlain, tarSeedGz)
        val limits = ExtractLimits(maxEntries = 2_000, maxTotalBytes = 16L shl 20, maxRatio = 1_000, ratioFloorBytes = 4L shl 20)

        k.run(
            "tar.mutation", 5_000,
            { r ->
                val isGz = r.nextBoolean()
                val base = if (isGz) tarSeedGz else tarSeedPlain
                isGz to mutateBytes(r, base)
            },
            listOf(false to tarSeedPlain, true to tarSeedGz),
            budgetMs = 240_000,
            show = { (gz, b) -> (if (gz) "tgz " else "tar ") + hex(b) },
            allowed = { it is IOException },
        ) { (isGz, bytes) ->
            val ext = if (isGz) ".tar.gz" else ".tar"
            val tar = file(bytes, "m$ext")
            try {
                runCatching { ZipFileSystem.summary(tar) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                val zfs = ZipFileSystem()
                runCatching { walk(zfs, FsPath.zipRoot(tar.path), 0) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                runCatching { extract(tar, limits) }.exceptionOrNull()?.let { if (it !is IOException) throw it }
                assertNothingEscaped("tar.mutation")
            } finally {
                FileInfo.deleteTree(dest); dest.mkdirs(); tar.delete()
            }
        }
    }
}
