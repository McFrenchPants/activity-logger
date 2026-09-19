package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelection
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/*
 * Semantic regression RECORDING format, version [SemanticRecording.FORMAT_VERSION] (= 1).
 *
 * A recording captures what one AI model answered for every corpus case, so the deterministic
 * rest of the pipeline can be replayed on the JVM (see SemanticReplay). JSON, UTF-8, shape:
 *
 * {
 *   "formatVersion": 1,
 *   "source": "DEVICE" | "STAND_IN" | "SYNTHETIC",
 *   "modelLabel": "gemini-nano (AICore)",          // free label for humans
 *   "deviceModel": "Pixel 10 Pro" | null,           // null when not recorded on a phone
 *   "interpreterVersion": "...", "promptVersion": "...", "schemaVersion": 1,
 *   "corpusSha256": "<64 lowercase hex>",           // SemanticCorpus.sha256 at record time
 *   "recordedAt": "2026-09-17T12:00:00Z",           // ISO-8601 instant
 *   "notes": "..." | null,                          // e.g. "schema rendering approximated"
 *   "entries": [
 *     {
 *       "caseId": "mow-lawn-synonym",
 *       "offeredCandidateIds": ["act-..."],         // CandidateSelection ids, in order
 *       "contextHash": "<CandidateSelection.contextHash>",
 *       "answer": {                                 // XOR failureKind
 *         "operation": "LOG_ACTIVITY", "activityResolution": "EXISTING_ACTIVITY",
 *         "matchedActivityId": "act-..." | null, "proposedCanonicalName": "..." | null,
 *         "activityState": "COMPLETED" | "IN_PROGRESS" | null,
 *         "temporalExpression": "..." | null, "confidenceBand": "HIGH" | "MEDIUM" | "LOW" | null
 *       } | null,
 *       "failureKind": "UNAVAILABLE" | "RETRYABLE" | "MALFORMED" | "OTHER" | null,
 *       "latencyMs": 1234 | null
 *     }
 *   ]
 * }
 *
 * Enum values are the domain constants' names. Every field is written, nulls explicitly.
 * Unknown keys are rejected on read. A recording holds only synthetic corpus-derived data: there
 * is deliberately no field for the model's raw text output.
 *
 * Changing the shape incompatibly means bumping FORMAT_VERSION; readers reject other versions.
 */

/** Where a recording's answers came from. */
@Serializable
enum class RecordingSource {
    /** The real on-device model on a phone: the official result. */
    DEVICE,

    /** A local stand-in model approximating the device model: NOT the official result. */
    STAND_IN,

    /** Hand-written answers for testing the harness itself: NOT a model result at all. */
    SYNTHETIC,
}

/** A recorded model answer: the seven [InterpretationCandidate] fields. */
@Serializable
data class RecordedAnswer(
    val operation: InterpretationOperation,
    val activityResolution: ActivityResolution,
    val matchedActivityId: String?,
    val proposedCanonicalName: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val confidenceBand: ConfidenceBand?,
) {
    /** Back to the domain type. */
    fun toCandidate(): InterpretationCandidate = InterpretationCandidate(
        operation = operation,
        activityResolution = activityResolution,
        matchedActivityId = matchedActivityId,
        proposedCanonicalName = proposedCanonicalName,
        activityState = activityState,
        temporalExpression = temporalExpression,
        confidenceBand = confidenceBand,
    )

    companion object {
        /** From the domain type; maps every field. */
        fun of(candidate: InterpretationCandidate): RecordedAnswer = RecordedAnswer(
            operation = candidate.operation,
            activityResolution = candidate.activityResolution,
            matchedActivityId = candidate.matchedActivityId,
            proposedCanonicalName = candidate.proposedCanonicalName,
            activityState = candidate.activityState,
            temporalExpression = candidate.temporalExpression,
            confidenceBand = candidate.confidenceBand,
        )
    }
}

