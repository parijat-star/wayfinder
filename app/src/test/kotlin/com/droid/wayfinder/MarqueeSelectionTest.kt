// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.MarqueeMode
import com.droid.wayfinder.data.MarqueeRect
import com.droid.wayfinder.data.MarqueeSession
import com.droid.wayfinder.data.applyMarqueeMode
import com.droid.wayfinder.data.marqueeAutoScrollSpeedPxPerSec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeSelectionTest {
    @Test fun fromPointsNormalizesAllFourDragDirections() {
        val dr = MarqueeRect.fromPoints(10f, 20f, 110f, 220f)
        val dl = MarqueeRect.fromPoints(110f, 20f, 10f, 220f)
        val ur = MarqueeRect.fromPoints(10f, 220f, 110f, 20f)
        val ul = MarqueeRect.fromPoints(110f, 220f, 10f, 20f)
        val expected = MarqueeRect(10f, 20f, 110f, 220f)
        assertEquals(expected, dr)
        assertEquals(expected, dl)
        assertEquals(expected, ur)
        assertEquals(expected, ul)
        assertEquals(100f, ul.width, 0.001f)
        assertEquals(200f, ul.height, 0.001f)
        assertFalse(ul.isEmpty)
    }

    @Test fun overlapsHandlesTouchingAndDisjointRects() {
        val a = MarqueeRect(0f, 0f, 100f, 50f)
        assertTrue(a.overlaps(MarqueeRect(50f, 25f, 150f, 75f)))
        assertFalse("edge-touching rects do not overlap", a.overlaps(MarqueeRect(100f, 0f, 200f, 50f)))
        assertFalse("zero-width rect does not overlap", a.overlaps(MarqueeRect(50f, 0f, 50f, 50f)))
    }

    @Test fun intersectsMarqueeHandlesStraightVerticalAndHorizontalDrags() {
        val item = MarqueeRect(20f, 40f, 200f, 90f)
        // Straight vertical drag through the item
        assertTrue(item.intersectsMarquee(MarqueeRect.fromPoints(100f, 10f, 100f, 120f)))
        // Straight vertical drag outside X bounds
        assertFalse(item.intersectsMarquee(MarqueeRect.fromPoints(5f, 10f, 5f, 120f)))
        // Straight horizontal drag through the item
        assertTrue(item.intersectsMarquee(MarqueeRect.fromPoints(10f, 65f, 250f, 65f)))
        // Straight horizontal drag outside Y bounds
        assertFalse(item.intersectsMarquee(MarqueeRect.fromPoints(10f, 100f, 250f, 100f)))
        // Degenerate zero-size point
        assertFalse(item.intersectsMarquee(MarqueeRect.fromPoints(100f, 65f, 100f, 65f)))
    }

    @Test fun clampedToRestrictsOverlayInsideViewport() {
        val raw = MarqueeRect(-40f, -120f, 650f, 900f)
        assertEquals(MarqueeRect(0f, 0f, 500f, 400f), raw.clampedTo(500f, 400f))

        val completelyAbove = MarqueeRect(10f, -100f, 50f, -20f)
        assertTrue(completelyAbove.clampedTo(500f, 400f).isEmpty)
    }

    @Test fun applyMarqueeModeSupportsReplaceAddAndToggle() {
        val initial = setOf("a", "b")
        val hits = setOf("b", "c")
        assertEquals(setOf("b", "c"), applyMarqueeMode(initial, hits, MarqueeMode.Replace))
        assertEquals(setOf("a", "b", "c"), applyMarqueeMode(initial, hits, MarqueeMode.Add))
        assertEquals(setOf("a", "c"), applyMarqueeMode(initial, hits, MarqueeMode.Toggle))
    }

    @Test fun sessionReplaceAddAndToggleRespectInitialSnapshotOnShrink() {
        val rows = mapOf(
            "a" to MarqueeRect(0f, 0f, 400f, 48f),
            "b" to MarqueeRect(0f, 48f, 400f, 96f),
            "c" to MarqueeRect(0f, 96f, 400f, 144f),
            "d" to MarqueeRect(0f, 144f, 400f, 192f),
        )

        // Shift+drag (Add): pre-selected {a}, drag over b..d, then shrink back to b..c.
        val addSession = MarqueeSession(300f, 60f, initialSelection = setOf("a"), mode = MarqueeMode.Add)
        addSession.updateVisibleItems(rows)
        addSession.moveTo(200f, 170f)
        assertEquals(setOf("a", "b", "c", "d"), addSession.computeSelection())
        addSession.moveTo(200f, 110f)
        assertEquals(setOf("a", "b", "c"), addSession.computeSelection())

        // Ctrl+drag (Toggle): pre-selected {a, b}, drag over b..c -> {a, c}, then shrink to c only -> {a, b, c}.
        val toggleSession = MarqueeSession(300f, 120f, initialSelection = setOf("a", "b"), mode = MarqueeMode.Toggle)
        toggleSession.updateVisibleItems(rows)
        toggleSession.moveTo(200f, 70f)
        assertEquals(setOf("a", "c"), toggleSession.computeSelection())
        toggleSession.moveTo(200f, 110f)
        assertEquals(setOf("a", "b", "c"), toggleSession.computeSelection())
    }

    @Test fun sessionPreservesScrolledOffItemsAndUpdatesOverlayDuringAutoScroll() {
        val session = MarqueeSession(250f, 20f, initialSelection = emptySet(), mode = MarqueeMode.Replace)
        // Initial viewport (height 200) shows items 0..3 (50px each).
        session.updateVisibleItems(
            mapOf(
                "i0" to MarqueeRect(0f, 0f, 400f, 50f),
                "i1" to MarqueeRect(0f, 50f, 400f, 100f),
                "i2" to MarqueeRect(0f, 100f, 400f, 150f),
                "i3" to MarqueeRect(0f, 150f, 400f, 200f),
            ),
        )
        session.moveTo(150f, 190f)
        assertEquals(setOf("i0", "i1", "i2", "i3"), session.computeSelection())

        // Auto-scroll down by 150px: i0..i2 leave viewport; i3..i6 are now visible.
        session.onScrolled(150f)
        session.updateVisibleItems(
            mapOf(
                "i3" to MarqueeRect(0f, 0f, 400f, 50f),
                "i4" to MarqueeRect(0f, 50f, 400f, 100f),
                "i5" to MarqueeRect(0f, 100f, 400f, 150f),
                "i6" to MarqueeRect(0f, 150f, 400f, 200f),
            ),
        )
        // i0..i2 are off-screen now, yet must remain selected along with i3..i6!
        assertEquals(setOf("i0", "i1", "i2", "i3", "i4", "i5", "i6"), session.computeSelection())

        // Overlay top scrolled above viewport (20 - 150 = -130), so it clamps cleanly to top = 0.
        assertEquals(MarqueeRect(150f, 0f, 250f, 190f), session.overlayRect(400f, 200f))

        // Dragging pointer back up shrinks selection accurately (excludes i5 and i6).
        session.moveTo(150f, 90f)
        assertEquals(setOf("i0", "i1", "i2", "i3", "i4"), session.computeSelection())

        // Filtering by validPaths excludes items that vanished mid-drag.
        assertEquals(setOf("i0", "i1", "i3", "i4"), session.computeSelection(validPaths = setOf("i0", "i1", "i3", "i4", "i5")))
    }

    @Test fun autoScrollSpeedRampsNearEdgesAndClampsOutsideViewport() {
        val vpH = 400f
        val edge = 40f
        val maxSpeed = 600f

        assertEquals(0f, marqueeAutoScrollSpeedPxPerSec(200f, vpH, edge, maxSpeed), 0.001f)
        assertEquals(0f, marqueeAutoScrollSpeedPxPerSec(40f, vpH, edge, maxSpeed), 0.001f)
        assertEquals(0f, marqueeAutoScrollSpeedPxPerSec(360f, vpH, edge, maxSpeed), 0.001f)

        // Halfway into top edge zone -> -50% maxSpeed
        assertEquals(-300f, marqueeAutoScrollSpeedPxPerSec(20f, vpH, edge, maxSpeed), 0.001f)
        // Right at top border -> -100% maxSpeed
        assertEquals(-600f, marqueeAutoScrollSpeedPxPerSec(0f, vpH, edge, maxSpeed), 0.001f)
        // Far above top border -> clamped at -150% maxSpeed
        assertEquals(-900f, marqueeAutoScrollSpeedPxPerSec(-200f, vpH, edge, maxSpeed), 0.001f)

        // Halfway into bottom edge zone -> +50% maxSpeed
        assertEquals(300f, marqueeAutoScrollSpeedPxPerSec(380f, vpH, edge, maxSpeed), 0.001f)
        // Far below bottom border -> clamped at +150% maxSpeed
        assertEquals(900f, marqueeAutoScrollSpeedPxPerSec(700f, vpH, edge, maxSpeed), 0.001f)

        // Guard invalid viewport/edge
        assertEquals(0f, marqueeAutoScrollSpeedPxPerSec(10f, 0f, edge, maxSpeed), 0.001f)
    }
}
