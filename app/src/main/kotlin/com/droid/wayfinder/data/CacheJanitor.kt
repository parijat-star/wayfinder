// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Evicts staged temporary files and directories (`remote-open/`, `zip-open/`, `picker/`, and
 * interrupted `wf-gdrive*.part` upload buffers) older than [DEFAULT_TTL_MS] from `cacheDir`.
 */
object CacheJanitor {
    const val DEFAULT_TTL_MS: Long = 24L * 3600_000L
    const val SWEEP_INTERVAL_MS: Long = 30L * 60_000L

    private val STAGED_SUBDIRS = listOf("remote-open", "zip-open", "picker", "view-archive", "thumbs", "zip-stage")
    private val ENCRYPTED_SUBDIRS = listOf("zip-open/enc", "picker/enc")
    private val ENCRYPTED_WIPE_SUBDIRS = listOf("zip-open/enc", "picker/enc", "zip-stage")
    private val CLOUD_TEMP_PREFIXES = listOf("wf-gdrive", "wf-onedrive")
    private const val CLOUD_TEMP_SUFFIX = ".part"

    private val lastSweepMillis = AtomicLong(0L)

    /**
     * Sweeps [cacheDir] immediately, deleting staged cache entries whose most recent modification
     * across their subtree is older than [ttlMillis] relative to [nowMillis].
     *
     * Inspecting the freshest timestamp in each entry subtree ensures that re-opening a file into
     * an existing `remote-open/<hash>/` or `zip-open/<hash>/` directory (`Conflict.Replace`) does
     * not get prematurely evicted solely because its parent directory was created >24h ago.
     *
     * @return number of top-level staged entries removed.
     */
    fun pruneStaleCaches(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MS,
    ): Int {
        lastSweepMillis.set(nowMillis)
        if (!cacheDir.isDirectory) return 0
        var removed = 0
        for (sub in ENCRYPTED_SUBDIRS) {
            val base = File(cacheDir, sub)
            val children = base.listFiles() ?: continue
            for (entry in children) {
                val freshest = freshestModifiedMillis(entry)
                if (nowMillis - freshest > ttlMillis) {
                    if (FileInfo.deleteTree(entry)) removed++
                }
            }
        }
        for (sub in STAGED_SUBDIRS) {
            val base = File(cacheDir, sub)
            val children = base.listFiles() ?: continue
            for (entry in children) {
                if ((sub == "zip-open" || sub == "picker") && entry.name == "enc" && entry.isDirectory) {
                    if (entry.listFiles().isNullOrEmpty() && nowMillis - entry.lastModified() > ttlMillis) {
                        entry.delete()
                    }
                    continue
                }
                val freshest = freshestModifiedMillis(entry)
                if (nowMillis - freshest > ttlMillis) {
                    if (FileInfo.deleteTree(entry)) removed++
                }
            }
        }
        cacheDir.listFiles()?.forEach { entry ->
            if (entry.isFile &&
                CLOUD_TEMP_PREFIXES.any { entry.name.startsWith(it) } &&
                entry.name.endsWith(CLOUD_TEMP_SUFFIX) &&
                nowMillis - entry.lastModified() > ttlMillis
            ) {
                if (entry.delete()) removed++
            }
        }
        return removed
    }

    /**
     * Immediately deletes temporary directories holding decrypted archive contents or staged
     * encrypted remote archives (`zip-open/enc/`, `zip-stage/`, and optionally `picker/enc/`)
     * so plaintext decrypted data never lingers across sessions.
     *
     * Pass [includePicker] = false when finishing a file-picker activity (`GET_CONTENT`) so the
     * external calling app still has time to read the returned `content://` URI.
     *
     * @return number of staged entries or directories removed.
     */
    fun wipeEncryptedStaging(cacheDir: File, includePicker: Boolean = true): Int {
        if (!cacheDir.isDirectory) return 0
        var removed = 0
        for (sub in ENCRYPTED_WIPE_SUBDIRS) {
            if (!includePicker && sub == "picker/enc") continue
            val base = File(cacheDir, sub)
            if (base.exists() || FileInfo.isSymlink(base.path)) {
                val childCount = base.listFiles()?.size ?: 0
                if (FileInfo.deleteTree(base)) {
                    removed += if (childCount > 0) childCount else 1
                }
            }
        }
        return removed
    }

    /**
     * Sweeps [cacheDir] only when at least [minIntervalMillis] has elapsed since the previous sweep,
     * keeping long-lived desktop sessions clean without redundant filesystem walks on back-to-back ops.
     */
    fun pruneIfDue(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        minIntervalMillis: Long = SWEEP_INTERVAL_MS,
        ttlMillis: Long = DEFAULT_TTL_MS,
    ): Int {
        val prev = lastSweepMillis.get()
        if (prev > 0L && nowMillis - prev < minIntervalMillis) return 0
        if (!lastSweepMillis.compareAndSet(prev, nowMillis)) return 0
        return pruneStaleCaches(cacheDir, nowMillis = nowMillis, ttlMillis = ttlMillis)
    }

    internal fun resetSweepClockForTests() {
        lastSweepMillis.set(0L)
    }

    /**
     * Returns the newest `lastModified` timestamp across [root] and any regular files/directories
     * inside it without following symbolic links.
     */
    internal fun freshestModifiedMillis(root: File): Long {
        var newest = root.lastModified()
        if (FileInfo.isSymlink(root.path) || !root.isDirectory) return newest
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (kid in kids) {
                val ts = kid.lastModified()
                if (ts > newest) newest = ts
                if (!FileInfo.isSymlink(kid.path) && kid.isDirectory) {
                    stack.addLast(kid)
                }
            }
        }
        return newest
    }
}
