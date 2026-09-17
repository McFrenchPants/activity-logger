package com.mcfrenchpants.activityledger.core.domain.naming

import kotlin.test.Test
import kotlin.test.assertEquals

class NameNormalizerTest {
    @Test
    fun `collapses tabs newlines and multiple spaces`() {
        assertEquals("clean dryer vent", NameNormalizer.normalize("clean\t\tdryer\n\n  vent"))
        assertEquals("clean dryer vent", NameNormalizer.normalize(" \r\n clean   dryer \t vent \n"))
    }

    @Test
    fun `folds case`() {
        assertEquals("replace smoke detector battery", NameNormalizer.normalize("Replace SMOKE Detector BaTTery"))
    }

    @Test
    fun `strips surrounding punctuation and whitespace`() {
        assertEquals("mow lawn", NameNormalizer.normalize("  Mow lawn!! "))
        assertEquals("edge lawn", NameNormalizer.normalize("\"Edge lawn.\""))
        assertEquals("edge lawn", NameNormalizer.normalize("( “Edge lawn” ) ."))
    }

    @Test
    fun `preserves internal punctuation`() {
        assertEquals("re-caulk tub", NameNormalizer.normalize("re-caulk tub"))
        assertEquals("change car's oil", NameNormalizer.normalize("Change car's oil"))
    }

    @Test
    fun `empty and punctuation-only input normalize to empty`() {
        assertEquals("", NameNormalizer.normalize(""))
        assertEquals("", NameNormalizer.normalize("   \t\n"))
        assertEquals("", NameNormalizer.normalize("!?.,"))
        assertEquals("", NameNormalizer.normalize(" - ... ! \"\" "))
    }

    @Test
    fun `is idempotent`() {
        val inputs = listOf(
            "", "  ", "!!", "  Mow lawn!! ", "\"Edge lawn.\"", "re-caulk tub", "A\tB\nC",
            "( “Edge lawn” ) .", "Flush Water Heater", "--x--", " Change oil ",
            "İstanbul trip", "'quoted' . \" name \"",
        )
        for (input in inputs) {
            val once = NameNormalizer.normalize(input)
            assertEquals(once, NameNormalizer.normalize(once), "not idempotent for <$input>")
        }
    }
}
