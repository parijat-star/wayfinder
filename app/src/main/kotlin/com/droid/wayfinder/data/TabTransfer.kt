// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.droid.wayfinder.MainActivity
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val MIME_WAYFINDER_TAB = "application/vnd.com.droid.wayfinder.tab"
const val EXTRA_TEAR_SESSION_ID = "com.droid.wayfinder.extra.TEAR_SESSION_ID"
const val EXTRA_TARGET_DISPLAY_ID = "com.droid.wayfinder.extra.TARGET_DISPLAY_ID"
const val TAB_TRANSFER_SESSION_TTL_MS = 15_000L

/** Connected logical display eligible to host an independent Wayfinder window. */
data class DisplayTarget(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val isDefault: Boolean = displayId == Display.DEFAULT_DISPLAY,
)

/** Target freeform window bounds in the target display's pixel coordinate space. */
data class LaunchGeometry(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * Full immutable snapshot of a tab being torn off or merged into another window so back/forward history,
 * view mode, sort order, search state, and selection survive seamlessly across windows and displays.
 */
data class TransferredTab(
    val path: String,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val anchor: String? = null,
    val focused: String? = null,
    val viewMode: ViewMode = ViewMode.Details,
    val sortKey: SortKey = SortKey.Name,
    val sortAsc: Boolean = true,
    val query: String = "",
    val searchResults: List<FileEntry>? = null,
    val wasSearching: Boolean = false,
    val searchFilter: SearchFilter = SearchFilter(),
    /** True when moving the last tab out of the primary window so the receiving window takes over tab persistence. */
    val promoteTargetToPrimary: Boolean = false,
)

/** Summary of a live Wayfinder window registered in [TabTransferRegistry]. */
data class WindowSummary(
    val windowId: Int,
    val displayId: Int,
    val title: String,
    val tabCount: Int,
    val isPicker: Boolean,
    val isPrimaryWindow: Boolean,
    val lastFocusedSeq: Long,
)

/** Pure Chrome-style tab strip drag, slot-swap, tear-threshold, auto-scroll, and display-bounds math. */
object TabStripDragMath {

    /**
     * Computes the clamped physical left coordinate (`[0, (tabCount - 1) * tabWidthPx]`) of the dragged tab
     * inside the strip content row so the tab tracks the pointer 1:1 without overlapping the `+` button.
     */
    fun clampedDraggedLeftPx(
        pointerContentX: Float,
        grabOffsetPx: Float,
        tabWidthPx: Float,
        tabCount: Int,
    ): Float {
        if (tabCount <= 1 || !tabWidthPx.isFinite() || tabWidthPx <= 0f) return 0f
        val safePointer = if (pointerContentX.isFinite()) pointerContentX else 0f
        val safeGrab = if (grabOffsetPx.isFinite()) grabOffsetPx.coerceIn(0f, tabWidthPx) else 0f
        val maxLeft = ((tabCount - 1).toFloat() * tabWidthPx).takeIf { it.isFinite() && it >= 0f } ?: return 0f
        val raw = safePointer - safeGrab
        if (!raw.isFinite()) return 0f
        return raw.coerceIn(0f, maxLeft)
    }

    /** Physical left X of logical tab [index] inside a [tabCount]-item strip of uniform [tabWidthPx]. */
    fun slotLeftPx(
        index: Int,
        tabWidthPx: Float,
        tabCount: Int,
        isRtl: Boolean,
    ): Float {
        if (tabCount <= 0 || !tabWidthPx.isFinite() || tabWidthPx <= 0f) return 0f
        val clamped = index.coerceIn(0, tabCount - 1)
        val visualSlot = if (isRtl) (tabCount - 1 - clamped) else clamped
        return (visualSlot * tabWidthPx).takeIf { it.isFinite() } ?: 0f
    }

    /**
     * Physical `graphicsLayer.translationX` for the dragged tab at logical [slotIndex].
     * Because `slotLeftPx(newSlot) + translation(newSlot) == clampedLeftPx`, swapping indices produces zero visual jump.
     */
    fun draggedTabTranslationPx(
        clampedLeftPx: Float,
        slotIndex: Int,
        tabWidthPx: Float,
        tabCount: Int,
        isRtl: Boolean,
    ): Float {
        if (!clampedLeftPx.isFinite()) return 0f
        val diff = clampedLeftPx - slotLeftPx(slotIndex, tabWidthPx, tabCount, isRtl)
        return if (diff.isFinite()) diff else 0f
    }

    /**
     * Target logical index (`0..<tabCount`) for an in-strip reorder with directional [hysteresisFraction]
     * (`> 0.5f`) so hovering on a midpoint boundary never oscillates between adjacent slots.
     */
    fun targetReorderIndex(
        clampedLeftPx: Float,
        tabWidthPx: Float,
        tabCount: Int,
        currentIndex: Int,
        isRtl: Boolean,
        hysteresisFraction: Float = 0.54f,
    ): Int {
        if (tabCount <= 1 || !tabWidthPx.isFinite() || tabWidthPx <= 0f || !clampedLeftPx.isFinite()) return 0
        val cur = currentIndex.coerceIn(0, tabCount - 1)
        val curVisual = if (isRtl) (tabCount - 1 - cur) else cur
        val maxVisual = (tabCount - 1).toFloat()
        val rawVisual = (clampedLeftPx / tabWidthPx).let { if (it.isFinite()) it.coerceIn(0f, maxVisual) else curVisual.toFloat() }
        val h = if (hysteresisFraction.isFinite()) hysteresisFraction.coerceIn(0.5f, 0.9f) else 0.54f
        val margin = 1f - h
        val nextVisual = when {
            rawVisual > curVisual -> floor((rawVisual + margin).toDouble()).toInt()
            rawVisual < curVisual -> ceil((rawVisual - margin).toDouble()).toInt()
            else -> curVisual
        }.coerceIn(0, tabCount - 1)
        return if (isRtl) (tabCount - 1 - nextVisual) else nextVisual
    }

    /**
     * Computes the insertion slot (`0..visibleTabCount`) when hovering a detached tab over a window's tab strip.
     */
    fun mergeInsertionIndex(
        pointerContentX: Float,
        tabWidthPx: Float,
        visibleTabCount: Int,
        isRtl: Boolean,
    ): Int {
        if (visibleTabCount <= 0 || !tabWidthPx.isFinite() || tabWidthPx <= 0f || !pointerContentX.isFinite()) return 0
        val totalW = visibleTabCount * tabWidthPx
        if (!totalW.isFinite() || totalW <= 0f) return 0
        val effectiveX = if (isRtl) (totalW - pointerContentX) else pointerContentX
        val raw = (effectiveX / tabWidthPx) + 0.5f
        if (!raw.isFinite()) return 0
        return floor(raw.toDouble()).toInt().coerceIn(0, visibleTabCount)
    }

    /**
     * True when the pointer has pulled outside the magnetic tab-strip zone and should detach into window tear/merge mode.
     */
    fun shouldDetachFromStrip(
        pointerStripX: Float,
        pointerStripY: Float,
        stripViewportWidthPx: Float,
        stripHeightPx: Float,
        verticalSlopPx: Float,
        horizontalSlopPx: Float,
    ): Boolean {
        if (!pointerStripX.isFinite() || !pointerStripY.isFinite()) return false
        val vSlop = if (verticalSlopPx.isFinite()) verticalSlopPx.coerceAtLeast(8f) else 32f
        val hSlop = if (horizontalSlopPx.isFinite()) horizontalSlopPx.coerceAtLeast(16f) else 56f
        val w = if (stripViewportWidthPx.isFinite()) stripViewportWidthPx.coerceAtLeast(0f) else 0f
        val h = if (stripHeightPx.isFinite()) stripHeightPx.coerceAtLeast(0f) else 0f
        if (pointerStripY < -vSlop || pointerStripY > h + vSlop) return true
        if (pointerStripX < -hSlop || pointerStripX > w + hSlop) return true
        return false
    }

    /**
     * Policy guard determining whether a tab in a window can detach from its strip:
     *  - Picker windows (`GET_CONTENT` / `OPEN_DOCUMENT` / `CREATE_DOCUMENT`) never tear tabs out.
     *  - Single-tab windows with active background file operations never drain their last tab (preserving `viewModelScope`).
     *  - Multi-tab windows can always tear out into a new or existing window.
     *  - Single-tab windows can detach ONLY when another Wayfinder window exists (to merge) or another display is connected.
     */
    fun canTearTabOut(
        tabCount: Int,
        isPickerWindow: Boolean,
        hasActiveFileOps: Boolean,
        otherWindowCount: Int,
        connectedDisplayCount: Int,
    ): Boolean {
        if (isPickerWindow || tabCount <= 0) return false
        if (tabCount == 1 && hasActiveFileOps) return false
        if (tabCount > 1) return true
        return otherWindowCount > 0 || connectedDisplayCount > 1
    }

    /** Smooth quadratic edge auto-scroll velocity (px/sec) for overflowing horizontal tab strips. */
    fun autoScrollSpeedPxPerSec(
        pointerViewportX: Float,
        viewportWidthPx: Float,
        edgeZonePx: Float,
        maxSpeedPxPerSec: Float,
    ): Float {
        if (!pointerViewportX.isFinite() || !viewportWidthPx.isFinite() || !edgeZonePx.isFinite() || !maxSpeedPxPerSec.isFinite()) {
            return 0f
        }
        if (viewportWidthPx <= 0f || edgeZonePx <= 0f || maxSpeedPxPerSec <= 0f) return 0f
        val zone = edgeZonePx.coerceAtMost(viewportWidthPx / 2f)
        if (zone <= 0f) return 0f
        return when {
            pointerViewportX < zone -> {
                val t = ((zone - pointerViewportX) / zone).coerceIn(0f, 1f)
                -(t * t * maxSpeedPxPerSec)
            }
            pointerViewportX > viewportWidthPx - zone -> {
                val t = ((pointerViewportX - (viewportWidthPx - zone)) / zone).coerceIn(0f, 1f)
                t * t * maxSpeedPxPerSec
            }
            else -> 0f
        }.let { if (it.isFinite()) it else 0f }
    }

    /**
     * Computes DPI-scaled freeform launch bounds in the target display's own pixel space, clamped inside
     * [marginDp] of the display edges. Returns `null` on compact displays smaller than minimum freeform dimensions.
     */
    fun computeLaunchGeometry(
        dropX: Int?,
        dropY: Int?,
        displayWidthPx: Int,
        displayHeightPx: Int,
        density: Float,
        defaultWidthDp: Int = 1120,
        defaultHeightDp: Int = 780,
        minWidthDp: Int = 520,
        minHeightDp: Int = 420,
        marginDp: Int = 24,
    ): LaunchGeometry? {
        val safeDensity = if (density.isFinite() && density > 0f) density.coerceIn(0.5f, 6f) else 1f
        val marginPx = (marginDp * safeDensity).roundToInt().coerceAtLeast(0)
        val minW = (minWidthDp * safeDensity).roundToInt()
        val minH = (minHeightDp * safeDensity).roundToInt()
        val availW = displayWidthPx - marginPx * 2
        val availH = displayHeightPx - marginPx * 2
        if (availW < minW || availH < minH) return null

        val targetW = (defaultWidthDp * safeDensity).roundToInt().coerceIn(minW, (availW * 0.88f).roundToInt().coerceAtLeast(minW))
        val targetH = (defaultHeightDp * safeDensity).roundToInt().coerceIn(minH, (availH * 0.88f).roundToInt().coerceAtLeast(minH))

        val rawLeft = if (dropX != null) dropX - targetW / 2 else marginPx + (availW - targetW) / 2
        val rawTop = if (dropY != null) dropY - (24f * safeDensity).roundToInt() else marginPx + (availH - targetH) / 2

        val clampedLeft = rawLeft.coerceIn(marginPx, (displayWidthPx - marginPx - targetW).coerceAtLeast(marginPx))
        val clampedTop = rawTop.coerceIn(marginPx, (displayHeightPx - marginPx - targetH).coerceAtLeast(marginPx))
        return LaunchGeometry(
            left = clampedLeft,
            top = clampedTop,
            right = clampedLeft + targetW,
            bottom = clampedTop + targetH,
        )
    }
}

/** Active cross-window / cross-display tab drag session managed by [TabTransferRegistry]. */
class TabDragSession internal constructor(
    val sessionId: String,
    val sourceWindowId: Int,
    val sourceTabId: Long,
    val tabTitle: String,
    val snapshot: TransferredTab,
    val canSpawnStandaloneWindow: Boolean,
    val sourceDisplayId: Int,
    val createdAtMs: Long,
    internal val onCommitSource: () -> Unit,
    internal val onRestoreSource: () -> Unit,
) {
    internal enum class Status {
        Active,
        AwaitingNewWindow,
        Merged,
        ClaimedByNewWindow,
        Cancelled,
    }

    internal var status: Status = Status.Active
    internal var sourceCommitted: Boolean = false
}

/**
 * Process-wide registry of live Wayfinder windows and transactional tab-transfer sessions.
 * All mutations are synchronized and publish a monotonic [revision] flow for Compose UI observers.
 */
object TabTransferRegistry {
    private class RegisteredWindow(
        val windowId: Int,
        var displayId: Int,
        var title: String,
        var tabCount: Int,
        var isPicker: Boolean,
        var isPrimaryWindow: Boolean,
        var lastFocusedSeq: Long,
        var onMergeTab: (TransferredTab, Int?) -> Boolean,
        var onBringToFront: () -> Unit,
    ) {
        fun toSummary() = WindowSummary(
            windowId = windowId,
            displayId = displayId,
            title = title,
            tabCount = tabCount,
            isPicker = isPicker,
            isPrimaryWindow = isPrimaryWindow,
            lastFocusedSeq = lastFocusedSeq,
        )
    }

    private val rng = SecureRandom()
    private val windows = LinkedHashMap<Int, RegisteredWindow>()
    private val sessions = LinkedHashMap<String, TabDragSession>()
    private var focusClock = 0L

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bumpLocked() {
        _revision.value = _revision.value + 1L
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(16).also { rng.nextBytes(it) }
        return buildString(32) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
    }

    /** Validates that [raw] is an exact 32-character lowercase hex session identifier. */
    fun sanitizeSessionId(raw: String?): String? {
        if (raw == null || raw.length != 32) return null
        for (i in 0 until 32) {
            val ch = raw[i]
            if (ch !in '0'..'9' && ch !in 'a'..'f') return null
        }
        return raw
    }

    @Synchronized
    private fun pruneExpiredLocked(nowMs: Long) {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val s = entry.value
            val terminal = s.status == TabDragSession.Status.Merged ||
                s.status == TabDragSession.Status.ClaimedByNewWindow ||
                s.status == TabDragSession.Status.Cancelled
            if (terminal || nowMs - s.createdAtMs > TAB_TRANSFER_SESSION_TTL_MS) {
                it.remove()
            }
        }
    }

    @Synchronized
    fun registerWindow(
        windowId: Int,
        displayId: Int,
        title: String,
        tabCount: Int,
        isPicker: Boolean,
        isPrimaryWindow: Boolean,
        onMergeTab: (TransferredTab, Int?) -> Boolean,
        onBringToFront: () -> Unit = {},
    ) {
        val existing = windows[windowId]
        val seq = existing?.lastFocusedSeq ?: ++focusClock
        windows[windowId] = RegisteredWindow(
            windowId = windowId,
            displayId = displayId,
            title = title,
            tabCount = tabCount,
            isPicker = isPicker,
            isPrimaryWindow = isPrimaryWindow,
            lastFocusedSeq = seq,
            onMergeTab = onMergeTab,
            onBringToFront = onBringToFront,
        )
        bumpLocked()
    }

    @Synchronized
    fun updateWindow(
        windowId: Int,
        displayId: Int,
        title: String,
        tabCount: Int,
        isPicker: Boolean,
        isPrimaryWindow: Boolean,
    ) {
        val w = windows[windowId] ?: return
        val changed = w.displayId != displayId ||
            w.title != title ||
            w.tabCount != tabCount ||
            w.isPicker != isPicker ||
            w.isPrimaryWindow != isPrimaryWindow
        if (changed) {
            w.displayId = displayId
            w.title = title
            w.tabCount = tabCount
            w.isPicker = isPicker
            w.isPrimaryWindow = isPrimaryWindow
            bumpLocked()
        }
    }

    @Synchronized
    fun markWindowFocused(windowId: Int) {
        val w = windows[windowId] ?: return
        w.lastFocusedSeq = ++focusClock
        bumpLocked()
    }

    @Synchronized
    fun unregisterWindow(windowId: Int) {
        if (windows.remove(windowId) != null) {
            bumpLocked()
        }
    }

    /** Live non-picker Wayfinder windows other than [excludeWindowId], ordered by most recently focused. */
    @Synchronized
    fun otherMergeableWindows(excludeWindowId: Int): List<WindowSummary> =
        windows.values
            .filter { it.windowId != excludeWindowId && !it.isPicker }
            .sortedByDescending { it.lastFocusedSeq }
            .map { it.toSummary() }

    /** Directly merges [tab] into a registered target window (used by context menu & command palette actions). */
    fun mergeIntoRegisteredWindow(
        targetWindowId: Int,
        tab: TransferredTab,
        insertIndex: Int? = null,
        bringToFront: Boolean = true,
    ): Boolean {
        val target = synchronized(this) {
            windows[targetWindowId]?.takeIf { !it.isPicker }
        } ?: return false
        val merged = target.onMergeTab(tab, insertIndex)
        if (merged && bringToFront) {
            target.onBringToFront()
        }
        return merged
    }

    @Synchronized
    fun startDragSession(
        sourceWindowId: Int,
        sourceTabId: Long,
        tabTitle: String,
        snapshot: TransferredTab,
        canSpawnStandaloneWindow: Boolean,
        sourceDisplayId: Int,
        nowMs: Long = System.currentTimeMillis(),
        onCommitSource: () -> Unit,
        onRestoreSource: () -> Unit,
    ): TabDragSession {
        pruneExpiredLocked(nowMs)
        val session = TabDragSession(
            sessionId = newSessionId(),
            sourceWindowId = sourceWindowId,
            sourceTabId = sourceTabId,
            tabTitle = tabTitle,
            snapshot = snapshot,
            canSpawnStandaloneWindow = canSpawnStandaloneWindow,
            sourceDisplayId = sourceDisplayId,
            createdAtMs = nowMs,
            onCommitSource = onCommitSource,
            onRestoreSource = onRestoreSource,
        )
        sessions[session.sessionId] = session
        bumpLocked()
        return session
    }

    @Synchronized
    fun activeSession(rawSessionId: String?): TabDragSession? {
        val id = sanitizeSessionId(rawSessionId) ?: return null
        return sessions[id]?.takeIf { it.status == TabDragSession.Status.Active }
    }

    /** Returns the sole active in-process drag session when exactly one tab drag is in flight. */
    @Synchronized
    fun soleActiveSession(): TabDragSession? =
        sessions.values.singleOrNull { it.status == TabDragSession.Status.Active }

    /** Re-docks an actively dragged tab back into its own originating window at [targetIndex]. */
    fun reattachInSourceWindow(
        rawSessionId: String?,
        onReattach: () -> Unit,
    ): Boolean {
        val session = synchronized(this) {
            val id = sanitizeSessionId(rawSessionId) ?: return false
            val s = sessions[id] ?: return false
            if (s.status != TabDragSession.Status.Active) return false
            s.status = TabDragSession.Status.Merged
            sessions.remove(id)
            bumpLocked()
            s
        }
        session.onRestoreSource()
        onReattach()
        return true
    }

    /** Merges an active drag session into another window [targetWindowId] at [insertIndex] and commits source removal. */
    fun completeMergeIntoWindow(
        rawSessionId: String?,
        targetWindowId: Int,
        insertIndex: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val pair = synchronized(this) {
            pruneExpiredLocked(nowMs)
            val id = sanitizeSessionId(rawSessionId) ?: return false
            val s = sessions[id] ?: return false
            if (s.status != TabDragSession.Status.Active) return false
            val target = windows[targetWindowId]?.takeIf { !it.isPicker } ?: return false
            s.status = TabDragSession.Status.Merged
            val needsCommit = !s.sourceCommitted
            s.sourceCommitted = true
            sessions.remove(id)
            bumpLocked()
            Triple(s, target, needsCommit)
        }
        val (session, target, needsCommit) = pair
        val merged = target.onMergeTab(session.snapshot, insertIndex)
        if (!merged) {
            session.onRestoreSource()
            return false
        }
        if (needsCommit) {
            session.onCommitSource()
        }
        target.onBringToFront()
        return true
    }

    /**
     * Marks an active drag session as accepted by an unhandled-drop window spawn (e.g. Android 15+ WM Shell
     * `DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG` or local tear-off launcher), committing removal in
     * the source window while keeping the [TransferredTab] claimable by the newly starting [MainActivity].
     */
    fun markAwaitingNewWindow(
        rawSessionId: String?,
        allowSingleTabSpawn: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        var commitCallback: (() -> Unit)? = null
        val ok = synchronized(this) {
            pruneExpiredLocked(nowMs)
            val id = sanitizeSessionId(rawSessionId) ?: return false
            val s = sessions[id] ?: return false
            when (s.status) {
                TabDragSession.Status.ClaimedByNewWindow,
                TabDragSession.Status.AwaitingNewWindow,
                -> return true
                TabDragSession.Status.Active -> {
                    if (!s.canSpawnStandaloneWindow && !allowSingleTabSpawn) return false
                    s.status = TabDragSession.Status.AwaitingNewWindow
                    if (!s.sourceCommitted) {
                        s.sourceCommitted = true
                        commitCallback = s.onCommitSource
                    }
                    bumpLocked()
                    true
                }
                else -> false
            }
        }
        commitCallback?.invoke()
        return ok
    }

    /** Creates a direct awaiting-new-window session for menu/shortcut actions ("Move tab to new window" / "Move to display"). */
    @Synchronized
    fun createDirectNewWindowSession(
        snapshot: TransferredTab,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        pruneExpiredLocked(nowMs)
        val id = newSessionId()
        val session = TabDragSession(
            sessionId = id,
            sourceWindowId = 0,
            sourceTabId = -1L,
            tabTitle = FsPath.name(snapshot.path),
            snapshot = snapshot,
            canSpawnStandaloneWindow = true,
            sourceDisplayId = Display.DEFAULT_DISPLAY,
            createdAtMs = nowMs,
            onCommitSource = {},
            onRestoreSource = {},
        ).apply {
            status = TabDragSession.Status.AwaitingNewWindow
            sourceCommitted = true
        }
        sessions[id] = session
        bumpLocked()
        return id
    }

    /**
     * Atomically claims a [TransferredTab] once for a newly launched [MainActivity].
     * Expired, malformed, cancelled, or already-claimed session IDs return `null`.
     */
    fun claimForNewWindow(
        rawSessionId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): TransferredTab? {
        var commitCallback: (() -> Unit)? = null
        val snapshot = synchronized(this) {
            pruneExpiredLocked(nowMs)
            val id = sanitizeSessionId(rawSessionId) ?: return null
            val s = sessions[id] ?: return null
            if (s.status != TabDragSession.Status.AwaitingNewWindow && s.status != TabDragSession.Status.Active) {
                return null
            }
            if (s.status == TabDragSession.Status.Active && !s.canSpawnStandaloneWindow) {
                return null
            }
            s.status = TabDragSession.Status.ClaimedByNewWindow
            if (!s.sourceCommitted) {
                s.sourceCommitted = true
                commitCallback = s.onCommitSource
            }
            sessions.remove(id)
            bumpLocked()
            s.snapshot
        }
        commitCallback?.invoke()
        return snapshot
    }

    /** Cancels an active drag session (e.g. Escape key or unaccepted drop), restoring the tab to its source window. */
    fun cancelDragSession(rawSessionId: String?): Boolean {
        val session = synchronized(this) {
            val id = sanitizeSessionId(rawSessionId) ?: return false
            val s = sessions[id] ?: return false
            if (s.status != TabDragSession.Status.Active) return false
            s.status = TabDragSession.Status.Cancelled
            sessions.remove(id)
            bumpLocked()
            s
        }
        session.onRestoreSource()
        return true
    }

    @Synchronized
    internal fun resetForTest() {
        windows.clear()
        sessions.clear()
        focusClock = 0L
        bumpLocked()
    }
}

