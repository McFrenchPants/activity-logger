package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.Clock

/*
 * JVM replay of a semantic recording through the REAL capture pipeline.
 *
 * For each recorded answer: a fresh InMemoryActivityRepository seeded with the case's fixed-id
 * catalog, a capture created through the repository API, a FakeActivityInterpreter returning the
 * recorded answer, and the production CaptureInterpretationOrchestrator with its default (real)
 * CandidateSelector, TemporalResolver and InterpretationValidator. The scorer below only READS
 * what the pipeline did (outcome, stored occurrence and activity) and compares it with the case's
 * expectation. It never decides confidence, validity or time itself.
 */

/** The one class every replayed entry falls into. */
enum class ReplayClass {
    /** Infrastructure did not run the model (UNAVAILABLE / RETRYABLE); excluded from rates. */
    NOT_RUN,

    /** The pipeline ended exactly where the product expects. */
    CORRECT,

    /** Should have been logged automatically, but went to review; nothing wrong was saved. */
    SAFE_MISS,

    /** Something was saved that should not have been, or was saved wrongly. */
    UNSAFE_MISS,
}

/** How the orchestrator ended, as a short code. */
enum class ReplayOutcome {
    AUTO_ACCEPTED,
    NEEDS_REVIEW,
    REJECTED,
    INTERPRETER_UNAVAILABLE,
}

/** Why an occurrence that was created is an UNSAFE_MISS. Codes only; no text. */
enum class UnsafeCheck {
    /** The case must go to review, but an occurrence was created. */
    EXPECTED_REVIEW,

    /** The occurrence's activity is one the case lists as must-not-match. */
    MUST_NOT_MATCH,

    /** An existing activity was expected, but a different existing one was used. */
    WRONG_EXISTING_ACTIVITY,

    /** An existing activity was expected, but a new (duplicate) activity was created. */
    DUPLICATE_NEW_ACTIVITY,

    /** A new activity was expected, but an existing one (not in `allowedActivityIds`) was matched. */
    MATCHED_EXISTING_WHEN_NEW_EXPECTED,

    /** A new activity was expected and created, but its name is not an acceptable one. */
    WRONG_NEW_NAME,

    /** The occurrence's state is not in the case's allowed states. */
    STATE_NOT_ALLOWED,

    /** The occurrence's local date (case zone) differs from the expected date. */
    WRONG_DATE,

    /** The occurrence's time precision differs from the expected precision. */
    WRONG_PRECISION,
}

/**
 * The score of one recording entry.
 *
 * @property replayClass The class; exactly one.
 * @property unsafeChecks For UNSAFE_MISS, every check that failed (non-empty); else empty.
 * @property validationReasons Reason codes the pipeline gave (sorted names); empty on auto-accept.
 * @property failureKind The recorded interpreter failure, if the entry is a failure.
 * @property resolutionAcceptable Secondary: recorded resolution is in the acceptable set
 *   (null for a failure entry).
 * @property temporalExpressionAgrees Secondary: recorded time words match an acceptable one,
 *   trimmed and case-insensitive; blank counts as empty (null for a failure entry).
 * @property confidenceBand Secondary: the recorded confidence band.
 */
data class ScoredEntry(
    val caseId: String,
    val category: CorpusCategory,
    val expectedOutcome: ExpectedOutcome,
    val replayClass: ReplayClass,
    val outcome: ReplayOutcome,
    val unsafeChecks: List<UnsafeCheck>,
    val validationReasons: List<String>,
    val failureKind: InterpreterFailureKind?,
    val resolutionAcceptable: Boolean?,
    val temporalExpressionAgrees: Boolean?,
    val confidenceBand: ConfidenceBand?,
    val latencyMs: Long?,
) {
    /** Short reason codes for reports: unsafe checks, else validation reasons, else failure kind. */
    val reasonCodes: List<String>
        get() = when {
            unsafeChecks.isNotEmpty() -> unsafeChecks.map { it.name }
            validationReasons.isNotEmpty() -> validationReasons
            failureKind != null -> listOf(failureKind.name)
            else -> emptyList()
        }
}

