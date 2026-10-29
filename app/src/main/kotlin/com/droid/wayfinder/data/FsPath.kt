// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

data class SmbPath(val host: String, val share: String?, val rel: String)

/**
 * Path helpers working for local paths (`/storage/...`), SMB (`smb://host/share/x`), virtual roots
 * (Network, Trash, Recents, Starred, `tag://Name`) and read-only zip paths (`/dir/a.zip!/inner/file`).
 * WebDAV (`dav://`, `davs://`), FTP (`ftp://`, `ftps://`) and SAF mounts (`saf://<mountId>/x`) share the
 * SMB path shape `scheme://authority/seg/...`; see [isRemote].
 */
object FsPath {
    const val SMB_ROOT = "smb://"
    const val TRASH_ROOT = "trash://"
    const val RECENTS_ROOT = "recents://"
    const val STARRED_ROOT = "starred://"
    const val PHONE_ROOT = "phone://"
    const val TAG_PREFIX = "tag://"

    fun isSmb(p: String) = p.startsWith(SMB_ROOT)
    fun isPhone(p: String) = p == PHONE_ROOT || p.startsWith(PHONE_ROOT)
    /** Any network or SAF location (SMB, WebDAV, FTP, cloud/document providers): not readable via java.io.File. */
    fun isRemote(p: String) = RemoteUri.schemeOf(p) != null
    fun isSaf(p: String) = p.startsWith(RemoteUri.SAF_SCHEME + "://")
    fun isDoc(p: String) = p.startsWith(RemoteUri.DOC_SCHEME + "://")
    fun isGdrive(p: String) = p.startsWith("gdrive://")
    fun isLocal(p: String) = p.startsWith("/")
    fun isVirtual(p: String) = p == SMB_ROOT || p == TRASH_ROOT || isCollection(p) || p == PHONE_ROOT
    /** Virtual listings gathered from many folders (Recents, Starred, color tags). */
    fun isCollection(p: String) = p == RECENTS_ROOT || p == STARRED_ROOT || p.startsWith(TAG_PREFIX)
    fun tagPath(tag: ColorTag) = TAG_PREFIX + tag.name
    fun tagOf(p: String): ColorTag? = if (p.startsWith(TAG_PREFIX)) ColorTag.fromName(p.removePrefix(TAG_PREFIX)) else null

    /** True for paths inside an archive (including the archive root `a.zip!`, `b.tar.gz!`). */
    fun isZip(p: String) = zipSplit(p) != null
    fun isArchive(p: String) = isZip(p)
    /** A local file that can be opened with java.io.File (not network, not inside an archive). */
    fun isLocalFile(p: String) = isLocal(p) && !isZip(p)
    /** Locations where nothing can be created, renamed or deleted. */
    fun isReadOnly(p: String) = isVirtual(p) || isZip(p)

    private val archiveMarker = Regex("(\\.(?:zip|tar\\.gz|tgz|tar))!(?=/|$)", RegexOption.IGNORE_CASE)

    /** Splits `/dir/a.zip!/x/y` or `/dir/a.tar.gz!/x/y` into (`/dir/a.tar.gz`, `x/y`); the archive root yields an empty inner path. */
    fun zipSplit(p: String): Pair<String, String>? {
        if (!isLocal(p)) return null
        val m = archiveMarker.find(p) ?: return null
        val archive = p.substring(0, m.groups[1]!!.range.last + 1)
        val inner = p.substring(m.range.last + 1).split('/').filter { it.isNotEmpty() }.joinToString("/")
        return archive to inner
    }

    /** Virtual folder path that browses archive [zipFile]. */
    fun zipRoot(zipFile: String) = normalize(zipFile) + "!"

    fun normalize(p: String): String = when {
        isPhone(p) -> {
            val sub = p.removePrefix(PHONE_ROOT).split('/').filter { it.isNotEmpty() }.joinToString("/")
            if (sub.isEmpty()) PHONE_ROOT else "$PHONE_ROOT$sub"
        }
        isDoc(p) -> p.trimEnd('/')
        isRemote(p) -> {
            val scheme = p.substringBefore("://") + "://"
            scheme + p.removePrefix(scheme).split('/').filter { it.isNotEmpty() }.joinToString("/")
        }
        isVirtual(p) -> p
        else -> p.trimEnd('/').ifEmpty { "/" }
    }

