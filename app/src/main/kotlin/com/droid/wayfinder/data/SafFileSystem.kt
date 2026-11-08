// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A document tree (cloud drive, USB stick, ...) granted through ACTION_OPEN_DOCUMENT_TREE. Browsed as `saf://<id>`. */
data class SafMount(val id: String, val label: String, val uri: String) {
    val path: String get() = "${RemoteUri.SAF_SCHEME}://$id"

    companion object {
        fun serialize(list: List<SafMount>): String =
            list.joinToString("\n") { "${it.id}\t${it.label.replace('\t', ' ').replace('\n', ' ')}\t${it.uri}" }

        fun parse(s: String?): List<SafMount> = s.orEmpty().split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3 || f[0].isEmpty()) null else SafMount(f[0], f[1], f[2])
        }
    }
}

/** Friendly names for well-known document providers (pure). */
object SafLabels {
    fun providerLabel(authority: String, treeDocId: String): String = when {
        authority == "com.google.android.apps.docs.storage" -> "Google Drive"
        authority == "com.android.externalstorage.documents" -> when {
            treeDocId.startsWith("primary:") -> "Internal storage"
            treeDocId.startsWith("home:") -> "Documents"
            else -> "USB / SD card"
        }
        authority == "com.android.providers.downloads.documents" -> "Downloads"
        authority == "com.android.providers.media.documents" -> "Media"
        authority == "com.android.mtp.documents" -> "Phone"
        authority == SystemDocumentsBridge.QUICK_ACCESS_AUTHORITY -> "Connected Phone"
        GooglebookEcosystem.isLinuxAuthority(authority) -> "Linux files"
        authority.startsWith("com.microsoft.skydrive") -> "OneDrive"
        authority.contains("dropbox", true) -> "Dropbox"
        authority.contains("nextcloud", true) -> "Nextcloud"
        authority.contains("smb", true) || authority.contains("samba", true) -> "SMB share"
        else -> authority.split('.').lastOrNull { it !in setOf("documents", "provider", "storage", "files", "android", "com") }
            ?.replaceFirstChar { it.uppercase() } ?: authority
    }
}

/**
 * A provider display name usable as one path segment: `/` and NUL become `_`; empty, `.` and `..` are dropped
 * (null). Applied identically when listing and when resolving paths, so lookups stay consistent.
 */
fun safeDisplayName(raw: String): String? {
    val n = raw.replace('/', '_').replace('\u0000', '_')
    return n.takeIf { FsPath.isSafeName(it) }
}

/**
 * Storage Access Framework file system. Paths are `saf://<mountId>/sub/path`; segments are mapped to document
 * IDs by looking up children by display name, with a path → document-ID cache.
 */
class SafFileSystem(context: Context, private val mounts: () -> List<SafMount>) : RemoteFileSystem {
    private val cr = context.applicationContext.contentResolver
    private val cache = HashMap<String, String>()

