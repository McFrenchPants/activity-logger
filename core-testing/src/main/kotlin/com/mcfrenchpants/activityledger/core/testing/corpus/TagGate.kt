package com.mcfrenchpants.activityledger.core.testing.corpus

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tag regression baseline (`recordings/tag-baseline.json`), created from a reviewed device run.
 *
 * Shape (every key required, unknown keys rejected):
 * `{ "mustStayCorrect": ["case-a", ...], "maxUnsafe": 3, "maxUnsafeRealEntry": 1 }`
 *
 * @property mustStayCorrect Tag corpus case ids that must stay CORRECT; unique.
 * @property maxUnsafe Most UNSAFE cases allowed in the whole recording; not negative.
 * @property maxUnsafeRealEntry Most UNSAFE cases allowed among REAL_ENTRY cases; not negative.
 */
@Serializable
data class TagBaseline(
    val mustStayCorrect: List<String>,
    val maxUnsafe: Int,
    val maxUnsafeRealEntry: Int,
) {
    init {
        val duplicates = mustStayCorrect.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "tag baseline lists duplicate case ids $duplicates" }
        require(maxUnsafe >= 0) { "tag baseline maxUnsafe must not be negative" }
        require(maxUnsafeRealEntry >= 0) { "tag baseline maxUnsafeRealEntry must not be negative" }
    }
}

/**
 * The regression gate over a replayed DEVICE tag recording. Stand-in recordings are never gated.
 *
 * Fails on: a [TagBaseline.mustStayCorrect] case that is not CORRECT; more UNSAFE cases than
 * [TagBaseline.maxUnsafe]; more REAL_ENTRY UNSAFE cases than [TagBaseline.maxUnsafeRealEntry];
 * a baseline id that is not in the tag corpus. NAME_MISMATCH is reported but never gated on its
 * own. A stale recording never reaches the gate: [TagReplay] rejects it.
 */
object TagGate {

    private val JSON = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    /** Parses a baseline file; throws on malformed JSON, unknown or missing keys, or invalid values. */
    fun parseBaseline(text: String): TagBaseline = JSON.decodeFromString(TagBaseline.serializer(), text)

    /**
     * Every gate failure for [result] against [baseline]; empty means the gate passes.
     * Messages name case ids, classes, codes and counts only.
     */
    fun failures(result: TagReplayResult, baseline: TagBaseline, corpus: TagCorpus): List<String> {
        val problems = mutableListOf<String>()
        val unknown = baseline.mustStayCorrect.filter { corpus.case(it) == null }
        if (unknown.isNotEmpty()) problems += "tag baseline lists case ids not in the tag corpus: ${unknown.joinToString(", ")}"
        val byId = result.entries.associateBy { it.caseId }
        baseline.mustStayCorrect.filter { it !in unknown }.forEach { id ->
            val entry = byId[id]
            when {
                entry == null -> problems += "baseline case $id is missing from the replay"
                entry.replayClass != TagReplayClass.CORRECT ->
                    problems += "baseline case $id is ${entry.replayClass}: ${entry.reasonCodes.joinToString(",").ifEmpty { "-" }}"
            }
        }
        val unsafe = result.of(TagReplayClass.UNSAFE)
        if (unsafe.size > baseline.maxUnsafe) {
            problems += "UNSAFE cases ${unsafe.size} exceed maxUnsafe ${baseline.maxUnsafe}: ${unsafe.joinToString(", ") { it.caseId }}"
        }
        val unsafeReal = unsafe.filter { it.group == TagCaseGroup.REAL_ENTRY }
        if (unsafeReal.size > baseline.maxUnsafeRealEntry) {
            problems += "REAL_ENTRY UNSAFE cases ${unsafeReal.size} exceed maxUnsafeRealEntry ${baseline.maxUnsafeRealEntry}: " +
                unsafeReal.joinToString(", ") { it.caseId }
        }
        return problems
    }
}
