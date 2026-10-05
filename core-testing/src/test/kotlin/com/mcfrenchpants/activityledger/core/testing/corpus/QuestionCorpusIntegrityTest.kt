package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.lookup.DateWords
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionDetector
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Structure and content rules of question-corpus.json. */
class QuestionCorpusIntegrityTest {

    private val corpus = QuestionCorpus.load()
    private val bytes = QuestionCorpus.resourceBytes()

    @Test
    fun `decodes strictly with schema version 1 and a sha256 of the file bytes`() {
        assertEquals(1, corpus.schemaVersion)
        assertTrue(corpus.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(corpus.sha256, QuestionCorpus.parse(bytes).sha256)
        assertTrue(corpus.description.isNotBlank())
    }

    @Test
    fun `file is UTF-8 with LF line endings only`() {
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains('\r'), "question-corpus.json must use LF line endings")
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `unknown keys and missing required fields are rejected`() {
        val text = bytes.toString(Charsets.UTF_8)
        assertFailsWith<Exception> {
            QuestionCorpus.parse(text.replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"extra\": true,").toByteArray())
        }
        assertFailsWith<Exception> {
            QuestionCorpus.parse(text.replaceFirst("\"firstDayOfWeek\": \"SUNDAY\",", "").toByteArray())
        }
        assertFailsWith<Exception> {
            QuestionCorpus.parse(text.replaceFirst("\"mustNotMatch\": [],", "").toByteArray())
        }
        assertFailsWith<Exception> {
            QuestionCorpus.parse(
                text.replaceFirst("\"preset\": \"ALL_TIME\"", "\"preset\": \"ALL_TIME\", \"from\": \"2026-01-01\", \"to\": \"2026-01-02\"")
                    .toByteArray(),
            )
        }
    }