    private val cols = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SUMMARY,
    )

    private class Row(val id: String, val name: String, val mime: String, val size: Long, val modified: Long, val summary: String? = null) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }

    private fun mountOf(path: String): SafMount {
        val id = path.substringAfter("://").substringBefore('/')
        return mounts().firstOrNull { it.id == id } ?: throw IOException("This cloud / storage location is no longer mounted")
    }

    private fun tree(m: SafMount): Uri = Uri.parse(m.uri)
    private fun docUri(m: SafMount, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree(m), docId)

    private fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw IOException("$what: permission to this location was revoked. Mount it again.", e)
    } catch (e: FileNotFoundException) {
        throw IOException("$what: not found", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("$what: ${e.message ?: "not supported by the provider"}", e)
    } catch (e: UnsupportedOperationException) {
        throw IOException("$what: not supported by the provider", e)
    }

    private fun children(m: SafMount, parentId: String): List<Row> {
        val treeUri = tree(m)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val isQuickAccess = treeUri.authority == SystemDocumentsBridge.QUICK_ACCESS_AUTHORITY
        val out = ArrayList<Row>()
        val usedNames = HashMap<String, Int>()
        SystemDocumentsBridge.queryWithLoadingWait(
            cr = cr,
            uri = uri,
            projection = cols,
            maxAttempts = if (isQuickAccess) 8 else 6,
            waitMs = if (isQuickAccess) 2000L else 1500L,
        ) { c ->
            while (c.moveToNext()) {
                // Display names are chosen by the (third-party) provider: never let them act as path separators.
                val rawName = safeDisplayName(c.getString(1) ?: continue) ?: continue
                val count = usedNames.getOrDefault(rawName, 0) + 1
                usedNames[rawName] = count
                val name = if (count == 1) rawName else "$rawName ($count)"
                val summary = if (c.columnCount > 5 && !c.isNull(5)) c.getString(5)?.takeIf { it.isNotBlank() } else null
                out += Row(
                    c.getString(0) ?: continue, name, c.getString(2).orEmpty(),
                    if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                    summary,
                )
            }
        }
        return out
    }

    private fun meta(m: SafMount, docId: String): Row? =
        cr.query(docUri(m, docId), cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Row(
                docId, c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                if (c.columnCount > 5 && !c.isNull(5)) c.getString(5)?.takeIf { it.isNotBlank() } else null,
            )
        }

    /** Document ID for [path], or null if it doesn't exist. */
    private fun resolve(m: SafMount, path: String): String? {
        val p = FsPath.normalize(path)
        if (RemoteUri.relOf(p).isEmpty()) return DocumentsContract.getTreeDocumentId(tree(m))
        synchronized(cache) { cache[p] }?.let { return it }
        val parent = FsPath.parent(p) ?: return null
        val parentId = resolve(m, parent) ?: return null
        val name = FsPath.name(p)
        val kids = children(m, parentId)
        synchronized(cache) { kids.forEach { cache[FsPath.child(parent, it.name)] = it.id } }
        return kids.firstOrNull { it.name == name }?.id
    }

    private fun invalidate(path: String) {
        synchronized(cache) { cache.keys.removeAll { FsPath.isWithin(path, it) } }
    }

    override fun disconnect(key: String) = invalidate("${RemoteUri.SAF_SCHEME}://$key")

    /** Resolves [path] (`saf://<mountId>/...`) to its underlying [DocumentsContract] document URI, or null if missing. */
    fun documentUriOf(path: String): Uri? = try {
        val m = mountOf(path)
        val id = resolve(m, path) ?: return null
        docUri(m, id)
    } catch (_: Exception) {
        null
    }

    private fun toEntry(parent: String, r: Row) = FileEntry(
        path = FsPath.child(parent, r.name), name = r.name, isDir = r.isDir,
        size = if (r.isDir) 0 else r.size, modified = r.modified,
        subtitle = r.summary,
    )

    override fun list(path: String): List<FileEntry> = guard("Couldn't open ${FsPath.name(path)}") {
        val m = mountOf(path)
        val p = FsPath.normalize(path)
        val id = resolve(m, p) ?: throw IOException("This folder no longer exists")
        val kids = children(m, id)
        synchronized(cache) { kids.forEach { cache[FsPath.child(p, it.name)] = it.id } }
        kids.map { toEntry(p, it) }
    }

    override fun stat(path: String): FileEntry? = guard("Couldn't read ${FsPath.name(path)}") {
        val m = mountOf(path)
        val p = FsPath.normalize(path)
        if (RemoteUri.relOf(p).isEmpty()) return@guard FileEntry(p, m.label, true, isServer = true)
        val id = resolve(m, p) ?: return@guard null
        val r = meta(m, id) ?: run { invalidate(p); return@guard null }
        FileEntry(p, FsPath.name(p), r.isDir, if (r.isDir) 0 else r.size, r.modified, subtitle = r.summary)
    }

    private fun create(path: String, mime: String): String {
        val m = mountOf(path)
        val parent = FsPath.parent(path) ?: throw IOException("Invalid path")
        val parentId = resolve(m, parent) ?: throw IOException("The parent folder no longer exists")
        val uri = DocumentsContract.createDocument(cr, docUri(m, parentId), mime, FsPath.name(path))
            ?: throw IOException("The provider refused to create ${FsPath.name(path)}")
        val id = DocumentsContract.getDocumentId(uri)
        synchronized(cache) { cache[FsPath.normalize(path)] = id }
        return id
    }

    private fun mimeFor(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun mkdir(path: String) = guard("Couldn't create folder ${FsPath.name(path)}") {
        if (stat(path)?.isDir == true) return@guard
        create(path, Document.MIME_TYPE_DIR)
        Unit
    }

    override fun createFile(path: String) = guard("Couldn't create ${FsPath.name(path)}") {
        if (exists(path)) throw IOException("${FsPath.name(path)} already exists")
        create(path, mimeFor(FsPath.name(path)))
        Unit
    }

    override fun delete(path: String) = guard("Couldn't delete ${FsPath.name(path)}") {
        val m = mountOf(path)
        val id = resolve(m, path) ?: return@guard
        if (!DocumentsContract.deleteDocument(cr, docUri(m, id))) throw IOException("The provider refused to delete ${FsPath.name(path)}")
        invalidate(FsPath.normalize(path))
    }

    override fun rename(path: String, newName: String): String = guard("Couldn't rename ${FsPath.name(path)}") {
        if (!FsPath.isSafeName(newName)) throw IOException("Invalid name")
        val m = mountOf(path)
        val p = FsPath.normalize(path)
        val dst = FsPath.child(FsPath.parent(p)!!, newName)
        if (exists(dst)) throw IOException("\"$newName\" already exists")
        val id = resolve(m, p) ?: throw IOException("${FsPath.name(p)} no longer exists")
        val newUri = DocumentsContract.renameDocument(cr, docUri(m, id), newName)
        invalidate(p)
        synchronized(cache) { cache[dst] = newUri?.let { DocumentsContract.getDocumentId(it) } ?: id }
        dst
    }

    override fun move(src: String, dstPath: String): Boolean {
        if (RemoteUri.rootOf(src) != RemoteUri.rootOf(dstPath) || FsPath.name(src) != FsPath.name(dstPath)) return false
        return try {
            val m = mountOf(src)
            val id = resolve(m, src) ?: return false
            val from = resolve(m, FsPath.parent(src) ?: return false) ?: return false
            val to = resolve(m, FsPath.parent(dstPath) ?: return false) ?: return false
            val moved = DocumentsContract.moveDocument(cr, docUri(m, id), docUri(m, from), docUri(m, to)) ?: return false
            invalidate(FsPath.normalize(src))
            synchronized(cache) { cache[FsPath.normalize(dstPath)] = DocumentsContract.getDocumentId(moved) }
            true
        } catch (_: Exception) {
            false // not supported by this provider: the caller copies + deletes instead
        }
    }

    override fun openInput(path: String): InputStream = guard("Couldn't open ${FsPath.name(path)}") {
        val m = mountOf(path)
        val id = resolve(m, path) ?: throw IOException("${FsPath.name(path)} no longer exists")
        cr.openInputStream(docUri(m, id))?.buffered(256 * 1024) ?: throw IOException("The provider returned no data")
    }

    override fun openOutput(path: String): OutputStream = guard("Couldn't write ${FsPath.name(path)}") {
        val m = mountOf(path)
        val id = resolve(m, path) ?: create(path, mimeFor(FsPath.name(path)))
        val uri = docUri(m, id)
        val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull() ?: cr.openOutputStream(uri, "w")
        out?.buffered(256 * 1024) ?: throw IOException("The provider refused to write ${FsPath.name(path)}")
    }
}
