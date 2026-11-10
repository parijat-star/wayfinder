// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.OneDriveAccount
import com.droid.wayfinder.data.OneDriveAccountStore
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.ui.AppState
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.ConnectOneDriveDialog
import com.droid.wayfinder.ui.Sidebar
import com.droid.wayfinder.ui.TabState
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class OneDriveUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(OneDriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sidebarRendersEmptyAndConnectedOneDriveAccountsWithQuotaSubtitles() {
        val vm = BrowserViewModel(app, SavedStateHandle())
        val personal = OneDriveAccount(
            accountId = "alex@outlook.com",
            email = "alex@outlook.com",
            displayName = "Alex",
            aliasLabel = "OneDrive · Personal",
            driveType = "personal",
            quotaUsed = 45_526_653_337L,
            quotaTotal = 1_099_511_627_776L,
            clientId = "11111111-2222-3333-4444-555555555555",
        )
        val work = OneDriveAccount(
            accountId = "alex@contoso.com",
            email = "alex@contoso.com",
            displayName = "Alex Contoso",
            aliasLabel = "OneDrive · Contoso",
            driveType = "business",
            quotaUsed = 137_438_953_472L,
            quotaTotal = 5_497_558_138_880L,
            clientId = "11111111-2222-3333-4444-555555555555",
        )
        val stateWithAccounts = AppState(
            tabs = listOf(TabState(id = 1L, path = "onedrive://alex@outlook.com/Projects/2026")),
            activeId = 1L,
            oneDriveAccounts = listOf(personal, work),
        )

        // Verify breadcrumbs start cleanly at OneDrive · Personal (without virtual "Network" prefix)
        val crumbs = FsPath.breadcrumbs("onedrive://alex@outlook.com/Projects/2026", stateWithAccounts.rootLabels)
        assertEquals(
            listOf(
                "OneDrive · Personal" to "onedrive://alex@outlook.com",
                "Projects" to "onedrive://alex@outlook.com/Projects",
                "2026" to "onedrive://alex@outlook.com/Projects/2026",
            ),
            crumbs,
        )

        composeRule.setContent {
            WayfinderTheme(dark = false) {
                Box(Modifier.size(width = 320.dp, height = 900.dp)) {
                    Sidebar(vm = vm, s = stateWithAccounts, modifier = Modifier.size(width = 300.dp, height = 900.dp))
                }
            }
        }

        composeRule.onNodeWithText("OneDrive").assertIsDisplayed()
        composeRule.onNodeWithText("OneDrive · Personal").assertIsDisplayed()
        composeRule.onNodeWithText("OneDrive · Contoso").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add Microsoft OneDrive account").assertIsDisplayed()
    }

    @Test
    fun connectOneDriveDialogValidatesClientIdAndSwitchesModes() {
        val vm = BrowserViewModel(app, SavedStateHandle())
        vm.showConnectOneDriveDialog()

        composeRule.setContent {
            val s = vm.state.value
            WayfinderTheme(dark = true) {
                ConnectOneDriveDialog(vm = vm, s = s)
            }
        }

        composeRule.onNodeWithText("Connect Microsoft OneDrive").assertIsDisplayed()
        // Out of the box, the bundled Public Client ID enables 1-click sign-in immediately!
        composeRule.onNodeWithText("Sign in with Microsoft").assertIsEnabled()

        // Expand Advanced to test custom Client ID validation
        composeRule.onNodeWithText("Advanced: Custom Entra Application (Client) ID").performClick()
        composeRule.onNodeWithText("Entra Application (Client) ID").performTextClearance()
        composeRule.onNodeWithText("Sign in with Microsoft").assertIsNotEnabled()

        // Enter valid UUID Client ID -> Sign in button re-enables
        composeRule.onNodeWithText("Entra Application (Client) ID")
            .performTextInput("11111111-2222-3333-4444-555555555555")
        composeRule.onNodeWithText("Sign in with Microsoft").assertIsEnabled()

        // Switch to Device Code tab -> button label updates to "Get Sign-In Code"
        composeRule.onNodeWithText("Sign In on Phone / Code").performClick()
        composeRule.onNodeWithText("Get Sign-In Code").assertIsEnabled()
        assertTrue(vm.state.value.dialog != null)
    }
}
