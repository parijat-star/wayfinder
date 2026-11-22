// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
