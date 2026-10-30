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

    private val STAGED_SUBDIRS = listOf("remote-open", "zip-open", "picker")
    private const val GDRIVE_TEMP_PREFIX = "wf-gdrive"
    private const val GDRIVE_TEMP_SUFFIX = ".part"

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
        for (sub in STAGED_SUBDIRS) {
            val base = File(cacheDir, sub)
            val children = base.listFiles() ?: continue
            for (entry in children) {
                val freshest = freshestModifiedMillis(entry)
                if (nowMillis - freshest > ttlMillis) {
                    if (FileInfo.deleteTree(entry)) removed++
                }
            }
        }
        cacheDir.listFiles()?.forEach { entry ->
            if (entry.isFile &&
                entry.name.startsWith(GDRIVE_TEMP_PREFIX) &&
                entry.name.endsWith(GDRIVE_TEMP_SUFFIX) &&
                nowMillis - entry.lastModified() > ttlMillis
            ) {
                if (entry.delete()) removed++
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
