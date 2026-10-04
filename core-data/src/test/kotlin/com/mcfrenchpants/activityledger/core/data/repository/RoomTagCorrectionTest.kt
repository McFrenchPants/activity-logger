package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.insertRow
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.DurationChange
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.TagNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RoomActivityRepository.correctTags (TG2.3, ADR-041) on the real (in-memory) Room database:
 * tag and duration corrections, the "nothing changed writes nothing" rule, rollback on every
 * error, and USER_CORRECTION aliases. Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class RoomTagCorrectionTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x30_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(
            db, ids, java.time.Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC")), Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun capture(): String = repository.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = null,
            capturedAt = Instant.ofEpochMilli(CAPTURED),
            zoneId = ZoneId.of("America/New_York"),
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

    private suspend fun acceptNew(subject: String, action: String, durationSeconds: Long? = null): String =
        repository.acceptTagged(
            TaggedAcceptRequest(
                captureId = capture(),
                interpretation = record(),
                subject = TagTarget.New(subject),
                action = TagTarget.New(action),
                occurredAt = Instant.ofEpochMilli(CAPTURED - 60_000),
                timePrecision = TimePrecision.APPROXIMATE,
                activityState = ActivityState.COMPLETED,
                durationSeconds = durationSeconds,
            ),
        )

    private suspend fun acceptUntagged(): String = repository.acceptInterpretation(
        capture(), record(), ActivityTarget.New("Walk dog"),
        Instant.ofEpochMilli(CAPTURED), TimePrecision.EXACT, ActivityState.COMPLETED,
    )

    private fun correct(
        occurrenceId: String,
        subject: TagTarget? = null,
        action: TagTarget? = null,
        duration: DurationChange? = null,
        learnSubjectAlias: String? = null,
        learnActionAlias: String? = null,
    ) = TagCorrectionRequest(
        occurrenceId = occurrenceId,
        subject = subject,
        action = action,
        duration = duration,
        learnSubjectAlias = learnSubjectAlias,
        learnActionAlias = learnActionAlias,
        source = CorrectionSource.USER,
        reason = "synthetic reason",
        now = Instant.ofEpochMilli(LATER),
    )

    /** Every row of every table the operation may touch, as strings (counts included implicitly). */
    private fun snapshot(): Map<String, List<String>> = TABLES.associateWith { dump(it) }

    private fun dump(table: String): List<String> =
        sql.query("SELECT * FROM $table ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "<null>" else c.getString(i) })
                }
            }
        }

    private fun stringColumn(query: String, vararg args: Any?): String? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun activityOf(occurrenceId: String): String =
        checkNotNull(stringColumn("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", occurrenceId))

    private fun pairOf(occurrenceId: String): Pair<String?, String?> {
        val activityId = activityOf(occurrenceId)
        return stringColumn("SELECT subject_id FROM canonical_activities WHERE id = ?", activityId) to
            stringColumn("SELECT action_id FROM canonical_activities WHERE id = ?", activityId)
    }

    private fun durationOf(occurrenceId: String): Long? =
        sql.query("SELECT duration_seconds FROM activity_occurrences WHERE id = ?", arrayOf(occurrenceId)).use { c ->
            c.moveToFirst()
            if (c.isNull(0)) null else c.getLong(0)
        }

    private fun insertTag(table: String, id: String, name: String, status: TagStatus, mergedInto: String? = null) {
        val mergedColumn = if (table == "subjects") "merged_into_subject_id" else "merged_into_action_id"
        sql.insertRow(
            table,
            mapOf(
                "id" to id, "display_name" to name, "normalized_name" to TagNormalizer.key(name),
                "status" to status, mergedColumn to mergedInto, "created_at" to NOW - 1000, "updated_at" to NOW - 1000,
            ),
        )
    }

    private fun insertPair(id: String, subjectId: String?, actionId: String?, status: CanonicalActivityStatus) =
        sql.insertRow(
            "canonical_activities",
            mapOf(
                "id" to id, "display_name" to "Fixture pair $id", "normalized_name" to "fixture pair",
                "status" to status, "created_at" to NOW - 1000, "updated_at" to NOW - 1000,
                "merged_into_activity_id" to null, "subject_id" to subjectId, "action_id" to actionId,
            ),
        )

    private suspend fun applied(request: TagCorrectionRequest): String =
        assertIs<CorrectionOutcome.Applied>(repository.correctTags(request)).correctionId

    // --- tag changes -------------------------------------------------------------

    @Test
    fun subjectOnlyChangeSwitchesThePairAndKeepsTheAction() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter", durationSeconds = 600)
        val other = acceptNew("Hot tub", "drain")
        val (furnaceId, actionId) = pairOf(entry)
        val (hotTubId, _) = pairOf(other)
        val oldActivity = activityOf(entry)

        val correctionId = applied(correct(entry, subject = TagTarget.Existing(checkNotNull(hotTubId))))

        val (subjectAfter, actionAfter) = pairOf(entry)
        assertEquals(hotTubId, subjectAfter)
        assertEquals(actionId, actionAfter, "the action is kept")
        assertNotEquals(oldActivity, activityOf(entry))
        assertEquals(3, sql.count("canonical_activities"), "the (hot tub, change filter) pair was created")
        assertEquals(1, sql.count("corrections"))
        val row = db.correctionDao().listForOccurrence(entry).single()
        assertEquals(correctionId, row.id)
        assertEquals(oldActivity, row.previousCanonicalActivityId)
        assertEquals(activityOf(entry), row.newCanonicalActivityId)
        assertNull(row.previousDurationSeconds)
        assertNull(row.newDurationSeconds)
        assertEquals(CorrectionSource.USER, row.source)
        assertEquals(LATER, row.createdAt)
        assertEquals(600L, durationOf(entry), "the duration is untouched")
        assertNotEquals(furnaceId, subjectAfter)
        assertEquals(LATER, stringColumn("SELECT updated_at FROM activity_occurrences WHERE id = ?", entry)?.toLong())
    }

    @Test
    fun actionOnlyChangeKeepsTheSubject() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")
        val other = acceptNew("Boiler", "inspect")
        val (subjectId, _) = pairOf(entry)
        val inspectId = checkNotNull(pairOf(other).second)

        applied(correct(entry, action = TagTarget.Existing(inspectId)))

        assertEquals(subjectId to inspectId, pairOf(entry))
        assertEquals(3, sql.count("canonical_activities"))
        assertEquals(2, sql.count("subjects"))
        assertEquals(2, sql.count("actions"))
    }

    @Test
    fun bothNewCreatesTheTagsAndThePair() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")

        applied(correct(entry, subject = TagTarget.New("  Boiler "), action = TagTarget.New("flush")))

        val (subjectId, actionId) = pairOf(entry)
        assertEquals("Boiler", stringColumn("SELECT display_name FROM subjects WHERE id = ?", subjectId))
        assertEquals("boiler", stringColumn("SELECT normalized_name FROM subjects WHERE id = ?", subjectId))
        assertEquals("flush", stringColumn("SELECT display_name FROM actions WHERE id = ?", actionId))
        assertEquals(2, sql.count("subjects"))
        assertEquals(2, sql.count("actions"))
        assertEquals(2, sql.count("canonical_activities"))
        assertEquals("Boiler flush", assertNotNull(repository.getActivity(activityOf(entry))).displayName)
        val history = assertNotNull(repository.loadHistory().single().occurrence)
        assertEquals("Boiler", history.subjectName)
        assertEquals("flush", history.actionName)
    }

    @Test
    fun newNameConvergingOnAnExistingTagReusesItAndTheExistingPair() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")
        val other = acceptNew("WiFi", "reset")
        val wifiId = checkNotNull(pairOf(other).first)

        applied(correct(entry, subject = TagTarget.New("Wi-Fi")))

        assertEquals(wifiId, pairOf(entry).first)
        assertEquals(2, sql.count("subjects"), "no duplicate WiFi tag")
    }

    // --- duration --------------------------------------------------------------------

    @Test
    fun durationCanBeSetChangedAndCleared() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")
        val activity = activityOf(entry)

        val setId = applied(correct(entry, duration = DurationChange(900)))
        assertEquals(900L, durationOf(entry))
        assertEquals(activity, activityOf(entry), "the pair is untouched")
        val setRow = db.correctionDao().listForOccurrence(entry).single { it.id == setId }
        assertNull(setRow.previousDurationSeconds)
        assertEquals(900L, setRow.newDurationSeconds)
        assertNull(setRow.previousCanonicalActivityId)
        assertNull(setRow.newCanonicalActivityId)
        assertEquals(0, sql.count("subjects", "updated_at = ?", LATER), "no tag was touched")

        val changeId = applied(correct(entry, duration = DurationChange(0)))
        assertEquals(0L, durationOf(entry))
        val changeRow = db.correctionDao().listForOccurrence(entry).single { it.id == changeId }
        assertEquals(900L, changeRow.previousDurationSeconds)
        assertEquals(0L, changeRow.newDurationSeconds)

        val clearId = applied(correct(entry, duration = DurationChange(null)))
        assertNull(durationOf(entry))
        val clearRow = db.correctionDao().listForOccurrence(entry).single { it.id == clearId }
        assertEquals(0L, clearRow.previousDurationSeconds)
        assertNull(clearRow.newDurationSeconds)
        assertEquals(3, sql.count("corrections"))
    }

    @Test
    fun tagAndDurationChangeTogetherWriteOneRow() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter", durationSeconds = 60)

        val id = applied(correct(entry, subject = TagTarget.New("Boiler"), duration = DurationChange(120)))

        val row = db.correctionDao().listForOccurrence(entry).single()
        assertEquals(id, row.id)
        assertNotNull(row.previousCanonicalActivityId)
        assertNotNull(row.newCanonicalActivityId)
        assertEquals(60L, row.previousDurationSeconds)
        assertEquals(120L, row.newDurationSeconds)
        assertEquals(120L, durationOf(entry))
    }

    // --- nothing changed ---------------------------------------------------------------

    @Test
    fun nothingChangedWritesNothing() = runBlocking<Unit> {
        val entry = acceptNew("WiFi", "reset", durationSeconds = 600)
        val (subjectId, actionId) = pairOf(entry)
        checkNotNull(subjectId)
        checkNotNull(actionId)
        val before = snapshot()

        val unchanged = listOf(
            correct(entry),
            correct(entry, subject = TagTarget.Existing(subjectId), action = TagTarget.Existing(actionId)),
            // Converges on the current tags by key ("Wi-Fi" == "WiFi", "Resets" == "reset").
            correct(entry, subject = TagTarget.New("Wi-Fi"), action = TagTarget.New("Resets")),
            correct(entry, duration = DurationChange(600)),
            // Aliases are never stored when the correction itself changes nothing.
            correct(entry, subject = TagTarget.New("Wi-Fi"), learnSubjectAlias = "router", learnActionAlias = "restart"),
        )
        for (request in unchanged) {
            assertEquals(CorrectionOutcome.NothingChanged, repository.correctTags(request))
            assertEquals(before, snapshot())
        }

        // Clearing a duration that is already clear is no change either.
        val noDuration = acceptNew("Boiler", "flush")
        val beforeCleared = snapshot()
        assertEquals(CorrectionOutcome.NothingChanged, repository.correctTags(correct(noDuration, duration = DurationChange(null))))
        assertEquals(beforeCleared, snapshot())
    }

    // --- untagged (v3) entries -----------------------------------------------------------

    @Test
    fun untaggedEntryNeedsBothTagsToBeRetagged() = runBlocking<Unit> {
        val v3 = acceptUntagged()
        val before = snapshot()

        for (request in listOf(
            correct(v3, subject = TagTarget.New("Dog")),
            correct(v3, action = TagTarget.New("walk")),
            correct(v3),
            correct(v3, duration = DurationChange(60)),
        )) {
            assertFailsWith<IllegalArgumentException> { repository.correctTags(request) }
            assertEquals(before, snapshot())
        }

        applied(correct(v3, subject = TagTarget.New("Dog"), action = TagTarget.New("walk"), duration = DurationChange(1800)))
        val (subjectId, actionId) = pairOf(v3)
        assertEquals("Dog", stringColumn("SELECT display_name FROM subjects WHERE id = ?", subjectId))
        assertEquals("walk", stringColumn("SELECT display_name FROM actions WHERE id = ?", actionId))
        assertEquals(1800L, durationOf(v3))
        val row = db.correctionDao().listForOccurrence(v3).single()
        assertNotNull(row.previousCanonicalActivityId)
        assertEquals(activityOf(v3), row.newCanonicalActivityId)
    }

    // --- errors roll everything back -----------------------------------------------------

    @Test
    fun errorsThrowAndWriteNothing() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "inspect", durationSeconds = 5)
        val (subjectId, actionId) = pairOf(entry)
        checkNotNull(subjectId)
        checkNotNull(actionId)
        insertTag("subjects", MERGED_SUBJECT, "Old furnace", TagStatus.MERGED, mergedInto = subjectId)
        insertTag("actions", MERGED_ACTION, "look at", TagStatus.MERGED, mergedInto = actionId)
        val shed = "subject-archived-pair"
        insertTag("subjects", shed, "Shed", TagStatus.ACTIVE)
        insertPair("archived-pair", shed, actionId, CanonicalActivityStatus.ARCHIVED)

        val bad = listOf(
            "unknown occurrence" to correct("no-such-occurrence", subject = TagTarget.New("Brand new")),
            "unknown subject" to correct(entry, subject = TagTarget.Existing("no-such-subject"), action = TagTarget.New("brand new")),
            "unknown action" to correct(entry, subject = TagTarget.New("Brand new"), action = TagTarget.Existing("no-such-action")),
            "action id as subject" to correct(entry, subject = TagTarget.Existing(actionId), action = TagTarget.New("brand new")),
            "subject id as action" to correct(entry, subject = TagTarget.New("Brand new"), action = TagTarget.Existing(subjectId)),
            "MERGED subject" to correct(entry, subject = TagTarget.Existing(MERGED_SUBJECT), action = TagTarget.New("brand new")),
            "MERGED action" to correct(entry, subject = TagTarget.New("Brand new"), action = TagTarget.Existing(MERGED_ACTION)),
            "blank new subject" to correct(entry, subject = TagTarget.New("the"), action = TagTarget.New("brand new")),
            "blank new action" to correct(entry, subject = TagTarget.New("Brand new"), action = TagTarget.New("!!")),
            "negative duration" to correct(entry, subject = TagTarget.New("Brand new"), duration = DurationChange(-1)),
            "negative duration alone" to correct(entry, duration = DurationChange(-1)),
            "archived pair" to correct(
                entry, subject = TagTarget.Existing(shed), duration = DurationChange(9),
                learnSubjectAlias = "cabin", learnActionAlias = "peek",
            ),
        )
        for ((label, request) in bad) {
            val before = snapshot()
            assertFailsWith<IllegalArgumentException>(label) { repository.correctTags(request) }
            assertEquals(before, snapshot(), "$label wrote something")
        }
        assertEquals(0, sql.count("subjects", "normalized_name = 'brandnew'"))
        assertEquals(0, sql.count("actions", "normalized_name = 'brandnew'"))
        assertEquals(0, sql.count("corrections"))
        assertEquals(0, sql.count("subject_aliases"))
        assertEquals(0, sql.count("action_aliases"))
        assertEquals(5L, durationOf(entry))
    }

    // --- aliases ---------------------------------------------------------------------------

    @Test
    fun aliasesAreStoredAsUserCorrection() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")

        applied(
            correct(
                entry, subject = TagTarget.New("Hot tub"), action = TagTarget.New("swap filter"),
                learnSubjectAlias = "  the Jacuzzi ", learnActionAlias = "switch filter",
            ),
        )

        val (subjectId, actionId) = pairOf(entry)
        sql.query("SELECT subject_id, alias_text, normalized_alias, source, created_at FROM subject_aliases").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(
                listOf(subjectId, "the Jacuzzi", "jacuzzi", AliasSource.USER_CORRECTION.name, LATER.toString()),
                (0..4).map(c::getString),
            )
        }
        sql.query("SELECT action_id, alias_text, normalized_alias, source FROM action_aliases").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(
                listOf(actionId, "switch filter", "switchfilter", AliasSource.USER_CORRECTION.name),
                (0..3).map(c::getString),
            )
        }
        val catalog = repository.loadTagCatalog()
        assertEquals(listOf("the Jacuzzi"), catalog.subjects.single { it.id == subjectId }.aliases)
    }

    @Test
    fun redundantAliasesAreSkippedWithoutFailingTheCorrection() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")
        val other = acceptNew("Wi-Fi", "reset")
        val wifiId = checkNotNull(pairOf(other).first)

        // Blank, and equal to the final tag's own key.
        applied(correct(entry, subject = TagTarget.Existing(wifiId), learnSubjectAlias = "WiFi", learnActionAlias = "  "))
        assertEquals(0, sql.count("subject_aliases"))
        assertEquals(0, sql.count("action_aliases"))

        // Already an alias of the final tag (same key, different wording).
        sql.insertRow(
            "subject_aliases",
            mapOf(
                "id" to "alias-router", "subject_id" to wifiId, "alias_text" to "router",
                "normalized_alias" to "router", "source" to AliasSource.MANUAL, "created_at" to NOW,
            ),
        )
        applied(correct(entry, duration = DurationChange(30)))
        val third = acceptNew("Boiler", "flush")
        applied(correct(third, subject = TagTarget.Existing(wifiId), learnSubjectAlias = "Routers"))
        assertEquals(1, sql.count("subject_aliases"))
        assertEquals("router", stringColumn("SELECT alias_text FROM subject_aliases"))
    }

    // --- immutability and history -----------------------------------------------------------

    @Test
    fun rawCaptureAndInterpretationAreUntouchedAndHistoryShowsTheCorrection() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter", durationSeconds = 60)
        val rawBefore = dump("raw_captures")
        val interpretationsBefore = dump("interpretations")
        val captureId = checkNotNull(stringColumn("SELECT raw_capture_id FROM activity_occurrences WHERE id = ?", entry))
        val capturedAt = stringColumn("SELECT captured_at FROM activity_occurrences WHERE id = ?", entry)
        val effective = stringColumn("SELECT effective_interpretation_id FROM activity_occurrences WHERE id = ?", entry)

        val first = applied(correct(entry, subject = TagTarget.New("Boiler"), learnSubjectAlias = "heater"))
        val second = applied(correct(entry, duration = DurationChange(null)))

        assertEquals(rawBefore, dump("raw_captures"), "the raw capture row is byte-identical")
        assertEquals(interpretationsBefore, dump("interpretations"))
        assertEquals(captureId, stringColumn("SELECT raw_capture_id FROM activity_occurrences WHERE id = ?", entry))
        assertEquals(capturedAt, stringColumn("SELECT captured_at FROM activity_occurrences WHERE id = ?", entry))
        assertEquals(effective, stringColumn("SELECT effective_interpretation_id FROM activity_occurrences WHERE id = ?", entry))
        assertEquals("ACTIVE", stringColumn("SELECT visibility_status FROM activity_occurrences WHERE id = ?", entry))
        assertEquals(listOf(first, second), db.correctionDao().listForOccurrence(entry).map { it.id })
        assertNull(durationOf(entry))
    }

    private companion object {
        const val NOW = 1_760_000_000_123L
        const val LATER = 1_760_000_500_000L
        const val CAPTURED = 1_759_999_000_456L
        const val MERGED_SUBJECT = "subject-merged"
        const val MERGED_ACTION = "action-merged"
        val TABLES = listOf(
            "raw_captures", "subjects", "actions", "subject_aliases", "action_aliases",
            "canonical_activities", "interpretations", "activity_occurrences", "corrections",
        )
    }
}
