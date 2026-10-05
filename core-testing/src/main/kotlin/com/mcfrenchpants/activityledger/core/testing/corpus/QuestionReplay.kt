package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.DateWords
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupOutcome
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionScope
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.resolve
import com.mcfrenchpants.activityledger.core.testing.runSuspend

/*
 * JVM replay of a QUESTION recording (QuestionRecording) through the REAL LookupService.
 *
 * For each case, the recorded candidate is returned by a replaying QuestionExtractor to a real
 * `LookupService(repository, extractor)` (default TemporalRangeResolver), whose repository is a
 * read-only view of the case's catalog fixture ([QuestionFixtureRepository]); the question is
 * asked with the case's own text, today and first day of the week. The scorer only READS the
 * outcome and compares it with the case's expectation; it never decides tags, kinds or dates.
 *
 * The recorded model words are used only to drive the lookup and are never stored in a
 * QuestionReplayEntry, so a report built from the result cannot leak them (AGENTS.md #11).
 */

/** The one class every replayed question falls into. */
enum class QuestionReplayClass {
    /** Outcome, target tags, kind, range and date-word status all as expected. */
    CORRECT,

    /** Nothing wrong was shown, but less than expected (nothing found, or the dates were not used). */
    SAFE_MISS,

    /** The user would be shown something wrong: an unwanted answer, wrong tags, kind or dates. */
    WRONG,

    /** The extractor produced no answer (any failure kind). */
    FAILED,
}

/** Why a case is not CORRECT. Codes only; no text. [wrong] codes make a case WRONG. */
enum class QuestionCheck(val wrong: Boolean) {
    /** Nothing-to-answer was expected, but an answer or a browse was shown. */
    UNEXPECTED_ANSWER(true),

    /** An answer was shown where a browse was expected, or the other way round. */
    WRONG_OUTCOME(true),

    /** The target's subject is not acceptable (set where it must be unset, or the other way round). */
    WRONG_SUBJECT(true),

    /** The target's action is not acceptable (set where it must be unset, or the other way round). */
    WRONG_ACTION(true),

    /** The target holds a tag the case lists as must-not-match. */
    MUST_NOT_MATCH(true),

    /** The date words were used and resolved to other dates than expected (no warning shown). */
    WRONG_RANGE(true),

    /** The kind is not an acceptable one. */
    WRONG_KIND(true),

    /** An answer or browse was expected but nothing was found. */
    NOTHING_FOUND(false),

    /** The range fell back to all time with the "dates not understood" note. */
    DATES_NOT_UNDERSTOOD(false),

    /** The date words were dropped: all time, shown as such on the range chip. */
    DATES_DROPPED(false),

    /**
     * The right dates, but the date words were counted as used where the case expects none or
     * not-understood (e.g. "ever" copied as date words: still all time).
     */
    DATE_WORDS_DIFFER(false),
}

/**
 * The score of one question corpus case. Holds ids, enum codes, dates and numbers only -- never
 * the recorded model words.
 *
 * @property checks For WRONG / SAFE_MISS, every check that applies (non-empty); else empty.
 * @property outcome What the lookup ended in, or null for a FAILED entry.
 * @property subjectId The answer target's subject tag id, or null (unset, or not an answer).
 * @property subjectExact Whether the subject was an exact tag match (null when unset).
 * @property actionId The same for the action.
 * @property actionExact The same for the action.
 * @property kind The resolved question kind (answer or browse only).
 * @property range The resolved range as dates (answer or browse only).
 * @property dateWords What became of the date words (answer or browse only).
 */
data class QuestionReplayEntry(
    val caseId: String,
    val group: QuestionCaseGroup,
    val replayClass: QuestionReplayClass,
    val checks: List<QuestionCheck>,
    val outcome: QuestionOutcome?,
    val subjectId: String?,
    val subjectExact: Boolean?,
    val actionId: String?,
    val actionExact: Boolean?,
    val kind: QuestionKind?,
    val range: QuestionRangeDates?,
    val dateWords: DateWords?,
    val failureKind: InterpreterFailureKind?,
    val latencyMs: Long?,
) {
    /** Short codes for reports: the checks, else the failure kind. */
    val reasonCodes: List<String>
        get() = when {
            checks.isNotEmpty() -> checks.map { it.name }
            failureKind != null -> listOf(failureKind.name)
            else -> emptyList()
        }
}

/** A recording's provenance header, copied from the [QuestionRecording] (no entries, so no model output). */
data class QuestionReplayProvenance(
    val formatVersion: Int,
    val source: RecordingSource,
    val modelLabel: String,
    val deviceModel: String?,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val questionCorpusSha256: String,
    val recordedAt: String,
    val notes: String?,
) {
    companion object {
        /** The header of [recording]. */
        fun of(recording: QuestionRecording): QuestionReplayProvenance = QuestionReplayProvenance(
            formatVersion = recording.formatVersion,
            source = recording.source,
            modelLabel = recording.modelLabel,
            deviceModel = recording.deviceModel,
            interpreterVersion = recording.interpreterVersion,
            promptVersion = recording.promptVersion,
            schemaVersion = recording.schemaVersion,
            questionCorpusSha256 = recording.questionCorpusSha256,
            recordedAt = recording.recordedAt,
            notes = recording.notes,
        )
    }
}

