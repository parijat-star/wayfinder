// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CiWorkflowConfigTest {
    @Test
    fun ciWorkflowPinsActionsUsesReadPermissionsAndVerifiesReleaseBuild() {
        val repoRoot = sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/build.gradle.kts").isFile }
        val ciFile = File(repoRoot, ".github/workflows/ci.yml")
        assertTrue("Expected .github/workflows/ci.yml to exist", ciFile.isFile)
        val content = ciFile.readText()

        assertTrue("ci.yml must trigger on pull_request", content.contains("pull_request:"))
        assertTrue("ci.yml must trigger on push", content.contains("push:"))
        assertTrue("ci.yml must restrict permissions to contents: read", content.contains("contents: read"))
        assertFalse("ci.yml must never request write permissions", content.contains("write"))
        assertTrue("ci.yml must disable persisted git credentials", content.contains("persist-credentials: false"))
        assertTrue("ci.yml must run testDebugUnitTest", content.contains("testDebugUnitTest"))
        assertTrue("ci.yml must run lintVitalRelease", content.contains("lintVitalRelease"))
        assertTrue("ci.yml must run assembleRelease", content.contains("assembleRelease"))

        val usesLines = content.lines()
            .map { it.trim() }
            .filter { it.startsWith("uses:") || it.startsWith("- uses:") }
        assertTrue("Expected at least 3 action uses: lines in ci.yml", usesLines.size >= 3)
        val pinnedShaRegex = Regex("""^(?:-\s*)?uses:\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}(?:\s+#.*)?$""")
        for (line in usesLines) {
            assertTrue("Every GitHub Action in ci.yml must be pinned to a 40-char commit SHA: '$line'", pinnedShaRegex.matches(line))
        }
    }
}
