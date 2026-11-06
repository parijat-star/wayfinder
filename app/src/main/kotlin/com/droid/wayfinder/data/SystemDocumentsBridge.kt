// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DiscoveredRoot(
    val authority: String,
    val rootId: String,
    val title: String,
    val documentId: String,
    val flags: Int = 0,
    val summary: String? = null,
)

object SystemDocumentsBridge {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val TAG = "SystemDocumentsBridge"
    const val QUICK_ACCESS_AUTHORITY = PhoneDetector.QUICK_ACCESS_AUTHORITY
    const val MTP_AUTHORITY = PhoneDetector.MTP_AUTHORITY

    private val displayNames = ConcurrentHashMap<String, String>()
    private val parents = ConcurrentHashMap<String, String>()

    fun isPhoneAuthority(authority: String?): Boolean = PhoneDetector.isPhoneAuthority(authority)

    fun authorityOf(path: String): String? {
        if (!FsPath.isDoc(path)) return null
        return path.removePrefix("doc://").substringBefore('/').takeIf { it.isNotBlank() }
    }

    fun isPhonePath(path: String): Boolean =
        FsPath.isPhone(path) || (FsPath.isDoc(path) && isPhoneAuthority(authorityOf(path)))

    fun registerNode(path: String, displayName: String, parentPath: String? = null) {
        val norm = FsPath.normalize(path)
        if (displayName.isNotBlank()) {
            displayNames[norm] = displayName
        }
        if (parentPath != null) {
            val normParent = FsPath.normalize(parentPath)
            if (normParent != norm) {
                parents[norm] = normParent
            }
        }
    }

    fun displayNameOf(path: String): String? = displayNames[FsPath.normalize(path)]

    /**
     * Human-friendly fallback name for a `doc://` path when [displayNames] has not cached the node yet,
     * avoiding exposing raw provider authorities or device UUIDs in breadcrumbs and titles.
     */
    fun fallbackNameOf(path: String): String {
        val norm = FsPath.normalize(path)
        val trimmed = norm.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = normalizeDocId(auth, trimmed.substringAfter('/', "").trimStart('/'))
        if (docId.contains("//")) {
            val remotePath = docId.substringAfter("//").trim('/')
            return if (remotePath.isEmpty()) "Phone" else remotePath.substringAfterLast('/')
        }
        if (!docId.contains('/')) {
            return when {
                isPhoneAuthority(auth) -> "Phone"
                GooglebookEcosystem.isLinuxAuthority(auth) -> "Linux files"
                auth == GoogleDriveManager.DOCS_AUTHORITY -> "Google Drive"
                docId.contains(':') -> docId.substringAfter(':').trim('/').substringAfterLast('/').ifEmpty { docId.substringBefore(':') }
                else -> docId.ifEmpty { auth }
            }
        }
        return docId.trimEnd('/').substringAfterLast('/')
    }

    /** Joins a relative sub-path onto a `doc://` root path, preserving QuickAccess `//` separator conventions. */
    fun childPath(rootPath: String, relativePath: String): String {
        val normRoot = FsPath.normalize(rootPath)
        val cleanRel = relativePath.split('/').filter { it.isNotEmpty() }.joinToString("/")
        if (cleanRel.isEmpty()) return normRoot
        val auth = authorityOf(normRoot)
        val docId = normRoot.removePrefix("doc://${auth.orEmpty()}/")
        return if (auth == PhoneDetector.QUICK_ACCESS_AUTHORITY && !docId.contains("//")) {
            "doc://$auth/$docId//$cleanRel"
        } else {
            "$normRoot/$cleanRel"
        }
    }

    fun parentOf(path: String): String? {
        val norm = FsPath.normalize(path)
        parents[norm]?.takeIf { it != norm }?.let { return it }
        val trimmed = norm.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = normalizeDocId(auth, trimmed.substringAfter('/', "").trimStart('/'))
        if (auth.isBlank() || docId.isEmpty() || !docId.contains('/')) return null
        if (docId.contains("//")) {
            val deviceId = docId.substringBefore("//")
            val remotePath = docId.substringAfter("//").trim('/')
            if (deviceId.isEmpty() || remotePath.isEmpty()) return null
            val parentRemote = remotePath.substringBeforeLast('/', "").trimEnd('/')
            return if (parentRemote.isEmpty()) {
                "doc://$auth/$deviceId"
            } else {
                "doc://$auth/$deviceId//$parentRemote"
            }
        }
        val parentDocId = docId.substringBeforeLast('/', "").trimEnd('/')
        return if (parentDocId.isEmpty()) null else "doc://$auth/$parentDocId"
    }

