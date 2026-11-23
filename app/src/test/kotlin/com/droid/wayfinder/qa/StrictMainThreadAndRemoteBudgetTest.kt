// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.FileSystem
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.MediaInspector
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.theme.WayfinderTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class StrictMainThreadAndRemoteBudgetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
        StrictMainThreadProvider.reset()
    }

    @After
    fun tearDown() {
        MainActivity.hasAccessOverride = null
        StrictMainThreadProvider.reset()
        h?.close()
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    private fun awaitStreamsClosed(fs: CountingTrapFileSystem, timeoutMs: Long = 2_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (fs.activeOpenStreams.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertEquals("All remote streams must be closed promptly", 0, fs.activeOpenStreams.get())
    }

    /** Spy [FileSystem] recording any call invoked while `Looper.myLooper() == Looper.getMainLooper()`. */
    private class StrictOffMainThreadFileSystem(private val delegate: FileSystem) : FileSystem {
        val mainThreadViolations: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val backgroundCallCount = AtomicInteger(0)

        private fun checkThread(op: String, path: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val trace = Thread.currentThread().stackTrace.drop(2).take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                mainThreadViolations += "FileSystem.$op($path) on main thread: $trace"
            } else {
                backgroundCallCount.incrementAndGet()
            }
        }

        override fun list(path: String): List<FileEntry> {
            checkThread("list", path)
            return delegate.list(path)
        }

        override fun stat(path: String): FileEntry? {
            checkThread("stat", path)
            return delegate.stat(path)
        }

        override fun exists(path: String): Boolean {
            checkThread("exists", path)
            return delegate.exists(path)
        }

        override fun mkdir(path: String) {
            checkThread("mkdir", path)
            delegate.mkdir(path)
        }

        override fun createFile(path: String) {
            checkThread("createFile", path)
            delegate.createFile(path)
        }

        override fun delete(path: String) {
            checkThread("delete", path)
            delegate.delete(path)
        }

        override fun rename(path: String, newName: String): String {
            checkThread("rename", path)
            return delegate.rename(path, newName)
        }

        override fun move(src: String, dstPath: String): Boolean {
            checkThread("move", src)
            return delegate.move(src, dstPath)
        }

        override fun openInput(path: String): InputStream {
            checkThread("openInput", path)
            return delegate.openInput(path)
        }

        override fun openOutput(path: String): OutputStream {
            checkThread("openOutput", path)
            return delegate.openOutput(path)
        }
    }

    /** Spy [ContentProvider] recording any query / getType / descriptor open executed on the main thread. */
    class StrictMainThreadProvider : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.droid.wayfinder.strictio"
            val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val backgroundCalls = AtomicInteger(0)
            var backingFile: File? = null

            fun reset() {
                violations.clear()
                backgroundCalls.set(0)
                backingFile = null
            }

            private fun record(op: String, uri: Uri) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    val trace = Thread.currentThread().stackTrace.drop(2).take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    violations += "ContentProvider.$op($uri) on main thread: $trace"
                } else {
                    backgroundCalls.incrementAndGet()
                }
            }
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            record("query", uri)
            val f = backingFile
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA, OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                if (f != null) addRow(arrayOf(f.path, f.name, f.length()))
            }
        }

        override fun getType(uri: Uri): String {
            record("getType", uri)
            return "text/plain"
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            record("openFile", uri)
            val f = backingFile ?: return null
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
            signal: CancellationSignal?,
        ): AssetFileDescriptor? {
            record("openTypedAssetFile", uri)
            val pfd = openFile(uri, "r") ?: return null
            return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /**
     * Synthetic remote [FileSystem] serving virtual multi-hundred-megabyte files in memory while counting
     * exact bytes read, open stream handles, and failing fast if a single stream exceeds [perStreamHardCapBytes].
     */
    private class CountingTrapFileSystem(
        private val entriesByDir: Map<String, List<FileEntry>>,
        private val headerByPath: Map<String, ByteArray> = emptyMap(),
        private val perStreamHardCapBytes: Long = 32L * 1024L * 1024L,
        private val slowChunkDelayMs: Long = 0L,
    ) : FileSystem {
        private val allEntries: Map<String, FileEntry> = entriesByDir.values.flatten().associateBy { it.path }
        val openInputCalls = AtomicInteger(0)
        val activeOpenStreams = AtomicInteger(0)
        val totalBytesRead = AtomicLong(0L)
        val maxSingleStreamBytes = AtomicLong(0L)
        val mainThreadIoViolations: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun resetCounters() {
            openInputCalls.set(0)
            totalBytesRead.set(0L)
            maxSingleStreamBytes.set(0L)
            mainThreadIoViolations.clear()
        }

        private fun checkOffMain(op: String, path: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mainThreadIoViolations += "Remote.$op($path) on main thread"
            }
        }

        override fun list(path: String): List<FileEntry> {
            checkOffMain("list", path)
            return entriesByDir[path] ?: emptyList()
        }

        override fun stat(path: String): FileEntry? {
            checkOffMain("stat", path)
            return allEntries[path]
        }

        override fun mkdir(path: String) = throw IOException("Read-only trap FS")
        override fun createFile(path: String) = throw IOException("Read-only trap FS")
        override fun delete(path: String) = throw IOException("Read-only trap FS")
        override fun rename(path: String, newName: String): String = throw IOException("Read-only trap FS")
        override fun openOutput(path: String): OutputStream = throw IOException("Read-only trap FS")

        override fun openInput(path: String): InputStream {
            checkOffMain("openInput", path)
            val entry = allEntries[path] ?: throw IOException("Missing remote path: $path")
            val header = headerByPath[path] ?: ByteArray(0)
            openInputCalls.incrementAndGet()
            activeOpenStreams.incrementAndGet()
            return object : InputStream() {
                private var pos = 0L
                private var streamBytes = 0L
                private val closed = AtomicBoolean(false)

                override fun read(): Int {
                    val single = ByteArray(1)
                    val n = read(single, 0, 1)
                    return if (n < 0) -1 else (single[0].toInt() and 0xFF)
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    checkOffMain("read", path)
                    if (closed.get()) throw IOException("Stream already closed for $path")
                    if (len == 0) return 0
                    if (pos >= entry.size) return -1
                    if (slowChunkDelayMs > 0L) {
                        try {
                            Thread.sleep(slowChunkDelayMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw java.io.InterruptedIOException(ie.message)
                        }
                    }
                    val toRead = minOf(len.toLong(), entry.size - pos).toInt()
                    for (i in 0 until toRead) {
                        val absIdx = pos + i
                        b[off + i] = if (absIdx < header.size) header[absIdx.toInt()] else ((absIdx * 31L + 7L) and 0xFFL).toByte()
                    }
                    pos += toRead
                    streamBytes += toRead
                    totalBytesRead.addAndGet(toRead.toLong())
                    maxSingleStreamBytes.accumulateAndGet(streamBytes, ::maxOf)
                    if (streamBytes > perStreamHardCapBytes) {
                        throw AssertionError("Unbounded remote read on $path: read $streamBytes bytes (> cap $perStreamHardCapBytes)")
                    }
                    return toRead
                }

                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        activeOpenStreams.decrementAndGet()
                    }
                }
            }
        }
    }

    /** Builds a valid 44-byte PCM RIFF/WAVE header advertising a 500 MB 48 kHz 16-bit stereo stream. */
    private fun buildWavHeader(totalFileBytes: Long): ByteArray {
        val sampleRate = 48_000
        val channels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataBytes = (totalFileBytes - 44L).coerceIn(0L, 0x7FFF_0000L).toInt()
        val riffChunkSize = dataBytes + 36
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk length
            putShort(1) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    @Test
    fun uiFlowsNeverPerformFileSystemOrContentProviderIoOnMainThread() {
        Robolectric.setupContentProvider(StrictMainThreadProvider::class.java, StrictMainThreadProvider.AUTHORITY)
        val harness = VmHarness().also { h = it }
        val workDir = harness.dir("StrictIo")
        val docFile = harness.file("StrictIo/notes.txt", "off-main-thread verification payload")
        harness.file("StrictIo/second.txt", "second file")
        StrictMainThreadProvider.backingFile = docFile

        val strictFs = StrictOffMainThreadFileSystem(LocalFileSystem())
        harness.vm.repo.localOverride = strictFs

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)
        strictFs.mainThreadViolations.clear()
        StrictMainThreadProvider.violations.clear()

        // 1. Navigate & list directory
        harness.vm.navigate(workDir.path)
        idle(harness)

        // 2. Search across folder
        harness.vm.setQuery("notes")
        idle(harness)
        harness.vm.setQuery("")
        idle(harness)

        // 3. Create new item
        harness.vm.createItem("created.txt", folder = false)
        idle(harness)

        // 4. Open file & Share file
        harness.select("StrictIo/notes.txt")
        harness.vm.openSelection()
        idle(harness)
        harness.vm.share()
        idle(harness)

        // 5. Properties (checksums + lstat + space)
        harness.vm.showProperties()
        harness.until(what = "checksums computed") { (harness.s.dialog as? com.droid.wayfinder.ui.Dialog.Properties)?.checksums != null }
        idle(harness)
        harness.vm.dismissDialog()
        idle(harness)

        // 6. QuickLook preview
        harness.vm.toggleQuickLook()
        idle(harness)
        harness.vm.closeQuickLook()
        idle(harness)

        // 7. Move to Trash & Undo
        harness.select("StrictIo/created.txt")
        harness.vm.requestDelete(permanent = false)
        idle(harness)
        harness.vm.undo()
        idle(harness)

        // 8. Content URI import off main thread
        val importUri = Uri.parse("content://${StrictMainThreadProvider.AUTHORITY}/docs/notes.txt")
        harness.vm.importUris(listOf(importUri), workDir.path)
        idle(harness)

        assertTrue("Expected real background FileSystem calls to be observed", strictFs.backgroundCallCount.get() > 0)
        assertTrue("Expected real background ContentProvider calls to be observed", StrictMainThreadProvider.backgroundCalls.get() > 0)
        assertTrue(
            "Main-thread FileSystem I/O violations:\n" + strictFs.mainThreadViolations.joinToString("\n"),
            strictFs.mainThreadViolations.isEmpty(),
        )
        assertTrue(
            "Main-thread ContentProvider I/O violations:\n" + StrictMainThreadProvider.violations.joinToString("\n"),
            StrictMainThreadProvider.violations.isEmpty(),
        )
    }

    @Test
    fun remoteFiveHundredMegabyteStreamsRespectByteBudgetsAndAlwaysClose() {
        val fiveHundredMb = 500L * 1024L * 1024L
        val sixtyMb = 60L * 1024L * 1024L
        val remoteDir = "smb://nas.test/media"
        val hugeVideo = FileEntry(
            path = "$remoteDir/huge_capture.mp4",
            name = "huge_capture.mp4",
            isDir = false,
            size = fiveHundredMb,
            modified = 1_700_000_000_000L,
        )
        val hugeWav = FileEntry(
            path = "$remoteDir/studio_master.wav",
            name = "studio_master.wav",
            isDir = false,
            size = fiveHundredMb,
            modified = 1_700_000_000_000L,
        )
        val oversizedImage = FileEntry(
            path = "$remoteDir/panorama.png",
            name = "panorama.png",
            isDir = false,
            size = sixtyMb,
            modified = 1_700_000_000_000L,
        )
        val hugeArchive = FileEntry(
            path = "$remoteDir/backup.zip",
            name = "backup.zip",
            isDir = false,
            size = fiveHundredMb,
            modified = 1_700_000_000_000L,
        )
        val corruptAudio = FileEntry(
            path = "$remoteDir/corrupt.mp3",
            name = "corrupt.mp3",
            isDir = false,
            size = fiveHundredMb,
            modified = 1_700_000_000_000L,
        )

        val trapFs = CountingTrapFileSystem(
            entriesByDir = mapOf(remoteDir to listOf(hugeVideo, hugeWav, oversizedImage, hugeArchive, corruptAudio)),
            headerByPath = mapOf(hugeWav.path to buildWavHeader(fiveHundredMb)),
            perStreamHardCapBytes = BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES + 1024L * 1024L,
        )

        val harness = VmHarness().also { h = it }
        harness.vm.repo.schemeOverrides["smb"] = trapFs

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        // 1. Navigating / browsing a folder with 500 MB files must read 0 file-content bytes
        harness.vm.navigate(remoteDir)
        idle(harness)
        assertEquals(5, harness.s.active.entries.size)
        assertEquals("Folder listing must never open file streams", 0, trapFs.openInputCalls.get())
        assertEquals(0L, trapFs.totalBytesRead.get())

        // 2. Oversized remote image (60 MB > REMOTE_THUMB_MAX_IMAGE_BYTES) is skipped with 0 bytes read
        assertFalse(harness.vm.canThumbnail(oversizedImage))
        assertNull(runBlocking { harness.vm.thumbnailSource(oversizedImage) })
        assertEquals(0, trapFs.openInputCalls.get())

        // 3. 500 MB remote video thumbnail fetches at most REMOTE_THUMB_VIDEO_PREFIX_BYTES and closes stream
        val stagedVideo = runBlocking { harness.vm.thumbnailSource(hugeVideo) }
        assertNotNull(stagedVideo)
        assertEquals(BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES, stagedVideo!!.length())
        assertEquals(BrowserViewModel.REMOTE_THUMB_VIDEO_PREFIX_BYTES, trapFs.totalBytesRead.get())
        assertEquals("All thumbnail streams must be closed", 0, trapFs.activeOpenStreams.get())
        val partFiles = File(harness.app.cacheDir, BrowserViewModel.THUMBS_DIR).listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue("No temporary .part files may remain", partFiles.isEmpty())

        // Second request hits disk cache with 0 additional network reads
        trapFs.resetCounters()
        assertEquals(stagedVideo, runBlocking { harness.vm.thumbnailSource(hugeVideo) })
        assertEquals("Cached thumbnail must not re-open remote stream", 0, trapFs.openInputCalls.get())

        // 4. QuickLook on 500 MB remote non-media archive shows placeholder with 0 network bytes read
        trapFs.resetCounters()
        harness.vm.setSelection(setOf(hugeArchive.path))
        harness.vm.toggleQuickLook()
        idle(harness)
        assertEquals("QuickLook on remote non-media must not hash or stream 500 MB file", 0L, trapFs.totalBytesRead.get())
        assertEquals(0, trapFs.activeOpenStreams.get())
        harness.vm.closeQuickLook()
        idle(harness)

        // 5. MediaInspector.inspectStream on 500 MB WAV reads < 1 MB and closes every stream
        trapFs.resetCounters()
        val wavInfo = runBlocking(Dispatchers.IO) {
            MediaInspector.inspectStream(
                context = harness.app,
                fileName = hugeWav.name,
                fileSizeBytes = hugeWav.size,
                openInput = { trapFs.openInput(hugeWav.path) },
            )
        }
        assertNotNull("Expected WAV MediaInfo from 500 MB remote header", wavInfo)
        assertEquals("WAV", wavInfo!!.container)
        assertEquals(1, wavInfo.audioTracks.size)
        assertEquals(48_000, wavInfo.audioTracks.single().sampleRateHz)
        assertEquals(2, wavInfo.audioTracks.single().channelCount)
        assertTrue(
            "WAV header inspection must read < 1 MB out of 500 MB, got ${trapFs.totalBytesRead.get()}",
            trapFs.totalBytesRead.get() in 44L..(1024L * 1024L),
        )
        awaitStreamsClosed(trapFs)

        // 6. Unparseable 500 MB stream + mid-flight cancellation respects per-stream cap and closes streams
        val customCap = 2L * 1024L * 1024L
        trapFs.resetCounters()
        val corruptResult = runBlocking(Dispatchers.IO) {
            runCatching {
                MediaInspector.inspectStream(
                    context = harness.app,
                    fileName = corruptAudio.name,
                    fileSizeBytes = corruptAudio.size,
                    openInput = { trapFs.openInput(corruptAudio.path) },
                    maxBytes = customCap,
                )
            }.getOrNull()
        }
        assertNull(corruptResult)
        assertTrue(
            "Single stream read (${trapFs.maxSingleStreamBytes.get()}) must not exceed custom cap ($customCap)",
            trapFs.maxSingleStreamBytes.get() <= customCap,
        )
        awaitStreamsClosed(trapFs)

        val slowTrapFs = CountingTrapFileSystem(
            entriesByDir = mapOf(remoteDir to listOf(corruptAudio)),
            perStreamHardCapBytes = customCap,
            slowChunkDelayMs = 15L,
        )
        runBlocking {
            val job = launch(Dispatchers.IO) {
                try {
                    MediaInspector.inspectStream(
                        context = harness.app,
                        fileName = corruptAudio.name,
                        fileSizeBytes = corruptAudio.size,
                        openInput = { slowTrapFs.openInput(corruptAudio.path) },
                        maxBytes = customCap,
                    )
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            val openDeadline = System.currentTimeMillis() + 500L
            while (slowTrapFs.openInputCalls.get() == 0 && System.currentTimeMillis() < openDeadline) {
                delay(5L)
            }
            delay(25L)
            job.cancelAndJoin()
        }
        awaitStreamsClosed(slowTrapFs)
        assertTrue(
            "Remote operations must never run on main thread:\n" + trapFs.mainThreadIoViolations.joinToString("\n"),
            trapFs.mainThreadIoViolations.isEmpty(),
        )
    }

    @Test
    fun localLargeTextQuickLookTruncatesAtPreviewCapWithoutFullHeapLoad() {
        val harness = VmHarness().also { h = it }
        val bigLog = File(harness.root, "Logs/huge.log").apply {
            parentFile?.mkdirs()
            outputStream().buffered().use { out ->
                val line = "2026-11-23T00:00:00Z INFO Wayfinder diagnostic log line padding0123456789\n".toByteArray()
                repeat(20_000) { out.write(line) } // ~1.48 MB (> 256 KB TEXT_PREVIEW_BYTES)
            }
        }
        harness.navigate(bigLog.parentFile!!.path)

        rule.setContent {
            WayfinderTheme(false) {
                AppRoot(harness.vm)
            }
        }
        idle(harness)

        harness.vm.setSelection(setOf(bigLog.path))
        harness.vm.toggleQuickLook()
        idle(harness)
        harness.until(what = "truncated preview rendered") {
            rule.onAllNodes(
                SemanticsMatcher("truncatedMarker") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.any { it.text.contains("preview truncated") } == true
                },
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
