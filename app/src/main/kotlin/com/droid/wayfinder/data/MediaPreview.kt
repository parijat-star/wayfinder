// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Metadata and optional embedded cover artwork extracted for an audio track in Quick Look / Details. */
data class AudioTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkBytes: ByteArray? = null,
) {
    val subtitle: String?
        get() = listOfNotNull(
            artist?.trim()?.takeIf { it.isNotEmpty() },
            album?.trim()?.takeIf { it.isNotEmpty() },
        ).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

    val hasAnyMetadata: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank() || artworkBytes != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrackMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artworkBytes.contentEquals(other.artworkBytes)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}

/** Pure, JVM-testable helpers for media preview playback math, formatting, and animated-image detection. */
object MediaPreviewSupport {
    const val SEEK_STEP_MS: Long = 10_000L
    const val SLIDER_KEY_STEP_MS: Long = 5_000L
    const val MAX_ARTWORK_BYTES: Int = 16 * 1024 * 1024
    const val MAX_ANIMATED_IMAGE_EDGE_PX: Int = 2048
    const val MAX_ARTWORK_EDGE_PX: Int = 720

    val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val ANIMATED_IMAGE_EXTS = setOf("gif", "webp")
    private const val MAX_WEBP_CHUNKS_TO_SCAN = 32

    /** True for file extensions whose files may contain multi-frame animations supported by [ImageDecoder]. */
    fun isAnimatedImageExtension(extension: String): Boolean =
        extension.trim().lowercase(Locale.ROOT) in ANIMATED_IMAGE_EXTS

    /** True when [bytes] starts with a standard GIF87a or GIF89a header. */
    fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
    }

    /**
     * True when [bytes] is a RIFF/WEBP container advertising animation via a `VP8X` animation flag
     * (`0x02`) or an `ANIM` top-level chunk. Safely bounds-checked against truncated or adversarial input.
     */
    fun isAnimatedWebpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WEBP")) return false

        var offset = 12
        var chunksVisited = 0
        while (offset + 8 <= bytes.size && chunksVisited < MAX_WEBP_CHUNKS_TO_SCAN) {
            chunksVisited++
            val chunkSize = readUint32Le(bytes, offset + 4)
            if (matchesAscii(bytes, offset, "VP8X")) {
                if (chunkSize < 10L || offset + 9 > bytes.size) return false
                return (bytes[offset + 8].toInt() and 0x02) != 0
            }
            if (matchesAscii(bytes, offset, "ANIM")) {
                return true
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = offset.toLong() + 8L + paddedSize
            if (paddedSize < 0L || nextOffset <= offset.toLong() || nextOffset > bytes.size.toLong()) {
                break
            }
            offset = nextOffset.toInt()
        }
        return false
    }

    /** True when [bytes] begins with a GIF header or an animated WebP header. */
    fun isAnimatedImageHeader(bytes: ByteArray): Boolean =
        isGifHeader(bytes) || isAnimatedWebpHeader(bytes)

    /**
     * Computes downscaled target dimensions that fit within `[maxEdge] x [maxEdge]` while preserving
     * aspect ratio. Never upscales images that are already within bounds.
     */
    fun computeScaledDimensions(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        if (maxEdge <= 0) return srcWidth to srcHeight
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= maxEdge) return srcWidth to srcHeight
        val scale = maxEdge.toDouble() / longest.toDouble()
        val dstW = (srcWidth * scale).roundToLong().toInt().coerceIn(1, maxEdge)
        val dstH = (srcHeight * scale).roundToLong().toInt().coerceIn(1, maxEdge)
        return dstW to dstH
    }

    /** Computes the largest power-of-2 [BitmapFactory.Options.inSampleSize] keeping the image near [maxEdge]. */
    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val halfW = srcWidth / 2
        val halfH = srcHeight / 2
        while (halfW / sample >= maxEdge && halfH / sample >= maxEdge && sample < (1 shl 20)) {
            sample *= 2
        }
        return sample
    }

    /** Formats a playback timestamp in milliseconds as `M:SS` or `H:MM:SS`. */
    fun formatPlaybackTime(timeMs: Long): String {
        if (timeMs <= 0L) return "0:00"
        val totalSec = timeMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** Computes an overflow-safe clamped seek target in milliseconds. */
    fun clampSeekPosition(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val base = currentMs.coerceAtLeast(0L)
        val target = when {
            deltaMs > 0L && base > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
            deltaMs < 0L && base < Long.MIN_VALUE - deltaMs -> 0L
            else -> (base + deltaMs).coerceAtLeast(0L)
        }
        return if (durationMs > 0L) target.coerceIn(0L, durationMs) else target
    }

    /** Converts `[positionMs]` within `[durationMs]` to a normalized `[0f, 1f]` slider fraction. */
    fun positionToFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        if (positionMs >= durationMs) return 1f
        return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Converts a normalized `[0f, 1f]` slider fraction back to milliseconds safely. */
    fun fractionToPositionMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L || !fraction.isFinite()) return 0L
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        return (clamped * durationMs.toDouble()).roundToLong().coerceIn(0L, durationMs)
    }

    /** Cycles to the next standard playback speed in [PLAYBACK_SPEEDS], wrapping to `0.5x`. */
    fun nextPlaybackSpeed(current: Float): Float {
        if (!current.isFinite()) return 1f
        val idx = PLAYBACK_SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        return if (idx >= 0) PLAYBACK_SPEEDS[(idx + 1) % PLAYBACK_SPEEDS.size] else 1f
    }

    /** Formats a playback speed multiplier concisely (e.g., `1×`, `1.25×`, `0.5×`). */
    fun formatPlaybackSpeed(speed: Float): String {
        val safe = if (speed.isFinite() && speed > 0f) speed else 1f
        val formatted = "%.2f".format(Locale.US, safe).trimEnd('0').trimEnd('.')
        return "${formatted}×"
    }

    /** Merges primary (e.g. Media3) metadata with fallback (e.g. MediaMetadataRetriever) metadata. */
    fun mergeAudioMetadata(primary: AudioTrackMetadata, fallback: AudioTrackMetadata): AudioTrackMetadata =
        AudioTrackMetadata(
            title = primary.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.title?.trim()?.takeIf { it.isNotEmpty() },
            artist = primary.artist?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.artist?.trim()?.takeIf { it.isNotEmpty() },
            album = primary.album?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.album?.trim()?.takeIf { it.isNotEmpty() },
            artworkBytes = primary.artworkBytes?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
                ?: fallback.artworkBytes?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES },
        )

    private fun matchesAscii(bytes: ByteArray, offset: Int, fourCc: String): Boolean {
        if (offset < 0 || offset + fourCc.length > bytes.size) return false
        for (i in fourCc.indices) {
            if (bytes[offset + i] != fourCc[i].code.toByte()) return false
        }
        return true
    }

    private fun readUint32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}

