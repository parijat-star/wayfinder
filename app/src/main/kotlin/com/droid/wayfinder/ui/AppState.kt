// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import com.droid.wayfinder.data.Checksums
import com.droid.wayfinder.data.ConnectConfig
import com.droid.wayfinder.data.PickerRequest
import com.droid.wayfinder.data.SavedServer
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsSpace
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.PosixInfo
import com.droid.wayfinder.data.Progress
import com.droid.wayfinder.data.ThemeMode

data class RootVolume(val path: String, val label: String, val removable: Boolean, val subtitle: String? = null)

/**
 * A mounted location in the sidebar's "Network & cloud" section: a saved server ([key] = credential key)
 * or a SAF document tree ([saf] = true, [key] = mount id).
 */
data class Mount(val path: String, val label: String, val subtitle: String?, val key: String, val saf: Boolean)

data class Clipboard(val paths: List<String>, val cut: Boolean)

data class TreeState(
    val expanded: Set<String> = emptySet(),
    val children: Map<String, List<FileEntry>> = emptyMap(),
    val loading: Set<String> = emptySet(),
)

data class OpStatus(
    val id: Long,
    val progress: Progress,
    val error: String? = null,
    val finished: Boolean = false,
)

sealed interface Dialog {
    data class Rename(val path: String, val name: String) : Dialog
    data class NewItem(val folder: Boolean) : Dialog
    data class Delete(val paths: List<String>) : Dialog
    /**
     * [posix]/[space] are filled for local items; [checksums] (SHA-256 + CRC32) for a single regular file,
     * with [hashProgress] shown while they are being computed.
     */
    data class Properties(
        val entries: List<FileEntry>, val bytes: Long? = null, val items: Int? = null,
        val posix: PosixInfo? = null, val space: FsSpace? = null,
        val checksums: Checksums? = null, val hashProgress: String? = null,
    ) : Dialog
    data class ConflictPrompt(val sources: List<String>, val dest: String, val move: Boolean, val names: List<String>) : Dialog
    data class Compress(val sources: List<String>, val suggested: String) : Dialog
    /** "Connect to Server" (Ctrl+Shift+K); [initial] pre-fills the form (e.g. when reconnecting a saved server). */
    data class Connect(val initial: ConnectConfig? = null) : Dialog
    data class SmbAuth(val host: String, val user: String = "", val domain: String = "") : Dialog
    data object Shortcuts : Dialog
    /** Pre-flight guidance dialog shown before launching the system document tree picker. */
    data class SafMountTip(val accountEmail: String? = null) : Dialog
    /** [existing] = every name in [dir], used for conflict detection. */
    data class BatchRename(val dir: String, val items: List<FileEntry>, val existing: List<String>) : Dialog
}

sealed interface UiEvent {
    /** [chooser] = always show the "Open with" app chooser. */
    data class OpenFile(val path: String, val chooser: Boolean = false) : UiEvent
    data class Share(val paths: List<String>) : UiEvent
    data class Toast(val message: String, val undo: Boolean = false) : UiEvent
    /** [what] names the copied value in the confirmation ("Path copied", "SHA-256 copied"). */
    data class CopyText(val text: String, val what: String = "Path") : UiEvent
    data object Finish : UiEvent
    /** Open an independent window, optionally at [path]. */
    data class NewWindow(val path: String?) : UiEvent
    /** Launch ACTION_OPEN_DOCUMENT_TREE to mount a cloud / SAF location. */
    data class PickDocumentTree(val initialUri: android.net.Uri? = null, val accountEmail: String? = null) : UiEvent
    /** Launch the system Google Account picker to grant account visibility to Wayfinder. */
    data object PickGoogleAccount : UiEvent
    /** Launch ACTION_OPEN_DOCUMENT in system DocumentsUI to import files from a wireless Cross-Device phone. */
    data class ImportPhoneDocuments(val initialUri: android.net.Uri? = null) : UiEvent
    /** Request Android 12+ BLUETOOTH_CONNECT runtime permission for wireless phone detection. */
    data object RequestBluetoothPermission : UiEvent
    /** Picker mode: return these local files (already downloaded if remote) to the calling app. */
    data class PickResult(val paths: List<String>) : UiEvent
    /** Folder picker mode (ACTION_OPEN_DOCUMENT_TREE): return the selected directory tree URI to the calling app. */
    data class PickTreeResult(val path: String, val uri: android.net.Uri? = null) : UiEvent
    /** Save-as mode (ACTION_CREATE_DOCUMENT): return the newly created document URI to the calling app. */
    data class CreateDocumentResult(
        val path: String,
        val uri: android.net.Uri? = null,
        val mimeType: String = "application/octet-stream",
    ) : UiEvent
    data object PickCancel : UiEvent
}

