package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolver

/*
 * Question answering (ADR-051). The model is asked ONLY to pull the user's own words out of a
 * question -- what it is about (subject), what was done (action), the words that say when (date
 * window) and what kind of question it is. It is never shown the user's tags, logged entries or
 * the date (ADR-011, ADR-038). Matching, date resolution and ranking are program logic.
 */

/**
 * The words a [QuestionExtractor] pulled out of one question.
 *
 * This is UNTRUSTED model output: nothing here is validated by construction. Resolving the words
 * against existing tags is deterministic domain logic, never the extractor's job.
 *
 * @property subject What the question is about, in the user's own words (e.g. "furnace"), if named.
 * @property action What was done, as a short verb phrase in the user's own words
 *   (e.g. "change filter"), if stated.
 * @property dateWindow The words of the question that say when, exactly as the user said them
 *   (e.g. "in August", "last month"), if any. Never a computed date: turning these words into a
 *   time range is deterministic domain logic (`TemporalRangeResolver`).
 * @property kind What the question asks for; [QuestionKind.UNKNOWN] when not said or unclear.
 */
data class QuestionCandidate(
    val subject: String?,
    val action: String?,
    val dateWindow: String? = null,
    val kind: QuestionKind = QuestionKind.UNKNOWN,
)

/** Outcome of one [QuestionExtractor.extract] call. */
sealed interface QuestionExtractionResult {
    /**
     * The model produced a candidate with the promised shape (which may still be meaningless).
     *
     * @property candidate The untrusted extracted words.
     */
    data class Success(val candidate: QuestionCandidate) : QuestionExtractionResult

    /**
     * No usable candidate. Deliberately carries no user text, no model output and no exception
     * message: only the failure kind.
     *
     * @property kind Coarse reason the call failed.
     */
    data class Failure(val kind: InterpreterFailureKind) : QuestionExtractionResult
}

/**
 * Pulls the subject, action and date words, and the question kind, out of a question's text. Implementations run on the phone
 * only. Callers must treat every result as untrusted.
 */
interface QuestionExtractor {
    /** Versions identifying which interpreter, prompt and output schema produced a result. */
    val provenance: InterpreterProvenance

    /**
     * Extracts from one question. Implementations report failures as
     * [QuestionExtractionResult.Failure] rather than throwing; cancellation of the calling
     * coroutine propagates.
     */
    suspend fun extract(questionText: String): QuestionExtractionResult
}

/** What [LookupService.ask] decided. */
sealed interface LookupOutcome {
    /** The text is blank or not a question ([QuestionDetector]); the extractor was never called. */
    data object NotAQuestion : LookupOutcome

    /** The extractor could not run on this device ([InterpreterFailureKind.UNAVAILABLE]). */
    data object Unavailable : LookupOutcome

    /** A transient failure ([InterpreterFailureKind.RETRYABLE]); the caller may retry later. */
    data object Busy : LookupOutcome

    /** The extractor failed for another reason ([InterpreterFailureKind.MALFORMED] or OTHER). */
    data object Failed : LookupOutcome

    /**
     * Nothing to answer from: no entries are logged at all, or the words resolved to no existing
     * tag, or no logged entry matched.
     */
    data object NotEnoughHistory : LookupOutcome

    /**
     * An answer. A near (closest-candidate) tag still answers without asking; see
     * [LookupMatch.exact], which is false on those matches so the UI can say it is the closest match.
     *
     * @property target What the question was about, resolved to tags.
     * @property result The ranked matches; never empty.
     */
    data class Answer(val target: LookupTarget, val result: LookupResult) : LookupOutcome {
        init {
            require(!result.isEmpty) { "An answer needs at least one match" }
        }
    }
}

/**
 * Turns a typed or spoken question into an answer from the logged history. Read only: it never
 * writes anything, never saves the question and never logs.
 *
 * Order: blank or not a question -> [LookupOutcome.NotAQuestion] (no extractor or repository
 * call); extract the words (failures map to Unavailable / Busy / Failed); resolve subject and
 * action words with [TagResolver] against the catalog (a named subject that matches no tag ->
 * NotEnoughHistory, never another subject's entries); build the target with
 * [LookupTarget.fromResolutions] (none -> NotEnoughHistory); load entries (none -> NotEnoughHistory);
 * rank with [HistoryLookup.rank] (nothing -> NotEnoughHistory); otherwise [LookupOutcome.Answer].
 */
class LookupService(
    private val repository: TagRepository,
    private val extractor: QuestionExtractor,
) {

    /** Answers [questionText]. See the class description for the order of steps. */
    suspend fun ask(questionText: String): LookupOutcome {
        if (questionText.isBlank() || !QuestionDetector.isQuestion(questionText)) return LookupOutcome.NotAQuestion

        val candidate = when (val extraction = extractor.extract(questionText)) {
            is QuestionExtractionResult.Success -> extraction.candidate
            is QuestionExtractionResult.Failure -> return when (extraction.kind) {
                InterpreterFailureKind.UNAVAILABLE -> LookupOutcome.Unavailable
                InterpreterFailureKind.RETRYABLE -> LookupOutcome.Busy
                InterpreterFailureKind.MALFORMED, InterpreterFailureKind.OTHER -> LookupOutcome.Failed
            }
        }

        val catalog = repository.loadTagCatalog()
        val subject = TagResolver.resolve(candidate.subject, TagKind.SUBJECT, catalog)
        val action = TagResolver.resolve(candidate.action, TagKind.ACTION, catalog)
        // A subject that was named but matches nothing logged means there is no history for what the
        // user asked about; answering with other subjects' entries of the same action would mislead.
        if (subject is TagResolution.New) return LookupOutcome.NotEnoughHistory
        val target = LookupTarget.fromResolutions(subject, action) ?: return LookupOutcome.NotEnoughHistory

        val entries = repository.loadLookupEntries()
        if (entries.isEmpty()) return LookupOutcome.NotEnoughHistory

        val result = HistoryLookup.rank(entries, target)
        if (result.isEmpty) return LookupOutcome.NotEnoughHistory
        return LookupOutcome.Answer(target, result)
    }
}
