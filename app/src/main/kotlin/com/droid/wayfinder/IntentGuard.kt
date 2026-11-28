// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.content.Context
import android.content.Intent
import com.droid.wayfinder.ui.BrowserViewModel
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MainActivity is exported (launcher, GET_CONTENT / OPEN_DOCUMENT, VIEW_DOWNLOADS), so any app can start it with
 * arbitrary extras. Our own window-management extras (start path, "new window", picker mode) are therefore only
 * honoured when the intent carries a per-installation secret that only this app knows; otherwise they are removed
 * before the view model reads them. Intents whose extras can't even be read (malformed / unknown Parcelables)
 * are replaced by a bare copy so they can't crash the app.
 */
object IntentGuard {
    const val EXTRA_TOKEN = "com.droid.wayfinder.extra.TOKEN"
    private const val PREFS = "intent_guard"

    /** Extras only our own code may set. */
    val INTERNAL_EXTRAS = listOf(
        BrowserViewModel.EXTRA_PATH, BrowserViewModel.EXTRA_NEW_WINDOW, BrowserViewModel.EXTRA_PICKER,
        BrowserViewModel.EXTRA_PICKER_MULTIPLE, BrowserViewModel.EXTRA_PICKER_MIME,
        BrowserViewModel.EXTRA_PICKER_MODE, BrowserViewModel.EXTRA_PICKER_TITLE,
        com.droid.wayfinder.data.EXTRA_TEAR_SESSION_ID,
        com.droid.wayfinder.data.EXTRA_TARGET_DISPLAY_ID,
    )

    @Volatile private var cached: String? = null

    /** Random 256-bit token, created once per installation (private preferences, excluded from backups). */
    @Synchronized
    fun token(ctx: Context): String {
        cached?.let { return it }
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = p.getString("token", null)?.takeIf { it.length == 64 } ?: run {
            val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
            b.joinToString("") { "%02x".format(it) }.also { p.edit().putString("token", it).apply() }
        }
        cached = t
        return t
    }

    /** Marks an intent created by this app as trusted. */
    fun sign(ctx: Context, intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, token(ctx))

    /** Constant-time token comparison (pure). */
    fun isTrusted(presented: String?, expected: String): Boolean =
        presented != null && MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())

    /**
     * Returns an intent that is safe to use: untrusted intents lose [INTERNAL_EXTRAS]; unreadable extras are
     * dropped entirely (only action, data and type are kept).
     */
    fun sanitize(ctx: Context, intent: Intent): Intent = try {
        val presented = intent.getStringExtra(EXTRA_TOKEN) // forces the extras to be unparcelled
        if (!isTrusted(presented, token(ctx))) INTERNAL_EXTRAS.forEach { intent.removeExtra(it) }
        intent
    } catch (_: Exception) {
        Intent(intent.action).apply {
            runCatching { setDataAndType(intent.data, intent.type) }
        }
    }
}
