// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** Verification state of an APK's signing certificate(s). */
enum class ApkSignatureStatus {
    Verified,
    UnsignedOrUnverified,
    Unknown,
}

/** Signing certificate fingerprint and optional X.509 subject parsed from an APK signature block. */
data class ApkCertificateInfo(
    val sha256Fingerprint: String,
    val subject: String? = null,
)

/** Non-empty package, SDK, ABI, and certificate metadata extracted from an Android `.apk` file. */
data class ApkInfo(
    val appName: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val supportedAbis: List<String> = emptyList(),
    val zipInspected: Boolean = false,
    val certificates: List<ApkCertificateInfo> = emptyList(),
    val signatureStatus: ApkSignatureStatus = ApkSignatureStatus.Unknown,
) {
    val isEmpty: Boolean
        get() = appName == null &&
            packageName == null &&
            versionName == null &&
            versionCode == null &&
            minSdk == null &&
            targetSdk == null &&
            !zipInspected &&
            certificates.isEmpty()
}

/**
 * Inspects Android `.apk` files using only platform [PackageManager], [ZipFile], and JDK
 * [MessageDigest] / [CertificateFactory] APIs — no third-party libraries or native binaries.
 */
object ApkInspector {
    private const val INSPECT_TIMEOUT_MS = 10_000L
    internal const val MAX_ZIP_ENTRIES = 50_000
    internal const val LOCAL_DOC_STAGE_MAX_BYTES = 128L * 1024L * 1024L
    private const val MAX_ABI_LEN = 32
    private const val MAX_TEXT_LEN = 256

