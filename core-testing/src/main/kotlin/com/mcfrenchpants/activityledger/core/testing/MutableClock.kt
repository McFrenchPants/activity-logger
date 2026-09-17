package com.mcfrenchpants.activityledger.core.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A test [Clock] whose instant is set explicitly and only moves when told to.
 *
 * Clocks derived via [withZone] share this clock's instant: setting or advancing any of them
 * moves all of them, so code under test that re-zones the clock still sees test-controlled time.
 */
class MutableClock private constructor(
    private val state: State,
    private val zone: ZoneId,
) : Clock() {

    constructor(instant: Instant, zone: ZoneId) : this(State(instant), zone)

    private class State(@Volatile var instant: Instant)

    /** The current instant; assign to jump to a specific time. */
    var currentInstant: Instant
        get() = state.instant
        set(value) {
            state.instant = value
        }

    /** Moves the clock forward (or backward, for a negative [duration]). */
    fun advance(duration: Duration) {
        state.instant = state.instant.plus(duration)
    }

    override fun instant(): Instant = state.instant

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): MutableClock = MutableClock(state, zone)
}
