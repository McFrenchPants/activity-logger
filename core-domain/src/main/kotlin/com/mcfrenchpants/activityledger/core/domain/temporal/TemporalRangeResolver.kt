package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.util.Locale

/**
 * Deterministically resolves the date-window words of a question ("in August", "this year",
 * "last month", "since June", "the last 30 days") into an Explore [DateRangeSelection].
 *
 * - Stateless and clock-free: `today` is the caller's local date; no zone, no I/O.
 * - The words are untrusted (they may come from a model); only these rules turn them into
 *   dates. Words matching no rule are [TemporalRange.Unrecognised]; nothing is guessed.
 * - Ranges never extend past today: a computed end after today is clamped to today, and a
 *   window starting after today is [TemporalRange.Future].
 * - "last week/month/year" (without "the") is the previous calendar period; "the last week" and
 *   "past week" are rolling windows ending today.
 *
 * Implemented, like [TemporalResolver], as an ordered list of phrase rules over the normalized
 * expression; the first matching rule wins.
 */
class TemporalRangeResolver {

    fun resolve(expression: String?, today: LocalDate, firstDayOfWeek: DayOfWeek): TemporalRange {
        if (expression == null || expression.isBlank()) return TemporalRange.NoWindow
        val normalized = NameNormalizer.normalize(expression).replace('’', '\'')
        if (normalized.isEmpty()) return TemporalRange.NoWindow
        if (normalized in PLACEHOLDERS || expression.trim().lowercase(Locale.ROOT) in PLACEHOLDERS) {
            return TemporalRange.NoWindow
        }
        val ctx = Context(today, firstDayOfWeek)
        // h. Explicit spans are matched before filler stripping.
        SPAN.matchEntire(normalized)?.let { return span(it.groupValues[1], it.groupValues[2], ctx) }
        val stripped = stripFiller(normalized)
        for (rule in RULES) {
            val match = rule.matcher.matchEntire(stripped) ?: continue
            return rule.resolve(match, ctx)
        }
        // j. Anything else.
        return TemporalRange.Unrecognised
    }

