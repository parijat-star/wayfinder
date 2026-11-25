// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import androidx.compose.material3.SnackbarHostState
import com.droid.wayfinder.ui.TransientSnackbarController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZoomToastControllerTest {

    @Test
    fun rapidKeyedToastsUpdateInPlaceAndResetDismissTimer() = runTest {
        val host = SnackbarHostState()
        val controller = TransientSnackbarController(host, this)

        controller.show("Interface zoom: 110%", key = "zoom", durationMs = 1_500L)
        runCurrent()
        val firstData = host.currentSnackbarData
        assertEquals("Interface zoom: 110%", firstData?.visuals?.message)

        advanceTimeBy(1_000L)
        runCurrent()
        controller.show("Interface zoom: 120%", key = "zoom", durationMs = 1_500L)
        runCurrent()

        // Same SnackbarData instance is updated in place (no queueing or crossfade flicker).
        assertSame(firstData, host.currentSnackbarData)
        assertEquals("Interface zoom: 120%", host.currentSnackbarData?.visuals?.message)

        // Original 1500ms mark (t = 1600ms) must NOT dismiss because the timer was reset at t = 1000ms.
        advanceTimeBy(600L)
        runCurrent()
        assertEquals("Interface zoom: 120%", host.currentSnackbarData?.visuals?.message)

        // 1500ms after the last zoom update (t = 2500ms), it dismisses cleanly.
        advanceTimeBy(900L)
        runCurrent()
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun differentToastImmediatelyReplacesActiveZoomToast() = runTest {
        val host = SnackbarHostState()
        val controller = TransientSnackbarController(host, this)

        controller.show("Interface zoom: 110%", key = "zoom", durationMs = 1_500L)
        runCurrent()
        assertEquals("Interface zoom: 110%", host.currentSnackbarData?.visuals?.message)

        controller.show("Path copied")
        runCurrent()
        assertEquals("Path copied", host.currentSnackbarData?.visuals?.message)

        // Previous zoom dismiss timer must not dismiss the replacement toast.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("Path copied", host.currentSnackbarData?.visuals?.message)

        host.currentSnackbarData?.dismiss()
        runCurrent()
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun undoActionCallbackFiresWhenActionPerformed() = runTest {
        val host = SnackbarHostState()
        val controller = TransientSnackbarController(host, this)
        var undone = false

        controller.show("Moved to Trash", actionLabel = "Undo", onAction = { undone = true })
        runCurrent()
        assertEquals("Moved to Trash", host.currentSnackbarData?.visuals?.message)
        assertEquals("Undo", host.currentSnackbarData?.visuals?.actionLabel)

        host.currentSnackbarData?.performAction()
        runCurrent()
        assertTrue(undone)
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun ephemeralZoomOrThemeToastDoesNotDismissActiveUndoSnackbar() = runTest {
        val host = SnackbarHostState()
        val controller = TransientSnackbarController(host, this)
        var undone = false

        controller.show("Moved 3 items to Trash", actionLabel = "Undo", onAction = { undone = true })
        runCurrent()
        val undoData = host.currentSnackbarData
        assertEquals("Moved 3 items to Trash", undoData?.visuals?.message)

        // Firing ephemeral zoom/theme status toasts while Undo is active must not clobber Undo.
        controller.show("Interface zoom: 110%", key = "zoom", durationMs = 1_500L)
        controller.show("Theme: Dark", key = "theme", durationMs = 1_500L)
        runCurrent()

        assertSame(undoData, host.currentSnackbarData)
        assertEquals("Moved 3 items to Trash", host.currentSnackbarData?.visuals?.message)

        host.currentSnackbarData?.performAction()
        runCurrent()
        assertTrue(undone)
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun zeroTimeoutEphemeralToastDismissesCleanlyWithoutStickingIndefinitely() = runTest {
        val host = SnackbarHostState()
        val controller = TransientSnackbarController(host, this) { _, _ -> 0L }

        controller.show("Interface zoom: 100%", key = "zoom", durationMs = 1_500L)
        runCurrent()
        assertNull(host.currentSnackbarData)
    }
}
