// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.util.Locale

/** Candidate activity resolved for a system Files directory-browsing intent. */
data class FilesActivityCandidate(
    val packageName: String,
    val activityName: String,
    val holdsManageDocuments: Boolean,
)

/**
 * Resolves and constructs explicit intents targeting only the platform's system Files app
 * (which holds [Manifest.permission.MANAGE_DOCUMENTS]) to open the folder containing an APK.
 *
 * Wayfinder intentionally omits `REQUEST_INSTALL_PACKAGES` and never installs packages. Rather than
 * letting Android's `PackageInstaller` silently abort when a user tries to open an `.apk`, Wayfinder
 * can hand off the containing folder's [DocumentsContract] URI directly to the privileged system
 * Files app. Targeting the verified `MANAGE_DOCUMENTS` component explicitly and omitting
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] prevents unprivileged third-party file managers from
 * intercepting the intent and avoids `UriGrantsManagerService` `SecurityException` crashes on
 * retail `user` builds.
 */
object SystemFilesHandoff {
    const val ACTION_BROWSE = "android.provider.action.BROWSE"
    const val FILES_APP_PACKAGE = "com.google.android.filesapp"
    const val GOOGLE_DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
    const val AOSP_DOCUMENTS_UI_PACKAGE = "com.android.documentsui"

    /** True when [nameOrPath] refers to an Android `.apk` package archive. */
    fun isApkFile(nameOrPath: String): Boolean {
        if (nameOrPath.isBlank() || nameOrPath.contains('\u0000')) return false
        val name = FsPath.name(nameOrPath).ifEmpty { nameOrPath }.trim()
        return name.lowercase(Locale.US).endsWith(".apk") && name.length > 4
    }

    /**
     * Returns the enclosing folder path of [apkPath] when it lives on a location representable by
     * `DocumentsContract` (shared storage, removable storage, SAF mount, or `doc://` mount), or
     * `null` for remote shares, archives, virtual roots, or malformed paths.
     */
    fun eligibleParentFolder(
        apkPath: String,
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }
            .getOrDefault("/storage/emulated/0"),
    ): String? {
        if (apkPath.isBlank() || apkPath.contains('\u0000')) return null
        val norm = FsPath.normalize(apkPath)
        if (FsPath.isZip(norm) || FsPath.isVirtual(norm) || FsPath.isPhone(norm)) return null
        val parent = FsPath.parent(norm) ?: return null
        if (FsPath.isSaf(norm) || FsPath.isDoc(norm)) return parent
        if (!FsPath.isLocalFile(norm)) return null
        val primaryNorm = FsPath.normalize(primaryStorageRoot)
        if (parent == primaryNorm || parent.startsWith("$primaryNorm/")) return parent
        if (parent.startsWith("/storage/")) {
            val afterStorage = parent.removePrefix("/storage/")
            val volume = afterStorage.substringBefore('/')
            if (volume.isNotBlank() && volume != "emulated" && volume != "self") return parent
        }
        return null
    }

    /**
     * Converts the enclosing folder of [apkPath] into a [DocumentsContract] document URI suitable
     * for opening in the system Files app (e.g.
     * `content://com.android.externalstorage.documents/document/primary%3ADownload`), or `null` when
     * the APK is not in a location accessible via `DocumentsContract`.
     */
    fun folderDocumentsUriForApk(
        apkPath: String,
        safMounts: List<SafMount> = emptyList(),
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }
            .getOrDefault("/storage/emulated/0"),
    ): Uri? {
        val parent = eligibleParentFolder(apkPath, primaryStorageRoot) ?: return null
        val uri = SystemDocumentsBridge.toDocumentsContractUri(
            path = parent,
            tree = false,
            safMounts = safMounts,
            primaryStorageRoot = primaryStorageRoot,
        ) ?: return null
        return uri.takeIf { it.scheme == "content" }
    }

    /**
     * Selects the highest-priority candidate activity whose package holds
     * [Manifest.permission.MANAGE_DOCUMENTS] and does not belong to [selfPackageName].
     * Pure JVM logic for deterministic unit testing.
     */
    fun selectPrivilegedFilesHandler(
        candidates: List<FilesActivityCandidate>,
        selfPackageName: String,
    ): FilesActivityCandidate? {
        val eligible = candidates.filter { c ->
            c.holdsManageDocuments &&
                c.packageName.isNotBlank() &&
                c.activityName.isNotBlank() &&
                !c.packageName.contains('\u0000') &&
                !c.activityName.contains('\u0000') &&
                c.packageName != selfPackageName
        }
        if (eligible.isEmpty()) return null
        return eligible.minByOrNull { priorityRank(it.packageName) }
    }

    internal fun priorityRank(packageName: String): Int {
        val pkg = packageName.lowercase(Locale.US)
        return when {
            pkg == FILES_APP_PACKAGE -> 0
            pkg == GOOGLE_DOCUMENTS_UI_PACKAGE -> 1
            pkg == AOSP_DOCUMENTS_UI_PACKAGE -> 2
            pkg.contains("filesapp") -> 3
            pkg.contains("documentsui") -> 4
            else -> 5
        }
    }

    /**
     * Builds an explicit [Intent] targeting only a system Files / DocumentsUI activity whose
     * package holds [Manifest.permission.MANAGE_DOCUMENTS] to open [folderUri].
     *
     * Deliberately omits [Intent.FLAG_GRANT_READ_URI_PERMISSION]: the target holds
     * `MANAGE_DOCUMENTS` natively, and requesting a URI grant on an un-owned `DocumentsProvider`
     * URI would risk `SecurityException` on retail `user` builds.
     */
    fun buildOpenFolderIntent(context: Context, folderUri: Uri): Intent? {
        if (folderUri.scheme != "content") return null
        val pm = context.packageManager
        val selfPkg = context.packageName
        val probes = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(ACTION_BROWSE).setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(ACTION_BROWSE, folderUri),
        )
        for (probe in probes) {
            val resolved = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            val candidates = resolved.mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName.orEmpty()
                val cls = ai.name.orEmpty()
                if (pkg.isBlank() || cls.isBlank()) return@mapNotNull null
                val holdsPerm = runCatching {
                    pm.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, pkg) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                FilesActivityCandidate(pkg, cls, holdsPerm)
            }
            val chosen = selectPrivilegedFilesHandler(candidates, selfPkg)
            if (chosen != null) {
                return Intent(probe).setClassName(chosen.packageName, chosen.activityName)
            }
        }
        return null
    }
}