    /**
     * Preserves or restores provider-specific documentId formatting (such as QuickAccess's
     * `<deviceId>//<remotePath>` double-slash convention).
     */
    fun normalizeDocId(authority: String, docId: String): String {
        if (authority == PhoneDetector.QUICK_ACCESS_AUTHORITY && docId.contains('/') && !docId.contains("//")) {
            val deviceId = docId.substringBefore('/')
            val remotePath = docId.substringAfter('/')
            return "$deviceId//$remotePath"
        }
        return docId
    }

    /**
     * Queries a DocumentsProvider children URI, waiting for asynchronous cloud/device sync when
     * the provider sets [DocumentsContract.EXTRA_LOADING] on an initially empty cursor.
     */
    fun <T> queryWithLoadingWait(
        cr: ContentResolver,
        uri: Uri,
        projection: Array<String>?,
        maxAttempts: Int = 6,
        waitMs: Long = 1500L,
        reader: (Cursor) -> T,
    ): T? {
        for (attempt in 0 until maxAttempts) {
            val cursor = cr.query(uri, projection, null, null, null) ?: return null
            val loading = runCatching {
                cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            }.getOrDefault(false)
            val empty = cursor.count == 0
            if (!loading || !empty || attempt == maxAttempts - 1) {
                return cursor.use(reader)
            }
            val notifyUri = runCatching { cursor.notificationUri }.getOrNull() ?: uri
            val latch = CountDownLatch(1)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    latch.countDown()
                }
            }
            val registered = runCatching {
                cr.registerContentObserver(notifyUri, true, observer)
                true
            }.getOrDefault(false)
            cursor.close()
            try {
                latch.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } finally {
                if (registered) {
                    runCatching { cr.unregisterContentObserver(observer) }
                }
            }
        }
        return cr.query(uri, projection, null, null, null)?.use(reader)
    }

    /**
     * Splits a synthetic child path `doc://<authority>/<parentDocId>/<newName>` (constructed via
     * [FsPath.join]) into `(authority, parentDocId, newName)` without corrupting opaque [parentDocId]
     * tokens that do not contain `/`.
     */
    fun splitParentAndChild(path: String): Triple<String, String, String>? {
        val norm = FsPath.normalize(path)
        val trimmed = norm.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/', "")
        if (auth.isBlank() || rest.isBlank()) return null
        val explicitParent = parents[norm]
        if (explicitParent != null) {
            val parentDocId = normalizeDocId(auth, explicitParent.removePrefix("doc://$auth/"))
            val name = rest.removePrefix("$parentDocId/").substringAfterLast('/')
            if (parentDocId.isNotBlank() && name.isNotBlank()) {
                return Triple(auth, parentDocId, name)
            }
        }
        if (!rest.contains('/')) return null
        val parentDocId = normalizeDocId(auth, rest.substringBeforeLast('/'))
        val name = rest.substringAfterLast('/')
        if (parentDocId.isBlank() || name.isBlank()) return null
        return Triple(auth, parentDocId, name)
    }

    /**
     * Converts a Wayfinder path (`local` `/storage/...`, `doc://...`, or `saf://...`) into a
     * [DocumentsContract] URI (document URI when [tree] is false, tree URI when [tree] is true),
     * or returns null if the path cannot be represented as a DocumentsContract URI.
     */
    fun toDocumentsContractUri(
        path: String,
        tree: Boolean = false,
        safMounts: List<SafMount> = emptyList(),
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }.getOrDefault("/storage/emulated/0"),
    ): Uri? {
        val norm = FsPath.normalize(path)
        if (norm.startsWith("doc://")) {
            val trimmed = norm.removePrefix("doc://")
            val auth = trimmed.substringBefore('/')
            val docId = normalizeDocId(auth, trimmed.substringAfter('/', ""))
            if (auth.isBlank() || docId.isBlank()) return null
            return if (tree) {
                DocumentsContract.buildTreeDocumentUri(auth, docId)
            } else {
                DocumentsContract.buildDocumentUri(auth, docId)
            }
        }
        if (norm.startsWith("saf://")) {
            val rem = norm.removePrefix("saf://")
            val id = rem.substringBefore('/')
            val rel = rem.substringAfter('/', "").trim('/')
            val mount = safMounts.firstOrNull { it.id == id } ?: return null
            val treeUri = runCatching { Uri.parse(mount.uri) }.getOrNull() ?: return null
            val baseDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            val targetDocId = if (rel.isEmpty()) baseDocId else "$baseDocId/$rel"
            return if (tree && rel.isEmpty()) {
                treeUri
            } else if (tree) {
                DocumentsContract.buildTreeDocumentUri(treeUri.authority, targetDocId)
            } else {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
            }
        }
        if (!FsPath.isLocalFile(norm)) return null
        val primaryNorm = FsPath.normalize(primaryStorageRoot)
        if (norm == primaryNorm || norm.startsWith("$primaryNorm/")) {
            val rel = norm.removePrefix(primaryNorm).trimStart('/')
            val docId = "primary:$rel"
            return if (tree) {
                DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
            } else {
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
            }
        }
        if (norm.startsWith("/storage/")) {
            val afterStorage = norm.removePrefix("/storage/")
            val volume = afterStorage.substringBefore('/')
            if (volume.isNotBlank() && volume != "emulated" && volume != "self") {
                val rel = afterStorage.substringAfter('/', "")
                val docId = "$volume:$rel"
                return if (tree) {
                    DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
                } else {
                    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
                }
            }
        }
        return null
    }

    /**
     * Converts an incoming [DocumentsContract] root, tree, or document [uri] (from `ACTION_BROWSE`
     * or `ACTION_MANAGE_DOCUMENT`) into a navigable Wayfinder path.
     */
    fun toWayfinderPath(
        uri: Uri?,
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }.getOrDefault("/storage/emulated/0"),
    ): String? {
        if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val auth = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        val docId = runCatching {
            val segs = uri.pathSegments.orEmpty()
            val docIdx = segs.indexOf("document")
            val treeIdx = segs.indexOf("tree")
            val rootIdx = segs.indexOf("root")
            when {
                docIdx >= 0 && docIdx + 1 < segs.size -> segs[docIdx + 1]
                treeIdx >= 0 && treeIdx + 1 < segs.size -> segs[treeIdx + 1]
                rootIdx >= 0 && rootIdx + 1 < segs.size -> {
                    val rootId = segs[rootIdx + 1]
                    if (auth == EXTERNAL_STORAGE_AUTHORITY && rootId == "primary") "primary:" else rootId
                }
                else -> null
            }
        }.getOrNull() ?: return null

        if (auth == EXTERNAL_STORAGE_AUTHORITY) {
            if (docId.startsWith("primary:")) {
                val rel = docId.removePrefix("primary:").trimStart('/')
                return if (rel.isEmpty()) primaryStorageRoot else FsPath.child(primaryStorageRoot, rel)
            }
            if (docId.contains(':')) {
                val vol = docId.substringBefore(':')
                val rel = docId.substringAfter(':').trimStart('/')
                if (vol.isNotBlank() && !vol.contains('/')) {
                    return if (rel.isEmpty()) "/storage/$vol" else "/storage/$vol/$rel"
                }
            }
        }
        return "doc://$auth/$docId"
    }

    /** Discovers all accessible DocumentsProvider roots on the system. */
    fun discoverRoots(context: Context): List<DiscoveredRoot> {
        val roots = mutableListOf<DiscoveredRoot>()
        val pm = context.packageManager
        val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")
        val providers = runCatching { pm.queryIntentContentProviders(intent, 0) }.getOrDefault(emptyList())

        for (ri in providers) {
            val auth = ri.providerInfo?.authority ?: continue
            val rootsUri = runCatching { DocumentsContract.buildRootsUri(auth) }.getOrNull() ?: continue
            runCatching {
                context.contentResolver.query(rootsUri, null, null, null, null)?.use { c ->
                    val rootIdCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                    val titleCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                    val docIdCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    val flagsCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
                    val summaryCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_SUMMARY)

                    while (c.moveToNext()) {
                        val rootId = if (rootIdCol >= 0) c.getString(rootIdCol) else ""
                        val title = if (titleCol >= 0) c.getString(titleCol) else ""
                        val docId = if (docIdCol >= 0) c.getString(docIdCol) else ""
                        val flags = if (flagsCol >= 0) c.getInt(flagsCol) else 0
                        val summary = if (summaryCol >= 0) c.getString(summaryCol) else null
                        if (docId.isNotBlank()) {
                            val root = DiscoveredRoot(auth, rootId, title, docId, flags, summary)
                            val rootPath = "doc://$auth/$docId"
                            val rootLabel = if (!summary.isNullOrBlank() && summary.contains("@")) {
                                "$title ($summary)"
                            } else {
                                title.ifBlank { auth }
                            }
                            registerNode(rootPath, rootLabel, parentPath = null)
                            roots.add(root)
                            Log.d(TAG, "Discovered root: auth=$auth, title=$title, rootId=$rootId, docId=$docId")
                        }
                    }
                }
            }.onFailure { e ->
                Log.d(TAG, "Cannot query roots for $auth: ${e.message}")
            }
        }
        return roots
    }

    /** Lists children of [documentId] under [authority] using direct DocumentsContract query. */
    fun listChildren(context: Context, authority: String, documentId: String): List<FileEntry>? {
        return runCatching {
            val resolvedDocId = normalizeDocId(authority, documentId)
            val parentPath = "doc://$authority/$resolvedDocId"
            val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, resolvedDocId)
            val isQuickAccess = authority == PhoneDetector.QUICK_ACCESS_AUTHORITY
            queryWithLoadingWait(
                cr = context.contentResolver,
                uri = childrenUri,
                projection = null,
                maxAttempts = if (isQuickAccess) 8 else 6,
                waitMs = if (isQuickAccess) 2000L else 1500L,
            ) { c ->
                val docIdCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val summaryCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SUMMARY)

                val list = mutableListOf<FileEntry>()
                while (c.moveToNext()) {
                    val childDocId = c.getString(docIdCol) ?: continue
                    val name = safeDisplayName(c.getString(nameCol)) ?: continue
                    val mime = c.getString(mimeCol) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (isDir || c.isNull(sizeCol)) 0L else c.getLong(sizeCol).coerceAtLeast(0L)
                    val mod = if (c.isNull(modCol)) 0L else c.getLong(modCol)
                    val childPath = "doc://$authority/$childDocId"
                    registerNode(childPath, name, parentPath)
                    val docSummary = if (summaryCol >= 0 && !c.isNull(summaryCol)) c.getString(summaryCol)?.takeIf { it.isNotBlank() } else null
                    val defaultSubtitle = when {
                        authority == GoogleDriveManager.DOCS_AUTHORITY -> "Google Drive"
                        authority.contains("mtp") || authority.contains("quickaccess") || authority.contains("phone") -> "Connected Phone"
                        GooglebookEcosystem.isLinuxAuthority(authority) -> "Linux files"
                        else -> "Documents"
                    }

                    list.add(
                        FileEntry(
                            path = childPath,
                            name = name,
                            isDir = isDir,
                            size = size,
                            modified = if (mod > 0) mod else 0L,
                            subtitle = docSummary ?: defaultSubtitle,
                        )
                    )
                }
                list.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }.onFailure { e ->
            Log.d(TAG, "Failed to list children for $authority / $documentId: ${e.message}")
        }.getOrNull()
    }
}

