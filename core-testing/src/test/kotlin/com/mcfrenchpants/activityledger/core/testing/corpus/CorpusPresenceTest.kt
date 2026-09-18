package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Proves the corpus covers every sentence the test strategy and AGENTS.md require. */
class CorpusPresenceTest {

    private val corpus = SemanticCorpus.load()

    private fun String.key() = trim().lowercase(Locale.ROOT)

    private fun casesWithText(sentence: String) = corpus.cases.filter { it.rawText.key() == sentence.key() }

    private fun casesContaining(phrase: String) = corpus.cases.filter { phrase.key() in it.rawText.key() }

    @Test
    fun `every required sentence is present verbatim`() {
        val missing = REQUIRED_SENTENCES.filter { casesWithText(it).isEmpty() }
        if (missing.isNotEmpty()) fail("missing ${missing.size} required sentence(s): indices ${missing.map(REQUIRED_SENTENCES::indexOf)}")
    }

    @Test
    fun `AGENTS section 6 examples are present with the right expectations`() {
        val cut = casesContaining("cut the grass")
        assertTrue(cut.isNotEmpty(), "no case contains 'cut the grass'")
        cut.forEach {
            assertEquals(ActivityResolution.EXISTING_ACTIVITY, it.expected.resolution, it.id)
            assertEquals("act-mow-lawn", it.expected.activityId, it.id)
        }

        val edged = casesContaining("edged the lawn")
        assertTrue(edged.isNotEmpty(), "no case contains 'edged the lawn'")
        edged.forEach { assertTrue("act-mow-lawn" in it.expected.mustNotMatch, "${it.id} must not match Mow lawn") }

        val yesterday = casesContaining("changed the furnace filter yesterday")
        assertTrue(yesterday.isNotEmpty(), "no case contains 'changed the furnace filter yesterday'")
        yesterday.forEach {
            val captureDate = it.capturedInstant.atZone(it.zone).toLocalDate()
            assertEquals(captureDate.minusDays(1), it.expected.expectedLocalDate, "${it.id} must resolve to yesterday")
        }
    }

    @Test
    fun `seed groups expect their canonical activity and edging never matches mowing`() {
        SEED_GROUPS.forEach { (activityId, sentences) ->
            sentences.forEach { sentence ->
                val household = casesWithText(sentence).filter { it.catalog == "household" }
                assertTrue(household.isNotEmpty(), "seed sentence for $activityId has no household case")
                household.forEach { assertEquals(activityId, it.expected.activityId, it.id) }
            }
        }
        EDGE_LAWN.forEach { sentence ->
            casesWithText(sentence).forEach { assertTrue("act-mow-lawn" in it.expected.mustNotMatch, "${it.id} must not match Mow lawn") }
        }
    }

