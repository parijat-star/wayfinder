// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import com.droid.wayfinder.data.CacheJanitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheJanitorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        CacheJanitor.resetSweepClockForTests()
    }

    @Test
    fun prunesStaleEntriesAcrossRemoteOpenZipOpenPickerAndGdriveTempFiles() {
        val cacheDir = tempFolder.newFolder("cache")
        val now = 1_800_000_000_000L
        val staleTs = now - CacheJanitor.DEFAULT_TTL_MS - 60_000L
        val freshTs = now - 3_600_000L

        val staleRemote = File(cacheDir, "remote-open/stale-hash").apply { mkdirs() }
        File(staleRemote, "video.mp4").apply { writeText("old"); setLastModified(staleTs) }
        staleRemote.setLastModified(staleTs)

        val freshRemote = File(cacheDir, "remote-open/fresh-hash").apply { mkdirs() }
        File(freshRemote, "report.pdf").apply { writeText("new"); setLastModified(freshTs) }
        freshRemote.setLastModified(freshTs)

        val staleZip = File(cacheDir, "zip-open/stale-archive").apply { mkdirs() }
        File(staleZip, "README.txt").apply { writeText("old"); setLastModified(staleTs) }
        staleZip.setLastModified(staleTs)

        val stalePicker = File(cacheDir, "picker/1700000000").apply { mkdirs() }
        File(stalePicker, "picked.jpg").apply { writeText("old"); setLastModified(staleTs) }
        stalePicker.setLastModified(staleTs)

        val staleGdrive = File(cacheDir, "wf-gdrive12345.part").apply {
            writeText("partial")
            setLastModified(staleTs)
        }
        val freshGdrive = File(cacheDir, "wf-gdrive99999.part").apply {
            writeText("uploading")
            setLastModified(freshTs)
        }
        val unrelatedCoilCache = File(cacheDir, "image_cache").apply {
            mkdirs()
            File(this, "thumb.0").apply { writeText("cached"); setLastModified(staleTs) }
            setLastModified(staleTs)
        }

        val removed = CacheJanitor.pruneStaleCaches(cacheDir, nowMillis = now)
        assertEquals(4, removed)
        assertFalse(staleRemote.exists())
        assertTrue(freshRemote.exists())
        assertFalse(staleZip.exists())
        assertFalse(stalePicker.exists())
        assertFalse(staleGdrive.exists())
        assertTrue(freshGdrive.exists())
        assertTrue("Unrelated Coil cache directories must not be deleted", unrelatedCoilCache.exists())
    }

    @Test
    fun preservesHashDirectoryWhenRestagedChildFileIsFreshEvenIfParentFolderIsOld() {
        val cacheDir = tempFolder.newFolder("cache")
        val now = 1_800_000_000_000L
        val oldDirTs = now - (3L * CacheJanitor.DEFAULT_TTL_MS)
        val restagedFileTs = now - 5 * 60_000L

        val hashDir = File(cacheDir, "remote-open/abcd1234").apply { mkdirs() }
        val restagedFile = File(hashDir, "presentation.pdf").apply {
            writeText("updated")
            setLastModified(restagedFileTs)
        }
        // Simulate POSIX behavior where overwriting an existing file in place leaves dir mtime old.
        hashDir.setLastModified(oldDirTs)

        val removed = CacheJanitor.pruneStaleCaches(cacheDir, nowMillis = now)
        assertEquals(0, removed)
        assertTrue(hashDir.exists())
        assertTrue(restagedFile.exists())
    }

    @Test
    fun pruneIfDueRespectsSweepIntervalUntilWindowElapses() {
        val cacheDir = tempFolder.newFolder("cache")
        val t0 = 1_800_000_000_000L
        val staleTs = t0 - CacheJanitor.DEFAULT_TTL_MS - 1_000L

        // Initial sweep sets clock at t0.
        assertEquals(0, CacheJanitor.pruneIfDue(cacheDir, nowMillis = t0))

        // Add a stale file; sweeping 5 minutes later should skip because < 30 min elapsed.
        val staleZip = File(cacheDir, "zip-open/entry1").apply {
            mkdirs()
            File(this, "a.bin").apply { writeText("x"); setLastModified(staleTs) }
            setLastModified(staleTs)
        }
        assertEquals(0, CacheJanitor.pruneIfDue(cacheDir, nowMillis = t0 + 5 * 60_000L))
        assertTrue(staleZip.exists())

        // Once 30 minutes have elapsed, pruneIfDue runs and deletes the stale entry.
        assertEquals(1, CacheJanitor.pruneIfDue(cacheDir, nowMillis = t0 + CacheJanitor.SWEEP_INTERVAL_MS + 1L))
        assertFalse(staleZip.exists())
    }
}
