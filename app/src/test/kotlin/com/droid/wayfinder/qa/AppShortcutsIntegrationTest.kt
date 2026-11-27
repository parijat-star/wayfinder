// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.AppShortcutSync
import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.AppShortcuts
import com.droid.wayfinder.data.OneDriveAccount
import com.droid.wayfinder.data.OneDriveAccountStore
import com.droid.wayfinder.data.OneDriveTokens
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.ui.BrowserViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppShortcutsIntegrationTest {
    private var h: VmHarness? = null

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
    }

    @After
    fun tearDown() {
        h?.close()
        h = null
        MainActivity.hasAccessOverride = null
    }

    private fun createStandardDirs(root: File): List<File> =
        listOf("Download", "Documents", "Pictures", "Music", "Movies").map { name ->
            File(root, name).apply { mkdirs() }
        }

    @Test
    fun startupPublishesDynamicShortcutsMirroringDefaultSidebarPins() {
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        createStandardDirs(root)

        val harness = VmHarness().also { h = it }
        harness.until(what = "dynamic shortcuts published") {
            ShortcutManagerCompat.getDynamicShortcuts(harness.app).size == 5
        }

        val published = ShortcutManagerCompat.getDynamicShortcuts(harness.app).sortedBy { it.rank }
        assertEquals(listOf("Downloads", "Documents", "Pictures", "Music", "Movies"), published.map { it.shortLabel.toString() })
        assertEquals(listOf(0, 1, 2, 3, 4), published.map { it.rank })

        for (shortcut in published) {
            val intent = shortcut.intent
            assertEquals(AppShortcuts.ACTION_OPEN_SHORTCUT, intent.action)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(
                "Shortcut intent must reuse existing window via CLEAR_TOP | SINGLE_TOP",
                (intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0 &&
                    (intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0,
            )
            assertFalse("Shortcut intent must never leak IntentGuard token", intent.hasExtra(IntentGuard.EXTRA_TOKEN))
            for (internalExtra in IntentGuard.INTERNAL_EXTRAS) {
                assertFalse("Shortcut intent must not include $internalExtra", intent.hasExtra(internalExtra))
            }
        }
    }

    @Test
    fun pinningCustomFolderReorderingAndSkippingFilesUpdatesDynamicShortcuts() {
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        createStandardDirs(root)
        val projects = File(root, "Projects").apply { mkdirs() }
        val readme = File(root, "README.txt").apply { writeText("hello") }

        val harness = VmHarness().also { h = it }
        harness.until(what = "initial shortcuts published") {
            ShortcutManagerCompat.getDynamicShortcuts(harness.app).size == 5
        }

        // Pin a regular file and a custom folder, then move both up to the top of the sidebar.
        harness.vm.pin(readme.path)
        harness.vm.pin(projects.path)
        repeat(6) { harness.vm.movePin(projects.path, -1) }
        repeat(6) { harness.vm.movePin(readme.path, -1) }

        harness.until(what = "custom folder promoted to rank 0 and file skipped") {
            val list = ShortcutManagerCompat.getDynamicShortcuts(harness.app).sortedBy { it.rank }
            list.firstOrNull()?.shortLabel?.toString() == "Projects"
        }

        val published = ShortcutManagerCompat.getDynamicShortcuts(harness.app).sortedBy { it.rank }
        assertEquals(listOf("Projects", "Downloads", "Documents", "Pictures", "Music"), published.map { it.shortLabel.toString() })
        assertTrue("Pinned files must not be published as folder shortcuts", published.none { it.shortLabel.toString() == "README.txt" })

        // Unpinning all pins clears dynamic shortcuts cleanly.
        harness.s.pins.toList().forEach { harness.vm.unpin(it) }
        harness.until(what = "dynamic shortcuts cleared") {
            ShortcutManagerCompat.getDynamicShortcuts(harness.app).isEmpty()
        }
    }

    @Test
    fun clickingShortcutWhileRunningOpensFolderInNewActiveTabWithoutDisturbingExistingTabs() {
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        val dirs = createStandardDirs(root)
        val docsDir = dirs[1]
        val picturesDir = dirs[2]
        val projectsDir = File(root, "Projects").apply { mkdirs() }

        val harness = VmHarness().also { h = it }
        harness.navigate(docsDir.path)
        harness.vm.pin(projectsDir.path)
        repeat(6) { harness.vm.movePin(projectsDir.path, -1) }
        harness.vm.toggleDualPane()
        harness.vm.switchPane()
        assertTrue("Expected right pane focused before shortcut tap", harness.s.rightActive)

        harness.until(what = "Projects shortcut published") {
            ShortcutManagerCompat.getDynamicShortcuts(harness.app).any { it.shortLabel.toString() == "Projects" }
        }
        val projectsShortcut = ShortcutManagerCompat.getDynamicShortcuts(harness.app)
            .first { it.shortLabel.toString() == "Projects" }

        val tabsBefore = harness.s.tabs.map { it.path }
        assertTrue(MainActivity.routeNewIntent(harness.app, projectsShortcut.intent, harness.vm))
        harness.settle()

        assertEquals("Existing tabs must be preserved with one new tab added", tabsBefore.size + 1, harness.s.tabs.size)
        assertEquals(projectsDir.path, harness.s.active.path)
        assertFalse("Opening a shortcut tab should focus the main tab pane", harness.s.rightActive)
        assertTrue("Previous tab must still be open", harness.s.tabs.any { it.path == docsDir.path })

        // Clicking a second shortcut opens another new tab rather than replacing the previous one.
        val picturesShortcut = ShortcutManagerCompat.getDynamicShortcuts(harness.app)
            .first { it.shortLabel.toString() == "Pictures" }
        assertTrue(MainActivity.routeNewIntent(harness.app, picturesShortcut.intent, harness.vm))
        harness.settle()

        assertEquals(tabsBefore.size + 2, harness.s.tabs.size)
        assertEquals(picturesDir.path, harness.s.active.path)
        assertTrue(harness.s.tabs.any { it.path == projectsDir.path })
    }

    @Test
    fun coldStartFromShortcutOpensOnlyRequestedFolder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        val dirs = createStandardDirs(root)
        val downloadsDir = dirs[0]
        val docsDir = dirs[1]

        val settings = Settings(app)
        settings.tabs = listOf(downloadsDir.path)
        settings.activeTab = 0
        settings.pins = dirs.map { it.path }

        val shortcutIntent = Intent(app, MainActivity::class.java).apply {
            action = AppShortcuts.ACTION_OPEN_SHORTCUT
            putExtra(AppShortcuts.EXTRA_SHORTCUT_ID, AppShortcuts.shortcutId(docsDir.path))
            putExtra(AppShortcuts.EXTRA_SHORTCUT_PATH, docsDir.path)
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java, shortcutIntent).create()
        val activity = controller.get()
        val prepared = activity.intent
        assertEquals(docsDir.path, prepared.getStringExtra(BrowserViewModel.EXTRA_PATH))
        assertFalse(
            "Cold start from shortcut must stay in main window so session tabs persist",
            prepared.getBooleanExtra(BrowserViewModel.EXTRA_NEW_WINDOW, false),
        )

        val vm = BrowserViewModel(
            app,
            SavedStateHandle(
                mapOf(
                    BrowserViewModel.EXTRA_PATH to prepared.getStringExtra(BrowserViewModel.EXTRA_PATH),
                ),
            ),
        )
        assertEquals("Cold start from shortcut should open only the requested folder", listOf(docsDir.path), vm.state.value.tabs.map { it.path })
        assertEquals(docsDir.path, vm.state.value.active.path)
        assertEquals(listOf(docsDir.path), settings.tabs)
        assertEquals(0, settings.activeTab)
        controller.destroy()
    }

    @Test
    fun pinnedRemoteFolderPublishesWithoutPathExtraAndResolvesSafelyById() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = OneDriveAccountStore(app)
        val account = OneDriveAccount(
            accountId = "acct_demo",
            email = "alex@example.com",
            displayName = "Alex",
            clientId = "client-1",
        )
        store.save(account, OneDriveTokens(accessToken = "access-1", refreshToken = "", expiresAtMillis = 0L))

        val remotePin = "${account.rootPath}/Specs"
        val harness = VmHarness().also { h = it }
        harness.vm.pin(remotePin)
        repeat(6) { harness.vm.movePin(remotePin, -1) }

        harness.until(what = "OneDrive pin published as shortcut") {
            ShortcutManagerCompat.getDynamicShortcuts(harness.app).any { it.shortLabel.toString() == "Specs" }
        }

        val remoteShortcut = ShortcutManagerCompat.getDynamicShortcuts(harness.app)
            .first { it.shortLabel.toString() == "Specs" }
        assertNull(
            "Remote shortcut intent must omit EXTRA_SHORTCUT_PATH to protect account/host info",
            remoteShortcut.intent.getStringExtra(AppShortcuts.EXTRA_SHORTCUT_PATH),
        )
        assertNotNull(remoteShortcut.intent.getStringExtra(AppShortcuts.EXTRA_SHORTCUT_ID))
        assertEquals(remotePin, AppShortcutSync.resolveIncomingShortcut(harness.app, remoteShortcut.intent))

        // Unpinning the remote folder immediately invalidates that shortcut ID.
        harness.vm.unpin(remotePin)
        assertNull(AppShortcutSync.resolveIncomingShortcut(harness.app, remoteShortcut.intent))
    }

    @Test
    fun spoofedShortcutIntentsAreRejected() {
        val harness = VmHarness().also { h = it }
        val tabsBefore = harness.s.tabs.map { it.path }

        val privateTarget = Intent(harness.app, MainActivity::class.java).apply {
            action = AppShortcuts.ACTION_OPEN_SHORTCUT
            putExtra(AppShortcuts.EXTRA_SHORTCUT_PATH, harness.app.applicationInfo.dataDir)
        }
        assertFalse(MainActivity.routeNewIntent(harness.app, privateTarget, harness.vm))

        val unpinnedRemote = Intent(harness.app, MainActivity::class.java).apply {
            action = AppShortcuts.ACTION_OPEN_SHORTCUT
            putExtra(AppShortcuts.EXTRA_SHORTCUT_PATH, "smb://evil.local/share")
        }
        assertFalse(MainActivity.routeNewIntent(harness.app, unpinnedRemote, harness.vm))

        val spoofedInternalFlag = Intent(Intent.ACTION_MAIN).apply {
            putExtra(BrowserViewModel.EXTRA_PATH, harness.app.applicationInfo.dataDir)
        }
        val sanitized = IntentGuard.sanitize(harness.app, spoofedInternalFlag)
        assertFalse(sanitized.hasExtra(BrowserViewModel.EXTRA_PATH))
        assertEquals(tabsBefore, harness.s.tabs.map { it.path })
    }
}
