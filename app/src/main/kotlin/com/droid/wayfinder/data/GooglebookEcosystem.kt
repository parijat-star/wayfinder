// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.UserHandle
import android.os.UserManager
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.ui.RootVolume
import java.io.File

/**
 * Represents a managed Work Profile or cross-profile target discoverable via [CrossProfileApps].
 */
data class WorkProfileTarget(
    val id: String,
    val label: String,
    val userHandle: UserHandle? = null,
)

/**
 * Googlebook 1P ecosystem integrations:
 * 1. Files AI / Context Engine summary (`COLUMN_SUMMARY`) & local text content search matching.
 * 2. Linux development environment (Terminal / Crostini VM) root discovery and file sharing.
 * 3. Enterprise `UserManager` policy guardrails and `CrossProfileApps` Work Profile switching.
 */
object GooglebookEcosystem {
    /** Maximum file size (64 KB) for in-memory text/code snippet indexing during search. */
    const val MAX_SEARCHABLE_TEXT_BYTES = 64L * 1024L

    /** Maximum number of bytes read from a local text/code file when matching content queries. */
    private const val MAX_SNIPPET_READ_BYTES = 8192

    /** Well-known DocumentsProvider authorities for the Googlebook / Android Virtualization Linux VM. */
    val LINUX_AUTHORITIES = setOf(
        "com.android.virtualization.terminal.guestdocuments",
        "com.google.android.virtualization.terminal.guestdocuments",
        "com.android.virtualization.terminal.documents",
        "com.google.android.virtualization.terminal.documents",
        "org.chromium.arc.crostini.documents",
    )

    fun isLinuxAuthority(authority: String?): Boolean {
        if (authority.isNullOrBlank()) return false
        val lower = authority.substringAfter('@').lowercase()
        return lower in LINUX_AUTHORITIES ||
            lower.contains("crostini") ||
            lower.contains("terminal.guestdocuments") ||
            lower.contains("terminal.documents") ||
            lower.contains("linuxfiles")
    }

    /** Default root ID advertised by AVF Terminal's `GuestDocumentsProvider` (`content://.../root/guest_root`). */
    const val AVF_GUEST_ROOT_ID = "guest_root"

    /**
     * Builds a valid 2-segment `DocumentsContract.buildRootUri(authority, rootId)` (`content://<authority>/root/<id>`)
     * for `DocumentsContract.EXTRA_INITIAL_URI`. Passing 1-segment `buildRootsUri` (`/root`) is rejected by
     * `DocumentsUI.launchToInitialUri()` (`DocumentsContract.isRootUri == false`), causing `DocumentsUI` to
     * fall back to the last-accessed folder instead of opening `Linux files`.
     */
    fun buildInitialLinuxRootUri(authority: String): android.net.Uri {
        val clean = authority.substringAfter('@')
        val rootId = if (clean.contains("guestdocuments", ignoreCase = true)) AVF_GUEST_ROOT_ID else "linuxfiles"
        return android.provider.DocumentsContract.buildRootUri(clean, rootId)
    }

    /**
     * Returns the installed Linux `DocumentsProvider` authority (such as AVF Terminal's
     * `com.android.virtualization.terminal.guestdocuments`) when registered on the device,
     * even on `user` builds where `/root` queries require an `ACTION_OPEN_DOCUMENT_TREE` grant.
     */
    fun findInstalledLinuxProviderAuthority(context: Context): String? {
        val pm = context.packageManager
        return LINUX_AUTHORITIES.firstOrNull { auth ->
            runCatching { pm.resolveContentProvider(auth, 0) != null }.getOrDefault(false)
        }
    }

    /**
     * Discovers the Googlebook Linux development environment root volume via:
     * 1. Accessible system `DocumentsProvider` roots (`GuestDocumentsProvider` / Crostini),
     * 2. Persisted SAF tree grants ([safMounts]) pointing at a Linux `DocumentsProvider` (used on
     *    `user` builds where `/root` requires `MANAGE_DOCUMENTS`), or
     * 3. A mounted `LinuxFiles` shared directory.
     */
    fun discoverLinuxRoot(
        context: Context,
        primaryStorage: String,
        discoveredRoots: List<DiscoveredRoot> = SystemDocumentsBridge.discoverRoots(context),
        safMounts: List<SafMount> = emptyList(),
        candidateDirs: List<File> = listOf(
            File("/mnt/chromeos/LinuxFiles"),
            File(primaryStorage, "LinuxFiles"),
        ),
    ): RootVolume? {
        for (r in discoveredRoots) {
            if (isLinuxAuthority(r.authority) || r.title.equals("Linux files", ignoreCase = true)) {
                val docId = r.documentId.ifBlank { "root" }
                return RootVolume(
                    path = "doc://${r.authority}/$docId",
                    label = r.title.ifBlank { "Linux files" },
                    removable = false,
                    subtitle = r.summary?.takeIf { it.isNotBlank() } ?: "Linux development environment",
                )
            }
        }
        for (sm in safMounts) {
            val uriAuth = runCatching { android.net.Uri.parse(sm.uri).authority }.getOrNull()
            if (isLinuxAuthority(uriAuth)) {
                return RootVolume(
                    path = sm.path,
                    label = sm.label.ifBlank { "Linux files" },
                    removable = false,
                    subtitle = "Linux development environment",
                )
            }
        }
        for (dir in candidateDirs) {
            if (dir.isDirectory && dir.canRead()) {
                return RootVolume(
                    path = dir.absolutePath,
                    label = "Linux files",
                    removable = false,
                    subtitle = "Linux development environment",
                )
            }
        }
        return null
    }

