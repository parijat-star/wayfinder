// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.Factory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.Authenticator
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Thrown when a server needs (other) credentials; [host] is the credential key (see [ServerAddress.credKey]). */
open class RemoteAuthException(val host: String, cause: Throwable? = null) :
    IOException("Sign in required for $host", cause)

class SmbAuthException(host: String, cause: Throwable? = null) : RemoteAuthException(host, cause)

/**
 * SMB2/3 file system on top of smbj. Paths look like `smb://host/share/dir/file`, or `smb://host:port/share/...`
 * for servers that don't listen on 445. Sessions and shares are keyed by that authority.
 */
class SmbFileSystem(
    private val creds: CredentialProvider,
    private val client: SMBClient = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            // Kerberos/SPNEGO depend on JDK classes missing on Android; NTLM is what home/office shares use.
            .withAuthenticators(listOf<Factory.Named<Authenticator>>(NtlmAuthenticator.Factory()))
            .build(),
    ),
    private val serverServiceFactory: (Session) -> ServerService = { s ->
        ServerService(SMBTransportFactories.SRVSVC.getTransport(s))
    },
) : RemoteFileSystem {

    private val sessions = HashMap<String, Session>()
    private val shares = HashMap<String, DiskShare>()

    /**
     * SMB path for a relative path. A local name containing `\` (legal on Android) would become extra path
     * components on the server, and `..` would climb up: both are refused.
     */
    private fun win(rel: String): String {
        val segs = rel.split('/').filter { it.isNotEmpty() }
        if (segs.any { !FsPath.isSafeName(it) || it.contains('\\') }) throw IOException("This name isn't allowed on SMB shares")
        return segs.joinToString("\\")
    }

    @Synchronized
    private fun session(host: String): Session {
        sessions[host]?.let { s -> if (s.connection.isConnected) return s else drop(host) }
        // [host] is the path authority: "nas" (port 445) or "nas:1445" / "[fe80::1]:1445".
        val (server, port) = RemoteUri.splitAuthority(host, Protocol.SMB.defaultPort)
        // Credentials are keyed by the bare host on 445 (as in earlier versions), by host:port otherwise.
        val credKey = RemoteUri.credKey(Protocol.SMB, server, port)
        val cred = creds.get(credKey)
        val conn: Connection = try {
            client.connect(server.removePrefix("[").removeSuffix("]"), port)
        } catch (e: Exception) {
            throw IOException("Can't reach $host. Check the address and your network.", e)
        }
        val pwd = if (cred == null || cred.user.isBlank()) null else cred.password.toCharArray()
        val ctx = if (pwd == null || cred == null) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(cred.user, pwd, cred.domain)
        }
        try {
            val s = conn.authenticate(ctx)
            sessions[host] = s
            return s
        } catch (e: Exception) {
            runCatching { conn.close() }
            if (e is SMBApiException || e.javaClass.simpleName.contains("Auth", true) || cred == null) {
                throw SmbAuthException(credKey, e)
            }
            throw IOException("Sign-in to $host failed: ${e.message}", e)
        } finally {
            pwd?.fill('\u0000')
        }
    }

    @Synchronized
    private fun drop(host: String) {
        shares.keys.filter { it.startsWith("$host/") }.forEach { runCatching { shares.remove(it)?.close() } }
        runCatching { sessions.remove(host)?.close() }
    }

    @Synchronized
    override fun disconnect(key: String) = drop(key)

    @Synchronized
    private fun share(p: SmbPath): DiskShare {
        val key = "${p.host}/${p.share}"
        shares[key]?.let { if (it.isConnected) return it }
        val s = session(p.host)
        try {
            val d = s.connectShare(p.share) as DiskShare
            shares[key] = d
            return d
        } catch (e: SMBApiException) {
            throw IOException("Can't open share \"${p.share}\": ${e.status.name.removePrefix("STATUS_").lowercase().replace('_', ' ')}", e)
        }
    }

    private fun <T> withShare(path: String, block: (DiskShare, SmbPath) -> T): T {
        val p = FsPath.parseSmb(path)
        require(p.share != null) { "No share in $path" }
        try {
            return block(share(p), p)
        } catch (e: SmbAuthException) {
            throw e
        } catch (e: SMBApiException) {
            throw IOException(describe(e), e)
        } catch (e: IOException) {
            // Connection may have gone stale: reconnect once.
            drop(p.host)
            return try {
                block(share(p), p)
            } catch (e2: SMBApiException) {
                throw IOException(describe(e2), e2)
            }
        }
    }

    private fun describe(e: SMBApiException) =
        e.status.name.removePrefix("STATUS_").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun listShares(host: String): List<FileEntry> {
        val s = session(host)
        try {
            val svc = serverServiceFactory(s)
            return svc.shares1
                .filter { (it.type and 0xFFFF) == 0 && (it.type ushr 31) == 0 && !it.netName.endsWith("$") && FsPath.isSafeName(it.netName) && !it.netName.contains('\\') }
                .map { FileEntry("smb://$host/${it.netName}", it.netName, true, isShare = true, isHidden = false) }
        } catch (e: SMBApiException) {
            throw IOException("Couldn't list shares on $host: ${describe(e)}. Enter a share name, e.g. smb://$host/share", e)
        } catch (e: Exception) {
            throw IOException("Couldn't list shares on $host. Enter a share name, e.g. smb://$host/share", e)
        }
    }

    override fun list(path: String): List<FileEntry> {
        val p = FsPath.parseSmb(path)
        if (p.share == null) return listShares(p.host)
        return withShare(path) { sh, sp ->
            // Names come from the server: drop anything that could climb out of this folder when copied locally.
            sh.list(win(sp.rel)).filter { FsPath.isSafeName(it.fileName) && !it.fileName.contains('\\') }.map { info ->
                val attrs = info.fileAttributes
                val dir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                FileEntry(
                    path = FsPath.child(FsPath.normalize(path), info.fileName),
                    name = info.fileName, isDir = dir,
                    size = if (dir) 0 else info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                    isHidden = (attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L,
                )
            }
        }
    }

    override fun stat(path: String): FileEntry? {
        val p = FsPath.parseSmb(path)
        if (p.share == null) return FileEntry(path, p.host, true, isServer = true)
        if (p.rel.isEmpty()) return FileEntry(path, p.share, true, isShare = true)
        return withShare(path) { sh, sp ->
            val rel = win(sp.rel)
            if (!sh.fileExists(rel) && !sh.folderExists(rel)) null
            else {
                val info = sh.getFileInformation(rel)
                val dir = (info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                FileEntry(path, FsPath.name(path), dir, if (dir) 0 else info.standardInformation.endOfFile,
                    info.basicInformation.lastWriteTime.toEpochMillis())
            }
        }
    }

    override fun mkdir(path: String) = withShare(path) { sh, sp ->
        if (!sh.folderExists(win(sp.rel))) sh.mkdir(win(sp.rel))
    }

    override fun createFile(path: String) = withShare(path) { sh, sp ->
        sh.openFile(
            win(sp.rel), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE, null,
        ).close()
    }

    override fun delete(path: String) = withShare(path) { sh, sp ->
        val rel = win(sp.rel)
        if (sh.folderExists(rel)) sh.rmdir(rel, true) else sh.rm(rel)
    }

    override fun rename(path: String, newName: String): String {
        if (!FsPath.isSafeName(newName)) throw IOException("Invalid name")
        val dst = FsPath.child(FsPath.parent(path)!!, newName)
        if (!move(path, dst)) throw IOException("Couldn't rename")
        return dst
    }

    override fun move(src: String, dstPath: String): Boolean {
        val a = FsPath.parseSmb(src)
        val b = FsPath.parseSmb(dstPath)
        if (!FsPath.isSmb(dstPath) || a.host != b.host || a.share != b.share || a.share == null || a.rel.isEmpty()) return false
        return withShare(src) { sh, sp ->
            val rel = win(sp.rel)
            if (sh.fileExists(win(b.rel)) || sh.folderExists(win(b.rel))) return@withShare false
            val dir = sh.folderExists(rel)
            val opts = if (dir) EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE) else EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            sh.open(rel, EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, opts).use {
                it.rename(win(b.rel))
            }
            true
        }
    }

    override fun openInput(path: String): InputStream {
        val p = FsPath.parseSmb(path)
        val f = withShare(path) { sh, _ ->
            sh.openFile(
                win(p.rel), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            )
        }
        return object : FilterInputStream(f.inputStream.buffered(256 * 1024)) {
            override fun close() = try { super.close() } finally { runCatching { f.close() } }
        }
    }

    override fun openOutput(path: String): OutputStream {
        val p = FsPath.parseSmb(path)
        val f = withShare(path) { sh, _ ->
            sh.openFile(
                win(p.rel), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            )
        }
        return object : FilterOutputStream(f.outputStream.buffered(256 * 1024)) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = try { super.close() } finally { runCatching { f.close() } }
        }
    }
}
