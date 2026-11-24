// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Non-empty EXIF & header metadata extracted from an image file without decoding pixels. */
data class ImageInfo(
    val width: Int? = null,
    val height: Int? = null,
    val orientation: String? = null,
    val colorSpace: String? = null,
    val dateTaken: String? = null,
    val camera: String? = null,
    val lens: String? = null,
    val software: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val exposureBias: String? = null,
    val exposureProgram: String? = null,
    val meteringMode: String? = null,
    val flash: String? = null,
    val whiteBalance: String? = null,
    val location: String? = null,
    val altitude: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val description: String? = null,
) {
    val isEmpty: Boolean
        get() = width == null &&
            height == null &&
            orientation == null &&
            colorSpace == null &&
            dateTaken == null &&
            camera == null &&
            lens == null &&
            software == null &&
            aperture == null &&
            shutterSpeed == null &&
            iso == null &&
            focalLength == null &&
            exposureBias == null &&
            exposureProgram == null &&
            meteringMode == null &&
            flash == null &&
            whiteBalance == null &&
            location == null &&
            altitude == null &&
            artist == null &&
            copyright == null &&
            description == null
}

/**
 * Inspects image headers and EXIF metadata using official AndroidX [ExifInterface]
 * (plus [BitmapFactory.Options.inJustDecodeBounds] for pixel dimensions when EXIF omits them)
 * without decoding pixel bitmaps into memory.
 */
object ImageInspector {
    private const val INSPECT_TIMEOUT_MS = 10_000L
    private const val REMOTE_INSPECT_MAX_BYTES = 2L * 1024L * 1024L

    /** Camera RAW & TIFF extensions supported by AndroidX [ExifInterface] beyond [FileKind.Image]. */
    private val EXTRA_EXIF_EXTENSIONS = setOf(
        "dng", "tif", "tiff", "cr2", "nef", "nrw", "arw", "orf", "rw2", "pef", "srw", "raf",
    )

    /** True if [entry] is a raster/RAW image whose headers/EXIF can be inspected. */
    fun isInspectableImage(entry: FileEntry): Boolean {
        if (entry.isDir || entry.isServer || entry.isShare) return false
        val ext = entry.extension.lowercase(Locale.US)
        if (ext == "svg") return false
        return entry.kind == FileKind.Image || ext in EXTRA_EXIF_EXTENSIONS
    }

