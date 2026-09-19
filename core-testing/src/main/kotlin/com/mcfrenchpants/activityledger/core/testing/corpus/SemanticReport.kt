package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand

/**
 * Renders a [ReplayResult] as a Markdown report and as a short console summary. Pure functions.
 *
 * The console summary carries only provenance source, counts, case ids and reason codes -- never
 * corpus sentences or model output. The Markdown may show a case's synthetic, committed corpus
 * sentence for readability, but never the model's proposed names or other model text.
 */
object SemanticReport {

    private val CLASSES = ReplayClass.entries

    /** Full Markdown report. [corpus] supplies case sentences for the miss lists (optional). */
    fun markdown(result: ReplayResult, corpus: SemanticCorpus? = null): String = buildString {
        val rec = result.recording
        appendLine("# Semantic regression replay: ${rec.source}")
        appendLine()
        banner(rec.source)?.let {
            appendLine("> **$it**")
            appendLine()
        }
        appendLine("## Provenance")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| source | ${rec.source} |")
        appendLine("| modelLabel | ${cell(rec.modelLabel)} |")
        appendLine("| deviceModel | ${cell(rec.deviceModel ?: "-")} |")
        appendLine("| interpreterVersion | ${cell(rec.interpreterVersion)} |")
        appendLine("| promptVersion | ${cell(rec.promptVersion)} |")
        appendLine("| schemaVersion | ${rec.schemaVersion} |")
        appendLine("| recordedAt | ${cell(rec.recordedAt)} |")
        appendLine("| recording formatVersion | ${rec.formatVersion} |")
        appendLine("| corpusSha256 (recorded) | `${rec.corpusSha256}` |")
        appendLine("| corpusSha256 (current) | `${result.corpusSha256}` |")
        appendLine("| corpus matches | ${if (result.corpusMatches) "yes" else "**NO - recorded against a different corpus**"} |")
        appendLine("| notes | ${cell(rec.notes ?: "-")} |")
        appendLine()

        val scored = result.entries.filter { it.replayClass != ReplayClass.NOT_RUN }
        appendLine("## Totals")
        appendLine()
        appendLine("Entries: ${result.entries.size}; scored (excluding NOT_RUN): ${scored.size}.")
        appendLine()
        appendLine("| Class | Count | Rate of scored |")
        appendLine("|---|---|---|")
        CLASSES.forEach { c ->
            val n = result.of(c).size
            val rate = if (c == ReplayClass.NOT_RUN || scored.isEmpty()) "-" else percent(n, scored.size)
            appendLine("| $c | $n | $rate |")
        }
        appendLine()

        appendLine("## By category")
        appendLine()
        appendLine("| Category | " + CLASSES.joinToString(" | ") + " |")
        appendLine("|---" + "|---".repeat(CLASSES.size) + "|")
        CorpusCategory.entries.forEach { cat ->
            val inCat = result.entries.filter { it.category == cat }
            if (inCat.isNotEmpty()) {
                appendLine("| $cat | " + CLASSES.joinToString(" | ") { c -> inCat.count { it.replayClass == c }.toString() } + " |")
            }
        }
        appendLine()

        missSection("UNSAFE_MISS (something wrong was saved)", result.of(ReplayClass.UNSAFE_MISS), corpus)
        missSection("SAFE_MISS (sent to review instead of logged)", result.of(ReplayClass.SAFE_MISS), corpus)

        appendLine("## Confidence band vs class")
        appendLine()
        appendLine("For calibrating the auto-accept policy (recorded model confidence; failures show as `none`).")
        appendLine()
        appendLine("| Band | " + CLASSES.joinToString(" | ") + " |")
        appendLine("|---" + "|---".repeat(CLASSES.size) + "|")
        (ConfidenceBand.entries.map { it.name } + "none").forEach { band ->
            val rows = result.entries.filter { (it.confidenceBand?.name ?: "none") == band }
            appendLine("| $band | " + CLASSES.joinToString(" | ") { c -> rows.count { it.replayClass == c }.toString() } + " |")
        }
        appendLine()

        appendLine("## Secondary diagnostics")
        appendLine()
        val answered = result.entries.filter { it.temporalExpressionAgrees != null }
        appendLine("- Temporal expression agreement: ${answered.count { it.temporalExpressionAgrees == true }} of ${answered.size} answered entries.")
        appendLine("- Resolution acceptable: ${answered.count { it.resolutionAcceptable == true }} of ${answered.size} answered entries.")
        val latencies = result.entries.mapNotNull { it.latencyMs }.sorted()
        if (latencies.isEmpty()) {
            appendLine("- Latency: not recorded.")
        } else {
            appendLine("- Latency: median ${median(latencies)} ms, max ${latencies.last()} ms (${latencies.size} entries).")
        }
        appendLine()

        appendLine("## NOT_RUN (model unavailable; excluded from rates)")
        appendLine()
        val notRun = result.of(ReplayClass.NOT_RUN)
        if (notRun.isEmpty()) appendLine("None.") else notRun.forEach { appendLine("- `${it.caseId}` (${it.failureKind})") }
    }

    /** Plain-text summary for the console: source, counts, case ids and reason codes only. */
    fun consoleSummary(result: ReplayResult): String = buildString {
        val rec = result.recording
        appendLine("Semantic replay [${rec.source}]${banner(rec.source)?.let { " $it" } ?: ""}")
        appendLine("  corpus matches recording: ${result.corpusMatches}")
        appendLine("  " + CLASSES.joinToString(", ") { "$it=${result.of(it).size}" } + " (total ${result.entries.size})")
        listOf(ReplayClass.UNSAFE_MISS, ReplayClass.SAFE_MISS).forEach { c ->
            result.of(c).forEach { appendLine("  $c ${it.caseId}: ${it.reasonCodes.joinToString(",")}") }
        }
        result.of(ReplayClass.NOT_RUN).forEach { appendLine("  NOT_RUN ${it.caseId}: ${it.failureKind}") }
    }

    /** The "not official" banner for [source], or null for DEVICE. */
    fun banner(source: RecordingSource): String? = when (source) {
        RecordingSource.DEVICE -> null
        RecordingSource.STAND_IN -> "STAND-IN MODEL -- NOT THE OFFICIAL RESULT"
        RecordingSource.SYNTHETIC -> "SYNTHETIC ANSWERS -- NOT A MODEL RESULT, NOT THE OFFICIAL RESULT"
    }

    private fun StringBuilder.missSection(title: String, rows: List<ScoredEntry>, corpus: SemanticCorpus?) {
        appendLine("## $title")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("None.")
        } else {
            appendLine("| Case | Category | Expected | Pipeline outcome | Reasons | Sentence |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { r ->
                val sentence = corpus?.case(r.caseId)?.rawText?.let(::cell) ?: "-"
                appendLine(
                    "| `${r.caseId}` | ${r.category} | ${r.expectedOutcome} | ${r.outcome} | " +
                        "${r.reasonCodes.joinToString(", ").ifEmpty { "-" }} | $sentence |",
                )
            }
        }
        appendLine()
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun percent(n: Int, of: Int): String = "%.1f%%".format(java.util.Locale.ROOT, 100.0 * n / of)

    private fun median(sorted: List<Long>): Long {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
