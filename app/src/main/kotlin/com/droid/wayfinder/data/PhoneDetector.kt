// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.droid.wayfinder.ui.Mount

object PhoneDetector {
    /** System DocumentsProvider authority for wireless Cross-Device / Exo phone file access on Googlebooks. */
    const val QUICK_ACCESS_AUTHORITY = "com.google.ambient.streaming.multidevicesync.quickaccess"

    /** System DocumentsProvider authority for wired USB MTP file transfer. */
    const val MTP_AUTHORITY = "com.android.mtp.documents"

    val PHONE_AUTHORITIES = setOf(QUICK_ACCESS_AUTHORITY, MTP_AUTHORITY)

    fun isPhoneAuthority(authority: String?): Boolean {
        if (authority.isNullOrBlank()) return false
        val clean = authority.substringAfter('@').lowercase()
        return clean in PHONE_AUTHORITIES || clean.contains("quickaccess") || clean.contains("mtp.documents")
    }

    /** True when the app holds runtime `BLUETOOTH_CONNECT` permission (or runs on Android 11 / API 30 and lower). */
    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Probes whether a persisted phone SAF tree URI (`QuickAccess` or `MTP`) is currently online and
     * serving its root document. Returns `false` when the phone disconnects, walks out of range, or locks.
     */
    fun isSafPhoneMountReachable(context: Context, mount: SafMount): Boolean = runCatching {
        val treeUri = Uri.parse(mount.uri)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            val loading = cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            cursor.count > 0 || loading
        } ?: false
    }.getOrDefault(false)

    /**
     * Checks if a phone is paired or actively connected (via Cross-Device QuickAccess,
     * USB MTP/PTP, or active phone SAF / MTP storage mount).
     */
    fun isPhoneConnected(context: Context, mounts: List<String> = emptyList()): Boolean {
        // 1. Check if any active SAF or storage mount corresponds to a phone/MTP device
        if (mounts.any { isPhoneMount(it) }) return true

        // 2. Check direct system DocumentsProvider roots for QuickAccess or MTP
        val hasProviderRoot = runCatching {
            SystemDocumentsBridge.discoverRoots(context).any { r ->
                isPhoneAuthority(r.authority) || isPhoneMount(r.title)
            }
        }.getOrDefault(false)
        if (hasProviderRoot) return true

        // 3. Check USB devices for connected phone / MTP device
        if (findConnectedUsbPhoneName(context) != null) return true

        // 4. Check Bluetooth for an actively connected phone
        if (findConnectedBluetoothPhoneName(context) != null) return true

        return false
    }

    /**
     * Detects a connected or paired phone and returns its real DocumentsProvider (`doc://` or `saf://`)
     * mount path when accessible, or an unmounted connection descriptor (`isMounted = false`) when
     * a phone is detected over USB/Bluetooth without an active storage grant.
     */
    fun detectPairedPhone(
        context: Context,
        mounts: List<Mount> = emptyList(),
        safMounts: List<SafMount> = emptyList(),
        isSafMountReachable: (SafMount) -> Boolean = { isSafPhoneMountReachable(context, it) },
    ): PairedPhone? {
        // 1. Check direct system DocumentsProvider roots (QuickAccessDocumentsProvider & MtpDocumentsProvider)
        runCatching {
            val roots = SystemDocumentsBridge.discoverRoots(context)
            val phoneRoot = roots.firstOrNull { r ->
                r.authority == QUICK_ACCESS_AUTHORITY || r.authority == MTP_AUTHORITY
            } ?: roots.firstOrNull { r ->
                isPhoneAuthority(r.authority) || (r.authority != GoogleDriveManager.DOCS_AUTHORITY && isPhoneMount(r.title))
            }
            if (phoneRoot != null) {
                val path = "doc://${phoneRoot.authority}/${phoneRoot.documentId}"
                val transport = if (phoneRoot.authority.contains("mtp", ignoreCase = true)) {
                    PhoneTransport.USB_MTP
                } else {
                    PhoneTransport.QUICK_ACCESS
                }
                val sub = phoneRoot.summary?.takeIf { it.isNotBlank() }
                    ?: if (transport == PhoneTransport.USB_MTP) "USB file transfer" else "Internal storage"
                return PairedPhone(
                    name = phoneRoot.title.ifBlank { "Connected Phone" },
                    subtitle = sub,
                    path = path,
                    isConnected = true,
                    authority = phoneRoot.authority,
                    isMounted = true,
                    transport = transport,
                )
            }
        }

        // 2. Check SAF mounts whose URI points to QuickAccessDocumentsProvider or MtpDocumentsProvider,
        // verifying that the provider is actively online right now so offline grants don't appear stuck mounted.
        val unreachableSafPaths = HashSet<String>()
        for (sm in safMounts) {
            val uriAuth = runCatching { Uri.parse(sm.uri).authority }.getOrNull()
            if (isPhoneAuthority(uriAuth) || isPhoneMount(sm.label)) {
                if (!isSafMountReachable(sm)) {
                    unreachableSafPaths += sm.path
                    continue
                }
                val transport = if (uriAuth?.contains("mtp", ignoreCase = true) == true) {
                    PhoneTransport.USB_MTP
                } else {
                    PhoneTransport.QUICK_ACCESS
                }
                val sub = if (transport == PhoneTransport.USB_MTP) "USB file transfer" else "Internal storage"
                return PairedPhone(
                    name = sm.label.removePrefix("Connected Phone · ").ifBlank { "Connected Phone" },
                    subtitle = sub,
                    path = sm.path,
                    isConnected = true,
                    authority = uriAuth,
                    isMounted = true,
                    transport = transport,
                )
            }
        }

        // 3. Check generic mounts list passed by the ViewModel (skipping any SAF phone mounts proven offline above)
        for (m in mounts) {
            if (m.path in unreachableSafPaths) continue
            if (isPhoneMount(m.label) || isPhoneMount(m.path)) {
                return PairedPhone(
                    name = m.label,
                    subtitle = "Internal storage",
                    path = m.path,
                    isConnected = true,
                    authority = null,
                    isMounted = true,
                    transport = PhoneTransport.QUICK_ACCESS,
                )
            }
        }

        // 4. Check StorageManager secondary storage volumes for removable / MTP phone volumes
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            for (vol in sm.storageVolumes) {
                if (vol.isPrimary) continue
                val desc = runCatching { vol.getDescription(context) }.getOrNull().orEmpty()
                val dirPath = vol.directory?.path ?: continue
                if (isPhoneMount(desc) || isPhoneMount(vol.mediaStoreVolumeName.orEmpty())) {
                    return PairedPhone(
                        name = desc.ifBlank { "Connected Phone" },
                        subtitle = "Internal storage",
                        path = dirPath,
                        isConnected = true,
                        authority = null,
                        isMounted = true,
                        transport = PhoneTransport.STORAGE_VOLUME,
                    )
                }
            }
        }

        // 5. Check attached USB MTP phone (not yet mounted as a DocumentsProvider root)
        findConnectedUsbPhoneName(context)?.let { usbName ->
            return PairedPhone(
                name = usbName,
                subtitle = "USB · Tap to connect storage",
                path = FsPath.PHONE_ROOT,
                isConnected = true,
                authority = MTP_AUTHORITY,
                isMounted = false,
                transport = PhoneTransport.USB_MTP,
            )
        }

        // 6. Check actively connected Bluetooth phone (not yet mounted via QuickAccessDocumentsProvider)
        findConnectedBluetoothPhoneName(context)?.let { btName ->
            return PairedPhone(
                name = btName,
                subtitle = "Tap to connect phone storage",
                path = FsPath.PHONE_ROOT,
                isConnected = true,
                authority = QUICK_ACCESS_AUTHORITY,
                isMounted = false,
                transport = PhoneTransport.BLUETOOTH_UNMOUNTED,
            )
        }

        return null
    }

    fun isPhoneMount(labelOrPath: String): Boolean {
        val l = labelOrPath.lowercase()
        if (l.contains("headphone") || l.contains("microphone") || l.contains("earphone") ||
            l.contains("speakerphone") || l.contains("lan") || l.contains("ethernet") ||
            l.contains("hub") || l.contains("dock") || l.contains("hid")
        ) {
            return false
        }
        return l.contains("phone") || l.contains("mtp") || l.contains("quickaccess") ||
            l.contains("pixel") || l.contains("galaxy") || l.contains("xperia") ||
            l.contains("oneplus") || l.contains("xiaomi") || l.contains("huawei") ||
            l.contains("motorola") || l.contains("iphone")
    }

    /**
     * Returns the display name of an attached USB MTP/PTP phone, or null if no USB phone is attached.
     * Excludes USB hubs, Ethernet adapters (`USB_CLASS_COMM` / `USB_CLASS_CDC_DATA`), HID devices, and webcams.
     */
    fun findConnectedUsbPhoneName(context: Context): String? {
        if (GooglebookEcosystem.isUsbFileTransferDisallowed(context)) return null
        return runCatching {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
            for (dev in usbManager.deviceList.values) {
                val prod = dev.productName?.trim().orEmpty()
                if (dev.deviceClass == UsbConstants.USB_CLASS_HUB || dev.deviceClass == UsbConstants.USB_CLASS_COMM) {
                    continue
                }
                val hasNetworkOrHubIface = (0 until dev.interfaceCount).any { i ->
                    val cls = dev.getInterface(i).interfaceClass
                    cls == UsbConstants.USB_CLASS_HUB ||
                        cls == UsbConstants.USB_CLASS_COMM ||
                        cls == UsbConstants.USB_CLASS_CDC_DATA ||
                        cls == UsbConstants.USB_CLASS_VIDEO
                }
                if (hasNetworkOrHubIface && !isPhoneMount(prod)) continue

                val isStillImageOrMtp = dev.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                    (0 until dev.interfaceCount).any { i ->
                        val iface = dev.getInterface(i)
                        (iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                            iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1) ||
                            iface.name?.contains("mtp", ignoreCase = true) == true
                    }
                if (isStillImageOrMtp || (prod.isNotEmpty() && isPhoneMount(prod))) {
                    return prod.ifEmpty { "USB Phone" }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Tracks actively connected Bluetooth smartphones observed via public SDK `ACTION_ACL_CONNECTED` /
     * `ACTION_ACL_DISCONNECTED` broadcasts and `BluetoothAdapter.getProfileProxy` (`HEADSET` / `A2DP`),
     * avoiding any non-SDK / `@SystemApi` reflection on `user` builds.
     */
    private val activeBluetoothPhones = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Records a public `ACTION_ACL_CONNECTED` or `ACTION_ACL_DISCONNECTED` transition for a phone. */
    @SuppressLint("MissingPermission")
    fun onBluetoothAclChanged(device: android.bluetooth.BluetoothDevice?, connected: Boolean) {
        if (device == null) return
        val addr = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (!connected) {
            activeBluetoothPhones.remove(addr)
            return
        }
        val name = runCatching { device.name }.getOrNull().orEmpty()
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        if (isPhoneBluetoothDevice(btClass) || isPhoneMount(name)) {
            activeBluetoothPhones[addr] = name.ifBlank { "Connected Phone" }
        }
    }

    /** Clears cached active Bluetooth phone state (for example when the Bluetooth adapter turns off). */
    fun clearActiveBluetoothPhones() {
        activeBluetoothPhones.clear()
    }

    /**
     * Queries public SDK `BluetoothAdapter.getProfileProxy` for `HEADSET` and `A2DP` to seed
     * [activeBluetoothPhones] for phones that connected before Wayfinder started, closing each
     * profile proxy immediately after reading `connectedDevices`.
     */
    @SuppressLint("MissingPermission")
    fun warmConnectedClassicProfiles(context: Context, onDiscovered: (() -> Unit)? = null) {
        if (!hasBluetoothConnectPermission(context)) return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = btManager.adapter ?: return
        if (!adapter.isEnabled) return
        val appCtx = context.applicationContext ?: context
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            val state = runCatching { adapter.getProfileConnectionState(profile) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
            if (state != BluetoothProfile.STATE_CONNECTED) continue
            runCatching {
                adapter.getProfileProxy(
                    appCtx,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(prof: Int, proxy: BluetoothProfile) {
                            var added = false
                            try {
                                for (dev in proxy.connectedDevices.orEmpty()) {
                                    val name = runCatching { dev.name }.getOrNull().orEmpty()
                                    if (isPhoneBluetoothDevice(dev.bluetoothClass) || isPhoneMount(name)) {
                                        val prev = activeBluetoothPhones.put(dev.address, name.ifBlank { "Connected Phone" })
                                        if (prev == null) added = true
                                    }
                                }
                            } catch (_: Throwable) {
                            } finally {
                                runCatching { adapter.closeProfileProxy(prof, proxy) }
                            }
                            if (added) onDiscovered?.invoke()
                        }

                        override fun onServiceDisconnected(prof: Int) {}
                    },
                    profile,
                )
            }
        }
    }

    /**
     * Returns the display name of an actively connected Bluetooth smartphone (`Major.PHONE`),
     * or null when no phone is connected over Bluetooth. Uses exclusively public Android SDK APIs
     * (`BluetoothManager.getConnectedDevices(GATT / GATT_SERVER)` + [activeBluetoothPhones]) so no
     * non-SDK / `@SystemApi` reflection occurs on strict `user` builds.
     */
    @SuppressLint("MissingPermission")
    fun findConnectedBluetoothPhoneName(context: Context): String? {
        if (!hasBluetoothConnectPermission(context)) return null
        return runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = btManager.adapter ?: return null
            if (!adapter.isEnabled) {
                activeBluetoothPhones.clear()
                return null
            }

            // BluetoothManager.getConnectedDevices supports GATT and GATT_SERVER in the public SDK.
            val connectedGattDevices = buildList {
                runCatching { addAll(btManager.getConnectedDevices(BluetoothProfile.GATT)) }
                runCatching { addAll(btManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)) }
            }.distinct()

            connectedGattDevices.firstOrNull { dev ->
                isPhoneBluetoothDevice(dev.bluetoothClass) || isPhoneMount(dev.name.orEmpty())
            }?.let { dev ->
                val label = dev.name?.takeIf { it.isNotBlank() } ?: "Connected Phone"
                activeBluetoothPhones[dev.address] = label
                return label
            }

            // Check phones tracked via public ACTION_ACL_CONNECTED or classic profile proxy warm-up.
            if (activeBluetoothPhones.isNotEmpty()) {
                val bondedByAddr = adapter.bondedDevices.orEmpty().associateBy { it.address }
                for ((addr, cachedName) in activeBluetoothPhones) {
                    val bonded = bondedByAddr[addr]
                    if (bonded != null) {
                        return bonded.name?.takeIf { it.isNotBlank() } ?: cachedName
                    }
                    if (cachedName.isNotBlank()) return cachedName
                }
            }
            null
        }.getOrNull()
    }

    private fun isPhoneBluetoothDevice(btClass: BluetoothClass?): Boolean {
        if (btClass == null) return false
        val major = btClass.majorDeviceClass
        val devClass = btClass.deviceClass
        return major == BluetoothClass.Device.Major.PHONE ||
            devClass == BluetoothClass.Device.PHONE_SMART ||
            devClass == BluetoothClass.Device.PHONE_CELLULAR
    }
}