    private val ABI_ORDER = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "armeabi",
        "x86_64",
        "x86",
        "riscv64",
        "mips64",
        "mips",
    )

    private val ABI_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

    /** True when [entry] is a regular `.apk` file eligible for package inspection. */
    fun isInspectableApk(entry: FileEntry): Boolean {
        if (entry.isDir || entry.isServer || entry.isShare) return false
        return entry.extension.equals("apk", ignoreCase = true)
    }

    /**
     * Inspects a local `.apk` [file] using [PackageManager.getPackageArchiveInfo] (for manifest
     * metadata and v1/v2/v3/v3.1 signing certificates) plus a zero-decompression ZIP central
     * directory scan (for native `lib/<abi>/<name>.so` ABIs).
     */
    suspend fun inspectFile(context: Context, file: File): ApkInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        if (!file.isFile || !file.canRead()) return@withTimeout null
        val pm = context.applicationContext.packageManager
        val path = file.path

        val (zipOk, abis) = inspectZipAbis(file)
        coroutineContext.ensureActive()

        val pkgWithSig = queryPackageArchiveInfo(pm, path, withSignatures = true)
        val pkgInfo: PackageInfo
        val certs: List<ApkCertificateInfo>
        val sigStatus: ApkSignatureStatus

        if (pkgWithSig != null) {
            pkgInfo = pkgWithSig
            val rawSigners = extractSignerByteArrays(pkgWithSig)
            certs = rawSigners.mapNotNull { parseCertificateInfo(it) }.distinctBy { it.sha256Fingerprint }
            sigStatus = if (certs.isNotEmpty()) {
                ApkSignatureStatus.Verified
            } else {
                ApkSignatureStatus.UnsignedOrUnverified
            }
        } else {
            val fallback = queryPackageArchiveInfo(pm, path, withSignatures = false)
                ?: return@withTimeout null
            pkgInfo = fallback
            certs = emptyList()
            sigStatus = ApkSignatureStatus.UnsignedOrUnverified
        }

        val appInfo = pkgInfo.applicationInfo
        val pkgName = sanitizeText(pkgInfo.packageName)
        val appLabel = appInfo?.let { ai ->
            runCatching {
                ai.sourceDir = path
                ai.publicSourceDir = path
                ai.loadLabel(pm).toString()
            }.getOrNull()
        }?.let(::sanitizeText)?.takeUnless { it == pkgName }

        val versionName = sanitizeText(pkgInfo.versionName)
        val versionCode = pkgInfo.longVersionCode.takeIf { it > 0L }
        val minSdk = appInfo?.minSdkVersion?.takeIf { it > 0 }
        val targetSdk = appInfo?.targetSdkVersion?.takeIf { it > 0 }

        ApkInfo(
            appName = appLabel,
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            supportedAbis = abis,
            zipInspected = zipOk,
            certificates = certs,
            signatureStatus = sigStatus,
        ).takeUnless { it.isEmpty }
    }

    /**
     * Stages a local-device `DocumentsProvider` stream (capped at [maxBytes]) to a temporary cache
     * file for [PackageManager.getPackageArchiveInfo], deleting the temporary file immediately
     * afterwards. Returns null if the stream exceeds [maxBytes] or is not a valid APK.
     */
    suspend fun inspectStagedStream(
        context: Context,
        openInput: () -> InputStream,
        maxBytes: Long = LOCAL_DOC_STAGE_MAX_BYTES,
    ): ApkInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val dir = File(context.applicationContext.cacheDir, "apk-inspect").apply { mkdirs() }
        val tmp = File.createTempFile("inspect-", ".apk", dir)
        try {
            val copied = openInput().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@use false
                        out.write(buf, 0, n)
                    }
                    total > 0L
                }
            }
            if (!copied) return@withTimeout null
            inspectFile(context, tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Reads ZIP entry names from [file] without decompressing entry payloads and extracts any
     * Android native library ABIs (`lib/<abi>/<name>.so`). Returns `(zipValid, sortedAbis)`.
     */
    fun inspectZipAbis(file: File, maxEntries: Int = MAX_ZIP_ENTRIES): Pair<Boolean, List<String>> {
        return runCatching {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }
                true to extractAbisFromEntryNames(names, maxEntries)
            }
        }.getOrElse { false to emptyList() }
    }

    /**
     * Pure JVM extraction of Android ABIs from ZIP entry paths (`lib/<abi>/<file>.so`).
     * Caps iteration at [maxEntries] to guard against zip-directory flooding.
     */
    fun extractAbisFromEntryNames(
        entryNames: Sequence<String>,
        maxEntries: Int = MAX_ZIP_ENTRIES,
    ): List<String> {
        val found = LinkedHashSet<String>()
        var count = 0
        for (raw in entryNames) {
            if (count++ >= maxEntries) break
            if (!raw.startsWith("lib/") || !raw.endsWith(".so") || raw.endsWith("/")) continue
            if (raw.contains('\u0000') || raw.contains('\\')) continue
            val firstSlash = raw.indexOf('/')
            val secondSlash = raw.indexOf('/', firstSlash + 1)
            if (secondSlash <= firstSlash + 1) continue
            // Must be strictly 3 segments: lib/<abi>/<libname>.so
            if (raw.indexOf('/', secondSlash + 1) >= 0) continue
            val abi = raw.substring(firstSlash + 1, secondSlash)
            val libName = raw.substring(secondSlash + 1)
            if (abi.isEmpty() || abi.length > MAX_ABI_LEN || abi == "." || abi == "..") continue
            if (!ABI_NAME_REGEX.matches(abi)) continue
            if (libName.length <= 3 || libName == ".so" || libName == "..") continue
            found.add(abi)
        }
        return sortAbis(found)
    }

    /** Formats the SHA-256 digest of DER-encoded certificate bytes as colon-separated uppercase hex. */
    fun sha256Fingerprint(certBytes: ByteArray): String? {
        if (certBytes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return buildString(digest.size * 3 - 1) {
            for (i in digest.indices) {
                if (i > 0) append(':')
                val b = digest[i].toInt() and 0xFF
                append(HEX_CHARS[b ushr 4])
                append(HEX_CHARS[b and 0x0F])
            }
        }
    }

    /**
     * Extracts an [ApkCertificateInfo] from raw DER-encoded X.509 certificate bytes.
     * Always computes the SHA-256 fingerprint for non-empty input; populates [ApkCertificateInfo.subject]
     * when the bytes parse as a valid X.509 certificate.
     */
    fun parseCertificateInfo(certBytes: ByteArray): ApkCertificateInfo? {
        val fingerprint = sha256Fingerprint(certBytes) ?: return null
        val subject = runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as? X509Certificate
            cert?.subjectX500Principal?.name
        }.getOrNull()?.let(::sanitizeText)
        return ApkCertificateInfo(sha256Fingerprint = fingerprint, subject = subject)
    }

    private fun queryPackageArchiveInfo(
        pm: PackageManager,
        archivePath: String,
        withSignatures: Boolean,
    ): PackageInfo? = runCatching {
        val flags = if (withSignatures) PackageManager.GET_SIGNING_CERTIFICATES else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, flags)
        }
    }.getOrNull()

    private fun extractSignerByteArrays(pkgInfo: PackageInfo): List<ByteArray> {
        val signingInfo = pkgInfo.signingInfo ?: return emptyList()
        val signers = signingInfo.apkContentsSigners?.takeIf { it.isNotEmpty() }
            ?: signingInfo.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) }
            ?: return emptyList()
        return signers.mapNotNull { sig -> runCatching { sig.toByteArray() }.getOrNull() }
    }

    private fun sortAbis(abis: Collection<String>): List<String> =
        abis.sortedWith(
            compareBy<String>(
                { ABI_ORDER.indexOf(it).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                { it.lowercase(Locale.US) },
            ),
        )

    internal fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(raw.length.coerceAtMost(MAX_TEXT_LEN)) {
            var prevSpace = false
            for (ch in raw) {
                if (length >= MAX_TEXT_LEN) break
                val isControl = ch.code < 0x20 || ch.code == 0x7F
                if (isControl || ch.isWhitespace()) {
                    if (isNotEmpty() && !prevSpace) {
                        append(' ')
                        prevSpace = true
                    }
                } else {
                    append(ch)
                    prevSpace = false
                }
            }
        }.trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )
}

