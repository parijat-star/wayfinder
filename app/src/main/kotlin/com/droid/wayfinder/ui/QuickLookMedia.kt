// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.droid.wayfinder.R
import com.droid.wayfinder.data.AudioTrackMetadata
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.MediaPreviewLoader
import com.droid.wayfinder.data.MediaPreviewSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Optional test hook to inject a fake/stub Media3 [Player] in headless Compose UI tests. */
internal var quickLookMediaPlayerFactory: ((context: Context, isVideo: Boolean) -> Player)? = null

/** Optional test hook to inject a decoded [Drawable] (e.g. an [Animatable]) for animated image tests. */
internal var quickLookAnimatedDrawableFactory: ((context: Context, uri: Uri) -> Drawable?)? = null

@Stable
private class Media3PreviewController(
    val player: Player?,
    initialFailed: Boolean,
) {
    var playbackState by mutableIntStateOf(player?.playbackState ?: Player.STATE_IDLE)
    var isPlaying by mutableStateOf(player?.isPlaying == true)
    var playWhenReady by mutableStateOf(player?.playWhenReady == true)
    var positionMs by mutableLongStateOf(player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
    var durationMs by mutableLongStateOf(validDurationMs(player?.duration ?: C.TIME_UNSET))
    var muted by mutableStateOf((player?.volume ?: 1f) <= 0f)
    var speed by mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f)
    var failed by mutableStateOf(initialFailed)
    var mediaMetadata by mutableStateOf(player?.mediaMetadata?.toAudioTrackMetadata() ?: AudioTrackMetadata())

    val isEnded: Boolean
        get() = playbackState == Player.STATE_ENDED

    val isBuffering: Boolean
        get() = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE

    fun syncFromPlayer() {
        val p = player ?: return
        playbackState = p.playbackState
        isPlaying = p.isPlaying
        playWhenReady = p.playWhenReady
        positionMs = p.currentPosition.coerceAtLeast(0L)
        durationMs = validDurationMs(p.duration)
        muted = p.volume <= 0f
        speed = p.playbackParameters.speed
        val nextMeta = p.mediaMetadata.toAudioTrackMetadata()
        if (nextMeta.hasAnyMetadata) {
            val merged = MediaPreviewSupport.mergeAudioMetadata(nextMeta, mediaMetadata)
            if (merged != mediaMetadata) {
                mediaMetadata = merged
            }
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (failed) return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0L)
            p.play()
        } else if (p.isPlaying || p.playWhenReady) {
            p.pause()
        } else {
            p.play()
        }
        syncFromPlayer()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (failed) return
        val dur = validDurationMs(p.duration).takeIf { it > 0L } ?: durationMs
        val target = MediaPreviewSupport.clampSeekPosition(p.currentPosition, deltaMs, dur)
        p.seekTo(target)
        positionMs = target
    }

    fun seekTo(targetMs: Long) {
        val p = player ?: return
        if (failed) return
        val dur = validDurationMs(p.duration).takeIf { it > 0L } ?: durationMs
        val clamped = if (dur > 0L) targetMs.coerceIn(0L, dur) else targetMs.coerceAtLeast(0L)
        p.seekTo(clamped)
        positionMs = clamped
    }

    fun toggleMute() {
        val p = player ?: return
        if (failed) return
        val nextMuted = !muted
        p.volume = if (nextMuted) 0f else 1f
        muted = nextMuted
    }

    fun cycleSpeed() {
        val p = player ?: return
        if (failed) return
        val next = MediaPreviewSupport.nextPlaybackSpeed(speed)
        p.setPlaybackSpeed(next)
        speed = next
    }

    companion object {
        private fun validDurationMs(raw: Long): Long =
            if (raw > 0L && raw != C.TIME_UNSET) raw else 0L
    }
}

private fun MediaMetadata.toAudioTrackMetadata(): AudioTrackMetadata = AudioTrackMetadata(
    title = title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    artist = (artist ?: albumArtist)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    album = albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    artworkBytes = artworkData?.takeIf { it.isNotEmpty() && it.size <= MediaPreviewSupport.MAX_ARTWORK_BYTES },
)

@OptIn(UnstableApi::class)
private fun buildDefaultExoPlayer(context: Context, isVideo: Boolean): ExoPlayer =
    ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (isVideo) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setSeekBackIncrementMs(MediaPreviewSupport.SEEK_STEP_MS)
        .setSeekForwardIncrementMs(MediaPreviewSupport.SEEK_STEP_MS)
        .build()