    @Test
    fun `required scenario shapes exist`() {
        val cases = corpus.cases

        fun newNeighbour(neighbourId: String) = cases.any {
            it.category == CorpusCategory.NEAR_NEIGHBOUR &&
                it.expected.resolution == ActivityResolution.NEW_ACTIVITY &&
                neighbourId in it.expected.mustNotMatch &&
                corpus.catalogFor(it).any { a -> a.id == neighbourId }
        }
        assertTrue(newNeighbour("act-blow-leaves"), "raked-leaves vs Blow leaves case missing")
        assertTrue(newNeighbour("act-wax-car"), "washed-car vs Wax car case missing")

        assertTrue(
            cases.any { c ->
                c.expected.resolution == ActivityResolution.NEW_ACTIVITY &&
                    "act-mow-lawn" in c.expected.mustNotMatch &&
                    corpus.catalogFor(c).let { cat -> cat.any { it.id == "act-mow-lawn" } && cat.none { it.id == "act-edge-lawn" } }
            },
            "edging against a catalog without Edge lawn missing",
        )
        assertTrue(
            cases.any { c ->
                c.expected.activityId == "act-edge-lawn" && corpus.catalogFor(c).any { it.id == "act-mow-lawn" }
            },
            "edging against a catalog with both Mow lawn and Edge lawn missing",
        )
        assertTrue(
            cases.any { it.expected.resolution == ActivityResolution.NEW_ACTIVITY && corpus.catalogFor(it).isEmpty() },
            "new activity against an empty catalog missing",
        )
        assertTrue(
            cases.any { it.expected.newActivityName == "Flush water heater" && corpus.catalogFor(it).isNotEmpty() },
            "new activity against a non-empty catalog missing",
        )
        assertTrue(
            cases.any { c ->
                "dryer" in c.rawText.key() && "vent" !in c.rawText.key() &&
                    c.expected.outcome == ExpectedOutcome.NEEDS_REVIEW &&
                    "act-clean-dryer-vent" in c.expected.mustNotMatch
            },
            "generic dryer case that must not auto-accept Clean dryer vent missing",
        )
        assertTrue(
            cases.any { it.expected.allowedStates == listOf(ActivityState.IN_PROGRESS) },
            "in-progress case missing",
        )
        assertTrue(
            cases.any { it.id == "state-im-mowing-now" && it.expected.allowedStates == listOf(ActivityState.IN_PROGRESS) },
            "'I'm mowing now.' must expect IN_PROGRESS",
        )
    }

    @Test
    fun `ambiguity sentences expect review`() {
        AMBIGUITY.forEach { sentence ->
            casesWithText(sentence).forEach {
                assertEquals(ExpectedOutcome.NEEDS_REVIEW, it.expected.outcome, it.id)
                assertEquals(CorpusCategory.AMBIGUITY, it.category, it.id)
            }
        }
    }

    @Test
    fun `default capture context is the fixed one`() {
        corpus.cases.forEach {
            assertEquals("2026-09-15T20:00:00-04:00", it.capturedAt, it.id)
            assertEquals("America/Detroit", it.zoneId, it.id)
        }
        assertEquals(LocalDate.of(2026, 9, 15).dayOfWeek, java.time.DayOfWeek.TUESDAY)
    }

    private companion object {
        val MOW_LAWN = listOf(
            "I mowed the lawn.", "I cut the grass.", "Finished mowing.", "Just mowed.", "I did the grass.", "The lawn is cut.",
        )
        val EDGE_LAWN = listOf("I edged the lawn.", "Finished edging.", "Did the lawn edges.")
        val FURNACE = listOf(
            "Changed the furnace filter.", "Replaced the HVAC filter.", "Put a new filter in the furnace.", "Swapped the air filter.",
        )
        val GUTTERS = listOf("Cleaned the gutters.", "Cleared leaves out of the gutters.", "Did the gutters.")
        val DRYER_VENT = listOf("Cleaned the dryer vent.", "Cleared lint out of the dryer pipe.")

        // TEST_STRATEGY section 5.
        val TEMPORAL = listOf(
            "Mowed yesterday.", "Mowed this morning.", "Mowed Saturday.", "Mowed about an hour ago.",
            "Just finished mowing.", "Mowed on September 1st.",
        )

        // TEST_STRATEGY section 6.
        val AMBIGUITY = listOf("Worked on the yard.", "Did the furnace thing.", "Handled the filter.", "Fixed that thing outside.")

        val SEED_GROUPS = mapOf(
            "act-mow-lawn" to MOW_LAWN,
            "act-edge-lawn" to EDGE_LAWN,
            "act-replace-furnace-filter" to FURNACE,
            "act-clean-gutters" to GUTTERS,
            "act-clean-dryer-vent" to DRYER_VENT,
        )

        // TEST_STRATEGY section 4 (all groups), 5 and 6.
        val REQUIRED_SENTENCES = MOW_LAWN + EDGE_LAWN + FURNACE + GUTTERS + DRYER_VENT + TEMPORAL + AMBIGUITY
    }
}
