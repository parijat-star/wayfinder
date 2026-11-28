// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.data.TabStripDragMath
import com.droid.wayfinder.data.TabTransferRegistry
import com.droid.wayfinder.data.TransferredTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzes session-ID validation, single-use claim semantics, and tab-drag math under hostile/degenerate inputs
 * (NaN, Infinity, negative widths, huge counts, NUL bytes, SQL/path traversal strings).
 */
class TabTransferFuzzTest {
    private val k = FuzzKit

    @After
    fun tearDown() {
        TabTransferRegistry.resetForTest()
    }

    @Test
    fun malformedAndSpoofedSessionIdsAreAlwaysRejected() {
        val realId = TabTransferRegistry.createDirectNewWindowSession(
            TransferredTab(path = "/storage/emulated/0/Docs"),
            nowMs = 10_000L,
        )
        k.run("TabTransferRegistry.sanitizeSessionId", 5_000, { r ->
            when (r.nextInt(4)) {
                0 -> k.mutate(r, realId)
                1 -> k.input(r, 80)
                2 -> realId.uppercase()
                else -> realId + "\u0000"
            }
        }, k.corpus) { candidate ->
            val sanitized = TabTransferRegistry.sanitizeSessionId(candidate)
            if (sanitized != null) {
                assertEquals(32, sanitized.length)
                assertTrue(sanitized.all { it in '0'..'9' || it in 'a'..'f' })
            }
            if (candidate != realId) {
                assertNull("Spoofed id '${k.escape(candidate)}' must not claim real session", TabTransferRegistry.claimForNewWindow(candidate, nowMs = 10_100L))
            }
        }
        // Original valid session remains uncorrupted and claimable exactly once.
        assertEquals("/storage/emulated/0/Docs", TabTransferRegistry.claimForNewWindow(realId, nowMs = 10_200L)?.path)
        assertNull(TabTransferRegistry.claimForNewWindow(realId, nowMs = 10_201L))
    }

    @Test
    fun dragAndLaunchMathNeverThrowsOrProducesOutOfBoundsIndicesOnDegenerateFloats() {
        val edgeFloats = floatArrayOf(
            0f, -0f, 1f, -1f, 0.0001f, -999999f, 999999f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        k.run("TabStripDragMath", 4_000, { r -> "${r.nextInt()}" }, emptyList()) { seedStr ->
            val h = seedStr.hashCode()
            val px = edgeFloats[h.mod(edgeFloats.size)]
            val grab = edgeFloats[(h ushr 3).mod(edgeFloats.size)]
            val tabW = edgeFloats[(h ushr 6).mod(edgeFloats.size)]
            val count = (h ushr 9).mod(25) - 5 // includes negative and zero counts!
            val rtl = (h and 1) == 0

            val clamped = TabStripDragMath.clampedDraggedLeftPx(px, grab, tabW, count)
            assertTrue("clampedDraggedLeftPx must be finite, got $clamped", clamped.isFinite() && clamped >= 0f)

            val reorderIdx = TabStripDragMath.targetReorderIndex(clamped, tabW, count, currentIndex = 0, isRtl = rtl)
            if (count > 0) {
                assertTrue("reorderIdx $reorderIdx out of 0..<${count}", reorderIdx in 0 until count)
            } else {
                assertEquals(0, reorderIdx)
            }

            val mergeIdx = TabStripDragMath.mergeInsertionIndex(px, tabW, count, rtl)
            val maxMerge = count.coerceAtLeast(0)
            assertTrue("mergeIdx $mergeIdx out of 0..$maxMerge", mergeIdx in 0..maxMerge)

            val speed = TabStripDragMath.autoScrollSpeedPxPerSec(px, tabW, grab, 600f)
            assertTrue("autoScrollSpeed must be finite, got $speed", speed.isFinite())
        }
    }
}
