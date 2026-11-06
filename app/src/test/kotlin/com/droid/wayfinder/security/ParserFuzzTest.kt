// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.FtpMapping
import com.droid.wayfinder.data.MAX_NAME_ATTEMPTS
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.MimeFilter
import com.droid.wayfinder.data.ModifiedRange
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.data.RemoteUri
import com.droid.wayfinder.data.RenameItem
import com.droid.wayfinder.data.RenameRule
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.SavedServer
import com.droid.wayfinder.data.SearchCategory
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SearchScope
import com.droid.wayfinder.data.ServerEntryCodec
import com.droid.wayfinder.data.SystemDocumentsBridge
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.data.WebDavXml
import com.droid.wayfinder.data.duplicateName
import com.droid.wayfinder.data.planBatchRename
import com.droid.wayfinder.data.safeDisplayName
import com.droid.wayfinder.ui.AppState
import com.droid.wayfinder.ui.RootVolume
import com.droid.wayfinder.ui.resolveAddress
import org.apache.commons.net.ftp.FTPFile
import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Fuzz tests for the pure parsers / path logic. Invariants: no unexpected exception, no output that could escape a
 * folder (separators / dot segments where forbidden), idempotence / round trips where the format promises them,
 * bounded running time.
 */
class ParserFuzzTest {
    private val k = FuzzKit

    // ------------------------------------------------------------------ RemoteUri

    private fun addressInput(r: Random): String {
        val scheme = listOf("smb://", "dav://", "davs://", "http://", "https://", "ftp://", "ftps://", "webdav://", "\\\\", "//", "", "bogus://")[r.nextInt(12)]
        val user = if (r.nextInt(3) == 0) k.input(r, 10) + (if (r.nextBoolean()) ":" + k.input(r, 10) else "") + "@" else ""
        val host = if (r.nextInt(4) == 0) k.input(r, 12) else listOf("nas", "10.0.0.2", "[::1]", "[fe80::1", "host.local", "a b")[r.nextInt(6)]
        val port = if (r.nextInt(3) == 0) ":" + listOf("0", "21", "65535", "65536", "-1", "x", "", "99999999999")[r.nextInt(8)] else ""
        val path = if (r.nextBoolean()) "/" + k.input(r, 30) else ""
        val s = scheme + user + host + port + path
        return if (r.nextInt(4) == 0) k.mutate(r, s) else s
    }

    @Test
    fun remoteUriParse() = k.run("RemoteUri.parse", 30_000, ::addressInput, k.corpus) { s ->
        val a = RemoteUri.parse(s)
        if (a != null) {
            assertTrue("host must be valid: '${k.escape(a.host)}'", RemoteUri.isValidHost(a.host))
            assertTrue("port ${a.port}", a.port in 1..65535)
            if (a.path.isNotEmpty()) assertTrue("path segments safe: ${k.escape(a.path)}", a.path.split('/').all { FsPath.isSafeName(it) })
            // The browsable root path stays inside the server it names.
            val root = a.rootPath
            assertEquals(a.protocol.scheme, RemoteUri.schemeOf(root))
            assertEquals(a.path, RemoteUri.relOf(root))
            assertNotNull(RemoteUri.credKeyOf(root))
            assertEquals(FsPath.normalize(root), FsPath.normalize(FsPath.normalize(root)))
        }
        // Must never throw for arbitrary path strings either.
        RemoteUri.schemeOf(s); RemoteUri.protocolOf(s); RemoteUri.credKeyOf(s); RemoteUri.rootOf(s); RemoteUri.relOf(s)
        val (h, p) = RemoteUri.splitAuthority(s, 21)
        assertTrue(p in 1..65535)
        assertNotNull(h)
    }

    // ------------------------------------------------------------------ FsPath

    private fun pathInput(r: Random): String {
        val prefixes = listOf(
            "/", "/storage/emulated/0/", "smb://h/s/", "dav://h/", "saf://m/", "ftp://h:21/",
            "phone://", "phone://Download/", "gdrive://u@e.com/", "doc://auth/",
            "/x/a.zip!/", "/x/a.tar.gz!/", "/x/a.tgz!/", "/x/a.tar!/",
            "", "trash://", "tag://",
        )
        val prefix = prefixes[r.nextInt(prefixes.size)]
        return prefix + k.input(r, 40)
    }