    /** Inspects a local image [file] directly without decoding pixels. */
    suspend fun inspectFile(file: File): ImageInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val initial = exif?.let { extractFromExif(it) }
        if (initial != null && initial.width != null && initial.height != null) {
            return@withTimeout initial.takeUnless { it.isEmpty }
        }
        val (bw, bh) = decodeBoundsFromFile(file)
        val merged = if (exif != null) {
            extractFromExif(exif, fallbackWidth = bw, fallbackHeight = bh)
        } else {
            ImageInfo(width = bw, height = bh)
        }
        merged.takeUnless { it.isEmpty }
    }

    /** Inspects a `content://` URI (SAF / `doc://`) without decoding pixels. */
    suspend fun inspectUri(context: Context, uri: Uri): ImageInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val cr = context.applicationContext.contentResolver
        val exif = runCatching {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
            }
        }.getOrNull() ?: runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()

        val initial = exif?.let { extractFromExif(it) }
        if (initial != null && initial.width != null && initial.height != null) {
            return@withTimeout initial.takeUnless { it.isEmpty }
        }
        val (bw, bh) = runCatching {
            cr.openInputStream(uri)?.use { decodeBoundsFromStream(it) }
        }.getOrNull() ?: (null to null)

        val merged = if (exif != null) {
            extractFromExif(exif, fallbackWidth = bw, fallbackHeight = bh)
        } else {
            ImageInfo(width = bw, height = bh)
        }
        merged.takeUnless { it.isEmpty }
    }

    /**
     * Inspects a remote or archive image via a bounded prefix read (capped at [maxBytes]) so large
     * files over SMB/WebDAV/FTP/cloud are never downloaded in full just to read EXIF headers.
     */
    suspend fun inspectStream(
        openInput: () -> InputStream,
        maxBytes: Long = REMOTE_INSPECT_MAX_BYTES,
    ): ImageInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val prefix = openInput().use { raw ->
            readPrefix(raw, maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        if (prefix.isEmpty()) return@withTimeout null
        val exif = runCatching { ByteArrayInputStream(prefix).use { ExifInterface(it) } }.getOrNull()
        val initial = exif?.let { extractFromExif(it) }
        if (initial != null && initial.width != null && initial.height != null) {
            return@withTimeout initial.takeUnless { it.isEmpty }
        }
        val (bw, bh) = ByteArrayInputStream(prefix).use { decodeBoundsFromStream(it) }
        val merged = if (exif != null) {
            extractFromExif(exif, fallbackWidth = bw, fallbackHeight = bh)
        } else {
            ImageInfo(width = bw, height = bh)
        }
        merged.takeUnless { it.isEmpty }
    }

    /** Extracts populated EXIF fields from an AndroidX [ExifInterface] instance. */
    fun extractFromExif(
        exif: ExifInterface,
        fallbackWidth: Int? = null,
        fallbackHeight: Int? = null,
    ): ImageInfo {
        val tags = HashMap<String, String?>(ALL_READ_TAGS.size)
        for (tag in ALL_READ_TAGS) {
            tags[tag] = exif.getAttribute(tag)
        }
        val latLong = runCatching { exif.latLong }.getOrNull()
        val rawAlt = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)
        val altitudeMeters = if (!rawAlt.isNullOrBlank()) {
            val alt = runCatching { exif.getAltitude(Double.NaN) }.getOrDefault(Double.NaN)
            alt.takeIf { it.isFinite() }
        } else {
            null
        }
        return extractFromTagMap(
            tags = tags,
            latLong = latLong,
            altitudeMeters = altitudeMeters,
            fallbackWidth = fallbackWidth,
            fallbackHeight = fallbackHeight,
        )
    }

    /**
     * Pure JVM extraction from an EXIF tag map (keyed by [ExifInterface] `TAG_*` names).
     * Omits all empty, blank, unknown, or zeroed/redacted tags.
     */
    fun extractFromTagMap(
        tags: Map<String, String?>,
        latLong: DoubleArray? = null,
        altitudeMeters: Double? = null,
        fallbackWidth: Int? = null,
        fallbackHeight: Int? = null,
    ): ImageInfo {
        fun attr(key: String): String? = ImageFormatters.sanitizeText(tags[key])
        fun attrInt(key: String): Int? = attr(key)?.toIntOrNull()

        val width = listOf(
            attrInt(ExifInterface.TAG_PIXEL_X_DIMENSION),
            attrInt(ExifInterface.TAG_IMAGE_WIDTH),
            fallbackWidth,
        ).firstOrNull { it != null && it > 0 }

        val height = listOf(
            attrInt(ExifInterface.TAG_PIXEL_Y_DIMENSION),
            attrInt(ExifInterface.TAG_IMAGE_LENGTH),
            fallbackHeight,
        ).firstOrNull { it != null && it > 0 }

        val orientation = attrInt(ExifInterface.TAG_ORIENTATION)
            ?.let { ImageFormatters.formatOrientation(it) }

        val colorSpace = attrInt(ExifInterface.TAG_COLOR_SPACE)
            ?.let { ImageFormatters.formatColorSpace(it) }

        val rawDate = attr(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: attr(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: attr(ExifInterface.TAG_DATETIME)
        val rawOffset = attr(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: attr(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            ?: attr(ExifInterface.TAG_OFFSET_TIME)
        val dateTaken = ImageFormatters.formatExifDateTime(rawDate, rawOffset)

        val camera = ImageFormatters.formatCameraName(
            make = attr(ExifInterface.TAG_MAKE),
            model = attr(ExifInterface.TAG_MODEL),
        )
        val lens = ImageFormatters.formatCameraName(
            make = attr(ExifInterface.TAG_LENS_MAKE),
            model = attr(ExifInterface.TAG_LENS_MODEL),
        )
        val software = attr(ExifInterface.TAG_SOFTWARE)

        val fNumber = ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_F_NUMBER))
            ?: ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_APERTURE_VALUE))
                ?.let { apex -> if (apex.isFinite() && apex in -10.0..30.0) Math.pow(2.0, apex / 2.0) else null }
        val aperture = fNumber?.let { ImageFormatters.formatAperture(it) }

        val exposureSec = ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_EXPOSURE_TIME))
            ?: ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_SHUTTER_SPEED_VALUE))
                ?.let { apex -> if (apex.isFinite() && apex in -20.0..30.0) Math.pow(2.0, -apex) else null }
        val shutterSpeed = exposureSec?.let { ImageFormatters.formatShutterSpeed(it) }

        val iso = ImageFormatters.formatIso(
            attr(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: attr(ExifInterface.TAG_ISO_SPEED),
        )

        val focalMm = ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_FOCAL_LENGTH))
        val focal35 = attrInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 }
        val focalLength = ImageFormatters.formatFocalLength(focalMm, focal35)

        val exposureBias = ImageFormatters.parseExifDouble(attr(ExifInterface.TAG_EXPOSURE_BIAS_VALUE))
            ?.let { ImageFormatters.formatExposureBias(it) }

        val exposureProgram = attrInt(ExifInterface.TAG_EXPOSURE_PROGRAM)
            ?.let { ImageFormatters.formatExposureProgram(it) }

        val meteringMode = attrInt(ExifInterface.TAG_METERING_MODE)
            ?.let { ImageFormatters.formatMeteringMode(it) }

        val flash = attrInt(ExifInterface.TAG_FLASH)
            ?.let { ImageFormatters.formatFlash(it) }

        val whiteBalance = attrInt(ExifInterface.TAG_WHITE_BALANCE)
            ?.let { ImageFormatters.formatWhiteBalance(it) }

        val location = if (latLong != null && latLong.size >= 2) {
            ImageFormatters.formatCoordinates(latLong[0], latLong[1])
        } else {
            null
        }
        // Only show altitude when coordinates are present (prevents showing bogus 0 m on FUSE-redacted photos).
        val altitude = if (location != null && altitudeMeters != null) {
            ImageFormatters.formatAltitude(altitudeMeters)
        } else {
            null
        }

        val artist = attr(ExifInterface.TAG_ARTIST)
        val copyright = attr(ExifInterface.TAG_COPYRIGHT)
        val description = attr(ExifInterface.TAG_IMAGE_DESCRIPTION)
            ?: attr(ExifInterface.TAG_USER_COMMENT)

        return ImageInfo(
            width = width,
            height = height,
            orientation = orientation,
            colorSpace = colorSpace,
            dateTaken = dateTaken,
            camera = camera,
            lens = lens,
            software = software,
            aperture = aperture,
            shutterSpeed = shutterSpeed,
            iso = iso,
            focalLength = focalLength,
            exposureBias = exposureBias,
            exposureProgram = exposureProgram,
            meteringMode = meteringMode,
            flash = flash,
            whiteBalance = whiteBalance,
            location = location,
            altitude = altitude,
            artist = artist,
            copyright = copyright,
            description = description,
        )
    }

    private fun decodeBoundsFromFile(file: File): Pair<Int?, Int?> = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        opts.outWidth.takeIf { it > 0 } to opts.outHeight.takeIf { it > 0 }
    }.getOrDefault(null to null)

    private fun decodeBoundsFromStream(input: InputStream): Pair<Int?, Int?> = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, opts)
        opts.outWidth.takeIf { it > 0 } to opts.outHeight.takeIf { it > 0 }
    }.getOrDefault(null to null)

    private fun readPrefix(input: InputStream, limit: Int): ByteArray {
        val buf = ByteArray(minOf(64 * 1024, limit.coerceAtLeast(1)))
        val out = java.io.ByteArrayOutputStream()
        var remaining = limit
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private val ALL_READ_TAGS = arrayOf(
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
    )
}

