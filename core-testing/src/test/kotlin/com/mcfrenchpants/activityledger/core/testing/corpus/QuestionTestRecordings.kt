package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind

/** Builds question recordings for tests. */
internal object QuestionRecordings {

    /** The default answer: no words at all (every case without an override). */
    val NOTHING = RecordedQuestion(null, null, null, QuestionKind.UNKNOWN)

    /** A recording with one answered entry per corpus case: [answers] where given, else [NOTHING]. */
    fun withAnswers(
        corpus: QuestionCorpus,
        answers: Map<String, RecordedQuestion>,
        source: RecordingSource = RecordingSource.SYNTHETIC,
    ): QuestionRecording {
        require(answers.keys.all { corpus.case(it) != null }) { "unknown case ids in test answers" }
        return QuestionRecording(
            source = source,
            modelLabel = "test",
            deviceModel = null,
            interpreterVersion = "test-question",
            promptVersion = "q2",
            schemaVersion = 1,
            questionCorpusSha256 = corpus.sha256,
            recordedAt = "2026-10-05T12:00:00Z",
            notes = null,
            entries = corpus.cases.map { QuestionRecordingEntry(it.id, answers[it.id] ?: NOTHING, null, 5) },
        )
    }

    /** Shorthand for a recorded answer. */
    fun q(subject: String?, action: String?, dateWindow: String? = null, kind: QuestionKind = QuestionKind.UNKNOWN) =
        RecordedQuestion(subject, action, dateWindow, kind)
}
