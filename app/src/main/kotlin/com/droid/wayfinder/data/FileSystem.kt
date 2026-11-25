// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Blocking file-system abstraction; callers run it on an IO dispatcher. */
interface FileSystem {
    fun list(path: String): List<FileEntry>
    fun stat(path: String): FileEntry?
    fun exists(path: String): Boolean = stat(path) != null
    fun mkdir(path: String)
    fun createFile(path: String)
    fun delete(path: String)
    fun rename(path: String, newName: String): String
    /** Fast same-volume move; returns false if the caller must copy + delete instead. */
    fun move(src: String, dstPath: String): Boolean = false
    fun openInput(path: String): InputStream
    fun openOutput(path: String): OutputStream
}

/** A network / provider-backed file system that may cache connections per credential key. */
interface RemoteFileSystem : FileSystem {
    /** Drops cached connections for [key] (host for SMB, `scheme://authority` otherwise, SAF mount id). */
    fun disconnect(key: String)
}

/** Optional hook on staged [OutputStream]s so failed or cancelled writes discard temp buffers without uploading partial bytes. */
interface AbortableOutputStream {
    fun abort()
}

/** Runs [block] and closes this [OutputStream], calling [AbortableOutputStream.abort] before `close()` if [block] throws. */
inline fun <R> OutputStream.useCommit(block: (OutputStream) -> R): R =
    use { out ->
        try {
            block(out)
        } catch (t: Throwable) {
            (out as? AbortableOutputStream)?.abort()
            throw t
        }
    }

class LocalFileSystem : FileSystem {
    private fun toEntry(f: File): FileEntry {
        val mode = FileInfo.mode(f.path)
        return FileEntry(
            path = f.path, name = f.name, isDir = f.isDirectory,
            size = if (f.isDirectory) 0 else f.length(), modified = f.lastModified(), isHidden = f.isHidden || f.name.startsWith("."),
            mode = mode, isLink = if (mode != 0) FileInfo.typeChar(mode) == 'l' else FileInfo.isSymlink(f.path),
        )
    }

    override fun list(path: String): List<FileEntry> {
        val dir = File(path)
        val files = dir.listFiles() ?: throw IOException(
            if (dir.exists()) "Can't read this folder (permission denied)" else "This folder no longer exists",
        )
        return files.map(::toEntry)
    }

    override fun stat(path: String): FileEntry? = File(path).takeIf { it.exists() || FileInfo.isSymlink(path) }?.let(::toEntry)
    override fun exists(path: String) = File(path).exists() || FileInfo.isSymlink(path)

    override fun mkdir(path: String) {
        val f = File(path)
        if (!f.isDirectory && !f.mkdirs()) throw IOException("Couldn't create folder ${f.name}")
    }

    override fun createFile(path: String) {
        val f = File(path)
        if (f.exists()) throw IOException("${f.name} already exists")
        if (!f.createNewFile()) throw IOException("Couldn't create ${f.name}")
    }

    override fun delete(path: String) {
        val f = File(path)
        // Never follows symbolic links: deleting a folder that contains a link removes the link, not its target.
        if (!FileInfo.deleteTree(f)) throw IOException("Couldn't delete ${f.name}")
    }

    override fun rename(path: String, newName: String): String {
        if (!FsPath.isSafeName(newName)) throw IOException("Invalid name")
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (dst.exists()) throw IOException("\"$newName\" already exists")
        if (!src.renameTo(dst)) throw IOException("Couldn't rename ${src.name}")
        return dst.path
    }

    override fun move(src: String, dstPath: String): Boolean {
        val dst = File(dstPath)
        if (dst.exists()) return false
        return File(src).renameTo(dst)
    }

    override fun openInput(path: String): InputStream = File(path).inputStream().buffered(64 * 1024)
    override fun openOutput(path: String): OutputStream = File(path).outputStream().buffered(64 * 1024)
}
