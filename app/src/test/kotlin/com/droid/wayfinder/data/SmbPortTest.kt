// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** SMB servers on a non-default port: path shape, parsing, credential keys and the port reaching connect. */
class SmbPortTest {

    @Test fun parseSmbSplitsHostAndPort() {
        FsPath.parseSmb("smb://nas/media/Movies/2020").let {
            assertEquals("nas", it.host); assertEquals("nas", it.server); assertEquals(445, it.port)
            assertEquals("media", it.share); assertEquals("Movies/2020", it.rel)
        }
        FsPath.parseSmb("smb://nas:1445/media/x").let {
            assertEquals("nas:1445", it.host); assertEquals("nas", it.server); assertEquals(1445, it.port)
            assertEquals("media", it.share); assertEquals("x", it.rel)
        }
        FsPath.parseSmb("smb://[fe80::1]:1445/share").let {
            assertEquals("[fe80::1]:1445", it.host); assertEquals("[fe80::1]", it.server); assertEquals(1445, it.port)
            assertEquals("share", it.share)
        }
        FsPath.parseSmb("smb://[fe80::1]/share").let { assertEquals("[fe80::1]", it.server); assertEquals(445, it.port) }
        FsPath.parseSmb("smb://192.168.1.5:139").let {
            assertEquals("192.168.1.5", it.server); assertEquals(139, it.port); assertNull(it.share)
        }
        // Invalid ports fall back to 445 rather than being misread.
        FsPath.parseSmb("smb://nas:99999/s").let { assertEquals(445, it.port) }
    }

    @Test fun pathsCarryThePortOnlyWhenItIsNot445() {
        assertEquals("smb://nas/media", ServerAddress(Protocol.SMB, "nas", 445, "media").rootPath)
        assertEquals("smb://nas:1445/media", ServerAddress(Protocol.SMB, "nas", 1445, "media").rootPath)
        assertEquals("smb://[fe80::1]:1445", ServerAddress(Protocol.SMB, "[fe80::1]", 1445).rootPath)
        assertEquals("smb://nas:1445/media/tv", RemoteUri.parse("smb://nas:1445/media/tv")!!.rootPath)
        assertEquals("smb://nas/media", RemoteUri.parse("smb://nas:445/media")!!.rootPath)
        assertEquals("smb://nas:1445/share", ConnectConfig(Protocol.SMB, "nas", 1445, "share").address.rootPath)
    }

    @Test fun credentialKeysStayCompatibleOnPort445() {
        // Earlier versions keyed SMB credentials by the bare host.
        assertEquals("nas", ServerAddress(Protocol.SMB, "nas", 445).credKey)
        assertEquals("nas", RemoteUri.credKeyOf("smb://nas/share/x"))
        assertEquals("nas", RemoteUri.credKeyOf("smb://nas:445/share/x"))
        // A different port is a different server.
        assertEquals("nas:1445", ServerAddress(Protocol.SMB, "nas", 1445).credKey)
        assertEquals("nas:1445", RemoteUri.credKeyOf("smb://nas:1445/share/x"))
    }

    @Test fun savedServersFromEarlierVersionsAndOnCustomPortsDecode() {
        // Old 3-field entry keyed by host -> port 445, unchanged paths.
        val old = ServerEntryCodec.decode("nas", "alice\tWORKGROUP\t")!!
        assertEquals("nas", old.host); assertEquals(445, old.port); assertEquals("smb://nas", old.rootPath)
        // New entry on a custom port.
        val srv = SavedServer("nas:1445", "bob", "", false, Protocol.SMB, "nas", 1445, "media")
        val back = ServerEntryCodec.decode(srv.key, ServerEntryCodec.encode(srv, ""))!!
        assertEquals("nas", back.host); assertEquals(1445, back.port); assertEquals("smb://nas:1445/media", back.rootPath)
        // A custom-port key without a stored port field still connects to its port.
        assertEquals(1445, ServerEntryCodec.decode("nas:1445", "bob\t\t")!!.port)
    }

    @Test fun thePortReachesConnectAndCredentialsUseTheMatchingKey() {
        val dialled = mutableListOf<Pair<String, Int>>()
        val keys = mutableListOf<String>()
        val fs = SmbFileSystem(
            creds = CredentialProvider { k -> keys += k; null },
            client = object : SMBClient() {
                override fun connect(hostname: String, port: Int): Connection {
                    dialled += hostname to port
                    throw IOException("refused")
                }
            },
        )
        for (p in listOf("smb://nas/share", "smb://nas:1445/share", "smb://[fe80::1]:1446/share", "smb://nas:445/share")) {
            val e = assertThrows(IOException::class.java) { fs.list(p) }
            assertTrue(e.message!!.contains("Can't reach"))
        }
        // Bare host -> 445 as before; custom ports are dialled; IPv6 brackets are removed for the socket.
        assertEquals(setOf("nas" to 445, "nas" to 1445, "fe80::1" to 1446), dialled.toSet())
        // Credentials: bare host on 445 (compatible with saved entries), host:port otherwise.
        assertEquals(setOf("nas", "nas:1445", "[fe80::1]:1446"), keys.toSet())
    }
}