/** A replayed recording: its provenance plus one score per entry, in recording order. */
data class ReplayResult(
    val recording: SemanticRecording,
    val corpusSha256: String,
    val entries: List<ScoredEntry>,
) {
    /** True if the recording was made against exactly the current corpus bytes. */
    val corpusMatches: Boolean get() = recording.corpusSha256 == corpusSha256

    /** Entries of [replayClass]. */
    fun of(replayClass: ReplayClass): List<ScoredEntry> = entries.filter { it.replayClass == replayClass }
}

/** The recording no longer matches the corpus (unknown case, or a different shortlist). */
class StaleRecordingException(message: String) : IllegalStateException(message)

/** Replays recordings against [corpus]. */
class SemanticReplay(private val corpus: SemanticCorpus) {

    /**
     * Replays and scores every entry of [recording].
     *
     * @throws StaleRecordingException naming the case id, if an entry's case is not in the
     *   corpus or its recorded shortlist (ids or context hash) differs from today's.
     */
    fun replay(recording: SemanticRecording): ReplayResult =
        ReplayResult(recording, corpus.sha256, recording.entries.map { replayEntry(recording, it) })

    private fun replayEntry(recording: SemanticRecording, entry: RecordingEntry): ScoredEntry {
        val case = corpus.case(entry.caseId) ?: throw StaleRecordingException(
            "stale recording: case id '${entry.caseId}' is not in the current corpus; re-record",
        )
        val shown = CorpusInterpretationInput.forCase(corpus, case)
        if (shown.selection.contextHash != entry.contextHash ||
            shown.selection.candidates.map { it.id } != entry.offeredCandidateIds
        ) {
            throw StaleRecordingException(
                "stale recording: case '${case.id}' was recorded with a different candidate shortlist " +
                    "(contextHash/candidate ids differ from the current corpus); re-record",
            )
        }

        val clock = Clock.fixed(case.capturedInstant, case.zone)
        val repository = InMemoryActivityRepository(clock)
        val fixture = corpus.catalogs.getValue(case.catalog)
        fixture.activities.forEach { repository.seedActivity(it.displayName, aliases = it.aliases, id = it.id) }
        val interpreter = FakeActivityInterpreter(recording.provenance)
        interpreter.enqueue(entry.toResult())
        val orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock)

        val (outcome, occurrence) = runSuspend {
            val captureId = repository.createRawCapture(
                NewRawCapture(
                    source = CaptureSource.PHONE_TEXT,
                    sourceSurface = null,
                    capturedAt = case.capturedInstant,
                    zoneId = case.zone,
                    rawText = case.rawText,
                    speechConfidence = null,
                    speechAlternativesJson = null,
                    processingState = ProcessingState.CAPTURED,
                ),
            )
            val outcome = orchestrator.process(captureId)
            val occurrence = (outcome as? CaptureProcessingOutcome.AutoAccepted)?.let { repository.getOccurrence(it.occurrenceId) }
            outcome to occurrence
        }
        // Harness self-checks: the pipeline saw exactly what the model was shown when recorded.
        check(interpreter.receivedInputs == listOf(shown.input)) {
            "replay harness error for case '${case.id}': orchestrator input differs from the shared input builder"
        }
        check(outcome is CaptureProcessingOutcome.AutoAccepted == repository.occurrences.isNotEmpty()) {
            "replay harness error for case '${case.id}': outcome and stored occurrences disagree"
        }

        val replayOutcome: ReplayOutcome
        val reasons: List<String>
        when (outcome) {
            is CaptureProcessingOutcome.AutoAccepted -> {
                replayOutcome = ReplayOutcome.AUTO_ACCEPTED
                reasons = emptyList()
            }
            is CaptureProcessingOutcome.NeedsReview -> {
                replayOutcome = ReplayOutcome.NEEDS_REVIEW
                reasons = outcome.reasons.map { it.name }.sorted()
            }
            is CaptureProcessingOutcome.Rejected -> {
                replayOutcome = ReplayOutcome.REJECTED
                reasons = outcome.reasons.map { it.name }.sorted()
            }
            is CaptureProcessingOutcome.InterpreterUnavailable -> {
                replayOutcome = ReplayOutcome.INTERPRETER_UNAVAILABLE
                reasons = emptyList()
            }
            CaptureProcessingOutcome.AlreadyHasOccurrence ->
                error("replay harness error for case '${case.id}': fresh capture already had an occurrence")
        }

