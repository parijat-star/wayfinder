// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import android.provider.MediaStore
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.inspector.MetadataRetriever
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Technical media metadata extracted without playing the file. */
data class MediaInfo(
    val container: String? = null,
    val durationUs: Long? = null,
    val overallBitrateBps: Long? = null,
    val videoTracks: List<VideoTrackInfo> = emptyList(),
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val tags: MediaTags? = null,
) {
    val isEmpty: Boolean
        get() = container == null &&
            durationUs == null &&
            overallBitrateBps == null &&
            videoTracks.isEmpty() &&
            audioTracks.isEmpty() &&
            subtitleTracks.isEmpty() &&
            tags == null
}

data class VideoTrackInfo(
    val codec: String,
    val codecProfile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int = 0,
    val pixelAspectRatio: Float = 1f,
    val frameRate: Float? = null,
    val bitrateBps: Long? = null,
    val bitDepth: Int? = null,
    val colorSpace: String? = null,
    val colorTransfer: String? = null,
    val colorRange: String? = null,
    val language: String? = null,
    val label: String? = null,
)

data class AudioTrackInfo(
    val codec: String,
    val codecProfile: String? = null,
    val channelCount: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateBps: Long? = null,
    val language: String? = null,
    val label: String? = null,
)

data class SubtitleTrackInfo(
    val codec: String,
    val language: String? = null,
    val label: String? = null,
)

data class MediaTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val encoder: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && year == null && genre == null && encoder == null
}

/**
 * Inspects audio/video files using official AndroidX Media3 [MetadataRetriever] without
 * instantiating a player or decoding/playing the media.
 */
object MediaInspector {
    private const val INSPECT_TIMEOUT_MS = 10_000L
    internal const val REMOTE_INSPECT_MAX_BYTES = 8L * 1024L * 1024L

    private val LOCAL_SEEKABLE_CONTENT_AUTHORITIES = setOf(
        SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY,
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        MediaStore.AUTHORITY,
    )