    /**
     * Evaluates whether [entry] matches the folded search query [foldedNeedle] across:
     * 1. File/folder display name (substring or multi-token match).
     * 2. Files AI / Context Engine summary stored in [FileEntry.subtitle] (`COLUMN_SUMMARY`).
     * 3. Local text/code/markdown file contents (up to [MAX_SNIPPET_READ_BYTES] for files <= 64 KB).
     */
    fun matchesSearchQuery(
        entry: FileEntry,
        foldedNeedle: String,
        readTextSnippet: ((FileEntry) -> String?)? = null,
    ): Boolean {
        if (foldedNeedle.isBlank()) return true
        val foldedName = foldForSearch(entry.name)
        if (foldedName.contains(foldedNeedle)) return true

        val foldedSubtitle = entry.subtitle?.let { foldForSearch(it) }.orEmpty()
        if (foldedSubtitle.isNotEmpty() && foldedSubtitle.contains(foldedNeedle)) return true

        val tokens = foldedNeedle.split(' ').filter { it.isNotEmpty() }
        val combinedMeta = if (foldedSubtitle.isEmpty()) foldedName else "$foldedName $foldedSubtitle"
        if (tokens.size > 1 && tokens.all { combinedMeta.contains(it) }) return true

        if (!entry.isDir && !entry.isLink && FsPath.isLocalFile(entry.path) &&
            entry.size in 1..MAX_SEARCHABLE_TEXT_BYTES &&
            (entry.kind == FileKind.Text || entry.kind == FileKind.Code)
        ) {
            val snippet = (readTextSnippet ?: ::defaultReadTextSnippet).invoke(entry)
            if (!snippet.isNullOrBlank()) {
                val foldedSnippet = foldForSearch(snippet)
                if (foldedSnippet.contains(foldedNeedle)) return true
                val fullHaystack = "$combinedMeta $foldedSnippet"
                if (tokens.size > 1 && tokens.all { fullHaystack.contains(it) }) return true
            }
        }
        return false
    }

    private fun defaultReadTextSnippet(entry: FileEntry): String? = runCatching {
        File(entry.path).inputStream().use { inp ->
            val buf = ByteArray(MAX_SNIPPET_READ_BYTES)
            val n = inp.read(buf).coerceAtLeast(0)
            if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull()

    /** True when enterprise policy (`DISALLOW_USB_FILE_TRANSFER`) blocks USB file transfers. */
    fun isUsbFileTransferDisallowed(context: Context): Boolean = runCatching {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        um.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)
    }.getOrDefault(false)

    /** True when enterprise policy (`DISALLOW_MOUNT_PHYSICAL_MEDIA`) blocks external physical media mounts. */
    fun isPhysicalMediaMountDisallowed(context: Context): Boolean = runCatching {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        um.hasUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
    }.getOrDefault(false)

    /**
     * Discovers available Work / Personal cross-profile targets via [CrossProfileApps].
     */
    fun discoverWorkProfiles(context: Context): List<WorkProfileTarget> = runCatching {
        val cpa = context.getSystemService(CrossProfileApps::class.java) ?: return emptyList()
        cpa.targetUserProfiles.map { handle ->
            val label = runCatching { cpa.getProfileSwitchingLabel(handle).toString() }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Work profile"
            WorkProfileTarget(
                id = handle.hashCode().toString(),
                label = label,
                userHandle = handle,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Switches to the target Work / Personal profile's Wayfinder activity via [CrossProfileApps].
     */
    fun switchToWorkProfile(context: Context, target: WorkProfileTarget): Boolean = runCatching {
        val handle = target.userHandle ?: return false
        val cpa = context.getSystemService(CrossProfileApps::class.java) ?: return false
        cpa.startMainActivity(ComponentName(context, MainActivity::class.java), handle)
        true
    }.getOrDefault(false)
}
