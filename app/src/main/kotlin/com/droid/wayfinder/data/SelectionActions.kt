// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

/**
 * Pure state describing the contextual selection actions (Cut, Copy, Compress to ZIP, Share, Delete —
 * or Restore and Delete permanently in Trash) shown inline on wide toolbars (`> 960.dp`) or in a
 * compact contextual bar on narrower viewports (`<= 960.dp`).
 */
data class SelectionToolbarState(
    val showToolbarActions: Boolean = false,
    val selectedCount: Int = 0,
    val inTrash: Boolean = false,
    val canCut: Boolean = false,
    val canCopy: Boolean = false,
    val canCompress: Boolean = false,
    val canShare: Boolean = false,
    val canRestore: Boolean = false,
    val canDelete: Boolean = false,
)

/**
 * Computes which contextual selection actions apply to [selectedEntries] inside [tabPath].
 * Mirrors the guards in [com.droid.wayfinder.ui.BrowserViewModel] and [com.droid.wayfinder.ui.fileMenu].
 *
 * Callers should pass [inTrash] from [TrashManager.isInTrash] so navigating inside a trashed
 * subfolder (`/storage/.../.Wayfinder-Trash/<slot>/<folder>`) is treated as inside Trash rather
 * than a writable local directory.
 */
fun selectionToolbarState(
    tabPath: String,
    selectedEntries: List<FileEntry>,
    inTrash: Boolean = tabPath == FsPath.TRASH_ROOT || FsPath.isWithin(FsPath.TRASH_ROOT, tabPath),
): SelectionToolbarState {
    val actionable = selectedEntries.filter { !it.isServer && !it.isShare && !FsPath.isVirtual(it.path) }
    if (actionable.isEmpty()) return SelectionToolbarState()
    val isTrash = inTrash || tabPath == FsPath.TRASH_ROOT || FsPath.isWithin(FsPath.TRASH_ROOT, tabPath)
    val anyZip = actionable.any { FsPath.isZip(it.path) }
    val readOnlyHere = FsPath.isReadOnly(tabPath)
    return SelectionToolbarState(
        showToolbarActions = true,
        selectedCount = actionable.size,
        inTrash = isTrash,
        canCut = !isTrash && !anyZip,
        canCopy = !isTrash,
        canCompress = !isTrash && !readOnlyHere,
        canShare = !isTrash && actionable.any { !it.isDir && FsPath.isLocalFile(it.path) },
        canRestore = isTrash && tabPath == FsPath.TRASH_ROOT,
        canDelete = !anyZip,
    )
}