    fun name(p: String): String = when {
        p == SMB_ROOT -> "Network"
        p == TRASH_ROOT -> "Trash"
        p == RECENTS_ROOT -> "Recents"
        p == STARRED_ROOT -> "Starred"
        p == PHONE_ROOT || (isPhone(p) && normalize(p) == PHONE_ROOT) -> "Phone"
        isPhone(p) -> normalize(p).removePrefix(PHONE_ROOT).substringAfterLast('/')
        isDoc(p) -> SystemDocumentsBridge.displayNameOf(p) ?: SystemDocumentsBridge.fallbackNameOf(p)
        p.startsWith(TAG_PREFIX) -> tagOf(p)?.label ?: p.removePrefix(TAG_PREFIX)
        p == "/" -> "/"
        else -> {
            val s = normalize(p).substringAfterLast('/')
            if (s.endsWith('!')) s.dropLast(1) else s
        }
    }

    fun parent(p: String): String? {
        val n = normalize(p)
        return when {
            n == "/" || isVirtual(n) || n == PHONE_ROOT -> null
            isPhone(n) -> {
                val sub = n.removePrefix(PHONE_ROOT).trim('/')
                if (!sub.contains('/')) PHONE_ROOT else "$PHONE_ROOT${sub.substringBeforeLast('/')}"
            }
            isDoc(n) -> {
                SystemDocumentsBridge.parentOf(n)
            }
            isRemote(n) -> {
                // Server / mount roots sit under the virtual "Network" location.
                val scheme = n.substringBefore("://") + "://"
                val rest = n.removePrefix(scheme)
                if (!rest.contains('/')) SMB_ROOT else scheme + rest.substringBeforeLast('/')
            }
            // A relative path without '/' has no parent; substringBeforeLast would return it unchanged
            // and make parent() chains loop forever.
            !n.contains('/') -> null
            else -> n.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    fun child(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    /** Longest file name (in UTF-8 bytes) accepted by common file systems (ext4, f2fs, FAT/exFAT via FUSE). */
    const val MAX_NAME_BYTES = 255

    /** Maximum folder nesting followed by recursive operations (copy, delete, measure, compress, search). */
    const val MAX_DEPTH = 256

    /**
     * True when [name] is a single path segment that can be joined to a folder without escaping it:
     * not empty, not `.` / `..`, and free of `/` and NUL. Used to drop hostile names from server listings.
     */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }

    /**
     * Why [name] can't be used for a new / renamed item, or null when it's fine. Stricter than [isSafeName]:
     * also rejects backslashes, control characters, blank names and over-long names.
     */
    fun nameError(name: String): String? = when {
        name.isBlank() -> "Name can't be empty"
        name == "." || name == ".." -> "Invalid name"
        name.contains('/') || name.contains('\\') -> "Names can't contain slashes"
        name.any { it < ' ' || it == '\u007f' } -> "Names can't contain control characters"
        name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> "Name is too long"
        else -> null
    }

    /** True when [p] equals [ancestor] or lies beneath it. */
    fun isWithin(ancestor: String, p: String): Boolean {
        if (ancestor == p) return true
        val a = normalize(ancestor)
        val n = normalize(p)
        if (a == n) return true
        if (a.endsWith("://")) return n.startsWith(a)
        val aTrim = a.trimEnd('/')
        return n.startsWith("$aTrim/") || aTrim.isEmpty()
    }

    fun parseSmb(p: String): SmbPath {
        val parts = p.removePrefix(SMB_ROOT).split('/').filter { it.isNotEmpty() }
        return SmbPath(
            host = parts.getOrElse(0) { "" },
            share = parts.getOrNull(1),
            rel = parts.drop(2).joinToString("/"),
        )
    }

    /** Parses user input such as `smb://user@host/share/dir`, `\\host\share`, or `host`. */
    fun parseServerInput(input: String): ServerInput? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("smb://").removePrefix("SMB://").replace('\\', '/').trim('/')
        var user: String? = null
        if (s.contains('@')) {
            user = s.substringBefore('@').takeIf { it.isNotEmpty() }
            s = s.substringAfter('@')
        }
        val parts = s.split('/').filter { it.isNotEmpty() }
        val host = parts.getOrNull(0) ?: return null
        val path = SMB_ROOT + parts.joinToString("/")
        return ServerInput(host, user, path)
    }

    /** Ancestors from root to [p] inclusive, restricted to those at or below [root] (for local paths). */
    fun breadcrumbs(p: String, rootLabels: Map<String, String>): List<Pair<String, String>> {
        val n = normalize(p)
        if (isVirtual(n)) return listOf(name(n) to n)
        if (isPhone(n)) {
            val out = mutableListOf((rootLabels[PHONE_ROOT] ?: "Phone") to PHONE_ROOT)
            val rest = n.removePrefix(PHONE_ROOT).trim('/')
            if (rest.isNotEmpty()) {
                var cur = PHONE_ROOT
                rest.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    cur = if (cur.endsWith('/')) "$cur$seg" else "$cur/$seg"
                    out += seg to cur
                }
            }
            return out
        }
        if (isDoc(n)) {
            val chain = ArrayDeque<Pair<String, String>>()
            var cur: String? = n
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur)) {
                val label = rootLabels[cur] ?: SystemDocumentsBridge.displayNameOf(cur) ?: name(cur)
                chain.addFirst(label to cur)
                cur = SystemDocumentsBridge.parentOf(cur)
            }
            return chain.toList()
        }
        if (isRemote(n)) {
            // Saved servers / mounts with an alias start their crumbs at the labelled root.
            val mount = rootLabels.keys.filter { isRemote(it) && isWithin(it, n) }.maxByOrNull { it.length }
            if (mount != null || !isSmb(n)) {
                val root = mount ?: (n.substringBefore("://") + "://" + n.substringAfter("://").substringBefore('/'))
                val out = mutableListOf("Network" to SMB_ROOT, (rootLabels[root] ?: name(root)) to root)
                var cur = root
                n.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { seg -> cur = "$cur/$seg"; out += seg to cur }
                return out
            }
        }
        if (isSmb(n)) {
            val sp = parseSmb(n)
            val out = mutableListOf("Network" to SMB_ROOT, sp.host to (SMB_ROOT + sp.host))
            var cur = SMB_ROOT + sp.host
            if (sp.share != null) {
                cur = "$cur/${sp.share}"
                out += sp.share to cur
                sp.rel.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    cur = "$cur/$seg"; out += seg to cur
                }
            }
            return out
        }
        val root = rootLabels.keys.filter { isWithin(it, n) }.maxByOrNull { it.length }
        val out = mutableListOf<Pair<String, String>>()
        var cur: String
        if (root != null) {
            out += (rootLabels[root] ?: name(root)) to root
            cur = root.trimEnd('/')
            n.removePrefix(cur).split('/').filter { it.isNotEmpty() }.forEach { seg ->
                cur = "$cur/$seg"; out += crumbLabel(seg) to cur
            }
        } else {
            out += "/" to "/"
            cur = ""
            n.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                cur = "$cur/$seg"; out += crumbLabel(seg) to cur
            }
        }
        return out
    }

    /**
     * Formats [p] as a human-friendly path using [rootLabels] and [breadcrumbs] (e.g. `Pixel 8a/Documents`
     * or `Internal storage/Download`) instead of raw `doc://...` or `/storage/emulated/...` URIs.
     */
    fun friendlyPath(p: String, rootLabels: Map<String, String>, separator: String = "/"): String {
        val n = normalize(p)
        if (isVirtual(n)) return rootLabels[n] ?: name(n)
        val crumbs = breadcrumbs(n, rootLabels)
        val displayCrumbs = if ((isSaf(n) || isGdrive(n)) && crumbs.size >= 2 && crumbs[0].second == SMB_ROOT) {
            crumbs.drop(1)
        } else {
            crumbs
        }
        return displayCrumbs.joinToString(separator) { it.first }
    }

    private fun crumbLabel(seg: String) = if (seg.endsWith('!')) seg.dropLast(1) else seg
}

data class ServerInput(val host: String, val user: String?, val path: String)
