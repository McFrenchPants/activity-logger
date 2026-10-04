package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.insertRow
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RoomActivityRepository.loadLookupEntries (TG4.2) on the real in-memory database. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class RoomLookupEntriesTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x50_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(
            db, ids, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC")), Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun capture(): String = repository.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = null,
            capturedAt = Instant.ofEpochMilli(NOW),
            zoneId = ZoneId.of("UTC"),
            rawText = "synthetic words",
            speechConfidence = null,
            speechAlternativesJson = null,
            processingState = ProcessingState.INTERPRETING,
        ),
    )

    private fun record() = InterpretationRecord(
        createdAt = Instant.ofEpochMilli(NOW - 5),
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 4,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.NEW_ACTIVITY,
        matchedActivityId = null,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = ConfidenceBand.HIGH,
        candidateContextHash = null,
        structuredResultJson = "{}",
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
        extractedSubject = "synthetic subject",
        extractedAction = "synthetic action",
        durationExpression = null,
        resolvedDurationSeconds = null,
    )

    private suspend fun accept(subject: String, action: String, at: Long, duration: Long? = null): String =
        repository.acceptTagged(
            TaggedAcceptRequest(
                captureId = capture(),
                interpretation = record(),
                subject = TagTarget.New(subject),
                action = TagTarget.New(action),
                occurredAt = Instant.ofEpochMilli(at),
                timePrecision = TimePrecision.EXACT,
                activityState = ActivityState.COMPLETED,
                durationSeconds = duration,
            ),
        )

    private fun stringColumn(query: String, vararg args: Any?): String? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun tagId(table: String, name: String): String =
        checkNotNull(stringColumn("SELECT id FROM $table WHERE display_name = ?", name)) { "no $name" }

    @Test
    fun emptyDatabaseGivesEmptyList() = runBlocking<Unit> {
        assertTrue(repository.loadLookupEntries().isEmpty())
    }

    @Test
    fun returnsTaggedEntriesNewestFirstWithNamesIdsAndDuration() = runBlocking<Unit> {
        val older = accept("Furnace", "inspect", at = 1_000L, duration = 600L)
        val newer = accept("Boiler", "change filter", at = 2_000L)

        val entries = repository.loadLookupEntries()

        assertEquals(listOf(newer, older), entries.map { it.occurrenceId })
        val first = entries[0]
        assertEquals("Boiler", first.subjectName)
        assertEquals("change filter", first.actionName)
        assertEquals(tagId("subjects", "Boiler"), first.subjectId)
        assertEquals(tagId("actions", "change filter"), first.actionId)
        assertEquals(Instant.ofEpochMilli(2_000L), first.occurredAt)
        assertEquals(null, first.durationSeconds)
        val second = entries[1]
        assertEquals("Furnace", second.subjectName)
        assertEquals(600L, second.durationSeconds)
        assertEquals(Instant.ofEpochMilli(1_000L), second.occurredAt)
    }

    @Test
    fun hiddenOccurrenceIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        val hidden = accept("Furnace", "inspect", at = 2_000L)
        sql.execSQL("UPDATE activity_occurrences SET visibility_status = 'HIDDEN' WHERE id = ?", arrayOf(hidden))

        assertEquals(listOf(kept), repository.loadLookupEntries().map { it.occurrenceId })
    }

    @Test
    fun untaggedOccurrenceIsExcluded() = runBlocking<Unit> {
        val tagged = accept("Furnace", "inspect", at = 1_000L)
        val untagged = accept("Boiler", "inspect", at = 2_000L)
        sql.insertRow(
            "canonical_activities",
            mapOf(
                "id" to "v3-activity", "display_name" to "Synthetic v3", "normalized_name" to "synthetic v3",
                "status" to "ACTIVE", "created_at" to NOW, "updated_at" to NOW,
                "merged_into_activity_id" to null, "subject_id" to null, "action_id" to null,
            ),
        )
        sql.execSQL(
            "UPDATE activity_occurrences SET canonical_activity_id = 'v3-activity' WHERE id = ?",
            arrayOf(untagged),
        )

        assertEquals(listOf(tagged), repository.loadLookupEntries().map { it.occurrenceId })
    }

    @Test
    fun renamedTagReturnsCurrentName() = runBlocking<Unit> {
        accept("Furnace", "inspect", at = 1_000L)
        repository.renameTag(TagKind.SUBJECT, tagId("subjects", "Furnace"), "Heating unit")

        val entry = repository.loadLookupEntries().single()

        assertEquals("Heating unit", entry.subjectName)
        assertEquals("inspect", entry.actionName)
    }

    @Test
    fun mergedOccurrencesAppearUnderTargetTagOnly() = runBlocking<Unit> {
        val moved = accept("Boiler", "inspect", at = 1_000L)
        val stays = accept("Furnace", "inspect", at = 2_000L)
        val boiler = tagId("subjects", "Boiler")
        val furnace = tagId("subjects", "Furnace")

        repository.mergeTags(TagKind.SUBJECT, boiler, furnace)

        val entries = repository.loadLookupEntries()
        assertEquals(listOf(stays, moved), entries.map { it.occurrenceId })
        assertTrue(entries.all { it.subjectId == furnace && it.subjectName == "Furnace" })
        assertTrue(entries.none { it.subjectId == boiler })
    }

    @Test
    fun occurrenceOnNonActivePairIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        val dropped = accept("Boiler", "inspect", at = 2_000L)
        sql.execSQL(
            "UPDATE canonical_activities SET status = 'MERGED' WHERE id = " +
                "(SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?)",
            arrayOf(dropped),
        )

        assertEquals(listOf(kept), repository.loadLookupEntries().map { it.occurrenceId })
    }

    @Test
    fun occurrenceWithNonActiveSubjectOrActionIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        accept("Boiler", "inspect", at = 2_000L)
        accept("Pump", "flush", at = 3_000L)
        sql.execSQL("UPDATE subjects SET status = 'MERGED' WHERE display_name = ?", arrayOf("Boiler"))
        sql.execSQL("UPDATE actions SET status = 'MERGED' WHERE display_name = ?", arrayOf("flush"))

        assertEquals(listOf(kept), repository.loadLookupEntries().map { it.occurrenceId })
    }

    @Test
    fun equalTimesAreOrderedByIdDescending() = runBlocking<Unit> {
        val a = accept("Furnace", "inspect", at = 1_000L)
        val b = accept("Furnace", "inspect", at = 1_000L)
        val c = accept("Boiler", "inspect", at = 1_000L)

        val got = repository.loadLookupEntries().map { it.occurrenceId }

        assertEquals(listOf(a, b, c).sortedDescending(), got)
        assertEquals(got, repository.loadLookupEntries().map { it.occurrenceId })
    }

    private companion object {
        const val NOW = 5_000_000L
    }
}
