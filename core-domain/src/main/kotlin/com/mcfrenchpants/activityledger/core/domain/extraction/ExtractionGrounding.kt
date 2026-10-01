package com.mcfrenchpants.activityledger.core.domain.extraction

import java.util.Locale

/**
 * The result of [ExtractionGrounding.ground].
 *
 * @property candidate The extraction with any ungrounded time or duration words set to null;
 *   every other field is unchanged.
 * @property timeDropped True when [ExtractionCandidate.temporalExpression] was non-null and was
 *   removed because the sentence does not contain it.
 * @property durationDropped True when [ExtractionCandidate.durationExpression] was non-null and
 *   was removed because the sentence does not contain it (or only as part of "... ago").
 */
data class GroundedExtraction(
    val candidate: ExtractionCandidate,
    val timeDropped: Boolean,
    val durationDropped: Boolean,
)

/**
 * Grounding guard for the time and duration words of an extraction (TG1.4b rule 7).
 *
 * The model sometimes invents time or duration wording that the user never said ("Spent 40
 * minutes mowing the lawn" -> time "yesterday"; "Mowed about an hour ago." -> duration "for an
 * hour"). Those words would silently move the entry to another day or give it a wrong length.
 * This guard keeps them only when they are actually in the captured sentence.
 *
 * **Grounded** means: after both texts are lowercased ([Locale.ROOT]) and every run of
 * characters that are not letters or digits is collapsed to one word break, the expression's
 * words appear as a contiguous word sequence in the raw text's words.
 * - A **time** expression must appear as said.
 * - A **duration** expression is first stripped of its leading qualifier words
 *   ([DURATION_LEADING_WORDS]: "for about 30 minutes" -> "30 minutes", "Spent 40 minutes" ->
 *   "40 minutes"), since the model tends to add "for"; what remains must be non-empty and must
 *   appear. In addition, an occurrence immediately followed by [AGO] in the raw text does not
 *   count: "an hour ago" is a time, not a duration.
 * An expression with no letters or digits is never grounded. Subject and action words are NOT
 * checked here (they are resolved, not trusted, by the tagging policy).
 *
 * Pure: no I/O, no clock, no logging.
 */
object ExtractionGrounding {

    /** Leading words a duration may carry that the sentence need not contain ("for an hour" vs "an hour"). */
    val DURATION_LEADING_WORDS: Set<String> = setOf("for", "about", "around", "roughly", "spent")

    /** The word that turns a following-on amount into a point in time ("an hour ago"). */
    const val AGO: String = "ago"

    /** [candidate] with its time and/or duration words removed when [rawText] does not contain them. */
    fun ground(candidate: ExtractionCandidate, rawText: String): GroundedExtraction {
        val raw = words(rawText)
        val time = candidate.temporalExpression
        val duration = candidate.durationExpression
        val timeDropped = time != null && !timeGrounded(time, raw)
        val durationDropped = duration != null && !durationGrounded(duration, raw)
        return GroundedExtraction(
            candidate = candidate.copy(
                temporalExpression = if (timeDropped) null else time,
                durationExpression = if (durationDropped) null else duration,
            ),
            timeDropped = timeDropped,
            durationDropped = durationDropped,
        )
    }

    /** True when the time [expression] appears as contiguous words in [raw]. */
    private fun timeGrounded(expression: String, raw: List<String>): Boolean {
        val e = words(expression)
        return e.isNotEmpty() && occurrences(e, raw).any()
    }

    /**
     * True when the duration [expression], with its leading [DURATION_LEADING_WORDS] removed,
     * is not empty and appears in [raw] at a place not immediately followed by [AGO].
     */
    private fun durationGrounded(expression: String, raw: List<String>): Boolean {
        val e = words(expression).dropWhile { it in DURATION_LEADING_WORDS }
        if (e.isEmpty()) return false
        return occurrences(e, raw).any { start -> raw.getOrNull(start + e.size) != AGO }
    }

    /** Start indexes in [raw] where [needle] appears as a contiguous word sequence. */
    private fun occurrences(needle: List<String>, raw: List<String>): Sequence<Int> =
        (0..raw.size - needle.size).asSequence().filter { start -> raw.subList(start, start + needle.size) == needle }

    /** Lowercase words: maximal runs of letters and digits. */
    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in text.lowercase(Locale.ROOT)) {
            if (Character.isLetterOrDigit(c)) {
                current.append(c)
            } else if (current.isNotEmpty()) {
                out.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}