@Composable
private fun rememberMedia3PreviewController(uri: Uri, isVideo: Boolean): Media3PreviewController {
    val ctx = LocalContext.current
    val controller = remember(uri, isVideo) {
        val created = runCatching {
            quickLookMediaPlayerFactory?.invoke(ctx, isVideo) ?: buildDefaultExoPlayer(ctx, isVideo)
        }.getOrNull()
        Media3PreviewController(player = created, initialFailed = created == null)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        runCatching { controller.player?.pause() }
    }

    DisposableEffect(controller, uri) {
        val p = controller.player
        if (p == null) {
            return@DisposableEffect onDispose {}
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                controller.syncFromPlayer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.syncFromPlayer()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                controller.syncFromPlayer()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                controller.syncFromPlayer()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val parsed = mediaMetadata.toAudioTrackMetadata()
                if (parsed.hasAnyMetadata) {
                    val merged = MediaPreviewSupport.mergeAudioMetadata(parsed, controller.mediaMetadata)
                    if (merged != controller.mediaMetadata) {
                        controller.mediaMetadata = merged
                    }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                controller.speed = playbackParameters.speed
            }

            override fun onVolumeChanged(volume: Float) {
                controller.muted = volume <= 0f
            }

            override fun onPlayerError(error: PlaybackException) {
                controller.failed = true
                controller.isPlaying = false
                controller.playWhenReady = false
            }
        }
        p.addListener(listener)
        runCatching {
            p.setMediaItem(MediaItem.fromUri(uri))
            p.playWhenReady = true
            p.prepare()
            controller.syncFromPlayer()
        }.onFailure {
            controller.failed = true
        }
        onDispose {
            runCatching { p.removeListener(listener) }
            runCatching { p.release() }
        }
    }

    LaunchedEffect(controller, controller.isPlaying, controller.playbackState, controller.failed) {
        if (controller.player == null || controller.failed) return@LaunchedEffect
        controller.syncFromPlayer()
        while (controller.isPlaying && !controller.failed) {
            delay(200L)
            controller.syncFromPlayer()
        }
    }

    return controller
}

@Composable
private fun RegisterQuickLookMediaActions(actions: QuickLookMediaActions?) {
    val focusCoord = LocalFocusCoordinator.current
    DisposableEffect(focusCoord, actions) {
        focusCoord.activeQuickLookMediaActions = actions
        onDispose {
            if (focusCoord.activeQuickLookMediaActions === actions) {
                focusCoord.activeQuickLookMediaActions = null
            }
        }
    }
}

/** In-place Media3 ExoPlayer video preview using official [ContentFrame] (`SURFACE_TYPE_TEXTURE_VIEW`). */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerPreview(vm: BrowserViewModel, e: FileEntry, uri: Uri) {
    val controller = rememberMedia3PreviewController(uri = uri, isVideo = true)
    var sliderFocused by remember(uri) { mutableStateOf(false) }
    val lastBodyReq = rememberQuickLookBodyLastRequester()

    val mediaActions = remember(controller, controller.failed) {
        if (controller.failed || controller.player == null) {
            null
        } else {
            QuickLookMediaActions(
                mode = QuickLookMediaMode.Media,
                togglePlayPause = controller::togglePlayPause,
                seekBy = controller::seekBy,
                toggleMute = controller::toggleMute,
                isSliderFocused = { sliderFocused },
            )
        }
    }
    RegisterQuickLookMediaActions(mediaActions)

    if (controller.failed || controller.player == null) {
        MediaErrorFallback(
            entry = e,
            message = stringResource(R.string.quicklook_cant_play_video),
            lastFocusRequester = lastBodyReq,
            onOpenExternal = { vm.closeQuickLook(); vm.openSelection() },
        )
        return
    }

    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ContentFrame(
            player = controller.player,
            modifier = Modifier
                .fillMaxSize()
                .clickable(noRipple, indication = null) { controller.togglePlayPause() },
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            shutter = {
                Box(Modifier.fillMaxSize().background(Color.Black))
            },
        )

        if (controller.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.88f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MediaSeekBar(
                mediaKey = uri,
                positionMs = controller.positionMs,
                durationMs = controller.durationMs,
                onSeekTo = controller::seekTo,
                onSliderFocusChanged = { sliderFocused = it },
                darkOverlay = true,
            )
            MediaTransportControls(
                controller = controller,
                lastFocusRequester = lastBodyReq,
                darkOverlay = true,
            )
        }
    }
}

