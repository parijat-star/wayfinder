// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import com.droid.wayfinder.R

/** Single keyboard shortcut exposed to Android's system Keyboard Shortcuts Helper (`Meta + /`). */
data class KeyboardShortcutEntry(
    @param:StringRes val labelRes: Int,
    val keyCode: Int,
    val modifiers: Int = 0,
)

/** Named group of keyboard shortcuts for the system Keyboard Shortcuts Helper. */
data class KeyboardShortcutSection(
    @param:StringRes val titleRes: Int,
    val entries: List<KeyboardShortcutEntry>,
)

/**
 * Catalog of Wayfinder's desktop keyboard shortcuts for Android's system Keyboard Shortcuts Helper
 * (`Activity.onProvideKeyboardShortcuts`). Mirrors the groups in the in-app F1 shortcuts sheet
 * and the key bindings handled in `AppRoot`.
 */
object KeyboardShortcuts {
    private const val CTRL = KeyEvent.META_CTRL_ON
    private const val SHIFT = KeyEvent.META_SHIFT_ON
    private const val ALT = KeyEvent.META_ALT_ON
    private const val CTRL_SHIFT = CTRL or SHIFT
    private const val ALT_SHIFT = ALT or SHIFT

    fun sectionsFor(dualPane: Boolean): List<KeyboardShortcutSection> = listOf(
        KeyboardShortcutSection(
            titleRes = R.string.shortcut_group_tabs_windows,
            entries = listOf(
                KeyboardShortcutEntry(R.string.shortcut_new_tab, KeyEvent.KEYCODE_T, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_close_tab, KeyEvent.KEYCODE_W, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_next_tab, KeyEvent.KEYCODE_TAB, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_previous_tab, KeyEvent.KEYCODE_TAB, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_go_to_tab, KeyEvent.KEYCODE_1, ALT),
                KeyboardShortcutEntry(R.string.shortcut_reopen_closed_tab, KeyEvent.KEYCODE_T, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_open_folder_new_tab, KeyEvent.KEYCODE_ENTER, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_command_palette, KeyEvent.KEYCODE_K, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_new_window, KeyEvent.KEYCODE_N, CTRL),
            ),
        ),
        KeyboardShortcutSection(
            titleRes = R.string.shortcut_group_navigate,
            entries = buildList {
                add(KeyboardShortcutEntry(R.string.shortcut_back, KeyEvent.KEYCODE_DPAD_LEFT, ALT))
                add(KeyboardShortcutEntry(R.string.shortcut_forward, KeyEvent.KEYCODE_DPAD_RIGHT, ALT))
                add(KeyboardShortcutEntry(R.string.shortcut_up_one_level, KeyEvent.KEYCODE_DPAD_UP, ALT))
                add(KeyboardShortcutEntry(R.string.shortcut_open, KeyEvent.KEYCODE_ENTER))
                add(KeyboardShortcutEntry(R.string.shortcut_quick_look, KeyEvent.KEYCODE_SPACE))
                add(KeyboardShortcutEntry(R.string.shortcut_address_bar, KeyEvent.KEYCODE_L, CTRL))
                add(KeyboardShortcutEntry(R.string.shortcut_search, KeyEvent.KEYCODE_F, CTRL))
                add(KeyboardShortcutEntry(R.string.shortcut_refresh, KeyEvent.KEYCODE_R, CTRL))
                if (!dualPane) {
                    add(KeyboardShortcutEntry(R.string.shortcut_refresh_f5, KeyEvent.KEYCODE_F5))
                }
                add(KeyboardShortcutEntry(R.string.shortcut_connect_server, KeyEvent.KEYCODE_K, CTRL_SHIFT))
            },
        ),
        KeyboardShortcutSection(
            titleRes = R.string.shortcut_group_select_edit,
            entries = listOf(
                KeyboardShortcutEntry(R.string.shortcut_select_all, KeyEvent.KEYCODE_A, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_invert_selection, KeyEvent.KEYCODE_I, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_clear_selection, KeyEvent.KEYCODE_A, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_toggle_item, KeyEvent.KEYCODE_SPACE, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_cut, KeyEvent.KEYCODE_X, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_copy, KeyEvent.KEYCODE_C, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_paste, KeyEvent.KEYCODE_V, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_undo, KeyEvent.KEYCODE_Z, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_redo, KeyEvent.KEYCODE_Y, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_duplicate, KeyEvent.KEYCODE_D, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_rename, KeyEvent.KEYCODE_F2),
                KeyboardShortcutEntry(R.string.shortcut_batch_rename, KeyEvent.KEYCODE_R, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_copy_path, KeyEvent.KEYCODE_C, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_delete, KeyEvent.KEYCODE_FORWARD_DEL),
                KeyboardShortcutEntry(R.string.shortcut_delete_permanently, KeyEvent.KEYCODE_FORWARD_DEL, SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_new_folder, KeyEvent.KEYCODE_N, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_new_text_file, KeyEvent.KEYCODE_M, CTRL_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_properties, KeyEvent.KEYCODE_I, CTRL),
            ),
        ),
        KeyboardShortcutSection(
            titleRes = R.string.shortcut_group_view,
            entries = listOf(
                KeyboardShortcutEntry(R.string.shortcut_view_details, KeyEvent.KEYCODE_1, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_view_grid, KeyEvent.KEYCODE_2, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_view_columns, KeyEvent.KEYCODE_3, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_view_dual_pane, KeyEvent.KEYCODE_4, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_hidden_files, KeyEvent.KEYCODE_H, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_pin_folder, KeyEvent.KEYCODE_B, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_toggle_sidebar, KeyEvent.KEYCODE_BACKSLASH, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_toggle_details_pane, KeyEvent.KEYCODE_P, ALT),
                KeyboardShortcutEntry(R.string.shortcut_locate_in_tree, KeyEvent.KEYCODE_L, ALT_SHIFT),
                KeyboardShortcutEntry(R.string.shortcut_zoom_in, KeyEvent.KEYCODE_EQUALS, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_zoom_out, KeyEvent.KEYCODE_MINUS, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_zoom_reset, KeyEvent.KEYCODE_0, CTRL),
                KeyboardShortcutEntry(R.string.shortcut_help, KeyEvent.KEYCODE_F1),
            ),
        ),
        KeyboardShortcutSection(
            titleRes = R.string.shortcut_group_dual_pane,
            entries = buildList {
                add(KeyboardShortcutEntry(R.string.shortcut_toggle_dual_pane, KeyEvent.KEYCODE_F3))
                if (dualPane) {
                    add(KeyboardShortcutEntry(R.string.shortcut_switch_pane, KeyEvent.KEYCODE_TAB))
                    add(KeyboardShortcutEntry(R.string.shortcut_copy_other_pane, KeyEvent.KEYCODE_F5))
                    add(KeyboardShortcutEntry(R.string.shortcut_move_other_pane, KeyEvent.KEYCODE_F6))
                }
            },
        ),
    )

    val sections: List<KeyboardShortcutSection> = sectionsFor(dualPane = true)

    fun toSystemGroups(context: Context, dualPane: Boolean = true): List<KeyboardShortcutGroup> =
        sectionsFor(dualPane).map { section ->
            KeyboardShortcutGroup(
                context.getString(section.titleRes),
                section.entries.map { entry ->
                    KeyboardShortcutInfo(
                        context.getString(entry.labelRes),
                        entry.keyCode,
                        entry.modifiers,
                    )
                },
            )
        }
}