    /**
     * Returns `true` only when [uri] is backed by on-device local storage where random `ContentResolver`
     * seeks will neither stage multi-gigabyte cloud/Cross-Device downloads nor fail with `ESPIPE` on pipes.
     */
    fun isLocalSeekableContentUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> true
            "content" -> uri.authority in LOCAL_SEEKABLE_CONTENT_AUTHORITIES
            else -> false
        }
    }

    @OptIn(UnstableApi::class)
    internal fun buildStreamInspectorExtractorsFactory(): DefaultExtractorsFactory =
        DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

    /** Inspects a seekable local `file://` or local-storage `content://` URI directly via Media3 [MetadataRetriever]. */
    @OptIn(UnstableApi::class)
    suspend fun inspectUri(
        context: Context,
        uri: Uri,
        fileSizeBytes: Long = 0L,
        fileExtension: String? = null,
    ): MediaInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val appContext = context.applicationContext
        val mediaItem = MediaItem.fromUri(uri)
        MetadataRetriever.Builder(appContext, mediaItem).build().use { retriever ->
            val trackGroups = retriever.retrieveTrackGroups().await()
            val durationUs = runCatching { retriever.retrieveDurationUs().await() }.getOrDefault(C.TIME_UNSET)
            extractFromTrackGroups(trackGroups, durationUs, fileSizeBytes, fileExtension)
                .takeUnless { it.isEmpty }
        }
    }

    /**
     * Inspects a non-local media file (SMB, WebDAV, FTP, Drive, OneDrive, Cross-Device Phone, MTP, Zip)
     * via a shared prefix-caching bounded [DataSource] backed by [openInput], capping total network/pipe
     * bytes read across all extractor seeks at [maxBytes].
     */
    @OptIn(UnstableApi::class)
    suspend fun inspectStream(
        context: Context,
        fileName: String,
        fileSizeBytes: Long,
        openInput: () -> InputStream,
        maxBytes: Long = REMOTE_INSPECT_MAX_BYTES,
    ): MediaInfo? {
        val sharedBuffer = SharedPrefixStreamBuffer(openInput, fileSizeBytes, maxBytes)
        return try {
            withTimeout(INSPECT_TIMEOUT_MS) {
                val appContext = context.applicationContext
                val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
                val factory = DataSource.Factory { BoundedStreamDataSource(sharedBuffer) }
                val mediaSourceFactory = DefaultMediaSourceFactory(factory, buildStreamInspectorExtractorsFactory())
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 0))
                val mediaItem = MediaItem.fromUri(Uri.parse("wayfinder://inspect/${Uri.encode(fileName)}"))
                MetadataRetriever.Builder(appContext, mediaItem)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .use { retriever ->
                        val trackGroups = retriever.retrieveTrackGroups().await()
                        val durationUs = runCatching { retriever.retrieveDurationUs().await() }.getOrDefault(C.TIME_UNSET)
                        extractFromTrackGroups(trackGroups, durationUs, fileSizeBytes, ext)
                            .takeUnless { it.isEmpty }
                    }
            }
        } finally {
            sharedBuffer.closeUnderlying()
        }
    }

    /** Pure extraction from Media3 [TrackGroupArray] + [durationUs]; safe to run in unit tests. */
    @OptIn(UnstableApi::class)
    fun extractFromTrackGroups(
        trackGroups: TrackGroupArray,
        durationUs: Long,
        fileSizeBytes: Long = 0L,
        fileExtension: String? = null,
    ): MediaInfo {
        val videos = ArrayList<VideoTrackInfo>()
        val audios = ArrayList<AudioTrackInfo>()
        val subtitles = ArrayList<SubtitleTrackInfo>()
        var containerMime: String? = null
        val metadataList = ArrayList<Metadata>()

        for (i in 0 until trackGroups.length) {
            val group = trackGroups.get(i)
            for (j in 0 until group.length) {
                val format = group.getFormat(j)
                if (containerMime == null && !format.containerMimeType.isNullOrBlank()) {
                    containerMime = format.containerMimeType
                }
                format.metadata?.let { metadataList.add(it) }

                when (MimeTypes.getTrackType(format.sampleMimeType)) {
                    C.TRACK_TYPE_VIDEO -> videos.add(extractVideoTrack(format))
                    C.TRACK_TYPE_AUDIO -> audios.add(extractAudioTrack(format))
                    C.TRACK_TYPE_TEXT -> subtitles.add(extractSubtitleTrack(format))
                }
            }
        }

        val validDurationUs = durationUs.takeIf { it > 0L && it != C.TIME_UNSET }
        val overallBitrate = computeOverallBitrate(
            fileSizeBytes = fileSizeBytes,
            durationUs = validDurationUs,
            trackBitrates = videos.mapNotNull { it.bitrateBps } + audios.mapNotNull { it.bitrateBps },
        )

        val container = MediaFormatters.friendlyContainerName(containerMime, fileExtension)
        val tags = extractTags(metadataList)

        return MediaInfo(
            container = container,
            durationUs = validDurationUs,
            overallBitrateBps = overallBitrate,
            videoTracks = videos,
            audioTracks = audios,
            subtitleTracks = subtitles,
            tags = tags,
        )
    }

    @OptIn(UnstableApi::class)
    private fun extractVideoTrack(f: Format): VideoTrackInfo {
        val color = f.colorInfo
        return VideoTrackInfo(
            codec = MediaFormatters.friendlyCodecName(f.sampleMimeType, f.codecs),
            codecProfile = f.codecs?.trim()?.takeIf { it.isNotEmpty() },
            width = f.width.takeIf { it > 0 },
            height = f.height.takeIf { it > 0 },
            rotationDegrees = ((f.rotationDegrees % 360) + 360) % 360,
            pixelAspectRatio = if (f.pixelWidthHeightRatio.isFinite() && f.pixelWidthHeightRatio > 0f) f.pixelWidthHeightRatio else 1f,
            frameRate = f.frameRate.takeIf { it.isFinite() && it > 0f && it != Format.NO_VALUE.toFloat() },
            bitrateBps = bestBitrate(f),
            bitDepth = color?.lumaBitdepth?.takeIf { it > 0 && it != Format.NO_VALUE },
            colorSpace = color?.let { friendlyColorSpace(it.colorSpace) },
            colorTransfer = color?.let { friendlyColorTransfer(it.colorTransfer) },
            colorRange = color?.let { friendlyColorRange(it.colorRange) },
            language = MediaFormatters.friendlyLanguage(f.language),
            label = f.label?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    @OptIn(UnstableApi::class)
    private fun extractAudioTrack(f: Format): AudioTrackInfo = AudioTrackInfo(
        codec = MediaFormatters.friendlyCodecName(f.sampleMimeType, f.codecs),
        codecProfile = f.codecs?.trim()?.takeIf { it.isNotEmpty() },
        channelCount = f.channelCount.takeIf { it > 0 },
        sampleRateHz = f.sampleRate.takeIf { it > 0 },
        bitDepth = pcmBitDepth(f.pcmEncoding),
        bitrateBps = bestBitrate(f),
        language = MediaFormatters.friendlyLanguage(f.language),
        label = f.label?.trim()?.takeIf { it.isNotEmpty() },
    )

    @OptIn(UnstableApi::class)
    private fun extractSubtitleTrack(f: Format): SubtitleTrackInfo = SubtitleTrackInfo(
        codec = MediaFormatters.friendlyCodecName(f.sampleMimeType, f.codecs),
        language = MediaFormatters.friendlyLanguage(f.language),
        label = f.label?.trim()?.takeIf { it.isNotEmpty() },
    )

    @OptIn(UnstableApi::class)
    private fun bestBitrate(f: Format): Long? {
        val raw = when {
            f.averageBitrate > 0 -> f.averageBitrate
            f.bitrate > 0 -> f.bitrate
            f.peakBitrate > 0 -> f.peakBitrate
            else -> Format.NO_VALUE
        }
        return raw.takeIf { it > 0 }?.toLong()
    }

    @OptIn(UnstableApi::class)
    private fun pcmBitDepth(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    @OptIn(UnstableApi::class)
    private fun friendlyColorSpace(space: Int): String? = when (space) {
        C.COLOR_SPACE_BT601 -> "BT.601"
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        else -> null
    }

    @OptIn(UnstableApi::class)
    private fun friendlyColorTransfer(transfer: Int): String? = when (transfer) {
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_SRGB -> "sRGB"
        C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma 2.2"
        C.COLOR_TRANSFER_LINEAR -> "Linear"
        else -> null
    }

    @OptIn(UnstableApi::class)
    private fun friendlyColorRange(range: Int): String? = when (range) {
        C.COLOR_RANGE_LIMITED -> "Limited"
        C.COLOR_RANGE_FULL -> "Full"
        else -> null
    }

    internal fun computeOverallBitrate(
        fileSizeBytes: Long,
        durationUs: Long?,
        trackBitrates: List<Long>,
    ): Long? {
        if (fileSizeBytes > 0L && durationUs != null && durationUs > 0L) {
            val bits = fileSizeBytes.toDouble() * 8.0
            val seconds = durationUs.toDouble() / 1_000_000.0
            val bps = (bits / seconds).toLong()
            if (bps > 0L) return bps
        }
        val summed = trackBitrates.filter { it > 0L }.sum()
        return summed.takeIf { it > 0L }
    }

    @OptIn(UnstableApi::class)
    private fun extractTags(metadataList: List<Metadata>): MediaTags? {
        if (metadataList.isEmpty()) return null
        val builder = MediaMetadata.Builder()
        var encoder: String? = null
        for (md in metadataList) {
            builder.populateFromMetadata(md)
            if (encoder == null) {
                encoder = findEncoderTag(md)
            }
        }
        val mm = builder.build()
        val year = mm.recordingYear?.toString()
            ?: mm.releaseYear?.toString()
        val tags = MediaTags(
            title = mm.title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            artist = (mm.artist ?: mm.albumArtist)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            album = mm.albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            year = year,
            genre = mm.genre?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            encoder = encoder,
        )
        return tags.takeUnless { it.isEmpty }
    }

    @OptIn(UnstableApi::class)
    private fun findEncoderTag(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is TextInformationFrame -> {
                    if (entry.id.equals("TSSE", ignoreCase = true) || entry.id.equals("TENC", ignoreCase = true)) {
                        val v = entry.values.firstOrNull()?.trim()
                        if (!v.isNullOrEmpty()) return v
                    }
                }
                is VorbisComment -> {
                    if (entry.key.equals("ENCODER", ignoreCase = true) || entry.key.equals("ENCODED_BY", ignoreCase = true)) {
                        val v = entry.value.trim()
                        if (v.isNotEmpty()) return v
                    }
                }
                is MdtaMetadataEntry -> {
                    if (entry.key.contains("encoder", ignoreCase = true) || entry.key.contains("software", ignoreCase = true)) {
                        if (entry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING) {
                            val v = String(entry.value, Charsets.UTF_8).trim()
                            if (v.isNotEmpty()) return v
                        }
                    }
                }
            }
        }
        return null
    }
}

