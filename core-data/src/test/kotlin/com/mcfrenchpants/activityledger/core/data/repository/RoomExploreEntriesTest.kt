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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RoomActivityRepository.loadExploreEntries (DH2.1) on the real in-memory database. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class RoomExploreEntriesTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x60_0000L)

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

    private suspend fun capture(text: String = "synthetic words"): String = repository.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = null,
            capturedAt = Instant.ofEpochMilli(NOW),
            zoneId = ZoneId.of("UTC"),
            rawText = text,
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

    @Suppress("LongParameterList")
    private suspend fun accept(
        subject: String,
        action: String,
        at: Long,
        duration: Long? = null,
        precision: TimePrecision = TimePrecision.EXACT,
        text: String = "synthetic words",
        subjectAlias: String? = null,
        actionAlias: String? = null,
    ): String = repository.acceptTagged(
        TaggedAcceptRequest(
            captureId = capture(text),
            interpretation = record(),
            subject = TagTarget.New(subject),
            action = TagTarget.New(action),
            occurredAt = Instant.ofEpochMilli(at),
            timePrecision = precision,
            activityState = ActivityState.COMPLETED,
            durationSeconds = duration,
            learnSubjectAlias = subjectAlias,
            learnActionAlias = actionAlias,
        ),
    )

    private fun stringColumn(query: String, vararg args: Any?): String? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun tagId(table: String, name: String): String =
        checkNotNull(stringColumn("SELECT id FROM $table WHERE display_name = ?", name)) { "no $name" }

    private fun activityIdOf(occurrenceId: String): String = checkNotNull(
        stringColumn("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", occurrenceId),
    )

    /** Moves [occurrenceId] onto a new untagged (v3-path) canonical activity named [name]. */
    private fun makeUntagged(occurrenceId: String, activityId: String, name: String) {
        sql.insertRow(
            "canonical_activities",
            mapOf(
                "id" to activityId, "display_name" to name, "normalized_name" to name.lowercase(),
                "status" to "ACTIVE", "created_at" to NOW, "updated_at" to NOW,
                "merged_into_activity_id" to null, "subject_id" to null, "action_id" to null,
            ),
        )
        sql.execSQL(
            "UPDATE activity_occurrences SET canonical_activity_id = ? WHERE id = ?",
            arrayOf(activityId, occurrenceId),
        )
    }

    /** Every row of every app table, as text, so any write would show up as a difference. */
    private fun snapshot(): Map<String, List<List<String?>>> {
        val tables = sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            sql.query("SELECT * FROM $table ORDER BY rowid").use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) })
                }
            }
        }
    }

    @Test
    fun emptyDatabaseGivesEmptyList() = runBlocking<Unit> {
        assertTrue(repository.loadExploreEntries().isEmpty())
    }

    @Test
    fun taggedEntryMapsEveryFieldWithCurrentNames() = runBlocking<Unit> {
        val occurrence = accept(
            "Furnace", "inspect", at = 1_000L, duration = 600L, precision = TimePrecision.DATE_ONLY,
            text = "synthetic furnace check", subjectAlias = "heater", actionAlias = "look at",
        )
        repository.renameTag(TagKind.SUBJECT, tagId("subjects", "Furnace"), "Heating unit")
        repository.renameTag(TagKind.ACTION, tagId("actions", "inspect"), "check over")

        val entry = repository.loadExploreEntries().single()

        assertEquals(occurrence, entry.occurrenceId)
        assertEquals(
            stringColumn("SELECT id FROM raw_captures WHERE raw_text = ?", "synthetic furnace check"),
            entry.captureId,
        )
        assertEquals(Instant.ofEpochMilli(1_000L), entry.occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, entry.timePrecision)
        assertEquals(600L, entry.durationSeconds)
        assertEquals(activityIdOf(occurrence), entry.activityId)
        assertEquals(
            stringColumn("SELECT display_name FROM canonical_activities WHERE id = ?", entry.activityId),
            entry.activityName,
        )
        assertEquals(tagId("subjects", "Heating unit"), entry.subjectId)
        assertEquals("Heating unit", entry.subjectName)
        assertEquals(tagId("actions", "check over"), entry.actionId)
        assertEquals("check over", entry.actionName)
        assertEquals("synthetic furnace check", entry.rawText)
        assertEquals(listOf("heater", "Furnace"), entry.subjectAliases)
        assertEquals(listOf("look at", "inspect"), entry.actionAliases)
    }

    @Test
    fun exactPrecisionAndMissingDurationAreReturnedAsStored() = runBlocking<Unit> {
        accept("Boiler", "flush", at = 2_000L)

        val entry = repository.loadExploreEntries().single()

        assertEquals(TimePrecision.EXACT, entry.timePrecision)
        assertNull(entry.durationSeconds)
        assertTrue(entry.subjectAliases.isEmpty())
        assertTrue(entry.actionAliases.isEmpty())
    }

    @Test
    fun hiddenOrRemovedOccurrenceIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        val hiddenByRepository = accept("Furnace", "inspect", at = 2_000L)
        val hiddenBySql = accept("Boiler", "inspect", at = 3_000L)
        repository.hideOccurrence(hiddenByRepository)
        sql.execSQL(
            "UPDATE activity_occurrences SET visibility_status = 'HIDDEN' WHERE id = ?",
            arrayOf(hiddenBySql),
        )

        assertEquals(listOf(kept), repository.loadExploreEntries().map { it.occurrenceId })
    }

    @Test
    fun captureWithoutOccurrenceIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        val waiting = capture("synthetic waiting words")
        sql.execSQL(
            "UPDATE raw_captures SET processing_state = 'NEEDS_REVIEW' WHERE id = ?",
            arrayOf(waiting),
        )
        capture("synthetic still interpreting")

        assertEquals(listOf(kept), repository.loadExploreEntries().map { it.occurrenceId })
    }

    @Test
    fun untaggedActivityIsIncludedWithNullTags() = runBlocking<Unit> {
        val tagged = accept("Furnace", "inspect", at = 1_000L, subjectAlias = "heater")
        val untagged = accept("Boiler", "inspect", at = 2_000L, text = "synthetic old words")
        makeUntagged(untagged, "v3-activity", "Synthetic v3")

        val entries = repository.loadExploreEntries()

        assertEquals(listOf(untagged, tagged), entries.map { it.occurrenceId })
        val old = entries[0]
        assertEquals("v3-activity", old.activityId)
        assertEquals("Synthetic v3", old.activityName)
        assertNull(old.subjectId)
        assertNull(old.subjectName)
        assertNull(old.actionId)
        assertNull(old.actionName)
        assertTrue(old.subjectAliases.isEmpty())
        assertTrue(old.actionAliases.isEmpty())
        assertEquals("synthetic old words", old.rawText)
        assertEquals(
            stringColumn("SELECT raw_capture_id FROM activity_occurrences WHERE id = ?", untagged),
            old.captureId,
        )
        assertTrue(entries[0].captureId != entries[1].captureId)
    }

    @Test
    fun nonActiveSubjectOrActionReadsAsNull() = runBlocking<Unit> {
        val boilerEntry = accept("Boiler", "inspect", at = 1_000L, subjectAlias = "water heater")
        val pumpEntry = accept("Pump", "flush", at = 2_000L, actionAlias = "drain")
        sql.execSQL("UPDATE subjects SET status = 'MERGED' WHERE display_name = ?", arrayOf("Boiler"))
        sql.execSQL("UPDATE actions SET status = 'MERGED' WHERE display_name = ?", arrayOf("flush"))

        val byId = repository.loadExploreEntries().associateBy { it.occurrenceId }

        val boiler = checkNotNull(byId[boilerEntry])
        assertNull(boiler.subjectId)
        assertNull(boiler.subjectName)
        assertTrue(boiler.subjectAliases.isEmpty())
        assertEquals(tagId("actions", "inspect"), boiler.actionId)
        assertEquals("inspect", boiler.actionName)
        val pump = checkNotNull(byId[pumpEntry])
        assertEquals(tagId("subjects", "Pump"), pump.subjectId)
        assertEquals("Pump", pump.subjectName)
        assertNull(pump.actionId)
        assertNull(pump.actionName)
        assertTrue(pump.actionAliases.isEmpty())
    }

    @Test
    fun occurrenceOnNonActiveCanonicalActivityIsExcluded() = runBlocking<Unit> {
        val kept = accept("Furnace", "inspect", at = 1_000L)
        val dropped = accept("Boiler", "inspect", at = 2_000L)
        sql.execSQL(
            "UPDATE canonical_activities SET status = 'MERGED' WHERE id = ?",
            arrayOf(activityIdOf(dropped)),
        )

        assertEquals(listOf(kept), repository.loadExploreEntries().map { it.occurrenceId })
    }

    @Test
    fun orderedNewestFirstWithIdTieBreakAndStableAcrossCalls() = runBlocking<Unit> {
        val oldest = accept("Furnace", "inspect", at = 500L)
        val a = accept("Furnace", "inspect", at = 1_000L)
        val b = accept("Boiler", "inspect", at = 1_000L)
        val c = accept("Pump", "flush", at = 1_000L)
        val newest = accept("Pump", "flush", at = 9_000L)

        val first = repository.loadExploreEntries()

        assertEquals(
            listOf(newest) + listOf(a, b, c).sortedDescending() + listOf(oldest),
            first.map { it.occurrenceId },
        )
        assertEquals(first, repository.loadExploreEntries())
    }

    @Test
    fun aliasesDoNotLeakBetweenEntries() = runBlocking<Unit> {
        val furnace = accept("Furnace", "inspect", at = 1_000L, subjectAlias = "heater", actionAlias = "look at")
        val boiler = accept("Boiler", "flush", at = 2_000L, subjectAlias = "water tank", actionAlias = "drain")
        val furnaceAgain = accept("Furnace", "flush", at = 3_000L)

        val byId = repository.loadExploreEntries().associateBy { it.occurrenceId }

        assertEquals(listOf("heater"), byId.getValue(furnace).subjectAliases)
        assertEquals(listOf("look at"), byId.getValue(furnace).actionAliases)
        assertEquals(listOf("water tank"), byId.getValue(boiler).subjectAliases)
        assertEquals(listOf("drain"), byId.getValue(boiler).actionAliases)
        assertEquals(listOf("heater"), byId.getValue(furnaceAgain).subjectAliases)
        assertEquals(listOf("drain"), byId.getValue(furnaceAgain).actionAliases)
    }

    @Test
    fun rawTextIsReturnedUnchangedAndDatabaseIsUntouched() = runBlocking<Unit> {
        val odd = "  Synthetic  MIXED case, punctuation!? é  "
        val occurrence = accept("Furnace", "inspect", at = 1_000L, text = odd, subjectAlias = "heater")
        val untagged = accept("Boiler", "inspect", at = 2_000L)
        makeUntagged(untagged, "v3-activity", "Synthetic v3")
        capture("synthetic waiting words")
        val before = snapshot()

        val entries = repository.loadExploreEntries()
        repository.loadExploreEntries()

        assertEquals(odd, entries.single { it.occurrenceId == occurrence }.rawText)
        assertEquals(before, snapshot())
        assertEquals(odd, stringColumn("SELECT raw_text FROM raw_captures WHERE raw_text = ?", odd))
    }

    private companion object {
        const val NOW = 5_000_000L
    }
}
