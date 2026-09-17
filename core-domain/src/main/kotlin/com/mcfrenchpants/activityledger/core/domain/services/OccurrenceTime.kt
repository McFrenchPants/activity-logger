package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.Instant

/**
 * A time chosen for an occurrence together with how precisely it is known.
 *
 * @property occurredAt When the activity happened.
 * @property precision How precisely [occurredAt] is known.
 */
data class OccurrenceTime(val occurredAt: Instant, val precision: TimePrecision)
