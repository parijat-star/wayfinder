// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.Context
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.SidebarModule
import com.droid.wayfinder.data.SidebarVisibility
import com.droid.wayfinder.data.TagIndex
import com.droid.wayfinder.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Process-wide preferences shared by every window: stars/color tags, theme, and sidebar module visibility.
 * Keeping a single source avoids two windows overwriting each other's saved state.
 */
object SharedUi {
    private var appContext: Context? = null
    private var settings: Settings? = null
    private val _marks = MutableStateFlow(Marks())
    private val _theme = MutableStateFlow(ThemeMode.System)
    private val _hideStatusBar = MutableStateFlow(false)
    private val _sidebarVisibility = MutableStateFlow(SidebarVisibility())
    val marks: StateFlow<Marks> = _marks.asStateFlow()
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val hideStatusBar: StateFlow<Boolean> = _hideStatusBar.asStateFlow()
    val sidebarVisibility: StateFlow<SidebarVisibility> = _sidebarVisibility.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        if (settings != null && appContext === ctx) {
            val s = settings!!
            _sidebarVisibility.value = s.sidebarVisibility
            return
        }
        appContext = ctx
        val s = Settings(ctx)
        settings = s
        _marks.value = Marks.parse(s.marks)
        TagIndex.marks = _marks.value
        _theme.value = s.themeMode
        _hideStatusBar.value = s.hideStatusBar
        _sidebarVisibility.value = s.sidebarVisibility
    }

    fun updateMarks(f: (Marks) -> Marks) {
        val m = _marks.updateAndGet(f)
        TagIndex.marks = m
        settings?.marks = m.serialize()
    }

    fun setTheme(t: ThemeMode) {
        _theme.value = t
        settings?.themeMode = t
    }

    fun setHideStatusBar(hide: Boolean) {
        _hideStatusBar.value = hide
        settings?.hideStatusBar = hide
    }

    fun setSidebarVisibility(visibility: SidebarVisibility) {
        _sidebarVisibility.value = visibility
        settings?.sidebarVisibility = visibility
    }

    fun setSidebarModuleVisible(module: SidebarModule, visible: Boolean): SidebarVisibility {
        val next = _sidebarVisibility.updateAndGet { it.withVisible(module, visible) }
        settings?.sidebarVisibility = next
        return next
    }
}