/** Pure formatting & sanitization helpers for [ImageInfo] fields (unit- and fuzz-tested on JVM). */
object ImageFormatters {
    private const val MAX_TEXT_LENGTH = 256

    /** Strips NUL/control characters, trims whitespace, and caps length to protect UI layout. */
    fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val sb = StringBuilder(minOf(raw.length, MAX_TEXT_LENGTH))
        for (c in raw) {
            if (c == '\u0000' || c == '\u007f' || c < ' ') continue
            sb.append(c)
            if (sb.length >= MAX_TEXT_LENGTH) break
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** Parses an EXIF decimal (`"1.8"`) or rational (`"18/10"`, `"-1/3"`) into a finite [Double]. */
    fun parseExifDouble(raw: String?): Double? {
        val s = sanitizeText(raw) ?: return null
        val slash = s.indexOf('/')
        val value = if (slash >= 0) {
            val num = s.substring(0, slash).trim().toDoubleOrNull() ?: return null
            val den = s.substring(slash + 1).trim().toDoubleOrNull() ?: return null
            if (!num.isFinite() || !den.isFinite() || den == 0.0) return null
            num / den
        } else {
            s.toDoubleOrNull() ?: return null
        }
        return value.takeIf { it.isFinite() }
    }

    /** Formats pixel dimensions with aspect ratio and megapixels (for images ≥ 0.1 MP). */
    fun formatDimensions(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val ratio = MediaFormatters.formatAspectRatio(width, height)
        val pixels = width.toLong() * height.toLong()
        val mp = if (pixels >= 100_000L) {
            val formatted = trimDecimal(pixels / 1_000_000.0, 1)
            "$formatted MP"
        } else {
            null
        }
        val details = listOfNotNull(ratio, mp).joinToString(" · ")
        return if (details.isNotEmpty()) "$width × $height ($details)" else "$width × $height"
    }

    /** Formats non-normal EXIF orientation values; returns null for normal/undefined. */
    fun formatOrientation(orientation: Int): String? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flipped horizontally"
        ExifInterface.ORIENTATION_ROTATE_180 -> "Rotated 180°"
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flipped vertically"
        ExifInterface.ORIENTATION_TRANSPOSE -> "Transposed"
        ExifInterface.ORIENTATION_ROTATE_90 -> "Rotated 90° CW"
        ExifInterface.ORIENTATION_TRANSVERSE -> "Transverse"
        ExifInterface.ORIENTATION_ROTATE_270 -> "Rotated 90° CCW"
        else -> null
    }

