// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SystemDocumentsBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Fuzzes the code that turns other apps' "open this" requests into locations: ACTION_VIEW / BROWSE URI
 * resolution (file://, DocumentsContract incl. raw: and relative-path IDs, arbitrary content://), and the
 * relative document-ID mapping. Invariants: never throws, never yields Wayfinder's private data dir or a
 * path with NUL / dot-dot segments, and mapped paths always stay inside shared storage and exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntentTargetFuzzTest {
    private val k = FuzzKit
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sharedRoot(): File = Files.createTempDirectory("wf-fuzz-shared-").toFile().apply {
        File(this, "Documents/.gemini/antigravity/scratch/app/build/outputs/apk/debug").mkdirs()
        File(this, "Documents/.gemini/antigravity/scratch/app/build/outputs/apk/debug/app-debug.apk").writeText("apk")
        File(this, "Download/x.apk").apply { parentFile?.mkdirs(); writeText("apk") }
        File(this, ".hidden/a.zip").apply { parentFile?.mkdirs(); writeText("PK") }
    }

    private fun assertSafe(out: String?, root: File, input: String) {
        if (out == null) return
        assertFalse("NUL in output for ${k.escape(input)}", out.contains('\u0000'))
        val dataDir = File(ctx.applicationInfo.dataDir).canonicalPath
        if (FsPath.isLocal(out)) {
            val local = FsPath.zipSplit(out)?.first ?: out
            val canon = runCatching { File(local).canonicalPath }.getOrDefault(local)
            assertFalse("private app path for ${k.escape(input)} -> ${k.escape(out)}", FsPath.isWithin(dataDir, canon))
        }
    }

    @Test fun relativeDocIdMappingStaysInsideSharedStorage() {
        val root = sharedRoot()
        val canonRoot = root.canonicalPath
        val seeds = listOf(
            "antigravity/scratch/app/build/outputs/apk/debug",
            "antigravity/scratch/app/build/outputs/apk/debug/app-debug.apk",
            "Download/x.apk", ".hidden/a.zip", "a/../../etc/passwd", "../../../", "/abs/path", "x//y", "acc=1;0",
        )
        try {
            k.run("localPathForRelativeDocId", 4_000, { r ->
                val base = seeds[r.nextInt(seeds.size)]
                if (r.nextBoolean()) k.mutate(r, base) else k.input(r, 40)
            }, k.corpus + seeds) { id ->
                val out = SystemDocumentsBridge.localPathForRelativeDocId(id, root.path)
                if (out != null) {
                    assertTrue("must exist: ${k.escape(out)}", File(out).exists())
                    assertTrue("must stay inside shared storage: ${k.escape(out)}", FsPath.isWithin(canonRoot, File(out).canonicalPath))
                    assertTrue("must end with the document ID", out.endsWith("/" + id.trim('/')))
                    assertFalse(out.split('/').contains(".."))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun viewAndBrowseTargetResolutionNeverEscapes() {
        val root = sharedRoot()
        val authorities = listOf(
            SystemDocumentsBridge.EXTERNAL_STORAGE_AUTHORITY,
            "com.android.providers.downloads.documents",
            "com.google.android.apps.antigravity.documents",
            "com.example.unknown",
            "${ctx.packageName}.fileprovider",
        )
        val mimes = listOf(null, MainActivity.APK_MIME, "application/zip", "resource/folder", "*/*", "")
        try {
            k.run("MainActivity.resolveViewTargetPath", 3_000, { r ->
                val body = k.input(r, 40)
                when (r.nextInt(5)) {
                    0 -> "file://" + (if (r.nextBoolean()) root.path + "/" else "") + body
                    1 -> "raw:" + (if (r.nextBoolean()) root.path + "/" else "/") + body
                    2 -> "primary:" + body
                    3 -> "antigravity/" + body
                    else -> body
                }
            }, k.corpus) { raw ->
                val auth = authorities[raw.length % authorities.size]
                val mime = mimes[raw.hashCode().mod(mimes.size)]
                val uri = when {
                    raw.startsWith("file://") -> Uri.parse(raw)
                    raw.length % 3 == 0 -> Uri.parse("content://$auth/$raw")
                    else -> runCatching { DocumentsContract.buildDocumentUri(auth, raw) }.getOrNull() ?: Uri.EMPTY
                }
                val out = MainActivity.resolveViewTargetPath(ctx, uri, mime, root.path)
                assertSafe(out, root, raw)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun apkNamesAreNeverTreatedAsArchives() {
        k.run("isApkName", 20_000, { r -> k.input(r, 30) + listOf(".apk", ".APK", ".apks", ".xapk", ".zip", "").random(r) }, k.corpus) { name ->
            if (MainActivity.isApkName(name)) {
                val lower = name.lowercase()
                assertTrue(lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm"))
            }
        }
    }
}
