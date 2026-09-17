package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministically resolves the temporal phrase extracted from an utterance ("yesterday",
 * "this morning", "Saturday", "two days ago") into an occurrence instant and a precision.
 *
 * - Stateless and clock-free: `capturedAt` is "now". All calendar arithmetic uses `zoneId`.
 * - Never fabricates precision: calendar-level phrases are [TimePrecision.DATE_ONLY], fuzzy
 *   phrases [TimePrecision.APPROXIMATE], and only an explicit clock time is
 *   [TimePrecision.EXACT].
 * - Unknown phrases are [TemporalResolution.Unresolvable]; forward-looking phrases are
 *   [TemporalResolution.Future]. A final guard guarantees no Resolved result is after
 *   `capturedAt`.
 *
 * Implemented as an ordered list of phrase rules over the normalized expression; the first
 * matching rule wins, so more specific rules come first. A new phrase or language is an
 * additional rule.
 */
class TemporalResolver {

    fun resolve(expression: String?, capturedAt: Instant, zoneId: ZoneId): TemporalResolution {
        if (expression == null || expression.isBlank()) {
            return TemporalResolution.Resolved(capturedAt, TimePrecision.INFERRED_NOW)
        }
        val (normalized, hedged) = normalize(expression)
        if (normalized.isEmpty()) {
            // Punctuation-only text is not "no time given"; don't treat it as now.
            return TemporalResolution.Unresolvable
        }
        val context = Context(capturedAt, zoneId)
        for (rule in RULES) {
            val match = rule.matcher.matchEntire(normalized) ?: continue
            return guard(soften(rule.resolve(match, context), hedged), capturedAt)
        }
        return TemporalResolution.Unresolvable
    }

    /** "about 3pm" names a clock time but not an exact one (ADR-018). */
    private fun soften(result: TemporalResolution, hedged: Boolean): TemporalResolution =
        if (hedged && result is TemporalResolution.Resolved && result.precision == TimePrecision.EXACT) {
            result.copy(precision = TimePrecision.APPROXIMATE)
        } else {
            result
        }

    private fun guard(result: TemporalResolution, capturedAt: Instant): TemporalResolution =
        if (result is TemporalResolution.Resolved && result.occurredAt.isAfter(capturedAt)) {
            TemporalResolution.Future
        } else {
            result
        }

    private class Context(val now: Instant, val zone: ZoneId) {
        val nowLocal: ZonedDateTime = now.atZone(zone)
        val today: LocalDate = nowLocal.toLocalDate()
        val yesterday: LocalDate = today.minusDays(1)

        fun startOf(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()
        fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()
    }

    /** One phrase family: a matcher over the whole normalized expression plus its resolution. */
    private class PhraseRule(
        val matcher: Regex,
        val resolve: (MatchResult, Context) -> TemporalResolution,
    )

    /** Part-of-day band [start, end) with an anchor used when the exact time is unknown. */
    private enum class Band(val start: LocalTime, val end: LocalTime?, val anchor: LocalTime) {
        MORNING(LocalTime.of(5, 0), LocalTime.of(12, 0), LocalTime.of(9, 0)),
        AFTERNOON(LocalTime.of(12, 0), LocalTime.of(17, 0), LocalTime.of(15, 0)),
        EVENING(LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(19, 0)),

        /** Ends at midnight (null end); overlaps EVENING on purpose. */
        TONIGHT(LocalTime.of(17, 0), null, LocalTime.of(21, 0)),
    }

