package com.mcfrenchpants.activityledger.core.testing.corpus

/**
 * Renders a [TagReplayResult] as a Markdown report and as a short console summary. Pure functions.
 *
 * Neither ever contains model output: a [TagReplayResult] holds ids, codes, booleans and numbers
 * only, and the only text taken from elsewhere is the recording's provenance header and, in the
 * Markdown, the committed corpus sentence and expected tags of a case (synthetic or
 * owner-approved; docs/SEMANTIC_CORPUS.md section 10). The console summary carries counts, case
 * ids and codes only.
 */
object TagReport {

    private val CLASSES = TagReplayClass.entries

    /** Full Markdown report. [corpus] supplies case sentences and expected tags for the case rows. */
    fun markdown(result: TagReplayResult, corpus: TagCorpus): String = buildString {
        val rec = result.provenance
        appendLine("# Tag corpus replay: ${rec.source}")
        appendLine()
        SemanticReport.banner(rec.source)?.let {
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
        appendLine("| tagCorpusSha256 | `${rec.tagCorpusSha256}` |")
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

        appendLine("## Unsafe checks")
        appendLine()
        appendLine("Number of cases failing each check (a case may fail several).")
        appendLine()
        appendLine("| Check | Cases |")
        appendLine("|---|---|")
        result.unsafeCheckCounts.forEach { (check, n) -> appendLine("| $check | $n |") }
        appendLine()

        appendLine("## Secondary diagnostics")
        appendLine()
        val answered = result.entries.filter { it.timeOk != null }
        appendLine("- Time resolves to the expected date: ${answered.count { it.timeOk == true }} of ${answered.size} answered cases.")
        appendLine("- Duration resolves to the expected minutes: ${answered.count { it.durationOk == true }} of ${answered.size} answered cases.")
        appendLine("- Subject inferred from a known pair: ${answered.count { it.subjectInferred }} cases.")
        appendLine("- Grounding guard: time words dropped in ${result.timeDroppedCount} cases, duration words dropped in ${result.durationDroppedCount} cases (not in the sentence).")
        val latencies = result.entries.mapNotNull { it.latencyMs }.sorted()
        if (latencies.isEmpty()) {
            appendLine("- Latency: not recorded.")
        } else {
            appendLine("- Latency: median ${median(latencies)} ms, max ${latencies.last()} ms (${latencies.size} cases).")
        }
        appendLine()

        listOf(
            TagReplayClass.UNSAFE to "UNSAFE (something was saved silently that should not have been)",
            TagReplayClass.NAME_MISMATCH to "NAME_MISMATCH (saved silently under an oddly named new tag; not gated)",
            TagReplayClass.SAFE_MISS to "SAFE_MISS (asked or held for review when a silent save was expected)",
            TagReplayClass.FAILED to "FAILED (no answer from the model)",
        ).forEach { (c, title) -> caseSection(title, result.of(c), corpus) }
    }

    /** Plain-text summary for the console: source, counts, case ids and codes only. */
    fun consoleSummary(result: TagReplayResult): String = buildString {
        val rec = result.provenance
        appendLine("Tag replay [${rec.source}]${SemanticReport.banner(rec.source)?.let { " $it" } ?: ""}")
        val counts = result.classCounts
        appendLine("  " + CLASSES.joinToString(", ") { "$it=${counts.getValue(it)}" } + " (total ${result.entries.size})")
        result.groupCounts.forEach { (group, byClass) ->
            if (byClass.values.sum() > 0) {
                appendLine("  $group: " + CLASSES.joinToString(", ") { "$it=${byClass.getValue(it)}" })
            }
        }
        val failing = result.unsafeCheckCounts.filterValues { it > 0 }
        if (failing.isNotEmpty()) appendLine("  unsafe checks: " + failing.entries.joinToString(", ") { "${it.key}=${it.value}" })
        appendLine("  grounding: TIME_DROPPED=${result.timeDroppedCount}, DURATION_DROPPED=${result.durationDroppedCount}")
        result.entries.filter { it.timeDropped || it.durationDropped }.forEach { e ->
            val codes = listOfNotNull("TIME_DROPPED".takeIf { e.timeDropped }, "DURATION_DROPPED".takeIf { e.durationDropped })
            appendLine("  GROUNDING ${e.caseId}: ${codes.joinToString(",")}")
        }
        listOf(TagReplayClass.UNSAFE, TagReplayClass.NAME_MISMATCH, TagReplayClass.SAFE_MISS, TagReplayClass.FAILED).forEach { c ->
            result.of(c).forEach { appendLine("  $c ${it.caseId}: ${it.outcome ?: "-"} ${it.reasonCodes.joinToString(",").ifEmpty { "-" }}") }
        }
    }

    private fun StringBuilder.caseSection(title: String, rows: List<TagReplayEntry>, corpus: TagCorpus) {
        appendLine("## $title")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("None.")
        } else {
            appendLine("| Case | Group | Class | Outcome | Codes | Resolved (subject / action) | Expected subject | Expected action | Expected outcome | Sentence |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            rows.forEach { r ->
                val case = corpus.case(r.caseId)
                val expected = case?.expected
                val resolved = if (r.subjectTag == null) "-" else
                    "${r.subjectTag}${if (r.subjectInferred) " (inferred)" else ""} / ${r.actionTag}"
                appendLine(
                    "| `${r.caseId}` | ${r.group} | ${r.replayClass} | ${r.outcome ?: "-"} | " +
                        "${r.reasonCodes.joinToString(", ").ifEmpty { "-" }} | $resolved | " +
                        "${expected?.subject?.let(::expectedTag) ?: "-"} | ${expected?.action?.let(::expectedTag) ?: "-"} | " +
                        "${expected?.let { e -> (listOf(e.outcome) + e.allowedOutcomes).joinToString(" or ") } ?: "-"} | " +
                        "${case?.rawText?.let(::cell) ?: "-"} |",
                )
            }
        }
        appendLine()
    }

    /** The expected tag from the corpus: existing ids, new names (corpus text), and "may be empty". */
    private fun expectedTag(tag: ExpectedTag): String {
        val parts = mutableListOf<String>()
        tag.acceptableExistingIds.forEach { parts += "`$it`" }
        tag.acceptableNewNames.forEach { parts += "new \"${cell(it)}\"" }
        if (tag.mayBeEmpty) parts += "may be empty"
        return parts.joinToString(" or ").ifEmpty { "-" }
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun percent(n: Int, of: Int): String =
        if (of == 0) "-" else "%.1f%%".format(java.util.Locale.ROOT, 100.0 * n / of)

    private fun median(sorted: List<Long>): Long {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
