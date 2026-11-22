// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import com.droid.wayfinder.data.LocalMediaStoreUri.MediaCollection
import com.droid.wayfinder.data.LocalMediaStoreUri.RelativeMediaLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaStoreUriTest {

    @Test
    fun collectionForMapsMediaExtensionsAndMimeTypes() {
        assertEquals(MediaCollection.Images, LocalMediaStoreUri.collectionFor("IMG_001.JPG"))
        assertEquals(MediaCollection.Images, LocalMediaStoreUri.collectionFor("photo.webp"))
        assertEquals(MediaCollection.Images, LocalMediaStoreUri.collectionFor("scan.heic"))
        assertEquals(MediaCollection.Video, LocalMediaStoreUri.collectionFor("clip.mp4"))
        assertEquals(MediaCollection.Audio, LocalMediaStoreUri.collectionFor("track.flac"))

        // Explicit MIME type overrides missing/unknown extensions
        assertEquals(MediaCollection.Images, LocalMediaStoreUri.collectionFor("noext", "image/png"))
        assertEquals(MediaCollection.Video, LocalMediaStoreUri.collectionFor("stream.bin", "VIDEO/WEBM"))
        assertEquals(MediaCollection.Audio, LocalMediaStoreUri.collectionFor("voice.dat", "audio/ogg"))

        // Non-media files return null so they stay on FileProvider
        assertNull(LocalMediaStoreUri.collectionFor("contract.docx"))
        assertNull(LocalMediaStoreUri.collectionFor("statement.pdf", "application/pdf"))
        assertNull(LocalMediaStoreUri.collectionFor("notes.txt", "text/plain"))
        assertNull(LocalMediaStoreUri.collectionFor("app.apk", "application/vnd.android.package-archive"))
        assertNull(LocalMediaStoreUri.collectionFor("archive.zip", "*/*"))
    }

    @Test
    fun relativeLocationInPrimaryStorageHandlesNestedAndRootFiles() {
        assertEquals(
            RelativeMediaLocation(relativePath = "Pictures/Vacation 2026/", displayName = "IMG_01.jpg"),
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/10/Pictures/Vacation 2026/IMG_01.jpg",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
        assertEquals(
            RelativeMediaLocation(relativePath = "/", displayName = "root_photo.png"),
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/10/root_photo.png",
                externalStorageRoot = "/storage/emulated/10/",
            ),
        )
        assertEquals(
            RelativeMediaLocation(relativePath = "Download/Mom's Photos/", displayName = "café.webp"),
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/0/Download/Mom's Photos/café.webp",
                externalStorageRoot = "/storage/emulated/0",
            ),
        )
    }

    @Test
    fun relativeLocationRejectsOutsidePathsAndTraversalSegments() {
        assertNull(
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/10",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
        assertNull(
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/100/Pictures/a.jpg",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
        assertNull(
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/data/user/10/com.droid.wayfinder/cache/remote-open/a.jpg",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
        assertNull(
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/10/Pictures/../Secret/a.jpg",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
        assertNull(
            LocalMediaStoreUri.relativeLocationInPrimaryStorage(
                filePath = "/storage/emulated/10/Pictures/./a.jpg",
                externalStorageRoot = "/storage/emulated/10",
            ),
        )
    }

    @Test
    fun buildDataCandidatesDeduplicatesAndDropsBlanks() {
        assertEquals(
            listOf("/storage/emulated/10/Pictures/a.jpg"),
            LocalMediaStoreUri.buildDataCandidates(
                "/storage/emulated/10/Pictures/a.jpg",
                "/storage/emulated/10/Pictures/a.jpg",
            ),
        )
        assertEquals(
            listOf("/sdcard/Pictures/a.jpg", "/storage/emulated/10/Pictures/a.jpg"),
            LocalMediaStoreUri.buildDataCandidates(
                "/sdcard/Pictures/a.jpg",
                "/storage/emulated/10/Pictures/a.jpg",
            ),
        )
        assertEquals(
            listOf("/storage/emulated/10/Pictures/a.jpg"),
            LocalMediaStoreUri.buildDataCandidates("/storage/emulated/10/Pictures/a.jpg", "  "),
        )
    }
}
