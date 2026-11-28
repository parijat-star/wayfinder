// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.Display
import android.view.DragEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droid.wayfinder.IntentGuard
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.R
import com.droid.wayfinder.data.ConnectedDisplays
import com.droid.wayfinder.data.DisplayTarget
import com.droid.wayfinder.data.EXTRA_TARGET_DISPLAY_ID
import com.droid.wayfinder.data.EXTRA_TEAR_SESSION_ID
import com.droid.wayfinder.data.MIME_WAYFINDER_TAB
import com.droid.wayfinder.data.TabDragSession
import com.droid.wayfinder.data.TabStripDragMath
import com.droid.wayfinder.data.TabTransferRegistry
import kotlin.math.roundToInt

internal const val DETACHED_TAB_GHOST_TAG = "wayfinder_detached_tab_ghost"
internal const val MERGE_PREVIEW_TAB_TAG = "wayfinder_merge_preview_tab"

/** Attached to [View.startDragAndDrop] so receiving Wayfinder windows verify in-process tab drag origin. */
internal data class WayfinderTabDragLocalState(
    val sessionId: String,
    val sourceWindowId: Int,
)

/** Live hover state when a torn tab hovers over a window's tab strip. */
internal data class TabMergePreview(
    val sessionId: String,
    val title: String,
    val path: String,
    val insertionIndex: Int,
)

/**
 * Per-window coordinator connecting [AppRoot]'s full-window [DragAndDropTarget] to [TitleBar]'s
 * tab-strip geometry and live [TabMergePreview] slot state.
 */
internal class TabStripDropController {
    var mergePreview by mutableStateOf<TabMergePreview?>(null)
    var stripContentOriginInRoot by mutableStateOf(Offset.Zero)
    var stripBottomInRoot by mutableFloatStateOf(0f)
    var tabWidthPx by mutableFloatStateOf(160f)
    var visibleTabCount by mutableIntStateOf(1)
    var isRtl by mutableStateOf(false)
    var windowScreenBounds by mutableStateOf(Rect.Zero)

    fun computeInsertionIndex(rootX: Float): Int =
        TabStripDragMath.mergeInsertionIndex(
            pointerContentX = rootX - stripContentOriginInRoot.x,
            tabWidthPx = tabWidthPx,
            visibleTabCount = visibleTabCount,
            isRtl = isRtl,
        )

    fun updatePreview(rootX: Float, session: TabDragSession) {
        mergePreview = TabMergePreview(
            sessionId = session.sessionId,
            title = session.tabTitle,
            path = session.snapshot.path,
            insertionIndex = computeInsertionIndex(rootX),
        )
    }

    fun clearPreview() {
        mergePreview = null
    }
}

internal val LocalTabStripDropController = staticCompositionLocalOf { TabStripDropController() }

/** Visual state of an in-process fallback tab drag (used in Robolectric tests & platforms without global drag). */
internal data class FallbackTabDragVisual(
    val sessionId: String,
    val sourceWindowId: Int,
    val title: String,
    val pointerInSourceRoot: Offset,
    val hoveringAnyStrip: Boolean,
)

/**
 * Coordinates cross-window hit testing when [startSystemTabDrag] falls back in-process (e.g. Robolectric UI tests).
 * Matches Android cross-window drag semantics: re-docking into the source window requires hovering its tab strip,
 * while dragging onto any existing non-source Wayfinder window previews and merges into that window's tab strip.
 */
internal object InProcessTabDragBus {
    class StripTarget(
        val windowId: Int,
        var stripScreenBounds: Rect,
        var windowScreenBounds: Rect = Rect.Zero,
        var onHoverScreen: (Offset, TabDragSession) -> Unit,
        var onExit: () -> Unit,
        var onDropScreen: (Offset, TabDragSession) -> Boolean,
    ) {
        fun hits(screenPos: Offset, session: TabDragSession): Boolean =
            if (windowId == session.sourceWindowId) {
                stripScreenBounds.contains(screenPos)
            } else {
                val wb = if (windowScreenBounds.isEmpty) stripScreenBounds else windowScreenBounds
                wb.contains(screenPos)
            }
    }

