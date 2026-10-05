package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolver
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalRange
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalRangeResolver
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * Question answering (ADR-051). The model is asked ONLY to pull the user's own words out of a
 * question -- what it is about (subject), what was done (action), the words that say when (date
 * window) and what kind of question it is. It is never shown the user's tags, logged entries or
 * the date (ADR-011, ADR-038). Matching, date resolution and ranking are program logic.
 *
 * A question now resolves to filter values (ADR-051): tags, a date range and a question kind,
 * carried by [QuestionScope]. The kind comes from fixed text rules first ([QuestionKindDetector]);
 * the model's date words count only when they occur in the question and are turned into dates
 * only by [TemporalRangeResolver]. The model still never produces a date, a count or an answer.
 *
 * Before any of that, the model's words are cleaned up by fixed rules ([QuestionWords], DH4.5):
 * last-time phrasing ("last", "ever") is not a date; placeholder subjects ("I", "something"),
 * subjects made only of the date words and generic actions ("do", "get done") are dropped unless
 * they are exactly a tag; an untagged subject re-joins its action ("add" + "chlorine") and a
 * two-part subject re-splits ("furnace filter" + "change" -> "furnace" + "change filter") only
 * when the result is an existing tag. The rules only drop or recombine the user's own words.
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
     * @property scope The question's kind and date range.
     */
    data class Answer(val target: LookupTarget, val result: LookupResult, val scope: QuestionScope) : LookupOutcome {
        init {
            require(!result.isEmpty) { "An answer needs at least one match" }
        }
    }

    /**
     * A question about a stretch of time rather than about something in particular ("what did I
     * do last week?"): it names no subject and no action, and either gives date words that were
     * understood or is a [QuestionKind.LIST] question. Only returned when at least one entry is logged.
     *
     * @property scope The question's kind and date range.
     */
    data class Browse(val scope: QuestionScope) : LookupOutcome
}

/**
 * Turns a typed or spoken question into an answer from the logged history. Read only: it never
 * writes anything, never saves the question and never logs. Apart from the repository reads it
 * is pure: today and the first day of the week are passed in, never read from a clock.
 *
 * Order: blank or not a question -> [LookupOutcome.NotAQuestion] (no extractor or repository
 * call); extract the words (failures map to Unavailable / Busy / Failed); drop last-time-only
 * date words ([QuestionWords] rule 1); work out the [QuestionScope] -- the kind
 * ([QuestionKindDetector] first, then the model's kind, else UNKNOWN) and the range (the
 * remaining date words only if they occur in the question, resolved by [TemporalRangeResolver];
 * no words -> all time / NONE, not understood -> all time / NOT_UNDERSTOOD, a window after today
 * -> NotEnoughHistory); load the catalog and clean the subject and action words
 * ([QuestionWords.clean] rules 2-6: placeholder subject, subject made of date words, generic
 * action, re-join, re-split -- these never affect the scope, so running them after it is the same
 * as running them before); resolve the cleaned subject and action words with [TagResolver]
 * against the catalog (a named subject that matches no tag -> NotEnoughHistory, never
 * another subject's entries); build the target with [LookupTarget.fromResolutions] (none ->
 * [LookupOutcome.Browse] when no subject and no action remain after the clean-up and the date words were used or
 * the kind is LIST and at least one entry is logged, otherwise NotEnoughHistory); load entries
 * (none -> NotEnoughHistory); rank with [HistoryLookup.rank] (nothing -> NotEnoughHistory);
 * otherwise [LookupOutcome.Answer].
 */