/** A replayed question recording: provenance plus one score per corpus case, in corpus order. */
data class QuestionReplayResult(
    val provenance: QuestionReplayProvenance,
    val entries: List<QuestionReplayEntry>,
) {
    /** Entries of [replayClass]. */
    fun of(replayClass: QuestionReplayClass): List<QuestionReplayEntry> = entries.filter { it.replayClass == replayClass }

    /** Count per class (every class present, zero included). */
    val classCounts: Map<QuestionReplayClass, Int>
        get() = QuestionReplayClass.entries.associateWith { c -> entries.count { it.replayClass == c } }

    /** Count per group and class (every group and class present, zero included). */
    val groupCounts: Map<QuestionCaseGroup, Map<QuestionReplayClass, Int>>
        get() = QuestionCaseGroup.entries.associateWith { g ->
            QuestionReplayClass.entries.associateWith { c -> entries.count { it.group == g && it.replayClass == c } }
        }

    /** Number of entries with each check (every check present, zero included). */
    val checkCounts: Map<QuestionCheck, Int>
        get() = QuestionCheck.entries.associateWith { q -> entries.count { q in it.checks } }
}

/**
 * Replays question recordings against [corpus].
 *
 * **Classification** of one answered case, with `outcome = LookupService.ask(...)`:
 * - Outcome: NOT_ENOUGH_HISTORY expected but ANSWER/BROWSE -> UNEXPECTED_ANSWER (wrong);
 *   ANSWER where BROWSE was expected or the other way round -> WRONG_OUTCOME (wrong);
 *   ANSWER/BROWSE expected but NOT_ENOUGH_HISTORY -> NOTHING_FOUND (safe miss).
 * - Target (any answer): a tag id in mustNotMatch -> MUST_NOT_MATCH; when ANSWER was expected, a
 *   subject (action) not in subjectIds (actionIds), or set where the list is empty, or unset
 *   where it is not -> WRONG_SUBJECT (WRONG_ACTION).
 * - Kind (when ANSWER or BROWSE was expected and given): outside kind + allowedKinds -> WRONG_KIND.
 * - Range and date words (same condition): equal dates and equal date-word status -> fine; date
 *   words USED with other dates -> WRONG_RANGE (wrong); else, when USED was expected:
 *   NOT_UNDERSTOOD -> DATES_NOT_UNDERSTOOD, NONE -> DATES_DROPPED; when NONE or NOT_UNDERSTOOD
 *   was expected: NOT_UNDERSTOOD -> DATES_NOT_UNDERSTOOD, NONE -> DATES_DROPPED, USED with the
 *   same (all-time) dates -> DATE_WORDS_DIFFER (safe misses).
 * Any wrong check -> WRONG; else any check -> SAFE_MISS; else CORRECT. A failure entry is FAILED.
 *
 * Range dates: a custom range compares by its dates; ALL_TIME is all time (its first day depends
 * on the entries and is not compared); any other preset is pinned to dates as of the case's today.
 */
class QuestionReplay(private val corpus: QuestionCorpus) {

