// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

class ImageInfoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isInspectableImageHandlesRasterRawAndExcludesSvgAndDirs() {
        assertTrue(ImageInspector.isInspectableImage(FileEntry("/a/photo.jpg", "photo.jpg", isDir = false)))
        assertTrue(ImageInspector.isInspectableImage(FileEntry("/a/photo.HEIC", "photo.HEIC", isDir = false)))
        assertTrue(ImageInspector.isInspectableImage(FileEntry("/a/shot.dng", "shot.dng", isDir = false)))
        assertTrue(ImageInspector.isInspectableImage(FileEntry("/a/shot.cr2", "shot.cr2", isDir = false)))
        assertTrue(ImageInspector.isInspectableImage(FileEntry("/a/scan.tiff", "scan.tiff", isDir = false)))
        assertFalse(ImageInspector.isInspectableImage(FileEntry("/a/icon.svg", "icon.svg", isDir = false)))
        assertFalse(ImageInspector.isInspectableImage(FileEntry("/a/dir", "dir", isDir = true)))
        assertFalse(ImageInspector.isInspectableImage(FileEntry("/a/clip.mp4", "clip.mp4", isDir = false)))
    }

    @Test
    fun isLocalDocumentAuthorityDistinguishesLocalProvidersFromCloudAndMtp() {
        assertTrue(ImageInspector.isLocalDocumentAuthority("com.android.externalstorage.documents"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("10@com.android.externalstorage.documents"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("com.android.providers.media.documents"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("com.android.providers.downloads.documents"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("com.android.virtualization.terminal.guestdocuments"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("10@com.google.android.virtualization.terminal.guestdocuments"))
        assertTrue(ImageInspector.isLocalDocumentAuthority("org.chromium.arc.crostini.documents"))

        assertFalse(ImageInspector.isLocalDocumentAuthority("com.google.android.apps.docs.storage"))
        assertFalse(ImageInspector.isLocalDocumentAuthority("10@com.google.android.apps.docs.storage"))
        assertFalse(ImageInspector.isLocalDocumentAuthority("com.android.mtp.documents"))
        assertFalse(ImageInspector.isLocalDocumentAuthority("com.google.ambient.streaming.documents"))
        assertFalse(ImageInspector.isLocalDocumentAuthority(""))
        assertFalse(ImageInspector.isLocalDocumentAuthority(null))
    }

    @Test
    fun formattersFormatDimensionsCameraExposureAndLocation() {
        assertEquals("4032 × 3024 (4:3 · 12.2 MP)", ImageFormatters.formatDimensions(4032, 3024))
        assertEquals("64 × 64 (1:1)", ImageFormatters.formatDimensions(64, 64))
        assertEquals("", ImageFormatters.formatDimensions(0, 100))

        assertNull(ImageFormatters.formatOrientation(ExifInterface.ORIENTATION_NORMAL))
        assertNull(ImageFormatters.formatOrientation(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals("Rotated 90° CW", ImageFormatters.formatOrientation(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals("Rotated 90° CCW", ImageFormatters.formatOrientation(ExifInterface.ORIENTATION_ROTATE_270))

        assertEquals("sRGB", ImageFormatters.formatColorSpace(ExifInterface.COLOR_SPACE_S_RGB))
        assertEquals("Adobe RGB", ImageFormatters.formatColorSpace(2))

        assertEquals("2026-06-15 14:22:10 -05:00", ImageFormatters.formatExifDateTime("2026:06:15 14:22:10", "-05:00"))
        assertNull(ImageFormatters.formatExifDateTime("0000:00:00 00:00:00", null))

        assertEquals("Google Pixel 9", ImageFormatters.formatCameraName("Google", "Pixel 9"))
        assertEquals("Canon EOS R5", ImageFormatters.formatCameraName("Canon", "Canon EOS R5"))
        assertEquals("NIKON Z 6", ImageFormatters.formatCameraName("NIKON CORPORATION", "NIKON Z 6"))

        assertEquals("f/1.8", ImageFormatters.formatAperture(1.8))
        assertEquals("f/8", ImageFormatters.formatAperture(8.0))
        assertNull(ImageFormatters.formatAperture(0.0))

        assertEquals("1/250 s", ImageFormatters.formatShutterSpeed(0.004))
        assertEquals("1/4000 s", ImageFormatters.formatShutterSpeed(0.00025))
        assertEquals("0.8 s", ImageFormatters.formatShutterSpeed(0.8))
        assertEquals("2 s", ImageFormatters.formatShutterSpeed(2.0))
        assertNull(ImageFormatters.formatShutterSpeed(0.0))

        assertEquals("ISO 100", ImageFormatters.formatIso("100"))
        assertEquals("ISO 640", ImageFormatters.formatIso("640, 640"))
        assertNull(ImageFormatters.formatIso("0"))

        assertEquals("6.8 mm (24 mm equiv.)", ImageFormatters.formatFocalLength(6.8, 24))
        assertEquals("50 mm", ImageFormatters.formatFocalLength(50.0, 50))
        assertEquals("85 mm", ImageFormatters.formatFocalLength(85.0, null))
        assertEquals("24 mm (35mm equiv.)", ImageFormatters.formatFocalLength(null, 24))

        assertEquals("+0.33 EV", ImageFormatters.formatExposureBias(1.0 / 3.0))
        assertEquals("-1 EV", ImageFormatters.formatExposureBias(-1.0))
        assertEquals("0 EV", ImageFormatters.formatExposureBias(0.0))

        assertEquals("Aperture priority", ImageFormatters.formatExposureProgram(ExifInterface.EXPOSURE_PROGRAM_APERTURE_PRIORITY.toInt()))
        assertNull(ImageFormatters.formatExposureProgram(ExifInterface.EXPOSURE_PROGRAM_NOT_DEFINED.toInt()))

        assertEquals("Multi-segment", ImageFormatters.formatMeteringMode(ExifInterface.METERING_MODE_PATTERN.toInt()))
        assertNull(ImageFormatters.formatMeteringMode(ExifInterface.METERING_MODE_UNKNOWN.toInt()))

        assertEquals("Did not fire", ImageFormatters.formatFlash(0))
        assertEquals("Fired", ImageFormatters.formatFlash(1))
        assertEquals("Did not fire (auto)", ImageFormatters.formatFlash(24))
        assertEquals("Fired (auto)", ImageFormatters.formatFlash(25))
        assertEquals("No flash function", ImageFormatters.formatFlash(32))

        assertEquals("Auto", ImageFormatters.formatWhiteBalance(ExifInterface.WHITE_BALANCE_AUTO.toInt()))
        assertEquals("Manual", ImageFormatters.formatWhiteBalance(ExifInterface.WHITE_BALANCE_MANUAL.toInt()))

        assertEquals("37.42200° N, 122.08410° W", ImageFormatters.formatCoordinates(37.422, -122.0841))
        assertNull(ImageFormatters.formatCoordinates(0.0, 0.0))
        assertNull(ImageFormatters.formatCoordinates(95.0, 10.0))
        assertEquals("42 m", ImageFormatters.formatAltitude(42.3))
        assertEquals("12 m below sea level", ImageFormatters.formatAltitude(-12.2))
    }

    @Test
    fun extractFromTagMapPopulatesPresentTagsAndOmitsEmptyAndRedactedOnes() {
        val tags = mapOf(
            ExifInterface.TAG_PIXEL_X_DIMENSION to "4080",
            ExifInterface.TAG_PIXEL_Y_DIMENSION to "3072",
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_NORMAL.toString(), // omitted
            ExifInterface.TAG_COLOR_SPACE to ExifInterface.COLOR_SPACE_S_RGB.toString(),
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:08:19 18:45:02",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "-05:00",
            ExifInterface.TAG_MAKE to "Google",
            ExifInterface.TAG_MODEL to "Pixel 9 Pro",
            ExifInterface.TAG_LENS_MODEL to "Pixel 9 Pro back camera 6.9mm f/1.68",
            ExifInterface.TAG_F_NUMBER to "1.7",
            ExifInterface.TAG_EXPOSURE_TIME to "0.002",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "50",
            ExifInterface.TAG_FOCAL_LENGTH to "690/100",
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "24",
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "0/100",
            ExifInterface.TAG_EXPOSURE_PROGRAM to ExifInterface.EXPOSURE_PROGRAM_NORMAL.toString(),
            ExifInterface.TAG_METERING_MODE to ExifInterface.METERING_MODE_CENTER_WEIGHT_AVERAGE.toString(),
            ExifInterface.TAG_FLASH to "16",
            ExifInterface.TAG_WHITE_BALANCE to ExifInterface.WHITE_BALANCE_AUTO.toString(),
            ExifInterface.TAG_ARTIST to "   ", // empty -> omitted
            ExifInterface.TAG_COPYRIGHT to "", // empty -> omitted
            ExifInterface.TAG_SOFTWARE to "HDR+ 1.0",
        )

        // Simulate FUSE-redacted [0.0, 0.0] latLong + 0.0 altitude: both must be omitted!
        val info = ImageInspector.extractFromTagMap(
            tags = tags,
            latLong = doubleArrayOf(0.0, 0.0),
            altitudeMeters = 0.0,
        )

        assertFalse(info.isEmpty)
        assertEquals(4080, info.width)
        assertEquals(3072, info.height)
        assertNull(info.orientation)
        assertEquals("sRGB", info.colorSpace)
        assertEquals("2026-08-19 18:45:02 -05:00", info.dateTaken)
        assertEquals("Google Pixel 9 Pro", info.camera)
        assertEquals("Pixel 9 Pro back camera 6.9mm f/1.68", info.lens)
        assertEquals("HDR+ 1.0", info.software)
        assertEquals("f/1.7", info.aperture)
        assertEquals("1/500 s", info.shutterSpeed)
        assertEquals("ISO 50", info.iso)
        assertEquals("6.9 mm (24 mm equiv.)", info.focalLength)
        assertEquals("0 EV", info.exposureBias)
        assertEquals("Program AE", info.exposureProgram)
        assertEquals("Center-weighted", info.meteringMode)
        assertEquals("Did not fire (suppressed)", info.flash)
        assertEquals("Auto", info.whiteBalance)
        assertNull(info.location)
        assertNull(info.altitude)
        assertNull(info.artist)
        assertNull(info.copyright)
        assertNull(info.description)

        val summary = ImageFormatters.formatSummary(info)
        assertTrue(summary.contains("Google Pixel 9 Pro"))
        assertTrue(summary.contains("1/500 s"))
        assertFalse(summary.contains("Location:"))
        assertFalse(summary.contains("Artist:"))
        assertFalse(summary.contains("Orientation:"))
    }

    @Test
    fun extractFromEmptyTagMapReturnsEmptyImageInfo() {
        val info = ImageInspector.extractFromTagMap(emptyMap())
        assertTrue(info.isEmpty)
        assertEquals("", ImageFormatters.formatSummary(info))
    }

    @Test
    fun inspectFileAndStreamReadRealExifFromJpeg() = runBlocking {
        val jpegBytes = buildMinimalJpegWithExif()
        val jpegFile = File(tmp.root, "photo.jpg").apply { writeBytes(jpegBytes) }

        val fromFile = ImageInspector.inspectFile(jpegFile)
        assertNotNull(fromFile)
        assertEquals(7728, fromFile!!.width)
        assertEquals(5152, fromFile.height)
        assertEquals("Fujifilm X-T5", fromFile.camera)
        assertEquals("f/2", fromFile.aperture)
        assertEquals("1/1000 s", fromFile.shutterSpeed)
        assertEquals("ISO 125", fromFile.iso)
        assertEquals("35 mm", fromFile.focalLength)
        assertEquals("2026-10-04 09:15:30", fromFile.dateTaken)
        // Unpopulated tags remain null
        assertNull(fromFile.lens)
        assertNull(fromFile.artist)
        assertNull(fromFile.location)

        val fromStream = ImageInspector.inspectStream(openInput = { ByteArrayInputStream(jpegBytes) })
        assertNotNull(fromStream)
        assertEquals("Fujifilm X-T5", fromStream!!.camera)
        assertEquals("1/1000 s", fromStream.shutterSpeed)
        assertEquals("f/2", fromStream.aperture)
    }

    @Test
    fun fuzzImageFormattersAndTagExtractorAgainstMalformedAndExtremeValues() {
        val rng = Random(0x45_58_49_46L)
        val specialDoubles = doubleArrayOf(
            0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 1e-9, 1e9, -90.0, 90.0, -180.0, 180.0,
        )
        val specialStrings = arrayOf(
            "", "   ", "\u0000\u0001\u0002", "0/0", "1/0", "-1/0", "NaN/1", "Infinity",
            "999999999999999999999/1", "0000:00:00 00:00:00", "A".repeat(10_000), "100, -5",
        )

        repeat(2_000) { iter ->
            val d1 = if (iter < specialDoubles.size) specialDoubles[iter] else Double.fromBits(rng.nextLong())
            val d2 = if (iter < specialDoubles.size) specialDoubles[(iter + 3) % specialDoubles.size] else Double.fromBits(rng.nextLong())
            val s1 = if (iter < specialStrings.size) specialStrings[iter] else "val_${rng.nextInt()}/${rng.nextInt(-2, 3)}"

            ImageFormatters.parseExifDouble(s1)?.let { assertTrue(it.isFinite()) }
            ImageFormatters.formatAperture(d1)
            ImageFormatters.formatShutterSpeed(d1)
            ImageFormatters.formatExposureBias(d1)
            ImageFormatters.formatFocalLength(d1, rng.nextInt(-10, 1000))
            ImageFormatters.formatCoordinates(d1, d2)
            ImageFormatters.formatAltitude(d1)
            ImageFormatters.formatDimensions(rng.nextInt(-100, 100_000), rng.nextInt(-100, 100_000))
            ImageFormatters.formatFlash(rng.nextInt(-5, 256))
            ImageFormatters.formatOrientation(rng.nextInt(-5, 20))
            ImageFormatters.formatMeteringMode(rng.nextInt(-5, 260))
            ImageFormatters.formatExposureProgram(rng.nextInt(-5, 20))
            ImageFormatters.formatExifDateTime(s1, s1)
            ImageFormatters.sanitizeText(s1)?.let { assertTrue(it.length <= 256) }

            val info = ImageInspector.extractFromTagMap(
                tags = mapOf(
                    ExifInterface.TAG_F_NUMBER to s1,
                    ExifInterface.TAG_EXPOSURE_TIME to s1,
                    ExifInterface.TAG_FOCAL_LENGTH to s1,
                    ExifInterface.TAG_MAKE to s1,
                    ExifInterface.TAG_MODEL to s1,
                    ExifInterface.TAG_DATETIME_ORIGINAL to s1,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to s1,
                    ExifInterface.TAG_IMAGE_DESCRIPTION to s1,
                ),
                latLong = doubleArrayOf(d1, d2),
                altitudeMeters = d1,
            )
            ImageFormatters.formatSummary(info)
        }
    }

    companion object {
        /** Builds a valid 1×1 JPEG containing a standard EXIF APP1 TIFF segment for real ExifInterface parsing. */
        private fun buildMinimalJpegWithExif(): ByteArray {
            val tiff = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // TIFF header: "II", magic 42, offset to IFD0 = 8
            tiff.put('I'.code.toByte()); tiff.put('I'.code.toByte())
            tiff.putShort(42)
            tiff.putInt(8)

            // Layout:
            // IFD0 at 8: 2 (count=3) + 3*12 (entries) + 4 (next=0) = 42 bytes -> ends at 50
            val makeBytes = "Fujifilm\u0000".toByteArray(Charsets.US_ASCII) // 9 bytes
            val modelBytes = "X-T5\u0000".toByteArray(Charsets.US_ASCII) // 5 bytes
            val makeOffset = 50
            val modelOffset = makeOffset + makeBytes.size // 59 -> pad to 60
            val exifIfdOffset = 66 // aligned
            // ExifIFD at 66: 2 (count=7) + 7*12 (84) + 4 (next=0) = 90 bytes -> ends at 156
            val expOffset = 156 // 8 bytes
            val fNumOffset = 164 // 8 bytes
            val dateBytes = "2026:10:04 09:15:30\u0000".toByteArray(Charsets.US_ASCII) // 20 bytes
            val dateOffset = 172 // 20 bytes -> ends at 192
            val focalOffset = 192 // 8 bytes -> ends at 200
            val tiffLen = 200

            fun entry(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
                tiff.putShort(tag.toShort())
                tiff.putShort(type.toShort())
                tiff.putInt(count)
                tiff.putInt(valueOrOffset)
            }

            // IFD0 (sorted by tag ID)
            tiff.position(8)
            tiff.putShort(3)
            entry(0x010F, 2, makeBytes.size, makeOffset) // Make
            entry(0x0110, 2, modelBytes.size, modelOffset) // Model
            entry(0x8769, 4, 1, exifIfdOffset) // ExifIFDPointer
            tiff.putInt(0)

            tiff.position(makeOffset); tiff.put(makeBytes)
            tiff.position(modelOffset); tiff.put(modelBytes)

            // ExifIFD (sorted by tag ID)
            tiff.position(exifIfdOffset)
            tiff.putShort(7)
            entry(0x829A, 5, 1, expOffset) // ExposureTime = 1/1000
            entry(0x829D, 5, 1, fNumOffset) // FNumber = 20/10
            entry(0x8827, 3, 1, 125) // PhotographicSensitivity = 125
            entry(0x9003, 2, dateBytes.size, dateOffset) // DateTimeOriginal
            entry(0x920A, 5, 1, focalOffset) // FocalLength = 35/1
            entry(0xA002, 4, 1, 7728) // PixelXDimension
            entry(0xA003, 4, 1, 5152) // PixelYDimension
            tiff.putInt(0)

            tiff.position(expOffset); tiff.putInt(1); tiff.putInt(1000)
            tiff.position(fNumOffset); tiff.putInt(20); tiff.putInt(10)
            tiff.position(dateOffset); tiff.put(dateBytes)
            tiff.position(focalOffset); tiff.putInt(35); tiff.putInt(1)

            val exifPayload = ByteArray(6 + tiffLen)
            System.arraycopy("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII), 0, exifPayload, 0, 6)
            System.arraycopy(tiff.array(), 0, exifPayload, 6, tiffLen)

            val app1Len = exifPayload.size + 2
            val out = java.io.ByteArrayOutputStream()
            // SOI
            out.write(0xFF); out.write(0xD8)
            // APP1 EXIF
            out.write(0xFF); out.write(0xE1)
            out.write((app1Len ushr 8) and 0xFF); out.write(app1Len and 0xFF)
            out.write(exifPayload)
            // Remainder of minimal 1x1 JPEG after SOI
            out.write(MINIMAL_1X1_JPEG, 2, MINIMAL_1X1_JPEG.size - 2)
            return out.toByteArray()
        }

        /** Valid minimal 1×1 white baseline JPEG (no EXIF) for real ExifInterface round-trip tests. */
        private val MINIMAL_1X1_JPEG = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00, 0x08, 0x06,
            0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B,
            0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27,
            0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01,
            0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
            0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10, 0x00, 0x02,
            0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D, 0x01, 0x02, 0x03, 0x00,
            0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32,
            0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52,
            0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19,
            0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46,
            0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(),
            0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(),
            0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(),
            0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(),
            0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(),
            0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(),
            0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(),
            0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(),
            0xDA.toByte(), 0xE1.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(),
            0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x7B, 0x94.toByte(), 0x11,
            0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
        )
    }
}
