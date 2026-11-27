// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.AppShortcutSync
import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.data.AppShortcuts
import com.droid.wayfinder.data.FsPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Fuzzes dynamic app shortcut planning and incoming ACTION_OPEN_SHORTCUT intent resolution.
 * Invariants: never throws, never resolves to Wayfinder's private data dir, trash dir, symlinks,
 * NUL / dot-dot traversal paths, or unpinned remote URIs, and never leaks internal signing tokens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShortcutFuzzTest {
    private val k = FuzzKit
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun sharedRoot(): File = Files.createTempDirectory("wf-fuzz-shortcuts-").toFile().apply {
        File(this, "Download").mkdirs()
        File(this, "Documents/Work").mkdirs()
        File(this, ".Wayfinder-Trash/deleted").mkdirs()
        File(this, "notes.txt").writeText("not a directory")
        runCatching {
            Files.createSymbolicLink(File(this, "link_to_private").toPath(), File(ctx.applicationInfo.dataDir).toPath())
        }
        runCatching {
            Files.createSymbolicLink(File(this, "link_to_trash").toPath(), File(this, ".Wayfinder-Trash").toPath())
        }
    }

    @Test
    fun incomingShortcutIntentResolutionNeverEscapesOrThrows() {
        val root = sharedRoot()
        val dataDir = File(ctx.applicationInfo.dataDir).canonicalPath
        val trashDir = File(root, ".Wayfinder-Trash").canonicalPath
        val pinned = listOf(File(root, "Download").path, File(root, "Documents/Work").path)
        val seeds = listOf(
            File(root, "Download").path,
            File(root, "link_to_private").path,
            File(root, "link_to_trash").path,
            File(root, "notes.txt").path,
            File(root, ".Wayfinder-Trash/deleted").path,
            ctx.applicationInfo.dataDir,
            "${root.path}/Download/../../etc/passwd",
            "smb://attacker.local/share",
            "onedrive://spoof/root",
            "gdrive://victim@example.com/root",
            "trash://",
            "recents://",
            "${root.path}/Download\u0000/x",
        )
        try {
            k.run("AppShortcutSync.resolveIncomingShortcut", 4_000, { r ->
                val base = seeds[r.nextInt(seeds.size)]
                if (r.nextBoolean()) k.mutate(r, base) else k.input(r, 48)
            }, k.corpus + seeds) { raw ->
                val intent = Intent(AppShortcuts.ACTION_OPEN_SHORTCUT).apply {
                    putExtra(AppShortcuts.EXTRA_SHORTCUT_ID, raw)
                    putExtra(AppShortcuts.EXTRA_SHORTCUT_PATH, raw)
                }
                val resolved = AppShortcutSync.resolveIncomingShortcut(
                    context = ctx,
                    intent = intent,
                    primaryStorageRoot = root.path,
                    pinnedOverride = pinned,
                )
                if (resolved != null) {
                    assertFalse("NUL in output for ${k.escape(raw)}", resolved.contains('\u0000'))
                    assertFalse("virtual path resolved: ${k.escape(resolved)}", FsPath.isVirtual(resolved))
                    assertFalse("archive path resolved: ${k.escape(resolved)}", FsPath.isZip(resolved))
                    assertFalse("unpinned remote resolved: ${k.escape(resolved)}", FsPath.isRemote(resolved) && resolved !in pinned)
                    if (FsPath.isLocalFile(resolved)) {
                        assertFalse("dot-dot segment in ${k.escape(resolved)}", resolved.split('/').contains(".."))
                        val file = File(resolved)
                        assertTrue("resolved local path must exist as dir: ${k.escape(resolved)}", file.isDirectory)
                        val canon = file.canonicalPath
                        assertFalse("private app path escaped: ${k.escape(canon)}", FsPath.isWithin(dataDir, canon))
                        assertFalse("trash dir escaped: ${k.escape(canon)}", FsPath.isWithin(trashDir, canon))
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun plannedShortcutIntentsNeverLeakInternalTokensOrRemotePaths() {
        val root = sharedRoot()
        try {
            k.run("AppShortcuts.planShortcuts", 1_500, { r ->
                val prefix = listOf("${root.path}/", "smb://host/", "onedrive://acct/", "gdrive://a@b.c/", "saf://m/", "").random(r)
                prefix + k.input(r, 32)
            }, k.corpus) { candidate ->
                val specs = AppShortcuts.planShortcuts(
                    pins = listOf(candidate),
                    maxCount = 5,
                    primaryStorageRoot = root.path,
                    trashRoot = File(root, ".Wayfinder-Trash").path,
                )
                for (spec in specs) {
                    assertTrue(spec.shortLabel.isNotBlank())
                    assertTrue(spec.longLabel.isNotBlank())
                    assertFalse(spec.id.contains('\u0000'))
                    val intent = AppShortcutSync.buildShortcutIntent(ctx, spec)
                    assertFalse(intent.hasExtra(IntentGuard.EXTRA_TOKEN))
                    for (internalExtra in IntentGuard.INTERNAL_EXTRAS) {
                        assertFalse(intent.hasExtra(internalExtra))
                    }
                    if (FsPath.isRemote(spec.path)) {
                        assertFalse("Remote shortcut must not expose path extra", intent.hasExtra(AppShortcuts.EXTRA_SHORTCUT_PATH))
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