/** Pure JVM formatters for [ApkInfo] display rows and clipboard summary export. */
object ApkFormatters {
    private const val MAX_REASONABLE_SDK = 1000

    /** Formats version name and version code, e.g. `"3.0 (20)"`. */
    fun formatVersion(versionName: String?, versionCode: Long?): String? {
        val name = ApkInspector.sanitizeText(versionName)
        val code = versionCode?.takeIf { it > 0L }
        return when {
            name != null && code != null -> "$name ($code)"
            name != null -> name
            code != null -> code.toString()
            else -> null
        }
    }

    /** Formats an Android API level with its platform release name when known, e.g. `"API 36 (Android 16)"`. */
    fun formatSdk(apiLevel: Int?): String? {
        if (apiLevel == null || apiLevel <= 0 || apiLevel > MAX_REASONABLE_SDK) return null
        val release = when (apiLevel) {
            1 -> "Android 1.0"
            2 -> "Android 1.1"
            3 -> "Android 1.5"
            4 -> "Android 1.6"
            5 -> "Android 2.0"
            6 -> "Android 2.0.1"
            7 -> "Android 2.1"
            8 -> "Android 2.2"
            9 -> "Android 2.3"
            10 -> "Android 2.3.3"
            11 -> "Android 3.0"
            12 -> "Android 3.1"
            13 -> "Android 3.2"
            14 -> "Android 4.0"
            15 -> "Android 4.0.3"
            16 -> "Android 4.1"
            17 -> "Android 4.2"
            18 -> "Android 4.3"
            19 -> "Android 4.4"
            20 -> "Android 4.4W"
            21 -> "Android 5.0"
            22 -> "Android 5.1"
            23 -> "Android 6.0"
            24 -> "Android 7.0"
            25 -> "Android 7.1"
            26 -> "Android 8.0"
            27 -> "Android 8.1"
            28 -> "Android 9"
            29 -> "Android 10"
            30 -> "Android 11"
            31 -> "Android 12"
            32 -> "Android 12L"
            33 -> "Android 13"
            34 -> "Android 14"
            35 -> "Android 15"
            36 -> "Android 16"
            37 -> "Android 17"
            else -> null
        }
        return if (release != null) "API $apiLevel ($release)" else "API $apiLevel"
    }

    /** Formats the list of supported native ABIs, or `"Universal (no native libs)"` for pure JVM APKs. */
    fun formatAbis(abis: List<String>, zipInspected: Boolean): String? = when {
        abis.isNotEmpty() -> abis.joinToString(", ")
        zipInspected -> "Universal (no native libs)"
        else -> null
    }

    /** Formats a plain-text multi-line summary of populated APK fields for clipboard copy. */
    fun formatSummary(info: ApkInfo): String = buildString {
        info.appName?.let { appendLine("App: $it") }
        info.packageName?.let { appendLine("Package: $it") }
        formatVersion(info.versionName, info.versionCode)?.let { appendLine("Version: $it") }
        formatSdk(info.targetSdk)?.let { appendLine("Target SDK: $it") }
        formatSdk(info.minSdk)?.let { appendLine("Min SDK: $it") }
        formatAbis(info.supportedAbis, info.zipInspected)?.let { appendLine("ABIs: $it") }
        if (info.certificates.isNotEmpty()) {
            info.certificates.forEachIndexed { idx, cert ->
                val suffix = if (info.certificates.size > 1) " #${idx + 1}" else ""
                appendLine("Cert$suffix SHA-256: ${cert.sha256Fingerprint}")
                cert.subject?.let { appendLine("Signer$suffix: $it") }
            }
        } else if (info.signatureStatus == ApkSignatureStatus.UnsignedOrUnverified) {
            appendLine("Signature: Unsigned or unverified")
        }
    }.trimEnd()
}
