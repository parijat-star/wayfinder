// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import java.io.File

/**
 * The local file a thumbnail of [e] can be decoded from: the file itself for local storage, or a cached
 * copy for network and cloud files (SMB/NAS, WebDAV, FTP, SAF). Null while fetching or when unavailable.
 */
@Composable
internal fun rememberThumbnailFile(vm: BrowserViewModel, e: FileEntry): File? {
    val local = FsPath.isLocalFile(e.path)
    val file by produceState(if (local && vm.canThumbnail(e)) File(e.path) else null, e.path, e.modified, e.size) {
        if (!local) value = vm.thumbnailSource(e)
    }
    return file
}

/** Image/video thumbnail for [e] (local or network), falling back to the file-type icon until it decodes. */
@Composable
internal fun EntryThumbnail(
    vm: BrowserViewModel,
    e: FileEntry,
    sizePx: Int,
    iconSize: Dp,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val file = if (vm.canThumbnail(e)) rememberThumbnailFile(vm, e) else null
    var loaded by remember(e.path, e.modified, file) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) Icon(e.icon(), null, Modifier.size(iconSize), tint = e.tint())
        if (file != null) {
            val ctx = LocalContext.current
            AsyncImage(
                model = remember(file, e.modified) { ImageRequest.Builder(ctx).data(file).size(sizePx).build() },
                contentDescription = e.name,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}
