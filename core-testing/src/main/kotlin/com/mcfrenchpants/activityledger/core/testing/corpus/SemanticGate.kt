package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The regression gate over a replayed DEVICE recording.
 *
 * Baseline file format (`recordings/baseline.json`): a plain JSON array of corpus case ids that
 * the device model is known to get CORRECT, e.g. `["mow-lawn-synonym", "yesterday-evening"]`.
 * Ids must be unique. Any listed case that is not CORRECT in the latest device recording fails
 * the gate. Cases not listed are reported but never fail the build.
 */
object SemanticGate {

    private val JSON = Json { ignoreUnknownKeys = false }

    /** Parses a baseline file (a JSON array of case ids). */
    fun parseBaseline(text: String): List<String> {
        val ids = JSON.decodeFromString(ListSerializer(String.serializer()), text)
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "baseline lists duplicate case ids $duplicates" }
        return ids
    }

    /**
     * Every gate failure for [result] against [baseline]; empty means the gate passes.
     * Messages name case ids and classes/reason codes only.
     */
    fun failures(result: ReplayResult, baseline: List<String>, corpus: SemanticCorpus): List<String> {
        val problems = mutableListOf<String>()
        val byId = result.entries.associateBy { it.caseId }
        val unknown = baseline.filter { corpus.case(it) == null }
        if (unknown.isNotEmpty()) problems += "baseline lists case ids not in the corpus: ${unknown.joinToString(", ")}"
        val missing = baseline.filter { it !in byId && it !in unknown }
        if (!result.corpusMatches && missing.isNotEmpty()) {
            problems += "recording was made against a different corpus (recorded ${result.recording.corpusSha256}, " +
                "current ${result.corpusSha256}) and lacks baseline cases: ${missing.joinToString(", ")}; re-record"
        }
        baseline.filter { it !in unknown }.forEach { id ->
            val entry = byId[id]
            when {
                entry == null -> problems += "baseline case $id is missing from the recording"
                entry.replayClass != ReplayClass.CORRECT ->
                    problems += "baseline case $id is ${entry.replayClass}: ${entry.reasonCodes.joinToString(",").ifEmpty { "-" }}"
            }
        }
        return problems
    }
}
