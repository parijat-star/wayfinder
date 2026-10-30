// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseBuildConfigTest {
    @Test
    fun minSdkIsAtLeastAndroid11AndProguardRulesCoverSmbjReflection() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            "Expected minSdkVersion >= 30 for StorageVolume and All Files Access APIs",
            ctx.applicationInfo.minSdkVersion >= 30,
        )

        val rulesFile = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
            .firstOrNull { it.isFile }
        assertTrue("Expected app/proguard-rules.pro to exist for R8 release builds", rulesFile != null)
        val rules = rulesFile!!.readText()
        assertTrue(rules.contains("-dontwarn org.ietf.jgss.**"))
        assertTrue(rules.contains("-dontwarn java.rmi.**"))
        assertTrue(rules.contains("-dontwarn javax.el.**"))
        assertTrue(rules.contains("-dontwarn javax.activation.**"))
        assertTrue(rules.contains("@net.engio.mbassy.listener.Handler"))
        assertTrue(rules.contains("com.hierynomus.**"))
        assertTrue(rules.contains("org.bouncycastle.jcajce.provider.**"))
    }
}
