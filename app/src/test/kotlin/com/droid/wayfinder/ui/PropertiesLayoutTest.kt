// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertiesLayoutTest {

    @Test
    fun shouldStackPropertiesRowsRespondsToNarrowWidthsAndLargeFontScales() {
        // Desktop / tablet / normal phone dialog content widths stay two-column.
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 500f, fontScale = 1.0f))
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 340f, fontScale = 1.0f))
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 340f, fontScale = 1.3f))

        // Narrow viewports switch to stacked label-above-value rows.
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 339f, fontScale = 1.0f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 280f, fontScale = 1.0f))

        // Large accessibility font scales switch to stacked rows even on wider dialogs.
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 480f, fontScale = 1.35f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 520f, fontScale = 1.5f))
    }
}
