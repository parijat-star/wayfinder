// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.Context
import android.icu.text.MessageFormat
import android.icu.text.NumberFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * Formats an ICU [MessageFormat] [pattern] (such as `{0, plural, =1 {# item} other {# items}}`)
 * using [locale]-appropriate plural rules and number grouping.
 */
fun formatIcu(pattern: String, vararg args: Any, locale: Locale = Locale.getDefault()): String =
    MessageFormat(pattern, locale).format(args)

/**
 * Formats an integer [value] with [locale]-aware grouping separators (for example `1,024` in `en-US`).
 */
fun formatLocalizedNumber(value: Long, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(value)

/**
 * Returns the active [Locale] from this [Context]'s configuration, falling back to [Locale.getDefault].
 */
fun Context.activeLocale(): Locale =
    resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

/**
 * Resolves the Android string resource [id] from this [Context] and evaluates its ICU [MessageFormat]
 * pattern against [activeLocale].
 */
fun Context.icuString(@StringRes id: Int, vararg args: Any): String =
    formatIcu(getString(id), *args, locale = activeLocale())

/**
 * Resolves the Android string resource [id] for the current Compose configuration and evaluates
 * its ICU [MessageFormat] pattern against the active [Locale].
 */
@Composable
@ReadOnlyComposable
fun icuStringResource(@StringRes id: Int, vararg args: Any): String {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    return formatIcu(stringResource(id), *args, locale = locale)
}
