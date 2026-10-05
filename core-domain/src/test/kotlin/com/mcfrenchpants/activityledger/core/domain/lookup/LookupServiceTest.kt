package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupServiceTest {

    private val base = Instant.parse("2026-01-01T00:00:00Z")
    private val today = LocalDate.of(2026, 10, 5)
    private val firstDay = DayOfWeek.MONDAY

    private val furnace = KnownTag("s-furnace", TagKind.SUBJECT, "furnace", emptyList())
    private val lawn = KnownTag("s-lawn", TagKind.SUBJECT, "lawn", emptyList())
    private val change = KnownTag("a-change", TagKind.ACTION, "change filter", emptyList())
    private val mow = KnownTag("a-mow", TagKind.ACTION, "mow", emptyList())

    private val catalog = TagCatalog(
        subjects = listOf(furnace, lawn),
        actions = listOf(change, mow),
        pairs = listOf(KnownPair("s-furnace", "a-change"), KnownPair("s-lawn", "a-mow")),
    )

    private fun entry(id: String, s: KnownTag, a: KnownTag, days: Long) =
        LookupEntry(id, s.id, s.displayName, a.id, a.displayName, base.plusSeconds(days * 86_400), null)

    private val entries = listOf(
        entry("o1", furnace, change, 10),
        entry("o2", furnace, change, 100),
        entry("o3", lawn, mow, 120),
        entry("o4", furnace, mow, 130),
    )

    private class FakeRepo(
        private val catalog: TagCatalog,
        private val entries: List<LookupEntry>,
    ) : TagRepository {
        var catalogLoads = 0
        var entryLoads = 0

        override suspend fun loadTagCatalog(): TagCatalog {
            catalogLoads++
            return catalog
        }

        override suspend fun loadLookupEntries(): List<LookupEntry> {
            entryLoads++
            return entries
        }

        override suspend fun loadExploreEntries(): List<ExploreEntry> = error("not used")

        override suspend fun acceptTagged(request: TaggedAcceptRequest): String = error("not used")
        override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome = error("not used")
        override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome =
            error("not used")
        override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome =
            error("not used")
        override suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords? = error("not used")
        override suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords? =
            error("not used")
    }

    private class FakeExtractor(private val result: QuestionExtractionResult) : QuestionExtractor {
        var calls = 0
        override val provenance = InterpreterProvenance("fake", "p1", 1)
        override suspend fun extract(questionText: String): QuestionExtractionResult {
            calls++
            return result
        }
    }

    private fun words(subject: String?, action: String?) =
        FakeExtractor(QuestionExtractionResult.Success(QuestionCandidate(subject, action)))

    private fun service(extractor: FakeExtractor, repo: FakeRepo = FakeRepo(catalog, entries)) =
        LookupService(repo, extractor)

    private val question = "when did I last change the furnace filter"

    @Test
    fun `statement and blank text are not questions and touch nothing`() = runSuspend {
        val repo = FakeRepo(catalog, entries)
        val extractor = words("furnace", "change filter")
        val svc = service(extractor, repo)
        assertEquals(LookupOutcome.NotAQuestion, svc.ask("changed the furnace filter", today, firstDay))
        assertEquals(LookupOutcome.NotAQuestion, svc.ask("   ", today, firstDay))
        assertEquals(LookupOutcome.NotAQuestion, svc.ask("", today, firstDay))
        assertEquals(0, extractor.calls)
        assertEquals(0, repo.catalogLoads)
        assertEquals(0, repo.entryLoads)
    }

    @Test
    fun `each failure kind maps to its outcome`() = runSuspend {
        val expected = mapOf(
            InterpreterFailureKind.UNAVAILABLE to LookupOutcome.Unavailable,
            InterpreterFailureKind.RETRYABLE to LookupOutcome.Busy,
            InterpreterFailureKind.MALFORMED to LookupOutcome.Failed,
            InterpreterFailureKind.OTHER to LookupOutcome.Failed,
        )
        for ((kind, outcome) in expected) {
            val repo = FakeRepo(catalog, entries)
            val extractor = FakeExtractor(QuestionExtractionResult.Failure(kind))
            assertEquals(outcome, service(extractor, repo).ask(question, today, firstDay), "kind $kind")
            assertEquals(1, extractor.calls)
            assertEquals(0, repo.entryLoads)
        }
    }

    @Test
    fun `no entries logged at all is not enough history`() = runSuspend {
        val svc = service(words("furnace", "change filter"), FakeRepo(catalog, emptyList()))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask(question, today, firstDay))
    }

    @Test
    fun `words that resolve to new tags are not enough history`() = runSuspend {
        val svc = service(words("garage door", "paint"))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask("when did I last paint the garage door", today, firstDay))
    }

    @Test
    fun `a named subject that matches nothing is not enough history even if the action matches`() = runSuspend {
        val svc = service(words("gutters", "mow"))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask("when did I last mow the gutters", today, firstDay))
    }

    @Test
    fun `both words null is not enough history`() = runSuspend {
        val extractor = words(null, null)
        assertEquals(LookupOutcome.NotEnoughHistory, service(extractor).ask(question, today, firstDay))
        assertEquals(1, extractor.calls)
    }

    @Test
    fun `resolved tags with no matching entry is not enough history`() = runSuspend {
        val svc = service(words("lawn", "change filter"), FakeRepo(catalog, listOf(entry("x", furnace, mow, 1))))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask("when did I last change the lawn filter", today, firstDay))
    }

    @Test
    fun `exact subject and action answers with both tier newest first and interval`() = runSuspend {
        val extractor = words("furnace", "change filter")
        val outcome = service(extractor).ask(question, today, firstDay)
        val answer = assertIs<LookupOutcome.Answer>(outcome)
        assertEquals(LookupTarget(LookupTag("s-furnace", true), LookupTag("a-change", true)), answer.target)
        assertEquals(listOf("o2", "o1", "o4"), answer.result.matches.map { it.entry.occurrenceId })
        assertEquals(LookupTier.BOTH, answer.result.top?.tier)
        assertEquals("o2", answer.result.top?.entry?.occurrenceId)
        assertEquals("o1", answer.result.previous?.entry?.occurrenceId)
        assertEquals(Duration.ofDays(90), answer.result.intervalFromPrevious)
        assertTrue(answer.result.top!!.exact)
        assertEquals(1, extractor.calls)
    }

    @Test
    fun `subject only question answers with subject tier`() = runSuspend {
        val answer = assertIs<LookupOutcome.Answer>(service(words("furnace", null)).ask("when did I last service the furnace", today, firstDay))
        assertNull(answer.target.action)
        assertEquals(LookupTier.SUBJECT_ONLY, answer.result.top?.tier)
        assertEquals(listOf("o4", "o2", "o1"), answer.result.matches.map { it.entry.occurrenceId })
    }

    @Test
    fun `action only question answers with action tier`() = runSuspend {
        val answer = assertIs<LookupOutcome.Answer>(service(words(null, "mow")).ask("when did I last mow", today, firstDay))
        assertNull(answer.target.subject)
        assertEquals(LookupTier.ACTION_ONLY, answer.result.top?.tier)
        assertEquals(listOf("o4", "o3"), answer.result.matches.map { it.entry.occurrenceId })
    }

    @Test
    fun `near subject still answers with exact false and does not ask`() = runSuspend {
        val extractor = words("furnaces room", "change filter")
        val answer = assertIs<LookupOutcome.Answer>(service(extractor).ask(question, today, firstDay))
        assertEquals(LookupTag("s-furnace", exact = false), answer.target.subject)
        assertEquals(LookupTier.BOTH, answer.result.top?.tier)
        assertFalse(answer.result.top!!.exact)
        assertEquals(1, extractor.calls)
    }

    // ---- Kind and date words (DH4.3) ------------------------------------------------------

    private fun candidate(
        subject: String? = "furnace",
        action: String? = "change filter",
        dateWindow: String? = null,
        kind: QuestionKind = QuestionKind.UNKNOWN,
    ) = FakeExtractor(QuestionExtractionResult.Success(QuestionCandidate(subject, action, dateWindow, kind)))

    private val allTime = DateRangeSelection.Preset(DateRangePreset.ALL_TIME)

    private suspend fun scopeOf(extractor: FakeExtractor, text: String): QuestionScope =
        assertIs<LookupOutcome.Answer>(service(extractor).ask(text, today, firstDay)).scope

    @Test
    fun `the answer carries the scope and keeps the ranking`() = runSuspend {
        val answer = assertIs<LookupOutcome.Answer>(service(words("furnace", "change filter")).ask(question, today, firstDay))
        assertEquals(QuestionScope(QuestionKind.LAST_TIME, allTime, DateWords.NONE), answer.scope)
        assertEquals(listOf("o2", "o1", "o4"), answer.result.matches.map { it.entry.occurrenceId })
    }

    @Test
    fun `the kind from the question text beats the model kind`() = runSuspend {
        val scope = scopeOf(candidate(kind = QuestionKind.LIST), "how many times did I change the furnace filter")
        assertEquals(QuestionKind.COUNT, scope.kind)
    }

    @Test
    fun `the model kind is used when the text rules say nothing`() = runSuspend {
        val scope = scopeOf(candidate(kind = QuestionKind.HOW_OFTEN), "furnace filter changes?")
        assertEquals(QuestionKind.HOW_OFTEN, scope.kind)
    }

    @Test
    fun `the kind is unknown when neither the text nor the model says`() = runSuspend {
        val scope = scopeOf(candidate(kind = QuestionKind.UNKNOWN), "furnace filter changes?")
        assertEquals(QuestionKind.UNKNOWN, scope.kind)
    }

    @Test
    fun `date words in the question are resolved by the program`() = runSuspend {
        val scope = scopeOf(
            candidate(subject = "lawn", action = "mow", dateWindow = "in August"),
            "How many times did I mow the lawn in August?",
        )
        val aug = YearMonth.of(2026, 8)
        assertEquals(
            QuestionScope(
                QuestionKind.COUNT,
                DateRangeSelection.Custom(aug.atDay(1), aug.atEndOfMonth(), RangeLabel.Month(aug)),
                DateWords.USED,
            ),
            scope,
        )
    }

    @Test
    fun `date words matched with curly apostrophes and different case`() = runSuspend {
        val scope = scopeOf(
            candidate(subject = "lawn", action = "mow", dateWindow = "Last Month"),
            "how many times did I mow the lawn last month’s weekends?",
        )
        assertEquals(DateWords.USED, scope.dateWords)
    }

    @Test
    fun `date words that are not in the question are ignored`() = runSuspend {
        val scope = scopeOf(
            candidate(subject = "lawn", action = "mow", dateWindow = "last month"),
            "How many times did I mow the lawn?",
        )
        assertEquals(allTime, scope.range)
        assertEquals(DateWords.NONE, scope.dateWords)
    }

    @Test
    fun `date words no rule understands fall back to all time`() = runSuspend {
        val scope = scopeOf(
            candidate(subject = "lawn", action = "mow", dateWindow = "back when it rained"),
            "How many times did I mow the lawn back when it rained?",
        )
        assertEquals(allTime, scope.range)
        assertEquals(DateWords.NOT_UNDERSTOOD, scope.dateWords)
    }

    @Test
    fun `future date words are not enough history and touch no tags`() = runSuspend {
        val repo = FakeRepo(catalog, entries)
        val outcome = service(candidate(subject = "lawn", action = "mow", dateWindow = "next week"), repo)
            .ask("Will I mow the lawn next week?", today, firstDay)
        assertEquals(LookupOutcome.NotEnoughHistory, outcome)
        assertEquals(0, repo.catalogLoads)
        assertEquals(0, repo.entryLoads)
    }

    @Test
    fun `no date words means all time and none`() = runSuspend {
        val scope = scopeOf(candidate(dateWindow = null), question)
        assertEquals(QuestionScope(QuestionKind.LAST_TIME, allTime, DateWords.NONE), scope)
        val blank = scopeOf(candidate(dateWindow = "  "), question)
        assertEquals(DateWords.NONE, blank.dateWords)
    }

    @Test
    fun `a date-only question with no subject or action browses that range`() = runSuspend {
        val outcome = service(candidate(subject = null, action = null, dateWindow = "last week"))
            .ask("What did I do last week?", today, firstDay)
        val browse = assertIs<LookupOutcome.Browse>(outcome)
        assertEquals(QuestionKind.LIST, browse.scope.kind)
        assertEquals(DateWords.USED, browse.scope.dateWords)
        // Today is Monday 2026-10-05; last week is Mon Sep 28 - Sun Oct 4.
        assertEquals(DateRangeSelection.Custom(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)), browse.scope.range)
    }

    @Test
    fun `a list question with no date words browses all time`() = runSuspend {
        val outcome = service(candidate(subject = null, action = null)).ask("What have I logged?", today, firstDay)
        assertEquals(LookupOutcome.Browse(QuestionScope(QuestionKind.LIST, allTime, DateWords.NONE)), outcome)
    }

    @Test
    fun `browse needs at least one logged entry`() = runSuspend {
        val outcome = service(candidate(subject = null, action = null, dateWindow = "last week"), FakeRepo(catalog, emptyList()))
            .ask("What did I do last week?", today, firstDay)
        assertEquals(LookupOutcome.NotEnoughHistory, outcome)
    }

    @Test
    fun `no subject, no action, no date words and not a list question is still not enough history`() = runSuspend {
        val outcome = service(candidate(subject = null, action = null, kind = QuestionKind.COUNT))
            .ask("How many times?", today, firstDay)
        assertEquals(LookupOutcome.NotEnoughHistory, outcome)
    }

    @Test
    fun `a named subject that matches nothing never browses`() = runSuspend {
        val outcome = service(candidate(subject = "gutters", action = null, dateWindow = "last week"))
            .ask("What did I do to the gutters last week?", today, firstDay)
        assertEquals(LookupOutcome.NotEnoughHistory, outcome)
    }

    // ---- Question-word clean-up (DH4.5) ---------------------------------------------------

    private val hotTub = KnownTag("s-hot-tub", TagKind.SUBJECT, "hot tub", emptyList())
    private val car = KnownTag("s-car", TagKind.SUBJECT, "car", listOf("truck"))
    private val grill = KnownTag("s-grill", TagKind.SUBJECT, "grill", emptyList())
    private val dogs = KnownTag("s-dogs", TagKind.SUBJECT, "dogs", emptyList())
    private val addChlorine = KnownTag("a-add-chlorine", TagKind.ACTION, "add chlorine", emptyList())
    private val changeOil = KnownTag("a-change-oil", TagKind.ACTION, "change oil", emptyList())
    private val clean = KnownTag("a-clean", TagKind.ACTION, "clean", emptyList())
    private val walk = KnownTag("a-walk", TagKind.ACTION, "walk", emptyList())

    private val household = TagCatalog(
        subjects = listOf(furnace, hotTub, car, grill, dogs),
        actions = listOf(change, addChlorine, changeOil, clean, walk),
        pairs = emptyList(),
    )

    private val householdEntries = listOf(
        entry("h1", furnace, change, 10),
        entry("h2", hotTub, addChlorine, 270),
        entry("h3", car, changeOil, 200),
        entry("h4", grill, clean, 250),
        entry("h5", dogs, walk, 272),
    )

    private suspend fun askHousehold(
        text: String,
        subject: String?,
        action: String?,
        dateWindow: String? = null,
    ): LookupOutcome = LookupService(FakeRepo(household, householdEntries), candidate(subject, action, dateWindow))
        .ask(text, today, firstDay)

    @Test
    fun `last-time date words are no date words`() = runSuspend {
        val furnaceAnswer = assertIs<LookupOutcome.Answer>(askHousehold(question, "furnace", "change filter", "last"))
        assertEquals(QuestionScope(QuestionKind.LAST_TIME, allTime, DateWords.NONE), furnaceAnswer.scope)
        val dogsAnswer = assertIs<LookupOutcome.Answer>(
            askHousehold("When was the last time I walked the dogs?", "dogs", "walked", "last time"),
        )
        assertEquals(DateWords.NONE, dogsAnswer.scope.dateWords)
        assertEquals(LookupTarget(LookupTag("s-dogs", true), LookupTag("a-walk", true)), dogsAnswer.target)
    }

    @Test
    fun `date-only questions with placeholder words browse`() = runSuspend {
        val lastWeek = assertIs<LookupOutcome.Browse>(askHousehold("What did I do last week?", "I", "do", "last week"))
        assertEquals(DateWords.USED, lastWeek.scope.dateWords)
        assertEquals(DateRangeSelection.Custom(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)), lastWeek.scope.range)
        val september = assertIs<LookupOutcome.Browse>(askHousehold("Show me what I did in September", "I", "did", "in September"))
        assertEquals(DateWords.USED, september.scope.dateWords)
        val month = assertIs<LookupOutcome.Browse>(askHousehold("What did I get done this month?", "I", "get done", "this month"))
        assertEquals(DateWords.USED, month.scope.dateWords)
    }

    @Test
    fun `a subject made of the date words browses with the date note`() = runSuspend {
        val browse = assertIs<LookupOutcome.Browse>(
            askHousehold("What did I do around the holidays?", "holidays", "do", "around the holidays"),
        )
        assertEquals(QuestionScope(QuestionKind.LIST, allTime, DateWords.NOT_UNDERSTOOD), browse.scope)
    }

    @Test
    fun `a placeholder subject leaves the action`() = runSuspend {
        val answer = assertIs<LookupOutcome.Answer>(askHousehold("When did I last clean something?", "something", "clean"))
        assertEquals(LookupTarget(null, LookupTag("a-clean", true)), answer.target)
    }

    @Test
    fun `a subject that is the action's object re-joins the action`() = runSuspend {
        val chlorine = assertIs<LookupOutcome.Answer>(askHousehold("When did I last add chlorine?", "chlorine", "add", "last"))
        assertEquals(LookupTarget(null, LookupTag("a-add-chlorine", true)), chlorine.target)
        assertEquals(DateWords.NONE, chlorine.scope.dateWords)
        val oil = assertIs<LookupOutcome.Answer>(askHousehold("How often do I change the oil?", "oil", "change"))
        assertEquals(LookupTarget(null, LookupTag("a-change-oil", true)), oil.target)
        val truck = assertIs<LookupOutcome.Answer>(askHousehold("Did I ever change the oil in the truck?", "oil", "change"))
        assertEquals(LookupTarget(null, LookupTag("a-change-oil", true)), truck.target)
        val lastWeek = askHousehold("How much chlorine did I add to the hot tub last week?", "chlorine", "add", "last week")
        // h2 is 2026-09-28, inside last week: the answer stands, its range is last week.
        val answer = assertIs<LookupOutcome.Answer>(lastWeek)
        assertEquals(LookupTarget(null, LookupTag("a-add-chlorine", true)), answer.target)
        assertEquals(DateWords.USED, answer.scope.dateWords)
    }

    @Test
    fun `a subject holding the action's object re-splits`() = runSuspend {
        val changed = assertIs<LookupOutcome.Answer>(askHousehold(question, "furnace filter", "change", "last"))
        assertEquals(LookupTarget(LookupTag("s-furnace", true), LookupTag("a-change", true)), changed.target)
        val replaced = assertIs<LookupOutcome.Answer>(
            askHousehold("Have I ever replaced the furnace filter?", "furnace filter", "replace"),
        )
        assertEquals(LookupTarget(LookupTag("s-furnace", true), LookupTag("a-change", false)), replaced.target)
    }

    @Test
    fun `an unlogged subject never re-joins a verb-only near action`() = runSuspend {
        assertEquals(
            LookupOutcome.NotEnoughHistory,
            askHousehold("When was the last time I cleaned the gutters?", "gutters", "cleaned", "last time"),
        )
    }
}