/**
 * Discovers connected displays where Wayfinder is permitted to launch windows, reading each display's
 * independent pixel bounds and DPI density so cross-display window placement scales cleanly.
 */
object ConnectedDisplays {
    @Volatile
    internal var overrideForTest: (() -> List<DisplayTarget>)? = null

    fun discoverEligibleDisplays(context: Context): List<DisplayTarget> {
        overrideForTest?.let { return it() }
        val dm = runCatching { context.getSystemService(DisplayManager::class.java) }.getOrNull() ?: return emptyList()
        val am = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
        val probeIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        val displays = runCatching { dm.displays }.getOrNull() ?: return emptyList()
        val result = ArrayList<DisplayTarget>(displays.size)
        for (display in displays) {
            if (!display.isValid) continue
            if (display.state == Display.STATE_OFF) continue
            if ((display.flags and Display.FLAG_PRIVATE) != 0) continue
            if (am != null) {
                val allowed = runCatching {
                    am.isActivityStartAllowedOnDisplay(context, display.displayId, probeIntent)
                }.getOrDefault(display.displayId == Display.DEFAULT_DISPLAY)
                if (!allowed) continue
            }
            val displayCtx = runCatching { context.createDisplayContext(display) }.getOrDefault(context)
            val wm = runCatching { displayCtx.getSystemService(WindowManager::class.java) }.getOrNull()
            val bounds = runCatching { wm?.maximumWindowMetrics?.bounds }.getOrNull()
            val dmMetrics = displayCtx.resources.displayMetrics
            val w = (bounds?.width()?.takeIf { it > 0 } ?: dmMetrics.widthPixels).coerceAtLeast(1)
            val h = (bounds?.height()?.takeIf { it > 0 } ?: dmMetrics.heightPixels).coerceAtLeast(1)
            val density = dmMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
            val rawName = runCatching { display.name }.getOrNull()?.trim().orEmpty()
            val label = rawName.ifEmpty {
                if (display.displayId == Display.DEFAULT_DISPLAY) "Built-in display" else "Display ${display.displayId}"
            }
            result += DisplayTarget(
                displayId = display.displayId,
                name = label,
                widthPx = w,
                heightPx = h,
                density = density,
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            )
        }
        return result.sortedWith(compareByDescending<DisplayTarget> { it.isDefault }.thenBy { it.displayId })
    }

    fun findValidDisplay(context: Context, displayId: Int): DisplayTarget? =
        discoverEligibleDisplays(context).firstOrNull { it.displayId == displayId }
}