    @Test
    fun `case ids are unique kebab-case and catalogs exist`() {
        val ids = corpus.cases.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate case ids")
        ids.forEach { assertTrue(it.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")), "bad id $it") }
        assertEquals(setOf("empty", "household-q"), corpus.catalogs.keys)
        corpus.cases.forEach { assertNotNull(corpus.catalogs[it.catalog], "case ${it.id} has an unknown catalog") }
    }

    @Test
    fun `fixtures have unique prefixed ids and entries reference existing tags`() {
        corpus.catalogs.forEach { (name, f) ->
            val subjectIds = f.subjects.map { it.id }
            val actionIds = f.actions.map { it.id }
            assertEquals(subjectIds.size, subjectIds.toSet().size, "$name: duplicate subject ids")
            assertEquals(actionIds.size, actionIds.toSet().size, "$name: duplicate action ids")
            subjectIds.forEach { assertTrue(it.startsWith("subj-"), "$name: $it") }
            actionIds.forEach { assertTrue(it.startsWith("act-"), "$name: $it") }
            val entryIds = f.entries.map { it.id }
            assertEquals(entryIds.size, entryIds.toSet().size, "$name: duplicate entry ids")
            f.entries.forEach { e ->
                assertTrue(e.subjectId in subjectIds, "$name: entry ${e.id} has an unknown subject")
                assertTrue(e.actionId in actionIds, "$name: entry ${e.id} has an unknown action")
                e.occurredDateTime
            }
            // Builds the domain catalog (checks its own rules).
            QuestionFixtureRepository.catalogOf(f)
        }
        val empty = corpus.catalogs.getValue("empty")
        assertTrue(empty.subjects.isEmpty() && empty.actions.isEmpty() && empty.entries.isEmpty())
    }

    @Test
    fun `every case is asked on 2026-10-05 in Detroit with Sunday weeks and nothing is logged after that`() {
        corpus.cases.forEach { c ->
            assertEquals("2026-10-05", c.today, c.id)
            assertEquals("America/Detroit", c.zoneId, c.id)
            assertEquals("SUNDAY", c.firstDayOfWeek, c.id)
            assertEquals(DayOfWeek.SUNDAY, c.weekStart)
            corpus.fixtureOf(c).entries.forEach { e ->
                assertFalse(
                    e.occurredDateTime.atZoneSameInstant(c.zone).toLocalDate().isAfter(c.todayDate),
                    "entry ${e.id} is after the today of case ${c.id}",
                )
            }
        }
    }

    @Test
    fun `expected tag ids exist in the case's fixture and match the outcome`() {
        corpus.cases.forEach { c ->
            val f = corpus.fixtureOf(c)
            val subjects = f.subjects.map { it.id }.toSet()
            val actions = f.actions.map { it.id }.toSet()
            val e = c.expected
            e.subjectIds.forEach { assertTrue(it in subjects, "case ${c.id}: unknown subject $it") }
            e.actionIds.forEach { assertTrue(it in actions, "case ${c.id}: unknown action $it") }
            e.mustNotMatch.forEach { assertTrue(it in subjects || it in actions, "case ${c.id}: unknown mustNotMatch $it") }
            assertTrue(e.mustNotMatch.none { it in e.subjectIds || it in e.actionIds }, "case ${c.id}: mustNotMatch overlaps")
            assertFalse(e.kind in e.allowedKinds, "case ${c.id}: allowedKinds repeats kind")
            if (e.outcome != QuestionOutcome.ANSWER) {
                assertTrue(e.subjectIds.isEmpty() && e.actionIds.isEmpty(), "case ${c.id}: ids on a non-answer")
            } else {
                assertTrue(e.subjectIds.isNotEmpty() || e.actionIds.isNotEmpty(), "case ${c.id}: an answer needs a target")
            }
            val range = e.range.toDates()
            if (e.outcome != QuestionOutcome.NOT_ENOUGH_HISTORY && e.dateWords != DateWords.USED) {
                assertTrue(range.isAllTime, "case ${c.id}: no used date words must mean all time")
            }
            if (e.outcome == QuestionOutcome.BROWSE) {
                assertTrue(e.dateWords == DateWords.USED || e.kind == QuestionKind.LIST, "case ${c.id}: a browse needs dates or LIST")
            }
            if (!range.isAllTime) assertFalse(range.to!!.isAfter(c.todayDate), "case ${c.id}: range ends after today")
            if (c.catalog == "empty") assertEquals(QuestionOutcome.NOT_ENOUGH_HISTORY, e.outcome, c.id)
        }
    }

    @Test
    fun `group counts are pinned`() {
        val counts = corpus.cases.groupingBy { it.group }.eachCount()
        assertEquals(
            mapOf(
                QuestionCaseGroup.LAST_TIME to 5,
                QuestionCaseGroup.COUNT to 9,
                QuestionCaseGroup.HOW_OFTEN to 5,
                QuestionCaseGroup.LIST to 4,
                QuestionCaseGroup.DATE_ONLY to 5,
                QuestionCaseGroup.NOT_LOGGED to 7,
                QuestionCaseGroup.TRICKY to 11,
            ),
            counts,
        )
        assertEquals(46, corpus.cases.size)
        assertTrue(corpus.cases.count { it.catalog == "empty" } >= 3)
    }

    @Test
    fun `every question passes the question detector`() {
        corpus.cases.forEach { assertTrue(QuestionDetector.isQuestion(it.question), "case ${it.id} is not a question") }
        assertTrue(corpus.cases.count { !it.question.trim().endsWith("?") } >= 2, "need questions without a question mark")
    }

    @Test
    fun `the required cases are present verbatim`() {
        fun byQuestion(q: String) = assertNotNull(corpus.cases.singleOrNull { it.question == q }, "missing: $q").expected
        val all = ExpectedQuestionRange(preset = "ALL_TIME")

        byQuestion("How many times did I mow the lawn in August?").let {
            assertEquals(QuestionOutcome.ANSWER, it.outcome)
            assertEquals(listOf("subj-lawn"), it.subjectIds)
            assertEquals(listOf("act-mow"), it.actionIds)
            assertEquals(QuestionKind.COUNT, it.kind)
            assertEquals(ExpectedQuestionRange(from = "2026-08-01", to = "2026-08-31"), it.range)
            assertEquals(DateWords.USED, it.dateWords)
        }
        byQuestion("How often do I change the oil?").let {
            assertEquals(QuestionOutcome.ANSWER, it.outcome)
            assertEquals(listOf("act-change-oil"), it.actionIds)
            assertEquals(QuestionKind.HOW_OFTEN, it.kind)
            assertEquals(all, it.range)
            assertEquals(DateWords.NONE, it.dateWords)
        }
        byQuestion("When was the last time I cleaned the gutters?").let {
            assertEquals(QuestionOutcome.NOT_ENOUGH_HISTORY, it.outcome)
            assertEquals(setOf("subj-grill", "subj-windows", "act-clean"), it.mustNotMatch.toSet())
        }
        byQuestion("When did I last change the furnace filter?").let {
            assertEquals(listOf("subj-furnace"), it.subjectIds)
            assertEquals(listOf("act-change-filter"), it.actionIds)
        }
        byQuestion("What did I do last week?").let {
            assertEquals(QuestionOutcome.BROWSE, it.outcome)
            assertEquals(ExpectedQuestionRange(from = "2026-09-27", to = "2026-10-03"), it.range)
            assertEquals(QuestionKind.LIST, it.kind)
        }
        val questions = corpus.cases.map { it.question }
        listOf("Did I ever ", "Have I ever ").forEach { p -> assertTrue(questions.any { it.startsWith(p) }, "no '$p' question") }
        listOf("this year", "last month", "in 2025", "since June", "in the past 30 days", "yesterday", "next week", "around the holidays")
            .forEach { w -> assertTrue(questions.any { it.contains(w) }, "no question with '$w'") }
        val future = corpus.cases.single { it.question.contains("next week") }
        assertEquals(QuestionOutcome.NOT_ENOUGH_HISTORY, future.expected.outcome)
        assertTrue(
            corpus.cases.any { it.expected.outcome == QuestionOutcome.ANSWER && it.expected.dateWords == DateWords.NOT_UNDERSTOOD },
            "need an answer with date words nobody understands",
        )
    }

    @Test
    fun `the household fixture has the shape the cases rely on`() {
        val f = corpus.catalogs.getValue("household-q")
        val zone = ZoneId.of("America/Detroit")
        fun dates(subject: String, action: String): List<LocalDate> =
            f.entries.filter { it.subjectId == subject && it.actionId == action }
                .map { it.occurredDateTime.atZoneSameInstant(zone).toLocalDate() }.sorted()

        assertEquals(4, dates("subj-lawn", "act-mow").count { YearMonth.from(it) == YearMonth.of(2026, 8) })
        listOf(6, 7, 9).forEach { m -> assertTrue(dates("subj-lawn", "act-mow").any { YearMonth.from(it) == YearMonth.of(2026, m) }) }
        assertTrue(dates("subj-car", "act-change-oil").size >= 4)
        assertEquals(YearMonth.of(2026, 9), YearMonth.from(dates("subj-furnace", "act-change-filter").last()))
        assertTrue(dates("subj-hot-tub", "act-add-chlorine").size >= 20)
        val walks = dates("subj-dogs", "act-walk")
        assertTrue(walks.size >= 20)
        assertTrue(walks.any { !it.isBefore(LocalDate.of(2026, 9, 27)) && !it.isAfter(LocalDate.of(2026, 10, 3)) })
        assertEquals(2, dates("subj-chimney", "act-sweep").size)
        assertEquals(setOf("subj-grill", "subj-windows"), f.entries.filter { it.actionId == "act-clean" }.map { it.subjectId }.toSet())
        assertTrue(f.subjects.single { it.id == "subj-lawn" }.aliases.contains("yard"))
        assertTrue(f.subjects.single { it.id == "subj-car" }.aliases.contains("truck"))
        val first = f.entries.minOf { it.occurredDateTime.atZoneSameInstant(zone).toLocalDate() }
        assertEquals(YearMonth.of(2025, 6), YearMonth.from(first))
    }

    @Test
    fun `no worked example of the question prompt or its subjects is used`() {
        // The q2 prompt's worked examples (core-ai QuestionPrompt.kt); QuestionPromptTest checks
        // the other direction against the prompt text itself.
        val examples = listOf(
            "When did I last clean the gutters?",
            "How many times did I water the tomatoes last month?",
            "How often do I descale the kettle?",
            "Did I service the generator in May?",
            "What did I do on Monday?",
        )
        val questions = corpus.cases.map { it.question.lowercase() }
        examples.forEach { assertFalse(it.lowercase() in questions, "worked example used: $it") }
        val tagText = corpus.catalogs.values.flatMap { f -> (f.subjects + f.actions).flatMap { listOf(it.displayName) + it.aliases } }
            .joinToString(" ").lowercase()
        listOf("gutter", "tomato", "kettle", "generator").forEach { assertFalse(tagText.contains(it), "fixture names $it") }
        listOf("tomato", "kettle", "generator").forEach { s -> assertTrue(questions.none { it.contains(s) }, "question names $s") }
        corpus.cases.filter { it.question.lowercase().contains("gutter") }.forEach {
            assertEquals(QuestionCaseGroup.NOT_LOGGED, it.group, "gutters only in a not-logged case")
        }
    }
}