        val expected = case.expected
        val unsafe = mutableListOf<UnsafeCheck>()
        val replayClass = when {
            replayOutcome == ReplayOutcome.INTERPRETER_UNAVAILABLE -> ReplayClass.NOT_RUN
            occurrence == null ->
                if (expected.outcome == ExpectedOutcome.NEEDS_REVIEW) ReplayClass.CORRECT else ReplayClass.SAFE_MISS
            else -> {
                val activityId = occurrence.canonicalActivityId
                val catalogIds = fixture.activities.map { it.id }.toSet()
                if (expected.outcome == ExpectedOutcome.NEEDS_REVIEW) unsafe += UnsafeCheck.EXPECTED_REVIEW
                if (activityId in expected.mustNotMatch) unsafe += UnsafeCheck.MUST_NOT_MATCH
                if (expected.outcome == ExpectedOutcome.AUTO_ACCEPT) {
                    if (activityId in catalogIds) {
                        // Any acceptable catalog id (activityId or an owner-accepted alternative) is correct.
                        when {
                            activityId in expected.acceptableActivityIds -> Unit
                            expected.activityId == null -> unsafe += UnsafeCheck.MATCHED_EXISTING_WHEN_NEW_EXPECTED
                            else -> unsafe += UnsafeCheck.WRONG_EXISTING_ACTIVITY
                        }
                    } else {
                        val created = checkNotNull(runSuspend { repository.getActivity(activityId) }) {
                            "replay harness error for case '${case.id}': occurrence activity not stored"
                        }
                        val acceptable = expected.acceptableNewNames.map(NameNormalizer::normalize).toSet()
                        when {
                            expected.activityId != null -> unsafe += UnsafeCheck.DUPLICATE_NEW_ACTIVITY
                            NameNormalizer.normalize(created.displayName) !in acceptable -> unsafe += UnsafeCheck.WRONG_NEW_NAME
                        }
                    }
                    if (occurrence.activityState !in expected.allowedStates) unsafe += UnsafeCheck.STATE_NOT_ALLOWED
                    val expectedDate = expected.expectedLocalDate
                    if (expectedDate != null && occurrence.occurredAt.atZone(case.zone).toLocalDate() != expectedDate) {
                        unsafe += UnsafeCheck.WRONG_DATE
                    }
                    if (expected.precision != null && occurrence.timePrecision != expected.precision) {
                        unsafe += UnsafeCheck.WRONG_PRECISION
                    }
                }
                if (unsafe.isEmpty()) ReplayClass.CORRECT else ReplayClass.UNSAFE_MISS
            }
        }

        val answer = entry.answer
        return ScoredEntry(
            caseId = case.id,
            category = case.category,
            expectedOutcome = expected.outcome,
            replayClass = replayClass,
            outcome = replayOutcome,
            unsafeChecks = unsafe.toList(),
            validationReasons = reasons,
            failureKind = entry.failureKind,
            resolutionAcceptable = answer?.let { it.activityResolution in expected.acceptableResolutions },
            temporalExpressionAgrees = answer?.let { temporalAgrees(it.temporalExpression, expected.acceptableTemporalExpressions) },
            confidenceBand = answer?.confidenceBand,
            latencyMs = entry.latencyMs,
        )
    }

    private fun temporalAgrees(recorded: String?, acceptable: List<String?>): Boolean {
        val words = recorded?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(java.util.Locale.ROOT)
        return acceptable.any { option ->
            val candidate = option?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(java.util.Locale.ROOT)
            candidate == words
        }
    }
}
