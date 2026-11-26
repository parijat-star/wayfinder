// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.DropboxAccount
import com.droid.wayfinder.data.DropboxAccountStore
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.ui.AppState
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.ConnectDropboxDialog
import com.droid.wayfinder.ui.Sidebar
import com.droid.wayfinder.ui.TabState
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class DropboxUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(DropboxAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sidebarRendersConnectedDropboxPersonalAndTeamAccountsWithBreadcrumbs() {
        val vm = BrowserViewModel(app, SavedStateHandle())
        val personal = DropboxAccount(
            accountId = "alex@example.com",
            email = "alex@example.com",
            displayName = "Alex",
            aliasLabel = "Dropbox · Personal",
            accountType = "pro",
            quotaUsed = 12_884_901_888L,
            quotaTotal = 2_199_023_255_552L,
            appKey = "wayfinderappkey1",
        )
        val team = DropboxAccount(
            accountId = "alex@studio.io",
            email = "alex@studio.io",
            displayName = "Alex Studio",
            accountType = "business",
            rootNamespaceId = "99887766",
            quotaUsed = 107_374_182_400L,
            quotaTotal = 5_497_558_138_880L,
            appKey = "wayfinderappkey1",
        )
        val stateWithAccounts = AppState(
            tabs = listOf(TabState(id = 1L, path = "dropbox://alex@example.com/Design/2026")),
            activeId = 1L,
            dropboxAccounts = listOf(personal, team),
        )

        val crumbs = FsPath.breadcrumbs("dropbox://alex@example.com/Design/2026", stateWithAccounts.rootLabels)
        assertEquals(
            listOf(
                "Dropbox · Personal" to "dropbox://alex@example.com",
                "Design" to "dropbox://alex@example.com/Design",
                "2026" to "dropbox://alex@example.com/Design/2026",
            ),
            crumbs,
        )

        composeRule.setContent {
            WayfinderTheme(dark = false) {
                Box(Modifier.size(width = 320.dp, height = 960.dp)) {
                    Sidebar(vm = vm, s = stateWithAccounts, modifier = Modifier.size(width = 300.dp, height = 960.dp))
                }
            }
        }

        composeRule.onNodeWithText("Dropbox").assertIsDisplayed()
        composeRule.onNodeWithText("Beta", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dropbox · Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Dropbox · Team").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add Dropbox account").assertIsDisplayed()
    }

    @Test
    fun connectDropboxDialogValidatesAppKeyAndSupportsPasteSignInCodeFlow() {
        val vm = BrowserViewModel(app, SavedStateHandle())
        vm.showConnectDropboxDialog()

        composeRule.setContent {
            val s by vm.state.collectAsState()
            WayfinderTheme(dark = true) {
                ConnectDropboxDialog(vm = vm, s = s)
            }
        }

        composeRule.onNodeWithText("Connect Dropbox").assertIsDisplayed()
        composeRule.onNodeWithText("Beta", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Beta: Shared sign-in currently supports up to 50 tester accounts.",
            substring = true,
        ).assertIsDisplayed()
        // Out of the box, the bundled Public App Key enables 1-click sign-in immediately
        assertEquals("prf937kdbc0vpc5", vm.state.value.dropboxDefaultAppKey)
        composeRule.onNodeWithText("Sign in with Dropbox").assertIsEnabled()

        // Expand Advanced to test custom App Key validation
        composeRule.onNodeWithText("Advanced: Custom Dropbox App Key").performClick()
        composeRule.onNodeWithText("Dropbox App Key").performTextClearance()
        composeRule.onNodeWithText("Sign in with Dropbox").assertIsNotEnabled()

        // Enter valid App Key -> Sign in button re-enables
        composeRule.onNodeWithText("Dropbox App Key").performTextInput("wayfinderappkey1")
        composeRule.onNodeWithText("Sign in with Dropbox").assertIsEnabled()

        // Switch to Paste Sign-In Code mode -> starts PKCE session and unlocks Authorization Code input
        composeRule.onNodeWithText("Paste Sign-In Code").performClick()
        composeRule.onNodeWithText("Get Sign-In Code").assertIsEnabled()
        composeRule.onNodeWithText("Get Sign-In Code").performClick()

        assertNotNull(vm.state.value.dropboxCodeSession)
        composeRule.onNodeWithText("Complete Sign-In").assertIsNotEnabled()

        composeRule.onNodeWithText("Authorization Code").performTextInput("VALID_DBX_AUTH_CODE_123")
        composeRule.onNodeWithText("Complete Sign-In").assertIsEnabled()
    }
}