/**
 * FileSystem implementation for direct DocumentsContract access (`doc://<authority>/<documentId>`).
 */
class DocFileSystem(private val context: Context) : RemoteFileSystem {
    override fun disconnect(key: String) {}

    private fun parse(path: String): Pair<String, String> {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = SystemDocumentsBridge.normalizeDocId(auth, trimmed.substringAfter('/'))
        return auth to docId
    }

    override fun list(path: String): List<FileEntry> {
        val (auth, docId) = parse(path)
        return SystemDocumentsBridge.listChildren(context, auth, docId)
            ?: throw IOException("Cannot read folder: $path (Permission denied or device disconnected)")
    }

    override fun stat(path: String): FileEntry? {
        val (auth, docId) = parse(path)
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUri(auth, docId)
            context.contentResolver.query(docUri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = safeDisplayName(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))) ?: "Item"
                    val mime = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (isDir) 0L else c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)).coerceAtLeast(0L)
                    val mod = c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    val summaryCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SUMMARY)
                    val summary = if (summaryCol >= 0 && !c.isNull(summaryCol)) c.getString(summaryCol)?.takeIf { it.isNotBlank() } else null
                    FileEntry("doc://$auth/$docId", name, isDir, size, if (mod > 0) mod else System.currentTimeMillis(), subtitle = summary)
                } else null
            }
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val (auth, docId) = parse(path)
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        return context.contentResolver.openInputStream(docUri)
            ?: throw IOException("Cannot open $path")
    }

    override fun openOutput(path: String): OutputStream {
        val (auth, docId) = parse(path)
        val existing = stat(path)
        val targetDocId = if (existing != null) {
            docId
        } else {
            val (_, parentDocId, name) = SystemDocumentsBridge.splitParentAndChild(path)
                ?: throw IOException("Invalid parent folder for $path")
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            val parentUri = DocumentsContract.buildDocumentUri(auth, parentDocId)
            val createdUri = DocumentsContract.createDocument(context.contentResolver, parentUri, mime, name)
                ?: throw IOException("Cannot create $name in $parentDocId")
            val createdId = DocumentsContract.getDocumentId(createdUri)
            SystemDocumentsBridge.registerNode("doc://$auth/$createdId", name, "doc://$auth/$parentDocId")
            createdId
        }
        val docUri = DocumentsContract.buildDocumentUri(auth, targetDocId)
        return runCatching { context.contentResolver.openOutputStream(docUri, "w") }.getOrNull()
            ?: context.contentResolver.openOutputStream(docUri)
            ?: throw IOException("Cannot write to $path")
    }

    override fun mkdir(path: String) {
        val (auth, parentDocId, name) = SystemDocumentsBridge.splitParentAndChild(path)
            ?: throw IOException("Invalid parent folder for $path")
        FsPath.nameError(name)?.let { throw IOException(it) }
        val parentUri = DocumentsContract.buildDocumentUri(auth, parentDocId)
        val createdUri = DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Failed to create folder $name")
        val createdId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull()
        if (!createdId.isNullOrBlank()) {
            SystemDocumentsBridge.registerNode("doc://$auth/$createdId", name, "doc://$auth/$parentDocId")
        }
    }

    override fun createFile(path: String) {
        createFileWithMime(path, null)
    }

    fun createFileWithMime(path: String, explicitMime: String? = null): String {
        val (auth, parentDocId, name) = SystemDocumentsBridge.splitParentAndChild(path)
            ?: throw IOException("Invalid parent folder for $path")
        FsPath.nameError(name)?.let { throw IOException(it) }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = explicitMime?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val parentUri = DocumentsContract.buildDocumentUri(auth, parentDocId)
        val createdUri = DocumentsContract.createDocument(context.contentResolver, parentUri, mime, name)
            ?: throw IOException("Failed to create file $name")
        val createdId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrDefault("$parentDocId/$name")
        val createdPath = "doc://$auth/$createdId"
        SystemDocumentsBridge.registerNode(createdPath, name, "doc://$auth/$parentDocId")
        return createdPath
    }

    override fun delete(path: String) {
        val (auth, docId) = parse(path)
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        if (!DocumentsContract.deleteDocument(context.contentResolver, docUri)) {
            throw IOException("Failed to delete $path")
        }
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val (auth, docId) = parse(path)
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        val resultUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
            ?: throw IOException("Failed to rename $path")
        val newDocId = DocumentsContract.getDocumentId(resultUri)
        return "doc://$auth/$newDocId"
    }
}
