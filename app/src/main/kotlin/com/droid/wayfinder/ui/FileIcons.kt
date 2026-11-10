// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileKind
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.Folder -> Icons.Outlined.Folder
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Video -> Icons.Outlined.VideoFile
    FileKind.Audio -> Icons.Outlined.AudioFile
    FileKind.Pdf -> Icons.Outlined.PictureAsPdf
    FileKind.Document -> Icons.Outlined.Description
    FileKind.Sheet -> Icons.Outlined.TableChart
    FileKind.Slides -> Icons.Outlined.Slideshow
    FileKind.Archive -> Icons.Outlined.Archive
    FileKind.Code -> Icons.Outlined.Code
    FileKind.Apk -> Icons.Outlined.Android
    FileKind.Text -> Icons.AutoMirrored.Outlined.TextSnippet
    FileKind.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

fun FileKind.tintForTheme(dark: Boolean): Color = if (dark) when (this) {
    FileKind.Folder -> Color(0xFF8AB4F8)
    FileKind.Image -> Color(0xFF81C995)
    FileKind.Video -> Color(0xFFF28B82)
    FileKind.Audio -> Color(0xFFC58AF9)
    FileKind.Pdf -> Color(0xFFF28B82)
    FileKind.Document -> Color(0xFF669DF6)
    FileKind.Sheet -> Color(0xFF81C995)
    FileKind.Slides -> Color(0xFFFCAD70)
    FileKind.Archive -> Color(0xFFFDD663)
    FileKind.Code -> Color(0xFF4DB6AC)
    FileKind.Apk -> Color(0xFF5BB974)
    FileKind.Text -> Color(0xFF9AA0A6)
    FileKind.Other -> Color(0xFF9AA0A6)
} else when (this) {
    FileKind.Folder -> Color(0xFF2B57B5)
    FileKind.Image -> Color(0xFF137333)
    FileKind.Video -> Color(0xFFB31412)
    FileKind.Audio -> Color(0xFF7627BB)
    FileKind.Pdf -> Color(0xFFB31412)
    FileKind.Document -> Color(0xFF175ABC)
    FileKind.Sheet -> Color(0xFF137333)
    FileKind.Slides -> Color(0xFF9E5100)
    FileKind.Archive -> Color(0xFF7A5E00)
    FileKind.Code -> Color(0xFF00695C)
    FileKind.Apk -> Color(0xFF137333)
    FileKind.Text -> Color(0xFF4E5257)
    FileKind.Other -> Color(0xFF4E5257)
}

@Composable
fun FileKind.tint(): Color = tintForTheme(com.droid.wayfinder.ui.theme.LocalIsDarkTheme.current)

fun FileEntry.icon(): ImageVector = when {
    isServer -> Icons.Outlined.Dns
    isShare -> Icons.Outlined.FolderShared
    else -> kind.icon()
}

@Composable
fun FileEntry.tint(): Color = if (isServer || isShare) {
    FileKind.Folder.tintForTheme(com.droid.wayfinder.ui.theme.LocalIsDarkTheme.current)
} else {
    kind.tint()
}

fun pinIcon(path: String, isKnownFile: Boolean = false): ImageVector {
    if (isKnownFile) {
        return FileKind.fromName(com.droid.wayfinder.data.FsPath.name(path)).icon()
    }
    if (com.droid.wayfinder.data.FsPath.isOneDrive(path) && com.droid.wayfinder.data.RemoteUri.relOf(path).isEmpty()) {
        return Icons.Outlined.Cloud
    }
    val name = path.substringAfterLast('/')
    return when (name.lowercase()) {
        "download", "downloads" -> Icons.Outlined.Download
        "documents" -> Icons.Outlined.Description
        "pictures", "dcim" -> Icons.Outlined.Image
        "music" -> Icons.Outlined.MusicNote
        "movies", "videos" -> Icons.Outlined.Movie
        else -> {
            val kind = FileKind.fromName(name)
            if (name.contains('.') && kind != FileKind.Other) kind.icon() else Icons.Outlined.Folder
        }
    }
}

