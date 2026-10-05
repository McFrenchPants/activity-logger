package com.mcfrenchpants.activityledger.core.testing.corpus

/**
 * Renders a [QuestionReplayResult] as a Markdown report (written by QuestionRegressionGateTest
 * to `build/reports/semantic-corpus/question-device.md`) and as a short console summary. Pure
 * functions.
 *
 * Only DEVICE recordings are supported (there is no stand-in question recorder); any other source
 * is rejected with [IllegalArgumentException].
 *
 * Neither output ever contains model output: a [QuestionReplayResult] holds ids, codes, dates
 * and numbers only, and the only text taken from elsewhere is the recording's provenance header
 * and, in the Markdown, the committed (synthetic) corpus question and expectation of a case. The
 * console summary carries counts, case ids and codes only.
 */
object QuestionReport {

    private val CLASSES = QuestionReplayClass.entries

    /** Full Markdown report. [corpus] supplies the questions and expectations for the case rows. */
    fun markdown(result: QuestionReplayResult, corpus: QuestionCorpus): String = buildString {
        val rec = result.provenance
        requireDevice(rec.source)
        appendLine("# Question corpus replay: ${rec.source}")
        appendLine()
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
        appendLine("| questionCorpusSha256 | `${rec.questionCorpusSha256}` |")
        appendLine("| notes | ${cell(rec.notes ?: "-")} |")
        appendLine()

        val total = result.entries.size
        val counts = result.classCounts
        appendLine("## Totals")
        appendLine()
        appendLine("Cases: $total.")
        appendLine()
        appendLine("| Class | Count | Rate |")
        appendLine("|---|---|---|")
        CLASSES.forEach { c -> appendLine("| $c | ${counts.getValue(c)} | ${percent(counts.getValue(c), total)} |") }
        appendLine()

        appendLine("## By group")
        appendLine()
        appendLine("| Group | Cases | " + CLASSES.joinToString(" | ") + " |")
        appendLine("|---|---" + "|---".repeat(CLASSES.size) + "|")
        result.groupCounts.forEach { (group, byClass) ->
            val n = byClass.values.sum()
            if (n > 0) appendLine("| $group | $n | " + CLASSES.joinToString(" | ") { byClass.getValue(it).toString() } + " |")
        }
        appendLine()

        appendLine("## Checks")
        appendLine()
        appendLine("Number of cases with each check (a case may have several).")
        appendLine()
        appendLine("| Check | Makes the case | Cases |")
        appendLine("|---|---|---|")
        result.checkCounts.forEach { (check, n) ->
            appendLine("| $check | ${if (check.wrong) "WRONG" else "SAFE_MISS"} | $n |")
        }
        appendLine()

        val latencies = result.entries.mapNotNull { it.latencyMs }.sorted()
        appendLine("## Latency")
        appendLine()
        if (latencies.isEmpty()) {
            appendLine("Not recorded.")
        } else {
            appendLine("Median ${median(latencies)} ms, max ${latencies.last()} ms (${latencies.size} cases).")
        }
        appendLine()

        appendLine("## Cases that are not CORRECT")
        appendLine()
        val rows = result.entries.filter { it.replayClass != QuestionReplayClass.CORRECT }
        if (rows.isEmpty()) {
            appendLine("None.")
        } else {
            appendLine(
                "| Case | Group | Class | Codes | Resolved (outcome; subject / action; kind; range; date words) | " +
                    "Expected (outcome; subject / action; kind; range; date words) | Question |",
            )
            appendLine("|---|---|---|---|---|---|---|")
            rows.forEach { r ->
                val case = corpus.case(r.caseId)
                appendLine(
                    "| `${r.caseId}` | ${r.group} | ${r.replayClass} | ${r.reasonCodes.joinToString(", ").ifEmpty { "-" }} | " +
                        "${resolved(r)} | ${case?.expected?.let(::expected) ?: "-"} | ${case?.question?.let(::cell) ?: "-"} |",
                )
            }
        }
        appendLine()
    }

    /** Plain-text summary for the console: source, counts, case ids and codes only. */
    fun consoleSummary(result: QuestionReplayResult): String = buildString {
        val rec = result.provenance
        requireDevice(rec.source)
        appendLine("Question replay [${rec.source}]")
        val counts = result.classCounts
        appendLine("  " + CLASSES.joinToString(", ") { "$it=${counts.getValue(it)}" } + " (total ${result.entries.size})")
        result.groupCounts.forEach { (group, byClass) ->
            if (byClass.values.sum() > 0) {
                appendLine("  $group: " + CLASSES.joinToString(", ") { "$it=${byClass.getValue(it)}" })
            }
        }
        val failing = result.checkCounts.filterValues { it > 0 }
        if (failing.isNotEmpty()) appendLine("  checks: " + failing.entries.joinToString(", ") { "${it.key}=${it.value}" })
        listOf(QuestionReplayClass.WRONG, QuestionReplayClass.SAFE_MISS, QuestionReplayClass.FAILED).forEach { c ->
            result.of(c).forEach { appendLine("  $c ${it.caseId}: ${it.outcome ?: "-"} ${it.reasonCodes.joinToString(",").ifEmpty { "-" }}") }
        }
    }

    private fun requireDevice(source: RecordingSource) {
        require(source == RecordingSource.DEVICE) {
            "question reports support DEVICE recordings only (there is no stand-in question recorder); got $source"
        }
    }

    private fun resolved(r: QuestionReplayEntry): String {
        if (r.outcome == null) return "-"
        val subject = r.subjectId?.let { "`$it`${if (r.subjectExact == false) " (closest)" else ""}" } ?: "unset"
        val action = r.actionId?.let { "`$it`${if (r.actionExact == false) " (closest)" else ""}" } ?: "unset"
        return "${r.outcome}; $subject / $action; ${r.kind ?: "-"}; ${r.range ?: "-"}; ${r.dateWords ?: "-"}"
    }

    private fun expected(e: ExpectedQuestion): String {
        fun ids(list: List<String>) = if (list.isEmpty()) "unset" else list.joinToString(" or ") { "`$it`" }
        val kinds = e.acceptableKinds.joinToString(" or ")
        val must = if (e.mustNotMatch.isEmpty()) "" else "; never ${e.mustNotMatch.joinToString(", ") { "`$it`" }}"
        return "${e.outcome}; ${ids(e.subjectIds)} / ${ids(e.actionIds)}; $kinds; ${e.range.toDates()}; ${e.dateWords}$must"
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun percent(n: Int, of: Int): String =
        if (of == 0) "-" else "%.1f%%".format(java.util.Locale.ROOT, 100.0 * n / of)

    private fun median(sorted: List<Long>): Long {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