class LookupService(
    private val repository: TagRepository,
    private val extractor: QuestionExtractor,
    private val rangeResolver: TemporalRangeResolver = TemporalRangeResolver(),
) {

    /**
     * Answers [questionText] as of [today] (the user's local date), with weeks starting on
     * [firstDayOfWeek]. See the class description for the order of steps.
     */
    suspend fun ask(questionText: String, today: LocalDate, firstDayOfWeek: DayOfWeek): LookupOutcome {
        if (questionText.isBlank() || !QuestionDetector.isQuestion(questionText)) return LookupOutcome.NotAQuestion

        val extracted = when (val extraction = extractor.extract(questionText)) {
            is QuestionExtractionResult.Success -> extraction.candidate
            is QuestionExtractionResult.Failure -> return when (extraction.kind) {
                InterpreterFailureKind.UNAVAILABLE -> LookupOutcome.Unavailable
                InterpreterFailureKind.RETRYABLE -> LookupOutcome.Busy
                InterpreterFailureKind.MALFORMED, InterpreterFailureKind.OTHER -> LookupOutcome.Failed
            }
        }

        // Rule 1 of the clean-up needs no catalog, so a window after today still touches no tags.
        val dated = QuestionWords.withoutLastTimeDateWords(extracted)
        val scope = scopeOf(questionText, dated, today, firstDayOfWeek)
            ?: return LookupOutcome.NotEnoughHistory

        val catalog = repository.loadTagCatalog()
        // Rules 2-6 only change the subject and action, which the scope never reads.
        val candidate = QuestionWords.clean(questionText, dated, catalog)
        val subject = TagResolver.resolve(candidate.subject, TagKind.SUBJECT, catalog)
        val action = TagResolver.resolve(candidate.action, TagKind.ACTION, catalog)
        // A subject that was named but matches nothing logged means there is no history for what the
        // user asked about; answering with other subjects' entries of the same action would mislead.
        if (subject is TagResolution.New) return LookupOutcome.NotEnoughHistory
        val target = LookupTarget.fromResolutions(subject, action)
            ?: return browseOrNothing(candidate, scope)

        val entries = repository.loadLookupEntries()
        if (entries.isEmpty()) return LookupOutcome.NotEnoughHistory

        val result = HistoryLookup.rank(entries, target)
        if (result.isEmpty) return LookupOutcome.NotEnoughHistory
        return LookupOutcome.Answer(target, result, scope)
    }

    /**
     * No target: a question that names nothing in particular but gives used date words or asks
     * for a list browses that range when anything is logged; anything else is NotEnoughHistory.
     */
    private suspend fun browseOrNothing(candidate: QuestionCandidate, scope: QuestionScope): LookupOutcome {
        val namesNothing = candidate.subject == null && candidate.action == null
        val browses = scope.dateWords == DateWords.USED || scope.kind == QuestionKind.LIST
        if (!namesNothing || !browses) return LookupOutcome.NotEnoughHistory
        if (repository.loadLookupEntries().isEmpty()) return LookupOutcome.NotEnoughHistory
        return LookupOutcome.Browse(scope)
    }

    /** The question's kind and range; null when its date words name a window after today. */
    private fun scopeOf(
        questionText: String,
        candidate: QuestionCandidate,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): QuestionScope? {
        val kind = QuestionKindDetector.detect(questionText)
            ?: candidate.kind.takeIf { it != QuestionKind.UNKNOWN }
            ?: QuestionKind.UNKNOWN
        // Untrusted: the model's date words count only when the user actually said them.
        val words = candidate.dateWindow?.takeIf { window ->
            val said = normalizeForMatch(window)
            said.isNotEmpty() && normalizeForMatch(questionText).contains(said)
        }
        val allTime = DateRangeSelection.Preset(DateRangePreset.ALL_TIME)
        return when (val range = rangeResolver.resolve(words, today, firstDayOfWeek)) {
            is TemporalRange.Resolved -> QuestionScope(kind, range.selection, DateWords.USED)
            TemporalRange.NoWindow -> QuestionScope(kind, allTime, DateWords.NONE)
            TemporalRange.Unrecognised -> QuestionScope(kind, allTime, DateWords.NOT_UNDERSTOOD)
            TemporalRange.Future -> null
        }
    }

    /** Lowercase, whitespace collapsed, edge punctuation trimmed, curly apostrophes made plain. */
    private fun normalizeForMatch(text: String): String = NameNormalizer.normalize(text.replace('’', '\''))
}
