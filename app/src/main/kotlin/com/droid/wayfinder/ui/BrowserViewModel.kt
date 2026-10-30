// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.DocFileSystem
import com.droid.wayfinder.data.DriveRestFileSystem
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.GoogleDriveManager
import com.droid.wayfinder.data.GooglebookEcosystem
import com.droid.wayfinder.data.WorkProfileTarget
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.NetworkDiscovery
import com.droid.wayfinder.data.Progress
import com.droid.wayfinder.data.RecentFiles
import com.droid.wayfinder.data.RenamePlan
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SearchScope
import com.droid.wayfinder.data.ServerStore
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.ConnectConfig
import com.droid.wayfinder.data.CredentialProvider
import com.droid.wayfinder.data.FtpFileSystem
import com.droid.wayfinder.data.MimeFilter
import com.droid.wayfinder.data.PhoneDetector
import com.droid.wayfinder.data.PhoneFileSystem
import com.droid.wayfinder.data.PickerRequest
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.data.RemoteAuthException
import com.droid.wayfinder.data.RemoteFileSystem
import com.droid.wayfinder.data.RemoteUri
import com.droid.wayfinder.data.SafFileSystem
import com.droid.wayfinder.data.SafLabels
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.SavedServer
import com.droid.wayfinder.data.SmbCred
import com.droid.wayfinder.data.SmbFileSystem
import com.droid.wayfinder.data.WebDavFileSystem
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ThemeMode
import com.droid.wayfinder.data.Transfer
import com.droid.wayfinder.data.Trashed
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.data.foldForSearch
import com.droid.wayfinder.data.naturalCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Undo/redo records. Performing a record's inverse yields the record that reverses it again, so the same
 * types serve both stacks: Renames ↔ Renames, Moves ↔ Moves, Created ↔ TrashOp.
 */
private sealed interface Undo {
    /** Items renamed from `first` to `second` (full paths). */
    data class Renames(val pairs: List<Pair<String, String>>) : Undo
    data class Moves(val items: List<Transfer>) : Undo
    data class TrashOp(val items: List<Trashed>) : Undo
    data class Created(val paths: List<String>) : Undo
}

/**
 * State holder for one window. [handle] carries the launching intent's extras: windows opened with Ctrl+N
 * pass [EXTRA_NEW_WINDOW] (and optionally [EXTRA_PATH]) and don't persist their tabs. File-picker windows
 * (GET_CONTENT / OPEN_DOCUMENT, see MainActivity) pass [EXTRA_PICKER] and don't persist their tabs either.
 */