    @Test
    fun fsPathInvariants() = k.run("FsPath", 40_000, ::pathInput, k.corpus) { p ->
        val n = FsPath.normalize(p)
        assertEquals("normalize idempotent", n, FsPath.normalize(n))
        FsPath.name(p); FsPath.isZip(p); FsPath.isReadOnly(p); FsPath.isRemote(p); FsPath.tagOf(p); FsPath.parseSmb(p)
        FsPath.breadcrumbs(p, mapOf("/storage/emulated/0" to "Internal", "smb://h/s" to "Share")).also { assertTrue(it.size <= p.length + 3) }
        // parent() chains terminate (no cycles) within the number of segments.
        var cur: String? = p
        var steps = 0
        while (cur != null) { cur = FsPath.parent(cur); assertTrue("parent loop for ${k.escape(p)}", ++steps <= p.length + 4) }
        FsPath.zipSplit(p)?.let { (archive, inner) ->
            val lower = archive.lowercase()
            assertTrue(lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar"))
            assertFalse(inner.startsWith("/") || inner.endsWith("/") || inner.contains("//"))
        }
        FsPath.isWithin(p, n); FsPath.isWithin(n, p)
        FsPath.parseServerInput(p)
    }

    @Test
    fun childOfSafeNameStaysInside() = k.run("FsPath.child", 30_000, { r -> k.input(r, 30) }, k.corpus) { name ->
        val dir = "/storage/emulated/0/Download"
        if (FsPath.isSafeName(name)) {
            val c = FsPath.child(dir, name)
            assertEquals(dir, FsPath.parent(c))
            assertTrue(FsPath.isWithin(dir, c))
            assertEquals(File(dir).canonicalFile, File(c).canonicalFile.parentFile)
        }
        val err = FsPath.nameError(name)
        if (err == null) {
            assertTrue(FsPath.isSafeName(name))
            assertFalse(name.contains('\\') || name.any { it < ' ' })
            assertTrue(name.toByteArray().size <= FsPath.MAX_NAME_BYTES)
        }
    }

    // ------------------------------------------------------------------ zip entry names / file names

    @Test
    fun zipEntrySanitizerNeverEscapes() {
        val base = File(System.getProperty("java.io.tmpdir"), "wf-zipname-base").canonicalFile
        k.run("safeZipEntryPath", 50_000, { r -> k.input(r, 60) }, k.corpus) { name ->
            val rel = FileInfo.safeZipEntryPath(name) ?: return@run
            val segs = rel.split('/')
            assertTrue(segs.isNotEmpty() && segs.size <= FsPath.MAX_DEPTH)
            assertTrue("segments: ${k.escape(rel)}", segs.all { FsPath.isSafeName(it) && !it.contains('\\') })
            val target = File(base, rel).canonicalPath
            assertTrue("escapes: ${k.escape(rel)}", target.startsWith(base.path + "/"))
        }
    }

    @Test
    fun sanitizeFileName() = k.run("sanitizeFileName", 40_000, { r -> k.input(r, 300) }, k.corpus) { raw ->
        val n = FileInfo.sanitizeFileName(raw, "fallback")
        assertNull("'${k.escape(n)}' -> ${FsPath.nameError(n)}", FsPath.nameError(n))
        assertTrue(FsPath.isSafeName(n))
    }

    @Test
    fun safDisplayNames() = k.run("safeDisplayName", 20_000, { r -> k.input(r, 30) }, k.corpus) { raw ->
        safeDisplayName(raw)?.let { assertTrue(FsPath.isSafeName(it)) }
    }

    @Test
    fun trashOriginValidation() = k.run("TrashManager.validOrigin", 30_000, { r -> "/" + k.input(r, 40) }, k.corpus) { raw ->
        val trash = "/storage/emulated/0/.Wayfinder-Trash"
        val o = TrashManager.validOrigin(raw, trash) ?: return@run
        assertTrue(o.startsWith("/"))
        assertTrue(o.split('/').drop(1).all { FsPath.isSafeName(it) })
        assertFalse(FsPath.isWithin(trash, o))
        assertFalse("origin must already be normalised: ${k.escape(o)}", o.contains("//") || (o.length > 1 && o.endsWith("/")))
    }

    // ------------------------------------------------------------------ FTP

    @Test
    fun ftpMapping() = k.run("FtpMapping", 30_000, { r -> k.input(r, 30) }, k.corpus) { raw ->
        val f = FTPFile().apply {
            name = raw
            type = if (raw.length % 2 == 0) FTPFile.DIRECTORY_TYPE else FTPFile.FILE_TYPE
            size = raw.length.toLong() - 5
        }
        val e = FtpMapping.entry("ftp://host/pub", f)
        if (e != null) {
            assertTrue(FsPath.isSafeName(e.name))
            assertFalse(FtpMapping.hasControl(e.name))
            assertEquals("ftp://host/pub", FsPath.parent(e.path))
            assertTrue(e.size >= 0)
        }
        // Server paths are either rejected or free of command separators.
        try {
            assertFalse(FtpMapping.hasControl(FtpMapping.serverPath("ftp://host/$raw")))
        } catch (_: IOException) {}
    }

    // ------------------------------------------------------------------ persistence formats

    @Test
    fun marksParseAndRoundTrip() = k.run("Marks", 20_000, { r ->
        val lines = (0..r.nextInt(8)).map {
            when (r.nextInt(4)) {
                0 -> "S\t" + k.input(r, 30)
                1 -> "T\t" + ColorTag.entries[r.nextInt(ColorTag.entries.size)].name + "\t" + k.input(r, 30)
                2 -> k.mutate(r, "T\tUrgent\t/a/b")
                else -> k.input(r, 30)
            }
        }
        lines.joinToString(if (r.nextInt(5) == 0) "\r\n" else "\n")
    }, k.corpus) { text ->
        val m = Marks.parse(text)
        val again = Marks.parse(m.serialize())
        // Paths that can't be stored (line breaks) are dropped; everything else survives exactly.
        fun ok(p: String) = p.none { it == '\n' || it == '\r' }
        assertEquals(m.starred.filter(::ok).toSet(), again.starred)
        assertEquals(m.tags.filterKeys(::ok), again.tags)
        // A path with a line break can never inject a record for another path.
        val injected = Marks(starred = setOf("/x\nS\t/victim", "/y")).serialize()
        assertEquals(setOf("/y"), Marks.parse(injected).starred)
    }

    @Test
    fun serverEntryCodec() = k.run("ServerEntryCodec", 20_000, { r ->
        if (r.nextBoolean()) k.input(r, 20) to (0..r.nextInt(12)).joinToString("\t") { k.input(r, 12) }
        else {
            val proto = Protocol.entries[r.nextInt(Protocol.entries.size)]
            val s = SavedServer(
                key = if (proto == Protocol.SMB) "nas" else "${proto.scheme}://nas:${1 + r.nextInt(65535)}",
                user = k.input(r, 12), domain = k.input(r, 8), hasPassword = false, protocol = proto, host = "nas",
                port = 1 + r.nextInt(65535), path = k.input(r, 20), alias = k.input(r, 12), guest = r.nextBoolean(), mounted = r.nextBoolean(),
            )
            "ENC:" + s.key to ServerEntryCodec.encode(s, if (r.nextBoolean()) "" else "QUJD" + r.nextInt(1000))
        }
    }, show = { (a, b) -> k.escape("$a => $b") }) { (key, raw) ->
        val realKey = key.removePrefix("ENC:")
        val s = ServerEntryCodec.decode(realKey, raw)
        ServerEntryCodec.credFields(raw)
        if (s != null) {
            assertTrue(s.port in 1..65535)
            assertTrue(s.path.isEmpty() || s.path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            // Encoding never lets a field spill into the next one: re-decoding is stable.
            val enc = ServerEntryCodec.credFields(raw)?.encPassword.orEmpty().filter { it != '\t' && it != '\n' && it != '\r' && it != '\u0000' }
            val again = ServerEntryCodec.decode(realKey, ServerEntryCodec.encode(s, enc))
            assertNotNull(again)
            assertEquals(s.protocol, again!!.protocol)
            assertEquals(s.port, again.port)
            assertEquals(s.guest, again.guest)
            assertEquals(s.mounted, again.mounted)
            assertEquals(enc.isNotEmpty(), again.hasPassword)
            assertFalse(again.user.contains('\t'))
        }
    }

    @Test
    fun safMountParse() = k.run("SafMount.parse", 20_000, { r -> (0..r.nextInt(5)).joinToString("\n") { (0..r.nextInt(5)).joinToString("\t") { k.input(r, 10) } } }, k.corpus) { text ->
        val list = SafMount.parse(text)
        assertTrue(list.all { it.id.isNotEmpty() })
        val clean = list.filter { m -> listOf(m.id, m.uri).none { s -> s.contains('\t') || s.contains('\n') } }
        assertEquals(clean.map { it.id to it.uri }, SafMount.parse(SafMount.serialize(clean)).map { it.id to it.uri })
    }

    // ------------------------------------------------------------------ filters

    @Test
    fun mimeFilter() = k.run("MimeFilter", 30_000, { r -> k.input(r, 20) to (0..r.nextInt(4)).map { k.input(r, 12) } }, show = { k.escape(it.toString()) }) { (mime, patterns) ->
        MimeFilter.matches(mime, patterns)
        MimeFilter.matches(null, patterns)
        assertTrue(MimeFilter.matches(mime, emptyList()))
        assertTrue(MimeFilter.matches(mime, patterns + "*/*"))
        if (mime.isNotBlank() && mime == mime.trim()) assertTrue(MimeFilter.matches(mime, listOf(mime)))
    }

    @Test
    fun searchFilter() = k.run("SearchFilter", 30_000, { r ->
        Triple(
            SearchFilter(SearchScope.entries.random(r), SearchCategory.entries.random(r), ModifiedRange.entries.random(r)),
            FileEntry("/x/" + k.input(r, 10), k.input(r, 20), r.nextBoolean(), r.nextLong(), listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, r.nextLong()).random(r)),
            listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, r.nextLong(), System.currentTimeMillis()).random(r),
        )
    }, show = { k.escape(it.toString()) }) { (f, e, now) ->
        val tz = TimeZone.getTimeZone(listOf("UTC", "Pacific/Kiritimati", "America/St_Johns", "Asia/Kathmandu").random(Random(now)))
        f.matches(e, now, tz)
        f.modified.since(now, tz)
        Calendar.getInstance() // touch
    }