    private val targets = LinkedHashMap<Int, StripTarget>()
    private var hoveredWindowId: Int? = null
    var activeVisual by mutableStateOf<FallbackTabDragVisual?>(null)
        private set

    fun register(target: StripTarget) {
        targets[target.windowId] = target
    }

    fun unregister(windowId: Int) {
        if (hoveredWindowId == windowId) hoveredWindowId = null
        targets.remove(windowId)
    }

    fun onMove(session: TabDragSession, screenPos: Offset, sourceRootPos: Offset) {
        val hit = targets.values.firstOrNull { it.hits(screenPos, session) }
        if (hoveredWindowId != null && hoveredWindowId != hit?.windowId) {
            targets[hoveredWindowId]?.onExit()
        }
        hoveredWindowId = hit?.windowId
        hit?.onHoverScreen(screenPos, session)
        activeVisual = FallbackTabDragVisual(
            sessionId = session.sessionId,
            sourceWindowId = session.sourceWindowId,
            title = session.tabTitle,
            pointerInSourceRoot = sourceRootPos,
            hoveringAnyStrip = hit != null,
        )
    }

    fun onRelease(
        session: TabDragSession,
        screenPos: Offset,
        onTearToNewWindow: (dropScreenX: Int, dropScreenY: Int) -> Unit,
    ) {
        val hit = targets.values.firstOrNull { it.hits(screenPos, session) }
        targets.values.forEach { it.onExit() }
        hoveredWindowId = null
        activeVisual = null
        if (hit != null && hit.onDropScreen(screenPos, session)) {
            return
        }
        if (session.canSpawnStandaloneWindow && TabTransferRegistry.markAwaitingNewWindow(session.sessionId)) {
            onTearToNewWindow(screenPos.x.roundToInt(), screenPos.y.roundToInt())
        } else {
            TabTransferRegistry.cancelDragSession(session.sessionId)
        }
    }

    fun onCancel(sessionId: String) {
        targets.values.forEach { it.onExit() }
        hoveredWindowId = null
        activeVisual = null
        TabTransferRegistry.cancelDragSession(sessionId)
    }
}

/**
 * Chrome-style mini-window [View.DragShadowBuilder] rendered when a tab detaches from the tab strip.
 */