/** Pure formatting helpers for [MediaInfo] fields (unit-tested on JVM). */
object MediaFormatters {

    /** Formats [durationUs] as `H:MM:SS`, `M:SS`, or `0:04.5` for very short clips. */
    fun formatDuration(durationUs: Long): String {
        if (durationUs < 0L) return ""
        val totalMs = durationUs / 1_000L
        val totalSec = totalMs / 1_000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0L -> "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
            totalSec < 10L && (totalMs % 1000L) >= 50L -> {
                val tenths = (totalMs % 1000L) / 100L
                if (tenths > 0L) "0:%02d.%d".format(Locale.US, seconds, tenths)
                else "0:%02d".format(Locale.US, seconds)
            }
            else -> "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** Formats [bps] as `8.5 Mbps`, `320 kbps`, or `512 bps`. */
    fun formatBitrate(bps: Long): String = when {
        bps >= 1_000_000L -> {
            val mbps = bps / 1_000_000.0
            val formatted = "%.2f".format(Locale.US, mbps).trimEnd('0').trimEnd('.')
            "$formatted Mbps"
        }
        bps >= 1_000L -> "${(bps + 500L) / 1_000L} kbps"
        else -> "$bps bps"
    }

    /** Formats [fps] as `24 fps`, `60 fps`, `23.98 fps`, `29.97 fps`, etc. */
    fun formatFrameRate(fps: Float): String {
        if (!fps.isFinite() || fps <= 0f) return "0 fps"
        if (fps >= 1_000_000f) {
            val formatted = "%.2f".format(Locale.US, fps).trimEnd('0').trimEnd('.')
            return "$formatted fps"
        }
        val rounded = fps.roundToInt()
        return if (abs(fps - rounded) < 0.01f) {
            "$rounded fps"
        } else {
            val formatted = "%.2f".format(Locale.US, fps).trimEnd('0').trimEnd('.')
            "$formatted fps"
        }
    }

    /** Formats video dimensions, aspect ratio, rotation, and non-square pixel aspect ratio. */
    fun formatResolution(
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
        pixelAspectRatio: Float = 1f,
    ): String {
        val effectiveW = if (width > 0 && pixelAspectRatio.isFinite() && pixelAspectRatio > 0f && abs(pixelAspectRatio - 1f) > 0.01f) {
            (width.toDouble() * pixelAspectRatio.toDouble()).coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
        } else {
            width
        }
        val ratio = formatAspectRatio(effectiveW, height)
        val parts = ArrayList<String>(3)
        parts.add("$width × $height")
        if (ratio != null) parts.add("($ratio)")
        val normRot = ((rotationDegrees % 360) + 360) % 360
        if (normRot != 0) parts.add("rotated ${normRot}°")
        return parts.joinToString(" ")
    }

    internal fun formatAspectRatio(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val g = gcd(w, h)
        val rw = w / g
        val rh = h / g
        return if (rw <= 21 && rh <= 21) {
            "$rw:$rh"
        } else {
            val dec = w.toDouble() / h.toDouble()
            val formatted = "%.2f".format(Locale.US, dec).trimEnd('0').trimEnd('.')
            "$formatted:1"
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a.toLong())
        var y = abs(b.toLong())
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x <= 0L || x > Int.MAX_VALUE.toLong()) 1 else x.toInt()
    }

