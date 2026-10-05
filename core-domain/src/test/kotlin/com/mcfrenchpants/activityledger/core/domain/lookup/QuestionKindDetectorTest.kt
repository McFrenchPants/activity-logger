package com.mcfrenchpants.activityledger.core.domain.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestionKindDetectorTest {

    private fun assertKind(expected: QuestionKind?, text: String) =
        assertEquals(expected, QuestionKindDetector.detect(text), "\"$text\"")

    @Test
    fun `count phrases`() {
        assertKind(QuestionKind.COUNT, "How many times did I mow in August?")
        assertKind(QuestionKind.COUNT, "how much did I run this year")
        assertKind(QuestionKind.COUNT, "What's the number of times I cleaned the hot tub?")
    }

    @Test
    fun `how often phrases`() {
        assertKind(QuestionKind.HOW_OFTEN, "How often do I change the oil?")
        assertKind(QuestionKind.HOW_OFTEN, "how regularly do I water the plants")
        assertKind(QuestionKind.HOW_OFTEN, "How frequently have I mowed?")
    }

    @Test
    fun `last time phrases`() {
        assertKind(QuestionKind.LAST_TIME, "When did I last mow the lawn?")
        assertKind(QuestionKind.LAST_TIME, "When was the last oil change?")
        assertKind(QuestionKind.LAST_TIME, "The last time I cleaned the gutters?")
        assertKind(QuestionKind.LAST_TIME, "Did I ever paint the fence?")
        assertKind(QuestionKind.LAST_TIME, "Have I ever fixed the gate?")
        assertKind(QuestionKind.LAST_TIME, "When did I clean the hot tub?")
    }

    @Test
    fun `list phrases only at the start`() {
        assertKind(QuestionKind.LIST, "What did I do last week?")
        assertKind(QuestionKind.LIST, "What have I logged?")
        assertKind(QuestionKind.LIST, "What else did I do yesterday?")
        assertKind(QuestionKind.LIST, "Show me August")
        assertKind(QuestionKind.LIST, "List everything from June")
        assertKind(null, "Can you show me August?")
        assertKind(null, "Is there a list of chores?")
    }

    @Test
    fun `earlier rules win`() {
        // COUNT before HOW_OFTEN.
        assertKind(QuestionKind.COUNT, "How many times and how often did I mow?")
        // HOW_OFTEN before LAST_TIME.
        assertKind(QuestionKind.HOW_OFTEN, "How often, since the last time I checked, did I mow?")
        // LAST_TIME before LIST.
        assertKind(QuestionKind.LAST_TIME, "What did I do the last time I visited?")
        // COUNT before LIST.
        assertKind(QuestionKind.COUNT, "Show me how many times I mowed")
    }

    @Test
    fun `phrases match whole words only`() {
        assertKind(null, "Somehow manya things?")
        assertKind(null, "showme the lawn")
        assertKind(null, "listen to the furnace?")
        assertKind(null, "how oftentimes?")
        assertKind(QuestionKind.COUNT, "HOW   MANY, times?")
    }

    @Test
    fun `curly apostrophes and punctuation are normalized`() {
        assertKind(QuestionKind.COUNT, "“How many” times did I mow?")
        assertKind(QuestionKind.LAST_TIME, "When did I last mow? I’m not sure")
        assertKind(QuestionKind.LIST, "...what did I do?")
    }

    @Test
    fun `other text has no kind`() {
        assertNull(QuestionKindDetector.detect(""))
        assertNull(QuestionKindDetector.detect("   "))
        assertNull(QuestionKindDetector.detect("mowed the lawn"))
        assertNull(QuestionKindDetector.detect("Where is the furnace filter?"))
        assertNull(QuestionKindDetector.detect("When is trash day?"))
    }
}
