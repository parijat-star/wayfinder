// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object GoogleDriveManager {
    const val DOCS_AUTHORITY = "com.google.android.apps.docs.storage"
    const val DEFAULT_DRIVE_ROOT_ID = "1"
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val DRIVE_ACCOUNT_ID_REGEX = Regex("acc=(\\d+);")

    fun extractEmail(text: String?): String? =
        text?.let { EMAIL_REGEX.find(it)?.value }

    /**
     * Extracts the numeric per-account root ID (`"1"`, `"2"`, ...) from a Google Drive
     * `com.google.android.apps.docs.storage` document or tree URI (`acc=<id>;<doc>`).
     */
    fun extractDriveAccountRootId(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull()
        val treeOrDocId = parsed?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
                ?: runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
        }.orEmpty()
        val candidate = treeOrDocId.ifEmpty { Uri.decode(uriString) }
        return DRIVE_ACCOUNT_ID_REGEX.find(candidate)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * Formats a mounted Google Drive folder title for the sidebar (e.g. `"Drive · Projects"`
     * from `"Drive (alex@example.com) · Projects"` or `"Google Drive · Projects"`), keeping
     * `"Drive"` when no subfolder name is present or when the folder name is `"My Drive"` / the email itself.
     */
    fun formatDriveMountTitle(label: String, email: String? = null): String {
        val trimmed = label.trim()
        val suffix = if (trimmed.contains('·')) trimmed.substringAfterLast('·').trim() else ""
        if (suffix.isNotEmpty() &&
            !suffix.equals("My Drive", ignoreCase = true) &&
            !suffix.equals("Drive", ignoreCase = true) &&
            !suffix.equals("Google Drive", ignoreCase = true) &&
            (email == null || !suffix.equals(email, ignoreCase = true)) &&
            extractEmail(suffix) == null
        ) {
            return "Drive · $suffix"
        }
        return "Drive"
    }

    /**
     * Builds a valid 2-segment `content://com.google.android.apps.docs.storage/root/<rootId>` URI
     * for [DocumentsContract.EXTRA_INITIAL_URI] so system `DocumentsUI` opens directly inside
     * Google Drive instead of rejecting a 1-segment `/root` URI and falling back to its last-opened
     * local folder (such as `Movies`).
     */
    fun buildInitialDriveRootUri(
        context: Context?,
        accountEmail: String?,
        mounts: List<SafMount> = emptyList(),
    ): Uri {
        val docRoots = if (context != null) {
            runCatching {
                SystemDocumentsBridge.discoverRoots(context)
                    .filter { it.authority == DOCS_AUTHORITY && it.rootId.isNotBlank() }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        if (accountEmail != null) {
            docRoots.firstOrNull { r ->
                r.summary?.contains(accountEmail, ignoreCase = true) == true ||
                    r.title.contains(accountEmail, ignoreCase = true)
            }?.let { match ->
                return DocumentsContract.buildRootUri(DOCS_AUTHORITY, match.rootId)
            }
        } else if (docRoots.isNotEmpty()) {
            return DocumentsContract.buildRootUri(DOCS_AUTHORITY, docRoots.first().rootId)
        }

        val driveMounts = mounts.filter {
            it.uri.contains(DOCS_AUTHORITY) || it.label.contains("Drive", ignoreCase = true)
        }
        if (accountEmail != null) {
            driveMounts.firstNotNullOfOrNull { m ->
                val mountEmail = extractEmail(m.label) ?: extractEmail(m.uri)
                if (mountEmail?.equals(accountEmail, ignoreCase = true) == true) {
                    extractDriveAccountRootId(m.uri)
                } else {
                    null
                }
            }?.let { existingId ->
                return DocumentsContract.buildRootUri(DOCS_AUTHORITY, existingId)
            }

            val occupiedByOtherEmails = driveMounts.mapNotNull { m ->
                val mountEmail = extractEmail(m.label) ?: extractEmail(m.uri)
                if (mountEmail != null && !mountEmail.equals(accountEmail, ignoreCase = true)) {
                    extractDriveAccountRootId(m.uri)
                } else {
                    null
                }
            }.toSet()

            if (context != null) {
                val visibleAccounts = runCatching {
                    AccountManager.get(context).getAccountsByType("com.google")
                        .mapNotNull { it.name.trim().takeIf { n -> n.contains("@") } }
                }.getOrDefault(emptyList())
                val accountIndex = visibleAccounts.indexOfFirst { it.equals(accountEmail, ignoreCase = true) }
                if (accountIndex >= 0) {
                    val candidateId = (accountIndex + 1).toString()
                    if (candidateId !in occupiedByOtherEmails) {
                        return DocumentsContract.buildRootUri(DOCS_AUTHORITY, candidateId)
                    }
                }
            }

            val fallbackId = (1..16).map { it.toString() }.firstOrNull { it !in occupiedByOtherEmails }
                ?: DEFAULT_DRIVE_ROOT_ID
            return DocumentsContract.buildRootUri(DOCS_AUTHORITY, fallbackId)
        }

        val firstKnownId = driveMounts.firstNotNullOfOrNull { extractDriveAccountRootId(it.uri) }
            ?: DEFAULT_DRIVE_ROOT_ID
        return DocumentsContract.buildRootUri(DOCS_AUTHORITY, firstKnownId)
    }

    /**
     * Queries the root document display name of a Google Drive tree URI (which returns the
     * account email address for top-level Drive account trees like `acc=1;0`).
     */
    fun queryTreeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim() else null
        }
    }.getOrNull()

    /**
     * Discovers all Google Drive accounts configured on this device, mapping them to
     * any existing SAF mounts or direct DocumentsProvider roots if already granted.
     */
    fun discoverAccounts(context: Context?, mounts: List<SafMount>): List<DriveAccount> {
        val emails = LinkedHashSet<String>()
        val uriToEmail = HashMap<String, String>()

        // 1. Try querying AccountManager (requires READ_CONTACTS or account visibility on API 26+)
        if (context != null) {
            runCatching {
                val am = AccountManager.get(context)
                am.getAccountsByType("com.google").forEach { acc ->
                    val name = acc.name?.trim().orEmpty()
                    if (name.contains("@") && !name.contains(" ")) {
                        emails.add(name)
                    }
                }
            }
        }

        // 2. Also surface any account emails advertised by the system Drive DocumentsProvider roots
        val docRoots = if (context != null) {
            runCatching {
                SystemDocumentsBridge.discoverRoots(context).filter { it.authority == DOCS_AUTHORITY }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        docRoots.forEach { r ->
            val summary = r.summary?.trim().orEmpty()
            if (summary.contains("@") && !summary.contains(" ")) {
                emails.add(summary)
            }
        }

        // 3. Inspect persisted URI permissions and active SAF mounts for Google Drive trees
        val candidateUris = LinkedHashSet<String>()
        mounts.filter { it.uri.contains(DOCS_AUTHORITY) || it.label.contains("Drive", ignoreCase = true) }.forEach { m ->
            candidateUris.add(m.uri)
            extractEmail(m.label)?.let { email ->
                emails.add(email)
                uriToEmail[m.uri] = email
            }
            extractEmail(m.uri)?.let { email ->
                emails.add(email)
                uriToEmail[m.uri] = email
            }
        }
        if (context != null) {
            runCatching {
                context.contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission && it.uri.authority == DOCS_AUTHORITY }
                    .forEach { perm -> candidateUris.add(perm.uri.toString()) }
            }
            for (uriStr in candidateUris) {
                val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: continue
                val displayName = queryTreeDisplayName(context, parsed).orEmpty()
                val email = extractEmail(displayName)
                if (email != null) {
                    emails.add(email)
                    uriToEmail[uriStr] = email
                }
            }
        }

        // Map each account email to all matching SAF mounts (preserving mounted folder names) or direct doc root
        val driveMounts = mounts.filter {
            it.uri.contains("docs.storage", ignoreCase = true) || it.label.contains("Drive", ignoreCase = true)
        }
        val assignedPaths = HashSet<String>()
        val results = ArrayList<DriveAccount>()

        val explicitMatchesByEmail = LinkedHashMap<String, MutableList<SafMount>>()
        val sqlIdsByEmail = HashMap<String, MutableSet<String>>()
        for (email in emails) {
            val e = email.lowercase()
            val list = driveMounts.filter { m ->
                val u = m.uri.lowercase()
                val l = m.label.lowercase()
                val mappedEmail = uriToEmail[m.uri]?.lowercase()
                mappedEmail == e || u.contains(e) || l.contains(e)
            }.toMutableList()
            explicitMatchesByEmail[email] = list
            list.forEach { m ->
                assignedPaths += m.path
                extractDriveAccountRootId(m.uri)?.let { sqlId ->
                    sqlIdsByEmail.getOrPut(email) { HashSet() } += sqlId
                }
            }
        }

        // Second pass: bind unlabeled Google Drive SAF mounts by matching acc=<sqlId>; or primary account
        for (m in driveMounts) {
            if (m.path in assignedPaths || emails.isEmpty()) continue
            val sqlId = extractDriveAccountRootId(m.uri)
            val ownerEmail = if (sqlId != null) {
                emails.firstOrNull { email -> sqlIdsByEmail[email]?.contains(sqlId) == true }
            } else {
                null
            } ?: emails.first()
            explicitMatchesByEmail.getOrPut(ownerEmail) { mutableListOf() } += m
            assignedPaths += m.path
        }

        for (email in emails) {
            val matchedMounts = explicitMatchesByEmail[email].orEmpty()
            if (matchedMounts.isNotEmpty()) {
                for (m in matchedMounts) {
                    results += DriveAccount(
                        email = email,
                        title = formatDriveMountTitle(m.label, email),
                        mountPath = m.path,
                        isMounted = true,
                    )
                }
            } else {
                val docMatch = docRoots.firstOrNull { r ->
                    val s = (r.summary ?: "").lowercase()
                    val t = r.title.lowercase()
                    val e = email.lowercase()
                    s.contains(e) || t.contains(e)
                }
                val docPath = docMatch?.let { "doc://${it.authority}/${it.documentId}" }
                results += DriveAccount(
                    email = email,
                    title = "Drive",
                    mountPath = docPath,
                    isMounted = docPath != null,
                )
            }
        }
        return results
    }
}
