package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The question regression baseline (`recordings/question-baseline.json`), written by hand from a
 * reviewed device run.
 *
 * Shape (every key required, unknown keys rejected): `{ "mustStayCorrect": ["case-a", ...], "maxWrong": 2 }`
 *
 * @property mustStayCorrect Question corpus case ids that must stay CORRECT; unique.
 * @property maxWrong Most WRONG cases allowed in the whole recording; not negative.
 */
@Serializable
data class QuestionBaseline(
    val mustStayCorrect: List<String>,
    val maxWrong: Int,
) {
    init {
        val duplicates = mustStayCorrect.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "question baseline lists duplicate case ids $duplicates" }
        require(maxWrong >= 0) { "question baseline maxWrong must not be negative" }
    }
}

/**
 * The regression gate over a replayed DEVICE question recording.
 *
 * Fails on: a [QuestionBaseline.mustStayCorrect] case that is not CORRECT; more WRONG cases than
 * [QuestionBaseline.maxWrong]; a baseline id that is not in the question corpus. SAFE_MISS and
 * FAILED are reported but never gated on their own. A stale recording never reaches the gate:
 * [QuestionReplay] rejects it.
 */
object QuestionGate {

    private val JSON = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    /** Parses a baseline file; throws on malformed JSON, unknown or missing keys, or invalid values. */
    fun parseBaseline(text: String): QuestionBaseline = JSON.decodeFromString(QuestionBaseline.serializer(), text)

    /**
     * Every gate failure for [result] against [baseline]; empty means the gate passes.
     * Messages name case ids, classes, codes and counts only.
     */
    fun failures(result: QuestionReplayResult, baseline: QuestionBaseline, corpus: QuestionCorpus): List<String> {
        val problems = mutableListOf<String>()
        val unknown = baseline.mustStayCorrect.filter { corpus.case(it) == null }
        if (unknown.isNotEmpty()) problems += "question baseline lists case ids not in the question corpus: ${unknown.joinToString(", ")}"
        val byId = result.entries.associateBy { it.caseId }
        baseline.mustStayCorrect.filter { it !in unknown }.forEach { id ->
            val entry = byId[id]
            when {
                entry == null -> problems += "baseline case $id is missing from the replay"
                entry.replayClass != QuestionReplayClass.CORRECT ->
                    problems += "baseline case $id is ${entry.replayClass}: ${entry.reasonCodes.joinToString(",").ifEmpty { "-" }}"
            }
        }
        val wrong = result.of(QuestionReplayClass.WRONG)
        if (wrong.size > baseline.maxWrong) {
            problems += "WRONG cases ${wrong.size} exceed maxWrong ${baseline.maxWrong}: ${wrong.joinToString(", ") { it.caseId }}"
        }
        return problems
    }
}
