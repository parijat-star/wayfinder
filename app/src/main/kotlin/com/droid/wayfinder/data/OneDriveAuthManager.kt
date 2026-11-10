// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OneDriveDeviceCodeSession(
    val clientId: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val message: String,
)

/**
 * Microsoft Identity Platform v2.0 OAuth manager supporting:
 * 1. **Loopback Browser Authorization Code + PKCE (`S256`)** (`http://localhost:<ephemeral-port>/callback`)
 * 2. **OAuth 2.0 Device Code Flow** (`/devicecode`) for phone/QR or headless sign-in
 * 3. **Single-flight automatic `refresh_token` rotation** per connected account
 *
 * Uses `/common` so both Personal Microsoft Accounts (`@outlook.com`, `@live.com`, `@hotmail.com`)
 * and Work/School Microsoft 365 Entra ID tenants authenticate seamlessly.
 */
class OneDriveAuthManager(
    private val store: OneDriveAccountStore,
    private val authBaseUrl: String = DEFAULT_AUTH_BASE_URL,
    private val graphBaseUrl: String = DEFAULT_GRAPH_BASE_URL,
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

    private val refreshLocks = ConcurrentHashMap<String, Any>()

    companion object {
        const val DEFAULT_AUTH_BASE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0"
        const val DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        const val BUNDLED_PUBLIC_CLIENT_ID = "d9327cab-9197-4495-be6d-e0e57f6a0106"
        const val SCOPES = "Files.ReadWrite User.Read offline_access"
        const val ENTRA_PORTAL_URL =
            "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"

        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isValidClientId(clientId: String): Boolean = UUID_REGEX.matches(clientId.trim())

        /** Generates a cryptographically random RFC 7636 PKCE `code_verifier` (64 URL-safe characters). */
        fun generateCodeVerifier(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(48)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Computes `BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))` per RFC 7636 Section 4.2. */
        fun computeCodeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun generateStateToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(24)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun parseCallbackQuery(requestLine: String): Map<String, String> {
            // Example: GET /callback?code=abc&state=xyz HTTP/1.1
            val parts = requestLine.split(' ')
            if (parts.size < 2) return emptyMap()
            val target = parts[1]
            val query = target.substringAfter('?', "")
            if (query.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawK = if (eq >= 0) pair.substring(0, eq) else pair
                val rawV = if (eq >= 0) pair.substring(eq + 1) else ""
                val k = runCatching { URLDecoder.decode(rawK, "UTF-8") }.getOrDefault(rawK)
                val v = runCatching { URLDecoder.decode(rawV, "UTF-8") }.getOrDefault(rawV)
                out[k] = v
            }
            return out
        }
    }

    fun effectiveClientId(explicit: String? = null): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() }
            ?: store.defaultClientId.takeIf { it.isNotEmpty() }
            ?: BUNDLED_PUBLIC_CLIENT_ID

    /**
     * Runs a full loopback OAuth 2.0 Authorization Code + PKCE sign-in flow:
     * binds an ephemeral loopback port, launches the user's browser via [onLaunchBrowser], validates the returned
     * `state` token in constant time, exchanges the `code` + `code_verifier` for tokens, and fetches
     * the account's Microsoft Graph profile and OneDrive quota.
     */
    suspend fun authorizeViaLoopback(
        clientIdInput: String,
        aliasLabel: String = "",
        timeoutMillis: Long = 180_000L,
        onLaunchBrowser: (String) -> Unit,
    ): OneDriveAccount {
        val clientId = effectiveClientId(clientIdInput)
        if (!isValidClientId(clientId)) {
            throw IOException("Please enter a valid Microsoft Entra Application (Client) ID UUID.")
        }
        store.defaultClientId = clientId

        val verifier = generateCodeVerifier()
        val challenge = computeCodeChallengeS256(verifier)
        val expectedState = generateStateToken()

        val server = withContext(Dispatchers.IO) {
            ServerSocket(0, 8).apply {
                soTimeout = timeoutMillis.coerceIn(5_000L, 300_000L).toInt()
            }
        }
        val redirectUri = "http://localhost:${server.localPort}"
        val authorizeUrl = "${authBaseUrl.trimEnd('/')}/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_mode", "query")
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", expectedState)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account")
            .build()
            .toString()

        onLaunchBrowser(authorizeUrl)

        val authCode = try {
            awaitLoopbackCode(server, expectedState, timeoutMillis.coerceIn(5_000L, 300_000L))
        } finally {
            runCatching { server.close() }
        }

        val tokens = withContext(Dispatchers.IO) {
            exchangeAuthorizationCode(clientId, authCode, redirectUri, verifier)
        }
        return withContext(Dispatchers.IO) {
            fetchProfileAndPersist(clientId, aliasLabel, tokens)
        }
    }

    private suspend fun awaitLoopbackCode(server: ServerSocket, expectedState: String, timeoutMillis: Long): String =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { server.close() } }
                val deadline = System.currentTimeMillis() + timeoutMillis
                try {
                    while (cont.isActive) {
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
                        server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val outcome = server.accept().use { socket ->
                            // Enforce loopback-only connections across both IPv4 (127.0.0.1) and IPv6 (::1).
                            if (socket.inetAddress?.isLoopbackAddress != true) return@use null
                            socket.soTimeout = 10_000
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                            val requestLine = reader.readLine().orEmpty()
                            if (requestLine.isBlank()) {
                                // Ignore speculative browser TCP preconnects.
                                return@use null
                            }
                            while (true) {
                                val headerLine = reader.readLine() ?: break
                                if (headerLine.isEmpty()) break
                            }
                            val reqTarget = requestLine.split(' ').getOrNull(1).orEmpty()
                            if (reqTarget.startsWith("/favicon.ico")) {
                                runCatching {
                                    socket.getOutputStream().write(
                                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                            .toByteArray(Charsets.UTF_8),
                                    )
                                }
                                return@use null
                            }

                            val params = parseCallbackQuery(requestLine)
                            val returnedState = params["state"].orEmpty()
                            val stateValid = MessageDigest.isEqual(
                                expectedState.toByteArray(Charsets.UTF_8),
                                returnedState.toByteArray(Charsets.UTF_8),
                            )
                            val error = params["error"]
                            val errorDesc = params["error_description"]
                            val code = params["code"]

                            val (statusLine, htmlBody, result) = when {
                                !stateValid -> Triple(
                                    "HTTP/1.1 400 Bad Request",
                                    loopbackHtml(false, "Sign-in state mismatch", "Please return to Wayfinder and try signing in again."),
                                    Result.failure<String>(IOException("OAuth state mismatch (possible CSRF or stale tab)")),
                                )
                                !error.isNullOrBlank() -> Triple(
                                    "HTTP/1.1 400 Bad Request",
                                    loopbackHtml(false, "Sign-in cancelled", errorDesc ?: error),
                                    Result.failure<String>(IOException("Microsoft sign-in failed: ${errorDesc ?: error}")),
                                )
                                code.isNullOrBlank() -> Triple(
                                    "HTTP/1.1 400 Bad Request",
                                    loopbackHtml(false, "Missing authorization code", "No authorization code was returned."),
                                    Result.failure<String>(IOException("No authorization code returned from Microsoft")),
                                )
                                else -> Triple(
                                    "HTTP/1.1 200 OK",
                                    loopbackHtml(true, "Signed in to Wayfinder", "Your OneDrive account is now connected. You can close this browser tab and return to Wayfinder."),
                                    Result.success(code),
                                )
                            }

                            val bodyBytes = htmlBody.toByteArray(Charsets.UTF_8)
                            val responseHeader = buildString {
                                append(statusLine).append("\r\n")
                                append("Content-Type: text/html; charset=utf-8\r\n")
                                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                                append("Cache-Control: no-store\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.UTF_8)
                            runCatching {
                                val out = socket.getOutputStream()
                                out.write(responseHeader)
                                out.write(bodyBytes)
                                out.flush()
                            }
                            result
                        }
                        if (outcome != null) {
                            outcome.fold(
                                onSuccess = { cont.resume(it) },
                                onFailure = { cont.resumeWithException(it) },
                            )
                            break
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    if (cont.isActive) {
                        cont.resumeWithException(IOException("Timed out waiting for Microsoft sign-in in browser.", e))
                    }
                } catch (e: Throwable) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            if (e is CancellationException) e else IOException("Sign-in cancelled or interrupted: ${e.message}", e),
                        )
                    }
                }
            }
        }

    /** Starts an OAuth 2.0 Device Code session (`/devicecode`). */
    suspend fun startDeviceCode(clientIdInput: String): OneDriveDeviceCodeSession = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId(clientIdInput)
        if (!isValidClientId(clientId)) {
            throw IOException("Please enter a valid Microsoft Entra Application (Client) ID UUID.")
        }
        store.defaultClientId = clientId

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPES)
            .build()
        val req = Request.Builder()
            .url("${authBaseUrl.trimEnd('/')}/devicecode")
            .post(form)
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Couldn't start OneDrive device sign-in (HTTP ${resp.code}): ${extractOAuthError(raw)}")
            }
            val json = JSONObject(raw)
            val deviceCode = json.optString("device_code")
            val userCode = json.optString("user_code")
            val verificationUri = json.optString("verification_uri").ifBlank { "https://microsoft.com/devicelogin" }
            if (deviceCode.isBlank() || userCode.isBlank()) {
                throw IOException("Unexpected device-code response from Microsoft.")
            }
            OneDriveDeviceCodeSession(
                clientId = clientId,
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresInSeconds = json.optInt("expires_in", 900).coerceAtLeast(60),
                intervalSeconds = json.optInt("interval", 5).coerceIn(1, 30),
                message = json.optString("message"),
            )
        }
    }

    /** Polls `/token` until the user approves [session] at `microsoft.com/devicelogin` or it expires. */
    suspend fun pollDeviceCode(
        session: OneDriveDeviceCodeSession,
        aliasLabel: String = "",
        pollIntervalOverrideMillis: Long? = null,
    ): OneDriveAccount {
        val deadline = clockMillis() + session.expiresInSeconds * 1000L
        var intervalMs = pollIntervalOverrideMillis ?: (session.intervalSeconds * 1000L)

        while (clockMillis() < deadline) {
            delay(intervalMs)
            val outcome = withContext(Dispatchers.IO) {
                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", session.clientId)
                    .add("device_code", session.deviceCode)
                    .build()
                val req = Request.Builder()
                    .url("${authBaseUrl.trimEnd('/')}/token")
                    .post(form)
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        PollStep.Success(parseTokenResponse(raw))
                    } else {
                        val errCode = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                        when (errCode) {
                            "authorization_pending" -> PollStep.Pending
                            "slow_down" -> PollStep.SlowDown
                            "authorization_declined" -> PollStep.Failed("Sign-in was declined.")
                            "expired_token" -> PollStep.Failed("Device sign-in code expired. Please request a new code.")
                            else -> PollStep.Failed("OneDrive sign-in failed: ${extractOAuthError(raw)}")
                        }
                    }
                }
            }
            when (outcome) {
                is PollStep.Success -> return withContext(Dispatchers.IO) {
                    fetchProfileAndPersist(session.clientId, aliasLabel, outcome.tokens)
                }
                PollStep.Pending -> Unit
                PollStep.SlowDown -> intervalMs += 5_000L
                is PollStep.Failed -> throw IOException(outcome.reason)
            }
        }
        throw IOException("Device sign-in code timed out. Please try again.")
    }

    private sealed interface PollStep {
        data class Success(val tokens: OneDriveTokens) : PollStep
        data object Pending : PollStep
        data object SlowDown : PollStep
        data class Failed(val reason: String) : PollStep
    }

    /**
     * Returns a valid Bearer `access_token` for [accountId], refreshing single-flight via `refresh_token`
     * when expired or within 60 seconds of expiration.
     */
    fun validAccessToken(accountId: String): String {
        val key = OneDriveAccountStore.sanitizeAccountKey(accountId)
        val fast = store.getTokens(key)
        if (fast != null && fast.accessToken.isNotBlank() && clockMillis() + 60_000L < fast.expiresAtMillis) {
            return fast.accessToken
        }

        val lock = refreshLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            val current = store.getTokens(key)
                ?: throw RemoteAuthException(
                    "${RemoteUri.ONEDRIVE_SCHEME}://$key",
                    IOException("Not signed in to OneDrive ($key). Please sign in again."),
                )
            if (current.accessToken.isNotBlank() && clockMillis() + 60_000L < current.expiresAtMillis) {
                return current.accessToken
            }
            if (current.refreshToken.isBlank()) {
                throw RemoteAuthException(
                    "${RemoteUri.ONEDRIVE_SCHEME}://$key",
                    IOException("OneDrive session expired for $key. Please sign in again."),
                )
            }
            val acc = store.getAccount(key)
            val clientId = effectiveClientId(acc?.clientId)
            if (!isValidClientId(clientId)) {
                throw RemoteAuthException(
                    "${RemoteUri.ONEDRIVE_SCHEME}://$key",
                    IOException("Missing Microsoft Entra Client ID for OneDrive ($key)."),
                )
            }

            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken)
                .add("scope", SCOPES)
                .build()
            val req = Request.Builder()
                .url("${authBaseUrl.trimEnd('/')}/token")
                .post(form)
                .header("Accept", "application/json")
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 400 || resp.code == 401) {
                        throw RemoteAuthException(
                            "${RemoteUri.ONEDRIVE_SCHEME}://$key",
                            IOException("OneDrive authorization expired for ${acc?.email ?: key}: ${extractOAuthError(raw)}"),
                        )
                    }
                    throw IOException("Couldn't refresh OneDrive token (HTTP ${resp.code}): ${extractOAuthError(raw)}")
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

    /** Refreshes `/me/drive` quota metadata for [accountId]. */
    fun refreshAccountQuota(accountId: String): OneDriveAccount? {
        val token = validAccessToken(accountId)
        val driveReq = Request.Builder()
            .url("${graphBaseUrl.trimEnd('/')}/me/drive?\$select=id,driveType,quota")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(driveReq).execute().use { resp ->
            if (!resp.isSuccessful) return store.getAccount(accountId)
            val body = resp.body?.string() ?: return store.getAccount(accountId)
            val json = JSONObject(body)
            val driveType = json.optString("driveType")
            val quota = json.optJSONObject("quota")
            val used = quota?.optLong("used", -1L) ?: -1L
            val total = quota?.optLong("total", -1L) ?: -1L
            store.updateQuota(accountId, driveType, used, total)
        }
        return store.getAccount(accountId)
    }

    private fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OneDriveTokens {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .add("scope", SCOPES)
            .build()
        val req = Request.Builder()
            .url("${authBaseUrl.trimEnd('/')}/token")
            .post(form)
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Failed to exchange OneDrive authorization code (HTTP ${resp.code}): ${extractOAuthError(raw)}")
            }
            return parseTokenResponse(raw)
        }
    }

    private fun parseTokenResponse(raw: String, fallbackRefreshToken: String = ""): OneDriveTokens {
        val json = JSONObject(raw)
        val access = json.optString("access_token")
        if (access.isBlank()) throw IOException("Microsoft token response did not include an access_token")
        val refresh = json.optString("refresh_token").ifBlank { fallbackRefreshToken }
        val expiresInSec = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        return OneDriveTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = clockMillis() + expiresInSec * 1000L,
        )
    }

    private fun fetchProfileAndPersist(
        clientId: String,
        aliasLabel: String,
        tokens: OneDriveTokens,
    ): OneDriveAccount {
        var email = ""
        var displayName = ""
        var fallbackId = ""

        val meReq = Request.Builder()
            .url("${graphBaseUrl.trimEnd('/')}/me?\$select=id,displayName,mail,userPrincipalName")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(meReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                fallbackId = json.optString("id")
                displayName = json.optString("displayName")
                email = json.optString("mail").takeIf { it.contains("@") }
                    ?: json.optString("userPrincipalName").takeIf { it.contains("@") }
                    ?: ""
            }
        }

        var driveType = "personal"
        var quotaUsed = -1L
        var quotaTotal = -1L

        val driveReq = Request.Builder()
            .url("${graphBaseUrl.trimEnd('/')}/me/drive?\$select=id,driveType,owner,quota")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(driveReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw IOException("Signed in, but couldn't access OneDrive storage (HTTP ${resp.code}): ${extractOAuthError(errBody)}")
            }
            val json = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            driveType = json.optString("driveType", "personal").ifBlank { "personal" }
            val quota = json.optJSONObject("quota")
            if (quota != null) {
                quotaUsed = quota.optLong("used", -1L)
                quotaTotal = quota.optLong("total", -1L)
            }
            if (email.isBlank()) {
                val ownerUser = json.optJSONObject("owner")?.optJSONObject("user")
                email = ownerUser?.optString("email").orEmpty()
                if (displayName.isBlank()) displayName = ownerUser?.optString("displayName").orEmpty()
                if (fallbackId.isBlank()) fallbackId = json.optString("id")
            }
        }

        val rawKey = email.ifBlank { fallbackId }.ifBlank { "onedrive-user" }
        val accountId = OneDriveAccountStore.sanitizeAccountKey(rawKey).ifBlank { "onedrive-user" }
        val account = OneDriveAccount(
            accountId = accountId,
            email = email.ifBlank { accountId },
            displayName = displayName,
            aliasLabel = aliasLabel.trim(),
            driveType = driveType,
            quotaUsed = quotaUsed,
            quotaTotal = quotaTotal,
            clientId = clientId,
        )
        store.save(account, tokens)
        return account
    }

    private fun extractOAuthError(raw: String): String = runCatching {
        val j = JSONObject(raw)
        j.optString("error_description").takeIf { it.isNotBlank() }
            ?: j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j.optString("error").takeIf { it.isNotBlank() }
            ?: raw.take(180)
    }.getOrDefault(raw.take(180))

    private fun loopbackHtml(ok: Boolean, title: String, subtitle: String): String {
        val accent = if (ok) "#0078D4" else "#D93025"
        val badge = if (ok) "&#10003;" else "!"
        val escTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val escSub = subtitle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$escTitle</title></head>
            <body style="font-family:system-ui,-apple-system,sans-serif;background:#f5f7fa;color:#1f2328;display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0;">
              <div style="background:#fff;border:1px solid #d8dee4;border-radius:16px;padding:32px;max-width:440px;text-align:center;box-shadow:0 6px 24px rgba(0,0,0,0.06);">
                <div style="width:48px;height:48px;border-radius:24px;background:${accent}1a;color:$accent;font-size:24px;font-weight:700;line-height:48px;margin:0 auto 16px;">$badge</div>
                <h1 style="font-size:20px;margin:0 0 8px;">$escTitle</h1>
                <p style="font-size:14px;color:#57606a;line-height:1.5;margin:0;">$escSub</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