    /** Formats audio channel counts into familiar layout names. */
    fun formatChannels(count: Int): String = when (count) {
        1 -> "1 (Mono)"
        2 -> "2 (Stereo)"
        6 -> "6 (5.1)"
        8 -> "8 (7.1)"
        else -> "$count ch"
    }

    /** Formats audio sample rate in kHz or Hz (e.g., `48 kHz`, `44.1 kHz`). */
    fun formatSampleRate(hz: Int): String = when {
        hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
        hz >= 1000 -> {
            val formatted = "%.2f".format(Locale.US, hz / 1000.0).trimEnd('0').trimEnd('.')
            "$formatted kHz"
        }
        else -> "$hz Hz"
    }

    /** Formats video color metadata (bit depth, color space, transfer / HDR, range). */
    fun formatColorSummary(track: VideoTrackInfo): String? {
        val parts = listOfNotNull(
            track.bitDepth?.let { "$it-bit" },
            track.colorSpace,
            track.colorTransfer,
            track.colorRange,
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Formats a codec display string, appending the RFC 6381 profile/level string when informative. */
    fun formatCodec(codec: String, codecProfile: String?): String =
        if (!codecProfile.isNullOrBlank() && !codec.contains(codecProfile, ignoreCase = true)) {
            "$codec ($codecProfile)"
        } else {
            codec
        }

    /** Maps Media3 MIME types to human-readable codec names. */
    fun friendlyCodecName(mimeType: String?, codecs: String? = null): String {
        val mime = mimeType?.lowercase(Locale.US) ?: return codecs?.takeIf { it.isNotBlank() } ?: "Unknown"
        return when (mime) {
            MimeTypes.VIDEO_H264 -> "H.264 / AVC"
            MimeTypes.VIDEO_H265 -> "H.265 / HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_VP8 -> "VP8"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.VIDEO_MP4V -> "MPEG-4 Visual"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_MPEG -> "MPEG-1"
            MimeTypes.VIDEO_H263 -> "H.263"
            MimeTypes.VIDEO_MJPEG -> "Motion JPEG"
            MimeTypes.VIDEO_APV -> "APV"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L1 -> "MPEG-1 Layer I"
            MimeTypes.AUDIO_MPEG_L2 -> "MPEG-1 Layer II"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC-3)"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus w/ Atmos"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
            MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
            MimeTypes.AUDIO_WAV -> "WAV / PCM"
            MimeTypes.APPLICATION_SUBRIP -> "SubRip (SRT)"
            MimeTypes.TEXT_VTT -> "WebVTT"
            MimeTypes.TEXT_SSA -> "ASS / SSA"
            MimeTypes.APPLICATION_TX3G -> "TX3G"
            MimeTypes.APPLICATION_TTML -> "TTML"
            MimeTypes.APPLICATION_CEA608 -> "CEA-608"
            MimeTypes.APPLICATION_CEA708 -> "CEA-708"
            MimeTypes.APPLICATION_PGS -> "PGS"
            MimeTypes.APPLICATION_DVBSUBS -> "DVB Subtitles"
            MimeTypes.APPLICATION_VOBSUB -> "VobSub"
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }
    }

    /** Maps Media3 container MIME types (or file extension fallback) to human-readable container names. */
    fun friendlyContainerName(containerMimeType: String?, fileExtension: String? = null): String? {
        val fromMime = when (containerMimeType?.lowercase(Locale.US)) {
            MimeTypes.VIDEO_MP4, MimeTypes.AUDIO_MP4 -> "MP4"
            MimeTypes.VIDEO_MATROSKA, MimeTypes.AUDIO_MATROSKA -> "Matroska (MKV)"
            MimeTypes.VIDEO_WEBM, MimeTypes.AUDIO_WEBM -> "WebM"
            "video/quicktime" -> "QuickTime (MOV)"
            MimeTypes.VIDEO_AVI -> "AVI"
            MimeTypes.VIDEO_MP2T -> "MPEG-TS"
            MimeTypes.VIDEO_PS -> "MPEG-PS"
            MimeTypes.VIDEO_FLV -> "FLV"
            MimeTypes.VIDEO_OGG, MimeTypes.AUDIO_OGG -> "Ogg"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_WAV, "audio/x-wav" -> "WAV"
            MimeTypes.VIDEO_H263, "video/3gpp", "audio/3gpp" -> "3GP"
            MimeTypes.AUDIO_AMR -> "AMR"
            else -> null
        }
        if (fromMime != null) return fromMime
        return when (fileExtension?.lowercase(Locale.US)) {
            "mp4", "m4v", "m4a" -> "MP4"
            "mkv", "mka" -> "Matroska (MKV)"
            "webm" -> "WebM"
            "mov" -> "QuickTime (MOV)"
            "avi" -> "AVI"
            "ts", "m2ts", "mts" -> "MPEG-TS"
            "3gp" -> "3GP"
            "mp3" -> "MP3"
            "wav" -> "WAV"
            "flac" -> "FLAC"
            "ogg", "oga", "ogv", "opus" -> "Ogg"
            "aac" -> "AAC (ADTS)"
            "amr" -> "AMR"
            else -> fileExtension?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        }
    }

    /** Formats a BCP-47 / ISO-639 language tag into a display language name, ignoring undetermined tags. */
    fun friendlyLanguage(code: String?): String? {
        val trimmed = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.equals("und", ignoreCase = true) || trimmed.equals("unknown", ignoreCase = true)) return null
        val display = Locale.forLanguageTag(trimmed).getDisplayLanguage(Locale.getDefault())
        return display.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: trimmed
    }

