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
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupServiceTest {

    private val base = Instant.parse("2026-01-01T00:00:00Z")

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
        assertEquals(LookupOutcome.NotAQuestion, svc.ask("changed the furnace filter"))
        assertEquals(LookupOutcome.NotAQuestion, svc.ask("   "))
        assertEquals(LookupOutcome.NotAQuestion, svc.ask(""))
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
            assertEquals(outcome, service(extractor, repo).ask(question), "kind $kind")
            assertEquals(1, extractor.calls)
            assertEquals(0, repo.entryLoads)
        }
    }

    @Test
    fun `no entries logged at all is not enough history`() = runSuspend {
        val svc = service(words("furnace", "change filter"), FakeRepo(catalog, emptyList()))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask(question))
    }

    @Test
    fun `words that resolve to new tags are not enough history`() = runSuspend {
        val svc = service(words("garage door", "paint"))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask("when did I last paint the garage door"))
    }

    @Test
    fun `both words null is not enough history`() = runSuspend {
        val extractor = words(null, null)
        assertEquals(LookupOutcome.NotEnoughHistory, service(extractor).ask(question))
        assertEquals(1, extractor.calls)
    }

    @Test
    fun `resolved tags with no matching entry is not enough history`() = runSuspend {
        val svc = service(words("lawn", "change filter"), FakeRepo(catalog, listOf(entry("x", furnace, mow, 1))))
        assertEquals(LookupOutcome.NotEnoughHistory, svc.ask("when did I last change the lawn filter"))
    }

    @Test
    fun `exact subject and action answers with both tier newest first and interval`() = runSuspend {
        val extractor = words("furnace", "change filter")
        val outcome = service(extractor).ask(question)
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
        val answer = assertIs<LookupOutcome.Answer>(service(words("furnace", null)).ask("when did I last service the furnace"))
        assertNull(answer.target.action)
        assertEquals(LookupTier.SUBJECT_ONLY, answer.result.top?.tier)
        assertEquals(listOf("o4", "o2", "o1"), answer.result.matches.map { it.entry.occurrenceId })
    }

    @Test
    fun `action only question answers with action tier`() = runSuspend {
        val answer = assertIs<LookupOutcome.Answer>(service(words(null, "mow")).ask("when did I last mow"))
        assertNull(answer.target.subject)
        assertEquals(LookupTier.ACTION_ONLY, answer.result.top?.tier)
        assertEquals(listOf("o4", "o3"), answer.result.matches.map { it.entry.occurrenceId })
    }

    @Test
    fun `near subject still answers with exact false and does not ask`() = runSuspend {
        val extractor = words("furnaces room", "change filter")
        val answer = assertIs<LookupOutcome.Answer>(service(extractor).ask(question))
        assertEquals(LookupTag("s-furnace", exact = false), answer.target.subject)
        assertEquals(LookupTier.BOTH, answer.result.top?.tier)
        assertFalse(answer.result.top!!.exact)
        assertEquals(1, extractor.calls)
    }
}
