// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context

/** Persistent UI preferences: pinned folders, open tabs, view options. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun list(key: String): List<String> =
        p.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    var pins: List<String>?
        get() = if (p.contains("pins")) list("pins") else null
        set(v) = p.edit().putString("pins", v.orEmpty().joinToString("\n")).apply()

    var tabs: List<String>
        get() = list("tabs")
        set(v) = p.edit().putString("tabs", v.joinToString("\n")).apply()

    var activeTab: Int
        get() = p.getInt("activeTab", 0)
        set(v) = p.edit().putInt("activeTab", v).apply()

    var showHidden: Boolean
        get() = p.getBoolean("showHidden", false)
        set(v) = p.edit().putBoolean("showHidden", v).apply()

    var sidebarVisible: Boolean
        get() = p.getBoolean("sidebar", true)
        set(v) = p.edit().putBoolean("sidebar", v).apply()

    var previewVisible: Boolean
        get() = p.getBoolean("preview", false)
        set(v) = p.edit().putBoolean("preview", v).apply()

    var sidebarWidth: Float
        get() = p.getFloat("sidebarWidth", 248f)
        set(v) = p.edit().putFloat("sidebarWidth", v).apply()

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(p.getString("viewMode", null) ?: "") }.getOrDefault(ViewMode.Details)
        set(v) = p.edit().putString("viewMode", v.name).apply()

    var sortKey: SortKey
        get() = runCatching { SortKey.valueOf(p.getString("sortKey", null) ?: "") }.getOrDefault(SortKey.Name)
        set(v) = p.edit().putString("sortKey", v.name).apply()

    var sortAsc: Boolean
        get() = p.getBoolean("sortAsc", true)
        set(v) = p.edit().putBoolean("sortAsc", v).apply()

    var gridSize: Float
        get() = p.getFloat("gridSize", 128f)
        set(v) = p.edit().putFloat("gridSize", v).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.System)
        set(v) = p.edit().putString("theme", v.name).apply()

    /** Interface zoom factor (1.0 = 100%). */
    var zoom: Float
        get() = p.getFloat("zoom", 1f)
        set(v) = p.edit().putFloat("zoom", v).apply()

    /** Stars and color tags (see [Marks.serialize]). */
    var marks: String?
        get() = p.getString("marks", null)
        set(v) = p.edit().putString("marks", v).apply()

    var tagsColumn: Boolean
        get() = p.getBoolean("tagsColumn", false)
        set(v) = p.edit().putBoolean("tagsColumn", v).apply()

    var permsColumn: Boolean
        get() = p.getBoolean("permsColumn", false)
        set(v) = p.edit().putBoolean("permsColumn", v).apply()

    /** Mounted document trees (cloud drives, USB, ...), see [SafMount.serialize]. */
    var safMounts: List<SafMount>
        get() = SafMount.parse(p.getString("safMounts", null))
        set(v) = p.edit().putString("safMounts", SafMount.serialize(v)).apply()

    /** Whether to show the SAF pre-flight helper dialog before launching the system folder picker. */
    var showSafMountTip: Boolean
        get() = p.getBoolean("showSafMountTip", true)
        set(v) = p.edit().putBoolean("showSafMountTip", v).apply()

    /** Last observed Cross-Device `QuickAccessDocumentsProvider` `COLUMN_ROOT_ID` (`<deviceId>`) for `EXTRA_INITIAL_URI`. */
    var lastQuickAccessRootId: String?
        get() = p.getString("lastQuickAccessRootId", null)?.takeIf { it.isNotBlank() }
        set(v) = p.edit().putString("lastQuickAccessRootId", v?.trim()).apply()
}