    /** Formats a multi-line plain-text summary suitable for clipboard copying. */
    fun formatSummary(info: MediaInfo): String = buildString {
        info.container?.let { appendLine("Container: $it") }
        info.durationUs?.let { appendLine("Duration: ${formatDuration(it)}") }
        info.overallBitrateBps?.let { appendLine("Bitrate: ${formatBitrate(it)}") }
        info.videoTracks.forEachIndexed { idx, v ->
            val header = if (info.videoTracks.size > 1) "Video #${idx + 1}" else "Video"
            appendLine("$header: ${formatCodec(v.codec, v.codecProfile)}")
            if (v.width != null && v.height != null) {
                appendLine("  Resolution: ${formatResolution(v.width, v.height, v.rotationDegrees, v.pixelAspectRatio)}")
            }
            v.frameRate?.let { appendLine("  Frame rate: ${formatFrameRate(it)}") }
            v.bitrateBps?.let { appendLine("  Bitrate: ${formatBitrate(it)}") }
            formatColorSummary(v)?.let { appendLine("  Color: $it") }
        }
        info.audioTracks.forEachIndexed { idx, a ->
            val header = if (info.audioTracks.size > 1) "Audio #${idx + 1}" else "Audio"
            appendLine("$header: ${formatCodec(a.codec, a.codecProfile)}")
            a.channelCount?.let { appendLine("  Channels: ${formatChannels(it)}") }
            a.sampleRateHz?.let { appendLine("  Sample rate: ${formatSampleRate(it)}") }
            a.bitDepth?.let { appendLine("  Bit depth: $it-bit") }
            a.bitrateBps?.let { appendLine("  Bitrate: ${formatBitrate(it)}") }
            a.language?.let { appendLine("  Language: $it") }
        }
        if (info.subtitleTracks.isNotEmpty()) {
            val subs = info.subtitleTracks.joinToString(", ") { s ->
                val name = listOfNotNull(s.language, s.label).firstOrNull()
                if (name != null) "$name (${s.codec})" else s.codec
            }
            appendLine("Subtitles: $subs")
        }
        info.tags?.let { t ->
            t.title?.let { appendLine("Title: $it") }
            t.artist?.let { appendLine("Artist: $it") }
            t.album?.let { appendLine("Album: $it") }
            t.year?.let { appendLine("Year: $it") }
            t.genre?.let { appendLine("Genre: $it") }
            t.encoder?.let { appendLine("Encoder: $it") }
        }
    }.trimEnd()
}

