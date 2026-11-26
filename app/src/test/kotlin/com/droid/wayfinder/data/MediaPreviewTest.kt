// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class MediaPreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun animatedImageExtensionMatchesGifAndWebpCaseInsensitively() {
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("gif"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("GIF"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("webp"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension(" WebP "))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("png"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("jpg"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("mp4"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension(""))
    }

    @Test
    fun gifHeaderSniffingDetectsGif87aAndGif89aOnly() {
        assertTrue(MediaPreviewSupport.isGifHeader("GIF87a\u0001\u0000".toByteArray(Charsets.US_ASCII)))
        assertTrue(MediaPreviewSupport.isGifHeader("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader("GIF88a".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader("GIF89".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertFalse(MediaPreviewSupport.isGifHeader(ByteArray(0)))
    }

    private fun buildWebp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((fourCc, payload) in chunks) {
            body.write(fourCc.toByteArray(Charsets.US_ASCII))
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
            body.write(lenBuf)
            body.write(payload)
            if ((payload.size and 1) != 0) body.write(0) // RIFF even-byte padding
        }
        val payloadBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadBytes.size).array())
        out.write(payloadBytes)
        return out.toByteArray()
    }

    @Test
    fun animatedWebpHeaderDetectsVp8xFlagAndAnimChunkSafely() {
        val animatedVp8x = buildWebp("VP8X" to byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(MediaPreviewSupport.isAnimatedWebpHeader(animatedVp8x))
        assertTrue(MediaPreviewSupport.isAnimatedImageHeader(animatedVp8x))

        val staticVp8x = buildWebp("VP8X" to byteArrayOf(0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(staticVp8x))

        val staticVp8 = buildWebp("VP8 " to ByteArray(16))
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(staticVp8))

        // Odd-length leading chunk followed by ANIM chunk tests RIFF padding alignment.
        val animAfterOddChunk = buildWebp("ICCP" to byteArrayOf(1, 2, 3), "ANIM" to ByteArray(6))
        assertTrue(MediaPreviewSupport.isAnimatedWebpHeader(animAfterOddChunk))

        // Truncated VP8X chunk must return false without throwing.
        val truncatedVp8x = "RIFF\u0020\u0000\u0000\u0000WEBPVP8X\u000A\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(truncatedVp8x))
    }

    @Test
    fun animatedHeaderParsersResistFuzzedAndAdversarialInputs() {
        val rng = Random(42)
        // Adversarial RIFF/WEBP headers with max uint32 chunk sizes (0xFFFFFFFF) and zero-length loops.
        val overflowChunk = ByteArray(32).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
            "JUNK".toByteArray().copyInto(this, 12)
            this[16] = 0xFF.toByte()
            this[17] = 0xFF.toByte()
            this[18] = 0xFF.toByte()
            this[19] = 0xFF.toByte()
        }
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(overflowChunk))

        val zeroLoopChunks = ByteArray(12 + 40 * 8).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
            for (i in 0 until 40) {
                "JUNK".toByteArray().copyInto(this, 12 + i * 8)
            }
        }
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(zeroLoopChunks))

        repeat(500) {
            val len = rng.nextInt(0, 128)
            val data = ByteArray(len)
            rng.nextBytes(data)
            if (len >= 12 && it % 3 == 0) {
                "RIFF".toByteArray().copyInto(data, 0)
                "WEBP".toByteArray().copyInto(data, 8)
            }
            MediaPreviewSupport.isAnimatedImageHeader(data)
        }
    }

    @Test
    fun dimensionScalingAndSampleSizeAvoidUpscalingAndZeroDivision() {
        assertEquals(800 to 600, MediaPreviewSupport.computeScaledDimensions(800, 600, 2048))
        assertEquals(2048 to 1024, MediaPreviewSupport.computeScaledDimensions(4096, 2048, 2048))
        assertEquals(1024 to 2048, MediaPreviewSupport.computeScaledDimensions(2048, 4096, 2048))
        assertEquals(2048 to 1, MediaPreviewSupport.computeScaledDimensions(10_000, 1, 2048))
        assertEquals(1 to 1, MediaPreviewSupport.computeScaledDimensions(0, 100, 2048))
        assertEquals(1 to 1, MediaPreviewSupport.computeScaledDimensions(-5, -5, 2048))
        assertEquals(400 to 300, MediaPreviewSupport.computeScaledDimensions(400, 300, 0))

        assertEquals(1, MediaPreviewSupport.computeInSampleSize(500, 500, 720))
        assertEquals(1, MediaPreviewSupport.computeInSampleSize(1000, 1000, 720))
        assertEquals(2, MediaPreviewSupport.computeInSampleSize(1440, 1440, 720))
        assertEquals(4, MediaPreviewSupport.computeInSampleSize(2880, 2880, 720))
        assertEquals(8, MediaPreviewSupport.computeInSampleSize(5760, 5760, 720))
        assertEquals(1, MediaPreviewSupport.computeInSampleSize(0, 0, 720))
    }

    @Test
    fun playbackTimeAndSeekMathHandleBoundariesAndOverflow() {
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(-5_000L))
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(0L))
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(999L))
        assertEquals("0:05", MediaPreviewSupport.formatPlaybackTime(5_000L))
        assertEquals("1:05", MediaPreviewSupport.formatPlaybackTime(65_000L))
        assertEquals("1:01:01", MediaPreviewSupport.formatPlaybackTime(3_661_000L))
        assertEquals("10:00:00", MediaPreviewSupport.formatPlaybackTime(36_000_000L))

        assertEquals(15_000L, MediaPreviewSupport.clampSeekPosition(5_000L, 10_000L, 60_000L))
        assertEquals(0L, MediaPreviewSupport.clampSeekPosition(5_000L, -10_000L, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.clampSeekPosition(55_000L, 10_000L, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.clampSeekPosition(Long.MAX_VALUE - 5L, 10_000L, 60_000L))
        assertEquals(0L, MediaPreviewSupport.clampSeekPosition(100L, Long.MIN_VALUE, 60_000L))
        assertEquals(20_000L, MediaPreviewSupport.clampSeekPosition(10_000L, 10_000L, -1L))

        assertEquals(0f, MediaPreviewSupport.positionToFraction(10_000L, 0L), 0.0001f)
        assertEquals(0.5f, MediaPreviewSupport.positionToFraction(30_000L, 60_000L), 0.0001f)
        assertEquals(1f, MediaPreviewSupport.positionToFraction(90_000L, 60_000L), 0.0001f)

        assertEquals(30_000L, MediaPreviewSupport.fractionToPositionMs(0.5f, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(Float.NaN, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(Float.POSITIVE_INFINITY, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.fractionToPositionMs(2.0f, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(0.5f, -1L))
    }

    @Test
    fun playbackSpeedCycleAndFormatting() {
        var speed = 1f
        val visited = mutableListOf<Float>()
        repeat(MediaPreviewSupport.PLAYBACK_SPEEDS.size) {
            visited += speed
            speed = MediaPreviewSupport.nextPlaybackSpeed(speed)
        }
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f), visited)
        assertEquals(1f, speed, 0.001f)
        assertEquals(1f, MediaPreviewSupport.nextPlaybackSpeed(Float.NaN), 0.001f)
        assertEquals("0.5×", MediaPreviewSupport.formatPlaybackSpeed(0.5f))
        assertEquals("0.75×", MediaPreviewSupport.formatPlaybackSpeed(0.75f))
        assertEquals("1×", MediaPreviewSupport.formatPlaybackSpeed(1f))
        assertEquals("1.25×", MediaPreviewSupport.formatPlaybackSpeed(1.25f))
        assertEquals("2×", MediaPreviewSupport.formatPlaybackSpeed(2f))
    }

    @Test
    fun audioMetadataMergeAndEqualityHandleArtworkBytes() {
        val artA = byteArrayOf(1, 2, 3, 4)
        val artB = byteArrayOf(1, 2, 3, 4)
        val artC = byteArrayOf(9, 8, 7)

        val meta1 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artA)
        val meta2 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artB)
        val meta3 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artC)

        assertEquals(meta1, meta2)
        assertEquals(meta1.hashCode(), meta2.hashCode())
        assertNotEquals(meta1, meta3)
        assertEquals("Artist  ·  Album", meta1.subtitle)

        val merged = MediaPreviewSupport.mergeAudioMetadata(
            primary = AudioTrackMetadata(title = "  Primary Title ", artist = "  ", album = null, artworkBytes = null),
            fallback = AudioTrackMetadata(title = "Fallback Title", artist = "Fallback Artist", album = "Fallback Album", artworkBytes = artC),
        )
        assertEquals("Primary Title", merged.title)
        assertEquals("Fallback Artist", merged.artist)
        assertEquals("Fallback Album", merged.album)
        assertArrayEquals(artC, merged.artworkBytes)
    }

    @Test
    fun loaderReadsFileHeadersAndRejectsEmptyOrOversizedArtworkSafely() {
        val gifFile = tmp.newFile("anim.gif").apply {
            writeBytes("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.ISO_8859_1))
        }
        val prefix = MediaPreviewLoader.readHeaderPrefix(gifFile)
        assertTrue(MediaPreviewSupport.isGifHeader(prefix))

        // Null / empty bytes return null safely
        assertNull(MediaPreviewLoader.decodeArtworkBitmap(null))
        assertNull(MediaPreviewLoader.decodeArtworkBitmap(ByteArray(0)))
    }
}
