// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.droid.wayfinder.R
import com.droid.wayfinder.data.Checksums
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileKind
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.formatSize
import com.droid.wayfinder.data.sortEntries
import com.droid.wayfinder.data.SortKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TEXT_PREVIEW_BYTES = 256 * 1024
private const val PDF_PREVIEW_PAGES = 8
private val TEXT_EXTS = setOf("csv", "tsv", "srt", "properties", "toml", "env", "gitignore", "bat", "ps1", "sql", "ts", "tsx", "jsx", "swift", "rb", "php", "cs")

/** macOS-style Quick Look overlay: Space opens/closes, arrows step through items, Enter opens. */
@Composable
fun QuickLook(vm: BrowserViewModel, s: AppState) {
    val path = s.quickLook ?: return
    val entry = (s.active.searchResults ?: s.active.entries).firstOrNull { it.path == path }
    LaunchedEffect(entry == null) { if (entry == null) vm.closeQuickLook() }
    if (entry == null) return
    val cs = MaterialTheme.colorScheme
    val noRipple = remember { MutableInteractionSource() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(noRipple, indication = null) { vm.closeQuickLook() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)
                .clickable(noRipple, indication = null) {},
            shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLowest, shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(vm, entry)
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) { Body(vm, entry) }
                Text(
                    "Space or Esc to close  ·  ← → ↑ ↓ previous / next  ·  Enter to open",
                    Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Header(vm: BrowserViewModel, e: FileEntry) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(e.icon(), null, Modifier.size(28.dp), tint = e.tint())
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = buildList {
                add(e.typeLabel)
                if (!e.isDir) add(formatSize(e.size))
                if (e.modified > 0) add(formatDateFull(e.modified))
            }
            Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        IconButton({ vm.quickLookMove(-1) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous (←)") }
        IconButton({ vm.quickLookMove(1) }) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next (→)") }
        FilledTonalButton({ vm.closeQuickLook(); vm.openSelection() }, Modifier.padding(horizontal = 8.dp)) {
            Text(if (e.isDir) "Open folder" else "Open")
        }
        IconButton({ vm.closeQuickLook() }) { Icon(Icons.Outlined.Close, "Close (Space)") }
    }
}

@Composable
private fun Body(vm: BrowserViewModel, e: FileEntry) {
    if (e.isDir && !e.isServer) { FolderPreview(vm, e); return }
    if (!FsPath.isLocalFile(e.path)) {
        Placeholder(e, if (FsPath.isZip(e.path)) "Preview isn't available inside archives. Press Enter to open." else "Preview isn't available for network and cloud files yet. Press Enter to open.")
        return
    }
    val ext = e.extension.lowercase()
    when {
        e.kind == FileKind.Image -> ImagePreview(e)
        e.kind == FileKind.Video -> VideoPreview(vm, e)
        e.kind == FileKind.Pdf -> PdfPreview(e)
        e.kind == FileKind.Audio -> AudioPreview(e)
        e.kind == FileKind.Text || e.kind == FileKind.Code || ext in TEXT_EXTS -> TextPreview(e)
        ext == "zip" || ext == "tar" || ext == "tgz" || e.name.endsWith(".tar.gz", true) -> ZipPreview(e)
        else -> BinaryPreview(e)
    }
}

/** Folders list their children (works for local, network and archive folders). */
@Composable
private fun FolderPreview(vm: BrowserViewModel, e: FileEntry) {
    val cs = MaterialTheme.colorScheme
    val kids by produceState<List<FileEntry>?>(null, e.path) { value = sortEntries(vm.listForColumn(e.path).filter { !it.isHidden }, SortKey.Name, true) }
    val k = kids
    when {
        k == null -> CircularProgressIndicator()
        k.isEmpty() -> Placeholder(e, "This folder is empty")
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "${k.size} item(s)  ·  ${k.count { it.isDir }} folder(s)", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(k.take(2000)) { c -> ListingRow(c.icon(), c.tint(), c.name, if (c.isDir) "—" else formatSize(c.size)) }
            }
        }
    }
}

private data class ZipListing(val entries: List<Triple<String, Long, Boolean>>, val total: Int, val bytes: Long)

/** Zip archives show their entry list (name, uncompressed size). */
@Composable
private fun ZipPreview(e: FileEntry) {
    val cs = MaterialTheme.colorScheme
    val listing by produceState<Result<ZipListing>?>(null, e.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val s = com.droid.wayfinder.data.ZipFileSystem.summary(File(e.path))
                ZipListing(s.entries, s.total, s.bytes)
            }
        }
    }
    val r = listing ?: run { CircularProgressIndicator(); return }
    val l = r.getOrNull() ?: run { BinaryPreview(e, "Couldn't read this archive: ${r.exceptionOrNull()?.message}"); return }
    Column(Modifier.fillMaxSize()) {
        Text(
            "${l.total} entries  ·  ${formatSize(l.bytes)} uncompressed  ·  Enter to browse", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(l.entries) { (name, size, dir) ->
                val k = if (dir) FileKind.Folder else FileKind.fromName(name)
                ListingRow(k.icon(), k.tint(), name, if (dir) "—" else formatSize(size.coerceAtLeast(0)))
            }
        }
    }
}

