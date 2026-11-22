// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.data.NetworkDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SmbDiscoveryThrottleTest {

    @Test
    fun smbSubnetDiscoveryThrottlesConcurrencySkipsSelfIpAndHonorsCooldown() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val probedIps = Collections.synchronizedList(ArrayList<String>())
        var fakeNowMs = 100_000L

        val discovery = NetworkDiscovery(
            context = ctx,
            scope = scope,
            maxConcurrentProbes = 8,
            probeDelayMs = 2L,
            connectTimeoutMs = 150,
            minScanIntervalMs = 10_000L,
            localIpv4Provider = { "192.168.1.42" },
            port445Probe = { ip, _ ->
                val cur = inFlight.incrementAndGet()
                peakInFlight.accumulateAndGet(cur, ::maxOf)
                probedIps.add(ip)
                try {
                    delay(4L)
                    ip == "192.168.1.10" || ip == "192.168.1.200"
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            clockMs = { fakeNowMs },
        )

        try {
            discovery.scan(force = false)
            val deadline = System.currentTimeMillis() + 10_000L
            while ((discovery.scanning.value || probedIps.size < 253) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            Thread.sleep(50L)
            assertFalse("Scan should complete cleanly", discovery.scanning.value)
            assertEquals("Should probe all 253 peer hosts in /24 while skipping self IP", 253, probedIps.size)
            assertFalse("Must never probe own local IPv4 address", "192.168.1.42" in probedIps)
            assertTrue("Peak concurrent port-445 probes must never exceed 8, got ${peakInFlight.get()}", peakInFlight.get() in 1..8)
            assertEquals(listOf("192.168.1.10", "192.168.1.200"), discovery.hosts.value.map { it.address })

            // Passive scan within 10s cooldown is suppressed
            probedIps.clear()
            fakeNowMs += 3_000L
            discovery.scan(force = false)
            Thread.sleep(80L)
            assertEquals("Passive scan within 10s cooldown must not re-probe subnet", 0, probedIps.size)

            // Explicit user refresh/rescan (force = true) bypasses cooldown immediately
            discovery.scan(force = true)
            val forceDeadline = System.currentTimeMillis() + 10_000L
            while ((discovery.scanning.value || probedIps.size < 253) && System.currentTimeMillis() < forceDeadline) {
                Thread.sleep(20L)
            }
            assertEquals(253, probedIps.size)
            assertTrue("Peak concurrency during forced rescan must also stay <= 8, got ${peakInFlight.get()}", peakInFlight.get() <= 8)
        } finally {
            discovery.stop()
            scope.cancel()
        }
    }
}