/** Off-main-thread Android SDK media preview loaders (zero third-party dependencies). */
object MediaPreviewLoader {
    private const val HEADER_PREFIX_BYTES = 512

    fun readHeaderPrefix(file: File, maxBytes: Int = HEADER_PREFIX_BYTES): ByteArray = runCatching {
        file.inputStream().use { inp ->
            val buf = ByteArray(maxBytes.coerceAtLeast(0))
            var total = 0
            while (total < buf.size) {
                val n = inp.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total == buf.size) buf else buf.copyOf(total)
        }
    }.getOrDefault(ByteArray(0))

    fun readHeaderPrefix(context: Context, uri: Uri, maxBytes: Int = HEADER_PREFIX_BYTES): ByteArray = runCatching {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return@runCatching ByteArray(0)
            return@runCatching readHeaderPrefix(File(path), maxBytes)
        }
        context.contentResolver.openInputStream(uri)?.use { inp ->
            val buf = ByteArray(maxBytes.coerceAtLeast(0))
            var total = 0
            while (total < buf.size) {
                val n = inp.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total == buf.size) buf else buf.copyOf(total)
        } ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))

    /**
     * Extracts embedded album art and basic ID3/MP4/FLAC/Vorbis tags using the official Android SDK
     * [MediaMetadataRetriever]. Must only be invoked on a background IO dispatcher.
     */
    fun extractFallbackAudioMetadata(context: Context, uri: Uri): AudioTrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: return AudioTrackMetadata()
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context.applicationContext, uri)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.trim()?.takeIf { it.isNotEmpty() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val artwork = retriever.embeddedPicture
                ?.takeIf { it.isNotEmpty() && it.size <= MediaPreviewSupport.MAX_ARTWORK_BYTES }
            AudioTrackMetadata(title = title, artist = artist, album = album, artworkBytes = artwork)
        } catch (_: Throwable) {
            AudioTrackMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Safely decodes embedded artwork bytes into a downsampled [Bitmap] bounded to [maxEdgePx]. */
    fun decodeArtworkBitmap(bytes: ByteArray?, maxEdgePx: Int = MediaPreviewSupport.MAX_ARTWORK_EDGE_PX): Bitmap? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MediaPreviewSupport.MAX_ARTWORK_BYTES) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = MediaPreviewSupport.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /**
     * Decodes a GIF or animated WebP using Android's native [ImageDecoder] (API 28+, minSdk 30) with
     * bounded dimensions so untrusted huge animations cannot exhaust memory.
     */
    fun decodeAnimatedDrawable(
        source: ImageDecoder.Source,
        maxEdgePx: Int = MediaPreviewSupport.MAX_ANIMATED_IMAGE_EDGE_PX,
    ): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val (w, h) = MediaPreviewSupport.computeScaledDimensions(
                srcWidth = info.size.width,
                srcHeight = info.size.height,
                maxEdge = maxEdgePx,
            )
            if (w != info.size.width || h != info.size.height) {
                decoder.setTargetSize(w, h)
            }
        }
    }.getOrNull()
}