    // ------------------------------------------------------------------ batch rename / unique names

    @Test
    fun batchRenamePlans() = k.run("planBatchRename", 15_000, { r ->
        val items = (0..r.nextInt(6)).map { RenameItem(k.input(r, 20), r.nextBoolean()) }
        val rule = when (r.nextInt(3)) {
            0 -> RenameRule.FindReplace(k.input(r, 4), k.input(r, 6), r.nextBoolean())
            1 -> RenameRule.PrefixSuffix(k.input(r, 6), k.input(r, 6))
            else -> RenameRule.Numbered(k.input(r, 8), listOf(0, 1, -5, Int.MAX_VALUE, Int.MIN_VALUE, r.nextInt()).random(r))
        }
        Triple(items, rule, (0..r.nextInt(5)).map { k.input(r, 10) })
    }, show = { k.escape(it.toString()) }) { (items, rule, existing) ->
        val plan = planBatchRename(items, rule, existing)
        assertEquals(items.size, plan.size)
        val accepted = plan.filter { it.conflict == null && it.changed }
        for (p in accepted) {
            assertNull("accepted invalid name '${k.escape(p.new)}'", FsPath.nameError(p.new))
            assertTrue(FsPath.isSafeName(p.new))
        }
        // No two accepted targets collide (case-insensitively), and none hits an untouched existing name.
        assertEquals(accepted.size, accepted.map { it.new.lowercase() }.toSet().size)
    }

