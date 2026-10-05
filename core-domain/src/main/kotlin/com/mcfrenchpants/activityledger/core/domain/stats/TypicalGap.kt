package com.mcfrenchpants.activityledger.core.domain.stats

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToLong

/** The unit a [TypicalGap] is shown in. */
enum class GapUnit { HOURS, DAYS, MONTHS, YEARS }

/**
 * The "usually every" value: the median gap between consecutive entries of one activity.
 *
 * @property duration The exact median gap.
 * @property unit The display unit chosen by the rounding rules in [of].
 * @property amount The rounded number of [unit]s (>= 1 for HOURS).
 */
data class TypicalGap(val duration: Duration, val unit: GapUnit, val amount: Long) {
    /** True when the gap displays as exactly one day ("every day"). */
    val isEveryDay: Boolean get() = unit == GapUnit.DAYS && amount == 1L

    /** True when the gap displays as exactly seven days ("every week"). */
    val isEveryWeek: Boolean get() = unit == GapUnit.DAYS && amount == 7L

    companion object {
        private const val SECONDS_PER_HOUR = 3_600.0
        private const val HOURS_SHOWN_BELOW = 20.0
        private const val SECONDS_PER_DAY = 86_400.0
        private const val DAYS_PER_MONTH = 30.44
        private const val DAYS_PER_YEAR = 365.25

        /**
         * Applies the display rounding: under 20 hours -> HOURS (rounded, minimum 1); under 60
         * days -> DAYS (rounded half up, minimum 1, so a roughly daily habit reads "every day"); under 730 days -> MONTHS (days / 30.44, rounded); otherwise
         * YEARS (days / 365.25, rounded).
         */
        fun of(duration: Duration): TypicalGap {
            val seconds = duration.toMillis() / 1_000.0
            val days = seconds / SECONDS_PER_DAY
            return when {
                seconds < HOURS_SHOWN_BELOW * SECONDS_PER_HOUR -> TypicalGap(duration, GapUnit.HOURS, max(1L, (seconds / SECONDS_PER_HOUR).roundToLong()))
                days < 60.0 -> TypicalGap(duration, GapUnit.DAYS, max(1L, days.roundToLong()))
                days < 730.0 -> TypicalGap(duration, GapUnit.MONTHS, (days / DAYS_PER_MONTH).roundToLong())
                else -> TypicalGap(duration, GapUnit.YEARS, (days / DAYS_PER_YEAR).roundToLong())
            }
        }

        /**
         * The median consecutive gap over [times] (any order). Even gap count -> mean of the two
         * middle gaps. Null when fewer than 3 times (fewer than 2 gaps).
         */
        fun fromTimes(times: List<Instant>): TypicalGap? {
            if (times.size < 3) return null
            val sorted = times.sorted()
            val gaps = sorted.zipWithNext { a, b -> Duration.between(a, b) }.sorted()
            val mid = gaps.size / 2
            val median = if (gaps.size % 2 == 1) gaps[mid] else gaps[mid - 1].plus(gaps[mid]).dividedBy(2)
            return of(median)
        }
    }
}