    /** Formats EXIF ColorSpace tag. */
    fun formatColorSpace(colorSpace: Int): String? = when (colorSpace) {
        ExifInterface.COLOR_SPACE_S_RGB -> "sRGB"
        2 -> "Adobe RGB"
        ExifInterface.COLOR_SPACE_UNCALIBRATED -> "Uncalibrated"
        else -> null
    }

    /** Formats EXIF `YYYY:MM:DD HH:MM:SS` (+ optional offset) as `YYYY-MM-DD HH:MM:SS[ offset]`. */
    fun formatExifDateTime(rawDate: String?, rawOffset: String? = null): String? {
        val cleaned = sanitizeText(rawDate) ?: return null
        if (cleaned.startsWith("0000:00:00") || cleaned.startsWith("0000-00-00")) return null
        val normalized = if (cleaned.length >= 10 && cleaned[4] == ':' && cleaned[7] == ':') {
            cleaned.substring(0, 4) + "-" + cleaned.substring(5, 7) + "-" + cleaned.substring(8)
        } else {
            cleaned
        }
        val offset = sanitizeText(rawOffset)?.takeIf {
            it.matches(Regex("""^[+-]\d{2}:\d{2}$"""))
        }
        return if (offset != null && !normalized.endsWith(offset)) "$normalized $offset" else normalized
    }

