// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.data.DriveRestFileSystem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class ReleaseConfigSecurityTest {
    @Test
    fun driveRestAuthFailureDoesNotLeakDeveloperKeySha1() {
        val fs = DriveRestFileSystem(context = null)
        try {
            fs.getToken("user@example.com")
            fail("Expected IOException when AccountManager is unavailable")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("user@example.com"))
            assertFalse("Error message must not contain hardcoded developer SHA-1", msg.contains("43:ED:0A:A8"))
        }
    }

    @Test
    fun gradleBuildScriptDoesNotSignReleaseWithDebugKey() {
        val gradleFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).firstOrNull { it.isFile } ?: return
        val content = gradleFile.readText()
        assertFalse(
            "Release buildType must not use debug signingConfig",
            content.contains("signingConfigs.getByName(\"debug\")"),
        )
        assertTrue(
            "Release buildType must enable R8 minification",
            content.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "Release buildType must enable resource shrinking",
            content.contains("isShrinkResources = true"),
        )
    }

    @Test
    fun oneDrivePublicClientSourceNeverEmbedsClientSecretAndPrivacyDocsCoverOneDrive() {
        val root = sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/build.gradle.kts").isFile }
        val oneDriveSources = listOf(
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/OneDriveAccountStore.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/OneDriveAuthManager.kt"),
            File(root, "app/src/main/kotlin/com/droid/wayfinder/data/OneDriveRestFileSystem.kt"),
        )
        for (src in oneDriveSources) {
            val text = src.readText()
            assertTrue("${src.name} must carry Apache-2.0 SPDX header", text.contains("SPDX-License-Identifier: Apache-2.0"))
            assertFalse("${src.name} must never embed an OAuth client_secret", text.contains("client_secret", ignoreCase = true))
        }
        assertTrue(File(root, "PRIVACY.md").readText().contains("OneDrive"))
        assertTrue(File(root, "docs/privacy.html").readText().contains("OneDrive"))
    }

    @Test
    fun releaseWorkflowPinsActionShasIsolatesPublishPermissionsAndGuardsMainAncestry() {
        val root = sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/build.gradle.kts").isFile }
        val workflow = File(root, ".github/workflows/release.yml").readText()
        val usesLines = workflow.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("uses:") || it.startsWith("- uses:") }
            .toList()
        assertTrue("Expected uses: entries in release.yml", usesLines.isNotEmpty())
        val pinnedShaRegex = Regex("""^(-\s*)?uses:\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}(\s+#.*)?$""")
        for (line in usesLines) {
            assertTrue("Action reference must be pinned to a 40-char commit SHA: $line", pinnedShaRegex.matches(line))
        }
        assertTrue(
            "Checkout step must disable persisted git credentials",
            workflow.contains("persist-credentials: false"),
        )
        assertTrue(
            "Stable releases must verify origin/main ancestry before signing/clobbering",
            workflow.contains("git merge-base --is-ancestor HEAD refs/remotes/origin/main"),
        )
        assertTrue(
            "Pre-release tags must pass --prerelease so they never overwrite releases/latest",
            workflow.contains("--prerelease"),
        )
        // Build signed release APK step must not receive raw WAYFINDER_KEYSTORE_BASE64 in its env.
        val gradleBuildSection = workflow.substringAfter("- name: Build signed release APK")
            .substringBefore("- name:")
        assertFalse(
            "Gradle assembleRelease step must not expose WAYFINDER_KEYSTORE_BASE64 in env",
            gradleBuildSection.contains("WAYFINDER_KEYSTORE_BASE64"),
        )

        val publishJobSection = workflow.substringAfter("  publish:")
        assertTrue(
            "Checkout-less publish job must pass GH_REPO to gh CLI",
            publishJobSection.contains("GH_REPO: \${{ github.repository }}"),
        )
        assertFalse(
            "Publish job must not run actions/checkout or hold signing secrets",
            publishJobSection.contains("actions/checkout") || publishJobSection.contains("WAYFINDER_KEYSTORE"),
        )

        val gradleScript = File(root, "app/build.gradle.kts").readText()
        assertTrue(
            "Release signingConfig must use providers.environmentVariable for config-cache safety",
            gradleScript.contains("providers.environmentVariable(\"WAYFINDER_KEYSTORE_PATH\")"),
        )
        assertTrue(
            "Release signingConfig must verify keystore file existence before creating signingConfig",
            gradleScript.contains("takeIf { it.isFile }"),
        )
    }
}