fun rootIcon(removable: Boolean): ImageVector = if (removable) Icons.Outlined.SdCard else Icons.Outlined.Laptop

fun formatDate(ms: Long, locale: Locale = Locale.getDefault()): String {
    if (ms <= 0) return ""
    val d = Date(ms)
    val now = Calendar.getInstance(locale)
    val c = Calendar.getInstance(locale).apply { time = d }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return if (sameYear) {
        runCatching {
            android.icu.text.DateFormat.getInstanceForSkeleton("MMMdjm", locale).format(d)
        }.getOrElse {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(d)
        }
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(d)
    }
}

fun formatDateFull(ms: Long, locale: Locale = Locale.getDefault()): String =
    if (ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, locale).format(Date(ms))

/**
 * Authentic 3-color Google Drive emblem (Yellow, Green, Blue) rendered via Canvas.
 */
@Composable
fun GoogleDriveIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).semantics { contentDescription = "Google Drive" }) {
        val w = size.width
        val h = size.height

        // Colors of Google Drive: Yellow (#FFBA00), Green (#00AC47), Blue (#0066DA)
        val yellow = Color(0xFFFFBA00)
        val green = Color(0xFF00AC47)
        val blue = Color(0xFF0066DA)

        // Yellow segment (left slanted)
        val pYellow = Path().apply {
            moveTo(w * 0.33f, h * 0.12f)
            lineTo(w * 0.52f, h * 0.12f)
            lineTo(w * 0.22f, h * 0.74f)
            lineTo(w * 0.03f, h * 0.74f)
            close()
        }
        drawPath(pYellow, yellow)

        // Green segment (right slanted)
        val pGreen = Path().apply {
            moveTo(w * 0.52f, h * 0.12f)
            lineTo(w * 0.71f, h * 0.12f)
            lineTo(w * 0.97f, h * 0.74f)
            lineTo(w * 0.78f, h * 0.74f)
            close()
        }
        drawPath(pGreen, green)

        // Blue segment (bottom horizontal)
        val pBlue = Path().apply {
            moveTo(w * 0.22f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.86f)
            lineTo(w * 0.88f, h * 0.64f)
            lineTo(w * 0.32f, h * 0.64f)
            close()
        }
        drawPath(pBlue, blue)
    }
}

/**
 * Two-tone Microsoft OneDrive cloud emblem (#0078D4 primary + #28A8EA foreground lobe) rendered via Canvas.
 */
@Composable
fun OneDriveIcon(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .semantics { contentDescription = "OneDrive" },
    ) {
        val w = size.width
        val h = size.height
        val deepBlue = Color(0xFF0078D4)
        val skyBlue = Color(0xFF28A8EA)

        // Back / upper cloud dome
        drawCircle(
            color = deepBlue,
            radius = w * 0.27f,
            center = androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.44f),
        )
        drawCircle(
            color = deepBlue,
            radius = w * 0.20f,
            center = androidx.compose.ui.geometry.Offset(w * 0.23f, h * 0.56f),
        )

        // Foreground right lobe + flat cloud base
        val base = Path().apply {
            moveTo(w * 0.22f, h * 0.76f)
            lineTo(w * 0.77f, h * 0.76f)
            cubicTo(w * 0.91f, h * 0.76f, w * 0.96f, h * 0.64f, w * 0.94f, h * 0.54f)
            cubicTo(w * 0.92f, h * 0.42f, w * 0.81f, h * 0.36f, w * 0.69f, h * 0.39f)
            cubicTo(w * 0.58f, h * 0.42f, w * 0.49f, h * 0.52f, w * 0.44f, h * 0.61f)
            lineTo(w * 0.22f, h * 0.61f)
            close()
        }
        drawPath(base, skyBlue)
        drawRoundRect(
            color = deepBlue,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.56f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.20f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.10f, h * 0.10f),
        )
        drawCircle(
            color = skyBlue,
            radius = w * 0.21f,
            center = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.56f),
        )
        drawRoundRect(
            color = skyBlue,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.58f),
            size = androidx.compose.ui.geometry.Size(w * 0.42f, h * 0.18f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.09f, h * 0.09f),
        )
    }
}
