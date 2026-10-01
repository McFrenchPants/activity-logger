package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionGrounding
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionPolicy
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.DurationResolver
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolver
import java.time.LocalDate

/*
 * JVM replay of a TAG recording (TagRecording) through the REAL deterministic tag logic.
 *
 * For each tag corpus case the recorded extraction is first passed through the production
 * ExtractionGrounding guard (time/duration words the sentence does not contain are dropped, and
 * the drop is recorded as a flag), then fed to the production TagDecisionPolicy
 * against the case's catalog fixture (TagCorpusCatalogs.toTagCatalog), its time words to the
 * production TemporalResolver at the case's capture instant and zone, and its duration words to
 * the production DurationResolver. The scorer only READS those decisions and compares them with
 * the case's expectation; it never decides tags, time or duration itself.
 *
 * Model output (the extracted words, and any new tag name built from them) is used only for that
 * comparison and is never stored in a TagReplayEntry, so a report built from the result cannot
 * leak it (AGENTS.md #11, docs/SEMANTIC_CORPUS.md section 10).
 */

/** The one class every replayed tag entry falls into. */
enum class TagReplayClass {
    /** The decision ended in an acceptable outcome and nothing wrong was saved. */
    CORRECT,

    /** Asked (CONFIRM) or held for review when a silent save was expected; nothing wrong was saved. */
    SAFE_MISS,

    /**
     * Saved silently with the right existing tags, but under a new tag whose name is not one of
     * the case's acceptable new names. Reported separately; not gated.
     */
    NAME_MISMATCH,

    /** Something was saved silently that should not have been, or was saved wrongly. */
    UNSAFE,

    /** The extractor produced no answer (any failure kind). */
    FAILED,
}

/** Why a silent save (AUTO_SAVE) is UNSAFE. Codes only; no text. */
enum class TagUnsafeCheck {
    /** AUTO_SAVE is not one of the case's acceptable outcomes. */
    SILENT_WHEN_NOT_ACCEPTABLE,

    /** The subject resolved to an existing tag the case lists as must-not-match. */
    SUBJECT_MUST_NOT_MATCH,

    /** The subject resolved to an existing tag that is not an acceptable one. */
    SUBJECT_WRONG_EXISTING_TAG,

    /** A new subject tag was created where an existing one is expected. */
    SUBJECT_DUPLICATE_TAG,

    /** The action resolved to an existing tag the case lists as must-not-match. */
    ACTION_MUST_NOT_MATCH,

    /** The action resolved to an existing tag that is not an acceptable one. */
    ACTION_WRONG_EXISTING_TAG,

    /** A new action tag was created where an existing one is expected. */
    ACTION_DUPLICATE_TAG,

    /** The time words do not resolve to the expected local date (see [TagReplay]). */
    WRONG_TIME,

    /** The duration words do not resolve to the expected number of minutes. */
    WRONG_DURATION,
}

/** Which side of a NAME_MISMATCH carries the oddly named new tag. Codes only. */
enum class TagNameMismatch {
    SUBJECT_NEW_NAME,
    ACTION_NEW_NAME,
}

/**
 * The score of one tag corpus case. Holds ids, enum codes, booleans and numbers only -- never
 * model text.
 *
 * @property outcome The policy outcome, or null for a FAILED entry.
 * @property decisionReasons The policy's reason codes (sorted names); empty on AUTO_SAVE.
 * @property unsafeChecks For UNSAFE, every check that failed (non-empty); else empty.
 * @property nameMismatches For NAME_MISMATCH, the side(s) with an unacceptable new name.
 * @property subjectTag How the subject resolved: an existing tag id, `new`, `near`, `empty`;
 *   null for a FAILED entry.
 * @property actionTag The same for the action.
 * @property subjectInferred True when the subject was filled in from the only known pair.
 * @property timeOk The time words resolve to the expected local date (see [TagReplay]); null
 *   for a FAILED entry.
 * @property durationOk The duration resolves to the expected minutes (both absent counts as
 *   equal); null for a FAILED entry.
 * @property timeDropped The grounding guard removed the recorded time words (not in the sentence).
 * @property durationDropped The grounding guard removed the recorded duration words.
 */