data class AppState(
    val tabs: List<TabState> = emptyList(),
    val activeId: Long = -1,
    val roots: List<RootVolume> = emptyList(),
    val pins: List<String> = emptyList(),
    val network: List<FileEntry> = emptyList(),
    val scanning: Boolean = false,
    val clipboard: Clipboard? = null,
    val showHidden: Boolean = false,
    val sidebarVisible: Boolean = true,
    val previewVisible: Boolean = false,
    val sidebarWidth: Float = 248f,
    val gridSize: Float = 128f,
    val tree: TreeState = TreeState(),
    val ops: List<OpStatus> = emptyList(),
    val dialog: Dialog? = null,
    val addressEditing: Boolean = false,
    val textFocus: Boolean = false,
    val canUndo: Boolean = false,
    val closedTabs: List<String> = emptyList(),
    val gridColumns: Int = 1,
    val pageSize: Int = 10,
    val focusSearch: Int = 0,
    /** Path shown in the Quick Look overlay (Space), or null when closed. */
    val quickLook: String? = null,
    val canRedo: Boolean = false,
    /** Dual-pane commander: [secondary] is the right pane (not shown in the tab strip). */
    val dualPane: Boolean = false,
    val secondary: TabState? = null,
    val rightActive: Boolean = false,
    val paletteOpen: Boolean = false,
    /** Most recently visited folders, newest first. */
    val recentFolders: List<String> = emptyList(),
    /** Stars and color tags (shared by all windows). */
    val marks: Marks = Marks(),
    /** Interface zoom factor (0.7–1.6). */
    val zoom: Float = 1f,
    val theme: ThemeMode = ThemeMode.System,
    /** Optional Details-view columns. */
    val tagsColumn: Boolean = false,
    val permsColumn: Boolean = false,
    /** Incremented to ask the sidebar to scroll to the active folder (Alt+Shift+L). */
    val treeLocate: Int = 0,
    /** True in windows opened with Ctrl+N (they don't persist the tab session). */
    val secondaryWindow: Boolean = false,
    /** Saved servers and SAF mounts shown in the sidebar. */
    val mounts: List<Mount> = emptyList(),
    /** Every remembered server (also ejected ones), offered in the Connect dialog. */
    val savedServers: List<SavedServer> = emptyList(),
    /** Non-null when launched by another app to pick a file (GET_CONTENT / OPEN_DOCUMENT). */
    val picker: PickerRequest? = null,
    /** Editable target filename in ACTION_CREATE_DOCUMENT ("Save As") picker mode. */
    val createFileName: String = "",
    /** True when a phone is actively connected or paired (USB / Bluetooth / MTP). */
    val phoneConnected: Boolean = false,
    /** True when BLUETOOTH_CONNECT is granted (or not required on API <= 30). */
    val bluetoothPermissionGranted: Boolean = true,
    /** Discovered Google Drive accounts on the device. */
    val driveAccounts: List<com.droid.wayfinder.data.DriveAccount> = emptyList(),
    /** Paired smartphone (e.g. Pixel 9) details. */
    val pairedPhone: com.droid.wayfinder.data.PairedPhone? = null,
    /** Discovered Linux development environment root path (`doc://...`, `saf://...`, or local `LinuxFiles`), if available. */
    val linuxRootPath: String? = null,
    /** Installed Linux `DocumentsProvider` authority (e.g. AVF Terminal `guestdocuments`) when detected on device. */
    val linuxProviderAuthority: String? = null,
    /** Discovered managed Work / Personal profiles via CrossProfileApps. */
    val workProfiles: List<com.droid.wayfinder.data.WorkProfileTarget> = emptyList(),
) {
    /** The tab selected in the tab strip (left pane in dual-pane mode). */
    val mainTab: TabState get() = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    /** The pane that keyboard shortcuts and menus act on. */
    val active: TabState get() = if (dualPane && rightActive && secondary != null) secondary else mainTab
    /** The non-focused pane in dual-pane mode. */
    val otherPane: TabState? get() = if (!dualPane || secondary == null) null else if (rightActive) mainTab else secondary
    fun tabById(id: Long): TabState? = tabs.firstOrNull { it.id == id } ?: secondary?.takeIf { it.id == id }
    val rootLabels: Map<String, String>
        get() = roots.associate { it.path to it.label } +
            mounts.associate { it.path to it.label } +
            driveAccounts.mapNotNull { acc -> acc.mountPath?.let { it to "Drive (${acc.email})" } }.toMap() +
            pairedPhone?.takeIf { it.isMounted && it.path != com.droid.wayfinder.data.FsPath.PHONE_ROOT }
                ?.let { mapOf(it.path to it.name) }.orEmpty()

    fun titleOf(path: String): String = when {
        com.droid.wayfinder.data.FsPath.isVirtual(path) -> com.droid.wayfinder.data.FsPath.name(path)
        else -> rootLabels[path.trimEnd('/')] ?: com.droid.wayfinder.data.FsPath.name(path)
    }
}

val Conflict.label: String
    get() = when (this) {
        Conflict.Replace -> "Replace"
        Conflict.KeepBoth -> "Keep both"
        Conflict.Skip -> "Skip"
    }
