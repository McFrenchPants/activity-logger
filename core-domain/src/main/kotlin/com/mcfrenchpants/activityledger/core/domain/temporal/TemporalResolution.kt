package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.Instant

/** Outcome of resolving an extracted temporal phrase against a capture instant. */
sealed interface TemporalResolution {
    /** The phrase resolved to [occurredAt], known to [precision]. Never after the capture instant. */
    data class Resolved(val occurredAt: Instant, val precision: TimePrecision) : TemporalResolution

    /** The phrase refers to a time after the capture instant; an activity log cannot record it. */
    data object Future : TemporalResolution

    /** The phrase is not one the resolver understands; no time is guessed. */
    data object Unresolvable : TemporalResolution
}
