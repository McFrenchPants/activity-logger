package com.mcfrenchpants.activityledger.core.testing

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryOccurrence
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proves [InMemoryActivityRepository] honours the ActivityRepository error contract. Synthetic text only. */
class InMemoryActivityRepositoryTest {
    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val captured = Instant.parse("2026-09-16T11:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("UTC"))
    private val repo = InMemoryActivityRepository(clock)

    private fun capture(
        text: String = "synthetic words",
        state: ProcessingState = ProcessingState.CAPTURED,
        at: Instant = captured,
    ) = NewRawCapture(CaptureSource.PHONE_TEXT, null, at, ZoneId.of("America/Detroit"), text, null, null, state)

    @Test
    fun suppliedCaptureIdIsUsedAndRepeatWritesNothing() = runSuspend {
        val id = repo.createRawCapture(capture("first words").copy(id = "watch-1"))
        assertEquals("watch-1", id)
        val writes = repo.writeCount
        val again = repo.createRawCapture(capture("other words").copy(id = "watch-1"))
        assertEquals("watch-1", again)
        assertEquals(writes, repo.writeCount)
        assertEquals("first words", repo.getCapture("watch-1")?.rawText)
    }

    @Test
    fun blankSuppliedCaptureIdIsRefusedWithoutEchoingText() = runSuspend {
        val e = assertFailsWith<IllegalArgumentException> {
            repo.createRawCapture(capture("synthetic secret words").copy(id = " "))
        }
        assertTrue("synthetic secret words" !in e.message.orEmpty())
        assertEquals(0, repo.writeCount)
    }

    @Test
    fun nullCaptureIdStillGeneratesDistinctIds() = runSuspend {
        val a = repo.createRawCapture(capture())
        val b = repo.createRawCapture(capture())
        assertTrue(a != b)
    }

    private fun record(
        matched: String? = null,
        status: ValidationStatus = ValidationStatus.VALID,
        createdAt: Instant = now,
    ) = InterpretationRecord(
        createdAt = createdAt,
        interpreterVersion = "test",
        promptVersion = "test",
        schemaVersion = 1,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = if (matched == null) ActivityResolution.NEW_ACTIVITY else ActivityResolution.EXISTING_ACTIVITY,
        matchedActivityId = matched,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = null,
        modelConfidenceBand = null,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = status,
        validationReason = null,
    )

    private suspend fun accept(captureId: String, target: ActivityTarget, matched: String? = null) =
        repo.acceptInterpretation(captureId, record(matched), target, captured, TimePrecision.EXACT, ActivityState.COMPLETED)

    private fun assertNoWrite(before: Int, block: suspend () -> Unit) {
        assertFailsWith<IllegalArgumentException> { runSuspend { block() } }
        assertEquals(before, repo.writeCount)
    }

    @Test
    fun `ids are deterministic and reads of unknown ids return null`() = runSuspend {
        assertEquals("capture-1", repo.createRawCapture(capture()))
        assertEquals("activity-1", repo.seedActivity("Mow lawn"))
        assertEquals("occurrence-1", accept("capture-1", ActivityTarget.Existing("activity-1"), "activity-1"))
        assertEquals("interpretation-1", repo.getOccurrence("occurrence-1")?.effectiveInterpretationId)
        assertEquals(1, repo.interpretationsFor("capture-1").size)
        assertNull(repo.getCapture("nope"))
        assertNull(repo.getOccurrence("nope"))
        assertNull(repo.getActivity("nope"))
        assertEquals(emptyList(), repo.interpretationsFor("nope"))
    }

    @Test
    fun `write functions refuse unknown ids and write nothing`() {
        val activity = repo.seedActivity("Mow lawn")
        val w = repo.writeCount
        assertNoWrite(w) { repo.recordOutcome("nope", record(), ProcessingState.NEEDS_REVIEW) }
        assertNoWrite(w) { accept("nope", ActivityTarget.Existing(activity), activity) }
        assertNoWrite(w) { accept("nope", ActivityTarget.New("Rake leaves")) }
        assertNoWrite(w) {
            repo.applyCorrection("nope", CorrectionChanges(activityState = ActivityState.IN_PROGRESS), CorrectionSource.USER, null, now)
        }
        assertNoWrite(w) {
            repo.applyCorrection("nope", CorrectionChanges(activity = ActivityTarget.New("Rake leaves")), CorrectionSource.USER, null, now)
        }
        assertEquals(1, repo.activities.size)
    }

    @Test
    fun `accept refuses missing and non-ACTIVE targets and writes nothing`() {
        val archived = repo.seedActivity("Old", CanonicalActivityStatus.ARCHIVED)
        val merged = repo.seedActivity("Merged", CanonicalActivityStatus.MERGED)
        val captureId = runSuspend { repo.createRawCapture(capture()) }
        val w = repo.writeCount
        for (target in listOf("missing", archived, merged)) {
            assertNoWrite(w) { accept(captureId, ActivityTarget.Existing(target)) }
        }
        // An interpretation naming a missing activity is refused like a database foreign key would.
        assertNoWrite(w) { accept(captureId, ActivityTarget.New("Rake leaves"), matched = "missing") }
        assertNoWrite(w) { repo.recordOutcome(captureId, record(matched = "missing"), ProcessingState.NEEDS_REVIEW) }
        runSuspend {
            assertEquals(ProcessingState.CAPTURED, repo.getCapture(captureId)?.processingState)
            assertTrue(repo.interpretationsFor(captureId).isEmpty())
        }
        assertTrue(repo.occurrences.isEmpty())
    }

    @Test
    fun `recordOutcome refuses disallowed states and writes nothing`() = runSuspend {
        val captureId = repo.createRawCapture(capture())
        val w = repo.writeCount
        val disallowed = ProcessingState.entries.toSet() -
            setOf(ProcessingState.NEEDS_REVIEW, ProcessingState.FAILED_RETRYABLE, ProcessingState.FAILED_FINAL)
        assertEquals(6, disallowed.size)
        for (state in disallowed) {
            assertNoWrite(w) { repo.recordOutcome(captureId, record(), state) }
        }
        assertEquals(ProcessingState.CAPTURED, repo.getCapture(captureId)?.processingState)
        assertTrue(repo.interpretationsFor(captureId).isEmpty())

        for (state in listOf(ProcessingState.FAILED_RETRYABLE, ProcessingState.FAILED_FINAL, ProcessingState.NEEDS_REVIEW)) {
            repo.recordOutcome(captureId, null, state)
            assertEquals(state, repo.getCapture(captureId)?.processingState)
        }
        repo.recordOutcome(captureId, record(status = ValidationStatus.INVALID), ProcessingState.NEEDS_REVIEW)
        repo.recordOutcome(captureId, record(status = ValidationStatus.NEEDS_REVIEW), ProcessingState.NEEDS_REVIEW)
        assertEquals(
            listOf(ValidationStatus.INVALID, ValidationStatus.NEEDS_REVIEW),
            repo.interpretationsFor(captureId).map { it.validationStatus },
        )
        assertEquals(w + 5, repo.writeCount)
    }

    @Test
    fun `recordOutcome after an occurrence is refused`() = runSuspend {
        val captureId = repo.createRawCapture(capture())
        accept(captureId, ActivityTarget.New("Rake leaves"))
        val w = repo.writeCount
        assertNoWrite(w) { repo.recordOutcome(captureId, null, ProcessingState.NEEDS_REVIEW) }
        assertEquals(ProcessingState.PERSISTED, repo.getCapture(captureId)?.processingState)
        assertEquals(1, repo.interpretationsFor(captureId).size)
    }

    @Test
    fun `accept creates occurrence and activity, and is idempotent per capture`() = runSuspend {
        val captureId = repo.createRawCapture(capture())
        val occurrenceId = accept(captureId, ActivityTarget.New("  Rake   Leaves "))
        val occurrence = assertNotNull(repo.getOccurrence(occurrenceId))
        val activity = assertNotNull(repo.getActivity(occurrence.canonicalActivityId))
        assertEquals("rake leaves", activity.normalizedName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(captured, occurrence.capturedAt)
        assertEquals(VisibilityStatus.ACTIVE, occurrence.visibilityStatus)
        val stored = assertNotNull(repo.getCapture(captureId))
        assertTrue(stored.hasOccurrence)
        assertEquals(ProcessingState.PERSISTED, stored.processingState)

        val w = repo.writeCount
        val other = repo.seedActivity("Other")
        assertEquals(occurrenceId, accept(captureId, ActivityTarget.Existing(other), other))
        assertEquals(occurrenceId, accept(captureId, ActivityTarget.Existing("missing")))
        assertEquals(w, repo.writeCount)
        assertEquals(1, repo.occurrences.size)
        assertEquals(1, repo.interpretationsFor(captureId).size)
    }

    @Test
    fun `applyCorrection returns NothingChanged when nothing differs`() = runSuspend {
        val activity = repo.seedActivity("Mow lawn")
        val captureId = repo.createRawCapture(capture())
        val occurrenceId = accept(captureId, ActivityTarget.Existing(activity), activity)
        val w = repo.writeCount
        val same = CorrectionChanges(ActivityTarget.Existing(activity), captured, TimePrecision.EXACT, ActivityState.COMPLETED)
        assertEquals(CorrectionOutcome.NothingChanged, repo.applyCorrection(occurrenceId, same, CorrectionSource.USER, null, now))
        assertEquals(CorrectionOutcome.NothingChanged, repo.applyCorrection(occurrenceId, CorrectionChanges(), CorrectionSource.USER, null, now))
        assertEquals(w, repo.writeCount)
        assertTrue(repo.corrections.isEmpty())
    }

    @Test
    fun `applyCorrection records previous and new values and refuses non-ACTIVE targets`() = runSuspend {
        val first = repo.seedActivity("Mow lawn")
        val second = repo.seedActivity("Edge lawn")
        val archived = repo.seedActivity("Old", CanonicalActivityStatus.ARCHIVED)
        val captureId = repo.createRawCapture(capture())
        val occurrenceId = accept(captureId, ActivityTarget.Existing(first), first)
        val w = repo.writeCount
        assertNoWrite(w) {
            repo.applyCorrection(occurrenceId, CorrectionChanges(ActivityTarget.Existing(archived)), CorrectionSource.USER, null, now)
        }
        assertNoWrite(w) {
            repo.applyCorrection(occurrenceId, CorrectionChanges(ActivityTarget.Existing("missing")), CorrectionSource.USER, null, now)
        }

        val applied = repo.applyCorrection(
            occurrenceId,
            CorrectionChanges(ActivityTarget.Existing(second), timePrecision = TimePrecision.APPROXIMATE),
            CorrectionSource.USER, "fix", now,
        )
        assertEquals(CorrectionOutcome.Applied("correction-1"), applied)
        val c = repo.corrections.single()
        assertEquals(occurrenceId, c.occurrenceId)
        assertEquals(first, c.previousActivityId)
        assertEquals(second, c.newActivityId)
        assertEquals(TimePrecision.EXACT, c.previousTimePrecision)
        assertEquals(TimePrecision.APPROXIMATE, c.newTimePrecision)
        assertNull(c.previousOccurredAt)
        assertNull(c.newActivityState)
        assertEquals("fix", c.reason)
        assertEquals(CorrectionSource.USER, c.source)
        assertEquals(now, c.createdAt)
        val occurrence = assertNotNull(repo.getOccurrence(occurrenceId))
        assertEquals(second, occurrence.canonicalActivityId)
        assertEquals(TimePrecision.APPROXIMATE, occurrence.timePrecision)

        val toNew = repo.applyCorrection(occurrenceId, CorrectionChanges(ActivityTarget.New("Rake leaves")), CorrectionSource.REINTERPRETATION, null, now)
        assertIs<CorrectionOutcome.Applied>(toNew)
        val newId = assertNotNull(repo.getOccurrence(occurrenceId)).canonicalActivityId
        assertEquals("rake leaves", repo.getActivity(newId)?.normalizedName)
        assertEquals(w + 2, repo.writeCount)
    }

    @Test
    fun `catalog lists ACTIVE activities by normalized name with aliases and visible last occurrence`() = runSuspend {
        val zebra = repo.seedActivity("Zebra walk", aliases = listOf(" Walk The ZEBRA! "))
        val apple = repo.seedActivity("apple picking")
        repo.seedActivity("Archived", CanonicalActivityStatus.ARCHIVED)
        repo.seedActivity("Merged", CanonicalActivityStatus.MERGED)

        val early = repo.createRawCapture(capture())
        val late = repo.createRawCapture(capture())
        val earlyOcc = repo.acceptInterpretation(early, record(zebra), ActivityTarget.Existing(zebra), captured.minusSeconds(100), TimePrecision.EXACT, ActivityState.COMPLETED)
        val lateOcc = repo.acceptInterpretation(late, record(zebra), ActivityTarget.Existing(zebra), captured.minusSeconds(10), TimePrecision.EXACT, ActivityState.COMPLETED)

        var catalog = repo.loadCatalog()
        assertEquals(listOf(apple, zebra), catalog.map { it.id })
        assertEquals(listOf("walk the zebra"), catalog[1].normalizedAliases)
        assertNull(catalog[0].lastOccurredAt)
        assertEquals(captured.minusSeconds(10), catalog[1].lastOccurredAt)

        repo.setVisibility(lateOcc, VisibilityStatus.HIDDEN)
        catalog = repo.loadCatalog()
        assertEquals(captured.minusSeconds(100), catalog[1].lastOccurredAt)
        repo.setVisibility(earlyOcc, VisibilityStatus.HIDDEN)
        assertNull(repo.loadCatalog()[1].lastOccurredAt)
    }

    @Test
    fun `raw text is unchanged after every write`() = runSuspend {
        val text = "  Original synthetic words, exactly.  "
        val captureId = repo.createRawCapture(capture(text))
        val before = assertNotNull(repo.getCapture(captureId))
        fun assertEvidence() = runSuspend {
            val after = assertNotNull(repo.getCapture(captureId))
            assertEquals(text, after.rawText)
            assertEquals(before.capturedAt, after.capturedAt)
            assertEquals(before.zoneId, after.zoneId)
            assertEquals(before.source, after.source)
        }
        repo.recordOutcome(captureId, null, ProcessingState.FAILED_RETRYABLE)
        assertEvidence()
        repo.recordOutcome(captureId, record(status = ValidationStatus.INVALID), ProcessingState.NEEDS_REVIEW)
        assertEvidence()
        val occurrenceId = accept(captureId, ActivityTarget.New("Rake leaves"))
        assertEvidence()
        repo.applyCorrection(occurrenceId, CorrectionChanges(ActivityTarget.New("Other"), captured.minusSeconds(5), TimePrecision.APPROXIMATE, ActivityState.IN_PROGRESS), CorrectionSource.USER, "r", now)
        assertEvidence()
    }

    @Test
    fun `seedActivity accepts an explicit id and keeps generated ids unchanged`() = runSuspend {
        val fixed = repo.seedActivity("Mow lawn", aliases = listOf("Cut Grass"), id = "act-mow-lawn")
        assertEquals("act-mow-lawn", fixed)
        assertEquals("activity-1", repo.seedActivity("Edge lawn"))
        val view = assertNotNull(repo.getActivity("act-mow-lawn"))
        assertEquals("mow lawn", view.normalizedName)
        assertEquals(CanonicalActivityStatus.ACTIVE, view.status)
        assertEquals(listOf("cut grass"), repo.loadCatalog().first { it.id == "act-mow-lawn" }.normalizedAliases)
        assertEquals(0, repo.writeCount)
        assertFailsWith<IllegalArgumentException> { repo.seedActivity("Again", id = "act-mow-lawn") }
        assertFailsWith<IllegalArgumentException> { repo.seedActivity("Blank", id = " ") }
        assertEquals(2, repo.activities.size)
    }

    // --- loadHistory / hideOccurrence ------------------------------------------

    private suspend fun occurrence(activity: String, capturedAt: Instant, occurredAt: Instant): Pair<String, String> {
        val captureId = repo.createRawCapture(capture(at = capturedAt))
        return captureId to repo.acceptInterpretation(
            captureId, record(activity), ActivityTarget.Existing(activity), occurredAt, TimePrecision.EXACT, ActivityState.COMPLETED,
        )
    }

    @Test
    fun `history is newest first with capture-id tie-break and omits HIDDEN occurrences`() = runSuspend {
        val walk = repo.seedActivity("Walk dog")
        val (c1, o1) = occurrence(walk, captured.plusSeconds(100), captured.plusSeconds(50))
        val c2 = repo.createRawCapture(capture(state = ProcessingState.CAPTURED, at = captured.plusSeconds(50))) // ties c1
        val c3 = repo.createRawCapture(capture(at = captured.plusSeconds(80)))
        repo.recordOutcome(c3, null, ProcessingState.NEEDS_REVIEW)
        val c4 = repo.createRawCapture(capture(at = captured.plusSeconds(60)))
        repo.recordOutcome(c4, null, ProcessingState.FAILED_RETRYABLE)
        val c5 = repo.createRawCapture(capture(at = captured.plusSeconds(200)))
        repo.recordOutcome(c5, null, ProcessingState.FAILED_FINAL)
        val (c6, o6) = occurrence(walk, captured, captured.plusSeconds(300))
        repo.setVisibility(o6, VisibilityStatus.HIDDEN)

        val history = repo.loadHistory()

        assertEquals(listOf(c5, c3, c4, c2, c1), history.map { it.captureId })
        assertTrue(history.none { it.captureId == c6 })
        assertEquals(
            listOf(
                ProcessingState.FAILED_FINAL, ProcessingState.NEEDS_REVIEW, ProcessingState.FAILED_RETRYABLE,
                ProcessingState.CAPTURED, ProcessingState.PERSISTED,
            ),
            history.map { it.processingState },
        )
        assertEquals(
            HistoryEntry(
                captureId = c1,
                rawText = "synthetic words",
                source = CaptureSource.PHONE_TEXT,
                capturedAt = captured.plusSeconds(100),
                zoneId = ZoneId.of("America/Detroit"),
                processingState = ProcessingState.PERSISTED,
                occurrence = HistoryOccurrence(o1, walk, "Walk dog", captured.plusSeconds(50), TimePrecision.EXACT, ActivityState.COMPLETED),
                pendingMatchedActivityId = null,
            ),
            history.last(),
        )
        assertNull(history.first().occurrence)
    }

    @Test
    fun `pending matched activity id comes from the latest interpretation`() = runSuspend {
        val walk = repo.seedActivity("Walk dog")
        val mow = repo.seedActivity("Mow lawn")
        val twoInterpretations = repo.createRawCapture(capture(at = captured.plusSeconds(3)))
        repo.recordOutcome(twoInterpretations, record(mow, createdAt = now.plusSeconds(2)), ProcessingState.NEEDS_REVIEW)
        repo.recordOutcome(twoInterpretations, record(walk, createdAt = now.plusSeconds(1)), ProcessingState.NEEDS_REVIEW)
        val sameTime = repo.createRawCapture(capture(at = captured.plusSeconds(2)))
        repo.recordOutcome(sameTime, record(mow), ProcessingState.NEEDS_REVIEW)
        repo.recordOutcome(sameTime, record(walk), ProcessingState.NEEDS_REVIEW)
        val none = repo.createRawCapture(capture(at = captured.plusSeconds(1)))
        val (accepted, _) = occurrence(mow, captured, captured)

        val byCapture = repo.loadHistory().associate { it.captureId to it.pendingMatchedActivityId }

        assertEquals(mapOf(twoInterpretations to mow, sameTime to walk, none to null, accepted to null), byCapture)
    }

    @Test
    fun `history shows the corrected activity`() = runSuspend {
        val walk = repo.seedActivity("Walk dog")
        val mow = repo.seedActivity("Mow lawn")
        val (_, occurrenceId) = occurrence(walk, captured, captured)
        repo.applyCorrection(occurrenceId, CorrectionChanges(ActivityTarget.Existing(mow)), CorrectionSource.USER, null, now)
        val shown = assertNotNull(repo.loadHistory().single().occurrence)
        assertEquals(mow, shown.activityId)
        assertEquals("Mow lawn", shown.activityDisplayName)
    }

    @Test
    fun `hideOccurrence hides once, is idempotent, and records no correction`() = runSuspend {
        val walk = repo.seedActivity("Walk dog")
        val (captureId, occurrenceId) = occurrence(walk, captured, captured)
        val interpretationsBefore = repo.interpretationsFor(captureId)
        val captureBefore = repo.getCapture(captureId)
        val activitiesBefore = repo.activities
        val w = repo.writeCount
        clock.advance(Duration.ofSeconds(5))

        repo.hideOccurrence(occurrenceId)

        assertEquals(VisibilityStatus.HIDDEN, repo.getOccurrence(occurrenceId)?.visibilityStatus)
        assertEquals(clock.instant(), repo.occurrenceUpdatedAt(occurrenceId))
        assertEquals(emptyList(), repo.loadHistory())
        assertEquals(w + 1, repo.writeCount)
        val hiddenAt = clock.instant()
        clock.advance(Duration.ofSeconds(5))

        repo.hideOccurrence(occurrenceId)

        assertEquals(w + 1, repo.writeCount)
        assertEquals(hiddenAt, repo.occurrenceUpdatedAt(occurrenceId))
        assertTrue(repo.corrections.isEmpty())
        assertEquals(interpretationsBefore, repo.interpretationsFor(captureId))
        assertEquals(captureBefore, repo.getCapture(captureId))
        assertEquals(activitiesBefore, repo.activities)
    }

    @Test
    fun `hideOccurrence of an unknown id throws and writes nothing`() {
        val walk = repo.seedActivity("Walk dog")
        val (_, occurrenceId) = runSuspend { occurrence(walk, captured, captured) }
        val before = repo.occurrences
        val w = repo.writeCount
        assertNoWrite(w) { repo.hideOccurrence("nope") }
        assertEquals(before, repo.occurrences)
        assertEquals(VisibilityStatus.ACTIVE, runSuspend { repo.getOccurrence(occurrenceId) }?.visibilityStatus)
    }
}
