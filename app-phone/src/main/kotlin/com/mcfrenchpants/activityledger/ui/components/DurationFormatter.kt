package com.mcfrenchpants.activityledger.ui.components

import android.content.res.Resources
import com.mcfrenchpants.activityledger.R

/**
 * Durations in plain words ("30 min", "1 h 15 min", "2 h"), from seconds.
 *
 * Rounded to the nearest minute; anything under a minute that is not zero reads as "1 min", so a
 * real duration is never shown as nothing. Never shows seconds.
 */
object DurationFormatter {

    /** Whole [hours] and [minutes] (0..59) that [seconds] rounds to. */
    data class Parts(val hours: Long, val minutes: Long)

    /** Rounds [seconds] to the nearest minute (at least one minute unless it is zero or less). */
    fun parts(seconds: Long): Parts {
        if (seconds <= 0L) return Parts(0, 0)
        val totalMinutes = maxOf(1L, (seconds + 30L) / 60L)
        return Parts(totalMinutes / 60L, totalMinutes % 60L)
    }

    /** [seconds] in plain words, using the string resources. */
    fun format(resources: Resources, seconds: Long): String {
        val (hours, minutes) = parts(seconds)
        return when {
            hours == 0L -> resources.getString(R.string.duration_minutes, minutes.toInt())
            minutes == 0L -> resources.getString(R.string.duration_hours, hours.toInt())
            else -> resources.getString(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
        }
    }
}