internal class WayfinderTabDragShadowBuilder(
    context: Context,
    rawTitle: String,
) : View.DragShadowBuilder() {
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val title = rawTitle.ifBlank { "Tab" }.let { if (it.length > 22) it.take(20) + "…" else it }

    val shadowWidthPx: Int = (224f * density).roundToInt()
    val shadowHeightPx: Int = (128f * density).roundToInt()
    private val tabEarWidthPx: Float = 148f * density
    private val tabEarHeightPx: Float = 32f * density

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2202329.toInt()
        style = Paint.Style.FILL
    }
    private val bodySurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEB2A2E37.toInt()
        style = Paint.Style.FILL
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38FFFFFF
        style = Paint.Style.FILL
    }
    private val accentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AB4F8.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F6FB.toInt()
        textSize = 12.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(shadowWidthPx, shadowHeightPx)
        outShadowTouchPoint.set(
            (tabEarWidthPx * 0.45f).roundToInt().coerceIn(16, shadowWidthPx - 16),
            (tabEarHeightPx * 0.55f).roundToInt().coerceIn(8, shadowHeightPx - 8),
        )
    }

    override fun onDrawShadow(canvas: Canvas) {
        val pad = 3f * density
        val r = 11f * density
        val bodyTop = pad + tabEarHeightPx

        // Unified tab ear + window body silhouette (no internal overlap seam)
        val earPath = Path().apply {
            addRoundRect(RectF(pad, pad, pad + tabEarWidthPx, bodyTop + r), r, r, Path.Direction.CW)
        }
        val bodyPath = Path().apply {
            addRoundRect(RectF(pad, bodyTop, shadowWidthPx - pad, shadowHeightPx - pad), r, r, Path.Direction.CW)
        }
        val silhouettePath = Path().apply {
            op(earPath, bodyPath, Path.Op.UNION)
        }
        canvas.drawPath(silhouettePath, cardFillPaint)

        val innerBody = RectF(
            pad + 6f * density,
            bodyTop + 26f * density,
            shadowWidthPx - pad - 6f * density,
            shadowHeightPx - pad - 6f * density,
        )
        canvas.drawRoundRect(innerBody, 8f * density, 8f * density, bodySurfacePaint)

        // Address bar & miniature file list lines
        val toolbarPill = RectF(
            pad + 10f * density,
            bodyTop + 8f * density,
            shadowWidthPx - pad - 10f * density,
            bodyTop + 20f * density,
        )
        canvas.drawRoundRect(toolbarPill, 6f * density, 6f * density, skeletonPaint)

        for (row in 0 until 3) {
            val rowTop = innerBody.top + (10f + row * 16f) * density
            if (rowTop + 8f * density <= innerBody.bottom - 4f * density) {
                val lineW = (if (row == 2) 0.52f else 0.78f) * innerBody.width()
                canvas.drawRoundRect(
                    RectF(innerBody.left + 10f * density, rowTop, innerBody.left + 10f * density + lineW, rowTop + 7f * density),
                    3.5f * density,
                    3.5f * density,
                    skeletonPaint,
                )
            }
        }

        // Folder icon accent + tab title inside tab ear
        val iconCx = pad + 16f * density
        val iconCy = pad + tabEarHeightPx * 0.54f
        canvas.drawCircle(iconCx, iconCy, 5f * density, accentDotPaint)
        val baseline = iconCy - (titlePaint.descent() + titlePaint.ascent()) / 2f
        canvas.drawText(title, pad + 27f * density, baseline, titlePaint)

        canvas.drawPath(silhouettePath, borderPaint)
    }
}

internal fun buildTransferredTabWindowIntent(
    context: Context,
    sessionId: String,
    fallbackPath: String,
    promoteToPrimary: Boolean,
    targetDisplayId: Int? = null,
): Intent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, !promoteToPrimary)
        .putExtra(BrowserViewModel.EXTRA_PATH, fallbackPath)
        .putExtra(EXTRA_TEAR_SESSION_ID, sessionId)
    if (targetDisplayId != null) {
        intent.putExtra(EXTRA_TARGET_DISPLAY_ID, targetDisplayId)
    }
    return IntentGuard.sign(context, intent)
}

internal fun sessionTabMimeType(sessionId: String): String = "$MIME_WAYFINDER_TAB.$sessionId"

internal fun buildTabClipDescription(
    title: String,
    sessionId: String,
    includeIntentMime: Boolean = false,
): ClipDescription {
    val mimes = if (includeIntentMime) {
        arrayOf(MIME_WAYFINDER_TAB, sessionTabMimeType(sessionId), ClipDescription.MIMETYPE_TEXT_INTENT)
    } else {
        arrayOf(MIME_WAYFINDER_TAB, sessionTabMimeType(sessionId))
    }
    return ClipDescription(title, mimes).apply {
        extras = PersistableBundle().apply {
            putString(EXTRA_TEAR_SESSION_ID, sessionId)
        }
    }
}

/**
 * Resolves the in-process [TabDragSession.sessionId] from a [DragEvent] across both same-window and
 * cross-window/cross-activity drags (where Android sets [DragEvent.getLocalState] to `null`).
 */