    /**
     * Replays and scores every case.
     *
     * @throws StaleRecordingException if [recording] was made against a different question corpus
     *   (hash) or its entries are not exactly one per current corpus case.
     */
    fun replay(recording: QuestionRecording): QuestionReplayResult {
        if (recording.questionCorpusSha256 != corpus.sha256) {
            throw StaleRecordingException(
                "stale question recording: recorded against question corpus ${recording.questionCorpusSha256}, " +
                    "current is ${corpus.sha256}; re-record",
            )
        }
        val byId = recording.entries.associateBy { it.caseId }
        val missing = corpus.cases.map { it.id }.filter { it !in byId }
        val extra = byId.keys.filter { corpus.case(it) == null }
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw StaleRecordingException(
                "stale question recording: entries do not cover exactly the question corpus cases " +
                    "(missing ${missing.sorted()}, unknown ${extra.sorted()}); re-record",
            )
        }
        return QuestionReplayResult(
            provenance = QuestionReplayProvenance.of(recording),
            entries = corpus.cases.map { score(it, byId.getValue(it.id), recording.provenance) },
        )
    }

    private fun score(case: QuestionCorpusCase, entry: QuestionRecordingEntry, provenance: InterpreterProvenance): QuestionReplayEntry {
        val answer = entry.answer
            ?: return QuestionReplayEntry(
                caseId = case.id,
                group = case.group,
                replayClass = QuestionReplayClass.FAILED,
                checks = emptyList(),
                outcome = null,
                subjectId = null,
                subjectExact = null,
                actionId = null,
                actionExact = null,
                kind = null,
                range = null,
                dateWords = null,
                failureKind = entry.failureKind,
                latencyMs = entry.latencyMs,
            )

        val service = LookupService(
            repository = QuestionFixtureRepository(corpus.fixtureOf(case)),
            extractor = ReplayingQuestionExtractor(answer.toCandidate(), provenance),
        )
        val today = case.todayDate
        val lookup = runSuspend { service.ask(case.question, today, case.weekStart) }
        val expected = case.expected

        val outcome: QuestionOutcome
        val scope: QuestionScope?
        var answerOutcome: LookupOutcome.Answer? = null
        when (lookup) {
            is LookupOutcome.Answer -> {
                outcome = QuestionOutcome.ANSWER
                scope = lookup.scope
                answerOutcome = lookup
            }
            is LookupOutcome.Browse -> {
                outcome = QuestionOutcome.BROWSE
                scope = lookup.scope
            }
            LookupOutcome.NotEnoughHistory -> {
                outcome = QuestionOutcome.NOT_ENOUGH_HISTORY
                scope = null
            }
            LookupOutcome.NotAQuestion, LookupOutcome.Unavailable, LookupOutcome.Busy, LookupOutcome.Failed ->
                error("question replay harness error for case '${case.id}': unexpected lookup outcome ${lookup::class.simpleName}")
        }
        val subject = answerOutcome?.target?.subject
        val action = answerOutcome?.target?.action
        val range = scope?.let { rangeDates(it.range, case) }

        val checks = mutableListOf<QuestionCheck>()
        when {
            expected.outcome == QuestionOutcome.NOT_ENOUGH_HISTORY && outcome != QuestionOutcome.NOT_ENOUGH_HISTORY ->
                checks += QuestionCheck.UNEXPECTED_ANSWER
            expected.outcome != QuestionOutcome.NOT_ENOUGH_HISTORY && outcome == QuestionOutcome.NOT_ENOUGH_HISTORY ->
                checks += QuestionCheck.NOTHING_FOUND
            expected.outcome != outcome -> checks += QuestionCheck.WRONG_OUTCOME
        }
        if (listOfNotNull(subject?.id, action?.id).any { it in expected.mustNotMatch }) checks += QuestionCheck.MUST_NOT_MATCH
        if (expected.outcome == QuestionOutcome.ANSWER && outcome == QuestionOutcome.ANSWER) {
            if (!sideOk(subject?.id, expected.subjectIds)) checks += QuestionCheck.WRONG_SUBJECT
            if (!sideOk(action?.id, expected.actionIds)) checks += QuestionCheck.WRONG_ACTION
        }
        if (scope != null && range != null && expected.outcome != QuestionOutcome.NOT_ENOUGH_HISTORY) {
            if (scope.kind !in expected.acceptableKinds) checks += QuestionCheck.WRONG_KIND
            dateCheck(expected, range, scope.dateWords)?.let { checks += it }
        }

        val replayClass = when {
            checks.any { it.wrong } -> QuestionReplayClass.WRONG
            checks.isNotEmpty() -> QuestionReplayClass.SAFE_MISS
            else -> QuestionReplayClass.CORRECT
        }
        return QuestionReplayEntry(
            caseId = case.id,
            group = case.group,
            replayClass = replayClass,
            checks = checks.toList(),
            outcome = outcome,
            subjectId = subject?.id,
            subjectExact = subject?.exact,
            actionId = action?.id,
            actionExact = action?.exact,
            kind = scope?.kind,
            range = range,
            dateWords = scope?.dateWords,
            failureKind = null,
            latencyMs = entry.latencyMs,
        )
    }

    /** One target side against its acceptable ids: empty means it must be unset. */
    private fun sideOk(id: String?, acceptable: List<String>): Boolean =
        if (acceptable.isEmpty()) id == null else id != null && id in acceptable

    /** The range / date-word check (see the class description), or null when both are as expected. */
    private fun dateCheck(expected: ExpectedQuestion, actual: QuestionRangeDates, words: DateWords): QuestionCheck? {
        val expectedRange = expected.range.toDates()
        if (actual == expectedRange && words == expected.dateWords) return null
        if (words == DateWords.USED && actual != expectedRange) return QuestionCheck.WRONG_RANGE
        return when (words) {
            DateWords.NOT_UNDERSTOOD -> QuestionCheck.DATES_NOT_UNDERSTOOD
            DateWords.NONE -> QuestionCheck.DATES_DROPPED
            // USED with the expected dates, but USED was not expected.
            DateWords.USED -> QuestionCheck.DATE_WORDS_DIFFER
        }
    }

    /** [selection] as dates: custom by its dates, ALL_TIME as all time, other presets as of the case's today. */
    private fun rangeDates(selection: DateRangeSelection, case: QuestionCorpusCase): QuestionRangeDates = when {
        selection == DateRangeSelection.Preset(DateRangePreset.ALL_TIME) -> QuestionRangeDates.ALL_TIME
        else -> resolve(selection, case.todayDate, earliestEntryDate = null).let { QuestionRangeDates(it.start, it.endInclusive) }
    }

    /** Returns the recorded candidate for the one question it is asked. Never suspends. */
    private class ReplayingQuestionExtractor(
        private val candidate: QuestionCandidate,
        override val provenance: InterpreterProvenance,
    ) : QuestionExtractor {
        override suspend fun extract(questionText: String): QuestionExtractionResult =
            QuestionExtractionResult.Success(candidate)
    }
}
