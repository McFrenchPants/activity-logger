package com.mcfrenchpants.activityledger.core.domain.temporal

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Deterministically resolves the duration phrase extracted from an utterance ("for half an
 * hour", "about 30 minutes", "two hours", "an hour and a half") into whole minutes.
 *
 * - Pure and stateless: no clock, no I/O, no logging. A duration is never a time; this never
 *   looks at the capture instant.
 * - Leading hedges and fillers are ignored, repeatedly: `for`, `about`, `around`, `roughly`,
 *   `spent`, `like` ("Spent 40 minutes", "for about 30 minutes"). Trailing `.`, `,`, `!`, `?`
 *   are ignored; matching is case-insensitive.
 * - Quantities: digits with an optional decimal part ("1.5"), `a` / `an`, and the number words
 *   one..twelve, twenty, thirty, forty, forty-five (also "forty five"), fifty, sixty.
 * - Forms (after the fillers): `half an hour` / `half hour` (30); `a quarter of an hour` /
 *   `quarter of an hour` / `quarter hour` (15); `<q> minute(s)|min(s)`; `<q> hour(s)|hr(s)`;
 *   `<q> hour(s) and a half` and `<q> and a half hours` (+30 minutes); `<q> hour(s) [and] <q>
 *   minute(s)`.
 * - Anything else -- a range ("30 to 40 minutes", "30-40 minutes"), extra words ("or so"), an
 *   unknown unit -- is unreadable and gives null; nothing is guessed.
 * - The result is rounded to whole minutes (half up). Zero, or more than 24 hours, is null.
 */
object DurationResolver {

    /** Longest duration accepted, in minutes (24 hours). */
    const val MAX_MINUTES: Int = 24 * 60

    private val LEADING_FILLERS = setOf("for", "about", "around", "roughly", "spent", "like")

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "forty five" to 45,
        "fifty" to 50, "sixty" to 60,
    )

    // Longer alternatives first so "forty five" is not read as "forty".
    private val QUANTITY: String = "(\\d+(?:\\.\\d+)?|" +
        NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")"
    private const val HOURS = "(?:hours?|hrs?)"
    private const val MINUTES = "(?:minutes?|mins?)"
    private const val HALF = "and a half"

    private val HALF_HOUR = Regex("half (?:an )?hour")
    private val QUARTER_HOUR = Regex("(?:a )?quarter (?:of an )?hour")
    private val MINUTES_ONLY = Regex("$QUANTITY ?$MINUTES")
    private val HOURS_HALF_AFTER = Regex("$QUANTITY ?$HOURS(?: $HALF)?")
    private val HALF_BEFORE_HOURS = Regex("$QUANTITY $HALF $HOURS")
    private val HOURS_AND_MINUTES = Regex("$QUANTITY ?$HOURS (?:and )?$QUANTITY ?$MINUTES")

    /**
     * The duration [text] states, in whole minutes, or null when [text] is null, blank or not a
     * duration this resolver can read.
     */
    fun resolve(text: String?): Int? {
        if (text == null) return null
        val words = normalize(text) ?: return null
        val minutes: BigDecimal = when {
            HALF_HOUR.matches(words) -> BigDecimal(30)
            QUARTER_HOUR.matches(words) -> BigDecimal(15)
            else -> MINUTES_ONLY.matchEntire(words)?.let { quantity(it.groupValues[1]) }
                ?: HOURS_AND_MINUTES.matchEntire(words)?.let { m ->
                    val h = quantity(m.groupValues[1]) ?: return null
                    val min = quantity(m.groupValues[2]) ?: return null
                    h * SIXTY + min
                }
                ?: HALF_BEFORE_HOURS.matchEntire(words)?.let { m ->
                    quantity(m.groupValues[1])?.let { it * SIXTY + THIRTY }
                }
                ?: HOURS_HALF_AFTER.matchEntire(words)?.let { m ->
                    val h = quantity(m.groupValues[1]) ?: return null
                    h * SIXTY + if (words.endsWith(HALF)) THIRTY else BigDecimal.ZERO
                }
                ?: return null
        }
        val rounded = minutes.setScale(0, RoundingMode.HALF_UP)
        if (rounded.signum() <= 0 || rounded > BigDecimal(MAX_MINUTES)) return null
        return rounded.toInt()
    }

    private val SIXTY = BigDecimal(60)
    private val THIRTY = BigDecimal(30)

    /** Lowercased, single-spaced, trailing punctuation and leading fillers removed; null if nothing is left. */
    private fun normalize(text: String): String? {
        var tokens = text.lowercase(Locale.ROOT)
            .trim()
            .trimEnd('.', ',', '!', '?', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        while (tokens.isNotEmpty() && tokens.first() in LEADING_FILLERS) tokens = tokens.drop(1)
        return tokens.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun quantity(token: String): BigDecimal? =
        NUMBER_WORDS[token]?.let { BigDecimal(it) } ?: token.toBigDecimalOrNull()
}