internal fun resolveTabDragSessionId(de: DragEvent): String? {
    (de.localState as? WayfinderTabDragLocalState)?.sessionId?.let {
        TabTransferRegistry.sanitizeSessionId(it)?.let { valid -> return valid }
    }
    val desc = de.clipDescription
    if (desc != null) {
        val extraSid = runCatching { desc.extras?.getString(EXTRA_TEAR_SESSION_ID) }.getOrNull()
        TabTransferRegistry.sanitizeSessionId(extraSid)?.let { return it }

        val prefix = "$MIME_WAYFINDER_TAB."
        var hasWayfinderTabMime = false
        for (i in 0 until desc.mimeTypeCount) {
            val mime = desc.getMimeType(i) ?: continue
            if (mime == MIME_WAYFINDER_TAB) hasWayfinderTabMime = true
            if (mime.startsWith(prefix)) {
                TabTransferRegistry.sanitizeSessionId(mime.removePrefix(prefix))?.let { return it }
            }
        }
        if (hasWayfinderTabMime) {
            TabTransferRegistry.soleActiveSession()?.sessionId?.let { return it }
        }
    }
    val clip = de.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) {
            val intentSid = runCatching { clip.getItemAt(i)?.intent?.getStringExtra(EXTRA_TEAR_SESSION_ID) }.getOrNull()
            TabTransferRegistry.sanitizeSessionId(intentSid)?.let { return it }
        }
    }
    return null
}

@RequiresApi(35)
private object Api35TabDragStarter {
    fun buildClipAndFlags(
        context: Context,
        title: String,
        launchIntent: Intent,
        sessionId: String,
        allowUnhandledSpawn: Boolean,
    ): Pair<ClipData, Int> {
        if (!allowUnhandledSpawn) {
            val clip = ClipData(
                buildTabClipDescription(title, sessionId, includeIntentMime = false),
                ClipData.Item(title),
            )
            return clip to View.DRAG_FLAG_GLOBAL
        }
        val pi = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val item = ClipData.Item.Builder()
            .setText(title)
            .setIntent(launchIntent)
            .setIntentSender(pi.intentSender)
            .build()
        val desc = buildTabClipDescription(title, sessionId, includeIntentMime = true)
        val flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG
        return ClipData(desc, item) to flags
    }
}

private val isRobolectricRuntime: Boolean by lazy(LazyThreadSafetyMode.NONE) {
    Build.FINGERPRINT == "robolectric" ||
        runCatching {
            Class.forName("org.robolectric.Robolectric")
            true
        }.getOrDefault(false)
}

/**
 * Starts a cross-window / cross-display system drag for [session].
 * Returns `false` in Robolectric JVM tests so [InProcessTabDragBus] drives the drag seamlessly.
 */
internal fun startSystemTabDrag(
    view: View,
    windowId: Int,
    session: TabDragSession,
): Boolean {
    if (isRobolectricRuntime) return false
    val ctx = view.context
    val launchIntent = buildTransferredTabWindowIntent(
        context = ctx,
        sessionId = session.sessionId,
        fallbackPath = session.snapshot.path,
        promoteToPrimary = session.snapshot.promoteTargetToPrimary,
    )
    val (clip, flags) = if (Build.VERSION.SDK_INT >= 35) {
        Api35TabDragStarter.buildClipAndFlags(
            context = ctx,
            title = session.tabTitle,
            launchIntent = launchIntent,
            sessionId = session.sessionId,
            allowUnhandledSpawn = session.canSpawnStandaloneWindow,
        )
    } else {
        ClipData(
            buildTabClipDescription(session.tabTitle, session.sessionId, includeIntentMime = false),
            ClipData.Item(session.tabTitle, launchIntent, null),
        ) to View.DRAG_FLAG_GLOBAL
    }
    val shadow = WayfinderTabDragShadowBuilder(ctx, session.tabTitle)
    val localState = WayfinderTabDragLocalState(sessionId = session.sessionId, sourceWindowId = windowId)
    return runCatching {
        view.startDragAndDrop(clip, shadow, localState, flags)
    }.getOrDefault(false)
}

