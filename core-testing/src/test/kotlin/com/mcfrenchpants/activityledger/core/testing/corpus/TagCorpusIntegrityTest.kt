package com.mcfrenchpants.activityledger.core.testing.corpus

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural integrity of tag-corpus.json. Failures are collected so one run lists every broken
 * case. Messages name case ids and fields only, never sentence text.
 */
class TagCorpusIntegrityTest {

    private val corpus = TagCorpus.load()

    private fun check(block: MutableList<String>.() -> Unit) {
        val problems = mutableListOf<String>().apply(block)
        if (problems.isNotEmpty()) fail(problems.joinToString(separator = "\n"))
    }

    private fun normalizedName(name: String): String = name.trim().lowercase()

    @Test
    fun `loads with a schema version, a stable sha256 and LF-only bytes`() {
        assertEquals(1, corpus.schemaVersion)
        assertTrue(Regex("[0-9a-f]{64}").matches(corpus.sha256), "sha256 must be 64 lowercase hex chars")
        assertEquals(corpus.sha256, TagCorpus.load().sha256, "hash must be stable across loads")
        val bytes = TagCorpus.resourceBytes()
        assertFalse(bytes.contains('\r'.code.toByte()), "tag-corpus.json must use LF line endings only")
    }

    @Test
    fun `case ids are unique and kebab-case`() = check {
        corpus.cases.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("duplicate id $it") }
        val kebab = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        corpus.cases.filterNot { kebab.matches(it.id) }.forEach { add("id not kebab-case: ${it.id}") }
    }

    @Test
    fun `case ids carry their group prefix`() = check {
        val prefixes = mapOf(
            TagCaseGroup.PORTED to "p-",
            TagCaseGroup.REAL_ENTRY to "real-",
            TagCaseGroup.SIBLING to "sib-",
            TagCaseGroup.EMPTY_START to "empty-",
        )
        corpus.cases.filterNot { it.id.startsWith(prefixes.getValue(it.group)) }.forEach { add("${it.id}: wrong prefix for ${it.group}") }
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
    fun `catalog fixtures are well formed`() = check {
        val kebab = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        corpus.catalogs.forEach { (name, fixture) ->
            val all = fixture.subjects + fixture.actions
            all.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("catalog $name: duplicate tag id $it") }
            fixture.subjects.filterNot { it.id.startsWith("subj-") && kebab.matches(it.id) }.forEach { add("catalog $name: bad subject id ${it.id}") }
            fixture.actions.filterNot { it.id.startsWith("act-") && kebab.matches(it.id) }.forEach { add("catalog $name: bad action id ${it.id}") }
            listOf("subject" to fixture.subjects, "action" to fixture.actions).forEach { (kind, tags) ->
                val names = tags.flatMap { t -> (listOf(t.displayName) + t.aliases).map(::normalizedName) }
                names.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { add("catalog $name: duplicate $kind name/alias '$it'") }
                tags.filter { it.displayName.isBlank() }.forEach { add("catalog $name: blank $kind name ${it.id}") }
            }
            val subjectIds = fixture.subjects.map { it.id }.toSet()
            val actionIds = fixture.actions.map { it.id }.toSet()
            fixture.pairs.forEach { p ->
                if (p.subjectId !in subjectIds) add("catalog $name: pair subject ${p.subjectId} not a subject")
                if (p.actionId !in actionIds) add("catalog $name: pair action ${p.actionId} not an action")
                if (p.displayName.isBlank()) add("catalog $name: pair ${p.subjectId}+${p.actionId} has a blank name")
            }
            fixture.pairs.groupBy { it.subjectId to it.actionId }.filterValues { it.size > 1 }.keys
                .forEach { add("catalog $name: duplicate pair ${it.first}+${it.second}") }
        }
        val empty = corpus.catalogs["empty"]
        if (empty == null || empty.subjects.isNotEmpty() || empty.actions.isNotEmpty() || empty.pairs.isNotEmpty()) {
            add("catalog empty must exist and have no subjects, actions or pairs")
        }
        assertEquals(setOf("empty", "household", "household-no-edge", "owner-2026-10-01"), corpus.catalogs.keys)
    }

    @Test
    fun `every catalog reference and referenced tag id exists under the right kind`() = check {
        corpus.cases.forEach { c ->
            val fixture = corpus.catalogs[c.catalog]
            if (fixture == null) {
                add("${c.id}: unknown catalog ${c.catalog}")
                return@forEach
            }
            listOf(
                Triple("subject", c.expected.subject, fixture.subjects.map { it.id }.toSet()),
                Triple("action", c.expected.action, fixture.actions.map { it.id }.toSet()),
            ).forEach { (kind, tag, ids) ->
                tag.existingId?.let { if (it !in ids) add("${c.id}: $kind existingId $it is not a $kind in ${c.catalog}") }
                tag.allowedExistingIds.filterNot { it in ids }.forEach { add("${c.id}: $kind allowedExistingIds $it is not a $kind in ${c.catalog}") }
                tag.mustNotMatch.filterNot { it in ids }.forEach { add("${c.id}: $kind mustNotMatch $it is not a $kind in ${c.catalog}") }
            }
        }
    }

    @Test
    fun `expected tags are consistent`() = check {
        corpus.cases.forEach { c ->
            val e = c.expected
            val fixture = corpus.catalogs[c.catalog] ?: return@forEach
            val isEmptyCatalog = fixture.subjects.isEmpty() && fixture.actions.isEmpty()
            listOf(
                Triple("subject", e.subject, fixture.subjects),
                Triple("action", e.action, fixture.actions),
            ).forEach { (kind, tag, existing) ->
                val where = "${c.id}: $kind"
                val hasExisting = tag.existingId != null
                val hasNew = tag.newName != null
                if (hasExisting && hasNew) add("$where sets both existingId and newName")
                if (!hasExisting && !hasNew && !tag.mayBeEmpty && e.outcome != TagOutcome.NEEDS_REVIEW) {
                    add("$where sets neither existingId nor newName without mayBeEmpty or NEEDS_REVIEW")
                }
                if (!hasNew && tag.allowedNewNames.isNotEmpty()) add("$where allowedNewNames without newName")
                if (tag.existingId != null && tag.existingId in tag.mustNotMatch) add("$where existingId is also in mustNotMatch")
                if (tag.existingId != null && tag.existingId in tag.allowedExistingIds) add("$where allowedExistingIds repeats existingId")
                tag.allowedExistingIds.filter { it in tag.mustNotMatch }.forEach { add("$where allowedExistingIds $it is also in mustNotMatch") }
                if (tag.allowedExistingIds.size != tag.allowedExistingIds.toSet().size) add("$where allowedExistingIds has duplicates")
                if (tag.mustNotMatch.size != tag.mustNotMatch.toSet().size) add("$where mustNotMatch has duplicates")
                if (isEmptyCatalog && (hasExisting || tag.allowedExistingIds.isNotEmpty())) add("$where expects an existing tag in an empty catalog")

                val names = tag.acceptableNewNames.map(::normalizedName)
                if (names.any { it.isEmpty() }) add("$where has a blank new name")
                if (names.size != names.toSet().size) add("$where new names repeat (case-insensitive)")
                val taken = existing.flatMap { t -> (listOf(t.displayName) + t.aliases).map(::normalizedName) }.toSet()
                names.forEachIndexed { i, n -> if (n in taken) add("$where new name #$i duplicates an existing $kind") }
            }
        }
    }

    @Test
    fun `outcome, state, duration and time fields are consistent`() = check {
        corpus.cases.forEach { c ->
            val e = c.expected
            if (e.outcome in e.allowedOutcomes) add("${c.id}: allowedOutcomes repeats outcome")
            if (e.allowedOutcomes.size != e.allowedOutcomes.toSet().size) add("${c.id}: allowedOutcomes has duplicates")
            if (e.allowedStates.isEmpty()) add("${c.id}: allowedStates is empty")
            if (e.allowedStates.size != e.allowedStates.toSet().size) add("${c.id}: allowedStates has duplicates")
            if (e.outcome == TagOutcome.AUTO_SAVE && null in e.allowedStates) add("${c.id}: AUTO_SAVE allows a missing state")
            if (c.category == CorpusCategory.AMBIGUITY && e.outcome != TagOutcome.NEEDS_REVIEW) add("${c.id}: AMBIGUITY must expect NEEDS_REVIEW")

            val hasDuration = e.durationExpression != null
            val minutes = e.durationMinutes
            if (hasDuration != (minutes != null)) add("${c.id}: durationExpression and durationMinutes must be set together")
            if (minutes != null && minutes <= 0) add("${c.id}: durationMinutes must be positive")
            if (!hasDuration && e.allowedDurationExpressions.isNotEmpty()) add("${c.id}: duration alternatives without a durationExpression")

            val hasTime = e.temporalExpression != null
            if (hasTime != (e.resolvedLocalDate != null)) add("${c.id}: temporalExpression and resolvedLocalDate must be set together")
            if (hasTime != (e.precision != null)) add("${c.id}: temporalExpression and precision must be set together")
            if (!hasTime && e.allowedTemporalExpressions.isNotEmpty()) add("${c.id}: time alternatives without a temporalExpression")
            e.resolvedLocalDate?.let { d -> runCatching { LocalDate.parse(d) }.onFailure { add("${c.id}: bad resolvedLocalDate") } }
            if (c.knownResolverGap != null && !hasTime) add("${c.id}: knownResolverGap on a case with no time")
        }
    }

    @Test
    fun `every corpus case has exactly one faithful ported counterpart`() = check {
        val source = SemanticCorpus.load()
        corpus.cases.forEach { c ->
            if ((c.group == TagCaseGroup.PORTED) != (c.sourceCaseId != null)) add("${c.id}: sourceCaseId must be set exactly for PORTED cases")
        }
        val ported = corpus.cases.filter { it.group == TagCaseGroup.PORTED }
        ported.groupBy { it.sourceCaseId }.filterValues { it.size > 1 }.keys.forEach { add("source $it ported more than once") }
        val sourceIds = source.cases.map { it.id }.toSet()
        ported.filter { it.sourceCaseId !in sourceIds }.forEach { add("${it.id}: sourceCaseId ${it.sourceCaseId} not in corpus.json") }
        source.cases.forEach { s ->
            val p = ported.singleOrNull { it.sourceCaseId == s.id }
            if (p == null) {
                add("corpus case ${s.id} has no single PORTED counterpart")
                return@forEach
            }
            val where = "${p.id} (from ${s.id})"
            if (p.id != "p-${s.id}") add("$where: id must be p-<source id>")
            if (p.rawText != s.rawText) add("$where: rawText differs")
            if (p.capturedAt != s.capturedAt || p.zoneId != s.zoneId) add("$where: capture context differs")
            if (p.category != s.category) add("$where: category differs")
            if (p.catalog != s.catalog) add("$where: catalog fixture name differs")
            if (p.knownResolverGap != s.knownResolverGap) add("$where: knownResolverGap differs")
            val pe = p.expected
            val se = s.expected
            if (pe.allowedStates != se.allowedStates) add("$where: allowedStates differ")
            if (pe.temporalExpression != se.temporalExpression) add("$where: temporalExpression differs")
            if (pe.allowedTemporalExpressions != se.allowedTemporalExpressions) add("$where: allowedTemporalExpressions differ")
            if (pe.resolvedLocalDate != se.resolvedLocalDate) add("$where: resolvedLocalDate differs")
            if (pe.precision != se.precision) add("$where: precision differs")
            if (pe.durationExpression != null || pe.durationMinutes != null) add("$where: ported cases state no duration")
            val expectedOutcome = if (se.outcome == ExpectedOutcome.AUTO_ACCEPT) TagOutcome.AUTO_SAVE else TagOutcome.NEEDS_REVIEW
            if (pe.outcome != expectedOutcome) add("$where: outcome does not follow the source outcome")
        }
    }

    @Test
    fun `real entries are the owner's exact sentences`() {
        val expected = listOf(
            "I just changed the furnace filter",
            "I just changed the hot tub filter",
            "Yesterday I changed the oil in the tractor",
            "It just went and got gas for the lawn mower",
            "I weeded the garden for half an hour",
            "Yesterday I replaced the headlight on James Durango",
            "I just walked the dogs for about 30 minutes",
            "Add to reboot the Wi-Fi",
            "I just cleaned the hot tub",
            "Spent 40 minutes mowing the lawn",
            "I just put all the dishes away",
            "Add sanitizer to the hot tub",
        )
        val real = corpus.cases.filter { it.group == TagCaseGroup.REAL_ENTRY }
        assertEquals(expected, real.map { it.rawText })
        assertTrue(real.all { it.catalog == "owner-2026-10-01" }, "real entries use the owner-2026-10-01 fixture")
        assertTrue(corpus.cases.filter { it.group == TagCaseGroup.SIBLING }.all { it.catalog == "owner-2026-10-01" }, "siblings use the owner-2026-10-01 fixture")
        val emptyStart = corpus.cases.filter { it.group == TagCaseGroup.EMPTY_START }
        assertTrue(emptyStart.all { it.catalog == "empty" }, "empty-start cases use the empty fixture")
        assertEquals(listOf(expected[0], expected[5], expected[6], expected[7]), emptyStart.map { it.rawText })
    }

    @Test
    fun `group, category and outcome counts are pinned`() {
        assertEquals(72, corpus.cases.size)
        assertEquals(
            mapOf(
                TagCaseGroup.PORTED to 48, TagCaseGroup.REAL_ENTRY to 12,
                TagCaseGroup.SIBLING to 8, TagCaseGroup.EMPTY_START to 4,
            ),
            corpus.cases.groupingBy { it.group }.eachCount(),
        )
        assertEquals(
            mapOf(
                CorpusCategory.SYNONYM to 25, CorpusCategory.NEAR_NEIGHBOUR to 16, CorpusCategory.NEW_ACTIVITY to 13,
                CorpusCategory.TEMPORAL to 11, CorpusCategory.AMBIGUITY to 4, CorpusCategory.STATE to 3,
            ),
            corpus.cases.groupingBy { it.category }.eachCount(),
        )
        assertEquals(
            mapOf(TagOutcome.AUTO_SAVE to 68, TagOutcome.NEEDS_REVIEW to 4),
            corpus.cases.groupingBy { it.expected.outcome }.eachCount(),
        )
        assertEquals(4, corpus.cases.count { it.expected.durationExpression != null })
    }
}