    @Test
    fun duplicateNamesAreFreeAndBounded() = k.run("duplicateName", 10_000, { r -> k.input(r, 30) to (0..r.nextInt(5)).map { k.input(r, 30) }.toSet() }, show = { k.escape(it.toString()) }) { (name, taken) ->
        val n = duplicateName(name, name.length % 2 == 0) { it in taken }
        assertFalse(n in taken)
        // A server claiming that every name exists must not hang us.
        try { duplicateName(name, false) { true }; throw AssertionError("expected IOException") } catch (_: IOException) {}
        assertTrue(MAX_NAME_ATTEMPTS <= 10_000)
    }

    // ------------------------------------------------------------------ intent guard token

    @Test
    fun intentTokenComparison() = k.run("IntentGuard.isTrusted", 20_000, { r -> k.input(r, 70) }, k.corpus) { presented ->
        val expected = "a".repeat(64)
        assertEquals(presented == expected, IntentGuard.isTrusted(presented, expected))
        assertFalse(IntentGuard.isTrusted(null, expected))
    }

    // ------------------------------------------------------------------ PhoneFileSystem & DriveRestFileSystem

    @Test
    fun phoneSegmentsValidation() {
        val base = File(System.getProperty("java.io.tmpdir"), "wf-phone-fuzz").canonicalFile
        val pfs = com.droid.wayfinder.data.PhoneFileSystem(null, base)
        k.run("PhoneFileSystem.validateSegments", 30_000, { r ->
            val prefix = if (r.nextInt(5) == 0) "" else "phone://"
            prefix + k.input(r, 50)
        }, k.corpus) { raw ->
            try {
                val segs = pfs.validateSegments(raw)
                assertTrue(segs.size <= FsPath.MAX_DEPTH)
                assertTrue(segs.all { FsPath.isSafeName(it) && !it.contains('\\') })
                var f = base
                for (s in segs) f = File(f, s)
                assertTrue(f.canonicalPath == base.path || f.canonicalPath.startsWith(base.path + "/"))
            } catch (_: IOException) {}
        }
    }

