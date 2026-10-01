package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/*
 * Tag corpus RECORDING format, version [TagRecording.FORMAT_VERSION] (= 1).
 *
 * A tag recording captures what one AI model EXTRACTED (prompt version 4, ADR-038) for every tag
 * corpus case (tag-corpus.json), so the deterministic resolution and policy can later be replayed
 * on the JVM. It is the extraction counterpart of SemanticRecording and deliberately a separate
 * file format. JSON, UTF-8, shape:
 *
 * {
 *   "formatVersion": 1,
 *   "source": "DEVICE" | "STAND_IN" | "SYNTHETIC",
 *   "modelLabel": "gemini-nano (AICore)",          // free label for humans
 *   "deviceModel": "Pixel 10 Pro" | null,           // null when not recorded on a phone
 *   "interpreterVersion": "gemini-nano-extract-1", "promptVersion": "4", "schemaVersion": 1,
 *   "tagCorpusSha256": "<64 lowercase hex>",        // TagCorpus.sha256 at record time
 *   "recordedAt": "2026-10-01T12:00:00Z",           // ISO-8601 instant
 *   "notes": "..." | null,                          // e.g. "busy retries: 3"
 *   "entries": [
 *     {
 *       "caseId": "real-changed-hot-tub-filter",
 *       "answer": {                                 // XOR failureKind
 *         "operation": "LOG_ACTIVITY" | "QUERY_HISTORY" | "UNSUPPORTED",
 *         "subject": "..." | null, "action": "..." | null,
 *         "activityState": "COMPLETED" | "IN_PROGRESS" | null,
 *         "temporalExpression": "..." | null, "durationExpression": "..." | null
 *       } | null,
 *       "failureKind": "UNAVAILABLE" | "RETRYABLE" | "MALFORMED" | "OTHER" | null,
 *       "latencyMs": 1234 | null
 *     }
 *   ]
 * }
 *
 * Enum values are the domain constants' names. Every field is written, nulls explicitly. Unknown
 * keys are rejected on read. There is deliberately no field for the model's raw text output, and
 * no field holding the case sentence: an entry refers to its case by id only.
 *
 * Changing the shape incompatibly means bumping FORMAT_VERSION; readers reject other versions.
 */

/** A recorded extraction answer: the six [ExtractionCandidate] fields. */
@Serializable
data class RecordedExtraction(
    val operation: InterpretationOperation,
    val subject: String?,
    val action: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val durationExpression: String?,
) {
    /** Back to the domain type. */
    fun toCandidate(): ExtractionCandidate = ExtractionCandidate(
        operation = operation,
        subject = subject,
        action = action,
        activityState = activityState,
        temporalExpression = temporalExpression,
        durationExpression = durationExpression,
    )

    companion object {
        /** From the domain type; maps every field. */
        fun of(candidate: ExtractionCandidate): RecordedExtraction = RecordedExtraction(
            operation = candidate.operation,
            subject = candidate.subject,
            action = candidate.action,
            activityState = candidate.activityState,
            temporalExpression = candidate.temporalExpression,
            durationExpression = candidate.durationExpression,
        )
    }
}

/**
 * What the extractor did for one tag corpus case. Exactly one of [answer] and [failureKind] is set.
 *
 * @property latencyMs Wall-clock time of the extractor call, if measured; never negative.
 */
@Serializable
data class TagRecordingEntry(
    val caseId: String,
    val answer: RecordedExtraction?,
    val failureKind: InterpreterFailureKind?,
    val latencyMs: Long?,
) {
    init {
        require((answer == null) != (failureKind == null)) {
            "tag recording entry $caseId must have exactly one of answer and failureKind"
        }
        require(latencyMs == null || latencyMs >= 0) { "tag recording entry $caseId has negative latencyMs" }
    }

    /** The extractor result this entry replays. */
    fun toResult(): ExtractionResult =
        answer?.let { ExtractionResult.Success(it.toCandidate()) }
            ?: ExtractionResult.Failure(checkNotNull(failureKind))

    companion object {
        /**
         * Builds the entry for [caseId] from what the extractor returned. Recorders must use this
         * (with the input from [TagCorpusExtractionInput.forCase]) rather than mapping fields
         * themselves.
         */
        fun of(caseId: String, result: ExtractionResult, latencyMs: Long?): TagRecordingEntry =
            TagRecordingEntry(
                caseId = caseId,
                answer = (result as? ExtractionResult.Success)?.let { RecordedExtraction.of(it.candidate) },
                failureKind = (result as? ExtractionResult.Failure)?.kind,
                latencyMs = latencyMs,
            )
    }
}

/** A whole tag recording: provenance header plus one entry per recorded case. See the file comment. */
@Serializable
data class TagRecording(
    val formatVersion: Int = FORMAT_VERSION,
    val source: RecordingSource,
    val modelLabel: String,
    val deviceModel: String?,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val tagCorpusSha256: String,
    val recordedAt: String,
    val notes: String?,
    val entries: List<TagRecordingEntry>,
) {
    init {
        require(formatVersion == FORMAT_VERSION) {
            "unsupported tag recording formatVersion $formatVersion (this reader supports $FORMAT_VERSION)"
        }
        runCatching { Instant.parse(recordedAt) }.onFailure {
            throw IllegalArgumentException("tag recording recordedAt is not an ISO-8601 instant")
        }
        val duplicates = entries.groupBy { it.caseId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "tag recording has duplicate entries for case ids $duplicates" }
    }

    /** The provenance a replayed extractor reports. */
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

        /** Decodes a tag recording; throws on malformed JSON, unknown keys or an invalid shape. */
        fun read(text: String): TagRecording = JSON.decodeFromString(serializer(), text)

        /** Decodes a tag recording from UTF-8 [input]. Does not close the stream. */
        fun read(input: InputStream): TagRecording = read(input.readBytes().toString(Charsets.UTF_8))

        /** Encodes [recording] as pretty-printed JSON. */
        fun write(recording: TagRecording): String = JSON.encodeToString(serializer(), recording) + "\n"

        /**
         * Writes [recording] as UTF-8 JSON to [output] and flushes it; does not close it. Plain
         * stream API only, so it works from an Android instrumented test as well as the JVM.
         */
        fun write(recording: TagRecording, output: OutputStream) {
            output.write(write(recording).toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}