/**
 * Observes connected display additions, removals, and state changes so menus and tear-off policies react live.
 */
@Composable
internal fun rememberConnectedDisplays(currentWindowView: View): Pair<Int, List<DisplayTarget>> {
    val ctx = LocalContext.current
    var displayEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(ctx) {
        val dm = runCatching { ctx.getSystemService(DisplayManager::class.java) }.getOrNull()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { displayEpoch++ }
            override fun onDisplayRemoved(displayId: Int) { displayEpoch++ }
            override fun onDisplayChanged(displayId: Int) { displayEpoch++ }
        }
        runCatching { dm?.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }
        onDispose {
            runCatching { dm?.unregisterDisplayListener(listener) }
        }
    }
    val currentDisplayId = runCatching { currentWindowView.display?.displayId }.getOrNull()
        ?: runCatching { ctx.findActivity()?.display?.displayId }.getOrNull()
        ?: Display.DEFAULT_DISPLAY
    val displays = remember(ctx, displayEpoch, currentDisplayId) {
        ConnectedDisplays.discoverEligibleDisplays(ctx)
    }
    return currentDisplayId to displays
}

/**
 * Registers this [AppRoot] window in [TabTransferRegistry] and handles both:
 *  1. Incoming cross-window tab merges into this window (using [ClipDescription] + [TabTransferRegistry]
 *     since Android sets [DragEvent.getLocalState] to `null` across separate `MainActivity` windows), and
 *  2. Outbound tab tear-off completion, source-strip re-docking, and Escape cancellation.
 */
