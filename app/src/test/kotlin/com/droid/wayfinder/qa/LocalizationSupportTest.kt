// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.R
import com.droid.wayfinder.ui.formatIcu
import com.droid.wayfinder.ui.formatLocalizedNumber
import com.droid.wayfinder.ui.icuString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalizationSupportTest {

    private fun projectRoot(): File =
        sequenceOf(File("."), File(".."))
            .map { it.canonicalFile }
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }

    @Test
    fun stringsXmlHasTranslatorDescriptionsCharLimitsIcuExactOneAndActiveReferences() {
        val root = projectRoot()
        val stringsFile = File(root, "app/src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val nodes = doc.getElementsByTagName("string")
        assertTrue("Expected at least 180 localized string resources, got ${nodes.length}", nodes.length >= 180)

        val mainSources = buildString {
            File(root, "app/src/main").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || (it.extension == "xml" && it.name != "strings.xml")) }
                .forEach { append(it.readText()).append('\n') }
        }
        assertFalse("UI sources must use ICU plurals rather than item(s)", mainSources.contains("item(s)"))
        assertFalse("UI sources must use ICU plurals rather than folder(s)", mainSources.contains("folder(s)"))
        assertFalse("UI sources must use ICU plurals rather than conflict(s)", mainSources.contains("conflict(s)"))

        val charLimitRegex = Regex("""\[CHAR_LIMIT=(\d+|NONE)]""")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as org.w3c.dom.Element
            val name = el.getAttribute("name")
            val desc = el.getAttribute("description")
            val value = el.textContent

            assertTrue("String '$name' must include a translator description", desc.isNotBlank())
            assertTrue("String '$name' description must include [CHAR_LIMIT=...]", charLimitRegex.containsMatchIn(desc))

            if (value.contains("plural,")) {
                assertTrue("ICU plural '$name' must define =1 branch", value.contains("=1 {"))
                assertTrue("ICU plural '$name' must define other branch", value.contains("other {"))
                assertFalse("ICU plural '$name' must use =1 rather than one {", Regex("""\bone\s*\{""").containsMatchIn(value))
            }

            val referenced = mainSources.contains("R.string.$name") || mainSources.contains("@string/$name")
            assertTrue("String '$name' should be wired into UI or manifest", referenced)
        }
    }

    @Test
    fun localesConfigDeclaresEnglishAndIsWiredInManifest() {
        val root = projectRoot()
        val localesXml = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        assertTrue(localesXml.contains("<locale-config"))
        assertTrue(localesXml.contains("android:name=\"en\""))

        val manifestXml = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifestXml.contains("android:localeConfig=\"@xml/locales_config\""))
    }

    @Test
    fun formatIcuAndFormatLocalizedNumberRespectActiveLocaleRules() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val bytePattern = ctx.getString(R.string.byte_count_plural)
        val itemPattern = ctx.getString(R.string.item_count_plural)

        assertEquals("1 byte", formatIcu(bytePattern, 1L, locale = Locale.US))
        assertEquals("1,048,576 bytes", formatIcu(bytePattern, 1_048_576L, locale = Locale.US))
        assertEquals("1.048.576 bytes", formatIcu(bytePattern, 1_048_576L, locale = Locale.GERMANY))

        val frenchBytes = formatIcu(bytePattern, 1_048_576L, locale = Locale.FRANCE)
        assertTrue("Expected grouped French number in '$frenchBytes'", frenchBytes.endsWith("048\u202F576 bytes") || frenchBytes.endsWith("048\u00A0576 bytes"))

        assertEquals("1 item", formatIcu(itemPattern, 1, locale = Locale.US))
        assertEquals("1,250 items", formatIcu(itemPattern, 1250, locale = Locale.US))
        assertEquals("1.250", formatLocalizedNumber(1250L, Locale.GERMANY))
        assertEquals("1 item will be deleted and can't be recovered.", ctx.icuString(R.string.dialog_delete_many_plural, 1))
        assertEquals("3 items will be deleted and can't be recovered.", ctx.icuString(R.string.dialog_delete_many_plural, 3))
    }
}
