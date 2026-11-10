// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Connected Microsoft OneDrive account metadata (Personal MSA or Work/School Microsoft 365).
 *
 * [accountId] is the canonical, URL-authority-safe identifier used in `onedrive://<accountId>/...` paths.
 */
data class OneDriveAccount(
    val accountId: String,
    val email: String,
    val displayName: String = "",
    val aliasLabel: String = "",
    val driveType: String = "personal",
    val quotaUsed: Long = -1L,
    val quotaTotal: Long = -1L,
    val clientId: String = "",
) {
    val rootPath: String get() = "${RemoteUri.ONEDRIVE_SCHEME}://$accountId"

    val isBusiness: Boolean
        get() = driveType.equals("business", ignoreCase = true) ||
            driveType.equals("documentLibrary", ignoreCase = true)

    val sidebarTitle: String
        get() {
            val custom = aliasLabel.trim()
            if (custom.isNotEmpty()) return custom
            return if (isBusiness) "OneDrive · Work" else "OneDrive · Personal"
        }

    val sidebarSubtitle: String
        get() = if (quotaUsed >= 0L && quotaTotal > 0L) {
            "$email · ${formatSize(quotaUsed)} / ${formatSize(quotaTotal)}"
        } else {
            email
        }
}

data class OneDriveTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/** Pluggable token encryption so AndroidKeyStore AES-256-GCM is used on device while unit tests can inject a deterministic cipher. */
interface TokenCipher {
    fun encrypt(plain: String): String?
    fun decrypt(encoded: String): String?
}

class KeystoreTokenCipher(private val alias: String = "wayfinder_onedrive") : TokenCipher {
    override fun encrypt(plain: String): String? = runCatching { SecretBox(alias).encrypt(plain) }.getOrNull()
    override fun decrypt(encoded: String): String? = runCatching { SecretBox(alias).decrypt(encoded) }.getOrNull()
}

/**
 * Persists connected [OneDriveAccount] profiles and hardware-encrypted OAuth 2.0 refresh tokens.
 *
 * `onedrive_accounts.xml` is explicitly excluded from Android cloud backup and device-to-device
 * transfer in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.
 */
class OneDriveAccountStore(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionTokens = ConcurrentHashMap<String, OneDriveTokens>()

    companion object {
        const val PREFS_NAME = "onedrive_accounts"
        private const val PREFIX_ACC = "acc_"
        private const val PREFIX_TOK = "tok_"
        private const val KEY_DEFAULT_CLIENT_ID = "default_client_id"

        /** Sanitizes an email/UPN or user ID into a safe lowercase authority segment for `onedrive://<accountId>/...`. */
        fun sanitizeAccountKey(raw: String): String {
            val cleaned = raw.trim().lowercase(Locale.ROOT)
                .filter { ch ->
                    (ch in 'a'..'z') || (ch in '0'..'9') || ch == '@' || ch == '.' || ch == '-' || ch == '_' || ch == '+'
                }
                .take(128)
            return cleaned
        }

        fun isValidAccountKey(key: String): Boolean =
            key.isNotEmpty() &&
                key.length <= 128 &&
                key != "." &&
                key != ".." &&
                key.all { ch ->
                    (ch in 'a'..'z') || (ch in '0'..'9') || ch == '@' || ch == '.' || ch == '-' || ch == '_' || ch == '+'
                }
    }

    var defaultClientId: String
        get() = prefs.getString(KEY_DEFAULT_CLIENT_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: OneDriveAuthManager.BUNDLED_PUBLIC_CLIENT_ID
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_CLIENT_ID, value.trim()).apply()
        }

    @Synchronized
    fun accounts(): List<OneDriveAccount> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX_ACC) }
            .sorted()
            .mapNotNull { prefKey ->
                val raw = prefs.getString(prefKey, null) ?: return@mapNotNull null
                decodeAccount(raw)
            }

    @Synchronized
    fun getAccount(accountId: String): OneDriveAccount? {
        val key = sanitizeAccountKey(accountId)
        if (!isValidAccountKey(key)) return null
        val raw = prefs.getString(PREFIX_ACC + key, null) ?: return null
        return decodeAccount(raw)
    }

    @Synchronized
    fun getTokens(accountId: String): OneDriveTokens? {
        val key = sanitizeAccountKey(accountId)
        if (!isValidAccountKey(key)) return null
        sessionTokens[key]?.let { return it }
        val encRefresh = prefs.getString(PREFIX_TOK + key, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = cipher.decrypt(encRefresh)?.takeIf { it.isNotBlank() } ?: return null
        val hydrated = OneDriveTokens(accessToken = "", refreshToken = refresh, expiresAtMillis = 0L)
        sessionTokens[key] = hydrated
        return hydrated
    }

    @Synchronized
    fun save(account: OneDriveAccount, tokens: OneDriveTokens) {
        val key = sanitizeAccountKey(account.accountId.ifBlank { account.email })
        require(isValidAccountKey(key)) { "Invalid OneDrive account key" }
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
    fun updateTokens(accountId: String, tokens: OneDriveTokens) {
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
    fun updateQuota(accountId: String, driveType: String?, used: Long, total: Long) {
        val existing = getAccount(accountId) ?: return
        val updated = existing.copy(
            driveType = driveType?.takeIf { it.isNotBlank() } ?: existing.driveType,
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

    private fun encodeAccount(a: OneDriveAccount): String = JSONObject().apply {
        put("accountId", a.accountId)
        put("email", a.email)
        put("displayName", a.displayName)
        put("aliasLabel", a.aliasLabel)
        put("driveType", a.driveType)
        put("quotaUsed", a.quotaUsed)
        put("quotaTotal", a.quotaTotal)
        put("clientId", a.clientId)
    }.toString()

    private fun decodeAccount(raw: String): OneDriveAccount? = runCatching {
        val obj = JSONObject(raw)
        val id = sanitizeAccountKey(obj.optString("accountId"))
        if (!isValidAccountKey(id)) return null
        val email = obj.optString("email").trim().ifEmpty { id }
        OneDriveAccount(
            accountId = id,
            email = email,
            displayName = obj.optString("displayName"),
            aliasLabel = obj.optString("aliasLabel"),
            driveType = obj.optString("driveType", "personal"),
            quotaUsed = obj.optLong("quotaUsed", -1L),
            quotaTotal = obj.optLong("quotaTotal", -1L),
            clientId = obj.optString("clientId"),
        )
    }.getOrNull()
}