class BrowserViewModel(private val app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    companion object {
        const val EXTRA_PATH = "com.droid.wayfinder.extra.PATH"
        const val EXTRA_NEW_WINDOW = "com.droid.wayfinder.extra.NEW_WINDOW"
        const val EXTRA_PICKER = "com.droid.wayfinder.extra.PICKER"
        const val EXTRA_PICKER_MULTIPLE = "com.droid.wayfinder.extra.PICKER_MULTIPLE"
        const val EXTRA_PICKER_MIME = "com.droid.wayfinder.extra.PICKER_MIME"
        const val EXTRA_PICKER_MODE = "com.droid.wayfinder.extra.PICKER_MODE"
        const val EXTRA_PICKER_TITLE = "com.droid.wayfinder.extra.PICKER_TITLE"
        private const val RECENT_DAYS = 30
    }

    private val settings = Settings(app)
    private val secondaryWindow: Boolean = handle.get<Any>(EXTRA_NEW_WINDOW) as? Boolean == true
    private val startPath: String? = (handle.get<Any>(EXTRA_PATH) as? String)?.takeIf { it.isNotBlank() && !it.contains('\u0000') }
    private val picker: PickerRequest? = if (handle.get<Any>(EXTRA_PICKER) as? Boolean == true) {
        val rawMode = handle.get<Any>(EXTRA_PICKER_MODE) as? String
        val mode = runCatching { com.droid.wayfinder.data.PickerMode.valueOf(rawMode.orEmpty()) }
            .getOrDefault(com.droid.wayfinder.data.PickerMode.OpenFile)
        val suggested = (handle.get<Any>(EXTRA_PICKER_TITLE) as? String)?.takeIf { it.isNotBlank() }.orEmpty()
        PickerRequest(
            allowMultiple = handle.get<Any>(EXTRA_PICKER_MULTIPLE) as? Boolean == true,
            mimeTypes = (handle.get<Any>(EXTRA_PICKER_MIME) as? Array<*>)?.filterIsInstance<String>().orEmpty(),
            mode = mode,
            suggestedName = if (mode == com.droid.wayfinder.data.PickerMode.CreateDocument && suggested.isEmpty()) "Untitled" else suggested,
        )
    } else null
    private val servers = ServerStore(app)
    private val smb = SmbFileSystem(servers)
    private val dav = WebDavFileSystem(servers)
    private val ftp = FtpFileSystem(servers)
    private var safMounts: List<SafMount> = settings.safMounts
    private val saf = SafFileSystem(app) { safMounts }
    private val gdrive = DriveRestFileSystem(app)
    private val docFs = DocFileSystem(app)
    private val phoneFs = PhoneFileSystem(app)
    private val primary: String = Environment.getExternalStorageDirectory().path
    private val trash = TrashManager(File(primary, ".Wayfinder-Trash"))
    private val discovery = NetworkDiscovery(app, viewModelScope)
    private val repo = FileRepository(
        LocalFileSystem(), smb, trash, networkRoot = { networkEntries() },
        byScheme = mapOf(
            "dav" to dav, "davs" to dav, "ftp" to ftp, "ftps" to ftp,
            RemoteUri.SAF_SCHEME to saf, RemoteUri.GDRIVE_SCHEME to gdrive,
            RemoteUri.DOC_SCHEME to docFs,
            RemoteUri.PHONE_SCHEME to phoneFs,
        ),
    )
    private val ops = FileOperations(repo)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var nextTabId = 1L
    private var opSeq = 0L
    private val opJobs = HashMap<Long, Job>()
    private val loadJobs = HashMap<Long, Job>()
    private var searchJob: Job? = null
    private val stale = HashSet<Long>()
    private val undoStack = ArrayDeque<Undo>()
    private val redoStack = ArrayDeque<Undo>()
    private val recentHosts = LinkedHashSet<String>()
    private var hashJob: Job? = null
    private var recentsCache: Pair<Long, List<FileEntry>>? = null
    private var phoneReceiver: BroadcastReceiver? = null
    private var rootsObserver: android.database.ContentObserver? = null
    private val observedPhoneTreeUris = HashSet<String>()
    private var phoneRetryJob: Job? = null
    private var storageVolumeCallback: Any? = null
    private var pendingMountAccount: String? = null
    internal var phoneConnectRetryDelaysMs: LongArray = longArrayOf(0L, 1_000L, 3_000L, 6_000L)
    internal var phoneDisconnectRetryDelaysMs: LongArray = longArrayOf(0L, 1_000L)
    internal var safPhoneMountReachabilityOverride: ((SafMount) -> Boolean)? = null

    init {
        SharedUi.init(app)
        // Downloaded network files / extracted archive entries may be sensitive: don't keep them around forever.
        viewModelScope.launch(Dispatchers.IO) { cleanCaches() }
        syncPersistedSafMounts()
        val roots = loadRoots()
        val pins = settings.pins ?: defaultPins(roots)
        val savedTabs = settings.tabs.filter {
            FsPath.isVirtual(it) || (FsPath.isRemote(it) && (!FsPath.isSaf(it) || safMounts.any { m -> FsPath.isWithin(m.path, it) })) ||
                File(it).isDirectory || FsPath.zipSplit(it)?.let { z -> File(z.first).isFile } == true
        }
        val startPaths = when {
            picker != null -> listOf(startPath ?: File(primary, Environment.DIRECTORY_DOWNLOADS).path.takeIf { File(it).isDirectory } ?: primary)
            secondaryWindow -> listOf(startPath ?: pins.firstOrNull() ?: primary)
            startPath != null -> listOf(startPath)
            else -> savedTabs.ifEmpty { listOf(pins.firstOrNull() ?: primary) }
        }
        val tabs = startPaths.map { newTabState(it) }
        val initialDrive = GoogleDriveManager.discoverAccounts(app, safMounts)
        val initialPhone = detectPhone(emptyList(), safMounts)
        val initialBtGranted = PhoneDetector.hasBluetoothConnectPermission(app)
        val initialLinux = GooglebookEcosystem.discoverLinuxRoot(app, primary, safMounts = safMounts)?.path
        val initialLinuxProvider = GooglebookEcosystem.findInstalledLinuxProviderAuthority(app)
        val initialWorkProfiles = GooglebookEcosystem.discoverWorkProfiles(app)
        _state.value = AppState(
            tabs = tabs,
            activeId = tabs[(if (secondaryWindow || picker != null || startPath != null) 0 else settings.activeTab).coerceIn(0, tabs.lastIndex)].id,
            roots = roots, pins = pins, showHidden = settings.showHidden,
            sidebarVisible = settings.sidebarVisible, previewVisible = settings.previewVisible,
            sidebarWidth = settings.sidebarWidth, gridSize = settings.gridSize,
            marks = SharedUi.marks.value, zoom = settings.zoom.coerceIn(0.7f, 1.6f), theme = SharedUi.theme.value,
            tagsColumn = settings.tagsColumn, permsColumn = settings.permsColumn, secondaryWindow = secondaryWindow,
            picker = picker,
            createFileName = picker?.suggestedName.orEmpty(),
            driveAccounts = initialDrive,
            pairedPhone = initialPhone,
            phoneConnected = initialPhone != null,
            bluetoothPermissionGranted = initialBtGranted,
            linuxRootPath = initialLinux,
            linuxProviderAuthority = initialLinuxProvider,
            workProfiles = initialWorkProfiles,
        )
        refreshMounts()
        tabs.forEach { load(it.id) }
        revealInTree(_state.value.active.path)
        roots.firstOrNull()?.let { ensureChildren(it.path) }
        discovery.start()
        viewModelScope.launch {
            discovery.hosts.collect { _state.update { s -> s.copy(network = networkEntries()) } }
        }
        viewModelScope.launch {
            discovery.scanning.collect { sc -> _state.update { s -> s.copy(scanning = sc) } }
        }
        _state.update { it.copy(network = networkEntries()) }
        viewModelScope.launch {
            SharedUi.marks.collect { m ->
                val changed = m != _state.value.marks
                _state.update { it.copy(marks = m) }
                // Starred / tag views list marked items, so they follow changes (also from other windows).
                if (changed) listOfNotNull(_state.value.mainTab, _state.value.secondary)
                    .filter { it.path == FsPath.STARRED_ROOT || FsPath.tagOf(it.path) != null }
                    .forEach { load(it.id, keepSelection = true) }
            }
        }
        viewModelScope.launch { SharedUi.theme.collect { t -> _state.update { it.copy(theme = t) } } }

        PhoneDetector.warmConnectedClassicProfiles(app) { refreshPhoneStatus() }
        refreshPhoneStatus()
        phoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val btDevice = intent?.let {
                    androidx.core.content.IntentCompat.getParcelableExtra(it, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                }
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        PhoneDetector.onBluetoothAclChanged(btDevice, connected = true)
                        refreshPhoneStatus(stagedRetry = true)
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        PhoneDetector.onBluetoothAclChanged(btDevice, connected = false)
                        refreshPhoneStatus(disconnectRetry = true)
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            PhoneDetector.clearActiveBluetoothPhones()
                            refreshPhoneStatus(disconnectRetry = true)
                        } else if (state == BluetoothAdapter.STATE_ON) {
                            PhoneDetector.warmConnectedClassicProfiles(app) { refreshPhoneStatus() }
                            refreshPhoneStatus(stagedRetry = true)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshPhoneStatus(disconnectRetry = true)
                    else -> refreshPhoneStatus(stagedRetry = true)
                }
            }
        }
        val phoneFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            ContextCompat.registerReceiver(app, phoneReceiver, phoneFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                val sm = app.getSystemService(StorageManager::class.java)
                val cb = object : StorageManager.StorageVolumeCallback() {
                    override fun onStateChanged(volume: android.os.storage.StorageVolume) {
                        _state.update { it.copy(roots = loadRoots()) }
                        refreshPhoneStatus(stagedRetry = true)
                    }
                }
                sm?.registerStorageVolumeCallback(ContextCompat.getMainExecutor(app), cb)
                storageVolumeCallback = cb
            }
        }
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshMounts()
            }
        }
        rootsObserver = observer
        for (auth in listOf(GoogleDriveManager.DOCS_AUTHORITY, PhoneDetector.QUICK_ACCESS_AUTHORITY, PhoneDetector.MTP_AUTHORITY)) {
            runCatching {
                val rootsUri = android.provider.DocumentsContract.buildRootsUri(auth)
                app.contentResolver.registerContentObserver(rootsUri, true, observer)
            }
        }
        registerPhoneTreeObservers()
    }

    /** Registers [rootsObserver] on persisted phone tree URIs so 3P installs receive live Cross-Device / MTP updates. */
    private fun registerPhoneTreeObservers() {
        val observer = rootsObserver ?: return
        for (sm in safMounts) {
            if (!isPhoneSafMount(sm)) continue
            if (observedPhoneTreeUris.add(sm.uri)) {
                runCatching {
                    app.contentResolver.registerContentObserver(Uri.parse(sm.uri), true, observer)
                }
            }
        }
    }

    /** Removes cached downloads (open / picker results) older than a day. */
    private fun cleanCaches() {
        val now = System.currentTimeMillis()
        for (sub in listOf("remote-open", "zip-open", "picker")) {
            File(app.cacheDir, sub).listFiles()?.filter { now - it.lastModified() > 24 * 3600_000L }?.forEach { FileInfo.deleteTree(it) }
        }
    }

    override fun onCleared() {
        discovery.stop()
        phoneRetryJob?.cancel()
        phoneRetryJob = null
        phoneReceiver?.let {
            runCatching { app.unregisterReceiver(it) }
            phoneReceiver = null
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            (storageVolumeCallback as? StorageManager.StorageVolumeCallback)?.let { cb ->
                runCatching { app.getSystemService(StorageManager::class.java)?.unregisterStorageVolumeCallback(cb) }
            }
            storageVolumeCallback = null
        }
        rootsObserver?.let {
            runCatching { app.contentResolver.unregisterContentObserver(it) }
            rootsObserver = null
        }
        observedPhoneTreeUris.clear()
    }

    private fun detectPhone(
        mounts: List<Mount>,
        safs: List<SafMount> = safMounts,
    ): com.droid.wayfinder.data.PairedPhone? {
        val override = safPhoneMountReachabilityOverride
        return if (override != null) {
            PhoneDetector.detectPairedPhone(app, mounts, safs, override)
        } else {
            PhoneDetector.detectPairedPhone(app, mounts, safs)
        }
    }

    private fun isPhoneSafMount(m: SafMount): Boolean {
        val auth = runCatching { Uri.parse(m.uri).authority }.getOrNull()
        return PhoneDetector.isPhoneAuthority(auth) || PhoneDetector.isPhoneMount(m.label)
    }

    /**
     * Updates tabs and sidebar tree state when a phone dynamically mounts or disconnects:
     *  - On auto-mount: tabs waiting at `phone://` automatically enter the newly mounted phone path.
     *  - On disconnect: tabs inside the disconnected phone mount fall back cleanly to `phone://`.
     */
    private fun applyPhoneTransition(
        previousPhone: com.droid.wayfinder.data.PairedPhone?,
        newPhone: com.droid.wayfinder.data.PairedPhone?,
    ) {
        val prevMountedPath = previousPhone?.takeIf { it.isMounted && it.path != FsPath.PHONE_ROOT }?.path
        val newMountedPath = newPhone?.takeIf { it.isMounted && it.path != FsPath.PHONE_ROOT }?.path
        if (prevMountedPath == newMountedPath) return

        val tabsToReload = ArrayList<Long>()
        if (newMountedPath != null) {
            _state.update { s ->
                val updatedTabs = s.tabs.map { t ->
                    if (t.path == FsPath.PHONE_ROOT) {
                        tabsToReload += t.id
                        t.navigatedTo(newMountedPath)
                    } else {
                        t
                    }
                }
                val updatedSecondary = s.secondary?.let { t ->
                    if (t.path == FsPath.PHONE_ROOT) {
                        tabsToReload += t.id
                        t.navigatedTo(newMountedPath)
                    } else {
                        t
                    }
                }
                s.copy(tabs = updatedTabs, secondary = updatedSecondary)
            }
        }
        if (prevMountedPath != null && newMountedPath != prevMountedPath) {
            val fallbackPath = newMountedPath ?: FsPath.PHONE_ROOT
            _state.update { s ->
                val updatedTabs = s.tabs.map { t ->
                    if (FsPath.isWithin(prevMountedPath, t.path)) {
                        tabsToReload += t.id
                        t.navigatedTo(fallbackPath).copy(
                            back = t.back.filterNot { FsPath.isWithin(prevMountedPath, it) },
                        )
                    } else {
                        t
                    }
                }
                val updatedSecondary = s.secondary?.let { t ->
                    if (FsPath.isWithin(prevMountedPath, t.path)) {
                        tabsToReload += t.id
                        t.navigatedTo(fallbackPath).copy(
                            back = t.back.filterNot { FsPath.isWithin(prevMountedPath, it) },
                        )
                    } else {
                        t
                    }
                }
                val prunedTree = s.tree.copy(
                    expanded = s.tree.expanded.filterNotTo(HashSet()) { FsPath.isWithin(prevMountedPath, it) },
                    children = s.tree.children.filterKeys { !FsPath.isWithin(prevMountedPath, it) },
                    loading = s.tree.loading.filterNotTo(HashSet()) { FsPath.isWithin(prevMountedPath, it) },
                )
                s.copy(tabs = updatedTabs, secondary = updatedSecondary, tree = prunedTree)
            }
        }
        if (tabsToReload.isNotEmpty()) {
            persist()
            tabsToReload.distinct().forEach { load(it) }
        }
    }

    fun requestBluetoothPermission() {
        emit(UiEvent.RequestBluetoothPermission)
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        _state.update { it.copy(bluetoothPermissionGranted = granted || PhoneDetector.hasBluetoothConnectPermission(app)) }
        if (granted) {
            PhoneDetector.warmConnectedClassicProfiles(app) { refreshPhoneStatus() }
        }
        refreshPhoneStatus(stagedRetry = granted)
    }

    fun refreshPhoneStatus(stagedRetry: Boolean = false, disconnectRetry: Boolean = false) {
        if (stagedRetry || disconnectRetry) {
            phoneRetryJob?.cancel()
        }
        val delays = when {
            stagedRetry -> phoneConnectRetryDelaysMs
            disconnectRetry -> phoneDisconnectRetryDelaysMs
            else -> longArrayOf(0L)
        }
        val job = viewModelScope.launch {
            for (delayMs in delays) {
                if (delayMs > 0L) withContext(Dispatchers.Default) { kotlinx.coroutines.delay(delayMs) }
                val snapshot = withContext(Dispatchers.IO) {
                    safMounts = settings.safMounts
                    syncPersistedSafMounts()
                    val saved = servers.saved()
                    val serverMounts = saved.filter { it.mounted }.map { sv ->
                        Mount(
                            sv.rootPath,
                            sv.label,
                            "${sv.protocol.label}${if (sv.user.isNotEmpty() && !sv.guest) " · ${sv.user}" else ""}",
                            sv.key,
                            saf = false,
                        )
                    }
                    val phone = detectPhone(serverMounts, safMounts)
                    val btGranted = PhoneDetector.hasBluetoothConnectPermission(app)
                    val currentMounts = computeMounts(saved, phone?.takeIf { it.isMounted }?.path)
                    Triple(saved, phone, currentMounts) to btGranted
                }
                val (triple, btGranted) = snapshot
                val (saved, phone, currentMounts) = triple
                val prevPhone = _state.value.pairedPhone
                _state.update {
                    it.copy(
                        savedServers = saved,
                        mounts = currentMounts,
                        network = networkEntries(saved, currentMounts),
                        pairedPhone = phone,
                        phoneConnected = phone != null,
                        bluetoothPermissionGranted = btGranted,
                    )
                }
                registerPhoneTreeObservers()
                applyPhoneTransition(prevPhone, phone)
                if (stagedRetry && phone?.isMounted == true) break
                if (disconnectRetry && phone?.isMounted != true) break
            }
        }
        if (stagedRetry || disconnectRetry) {
            phoneRetryJob = job
        }
    }

    // ---------------------------------------------------------------- setup helpers

    private fun loadRoots(): List<RootVolume> {
        val sm = app.getSystemService(StorageManager::class.java)
        val disallowPhysicalMedia = GooglebookEcosystem.isPhysicalMediaMountDisallowed(app)
        val primaryFree = runCatching {
            val stat = android.os.StatFs(primary)
            val gb = stat.availableBytes / (1024L * 1024 * 1024)
            "$gb GB free"
        }.getOrNull()
        val vols = sm.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED && it.directory != null && (!disallowPhysicalMedia || it.isPrimary) }
            .map { RootVolume(it.directory!!.path, if (it.isPrimary) "Internal storage" else it.getDescription(app), !it.isPrimary, if (it.isPrimary) primaryFree else null) }
            .toMutableList()

        // Also discover any additional mounted storage directories under /storage (SD cards, USB drives, MTP mounts)
        if (!disallowPhysicalMedia) {
            runCatching {
                File("/storage").listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                        val p = f.absolutePath
                        if (vols.none { it.path == p }) {
                            vols += RootVolume(p, f.name, true)
                        }
                    }
                }
            }
        }
        GooglebookEcosystem.discoverLinuxRoot(app, primary, safMounts = safMounts)?.let { linuxRoot ->
            if (vols.none { it.path == linuxRoot.path }) {
                vols += linuxRoot
            }
        }
        return vols.ifEmpty { listOf(RootVolume(primary, "Internal storage", false, primaryFree)) }
            .sortedByDescending { !it.removable }
    }

    private fun defaultPins(roots: List<RootVolume>): List<String> {
        val base = roots.first().path
        return listOf("Download", "Documents", "Pictures", "Music", "Movies")
            .map { File(base, it) }.filter { it.isDirectory }.map { it.path }
    }

    private fun newTabState(path: String) = TabState(
        id = nextTabId++, path = FsPath.normalize(path),
        viewMode = settings.viewMode, sortKey = settings.sortKey, sortAsc = settings.sortAsc,
    )

    /** Saved (mounted) servers and SAF mounts for the sidebar's "Network & cloud" section. */
    private fun computeMounts(
        saved: List<SavedServer>,
        mountedPhonePath: String? = _state.value.pairedPhone?.takeIf { it.isMounted }?.path,
        linuxPath: String? = _state.value.linuxRootPath,
    ): List<Mount> =
        saved.filter { it.mounted }.map { sv ->
            Mount(sv.rootPath, sv.label, "${sv.protocol.label}${if (sv.user.isNotEmpty() && !sv.guest) " · ${sv.user}" else ""}", sv.key, saf = false)
        } + safMounts
            .filter { m -> (!isPhoneSafMount(m) || m.path == mountedPhonePath) && m.path != linuxPath }
            .map { m -> Mount(m.path, m.label, "Cloud / storage provider", m.id, saf = true) }

    /**
     * Synchronizes any persisted SAF tree URI permissions held by the app into [safMounts]
     * so tree grants received on startup or across sessions are automatically mounted.
     */
    private fun syncPersistedSafMounts() {
        val persisted = runCatching {
            app.contentResolver.persistedUriPermissions.filter { perm ->
                perm.isReadPermission && android.provider.DocumentsContract.isTreeUri(perm.uri)
            }
        }.getOrDefault(emptyList())
        if (persisted.isEmpty()) return
        var updated = safMounts
        var changed = false
        for (perm in persisted) {
            val rawUri = perm.uri
            val auth = rawUri.authority.orEmpty()
            val uri = if (auth.contains('@')) rawUri.buildUpon().authority(auth.substringAfter('@')).build() else rawUri
            val uriStr = uri.toString()
            if (updated.any { it.uri == uriStr }) continue
            val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
            val provider = SafLabels.providerLabel(uri.authority.orEmpty(), treeId)
            val name = GoogleDriveManager.queryTreeDisplayName(app, uri)
            val label = when {
                !name.isNullOrBlank() && name.contains("@") && uri.authority == GoogleDriveManager.DOCS_AUTHORITY ->
                    "Drive ($name)"
                PhoneDetector.isPhoneAuthority(uri.authority) ->
                    name?.takeIf { it.isNotBlank() } ?: provider
                name.isNullOrBlank() || name == provider -> provider
                else -> "$provider · $name"
            }
            val mount = SafMount(java.util.UUID.randomUUID().toString().replace("-", "").take(8), label, uriStr)
            updated = updated + mount
            changed = true
        }
        if (changed) {
            safMounts = updated
            settings.safMounts = updated
        }
    }

    private fun refreshMounts() {
        safMounts = settings.safMounts // other windows may have (un)mounted locations
        syncPersistedSafMounts()
        val saved = servers.saved()
        val serverMounts = saved.filter { it.mounted }.map { sv ->
            Mount(sv.rootPath, sv.label, "${sv.protocol.label}${if (sv.user.isNotEmpty() && !sv.guest) " · ${sv.user}" else ""}", sv.key, saf = false)
        }
        val driveAccs = GoogleDriveManager.discoverAccounts(app, safMounts)
        val phone = detectPhone(serverMounts, safMounts)
        val btGranted = PhoneDetector.hasBluetoothConnectPermission(app)
        val linuxPath = GooglebookEcosystem.discoverLinuxRoot(app, primary, safMounts = safMounts)?.path
        val linuxProvider = GooglebookEcosystem.findInstalledLinuxProviderAuthority(app)
        val currentMounts = computeMounts(saved, phone?.takeIf { it.isMounted }?.path, linuxPath)
        val workProfiles = GooglebookEcosystem.discoverWorkProfiles(app)
        val prevPhone = _state.value.pairedPhone
        val prevLinux = _state.value.linuxRootPath
        val updatedRoots = if (linuxPath != prevLinux) loadRoots() else null
        _state.update { it.copy(
            roots = updatedRoots ?: it.roots,
            savedServers = saved,
            mounts = currentMounts,
            network = networkEntries(saved, currentMounts),
            driveAccounts = driveAccs,
            pairedPhone = phone,
            phoneConnected = phone != null,
            bluetoothPermissionGranted = btGranted,
            linuxRootPath = linuxPath,
            linuxProviderAuthority = linuxProvider,
            workProfiles = workProfiles,
        ) }
        registerPhoneTreeObservers()
        applyPhoneTransition(prevPhone, phone)
    }

    /** The Network root listing: mounts first, then servers seen this session or found on the LAN. */
    private fun networkEntries(
        saved: List<SavedServer> = servers.saved(),
        activeMounts: List<Mount> = computeMounts(saved),
    ): List<FileEntry> {
        val mounts = activeMounts.map { FileEntry(it.path, it.label, true, isServer = true, subtitle = it.subtitle) }
        val map = LinkedHashMap<String, FileEntry>()
        saved.filter { it.protocol == Protocol.SMB }.forEach { sv -> map[sv.host] = FileEntry("smb://${sv.host}", sv.host, true, isServer = true) }
        recentHosts.forEach { h -> map.putIfAbsent(h, FileEntry("smb://$h", h, true, isServer = true)) }
        discovery.hosts.value.forEach { d ->
            val existing = map[d.address]
            if (existing == null || existing.name == existing.path.removePrefix("smb://")) {
                map[d.address] = FileEntry("smb://${d.address}", d.label, true, isServer = true, subtitle = d.address.takeIf { it != d.label })
            }
        }
        val mountPaths = mounts.mapTo(HashSet()) { it.path }
        return mounts + map.values.filter { it.path !in mountPaths }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    private fun updateTab(id: Long, f: (TabState) -> TabState) =
        _state.update { s ->
            s.copy(
                tabs = s.tabs.map { if (it.id == id) f(it) else it },
                secondary = s.secondary?.let { if (it.id == id) f(it) else it },
            )
        }

    private val tab: TabState get() = _state.value.active
    private val hidden: Boolean get() = _state.value.showHidden

    private fun emit(e: UiEvent) { _events.tryEmit(e) }
    private fun toast(msg: String, undo: Boolean = false) = emit(UiEvent.Toast(msg, undo))

    private fun persist() {
        // Extra and picker windows keep their tabs to themselves so they don't overwrite the main window's session.
        if (secondaryWindow || picker != null) return
        val s = _state.value
        settings.tabs = s.tabs.map { it.path }
        settings.activeTab = s.tabs.indexOfFirst { it.id == s.activeId }.coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- loading

    /** Loads the listing of tab [id]; [select] (a child path) is selected and focused once loaded. */
    private fun load(id: Long, keepSelection: Boolean = false, select: String? = null) {
        val path = _state.value.tabById(id)?.path ?: return
        loadJobs[id]?.cancel()
        stale.remove(id)
        updateTab(id) { it.copy(loading = true, error = null) }
        loadJobs[id] = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { listAny(path) }
                updateTab(id) { t ->
                    if (t.path != path) t
                    else {
                        val paths = list.mapTo(HashSet()) { it.path }
                        val sel = select?.takeIf { it in paths }
                        t.copy(
                            entries = list, loading = false, error = null,
                            selection = if (sel != null) setOf(sel) else if (keepSelection) t.selection.filterTo(HashSet()) { it in paths } else emptySet(),
                            focused = sel ?: t.focused?.takeIf { keepSelection && it in paths },
                            anchor = sel ?: t.anchor,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteAuthException) {
                updateTab(id) { if (it.path == path) it.copy(loading = false, entries = emptyList(), error = TabError(e.message ?: "Sign in required", e.host)) else it }
                if (tab.id == id) promptAuth(e.host)
            } catch (e: Exception) {
                updateTab(id) { if (it.path == path) it.copy(loading = false, entries = emptyList(), error = TabError(e.message ?: "Couldn't open this folder")) else it }
            }
        }
    }

    /** Lists real folders, archives and the virtual collections (Recents, Starred, Phone, color tags). */
    private suspend fun listAny(path: String, fresh: Boolean = false): List<FileEntry> = when {
        path == FsPath.RECENTS_ROOT -> recents(fresh)
        path == FsPath.STARRED_ROOT -> statAll(_state.value.marks.starred)
        FsPath.isPhone(path) -> phoneFs.list(path)
        FsPath.tagOf(path) != null -> statAll(_state.value.marks.pathsWith(FsPath.tagOf(path)!!))
        else -> repo.list(path)
    }

    private fun statAll(paths: Collection<String>): List<FileEntry> =
        paths.mapNotNull { p -> try { repo.fs(p).stat(p) } catch (_: Exception) { null } }

    private fun quickShareDir(): File =
        (runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Quick Share") }.getOrNull()
            ?: File(Environment.getExternalStorageDirectory(), "Download/Quick Share")).apply { mkdirs() }

    /** Files from phone integration (Quick Share / Nearby Share transfers, connected phone MTP storage). */
    private suspend fun phoneFiles(): List<FileEntry> {
        val results = mutableListOf<FileEntry>()
        val seen = HashSet<String>()
        val downloadsDir = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        val candidates = listOfNotNull(
            downloadsDir?.let { File(it, "Quick Share") },
            downloadsDir?.let { File(it, "Nearby Share") },
        )
        for (dir in candidates) {
            if (dir.isDirectory) {
                dir.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { f ->
                    if (seen.add(f.absolutePath)) {
                        results += FileEntry(
                            path = f.absolutePath,
                            name = f.name,
                            isDir = f.isDirectory,
                            size = f.length(),
                            modified = f.lastModified(),
                            subtitle = "Quick Share",
                        )
                    }
                }
            }
        }
        for (m in _state.value.mounts) {
            val lbl = m.label.lowercase()
            if (lbl.contains("phone") || lbl.contains("mtp") || lbl.contains("pixel") || lbl.contains("galaxy")) {
                try {
                    repo.list(m.path).forEach { e ->
                        if (seen.add(e.path)) results += e.copy(subtitle = m.label)
                    }
                } catch (_: Exception) {}
            }
        }
        // Also check any secondary volume mounted under /storage (e.g. Pixel 9 MTP / external storage)
        runCatching {
            File("/storage").listFiles()?.forEach { sub ->
                if (sub.isDirectory && sub.name != "emulated" && sub.name != "self") {
                    sub.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { f ->
                        if (seen.add(f.absolutePath)) {
                            results += FileEntry(
                                path = f.absolutePath,
                                name = f.name,
                                isDir = f.isDirectory,
                                size = if (f.isDirectory) 0L else f.length(),
                                modified = f.lastModified(),
                                subtitle = "Pixel 9",
                            )
                        }
                    }
                }
            }
        }
        return results.sortedByDescending { it.modified }
    }

    fun openQuickShareFolder() {
        navigate(quickShareDir().absolutePath)
    }

    fun openPhoneHubSettings() {
        val context = app
        val intents = listOf(
            Intent("android.settings.CROSS_DEVICE_SETTINGS"),
            Intent("com.google.android.gms.nearby.sharing.ACTION_NEARBY_SHARING_SETTINGS"),
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        toast("Unable to open settings")
    }

    /** Recently modified files across all storage roots (bounded walk, cached for a short while). */
    private suspend fun recents(fresh: Boolean): List<FileEntry> {
        val now = System.currentTimeMillis()
        recentsCache?.takeIf { !fresh && now - it.first < 60_000 }?.let { (_, list) -> return list.filter { File(it.path).exists() } }
        val ctx = kotlin.coroutines.coroutineContext
        val trashDir = File(primary, ".Wayfinder-Trash").path
        val list = RecentFiles.scan(
            roots().map { it.path }, now - RECENT_DAYS * 24L * 3600 * 1000, limit = 200,
            skip = { it.path == trashDir }, isActive = { ctx.isActive },
        )
        recentsCache = now to list
        return list
    }

    private fun promptAuth(host: String) {
        if (_state.value.dialog is Dialog.SmbAuth) return
        val saved = servers.get(host)
        _state.update { it.copy(dialog = Dialog.SmbAuth(host, saved?.user.orEmpty(), saved?.domain.orEmpty())) }
    }

    fun refresh() {
        val t = tab
        if (t.path == FsPath.SMB_ROOT) discovery.scan()
        if (t.path == FsPath.RECENTS_ROOT) recentsCache = null
        refreshPhoneStatus()
        load(t.id, keepSelection = true)
        _state.update { s -> s.copy(tree = s.tree.copy(children = s.tree.children.filterKeys { k -> k in s.tree.expanded })) }
        _state.value.tree.expanded.forEach { ensureChildren(it, force = true) }
    }

    fun refreshAll() {
        refreshMounts()
        val s = _state.value
        s.tabs.filter { it.id != s.activeId }.forEach { stale += it.id }
        load(s.activeId, keepSelection = true)
        if (s.dualPane) s.secondary?.let { load(it.id, keepSelection = true) }
        s.tree.expanded.forEach { ensureChildren(it, force = true) }
    }

    // ---------------------------------------------------------------- tabs

    fun newTab(path: String? = null, activate: Boolean = true) {
        val t = newTabState(path ?: roots().first().path)
        _state.update { s ->
            val idx = s.tabs.indexOfFirst { it.id == s.activeId } + 1
            s.copy(
                tabs = s.tabs.toMutableList().also { it.add(if (activate) idx else s.tabs.size, t) },
                activeId = if (activate) t.id else s.activeId,
            )
        }
        load(t.id); persist()
        if (activate) revealInTree(t.path)
    }

    private fun roots() = _state.value.roots

    fun duplicateTab(id: Long) {
        val src = _state.value.tabs.firstOrNull { it.id == id } ?: return
        newTab(src.path)
    }

    fun closeTab(id: Long) {
        val s = _state.value
        if (s.tabs.size <= 1) { emit(UiEvent.Finish); return }
        val idx = s.tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val closing = s.tabs[idx]
        loadJobs.remove(id)?.cancel()
        val remaining = s.tabs.filter { it.id != id }
        _state.update {
            it.copy(
                tabs = remaining,
                activeId = if (it.activeId == id) remaining[idx.coerceAtMost(remaining.lastIndex)].id else it.activeId,
                closedTabs = (it.closedTabs + closing.path).takeLast(20),
            )
        }
        activateStale(_state.value.activeId); persist()
    }

    fun closeOtherTabs(id: Long) {
        val keep = _state.value.tabs.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(tabs = listOf(keep), activeId = id, closedTabs = (it.closedTabs + it.tabs.filter { t -> t.id != id }.map { t -> t.path }).takeLast(20)) }
        persist()
    }

    fun reopenClosedTab() {
        val p = _state.value.closedTabs.lastOrNull() ?: return
        _state.update { it.copy(closedTabs = it.closedTabs.dropLast(1)) }
        newTab(p)
    }

    fun selectTab(id: Long) {
        if (_state.value.tabs.none { it.id == id }) return
        _state.update { it.copy(activeId = id, rightActive = false) }
        activateStale(id); persist(); revealInTree(tab.path)
    }

    private fun activateStale(id: Long) { if (id in stale) load(id, keepSelection = true) }

    fun cycleTab(delta: Int) {
        val s = _state.value
        val i = s.tabs.indexOfFirst { it.id == s.activeId }
        selectTab(s.tabs[(i + delta).mod(s.tabs.size)].id)
    }

    fun selectTabIndex(i: Int) {
        val s = _state.value
        selectTab(if (i == 8) s.tabs.last().id else s.tabs.getOrNull(i)?.id ?: return)
    }

    fun moveTabBy(id: Long, delta: Int) {
        val i = _state.value.tabs.indexOfFirst { it.id == id }
        if (i >= 0) moveTab(i, i + delta)
    }

    fun moveTab(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.tabs.indices || to !in s.tabs.indices) s
            else s.copy(tabs = s.tabs.toMutableList().also { it.add(to, it.removeAt(from)) })
        }
        persist()
    }

    // ---------------------------------------------------------------- dual pane

    fun toggleDualPane() {
        val s = _state.value
        if (s.dualPane) { _state.update { it.copy(dualPane = false, rightActive = false) }; revealInTree(tab.path); return }
        val sec = s.secondary ?: newTabState(s.mainTab.path).also { _state.update { st -> st.copy(secondary = it) } }
        _state.update { it.copy(dualPane = true) }
        load(sec.id, keepSelection = true)
    }

    /** Focuses the left (false) or right (true) pane. */
    fun activatePane(right: Boolean) {
        val s = _state.value
        if (!s.dualPane || s.rightActive == right) return
        _state.update { it.copy(rightActive = right, quickLook = null) }
        revealInTree(tab.path)
    }

    fun switchPane() = activatePane(!_state.value.rightActive)

    /** F5 / F6 in dual-pane mode: copy or move the selection to the other pane's folder. */
    fun transferToOtherPane(move: Boolean) {
        val other = _state.value.otherPane ?: return
        val dest = other.path
        val sources = selectionPaths().filter { !FsPath.isVirtual(it) }
        if (sources.isEmpty()) { toast("Select items to ${if (move) "move" else "copy"}"); return }
        if (FsPath.isReadOnly(dest) || trash.isInTrash(dest) || dest == tab.path) { toast("Can't ${if (move) "move" else "copy"} there"); return }
        transferWithPrompt(sources, dest, move && sources.none { FsPath.isZip(it) })
    }

    // ---------------------------------------------------------------- navigation

    fun navigate(path: String, id: Long = tab.id, select: String? = null) {
        val p = FsPath.normalize(path)
        updateTab(id) { it.navigatedTo(p) }
        load(id, select = select); persist(); noteRecent(p)
        if (id == tab.id) revealInTree(p)
        _state.update { it.copy(addressEditing = false) }
    }

    private fun noteRecent(p: String) {
        if (FsPath.isVirtual(p)) return
        _state.update { s -> s.copy(recentFolders = (listOf(p) + s.recentFolders.filter { it != p }).take(20)) }
    }

    fun back() = step { it.goBack() }
    fun forward() = step { it.goForward() }
    fun up() { FsPath.parent(tab.path)?.let { navigate(it, select = tab.path) } }

    private fun step(f: (TabState) -> TabState) {
        val id = tab.id
        val from = tab.path
        updateTab(id, f)
        // Going back to an ancestor re-selects the folder we came from.
        load(id, select = from.takeIf { FsPath.parent(it) == tab.path }); persist(); revealInTree(tab.path)
    }

    fun open(entry: FileEntry, newTab: Boolean = false) {
        // Picker mode: double-clicking a file selects it and returns it to the calling app.
        if (picker != null && picker.mode == com.droid.wayfinder.data.PickerMode.OpenFile && !entry.isDir) { pick(listOf(entry)); return }
        when {
            entry.isDir && newTab -> newTab(entry.path, activate = false)
            entry.isDir -> navigate(entry.path)
            isBrowsableArchive(entry) && newTab -> newTab(FsPath.zipRoot(entry.path), activate = false)
            isBrowsableArchive(entry) -> navigate(FsPath.zipRoot(entry.path))
            else -> openFile(entry.path)
        }
    }

    /** Local archives (.zip, .tar.gz, .tgz, .tar) open as read-only folders (archives inside archives aren't supported). */
    fun isBrowsableArchive(e: FileEntry): Boolean {
        if (e.isDir || !FsPath.isLocalFile(e.path)) return false
        val n = e.name.lowercase()
        return n.endsWith(".zip") || n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar")
    }

    fun isBrowsableZip(e: FileEntry) = isBrowsableArchive(e)

    /** "Browse archive": open the selected archive as a folder. */
    fun browseArchive(path: String) = navigate(FsPath.zipRoot(path))

    /** "Show in enclosing folder" for search results and collection items. */
    fun showInEnclosingFolder(path: String) {
        val parent = FsPath.parent(path) ?: return
        navigate(parent, select = path)
    }

    /** "Open with…": always offers the app chooser. */
    fun openWith() {
        val e = tab.selectedEntries(hidden).singleOrNull()?.takeIf { !it.isDir && !it.isServer && !it.isShare } ?: return
        openFile(e.path, chooser = true)
    }

    fun openSelection(newTab: Boolean = false) {
        val sel = tab.selectedEntries(hidden)
        if (sel.size == 1) open(sel.first(), newTab)
        else if (sel.isNotEmpty()) sel.filter { it.isDir }.forEach { open(it, newTab = true) }
    }

    private fun openFile(path: String, chooser: Boolean = false) {
        if (FsPath.isLocalFile(path)) {
            if (isUnsafeToExpose(path)) { toast("This file can't be opened"); return }
            emit(UiEvent.OpenFile(path, chooser))
            return
        }
        // Network files are downloaded and archive entries extracted to the cache, then opened from there.
        val zip = FsPath.isZip(path)
        val dir = File(app.cacheDir, "${if (zip) "zip-open" else "remote-open"}/${cacheKeyFor(path)}").apply { mkdirs() }
        launchOp(if (zip) "Extracting" else "Downloading", { report ->
            ops.transfer(listOf(path), dir.path, move = false, conflict = Conflict.Replace, onProgress = report)
        }) { res ->
            val target = res.firstOrNull()?.target ?: File(dir, FileInfo.sanitizeFileName(FsPath.name(path), "file")).path
            emit(UiEvent.OpenFile(target, chooser))
        }
    }

    private fun cacheKeyFor(path: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- selection & focus

    fun click(path: String, ctrl: Boolean, shift: Boolean) =
        updateTab(tab.id) { it.clicked(path, ctrl, shift, hidden) }

    fun clearSelection() = updateTab(tab.id) { it.copy(selection = emptySet()) }
    fun selectAll() = updateTab(tab.id) { it.selectAll(hidden) }
    fun invertSelection() = updateTab(tab.id) { it.invertSelection(hidden) }
    fun setSelection(paths: Set<String>) = updateTab(tab.id) { it.copy(selection = paths, anchor = paths.firstOrNull() ?: it.anchor) }
    fun setSelectionIfNotSelected(path: String) { if (path !in tab.selection) updateTab(tab.id) { it.copy(selection = setOf(path), anchor = path, focused = path) } }

    fun moveFocus(delta: Int, extend: Boolean = false, keepSelection: Boolean = false) =
        updateTab(tab.id) { it.moveFocus(delta, extend, keepSelection, hidden) }

    fun focusEdge(last: Boolean, extend: Boolean) =
        updateTab(tab.id) { it.moveFocus(if (last) Int.MAX_VALUE / 2 else -Int.MAX_VALUE / 2, extend, false, hidden) }

    fun typeAhead(prefix: String) {
        val t = tab
        val match = t.visible(hidden).firstOrNull { it.name.startsWith(prefix, ignoreCase = true) } ?: return
        updateTab(t.id) { it.copy(selection = setOf(match.path), anchor = match.path, focused = match.path) }
    }

    fun toggleFocusedSelection() {
        val f = tab.focused ?: return
        updateTab(tab.id) { it.copy(selection = if (f in it.selection) it.selection - f else it.selection + f, anchor = f) }
    }

    // ---------------------------------------------------------------- quick look (Space)

    fun toggleQuickLook() {
        if (_state.value.quickLook != null) { closeQuickLook(); return }
        val t = tab
        val target = t.focused?.takeIf { it in t.selection }
            ?: t.selectedEntries(hidden).firstOrNull()?.path
            ?: t.focused
            ?: t.visible(hidden).firstOrNull()?.path
            ?: return
        if (target !in t.selection) updateTab(t.id) { it.copy(selection = setOf(target), anchor = target, focused = target) }
        _state.update { it.copy(quickLook = target) }
    }

    fun closeQuickLook() { if (_state.value.quickLook != null) _state.update { it.copy(quickLook = null) } }

    /** Moves to the previous/next item while Quick Look stays open. */
    fun quickLookMove(delta: Int) {
        val t = tab
        val cur = _state.value.quickLook ?: return
        if (t.focused != cur) updateTab(t.id) { it.copy(focused = cur, anchor = cur, selection = setOf(cur)) }
        moveFocus(delta)
        tab.focused?.let { f -> _state.update { it.copy(quickLook = f) } }
    }

    // ---------------------------------------------------------------- view options

    fun setViewMode(m: ViewMode) { settings.viewMode = m; updateTab(tab.id) { it.copy(viewMode = m) } }

    fun setSort(key: SortKey, asc: Boolean? = null) {
        val t = tab
        val a = asc ?: if (t.sortKey == key) !t.sortAsc else true
        settings.sortKey = key; settings.sortAsc = a
        updateTab(t.id) { it.copy(sortKey = key, sortAsc = a) }
    }

    fun toggleHidden() { settings.showHidden = !hidden; _state.update { it.copy(showHidden = !it.showHidden) } }
    fun toggleSidebar() { settings.sidebarVisible = !_state.value.sidebarVisible; _state.update { it.copy(sidebarVisible = !it.sidebarVisible) } }
    fun togglePreview() { settings.previewVisible = !_state.value.previewVisible; _state.update { it.copy(previewVisible = !it.previewVisible) } }
    fun setSidebarWidth(w: Float) { settings.sidebarWidth = w; _state.update { it.copy(sidebarWidth = w) } }
    fun setGridSize(s: Float) { val v = s.coerceIn(80f, 280f); settings.gridSize = v; _state.update { it.copy(gridSize = v) } }
    fun setGridColumns(c: Int, page: Int) { if (c != _state.value.gridColumns || page != _state.value.pageSize) _state.update { it.copy(gridColumns = c, pageSize = page) } }
    fun setAddressEditing(on: Boolean) = _state.update { it.copy(addressEditing = on) }
    fun setTextFocus(on: Boolean) { if (_state.value.textFocus != on) _state.update { it.copy(textFocus = on) } }
    fun requestSearchFocus() = _state.update { it.copy(focusSearch = it.focusSearch + 1) }
    fun showDialog(d: Dialog?) {
        if (d !is Dialog.Properties) { hashJob?.cancel(); hashJob = null }
        _state.update { it.copy(dialog = d) }
    }
    fun dismissDialog() = showDialog(null)

    // ---------------------------------------------------------------- zoom, theme, columns, windows

    /** Ctrl+= / Ctrl+- in 10% steps; [steps] = 0 resets to 100%. Range 70%–160%. */
    fun zoomBy(steps: Int) {
        val z = if (steps == 0) 1f else (((_state.value.zoom * 10).roundToInt() + steps).coerceIn(7, 16)) / 10f
        settings.zoom = z
        _state.update { it.copy(zoom = z) }
        toast("Interface zoom: ${(z * 100).roundToInt()}%")
    }

    /** System → Light → Dark → System. */
    fun cycleTheme() {
        val next = ThemeMode.entries[(_state.value.theme.ordinal + 1) % ThemeMode.entries.size]
        SharedUi.setTheme(next)
        toast("Theme: ${next.label}")
    }

    fun toggleTagsColumn() { val v = !_state.value.tagsColumn; settings.tagsColumn = v; _state.update { it.copy(tagsColumn = v) } }
    fun togglePermsColumn() { val v = !_state.value.permsColumn; settings.permsColumn = v; _state.update { it.copy(permsColumn = v) } }

    /** Ctrl+N / "Open in new window". */
    fun newWindow(path: String? = null) = emit(UiEvent.NewWindow(path ?: tab.path))

    // ---------------------------------------------------------------- stars & color tags

    private fun markTargets(): List<String> = selectionPaths().filter { !FsPath.isVirtual(it) }.ifEmpty {
        listOf(tab.path).filter { !FsPath.isVirtual(it) }
    }

    /** Stars (or unstars, if all are starred) the selection — the current folder when nothing is selected. */
    fun toggleStar(paths: List<String> = markTargets()) {
        if (paths.isEmpty()) return
        val starring = !paths.all { _state.value.marks.isStarred(it) }
        SharedUi.updateMarks { it.toggleStar(paths) }
        toast(if (starring) "Added to Starred" else "Removed from Starred")
    }

    fun toggleTag(tag: ColorTag, paths: List<String> = markTargets()) {
        if (paths.isNotEmpty()) SharedUi.updateMarks { it.toggleTag(paths, tag) }
    }

    private fun remapMarks(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        SharedUi.updateMarks { m -> pairs.fold(m) { acc, (a, b) -> acc.renamed(a, b) } }
        val curPins = _state.value.pins
        if (curPins.isNotEmpty()) {
            val updatedPins = pairs.fold(curPins) { pins, (from, to) ->
                if (from == to) pins
                else pins.map { p ->
                    when {
                        p == from -> to
                        p.startsWith("$from/") -> to + p.substring(from.length)
                        else -> p
                    }
                }.distinct()
            }
            if (updatedPins != curPins) setPins(updatedPins)
        }
    }

    fun openPalette() = _state.update { it.copy(paletteOpen = true, quickLook = null, addressEditing = false) }
    fun closePalette() = _state.update { it.copy(paletteOpen = false, textFocus = false) }

    // ---------------------------------------------------------------- Miller columns

    /** Listing of an ancestor column (current folder uses the tab's own entries). Errors yield an empty list. */
    suspend fun listForColumn(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try { listAny(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }

    /** Click in an ancestor column: folders become the current folder, files are revealed in their folder. */
    fun columnClick(entry: FileEntry) {
        if (entry.isDir) navigate(entry.path)
        else FsPath.parent(entry.path)?.let { navigate(it, select = entry.path) }
    }

    /** → in columns view: enter the focused/selected folder. */
    fun columnEnter() {
        val t = tab
        val e = t.focused?.let { f -> t.visible(hidden).firstOrNull { it.path == f } } ?: t.selectedEntries(hidden).singleOrNull() ?: return
        if (e.isDir) navigate(e.path)
    }

    // ---------------------------------------------------------------- search

    /** Changes the search scope/category/date filters and re-runs the current search. */
    fun setSearchFilter(f: SearchFilter) {
        updateTab(tab.id) { it.copy(searchFilter = f) }
        setQuery(tab.query)
    }

    fun setQuery(q: String) {
        searchJob?.cancel()
        val id = tab.id
        val root = tab.path
        val filter = tab.searchFilter
        updateTab(id) { it.copy(query = q, searchResults = if (q.isBlank()) null else it.searchResults, searching = q.isNotBlank()) }
        // "Everywhere" walks every storage volume; "This folder" walks the current folder (not virtual views).
        val starts = when {
            filter.scope == SearchScope.Everywhere -> roots().map { it.path }
            FsPath.isVirtual(root) -> emptyList()
            else -> listOf(root)
        }
        if (q.isBlank() || starts.isEmpty()) { updateTab(id) { it.copy(searching = false, searchResults = if (q.isBlank()) null else emptyList()) }; return }
        val needle = foldForSearch(q.trim())
        val now = System.currentTimeMillis()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val found = ArrayList<FileEntry>()
            val queue = ArrayDeque<String>().apply { addAll(starts) }
            val seen = HashSet<String>().apply { addAll(starts) }
            var dirs = 0
            var lastPush = 0L
            try {
                while (queue.isNotEmpty() && found.size < 5000 && dirs < 200_000 && isActive) {
                    val dir = queue.removeFirst()
                    dirs++
                    val list = try { repo.fs(dir).list(dir) } catch (_: Exception) { continue }
                    for (e in list) {
                        // Linked folders are not followed (symlink loops would make the walk endless).
                        if (e.isDir && !e.isLink && (hidden || !e.isHidden) && seen.add(e.path)) queue.add(e.path)
                        if (GooglebookEcosystem.matchesSearchQuery(e, needle) && filter.matches(e, now)) found += e
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPush > 150) {
                        lastPush = now
                        val snapshot = found.toList()
                        updateTab(id) { if (it.query == q) it.copy(searchResults = snapshot) else it }
                    }
                }
            } finally {
                val snapshot = found.toList()
                updateTab(id) { if (it.query == q) it.copy(searchResults = snapshot, searching = false) else it }
            }
        }
    }

    // ---------------------------------------------------------------- tree & pins

    fun toggleTree(path: String) {
        if (path in _state.value.tree.expanded) {
            _state.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded - path)) }
        } else {
            _state.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded + path)) }
            ensureChildren(path)
        }
    }

    private fun expandTree(path: String) {
        _state.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded + path)) }
        ensureChildren(path)
    }

    private fun ensureChildren(path: String, force: Boolean = false) {
        val t = _state.value.tree
        if ((!force && path in t.children) || path in t.loading) return
        _state.update { it.copy(tree = it.tree.copy(loading = it.tree.loading + path)) }
        viewModelScope.launch {
            try {
                val dirs = withContext(Dispatchers.IO) { repo.list(path).filter { it.isDir }.sortedWith { a, b -> naturalCompare(a.name, b.name) } }
                _state.update { it.copy(tree = it.tree.copy(children = it.tree.children + (path to dirs), loading = it.tree.loading - path)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteAuthException) {
                _state.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded - path, loading = it.tree.loading - path)) }
                promptAuth(e.host)
            } catch (e: Exception) {
                _state.update { it.copy(tree = it.tree.copy(children = it.tree.children + (path to emptyList()), loading = it.tree.loading - path)) }
            }
        }
    }

    /** Alt+Shift+L: expand the tree down to the current folder and scroll the sidebar to it. */
    fun locateInTree() {
        if (!_state.value.sidebarVisible) toggleSidebar()
        revealInTree(tab.path)
        _state.update { it.copy(treeLocate = it.treeLocate + 1) }
    }

    fun collapseTree() = _state.update { it.copy(tree = it.tree.copy(expanded = emptySet())) }

    private fun revealInTree(p: String) {
        // Inside an archive, reveal the folder that contains the .zip.
        val path = FsPath.zipSplit(p)?.first ?: p
        if (FsPath.isVirtual(path)) return
        // Stop at the sidebar roots so we never list (and maybe prompt for) folders above a mount's base path.
        val rootPaths = _state.value.roots.map { it.path } +
            _state.value.network.map { it.path } +
            _state.value.mounts.map { it.path } +
            _state.value.driveAccounts.mapNotNull { it.mountPath } +
            listOfNotNull(_state.value.pairedPhone?.path).filter { it != FsPath.PHONE_ROOT }
        if (rootPaths.none { FsPath.isWithin(it, path) }) return
        var cur = FsPath.parent(path)
        while (cur != null && cur != FsPath.SMB_ROOT) {
            if (cur !in _state.value.tree.expanded) expandTree(cur) else ensureChildren(cur)
            if (cur in rootPaths) break
            cur = FsPath.parent(cur)
        }
    }

    fun togglePin(path: String) { if (path in _state.value.pins) unpin(path) else pin(path) }

    /** Pins all [paths] unless every one is already pinned, in which case they are all unpinned. */
    fun togglePins(paths: List<String> = markTargets()) {
        val valid = paths.filter { !FsPath.isVirtual(it) }
        if (valid.isEmpty()) return
        if (valid.any { FsPath.isZip(it) }) { toast("Items inside archives can't be pinned"); return }
        val cur = _state.value.pins
        if (valid.all { it in cur }) {
            val next = cur.filter { it !in valid.toSet() }
            setPins(next)
            toast(if (valid.size == 1) "Unpinned ${FsPath.name(valid[0])}" else "Unpinned ${valid.size} items")
        } else {
            val added = valid.filter { it !in cur }
            setPins(cur + added)
            toast(if (added.size == 1) "Pinned ${FsPath.name(added[0])}" else "Pinned ${added.size} items")
        }
    }

    fun pin(path: String) {
        if (path in _state.value.pins || FsPath.isVirtual(path)) return
        if (FsPath.isZip(path)) { toast("Folders inside archives can't be pinned"); return }
        setPins(_state.value.pins + path)
        toast("Pinned ${FsPath.name(path)}")
    }

    fun unpin(path: String) {
        if (path !in _state.value.pins) return
        setPins(_state.value.pins - path)
        toast("Unpinned ${FsPath.name(path)}")
    }

    /** Opens a pinned folder or file from the sidebar (navigates into folders/archives, opens and reveals regular files). */
    fun openPinned(path: String, newTab: Boolean = false) {
        if (FsPath.isLocalFile(path)) {
            val f = File(path)
            if (f.isFile) {
                val entry = FileEntry(path = path, name = f.name, isDir = false, size = f.length(), modified = f.lastModified())
                if (isBrowsableArchive(entry)) {
                    if (newTab) newTab(FsPath.zipRoot(path), activate = false) else navigate(FsPath.zipRoot(path))
                } else {
                    val parent = FsPath.parent(path)
                    if (newTab && parent != null) {
                        newTab(parent, activate = true)
                    } else {
                        if (parent != null && tab.path != parent) navigate(parent, select = path)
                        else if (parent != null) updateTab(tab.id) { it.copy(selection = setOf(path), anchor = path, focused = path) }
                        openFile(path)
                    }
                }
                return
            }
            if (newTab) newTab(path, activate = false) else navigate(path)
            return
        }
        if (newTab) {
            newTab(path, activate = false)
            return
        }
        viewModelScope.launch {
            val stat = withContext(Dispatchers.IO) { runCatching { repo.fs(path).stat(path) }.getOrNull() }
            if (stat != null && !stat.isDir) {
                val parent = FsPath.parent(path)
                if (parent != null && tab.path != parent) navigate(parent, select = path)
                openFile(path)
            } else {
                navigate(path)
            }
        }
    }

    fun movePin(path: String, delta: Int) {
        val l = _state.value.pins.toMutableList()
        val i = l.indexOf(path)
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        setPins(l)
    }

    private fun setPins(l: List<String>) { settings.pins = l; _state.update { it.copy(pins = l) } }

    // ---------------------------------------------------------------- operations

    private fun <T> launchOp(title: String, work: suspend (report: (Progress) -> Unit) -> T, after: (T) -> Unit = {}) {
        val id = ++opSeq
        _state.update { it.copy(ops = it.ops + OpStatus(id, Progress(title))) }
        opJobs[id] = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    work { p -> _state.update { s -> s.copy(ops = s.ops.map { if (it.id == id) it.copy(progress = p) else it }) } }
                }
                after(result)
            } catch (e: CancellationException) {
                toast("Cancelled")
            } catch (e: Throwable) {
                toast(e.message ?: "$title failed")
            } finally {
                opJobs.remove(id)
                _state.update { s -> s.copy(ops = s.ops.filter { it.id != id }) }
                refreshAll()
            }
        }
    }

    fun cancelOp(id: Long) { opJobs[id]?.cancel() }

    private fun selectionPaths(): List<String> = tab.selectedEntries(hidden).map { it.path }

    fun copy() = clip(false)
    fun cut() = clip(true)

    private fun clip(cut: Boolean) {
        val p = selectionPaths().filter { !FsPath.isVirtual(it) }
        if (p.isEmpty()) return
        if (cut && p.any { FsPath.isZip(it) }) { toast("Items in an archive can only be copied"); return }
        _state.update { it.copy(clipboard = Clipboard(p, cut)) }
        toast(if (cut) "${p.size} item(s) ready to move" else "${p.size} item(s) copied")
    }

    fun paste() {
        val c = _state.value.clipboard ?: return
        val dest = if (tab.path == FsPath.PHONE_ROOT) {
            quickShareDir().absolutePath
        } else tab.path
        if (FsPath.isReadOnly(dest) || trash.isInTrash(dest)) { toast(if (FsPath.isZip(dest)) "Archives are read-only" else "Can't paste here"); return }
        transferWithPrompt(c.paths, dest, move = c.cut)
    }

    fun dropOn(targetDir: String, paths: List<String>, copy: Boolean) {
        // Dropping onto Starred or a color tag marks the items instead of moving them.
        if (targetDir == FsPath.STARRED_ROOT) {
            SharedUi.updateMarks { m -> m.copy(starred = m.starred + paths) }
            toast("Added to Starred"); return
        }
        FsPath.tagOf(targetDir)?.let { t ->
            SharedUi.updateMarks { m -> m.copy(tags = m.tags + paths.associateWith { p -> m.tagsOf(p) + t }) }
            toast("Tagged ${t.label}"); return
        }
        if (targetDir == FsPath.TRASH_ROOT) {
            val local = paths.filter { !FsPath.isRemote(it) && !trash.isInTrash(it) }
            if (local.isNotEmpty()) moveToTrash(local)
            return
        }
        val dest = if (targetDir == FsPath.PHONE_ROOT) {
            quickShareDir().absolutePath
        } else targetDir
        val real = paths.filter { p -> FsPath.parent(p) != dest && !FsPath.isWithin(p, dest) }
        if (FsPath.isZip(dest)) { toast("Archives are read-only"); return }
        if (real.isEmpty() || FsPath.isVirtual(dest) || trash.isInTrash(dest)) return
        // Items dragged out of an archive are always copied.
        transferWithPrompt(real, dest, move = !copy && real.none { FsPath.isZip(it) })
    }

    private fun transferWithPrompt(sources: List<String>, dest: String, move: Boolean) {
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                try { ops.conflicts(sources.filter { FsPath.parent(it) != dest }, dest) } catch (_: Exception) { emptyList() }
            }
            if (names.isEmpty()) runTransfer(sources, dest, move, Conflict.KeepBoth)
            else showDialog(Dialog.ConflictPrompt(sources, dest, move, names))
        }
    }

    fun resolveConflict(d: Dialog.ConflictPrompt, how: Conflict) {
        dismissDialog()
        runTransfer(d.sources, d.dest, d.move, how)
    }

    private fun runTransfer(sources: List<String>, dest: String, move: Boolean, how: Conflict) {
        launchOp(if (move) "Moving" else "Copying", { report -> ops.transfer(sources, dest, move, how, report) }) { res ->
            if (res.isNotEmpty()) {
                record(if (move) Undo.Moves(res) else Undo.Created(res.map { it.target }))
                if (move) remapMarks(res.map { it.source to it.target })
                if (move) _state.update { it.copy(clipboard = null) }
                // Select the results in whichever visible pane shows the destination (e.g. the other pane after F5/F6).
                val s = _state.value
                val target = listOfNotNull(s.active, s.otherPane).firstOrNull { it.path == dest } ?: s.active
                updateTab(target.id) { it.copy(selection = res.map { r -> r.target }.toSet()) }
            }
        }
    }

    fun requestDelete(permanent: Boolean = false) {
        val paths = selectionPaths()
        if (paths.isEmpty()) return
        if (paths.any { FsPath.isZip(it) }) { toast("Archives are read-only"); return }
        val p = tab.path
        // Network and provider items can't go to the local Trash.
        val mustBePermanent = permanent || FsPath.isRemote(p) || paths.any { FsPath.isRemote(it) } || trash.isInTrash(p)
        if (mustBePermanent) showDialog(Dialog.Delete(paths)) else moveToTrash(paths)
    }

    private fun moveToTrash(paths: List<String>) {
        launchOp("Moving to Trash", { _ -> trash.moveToTrash(paths) }) { res ->
            record(Undo.TrashOp(res))
            toast(if (res.size == 1) "Moved \"${FsPath.name(res[0].original)}\" to Trash" else "Moved ${res.size} items to Trash", undo = true)
        }
    }

    fun confirmDelete(paths: List<String>) {
        dismissDialog()
        launchOp("Deleting", { report ->
            paths.forEach { p -> if (trash.isInTrash(p)) trash.deletePermanently(p) else ops.deletePermanently(listOf(p), report) }
        })
    }

    fun restoreFromTrash() {
        val paths = selectionPaths()
        launchOp("Restoring", { _ -> paths.map { trash.restore(it) } }) { toast("Restored ${it.size} item(s)") }
    }

    fun emptyTrash() { launchOp("Emptying Trash", { _ -> trash.empty() }) { toast("Trash emptied") } }

    fun requestRename() {
        val e = tab.selectedEntries(hidden).singleOrNull() ?: tab.focused?.let { f -> tab.visible(hidden).firstOrNull { it.path == f } } ?: return
        if (e.isServer || e.isShare || trash.isInTrash(e.path) || FsPath.isZip(e.path)) return
        showDialog(Dialog.Rename(e.path, e.name))
    }

    fun rename(path: String, newName: String) {
        dismissDialog()
        val name = newName.trim()
        if (name.isEmpty() || name == FsPath.name(path)) return
        FsPath.nameError(name)?.let { toast(it); return }
        launchOp("Renaming", { _ -> repo.fs(path).rename(path, name) }) { to ->
            record(Undo.Renames(listOf(path to to)))
            remapMarks(listOf(path to to))
            updateTab(tab.id) { it.copy(selection = setOf(to), focused = to, anchor = to) }
        }
    }

    /** Ctrl+Shift+R: batch rename the selection (all visible items when nothing is selected). */
    fun requestBatchRename() {
        val t = tab
        if (FsPath.isReadOnly(t.path) || trash.isInTrash(t.path)) return
        val items = t.selectedEntries(hidden).ifEmpty { t.visible(hidden) }.filter { !it.isServer && !it.isShare }
        if (items.isEmpty()) return
        showDialog(Dialog.BatchRename(t.path, items, t.entries.map { it.name }))
    }

    /** Applies a batch rename plan (old name → new name within [dir]) as one undoable step. */
    fun batchRename(dir: String, plan: List<RenamePlan>) {
        dismissDialog()
        if (plan.any { it.changed && (it.conflict != null || FsPath.nameError(it.new) != null || !FsPath.isSafeName(it.old)) }) {
            toast("Some new names aren't valid"); return
        }
        val pairs = plan.filter { it.changed }.map { FsPath.child(dir, it.old) to FsPath.child(dir, it.new) }
        if (pairs.isEmpty()) return
        launchOp("Renaming", { _ -> applyRenames(pairs) }) { done ->
            record(Undo.Renames(done))
            val targets = done.map { it.second }.toSet()
            updateTab(tab.id) { it.copy(selection = targets) }
            toast("Renamed ${done.size} item(s)")
        }
    }

    /**
     * Renames each `first` to the file name of `second` (same folder). Goes through temporary names so that
     * swaps and case-only changes work; returns the pairs actually applied.
     */
    private fun applyRenames(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
        val tmp = pairs.mapIndexed { i, (from, _) ->
            val fs = repo.fs(from)
            from to fs.rename(from, ".wf-rename-${System.nanoTime()}-$i")
        }
        val out = ArrayList<Pair<String, String>>()
        var error: Exception? = null
        tmp.forEachIndexed { i, (from, t) ->
            val wanted = FsPath.name(pairs[i].second)
            try {
                out += from to repo.fs(t).rename(t, wanted)
            } catch (e: Exception) {
                // Put it back under its original name so nothing is left with a temporary name.
                runCatching { repo.fs(t).rename(t, FsPath.name(from)) }
                error = error ?: e
            }
        }
        val err = error
        if (err != null && out.isEmpty()) throw err
        error?.let { toast(it.message ?: "Some items couldn't be renamed") }
        remapMarks(out)
        return out
    }

    /** Ctrl+D: duplicate the selection next to the originals ("name copy.ext"). */
    fun duplicate() {
        val paths = selectionPaths().filter { !FsPath.isReadOnly(it) && !trash.isInTrash(it) }
        val t = tab
        if (paths.isEmpty() || FsPath.isReadOnly(t.path) || trash.isInTrash(t.path)) return
        launchOp("Duplicating", { report -> ops.duplicate(paths, report) }) { out ->
            record(Undo.Created(out))
            updateTab(t.id) { it.copy(selection = out.toSet(), focused = out.lastOrNull(), anchor = out.firstOrNull()) }
        }
    }

    fun requestNewItem(folder: Boolean) {
        if (FsPath.isReadOnly(tab.path)) { if (FsPath.isZip(tab.path)) toast("Archives are read-only"); return }
        showDialog(Dialog.NewItem(folder))
    }

    fun createItem(name: String, folder: Boolean) {
        dismissDialog()
        val n = name.trim()
        if (n.isEmpty()) return
        // Typed names must stay inside the current folder ("../x", "a/b", NUL, ...).
        FsPath.nameError(n)?.let { toast(it); return }
        val dir = tab.path
        launchOp("Creating", { _ ->
            val target = FsPath.child(dir, n)
            if (repo.fs(dir).exists(target)) throw java.io.IOException("\"$n\" already exists")
            if (folder) repo.fs(dir).mkdir(target) else repo.fs(dir).createFile(target)
            target
        }) { target ->
            record(Undo.Created(listOf(target)))
            updateTab(tab.id) { it.copy(selection = setOf(target), focused = target, anchor = target) }
        }
    }

    fun requestCompress() {
        val paths = selectionPaths()
        if (paths.isEmpty() || FsPath.isReadOnly(tab.path)) return
        val suggested = if (paths.size == 1) FsPath.name(paths[0]).substringBeforeLast('.', FsPath.name(paths[0])) else FsPath.name(tab.path).ifEmpty { "Archive" }
        showDialog(Dialog.Compress(paths, suggested))
    }

    fun compress(sources: List<String>, name: String) {
        dismissDialog()
        val dir = tab.path
        val wanted = name.trim().removeSuffix(".zip") + ".zip"
        FsPath.nameError(wanted)?.let { toast(it); return }
        launchOp("Compressing", { report ->
            val fileName = ops.uniqueName(dir, wanted)
            ops.compress(sources, FsPath.child(dir, fileName), report)
        }) { zip ->
            record(Undo.Created(listOf(zip)))
            updateTab(tab.id) { it.copy(selection = setOf(zip), focused = zip) }
        }
    }

    fun extractSelection() {
        val z = tab.selectedEntries(hidden).filter { isBrowsableArchive(it) }
        if (z.isEmpty() || FsPath.isReadOnly(tab.path)) return
        val dir = tab.path
        launchOp("Extracting", { report -> z.map { ops.extract(it.path, dir, report) } }) { out ->
            record(Undo.Created(out))
            updateTab(tab.id) { it.copy(selection = out.toSet()) }
        }
    }

    fun share() {
        val files = tab.selectedEntries(hidden).filter { !it.isDir && FsPath.isLocalFile(it.path) }.map { it.path }
        if (files.isEmpty()) { toast("Select local files to share"); return }
        if (files.any { isUnsafeToExpose(it) }) { toast("This file can't be shared"); return }
        emit(UiEvent.Share(files))
    }

    /** Copies the selected items into the Googlebook Linux development environment (`Linux files`). */
    fun shareWithLinux() {
        val linuxDest = _state.value.linuxRootPath ?: GooglebookEcosystem.discoverLinuxRoot(app, primary)?.path
        if (linuxDest == null) {
            toast("Linux development environment is not enabled")
            return
        }
        val sources = selectionPaths().filter { !FsPath.isVirtual(it) }
        if (sources.isEmpty()) {
            toast("Select items to copy to Linux files")
            return
        }
        if (tab.path == linuxDest) {
            toast("Already in Linux files")
            return
        }
        transferWithPrompt(sources, linuxDest, move = false)
    }

    /** Switches to the selected managed Work / Personal profile via [CrossProfileApps]. */
    fun openWorkProfile(target: WorkProfileTarget) {
        if (!GooglebookEcosystem.switchToWorkProfile(app, target)) {
            toast("Unable to switch to ${target.label}")
        }
    }

    fun copyPathToClipboard() {
        val p = selectionPaths().ifEmpty { listOf(tab.path) }
        emit(UiEvent.CopyText(p.joinToString("\n")))
    }

    fun showProperties() {
        val sel = tab.selectedEntries(hidden)
        viewModelScope.launch {
            val entries = sel.ifEmpty {
                listOfNotNull(withContext(Dispatchers.IO) { try { repo.fs(tab.path).stat(tab.path) } catch (_: Exception) { null } })
            }
            if (entries.isEmpty()) return@launch
            showDialog(Dialog.Properties(entries))
            fun patch(f: (Dialog.Properties) -> Dialog.Properties) =
                _state.update { s -> (s.dialog as? Dialog.Properties)?.takeIf { it.entries == entries }?.let { s.copy(dialog = f(it)) } ?: s }
            val single = entries.singleOrNull()
            if (single != null && FsPath.isLocalFile(single.path)) {
                val (posix, space) = withContext(Dispatchers.IO) { FileInfo.lstat(single.path) to FileInfo.space(single.path) }
                patch { it.copy(posix = posix, space = space) }
            } else if (entries.all { FsPath.isLocalFile(it.path) }) {
                val space = withContext(Dispatchers.IO) { FileInfo.space(entries.first().path) }
                patch { it.copy(space = space) }
            }
            if (single != null && !single.isDir && !single.isServer && !single.isShare) {
                patch { it.copy(hashProgress = "Computing SHA-256 & CRC32…") }
                hashJob?.cancel()
                hashJob = viewModelScope.launch {
                    try {
                        val sums = withContext(Dispatchers.IO) {
                            FileInfo.checksums(repo.fs(single.path).openInput(single.path)) { done ->
                                val pct = if (single.size > 0) " ${(done * 100 / single.size).coerceIn(0, 100)}%" else ""
                                patch { it.copy(hashProgress = "Computing SHA-256 & CRC32…$pct") }
                            }
                        }
                        patch { it.copy(checksums = sums, hashProgress = null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        patch { it.copy(hashProgress = "Couldn't read file: ${e.message}") }
                    }
                }
            }
            val (bytes, items) = withContext(Dispatchers.IO) {
                try { ops.measure(entries.map { it.path }) } catch (_: Exception) { 0L to 0 }
            }
            patch { it.copy(bytes = bytes, items = items) }
        }
    }

    fun copyValue(text: String, what: String) = emit(UiEvent.CopyText(text, what))

    // ---------------------------------------------------------------- import (drop from other apps)

    /** Copies content URIs dropped from other apps into [dest]; [onDone] releases the drag permissions. */
    fun importUris(uris: List<Uri>, dest: String, onDone: () -> Unit = {}) {
        if (uris.isEmpty()) { onDone(); return }
        val destDir = if (dest == FsPath.PHONE_ROOT) {
            quickShareDir().absolutePath
        } else dest
        if (FsPath.isReadOnly(destDir) || trash.isInTrash(destDir)) { toast("Can't copy files here"); onDone(); return }
        val cr = app.contentResolver
        launchOp("Importing", { report ->
            val out = ArrayList<String>()
            try {
                uris.forEachIndexed { i, uri ->
                    // Only content:// URIs from other apps: file:// (or our own provider) would let the dragging app
                    // make us copy our private files (e.g. saved server credentials) to shared storage.
                    if (!isImportable(uri)) throw java.io.IOException("Can't import this item (unsupported source)")
                    val raw = runCatching {
                        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
                    val name = FileInfo.sanitizeFileName(raw, "Imported file ${i + 1}")
                    report(Progress("Importing", doneItems = i, totalItems = uris.size, current = name))
                    val target = FsPath.child(destDir, ops.uniqueName(destDir, name))
                    val input = cr.openInputStream(uri) ?: throw java.io.IOException("Can't read $name")
                    input.use { inp ->
                        repo.fs(destDir).openOutput(target).use { o ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive() // cancellable (endless / huge streams)
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    out += target
                }
            } finally { onDone() }
            out
        }) { out ->
            if (out.isNotEmpty()) {
                record(Undo.Created(out))
                listOfNotNull(_state.value.active, _state.value.otherPane).firstOrNull { it.path == dest }?.let { t ->
                    updateTab(t.id) { it.copy(selection = out.toSet()) }
                }
                toast(if (out.size == 1) "Copied \"${FsPath.name(out[0])}\" here" else "Copied ${out.size} items here")
            }
        }
    }

    /** content:// URIs of other apps only (never file://, never our own FileProvider). */
    private fun isImportable(uri: Uri): Boolean =
        uri.scheme.equals("content", ignoreCase = true) && uri.authority != null && uri.authority != app.packageName + ".fileprovider"

    // ---------------------------------------------------------------- undo / redo

    private fun syncUndo() = _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }

    /** Records a new user operation: it becomes undoable and invalidates the redo history. */
    private fun record(u: Undo) {
        undoStack.addLast(u)
        while (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        syncUndo()
    }

    fun undo() = replay(undoStack, redoStack, "Undoing")
    fun redo() = replay(redoStack, undoStack, "Redoing")

    private fun replay(from: ArrayDeque<Undo>, to: ArrayDeque<Undo>, title: String) {
        val u = from.removeLastOrNull() ?: return
        syncUndo()
        launchOp(title, { report -> reverse(u, report) }) { inverse ->
            if (inverse != null) to.addLast(inverse)
            syncUndo()
        }
    }

    /** Reverses [u] on disk and returns the record that would reverse it again (null if not reversible). */
    private suspend fun reverse(u: Undo, report: (Progress) -> Unit): Undo? = when (u) {
        is Undo.Renames -> Undo.Renames(applyRenames(u.pairs.map { (from, to) -> to to from }))
        is Undo.Moves -> Undo.Moves(
            u.items.groupBy { FsPath.parent(it.source)!! }.flatMap { (dir, list) ->
                ops.transfer(list.map { it.target }, dir, true, Conflict.KeepBoth, report)
            }.also { back -> remapMarks(back.map { it.source to it.target }) },
        )
        is Undo.TrashOp -> Undo.Created(u.items.map { trash.restore(it.trashed) })
        is Undo.Created -> {
            // Local items go to the Trash so the step can be redone; network items are deleted.
            val (remote, local) = u.paths.partition { FsPath.isRemote(it) }
            if (remote.isNotEmpty()) ops.deletePermanently(remote, report)
            if (local.isEmpty()) null else Undo.TrashOp(trash.moveToTrash(local))
        }
    }

    // ---------------------------------------------------------------- servers (SMB, WebDAV, FTP)

    fun showConnectDialog(initial: ConnectConfig? = null) = showDialog(Dialog.Connect(initial))

    /** Connect dialog values for a saved server (the stored password is reused when the field is left empty). */
    fun connectConfigFor(sv: SavedServer) =
        ConnectConfig(sv.protocol, sv.host, sv.port, sv.path, sv.user, "", sv.domain, sv.guest, sv.alias, remember = sv.hasPassword || sv.guest)

    private fun liveFs(proto: Protocol): RemoteFileSystem = when {
        proto == Protocol.SMB -> smb
        proto.isWebDav -> dav
        else -> ftp
    }

    private fun disconnectKey(key: String) {
        val proto = if (key.contains("://")) RemoteUri.protocolOf(key) else Protocol.SMB
        proto?.let { liveFs(it).disconnect(key) }
    }

    /** Credentials from the dialog; an empty password falls back to the remembered one for the same user. */
    private fun effectiveCred(cfg: ConnectConfig): SmbCred {
        val c = cfg.cred
        if (cfg.guest || c.password.isNotEmpty() || c.user.isBlank()) return c
        val saved = servers.get(cfg.address.credKey)
        return if (saved != null && saved.user == c.user) c.copy(password = saved.password) else c
    }

    /** "Test connection": signs in on a throw-away connection and lists the location; returns the latency in ms. */
    suspend fun testConnection(cfg: ConnectConfig): Result<Long> = withContext(Dispatchers.IO) {
        val addr = cfg.address
        if (!RemoteUri.isValidHost(addr.host)) return@withContext Result.failure(java.io.IOException("Enter a valid server address"))
        val cred = effectiveCred(cfg)
        val creds = CredentialProvider { cred }
        val fs: RemoteFileSystem = when {
            addr.protocol == Protocol.SMB -> SmbFileSystem(creds)
            addr.protocol.isWebDav -> WebDavFileSystem(creds)
            else -> FtpFileSystem(creds)
        }
        val t0 = System.nanoTime()
        try {
            fs.list(addr.rootPath)
            Result.success((System.nanoTime() - t0) / 1_000_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteAuthException) {
            Result.failure(java.io.IOException(if (cred.user.isBlank()) "The server requires a user name and password" else "Sign-in failed: check the user name and password"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { fs.disconnect(addr.credKey) }
        }
    }

    /** "Connect & Mount": remembers the server (alias, credentials), shows it in the sidebar and opens it. */
    fun connectMount(cfg: ConnectConfig) {
        val addr = cfg.address
        if (!RemoteUri.isValidHost(addr.host)) { toast("Enter a valid server address, e.g. smb://nas/media"); return }
        dismissDialog()
        val cred = effectiveCred(cfg)
        servers.save(
            SavedServer(
                key = addr.credKey, user = cred.user, domain = cred.domain, hasPassword = false, protocol = addr.protocol,
                host = addr.host, port = addr.port, path = addr.path, alias = cfg.alias.trim(), guest = cfg.guest, mounted = true,
            ),
            cred, cfg.remember,
        )
        disconnectKey(addr.credKey)
        if (addr.protocol == Protocol.SMB) recentHosts += addr.host
        refreshMounts()
        navigate(addr.rootPath)
    }

    /**
     * Address bar input for remote locations (`smb://`, `\\server\share`, `dav(s)://`, `http(s)://`, `ftp(s)://`,
     * `saf://`). User info in the URL becomes session credentials. Returns false if [input] isn't an address.
     */
    fun openAddress(input: String): Boolean {
        val t = input.trim()
        if (FsPath.isSaf(t)) { navigate(t); return true }
        val a = RemoteUri.parse(t) ?: return false
        if (a.user != null) {
            val stored = servers.get(a.credKey)
            if (a.password != null || stored == null || stored.user != a.user) servers.putSession(a.credKey, SmbCred(a.user, a.password.orEmpty()))
            disconnectKey(a.credKey)
        }
        if (a.protocol == Protocol.SMB) { recentHosts += a.host; _state.update { it.copy(network = networkEntries()) } }
        navigate(a.rootPath)
        return true
    }

    fun submitAuth(host: String, user: String, password: String, domain: String, remember: Boolean) {
        dismissDialog()
        servers.put(host, SmbCred(user, password, domain), remember)
        disconnectKey(host)
        val root = if (host.contains("://")) host else "smb://$host"
        _state.update { s ->
            s.copy(tree = s.tree.copy(children = s.tree.children.filterKeys { !FsPath.isWithin(root, it) }))
        }
        (_state.value.tabs + listOfNotNull(_state.value.secondary)).filter { FsPath.isWithin(root, it.path) }.forEach { load(it.id) }
        _state.value.tree.expanded.filter { FsPath.isWithin(root, it) }.forEach { ensureChildren(it, force = true) }
        refreshMounts()
    }

    fun forgetServer(host: String) {
        servers.forget(host); disconnectKey(host); recentHosts.remove(host)
        refreshMounts()
    }

    /** Sends tabs showing [root] (or below) back to Network and prunes the sidebar tree. */
    private fun leave(root: String) {
        val s = _state.value
        (s.tabs + listOfNotNull(s.secondary)).filter { FsPath.isWithin(root, it.path) }.forEach { navigate(FsPath.SMB_ROOT, it.id) }
        _state.update { st ->
            st.copy(tree = st.tree.copy(
                children = st.tree.children.filterKeys { !FsPath.isWithin(root, it) },
                expanded = st.tree.expanded.filterTo(HashSet()) { !FsPath.isWithin(root, it) },
            ))
        }
    }

    /** Eject: disconnects and hides a mount (saved servers stay available in the Connect dialog). */
    fun eject(m: Mount) {
        if (m.saf) unmountSaf(m.key) else { servers.setMounted(m.key, false); disconnectKey(m.key) }
        leave(m.path)
        refreshMounts()
        toast(if (m.saf) "Unmounted ${m.label}" else "Disconnected ${m.label}")
    }

    /** Forget: removes the mount and any remembered credentials. */
    fun forgetMount(m: Mount) {
        if (m.saf) unmountSaf(m.key) else { servers.forget(m.key); disconnectKey(m.key); recentHosts.remove(m.key) }
        leave(m.path)
        refreshMounts()
    }

    // ---------------------------------------------------------------- cloud / SAF mounts & Google Drive

    fun requestGoogleAccountSignIn() = emit(UiEvent.PickGoogleAccount)

    fun onGoogleAccountPicked(email: String?) {
        refreshMounts()
        if (!email.isNullOrBlank()) {
            val existing = _state.value.driveAccounts.firstOrNull { it.email.equals(email, ignoreCase = true) }
            if (existing != null && existing.isMounted && existing.mountPath != null) {
                navigate(existing.mountPath)
            } else {
                requestMountSaf(email)
            }
        }
    }

    fun openDriveAccount(acc: com.droid.wayfinder.data.DriveAccount) {
        if (acc.isMounted && acc.mountPath != null) {
            navigate(acc.mountPath)
        } else {
            requestMountSaf(acc.email)
        }
    }

    fun openPairedPhone(phone: com.droid.wayfinder.data.PairedPhone) {
        if (phone.isMounted && phone.path != FsPath.PHONE_ROOT) {
            navigate(phone.path)
        } else {
            navigate(FsPath.PHONE_ROOT)
        }
    }

    fun requestMountPhoneStorage(phone: com.droid.wayfinder.data.PairedPhone? = _state.value.pairedPhone) {
        // QuickAccessDocumentsProvider omits FLAG_SUPPORTS_IS_CHILD on user builds, so folder-tree
        // mounting via ACTION_OPEN_DOCUMENT_TREE targets MTP (or another tree-capable provider).
        val targetAuth = phone?.authority?.takeIf { it != PhoneDetector.QUICK_ACCESS_AUTHORITY }
            ?: PhoneDetector.MTP_AUTHORITY
        val initialUri = runCatching {
            android.provider.DocumentsContract.buildRootsUri(targetAuth)
        }.getOrNull()
        emit(UiEvent.PickDocumentTree(initialUri = initialUri))
    }

    fun requestImportFromPhone() {
        val initialUri = runCatching {
            android.provider.DocumentsContract.buildRootsUri(PhoneDetector.QUICK_ACCESS_AUTHORITY)
        }.getOrNull()
        emit(UiEvent.ImportPhoneDocuments(initialUri = initialUri))
    }

    fun requestMountLinuxFiles() {
        val auth = _state.value.linuxProviderAuthority
            ?: GooglebookEcosystem.findInstalledLinuxProviderAuthority(app)
        val initialUri = auth?.let {
            runCatching { android.provider.DocumentsContract.buildRootsUri(it) }.getOrNull()
        }
        emit(UiEvent.PickDocumentTree(initialUri = initialUri))
    }

    /**
     * Streams documents selected via `ACTION_OPEN_DOCUMENT` (such as wireless Cross-Device
     * `QuickAccessDocumentsProvider` items on `user` builds) into the active writable local
     * directory, or `<primaryStorage>/Download` when viewing a virtual/read-only root.
     */
    fun importExternalUrisToDownloads(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val currentPath = tab.path
        val destDir = if (
            FsPath.isLocalFile(currentPath) &&
            !FsPath.isReadOnly(currentPath) &&
            !trash.isInTrash(currentPath) &&
            File(currentPath).isDirectory
        ) {
            currentPath
        } else {
            File(primary, Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }.path
        }
        launchOp(
            title = "Importing",
            work = { report ->
                val created = mutableListOf<String>()
                for ((idx, uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    var displayName: String? = null
                    var declaredSize = 0L
                    runCatching {
                        app.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null,
                            null,
                            null,
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx).coerceAtLeast(0L)
                            }
                        }
                    }
                    val baseName = sanitizeImportedFileName(
                        displayName ?: uri.lastPathSegment?.substringAfterLast(':'),
                    )
                    val finalName = ops.uniqueName(destDir, baseName)
                    val targetFile = File(destDir, finalName)
                    report(Progress("Importing", 0L, declaredSize, idx, uris.size, finalName))
                    try {
                        val inp = app.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("Couldn't read \"$finalName\"")
                        inp.use { src ->
                            targetFile.outputStream().use { dst ->
                                val buf = ByteArray(128 * 1024)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    dst.write(buf, 0, n)
                                    copied += n
                                    report(Progress("Importing", copied, declaredSize, idx, uris.size, finalName))
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        runCatching { targetFile.delete() }
                        throw t
                    }
                    created += targetFile.path
                }
                created
            },
            after = { imported ->
                if (imported.isNotEmpty()) {
                    record(Undo.Created(imported))
                    if (tab.path != destDir) {
                        navigate(destDir, select = imported.first())
                    }
                    updateTab(tab.id) {
                        it.copy(selection = imported.toSet(), focused = imported.first())
                    }
                    toast("Imported ${imported.size} file(s) to ${FsPath.name(destDir)}", undo = true)
                }
            },
        )
    }

    fun requestMountSaf(accountEmail: String? = null) {
        if (settings.showSafMountTip) {
            showDialog(Dialog.SafMountTip(accountEmail))
        } else {
            confirmSafMountTip(dontShowAgain = false, accountEmail = accountEmail)
        }
    }

    fun confirmSafMountTip(dontShowAgain: Boolean, accountEmail: String? = null) {
        if (dontShowAgain) settings.showSafMountTip = false
        dismissDialog()
        pendingMountAccount = accountEmail
        val initialUri = runCatching {
            android.provider.DocumentsContract.buildRootsUri(com.droid.wayfinder.data.GoogleDriveManager.DOCS_AUTHORITY)
        }.getOrNull()
        emit(UiEvent.PickDocumentTree(initialUri = if (accountEmail != null) initialUri else null, accountEmail = accountEmail))
    }

    /** Keeps access to a tree picked with ACTION_OPEN_DOCUMENT_TREE and mounts it as `saf://<id>`. */
    fun mountSaf(rawUri: Uri) {
        val cr = app.contentResolver
        val auth = rawUri.authority.orEmpty()
        val uri = if (auth.contains('@')) rawUri.buildUpon().authority(auth.substringAfter('@')).build() else rawUri
        val rw = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = listOf(uri, rawUri).distinct().any { candidate ->
            try {
                cr.takePersistableUriPermission(candidate, rw)
                true
            } catch (_: SecurityException) {
                try {
                    cr.takePersistableUriPermission(candidate, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    false
                }
            }
        }
        if (!persisted && cr.persistedUriPermissions.none { it.uri == uri }) {
            toast("Couldn't keep access to this location"); return
        }
        safMounts.firstOrNull { it.uri == uri.toString() }?.let { refreshMounts(); navigate(it.path); return }
        viewModelScope.launch {
            val targetAcc = pendingMountAccount
            pendingMountAccount = null
            val label = withContext(Dispatchers.IO) {
                val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
                val provider = SafLabels.providerLabel(uri.authority.orEmpty(), treeId)
                val name = GoogleDriveManager.queryTreeDisplayName(app, uri)
                when {
                    targetAcc != null -> if (!name.isNullOrBlank() && name != targetAcc) "Drive ($targetAcc) · $name" else "Drive ($targetAcc)"
                    !name.isNullOrBlank() && name.contains("@") && uri.authority == GoogleDriveManager.DOCS_AUTHORITY -> "Drive ($name)"
                    name.isNullOrBlank() || name == provider -> provider
                    else -> "$provider · $name"
                }
            }
            val m = SafMount(java.util.UUID.randomUUID().toString().replace("-", "").take(8), label, uri.toString())
            safMounts = safMounts + m
            settings.safMounts = safMounts
            refreshMounts()
            navigate(m.path)
            toast("Mounted $label")
        }
    }

    private fun unmountSaf(id: String) {
        val m = safMounts.firstOrNull { it.id == id } ?: return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(m.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        safMounts = safMounts - m
        settings.safMounts = safMounts
        saf.disconnect(id)
    }

    // ---------------------------------------------------------------- picker mode (GET_CONTENT / OPEN_DOCUMENT / OPEN_DOCUMENT_TREE / CREATE_DOCUMENT)

    private fun mimeOf(name: String): String? =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** True if the requesting app accepts [e] (light MIME check on the file extension). */
    fun acceptsForPick(e: FileEntry): Boolean = !e.isDir && MimeFilter.matches(mimeOf(e.name), picker?.mimeTypes.orEmpty())

    /** Files of the current selection that can be returned ("Select File" is enabled when non-empty). */
    fun pickableSelection(): List<FileEntry> =
        tab.selectedEntries(hidden).filter { !it.isDir && !it.isServer && !it.isShare && acceptsForPick(it) }
            .let { if (picker?.allowMultiple == true) it else it.take(1) }

    fun pickSelection() = pick(tab.selectedEntries(hidden).filter { !it.isDir && !it.isServer && !it.isShare })

    fun setCreateFileName(name: String) {
        _state.update { it.copy(createFileName = name) }
    }

    /** True when the active folder (or single selected subfolder) can be granted via ACTION_OPEN_DOCUMENT_TREE. */
    fun canPickFolder(folderPath: String = targetTreeFolder()): Boolean {
        if (FsPath.isVirtual(folderPath) || FsPath.isZip(folderPath)) return false
        if (FsPath.isLocalFile(folderPath) && (FileInfo.isSymlink(folderPath) || isPrivatePath(folderPath))) return false
        return FsPath.isLocalFile(folderPath) || folderPath.startsWith("doc://") || folderPath.startsWith("saf://")
    }

    fun targetTreeFolder(): String =
        tab.selectedEntries(hidden).singleOrNull()?.takeIf { it.isDir && !it.isServer && !it.isShare }?.path ?: tab.path

    /** Returns the selected (or active) folder tree URI to the calling app for ACTION_OPEN_DOCUMENT_TREE. */
    fun pickCurrentFolder() {
        val p = picker ?: return
        if (p.mode != com.droid.wayfinder.data.PickerMode.OpenTree) return
        val folder = targetTreeFolder()
        if (!canPickFolder(folder)) {
            toast("Select a local or document folder"); return
        }
        val uri = com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(
            path = folder,
            tree = true,
            safMounts = safMounts,
            primaryStorageRoot = primary,
        )
        emit(UiEvent.PickTreeResult(folder, uri))
    }

    /** Creates the target document in the active folder and returns its URI for ACTION_CREATE_DOCUMENT. */
    fun confirmCreateDocument() {
        val p = picker ?: return
        if (p.mode != com.droid.wayfinder.data.PickerMode.CreateDocument) return
        val dir = tab.path
        if (FsPath.isReadOnly(dir) || trash.isInTrash(dir)) {
            toast("Cannot save files in this location"); return
        }
        if (FsPath.isLocalFile(dir) && (FileInfo.isSymlink(dir) || isPrivatePath(dir))) {
            toast("Cannot save files in this location"); return
        }
        val name = _state.value.createFileName.trim()
        val err = FsPath.nameError(name)
        if (err != null) {
            toast(err); return
        }
        val requestedMime = p.mimeTypes.firstOrNull { it.isNotBlank() && it != "*/*" }
            ?: mimeOf(name)
            ?: "application/octet-stream"
        val candidatePath = FsPath.child(dir, name)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val createdPath = if (dir.startsWith("doc://")) {
                        docFs.createFileWithMime(candidatePath, requestedMime)
                    } else {
                        repo.fs(dir).createFile(candidatePath)
                        candidatePath
                    }
                    val uri = com.droid.wayfinder.data.SystemDocumentsBridge.toDocumentsContractUri(
                        path = createdPath,
                        tree = false,
                        safMounts = safMounts,
                        primaryStorageRoot = primary,
                    )
                    createdPath to uri
                }
            }
            result.onSuccess { (createdPath, uri) ->
                load(tab.id, select = createdPath)
                emit(UiEvent.CreateDocumentResult(createdPath, uri, requestedMime))
            }.onFailure { e ->
                toast(e.message ?: "Couldn't create file")
            }
        }
    }

    /** Returns [entries] to the calling app; network / archive files are downloaded to the cache first. */
    fun pick(entries: List<FileEntry>) {
        val p = picker ?: return
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) { toast("Select a file"); return }
        if (!p.allowMultiple && files.size > 1) { toast("The requesting app accepts a single file"); return }
        if (files.any { !acceptsForPick(it) }) { toast("The requesting app expects ${p.description ?: "a different kind of file"}"); return }
        // Never hand out symbolic links or the app's private files (settings, saved servers) to external apps.
        if (files.any { FsPath.isLocalFile(it.path) && isUnsafeToExpose(it.path) }) { toast("This file can't be shared"); return }
        val local = files.filter { FsPath.isLocalFile(it.path) }.map { it.path }
        val remote = files.filter { !FsPath.isLocalFile(it.path) }.map { it.path }
        if (remote.isEmpty()) { emit(UiEvent.PickResult(local)); return }
        val base = File(app.cacheDir, "picker")
        val now = System.currentTimeMillis()
        base.listFiles()?.filter { now - it.lastModified() > 24 * 3600_000L }?.forEach { FileInfo.deleteTree(it) }
        val dir = File(base, now.toString()).apply { mkdirs() }
        launchOp("Downloading", { report ->
            ops.transfer(remote, dir.path, move = false, conflict = Conflict.KeepBoth, onProgress = report).map { it.target }
        }) { got -> emit(UiEvent.PickResult(local + got)) }
    }

    fun cancelPick() = emit(UiEvent.PickCancel)

    /** True for symbolic links or files resolving inside the app's private storage, which must never leave the app. */
    private fun isUnsafeToExpose(path: String): Boolean {
        if (FileInfo.isSymlink(path)) return true
        return isPrivatePath(path)
    }

    /** True for files in the app's internal storage (resolving links), which must never leave the app. */
    private fun isPrivatePath(path: String): Boolean {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val data = runCatching { File(app.applicationInfo.dataDir).canonicalPath }.getOrDefault(app.applicationInfo.dataDir)
        return FsPath.isWithin(data, canonical) || FsPath.isWithin(app.applicationInfo.dataDir, path)
    }
}

internal fun sanitizeImportedFileName(raw: String?, fallback: String = "imported-file"): String {
    if (raw.isNullOrBlank()) return fallback
    val leaf = raw.replace('\\', '/').trim().trimEnd('/').substringAfterLast('/')
    val cleaned = buildString(leaf.length) {
        for (ch in leaf) {
            if (ch == '/' || ch == '\\' || ch < ' ' || ch == '\u007f') append('_') else append(ch)
        }
    }.trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback
    if (cleaned.toByteArray(Charsets.UTF_8).size <= FsPath.MAX_NAME_BYTES) return cleaned
    val dot = cleaned.lastIndexOf('.')
    val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
    val maxBaseBytes = (FsPath.MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size).coerceAtLeast(16)
    val base = buildString {
        var bytes = 0
        for (ch in if (ext.isEmpty()) cleaned else cleaned.substring(0, dot)) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + chBytes > maxBaseBytes) break
            append(ch)
            bytes += chBytes
        }
    }.trim().ifEmpty { fallback }
    return (base + ext).takeIf { FsPath.nameError(it) == null } ?: fallback
}
