// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Connected Dropbox account metadata (Personal Basic/Plus/Professional or Dropbox Business/Team).
 *
 * [accountId] is the canonical, URL-authority-safe identifier used in `dropbox://<accountId>/...` paths.
 * When [rootNamespaceId] is non-blank (for Dropbox Business Team Space accounts where the team root
 * differs from the member home namespace), [DropboxRestFileSystem] attaches the `Dropbox-API-Path-Root`
 * header so shared team folders appear directly under the root listing.
 */
data class DropboxAccount(
    val accountId: String,
    val email: String,
    val displayName: String = "",
    val aliasLabel: String = "",
    val accountType: String = "basic",
    val rootNamespaceId: String = "",
    val quotaUsed: Long = -1L,
    val quotaTotal: Long = -1L,
    val appKey: String = "",
) {
    val rootPath: String get() = "${RemoteUri.DROPBOX_SCHEME}://$accountId"

    val isBusiness: Boolean
        get() = accountType.equals("business", ignoreCase = true) || rootNamespaceId.isNotBlank()

    val sidebarTitle: String
        get() {
            val custom = aliasLabel.trim()
            if (custom.isNotEmpty()) return custom
            return if (isBusiness) "Dropbox · Team" else "Dropbox"
        }

    val sidebarSubtitle: String
        get() = if (quotaUsed >= 0L && quotaTotal > 0L) {
            "$email · ${formatSize(quotaUsed)} / ${formatSize(quotaTotal)}"
        } else {
            email
        }
}

data class DropboxTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/**
 * Persists connected [DropboxAccount] profiles and hardware-encrypted OAuth 2.0 refresh tokens.
 *
 * `dropbox_accounts.xml` is explicitly excluded from Android cloud backup and device-to-device
 * transfer in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.
 */