/** In-place Media3 ExoPlayer audio preview with embedded album art, track tags, seekbar, and transport controls. */
@Composable
internal fun AudioPlayerPreview(vm: BrowserViewModel, e: FileEntry, uri: Uri) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val controller = rememberMedia3PreviewController(uri = uri, isVideo = false)
    var sliderFocused by remember(uri) { mutableStateOf(false) }
    val lastBodyReq = rememberQuickLookBodyLastRequester()

    val mediaActions = remember(controller, controller.failed) {
        if (controller.failed || controller.player == null) {
            null
        } else {
            QuickLookMediaActions(
                mode = QuickLookMediaMode.Media,
                togglePlayPause = controller::togglePlayPause,
                seekBy = controller::seekBy,
                toggleMute = controller::toggleMute,
                isSliderFocused = { sliderFocused },
            )
        }
    }
    RegisterQuickLookMediaActions(mediaActions)

    val fallbackMeta by produceState(AudioTrackMetadata(), uri) {
        value = withContext(Dispatchers.IO) { MediaPreviewLoader.extractFallbackAudioMetadata(ctx, uri) }
    }
    val mergedMeta = remember(controller.mediaMetadata, fallbackMeta) {
        MediaPreviewSupport.mergeAudioMetadata(controller.mediaMetadata, fallbackMeta)
    }
    val artworkKey = remember(mergedMeta) { ArtworkBytesKey(mergedMeta.artworkBytes) }
    val artworkBitmap by produceState<ImageBitmap?>(null, artworkKey) {
        value = withContext(Dispatchers.IO) {
            MediaPreviewLoader.decodeArtworkBitmap(artworkKey.bytes)?.asImageBitmap()
        }
    }

    val displayTitle = mergedMeta.title ?: e.name.substringBeforeLast('.').ifEmpty { e.name }
    val displaySubtitle = mergedMeta.subtitle

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 420.dp || maxWidth < 420.dp
        val artSize = when {
            maxHeight < 340.dp -> 96.dp
            compact -> 136.dp
            else -> 212.dp
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 16.dp else 32.dp, vertical = if (compact) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        ) {
            Surface(
                modifier = Modifier.size(artSize),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                val art = artworkBitmap
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = stringResource(R.string.quicklook_album_art),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(artSize * 0.44f),
                            tint = e.tint(),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (displaySubtitle != null) {
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (controller.failed || controller.player == null) {
                Text(
                    text = stringResource(R.string.quicklook_cant_play_audio),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(
                    onClick = { vm.closeQuickLook(); vm.openSelection() },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .focusRequester(lastBodyReq)
                        .focusRing(RoundedCornerShape(20.dp)),
                ) {
                    Text(stringResource(R.string.action_open_external_player), maxLines = 1, softWrap = false)
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (controller.isBuffering && controller.durationMs == 0L) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    MediaSeekBar(
                        mediaKey = uri,
                        positionMs = controller.positionMs,
                        durationMs = controller.durationMs,
                        onSeekTo = controller::seekTo,
                        onSliderFocusChanged = { sliderFocused = it },
                        darkOverlay = false,
                    )
                    MediaTransportControls(
                        controller = controller,
                        lastFocusRequester = lastBodyReq,
                        darkOverlay = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaErrorFallback(
    entry: FileEntry,
    message: String,
    lastFocusRequester: FocusRequester,
    onOpenExternal: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(entry.icon(), null, Modifier.size(88.dp), tint = entry.tint())
        Text(message, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        FilledTonalButton(
            onClick = onOpenExternal,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .focusRequester(lastFocusRequester)
                .focusRing(RoundedCornerShape(20.dp)),
        ) {
            Text(stringResource(R.string.action_open_external_player), maxLines = 1, softWrap = false)
        }
    }
}

private class ArtworkBytesKey(val bytes: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ArtworkBytesKey && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes?.contentHashCode() ?: 0
}

@Composable
private fun MediaSeekBar(
    mediaKey: Any,
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    onSliderFocusChanged: (Boolean) -> Unit,
    darkOverlay: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    var scrubFraction by remember(mediaKey) { mutableStateOf<Float?>(null) }
    val activeFraction = scrubFraction ?: MediaPreviewSupport.positionToFraction(positionMs, durationMs)
    val displayedPositionMs = scrubFraction?.let { MediaPreviewSupport.fractionToPositionMs(it, durationMs) } ?: positionMs
    val seekable = durationMs > 0L
    val sliderLabel = stringResource(R.string.action_playback_position)
    val timeColor = if (darkOverlay) Color.White else cs.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = activeFraction,
            onValueChange = { frac -> if (seekable) scrubFraction = frac.coerceIn(0f, 1f) },
            onValueChangeFinished = {
                val target = scrubFraction?.let { MediaPreviewSupport.fractionToPositionMs(it, durationMs) }
                scrubFraction = null
                if (target != null) onSeekTo(target)
            },
            enabled = seekable,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onSliderFocusChanged(it.isFocused) }
                .focusRing(RoundedCornerShape(12.dp))
                .semantics { contentDescription = sliderLabel },
            colors = if (darkOverlay) {
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                    disabledThumbColor = Color.White.copy(alpha = 0.5f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                )
            } else {
                SliderDefaults.colors()
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = MediaPreviewSupport.formatPlaybackTime(displayedPositionMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = timeColor,
            )
            Text(
                text = MediaPreviewSupport.formatPlaybackTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (darkOverlay) Color.White.copy(alpha = 0.8f) else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaTransportControls(
    controller: Media3PreviewController,
    lastFocusRequester: FocusRequester,
    darkOverlay: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val iconTint = if (darkOverlay) Color.White else cs.onSurface
    val playIcon = when {
        controller.isEnded -> Icons.Outlined.Replay
        controller.isPlaying || controller.playWhenReady -> Icons.Outlined.Pause
        else -> Icons.Outlined.PlayArrow
    }
    val playDesc = stringResource(
        when {
            controller.isEnded -> R.string.action_replay
            controller.isPlaying || controller.playWhenReady -> R.string.action_pause
            else -> R.string.action_play
        },
    )
    val playTooltip = stringResource(
        when {
            controller.isEnded -> R.string.tooltip_media_replay
            controller.isPlaying || controller.playWhenReady -> R.string.tooltip_media_pause
            else -> R.string.tooltip_media_play
        },
    )
    val seekBackDesc = stringResource(R.string.action_seek_back_10)
    val seekBackTooltip = stringResource(R.string.tooltip_media_seek_back)
    val seekForwardDesc = stringResource(R.string.action_seek_forward_10)
    val seekForwardTooltip = stringResource(R.string.tooltip_media_seek_forward)
    val muteDesc = stringResource(if (controller.muted) R.string.action_unmute else R.string.action_mute)
    val muteTooltip = stringResource(if (controller.muted) R.string.tooltip_media_unmute else R.string.tooltip_media_mute)
    val speedLabel = MediaPreviewSupport.formatPlaybackSpeed(controller.speed)
    val speedDesc = stringResource(R.string.action_playback_speed, speedLabel)
    val speedTooltip = stringResource(R.string.tooltip_media_speed)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (darkOverlay) Arrangement.Start else Arrangement.Center,
    ) {
        if (!darkOverlay) {
            ActionTooltip(speedTooltip) {
                TextButton(
                    onClick = controller::cycleSpeed,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .focusRing(CircleShape)
                        .semantics { contentDescription = speedDesc },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(speedLabel, style = MaterialTheme.typography.labelLarge, color = iconTint, maxLines = 1, softWrap = false)
                }
            }
        }

        ActionTooltip(seekBackTooltip) {
            IconButton(
                onClick = { controller.seekBy(-MediaPreviewSupport.SEEK_STEP_MS) },
                modifier = Modifier.size(48.dp).focusRing(CircleShape),
            ) {
                Icon(Icons.Outlined.Replay10, seekBackDesc, tint = iconTint)
            }
        }

        ActionTooltip(playTooltip) {
            FilledTonalIconButton(
                onClick = controller::togglePlayPause,
                modifier = Modifier.size(if (darkOverlay) 48.dp else 56.dp).focusRing(CircleShape),
                shape = CircleShape,
                colors = if (darkOverlay) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
            ) {
                Icon(playIcon, playDesc, Modifier.size(if (darkOverlay) 26.dp else 30.dp))
            }
        }

        ActionTooltip(seekForwardTooltip) {
            IconButton(
                onClick = { controller.seekBy(MediaPreviewSupport.SEEK_STEP_MS) },
                modifier = Modifier.size(48.dp).focusRing(CircleShape),
            ) {
                Icon(Icons.Outlined.Forward10, seekForwardDesc, tint = iconTint)
            }
        }

        if (darkOverlay) {
            Spacer(Modifier.weight(1f))
            ActionTooltip(speedTooltip) {
                TextButton(
                    onClick = controller::cycleSpeed,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .focusRing(CircleShape)
                        .semantics { contentDescription = speedDesc },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(speedLabel, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, softWrap = false)
                }
            }
        }

        ActionTooltip(muteTooltip) {
            IconButton(
                onClick = controller::toggleMute,
                modifier = Modifier.size(48.dp).focusRequester(lastFocusRequester).focusRing(CircleShape),
            ) {
                Icon(
                    imageVector = if (controller.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = muteDesc,
                    tint = iconTint,
                )
            }
        }
    }
}

private sealed interface AnimatedDecodeState {
    data object Loading : AnimatedDecodeState
    data class Animated(val drawable: Drawable) : AnimatedDecodeState
    data object StaticFallback : AnimatedDecodeState
}

/**
 * Animated GIF & animated WebP preview powered by Android's native [ImageDecoder] and
 * [android.graphics.drawable.AnimatedImageDrawable] (zero third-party libraries).
 * Falls back transparently to static Coil rendering for single-frame or unsupported files.
 */
@Composable
internal fun AnimatedImagePreview(
    e: FileEntry,
    uri: Uri,
    fallbackModel: Any = uri,
) {
    val ctx = LocalContext.current
    val decodeState by produceState<AnimatedDecodeState>(AnimatedDecodeState.Loading, uri, e.modified) {
        value = withContext(Dispatchers.IO) {
            val injected = quickLookAnimatedDrawableFactory?.invoke(ctx, uri)
            if (injected != null) {
                return@withContext if (injected is Animatable) {
                    AnimatedDecodeState.Animated(injected)
                } else {
                    AnimatedDecodeState.StaticFallback
                }
            }
            val header = MediaPreviewLoader.readHeaderPrefix(ctx, uri)
            if (!MediaPreviewSupport.isAnimatedImageHeader(header)) {
                return@withContext AnimatedDecodeState.StaticFallback
            }
            val source = runCatching {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    ImageDecoder.createSource(File(uri.path ?: return@runCatching null))
                } else {
                    ImageDecoder.createSource(ctx.contentResolver, uri)
                }
            }.getOrNull() ?: return@withContext AnimatedDecodeState.StaticFallback
            val decoded = MediaPreviewLoader.decodeAnimatedDrawable(source)
            if (decoded is Animatable) {
                AnimatedDecodeState.Animated(decoded)
            } else {
                AnimatedDecodeState.StaticFallback
            }
        }
    }

    when (val state = decodeState) {
        AnimatedDecodeState.Loading -> CircularProgressIndicator()
        AnimatedDecodeState.StaticFallback -> {
            AsyncImage(
                remember(fallbackModel, e.modified) { ImageRequest.Builder(ctx).data(fallbackModel).size(2048).build() },
                e.name,
                Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit,
            )
        }
        is AnimatedDecodeState.Animated -> {
            val drawable = state.drawable
            val animatable = drawable as Animatable
            var playing by remember(drawable) { mutableStateOf(true) }
            val lastBodyReq = rememberQuickLookBodyLastRequester()

            fun toggle() {
                if (playing) {
                    animatable.stop()
                    playing = false
                } else {
                    animatable.start()
                    playing = true
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
                if (playing) {
                    animatable.stop()
                    playing = false
                }
            }

            DisposableEffect(drawable) {
                animatable.start()
                onDispose { runCatching { animatable.stop() } }
            }

            val mediaActions = remember(drawable) {
                QuickLookMediaActions(
                    mode = QuickLookMediaMode.AnimatedImage,
                    togglePlayPause = ::toggle,
                )
            }
            RegisterQuickLookMediaActions(mediaActions)

            val noRipple = remember { MutableInteractionSource() }
            val btnDesc = stringResource(if (playing) R.string.action_pause else R.string.action_play)
            val btnTooltip = stringResource(if (playing) R.string.tooltip_media_pause else R.string.tooltip_media_play)

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            contentDescription = e.name
                            setImageDrawable(drawable)
                        }
                    },
                    update = { view ->
                        if (view.drawable !== drawable) {
                            view.setImageDrawable(drawable)
                        }
                        view.contentDescription = e.name
                    },
                    onRelease = { view ->
                        view.setImageDrawable(null)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clickable(noRipple, indication = null) { toggle() },
                )

                Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    ActionTooltip(btnTooltip) {
                        FilledTonalIconButton(
                            onClick = ::toggle,
                            modifier = Modifier
                                .size(48.dp)
                                .focusRequester(lastBodyReq)
                                .focusRing(CircleShape),
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = btnDesc,
                            )
                        }
                    }
                }
            }
        }
    }
}