/**
 * What the model did for one corpus case. Exactly one of [answer] and [failureKind] is set.
 *
 * @property offeredCandidateIds Ids of the shortlist shown to the model, in order.
 * @property contextHash The shortlist's `CandidateSelection.contextHash`.
 * @property latencyMs Wall-clock time of the interpreter call, if measured.
 */
@Serializable
data class RecordingEntry(
    val caseId: String,
    val offeredCandidateIds: List<String>,
    val contextHash: String,
    val answer: RecordedAnswer?,
    val failureKind: InterpreterFailureKind?,
    val latencyMs: Long?,
) {
    init {
        require((answer == null) != (failureKind == null)) {
            "recording entry $caseId must have exactly one of answer and failureKind"
        }
        require(latencyMs == null || latencyMs >= 0) { "recording entry $caseId has negative latencyMs" }
    }

    /** The interpreter result this entry replays. No structured JSON is ever recorded. */
    fun toResult(): InterpretationResult =
        answer?.let { InterpretationResult.Success(it.toCandidate(), structuredResultJson = null) }
            ?: InterpretationResult.Failure(checkNotNull(failureKind), structuredResultJson = null)

    companion object {
        /**
         * Builds the entry for [caseId] from what the interpreter returned. Recorders must use this
         * (with the selection from [CorpusInterpretationInput.forCase]) rather than mapping fields
         * themselves. The result's structuredResultJson is deliberately dropped.
         */
        fun of(
            caseId: String,
            selection: CandidateSelection,
            result: InterpretationResult,
            latencyMs: Long?,
        ): RecordingEntry = RecordingEntry(
            caseId = caseId,
            offeredCandidateIds = selection.candidates.map { it.id },
            contextHash = selection.contextHash,
            answer = (result as? InterpretationResult.Success)?.let { RecordedAnswer.of(it.candidate) },
            failureKind = (result as? InterpretationResult.Failure)?.kind,
            latencyMs = latencyMs,
        )
    }
}

/** A whole recording: provenance header plus one entry per recorded case. See the file comment. */
@Serializable
data class SemanticRecording(
    val formatVersion: Int = FORMAT_VERSION,
    val source: RecordingSource,
    val modelLabel: String,
    val deviceModel: String?,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val corpusSha256: String,
    val recordedAt: String,
    val notes: String?,
    val entries: List<RecordingEntry>,
) {
    init {
        require(formatVersion == FORMAT_VERSION) {
            "unsupported recording formatVersion $formatVersion (this reader supports $FORMAT_VERSION)"
        }
        runCatching { Instant.parse(recordedAt) }.onFailure {
            throw IllegalArgumentException("recording recordedAt is not an ISO-8601 instant")
        }
        val duplicates = entries.groupBy { it.caseId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "recording has duplicate entries for case ids $duplicates" }
    }

    /** The provenance a replayed interpreter reports. */
    val provenance: InterpreterProvenance
        get() = InterpreterProvenance(interpreterVersion, promptVersion, schemaVersion)

    companion object {
        const val FORMAT_VERSION: Int = 1

        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Decodes a recording; throws on malformed JSON, unknown keys or an invalid shape. */
        fun read(text: String): SemanticRecording = JSON.decodeFromString(serializer(), text)

        /** Decodes a recording from UTF-8 [input]. Does not close the stream. */
        fun read(input: InputStream): SemanticRecording = read(input.readBytes().toString(Charsets.UTF_8))

        /** Encodes [recording] as pretty-printed JSON. */
        fun write(recording: SemanticRecording): String = JSON.encodeToString(serializer(), recording) + "\n"

        /**
         * Writes [recording] as UTF-8 JSON to [output] and flushes it; does not close it. Plain
         * stream API only, so it works from an Android instrumented test as well as the JVM.
         */
        fun write(recording: SemanticRecording, output: OutputStream) {
            output.write(write(recording).toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}
