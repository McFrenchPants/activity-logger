package com.mcfrenchpants.activityledger.core.domain.lookup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionDetectorTest {

    @Test
    fun `question mark makes a question`() {
        assertTrue(QuestionDetector.isQuestion("the furnace filter?"))
        assertTrue(QuestionDetector.isQuestion("  mowed the lawn?  "))
    }

    @Test
    fun `first word list makes a question without a question mark`() {
        listOf(
            "when did I last change the furnace filter",
            "How many times did I mow",
            "What did I do to the hot tub",
            "which filter did I change",
            "Where did I put the sanitizer",
            "who cleaned the gutters",
            "Does the lawn need mowing",
            "have I cleaned the hot tub",
            "has the filter been changed",
            "Show me the furnace filter",
            "tell me when I mowed",
            "list the hot tub entries",
            "\"When did I mow\"",
        ).forEach { assertTrue(QuestionDetector.isQuestion(it), it) }
    }

    @Test
    fun `statements are not questions`() {
        listOf(
            "I just changed the furnace filter",
            "Mowed the lawn for 40 minutes",
            "I just cleaned the hot tub",
            "Add sanitizer to the hot tub",
            "Changed the hot tub filter",
            "Whenever I mow it rains",
            "Doing the dishes",
        ).forEach { assertFalse(QuestionDetector.isQuestion(it), it) }
    }

    @Test
    fun `blank text is not a question`() {
        assertFalse(QuestionDetector.isQuestion(""))
        assertFalse(QuestionDetector.isQuestion("   \n "))
    }

    @Test
    fun `known false positive - a statement starting with Did is accepted as a question`() {
        assertTrue(QuestionDetector.isQuestion("Did the laundry"))
    }
}