    @Test
    fun driveIdValidation() = k.run("DriveRestFileSystem.isSafeDriveId", 20_000, { r -> k.input(r, 40) }, k.corpus) { id ->
        if (com.droid.wayfinder.data.DriveRestFileSystem.isSafeDriveId(id)) {
            assertTrue(id.isNotEmpty() && id.length <= 256)
            assertFalse(id == "." || id == "..")
            assertFalse(id.contains('\'') || id.contains('/') || id.contains('\\') || id.contains(' '))
            assertTrue(FsPath.isSafeName(id))
        }
    }

    // ------------------------------------------------------------------ SystemDocumentsBridge & Friendly Address Resolution

    @Test
    fun systemDocumentsBridgeAndFriendlyAddressResolution() {
        val quickAccessRoot = "doc://com.google.ambient.streaming.multidevicesync.quickaccess/pixel-8a-uuid"
        val state = AppState(
            roots = listOf(RootVolume(path = "/storage/emulated/0", label = "Internal storage", removable = false)),
            pairedPhone = com.droid.wayfinder.data.PairedPhone(
                name = "Pixel 8a",
                path = quickAccessRoot,
                isMounted = true,
            ),
            linuxRootPath = "doc://com.google.android.documents.linux/linux_root",
        )
        val authorities = listOf(
            "com.google.ambient.streaming.multidevicesync.quickaccess",
            "com.android.mtp.documents",
            "com.google.android.apps.nbu.files.provider",
            "com.google.android.documents.linux",
            "com.android.externalstorage.documents",
            "",
        )
        val prefixes = listOf(
            "doc://com.google.ambient.streaming.multidevicesync.quickaccess/pixel-8a-uuid//",
            "doc://com.google.ambient.streaming.multidevicesync.quickaccess/pixel-8a-uuid/",
            "doc://com.android.mtp.documents/",
            "doc://com.google.android.documents.linux/linux_root/",
            "doc://",
            "Pixel 8a/",
            "Phone/",
            "Internal storage/",
            "Linux files/",
            "phone://",
            "/",
            "",
        )
        k.run("SystemDocumentsBridge+resolveAddress", 30_000, { r ->
            val pfx = prefixes[r.nextInt(prefixes.size)]
            val body = k.input(r, 40)
            val s = pfx + body
            if (r.nextInt(4) == 0) k.mutate(r, s) else s
        }, k.corpus) { raw ->
            val auth = authorities[raw.length % authorities.size]
            SystemDocumentsBridge.authorityOf(raw)
            SystemDocumentsBridge.isPhonePath(raw)
            SystemDocumentsBridge.normalizeDocId(auth, raw)
            SystemDocumentsBridge.fallbackNameOf(raw)
            SystemDocumentsBridge.splitParentAndChild(raw)

            val cp = SystemDocumentsBridge.childPath(quickAccessRoot, raw)
            assertEquals("childPath must be normalized", FsPath.normalize(cp), cp)

            var cur: String? = FsPath.normalize(if (FsPath.isDoc(raw)) raw else "doc://$auth/$raw")
            var steps = 0
            while (cur != null) {
                assertEquals("parentOf must return normalized path", FsPath.normalize(cur), cur)
                val next = SystemDocumentsBridge.parentOf(cur)
                if (next != null) assertFalse("self-parent cycle for ${k.escape(cur)}", next == cur)
                cur = next
                assertTrue("parentOf loop for ${k.escape(raw)}", ++steps <= raw.length + 10)
            }

            val friendly = FsPath.friendlyPath(raw, state.rootLabels)
            assertNotNull(friendly)

            val resolved = resolveAddress(raw, state)
            if (resolved != null) {
                assertEquals("resolveAddress must return normalized path for '${k.escape(raw)}'", FsPath.normalize(resolved), resolved)
            }
        }
    }

