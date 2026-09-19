package com.mcfrenchpants.activityledger.ui.time

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders an occurrence's time exactly per UX_VISUAL_SPEC 4.3 (ADR-018). Pure Kotlin/java.time,
 * no Android dependency.
 *
 * | precision              | today              | other day              | other year                  |
 * |------------------------|--------------------|------------------------|-----------------------------|
 * | EXACT / INFERRED_NOW   | `Today, 3:12 PM`   | `Sep 12, 9:40 AM`      | `Sep 12, 2025, 9:40 AM`     |
 * | APPROXIMATE            | `Today, afternoon` | `Aug 23, morning`      | `Aug 23, 2025, morning`     |
 * | DATE_ONLY              | `Sat, Sep 12`      | `Sat, Sep 12`          | `Sat, Sep 12, 2025`         |
 *
 * - Only "Today" is special-cased: there is no "Yesterday". DATE_ONLY never says "Today".
 * - "Today" and "other year" are judged in [zone] (the capture's zone), for both the occurrence
 *   and [now].
 * - APPROXIMATE and DATE_ONLY never render a clock time: the user did not give one.
 * - Part of day uses the same bands as core-domain's TemporalResolver, so its anchors
 *   round-trip: morning [05:00, 12:00) (anchor 09:00), afternoon [12:00, 17:00) (anchor 15:00),
 *   evening [17:00, 21:00) (anchor 19:00), night otherwise (TemporalResolver's "last night" /
 *   "tonight" anchor 21:00 lands here).
 * - Month and weekday names come from [Locale] via java.time. The words "Today" and the
 *   part-of-day names are English, as is all app copy today.
 */
object OccurrenceTimeFormatter {

    fun format(
        occurredAt: Instant,
        precision: TimePrecision,
        zone: ZoneId,
        now: Instant,
        locale: Locale,
    ): String {
        val local = occurredAt.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val date = local.toLocalDate()
        val isToday = date == today
        val sameYear = date.year == today.year

        return when (precision) {
            TimePrecision.EXACT, TimePrecision.INFERRED_NOW -> {
                val time = local.format(DateTimeFormatter.ofPattern("h:mm a", locale))
                "${dayPrefix(isToday, sameYear, date, locale)}, $time"
            }
            TimePrecision.APPROXIMATE ->
                "${dayPrefix(isToday, sameYear, date, locale)}, ${partOfDay(local.toLocalTime())}"
            TimePrecision.DATE_ONLY -> {
                val pattern = if (sameYear) "EEE, MMM d" else "EEE, MMM d, yyyy"
                date.format(DateTimeFormatter.ofPattern(pattern, locale))
            }
        }
    }

    private fun dayPrefix(isToday: Boolean, sameYear: Boolean, date: LocalDate, locale: Locale): String =
        when {
            isToday -> TODAY
            sameYear -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }

    /** Part of day for a local time; boundaries match TemporalResolver's bands. */
    internal fun partOfDay(time: LocalTime): String = when {
        time >= MORNING_START && time < AFTERNOON_START -> "morning"
        time >= AFTERNOON_START && time < EVENING_START -> "afternoon"
        time >= EVENING_START && time < NIGHT_START -> "evening"
        else -> "night"
    }

    private const val TODAY = "Today"
    private val MORNING_START: LocalTime = LocalTime.of(5, 0)
    private val AFTERNOON_START: LocalTime = LocalTime.of(12, 0)
    private val EVENING_START: LocalTime = LocalTime.of(17, 0)
    private val NIGHT_START: LocalTime = LocalTime.of(21, 0)
}
