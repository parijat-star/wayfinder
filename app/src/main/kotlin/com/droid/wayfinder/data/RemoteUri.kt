// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.net.URLDecoder

/** Network protocols offered by "Connect to Server". [scheme] is the prefix of paths on that protocol. */
enum class Protocol(val scheme: String, val label: String, val defaultPort: Int) {
    SMB("smb", "SMB2/3", 445),
    DAV("dav", "WebDAV", 80),
    DAVS("davs", "WebDAVS", 443),
    FTP("ftp", "FTP", 21),
    FTPS("ftps", "FTPS", 21);

    val isWebDav get() = this == DAV || this == DAVS
    val isFtp get() = this == FTP || this == FTPS

    companion object {
        fun fromScheme(s: String): Protocol? = when (s.lowercase()) {
            "smb", "cifs" -> SMB
            "dav", "webdav", "http" -> DAV
            "davs", "webdavs", "https" -> DAVS
            "ftp" -> FTP
            "ftps", "ftpes" -> FTPS
            else -> null
        }
    }
}

/**
 * A parsed server address. [path] is the SMB share (plus sub folders) or the WebDAV/FTP base path,
 * without leading/trailing slashes. [user]/[password] are only set when the input contained them.
 */
data class ServerAddress(
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val path: String = "",
    val user: String? = null,
    val password: String? = null,
) {
    /** `host` or `host:port` when the port isn't the protocol default. */
    val authority: String get() = if (port == protocol.defaultPort || port <= 0) host else "$host:$port"

    /**
     * The browsable path of this location, e.g. `davs://cloud:8443/remote.php/dav` or `smb://nas:1445/media`.
     * The port is only included when it isn't the protocol default, so `smb://nas/media` stays unchanged.
     */
    val rootPath: String
        get() = protocol.scheme + "://" + authority + (if (path.isNotEmpty()) "/$path" else "")

    /**
     * Key used to store credentials: the authority for SMB (`host`, as in older versions, or `host:port` on a
     * non-default port), `scheme://authority` otherwise.
     */
    val credKey: String get() = RemoteUri.credKey(protocol, host, port)
}

/** Pure helpers to parse user-typed server addresses and to split remote paths. */
object RemoteUri {
    const val SAF_SCHEME = "saf"
    const val GDRIVE_SCHEME = "gdrive"
    const val ONEDRIVE_SCHEME = "onedrive"
    const val DOC_SCHEME = "doc"
    const val PHONE_SCHEME = "phone"
    private val schemes =
        Protocol.entries.map { it.scheme } + SAF_SCHEME + GDRIVE_SCHEME + ONEDRIVE_SCHEME + DOC_SCHEME + PHONE_SCHEME

    /** Scheme of a remote path (`smb`, `dav`, `davs`, `ftp`, `ftps`, `saf`), or null for local/virtual paths. */
    fun schemeOf(p: String): String? {
        val i = p.indexOf("://")
        if (i <= 0) return null
        val s = p.substring(0, i)
        return s.takeIf { it in schemes }
    }

    fun protocolOf(p: String): Protocol? = schemeOf(p)?.let { s -> Protocol.entries.firstOrNull { it.scheme == s } }

    fun credKey(protocol: Protocol, host: String, port: Int): String {
        val authority = if (port == protocol.defaultPort || port <= 0) host else "$host:$port"
        return if (protocol == Protocol.SMB) authority else protocol.scheme + "://" + authority
    }

    /** Credential key of a remote path: the authority for SMB (`host` on port 445), `scheme://authority` for WebDAV/FTP. */
    fun credKeyOf(path: String): String? {
        val proto = protocolOf(path) ?: return null
        val authority = path.substringAfter("://").substringBefore('/')
        if (proto != Protocol.SMB) return "${proto.scheme}://$authority"
        // smb://nas:445/... uses the same credentials as smb://nas/...
        val (host, port) = splitAuthority(authority, Protocol.SMB.defaultPort)
        return credKey(Protocol.SMB, host, port)
    }