data class TagReplayEntry(
    val caseId: String,
    val group: TagCaseGroup,
    val replayClass: TagReplayClass,
    val outcome: TagOutcome?,
    val decisionReasons: List<String>,
    val unsafeChecks: List<TagUnsafeCheck>,
    val nameMismatches: List<TagNameMismatch>,
    val subjectTag: String?,
    val actionTag: String?,
    val subjectInferred: Boolean,
    val timeOk: Boolean?,
    val durationOk: Boolean?,
    val failureKind: InterpreterFailureKind?,
    val latencyMs: Long?,
    val timeDropped: Boolean = false,
    val durationDropped: Boolean = false,
) {
    /** Short codes for reports: unsafe checks, else name mismatches, else decision reasons, else failure kind. */
    val reasonCodes: List<String>
        get() = when {
            unsafeChecks.isNotEmpty() -> unsafeChecks.map { it.name }
            nameMismatches.isNotEmpty() -> nameMismatches.map { it.name }
            decisionReasons.isNotEmpty() -> decisionReasons
            failureKind != null -> listOf(failureKind.name)
            else -> emptyList()
        }
}

/**
 * A recording's provenance header, copied from the [TagRecording] (no entries, so no model
 * output). [notes] is the recorder's own note (e.g. busy retries), not model text.
 */
data class TagReplayProvenance(
    val formatVersion: Int,
    val source: RecordingSource,
    val modelLabel: String,
    val deviceModel: String?,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val tagCorpusSha256: String,
    val recordedAt: String,
    val notes: String?,
) {
    companion object {
        /** The header of [recording]. */
        fun of(recording: TagRecording): TagReplayProvenance = TagReplayProvenance(
            formatVersion = recording.formatVersion,
            source = recording.source,
            modelLabel = recording.modelLabel,
            deviceModel = recording.deviceModel,
            interpreterVersion = recording.interpreterVersion,
            promptVersion = recording.promptVersion,
            schemaVersion = recording.schemaVersion,
            tagCorpusSha256 = recording.tagCorpusSha256,
            recordedAt = recording.recordedAt,
            notes = recording.notes,
        )
    }
}

/** A replayed tag recording: provenance plus one score per corpus case, in corpus order. */
data class TagReplayResult(
    val provenance: TagReplayProvenance,
    val entries: List<TagReplayEntry>,
) {
    /** Entries of [replayClass]. */
    fun of(replayClass: TagReplayClass): List<TagReplayEntry> = entries.filter { it.replayClass == replayClass }

    /** Count per class (every class present, zero included). */
    val classCounts: Map<TagReplayClass, Int>
        get() = TagReplayClass.entries.associateWith { c -> entries.count { it.replayClass == c } }

    /** Count per group and class (every group and class present, zero included). */
    val groupCounts: Map<TagCaseGroup, Map<TagReplayClass, Int>>
        get() = TagCaseGroup.entries.associateWith { g ->
            TagReplayClass.entries.associateWith { c -> entries.count { it.group == g && it.replayClass == c } }
        }

    /** Number of entries failing each unsafe check (every check present, zero included). */
    val unsafeCheckCounts: Map<TagUnsafeCheck, Int>
        get() = TagUnsafeCheck.entries.associateWith { u -> entries.count { u in it.unsafeChecks } }

    /** Number of entries whose time words the grounding guard dropped. */
    val timeDroppedCount: Int
        get() = entries.count { it.timeDropped }

    /** Number of entries whose duration words the grounding guard dropped. */
    val durationDroppedCount: Int
        get() = entries.count { it.durationDropped }
}