@Composable
private fun ListingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, name: String, size: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(name, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Unknown/binary files: size plus SHA-256 and CRC32 computed in the background. */
@Composable
private fun BinaryPreview(e: FileEntry, message: String = "No preview available for this file type.") {
    val cs = MaterialTheme.colorScheme
    var done by remember(e.path) { mutableStateOf(0L) }
    val sums by produceState<Result<Checksums>?>(null, e.path) {
        value = withContext(Dispatchers.IO) { runCatching { FileInfo.checksums(File(e.path).inputStream()) { done = it } } }
    }
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(e.icon(), null, Modifier.size(96.dp), tint = e.tint())
        Text(message, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Text("${formatSize(e.size)}  (${icuStringResource(R.string.byte_count_plural, e.size)})", style = MaterialTheme.typography.bodyMedium)
        val r = sums
        when {
            r == null -> Text(
                "Computing SHA-256 & CRC32…" + if (e.size > 0) " ${(done * 100 / e.size).coerceIn(0, 100)}%" else "",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            r.isFailure -> Text("Couldn't read file: ${r.exceptionOrNull()?.message}", style = MaterialTheme.typography.bodySmall, color = cs.error)
            else -> SelectionContainer {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SHA-256  ${r.getOrThrow().sha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("CRC32  ${r.getOrThrow().crc32}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun Placeholder(e: FileEntry, message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(e.icon(), null, Modifier.size(120.dp), tint = e.tint())
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImagePreview(e: FileEntry) {
    val ctx = LocalContext.current
    AsyncImage(
        remember(e.path) { ImageRequest.Builder(ctx).data(File(e.path)).size(2048).build() },
        e.name, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
    )
}

@Composable
private fun VideoPreview(vm: BrowserViewModel, e: FileEntry) {
    val ctx = LocalContext.current
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            remember(e.path) { ImageRequest.Builder(ctx).data(File(e.path)).size(1920).build() },
            e.name, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
        )
        FilledTonalIconButton({ vm.closeQuickLook(); vm.openSelection() }, Modifier.size(72.dp), shape = CircleShape) {
            Icon(Icons.Outlined.PlayArrow, "Play", Modifier.size(40.dp))
        }
    }
}

@Composable
private fun PdfPreview(e: FileEntry) {
    val pages by produceState<List<ImageBitmap>?>(null, e.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(File(e.path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        (0 until minOf(r.pageCount, PDF_PREVIEW_PAGES)).map { i ->
                            r.openPage(i).use { p ->
                                val scale = 1400f / p.width
                                val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp.asImageBitmap()
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    when {
        pages == null -> CircularProgressIndicator()
        pages!!.isEmpty() -> Placeholder(e, "Couldn't render this PDF.")
        else -> LazyColumn(
            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(pages!!) { img ->
                Image(img, null, Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

@Composable
private fun TextPreview(e: FileEntry) {
    val text by produceState<String?>(null, e.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                File(e.path).inputStream().use { input ->
                    val buf = ByteArray(TEXT_PREVIEW_BYTES)
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    val s = String(buf, 0, n, Charsets.UTF_8)
                    if (e.size > TEXT_PREVIEW_BYTES) "$s\n\n… (preview truncated)" else s
                }
            }.getOrElse { "Couldn't read file: ${it.message}" }
        }
    }
    val t = text
    if (t == null) { CircularProgressIndicator(); return }
    // Line numbers in a dimmed gutter.
    val numColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val numbered = remember(t, numColor) {
        val lines = t.split('\n')
        val w = lines.size.toString().length
        buildAnnotatedString {
            lines.forEachIndexed { i, line ->
                withStyle(SpanStyle(color = numColor)) { append((i + 1).toString().padStart(w)); append("  ") }
                append(line)
                if (i < lines.lastIndex) append('\n')
            }
        }
    }
    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            numbered,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AudioPreview(e: FileEntry) {
    var playing by remember(e.path) { mutableStateOf(false) }
    val player = remember(e.path) {
        runCatching { MediaPlayer().apply { setDataSource(e.path); prepare() } }.getOrNull()
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false }
        onDispose { player?.release() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(e.icon(), null, Modifier.size(120.dp), tint = e.tint())
        if (player == null) {
            Text("Can't play this audio file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FilledTonalIconButton(
                { if (playing) player.pause() else player.start(); playing = !playing },
                Modifier.size(64.dp), shape = CircleShape,
            ) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp)) }
        }
    }
}
