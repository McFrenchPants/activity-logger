package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/*
 * Question corpus RECORDING format, version [QuestionRecording.FORMAT_VERSION] (= 1).
 *
 * A question recording captures what one AI model EXTRACTED from every question corpus case
 * (question-corpus.json), so the real LookupService can later replay it on the JVM. JSON, UTF-8:
 *
 * {
 *   "formatVersion": 1,
 *   "source": "DEVICE" | "STAND_IN" | "SYNTHETIC",
 *   "modelLabel": "gemini-nano (AICore)",          // free label for humans
 *   "deviceModel": "Pixel 10 Pro" | null,
 *   "interpreterVersion": "gemini-nano-question-1", "promptVersion": "q2", "schemaVersion": 1,
 *   "questionCorpusSha256": "<64 lowercase hex>",   // QuestionCorpus.sha256 at record time
 *   "recordedAt": "2026-10-05T12:00:00Z",           // ISO-8601 instant
 *   "notes": "in-app runner; busy retries: 0" | null,
 *   "entries": [
 *     {
 *       "caseId": "c-mow-lawn-august",
 *       "answer": {                                 // XOR failureKind
 *         "subject": "..." | null, "action": "..." | null, "dateWindow": "..." | null,
 *         "kind": "LAST_TIME" | "COUNT" | "HOW_OFTEN" | "LIST" | "UNKNOWN"
 *       } | null,
 *       "failureKind": "UNAVAILABLE" | "RETRYABLE" | "MALFORMED" | "OTHER" | null,
 *       "latencyMs": 1234 | null
 *     }
 *   ]
 * }
 *
 * Every field is written, nulls explicitly; unknown keys are rejected on read. There is
 * deliberately no field for the question text or the model's raw output: an entry refers to its
 * case by id only. Changing the shape incompatibly means bumping FORMAT_VERSION.
 */

/** A recorded question extraction: the four [QuestionCandidate] fields. Untrusted model words. */
@Serializable
data class RecordedQuestion(
    val subject: String?,
    val action: String?,
    val dateWindow: String?,
    val kind: QuestionKind,
) {
    /** Back to the domain type. */
    fun toCandidate(): QuestionCandidate = QuestionCandidate(subject, action, dateWindow, kind)

    companion object {
        /** From the domain type; maps every field. */
        fun of(candidate: QuestionCandidate): RecordedQuestion =
            RecordedQuestion(candidate.subject, candidate.action, candidate.dateWindow, candidate.kind)
    }
}

/**
 * What the extractor did for one question corpus case. Exactly one of [answer] and
 * [failureKind] is set.
 *
 * @property latencyMs Wall-clock time of the extractor call, if measured; never negative.
 */
@Serializable
data class QuestionRecordingEntry(
    val caseId: String,
    val answer: RecordedQuestion?,
    val failureKind: InterpreterFailureKind?,
    val latencyMs: Long?,
) {
    init {
        require((answer == null) != (failureKind == null)) {
            "question recording entry $caseId must have exactly one of answer and failureKind"
        }
        require(latencyMs == null || latencyMs >= 0) { "question recording entry $caseId has negative latencyMs" }
    }

    /** The extractor result this entry replays. */
    fun toResult(): QuestionExtractionResult =
        answer?.let { QuestionExtractionResult.Success(it.toCandidate()) }
            ?: QuestionExtractionResult.Failure(checkNotNull(failureKind))

    companion object {
        /** Builds the entry for [caseId] from what the extractor returned. Recorders must use this. */
        fun of(caseId: String, result: QuestionExtractionResult, latencyMs: Long?): QuestionRecordingEntry =
            QuestionRecordingEntry(
                caseId = caseId,
                answer = (result as? QuestionExtractionResult.Success)?.let { RecordedQuestion.of(it.candidate) },
                failureKind = (result as? QuestionExtractionResult.Failure)?.kind,
                latencyMs = latencyMs,
            )
    }
}

/** A whole question recording: provenance header plus one entry per recorded case. See the file comment. */
@Serializable
data class QuestionRecording(
    val formatVersion: Int = FORMAT_VERSION,
    val source: RecordingSource,
    val modelLabel: String,
    val deviceModel: String?,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val questionCorpusSha256: String,
    val recordedAt: String,
    val notes: String?,
    val entries: List<QuestionRecordingEntry>,
) {
    init {
        require(formatVersion == FORMAT_VERSION) {
            "unsupported question recording formatVersion $formatVersion (this reader supports $FORMAT_VERSION)"
        }
        runCatching { Instant.parse(recordedAt) }.onFailure {
            throw IllegalArgumentException("question recording recordedAt is not an ISO-8601 instant")
        }
        val duplicates = entries.groupBy { it.caseId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "question recording has duplicate entries for case ids $duplicates" }
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

        /** Decodes a question recording; throws on malformed JSON, unknown or missing keys, or an invalid shape. */
        fun read(text: String): QuestionRecording = JSON.decodeFromString(serializer(), text)

        /** Decodes a question recording from UTF-8 [input]. Does not close the stream. */
        fun read(input: InputStream): QuestionRecording = read(input.readBytes().toString(Charsets.UTF_8))

        /** Encodes [recording] as pretty-printed JSON. */
        fun write(recording: QuestionRecording): String = JSON.encodeToString(serializer(), recording) + "\n"

        /** Writes [recording] as UTF-8 JSON to [output] and flushes it; does not close it. */
        fun write(recording: QuestionRecording, output: OutputStream) {
            output.write(write(recording).toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}
