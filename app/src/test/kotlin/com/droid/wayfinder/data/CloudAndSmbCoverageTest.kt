// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2FileId
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.common.SmbPath as SmbjPath
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.connection.ConnectionContext
import com.hierynomus.smbj.connection.NegotiatedProtocol
import com.hierynomus.smbj.event.SMBEventBus
import com.hierynomus.smbj.server.ServerList
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.Share
import com.hierynomus.smbj.share.TestSmbFile
import com.hierynomus.smbj.share.TreeConnect
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1
import com.rapid7.client.dcerpc.transport.RPCTransport
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Set as JavaSet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudAndSmbCoverageTest {

    // ------------------------------------------------------------------ DriveRestFileSystem

    private data class RecordedCall(val method: String, val url: String, val auth: String?, val body: String)

    @Test
    fun driveRestFileSystemFullLifecycleAndErrorPaths() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<RecordedCall>()
        var nextStatus = 200
        var nextBody = "{}"

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                calls += RecordedCall(
                    method = req.method,
                    url = req.url.toString(),
                    auth = req.header("Authorization"),
                    body = buf.readUtf8(),
                )
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextStatus)
                    .message(if (nextStatus in 200..299) "OK" else "Error")
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val fs = DriveRestFileSystem(context, client)

        // Token management
        fs.setToken("Alice@Example.com", "tok-123")
        assertEquals("tok-123", fs.getToken("alice@example.com"))
        fs.disconnect("ALICE@EXAMPLE.COM")
        try {
            fs.getToken("alice@example.com")
            org.junit.Assert.fail("Expected IOException when no token cached and AccountManager has no token")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Google Drive authorization required"))
        }

        val overrideFs = DriveRestFileSystem(context, client, tokenOverride = { "override-$it" })
        assertEquals("override-user@test.com", overrideFs.getToken("user@test.com"))

        // Path parsing & ID safety
        assertEquals("user@test.com" to "root", overrideFs.parsePath("gdrive://user@test.com/"))
        assertEquals("user@test.com" to "folder_1", overrideFs.parsePath("gdrive://user@test.com/folder_1"))
        assertFalse(DriveRestFileSystem.isSafeDriveId(".."))
        assertFalse(DriveRestFileSystem.isSafeDriveId("bad/id"))
        assertTrue(DriveRestFileSystem.isSafeDriveId("1AbC_dEf-9.0"))

        // list() with folders, files, invalid IDs, and both ISO timestamp formats
        nextStatus = 200
        nextBody = """
            {
              "files": [
                {"id": "file_b", "name": "notes.txt", "mimeType": "text/plain", "size": 42, "modifiedTime": "2026-01-15T10:20:30.500Z"},
                {"id": "dir_a", "name": "Projects", "mimeType": "application/vnd.google-apps.folder", "modifiedTime": "2026-01-15T10:20:30Z"},
                {"id": "../bad", "name": "evil", "mimeType": "text/plain"},
                {"id": "file_c", "name": "report.pdf", "mimeType": "application/pdf", "size": 100, "modifiedTime": ""}
              ]
            }
        """.trimIndent()

        val listed = overrideFs.list("gdrive://user@test.com/root")
        assertEquals(3, listed.size)
        // Sorted with directories first, then alphabetical
        assertEquals("Projects", listed[0].name)
        assertTrue(listed[0].isDir)
        assertEquals("notes.txt", listed[1].name)
        assertEquals(42L, listed[1].size)
        assertEquals("report.pdf", listed[2].name)

        // list() HTTP error
        nextStatus = 403
        nextBody = """{"error":"forbidden"}"""
        try {
            overrideFs.list("gdrive://user@test.com/root")
            org.junit.Assert.fail("Expected list HTTP 403 to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 403"))
        }

        // stat() root, valid file, invalid ID, and HTTP 404
        val rootStat = overrideFs.stat("gdrive://user@test.com/root")
        assertNotNull(rootStat)
        assertEquals("My Drive", rootStat!!.name)
        assertTrue(rootStat.isDir)

        assertNull(overrideFs.stat("gdrive://user@test.com/.."))

        nextStatus = 200
        nextBody = """{"id":"file_b","name":"notes.txt","mimeType":"text/plain","size":128,"modifiedTime":"invalid-date"}"""
        val fileStat = overrideFs.stat("gdrive://user@test.com/file_b")
        assertNotNull(fileStat)
        assertEquals("notes.txt", fileStat!!.name)
        assertEquals(128L, fileStat.size)

        nextStatus = 404
        assertNull(overrideFs.stat("gdrive://user@test.com/file_b"))

        // openInput() success & error
        nextStatus = 200
        nextBody = "hello drive world"
        val downloaded = overrideFs.openInput("gdrive://user@test.com/file_b").use { it.readBytes().decodeToString() }
        assertEquals("hello drive world", downloaded)

        nextStatus = 500
        try {
            overrideFs.openInput("gdrive://user@test.com/file_b").close()
            org.junit.Assert.fail("Expected openInput HTTP 500 to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }

        // openOutput() success & error
        nextStatus = 200
        nextBody = """{"id":"uploaded_1"}"""
        overrideFs.openOutput("gdrive://user@test.com/new.txt").use { out ->
            out.write('A'.code)
            out.write("BCDEF".toByteArray(), 0, 5)
        }
        assertEquals("ABCDEF", calls.last().body)

        nextStatus = 502
        try {
            overrideFs.openOutput("gdrive://user@test.com/new.txt").use { it.write("x".toByteArray()) }
            org.junit.Assert.fail("Expected openOutput HTTP 502 to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 502"))
        }

        // mkdir(), createFile(), delete(), rename()
        nextStatus = 200
        overrideFs.mkdir("gdrive://user@test.com/NewFolder")
        assertEquals("application/vnd.google-apps.folder", JSONObject(calls.last().body).getString("mimeType"))

        overrideFs.createFile("gdrive://user@test.com/NewFile.txt")
        assertTrue(calls.last().body.contains("NewFile.txt"))

        val renamed = overrideFs.rename("gdrive://user@test.com/file_b", "renamed.txt")
        assertEquals("gdrive://user@test.com/file_b", renamed)
        assertEquals("PATCH", calls.last().method)

        overrideFs.delete("gdrive://user@test.com/file_b")
        assertEquals("DELETE", calls.last().method)
    }

    // ------------------------------------------------------------------ SmbFileSystem

    private class FakeSmbContext {
        var connected = true
        var failNextListWithIo = false
        var failNextListWithSmbRuntime = false
        var failAuthWithSmbApi = false
        var forceClosedConnections = 0
        val files = mutableMapOf(
            "docs\\readme.txt" to "hello smb".toByteArray(),
        )
        val dirs = mutableSetOf("docs", "empty_dir")
        var sharesList: List<NetShareInfo1> = listOf(
            NetShareInfo1("Public", 0, "Public Share"),
            NetShareInfo1("IPC$", 3, "IPC"),
            NetShareInfo1("Admin$", 0, "Hidden Admin"),
            NetShareInfo1("..", 0, "Unsafe"),
        )
        var shareListError: Exception? = null

        val config: SmbConfig = SmbConfig.createDefaultConfig()
        val bus = SMBEventBus()

        val client = object : SMBClient(config, bus) {
            override fun connect(hostname: String): Connection = connect(hostname, SMBClient.DEFAULT_PORT)
            override fun connect(hostname: String, port: Int): Connection {
                if (hostname == "unreachable.local") throw IOException("Host down")
                return createConnection()
            }
        }

        fun createConnection(): Connection = object : Connection(config, client, bus, ServerList()) {
            private var open = true
            override fun isConnected(): Boolean = connected && open
            override fun close() {
                open = false
            }
            override fun close(force: Boolean) {
                open = false
                if (force) forceClosedConnections++
            }
            override fun authenticate(authContext: AuthenticationContext?): Session {
                if (failAuthWithSmbApi) {
                    throw SMBApiException(NtStatus.STATUS_LOGON_FAILURE.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "Logon failed", null)
                }
                return createSession(this, authContext)
            }
        }

        fun createSession(conn: Connection, authContext: AuthenticationContext?): Session =
            object : Session(conn, config, authContext ?: AuthenticationContext.guest(), bus, null, null, null) {
                override fun connectShare(shareName: String): Share {
                    if (shareName == "denied") {
                        throw SMBApiException(NtStatus.STATUS_ACCESS_DENIED.value, SMB2MessageCommandCode.SMB2_TREE_CONNECT, "Denied", null)
                    }
                    return createDiskShare(this, shareName)
                }
                override fun close() {}
            }

        fun createDiskShare(session: Session, shareName: String): DiskShare {
            val ctxCtor = ConnectionContext::class.java.declaredConstructors.first().apply { isAccessible = true }
            val connCtx = ctxCtor.newInstance(java.util.UUID.randomUUID(), "nas.local", 445, config) as ConnectionContext
            val negField = ConnectionContext::class.java.getDeclaredField("negotiatedProtocol").apply { isAccessible = true }
            negField.set(connCtx, NegotiatedProtocol(SMB2Dialect.SMB_3_1_1, 65536, 65536, 65536, true))

            val smbPath = SmbjPath("nas.local", shareName)
            val treeConnect = TreeConnect(1L, smbPath, session, emptySet(), config, connCtx, bus, emptySet(), emptySet())

            return object : DiskShare(smbPath, treeConnect, null) {
                override fun isConnected(): Boolean = connected
                override fun close() {}

                override fun list(path: String): List<FileIdBothDirectoryInformation> {
                    if (failNextListWithIo) {
                        failNextListWithIo = false
                        throw IOException("Broken pipe")
                    }
                    if (failNextListWithSmbRuntime) {
                        failNextListWithSmbRuntime = false
                        throw SMBRuntimeException(TransportException("Broken pipe"))
                    }
                    val ctor = FileIdBothDirectoryInformation::class.java.declaredConstructors.first().apply { isAccessible = true }
                    val now = FileTime.ofEpochMillis(1700000000000L)
                    return listOf(
                        ctor.newInstance(
                            0L, 0L, "docs", now, now, now, now, 0L, 0L,
                            FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value, 0L, "docs", 1L,
                        ) as FileIdBothDirectoryInformation,
                        ctor.newInstance(
                            0L, 0L, ".hidden.txt", now, now, now, now, 9L, 9L,
                            FileAttributes.FILE_ATTRIBUTE_HIDDEN.value, 0L, "hidden", 2L,
                        ) as FileIdBothDirectoryInformation,
                        ctor.newInstance(
                            0L, 0L, "../escape", now, now, now, now, 1L, 1L,
                            0L, 0L, "esc", 3L,
                        ) as FileIdBothDirectoryInformation,
                    )
                }

                override fun fileExists(path: String): Boolean = files.containsKey(path)
                override fun folderExists(path: String): Boolean = dirs.contains(path)

                override fun getFileInformation(path: String): FileAllInformation {
                    val now = FileTime.ofEpochMillis(1700000000000L)
                    val isDir = dirs.contains(path)
                    val attrs = if (isDir) FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value else FileAttributes.FILE_ATTRIBUTE_NORMAL.value
                    val basic = FileBasicInformation(now, now, now, now, attrs)
                    val stdCtor = FileStandardInformation::class.java.declaredConstructors.first().apply { isAccessible = true }
                    val size = files[path]?.size?.toLong() ?: 0L
                    val std = stdCtor.newInstance(size, size, 1L, false, isDir) as FileStandardInformation
                    val allCtor = FileAllInformation::class.java.declaredConstructors.first().apply { isAccessible = true }
                    return allCtor.newInstance(basic, std, null, null, null, null, null, null, path) as FileAllInformation
                }

                override fun mkdir(path: String) {
                    dirs.add(path)
                }

                override fun rmdir(path: String, recursive: Boolean) {
                    dirs.remove(path)
                }

                override fun rm(path: String) {
                    files.remove(path)
                }

                override fun open(
                    path: String,
                    accessMask: MutableSet<AccessMask>?,
                    attributes: MutableSet<FileAttributes>?,
                    shareAccess: MutableSet<SMB2ShareAccess>?,
                    createDisposition: SMB2CreateDisposition?,
                    createOptions: MutableSet<SMB2CreateOptions>?,
                ): DiskEntry {
                    return object : TestSmbFile(SMB2FileId(byteArrayOf(1), byteArrayOf(1)), this, SmbjPath(smbPath, path)) {
                        override fun rename(newName: String) {
                            if (dirs.remove(path)) dirs.add(newName)
                            files.remove(path)?.let { files[newName] = it }
                        }
                        override fun close() {}
                    }
                }

                override fun openFile(
                    path: String,
                    accessMask: MutableSet<AccessMask>?,
                    attributes: MutableSet<FileAttributes>?,
                    shareAccess: MutableSet<SMB2ShareAccess>?,
                    createDisposition: SMB2CreateDisposition?,
                    createOptions: MutableSet<SMB2CreateOptions>?,
                ): SmbFile {
                    if (createDisposition == SMB2CreateDisposition.FILE_CREATE) {
                        files[path] = ByteArray(0)
                    }
                    return object : TestSmbFile(SMB2FileId(byteArrayOf(1), byteArrayOf(1)), this, SmbjPath(smbPath, path)) {
                        override fun getInputStream(): InputStream = ByteArrayInputStream(files[path] ?: ByteArray(0))
                        override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                            override fun close() {
                                super.close()
                                files[path] = toByteArray()
                            }
                        }
                        override fun close() {}
                    }
                }
            }
        }
    }

    @Test
    fun smbFileSystemOperationsReconnectAndErrorHandling() {
        val fake = FakeSmbContext()
        var creds: SmbCred? = SmbCred("alice", "secret", "WORKGROUP")
        val dummyTransport = object : RPCTransport() {
            override fun transact(writeBuffer: ByteArray?, readBuffer: ByteArray?): Int = 0
            override fun write(writeBuffer: ByteArray?) {}
            override fun read(readBuffer: ByteArray?): Int = 0
        }
        val fs = SmbFileSystem(
            creds = CredentialProvider { creds },
            client = fake.client,
            serverServiceFactory = {
                fake.shareListError?.let { throw it }
                object : ServerService(dummyTransport) {
                    override fun getShares1(): List<NetShareInfo1> = fake.sharesList
                }
            },
        )

        // listShares filters out IPC$, Admin$, and unsafe '..'
        val shares = fs.list("smb://nas.local")
        assertEquals(1, shares.size)
        assertEquals("Public", shares[0].name)
        assertTrue(shares[0].isShare)

        // listShares error handling
        fake.shareListError = SMBApiException(NtStatus.STATUS_ACCESS_DENIED.value, SMB2MessageCommandCode.SMB2_IOCTL, "Denied", null)
        try {
            fs.list("smb://nas.local")
            org.junit.Assert.fail("Expected SMBApiException in listShares to wrap into IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Couldn't list shares"))
        }
        fake.shareListError = null

        // list directory with automatic reconnect on transient IOException and SMBRuntimeException(TransportException)
        fake.failNextListWithIo = true
        val entries = fs.list("smb://nas.local/Public")
        assertEquals(2, entries.size)
        assertEquals("docs", entries[0].name)
        assertTrue(entries[0].isDir)
        assertEquals(".hidden.txt", entries[1].name)
        assertTrue(entries[1].isHidden)

        val closedBeforeRuntimeRetry = fake.forceClosedConnections
        fake.failNextListWithSmbRuntime = true
        val entriesAfterRuntimeDrop = fs.list("smb://nas.local/Public")
        assertEquals(2, entriesAfterRuntimeDrop.size)
        assertEquals(closedBeforeRuntimeRetry + 1, fake.forceClosedConnections)

        // stat server root, share root, existing folder, existing file, missing file
        assertTrue(fs.stat("smb://nas.local")!!.isServer)
        assertTrue(fs.stat("smb://nas.local/Public")!!.isShare)
        assertTrue(fs.stat("smb://nas.local/Public/docs")!!.isDir)
        val fileStat = fs.stat("smb://nas.local/Public/docs/readme.txt")
        assertNotNull(fileStat)
        assertEquals(9L, fileStat!!.size)
        assertNull(fs.stat("smb://nas.local/Public/missing.txt"))

        // mkdir, createFile, openOutput, openInput, rename, delete
        fs.mkdir("smb://nas.local/Public/new_dir")
        assertTrue(fake.dirs.contains("new_dir"))

        fs.createFile("smb://nas.local/Public/docs/created.txt")
        assertTrue(fake.files.containsKey("docs\\created.txt"))

        fs.openOutput("smb://nas.local/Public/docs/created.txt").use {
            it.write("updated payload".toByteArray())
        }
        val readBack = fs.openInput("smb://nas.local/Public/docs/created.txt").use { it.readBytes().decodeToString() }
        assertEquals("updated payload", readBack)

        val renamedPath = fs.rename("smb://nas.local/Public/docs/created.txt", "moved.txt")
        assertEquals("smb://nas.local/Public/docs/moved.txt", renamedPath)
        assertTrue(fake.files.containsKey("docs\\moved.txt"))

        // move across different shares returns false
        assertFalse(fs.move("smb://nas.local/Public/docs/moved.txt", "smb://nas.local/Other/moved.txt"))

        fs.delete("smb://nas.local/Public/docs/moved.txt")
        assertFalse(fake.files.containsKey("docs\\moved.txt"))
        fs.delete("smb://nas.local/Public/new_dir")
        assertFalse(fake.dirs.contains("new_dir"))

        // Auth failure throws SmbAuthException
        fs.disconnect("nas.local")
        creds = null
        fake.failAuthWithSmbApi = true
        try {
            fs.list("smb://nas.local/Public")
            org.junit.Assert.fail("Expected SmbAuthException")
        } catch (e: SmbAuthException) {
            assertEquals("nas.local", e.host)
        }
    }

    // ------------------------------------------------------------------ NetworkDiscovery

    @Test
    fun networkDiscoveryDeduplicatesPrefersNamedHostAndProbesSubnet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val discovery = NetworkDiscovery(context, scope)

        // Invoke private add() to test host sanitization, IP replacement, and sorting
        val addMethod = NetworkDiscovery::class.java.getDeclaredMethod("add", DiscoveredHost::class.java).apply {
            isAccessible = true
        }
        addMethod.invoke(discovery, DiscoveredHost("192.168.1.50", "192.168.1.50"))
        assertEquals(listOf(DiscoveredHost("192.168.1.50", "192.168.1.50")), discovery.hosts.value)

        // Named mDNS discovery for the same IP replaces bare IP label
        addMethod.invoke(discovery, DiscoveredHost("Office-NAS\u0007", "192.168.1.50"))
        assertEquals(listOf(DiscoveredHost("Office-NAS", "192.168.1.50")), discovery.hosts.value)

        // Adding bare IP after named host preserves the named host
        addMethod.invoke(discovery, DiscoveredHost("192.168.1.50", "192.168.1.50"))
        assertEquals("Office-NAS", discovery.hosts.value.single().label)

        // Test enqueue / resolveNext via reflection with NsdServiceInfo
        val enqueueMethod = NetworkDiscovery::class.java.getDeclaredMethod("enqueue", NsdServiceInfo::class.java).apply {
            isAccessible = true
        }
        val svc = NsdServiceInfo().apply {
            serviceName = "MediaBox"
            serviceType = "_smb._tcp"
            @Suppress("DEPRECATION")
            host = InetAddress.getByName("192.168.1.77")
            port = 445
        }
        enqueueMethod.invoke(discovery, svc)

        // Start and stop discovery before setting LinkProperties so scan() exits immediately
        discovery.start()
        discovery.stop()

        // Configure ConnectivityManager active network LinkProperties to cover localPrefix()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet: Network? = cm.activeNetwork
        if (activeNet != null) {
            val lp = LinkProperties()
            val laCtor = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            lp.setLinkAddresses(listOf(laCtor.newInstance(InetAddress.getByName("192.168.99.10"), 24)))
            shadowOf(cm).setLinkProperties(activeNet, lp)
            val localPrefix = NetworkDiscovery::class.java.getDeclaredMethod("localPrefix").apply { isAccessible = true }
            assertEquals("192.168.99", localPrefix.invoke(discovery))
        }
    }
}