@Composable
internal fun Modifier.tabDragSessionHost(
    vm: BrowserViewModel,
    s: AppState,
    windowId: Int,
    currentDisplayId: Int,
    stripController: TabStripDropController,
): Modifier {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val currentVm by rememberUpdatedState(vm)
    val currentDisplay by rememberUpdatedState(currentDisplayId)
    val currentIsPicker by rememberUpdatedState(s.picker != null)

    val activeTitle = s.titleOf(s.mainTab.path)
    val tabCount = s.tabs.size
    val isPicker = s.picker != null
    val isPrimary = !s.secondaryWindow && s.picker == null

    DisposableEffect(windowId, ctx) {
        TabTransferRegistry.registerWindow(
            windowId = windowId,
            displayId = currentDisplay,
            title = activeTitle,
            tabCount = tabCount,
            isPicker = isPicker,
            isPrimaryWindow = isPrimary,
            onMergeTab = { transferred, insertIndex ->
                currentVm.adoptTransferredTab(transferred, insertIndex)
            },
            onBringToFront = {
                val act = ctx.findActivity()
                if (act != null) {
                    runCatching {
                        val am = ctx.getSystemService(ActivityManager::class.java)
                        am?.moveTaskToFront(act.taskId, 0)
                    }
                }
            },
        )
        onDispose {
            TabTransferRegistry.unregisterWindow(windowId)
        }
    }

    DisposableEffect(windowId, currentDisplayId, activeTitle, tabCount, isPicker, isPrimary) {
        TabTransferRegistry.updateWindow(
            windowId = windowId,
            displayId = currentDisplayId,
            title = activeTitle,
            tabCount = tabCount,
            isPicker = isPicker,
            isPrimaryWindow = isPrimary,
        )
        onDispose { }
    }

    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var escapeCancelled by remember { mutableStateOf(false) }

    val target = remember(windowId, stripController, density) {
        object : DragAndDropTarget {
            private var trackedSessionId: String? = null
            private var isSourceWindow: Boolean = false
            private var bodyDropLocalPos: Offset? = null

            private fun activeSessionFor(event: DragAndDropEvent): TabDragSession? {
                val de = runCatching { event.toAndroidDragEvent() }.getOrNull() ?: return null
                val sid = trackedSessionId ?: resolveTabDragSessionId(de)
                return TabTransferRegistry.activeSession(sid)
            }

            private fun isOverSourceStrip(yPx: Float): Boolean {
                val minStripPx = with(density) { 48.dp.toPx() }
                val tolerancePx = with(density) { 10.dp.toPx() }
                val bottom = stripController.stripBottomInRoot.coerceAtLeast(minStripPx)
                return yPx <= bottom + tolerancePx
            }

            private fun updateHover(event: DragAndDropEvent) {
                val session = activeSessionFor(event) ?: run {
                    stripController.clearPreview()
                    return
                }
                val de = event.toAndroidDragEvent()
                if (session.sourceWindowId == windowId) {
                    if (!currentIsPicker && isOverSourceStrip(de.y)) {
                        stripController.updatePreview(de.x, session)
                    } else {
                        stripController.clearPreview()
                    }
                } else if (!currentIsPicker) {
                    stripController.updatePreview(de.x, session)
                }
            }

            override fun onStarted(event: DragAndDropEvent) {
                val de = runCatching { event.toAndroidDragEvent() }.getOrNull() ?: return
                val sid = resolveTabDragSessionId(de) ?: return
                val session = TabTransferRegistry.activeSession(sid) ?: return
                trackedSessionId = session.sessionId
                isSourceWindow = session.sourceWindowId == windowId
                if (isSourceWindow) {
                    activeSessionId = session.sessionId
                    escapeCancelled = false
                    bodyDropLocalPos = null
                }
            }

            override fun onEntered(event: DragAndDropEvent) {
                updateHover(event)
            }

            override fun onMoved(event: DragAndDropEvent) {
                updateHover(event)
            }

            override fun onExited(event: DragAndDropEvent) {
                stripController.clearPreview()
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val de = event.toAndroidDragEvent()
                val sid = trackedSessionId ?: resolveTabDragSessionId(de)
                stripController.clearPreview()
                val session = TabTransferRegistry.activeSession(sid) ?: return false

                if (session.sourceWindowId != windowId) {
                    if (currentIsPicker) return false
                    val slot = stripController.computeInsertionIndex(de.x)
                    return TabTransferRegistry.completeMergeIntoWindow(
                        rawSessionId = session.sessionId,
                        targetWindowId = windowId,
                        insertIndex = slot,
                    )
                }

                if (!currentIsPicker && isOverSourceStrip(de.y)) {
                    val slot = stripController.computeInsertionIndex(de.x)
                    return TabTransferRegistry.reattachInSourceWindow(session.sessionId) {
                        currentVm.moveTabToInsertionSlot(session.sourceTabId, slot)
                    }
                }

                bodyDropLocalPos = Offset(de.x, de.y)
                if (Build.VERSION.SDK_INT >= 35 && session.canSpawnStandaloneWindow) {
                    // Return false so Android 15+ WM Shell GlobalDragListener spawns a native freeform/split window.
                    return false
                }
                if (session.canSpawnStandaloneWindow && !escapeCancelled) {
                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                    val screenX = loc[0] + de.x.roundToInt()
                    val screenY = loc[1] + de.y.roundToInt()
                    if (TabTransferRegistry.markAwaitingNewWindow(session.sessionId)) {
                        currentVm.launchDetachedSessionWindow(
                            sessionId = session.sessionId,
                            fallbackPath = session.snapshot.path,
                            promoteToPrimary = session.snapshot.promoteTargetToPrimary,
                            targetDisplayId = currentDisplay,
                            dropX = screenX,
                            dropY = screenY,
                        )
                        return true
                    }
                }
                return false
            }

            override fun onEnded(event: DragAndDropEvent) {
                stripController.clearPreview()
                val de = event.toAndroidDragEvent()
                val sid = trackedSessionId ?: resolveTabDragSessionId(de)
                val wasSource = isSourceWindow
                val bodyDrop = bodyDropLocalPos
                trackedSessionId = null
                isSourceWindow = false
                activeSessionId = null
                bodyDropLocalPos = null

                if (!wasSource) return
                val session = TabTransferRegistry.activeSession(sid) ?: return
                if (de.result) {
                    // Accepted by Android 15+ WM Shell unhandled-drop launcher (cross-window merges already transitioned status to Merged).
                    TabTransferRegistry.markAwaitingNewWindow(session.sessionId)
                } else if (!escapeCancelled && bodyDrop != null && session.canSpawnStandaloneWindow) {
                    // Fallback for non-desktop shells on API 35+ that do not consume unhandled drags.
                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                    val screenX = loc[0] + bodyDrop.x.roundToInt()
                    val screenY = loc[1] + bodyDrop.y.roundToInt()
                    if (TabTransferRegistry.markAwaitingNewWindow(session.sessionId)) {
                        currentVm.launchDetachedSessionWindow(
                            sessionId = session.sessionId,
                            fallbackPath = session.snapshot.path,
                            promoteToPrimary = session.snapshot.promoteTargetToPrimary,
                            targetDisplayId = currentDisplay,
                            dropX = screenX,
                            dropY = screenY,
                        )
                    } else {
                        TabTransferRegistry.cancelDragSession(session.sessionId)
                    }
                } else {
                    TabTransferRegistry.cancelDragSession(session.sessionId)
                }
                escapeCancelled = false
            }
        }
    }

    return this
        .onGloballyPositioned { coords ->
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            val b = coords.boundsInRoot()
            stripController.windowScreenBounds = Rect(
                left = loc[0] + b.left,
                top = loc[1] + b.top,
                right = loc[0] + b.right,
                bottom = loc[1] + b.bottom,
            )
        }
        .onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                val fallback = InProcessTabDragBus.activeVisual
                if (fallback != null && fallback.sourceWindowId == windowId) {
                    InProcessTabDragBus.onCancel(fallback.sessionId)
                    return@onPreviewKeyEvent true
                }
                val sid = activeSessionId
                if (sid != null && TabTransferRegistry.activeSession(sid) != null) {
                    escapeCancelled = true
                    stripController.clearPreview()
                    TabTransferRegistry.cancelDragSession(sid)
                    return@onPreviewKeyEvent true
                }
            }
            false
        }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { e ->
                val de = runCatching { e.toAndroidDragEvent() }.getOrNull() ?: return@dragAndDropTarget false
                val sid = resolveTabDragSessionId(de) ?: return@dragAndDropTarget false
                val session = TabTransferRegistry.activeSession(sid) ?: return@dragAndDropTarget false
                !currentIsPicker || session.sourceWindowId == windowId
            },
            target = target,
        )
}

