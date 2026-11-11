// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.DEFAULT_COLUMN_WIDTHS
import com.droid.wayfinder.data.DEFAULT_METADATA_COLUMNS
import com.droid.wayfinder.data.MAX_COLUMN_WIDTH_DP
import com.droid.wayfinder.data.MIN_COLUMN_WIDTH_DP
import com.droid.wayfinder.data.MIN_NAME_COLUMN_WIDTH_DP
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.ContentCard
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.Toolbar
import com.droid.wayfinder.ui.columnsMenu
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.theme.WayfinderTheme
import com.droid.wayfinder.ui.visibleColumnsForWidth
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class DetailsColumnsCustomizationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        h?.close()
    }

    @Test
    fun settingsBackwardCompatibilityAndColumnPersistence() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val rawPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        rawPrefs.edit().clear()
            .putBoolean("tagsColumn", true)
            .putBoolean("permsColumn", true)
            .commit()

        val settings = Settings(ctx)
        assertTrue(settings.tagsColumn)
        assertTrue(settings.permsColumn)
        assertTrue(settings.modifiedColumn)
        assertTrue(settings.typeColumn)
        assertTrue(settings.sizeColumn)
        assertEquals(DEFAULT_METADATA_COLUMNS, settings.columnOrder)
        assertEquals(DEFAULT_COLUMN_WIDTHS, settings.columnWidths)

        settings.modifiedColumn = false
        settings.typeColumn = false
        settings.sizeColumn = false
        settings.setColumnWidth(SortKey.Modified, 220f)
        settings.setColumnWidth(SortKey.Size, 10f) // clamps to 64f
        settings.setColumnWidth(SortKey.Type, 900f) // clamps to 360f
        val customOrder = listOf(SortKey.Size, SortKey.Modified, SortKey.Type, SortKey.Permissions, SortKey.Tags)
        settings.columnOrder = customOrder

        val reloaded = Settings(ctx)
        assertFalse(reloaded.modifiedColumn)
        assertFalse(reloaded.typeColumn)
        assertFalse(reloaded.sizeColumn)
        assertEquals(220f, reloaded.columnWidth(SortKey.Modified), 0.01f)
        assertEquals(MIN_COLUMN_WIDTH_DP, reloaded.columnWidth(SortKey.Size), 0.01f)
        assertEquals(MAX_COLUMN_WIDTH_DP, reloaded.columnWidth(SortKey.Type), 0.01f)
        assertEquals(customOrder, reloaded.columnOrder)

        reloaded.resetColumns()
        assertTrue(reloaded.modifiedColumn)
        assertTrue(reloaded.typeColumn)
        assertTrue(reloaded.sizeColumn)
        assertFalse(reloaded.tagsColumn)
        assertFalse(reloaded.permsColumn)
        assertEquals(DEFAULT_METADATA_COLUMNS, reloaded.columnOrder)
        assertEquals(DEFAULT_COLUMN_WIDTHS, reloaded.columnWidths)
    }

    @Test
    fun hidingColumnResetsSortAcrossOpenTabsSecondaryPaneAndSettings() {
        val harness = VmHarness().also { h = it }
        harness.file("a.txt", "a")
        harness.file("b.txt", "bbb")
        harness.navigate(harness.root.path)

        // Sort tab 1 by Size (descending)
        harness.vm.setSort(SortKey.Size, asc = false)
        assertEquals(SortKey.Size, harness.s.active.sortKey)
        assertFalse(harness.s.active.sortAsc)

        // Open tab 2 sorted by Type (descending)
        harness.vm.newTab(harness.root.path)
        harness.settle()
        harness.vm.setSort(SortKey.Type, asc = false)
        assertEquals(SortKey.Type, harness.s.active.sortKey)

        // Enable dual pane and sort secondary pane by Size (descending)
        harness.vm.toggleDualPane()
        harness.settle()
        harness.vm.activatePane(right = true)
        harness.vm.setSort(SortKey.Size, asc = false)
        assertEquals(SortKey.Size, harness.s.secondary!!.sortKey)
        assertEquals(SortKey.Size, Settings(harness.app).sortKey)

        // Hide Size column -> tab 1, secondary pane, and settings.sortKey reset to Name (asc = true),
        // while tab 2 (sorted by Type) stays sorted by Type.
        harness.vm.toggleSizeColumn()
        assertFalse(harness.s.sizeColumn)
        assertEquals(SortKey.Name, harness.s.tabs[0].sortKey)
        assertTrue(harness.s.tabs[0].sortAsc)
        assertEquals(SortKey.Type, harness.s.tabs[1].sortKey)
        assertFalse(harness.s.tabs[1].sortAsc)
        assertEquals(SortKey.Name, harness.s.secondary!!.sortKey)
        assertTrue(harness.s.secondary!!.sortAsc)
        assertEquals(SortKey.Name, Settings(harness.app).sortKey)
        assertTrue(Settings(harness.app).sortAsc)

        // Show Tags, sort by Tags, then Reset columns -> hides Tags and resets sort to Name (asc = true)
        harness.vm.toggleTagsColumn()
        harness.vm.setSort(SortKey.Tags, asc = false)
        assertEquals(SortKey.Tags, harness.s.active.sortKey)
        harness.vm.resetColumns()
        assertFalse(harness.s.tagsColumn)
        assertEquals(SortKey.Name, harness.s.active.sortKey)
        assertTrue(harness.s.active.sortAsc)
    }

    @Test
    fun columnsMenuAndCommandPaletteExposeTogglesMoveAndResetCommands() {
        val harness = VmHarness().also { h = it }
        harness.navigate(harness.root.path)

        // Verify columnsMenu has all 5 metadata column toggles + Divider + Reset columns
        val menuItems = columnsMenu(harness.vm, harness.s).filterIsInstance<MenuEntry.Item>()
        assertEquals(
            listOf(
                "Date modified column",
                "Type column",
                "Size column",
                "Tags column",
                "Permissions column",
                "Reset columns",
            ),
            menuItems.map { it.label },
        )

        // Verify palette sort commands only include Name and currently visible metadata columns
        val initialCmds = paletteCommands(harness.vm, harness.s)
        val initialSortNames = initialCmds.filter { it.name.startsWith("Sort by ") }.map { it.name }
        assertEquals(
            listOf("Sort by name", "Sort by date modified", "Sort by type", "Sort by size"),
            initialSortNames,
        )

        // Verify Move left/right commands for visible columns [Modified, Type, Size]
        assertNull(initialCmds.find { it.name == "Move Date modified left" })
        assertNotNull(initialCmds.find { it.name == "Move Date modified right" })
        assertNotNull(initialCmds.find { it.name == "Move Type left" })
        assertNotNull(initialCmds.find { it.name == "Move Type right" })
        assertNotNull(initialCmds.find { it.name == "Move Size left" })
        assertNull(initialCmds.find { it.name == "Move Size right" })

        // Invoke "Move Size left" via palette command
        initialCmds.first { it.name == "Move Size left" }.action()
        assertEquals(
            listOf(SortKey.Modified, SortKey.Size, SortKey.Type),
            harness.s.visibleMetadataColumns,
        )

        // Hide Date modified and show Permissions via palette commands
        paletteCommands(harness.vm, harness.s).first { it.name == "Hide Date modified column" }.action()
        paletteCommands(harness.vm, harness.s).first { it.name == "Show Permissions column" }.action()
        assertFalse(harness.s.modifiedColumn)
        assertTrue(harness.s.permsColumn)

        val updatedSortNames = paletteCommands(harness.vm, harness.s)
            .filter { it.name.startsWith("Sort by ") }
            .map { it.name }
        assertEquals(
            listOf("Sort by name", "Sort by type", "Sort by size", "Sort by permissions"),
            updatedSortNames,
        )

        // Reset columns via palette command
        paletteCommands(harness.vm, harness.s).first { it.name == "Reset columns" }.action()
        assertEquals(listOf(SortKey.Modified, SortKey.Type, SortKey.Size), harness.s.visibleMetadataColumns)
        assertEquals(DEFAULT_METADATA_COLUMNS, harness.s.columnOrder)
        assertEquals(DEFAULT_COLUMN_WIDTHS, harness.s.columnWidths)
    }

    @Test
    fun narrowSplitScreenHidesTrailingColumnsToKeepNameAtLeast140Dp() {
        val harness = VmHarness().also { h = it }
        // Fixed overhead = 82dp, min Name = 140dp -> 222dp minimum before metadata columns.
        // Default visible columns: Modified (168dp), Type (132dp), Size (88dp).
        // Width = 800dp -> 578dp available -> all 3 fit (388dp).
        assertEquals(
            listOf(SortKey.Modified, SortKey.Type, SortKey.Size),
            visibleColumnsForWidth(harness.s, 800.dp, fontScale = 1f),
        )
        // Width = 540dp -> 318dp available -> Modified (168dp) + Type (132dp) = 300dp fit; Size (88dp) hidden.
        assertEquals(
            listOf(SortKey.Modified, SortKey.Type),
            visibleColumnsForWidth(harness.s, 540.dp, fontScale = 1f),
        )
        // Width = 420dp -> 198dp available -> only Modified (168dp) fits.
        assertEquals(
            listOf(SortKey.Modified),
            visibleColumnsForWidth(harness.s, 420.dp, fontScale = 1f),
        )
        // Width = 350dp -> 128dp available -> 0 metadata columns fit so Name stays >= 140dp.
        assertEquals(
            emptyList<SortKey>(),
            visibleColumnsForWidth(harness.s, 350.dp, fontScale = 1f),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun detailsHeaderResizeDoubleClickResetReorderAndEntryRowAlignment() {
        val harness = VmHarness().also { h = it }
        val file = harness.file("report.txt", "hello world")
        harness.navigate(harness.root.path)
        harness.select("report.txt")
        harness.vm.toggleTag(ColorTag.Urgent)
        harness.settle()

        val testFontScale = mutableFloatStateOf(1f)
        rule.setContent {
            val s by harness.vm.state.collectAsState()
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = testFontScale.floatValue)) {
                WayfinderTheme(false) {
                    Box(Modifier.size(width = 1000.dp, height = 500.dp)) {
                        ContentCard(harness.vm, s, Modifier.size(width = 1000.dp, height = 500.dp))
                    }
                }
            }
        }
        rule.waitForIdle()

        // Default columns: Name, Date modified, Type, Size
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Date modified").assertIsDisplayed()
        rule.onNodeWithText("Type").assertIsDisplayed()
        rule.onNodeWithText("Size").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Tags").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Permissions").fetchSemanticsNodes().size)

        // Verify MarkBadges shows tag inline on the row when Tags column is hidden, and in Tags column when shown
        assertTrue(harness.s.marks.tagsOf(file.path).contains(ColorTag.Urgent))
        harness.vm.toggleTagsColumn()
        harness.vm.togglePermsColumn()
        rule.waitForIdle()
        rule.onNodeWithText("Tags").assertIsDisplayed()
        rule.onNodeWithText("Permissions").assertIsDisplayed()
        harness.vm.toggleTagsColumn()
        harness.vm.togglePermsColumn()
        rule.waitForIdle()

        // Resize "Date modified" column via its left-edge divider handle:
        // dragging left (-60dp) widens the column (+60dp -> 228dp); dragging right (+40dp) narrows it (-40dp -> 188dp).
        val densityPx = rule.density.density
        rule.onNodeWithContentDescription("Resize Date modified column").performTouchInput {
            down(center)
            moveBy(Offset(-60f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(228f, harness.s.columnWidth(SortKey.Modified), 2f)
        assertEquals(228f, Settings(harness.app).columnWidth(SortKey.Modified), 2f)

        rule.onNodeWithContentDescription("Resize Date modified column").performTouchInput {
            down(center)
            moveBy(Offset(40f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(188f, harness.s.columnWidth(SortKey.Modified), 2f)

        // Double-click "Resize Date modified column" divider handle to reset width to 168dp
        rule.onNodeWithContentDescription("Resize Date modified column").performTouchInput {
            doubleClick()
        }
        rule.waitForIdle()
        assertEquals(168f, harness.s.columnWidth(SortKey.Modified), 0.5f)
        assertEquals(168f, Settings(harness.app).columnWidth(SortKey.Modified), 0.5f)
        assertEquals(132f, harness.s.columnWidth(SortKey.Type), 0.5f)
        assertEquals(88f, harness.s.columnWidth(SortKey.Size), 0.5f)

        // Pairwise interior divider between "Date modified" (168dp) and "Type" (132dp):
        // dragging right (+30dp) widens Date modified (+30dp -> 198dp) and narrows Type (-30dp -> 102dp), leaving Size (88dp) unchanged.
        rule.onNodeWithContentDescription("Resize Type column").performTouchInput {
            down(center)
            moveBy(Offset(30f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(198f, harness.s.columnWidth(SortKey.Modified), 2f)
        assertEquals(102f, harness.s.columnWidth(SortKey.Type), 2f)
        assertEquals(88f, harness.s.columnWidth(SortKey.Size), 0.5f)

        // Double-tapping that interior divider resets BOTH adjacent columns (Modified -> 168dp, Type -> 132dp).
        rule.onNodeWithContentDescription("Resize Type column").performTouchInput {
            doubleClick()
        }
        rule.waitForIdle()
        assertEquals(168f, harness.s.columnWidth(SortKey.Modified), 0.5f)
        assertEquals(132f, harness.s.columnWidth(SortKey.Type), 0.5f)
        assertEquals(168f, Settings(harness.app).columnWidth(SortKey.Modified), 0.5f)
        assertEquals(132f, Settings(harness.app).columnWidth(SortKey.Type), 0.5f)

        // Verify fontScale scaling & pairwise coupling on Size's left divider (1.25f scale -> -50dp screen = +40dp Size, -40dp Type)
        testFontScale.floatValue = 1.25f
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Resize Size column").performTouchInput {
            down(center)
            moveBy(Offset(-50f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(128f, harness.s.columnWidth(SortKey.Size), 2f)
        assertEquals(92f, harness.s.columnWidth(SortKey.Type), 2f)
        assertEquals(168f, harness.s.columnWidth(SortKey.Modified), 0.5f)
        testFontScale.floatValue = 1f
        harness.vm.resetColumnWidth(SortKey.Type)
        harness.vm.resetColumnWidth(SortKey.Size)
        rule.waitForIdle()

        // Drag "Date modified" header right to reorder it after "Type" -> [Type, Modified, Size]
        rule.onNodeWithText("Date modified").performTouchInput {
            down(center)
            moveBy(Offset(80f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(
            listOf(SortKey.Type, SortKey.Modified, SortKey.Size),
            harness.s.visibleMetadataColumns,
        )
        assertEquals(
            listOf(SortKey.Type, SortKey.Modified, SortKey.Size, SortKey.Tags, SortKey.Permissions),
            Settings(harness.app).columnOrder,
        )

        // Verify header X positions, EntryRow cell X alignment, and resize divider placement just left of each header
        val nameHeaderLeft = rule.onNodeWithText("Name").fetchSemanticsNode().boundsInRoot.left
        val typeHeaderLeft = rule.onNodeWithText("Type").fetchSemanticsNode().boundsInRoot.left
        val modHeaderLeft = rule.onNodeWithText("Date modified").fetchSemanticsNode().boundsInRoot.left
        val sizeHeaderLeft = rule.onNodeWithText("Size").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Type header should be left of Date modified header", typeHeaderLeft < modHeaderLeft)
        assertTrue("Date modified header should be left of Size header", modHeaderLeft < sizeHeaderLeft)

        val nameCellLeft = rule.onNodeWithText("report.txt").fetchSemanticsNode().boundsInRoot.left
        val typeCellLeft = rule.onNodeWithText("Text file").fetchSemanticsNode().boundsInRoot.left
        val sizeCellLeft = rule.onNodeWithText("11 B").fetchSemanticsNode().boundsInRoot.left
        assertEquals("Name header and cell left edges should align", nameHeaderLeft, nameCellLeft, 1f)
        assertEquals("Type header and cell left edges should align", typeHeaderLeft, typeCellLeft, 1f)
        assertEquals("Size header and cell left edges should align", sizeHeaderLeft, sizeCellLeft, 1f)

        val typeHandleBounds = rule.onNodeWithContentDescription("Resize Type column").fetchSemanticsNode().boundsInRoot
        val modHandleBounds = rule.onNodeWithContentDescription("Resize Date modified column").fetchSemanticsNode().boundsInRoot
        val sizeHandleBounds = rule.onNodeWithContentDescription("Resize Size column").fetchSemanticsNode().boundsInRoot
        assertEquals("Type resize handle sits immediately left of Type header", typeHeaderLeft, typeHandleBounds.right, 1f)
        assertEquals("Date modified resize handle sits immediately left of Date modified header", modHeaderLeft, modHandleBounds.right, 1f)
        assertEquals("Size resize handle sits immediately left of Size header", sizeHeaderLeft, sizeHandleBounds.right, 1f)
        assertTrue("Name column stays at least 140dp wide", typeCellLeft >= MIN_NAME_COLUMN_WIDTH_DP * densityPx)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun toolbarGatesColumnTogglesToDetailsViewAndFiltersSortMenu() {
        val harness = VmHarness().also { h = it }
        harness.file("item.txt", "hello")
        harness.navigate(harness.root.path)

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = 960.dp, height = 360.dp)) {
                    Toolbar(harness.vm, s)
                }
            }
        }
        rule.waitForIdle()

        // In Details view, OverflowMenu offers all 5 metadata column toggles
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Date modified column").assertIsDisplayed()
        rule.onNodeWithText("Type column").assertIsDisplayed()
        rule.onNodeWithText("Size column").assertIsDisplayed()
        rule.onNodeWithText("Tags column").assertIsDisplayed()
        rule.onNodeWithText("Permissions column").assertIsDisplayed()
        // Hide Type column from OverflowMenu
        rule.onNodeWithText("Type column").performClick()
        rule.waitForIdle()
        assertFalse(harness.s.typeColumn)

        // SortButton dropdown should NOT show "Type" (hidden) or "Tags" (hidden), but SHOULD show "Name", "Date modified", "Size"
        rule.onNodeWithText("Sort").performClick()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Date modified").assertIsDisplayed()
        rule.onNodeWithText("Size").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Type").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Tags").fetchSemanticsNodes().size)
        // Close Sort menu by selecting Name
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()

        // Switch to Grid view -> OverflowMenu hides Details-only column toggles,
        // while SortButton and CommandPalette restore ALL SortKey options (including hidden Type, Tags, Permissions).
        harness.vm.setViewMode(ViewMode.Grid)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("More").performClick()
        assertEquals(0, rule.onAllNodesWithText("Date modified column").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Tags column").fetchSemanticsNodes().size)
        // Dismiss OverflowMenu by toggling Show hidden files off/on or clicking Sort after closing
        rule.onNodeWithText("Show hidden files").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Sort").performClick()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Date modified").assertIsDisplayed()
        rule.onNodeWithText("Type").assertIsDisplayed()
        rule.onNodeWithText("Size").assertIsDisplayed()
        rule.onNodeWithText("Tags").assertIsDisplayed()
        rule.onNodeWithText("Permissions").assertIsDisplayed()
        // Sort by Tags while in Grid view (even though Tags column is hidden in Details view)
        rule.onNodeWithText("Tags").performClick()
        rule.waitForIdle()
        assertEquals(SortKey.Tags, harness.s.active.sortKey)

        // All non-Details modes (Grid, List, Columns) expose all 6 sort keys in CommandPalette
        for (mode in listOf(ViewMode.Grid, ViewMode.List, ViewMode.Columns)) {
            harness.vm.setViewMode(mode)
            val sortNames = paletteCommands(harness.vm, harness.s).filter { it.name.startsWith("Sort by ") }.map { it.name }
            assertEquals(
                listOf("Sort by name", "Sort by date modified", "Sort by type", "Sort by size", "Sort by tags", "Sort by permissions"),
                sortNames,
            )
        }

        // Switching back to Details view while sorted by hidden Tags resets sort to Name (ascending)
        harness.vm.setSort(SortKey.Tags, asc = false)
        harness.vm.setViewMode(ViewMode.Details)
        rule.waitForIdle()
        assertEquals(SortKey.Name, harness.s.active.sortKey)
        assertTrue(harness.s.active.sortAsc)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun abortedAndEdgeHeaderDragsDoNotTriggerSortAndNarrowPaneScopesReorderToDisplayedColumns() {
        val harness = VmHarness().also { h = it }
        harness.file("alpha.txt", "hello")
        harness.navigate(harness.root.path)

        val paneWidth = mutableStateOf(1000.dp)
        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = paneWidth.value, height = 500.dp)) {
                    ContentCard(harness.vm, s, Modifier.size(width = paneWidth.value, height = 500.dp))
                }
            }
        }
        rule.waitForIdle()
        val densityPx = rule.density.density

        assertEquals(SortKey.Name, harness.s.active.sortKey)
        assertTrue(harness.s.active.sortAsc)

        // 1a. Aborted short drag (~29dp: > touchSlop, < 40dp reorder threshold) must neither reorder nor sort
        rule.onNodeWithText("Type").performTouchInput {
            down(center)
            moveBy(Offset(29f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(SortKey.Modified, SortKey.Type, SortKey.Size), harness.s.visibleMetadataColumns)
        assertEquals(SortKey.Name, harness.s.active.sortKey)

        // 1b. Outward edge drag on rightmost column ("Size" dragged right +60dp) must neither reorder nor sort
        rule.onNodeWithText("Size").performTouchInput {
            down(center)
            moveBy(Offset(60f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(SortKey.Modified, SortKey.Type, SortKey.Size), harness.s.visibleMetadataColumns)
        assertEquals(SortKey.Name, harness.s.active.sortKey)

        // 1c. Outward edge drag on leftmost metadata column ("Date modified" dragged left -60dp) must not sort
        rule.onNodeWithText("Date modified").performTouchInput {
            down(center)
            moveBy(Offset(-60f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(SortKey.Name, harness.s.active.sortKey)

        // Normal click on "Size" still triggers sort as expected
        rule.onNodeWithText("Size").performClick()
        rule.waitForIdle()
        assertEquals(SortKey.Size, harness.s.active.sortKey)
        harness.vm.setSort(SortKey.Name, asc = true)
        rule.waitForIdle()

        // 2. Narrow / split-pane width (540dp): Modified (168dp) + Type (132dp) fit; Size (88dp) is hidden by width.
        paneWidth.value = 540.dp
        rule.waitForIdle()
        rule.onNodeWithText("Date modified").assertIsDisplayed()
        rule.onNodeWithText("Type").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Size").fetchSemanticsNodes().size)
        assertEquals(listOf(SortKey.Modified, SortKey.Type), harness.s.displayedMetadataColumns)

        // Dragging rightmost displayed header ("Type") right (+80dp) must NOT swap with width-hidden "Size"
        rule.onNodeWithText("Type").performTouchInput {
            down(center)
            moveBy(Offset(80f * densityPx, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(DEFAULT_METADATA_COLUMNS, harness.s.columnOrder)
        rule.onNodeWithText("Type").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Size").fetchSemanticsNodes().size)
        assertEquals(SortKey.Name, harness.s.active.sortKey)

        // CommandPalette and vm.moveColumn also gate moves to displayedMetadataColumns ([Modified, Type])
        val narrowCmds = paletteCommands(harness.vm, harness.s)
        assertNotNull(narrowCmds.find { it.name == "Move Date modified right" })
        assertNotNull(narrowCmds.find { it.name == "Move Type left" })
        assertNull(narrowCmds.find { it.name == "Move Type right" })
        assertNull(narrowCmds.find { it.name == "Move Size left" })
        harness.vm.moveColumn(SortKey.Type, 1)
        assertEquals(DEFAULT_METADATA_COLUMNS, harness.s.columnOrder)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun detailsHeaderRightClickMenuTogglesColumnsAndResets() {
        val harness = VmHarness().also { h = it }
        harness.file("alpha.txt", "hello")
        harness.navigate(harness.root.path)

        rule.setContent {
            WayfinderTheme(false) {
                Box(Modifier.size(width = 1080.dp, height = 640.dp)) {
                    AppRoot(harness.vm)
                }
            }
        }
        rule.waitForIdle()

        // Right-click on DetailsHeader to open columnsMenu
        rule.onNodeWithText("Date modified").performMouseInput {
            rightClick()
        }
        rule.waitForIdle()

        // Toggle Date modified column off via context menu
        rule.onNodeWithText("Date modified column").performClick()
        rule.waitForIdle()
        assertFalse(harness.s.modifiedColumn)
        assertEquals(0, rule.onAllNodesWithText("Date modified").fetchSemanticsNodes().size)

        // Right-click on Name header and click "Reset columns"
        rule.onNodeWithText("Name").performMouseInput {
            rightClick()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Reset columns").performClick()
        rule.waitForIdle()
        assertTrue(harness.s.modifiedColumn)
        rule.onNodeWithText("Date modified").assertIsDisplayed()
    }
}
