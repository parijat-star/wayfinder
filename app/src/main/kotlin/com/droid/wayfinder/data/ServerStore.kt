// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmbCred(val user: String, val password: String, val domain: String = "")

/**
 * A remembered server. [key] is the credential key (host, or host:port off 445, for SMB; `scheme://authority` otherwise),
 * [path] the SMB share or WebDAV/FTP base path, [alias] the optional sidebar label, [guest] = guest/anonymous
 * sign-in, [mounted] = shown in the sidebar (ejecting keeps the entry so it can be reconnected quickly).
 */
data class SavedServer(
    val key: String,
    val user: String,
    val domain: String,
    val hasPassword: Boolean,
    val protocol: Protocol = Protocol.SMB,
    val host: String = key,
    val port: Int = protocol.defaultPort,
    val path: String = "",
    val alias: String = "",
    val guest: Boolean = false,
    val mounted: Boolean = true,
) {
    val address: ServerAddress get() = ServerAddress(protocol, host, port, path)
    val rootPath: String get() = address.rootPath
    val label: String get() = alias.ifBlank { if (path.isEmpty()) host else "${path.substringAfterLast('/')} on $host" }
}

fun interface CredentialProvider {
    /** Credentials for [key] (see [ServerAddress.credKey]); a blank user means guest / anonymous. */
    fun get(key: String): SmbCred?
}

/** AES-GCM encryption with a non-exportable Android Keystore key. */
class SecretBox(private val alias: String = "wayfinder_smb") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        if (raw.size <= 12) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12))
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Pure (de)serialisation of a saved-server preference value. Stored as tab-separated fields: user, domain,
 * encrypted password, protocol, port, path, alias, guest, mounted. Entries written by older versions only have the
 * first three fields (SMB, keyed by host). Free-text fields are stripped of tabs / line breaks so one field can
 * never spill into the next (e.g. a user name turning on "guest" or changing the protocol).
 */
object ServerEntryCodec {
    data class CredFields(val user: String, val domain: String, val encPassword: String, val guest: Boolean)

    private fun clean(s: String) = s.map { if (it == '\t' || it == '\n' || it == '\r' || it == '\u0000') ' ' else it }.joinToString("")

    fun credFields(raw: String): CredFields? {
        val f = raw.split('\t')
        if (f.size < 3) return null
        return CredFields(f[0], f[1], f[2], f.getOrNull(7) == "1")
    }

    fun decode(key: String, raw: String): SavedServer? {
        val f = raw.split('\t')
        if (f.size < 3 || key.isEmpty()) return null
        val proto = f.getOrNull(3)?.let { p -> Protocol.entries.firstOrNull { it.name == p } } ?: Protocol.SMB
        // SMB keys are the authority: "nas" (port 445, as written by older versions) or "nas:1445".
        val (host, keyPort) = RemoteUri.splitAuthority(if (proto == Protocol.SMB) key else key.substringAfter("://"), proto.defaultPort)
        if (host.isBlank()) return null
        return SavedServer(
            key = key, user = f[0], domain = f[1], hasPassword = f[2].isNotEmpty(), protocol = proto, host = host,
            port = f.getOrNull(4)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: keyPort,
            path = f.getOrNull(5).orEmpty().split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/"),
            alias = f.getOrNull(6).orEmpty(), guest = f.getOrNull(7) == "1", mounted = f.getOrNull(8) != "0",
        )
    }

    fun encode(s: SavedServer, enc: String) = listOf(
        clean(s.user), clean(s.domain), clean(enc), s.protocol.name, s.port.toString(), clean(s.path),
        clean(s.alias), if (s.guest) "1" else "0", if (s.mounted) "1" else "0",
    ).joinToString("\t")
}

/** Saved servers of every protocol (credentials encrypted at rest) plus in-memory session credentials. */
class ServerStore(context: Context, private val box: SecretBox = SecretBox()) : CredentialProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
    private val session = HashMap<String, SmbCred>()

    private fun decodeEntry(key: String, raw: String): SavedServer? = ServerEntryCodec.decode(key, raw)
    private fun encodeEntry(s: SavedServer, enc: String) = ServerEntryCodec.encode(s, enc)

    @Synchronized
    override fun get(key: String): SmbCred? {
        session[key]?.let { return it }
        val raw = prefs.getString("srv_$key", null) ?: return null
        val f = ServerEntryCodec.credFields(raw) ?: return null
        if (f.guest) return SmbCred("", "", "")
        val pass = if (f.encPassword.isEmpty()) "" else box.decrypt(f.encPassword) ?: return null
        return SmbCred(f.user, pass, f.domain)
    }

    /** Updates the credentials of [key], keeping any saved protocol/path/alias. */
    @Synchronized
    fun put(key: String, cred: SmbCred, remember: Boolean) {
        val existing = prefs.getString("srv_$key", null)?.let { decodeEntry(key, it) } ?: run {
            val proto = if (key.contains("://")) RemoteUri.protocolOf(key) ?: Protocol.SMB else Protocol.SMB
            val (host, port) = RemoteUri.splitAuthority(if (proto == Protocol.SMB) key else key.substringAfter("://"), proto.defaultPort)
            SavedServer(key, "", "", false, proto, host, port)
        }
        save(existing.copy(user = cred.user, domain = cred.domain, guest = false), cred, remember)
    }

    /** Saves (or replaces) a server entry and makes its credentials available for this session. */
    @Synchronized
    fun save(server: SavedServer, cred: SmbCred, remember: Boolean) {
        session[server.key] = cred
        // Host and user name are always remembered; the password only when asked.
        val enc = if (!remember || cred.password.isEmpty()) "" else box.encrypt(cred.password)
        prefs.edit().putString("srv_${server.key}", encodeEntry(server.copy(hasPassword = enc.isNotEmpty()), enc)).apply()
    }

    /** Shows or hides a saved server in the sidebar without forgetting it. */
    @Synchronized
    fun setMounted(key: String, mounted: Boolean) {
        val raw = prefs.getString("srv_$key", null) ?: return
        val f = ServerEntryCodec.credFields(raw) ?: return
        val e = decodeEntry(key, raw) ?: return
        prefs.edit().putString("srv_$key", encodeEntry(e.copy(mounted = mounted), f.encPassword)).apply()
        if (!mounted) session.remove(key)
    }

    /** Session-only credentials (e.g. `ftp://user:pw@host` typed in the address bar). */
    @Synchronized
    fun putSession(key: String, cred: SmbCred) { session[key] = cred }

    @Synchronized
    fun forget(key: String) {
        session.remove(key)
        prefs.edit().remove("srv_$key").apply()
    }

    @Synchronized
    fun saved(): List<SavedServer> = prefs.all.keys.filter { it.startsWith("srv_") }.sorted().mapNotNull { k ->
        decodeEntry(k.removePrefix("srv_"), prefs.getString(k, null) ?: return@mapNotNull null)
    }
}
