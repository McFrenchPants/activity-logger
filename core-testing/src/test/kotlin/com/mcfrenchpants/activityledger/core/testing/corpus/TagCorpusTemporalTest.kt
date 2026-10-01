package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hard deterministic check for the tag corpus, modelled on [CorpusTemporalTest]: every
 * acceptable time expression of every case with a time must resolve, via the real
 * [TemporalResolver], to the expected local date (in the case's zone) and precision.
 *
 * The set of failing cases must equal EXACTLY the set marked `knownResolverGap`, so both a
 * resolver fix and a resolver regression fail loudly. Duration words are never time words, so
 * cases that only state a duration behave like the no-time cases.
 */
class TagCorpusTemporalTest {

    private val corpus = TagCorpus.load()
    private val resolver = TemporalResolver()

    /** Describes why [case] does not resolve as expected, or null if every expression does. */
    private fun mismatch(case: TagCorpusCase): String? {
        val e = case.expected
        val problems = e.acceptableTemporalExpressions.mapIndexedNotNull { index, expression ->
            val label = if (index == 0) "primary" else "alternative #$index"
            when (val r = resolver.resolve(expression, case.capturedInstant, case.zone)) {
                is TemporalResolution.Resolved -> {
                    val date = r.occurredAt.atZone(case.zone).toLocalDate()
                    if (date == e.expectedLocalDate && r.precision == e.precision) {
                        null
                    } else {
                        "$label -> $date ${r.precision} (expected ${e.expectedLocalDate} ${e.precision})"
                    }
                }
                else -> "$label -> $r (expected ${e.expectedLocalDate} ${e.precision})"
            }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    @Test
    fun `resolver gaps are exactly the marked cases`() {
        val timed = corpus.cases.filter { it.expected.temporalExpression != null }
        assertTrue(timed.size >= 10, "expected at least 10 timed cases, got ${timed.size}")

        val actualGaps = timed.mapNotNull { c -> mismatch(c)?.let { c.id to it } }.toMap()
        val marked = corpus.cases.filter { it.knownResolverGap != null }.map { it.id }.toSet()

        val unmarked = actualGaps.filterKeys { it !in marked }
        val fixed = marked - actualGaps.keys
        val message = buildString {
            if (unmarked.isNotEmpty()) {
                append("Resolver disagrees with product-correct expectation on UNMARKED cases:\n")
                unmarked.forEach { (id, why) -> append("  $id: $why\n") }
            }
            if (fixed.isNotEmpty()) {
                append("Cases marked knownResolverGap now resolve correctly (remove the marker): $fixed\n")
            }
        }
        assertEquals(marked, actualGaps.keys, message)
    }

    @Test
    fun `cases without a time resolve to the capture instant`() {
        val untimed = corpus.cases.filter { it.expected.temporalExpression == null }
        assertTrue(untimed.any { it.expected.durationExpression != null }, "duration-only cases must be among the no-time cases")
        untimed.forEach { c ->
            val r = resolver.resolve(null, c.capturedInstant, c.zone)
            assertEquals(TemporalResolution.Resolved(c.capturedInstant, TimePrecision.INFERRED_NOW), r, c.id)
        }
    }
}