/** Floating Chrome-style mini-window ghost shown during in-process / Robolectric detached tab drags. */
@Composable
internal fun DetachedTabGhost(visual: FallbackTabDragVisual) {
    if (visual.hoveringAnyStrip) return
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .offset { IntOffset(visual.pointerInSourceRoot.x.roundToInt() - 64, visual.pointerInSourceRoot.y.roundToInt() - 16) }
            .width(216.dp)
            .testTag(DETACHED_TAB_GHOST_TAG),
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.65f)),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainerLow, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = cs.primary)
                Text(
                    visual.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainerLowest, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.82f).height(6.dp).background(cs.onSurfaceVariant.copy(alpha = 0.22f), CircleShape))
                Box(Modifier.fillMaxWidth(0.58f).height(6.dp).background(cs.onSurfaceVariant.copy(alpha = 0.16f), CircleShape))
            }
        }
    }
}

/**
 * Launches a new independent Wayfinder window, optionally targeting a specific connected [targetDisplayId]
 * and DPI-scaled freeform bounds around `[dropX, dropY]`.
 */
internal fun openNewWindowOnDisplay(
    ctx: Context,
    path: String?,
    targetDisplayId: Int? = null,
): Boolean {
    val targetDisplay = if (targetDisplayId != null) {
        val found = ConnectedDisplays.findValidDisplay(ctx, targetDisplayId)
        if (found == null) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_display_disconnected), Toast.LENGTH_SHORT).show()
            return false
        }
        found
    } else null

    val intent = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(BrowserViewModel.EXTRA_NEW_WINDOW, true)
    if (path != null) intent.putExtra(BrowserViewModel.EXTRA_PATH, path)
    if (targetDisplay != null) intent.putExtra(EXTRA_TARGET_DISPLAY_ID, targetDisplay.displayId)
    IntentGuard.sign(ctx, intent)

    return try {
        val opts = buildLaunchOptionsBundle(ctx, targetDisplay, dropX = null, dropY = null)
        if (opts != null) ctx.startActivity(intent, opts) else ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        Toast.makeText(ctx, "Couldn't open a new window", Toast.LENGTH_SHORT).show()
        false
    }
}