    /** Root path (`scheme://authority`) of the server or SAF mount holding [path]. */
    fun rootOf(path: String): String? {
        val s = schemeOf(path) ?: return null
        val authority = path.substringAfter("://").substringBefore('/')
        return if (authority.isEmpty()) null else "$s://$authority"
    }

    /** Splits an authority (`host`, `host:21`, `[::1]:21`) into host and port (or [defaultPort]). */
    fun splitAuthority(authority: String, defaultPort: Int): Pair<String, Int> {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end > 0) {
                val host = authority.substring(0, end + 1)
                val port = authority.substring(end + 1).removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                return host to port
            }
        }
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(':') == colon) {
            val port = authority.substring(colon + 1).toIntOrNull()
            if (port != null && port in 1..65535) return authority.substring(0, colon) to port
        }
        return authority to defaultPort
    }

    /**
     * A host name / IP literal that can be used safely in URLs, FTP and SMB connections and credential keys:
     * non-blank, no whitespace or control characters and none of the URL delimiters `/ \ ? # @`.
     */
    fun isValidHost(host: String): Boolean =
        host.isNotBlank() && host.length <= 255 && host.none { it.isWhitespace() || it < ' ' || it == '\u007f' || it in "/\\?#@" }

    /** Relative path inside the server for [path] (everything after the authority, no leading slash). */
    fun relOf(path: String): String = path.substringAfter("://").substringAfter('/', "").trim('/')

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    /**
     * Parses addresses such as `smb://nas/share`, `\\nas\share`, `davs://cloud/remote.php/dav`,
     * `https://host/dav`, `ftp://user:pw@host:2121/pub` or a bare `host[/path]` (using [defaultProtocol]).
     */
    fun parse(input: String, defaultProtocol: Protocol = Protocol.SMB): ServerAddress? {
        var s = input.trim()
        if (s.isEmpty()) return null
        var protocol = defaultProtocol
        when {
            s.startsWith("\\\\") || s.startsWith("//") -> {
                protocol = Protocol.SMB
                s = s.replace('\\', '/').trimStart('/')
            }
            s.contains("://") -> {
                protocol = Protocol.fromScheme(s.substringBefore("://")) ?: return null
                s = s.substringAfter("://")
            }
            else -> s = s.replace('\\', '/')
        }
        val slash = s.indexOf('/')
        var authority = if (slash >= 0) s.substring(0, slash) else s
        val rawPath = if (slash >= 0) s.substring(slash + 1) else ""
        var user: String? = null
        var password: String? = null
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            val info = authority.substring(0, at)
            authority = authority.substring(at + 1)
            val c = info.indexOf(':')
            user = decode(if (c >= 0) info.substring(0, c) else info).takeIf { it.isNotEmpty() }
            password = if (c >= 0) decode(info.substring(c + 1)) else null
        }
        // SMB "DOMAIN;user" style user info is kept verbatim in the user name.
        val (host, port) = splitAuthority(authority, protocol.defaultPort)
        if (!isValidHost(host)) return null
        // Decoded segments may contain "/" (%2F) or be "." / ".." (%2E%2E): normalise them away.
        val path = rawPath.split('/').filter { it.isNotEmpty() }.flatMap { decode(it).split('/') }
            .filter { FsPath.isSafeName(it) }.joinToString("/")
        return ServerAddress(protocol, host, port, path, user, password)
    }
}

/** Everything entered in the "Connect to Server" dialog. */
data class ConnectConfig(
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    /** SMB share (optionally with sub folders) or WebDAV / FTP base path. */
    val path: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val guest: Boolean = false,
    val alias: String = "",
    val remember: Boolean = true,
) {
    val address: ServerAddress
        get() = ServerAddress(protocol, host.trim(), port, path.replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/"))
    val cred: SmbCred
        get() = if (guest) SmbCred("", "", "") else SmbCred(user.trim(), password, if (protocol == Protocol.SMB) domain.trim() else "")
}
