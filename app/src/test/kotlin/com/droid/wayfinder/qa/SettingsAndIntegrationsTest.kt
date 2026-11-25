// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.DropboxAccount
import com.droid.wayfinder.data.DropboxAccountStore
import com.droid.wayfinder.data.DropboxTokens
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.OneDriveAccount
import com.droid.wayfinder.data.OneDriveAccountStore
import com.droid.wayfinder.data.OneDriveTokens
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.SettingsSection
import com.droid.wayfinder.data.SidebarModule
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.TokenCipher
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.SettingsDialog
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.Sidebar
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.paletteFolders
import com.droid.wayfinder.ui.sidebarSectionMenu
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h860dp-land-xhdpi")
class SettingsAndIntegrationsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    private object TestCipher : TokenCipher {
        override fun encrypt(plain: String): String = "enc:$plain"
        override fun decrypt(encoded: String): String? = encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
    }

    @Before
    fun setUp() {
        MainActivity.hasAccessOverride = { true }
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(OneDriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(DropboxAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        SharedUi.init(app)
    }

    @After
    fun tearDown() {
        MainActivity.hasAccessOverride = null
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        SharedUi.init(app)
        h?.close()
    }

    @Test
    fun hidingIntegrationsPreservesSavedAccountsFiltersPalettePausesDiscoveryAndSupportsUndoRedo() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val odStore = OneDriveAccountStore(app, cipher = TestCipher)
        odStore.save(
            OneDriveAccount(accountId = "alex@outlook.com", email = "alex@outlook.com", displayName = "Alex"),
            OneDriveTokens("AT_OD", "RT_OD", System.currentTimeMillis() + 3_600_000L),
        )
        val dbxStore = DropboxAccountStore(app, cipher = TestCipher)
        dbxStore.save(
            DropboxAccount(accountId = "alex@example.com", email = "alex@example.com", displayName = "Alex"),
            DropboxTokens("AT_DBX", "RT_DBX", System.currentTimeMillis() + 3_600_000L),
        )

        val harness = VmHarness().also { h = it }
        // Re-load accounts saved with TestCipher in state for non-Keystore Robolectric verification
        val vm = harness.vm
        assertTrue("Network discovery starts enabled by default", vm.isNetworkDiscoveryRunning)

        // Hide Dropbox via Sidebar section context menu -> triggers Undoable snackbar & records Undo/Redo
        val sectionItems = sidebarSectionMenu(vm, SidebarModule.Dropbox).filterIsInstance<com.droid.wayfinder.ui.MenuEntry.Item>()
        assertEquals(listOf("Hide Dropbox in sidebar", "Customize sidebar…"), sectionItems.map { it.label })
        sectionItems.first().action()
        harness.settle()

        assertFalse(harness.s.sidebarVisibility.dropbox)
        assertFalse(Settings(app).sidebarVisibility.dropbox)
        assertTrue(harness.toasts().contains("Hidden Dropbox in sidebar"))
        assertNotNull("Saved Dropbox account must remain intact when hidden", dbxStore.getAccount("alex@example.com"))

        // Command Palette hides Connect Dropbox… when Dropbox is hidden
        assertNull(paletteCommands(vm, harness.s).firstOrNull { it.name == "Connect Dropbox…" })
        assertNull(paletteFolders(vm, harness.s).firstOrNull { it.description.contains("dropbox://") })
        assertNotNull(paletteCommands(vm, harness.s).firstOrNull { it.name == "Settings…" })
        assertNotNull(paletteCommands(vm, harness.s).firstOrNull { it.name == "Customize sidebar…" })

        // Undo restores Dropbox visibility; Redo hides it again
        vm.undo()
        harness.settle()
        assertTrue(harness.s.sidebarVisibility.dropbox)
        assertNotNull(paletteCommands(vm, harness.s).firstOrNull { it.name == "Connect Dropbox…" })

        vm.redo()
        harness.settle()
        assertFalse(harness.s.sidebarVisibility.dropbox)

        // Hiding Network & cloud stops passive background LAN discovery; explicit smb:// navigation restarts it
        vm.setSidebarModuleVisible(SidebarModule.NetworkAndCloud, false)
        assertFalse("Passive LAN discovery must stop when Network & cloud is hidden", vm.isNetworkDiscoveryRunning)
        assertNull(paletteCommands(vm, harness.s).firstOrNull { it.name == "Connect to server…" })

        vm.navigate(FsPath.SMB_ROOT)
        assertTrue("Explicit Ctrl+L navigation to smb:// restarts discovery on demand", vm.isNetworkDiscoveryRunning)

        // Persisted visibility survives across a brand-new ViewModel instance
        vm.setSidebarModuleVisible(SidebarModule.OneDrive, false)
        vm.setSidebarModuleVisible(SidebarModule.ColorTags, false)
        val freshVm = BrowserViewModel(app, SavedStateHandle())
        try {
            assertFalse(freshVm.state.value.sidebarVisibility.dropbox)
            assertFalse(freshVm.state.value.sidebarVisibility.networkAndCloud)
            assertFalse(freshVm.state.value.sidebarVisibility.oneDrive)
            assertFalse(freshVm.state.value.sidebarVisibility.colorTags)
            assertFalse("Fresh ViewModel must not start passive LAN discovery while Network & cloud is hidden", freshVm.isNetworkDiscoveryRunning)
        } finally {
            freshVm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(freshVm)
        }
    }

    @Test
    fun childDialogsOpenedFromSettingsReturnToActiveSettingsSectionOnDismiss() {
        val harness = VmHarness().also { h = it }
        val vm = harness.vm

        vm.showSettings(SettingsSection.Integrations)
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)

        // Launch ConnectDropbox from Settings -> dismissing returns to Settings(Integrations)
        vm.showConnectDropboxDialog()
        assertTrue(harness.s.dialog is Dialog.ConnectDropbox)
        vm.dismissDropboxDialog()
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)

        // Launch ConnectOneDrive from Settings -> dismissing returns to Settings(Integrations)
        vm.showConnectOneDriveDialog()
        assertTrue(harness.s.dialog is Dialog.ConnectOneDrive)
        vm.dismissOneDriveDialog()
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)

        // Switch to SystemAndAbout and launch DefaultHandler -> dismissing returns to Settings(SystemAndAbout)
        vm.updateSettingsSection(SettingsSection.SystemAndAbout)
        vm.showDefaultHandlerDialog()
        assertEquals(Dialog.DefaultHandler, harness.s.dialog)
        vm.dismissDialog()
        assertEquals(Dialog.Settings(SettingsSection.SystemAndAbout), harness.s.dialog)

        // Launching SAF folder picker when showSafMountTip == false keeps Settings underneath if picker is cancelled
        vm.updateSettingsSection(SettingsSection.Integrations)
        vm.setShowSafMountTip(false)
        vm.requestMountSaf()
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)

        // Closing Settings itself clears dialog completely
        vm.closeSettings()
        assertNull(harness.s.dialog)
    }

    @Test
    fun sidebarHidesDisabledModulesAndRendersCustomizeSidebarAction() {
        val harness = VmHarness().also { h = it }
        val vm = harness.vm

        rule.setContent {
            val s by vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = 320.dp, height = 1400.dp)) {
                    Sidebar(vm, s, Modifier.size(width = 300.dp, height = 1400.dp))
                }
            }
        }
        rule.waitForIdle()

        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Google Drive"))
        rule.onNodeWithText("Google Drive").assertIsDisplayed()
        list.performScrollToNode(hasText("OneDrive"))
        rule.onNodeWithText("OneDrive").assertIsDisplayed()
        list.performScrollToNode(hasText("Dropbox"))
        rule.onNodeWithText("Dropbox").assertIsDisplayed()
        list.performScrollToNode(hasText("Color tags"))
        rule.onNodeWithText("Color tags").assertIsDisplayed()
        list.performScrollToNode(hasText("Customize sidebar…"))
        rule.onNodeWithText("Customize sidebar…").assertIsDisplayed()

        vm.setSidebarModuleVisible(SidebarModule.OneDrive, false)
        vm.setSidebarModuleVisible(SidebarModule.Dropbox, false)
        vm.setSidebarModuleVisible(SidebarModule.ColorTags, false)
        rule.waitForIdle()

        rule.onAllNodesWithText("OneDrive").assertCountEquals(0)
        rule.onAllNodesWithText("Dropbox").assertCountEquals(0)
        rule.onAllNodesWithText("Color tags").assertCountEquals(0)
        list.performScrollToNode(hasText("Google Drive"))
        rule.onNodeWithText("Google Drive").assertIsDisplayed()

        // Clicking "Customize sidebar…" opens Settings directly on Integrations
        list.performScrollToNode(hasText("Customize sidebar…"))
        rule.onNodeWithText("Customize sidebar…").performClick()
        rule.waitForIdle()
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)
    }

    @Test
    fun settingsTwoPaneDesktopControlsColumnsGridSizeAndTouchTargets() {
        val harness = VmHarness().also { h = it }
        val vm = harness.vm
        vm.showSettings(SettingsSection.Integrations)

        var density: Density? = null
        rule.setContent {
            density = LocalDensity.current
            WayfinderTheme(false) {
                AppRoot(vm)
            }
        }
        rule.waitForIdle()
        val activeDensity = density!!

        assertInteractiveControlsAtLeast48dp("Desktop Settings -> Integrations", activeDensity)

        // Switch to Files & View and reorder / toggle columns
        rule.onNodeWithText("Files & View").performClick()
        rule.waitForIdle()
        assertInteractiveControlsAtLeast48dp("Desktop Settings -> Files & View", activeDensity)

        assertEquals(SortKey.Modified, harness.s.columnOrder.first())
        rule.onNodeWithContentDescription("Move Type up").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(SortKey.Type, harness.s.columnOrder.first())

        rule.onNodeWithText("Reset columns").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(SortKey.Modified, harness.s.columnOrder.first())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-xhdpi")
    fun settingsCompactPhoneSheetMeets48dpTouchTargetsAcrossAllSections() {
        val harness = VmHarness().also { h = it }
        val vm = harness.vm
        vm.showSettings(SettingsSection.Integrations)

        var density: Density? = null
        rule.setContent {
            density = LocalDensity.current
            val s by vm.state.collectAsState()
            WayfinderTheme(true) {
                SettingsDialog(vm, s, s.dialog?.let { (it as? Dialog.Settings)?.initialSection } ?: SettingsSection.Integrations)
            }
        }
        rule.waitForIdle()

        for (section in SettingsSection.entries) {
            vm.updateSettingsSection(section)
            rule.waitForIdle()
            assertInteractiveControlsAtLeast48dp("Compact 360dp Settings -> $section", density!!)
            if (section == SettingsSection.Appearance) {
                val zoomOutY = rule.onNodeWithContentDescription("Zoom out").fetchSemanticsNode().boundsInRoot.center.y
                val resetY = rule.onNodeWithText("Reset (100%)").fetchSemanticsNode().boundsInRoot.center.y
                val zoomInY = rule.onNodeWithContentDescription("Zoom in").fetchSemanticsNode().boundsInRoot.center.y
                assertTrue(
                    "Zoom controls must stay on a single row at 360dp (zoomOutY=$zoomOutY, resetY=$resetY, zoomInY=$zoomInY)",
                    kotlin.math.abs(zoomOutY - resetY) < 2f && kotlin.math.abs(zoomInY - resetY) < 2f,
                )
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-xhdpi")
    fun compactDrawerHeaderGearAndOverflowMenuExposeSettingsWithoutScrolling() {
        val harness = VmHarness().also { h = it }
        val vm = harness.vm

        rule.setContent {
            WayfinderTheme(true) {
                AppRoot(vm)
            }
        }
        rule.waitForIdle()

        // 1. Overflow menu on compact phone exposes Settings… above the fold without scrolling
        rule.onNodeWithContentDescription("More").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Settings…").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)

        vm.closeSettings()
        rule.waitForIdle()
        assertNull(harness.s.dialog)

        // 2. Compact navigation drawer header exposes a 48dp Settings gear icon next to Close
        rule.onNodeWithContentDescription("Open navigation drawer").performClick()
        rule.waitForIdle()
        assertTrue(harness.s.compactSidebarOpen)
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertFalse(harness.s.compactSidebarOpen)
        assertEquals(Dialog.Settings(SettingsSection.Integrations), harness.s.dialog)
    }

    private fun assertInteractiveControlsAtLeast48dp(label: String, density: Density) {
        val minPx = with(density) { 47.5.dp.toPx() }
        val matcher = SemanticsMatcher("hasClick") { node ->
            SemanticsActions.OnClick in node.config || SemanticsActions.OnLongClick in node.config
        }
        val violations = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            val w = maxOf(node.size.width.toFloat(), node.touchBoundsInRoot.width)
            val h = maxOf(node.size.height.toFloat(), node.touchBoundsInRoot.height)
            if (w < minPx || h < minPx) {
                val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    ?: "node#${node.id}"
                "$label: '$desc' measured ${with(density) { w.toDp() }} x ${with(density) { h.toDp() }}"
            } else {
                null
            }
        }
        assertTrue("Controls below 48dp:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
