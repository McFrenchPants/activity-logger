package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind.COUNT
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind.HOW_OFTEN
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind.LAST_TIME
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind.LIST
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the question corpus expectations and the deterministic logic agree: an IDEAL reader's
 * words for every case (what a perfect model following the q2 prompt would return: the user's own
 * subject and action words, the date words copied verbatim, the kind) are replayed through the
 * real LookupService, and every case must score CORRECT.
 *
 * The ideal words live here, in the test, never in the corpus file. [KNOWN_GAPS] lists cases
 * where even ideal words cannot reach the expectation; it must be exactly the failing set (it is
 * empty: there are no such gaps).
 */
class QuestionCorpusOracleTest {

    private val corpus = QuestionCorpus.load()

    private fun ideal(subject: String?, action: String?, dateWindow: String? = null, kind: QuestionKind) =
        RecordedQuestion(subject, action, dateWindow, kind)

    private val idealWords: Map<String, RecordedQuestion> = mapOf(
        "lt-furnace-filter" to ideal("furnace", "change filter", kind = LAST_TIME),
        "lt-walked-dogs" to ideal("dogs", "walk", kind = LAST_TIME),
        "lt-yard-alias" to ideal("yard", "mow", kind = LAST_TIME),
        "lt-subject-only-grill" to ideal("grill", null, kind = LAST_TIME),
        "lt-action-only-chlorine" to ideal(null, "add chlorine", kind = LAST_TIME),
        "c-mow-lawn-august" to ideal("lawn", "mow", "in August", COUNT),
        "c-chlorine-this-year" to ideal("hot tub", "add chlorine", "this year", COUNT),
        "c-dogs-last-month" to ideal("dogs", "walk", "last month", COUNT),
        "c-oil-in-2025" to ideal(null, "change oil", "in 2025", COUNT),
        "c-mowed-since-june" to ideal("lawn", "mow", "since June", COUNT),
        "c-dogs-past-30-days" to ideal("dogs", "walk", "in the past 30 days", COUNT),
        "c-dogs-yesterday" to ideal("dogs", "walk", "yesterday", COUNT),
        "c-car-subject-only-this-year" to ideal("car", null, "this year", COUNT),
        "c-how-much-chlorine-last-week" to ideal("hot tub", "add chlorine", "last week", COUNT),
        "ho-change-oil" to ideal(null, "change oil", kind = HOW_OFTEN),
        "ho-walk-dogs" to ideal("dogs", "walk", kind = HOW_OFTEN),
        "ho-sweep-chimney-two-entries" to ideal("chimney", "sweep", kind = HOW_OFTEN),
        "ho-mow-lawn-july" to ideal("lawn", "mow", "in July", HOW_OFTEN),
        "ho-chlorine-regularly" to ideal("hot tub", "add chlorine", kind = HOW_OFTEN),
        "li-show-me-car" to ideal("car", null, kind = LIST),
        "li-what-to-furnace" to ideal("furnace", null, kind = LIST),
        "li-list-fertilized-lawn" to ideal("lawn", "fertilize", kind = LIST),
        "li-grill-this-year" to ideal("grill", null, "this year", LIST),
        "do-last-week" to ideal(null, null, "last week", LIST),
        "do-yesterday" to ideal(null, null, "yesterday", LIST),
        "do-show-september" to ideal(null, null, "in September", LIST),
        "do-this-month" to ideal(null, null, "this month", LIST),
        "do-around-the-holidays" to ideal(null, null, "around the holidays", LIST),
        "nl-cleaned-gutters" to ideal("gutters", "clean", kind = LAST_TIME),
        "nl-wash-boat" to ideal("boat", "wash", kind = LAST_TIME),
        "nl-paint" to ideal(null, "paint", kind = LAST_TIME),
        "nl-ever-cleaned-pool" to ideal("pool", "clean", kind = LAST_TIME),
        "nl-empty-mow-lawn" to ideal("lawn", "mow", kind = LAST_TIME),
        "nl-empty-this-week" to ideal(null, null, "this week", LIST),
        "nl-empty-how-often-chlorine" to ideal("hot tub", "add chlorine", kind = HOW_OFTEN),
        "tr-future-next-week" to ideal("dogs", "walk", "next week", COUNT),
        "tr-windows-around-the-holidays" to ideal("windows", "clean", "around the holidays", COUNT),
        "tr-did-ever-clean-grill" to ideal("grill", "clean", kind = LAST_TIME),
        "tr-have-ever-cleaned-windows" to ideal("windows", "clean", kind = LAST_TIME),
        "tr-replaced-furnace-filter" to ideal("furnace", "replace filter", kind = LAST_TIME),
        "tr-truck-alias-oil" to ideal("truck", "change oil", kind = LAST_TIME),
        "tr-grass-synonym" to ideal("grass", "mow", kind = LAST_TIME),
        "tr-clean-shared-action" to ideal(null, "clean", kind = LAST_TIME),
        "tr-no-question-mark-chlorine" to ideal("hot tub", "add chlorine", kind = LAST_TIME),
        "tr-no-question-mark-yard-count" to ideal("yard", "mow", "this year", COUNT),
        "tr-did-mow-yesterday" to ideal("lawn", "mow", "yesterday", LAST_TIME),
    )

    @Test
    fun `there is an ideal reading for exactly the corpus cases`() {
        assertEquals(corpus.cases.map { it.id }.sorted(), idealWords.keys.sorted())
    }

    @Test
    fun `ideal words date windows are copied from the question`() {
        idealWords.forEach { (id, words) ->
            val window = words.dateWindow ?: return@forEach
            val question = checkNotNull(corpus.case(id)).question
            kotlin.test.assertTrue(question.lowercase().contains(window.lowercase()), "case $id: ideal date words not in the question")
        }
    }

    @Test
    fun `every case is CORRECT through the real lookup with ideal words`() {
        val result = QuestionReplay(corpus).replay(QuestionRecordings.withAnswers(corpus, idealWords))
        val failing = result.entries.filter { it.replayClass != QuestionReplayClass.CORRECT }
        assertEquals(
            KNOWN_GAPS,
            failing.map { it.caseId }.toSet(),
            "non-CORRECT with ideal words: " + failing.joinToString("; ") { "${it.caseId} ${it.replayClass} ${it.reasonCodes}" },
        )
    }

    private companion object {
        /** Cases the deterministic logic cannot get right even with ideal words. None. */
        val KNOWN_GAPS: Set<String> = emptySet()
    }
}
