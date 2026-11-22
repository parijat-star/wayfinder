// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.view.KeyEvent
import com.droid.wayfinder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutsTest {
    private val allowedModifiers =
        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

    @Test
    fun sectionsCoverAllFiveGroupsAndAreNonEmpty() {
        assertEquals(
            listOf(
                R.string.shortcut_group_tabs_windows,
                R.string.shortcut_group_navigate,
                R.string.shortcut_group_select_edit,
                R.string.shortcut_group_view,
                R.string.shortcut_group_dual_pane,
            ),
            KeyboardShortcuts.sections.map { it.titleRes },
        )
        KeyboardShortcuts.sections.forEach { section ->
            assertTrue("Section ${section.titleRes} should have shortcuts", section.entries.isNotEmpty())
        }
    }

    @Test
    fun allEntriesHaveValidKeyCodesModifiersAndNoDuplicates() {
        val seenBindings = mutableSetOf<Pair<Int, Int>>()
        val seenLabels = mutableSetOf<Int>()

        for (section in KeyboardShortcuts.sections) {
            assertTrue("Section titleRes must be non-zero", section.titleRes != 0)
            for (entry in section.entries) {
                assertTrue("Label res must be non-zero", entry.labelRes != 0)
                assertTrue("Key code must be positive for label ${entry.labelRes}", entry.keyCode > 0)
                assertEquals(
                    "Unexpected modifier bits for label ${entry.labelRes}",
                    0,
                    entry.modifiers and allowedModifiers.inv(),
                )
                assertTrue(
                    "Duplicate label resource ${entry.labelRes}",
                    seenLabels.add(entry.labelRes),
                )
                assertTrue(
                    "Duplicate shortcut binding (keyCode=${entry.keyCode}, modifiers=${entry.modifiers})",
                    seenBindings.add(entry.keyCode to entry.modifiers),
                )
            }
        }
    }

    @Test
    fun keyBindingsMatchAppRootShortcuts() {
        val byLabel = KeyboardShortcuts.sections
            .flatMap { it.entries }
            .associateBy { it.labelRes }

        assertEquals(KeyEvent.KEYCODE_T to KeyEvent.META_CTRL_ON, byLabel.getValue(R.string.shortcut_new_tab).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_K to KeyEvent.META_CTRL_ON, byLabel.getValue(R.string.shortcut_command_palette).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_TAB to (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON), byLabel.getValue(R.string.shortcut_previous_tab).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.META_ALT_ON, byLabel.getValue(R.string.shortcut_back).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL to 0, byLabel.getValue(R.string.shortcut_delete).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL to KeyEvent.META_SHIFT_ON, byLabel.getValue(R.string.shortcut_delete_permanently).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_F1 to 0, byLabel.getValue(R.string.shortcut_help).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_F2 to 0, byLabel.getValue(R.string.shortcut_rename).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_F3 to 0, byLabel.getValue(R.string.shortcut_toggle_dual_pane).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_F5 to 0, byLabel.getValue(R.string.shortcut_copy_other_pane).let { it.keyCode to it.modifiers })
        assertEquals(KeyEvent.KEYCODE_F6 to 0, byLabel.getValue(R.string.shortcut_move_other_pane).let { it.keyCode to it.modifiers })
    }
}
