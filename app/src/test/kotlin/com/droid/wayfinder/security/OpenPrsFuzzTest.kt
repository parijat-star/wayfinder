// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import android.graphics.Rect
import com.droid.wayfinder.data.AudioTrackInfo
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalMediaStoreUri
import com.droid.wayfinder.data.LocalUriResolver
import com.droid.wayfinder.data.MarqueeMode
import com.droid.wayfinder.data.MarqueeRect
import com.droid.wayfinder.data.MarqueeSession
import com.droid.wayfinder.data.MediaFormatters
import com.droid.wayfinder.data.MediaInfo
import com.droid.wayfinder.data.MediaTags
import com.droid.wayfinder.data.SharedPrefixStreamBuffer
import com.droid.wayfinder.data.SubtitleTrackInfo
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.data.VideoTrackInfo
import com.droid.wayfinder.data.applyMarqueeMode
import com.droid.wayfinder.data.marqueeAutoScrollSpeedPxPerSec
import com.droid.wayfinder.data.selectionToolbarState
import com.droid.wayfinder.ui.resolveDesktopCaptionHorizontalInsets
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Deterministic fuzz tests covering state machines, formatters, URI/path resolvers, and inset math
 * introduced across open PRs (#59, #63, #65, #66, #71).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenPrsFuzzTest {

    @Test
    fun fuzzMediaFormattersAgainstExtremeAndCorruptedMetadata() {
        val rng = Random(0x59_63_65_72L)
        val specialFloats = floatArrayOf(
            0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1f, 23.976f, 29.97f, 59.94f,
        )
        val specialInts = intArrayOf(
            0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, 1920, 1080, 3840, 2160, 90, -90, 270, -270, 360000,
        )
        val specialLongs = longArrayOf(
            0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 500L, 999_999L, 1_000_000L, 86_400_000_000L,
        )

        repeat(2_000) { iter ->
            val fps = if (iter < specialFloats.size) specialFloats[iter] else Float.fromBits(rng.nextInt())
            val par = if (iter < specialFloats.size) specialFloats[(iter + 3) % specialFloats.size] else Float.fromBits(rng.nextInt())
            val w = if (iter < specialInts.size) specialInts[iter] else rng.nextInt()
            val h = if (iter < specialInts.size) specialInts[(iter + 5) % specialInts.size] else rng.nextInt()
            val rot = if (iter < specialInts.size) specialInts[(iter + 7) % specialInts.size] else rng.nextInt()
            val dur = if (iter < specialLongs.size) specialLongs[iter] else rng.nextLong()
            val bps = if (iter < specialLongs.size) specialLongs[(iter + 2) % specialLongs.size] else rng.nextLong()

            val fpsStr = MediaFormatters.formatFrameRate(fps)
            assertTrue(fpsStr.endsWith(" fps"))

            val resStr = MediaFormatters.formatResolution(w, h, rot, par)
            assertTrue(resStr.isNotEmpty())

            val durStr = MediaFormatters.formatDuration(dur)
            if (dur < 0L) assertEquals("", durStr) else assertTrue(durStr.isNotEmpty())

            val brStr = MediaFormatters.formatBitrate(bps)
            assertTrue(brStr.isNotEmpty())

            val lang = MediaFormatters.friendlyLanguage(if (rng.nextBoolean()) "en-US\u0000bad" else "und")
            if (lang != null) assertTrue(lang.isNotBlank())

            val summary = MediaFormatters.formatSummary(
                MediaInfo(
                    container = MediaFormatters.friendlyContainerName("video/mp4", "mp4"),
                    durationUs = dur.takeIf { it >= 0L },
                    overallBitrateBps = bps.takeIf { it > 0L },
                    videoTracks = listOf(
                        VideoTrackInfo(
                            codec = MediaFormatters.friendlyCodecName("video/avc", "avc1.640028"),
                            codecProfile = "avc1.640028",
                            width = w.takeIf { it > 0 },
                            height = h.takeIf { it > 0 },
                            rotationDegrees = rot,
                            pixelAspectRatio = par,
                            frameRate = fps,
                            bitrateBps = bps.takeIf { it > 0L },
                            bitDepth = 10,
                            colorSpace = "BT.709",
                            colorTransfer = "SDR",
                            colorRange = "Limited",
                        ),
                    ),
                    audioTracks = listOf(
                        AudioTrackInfo(
                            codec = "AAC",
                            channelCount = rng.nextInt(-4, 16),
                            sampleRateHz = rng.nextInt(-1000, 192_000),
                            bitDepth = 24,
                            bitrateBps = 192_000L,
                            language = "English",
                        ),
                    ),
                    subtitleTracks = listOf(SubtitleTrackInfo(codec = "WebVTT", language = "en")),
                    tags = MediaTags(title = "Track $iter", artist = "Artist"),
                ),
            )
            assertTrue(summary.isNotEmpty())
        }
    }

    @Test
    fun fuzzMarqueeSessionSetAlgebraAndViewportClamping() {
        val rng = Random(0x66_70_72L)
        val modes = MarqueeMode.entries
        val universe = (0 until 40).map { "/storage/emulated/0/Docs/item_$it.txt" }
        val validSet = universe.toSet()

        repeat(1_500) {
            val initial = universe.filter { rng.nextInt(4) == 0 }.toSet()
            val mode = modes[rng.nextInt(modes.size)]
            val startX = rng.nextFloat() * 1200f - 100f
            val startY = rng.nextFloat() * 900f - 100f
            val session = MarqueeSession(startX, startY, initial, mode)

            repeat(rng.nextInt(1, 12)) {
                val visible = HashMap<String, MarqueeRect>()
                repeat(rng.nextInt(0, 15)) {
                    val p = universe[rng.nextInt(universe.size)]
                    val x1 = rng.nextFloat() * 1000f
                    val y1 = rng.nextFloat() * 800f
                    val w = if (rng.nextInt(8) == 0) 0f else rng.nextFloat() * 300f
                    val h = if (rng.nextInt(8) == 0) 0f else rng.nextFloat() * 64f
                    visible[p] = MarqueeRect(x1, y1, x1 + w, y1 + h)
                }
                session.updateVisibleItems(visible)
                session.moveTo(rng.nextFloat() * 1400f - 200f, rng.nextFloat() * 1000f - 200f)
                session.onScrolled(rng.nextFloat() * 400f - 150f)

                val vpW = rng.nextFloat() * 1600f
                val vpH = rng.nextFloat() * 1000f
                val overlay = session.overlayRect(vpW, vpH)
                assertTrue(overlay.left >= 0f && overlay.right <= vpW.coerceAtLeast(0f) + 0.001f)
                assertTrue(overlay.top >= 0f && overlay.bottom <= vpH.coerceAtLeast(0f) + 0.001f)
                assertTrue(overlay.right >= overlay.left && overlay.bottom >= overlay.top)

                val speed = marqueeAutoScrollSpeedPxPerSec(
                    pointerY = session.currentViewportY,
                    viewportHeight = vpH,
                    edgeZonePx = 36f,
                    maxSpeedPxPerSec = 720f,
                )
                assertTrue(speed.isFinite())

                val selected = session.computeSelection(validSet)
                assertTrue(validSet.containsAll(selected))
                if (mode == MarqueeMode.Add) {
                    assertTrue(selected.containsAll(initial))
                }
            }

            // Algebraic check on applyMarqueeMode
            val hits = universe.filter { rng.nextBoolean() }.toSet()
            assertEquals(hits, applyMarqueeMode(initial, hits, MarqueeMode.Replace))
            assertEquals(initial + hits, applyMarqueeMode(initial, hits, MarqueeMode.Add))
            val toggled = applyMarqueeMode(initial, hits, MarqueeMode.Toggle)
            assertEquals(initial, applyMarqueeMode(toggled, hits, MarqueeMode.Toggle))
        }
    }

    @Test
    fun fuzzLocalMediaStoreAndSafDocIdBoundariesAndCaptionInsets() {
        val rng = Random(0x59_71L)
        val root = "/storage/emulated/10"
        val badSegments = listOf("..", ".", "", "a\u0000b", "Pictures", "DCIM", ".hidden")

        repeat(1_500) {
            val depth = rng.nextInt(1, 6)
            val segs = List(depth) {
                if (rng.nextInt(3) == 0) badSegments[rng.nextInt(badSegments.size)] else "dir_${rng.nextInt(50)}"
            }
            val candidate = "$root/${segs.joinToString("/")}/img_${rng.nextInt(100)}.jpg"
            val rel = LocalMediaStoreUri.relativeLocationInPrimaryStorage(candidate, root)
            if (segs.any { it.isEmpty() || it == "." || it == ".." }) {
                assertNull("Traversal or empty segment must be rejected: $candidate", rel)
            } else if (rel != null) {
                assertFalse(rel.relativePath.contains(".."))
                assertTrue(rel.relativePath.endsWith("/"))
                assertTrue(rel.displayName.isNotEmpty())
            }

            // Sibling prefix confusion check on SAF tree docId matching
            val treeId = "primary:Pictures/Trip${rng.nextInt(10)}"
            assertTrue(LocalUriResolver.isDocIdWithinTree(treeId, treeId))
            assertTrue(LocalUriResolver.isDocIdWithinTree(treeId, "$treeId/sub/photo.jpg"))
            assertFalse(LocalUriResolver.isDocIdWithinTree(treeId, "${treeId}_other/photo.jpg"))

            // Random freeform resize caption inset bounding rects
            val widthPx = rng.nextInt(-100, 4000)
            val rects = List(rng.nextInt(0, 5)) {
                val l = rng.nextInt(-50, 4200)
                val r = l + rng.nextInt(-20, 2000)
                Rect(l, 0, r, rng.nextInt(0, 96))
            }
            val resolved = resolveDesktopCaptionHorizontalInsets(rects, widthPx, edgeTolerancePx = 48)
            if (resolved != null) {
                assertTrue(resolved.first >= 0)
                assertTrue(resolved.second >= 0)
            }
        }
    }

    @Test
    fun fuzzSelectionActionsTrashBreadcrumbsAndSharedStreamBuffer() {
        val rng = Random(0x73_75_79_83L)
        val trashDir = File(System.getProperty("java.io.tmpdir"), "wf_fuzz_trash_${System.nanoTime()}").apply { mkdirs() }
        try {
            val trash = TrashManager(trashDir)
            val tabPaths = listOf(
                "/storage/emulated/10/Docs",
                FsPath.TRASH_ROOT,
                "${trashDir.path}/slot1/OldFolder",
                "${trashDir.path}/slot1/archive.zip!/inner",
                "/storage/emulated/10/pack.zip!/sub",
                FsPath.STARRED_ROOT,
                FsPath.RECENTS_ROOT,
                FsPath.SMB_ROOT,
            )

            repeat(1_000) {
                val tabPath = tabPaths[rng.nextInt(tabPaths.size)]
                val inTrash = trash.isInTrash(tabPath)
                val entries = List(rng.nextInt(0, 6)) { idx ->
                    val isZip = rng.nextInt(4) == 0
                    val p = if (isZip) "/storage/emulated/10/pack.zip!/f_$idx.txt" else "$tabPath/f_$idx.txt"
                    FileEntry(
                        path = p,
                        name = "f_$idx.txt",
                        isDir = rng.nextBoolean(),
                        isServer = rng.nextInt(8) == 0,
                        isShare = rng.nextInt(8) == 0,
                    )
                }
                val state = selectionToolbarState(tabPath, entries, inTrash = inTrash)
                if (entries.any { FsPath.isZip(it.path) && !it.isServer && !it.isShare && !FsPath.isVirtual(it.path) }) {
                    assertFalse("Zip entries must never enable Delete or Cut", state.canDelete || state.canCut)
                }
                if (inTrash) {
                    assertFalse("Trash must never enable Cut, Copy, Compress, or Share", state.canCut || state.canCopy || state.canCompress || state.canShare)
                }

                // Trash traversal & breadcrumb fuzzing
                val maybeTraversal = "${trashDir.path}/slot_${rng.nextInt(5)}/${if (rng.nextBoolean()) ".." else "sub"}/x.txt"
                if (maybeTraversal.contains("/../")) {
                    assertFalse(trash.isInTrash(maybeTraversal))
                    assertNull(trash.breadcrumbsOf(maybeTraversal))
                } else {
                    val crumbs = trash.breadcrumbsOf(maybeTraversal)
                    assertTrue(crumbs != null && crumbs.first().second == FsPath.TRASH_ROOT)
                    assertTrue(crumbs!!.none { it.first.contains(".Wayfinder-Trash") || it.first.startsWith("slot_") })
                }
            }

            // SharedPrefixStreamBuffer interleaved random forward/backward seeks
            val data = ByteArray(200_000) { (it * 17 + 3).toByte() }
            val cap = 131_072L
            val shared = SharedPrefixStreamBuffer(
                openInput = { ByteArrayInputStream(data) },
                totalSize = data.size.toLong(),
                maxBytes = cap,
            )
            val dest = ByteArray(8192)
            repeat(300) {
                val pos = rng.nextLong(-64L, 220_000L)
                val len = rng.nextInt(0, dest.size)
                val n = shared.readAt(pos, dest, 0, len)
                if (len == 0) {
                    assertEquals(0, n)
                } else if (pos < 0L || pos >= cap) {
                    assertEquals(-1, n)
                } else if (n > 0) {
                    for (i in 0 until n) {
                        assertEquals(data[pos.toInt() + i], dest[i])
                    }
                }
                assertTrue(shared.bytesDownloaded <= cap)
            }
            shared.closeUnderlying()
        } finally {
            trashDir.deleteRecursively()
        }
    }
}
