// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

/** Axis-aligned rectangle in floating-point pixel coordinates, guaranteed normalized when built via [fromPoints]. */
data class MarqueeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun overlaps(other: MarqueeRect): Boolean =
        !isEmpty && !other.isEmpty &&
            right > other.left && other.right > left &&
            bottom > other.top && other.bottom > top

    /**
     * True if this item rectangle intersects [marquee]. Handles straight vertical (zero width,
     * positive height) and straight horizontal (positive width, zero height) marquee drags so
     * dragging straight down a list or straight across a grid row still selects every crossed item.
     */
    fun intersectsMarquee(marquee: MarqueeRect): Boolean {
        if (isEmpty) return false
        if (marquee.width <= 0f && marquee.height <= 0f) return false
        val xHit = if (marquee.width <= 0f) {
            marquee.left in left..right
        } else {
            right > marquee.left && marquee.right > left
        }
        val yHit = if (marquee.height <= 0f) {
            marquee.top in top..bottom
        } else {
            bottom > marquee.top && marquee.bottom > top
        }
        return xHit && yHit
    }

    fun translate(dx: Float, dy: Float): MarqueeRect =
        MarqueeRect(left + dx, top + dy, right + dx, bottom + dy)

    fun clampedTo(viewportWidth: Float, viewportHeight: Float): MarqueeRect {
        val w = viewportWidth.coerceAtLeast(0f)
        val h = viewportHeight.coerceAtLeast(0f)
        val l = left.coerceIn(0f, w)
        val t = top.coerceIn(0f, h)
        val r = right.coerceIn(l, w)
        val b = bottom.coerceIn(t, h)
        return MarqueeRect(l, t, r, b)
    }

    companion object {
        fun fromPoints(x1: Float, y1: Float, x2: Float, y2: Float): MarqueeRect =
            MarqueeRect(
                left = minOf(x1, x2),
                top = minOf(y1, y2),
                right = maxOf(x1, x2),
                bottom = maxOf(y1, y2),
            )
    }
}

/** How a marquee drag combines intersected items with the selection that existed before the drag started. */
enum class MarqueeMode {
    /** No modifier: selection equals only the items intersecting the marquee box. */
    Replace,
    /** Shift held: union of pre-drag selection and items intersecting the marquee box. */
    Add,
    /** Ctrl/Meta held: toggles items intersecting the marquee box against the pre-drag selection. */
    Toggle,
}

/** Applies [mode] to combine [initialSelection] (snapshotted at drag start) with currently [hitItems]. */
fun applyMarqueeMode(
    initialSelection: Set<String>,
    hitItems: Set<String>,
    mode: MarqueeMode,
): Set<String> = when (mode) {
    MarqueeMode.Replace -> hitItems
    MarqueeMode.Add -> initialSelection + hitItems
    MarqueeMode.Toggle -> (initialSelection - hitItems) + (hitItems - initialSelection)
}

/**
 * Computes vertical auto-scroll speed (px/sec; negative scrolls up, positive scrolls down) when
 * dragging near or past the top/bottom edges of a scrollable list or grid viewport.
 */
fun marqueeAutoScrollSpeedPxPerSec(
    pointerY: Float,
    viewportHeight: Float,
    edgeZonePx: Float,
    maxSpeedPxPerSec: Float,
): Float {
    if (viewportHeight <= 0f || edgeZonePx <= 0f || maxSpeedPxPerSec <= 0f) return 0f
    val edge = minOf(edgeZonePx, viewportHeight * 0.35f)
    if (edge <= 0f) return 0f
    return when {
        pointerY < edge -> {
            val ratio = ((edge - pointerY) / edge).coerceIn(0f, 1.5f)
            -maxSpeedPxPerSec * ratio
        }
        pointerY > viewportHeight - edge -> {
            val ratio = ((pointerY - (viewportHeight - edge)) / edge).coerceIn(0f, 1.5f)
            maxSpeedPxPerSec * ratio
        }
        else -> 0f
    }
}

/**
 * Pure state machine for a single marquee (rubber-band) selection drag.
 *
 * Tracks coordinates in content space (viewport coordinates + cumulative vertical scroll delta since
 * drag start) so items that scroll off-screen during auto-scroll remain accurately selected or
 * deselected as the user extends or shrinks the selection box.
 */
class MarqueeSession(
    val startContentX: Float,
    val startContentY: Float,
    val initialSelection: Set<String>,
    val mode: MarqueeMode,
) {
    var currentViewportX: Float = startContentX
        private set
    var currentViewportY: Float = startContentY
        private set
    var scrollY: Float = 0f
        private set

    private val itemContentBounds = LinkedHashMap<String, MarqueeRect>()

    fun moveTo(viewportX: Float, viewportY: Float) {
        currentViewportX = viewportX
        currentViewportY = viewportY
    }

    fun onScrolled(deltaY: Float) {
        scrollY += deltaY
    }

    /** Records currently visible items' viewport-local bounds into drag content space. */
    fun updateVisibleItems(visibleViewportBounds: Map<String, MarqueeRect>) {
        for ((path, rect) in visibleViewportBounds) {
            itemContentBounds[path] = rect.translate(0f, scrollY)
        }
    }

    /** Normalized selection rectangle in drag content space. */
    fun contentRect(): MarqueeRect =
        MarqueeRect.fromPoints(
            startContentX,
            startContentY,
            currentViewportX,
            currentViewportY + scrollY,
        )

    /** Normalized selection rectangle in viewport-local space, clamped to the viewport for drawing. */
    fun overlayRect(viewportWidth: Float, viewportHeight: Float): MarqueeRect =
        MarqueeRect.fromPoints(
            startContentX,
            startContentY - scrollY,
            currentViewportX,
            currentViewportY,
        ).clampedTo(viewportWidth, viewportHeight)

    /** Computes the live selection set from all items seen during this drag (optionally filtered to [validPaths]). */
    fun computeSelection(validPaths: Set<String>? = null): Set<String> {
        val box = contentRect()
        val hits = LinkedHashSet<String>()
        for ((path, bounds) in itemContentBounds) {
            if ((validPaths == null || path in validPaths) && bounds.intersectsMarquee(box)) {
                hits += path
            }
        }
        val base = if (validPaths == null) {
            initialSelection
        } else {
            initialSelection.filterTo(LinkedHashSet()) { it in validPaths }
        }
        return applyMarqueeMode(base, hits, mode)
    }
}
