// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppBundleAndPlayDocsTest {

    private fun projectRoot(): File =
        sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/build.gradle.kts").isFile }

    private fun extractFencedBlock(markdown: String, startMarker: String, endMarker: String): String {
        val region = markdown.substringAfter(startMarker, "").substringBefore(endMarker, "")
        assertTrue("Missing marker block $startMarker .. $endMarker", region.isNotBlank())
        return region
            .substringAfter("```text")
            .substringBefore("```")
            .trim()
    }

    @Test
    fun appBuildGradleDisablesBundleLanguageSplittingForOfflinePerAppLocales() {
        val gradleKts = File(projectRoot(), "app/build.gradle.kts").readText()
        assertTrue(
            "Expected bundle { language { enableSplit = false } } in app/build.gradle.kts",
            Regex("""bundle\s*\{\s*language\s*\{[^}]*enableSplit\s*=\s*false""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(gradleKts),
        )
    }

    @Test
    fun standalonePrivacyHtmlCoversPermissionsEncryptionAndLinksFromHomepage() {
        val root = projectRoot()
        val privacyHtml = File(root, "docs/privacy.html")
        assertTrue("docs/privacy.html must exist for GitHub Pages Play Console URL", privacyHtml.isFile)
        val html = privacyHtml.readText()
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(html.contains("BLUETOOTH_CONNECT"))
        assertTrue(html.contains("neverForLocation"))
        assertTrue(html.contains("AndroidKeyStore"))
        assertTrue(html.contains("allowBackup=\"false\""))
        assertTrue(html.contains("Apache License, Version 2.0"))

        val homepage = File(root, "docs/homepage_preview.html").readText()
        assertTrue("docs/homepage_preview.html should link to privacy.html", homepage.contains("href=\"privacy.html\""))
    }

    @Test
    fun playStoreSubmissionListingCopyFitsGooglePlayCharacterLimits() {
        val submissionMd = File(projectRoot(), "docs/PLAY_STORE_SUBMISSION.md").readText()
        assertTrue(submissionMd.contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(submissionMd.contains("Data Safety"))
        assertTrue(submissionMd.contains("bundleRelease"))

        val appName = extractFencedBlock(submissionMd, "<!-- PLAY_APP_NAME_START -->", "<!-- PLAY_APP_NAME_END -->")
        val shortDesc = extractFencedBlock(submissionMd, "<!-- PLAY_SHORT_DESC_START -->", "<!-- PLAY_SHORT_DESC_END -->")
        val fullDesc = extractFencedBlock(submissionMd, "<!-- PLAY_FULL_DESC_START -->", "<!-- PLAY_FULL_DESC_END -->")
        val releaseNotes = extractFencedBlock(submissionMd, "<!-- PLAY_RELEASE_NOTES_START -->", "<!-- PLAY_RELEASE_NOTES_END -->")

        assertTrue("App Name '${appName}' (${appName.length} chars) must be 1..30 chars", appName.length in 1..30)
        assertTrue("Short Description (${shortDesc.length} chars) must be 1..80 chars", shortDesc.length in 1..80)
        assertTrue("Full Description (${fullDesc.length} chars) must be 1..4000 chars", fullDesc.length in 1..4000)
        assertTrue("Release Notes (${releaseNotes.length} chars) must be 1..500 chars", releaseNotes.length in 1..500)
    }
}