/** Cancellable coroutine bridge for Guava [ListenableFuture] without an extra dependency. */
internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            if (isCancelled) {
                cont.cancel()
                return@addListener
            }
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        },
        MoreExecutors.directExecutor(),
    )
    cont.invokeOnCancellation { cancel(true) }
}

/**
 * Shared prefix cache over a single lazy [openInput] stream for [BoundedStreamDataSource].
 *
 * Ensures that multiple `close()` + `open(DataSpec(position))` extractor seeks within `[0, maxBytes)`
 * reuse a single underlying stream (serving backward seeks from memory and advancing forward seeks
 * incrementally), strictly cap total network/pipe bytes transferred at [maxBytes], and abort
 * blocking socket reads immediately when [closeUnderlying] is called on timeout or cancellation.
 */
internal class SharedPrefixStreamBuffer(
    private val openInput: () -> InputStream,
    val totalSize: Long,
    val maxBytes: Long,
) {
    companion object {
        private const val CHUNK_SIZE = 64 * 1024
    }

    private val ioLock = Any()
    private val chunkLock = Any()
    private val chunks = ArrayList<ByteArray>()
    private var bufferedBytes = 0L
    private var eofReached = false
    private var streamFailure: IOException? = null

    @Volatile private var closed = false
    @Volatile private var activeStream: InputStream? = null

    val bytesDownloaded: Long
        get() = synchronized(chunkLock) { bufferedBytes }

    fun ensureBufferedTo(targetExclusive: Long) {
        val clampedTarget = minOf(targetExclusive, maxBytes)
        if (clampedTarget <= 0L) return
        synchronized(ioLock) {
            checkNotCancelled()
            while (true) {
                val curBuffered = synchronized(chunkLock) { bufferedBytes }
                if (curBuffered >= clampedTarget || eofReached) return
                streamFailure?.let { throw it }
                checkNotCancelled()
                val stream = try {
                    activeStream ?: openInput().also { opened ->
                        activeStream = opened
                        if (closed) {
                            runCatching { opened.close() }
                            activeStream = null
                            throw InterruptedIOException("Media inspection cancelled")
                        }
                    }
                } catch (e: IOException) {
                    closeActiveStream()
                    streamFailure = e
                    throw e
                }
                val chunkIndex = (curBuffered / CHUNK_SIZE).toInt()
                val chunkOffset = (curBuffered % CHUNK_SIZE).toInt()
                val chunk = synchronized(chunkLock) {
                    while (chunks.size <= chunkIndex) {
                        chunks.add(ByteArray(CHUNK_SIZE))
                    }
                    chunks[chunkIndex]
                }
                val maxToRead = minOf(
                    (CHUNK_SIZE - chunkOffset).toLong(),
                    maxBytes - curBuffered,
                ).toInt()
                if (maxToRead <= 0) return
                val n = try {
                    stream.read(chunk, chunkOffset, maxToRead)
                } catch (e: IOException) {
                    closeActiveStream()
                    streamFailure = e
                    throw e
                }
                checkNotCancelled()
                if (n < 0) {
                    eofReached = true
                    closeActiveStream()
                    return
                }
                if (n > 0) {
                    val reachedCap = synchronized(chunkLock) {
                        bufferedBytes += n
                        if (bufferedBytes >= maxBytes) {
                            eofReached = true
                            true
                        } else {
                            false
                        }
                    }
                    if (reachedCap) {
                        closeActiveStream()
                        return
                    }
                }
            }
        }
    }

    fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0L || position >= maxBytes) return C.RESULT_END_OF_INPUT
        checkNotCancelled()
        val desiredEnd = minOf(position + length.toLong(), maxBytes)
        ensureBufferedTo(desiredEnd)
        synchronized(chunkLock) {
            if (position >= bufferedBytes) return C.RESULT_END_OF_INPUT
            val available = minOf(length.toLong(), bufferedBytes - position).toInt()
            val chunkIndex = (position / CHUNK_SIZE).toInt()
            val chunkOffset = (position % CHUNK_SIZE).toInt()
            val toCopy = minOf(available, CHUNK_SIZE - chunkOffset)
            System.arraycopy(chunks[chunkIndex], chunkOffset, dest, offset, toCopy)
            return toCopy
        }
    }

    fun closeUnderlying() {
        closed = true
        closeActiveStream()
    }

    private fun closeActiveStream() {
        val s = activeStream
        activeStream = null
        if (s != null) {
            runCatching { s.close() }
        }
    }

    private fun checkNotCancelled() {
        if (closed || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Media inspection cancelled")
        }
    }
}

