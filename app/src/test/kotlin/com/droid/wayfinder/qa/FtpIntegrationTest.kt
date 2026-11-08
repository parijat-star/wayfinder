// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.CredentialProvider
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FtpFileSystem
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.RemoteAuthException
import com.droid.wayfinder.data.SmbCred
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket

/** FTP integration against an embedded Apache FtpServer (MLSD/MLST capable) serving a temp folder. */
class FtpIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var server: FtpServer
    private var port = 0
    private lateinit var root: String
    private lateinit var fs: FtpFileSystem

    @Before fun setUp() {
        home = tmp.newFolder("ftp-home")
        File(home, "pub").mkdirs()
        File(home, "pub/readme.txt").writeText("hello ftp")
        File(home, "pub/sub").mkdirs()
        File(home, "pub/sub/data.bin").writeBytes(ByteArray(200_000) { (it % 13).toByte() })
        File(home, "pub/Ünïcode file.txt").writeText("utf8")

        port = ServerSocket(0).use { it.localPort }
        val sf = FtpServerFactory()
        sf.addListener("default", ListenerFactory().apply { this.port = this@FtpIntegrationTest.port; serverAddress = "127.0.0.1" }.createListener())
        val um = PropertiesUserManagerFactory().createUserManager()
        um.save(BaseUser().apply {
            name = "qa"; password = "pw"; homeDirectory = home.path; enabled = true
            authorities = listOf<Authority>(WritePermission(), ConcurrentLoginPermission(20, 20), TransferRatePermission(0, 0))
        })
        sf.userManager = um
        server = sf.createServer()
        server.start()
        root = "ftp://127.0.0.1:$port"
        fs = FtpFileSystem(CredentialProvider { SmbCred("qa", "pw") })
    }

    @After fun tearDown() {
        runCatching { fs.disconnect(root) }
        server.stop()
    }

    @Test fun listsFolderWithSizesAndTypes() {
        val list = fs.list("$root/pub").sortedBy { it.name }
        assertEquals(listOf("readme.txt", "sub", "Ünïcode file.txt"), list.map { it.name })
        val readme = list.first { it.name == "readme.txt" }
        assertEquals(9L, readme.size); assertFalse(readme.isDir); assertEquals("$root/pub/readme.txt", readme.path)
        assertTrue(readme.modified > 0)
        assertTrue(list.first { it.name == "sub" }.isDir)
        assertEquals(listOf("pub"), fs.list(root).map { it.name })
    }

    @Test fun statFileFolderRootAndMissing() {
        assertEquals(9L, fs.stat("$root/pub/readme.txt")!!.size)
        assertTrue(fs.stat("$root/pub/sub")!!.isDir)
        assertNull(fs.stat("$root/pub/nope.txt"))
        val r = fs.stat(root)!!
        assertTrue(r.isServer && r.isDir)
    }

    @Test fun listAndStatFallbackToListWhenServerLacksMlsd() {
        val noMlsdPort = ServerSocket(0).use { it.localPort }
        val sf = FtpServerFactory()
        sf.addListener("default", ListenerFactory().apply { port = noMlsdPort; serverAddress = "127.0.0.1" }.createListener())
        val um = PropertiesUserManagerFactory().createUserManager()
        um.save(BaseUser().apply {
            name = "qa"; password = "pw"; homeDirectory = home.path; enabled = true
            authorities = listOf<Authority>(WritePermission(), ConcurrentLoginPermission(20, 20), TransferRatePermission(0, 0))
        })
        sf.userManager = um
        val cf = org.apache.ftpserver.command.CommandFactoryFactory()
        cf.addCommand("FEAT", org.apache.ftpserver.command.Command { session, _, _ ->
            session.write(org.apache.ftpserver.ftplet.DefaultFtpReply(211, "Extensions supported:\n UTF8\n211 End."))
        })
        sf.commandFactory = cf.createCommandFactory()
        val noMlsdServer = sf.createServer()
        noMlsdServer.start()
        val noMlsdRoot = "ftp://127.0.0.1:$noMlsdPort"
        try {
            val list = fs.list("$noMlsdRoot/pub").sortedBy { it.name }
            assertEquals(listOf("readme.txt", "sub", "Ünïcode file.txt"), list.map { it.name })
            assertEquals(9L, list.first { it.name == "readme.txt" }.size)
            assertTrue(list.first { it.name == "sub" }.isDir)
            assertEquals(9L, fs.stat("$noMlsdRoot/pub/readme.txt")!!.size)
            assertTrue(fs.stat("$noMlsdRoot/pub/sub")!!.isDir)
        } finally {
            runCatching { fs.disconnect(noMlsdRoot) }
            noMlsdServer.stop()
        }
    }

    @Test fun downloadAndUploadRoundTrip() {
        assertEquals("hello ftp", fs.openInput("$root/pub/readme.txt").use { it.readBytes().decodeToString() })
        assertEquals("utf8", fs.openInput("$root/pub/Ünïcode file.txt").use { it.readBytes().decodeToString() })
        val payload = ByteArray(1_000_000) { (it * 7).toByte() }
        fs.openOutput("$root/pub/upload.bin").use { it.write(payload) }
        assertTrue(payload.contentEquals(File(home, "pub/upload.bin").readBytes()))
        assertTrue(payload.contentEquals(fs.openInput("$root/pub/upload.bin").use { it.readBytes() }))
    }

    @Test fun concurrentStreamsUseSeparateConnections() {
        val a = fs.openInput("$root/pub/readme.txt")
        val b = fs.openInput("$root/pub/sub/data.bin")
        assertEquals(200_000, b.use { it.readBytes().size })
        assertEquals("hello ftp", a.use { it.readBytes().decodeToString() })
        // Pool still healthy afterwards.
        assertEquals(3, fs.list("$root/pub").size)
    }

    @Test fun missingFileDownloadIsAnError() {
        assertThrows(IOException::class.java) { fs.openInput("$root/pub/ghost.txt").use { it.read() } }
        assertEquals(3, fs.list("$root/pub").size) // connection returned to the pool in a usable state
    }

    @Test fun mkdirCreateFileRenameMoveDelete() {
        fs.mkdir("$root/pub/new dir")
        assertTrue(File(home, "pub/new dir").isDirectory)
        fs.mkdir("$root/pub/new dir") // already there: accepted
        fs.createFile("$root/pub/new dir/empty.txt")
        assertEquals(0L, File(home, "pub/new dir/empty.txt").length())
        assertThrows(IOException::class.java) { fs.createFile("$root/pub/readme.txt") }

        assertEquals("$root/pub/README.md", fs.rename("$root/pub/readme.txt", "README.md"))
        assertTrue(File(home, "pub/README.md").exists())
        assertThrows(IOException::class.java) { fs.rename("$root/pub/README.md", "sub") }

        assertTrue(fs.move("$root/pub/README.md", "$root/pub/new dir/README.md"))
        assertTrue(File(home, "pub/new dir/README.md").exists())
        assertFalse("never overwrites", fs.move("$root/pub/new dir/README.md", "$root/pub/new dir/empty.txt"))
        assertFalse("other server needs copy", fs.move("$root/pub/new dir/README.md", "ftp://elsewhere/x"))

        fs.delete("$root/pub/new dir/empty.txt")
        assertFalse(File(home, "pub/new dir/empty.txt").exists())
        fs.delete("$root/pub/new dir") // recursive
        assertFalse(File(home, "pub/new dir").exists())
        fs.delete("$root/pub/new dir") // missing: no-op
    }

    @Test fun badPasswordRaisesRemoteAuthException() {
        val bad = FtpFileSystem(CredentialProvider { SmbCred("qa", "nope") })
        val e = assertThrows(RemoteAuthException::class.java) { bad.list("$root/pub") }
        assertEquals(root, e.host)
        val anon = FtpFileSystem(CredentialProvider { null })
        assertThrows(RemoteAuthException::class.java) { anon.list(root) }
    }

    @Test fun unreachableServerIsAClearError() {
        val dead = ServerSocket(0).use { it.localPort }
        val e = assertThrows(IOException::class.java) { fs.list("ftp://127.0.0.1:$dead/") }
        assertTrue(e.message, e.message!!.startsWith("Can't reach"))
    }

    @Test fun copyTreesBetweenLocalAndFtp() = runBlocking {
        val local = tmp.newFolder("local")
        val ops = FileOperations(FileRepository(LocalFileSystem(), LocalFileSystem(), null, byScheme = mapOf("ftp" to fs)))
        ops.transfer(listOf("$root/pub/sub"), local.path, move = false, conflict = Conflict.KeepBoth) {}
        assertEquals(200_000L, File(local, "sub/data.bin").length())
        File(local, "up/inner").mkdirs(); File(local, "up/inner/x.txt").writeText("X")
        ops.transfer(listOf(File(local, "up").path), "$root/pub", move = true, conflict = Conflict.KeepBoth) {}
        assertEquals("X", File(home, "pub/up/inner/x.txt").readText())
        assertFalse(File(local, "up").exists())
        // Keep-both on the server side.
        File(local, "readme.txt").writeText("local")
        val res = ops.transfer(listOf(File(local, "readme.txt").path), "$root/pub", move = false, conflict = Conflict.KeepBoth) {}
        assertEquals("$root/pub/readme (1).txt", res.single().target)
        // Same-server move uses RNFR/RNTO.
        ops.transfer(listOf("$root/pub/readme (1).txt"), "$root/pub/sub", move = true, conflict = Conflict.KeepBoth) {}
        assertTrue(File(home, "pub/sub/readme (1).txt").exists())
    }
}