/**
 * Launches a new Wayfinder window adopting a [TabTransferRegistry] session, re-validating [e.targetDisplayId]
 * before commit so disconnecting an external monitor mid-action restores the tab cleanly with zero loss.
 */
internal fun openTransferredTabWindow(
    ctx: Context,
    e: UiEvent.LaunchTransferredTabWindow,
): Boolean {
    val explicitTargetDisplay = if (e.targetDisplayId != null) {
        val found = ConnectedDisplays.findValidDisplay(ctx, e.targetDisplayId)
        if (found == null) {
            e.onLaunchFailed?.invoke()
            Toast.makeText(ctx, ctx.getString(R.string.toast_display_disconnected), Toast.LENGTH_SHORT).show()
            return false
        }
        found
    } else null

    val effectiveDisplay = explicitTargetDisplay ?: run {
        val curId = runCatching { ctx.findActivity()?.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        ConnectedDisplays.findValidDisplay(ctx, curId)
    }

    val intent = buildTransferredTabWindowIntent(
        context = ctx,
        sessionId = e.sessionId,
        fallbackPath = e.fallbackPath,
        promoteToPrimary = e.promoteToPrimary,
        targetDisplayId = effectiveDisplay?.displayId,
    )

    return try {
        val opts = buildLaunchOptionsBundle(ctx, effectiveDisplay, dropX = e.dropX, dropY = e.dropY)
        if (opts != null) ctx.startActivity(intent, opts) else ctx.startActivity(intent)
        e.onLaunched?.invoke()
        true
    } catch (_: Exception) {
        e.onLaunchFailed?.invoke()
        Toast.makeText(ctx, "Couldn't open a new window", Toast.LENGTH_SHORT).show()
        false
    }
}

private fun buildLaunchOptionsBundle(
    ctx: Context,
    targetDisplay: DisplayTarget?,
    dropX: Int?,
    dropY: Int?,
): android.os.Bundle? = runCatching {
    if (targetDisplay == null && dropX == null && dropY == null) return null
    val opts = ActivityOptions.makeBasic()
    if (targetDisplay != null) {
        opts.setLaunchDisplayId(targetDisplay.displayId)
        if (dropX != null || dropY != null || !targetDisplay.isDefault) {
            val geom = TabStripDragMath.computeLaunchGeometry(
                dropX = dropX,
                dropY = dropY,
                displayWidthPx = targetDisplay.widthPx,
                displayHeightPx = targetDisplay.heightPx,
                density = targetDisplay.density,
            )
            if (geom != null) {
                opts.setLaunchBounds(geom.toAndroidRect())
            }
        }
    } else if (dropX != null && dropY != null) {
        val dm = ctx.resources.displayMetrics
        val geom = TabStripDragMath.computeLaunchGeometry(
            dropX = dropX,
            dropY = dropY,
            displayWidthPx = dm.widthPixels,
            displayHeightPx = dm.heightPixels,
            density = dm.density,
        )
        if (geom != null) {
            opts.setLaunchBounds(geom.toAndroidRect())
        }
    }
    opts.toBundle()
}.getOrNull()