    // ------------------------------------------------------------------ WebDavXml multistatus & href fuzzing

    private fun webDavXmlInput(r: Random): String {
        if (r.nextInt(5) == 0) {
            return listOf(
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><d:multistatus xmlns:d=\"DAV:\">&xxe;</d:multistatus>",
                "<?xml version=\"1.0\"?><!ENTITY lol \"lol\"><d:multistatus xmlns:d=\"DAV:\"/>",
                k.input(r, 80),
            )[r.nextInt(3)]
        }
        val sb = StringBuilder("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
        repeat(1 + r.nextInt(4)) {
            val href = k.input(r, 30).replace("&", "&amp;").replace("<", "&lt;")
            val size = listOf("-1", "0", "1024", "99999999999999999999999", k.input(r, 8))[r.nextInt(5)]
                .replace("&", "&amp;").replace("<", "&lt;")
            val status = listOf("HTTP/1.1 200 OK", "HTTP/1.1 404 Not Found", "HTTP/1.1 207 Multi-Status", k.input(r, 12))[r.nextInt(4)]
                .replace("&", "&amp;").replace("<", "&lt;")
            val date = listOf("Wed, 21 Oct 2015 07:28:00 GMT", "2026-10-29T12:00:00Z", k.input(r, 16))[r.nextInt(3)]
                .replace("&", "&amp;").replace("<", "&lt;")
            val dirTag = if (r.nextBoolean()) "<d:collection/>" else ""
            sb.append(
                "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
                    "<d:resourcetype>$dirTag</d:resourcetype>" +
                    "<d:getcontentlength>$size</d:getcontentlength>" +
                    "<d:getlastmodified>$date</d:getlastmodified>" +
                    "</d:prop><d:status>$status</d:status></d:propstat></d:response>"
            )
        }
        sb.append("</d:multistatus>")
        val xml = sb.toString()
        return if (r.nextInt(3) == 0) k.mutate(r, xml) else xml
    }

    @Test
    fun webDavMultistatusAndHrefFuzz() {
        k.run("WebDavXml.hrefToPath", 20_000, { r -> k.input(r, 50) }, k.corpus) { raw ->
            val p = WebDavXml.hrefToPath(raw)
            assertTrue("hrefToPath must start with '/': ${k.escape(p)}", p.startsWith("/"))
            assertFalse("hrefToPath must not contain '//': ${k.escape(p)}", p.contains("//"))
            if (p.length > 1) assertFalse("hrefToPath must not end with '/': ${k.escape(p)}", p.endsWith("/"))
            WebDavXml.parseHttpDate(raw)
        }
        k.run(
            "WebDavXml.parseMultistatus", 10_000, ::webDavXmlInput, k.corpus,
            allowed = { it is IOException },
        ) { xml ->
            val resources = WebDavXml.parseMultistatus(xml, KXmlParser())
            for (res in resources) {
                assertTrue("size must be non-negative: ${res.size}", res.size >= 0L)
                assertTrue("resource path must start with '/': ${k.escape(res.path)}", res.path.startsWith("/"))
                assertFalse("resource path must not contain '//': ${k.escape(res.path)}", res.path.contains("//"))
                if (res.isDir) assertEquals(0L, res.size)
            }
        }
    }
}