/**
 * Replays tag recordings against [corpus].
 *
 * **Grounding** (TG1.4b): every answer is first passed through `ExtractionGrounding.ground`
 * with the case's sentence; the grounded extraction is what the policy, the time check and the
 * duration check see. Drops are recorded per entry ([TagReplayEntry.timeDropped] /
 * [TagReplayEntry.durationDropped]).
 *
 * **Classification** of one answered case, with `decision = TagDecisionPolicy.decide(...)`:
 * 1. AUTO_SAVE with any [TagUnsafeCheck] -> UNSAFE. The checks (silent saves only):
 *    - SILENT_WHEN_NOT_ACCEPTABLE: AUTO_SAVE is not an acceptable outcome.
 *    - per side, `<SIDE>_MUST_NOT_MATCH`: an exact tag id in the side's mustNotMatch.
 *    - `<SIDE>_WRONG_EXISTING_TAG`: an exact tag id not in the side's acceptable existing ids
 *      (an inferred subject is held to the same rule: it must be an acceptable id).
 *    - `<SIDE>_DUPLICATE_TAG`: a new tag where the side expects an existing id.
 *    - WRONG_TIME: the time words (production TemporalResolver at the capture instant/zone)
 *      do not resolve to the expected local date -- the expected `resolvedLocalDate` when the
 *      case has time words, else the capture's own local date. Future or unreadable words have
 *      no date and therefore count as wrong.
 *    - WRONG_DURATION: DurationResolver minutes differ from `durationMinutes` (both null equal).
 * 2. AUTO_SAVE with a new tag whose name is not acceptable (and no existing id expected) ->
 *    NAME_MISMATCH.
 * 3. Outcome acceptable -> CORRECT.
 * 4. Otherwise -> SAFE_MISS (CONFIRM or NEEDS_REVIEW when a silent save was expected).
 * A failure entry is FAILED.
 */
class TagReplay(private val corpus: TagCorpus) {

    private val temporalResolver = TemporalResolver()

