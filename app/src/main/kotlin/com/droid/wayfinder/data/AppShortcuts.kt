// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.security.MessageDigest

/** Visual category for a dynamic launcher / taskbar app shortcut icon. */
enum class ShortcutIconKind {
    Downloads,
    Documents,
    Pictures,
    Music,
    Movies,
    Cloud,
    Network,
    Folder,
}

/** Localized display labels for Android's five standard shared-storage folders. */
data class StandardFolderLabels(
    val downloads: String = "Downloads",
    val documents: String = "Documents",
    val pictures: String = "Pictures",
    val music: String = "Music",
    val movies: String = "Movies",
)

/**
 * Pure description of one dynamic app shortcut to publish to Android's launcher / taskbar.
 *
 * [exposePathExtra] is true only for local filesystem directories so remote URIs (which may embed
 * account emails or server hostnames) are never leaked into launcher-readable shortcut metadata.
 */
data class AppShortcutSpec(
    val id: String,
    val path: String,
    val shortLabel: String,
    val longLabel: String,
    val icon: ShortcutIconKind,
    val rank: Int,
    val exposePathExtra: Boolean,
)

/**
 * Pure planning, ID generation, icon classification, and incoming-intent resolution for Wayfinder's
 * dynamic app shortcuts, which mirror the top folder pins in the sidebar.
 */
object AppShortcuts {
    const val ACTION_OPEN_SHORTCUT = "com.droid.wayfinder.action.OPEN_SHORTCUT"
    const val EXTRA_SHORTCUT_ID = "com.droid.wayfinder.extra.SHORTCUT_ID"
    const val EXTRA_SHORTCUT_PATH = "com.droid.wayfinder.extra.SHORTCUT_PATH"

    /**
     * Upper bound on dynamic shortcuts Wayfinder publishes. Stock Android / Launcher3 taskbars display
     * up to 4 shortcuts (ranks 0..3), while launchers supporting 5 will show all 5 (e.g. Downloads,
     * Documents, Pictures, Music, Movies by default). Always clamped to the device's
     * `getMaxShortcutCountPerActivity()`.
     */
    const val MAX_SHORTCUTS = 5

    private const val MAX_SHORT_LABEL_LEN = 25
    private const val MAX_LONG_LABEL_LEN = 40
    private const val MAX_SHORTCUT_ID_LEN = 64

