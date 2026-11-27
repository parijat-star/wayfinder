// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.droid.wayfinder.data.AppShortcutSpec
import com.droid.wayfinder.data.AppShortcuts
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.OneDriveAccountStore
import com.droid.wayfinder.data.RemoteUri
import com.droid.wayfinder.data.ServerStore
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.ShortcutIconKind
import com.droid.wayfinder.data.StandardFolderLabels
import java.io.File

/**
 * Android framework bridge for Wayfinder's dynamic app shortcuts (`ShortcutManagerCompat`).
 * Mirrors the top sidebar folder pins into the launcher / taskbar right-click menu and validates
 * incoming [AppShortcuts.ACTION_OPEN_SHORTCUT] intents.
 */
object AppShortcutSync {

    fun maxShortcutCount(context: Context): Int = runCatching {
        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
    }.getOrDefault(AppShortcuts.MAX_SHORTCUTS).coerceIn(0, AppShortcuts.MAX_SHORTCUTS)

    fun standardLabels(context: Context): StandardFolderLabels = StandardFolderLabels(
        downloads = context.getString(R.string.shortcut_downloads),
        documents = context.getString(R.string.shortcut_documents),
        pictures = context.getString(R.string.shortcut_pictures),
        music = context.getString(R.string.shortcut_music),
        movies = context.getString(R.string.shortcut_movies),
    )

    @DrawableRes
    fun iconResFor(kind: ShortcutIconKind): Int = when (kind) {
        ShortcutIconKind.Downloads -> R.drawable.ic_shortcut_downloads
        ShortcutIconKind.Documents -> R.drawable.ic_shortcut_documents
        ShortcutIconKind.Pictures -> R.drawable.ic_shortcut_pictures
        ShortcutIconKind.Music -> R.drawable.ic_shortcut_music
        ShortcutIconKind.Movies -> R.drawable.ic_shortcut_movies
        ShortcutIconKind.Cloud -> R.drawable.ic_shortcut_cloud
        ShortcutIconKind.Network -> R.drawable.ic_shortcut_network
        ShortcutIconKind.Folder -> R.drawable.ic_shortcut_folder
    }

    /**
     * Builds the explicit launch intent stored inside a dynamic shortcut.
     *
     * Intentionally omits [IntentGuard.EXTRA_TOKEN] and all internal extras so launcher processes
     * can never read Wayfinder's per-install signing token. Uses [Intent.FLAG_ACTIVITY_CLEAR_TOP]
     * + [Intent.FLAG_ACTIVITY_SINGLE_TOP] so clicking a shortcut while Wayfinder is open routes to
     * [MainActivity.onNewIntent] and opens a new tab in the active window.
     */
    fun buildShortcutIntent(context: Context, spec: AppShortcutSpec): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = AppShortcuts.ACTION_OPEN_SHORTCUT
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AppShortcuts.EXTRA_SHORTCUT_ID, spec.id)
            if (spec.exposePathExtra) {
                putExtra(AppShortcuts.EXTRA_SHORTCUT_PATH, spec.path)
            }
        }

    /** Publishes [specs] as the app's current dynamic shortcuts, replacing any previous list. */
    fun publish(context: Context, specs: List<AppShortcutSpec>): Boolean {
        val cap = maxShortcutCount(context)
        val bounded = specs.take(cap)
        val shortcuts = bounded.map { spec ->
            ShortcutInfoCompat.Builder(context, spec.id)
                .setShortLabel(spec.shortLabel)
                .setLongLabel(spec.longLabel)
                .setIcon(IconCompat.createWithResource(context, iconResFor(spec.icon)))
                .setRank(spec.rank)
                .setIntent(buildShortcutIntent(context, spec))
                .build()
        }
        return runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }.getOrDefault(false)
    }

    internal fun defaultStandardPinPaths(primaryStorageRoot: String): List<String> =
        listOf("Download", "Documents", "Pictures", "Music", "Movies")
            .map { File(primaryStorageRoot, it) }
            .filter { it.isDirectory }
            .map { it.path }

    internal fun isSafeLocalDirectory(
        context: Context,
        path: String,
        trashRoot: String? = null,
    ): Boolean {
        if (!AppShortcuts.isSafeLocalPathSyntax(path, trashRoot)) return false
        val norm = FsPath.normalize(path)
        if (MainActivity.isPrivateAppPath(context, norm)) return false
        if (FileInfo.isSymlink(norm)) return false
        val file = File(norm)
        if (!runCatching { file.isDirectory }.getOrDefault(false)) return false
        if (trashRoot != null) {
            val canonical = runCatching { file.canonicalPath }.getOrDefault(norm)
            val canonicalTrash = runCatching { File(trashRoot).canonicalPath }.getOrDefault(trashRoot)
            if (FsPath.isWithin(canonicalTrash, canonical)) return false
        }
        return true
    }

    internal fun isValidRemotePin(context: Context, path: String): Boolean {
        val norm = FsPath.normalize(path)
        if (!FsPath.isRemote(norm) || norm.contains('\u0000') || FsPath.isVirtual(norm) || FsPath.isZip(norm)) {
            return false
        }
        return when {
            FsPath.isSaf(norm) -> Settings(context).safMounts.any { FsPath.isWithin(it.path, norm) }
            FsPath.isOneDrive(norm) -> {
                val accountId = norm.removePrefix("${RemoteUri.ONEDRIVE_SCHEME}://").substringBefore('/')
                accountId.isNotBlank() && OneDriveAccountStore(context).getAccount(accountId) != null
            }
            FsPath.isGdrive(norm) -> {
                val email = norm.removePrefix("${RemoteUri.GDRIVE_SCHEME}://").substringBefore('/')
                email.isNotBlank() && email.contains('@')
            }
            FsPath.isDoc(norm) -> norm.removePrefix("${RemoteUri.DOC_SCHEME}://").contains('/')
            RemoteUri.protocolOf(norm) != null -> {
                val credKey = RemoteUri.credKeyOf(norm)
                credKey != null && ServerStore(context).saved().any { it.key == credKey }
            }
            else -> false
        }
    }

    /**
     * Validates an incoming [AppShortcuts.ACTION_OPEN_SHORTCUT] intent and returns its navigable
     * folder path, or null if the intent is malformed, spoofed, or points to a missing/unsafe folder.
     */
    fun resolveIncomingShortcut(
        context: Context,
        intent: Intent,
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }.getOrDefault("/storage/emulated/0"),
        pinnedOverride: List<String>? = null,
    ): String? {
        if (intent.action != AppShortcuts.ACTION_OPEN_SHORTCUT) return null
        val shortcutId = runCatching { intent.getStringExtra(AppShortcuts.EXTRA_SHORTCUT_ID) }.getOrNull()
        val pathExtra = runCatching { intent.getStringExtra(AppShortcuts.EXTRA_SHORTCUT_PATH) }.getOrNull()
        val pinned = pinnedOverride ?: Settings(context).pins ?: defaultStandardPinPaths(primaryStorageRoot)
        val trashRoot = File(primaryStorageRoot, ".Wayfinder-Trash").path
        return AppShortcuts.resolveTargetPath(
            shortcutId = shortcutId,
            pathExtra = pathExtra,
            pinnedPaths = pinned,
            trashRoot = trashRoot,
            isSafeLocalDirectory = { path -> isSafeLocalDirectory(context, path, trashRoot) },
            isValidRemotePath = { path -> isValidRemotePin(context, path) },
        )
    }
}