    /** Combines camera/lens make and model without repeating the brand prefix (e.g., `Canon EOS R5`). */
    fun formatCameraName(make: String?, model: String?): String? {
        val mk = sanitizeText(make)
        val md = sanitizeText(model)
        if (mk == null) return md
        if (md == null) return mk
        if (md.startsWith(mk, ignoreCase = true)) return md
        val firstWord = mk.substringBefore(' ')
        if (firstWord.length >= 3 && md.startsWith(firstWord, ignoreCase = true)) return md
        return "$mk $md"
    }

    /** Formats aperture f-number as `f/1.8`, `f/8`, etc. */
    fun formatAperture(fNumber: Double): String? {
        if (!fNumber.isFinite() || fNumber <= 0.0 || fNumber > 1000.0) return null
        return "f/${trimDecimal(fNumber, 1)}"
    }

    /** Formats exposure time in seconds as `1/250 s`, `0.8 s`, `2 s`, etc. */
    fun formatShutterSpeed(seconds: Double): String? {
        if (!seconds.isFinite() || seconds <= 0.0 || seconds < 1e-6 || seconds > 86_400.0) return null
        return when {
            seconds < 0.5 -> {
                val denom = (1.0 / seconds).roundToLong().coerceAtLeast(1L)
                "1/$denom s"
            }
            abs(seconds - seconds.roundToLong()) < 0.01 -> "${seconds.roundToLong()} s"
            else -> "${trimDecimal(seconds, 1)} s"
        }
    }

    /** Formats ISO sensitivity string (handling multi-value EXIF arrays like `"100, 100"`). */
    fun formatIso(raw: String?): String? {
        val cleaned = sanitizeText(raw) ?: return null
        val first = cleaned.substringBefore(',').trim().toIntOrNull() ?: return null
        return if (first > 0) "ISO $first" else null
    }

    /** Formats actual focal length and optional 35mm equivalent (omitting duplicate equiv). */
    fun formatFocalLength(focalMm: Double?, equiv35mm: Int?): String? {
        val validFocal = focalMm?.takeIf { it.isFinite() && it > 0.0 && it < 100_000.0 }
        val validEquiv = equiv35mm?.takeIf { it > 0 && it < 100_000 }
        return when {
            validFocal != null && validEquiv != null -> {
                val actualStr = "${trimDecimal(validFocal, 1)} mm"
                if (abs(validFocal - validEquiv) < 0.5) {
                    actualStr
                } else {
                    "$actualStr ($validEquiv mm equiv.)"
                }
            }
            validFocal != null -> "${trimDecimal(validFocal, 1)} mm"
            validEquiv != null -> "$validEquiv mm (35mm equiv.)"
            else -> null
        }
    }

    /** Formats exposure compensation in EV (e.g., `+0.33 EV`, `-1 EV`, `0 EV`). */
    fun formatExposureBias(ev: Double): String? {
        if (!ev.isFinite() || abs(ev) > 100.0) return null
        if (abs(ev) < 0.005) return "0 EV"
        val sign = if (ev > 0.0) "+" else "-"
        return "$sign${trimDecimal(abs(ev), 2)} EV"
    }

    /** Formats EXIF ExposureProgram enum; returns null for undefined/unknown. */
    fun formatExposureProgram(program: Int): String? = when (program) {
        ExifInterface.EXPOSURE_PROGRAM_MANUAL.toInt() -> "Manual"
        ExifInterface.EXPOSURE_PROGRAM_NORMAL.toInt() -> "Program AE"
        ExifInterface.EXPOSURE_PROGRAM_APERTURE_PRIORITY.toInt() -> "Aperture priority"
        ExifInterface.EXPOSURE_PROGRAM_SHUTTER_PRIORITY.toInt() -> "Shutter priority"
        ExifInterface.EXPOSURE_PROGRAM_CREATIVE.toInt() -> "Creative"
        ExifInterface.EXPOSURE_PROGRAM_ACTION.toInt() -> "Action"
        ExifInterface.EXPOSURE_PROGRAM_PORTRAIT_MODE.toInt() -> "Portrait"
        ExifInterface.EXPOSURE_PROGRAM_LANDSCAPE_MODE.toInt() -> "Landscape"
        else -> null
    }

