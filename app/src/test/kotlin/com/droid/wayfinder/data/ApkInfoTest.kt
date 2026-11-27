// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ApkInfoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isInspectableApkMatchesApkFilesOnly() {
        assertTrue(ApkInspector.isInspectableApk(FileEntry("/a/app.apk", "app.apk", isDir = false)))
        assertTrue(ApkInspector.isInspectableApk(FileEntry("/a/APP-RELEASE.APK", "APP-RELEASE.APK", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/bundle.apks", "bundle.apks", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/bundle.xapk", "bundle.xapk", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/archive.zip", "archive.zip", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/folder.apk", "folder.apk", isDir = true)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("smb://host", "host.apk", isDir = false, isServer = true)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("smb://host/share", "share.apk", isDir = false, isShare = true)))
    }

    @Test
    fun extractAbisFromEntryNamesSortsDeduplicatesAndRejectsMalformedEntries() {
        val entries = sequenceOf(
            "AndroidManifest.xml",
            "classes.dex",
            "lib/x86_64/libfoo.so",
            "lib/arm64-v8a/libfoo.so",
            "lib/arm64-v8a/libbar.so",
            "lib/armeabi-v7a/libfoo.so",
            "lib/riscv64/libfoo.so",
            // Must be ignored:
            "lib/x86/nested/libbad.so",
            "lib/x86/not_a_lib.txt",
            "lib/x86/.so",
            "lib/x86/",
            "lib/../libevil.so",
            "lib/./libevil.so",
            "lib/bad abi/libfoo.so",
            "lib/bad\u0000abi/libfoo.so",
            "lib/bad\\abi/libfoo.so",
            "lib/${"a".repeat(33)}/libfoo.so",
            "assets/lib/mips/libasset.so",
        )
        assertEquals(
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64"),
            ApkInspector.extractAbisFromEntryNames(entries),
        )
    }

    @Test
    fun extractAbisCapsScannedEntryCount() {
        val flooded = (1..200).asSequence().map { "res/drawable/img_$it.png" } +
            sequenceOf("lib/arm64-v8a/liblate.so")
        assertEquals(emptyList<String>(), ApkInspector.extractAbisFromEntryNames(flooded, maxEntries = 50))
        assertEquals(listOf("arm64-v8a"), ApkInspector.extractAbisFromEntryNames(flooded, maxEntries = 250))
    }

    @Test
    fun inspectZipAbisReadsRealZipFilesAndHandlesCorruptInputs() {
        val nativeApk = tmp.newFile("native.apk")
        ZipOutputStream(nativeApk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(byteArrayOf(1, 2)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/x86_64/libnative.so")); zos.write(byteArrayOf(0)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so")); zos.write(byteArrayOf(0)); zos.closeEntry()
        }
        assertEquals(true to listOf("arm64-v8a", "x86_64"), ApkInspector.inspectZipAbis(nativeApk))

        val universalApk = tmp.newFile("universal.apk")
        ZipOutputStream(universalApk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(byteArrayOf(1)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex")); zos.write(byteArrayOf(2)); zos.closeEntry()
        }
        assertEquals(true to emptyList<String>(), ApkInspector.inspectZipAbis(universalApk))

        val corrupt = tmp.newFile("corrupt.apk").apply { writeBytes("not a zip archive".toByteArray()) }
        assertEquals(false to emptyList<String>(), ApkInspector.inspectZipAbis(corrupt))
        assertEquals(false to emptyList<String>(), ApkInspector.inspectZipAbis(File(tmp.root, "missing.apk")))
    }

    @Test
    fun sha256FingerprintAndCertificateParsingWorkOnValidAndInvalidDerBytes() {
        assertNull(ApkInspector.sha256Fingerprint(ByteArray(0)))
        assertNull(ApkInspector.parseCertificateInfo(ByteArray(0)))

        // Known SHA-256 test vector for ASCII "abc".
        val abcFp = ApkInspector.sha256Fingerprint("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            abcFp,
        )

        // Non-X.509 bytes still yield their SHA-256 fingerprint with a null subject.
        val rawParsed = ApkInspector.parseCertificateInfo("abc".toByteArray(Charsets.UTF_8))
        assertNotNull(rawParsed)
        assertEquals(abcFp, rawParsed!!.sha256Fingerprint)
        assertNull(rawParsed.subject)

        // Public self-signed test X.509 DER certificate (CN=Wayfinder Test, O=Wayfinder).
        val derBytes = Base64.getMimeDecoder().decode(TEST_X509_CERT_BASE64)
        val certInfo = ApkInspector.parseCertificateInfo(derBytes)
        assertNotNull(certInfo)
        assertEquals(TEST_X509_CERT_SHA256, certInfo!!.sha256Fingerprint)
        assertEquals("CN=Wayfinder Test,O=Wayfinder", certInfo.subject)
    }

    @Test
    fun formattersFormatVersionSdkAbisAndSummary() {
        assertEquals("3.0 (20)", ApkFormatters.formatVersion("3.0", 20L))
        assertEquals("3.0", ApkFormatters.formatVersion("3.0", null))
        assertEquals("3.0", ApkFormatters.formatVersion("3.0", 0L))
        assertEquals("20", ApkFormatters.formatVersion(null, 20L))
        assertEquals("20", ApkFormatters.formatVersion("   ", 20L))
        assertNull(ApkFormatters.formatVersion(null, null))
        assertNull(ApkFormatters.formatVersion("", -1L))

        assertEquals("API 30 (Android 11)", ApkFormatters.formatSdk(30))
        assertEquals("API 34 (Android 14)", ApkFormatters.formatSdk(34))
        assertEquals("API 35 (Android 15)", ApkFormatters.formatSdk(35))
        assertEquals("API 36 (Android 16)", ApkFormatters.formatSdk(36))
        assertEquals("API 99", ApkFormatters.formatSdk(99))
        assertNull(ApkFormatters.formatSdk(0))
        assertNull(ApkFormatters.formatSdk(-5))
        assertNull(ApkFormatters.formatSdk(1001))
        assertNull(ApkFormatters.formatSdk(null))

        assertEquals("arm64-v8a, x86_64", ApkFormatters.formatAbis(listOf("arm64-v8a", "x86_64"), zipInspected = true))
        assertEquals("Universal (no native libs)", ApkFormatters.formatAbis(emptyList(), zipInspected = true))
        assertNull(ApkFormatters.formatAbis(emptyList(), zipInspected = false))

        val signed = ApkInfo(
            appName = "Wayfinder",
            packageName = "com.droid.wayfinder",
            versionName = "3.0",
            versionCode = 20L,
            minSdk = 30,
            targetSdk = 36,
            supportedAbis = emptyList(),
            zipInspected = true,
            certificates = listOf(ApkCertificateInfo(TEST_X509_CERT_SHA256, "CN=Wayfinder Test,O=Wayfinder")),
            signatureStatus = ApkSignatureStatus.Verified,
        )
        assertFalse(signed.isEmpty)
        val summary = ApkFormatters.formatSummary(signed)
        assertTrue(summary.contains("App: Wayfinder"))
        assertTrue(summary.contains("Package: com.droid.wayfinder"))
        assertTrue(summary.contains("Version: 3.0 (20)"))
        assertTrue(summary.contains("Target SDK: API 36 (Android 16)"))
        assertTrue(summary.contains("Min SDK: API 30 (Android 11)"))
        assertTrue(summary.contains("ABIs: Universal (no native libs)"))
        assertTrue(summary.contains("Cert SHA-256: $TEST_X509_CERT_SHA256"))
        assertTrue(summary.contains("Signer: CN=Wayfinder Test,O=Wayfinder"))

        val unsigned = ApkInfo(
            packageName = "com.example.unsigned",
            signatureStatus = ApkSignatureStatus.UnsignedOrUnverified,
        )
        assertTrue(ApkFormatters.formatSummary(unsigned).contains("Signature: Unsigned or unverified"))

        assertTrue(ApkInfo().isEmpty)
        assertEquals("", ApkFormatters.formatSummary(ApkInfo()))
    }

    @Test
    fun fuzzUntrustedApkEntryNamesCertificatesAndFormatters() {
        val rng = Random(20261126L)
        val fingerprintPattern = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._-\\\u0000\r\n\t :!@#$"

        fun randString(maxLen: Int): String {
            val len = rng.nextInt(0, maxLen + 1)
            return buildString(len) { repeat(len) { append(alphabet[rng.nextInt(alphabet.length)]) } }
        }

        // Fuzz ABI extraction across adversarial ZIP entry paths.
        repeat(1_500) {
            val entries = List(rng.nextInt(0, 12)) {
                when (rng.nextInt(4)) {
                    0 -> "lib/${randString(40)}/${randString(20)}.so"
                    1 -> "lib/${randString(12)}/${randString(12)}"
                    else -> randString(64)
                }
            }
            val abis = ApkInspector.extractAbisFromEntryNames(entries.asSequence())
            for (abi in abis) {
                assertTrue(abi.length in 1..32)
                assertFalse(abi.contains('/'))
                assertFalse(abi.contains('\\'))
                assertFalse(abi.contains('\u0000'))
                assertTrue(abi != "." && abi != "..")
            }
        }

        // Fuzz certificate parsing and formatting on arbitrary bytes and values.
        repeat(1_000) {
            val len = rng.nextInt(0, 512)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val parsed = ApkInspector.parseCertificateInfo(bytes)
            if (len == 0) {
                assertNull(parsed)
            } else {
                assertNotNull(parsed)
                assertTrue(
                    "Invalid SHA-256 fingerprint format: ${parsed!!.sha256Fingerprint}",
                    fingerprintPattern.matches(parsed.sha256Fingerprint),
                )
            }

            val clean = ApkInspector.sanitizeText(randString(400))
            if (clean != null) {
                assertTrue(clean.length <= 256)
                assertFalse(clean.any { it.code < 0x20 || it.code == 0x7F })
            }

            val sdk = ApkFormatters.formatSdk(rng.nextInt(-100, 1200))
            if (sdk != null) assertTrue(sdk.startsWith("API "))
            ApkFormatters.formatVersion(randString(80), rng.nextLong(-10L, 1_000_000L))
        }
    }

    companion object {
        // Public self-signed test X.509 certificate only (no private key).
        private const val TEST_X509_CERT_SHA256 =
            "C5:16:82:AA:CF:D3:89:3D:37:0A:0E:C4:61:66:4C:96:69:39:1C:58:ED:4A:20:47:B5:BB:AB:3E:B6:3B:2C:CF"
        private const val TEST_X509_CERT_BASE64 =
            "MIIC/TCCAeWgAwIBAgIIGhZFR/6W1jcwDQYJKoZIhvcNAQEMBQAwLTESMBAGA1UEChMJV2F5Zmlu" +
                "ZGVyMRcwFQYDVQQDEw5XYXlmaW5kZXIgVGVzdDAeFw0yNjExMjcwMTQ2MzhaFw0zNjExMjQwMTQ2" +
                "MzhaMC0xEjAQBgNVBAoTCVdheWZpbmRlcjEXMBUGA1UEAxMOV2F5ZmluZGVyIFRlc3QwggEiMA0G" +
                "CSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDeYPm09CI0Y9O4NPmeA2nwHSH65XtGiKUUBHIabVuZ" +
                "K2NkNkxXtoejh4Hbqz4Zas9A2AnTLIHjy0pg08RsjZjngw7nKf3yjY4hYZkzL/IndMlgcSAdJ6Ks" +
                "NaQj1mEdrit5NPkZJ/A57OG/ov0Cy771E3OXC5dne9Prq/5Oqdqucf6M1i6uJa5Bm3EfSs/nm/zO" +
                "aEdt75AKPntrd+Vqz/jJEnZGGoX3aTV+fJ2MnDyrNe8QMetlLMzXM0Sc6dowO6GQ7JIzAukK8dtP" +
                "ZzypKYbEvl8XRt0kmJuRp2QtpHSUyrSn/uzJxgepfBQ9nY7qQhDiF5DIudNyFTC5DMveOBS5AgMB" +
                "AAGjITAfMB0GA1UdDgQWBBRX+vbyb5ycUUZw2pMKcK6hn7QzqDANBgkqhkiG9w0BAQwFAAOCAQEA" +
                "w5m+8HnsDaMRT7f9809v00tLTpZVn67dLtfufCrHSVrb3yMzutNOn7eqoWXFCOC6Lq7FQWd4FYZL" +
                "TUFa26H2zpL+52lq3V0hKvDTqghYulfoIz6ay4vcBB8cwS5xI5P+NCPVoNlKWJG+jkm8XNGz8vzR" +
                "lHHhcNYLviU1I3ZZL9OuR2XNaBg7f+wcb9VPSinJDK0mfOGOwn1UKbscxLd2QCQH+hYnqOc3SxW5" +
                "YxrEG/N/JV+WWJ5Lao/rqq3+W05De4JoGGiPfHPgHHLw4y3/uv1z6NLZgdWVug2anBN1CkOjzFJq" +
                "2ahQeOerfjWJkiRSpttUpyA1GUEJAAAMwUpOEQ=="
    }
}