    private class Context(val today: LocalDate, val firstDayOfWeek: DayOfWeek) {
        val startOfThisWeek: LocalDate =
            today.minusDays(((today.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong())
    }

    /** One phrase family: a matcher over the whole normalized expression plus its resolution. */
    private class PhraseRule(
        val matcher: Regex,
        val resolve: (MatchResult, Context) -> TemporalRange,
    )

    /** One side of an explicit span: a month (optionally with a year) or a year alone. */
    private data class SpanSide(val month: Month?, val year: Int?)

    private companion object {
        /** Same placeholder words as [TemporalResolver] (copied, not shared, on purpose). */
        private val PLACEHOLDERS = setOf(
            "unresolved", "unknown", "none", "null", "n/a", "na", "undefined", "unspecified",
            "not specified", "not given", "nothing",
        )

        private val LEADING_FILLERS = listOf("in ", "during ", "over ", "within ", "for ", "on ", "of ")

        private const val MIN_YEAR = 1900

        private const val WORD_NUMBER =
            "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
        private val WORD_NUMBERS = WORD_NUMBER.split('|')
            .mapIndexed { index, word -> word to index + 1 }.toMap()
        private const val QUANTITY = "(\\d{1,3}|$WORD_NUMBER|an|a|(?:a )?couple(?: of)?)"

        private val MONTHS: Map<String, Month> = buildMap {
            Month.values().forEach { put(it.name.lowercase(Locale.ROOT), it) }
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
        private const val OPTIONAL_YEAR = "(?:,? (\\d{4}))?"

        private val WEEKDAYS: Map<String, DayOfWeek> =
            DayOfWeek.values().associateBy { it.name.lowercase(Locale.ROOT) }
        private val WEEKDAY_ALT = WEEKDAYS.keys.joinToString("|")

        private val SPAN = Regex("(?:from|between) (.+?) (?:to|and|through|until|till) (.+)")

        private val MONTH_ONLY = Regex("($MONTH_ALT)\\.?")
        private val MONTH_WITH_YEAR = Regex("($MONTH_ALT)\\.?,? (?:of )?(\\d{4})")
        private val YEAR_ONLY = Regex("(?:the year )?(\\d{4})")
        private val WEEKDAY = Regex("(?:last )?($WEEKDAY_ALT)")
        private val MONTH_DAY = Regex("($MONTH_ALT)\\.? $DAY$OPTIONAL_YEAR")
        private val DAY_MONTH = Regex("(?:the )?$DAY (?:of )?($MONTH_ALT)\\.?$OPTIONAL_YEAR")

        private fun stripFiller(s: String): String {
            for (filler in LEADING_FILLERS) {
                if (s.startsWith(filler)) return s.removePrefix(filler)
            }
            return s
        }

        /** Parses a quantity group; returns null if out of range. */
        private fun quantity(token: String): Long? = when {
            token == "a" || token == "an" -> 1L
            token.startsWith("a couple") || token.startsWith("couple") -> 2L
            token in WORD_NUMBERS -> WORD_NUMBERS.getValue(token).toLong()
            else -> token.toLongOrNull()?.takeIf { it in 1L..999L }
        }

        /** A four-digit year, or null when it is before [MIN_YEAR]. */
        private fun year(token: String): Int? = token.toIntOrNull()?.takeIf { it >= MIN_YEAR }

        private fun preset(kind: DateRangePreset): TemporalRange =
            TemporalRange.Resolved(DateRangeSelection.Preset(kind))

        /** [start, end] clamped to today; Future when it starts after today. */
        private fun window(start: LocalDate, end: LocalDate, label: RangeLabel?, ctx: Context): TemporalRange {
            if (start.isAfter(ctx.today)) return TemporalRange.Future
            val clampedEnd = if (end.isAfter(ctx.today)) ctx.today else end
            return TemporalRange.Resolved(DateRangeSelection.Custom(start, clampedEnd, label))
        }

        private fun monthWindow(month: YearMonth, ctx: Context): TemporalRange =
            window(month.atDay(1), month.atEndOfMonth(), RangeLabel.Month(month), ctx)

        private fun yearWindow(year: Int, ctx: Context): TemporalRange =
            window(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), RangeLabel.Year(year), ctx)

        /** The most recent [month] that has started on or before today. */
        private fun mostRecentMonth(month: Month, ctx: Context): YearMonth {
            val year = if (month.value <= ctx.today.monthValue) ctx.today.year else ctx.today.year - 1
            return YearMonth.of(year, month)
        }

        /**
         * The day a bare weekday names: the most recent such day strictly before today, so the
         * same weekday as today means 7 days ago (the same rule as [TemporalResolver]).
         */
        private fun previousWeekday(dayName: String, ctx: Context): LocalDate {
            val target = WEEKDAYS.getValue(dayName)
            val back = ((ctx.today.dayOfWeek.value - target.value + 7) % 7).let { if (it == 0) 7 else it }
            return ctx.today.minusDays(back.toLong())
        }

        /**
         * A month-day date. With a year: that date (null when invalid or the year is before
         * [MIN_YEAR]; it may lie after today). Without: the most recent such date not after today,
         * walking back up to 8 years for a leap day; null when the day can never exist.
         */
        private fun monthDayDate(monthToken: String, dayToken: String, yearToken: String, ctx: Context): LocalDate? {
            val month = MONTHS[monthToken] ?: return null
            val day = dayToken.toInt()
            if (day < 1 || day > month.maxLength()) return null
            if (yearToken.isNotEmpty()) {
                val year = year(yearToken) ?: return null
                if (day > month.length(Year.isLeap(year.toLong()))) return null
                return LocalDate.of(year, month, day)
            }
            var year = ctx.today.year
            repeat(9) {
                if (day <= month.length(Year.isLeap(year.toLong()))) {
                    val date = LocalDate.of(year, month, day)
                    if (!date.isAfter(ctx.today)) return date
                }
                year--
            }
            return null
        }

        private fun startOfLastWeek(ctx: Context): LocalDate = ctx.startOfThisWeek.minusDays(7)
        private fun lastMonth(ctx: Context): YearMonth = YearMonth.from(ctx.today).minusMonths(1)

        /** b. A rolling window of [n] units ending today; presets where they match exactly. */
        private fun rolling(n: Long, unitWord: String, ctx: Context): TemporalRange {
            val unit = unitWord.first()
            val kind = when {
                unit == 'd' && n == 7L -> DateRangePreset.LAST_7_DAYS
                unit == 'w' && n == 1L -> DateRangePreset.LAST_7_DAYS
                unit == 'd' && n == 30L -> DateRangePreset.LAST_30_DAYS
                unit == 'm' && n == 12L -> DateRangePreset.LAST_12_MONTHS
                unit == 'y' && n == 1L -> DateRangePreset.LAST_12_MONTHS
                else -> null
            }
            if (kind != null) return preset(kind)
            val d = ctx.today
            val start = when (unit) {
                'd' -> d.minusDays(n - 1)
                'w' -> d.minusDays(7 * n - 1)
                'm' -> d.minusMonths(n).plusDays(1)
                else -> d.minusYears(n).plusDays(1)
            }
            return window(start, d, null, ctx)
        }

        /** g. The first day of the X in "since X", or null when X is not understood. */
        private fun sinceStart(x: String, ctx: Context): LocalDate? {
            when (x) {
                "yesterday" -> return ctx.today.minusDays(1)
                "last week" -> return startOfLastWeek(ctx)
                "last month" -> return lastMonth(ctx).atDay(1)
                "last year" -> return LocalDate.of(ctx.today.year - 1, 1, 1)
            }
            MONTH_ONLY.matchEntire(x)?.let { return mostRecentMonth(MONTHS.getValue(it.groupValues[1]), ctx).atDay(1) }
            MONTH_WITH_YEAR.matchEntire(x)?.let { m ->
                val year = year(m.groupValues[2]) ?: return null
                return LocalDate.of(year, MONTHS.getValue(m.groupValues[1]), 1)
            }
            YEAR_ONLY.matchEntire(x)?.let { m ->
                val year = year(m.groupValues[1]) ?: return null
                return LocalDate.of(year, 1, 1)
            }
            WEEKDAY.matchEntire(x)?.let { return previousWeekday(it.groupValues[1], ctx) }
            MONTH_DAY.matchEntire(x)?.let { m ->
                return monthDayDate(m.groupValues[1], m.groupValues[2], m.groupValues[3], ctx)
            }
            DAY_MONTH.matchEntire(x)?.let { m ->
                return monthDayDate(m.groupValues[2], m.groupValues[1], m.groupValues[3], ctx)
            }
            return null
        }

        private fun spanSide(text: String): SpanSide? {
            MONTH_WITH_YEAR.matchEntire(text)?.let { m ->
                val year = year(m.groupValues[2]) ?: return null
                return SpanSide(MONTHS.getValue(m.groupValues[1]), year)
            }
            MONTH_ONLY.matchEntire(text)?.let { return SpanSide(MONTHS.getValue(it.groupValues[1]), null) }
            YEAR_ONLY.matchEntire(text)?.let { m ->
                val year = year(m.groupValues[1]) ?: return null
                return SpanSide(null, year)
            }
            return null
        }

        /** h. "(from|between) X (to|and|through|until|till) Y". */
        private fun span(fromText: String, toText: String, ctx: Context): TemporalRange {
            val from = spanSide(fromText) ?: return TemporalRange.Unrecognised
            val to = spanSide(toText) ?: return TemporalRange.Unrecognised
            val fromYear: Int
            val toYear: Int
            if (from.year == null && to.year == null) {
                // Both are months without a year (a year-only side always has a year).
                val toMonth = mostRecentMonth(to.month!!, ctx)
                toYear = toMonth.year
                fromYear = if (from.month!!.value <= to.month.value) toYear else toYear - 1
            } else {
                fromYear = from.year ?: to.year!!
                toYear = to.year ?: from.year!!
            }
            val start = from.month?.let { YearMonth.of(fromYear, it).atDay(1) } ?: LocalDate.of(fromYear, 1, 1)
            val end = to.month?.let { YearMonth.of(toYear, it).atEndOfMonth() } ?: LocalDate.of(toYear, 12, 31)
            if (start.isAfter(end)) return TemporalRange.Unrecognised
            return window(start, end, null, ctx)
        }

        private val RULES: List<PhraseRule> = listOf(
            // a. All time.
            PhraseRule(Regex("ever|all time|of all time|ever before|at all")) { _, _ ->
                preset(DateRangePreset.ALL_TIME)
            },
            // b. Rolling windows ending today.
            PhraseRule(Regex("(?:the )?(?:last|past) $QUANTITY (days?|weeks?|months?|years?)")) { m, ctx ->
                val n = quantity(m.groupValues[1]) ?: return@PhraseRule TemporalRange.Unrecognised
                rolling(n, m.groupValues[2], ctx)
            },
            PhraseRule(
                Regex("(?:the )?past (day|week|month|year)|the last (day|week|month|year)|this past (week|month|year)"),
            ) { m, ctx ->
                val unit = m.groupValues.drop(1).first { it.isNotEmpty() }
                rolling(1, unit, ctx)
            },
            // c. Calendar periods.
            PhraseRule(Regex("today")) { _, ctx -> window(ctx.today, ctx.today, null, ctx) },
            PhraseRule(Regex("yesterday")) { _, ctx ->
                val y = ctx.today.minusDays(1)
                window(y, y, null, ctx)
            },
            PhraseRule(Regex("this week")) { _, ctx -> window(ctx.startOfThisWeek, ctx.today, null, ctx) },
            PhraseRule(Regex("last week")) { _, ctx ->
                val start = startOfLastWeek(ctx)
                window(start, start.plusDays(6), null, ctx)
            },
            PhraseRule(Regex("this month")) { _, ctx -> monthWindow(YearMonth.from(ctx.today), ctx) },
            PhraseRule(Regex("last month")) { _, ctx -> monthWindow(lastMonth(ctx), ctx) },
            PhraseRule(Regex("this year")) { _, ctx -> yearWindow(ctx.today.year, ctx) },
            PhraseRule(Regex("last year")) { _, ctx -> yearWindow(ctx.today.year - 1, ctx) },
            PhraseRule(Regex("next .+|tomorrow(?: .*)?|later")) { _, _ -> TemporalRange.Future },
            // d. A named month with no year: the most recent one that has started.
            PhraseRule(MONTH_ONLY) { m, ctx ->
                monthWindow(mostRecentMonth(MONTHS.getValue(m.groupValues[1]), ctx), ctx)
            },
            // e. A named month with a year.
            PhraseRule(MONTH_WITH_YEAR) { m, ctx ->
                val year = year(m.groupValues[2]) ?: return@PhraseRule TemporalRange.Unrecognised
                monthWindow(YearMonth.of(year, MONTHS.getValue(m.groupValues[1])), ctx)
            },
            // f. A year alone.
            PhraseRule(YEAR_ONLY) { m, ctx ->
                val year = year(m.groupValues[1]) ?: return@PhraseRule TemporalRange.Unrecognised
                yearWindow(year, ctx)
            },
            // g. Since X, through today.
            PhraseRule(Regex("since (.+)")) { m, ctx ->
                val start = sinceStart(m.groupValues[1], ctx) ?: return@PhraseRule TemporalRange.Unrecognised
                window(start, ctx.today, null, ctx)
            },
            // i. Single days.
            PhraseRule(WEEKDAY) { m, ctx ->
                val day = previousWeekday(m.groupValues[1], ctx)
                window(day, day, null, ctx)
            },
            PhraseRule(MONTH_DAY) { m, ctx ->
                singleDay(monthDayDate(m.groupValues[1], m.groupValues[2], m.groupValues[3], ctx), ctx)
            },
            PhraseRule(DAY_MONTH) { m, ctx ->
                singleDay(monthDayDate(m.groupValues[2], m.groupValues[1], m.groupValues[3], ctx), ctx)
            },
        )

        private fun singleDay(date: LocalDate?, ctx: Context): TemporalRange =
            if (date == null) TemporalRange.Unrecognised else window(date, date, null, ctx)
    }
}