    /** Formats EXIF MeteringMode enum; returns null for unknown/other. */
    fun formatMeteringMode(mode: Int): String? = when (mode) {
        ExifInterface.METERING_MODE_AVERAGE.toInt() -> "Average"
        ExifInterface.METERING_MODE_CENTER_WEIGHT_AVERAGE.toInt() -> "Center-weighted"
        ExifInterface.METERING_MODE_SPOT.toInt() -> "Spot"
        ExifInterface.METERING_MODE_MULTI_SPOT.toInt() -> "Multi-spot"
        ExifInterface.METERING_MODE_PATTERN.toInt() -> "Multi-segment"
        ExifInterface.METERING_MODE_PARTIAL.toInt() -> "Partial"
        else -> null
    }

    /** Formats EXIF Flash bitmask into a concise human-readable description. */
    fun formatFlash(flash: Int): String? {
        if (flash < 0) return null
        if ((flash and 0x20) != 0) return "No flash function"
        val fired = (flash and 0x01) != 0
        val mode = when ((flash ushr 3) and 0x03) {
            1 -> "compulsory"
            2 -> "suppressed"
            3 -> "auto"
            else -> null
        }
        val base = if (fired) "Fired" else "Did not fire"
        return if (mode != null) "$base ($mode)" else base
    }

    /** Formats EXIF WhiteBalance enum. */
    fun formatWhiteBalance(wb: Int): String? = when (wb) {
        ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "Auto"
        ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "Manual"
        else -> null
    }

    /** Formats valid, non-redacted GPS coordinates (rejecting NaN, out-of-range, and 0,0). */
    fun formatCoordinates(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        val ns = if (lat >= 0.0) "N" else "S"
        val ew = if (lon >= 0.0) "E" else "W"
        return "%.5f° %s, %.5f° %s".format(Locale.US, abs(lat), ns, abs(lon), ew)
    }

    /** Formats GPS altitude in meters. */
    fun formatAltitude(meters: Double): String? {
        if (!meters.isFinite() || abs(meters) > 100_000.0) return null
        val rounded = meters.roundToInt()
        return if (rounded < 0) "${abs(rounded)} m below sea level" else "$rounded m"
    }

    /** Formats a plain-text multi-line summary of populated EXIF fields for clipboard copy. */
    fun formatSummary(info: ImageInfo): String = buildString {
        if (info.width != null && info.height != null) {
            appendLine("Dimensions: ${formatDimensions(info.width, info.height)}")
        }
        info.orientation?.let { appendLine("Orientation: $it") }
        info.colorSpace?.let { appendLine("Color space: $it") }
        info.dateTaken?.let { appendLine("Date taken: $it") }
        info.camera?.let { appendLine("Camera: $it") }
        info.lens?.let { appendLine("Lens: $it") }
        info.software?.let { appendLine("Software: $it") }
        info.aperture?.let { appendLine("Aperture: $it") }
        info.shutterSpeed?.let { appendLine("Shutter speed: $it") }
        info.iso?.let { appendLine("ISO: $it") }
        info.focalLength?.let { appendLine("Focal length: $it") }
        info.exposureBias?.let { appendLine("Exposure bias: $it") }
        info.exposureProgram?.let { appendLine("Exposure program: $it") }
        info.meteringMode?.let { appendLine("Metering: $it") }
        info.flash?.let { appendLine("Flash: $it") }
        info.whiteBalance?.let { appendLine("White balance: $it") }
        info.location?.let { appendLine("Location: $it") }
        info.altitude?.let { appendLine("Altitude: $it") }
        info.artist?.let { appendLine("Artist: $it") }
        info.copyright?.let { appendLine("Copyright: $it") }
        info.description?.let { appendLine("Description: $it") }
    }.trimEnd()

    private fun trimDecimal(value: Double, decimals: Int): String =
        "%.${decimals}f".format(Locale.US, value).trimEnd('0').trimEnd('.')
}
