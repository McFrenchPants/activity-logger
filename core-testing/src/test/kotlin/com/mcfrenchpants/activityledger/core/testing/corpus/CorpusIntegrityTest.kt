package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural integrity of corpus.json. Failures are collected so one run lists every broken
 * case. Messages name case ids and fields only, never sentence text.
 */
class CorpusIntegrityTest {

    private val corpus = SemanticCorpus.load()

    private fun check(block: MutableList<String>.() -> Unit) {
        val problems = mutableListOf<String>().apply(block)
        if (problems.isNotEmpty()) fail(problems.joinToString(separator = "\n"))
    }

    @Test
    fun `loads with a schema version and a stable sha256`() {
        assertEquals(1, corpus.schemaVersion)
        assertTrue(corpus.cases.size in 40..60, "expected roughly 40-50 cases, got ${corpus.cases.size}")
        assertTrue(Regex("[0-9a-f]{64}").matches(corpus.sha256), "sha256 must be 64 lowercase hex chars")
        assertEquals(corpus.sha256, SemanticCorpus.load().sha256, "hash must be stable across loads")
    }

    @Test
    fun `case ids are unique and kebab-case`() = check {
        corpus.cases.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("duplicate id $it") }
        val kebab = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        corpus.cases.filterNot { kebab.matches(it.id) }.forEach { add("id not kebab-case: ${it.id}") }
    }

    @Test
    fun `capture context parses`() = check {
        corpus.cases.forEach { c ->
            runCatching { OffsetDateTime.parse(c.capturedAt) }.onFailure { add("${c.id}: bad capturedAt") }
            runCatching { ZoneId.of(c.zoneId) }.onFailure { add("${c.id}: bad zoneId") }
            if (c.rawText.isBlank()) add("${c.id}: blank rawText")
        }
    }

    @Test
    fun `catalog fixtures are well formed and within the selector bound`() = check {
        corpus.catalogs.forEach { (name, fixture) ->
            if (fixture.activities.size > CandidateSelector.DEFAULT_BOUND) {
                add("catalog $name has ${fixture.activities.size} > ${CandidateSelector.DEFAULT_BOUND} activities")
            }
            fixture.activities.groupBy { it.id }.filterValues { it.size > 1 }.keys
                .forEach { add("catalog $name: duplicate id $it") }
            val names = fixture.activities.flatMap { a -> (listOf(a.displayName) + a.aliases).map(NameNormalizer::normalize) }
            names.groupBy { it }.filterValues { it.size > 1 }.keys
                .forEach { add("catalog $name: duplicate normalized name/alias '$it'") }
        }
    }

    @Test
    fun `every catalog reference and referenced activity id exists`() = check {
        corpus.cases.forEach { c ->
            val fixture = corpus.catalogs[c.catalog]
            if (fixture == null) {
                add("${c.id}: unknown catalog ${c.catalog}")
                return@forEach
            }
            val ids = fixture.activities.map { it.id }.toSet()
            c.expected.activityId?.let { if (it !in ids) add("${c.id}: activityId $it not in ${c.catalog}") }
            c.expected.mustNotMatch.filterNot { it in ids }.forEach { add("${c.id}: mustNotMatch $it not in ${c.catalog}") }
            if (c.expected.activityId != null && c.expected.activityId in c.expected.mustNotMatch) {
                add("${c.id}: activityId is also in mustNotMatch")
            }
        }
    }

    @Test
    fun `resolution fields are consistent`() = check {
        corpus.cases.forEach { c ->
            val e = c.expected
            if (e.resolution in e.allowedResolutions) add("${c.id}: allowedResolutions repeats resolution")
            when (e.resolution) {
                ActivityResolution.EXISTING_ACTIVITY -> {
                    if (e.activityId == null) add("${c.id}: EXISTING_ACTIVITY without activityId")
                    if (e.newActivityName != null || e.allowedNewNames.isNotEmpty()) add("${c.id}: EXISTING_ACTIVITY with a new name")
                }
                ActivityResolution.NEW_ACTIVITY -> {
                    if (e.newActivityName == null) add("${c.id}: NEW_ACTIVITY without newActivityName")
                    if (e.activityId != null) add("${c.id}: NEW_ACTIVITY with activityId")
                }
                ActivityResolution.AMBIGUOUS, ActivityResolution.UNRESOLVED -> {
                    if (e.activityId != null || e.newActivityName != null || e.allowedNewNames.isNotEmpty()) {
                        add("${c.id}: ${e.resolution} with an activity id or name")
                    }
                }
            }
            if (e.allowedStates.isEmpty()) add("${c.id}: allowedStates is empty")
            if (e.allowedStates.size != e.allowedStates.toSet().size) add("${c.id}: allowedStates has duplicates")
        }
    }

    @Test
    fun `auto-accept expectations are actually auto-acceptable`() = check {
        corpus.cases.filter { it.expected.outcome == ExpectedOutcome.AUTO_ACCEPT }.forEach { c ->
            val e = c.expected
            val loggable = setOf(ActivityResolution.EXISTING_ACTIVITY, ActivityResolution.NEW_ACTIVITY)
            if (!loggable.containsAll(e.acceptableResolutions)) add("${c.id}: AUTO_ACCEPT allows an unloggable resolution")
            // A missing state is a rejecting reason (ADR-027), so it can never be auto-accepted.
            if (null in e.allowedStates) add("${c.id}: AUTO_ACCEPT allows a missing state")
            if (c.category == CorpusCategory.AMBIGUITY) add("${c.id}: AMBIGUITY case expects AUTO_ACCEPT")
        }
    }

    @Test
    fun `ambiguity cases expect review`() = check {
        corpus.cases.filter { it.category == CorpusCategory.AMBIGUITY }.forEach { c ->
            if (c.expected.outcome != ExpectedOutcome.NEEDS_REVIEW) add("${c.id}: AMBIGUITY must expect NEEDS_REVIEW")
            if (c.expected.activityId != null) add("${c.id}: AMBIGUITY must not expect an activity")
        }
    }

    @Test
    fun `new activity names are valid, distinct from the catalog and normalize uniquely`() = check {
        corpus.cases.filter { it.expected.resolution == ActivityResolution.NEW_ACTIVITY }.forEach { c ->
            val catalog = corpus.catalogFor(c)
            val taken = catalog.flatMap { listOf(it.normalizedName) + it.normalizedAliases }.toSet()
            val normalized = c.expected.acceptableNewNames.map(NameNormalizer::normalize)
            if (normalized.size != normalized.toSet().size) add("${c.id}: new names repeat after normalization")
            c.expected.acceptableNewNames.forEachIndexed { i, name ->
                val result = NewActivityNameCheck.check(name)
                if (result is NewActivityNameCheck.Result.Invalid) add("${c.id}: new name #$i invalid (${result.reason})")
                if (normalized[i] in taken) add("${c.id}: new name #$i duplicates an existing activity")
            }
        }
    }

    @Test
    fun `time fields are set together`() = check {
        corpus.cases.forEach { c ->
            val e = c.expected
            val hasTime = e.temporalExpression != null
            if (hasTime != (e.resolvedLocalDate != null)) add("${c.id}: temporalExpression and resolvedLocalDate must be set together")
            if (hasTime != (e.precision != null)) add("${c.id}: temporalExpression and precision must be set together")
            if (!hasTime && e.allowedTemporalExpressions.isNotEmpty()) add("${c.id}: alternatives without a temporalExpression")
            e.resolvedLocalDate?.let { d -> runCatching { LocalDate.parse(d) }.onFailure { add("${c.id}: bad resolvedLocalDate") } }
            if (c.knownResolverGap != null && !hasTime) add("${c.id}: knownResolverGap on a case with no time")
        }
    }
}
