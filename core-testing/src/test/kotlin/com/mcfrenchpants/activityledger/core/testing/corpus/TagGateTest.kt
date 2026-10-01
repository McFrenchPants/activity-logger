package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagGateTest {

    private val corpus = TagCorpus.load()

    private fun log(subject: String?, action: String?, time: String? = null) =
        RecordedExtraction(InterpretationOperation.LOG_ACTIVITY, subject, action, null, time, null)

    /** hot-tub filter correct; furnace-filter UNSAFE (REAL_ENTRY); finished-mowing UNSAFE (PORTED); everything else review. */
    private val result = TagReplay(corpus).replay(
        TagRecordings.withAnswers(
            corpus,
            mapOf(
                "real-changed-hot-tub-filter" to log("hot tub", "change filter", time = "just"),
                "real-changed-furnace-filter" to log("hot tub", "change filter", time = "just"),
                "p-mow-finished-mowing" to log(null, "water"),
            ),
        ),
    )

    @Test
    fun `parses a baseline strictly`() {
        val b = TagGate.parseBaseline("""{ "mustStayCorrect": ["a", "b"], "maxUnsafe": 3, "maxUnsafeRealEntry": 1 }""")
        assertEquals(TagBaseline(listOf("a", "b"), 3, 1), b)
        assertFailsWith<SerializationException> {
            TagGate.parseBaseline("""{ "mustStayCorrect": [], "maxUnsafe": 0, "maxUnsafeRealEntry": 0, "extra": 1 }""")
        }
        assertFailsWith<SerializationException> { TagGate.parseBaseline("""{ "mustStayCorrect": [], "maxUnsafe": 0 }""") }
        assertFailsWith<SerializationException> { TagGate.parseBaseline("""["a"]""") }
        assertFailsWith<IllegalArgumentException> {
            TagGate.parseBaseline("""{ "mustStayCorrect": ["a", "a"], "maxUnsafe": 0, "maxUnsafeRealEntry": 0 }""")
        }
        assertFailsWith<IllegalArgumentException> {
            TagGate.parseBaseline("""{ "mustStayCorrect": [], "maxUnsafe": -1, "maxUnsafeRealEntry": 0 }""")
        }
    }

    @Test
    fun `passes when correct cases stay correct and unsafe counts are within limits`() {
        val baseline = TagBaseline(listOf("real-changed-hot-tub-filter", "p-ambiguous-worked-on-the-yard"), 2, 1)
        assertEquals(emptyList(), TagGate.failures(result, baseline, corpus))
    }

    @Test
    fun `fails when a must-stay-correct case is not correct`() {
        val failures = TagGate.failures(result, TagBaseline(listOf("real-changed-furnace-filter", "real-reboot-wifi"), 2, 1), corpus)
        assertEquals(2, failures.size, failures.toString())
        assertTrue(failures[0].contains("real-changed-furnace-filter is UNSAFE"))
        assertTrue(failures[1].contains("real-reboot-wifi is SAFE_MISS"))
    }

    @Test
    fun `fails when total unsafe exceeds maxUnsafe`() {
        val failures = TagGate.failures(result, TagBaseline(emptyList(), 1, 1), corpus)
        assertEquals(1, failures.size, failures.toString())
        assertTrue(failures.single().startsWith("UNSAFE cases 2 exceed maxUnsafe 1"))
    }

    @Test
    fun `fails when real-entry unsafe exceeds maxUnsafeRealEntry`() {
        val failures = TagGate.failures(result, TagBaseline(emptyList(), 2, 0), corpus)
        assertEquals(1, failures.size, failures.toString())
        assertTrue(failures.single().startsWith("REAL_ENTRY UNSAFE cases 1 exceed maxUnsafeRealEntry 0"))
        assertTrue(failures.single().endsWith("real-changed-furnace-filter"))
    }

    @Test
    fun `fails on an unknown baseline id`() {
        val failures = TagGate.failures(result, TagBaseline(listOf("no-such-case"), 2, 1), corpus)
        assertEquals(listOf("tag baseline lists case ids not in the tag corpus: no-such-case"), failures)
    }
}
