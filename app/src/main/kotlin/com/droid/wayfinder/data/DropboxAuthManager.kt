// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Active PKCE code-paste session when signing in via Dropbox's no-redirect flow (`redirect_uri` omitted),
 * allowing sign-in from any browser or secondary device without registering a redirect URI.
 */
data class DropboxCodeSession(
    val appKey: String,
    val authorizeUrl: String,
    val codeVerifier: String,
)

internal data class DropboxRedirectResult(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * In-memory handoff channel between [com.droid.wayfinder.DropboxOAuthCallbackActivity] and
 * [DropboxAuthManager.authorizeViaRedirect]. Only accepts a callback when an interactive sign-in
 * coroutine is actively awaiting one, and never persists callback query parameters to disk.
 */
internal object DropboxOAuthCallbackBus {
    private val pending = AtomicReference<CompletableDeferred<DropboxRedirectResult>?>(null)

    fun register(deferred: CompletableDeferred<DropboxRedirectResult>) {
        pending.getAndSet(deferred)?.cancel()
    }

    fun unregister(deferred: CompletableDeferred<DropboxRedirectResult>) {
        pending.compareAndSet(deferred, null)
    }

    fun deliver(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals(DropboxAuthManager.REDIRECT_SCHEME, ignoreCase = true) ||
            !uri.host.equals(DropboxAuthManager.REDIRECT_HOST, ignoreCase = true)
        ) {
            return false
        }
        val waiter = pending.getAndSet(null) ?: return false
        return waiter.complete(
            DropboxRedirectResult(
                code = uri.getQueryParameter("code")?.take(1024),
                state = uri.getQueryParameter("state")?.take(512),
                error = uri.getQueryParameter("error")?.take(256),
                errorDescription = uri.getQueryParameter("error_description")?.take(512),
            ),
        )
    }
}

/**
 * Dropbox OAuth 2.0 public-client manager supporting:
 * 1. **System Browser Redirect + PKCE (`S256`)** (`com.droid.wayfinder.dropbox://oauth` with `token_access_type=offline`)
 * 2. **No-Redirect PKCE Code Copy/Paste** (`redirect_uri` omitted — works on any device or custom App Key with zero redirect setup)
 * 3. **Single-flight automatic `refresh_token` access-token renewal** per connected account
 */
