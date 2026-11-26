// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class DiscoveredHost(val label: String, val address: String)

/** Finds SMB servers on the LAN via passive mDNS (`_smb._tcp`), with throttled on-demand port-445 scan. */
class NetworkDiscovery @JvmOverloads constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val maxConcurrentProbes: Int = DEFAULT_MAX_CONCURRENT_PROBES,
    private val probeDelayMs: Long = DEFAULT_PROBE_DELAY_MS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val minScanIntervalMs: Long = DEFAULT_MIN_SCAN_INTERVAL_MS,
    private val localIpv4Provider: (() -> String?)? = null,
    private val port445Probe: (suspend (String, Int) -> Boolean)? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        internal const val DEFAULT_MAX_CONCURRENT_PROBES = 8
        internal const val DEFAULT_PROBE_DELAY_MS = 15L
        internal const val DEFAULT_CONNECT_TIMEOUT_MS = 250
        internal const val DEFAULT_MIN_SCAN_INTERVAL_MS = 10_000L
        internal const val MAX_PENDING_RESOLVES = 64
    }

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var listener: NsdManager.DiscoveryListener? = null
    val isStarted: Boolean
        @Synchronized get() = listener != null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var sessionGen = 0
    private var scanJob: Job? = null
    @Volatile private var hasCompletedScan = false
    @Volatile private var lastScanFinishedAtMs = 0L

    private fun add(found: DiscoveredHost) = _hosts.update { cur ->
        // Service names come from anyone on the LAN: bound their length and the list size.
        val h = found.copy(label = found.label.filter { it >= ' ' }.take(64).ifBlank { found.address })
        val existing = cur.indexOfFirst { it.address == h.address }
        when {
            existing < 0 && cur.size >= 256 -> cur
            existing < 0 -> (cur + h).sortedBy { it.label.lowercase() }
            // Prefer a real name over a bare IP label.
            cur[existing].label == cur[existing].address && h.label != h.address ->
                (cur.toMutableList().also { it[existing] = h }).sortedBy { it.label.lowercase() }
            else -> cur
        }
    }

    fun start() {
        synchronized(this) {
            if (listener != null) return
            val gen = sessionGen
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(info: NsdServiceInfo) = enqueue(info, gen)
                override fun onServiceLost(info: NsdServiceInfo) {
                    val active = synchronized(this@NetworkDiscovery) { listener != null && gen == sessionGen }
                    if (active) {
                        _hosts.update { cur -> cur.filterNot { it.label == info.serviceName } }
                    }
                }
            }
            runCatching {
                nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, l)
                listener = l
            }
        }
    }

    fun stop() {
        val oldListener: NsdManager.DiscoveryListener?
        val oldScan: Job?
        synchronized(this) {
            sessionGen++
            oldListener = listener
            listener = null
            pending.clear()
            resolving = false
            oldScan = scanJob
            scanJob = null
            _scanning.value = false
        }
        oldListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        oldScan?.cancel()
    }

    @Suppress("unused")
    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        enqueue(info, sessionGen, requireActiveListener = false)
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo, gen: Int, requireActiveListener: Boolean = true) {
        if ((requireActiveListener && listener == null) || gen != sessionGen) return
        if (pending.size >= MAX_PENDING_RESOLVES) return
        if (pending.any { it.serviceName == info.serviceName }) return
        pending.addLast(info)
        resolveNext(gen, requireActiveListener)
    }

    @Synchronized
    private fun resolveNext(gen: Int, requireActiveListener: Boolean = true) {
        if ((requireActiveListener && listener == null) || gen != sessionGen || resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = done()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val active = synchronized(this@NetworkDiscovery) {
                        (!requireActiveListener || listener != null) && gen == sessionGen
                    }
                    if (active) {
                        val host = serviceInfo.host
                        if (host is Inet4Address) {
                            host.hostAddress?.let { add(DiscoveredHost(serviceInfo.serviceName, it)) }
                        }
                    }
                    done()
                }

                fun done() = synchronized(this@NetworkDiscovery) {
                    if (gen == sessionGen) {
                        resolving = false
                        resolveNext(gen, requireActiveListener)
                    }
                }
            })
        }.onFailure {
            if (gen == sessionGen) {
                resolving = false
                resolveNext(gen, requireActiveListener)
            }
        }
    }

    /**
     * Sweeps the local `/24` IPv4 subnet on TCP port 445 with bounded concurrency ([maxConcurrentProbes] = 8)
     * and inter-probe pacing ([probeDelayMs]) so Wi-Fi/ARP tables and network firewalls are not flooded.
     * Passive refreshes respect [minScanIntervalMs]; explicit user Rescan passes [force] = `true`.
     */
    @JvmOverloads
    fun scan(force: Boolean = false) {
        synchronized(this) {
            if (scanJob?.isActive == true) return
            if (!force && hasCompletedScan && clockMs() - lastScanFinishedAtMs < minScanIntervalMs) return
            _scanning.value = true
            lateinit var launched: Job
            launched = scope.launch(Dispatchers.IO) {
                var completedCleanly = false
                try {
                    val selfIp = localIpv4Provider?.invoke() ?: detectLocalIpv4() ?: return@launch
                    val prefix = selfIp.substringBeforeLast('.', missingDelimiterValue = "")
                    if (prefix.count { it == '.' } != 2) return@launch

                    val workerCount = maxConcurrentProbes.coerceIn(1, 32)
                    val nextHostOctet = AtomicInteger(1)
                    (0 until workerCount).map {
                        async {
                            while (true) {
                                coroutineContext.ensureActive()
                                val host = nextHostOctet.getAndIncrement()
                                if (host > 254) break
                                val ip = "$prefix.$host"
                                if (ip == selfIp) continue
                                if (open445(ip)) {
                                    add(DiscoveredHost(ip, ip))
                                }
                                if (probeDelayMs > 0L) {
                                    delay(probeDelayMs)
                                }
                            }
                        }
                    }.awaitAll()
                    completedCleanly = true
                } finally {
                    if (completedCleanly) {
                        lastScanFinishedAtMs = clockMs()
                        hasCompletedScan = true
                    }
                    synchronized(this@NetworkDiscovery) {
                        if (scanJob === launched) {
                            scanJob = null
                            _scanning.value = false
                        }
                    }
                }
            }
            scanJob = launched
        }
    }

    @Suppress("unused")
    private fun localPrefix(): String? = detectLocalIpv4()?.substringBeforeLast('.')

    private fun detectLocalIpv4(): String? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val addr = props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: return null
        return addr.hostAddress
    }

    private suspend fun open445(ip: String): Boolean {
        val custom = port445Probe
        if (custom != null) return custom(ip, connectTimeoutMs)
        return withContext(Dispatchers.IO) {
            try {
                Socket().use {
                    it.connect(InetSocketAddress(ip, 445), connectTimeoutMs)
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }
}
