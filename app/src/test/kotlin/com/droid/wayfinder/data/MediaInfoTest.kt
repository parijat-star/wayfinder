// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class MediaInfoTest {

    @Test
    fun formatDurationHandlesShortMinutesAndHours() {
        assertEquals("", MediaFormatters.formatDuration(-1L))
        assertEquals("0:00", MediaFormatters.formatDuration(0L))
        assertEquals("0:04.5", MediaFormatters.formatDuration(4_500_000L))
        assertEquals("0:09", MediaFormatters.formatDuration(9_000_000L))
        assertEquals("0:42", MediaFormatters.formatDuration(42_000_000L))
        assertEquals("3:05", MediaFormatters.formatDuration(185_000_000L))
        assertEquals("1:23:45", MediaFormatters.formatDuration((3600L + 23L * 60L + 45L) * 1_000_000L))
    }

    @Test
    fun formatBitrateFormatsBpsKbpsAndMbps() {
        assertEquals("512 bps", MediaFormatters.formatBitrate(512L))
        assertEquals("192 kbps", MediaFormatters.formatBitrate(192_000L))
        assertEquals("320 kbps", MediaFormatters.formatBitrate(320_000L))
        assertEquals("4.25 Mbps", MediaFormatters.formatBitrate(4_250_000L))
        assertEquals("8.5 Mbps", MediaFormatters.formatBitrate(8_500_000L))
        assertEquals("12 Mbps", MediaFormatters.formatBitrate(12_000_000L))
    }

    @Test
    fun formatFrameRateFormatsIntegerAndFractionalRates() {
        assertEquals("24 fps", MediaFormatters.formatFrameRate(24f))
        assertEquals("30 fps", MediaFormatters.formatFrameRate(29.999f))
        assertEquals("60 fps", MediaFormatters.formatFrameRate(60f))
        assertEquals("23.98 fps", MediaFormatters.formatFrameRate(23.976f))
        assertEquals("29.97 fps", MediaFormatters.formatFrameRate(29.97f))
    }

    @Test
    fun formatResolutionIncludesAspectRatioAndRotation() {
        assertEquals("1920 × 1080 (16:9)", MediaFormatters.formatResolution(1920, 1080))
        assertEquals("3840 × 2160 (16:9)", MediaFormatters.formatResolution(3840, 2160))
        assertEquals("1080 × 1920 (9:16)", MediaFormatters.formatResolution(1080, 1920))
        assertEquals("1280 × 960 (4:3)", MediaFormatters.formatResolution(1280, 960))
        assertEquals("1920 × 1080 (16:9) rotated 90°", MediaFormatters.formatResolution(1920, 1080, rotationDegrees = 90))
        assertEquals("2560 × 1080 (2.37:1)", MediaFormatters.formatResolution(2560, 1080))
    }

    @Test
    fun formatChannelsAndSampleRate() {
        assertEquals("1 (Mono)", MediaFormatters.formatChannels(1))
        assertEquals("2 (Stereo)", MediaFormatters.formatChannels(2))
        assertEquals("6 (5.1)", MediaFormatters.formatChannels(6))
        assertEquals("8 (7.1)", MediaFormatters.formatChannels(8))
        assertEquals("4 ch", MediaFormatters.formatChannels(4))

        assertEquals("48 kHz", MediaFormatters.formatSampleRate(48_000))
        assertEquals("44.1 kHz", MediaFormatters.formatSampleRate(44_100))
        assertEquals("96 kHz", MediaFormatters.formatSampleRate(96_000))
        assertEquals("800 Hz", MediaFormatters.formatSampleRate(800))
    }

    @Test
    fun friendlyCodecAndContainerNames() {
        assertEquals("H.264 / AVC", MediaFormatters.friendlyCodecName(MimeTypes.VIDEO_H264))
        assertEquals("H.265 / HEVC", MediaFormatters.friendlyCodecName(MimeTypes.VIDEO_H265))
        assertEquals("AV1", MediaFormatters.friendlyCodecName(MimeTypes.VIDEO_AV1))
        assertEquals("VP9", MediaFormatters.friendlyCodecName(MimeTypes.VIDEO_VP9))
        assertEquals("AAC", MediaFormatters.friendlyCodecName(MimeTypes.AUDIO_AAC))
        assertEquals("Opus", MediaFormatters.friendlyCodecName(MimeTypes.AUDIO_OPUS))
        assertEquals("FLAC", MediaFormatters.friendlyCodecName(MimeTypes.AUDIO_FLAC))
        assertEquals("SubRip (SRT)", MediaFormatters.friendlyCodecName(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("H.264 / AVC (avc1.640028)", MediaFormatters.formatCodec("H.264 / AVC", "avc1.640028"))

        assertEquals("MP4", MediaFormatters.friendlyContainerName(MimeTypes.VIDEO_MP4))
        assertEquals("Matroska (MKV)", MediaFormatters.friendlyContainerName(MimeTypes.VIDEO_MATROSKA))
        assertEquals("WebM", MediaFormatters.friendlyContainerName(null, "webm"))
        assertEquals("FLAC", MediaFormatters.friendlyContainerName(null, "flac"))
        assertNull(MediaFormatters.friendlyLanguage("und"))
        assertNotNull(MediaFormatters.friendlyLanguage("en"))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun extractFromTrackGroupsParsesVideoAudioSubtitlesColorAndTags() {
        val colorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setLumaBitdepth(10)
            .build()

        val videoFormat = Format.Builder()
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L120.90")
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(23.976f)
            .setAverageBitrate(12_000_000)
            .setColorInfo(colorInfo)
            .setMetadata(
                Metadata(
                    MdtaMetadataEntry(
                        "com.apple.quicktime.software",
                        "Lavf61.1.100".toByteArray(Charsets.UTF_8),
                        MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                    ),
                ),
            )
            .build()

        val audioEn = Format.Builder()
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .setAverageBitrate(640_000)
            .setLanguage("en")
            .build()

        val audioJa = Format.Builder()
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setSampleMimeType(MimeTypes.AUDIO_OPUS)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .setAverageBitrate(192_000)
            .setLanguage("ja")
            .build()

        val subtitle = Format.Builder()
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en")
            .build()

        val groups = TrackGroupArray(
            TrackGroup("v", videoFormat),
            TrackGroup("a1", audioEn),
            TrackGroup("a2", audioJa),
            TrackGroup("s1", subtitle),
        )

        val durationUs = 120_000_000L // 2 minutes
        val fileSizeBytes = 200_000_000L // ~13.33 Mbps overall
        val info = MediaInspector.extractFromTrackGroups(groups, durationUs, fileSizeBytes, "mkv")

        assertFalse(info.isEmpty)
        assertEquals("Matroska (MKV)", info.container)
        assertEquals(durationUs, info.durationUs)
        assertNotNull(info.overallBitrateBps)
        assertTrue(info.overallBitrateBps!! in 13_300_000L..13_400_000L)

        assertEquals(1, info.videoTracks.size)
        val v = info.videoTracks[0]
        assertEquals("H.265 / HEVC", v.codec)
        assertEquals("hvc1.1.6.L120.90", v.codecProfile)
        assertEquals(3840, v.width)
        assertEquals(2160, v.height)
        assertEquals(10, v.bitDepth)
        assertEquals("BT.2020", v.colorSpace)
        assertEquals("HDR10 (PQ)", v.colorTransfer)
        assertEquals("Limited", v.colorRange)
        assertEquals("10-bit · BT.2020 · HDR10 (PQ) · Limited", MediaFormatters.formatColorSummary(v))

        assertEquals(2, info.audioTracks.size)
        assertEquals("Dolby Digital Plus (E-AC-3)", info.audioTracks[0].codec)
        assertEquals(6, info.audioTracks[0].channelCount)
        assertEquals("Opus", info.audioTracks[1].codec)
        assertEquals(2, info.audioTracks[1].channelCount)

        assertEquals(1, info.subtitleTracks.size)
        assertEquals("SubRip (SRT)", info.subtitleTracks[0].codec)

        assertEquals("Lavf61.1.100", info.tags?.encoder)

        val summary = MediaFormatters.formatSummary(info)
        assertTrue(summary.contains("Matroska (MKV)"))
        assertTrue(summary.contains("H.265 / HEVC (hvc1.1.6.L120.90)"))
        assertTrue(summary.contains("3840 × 2160 (16:9)"))
        assertTrue(summary.contains("Audio #1"))
        assertTrue(summary.contains("Audio #2"))
        assertTrue(summary.contains("SubRip (SRT)"))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun extractFromTrackGroupsParsesAudioTagsFromId3AndVorbis() {
        val flac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setChannelCount(2)
            .setSampleRate(96_000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setMetadata(
                Metadata(
                    VorbisComment("TITLE", "Midnight City"),
                    VorbisComment("ARTIST", "M83"),
                    VorbisComment("ALBUM", "Hurry Up, We're Dreaming"),
                    VorbisComment("ENCODER", "libFLAC 1.4.3"),
                ),
            )
            .build()

        val info = MediaInspector.extractFromTrackGroups(
            TrackGroupArray(TrackGroup("a", flac)),
            durationUs = 243_000_000L,
            fileSizeBytes = 0L,
            fileExtension = "flac",
        )
        assertEquals("FLAC", info.container)
        assertEquals(24, info.audioTracks.single().bitDepth)
        assertEquals("Midnight City", info.tags?.title)
        assertEquals("M83", info.tags?.artist)
        assertEquals("Hurry Up, We're Dreaming", info.tags?.album)
        assertEquals("libFLAC 1.4.3", info.tags?.encoder)

        val mp3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
            .setChannelCount(2)
            .setSampleRate(44_100)
            .setAverageBitrate(320_000)
            .setMetadata(
                Metadata(
                    TextInformationFrame("TIT2", null, listOf("Track One")),
                    TextInformationFrame("TPE1", null, listOf("Artist Name")),
                    TextInformationFrame("TSSE", null, listOf("LAME 3.100")),
                ),
            )
            .build()
        val mp3Info = MediaInspector.extractFromTrackGroups(
            TrackGroupArray(TrackGroup("a", mp3)),
            durationUs = 180_000_000L,
            fileSizeBytes = 0L,
            fileExtension = "mp3",
        )
        assertEquals("Track One", mp3Info.tags?.title)
        assertEquals("Artist Name", mp3Info.tags?.artist)
        assertEquals("LAME 3.100", mp3Info.tags?.encoder)
        assertEquals(320_000L, mp3Info.overallBitrateBps)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun extractFromEmptyTrackGroupsReturnsEmptyMediaInfo() {
        val info = MediaInspector.extractFromTrackGroups(TrackGroupArray(), C.TIME_UNSET, 0L, null)
        assertTrue(info.isEmpty)
        assertNull(info.durationUs)
        assertNull(info.overallBitrateBps)
        assertNull(info.tags)
    }

    @Test
    fun isLocalSeekableContentUriAllowsOnlyLocalStorageAuthoritiesAndRejectsCloudOrPhone() {
        assertTrue(MediaInspector.isLocalSeekableContentUri(Uri.parse("file:///storage/emulated/0/Movies/clip.mp4")))
        assertTrue(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fclip.mp4"),
            ),
        )
        assertTrue(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://com.android.providers.downloads.documents/document/123"),
            ),
        )
        assertTrue(MediaInspector.isLocalSeekableContentUri(Uri.parse("content://media/external/video/media/42")))

        // Cloud, Cross-Device Phone, MTP, and unknown third-party SAF providers must use bounded stream inspection.
        assertFalse(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dbig4k"),
            ),
        )
        assertFalse(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://com.google.ambient.streaming.multidevicesync.quickaccess/document/phone//DCIM/4k.mp4"),
            ),
        )
        assertFalse(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://com.android.mtp.documents/document/77"),
            ),
        )
        assertFalse(
            MediaInspector.isLocalSeekableContentUri(
                Uri.parse("content://org.nextcloud.documents/document/video.mkv"),
            ),
        )
        assertFalse(MediaInspector.isLocalSeekableContentUri(null))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun boundedStreamDataSourceReusesSingleStreamSupportsBackwardSeeksAndEnforcesTotalCap() {
        val totalSize = 6 * 1024 * 1024 // 6 MB file (< 8 MB cap)
        val payload = ByteArray(totalSize) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val openCount = AtomicInteger(0)
        val underlyingBytesRead = AtomicLong(0L)
        val streamClosed = AtomicBoolean(false)

        val maxBytes = 8L * 1024L * 1024L
        val shared = SharedPrefixStreamBuffer(
            openInput = {
                openCount.incrementAndGet()
                object : InputStream() {
                    private val delegate = ByteArrayInputStream(payload)
                    override fun read(): Int = delegate.read().also { if (it >= 0) underlyingBytesRead.incrementAndGet() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        delegate.read(b, off, len).also { if (it > 0) underlyingBytesRead.addAndGet(it.toLong()) }
                    override fun close() {
                        streamClosed.set(true)
                        delegate.close()
                    }
                }
            },
            totalSize = totalSize.toLong(),
            maxBytes = maxBytes,
        )

        val uri = Uri.parse("wayfinder://inspect/sample.mp4")
        val ds = BoundedStreamDataSource(shared)

        // 1. Forward seek near end of 6 MB file (e.g. trailing MP4 moov atom at 5.5 MB).
        val moovOffset = (5.5 * 1024 * 1024).toLong()
        ds.open(DataSpec.Builder().setUri(uri).setPosition(moovOffset).setLength(4096L).build())
        val moovBuf = ByteArray(4096)
        var off = 0
        while (off < moovBuf.size) {
            val n = ds.read(moovBuf, off, moovBuf.size - off)
            assertTrue("Expected bytes at moov offset", n > 0)
            off += n
        }
        assertArrayEquals(payload.copyOfRange(moovOffset.toInt(), moovOffset.toInt() + 4096), moovBuf)
        ds.close()

        // 2. Backward seek to 3.0 MB (mdat header) must succeed from cache without re-opening or exceeding 8 MB!
        val mdatOffset = 3L * 1024L * 1024L
        val downloadsBeforeBackSeek = underlyingBytesRead.get()
        ds.open(DataSpec.Builder().setUri(uri).setPosition(mdatOffset).setLength(4096L).build())
        val mdatBuf = ByteArray(4096)
        off = 0
        while (off < mdatBuf.size) {
            val n = ds.read(mdatBuf, off, mdatBuf.size - off)
            assertTrue("Expected cached bytes on backward seek", n > 0)
            off += n
        }
        assertArrayEquals(payload.copyOfRange(mdatOffset.toInt(), mdatOffset.toInt() + 4096), mdatBuf)
        assertEquals("Backward seek must be served from prefix cache with 0 extra network bytes", downloadsBeforeBackSeek, underlyingBytesRead.get())
        assertEquals("Underlying stream must only be opened once across seeks", 1, openCount.get())
        ds.close()

        shared.closeUnderlying()
        assertTrue("closeUnderlying must close live InputStream", streamClosed.get())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun boundedStreamDataSourceNeverDownloadsMoreThanMaxBytesAcrossRepeatedNearLimitSeeks() {
        val maxBytes = 1024L * 1024L // 1 MB cap for test speed
        val openCount = AtomicInteger(0)
        val underlyingBytesRead = AtomicLong(0L)

        val shared = SharedPrefixStreamBuffer(
            openInput = {
                openCount.incrementAndGet()
                object : InputStream() {
                    private var remaining = 50L * 1024L * 1024L // 50 MB remote file
                    override fun read(): Int {
                        if (remaining <= 0L) return -1
                        remaining--
                        underlyingBytesRead.incrementAndGet()
                        return 0x42
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0L) return -1
                        val n = minOf(len.toLong(), remaining).toInt()
                        b.fill(0x42, off, off + n)
                        remaining -= n
                        underlyingBytesRead.addAndGet(n.toLong())
                        return n
                    }
                }
            },
            totalSize = 50L * 1024L * 1024L,
            maxBytes = maxBytes,
        )

        val uri = Uri.parse("wayfinder://inspect/huge.mp4")
        val ds = BoundedStreamDataSource(shared)
        val seekPos = 900L * 1024L // 900 KB (< 1 MB cap)
        val scratch = ByteArray(16 * 1024)

        // Simulate 10 repeated extractor seeks near the cap; files > maxBytes report C.LENGTH_UNSET so CBR/WAV durations are never truncated.
        repeat(10) {
            val reportedLen = ds.open(DataSpec.Builder().setUri(uri).setPosition(seekPos).build())
            assertEquals(C.LENGTH_UNSET.toLong(), reportedLen)
            while (ds.read(scratch, 0, scratch.size) != C.RESULT_END_OF_INPUT) {
                // drain up to cap
            }
            ds.close()
        }

        assertEquals("Must open underlying remote stream only once", 1, openCount.get())
        assertEquals("Total remote bytes transferred must never exceed maxBytes", maxBytes, underlyingBytesRead.get())

        // Seeking at or beyond maxBytes fails fast with non-retryable DataSourceException(ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) and 0 extra reads.
        try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(maxBytes).build())
            fail("Expected DataSourceException when seeking past maxBytes")
        } catch (e: androidx.media3.datasource.DataSourceException) {
            assertEquals(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                e.reason,
            )
        }
        assertEquals(maxBytes, underlyingBytesRead.get())
        shared.closeUnderlying()
    }

    @Test
    fun sharedPrefixStreamBufferClosesStreamAndRemembersStickyFailureOnMidStreamIoError() {
        val openCount = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        val shared = SharedPrefixStreamBuffer(
            openInput = {
                openCount.incrementAndGet()
                object : InputStream() {
                    private var emitted = 0
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (emitted == 0) {
                            val n = minOf(len, 1024)
                            b.fill(0x11, off, off + n)
                            emitted += n
                            return n
                        }
                        throw IOException("Simulated socket reset")
                    }
                    override fun close() {
                        closed.set(true)
                    }
                }
            },
            totalSize = 1024L * 1024L,
            maxBytes = 1024L * 1024L,
        )

        val buf = ByteArray(4096)
        // First read of 512 bytes succeeds from the initial 1024-byte read.
        assertEquals(512, shared.readAt(0L, buf, 0, 512))

        // Reading past byte 1024 hits the mid-stream IOException, closes the stream, and never re-opens at byte 0.
        try {
            shared.readAt(1024L, buf, 0, 512)
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }
        assertTrue("Broken stream must be closed immediately", closed.get())
        try {
            shared.readAt(2048L, buf, 0, 512)
            fail("Expected sticky IOException without re-opening stream")
        } catch (_: IOException) {
            // expected
        }
        assertEquals("Must not re-open stream from byte 0 when bufferedBytes > 0", 1, openCount.get())
    }

    @Test
    fun listenableFutureAwaitPropagatesCancellationCleanly() = runBlocking {
        val cancelledFuture = SettableFuture.create<String>()
        cancelledFuture.cancel(true)
        try {
            cancelledFuture.await()
            fail("Expected CancellationException for cancelled ListenableFuture")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