class DropboxAuthManager(
    private val store: DropboxAccountStore,
    private val authorizeBaseUrl: String = DEFAULT_AUTHORIZE_URL,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    clientOverride: OkHttpClient? = null,
) {
    private val http: OkHttpClient = clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    companion object {
        const val DEFAULT_AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
        const val DEFAULT_API_BASE_URL = "https://api.dropboxapi.com"
        const val DEFAULT_CONTENT_BASE_URL = "https://content.dropboxapi.com"
        const val BUNDLED_PUBLIC_APP_KEY = "prf937kdbc0vpc5"
        const val REDIRECT_SCHEME = "com.droid.wayfinder.dropbox"
        const val REDIRECT_HOST = "oauth"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"
        const val SCOPES = "files.metadata.read files.content.read files.content.write account_info.read"
        const val APP_CONSOLE_URL = "https://www.dropbox.com/developers/apps"

        private val APP_KEY_REGEX = Regex("^[A-Za-z0-9_-]{8,64}$")
        private val AUTH_CODE_REGEX = Regex("^[\\x21-\\x7E]{8,1024}$")

        fun isValidAppKey(appKey: String): Boolean = APP_KEY_REGEX.matches(appKey.trim())

        fun isValidAuthorizationCode(code: String): Boolean = AUTH_CODE_REGEX.matches(code.trim())
    }

    fun effectiveAppKey(explicit: String? = null): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() }
            ?: store.defaultAppKey.takeIf { it.isNotEmpty() }
            ?: BUNDLED_PUBLIC_APP_KEY

    /**
     * Runs an interactive browser OAuth 2.0 Authorization Code + PKCE (`S256`) flow using [REDIRECT_URI],
     * verifies the returned `state` token in constant time, exchanges the code for offline tokens, and
     * fetches the account's Dropbox profile, namespace info, and storage quota.
     */
    suspend fun authorizeViaRedirect(
        appKeyInput: String,
        aliasLabel: String = "",
        timeoutMillis: Long = 180_000L,
        onLaunchBrowser: (String) -> Unit,
    ): DropboxAccount {
        val appKey = effectiveAppKey(appKeyInput)
        if (!isValidAppKey(appKey)) {
            throw IOException("Please enter a valid Dropbox App Key.")
        }
        store.defaultAppKey = appKey

        val verifier = OneDriveAuthManager.generateCodeVerifier()
        val challenge = OneDriveAuthManager.computeCodeChallengeS256(verifier)
        val expectedState = OneDriveAuthManager.generateStateToken()
        val clampedTimeoutMs = timeoutMillis.coerceIn(5_000L, 300_000L)

        val deferred = CompletableDeferred<DropboxRedirectResult>()
        DropboxOAuthCallbackBus.register(deferred)
        val authCode = try {
            val authorizeUrl = authorizeBaseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", appKey)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("scope", SCOPES)
                .addQueryParameter("token_access_type", "offline")
                .addQueryParameter("state", expectedState)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

            onLaunchBrowser(authorizeUrl)

            val callback = withTimeoutOrNull(clampedTimeoutMs) { deferred.await() }
                ?: throw IOException("Timed out waiting for Dropbox sign-in in browser.")

            val returnedState = callback.state.orEmpty()
            val stateValid = MessageDigest.isEqual(
                expectedState.toByteArray(Charsets.UTF_8),
                returnedState.toByteArray(Charsets.UTF_8),
            )
            if (!stateValid) {
                throw IOException("OAuth state mismatch (possible CSRF or stale browser tab).")
            }
            if (!callback.error.isNullOrBlank()) {
                val detail = callback.errorDescription?.takeIf { it.isNotBlank() } ?: callback.error
                throw IOException("Dropbox sign-in failed: ${appendUserLimitHint(appKey, detail)}")
            }
            val code = callback.code?.trim().orEmpty()
            if (code.isEmpty()) {
                throw IOException("No authorization code was returned from Dropbox.")
            }
            code
        } finally {
            DropboxOAuthCallbackBus.unregister(deferred)
        }

        val tokens = withContext(Dispatchers.IO) {
            exchangeAuthorizationCode(
                appKey = appKey,
                code = authCode,
                redirectUri = REDIRECT_URI,
                codeVerifier = verifier,
            )
        }
        return withContext(Dispatchers.IO) {
            fetchProfileAndPersist(appKey, aliasLabel, tokens)
        }
    }

    /**
     * Starts a no-redirect PKCE sign-in session (`redirect_uri` and `state` omitted per Dropbox's
     * manual-code OAuth specification) so the user can approve in any browser and paste the code.
     */
    fun startCodePasteSession(appKeyInput: String): DropboxCodeSession {
        val appKey = effectiveAppKey(appKeyInput)
        if (!isValidAppKey(appKey)) {
            throw IOException("Please enter a valid Dropbox App Key.")
        }
        store.defaultAppKey = appKey

        val verifier = OneDriveAuthManager.generateCodeVerifier()
        val challenge = OneDriveAuthManager.computeCodeChallengeS256(verifier)
        // Note: Dropbox rejects `state` when `redirect_uri` is omitted; PKCE `code_verifier` binds the session.
        val authorizeUrl = authorizeBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", appKey)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("token_access_type", "offline")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        return DropboxCodeSession(
            appKey = appKey,
            authorizeUrl = authorizeUrl,
            codeVerifier = verifier,
        )
    }

    /** Exchanges a user-pasted authorization code from [session] for offline tokens and saves the account. */
    suspend fun completeCodePasteSignIn(
        session: DropboxCodeSession,
        pastedCode: String,
        aliasLabel: String = "",
    ): DropboxAccount = withContext(Dispatchers.IO) {
        val trimmedCode = pastedCode.trim()
        if (!isValidAuthorizationCode(trimmedCode)) {
            throw IOException("Please paste the valid authorization code displayed by Dropbox.")
        }
        val tokens = exchangeAuthorizationCode(
            appKey = session.appKey,
            code = trimmedCode,
            redirectUri = null,
            codeVerifier = session.codeVerifier,
        )
        fetchProfileAndPersist(session.appKey, aliasLabel, tokens)
    }

    /**
     * Returns a valid Bearer `access_token` for [accountId], refreshing single-flight via `refresh_token`
     * when expired or within 60 seconds of expiration.
     */
    fun validAccessToken(accountId: String): String {
        val key = DropboxAccountStore.sanitizeAccountKey(accountId)
        val fast = store.getTokens(key)
        if (fast != null && fast.accessToken.isNotBlank() && clockMillis() + 60_000L < fast.expiresAtMillis) {
            return fast.accessToken
        }

        val lock = refreshLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            val current = store.getTokens(key)
                ?: throw RemoteAuthException(
                    "${RemoteUri.DROPBOX_SCHEME}://$key",
                    IOException("Not signed in to Dropbox ($key). Please sign in again."),
                )
            if (current.accessToken.isNotBlank() && clockMillis() + 60_000L < current.expiresAtMillis) {
                return current.accessToken
            }
            if (current.refreshToken.isBlank()) {
                throw RemoteAuthException(
                    "${RemoteUri.DROPBOX_SCHEME}://$key",
                    IOException("Dropbox session expired for $key. Please sign in again."),
                )
            }
            val acc = store.getAccount(key)
            val appKey = effectiveAppKey(acc?.appKey)
            if (!isValidAppKey(appKey)) {
                throw RemoteAuthException(
                    "${RemoteUri.DROPBOX_SCHEME}://$key",
                    IOException("Missing Dropbox App Key for $key."),
                )
            }

            val form = FormBody.Builder()
                .add("client_id", appKey)
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken)
                .build()
            val req = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/oauth2/token")
                .post(form)
                .header("Accept", "application/json")
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 400 || resp.code == 401) {
                        throw RemoteAuthException(
                            "${RemoteUri.DROPBOX_SCHEME}://$key",
                            IOException("Dropbox authorization expired for ${acc?.email ?: key}: ${extractOAuthError(raw)}"),
                        )
                    }
                    throw IOException("Couldn't refresh Dropbox token (HTTP ${resp.code}): ${extractOAuthError(raw)}")
                }
                val refreshed = parseTokenResponse(raw, fallbackRefreshToken = current.refreshToken)
                store.updateTokens(key, refreshed)
                return refreshed.accessToken
            }
        }
    }

    fun invalidateAccessToken(accountId: String) {
        store.invalidateAccessToken(accountId)
    }

    /** Refreshes account type, team root namespace, and `/2/users/get_space_usage` quota for [accountId]. */
    fun refreshAccountQuota(accountId: String): DropboxAccount? {
        val token = validAccessToken(accountId)
        var accountType: String? = null
        var rootNamespaceId: String? = null

        val accReq = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/2/users/get_current_account")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post("null".toRequestBody(jsonMedia))
            .build()
        runCatching {
            http.newCall(accReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                    accountType = json.optJSONObject("account_type")?.optString(".tag")
                    rootNamespaceId = parseTeamRootNamespace(json.optJSONObject("root_info"))
                }
            }
        }

        val spaceReq = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/2/users/get_space_usage")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post("null".toRequestBody(jsonMedia))
            .build()
        http.newCall(spaceReq).execute().use { resp ->
            if (!resp.isSuccessful) return store.getAccount(accountId)
            val body = resp.body?.string() ?: return store.getAccount(accountId)
            val json = JSONObject(body)
            val (used, total) = parseSpaceUsage(json)
            store.updateQuota(accountId, accountType, rootNamespaceId, used, total)
        }
        return store.getAccount(accountId)
    }

    private fun exchangeAuthorizationCode(
        appKey: String,
        code: String,
        redirectUri: String?,
        codeVerifier: String,
    ): DropboxTokens {
        val formBuilder = FormBody.Builder()
            .add("client_id", appKey)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
        if (!redirectUri.isNullOrBlank()) {
            formBuilder.add("redirect_uri", redirectUri)
        }
        val req = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/oauth2/token")
            .post(formBuilder.build())
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = appendUserLimitHint(appKey, extractOAuthError(raw))
                throw IOException("Failed to exchange Dropbox authorization code (HTTP ${resp.code}): $detail")
            }
            return parseTokenResponse(raw)
        }
    }

    internal fun appendUserLimitHint(appKey: String, detail: String): String {
        val isUserLimit = detail.contains("user limit", ignoreCase = true) ||
            detail.contains("max_users", ignoreCase = true) ||
            detail.contains("additional users", ignoreCase = true) ||
            detail.contains("user_limit", ignoreCase = true)
        return if (appKey == BUNDLED_PUBLIC_APP_KEY && isUserLimit) {
            "$detail (Tip: expand \"Advanced: Custom Dropbox App Key\" to connect with your own free key.)"
        } else {
            detail
        }
    }

    private fun parseTokenResponse(raw: String, fallbackRefreshToken: String = ""): DropboxTokens {
        val json = JSONObject(raw)
        val access = json.optString("access_token")
        if (access.isBlank()) throw IOException("Dropbox token response did not include an access_token")
        val refresh = json.optString("refresh_token").ifBlank { fallbackRefreshToken }
        val expiresInSec = json.optLong("expires_in", 14_400L).coerceAtLeast(60L)
        return DropboxTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = clockMillis() + expiresInSec * 1000L,
        )
    }

    private fun fetchProfileAndPersist(
        appKey: String,
        aliasLabel: String,
        tokens: DropboxTokens,
    ): DropboxAccount {
        var email = ""
        var displayName = ""
        var fallbackId = ""
        var accountType = "basic"
        var rootNamespaceId = ""

        val meReq = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/2/users/get_current_account")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .post("null".toRequestBody(jsonMedia))
            .build()
        http.newCall(meReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw IOException("Signed in, but couldn't read Dropbox account profile (HTTP ${resp.code}): ${extractOAuthError(errBody)}")
            }
            val json = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            fallbackId = json.optString("account_id")
            email = json.optString("email").takeIf { it.contains("@") }.orEmpty()
            displayName = json.optJSONObject("name")?.optString("display_name").orEmpty()
            accountType = json.optJSONObject("account_type")?.optString(".tag", "basic")?.ifBlank { "basic" } ?: "basic"
            rootNamespaceId = parseTeamRootNamespace(json.optJSONObject("root_info"))
        }

        var quotaUsed = -1L
        var quotaTotal = -1L
        val spaceReq = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/2/users/get_space_usage")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .post("null".toRequestBody(jsonMedia))
            .build()
        runCatching {
            http.newCall(spaceReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                    val (used, total) = parseSpaceUsage(json)
                    quotaUsed = used
                    quotaTotal = total
                }
            }
        }

        val rawKey = email.ifBlank { fallbackId }.ifBlank { "dropbox-user" }
        val accountId = DropboxAccountStore.sanitizeAccountKey(rawKey).ifBlank { "dropbox-user" }
        val account = DropboxAccount(
            accountId = accountId,
            email = email.ifBlank { accountId },
            displayName = displayName,
            aliasLabel = aliasLabel.trim(),
            accountType = accountType,
            rootNamespaceId = rootNamespaceId,
            quotaUsed = quotaUsed,
            quotaTotal = quotaTotal,
            appKey = appKey,
        )
        store.save(account, tokens)
        return account
    }

    private fun parseTeamRootNamespace(rootInfo: JSONObject?): String {
        if (rootInfo == null) return ""
        val rootNs = rootInfo.optString("root_namespace_id").trim()
        val homeNs = rootInfo.optString("home_namespace_id").trim()
        val tag = rootInfo.optString(".tag")
        // Only pin Dropbox-API-Path-Root when the account operates in a distinct Team Space root.
        return if (rootNs.isNotEmpty() && (tag.equals("team", ignoreCase = true) || (homeNs.isNotEmpty() && rootNs != homeNs))) {
            rootNs
        } else {
            ""
        }
    }

    private fun parseSpaceUsage(json: JSONObject): Pair<Long, Long> {
        val topUsed = json.optLong("used", -1L)
        val alloc = json.optJSONObject("allocation") ?: return topUsed to -1L
        if (alloc.optString(".tag").equals("team", ignoreCase = true)) {
            val memberCap = alloc.optLong("user_within_team_space_allocated", 0L)
            if (memberCap > 0L) {
                val memberUsed = alloc.optLong("user_within_team_space_used_cached", topUsed)
                return memberUsed to memberCap
            }
            return alloc.optLong("used", topUsed) to alloc.optLong("allocated", -1L)
        }
        return topUsed to alloc.optLong("allocated", -1L)
    }

    private fun extractOAuthError(raw: String): String = runCatching {
        val j = JSONObject(raw)
        j.optString("error_description").takeIf { it.isNotBlank() }
            ?: j.optString("error_summary").takeIf { it.isNotBlank() }
            ?: j.optJSONObject("error")?.optString(".tag")?.takeIf { it.isNotBlank() }
            ?: j.optString("error").takeIf { it.isNotBlank() }
            ?: raw.take(180)
    }.getOrDefault(raw.take(180))
}