class DropboxAccountStore(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(alias = KEYSTORE_ALIAS),
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionTokens = ConcurrentHashMap<String, DropboxTokens>()

    companion object {
        const val PREFS_NAME = "dropbox_accounts"
        const val KEYSTORE_ALIAS = "wayfinder_dropbox"
        private const val PREFIX_ACC = "acc_"
        private const val PREFIX_TOK = "tok_"
        private const val KEY_DEFAULT_APP_KEY = "default_app_key"

        /** Sanitizes an email or Dropbox `dbid:...` into a safe lowercase authority segment for `dropbox://<accountId>/...`. */
        fun sanitizeAccountKey(raw: String): String =
            raw.trim().lowercase(Locale.ROOT)
                .replace(':', '-')
                .filter { ch ->
                    (ch in 'a'..'z') || (ch in '0'..'9') || ch == '@' || ch == '.' || ch == '-' || ch == '_' || ch == '+'
                }
                .take(128)

        fun isValidAccountKey(key: String): Boolean =
            key.isNotEmpty() &&
                key.length <= 128 &&
                key != "." &&
                key != ".." &&
                key.all { ch ->
                    (ch in 'a'..'z') || (ch in '0'..'9') || ch == '@' || ch == '.' || ch == '-' || ch == '_' || ch == '+'
                }
    }

    var defaultAppKey: String
        get() = prefs.getString(KEY_DEFAULT_APP_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DropboxAuthManager.BUNDLED_PUBLIC_APP_KEY
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_APP_KEY, value.trim()).apply()
        }

    @Synchronized
    fun accounts(): List<DropboxAccount> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX_ACC) }
            .sorted()
            .mapNotNull { prefKey ->
                val raw = prefs.getString(prefKey, null) ?: return@mapNotNull null
                decodeAccount(raw)
            }

    @Synchronized
    fun getAccount(accountId: String): DropboxAccount? {
        val key = sanitizeAccountKey(accountId)
        if (!isValidAccountKey(key)) return null
        val raw = prefs.getString(PREFIX_ACC + key, null) ?: return null
        return decodeAccount(raw)
    }

    @Synchronized
    fun getTokens(accountId: String): DropboxTokens? {
        val key = sanitizeAccountKey(accountId)
        if (!isValidAccountKey(key)) return null
        sessionTokens[key]?.let { return it }
        val encRefresh = prefs.getString(PREFIX_TOK + key, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = cipher.decrypt(encRefresh)?.takeIf { it.isNotBlank() } ?: return null
        val hydrated = DropboxTokens(accessToken = "", refreshToken = refresh, expiresAtMillis = 0L)
        sessionTokens[key] = hydrated
        return hydrated
    }

    @Synchronized
    fun save(account: DropboxAccount, tokens: DropboxTokens) {
        val key = sanitizeAccountKey(account.accountId.ifBlank { account.email })
        require(isValidAccountKey(key)) { "Invalid Dropbox account key" }
        val normalized = account.copy(accountId = key)
        sessionTokens[key] = tokens
        val editor = prefs.edit().putString(PREFIX_ACC + key, encodeAccount(normalized))
        if (tokens.refreshToken.isNotBlank()) {
            val enc = cipher.encrypt(tokens.refreshToken)
            if (!enc.isNullOrBlank()) {
                editor.putString(PREFIX_TOK + key, enc)
            } else {
                // Never persist plaintext refresh tokens if Keystore encryption is unavailable.
                editor.remove(PREFIX_TOK + key)
            }
        }
        editor.apply()
    }

    @Synchronized
    fun updateTokens(accountId: String, tokens: DropboxTokens) {
        val key = sanitizeAccountKey(accountId)
        if (!isValidAccountKey(key)) return
        val prev = sessionTokens[key]
        val mergedRefresh = tokens.refreshToken.ifBlank { prev?.refreshToken.orEmpty() }
        val merged = tokens.copy(refreshToken = mergedRefresh)
        sessionTokens[key] = merged
        if (mergedRefresh.isNotBlank()) {
            val enc = cipher.encrypt(mergedRefresh)
            if (!enc.isNullOrBlank()) {
                prefs.edit().putString(PREFIX_TOK + key, enc).apply()
            }
        }
    }

    @Synchronized
    fun invalidateAccessToken(accountId: String) {
        val key = sanitizeAccountKey(accountId)
        val cur = sessionTokens[key] ?: getTokens(key) ?: return
        sessionTokens[key] = cur.copy(accessToken = "", expiresAtMillis = 0L)
    }

    @Synchronized
    fun updateQuota(
        accountId: String,
        accountType: String?,
        rootNamespaceId: String?,
        used: Long,
        total: Long,
    ) {
        val existing = getAccount(accountId) ?: return
        val updated = existing.copy(
            accountType = accountType?.takeIf { it.isNotBlank() } ?: existing.accountType,
            rootNamespaceId = rootNamespaceId ?: existing.rootNamespaceId,
            quotaUsed = if (used >= 0L) used else existing.quotaUsed,
            quotaTotal = if (total > 0L) total else existing.quotaTotal,
        )
        prefs.edit().putString(PREFIX_ACC + existing.accountId, encodeAccount(updated)).apply()
    }

    @Synchronized
    fun remove(accountId: String) {
        val key = sanitizeAccountKey(accountId)
        sessionTokens.remove(key)
        prefs.edit()
            .remove(PREFIX_ACC + key)
            .remove(PREFIX_TOK + key)
            .apply()
    }

    private fun encodeAccount(a: DropboxAccount): String = JSONObject().apply {
        put("accountId", a.accountId)
        put("email", a.email)
        put("displayName", a.displayName)
        put("aliasLabel", a.aliasLabel)
        put("accountType", a.accountType)
        put("rootNamespaceId", a.rootNamespaceId)
        put("quotaUsed", a.quotaUsed)
        put("quotaTotal", a.quotaTotal)
        put("appKey", a.appKey)
    }.toString()

    private fun decodeAccount(raw: String): DropboxAccount? = runCatching {
        val obj = JSONObject(raw)
        val id = sanitizeAccountKey(obj.optString("accountId"))
        if (!isValidAccountKey(id)) return null
        val email = obj.optString("email").trim().ifEmpty { id }
        DropboxAccount(
            accountId = id,
            email = email,
            displayName = obj.optString("displayName"),
            aliasLabel = obj.optString("aliasLabel"),
            accountType = obj.optString("accountType", "basic"),
            rootNamespaceId = obj.optString("rootNamespaceId"),
            quotaUsed = obj.optLong("quotaUsed", -1L),
            quotaTotal = obj.optLong("quotaTotal", -1L),
            appKey = obj.optString("appKey"),
        )
    }.getOrNull()
}
