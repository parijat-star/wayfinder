// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.MenuEntry
import com.droid.wayfinder.ui.QuickLook
import com.droid.wayfinder.ui.fileMenu
import com.droid.wayfinder.ui.paletteCommands
import com.droid.wayfinder.ui.quickLookAnimatedDrawableFactory
import com.droid.wayfinder.ui.quickLookMediaPlayerFactory
import com.droid.wayfinder.ui.theme.WayfinderTheme
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class QuickLookMediaPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After
    fun tearDown() {
        quickLookMediaPlayerFactory = null
        quickLookAnimatedDrawableFactory = null
        h?.close()
    }

    private fun idle(harness: VmHarness) {
        rule.waitForIdle()
        harness.settle()
        rule.waitForIdle()
    }

    /** In-memory Media3 [SimpleBasePlayer] for deterministic Compose UI playback tests. */
    private class FakeMedia3Player(
        private val durationMs: Long,
        private val metadata: MediaMetadata = MediaMetadata.EMPTY,
        private val videoSize: VideoSize = VideoSize.UNKNOWN,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        var fakePlaybackState: Int = STATE_IDLE
        var fakePlayWhenReady: Boolean = false
        var fakePositionMs: Long = 0L
        var fakeVolume: Float = 1f
        var fakePlaybackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        var released: Boolean = false
        private var currentItem: MediaItem? = null

        fun simulateEnded() {
            fakePlaybackState = STATE_ENDED
            fakePositionMs = durationMs
            invalidateState()
        }

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_STOP,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_BACK,
                    COMMAND_SEEK_FORWARD,
                    COMMAND_SET_SPEED_AND_PITCH,
                    COMMAND_SET_VOLUME,
                    COMMAND_GET_VOLUME,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_METADATA,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_SET_VIDEO_SURFACE,
                    COMMAND_RELEASE,
                )
                .build()
            val builder = State.Builder()
                .setAvailableCommands(commands)
                .setPlaybackState(fakePlaybackState)
                .setPlayWhenReady(fakePlayWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setVolume(fakeVolume)
                .setPlaybackParameters(fakePlaybackParameters)
                .setContentPositionMs(PositionSupplier { fakePositionMs })
                .setVideoSize(videoSize)
                .setNewlyRenderedFirstFrame(videoSize != VideoSize.UNKNOWN && fakePlaybackState == STATE_READY)
            val item = currentItem
            if (item != null) {
                val itemData = MediaItemData.Builder("item")
                    .setMediaItem(item)
                    .setMediaMetadata(metadata)
                    .setDurationUs(if (durationMs > 0L) durationMs * 1000L else C.TIME_UNSET)
                    .setIsSeekable(durationMs > 0L)
                    .build()
                builder.setPlaylist(listOf(itemData))
            }
            return builder.build()
        }

        override fun handleSetMediaItems(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<*> {
            currentItem = mediaItems.firstOrNull()
            fakePositionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0L)
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            fakePlaybackState = STATE_READY
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            fakePlayWhenReady = playWhenReady
            if (playWhenReady && fakePlaybackState == STATE_ENDED) {
                fakePlaybackState = STATE_READY
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            fakePositionMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
            if (fakePlaybackState == STATE_ENDED && fakePositionMs < durationMs) {
                fakePlaybackState = STATE_READY
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleSetVolume(volume: Float): ListenableFuture<*> {
            fakeVolume = volume.coerceIn(0f, 1f)
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            fakePlaybackParameters = playbackParameters
            return Futures.immediateVoidFuture()
        }

        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleRelease(): ListenableFuture<*> {
            released = true
            return Futures.immediateVoidFuture()
        }
    }

    /** Minimal [Drawable] + [Animatable] used to verify GIF/WebP animation lifecycle and pause/resume controls. */
    private class FakeAnimatedDrawable : Drawable(), Animatable {
        private var running = false
        var startCount = 0
        var stopCount = 0

        override fun start() {
            running = true
            startCount++
        }

        override fun stop() {
            running = false
            stopCount++
        }

        override fun isRunning(): Boolean = running
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private fun createTestArtworkPng(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(42, 118, 210))
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    @Test
    fun videoPreviewPlaysInPlaceWithFullControlsTouchTargetsAndReplay() {
        val harness = VmHarness().also { h = it }
        harness.file("media/demo.mp4", "video-bytes")
        harness.navigate(harness.pathOf("media"))
        harness.select("media/demo.mp4")

        lateinit var fakePlayer: FakeMedia3Player
        quickLookMediaPlayerFactory = { _, isVideo ->
            assertTrue("Expected isVideo=true for mp4", isVideo)
            FakeMedia3Player(
                durationMs = 125_000L, // 2:05
                videoSize = VideoSize(1920, 1080),
            ).also { fakePlayer = it }
        }

        harness.vm.toggleQuickLook()
        harness.settle()

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) { QuickLook(harness.vm, s) }
        }
        idle(harness)

        // Video stays inside QuickLook with seekbar, duration, and 48dp controls
        assertEquals(harness.pathOf("media/demo.mp4"), harness.s.quickLook)
        rule.onNodeWithText("2:05").assertIsDisplayed()
        rule.onNodeWithContentDescription("Playback position").assertIsDisplayed()

        listOf("Pause", "Rewind 10 seconds", "Fast-forward 10 seconds", "Mute", "Playback speed 1×").forEach { desc ->
            rule.onNodeWithContentDescription(desc)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        // Pause -> Resume
        rule.onNodeWithContentDescription("Pause").performClick()
        idle(harness)
        assertFalse(fakePlayer.fakePlayWhenReady)
        rule.onNodeWithContentDescription("Play").performClick()
        idle(harness)
        assertTrue(fakePlayer.fakePlayWhenReady)

        // Seek +10s then -10s
        rule.onNodeWithContentDescription("Fast-forward 10 seconds").performClick()
        idle(harness)
        assertEquals(10_000L, fakePlayer.fakePositionMs)
        rule.onNodeWithText("0:10").assertIsDisplayed()

        rule.onNodeWithContentDescription("Rewind 10 seconds").performClick()
        idle(harness)
        assertEquals(0L, fakePlayer.fakePositionMs)

        // Speed toggle cycles 1× -> 1.25×
        rule.onNodeWithContentDescription("Playback speed 1×").performClick()
        idle(harness)
        assertEquals(1.25f, fakePlayer.fakePlaybackParameters.speed, 0.001f)
        rule.onNodeWithContentDescription("Playback speed 1.25×").assertIsDisplayed()

        // Mute / Unmute
        rule.onNodeWithContentDescription("Mute").performClick()
        idle(harness)
        assertEquals(0f, fakePlayer.fakeVolume, 0.001f)
        rule.onNodeWithContentDescription("Unmute").performClick()
        idle(harness)
        assertEquals(1f, fakePlayer.fakeVolume, 0.001f)

        // Ended state shows Replay and clicking restarts from 0
        rule.runOnUiThread { fakePlayer.simulateEnded() }
        idle(harness)
        rule.onNodeWithContentDescription("Replay").performClick()
        idle(harness)
        assertEquals(0L, fakePlayer.fakePositionMs)
        assertTrue(fakePlayer.fakePlayWhenReady)

        // Closing Quick Look releases ExoPlayer cleanly
        harness.vm.closeQuickLook()
        idle(harness)
        assertTrue("Media3 player must be released when QuickLook closes", fakePlayer.released)
    }

    @Test
    fun audioPreviewDisplaysEmbeddedAlbumArtMetadataSeekbarAndControls() {
        val harness = VmHarness().also { h = it }
        harness.file("music/01_track.mp3", "audio-bytes")
        harness.file("music/02_plain.mp3", "audio-bytes")
        harness.navigate(harness.pathOf("music"))
        harness.select("music/01_track.mp3")

        val artBytes = createTestArtworkPng()
        val richMeta = MediaMetadata.Builder()
            .setTitle("Midnight Horizon")
            .setArtist("Neon Pulse")
            .setAlbumTitle("Starlight Sessions")
            .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        var useRichMeta = true
        lateinit var fakePlayer: FakeMedia3Player
        quickLookMediaPlayerFactory = { _, isVideo ->
            assertFalse("Expected isVideo=false for mp3", isVideo)
            FakeMedia3Player(
                durationMs = 225_000L, // 3:45
                metadata = if (useRichMeta) richMeta else MediaMetadata.EMPTY,
            ).also { fakePlayer = it }
        }

        harness.vm.toggleQuickLook()
        harness.settle()

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) { QuickLook(harness.vm, s) }
        }
        idle(harness)

        // Wait for background artwork decode on Dispatchers.IO
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription("Album artwork").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Album artwork").assertIsDisplayed()
        rule.onNodeWithText("Midnight Horizon").assertIsDisplayed()
        rule.onNodeWithText("Neon Pulse  ·  Starlight Sessions").assertIsDisplayed()
        rule.onNodeWithText("3:45").assertIsDisplayed()
        rule.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mute").assertIsDisplayed()

        // Step to a track without album art -> falls back cleanly to filename stem & no artwork image node
        useRichMeta = false
        val firstPlayer = fakePlayer
        harness.vm.quickLookMove(1)
        idle(harness)
        assertTrue("Stepping to next audio track must release previous player", firstPlayer.released)
        rule.onNodeWithText("02_plain").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithContentDescription("Album artwork").fetchSemanticsNodes().size)
    }

    @Test
    fun animatedGifPreviewAutoPlaysAndSupportsPauseResumeAndDisposal() {
        val harness = VmHarness().also { h = it }
        harness.file("pics/loop.gif", "GIF89a")
        harness.navigate(harness.pathOf("pics"))
        harness.select("pics/loop.gif")

        val fakeAnim = FakeAnimatedDrawable()
        quickLookAnimatedDrawableFactory = { _, _ -> fakeAnim }

        harness.vm.toggleQuickLook()
        harness.settle()

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) { QuickLook(harness.vm, s) }
        }
        idle(harness)

        rule.waitUntil(5_000) { fakeAnim.isRunning }
        assertTrue("Animated GIF should auto-start when QuickLook opens", fakeAnim.isRunning)

        // Pause button stops animation; Play restarts it
        rule.onNodeWithContentDescription("Pause")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        idle(harness)
        assertFalse("Clicking Pause should stop GIF animation", fakeAnim.isRunning)

        rule.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        idle(harness)
        assertTrue("Clicking Play should resume GIF animation", fakeAnim.isRunning)

        // Closing Quick Look stops animation
        harness.vm.closeQuickLook()
        idle(harness)
        assertFalse("Closing QuickLook must stop GIF animation", fakeAnim.isRunning)
    }

    @Test
    fun quickLookKeyboardShortcutsControlPlaybackAndMenusExposeQuickLook() {
        val harness = VmHarness().also { h = it }
        val clip = harness.file("deck/clip.mp4", "video-bytes")
        harness.file("deck/note.txt", "hello")
        harness.navigate(harness.pathOf("deck"))
        harness.select("deck/clip.mp4")

        lateinit var fakePlayer: FakeMedia3Player
        quickLookMediaPlayerFactory = { _, _ ->
            FakeMedia3Player(durationMs = 90_000L, videoSize = VideoSize(1280, 720)).also { fakePlayer = it }
        }

        // Verify mouse context menu and command palette expose Quick Look
        val entry = harness.s.active.entries.single { it.path == clip.path }
        val menuLabels = fileMenu(harness.vm, harness.s, entry).filterIsInstance<MenuEntry.Item>().map { it.label }
        assertTrue("Context menu should expose Quick Look", "Quick Look" in menuLabels)
        val paletteCmd = paletteCommands(harness.vm, harness.s).firstOrNull { it.name == "Quick Look" }
        assertNotNull("Command palette should expose Quick Look", paletteCmd)

        rule.setContent {
            WayfinderTheme(false) { AppRoot(harness.vm) }
        }
        idle(harness)

        // Open Quick Look via Spacebar
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        idle(harness)
        assertEquals(clip.path, harness.s.quickLook)
        rule.onNodeWithText("K play/pause", substring = true).assertIsDisplayed()

        // K pauses and resumes
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.K) }
        idle(harness)
        assertFalse(fakePlayer.fakePlayWhenReady)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.K) }
        idle(harness)
        assertTrue(fakePlayer.fakePlayWhenReady)

        // L seeks +10s, J seeks -10s, Shift+Right seeks +10s
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.L) }
        idle(harness)
        assertEquals(10_000L, fakePlayer.fakePositionMs)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.J) }
        idle(harness)
        assertEquals(0L, fakePlayer.fakePositionMs)
        rule.onNode(isFocused()).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) }
        }
        idle(harness)
        assertEquals(10_000L, fakePlayer.fakePositionMs)

        // M toggles mute
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.M) }
        idle(harness)
        assertEquals(0f, fakePlayer.fakeVolume, 0.001f)

        // Right arrow still navigates to next item (note.txt) and releases video player
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        idle(harness)
        assertEquals(harness.pathOf("deck/note.txt"), harness.s.quickLook)
        assertTrue(fakePlayer.released)

        // Escape closes Quick Look cleanly
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
        idle(harness)
        assertNull(harness.s.quickLook)

        // F1 shortcuts dialog documents the Quick Look media shortcuts
        harness.vm.showDialog(Dialog.Shortcuts)
        idle(harness)
        rule.onNodeWithText("Play / pause media or GIF").assertExists()
        rule.onNodeWithText("Mute / unmute audio").assertExists()
    }

    @Test
    fun compactViewportAudioPreviewFitsWithoutCrashOrMissingControls() {
        val harness = VmHarness().also { h = it }
        harness.file("tiny/song.mp3", "audio")
        harness.navigate(harness.pathOf("tiny"))
        harness.select("tiny/song.mp3")

        quickLookMediaPlayerFactory = { _, _ ->
            FakeMedia3Player(durationMs = 60_000L)
        }
        harness.vm.toggleQuickLook()
        harness.settle()

        rule.setContent {
            val s by harness.vm.state.collectAsState()
            WayfinderTheme(false) {
                Box(Modifier.size(width = 360.dp, height = 560.dp)) {
                    QuickLook(harness.vm, s)
                }
            }
        }
        idle(harness)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("1:00").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun loaderReadsUriHeadersAndDecodesEmbeddedArtworkBitmapSafely() {
        val harness = VmHarness().also { h = it }
        val gifFile = harness.file("hdr/sample.gif", "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;")
        val uriPrefix = com.droid.wayfinder.data.MediaPreviewLoader.readHeaderPrefix(
            harness.app,
            android.net.Uri.fromFile(gifFile),
        )
        assertTrue(com.droid.wayfinder.data.MediaPreviewSupport.isGifHeader(uriPrefix))

        val pngBytes = createTestArtworkPng()
        val decoded = com.droid.wayfinder.data.MediaPreviewLoader.decodeArtworkBitmap(pngBytes, maxEdgePx = 720)
        assertNotNull(decoded)
        assertEquals(64, decoded!!.width)
        assertEquals(64, decoded.height)
        assertNull(com.droid.wayfinder.data.MediaPreviewLoader.decodeArtworkBitmap("corrupt-image".toByteArray()))
    }
}
