package com.mcfrenchpants.activityledger.core.testing

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RunSuspendTest {
    private suspend fun suspendingIdentity(value: Int): Int = value

    @Test
    fun `returns the value`() {
        assertEquals(42, runSuspend { suspendingIdentity(42) })
    }

    @Test
    fun `rethrows the block's exception`() {
        val boom = IllegalArgumentException("boom")
        val thrown = assertFailsWith<IllegalArgumentException> { runSuspend<Unit> { throw boom } }
        assertSame(boom, thrown)
    }

    @Test
    fun `throws IllegalStateException when block suspends forever`() {
        assertFailsWith<IllegalStateException> {
            runSuspend { suspendCoroutine<Unit> { } }
        }
    }
}

class MutableClockTest {
    private val start = Instant.parse("2026-03-01T10:00:00Z")
    private val utc = ZoneId.of("UTC")
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `instant and zone`() {
        val clock = MutableClock(start, chicago)
        assertEquals(start, clock.instant())
        assertEquals(start.toEpochMilli(), clock.millis())
        assertEquals(chicago, clock.zone)
    }

    @Test
    fun `advance and set`() {
        val clock = MutableClock(start, utc)
        clock.advance(Duration.ofMinutes(90))
        assertEquals(start.plusSeconds(5400), clock.instant())
        val later = Instant.parse("2026-04-01T00:00:00Z")
        clock.currentInstant = later
        assertEquals(later, clock.instant())
    }

    @Test
    fun `withZone changes zone and shares time`() {
        val clock = MutableClock(start, utc)
        val zoned = clock.withZone(chicago)
        assertEquals(chicago, zoned.zone)
        assertEquals(utc, clock.zone)
        assertEquals(start, zoned.instant())
        clock.advance(Duration.ofHours(1))
        assertEquals(start.plusSeconds(3600), zoned.instant())
    }
}

class FakeActivityInterpreterTest {
    private fun input(text: String) = InterpretationInput(
        rawText = text,
        capturedAt = Instant.parse("2026-03-01T10:00:00Z"),
        zoneId = ZoneId.of("UTC"),
        candidates = emptyList(),
    )

    private val success = InterpretationResult.Success(
        InterpretationCandidate(
            operation = InterpretationOperation.LOG_ACTIVITY,
            activityResolution = ActivityResolution.NEW_ACTIVITY,
            matchedActivityId = null,
            proposedCanonicalName = "Mow lawn",
            activityState = null,
            temporalExpression = null,
            confidenceBand = null,
        ),
        structuredResultJson = "{}",
    )
    private val failure = InterpretationResult.Failure(InterpreterFailureKind.RETRYABLE, null)

    @Test
    fun `returns scripted results in order and records inputs`() {
        val fake = FakeActivityInterpreter()
        fake.enqueue(success, failure)
        assertEquals(success, runSuspend { fake.interpret(input("a")) })
        assertEquals(failure, runSuspend { fake.interpret(input("b")) })
        assertEquals(listOf("a", "b"), fake.receivedInputs.map { it.rawText })
        assertEquals(2, fake.callCount)
        assertFailsWith<IllegalStateException> { runSuspend { fake.interpret(input("c")) } }
    }

    @Test
    fun `falls back to lambda and provenance is settable`() {
        val fake = FakeActivityInterpreter()
        fake.respondWith { if (it.rawText == "x") failure else success }
        assertEquals(failure, runSuspend { fake.interpret(input("x")) })
        assertEquals(success, runSuspend { fake.interpret(input("y")) })
        val p = InterpreterProvenance("i2", "p2", 2)
        fake.provenance = p
        assertEquals(p, fake.provenance)
    }
}
