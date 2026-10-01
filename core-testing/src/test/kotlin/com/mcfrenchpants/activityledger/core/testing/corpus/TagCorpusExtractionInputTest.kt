package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagCorpusExtractionInputTest {

    @Test
    fun `every case maps its sentence, capture instant and zone exactly`() {
        val cases = TagCorpus.load().cases
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val input = TagCorpusExtractionInput.forCase(case)
            // Case id only in messages: never the sentence (AGENTS.md #11 style).
            assertTrue(input.rawText == case.rawText, "rawText differs for case ${case.id}")
            assertEquals(case.capturedInstant, input.capturedAt, "capturedAt of case ${case.id}")
            assertEquals(case.zone, input.zoneId, "zone of case ${case.id}")
        }
    }

    @Test
    fun `equal cases build equal inputs`() {
        val case = TagCorpus.load().cases.first()
        assertEquals(TagCorpusExtractionInput.forCase(case), TagCorpusExtractionInput.forCase(case.copy()))
    }
}
