// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.DriveAccount
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.PairedPhone
import com.droid.wayfinder.qa.VmHarness
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class QuickLookAndMenusCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    private fun idle() {
        rule.waitForIdle()
        h?.settle()
        rule.waitForIdle()
    }

    @Test
    fun quickLookPreviewsFolderZipBinaryTextMediaAndPlaceholders() {
        val hh = VmHarness().also { h = it }
        hh.dir("ql/empty_dir")
        hh.dir("ql/non_empty/sub")
        hh.file("ql/non_empty/readme.txt", "hello world")
        hh.file("ql/notes.toml", "title = \"Wayfinder\"\nversion = 2")
        hh.file("ql/blob.bin", "binary-payload-12345")
        hh.file("ql/corrupt.zip", "not-a-valid-zip-header")
        hh.file("ql/corrupt.pdf", "not-a-valid-pdf")
        hh.file("ql/track.mp3", "not-a-valid-mp3")
        hh.file("ql/photo.png", "fake-png")
        hh.file("ql/clip.mp4", "fake-mp4")

        val validZip = File(hh.root, "ql/archive.zip")
        ZipOutputStream(FileOutputStream(validZip)).use { zos ->
            zos.putNextEntry(ZipEntry("docs/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("docs/guide.txt"))
            zos.write("guide content".toByteArray())
            zos.closeEntry()
        }

        hh.navigate(hh.pathOf("ql"))

        rule.setContent {
            val s by hh.vm.state.collectAsState()
            WayfinderTheme(false) {
                QuickLook(hh.vm, s)
            }
        }
        idle()

        fun preview(rel: String) {
            hh.select(rel)
            if (hh.s.quickLook != null) hh.vm.closeQuickLook()
            hh.vm.toggleQuickLook()
            idle()
        }

        // 1. Empty folder preview
        preview("ql/empty_dir")
        rule.onNodeWithText("This folder is empty").assertIsDisplayed()

        // 2. Non-empty folder preview
        preview("ql/non_empty")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("2 items · 1 folder").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("readme.txt").assertIsDisplayed()

        // 3. Valid ZIP archive preview
        preview("ql/archive.zip")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("2 entries", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("docs/guide.txt").assertIsDisplayed()

        // 4. Corrupt ZIP archive falls back to BinaryPreview with error message
        preview("ql/corrupt.zip")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Couldn't read this archive:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 5. Binary file preview computes SHA-256 and CRC32
        preview("ql/blob.bin")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("SHA-256", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("CRC32", substring = true).assertIsDisplayed()

        // 6. Text / TOML preview with line numbers
        preview("ql/notes.toml")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Wayfinder", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 7. Corrupt PDF preview shows error placeholder
        preview("ql/corrupt.pdf")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Couldn't render this PDF.").fetchSemanticsNodes().isNotEmpty()
        }

        // 8. Unplayable audio file shows fallback text
        preview("ql/track.mp3")
        rule.onNodeWithText("Can't play this audio file.").assertIsDisplayed()

        // 9. Image and Video previews + Next/Previous/Close header buttons
        preview("ql/photo.png")
        rule.onNodeWithContentDescription("Next (→)").performClick()
        idle()
        rule.onNodeWithContentDescription("Previous (←)").performClick()
        idle()

        preview("ql/clip.mp4")
        rule.onNodeWithContentDescription("Play").performClick()
        idle()
        assertNull(hh.s.quickLook)
    }

    @Test
    fun quickLookPlaceholdersForRemoteAndArchiveEntries() {
        val hh = VmHarness().also { h = it }
        val remoteEntry = FileEntry(path = "smb://nas.local/docs/report.txt", name = "report.txt", isDir = false, size = 128L, modified = 1000L)
        val zipEntry = FileEntry(path = "/storage/emulated/0/a.zip!/inner.txt", name = "inner.txt", isDir = false, size = 64L, modified = 1000L)

        val currentPath = androidx.compose.runtime.mutableStateOf(remoteEntry.path)
        rule.setContent {
            val base = hh.s
            val activeTab = base.active.copy(entries = listOf(remoteEntry, zipEntry))
            val customState = base.copy(
                tabs = listOf(activeTab),
                activeId = activeTab.id,
                quickLook = currentPath.value,
            )
            WayfinderTheme(false) {
                QuickLook(hh.vm, customState)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Preview isn't available for network and cloud files yet. Press Enter to open.").assertIsDisplayed()

        currentPath.value = zipEntry.path
        rule.waitForIdle()
        rule.onNodeWithText("Preview isn't available inside archives. Press Enter to open.").assertIsDisplayed()
    }

    @Test
    fun contextMenusAndMenuHostCoverSidebarMountTabDriveAndPhoneMenus() {
        val hh = VmHarness().also { h = it }
        val dirFile = hh.dir("menu_dir")
        val itemFile = hh.file("menu_dir/doc.txt", "hello")
        hh.navigate(dirFile.path)
        hh.select("menu_dir/doc.txt")

        val menu = MenuController()
        var clickedItem = false
        menu.show(
            Offset(10f, 20f),
            listOf(
                MenuEntry.Divider,
                MenuEntry.Item("Checked Action", shortcut = "Ctrl+T", checked = true) { clickedItem = true },
                MenuEntry.Divider,
                MenuEntry.Divider,
                MenuEntry.Item("Submenu Parent", submenu = {
                    listOf(MenuEntry.Item("Submenu Child") { clickedItem = true })
                }) {},
                MenuEntry.Divider,
            ),
        )
        // Leading/trailing/duplicate dividers are trimmed
        assertEquals(3, menu.items.size)

        rule.setContent {
            WayfinderTheme(false) {
                MenuHost(menu)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Checked Action").assertIsDisplayed()
        rule.onNodeWithText("Submenu Parent").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Submenu Child").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTrue(clickedItem)
        assertNull(menu.offset)

        // sidebarMenu: unpinned -> pin, pinned -> move up / move down / unpin, SMB root -> remove server
        val unpinnedItems = sidebarMenu(hh.vm, hh.s, dirFile.path, pinned = false).filterIsInstance<MenuEntry.Item>()
        unpinnedItems.single { it.label == "Pin to sidebar" }.action()
        idle()
        assertTrue(dirFile.path in hh.s.pins)

        val pinnedItems = sidebarMenu(hh.vm, hh.s, dirFile.path, pinned = true).filterIsInstance<MenuEntry.Item>()
        pinnedItems.single { it.label == "Move up" }.action()
        pinnedItems.single { it.label == "Move down" }.action()
        pinnedItems.single { it.label == "Unpin from sidebar" }.action()
        idle()
        assertFalse(dirFile.path in hh.s.pins)

        // fileMenu on a regular file also includes Pin to sidebar / Unpin from sidebar
        val docEntry = hh.s.active.entries.single { it.path == itemFile.path }
        val fileMenuPin = fileMenu(hh.vm, hh.s, docEntry).filterIsInstance<MenuEntry.Item>().single { it.label == "Pin to sidebar" }
        fileMenuPin.action()
        idle()
        assertTrue(itemFile.path in hh.s.pins)
        val pinnedFileSidebarMenu = sidebarMenu(hh.vm, hh.s, itemFile.path, pinned = true).filterIsInstance<MenuEntry.Item>()
        assertTrue(pinnedFileSidebarMenu.any { it.label == "Show in enclosing folder" })
        val fileMenuUnpin = fileMenu(hh.vm, hh.s, docEntry).filterIsInstance<MenuEntry.Item>().single { it.label == "Unpin from sidebar" }
        fileMenuUnpin.action()
        idle()
        assertFalse(itemFile.path in hh.s.pins)

        val smbRootItems = sidebarMenu(hh.vm, hh.s, "smb://nas.local", pinned = false).filterIsInstance<MenuEntry.Item>()
        assertTrue(smbRootItems.any { it.label == "Remove server" })
        smbRootItems.single { it.label == "Remove server" }.action()

        // mountMenu: SAF mount vs saved server mount
        val safMount = Mount("saf://m1", "Drive", "Cloud", "m1", saf = true)
        val safMountItems = mountMenu(hh.vm, safMount).filterIsInstance<MenuEntry.Item>()
        assertEquals(listOf("Unmount", "Forget"), safMountItems.map { it.label })
        safMountItems.forEach { it.action() }

        val srvMount = Mount("smb://nas.local/share", "share on nas.local", "SMB", "nas.local", saf = false)
        val srvMountItems = mountMenu(hh.vm, srvMount).filterIsInstance<MenuEntry.Item>()
        assertEquals(listOf("Disconnect", "Forget"), srvMountItems.map { it.label })
        srvMountItems.forEach { it.action() }

        // tabMenu: Duplicate, Close other tabs, Close tab
        val firstTabId = hh.s.activeId
        val tabItems = tabMenu(hh.vm, firstTabId).filterIsInstance<MenuEntry.Item>()
        tabItems.single { it.label == "Duplicate tab" }.action()
        idle()
        assertEquals(2, hh.s.tabs.size)
        tabItems.single { it.label == "Close other tabs" }.action()
        idle()
        assertEquals(1, hh.s.tabs.size)

        // driveAccountMenu: unmounted vs mounted
        val unmountedAcc = DriveAccount("alice@gmail.com", "Alice", isMounted = false, mountPath = null)
        val unmountedDriveItems = driveAccountMenu(hh.vm, unmountedAcc, null).filterIsInstance<MenuEntry.Item>()
        assertEquals(
            listOf("Browse My Drive (REST API)", "Mount folder via picker (SAF)…"),
            unmountedDriveItems.map { it.label },
        )
        unmountedDriveItems.single { it.label == "Mount folder via picker (SAF)…" }.action()
        idle()
        hh.vm.dismissDialog()

        val mountedAcc = DriveAccount("alice@gmail.com", "Alice", isMounted = true, mountPath = "saf://m1")
        val mountedDriveItems = driveAccountMenu(hh.vm, mountedAcc, safMount).filterIsInstance<MenuEntry.Item>()
        assertEquals(
            listOf("Open", "Open in new tab", "Open in new window", "Unmount", "Forget"),
            mountedDriveItems.map { it.label },
        )
        mountedDriveItems.single { it.label == "Unmount" }.action()
        mountedDriveItems.single { it.label == "Forget" }.action()

        // phoneMenu: mounted vs unmounted actions
        val phone = PairedPhone(name = "Pixel 9 Pro", subtitle = "Internal Storage", path = dirFile.path, isConnected = true, isMounted = true)
        val phoneItems = phoneMenu(hh.vm, phone, safMount).filterIsInstance<MenuEntry.Item>()
        assertEquals(7, phoneItems.size)
        phoneItems.single { it.label == "Open" }.action()
        phoneItems.single { it.label == "Quick Share folder" }.action()
        phoneItems.single { it.label == "Phone Hub settings" }.action()
        val unmountedPhone = PairedPhone(name = "Pixel 9 Pro", subtitle = "Tap to connect", path = FsPath.PHONE_ROOT, isConnected = true, isMounted = false)
        val unmountedPhoneItems = phoneMenu(hh.vm, unmountedPhone).filterIsInstance<MenuEntry.Item>()
        assertEquals(4, unmountedPhoneItems.size)
        idle()

        // backgroundMenu for collection, Trash root, and SMB root
        val trashState = hh.s.copy(tabs = listOf(hh.s.active.copy(path = FsPath.TRASH_ROOT)))
        assertEquals(listOf("Empty Trash", "Refresh"), backgroundMenu(hh.vm, trashState).filterIsInstance<MenuEntry.Item>().map { it.label })

        val smbState = hh.s.copy(tabs = listOf(hh.s.active.copy(path = FsPath.SMB_ROOT)))
        assertEquals(listOf("Connect to server…", "Rescan network"), backgroundMenu(hh.vm, smbState).filterIsInstance<MenuEntry.Item>().map { it.label })

        val starState = hh.s.copy(tabs = listOf(hh.s.active.copy(path = FsPath.STARRED_ROOT)))
        assertEquals(listOf("Select all", "Refresh", "Open in new window"), backgroundMenu(hh.vm, starState).filterIsInstance<MenuEntry.Item>().map { it.label })

        // starItem and tagsItem submenu
        val starEntry = starItem(hh.vm, hh.s, listOf(itemFile.path)) as MenuEntry.Item
        assertEquals("Add to Starred", starEntry.label)
        starEntry.action()
        idle()
        val tagsEntry = tagsItem(hh.vm, hh.s, listOf(itemFile.path), back = { emptyList() }) as MenuEntry.Item
        val tagSubItems = tagsEntry.submenu!!.invoke().filterIsInstance<MenuEntry.Item>()
        assertTrue(tagSubItems.any { it.label == "Back" })
        tagSubItems.first { it.label != "Back" }.action()
    }

    @Test
    fun dialogsCoverSmbAuthSafMountTipShortcutsAndCompress() {
        val hh = VmHarness().also { h = it }
        val f = hh.file("dlg/report.txt", "content")
        hh.navigate(hh.pathOf("dlg"))
        hh.select("dlg/report.txt")

        rule.setContent {
            val s by hh.vm.state.collectAsState()
            WayfinderTheme(false) {
                Dialogs(hh.vm, s)
            }
        }
        idle()

        // 1. SmbAuth dialog (SMB host shows Domain field)
        hh.vm.showDialog(Dialog.SmbAuth(host = "nas.local", user = "alice", domain = "WORKGROUP"))
        idle()
        rule.onNodeWithText("Sign in to nas.local").assertIsDisplayed()
        rule.onNodeWithText("Domain / workgroup (optional)").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Password")).performTextInput("secret")
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        rule.onNodeWithText("Sign in").performClick()
        idle()

        // 2. SafMountTip dialog with Google Drive account email (renders GoogleDriveIcon)
        hh.vm.showDialog(Dialog.SafMountTip(accountEmail = "team@gmail.com"))
        idle()
        rule.onNodeWithText("Connect Google Drive").assertIsDisplayed()
        rule.onNodeWithText("Google Drive Selection Tip").assertIsDisplayed()
        rule.onNodeWithText("Don't show this tip again").performClick()
        rule.onNodeWithText("Open Drive Picker").performClick()
        idle()
        assertNull(hh.s.dialog)

        // 3. SafMountTip dialog without account email (generic Cloud/External Storage tip)
        hh.vm.showDialog(Dialog.SafMountTip(accountEmail = null))
        idle()
        rule.onNodeWithText("Mount Cloud or External Storage").assertIsDisplayed()
        rule.onNodeWithText("Important Android Tip").assertIsDisplayed()
        rule.onNodeWithText("Open Folder Picker").performClick()
        idle()
        assertNull(hh.s.dialog)

        // 4. Shortcuts dialog
        hh.vm.showDialog(Dialog.Shortcuts)
        idle()
        rule.onNodeWithText("Keyboard & trackpad shortcuts").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        idle()
        assertNull(hh.s.dialog)

        // 5. Compress dialog
        hh.vm.requestCompress()
        idle()
        rule.onNodeWithText("Compress to ZIP").assertIsDisplayed()
        rule.onNodeWithText("Compress").performClick()
        hh.until { File(hh.root, "dlg/report.zip").exists() }
    }

    @Test
    fun fileIconsAndDateFormattersCoverAllKindsAndBranches() {
        val serverEntry = FileEntry(path = "smb://nas", name = "nas", isDir = true, size = 0L, modified = 0L, isServer = true)
        val shareEntry = FileEntry(path = "smb://nas/pub", name = "pub", isDir = true, size = 0L, modified = 0L, isShare = true)
        rule.setContent {
            WayfinderTheme(false) {
                GoogleDriveIcon(Modifier.size(24.dp))
                for (kind in FileKind.entries) {
                    assertNotNull(kind.icon())
                    assertNotNull(kind.tint())
                }
                assertNotNull(serverEntry.icon())
                assertNotNull(serverEntry.tint())
                assertNotNull(shareEntry.icon())
                assertNotNull(shareEntry.tint())
            }
        }
        rule.waitForIdle()

        for (p in listOf("/storage/Download", "/storage/Documents", "/storage/Pictures", "/storage/Music", "/storage/Movies", "/storage/Custom")) {
            assertNotNull(pinIcon(p))
        }
        assertNotNull(rootIcon(removable = true))
        assertNotNull(rootIcon(removable = false))

        assertEquals("", formatDate(0L))
        assertEquals("—", formatDateFull(0L))
        val now = System.currentTimeMillis()
        assertTrue(formatDate(now).isNotEmpty())
        assertTrue(formatDateFull(now).isNotEmpty())

        val oldCal = Calendar.getInstance().apply { set(2001, Calendar.JANUARY, 15, 10, 30) }
        assertTrue(formatDate(oldCal.timeInMillis).isNotEmpty())
    }
}
