package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.Fixtures
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.T0
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.id
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.dao.DaoWriteSurfaceGuardTest
import com.mcfrenchpants.activityledger.core.data.db.dao.LedgerWriteDao
import com.mcfrenchpants.activityledger.core.data.db.dao.RawCaptureDao
import com.mcfrenchpants.activityledger.core.data.db.insertActivityOccurrence
import com.mcfrenchpants.activityledger.core.data.db.insertCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.db.insertCorrection
import com.mcfrenchpants.activityledger.core.data.db.insertInterpretation
import com.mcfrenchpants.activityledger.core.data.db.insertRawCapture
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryOccurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RoomActivityRepository.loadHistory and hideOccurrence on the real (in-memory) Room database. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class RoomActivityRepositoryHistoryTest {

    private class SteppingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: RoomActivityRepository
    private val sql get() = db.openHelper.writableDatabase
    private val clock = SteppingClock(Instant.ofEpochMilli(NOW))
    private val ids = DeterministicIdFactory(next = 0x10_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, ids, clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- fixtures (raw SQL, so ids, times and states are exact) ------------------

    private fun activity(n: Int, displayName: String) = sql.insertCanonicalActivity(
        Fixtures.canonicalActivity(id(n), normalizedName = NameNormalizer.normalize(displayName))
            .copy(displayName = displayName),
    )

    private fun capture(n: Int, capturedAt: Long, state: ProcessingState, text: String = "synthetic $n") =
        sql.insertRawCapture(
            Fixtures.rawCapture(id(n)).copy(capturedAt = capturedAt, processingState = state, rawText = text),
        )

    private fun interpretation(n: Int, captureN: Int, matched: String?, createdAt: Long = T0) =
        sql.insertInterpretation(Fixtures.interpretation(id(n), id(captureN), matched).copy(createdAt = createdAt))

    /** Capture [captureN] with interpretation 200+captureN and occurrence 300+captureN. */
    private fun capturedOccurrence(
        captureN: Int,
        activityN: Int,
        capturedAt: Long,
        occurredAt: Long,
        visibility: VisibilityStatus = VisibilityStatus.ACTIVE,
    ): String {
        capture(captureN, capturedAt, ProcessingState.PERSISTED)
        interpretation(200 + captureN, captureN, id(activityN))
        sql.insertActivityOccurrence(
            Fixtures.occurrence(id(300 + captureN), id(activityN), id(captureN), id(200 + captureN))
                .copy(capturedAt = capturedAt, occurredAt = occurredAt, visibilityStatus = visibility),
        )
        return id(300 + captureN)
    }

    /** Every row of [table], every column as text, ordered by id: a content snapshot. */
    private fun dump(table: String): List<List<String?>> =
        sql.query("SELECT * FROM $table ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) })
            }
        }

    private fun dumpAll() = TABLES.associateWith { dump(it) }

    // --- loadHistory -------------------------------------------------------------

    @Test
    fun historyOrdersNewestFirstWithIdTieBreakAndOmitsHidden() = runBlocking<Unit> {
        activity(1, "Walk dog")
        capturedOccurrence(11, activityN = 1, capturedAt = T0 + 100, occurredAt = T0 + 50)
        capture(12, T0 + 80, ProcessingState.NEEDS_REVIEW)
        capture(13, T0 + 60, ProcessingState.FAILED_RETRYABLE)
        capture(14, T0 + 50, ProcessingState.FAILED_FINAL) // ties with capture 11's occurred_at
        capture(15, T0 + 200, ProcessingState.CAPTURED)
        capturedOccurrence(16, activityN = 1, capturedAt = T0 + 5, occurredAt = T0 + 300, visibility = VisibilityStatus.HIDDEN)
        capturedOccurrence(17, activityN = 1, capturedAt = T0 + 10, occurredAt = T0 + 150)

        val history = repository.loadHistory()

        assertEquals(listOf(id(15), id(17), id(12), id(13), id(14), id(11)), history.map { it.captureId })
        assertEquals(
            listOf(
                ProcessingState.CAPTURED, ProcessingState.PERSISTED, ProcessingState.NEEDS_REVIEW,
                ProcessingState.FAILED_RETRYABLE, ProcessingState.FAILED_FINAL, ProcessingState.PERSISTED,
            ),
            history.map { it.processingState },
        )
        assertEquals(
            HistoryEntry(
                captureId = id(17),
                rawText = "synthetic 17",
                source = CaptureSource.WATCH_VOICE,
                capturedAt = Instant.ofEpochMilli(T0 + 10),
                zoneId = ZoneId.of("Europe/London"),
                processingState = ProcessingState.PERSISTED,
                occurrence = HistoryOccurrence(
                    occurrenceId = id(317),
                    activityId = id(1),
                    activityDisplayName = "Walk dog",
                    occurredAt = Instant.ofEpochMilli(T0 + 150),
                    timePrecision = TimePrecision.EXACT,
                    activityState = ActivityState.COMPLETED,
                ),
                pendingMatchedActivityId = null,
            ),
            history[1],
        )
        assertEquals(
            HistoryEntry(
                captureId = id(12),
                rawText = "synthetic 12",
                source = CaptureSource.WATCH_VOICE,
                capturedAt = Instant.ofEpochMilli(T0 + 80),
                zoneId = ZoneId.of("Europe/London"),
                processingState = ProcessingState.NEEDS_REVIEW,
                occurrence = null,
                pendingMatchedActivityId = null,
            ),
            history[2],
        )
        assertTrue(history.none { it.captureId == id(16) })
    }

    @Test
    fun emptyLedgerHasEmptyHistory() = runBlocking<Unit> {
        assertEquals(emptyList(), repository.loadHistory())
    }

    @Test
    fun pendingMatchedActivityIdComesFromTheLatestInterpretation() = runBlocking<Unit> {
        activity(1, "Walk dog")
        activity(2, "Mow lawn")
        // Two interpretations: the later created_at wins even though it was inserted first.
        capture(21, T0 + 40, ProcessingState.NEEDS_REVIEW)
        interpretation(51, 21, id(2), createdAt = T0 + 2)
        interpretation(52, 21, id(1), createdAt = T0 + 1)
        // Equal created_at: the greater id wins.
        capture(22, T0 + 30, ProcessingState.NEEDS_REVIEW)
        interpretation(53, 22, id(2), createdAt = T0 + 1)
        interpretation(54, 22, id(1), createdAt = T0 + 1)
        // Latest interpretation matched nothing.
        capture(23, T0 + 20, ProcessingState.NEEDS_REVIEW)
        interpretation(55, 23, id(1), createdAt = T0 + 1)
        interpretation(56, 23, null, createdAt = T0 + 2)
        // No interpretation at all.
        capture(24, T0 + 10, ProcessingState.FAILED_RETRYABLE)
        // With an occurrence, the pending id is always null.
        capturedOccurrence(25, activityN = 2, capturedAt = T0, occurredAt = T0)

        val byCapture = repository.loadHistory().associate { it.captureId to it.pendingMatchedActivityId }

        assertEquals(
            mapOf(id(21) to id(2), id(22) to id(1), id(23) to null, id(24) to null, id(25) to null),
            byCapture,
        )
    }

    @Test
    fun correctedOccurrenceShowsTheCurrentActivity() = runBlocking<Unit> {
        activity(1, "Walk dog")
        activity(2, "Mow lawn")
        val moved = capturedOccurrence(31, activityN = 1, capturedAt = T0, occurredAt = T0 + 20)
        val renamed = capturedOccurrence(32, activityN = 1, capturedAt = T0, occurredAt = T0 + 10)

        assertIs<CorrectionOutcome.Applied>(
            repository.applyCorrection(
                moved, CorrectionChanges(activity = ActivityTarget.Existing(id(2))), CorrectionSource.USER, null,
                Instant.ofEpochMilli(NOW),
            ),
        )
        assertIs<CorrectionOutcome.Applied>(
            repository.applyCorrection(
                renamed, CorrectionChanges(activity = ActivityTarget.New("Rake leaves")), CorrectionSource.USER, null,
                Instant.ofEpochMilli(NOW),
            ),
        )

        val occurrences = repository.loadHistory().map { assertNotNull(it.occurrence) }
        assertEquals(listOf(moved, renamed), occurrences.map { it.occurrenceId })
        assertEquals(id(2), occurrences[0].activityId)
        assertEquals("Mow lawn", occurrences[0].activityDisplayName)
        assertEquals("Rake leaves", occurrences[1].activityDisplayName)
        assertEquals(repository.getOccurrence(renamed)?.canonicalActivityId, occurrences[1].activityId)
    }

    @Test
    fun historyIsOneStatementWithNoPerRowParameter() {
        val querySql = DaoWriteSurfaceGuardTest.querySql(RawCaptureDao::class.java, "loadHistory")
        assertTrue(querySql.trimStart().startsWith("SELECT"), querySql)
        assertTrue(querySql.contains("LEFT JOIN activity_occurrences"), querySql)
        assertTrue(querySql.contains("LEFT JOIN canonical_activities"), querySql)
        assertTrue(!querySql.contains(":"), "the query takes no per-row parameter: $querySql")
    }

    // --- hideOccurrence -------------------------------------------------------------

    @Test
    fun hideSetsHiddenWithUpdatedAtAndRemovesFromHistory() = runBlocking<Unit> {
        activity(1, "Walk dog")
        val occurrenceId = capturedOccurrence(41, activityN = 1, capturedAt = T0, occurredAt = T0)
        capture(42, T0 + 1, ProcessingState.NEEDS_REVIEW)
        assertEquals(listOf(id(42), id(41)), repository.loadHistory().map { it.captureId })
        clock.now = clock.now.plusMillis(1_000)

        repository.hideOccurrence(occurrenceId)

        val row = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))
        assertEquals(VisibilityStatus.HIDDEN, row.visibilityStatus)
        assertEquals(clock.millis(), row.updatedAt)
        assertEquals(
            Fixtures.occurrence(occurrenceId, id(1), id(41), id(241)).copy(
                visibilityStatus = VisibilityStatus.HIDDEN, updatedAt = clock.millis(),
            ),
            row,
        )
        assertEquals(listOf(id(42)), repository.loadHistory().map { it.captureId })
    }

    @Test
    fun hidingTwiceIsANoOp() = runBlocking<Unit> {
        activity(1, "Walk dog")
        val occurrenceId = capturedOccurrence(43, activityN = 1, capturedAt = T0, occurredAt = T0)
        clock.now = clock.now.plusMillis(1_000)
        repository.hideOccurrence(occurrenceId)
        val hiddenAt = clock.millis()
        val before = dumpAll()
        clock.now = clock.now.plusMillis(1_000)

        repository.hideOccurrence(occurrenceId)

        assertEquals(hiddenAt, db.activityOccurrenceDao().getById(occurrenceId)?.updatedAt)
        assertEquals(before, dumpAll())
    }

    @Test
    fun hidingAnUnknownOccurrenceThrowsAndWritesNothing() = runBlocking<Unit> {
        activity(1, "Walk dog")
        capturedOccurrence(44, activityN = 1, capturedAt = T0, occurredAt = T0)
        val before = dumpAll()
        clock.now = clock.now.plusMillis(1_000)

        assertFailsWith<IllegalArgumentException> { repository.hideOccurrence(id(999)) }

        assertEquals(before, dumpAll())
    }

    @Test
    fun hideTouchesNoOtherTableAndRecordsNoCorrection() = runBlocking<Unit> {
        activity(1, "Walk dog")
        val occurrenceId = capturedOccurrence(45, activityN = 1, capturedAt = T0, occurredAt = T0)
        interpretation(60, 45, id(1), createdAt = T0 + 3)
        sql.insertCorrection(Fixtures.correction(id(70), occurrenceId, id(1), id(245)))
        val before = dumpAll()
        clock.now = clock.now.plusMillis(1_000)

        repository.hideOccurrence(occurrenceId)

        for (table in TABLES - "activity_occurrences") {
            assertEquals(before.getValue(table), dump(table), table)
        }
        assertEquals(1, sql.count("corrections"))
        assertEquals(2, sql.count("interpretations"))
        assertEquals(1, sql.count("raw_captures"))
        assertEquals(1, sql.count("canonical_activities"))
        assertEquals(emptyList(), repository.loadHistory())
    }

    @Test
    fun hideGoesThroughTheLedgerWriteDaoTransaction() {
        // The visibility UPDATE exists only in LedgerWriteDao (DaoWriteSurfaceGuardTest keeps
        // occurrence UPDATEs out of every other DAO).
        val updateSql = DaoWriteSurfaceGuardTest.querySql(LedgerWriteDao::class.java, "updateOccurrenceVisibility")
        assertTrue(updateSql.startsWith("UPDATE activity_occurrences SET visibility_status"), updateSql)
    }

    private companion object {
        const val NOW = 1_760_000_000_123L
        val TABLES = listOf(
            "raw_captures", "canonical_activities", "activity_aliases", "interpretations",
            "activity_occurrences", "corrections",
        )
    }
}
