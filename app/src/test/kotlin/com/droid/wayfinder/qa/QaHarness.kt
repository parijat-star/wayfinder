// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.ui.AppState
import com.droid.wayfinder.ui.BrowserViewModel
import com.droid.wayfinder.ui.SharedUi
import com.droid.wayfinder.ui.UiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections

/**
 * QA harness for Robolectric tests: builds a real [BrowserViewModel] over Robolectric's external storage
 * (a temp dir), pumps the main looper until async work settles, and records emitted [UiEvent]s.
 */
class VmHarness(private val startExtras: Map<String, Any?> = emptyMap()) {
    val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    val root: File = Environment.getExternalStorageDirectory().apply { mkdirs() }
    val events: MutableList<UiEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val vm: BrowserViewModel

    init {
        // SharedUi is a process-wide singleton: reset stars/tags and theme between tests.
        SharedUi.init(app)
        SharedUi.updateMarks { Marks() }
        SharedUi.setTheme(com.droid.wayfinder.data.ThemeMode.System)
        SharedUi.setHideStatusBar(false)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                BrowserViewModel(app, SavedStateHandle(startExtras)) as T
        }
        vm = ViewModelProvider(store, factory)[BrowserViewModel::class.java]
        scope.launch { vm.events.collect { events += it } }
        settle()
    }

    val s: AppState get() = vm.state.value

    fun file(rel: String, text: String = rel): File = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }
    fun dir(rel: String): File = File(root, rel).apply { mkdirs() }

    /** Runs the main looper until [cond] holds (async IO work posts back to the main thread). */
    fun until(timeoutMs: Long = 8000, what: String = "condition", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what; state=${summary()}")
            Thread.sleep(5)
        }
    }

    /** Waits until no operation is running and no pane is loading, stable for [stableMs]. */
    fun settle(stableMs: Long = 120, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        var since = -1L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val st = vm.state.value
            val quiet = st.ops.isEmpty() && st.tabs.none { it.loading } && st.secondary?.loading != true && st.active.searching.not()
            val now = System.currentTimeMillis()
            if (quiet) { if (since < 0) since = now; if (now - since >= stableMs) return } else since = -1
            if (now > end) throw AssertionError("Timed out settling; state=${summary()}")
            Thread.sleep(5)
        }
    }

    fun navigate(path: String) { vm.navigate(path); settle() }

    fun pathOf(rel: String) = File(root, rel).path

    fun names(): List<String> = s.active.visible(s.showHidden).map { it.name }

    fun select(vararg rel: String) { vm.setSelection(rel.map { pathOf(it) }.toSet()) }

    fun toasts(): List<String> = events.toList().filterIsInstance<UiEvent.Toast>().map { it.message }

    private fun summary(): String {
        val st = vm.state.value
        return "path=${st.active.path} loading=${st.active.loading} ops=${st.ops.map { it.progress.title }} dialog=${st.dialog} err=${st.active.error}"
    }

    fun close() {
        scope.cancel()
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