/**
 * Bounded read-only Media3 [DataSource] backed by a [SharedPrefixStreamBuffer].
 * Caps total bytes read across all extractor seeks to [SharedPrefixStreamBuffer.maxBytes].
 */
@OptIn(UnstableApi::class)
internal class BoundedStreamDataSource(
    private val shared: SharedPrefixStreamBuffer,
) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var readPosition = 0L
    private var remainingLength = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val position = dataSpec.position
        if (position < 0L || position >= shared.maxBytes) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        if (position > 0L) {
            shared.ensureBufferedTo(position)
            if (position > shared.bytesDownloaded) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
        }
        readPosition = position
        opened = true
        transferStarted(dataSpec)
        val remainingSize = if (shared.totalSize in 1L..shared.maxBytes) {
            (shared.totalSize - position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        val resolvedLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            if (remainingSize != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remainingSize) else dataSpec.length
        } else {
            remainingSize
        }
        remainingLength = resolvedLength
        return resolvedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        if (remainingLength == 0L) return C.RESULT_END_OF_INPUT
        val maxRead = if (remainingLength != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), remainingLength).toInt()
        } else {
            length
        }
        val n = shared.readAt(readPosition, buffer, offset, maxRead)
        if (n == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        readPosition += n
        if (remainingLength != C.LENGTH_UNSET.toLong()) {
            remainingLength = (remainingLength - n).coerceAtLeast(0L)
        }
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        remainingLength = C.LENGTH_UNSET.toLong()
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
