// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import org.junit.Assert.fail
import kotlin.random.Random

/**
 * Tiny deterministic fuzzing kit (JUnit-driven, no instrumentation): a corpus of hostile inputs plus seeded random
 * generation and mutation. Every failure reports the seed / iteration / input so it can be replayed.
 *
 * Tunables (system properties, forwarded by Gradle when set): `fuzz.seed` (default fixed) and `fuzz.scale`
 * (multiplies iteration counts; default 1).
 */
object FuzzKit {
    val seed: Long = System.getProperty("fuzz.seed")?.toLongOrNull() ?: 0x5EC0_F022L
    private val scale: Double = System.getProperty("fuzz.scale")?.toDoubleOrNull() ?: 1.0

    /** Total iterations executed by all fuzz targets in this JVM (reported in the security review). */
    @Volatile var executed = 0L

    fun iterations(n: Int): Int = (n * scale).toInt().coerceAtLeast(1)

    /** Hand-written nasty inputs: traversal, encodings, separators, control chars, URL / path syntax, huge strings. */
    val corpus: List<String> = listOf(
        "", " ", ".", "..", "...", "./", "../", "..\\", "/..", "/../", "/./", "//", "///", "\\", "\\\\", "\\\\host\\share\\..\\..",
        "\u0000", "a\u0000b", "../\u0000", "\r\n", "a\r\nDELE /etc", "\n", "\t", "S\t", "T\tUrgent\t", "S\t/a\tb", "T\tNope\t/x",
        "%", "%2", "%zz", "%2e%2e", "%2E%2E%2F", "%2F", "..%2F..%2Fetc%2Fpasswd", "%c0%af", "%00", "%0d%0a", "+", "%2B",
        "smb://", "smb:///", "smb://host", "smb://host/share/../../x", "smb://h/s/a%2Fb", "SMB://Host/Share",
        "dav://", "davs://[::1", "davs://[::1]:99999/x", "dav://u:p@h:0/", "http://a@b@c/d", "https://host:65536/p",
        "ftp://user:pa%0D%0ADELE%20x@h/", "ftps://h:-1/", "ftp://[fe80::1%25eth0]:21/pub", "webdav://h/remote.php/dav/files/u",
        "saf://", "saf://abc/../x", "onedrive://", "onedrive:///x", "onedrive://alex@outlook.com/../../etc", "onedrive://a%00b/x",
        "trash://", "recents://", "starred://", "tag://", "tag://Urgent", "tag://../x",
        "/a.zip!", "/a.zip!/", "/a.ZIP!/b/../../c", "/x/a.zip!/b.zip!/c", "a.zip!/x", "/a.zip!!/x", "/.zip!", "/a.zip.zip!/x",
        "C:\\Windows\\System32", "C:/x", "/storage/emulated/0/../../data/data", "/data/data/com.droid.wayfinder/shared_prefs",
        "😀", "\uD800", "\uDC00x", "\uFFFF", "\u202Egnp.exe", "\u200B", "e\u0301", "İ", "ß", "ﬀ",
        "a".repeat(300), "😀".repeat(200), "/".repeat(2000), "../".repeat(500), "a/".repeat(3000), "%2e%2e/".repeat(200),
        "<", ">", "&", "&amp;", "&#0;", "&#x110000;", "]]>", "<![CDATA[x]]>", "<!DOCTYPE", "<!ENTITY", "<?xml?>",
        "-1", "0", "2147483648", "9223372036854775808", "-9223372036854775809", "1e9", "NaN", "0x10", "٣",
        "image/*", "*/*", "*", "/*", "image/", "/", "application/pdf", "IMAGE/PNG", " image/png ",
    )

    private val specialChars = "/\\.\u0000\r\n\t:%@#?[]!*\"'<>&;|~+= -_😀\u202E\uD800"

    fun randomChar(r: Random): Char = when (r.nextInt(10)) {
        in 0..3 -> specialChars[r.nextInt(specialChars.length)]
        in 4..6 -> ('a' + r.nextInt(26))
        7 -> ('0' + r.nextInt(10))
        8 -> r.nextInt(0x20).toChar()
        else -> r.nextInt(0xFFFF).toChar()
    }

    fun randomString(r: Random, maxLen: Int = 40): String {
        val n = if (r.nextInt(50) == 0) r.nextInt(maxLen * 20 + 1) else r.nextInt(maxLen + 1)
        return buildString(n) { repeat(n) { append(randomChar(r)) } }
    }

    /** Applies 1–4 random edits (insert, delete, flip, duplicate, splice a corpus item). */
    fun mutate(r: Random, s: String): String {
        var out = StringBuilder(s)
        repeat(1 + r.nextInt(4)) {
            when (r.nextInt(6)) {
                0 -> out.insert(if (out.isEmpty()) 0 else r.nextInt(out.length + 1), randomChar(r))
                1 -> if (out.isNotEmpty()) out.deleteCharAt(r.nextInt(out.length))
                2 -> if (out.isNotEmpty()) out.setCharAt(r.nextInt(out.length), randomChar(r))
                3 -> if (out.isNotEmpty() && out.length < 20_000) {
                    val a = r.nextInt(out.length); val b = (a + r.nextInt(16)).coerceAtMost(out.length)
                    out.insert(b, out.substring(a, b))
                }
                4 -> out.insert(if (out.isEmpty()) 0 else r.nextInt(out.length + 1), corpus[r.nextInt(corpus.size)])
                else -> if (out.length > 1) out = StringBuilder(out.substring(0, r.nextInt(out.length)))
            }
        }
        return out.toString()
    }

    /** A random input: corpus item, mutated corpus item, random string, or a join of path-like pieces. */
    fun input(r: Random, maxLen: Int = 40): String = when (r.nextInt(5)) {
        0 -> corpus[r.nextInt(corpus.size)]
        1 -> mutate(r, corpus[r.nextInt(corpus.size)])
        2 -> randomString(r, maxLen)
        3 -> (0..r.nextInt(6)).joinToString(listOf("/", "\\", "/", "%2F")[r.nextInt(4)]) { if (r.nextBoolean()) corpus[r.nextInt(corpus.size)].take(12) else randomString(r, 8) }
        else -> mutate(r, randomString(r, maxLen))
    }

    fun escape(s: String): String = buildString {
        for (c in s.take(400)) if (c < ' ' || c.code in 0x7f..0x9f || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
        if (s.length > 400) append("…(${s.length} chars)")
    }

    /**
     * Runs [body] for every corpus item and then [n] generated inputs; any throwable (other than those [allowed]
     * returns true for) fails the test with a replayable report. Each target must finish within [budgetMs].
     */
    fun <T> run(
        name: String,
        n: Int,
        gen: (Random) -> T,
        corpusInputs: List<T> = emptyList(),
        budgetMs: Long = 60_000,
        show: (T) -> String = { escape(it.toString()) },
        allowed: (Throwable) -> Boolean = { false },
        body: (T) -> Unit,
    ) {
        val r = Random(seed xor name.hashCode().toLong())
        val start = System.currentTimeMillis()
        val total = iterations(n)
        var i = -corpusInputs.size
        val inputs = corpusInputs.asSequence() + generateSequence { gen(r) }.take(total)
        for (x in inputs) {
            try {
                body(x)
            } catch (t: Throwable) {
                if (!allowed(t)) {
                    val err = AssertionError("[$name] seed=$seed iteration=$i input=\"${show(x)}\" -> $t", t)
                    throw err
                }
            }
            i++
            executed++
            if (System.currentTimeMillis() - start > budgetMs) fail("[$name] exceeded the ${budgetMs}ms budget after $i iterations")
        }
    }
}
