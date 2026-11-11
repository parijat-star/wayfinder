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

    var modifiedColumn: Boolean
        get() = p.getBoolean("modifiedColumn", true)
        set(v) = p.edit().putBoolean("modifiedColumn", v).apply()

    var typeColumn: Boolean
        get() = p.getBoolean("typeColumn", true)
        set(v) = p.edit().putBoolean("typeColumn", v).apply()

    var sizeColumn: Boolean
        get() = p.getBoolean("sizeColumn", true)
        set(v) = p.edit().putBoolean("sizeColumn", v).apply()

    var tagsColumn: Boolean
        get() = p.getBoolean("tagsColumn", false)
        set(v) = p.edit().putBoolean("tagsColumn", v).apply()

    var permsColumn: Boolean
        get() = p.getBoolean("permsColumn", false)
        set(v) = p.edit().putBoolean("permsColumn", v).apply()

    /** Whether to show Wayfinder's bottom item-count status bar inside the file pane. */
    var statusBarVisible: Boolean
        get() = p.getBoolean("statusBar", true)
        set(v) = p.edit().putBoolean("statusBar", v).apply()

    /** Whether to hide the Android system status bar across fullscreen Wayfinder windows. */
    var hideStatusBar: Boolean
        get() = p.getBoolean("hideStatusBar", false)
        set(v) = p.edit().putBoolean("hideStatusBar", v).apply()

    var columnOrder: List<SortKey>
        get() = parseColumnOrder(p.getString("columnOrder", null))
        set(v) = p.edit().putString("columnOrder", normalizeColumnOrder(v).joinToString(",") { it.name }).apply()

    var columnWidths: Map<SortKey, Float>
        get() = parseColumnWidths(p.getString("columnWidths", null))
        set(v) {
            val serialized = DEFAULT_METADATA_COLUMNS.joinToString(",") { k ->
                val w = (v[k] ?: defaultColumnWidth(k)).coerceIn(MIN_COLUMN_WIDTH_DP, MAX_COLUMN_WIDTH_DP)
                "${k.name}:$w"
            }
            p.edit().putString("columnWidths", serialized).apply()
        }

    fun columnWidth(key: SortKey): Float =
        (columnWidths[key] ?: defaultColumnWidth(key)).coerceIn(MIN_COLUMN_WIDTH_DP, MAX_COLUMN_WIDTH_DP)

    fun setColumnWidth(key: SortKey, widthDp: Float) {
        if (key == SortKey.Name) return
        val next = columnWidths.toMutableMap()
        next[key] = widthDp.coerceIn(MIN_COLUMN_WIDTH_DP, MAX_COLUMN_WIDTH_DP)
        columnWidths = next
    }

    fun isColumnVisible(key: SortKey): Boolean = when (key) {
        SortKey.Name -> true
        SortKey.Modified -> modifiedColumn
        SortKey.Type -> typeColumn
        SortKey.Size -> sizeColumn
        SortKey.Tags -> tagsColumn
        SortKey.Permissions -> permsColumn
    }

    fun setColumnVisible(key: SortKey, visible: Boolean) {
        when (key) {
            SortKey.Name -> Unit
            SortKey.Modified -> modifiedColumn = visible
            SortKey.Type -> typeColumn = visible
            SortKey.Size -> sizeColumn = visible
            SortKey.Tags -> tagsColumn = visible
            SortKey.Permissions -> permsColumn = visible
        }
    }

    fun resetColumns() {
        p.edit()
            .putBoolean("modifiedColumn", true)
            .putBoolean("typeColumn", true)
            .putBoolean("sizeColumn", true)
            .putBoolean("tagsColumn", false)
            .putBoolean("permsColumn", false)
            .remove("columnOrder")
            .remove("columnWidths")
            .apply()
    }

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

    /** Whether the first-install "Make Wayfinder your default file handler?" prompt has been shown. */
    var shownDefaultHandlerPrompt: Boolean
        get() = p.getBoolean("shownDefaultHandlerPrompt", false)
        set(v) = p.edit().putBoolean("shownDefaultHandlerPrompt", v).apply()
}

fun normalizeColumnOrder(order: List<SortKey>): List<SortKey> {
    val valid = order.filter { it != SortKey.Name && it in DEFAULT_METADATA_COLUMNS }.distinct()
    return valid + DEFAULT_METADATA_COLUMNS.filter { it !in valid }
}

fun parseColumnOrder(raw: String?): List<SortKey> {
    if (raw.isNullOrBlank()) return DEFAULT_METADATA_COLUMNS
    val parsed = raw.split(',', '\n').mapNotNull { token ->
        val t = token.trim()
        DEFAULT_METADATA_COLUMNS.firstOrNull { it.name.equals(t, ignoreCase = true) }
    }
    return normalizeColumnOrder(parsed)
}

fun parseColumnWidths(raw: String?): Map<SortKey, Float> {
    val out = LinkedHashMap<SortKey, Float>(DEFAULT_COLUMN_WIDTHS)
    if (raw.isNullOrBlank()) return out
    raw.split(',', '\n').forEach { entry ->
        val parts = entry.split(':', '=')
        if (parts.size == 2) {
            val key = DEFAULT_METADATA_COLUMNS.firstOrNull { it.name.equals(parts[0].trim(), ignoreCase = true) }
            val value = parts[1].trim().toFloatOrNull()
            if (key != null && value != null && value.isFinite()) {
                out[key] = value.coerceIn(MIN_COLUMN_WIDTH_DP, MAX_COLUMN_WIDTH_DP)
            }
        }
    }
    return out
}
