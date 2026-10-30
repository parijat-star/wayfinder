// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object GoogleDriveManager {
    const val DOCS_AUTHORITY = "com.google.android.apps.docs.storage"
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    fun extractEmail(text: String?): String? =
        text?.let { EMAIL_REGEX.find(it)?.value }

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

        // 1. Check accounts granted explicit per-app visibility via AccountManager.newChooseAccountIntent
        // (does not require READ_CONTACTS or GET_ACCOUNTS permissions on API 26+)
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

        // Map each account email to an existing SAF mount or direct doc root if present
        return emails.map { email ->
            val docMatch = docRoots.firstOrNull { r ->
                val s = (r.summary ?: "").lowercase()
                val t = r.title.lowercase()
                val e = email.lowercase()
                s.contains(e) || t.contains(e)
            }
            val match = mounts.firstOrNull { m ->
                val u = m.uri.lowercase()
                val l = m.label.lowercase()
                val e = email.lowercase()
                val mappedEmail = uriToEmail[m.uri]?.lowercase()
                (u.contains("docs.storage") || l.contains("drive")) &&
                    (mappedEmail == e || u.contains(e) || l.contains(e))
            } ?: mounts.firstOrNull { m ->
                // If there is only one Google Drive mount, bind it to the primary account
                email == emails.first() && (m.uri.contains("docs.storage") || m.label.contains("Drive", ignoreCase = true))
            }
            // Prefer SAF mount when present (works with or without MANAGE_DOCUMENTS), falling back to doc:// root
            val mountPath = match?.path ?: docMatch?.let { "doc://${it.authority}/${it.documentId}" }
            DriveAccount(
                email = email,
                title = "Drive",
                mountPath = mountPath,
                isMounted = mountPath != null,
            )
        }
    }
}