    private companion object {
        private val LEADING_FILLERS = listOf("on ", "at ", "about ", "around ", "approximately ")
        private val HEDGES = setOf("about ", "around ", "approximately ")

        private const val WORD_NUMBER =
            "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
        private val WORD_NUMBERS = WORD_NUMBER.split('|')
            .mapIndexed { index, word -> word to index + 1 }.toMap()

        private const val APPROX = "(?:(?:about|around|approximately) )?"
        private const val QUANTITY = "(\\d{1,3}|$WORD_NUMBER|an|a|a couple(?: of)?)"

        private val MONTHS: Map<String, Month> = buildMap {
            Month.values().forEach { put(it.name.lowercase(java.util.Locale.ROOT), it) }
            put("jan", Month.JANUARY)
            put("feb", Month.FEBRUARY)
            put("mar", Month.MARCH)
            put("apr", Month.APRIL)
            put("jun", Month.JUNE)
            put("jul", Month.JULY)
            put("aug", Month.AUGUST)
            put("sep", Month.SEPTEMBER)
            put("sept", Month.SEPTEMBER)
            put("oct", Month.OCTOBER)
            put("nov", Month.NOVEMBER)
            put("dec", Month.DECEMBER)
        }
        private val MONTH_ALT = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
        private const val DAY = "(\\d{1,2})(?:st|nd|rd|th)?"

        private val WEEKDAYS: Map<String, DayOfWeek> =
            DayOfWeek.values().associateBy { it.name.lowercase(java.util.Locale.ROOT) }

        /** Returns the normalized expression and whether a hedge word (about/around/approximately) was stripped. */
        private fun normalize(expression: String): Pair<String, Boolean> {
            var s = NameNormalizer.normalize(expression).replace('’', '\'')
            var hedged = false
            for (filler in LEADING_FILLERS) {
                if (s.startsWith(filler)) {
                    s = s.removePrefix(filler)
                    hedged = filler in HEDGES
                    break
                }
            }
            return s to hedged
        }

        /** Parses a quantity group; returns null if out of range. */
        private fun quantity(token: String): Long? = when {
            token == "a" || token == "an" -> 1L
            token.startsWith("a couple") -> 2L
            token in WORD_NUMBERS -> WORD_NUMBERS.getValue(token).toLong()
            else -> token.toLongOrNull()?.takeIf { it in 1L..999L }
        }

        private fun resolved(instant: Instant, precision: TimePrecision) =
            TemporalResolution.Resolved(instant, precision)

        private fun band(word: String): Band = when (word) {
            "morning" -> Band.MORNING
            "afternoon" -> Band.AFTERNOON
            "evening" -> Band.EVENING
            else -> Band.TONIGHT
        }

        private fun thisBand(band: Band, ctx: Context): TemporalResolution {
            val nowTime = ctx.nowLocal.toLocalTime()
            if (nowTime.isBefore(band.start)) return TemporalResolution.Future
            val insideBand = band.end == null || nowTime.isBefore(band.end)
            return if (insideBand && nowTime.isBefore(band.anchor)) {
                resolved(ctx.now, TimePrecision.APPROXIMATE)
            } else {
                resolved(ctx.at(ctx.today, band.anchor), TimePrecision.APPROXIMATE)
            }
        }

        private fun monthDate(monthToken: String, dayToken: String, ctx: Context): TemporalResolution {
            val month = MONTHS[monthToken] ?: return TemporalResolution.Unresolvable
            val day = dayToken.toInt()
            if (day < 1 || day > month.maxLength()) return TemporalResolution.Unresolvable
            // A leap day recurs within 8 years; walk back until the date exists and is not after today.
            var year = ctx.today.year
            repeat(9) {
                if (day <= month.length(java.time.Year.isLeap(year.toLong()))) {
                    val date = LocalDate.of(year, month, day)
                    if (!date.isAfter(ctx.today)) {
                        return resolved(ctx.startOf(date), TimePrecision.DATE_ONLY)
                    }
                }
                year--
            }
            return TemporalResolution.Unresolvable
        }

        private fun clockToday(hour: Int, minute: Int, pm: Boolean, ctx: Context): TemporalResolution {
            if (hour !in 1..12 || minute !in 0..59) return TemporalResolution.Unresolvable
            val hour24 = (hour % 12) + if (pm) 12 else 0
            val local = ctx.today.atTime(hour24, minute)
            // A clock time skipped by a DST gap never happened; don't silently shift it.
            if (ctx.zone.rules.getValidOffsets(local).isEmpty()) return TemporalResolution.Unresolvable
            val instant = local.atZone(ctx.zone).toInstant()
            return if (instant.isAfter(ctx.now)) {
                TemporalResolution.Future
            } else {
                resolved(instant, TimePrecision.EXACT)
            }
        }

        private val RULES: List<PhraseRule> = listOf(
            // 2. Immediate past.
            PhraseRule(Regex("just now|just|now|right now|a moment ago|just finished|just did it")) { _, ctx ->
                resolved(ctx.now, TimePrecision.INFERRED_NOW)
            },
            // 3. Yesterday part-of-day.
            PhraseRule(Regex("yesterday (morning|afternoon|evening)")) { m, ctx ->
                resolved(ctx.at(ctx.yesterday, band(m.groupValues[1]).anchor), TimePrecision.APPROXIMATE)
            },
            // 4. This part-of-day / tonight.
            PhraseRule(Regex("(?:earlier )?this (morning|afternoon|evening)|(tonight)")) { m, ctx ->
                val word = m.groupValues[1].ifEmpty { m.groupValues[2] }
                thisBand(band(word), ctx)
            },
            // 5. Earlier today.
            PhraseRule(Regex("earlier today")) { _, ctx ->
                val start = ctx.startOf(ctx.today)
                val half = Duration.between(start, ctx.now).dividedBy(2)
                resolved(start.plus(half), TimePrecision.APPROXIMATE)
            },
            // 6. Last night.
            PhraseRule(Regex("last night")) { _, ctx ->
                resolved(ctx.at(ctx.yesterday, LocalTime.of(21, 0)), TimePrecision.APPROXIMATE)
            },
            // 7. Today / yesterday.
            PhraseRule(Regex("today")) { _, ctx -> resolved(ctx.startOf(ctx.today), TimePrecision.DATE_ONLY) },
            PhraseRule(Regex("yesterday")) { _, ctx ->
                resolved(ctx.startOf(ctx.yesterday), TimePrecision.DATE_ONLY)
            },
            // 8. Minutes / hours ago (instant arithmetic).
            PhraseRule(Regex("${APPROX}half an hour ago")) { _, ctx ->
                resolved(ctx.now.minus(Duration.ofMinutes(30)), TimePrecision.APPROXIMATE)
            },
            PhraseRule(Regex("$APPROX$QUANTITY (minutes?|mins?|hours?) ago")) { m, ctx ->
                val n = quantity(m.groupValues[1]) ?: return@PhraseRule TemporalResolution.Unresolvable
                val duration = if (m.groupValues[2].startsWith("h")) Duration.ofHours(n) else Duration.ofMinutes(n)
                resolved(ctx.now.minus(duration), TimePrecision.APPROXIMATE)
            },
            // 9. Days / weeks ago (calendar arithmetic).
            PhraseRule(Regex("$APPROX$QUANTITY (days?|weeks?) ago")) { m, ctx ->
                val n = quantity(m.groupValues[1]) ?: return@PhraseRule TemporalResolution.Unresolvable
                val days = if (m.groupValues[2].startsWith("w")) n * 7 else n
                resolved(ctx.startOf(ctx.today.minusDays(days)), TimePrecision.DATE_ONLY)
            },
            // 10. Weekday: most recent such day strictly before today.
            PhraseRule(Regex("(?:last )?(${WEEKDAYS.keys.joinToString("|")})")) { m, ctx ->
                val target = WEEKDAYS.getValue(m.groupValues[1])
                val back = ((ctx.today.dayOfWeek.value - target.value + 7) % 7).let { if (it == 0) 7 else it }
                resolved(ctx.startOf(ctx.today.minusDays(back.toLong())), TimePrecision.DATE_ONLY)
            },
            // 11. Month-name dates.
            PhraseRule(Regex("($MONTH_ALT)\\.? $DAY")) { m, ctx ->
                monthDate(m.groupValues[1], m.groupValues[2], ctx)
            },
            PhraseRule(Regex("(?:the )?$DAY (?:of )?($MONTH_ALT)")) { m, ctx ->
                monthDate(m.groupValues[2], m.groupValues[1], ctx)
            },
            // 12. Explicit clock time today.
            PhraseRule(Regex("(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m|p\\.m)")) { m, ctx ->
                val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
                clockToday(m.groupValues[1].toInt(), minute, m.groupValues[3].startsWith("p"), ctx)
            },
            PhraseRule(Regex("(\\d{1,2})(?::(\\d{2}))?(?: o'clock)? this (morning|afternoon|evening)")) { m, ctx ->
                val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
                clockToday(m.groupValues[1].toInt(), minute, m.groupValues[3] != "morning", ctx)
            },
            // 13. Forward-looking phrases.
            PhraseRule(Regex("tomorrow(?: .*)?|next .+|later|later today")) { _, _ -> TemporalResolution.Future },
            PhraseRule(Regex("in (?:\\d{1,3}|$WORD_NUMBER|an|a|a couple(?: of)?|half an) .+")) { _, _ ->
                TemporalResolution.Future
            },
            // 14. Everything else falls through to Unresolvable.
        )
    }
}