    /**
     * Replays and scores every case.
     *
     * @throws StaleRecordingException if [recording] was made against a different tag corpus
     *   (hash) or its entries are not exactly one per current corpus case.
     */
    fun replay(recording: TagRecording): TagReplayResult {
        if (recording.tagCorpusSha256 != corpus.sha256) {
            throw StaleRecordingException(
                "stale tag recording: recorded against tag corpus ${recording.tagCorpusSha256}, " +
                    "current is ${corpus.sha256}; re-record",
            )
        }
        val byId = recording.entries.associateBy { it.caseId }
        val corpusIds = corpus.cases.map { it.id }
        val missing = corpusIds.filter { it !in byId }
        val extra = byId.keys.filter { corpus.case(it) == null }
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw StaleRecordingException(
                "stale tag recording: entries do not cover exactly the tag corpus cases " +
                    "(missing ${missing.sorted()}, unknown ${extra.sorted()}); re-record",
            )
        }
        return TagReplayResult(
            provenance = TagReplayProvenance.of(recording),
            entries = corpus.cases.map { score(it, byId.getValue(it.id)) },
        )
    }

    private fun score(case: TagCorpusCase, entry: TagRecordingEntry): TagReplayEntry {
        val answer = entry.answer
            ?: return TagReplayEntry(
                caseId = case.id,
                group = case.group,
                replayClass = TagReplayClass.FAILED,
                outcome = null,
                decisionReasons = emptyList(),
                unsafeChecks = emptyList(),
                nameMismatches = emptyList(),
                subjectTag = null,
                actionTag = null,
                subjectInferred = false,
                timeOk = null,
                durationOk = null,
                failureKind = entry.failureKind,
                latencyMs = entry.latencyMs,
            )

        val fixture = corpus.catalogs[case.catalog]
            ?: error("tag replay harness error for case '${case.id}': catalog fixture missing")
        val grounded = ExtractionGrounding.ground(answer.toCandidate(), case.rawText)
        val candidate = grounded.candidate
        val decision = TagDecisionPolicy.decide(candidate, TagCorpusCatalogs.toTagCatalog(fixture))
        val outcome = TagOutcome.valueOf(decision.outcome.name)
        val expected = case.expected

        val timeOk = resolvedLocalDate(candidate.temporalExpression, case) ==
            (expected.expectedLocalDate ?: case.capturedInstant.atZone(case.zone).toLocalDate())
        val durationOk = DurationResolver.resolve(candidate.durationExpression) == expected.durationMinutes

        val unsafe = mutableListOf<TagUnsafeCheck>()
        val mismatches = mutableListOf<TagNameMismatch>()
        if (decision.outcome == TagDecisionOutcome.AUTO_SAVE) {
            if (outcome !in expected.acceptableOutcomes) unsafe += TagUnsafeCheck.SILENT_WHEN_NOT_ACCEPTABLE
            checkSide(decision.subject, expected.subject, Side.SUBJECT, unsafe, mismatches)
            checkSide(decision.action, expected.action, Side.ACTION, unsafe, mismatches)
            if (!timeOk) unsafe += TagUnsafeCheck.WRONG_TIME
            if (!durationOk) unsafe += TagUnsafeCheck.WRONG_DURATION
        }

        val replayClass = when {
            unsafe.isNotEmpty() -> TagReplayClass.UNSAFE
            mismatches.isNotEmpty() -> TagReplayClass.NAME_MISMATCH
            outcome in expected.acceptableOutcomes -> TagReplayClass.CORRECT
            else -> TagReplayClass.SAFE_MISS
        }

        return TagReplayEntry(
            caseId = case.id,
            group = case.group,
            replayClass = replayClass,
            outcome = outcome,
            decisionReasons = decision.reasons.map { it.name }.sorted(),
            unsafeChecks = unsafe.toList(),
            nameMismatches = if (replayClass == TagReplayClass.NAME_MISMATCH) mismatches.toList() else emptyList(),
            subjectTag = code(decision.subject),
            actionTag = code(decision.action),
            subjectInferred = decision.subjectInferred,
            timeOk = timeOk,
            durationOk = durationOk,
            failureKind = null,
            latencyMs = entry.latencyMs,
            timeDropped = grounded.timeDropped,
            durationDropped = grounded.durationDropped,
        )
    }

    private enum class Side(
        val mustNotMatch: TagUnsafeCheck,
        val wrongExisting: TagUnsafeCheck,
        val duplicate: TagUnsafeCheck,
        val nameMismatch: TagNameMismatch,
    ) {
        SUBJECT(
            TagUnsafeCheck.SUBJECT_MUST_NOT_MATCH,
            TagUnsafeCheck.SUBJECT_WRONG_EXISTING_TAG,
            TagUnsafeCheck.SUBJECT_DUPLICATE_TAG,
            TagNameMismatch.SUBJECT_NEW_NAME,
        ),
        ACTION(
            TagUnsafeCheck.ACTION_MUST_NOT_MATCH,
            TagUnsafeCheck.ACTION_WRONG_EXISTING_TAG,
            TagUnsafeCheck.ACTION_DUPLICATE_TAG,
            TagNameMismatch.ACTION_NEW_NAME,
        ),
    }

    /**
     * Checks one side of a silent save. An inferred subject is an Exact resolution and is held to
     * the same acceptable-id rule as a stated one: a silently inferred wrong subject is a wrong
     * save, whether or not the case allows the subject words to be omitted.
     */
    private fun checkSide(
        resolution: TagResolution,
        expected: ExpectedTag,
        side: Side,
        unsafe: MutableList<TagUnsafeCheck>,
        mismatches: MutableList<TagNameMismatch>,
    ) {
        when (resolution) {
            is TagResolution.Exact -> {
                val id = resolution.tag.id
                if (id in expected.mustNotMatch) unsafe += side.mustNotMatch
                if (id !in expected.acceptableExistingIds) unsafe += side.wrongExisting
            }
            is TagResolution.New -> when {
                expected.existingId != null -> unsafe += side.duplicate
                !expected.acceptsNewName(resolution.name) -> mismatches += side.nameMismatch
            }
            // The policy never auto-saves an Empty or Near side; nothing was saved for it.
            TagResolution.Empty, is TagResolution.Near -> Unit
        }
    }

    /** The local date (case zone) the time words resolve to, or null when Future/Unresolvable. */
    private fun resolvedLocalDate(expression: String?, case: TagCorpusCase): LocalDate? =
        when (val r = temporalResolver.resolve(expression, case.capturedInstant, case.zone)) {
            is TemporalResolution.Resolved -> r.occurredAt.atZone(case.zone).toLocalDate()
            TemporalResolution.Future, TemporalResolution.Unresolvable -> null
        }

    private fun code(resolution: TagResolution): String = when (resolution) {
        is TagResolution.Exact -> resolution.tag.id
        is TagResolution.New -> NEW
        is TagResolution.Near -> NEAR
        TagResolution.Empty -> EMPTY
    }

    companion object {
        /** [TagReplayEntry.subjectTag]/[TagReplayEntry.actionTag] code for a new tag (its name is model text). */
        const val NEW: String = "new"

        /** Code for a side that resolved close to existing tags. */
        const val NEAR: String = "near"

        /** Code for a side with no usable words. */
        const val EMPTY: String = "empty"
    }
}