    /** Deterministic, opaque shortcut ID derived from the normalized target path. */
    fun shortcutId(path: String): String {
        val norm = FsPath.normalize(path)
        val digest = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray(Charsets.UTF_8))
        val hex = buildString(16) {
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xFF
                append("0123456789abcdef"[b ushr 4])
                append("0123456789abcdef"[b and 0x0F])
            }
        }
        return "pin_$hex"
    }

    /** Returns the localized standard label when [path] is one of the 5 standard folders under [primaryStorageRoot]. */
    fun standardDisplayLabel(
        path: String,
        primaryStorageRoot: String,
        labels: StandardFolderLabels,
    ): String? {
        if (!FsPath.isLocalFile(path) || primaryStorageRoot.isBlank()) return null
        val norm = FsPath.normalize(path)
        val root = FsPath.normalize(primaryStorageRoot)
        if (FsPath.parent(norm) != root) return null
        return when (FsPath.name(norm).lowercase()) {
            "download", "downloads" -> labels.downloads
            "documents" -> labels.documents
            "pictures" -> labels.pictures
            "music" -> labels.music
            "movies" -> labels.movies
            else -> null
        }
    }

    /** Chooses an icon category matching Wayfinder's sidebar pin icons. */
    fun classifyIcon(path: String, primaryStorageRoot: String = ""): ShortcutIconKind {
        val norm = FsPath.normalize(path)
        if (FsPath.isSaf(norm) || FsPath.isGdrive(norm) || FsPath.isOneDrive(norm) || FsPath.isDoc(norm)) {
            return ShortcutIconKind.Cloud
        }
        if (FsPath.isRemote(norm)) {
            return ShortcutIconKind.Network
        }
        if (primaryStorageRoot.isNotBlank()) {
            val root = FsPath.normalize(primaryStorageRoot)
            if (FsPath.parent(norm) == root) {
                when (FsPath.name(norm).lowercase()) {
                    "download", "downloads" -> return ShortcutIconKind.Downloads
                    "documents" -> return ShortcutIconKind.Documents
                    "pictures", "dcim" -> return ShortcutIconKind.Pictures
                    "music" -> return ShortcutIconKind.Music
                    "movies", "videos" -> return ShortcutIconKind.Movies
                }
            }
        }
        return when (FsPath.name(norm).lowercase()) {
            "download", "downloads" -> ShortcutIconKind.Downloads
            "documents" -> ShortcutIconKind.Documents
            "pictures", "dcim" -> ShortcutIconKind.Pictures
            "music" -> ShortcutIconKind.Music
            "movies", "videos" -> ShortcutIconKind.Movies
            else -> ShortcutIconKind.Folder
        }
    }

    /** True when [rawPath] is syntactically safe as a local directory path (absolute, no NUL / `.` / `..`, outside trash). */
    fun isSafeLocalPathSyntax(rawPath: String, trashRoot: String? = null): Boolean {
        if (rawPath.isBlank() || rawPath.contains('\u0000')) return false
        if (!rawPath.startsWith("/")) return false
        if (rawPath.split('/').any { it == "." || it == ".." }) return false
        val norm = FsPath.normalize(rawPath)
        if (!FsPath.isLocalFile(norm)) return false
        if (trashRoot != null && FsPath.isWithin(trashRoot, norm)) return false
        return true
    }

    /**
     * Converts ordered sidebar [pins] into up to `min(maxCount, MAX_SHORTCUTS)` dynamic shortcut specs.
     * Non-folder pins, virtual roots, archive paths, trash paths, duplicates, and items failing
     * [isPinEligible] are skipped while preserving the user's sidebar order.
     */
    fun planShortcuts(
        pins: List<String>,
        maxCount: Int,
        primaryStorageRoot: String,
        rootLabels: Map<String, String> = emptyMap(),
        standardLabels: StandardFolderLabels = StandardFolderLabels(),
        trashRoot: String? = null,
        isPinEligible: (String) -> Boolean = { true },
    ): List<AppShortcutSpec> {
        val limit = maxCount.coerceIn(0, MAX_SHORTCUTS)
        if (limit == 0 || pins.isEmpty()) return emptyList()

        val eligible = ArrayList<String>(limit)
        val seen = HashSet<String>()
        for (raw in pins) {
            if (eligible.size >= limit) break
            if (raw.isBlank() || raw.contains('\u0000')) continue
            val norm = FsPath.normalize(raw)
            if (FsPath.isVirtual(norm) || FsPath.isZip(norm)) continue
            if (FsPath.isLocal(norm) && !isSafeLocalPathSyntax(raw, trashRoot)) continue
            if (trashRoot != null && FsPath.isWithin(trashRoot, norm)) continue
            if (!seen.add(norm)) continue
            if (!isPinEligible(norm)) continue
            eligible += norm
        }
        if (eligible.isEmpty()) return emptyList()

        val baseLabels = eligible.map { path ->
            val rawLabel = standardDisplayLabel(path, primaryStorageRoot, standardLabels)
                ?: rootLabels[path]
                ?: FsPath.name(path)
            sanitizeLabel(rawLabel).ifEmpty { "Folder" }
        }
        val labelCounts = HashMap<String, Int>()
        baseLabels.forEach { label ->
            val key = label.lowercase()
            labelCounts[key] = (labelCounts[key] ?: 0) + 1
        }

        return eligible.mapIndexed { index, path ->
            val base = baseLabels[index]
            val shortLabel = truncateLabel(base, MAX_SHORT_LABEL_LEN)
            val longCandidate = if ((labelCounts[base.lowercase()] ?: 0) > 1) {
                val parentLabel = FsPath.parent(path)?.let { parent ->
                    sanitizeLabel(rootLabels[parent] ?: FsPath.name(parent))
                }?.takeIf { it.isNotEmpty() && it != "/" }
                if (parentLabel != null) "$base ($parentLabel)" else base
            } else {
                base
            }
            AppShortcutSpec(
                id = shortcutId(path),
                path = path,
                shortLabel = shortLabel,
                longLabel = truncateLabel(longCandidate, MAX_LONG_LABEL_LEN),
                icon = classifyIcon(path, primaryStorageRoot),
                rank = index,
                exposePathExtra = FsPath.isLocalFile(path),
            )
        }
    }

    /**
     * Resolves an incoming [ACTION_OPEN_SHORTCUT] intent's extras into a validated Wayfinder folder path.
     *
     * 1. Attempts to match [shortcutId] against the user's persisted [pinnedPaths] (supporting both
     *    local and pinned remote/cloud folders without exposing remote URIs in shortcut extras).
     * 2. Falls back to [pathExtra] ONLY for safe local directories (so local dynamic shortcuts dragged
     *    to the home screen as pinned shortcuts continue to work even after unpinning from the sidebar).
     */
    fun resolveTargetPath(
        shortcutId: String?,
        pathExtra: String?,
        pinnedPaths: List<String>,
        trashRoot: String? = null,
        isSafeLocalDirectory: (String) -> Boolean,
        isValidRemotePath: (String) -> Boolean = { true },
    ): String? {
        val cleanId = shortcutId?.takeIf {
            it.isNotBlank() && it.length <= MAX_SHORTCUT_ID_LEN && it.none { c -> c.isWhitespace() || c < ' ' }
        }
        if (cleanId != null) {
            for (rawPin in pinnedPaths) {
                if (rawPin.isBlank() || rawPin.contains('\u0000')) continue
                val norm = FsPath.normalize(rawPin)
                if (shortcutId(norm) != cleanId) continue
                return when {
                    FsPath.isVirtual(norm) || FsPath.isZip(norm) -> null
                    FsPath.isLocalFile(norm) ->
                        norm.takeIf { isSafeLocalPathSyntax(rawPin, trashRoot) && isSafeLocalDirectory(norm) }
                    FsPath.isRemote(norm) ->
                        norm.takeIf { isValidRemotePath(norm) }
                    else -> null
                }
            }
        }
        if (pathExtra != null && isSafeLocalPathSyntax(pathExtra, trashRoot)) {
            val norm = FsPath.normalize(pathExtra)
            if (isSafeLocalDirectory(norm)) return norm
        }
        return null
    }

    private fun sanitizeLabel(raw: String): String {
        val cleaned = buildString(raw.length) {
            var prevSpace = false
            for (ch in raw) {
                if (ch < ' ' || ch == '\u007f' || ch.isWhitespace()) {
                    if (isNotEmpty() && !prevSpace) {
                        append(' ')
                        prevSpace = true
                    }
                } else {
                    append(ch)
                    prevSpace = false
                }
            }
        }.trim()
        return cleaned
    }

    private fun truncateLabel(label: String, maxLen: Int): String =
        if (label.length <= maxLen) label else label.take((maxLen - 1).coerceAtLeast(1)).trimEnd() + "…"
}
