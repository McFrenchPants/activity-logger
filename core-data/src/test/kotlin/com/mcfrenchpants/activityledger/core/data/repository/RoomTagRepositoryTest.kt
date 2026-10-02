package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.dao.DaoWriteSurfaceGuardTest
import com.mcfrenchpants.activityledger.core.data.db.dao.RawCaptureDao
import com.mcfrenchpants.activityledger.core.data.db.insertRow
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptTaggedRequest
import com.mcfrenchpants.activityledger.core.data.ledger.ActivityLedgerWriter
import com.mcfrenchpants.activityledger.core.data.ledger.NewInterpretation
import com.mcfrenchpants.activityledger.core.data.ledger.TagRef
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The subject + action tag half of RoomActivityRepository (TG2.2, ADR-040) on the real
 * (in-memory) Room database. Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class RoomTagRepositoryTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x20_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, ids, java.time.Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC")), Dispatchers.Unconfined)
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

    private fun record(
        extractedSubject: String? = "synthetic subject",
        extractedAction: String? = "synthetic action",
        durationExpression: String? = "ten minutes",
        resolvedDurationSeconds: Long? = 600,
    ) = InterpretationRecord(
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
        extractedSubject = extractedSubject,
        extractedAction = extractedAction,
        durationExpression = durationExpression,
        resolvedDurationSeconds = resolvedDurationSeconds,
    )

    private fun request(
        captureId: String,
        subject: TagTarget,
        action: TagTarget,
        durationSeconds: Long? = null,
        learnSubjectAlias: String? = null,
        learnActionAlias: String? = null,
        interpretation: InterpretationRecord = record(),
    ) = TaggedAcceptRequest(
        captureId = captureId,
        interpretation = interpretation,
        subject = subject,
        action = action,
        occurredAt = Instant.ofEpochMilli(CAPTURED - 60_000),
        timePrecision = TimePrecision.APPROXIMATE,
        activityState = ActivityState.COMPLETED,
        durationSeconds = durationSeconds,
        learnSubjectAlias = learnSubjectAlias,
        learnActionAlias = learnActionAlias,
    )

    private suspend fun acceptNew(subject: String, action: String, durationSeconds: Long? = null): String =
        repository.acceptTagged(request(capture(), TagTarget.New(subject), TagTarget.New(action), durationSeconds))

    /** Row counts of every table a tagged accept may write, plus every capture's processing state. */
    private fun snapshot(): Map<String, Any> =
        WRITTEN_TABLES.associateWith { sql.count(it) } +
            ("states" to sql.query("SELECT id, processing_state FROM raw_captures ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) + "=" + c.getString(1)) }
            })

    private fun stringColumn(query: String, vararg args: Any?): String? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun occurrenceActivityId(occurrenceId: String): String =
        checkNotNull(stringColumn("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", occurrenceId))

    private fun pairOf(occurrenceId: String): Pair<String, String> {
        val activityId = occurrenceActivityId(occurrenceId)
        return checkNotNull(stringColumn("SELECT subject_id FROM canonical_activities WHERE id = ?", activityId)) to
            checkNotNull(stringColumn("SELECT action_id FROM canonical_activities WHERE id = ?", activityId))
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

    // --- loadTagCatalog -------------------------------------------------------

    @Test
    fun emptyDatabaseGivesEmptyCatalog() = runBlocking<Unit> {
        assertEquals(TagCatalog.EMPTY, repository.loadTagCatalog())
    }

    @Test
    fun catalogHasOnlyActiveTagsAndPairsWhoseTagsAreBothActive() = runBlocking<Unit> {
        val occurrence = acceptNew("Zebra pen", "clean")
        val (subjectId, actionId) = pairOf(occurrence)
        val apple = pairOf(acceptNew("Apple tree", "clean")).first
        // A MERGED subject (with an alias), an ACTIVE pair using it, an untagged activity, and a
        // MERGED action with an ACTIVE pair using it: none may surface in the catalog.
        insertTag("subjects", MERGED_SUBJECT, "Old pen", TagStatus.MERGED, mergedInto = subjectId)
        sql.insertRow(
            "subject_aliases",
            mapOf(
                "id" to "alias-merged", "subject_id" to MERGED_SUBJECT, "alias_text" to "old enclosure",
                "normalized_alias" to "oldenclosure", "source" to AliasSource.MANUAL, "created_at" to NOW,
            ),
        )
        insertPair("pair-merged-subject", MERGED_SUBJECT, actionId, CanonicalActivityStatus.ACTIVE)
        insertTag("actions", MERGED_ACTION, "scrub", TagStatus.MERGED, mergedInto = actionId)
        insertPair("pair-merged-action", subjectId, MERGED_ACTION, CanonicalActivityStatus.ACTIVE)
        insertPair("untagged", null, null, CanonicalActivityStatus.ACTIVE)
        repository.acceptInterpretation(
            capture(), record(null, null, null, null), ActivityTarget.New("Legacy activity"),
            Instant.ofEpochMilli(CAPTURED), TimePrecision.EXACT, ActivityState.COMPLETED,
        )

        val catalog = repository.loadTagCatalog()

        assertEquals(
            listOf(KnownTag(apple, TagKind.SUBJECT, "Apple tree", emptyList()), KnownTag(subjectId, TagKind.SUBJECT, "Zebra pen", emptyList())),
            catalog.subjects,
            "ordered by normalized name; MERGED subject excluded",
        )
        assertEquals(listOf(KnownTag(actionId, TagKind.ACTION, "clean", emptyList())), catalog.actions)
        assertEquals(setOf(KnownPair(subjectId, actionId), KnownPair(apple, actionId)), catalog.pairs.toSet())
        assertEquals(2, catalog.pairs.size)
    }

    // --- acceptTagged: find-or-create ----------------------------------------

    @Test
    fun newPlusNewCreatesBothTagsPairAndOccurrence() = runBlocking<Unit> {
        val captureId = capture()
        val occurrenceId = repository.acceptTagged(
            request(captureId, TagTarget.New("  Furnace "), TagTarget.New("change filter"), durationSeconds = 600),
        )

        assertEquals(1, sql.count("subjects"))
        assertEquals(1, sql.count("actions"))
        assertEquals(1, sql.count("canonical_activities"))
        assertEquals(1, sql.count("activity_occurrences"))
        assertEquals(ProcessingState.PERSISTED, repository.getCapture(captureId)?.processingState)

        val (subjectId, actionId) = pairOf(occurrenceId)
        val activity = assertNotNull(repository.getActivity(occurrenceActivityId(occurrenceId)))
        assertEquals("Furnace change filter", activity.displayName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(subjectId, activity.subjectId)
        assertEquals(actionId, activity.actionId)
        assertEquals("furnace", stringColumn("SELECT normalized_name FROM subjects WHERE id = ?", subjectId))
        assertEquals("changefilter", stringColumn("SELECT normalized_name FROM actions WHERE id = ?", actionId))

        val catalog = repository.loadTagCatalog()
        assertEquals(listOf(KnownTag(subjectId, TagKind.SUBJECT, "Furnace", emptyList())), catalog.subjects)
        assertEquals(listOf(KnownTag(actionId, TagKind.ACTION, "change filter", emptyList())), catalog.actions)
        assertEquals(listOf(KnownPair(subjectId, actionId)), catalog.pairs)
    }

    @Test
    fun secondCaptureWithExistingIdsReusesThePair() = runBlocking<Unit> {
        val first = acceptNew("Furnace", "change filter")
        val (subjectId, actionId) = pairOf(first)

        val second = repository.acceptTagged(
            request(capture(), TagTarget.Existing(subjectId), TagTarget.Existing(actionId)),
        )

        assertNotEquals(first, second)
        assertEquals(occurrenceActivityId(first), occurrenceActivityId(second))
        assertEquals(1, sql.count("canonical_activities"))
        assertEquals(1, sql.count("subjects"))
        assertEquals(1, sql.count("actions"))
        assertEquals(2, sql.count("activity_occurrences"))
    }

    @Test
    fun newNameWithSameKeyAsExistingTagReusesIt() = runBlocking<Unit> {
        val first = pairOf(acceptNew("WiFi", "reset"))
        val second = pairOf(acceptNew("Wi-Fi", "Reset"))

        assertEquals(first, second)
        assertEquals(1, sql.count("subjects"))
        assertEquals("WiFi", stringColumn("SELECT display_name FROM subjects"), "the existing name is kept")
        assertEquals(1, sql.count("canonical_activities"))
    }

    @Test
    fun newNameMatchingAnAliasKeyReusesThatTag() = runBlocking<Unit> {
        val first = repository.acceptTagged(
            request(capture(), TagTarget.New("Hot tub"), TagTarget.New("drain"), learnSubjectAlias = "Spa"),
        )
        val second = acceptNew("spa", "drain")

        assertEquals(pairOf(first), pairOf(second))
        assertEquals(1, sql.count("subjects"))
    }

    @Test
    fun newSubjectNamedLikeAnExistingActionIsASeparateSubject() = runBlocking<Unit> {
        val (_, mowAction) = pairOf(acceptNew("Lawn", "mow"))
        val (mowSubject, _) = pairOf(acceptNew("mow", "check"))

        assertNotEquals(mowAction, mowSubject)
        assertEquals(2, sql.count("subjects"))
        assertEquals(2, sql.count("actions"))
        assertEquals(1, sql.count("subjects", "normalized_name = 'mow'"))
        assertEquals(1, sql.count("actions", "normalized_name = 'mow'"))
    }

    @Test
    fun secondAcceptOfSameCaptureWritesNothing() = runBlocking<Unit> {
        val captureId = capture()
        val first = repository.acceptTagged(request(captureId, TagTarget.New("Furnace"), TagTarget.New("inspect")))
        val before = snapshot()

        val again = repository.acceptTagged(
            request(
                captureId, TagTarget.New("Boiler"), TagTarget.New("replace"), durationSeconds = 5,
                learnSubjectAlias = "heater", learnActionAlias = "swap",
            ),
        )

        assertEquals(first, again)
        assertEquals(before, snapshot())
    }

    // --- acceptTagged: errors write nothing ------------------------------------

    @Test
    fun errorsThrowAndWriteNothing() = runBlocking<Unit> {
        val (subjectId, actionId) = pairOf(acceptNew("Furnace", "inspect"))
        insertTag("subjects", MERGED_SUBJECT, "Old furnace", TagStatus.MERGED, mergedInto = subjectId)
        insertTag("actions", MERGED_ACTION, "look at", TagStatus.MERGED, mergedInto = actionId)
        val archivedSubject = "subject-archived-pair"
        insertTag("subjects", archivedSubject, "Shed", TagStatus.ACTIVE)
        insertPair("archived-pair", archivedSubject, actionId, CanonicalActivityStatus.ARCHIVED)
        val captureId = capture()

        val bad = listOf(
            "unknown existing action" to request(captureId, TagTarget.New("Brand new"), TagTarget.Existing("no-such-action")),
            "unknown existing subject" to request(captureId, TagTarget.Existing("no-such-subject"), TagTarget.New("brand new")),
            "action id used as subject" to request(captureId, TagTarget.Existing(actionId), TagTarget.New("brand new")),
            "MERGED subject" to request(captureId, TagTarget.Existing(MERGED_SUBJECT), TagTarget.New("brand new")),
            "MERGED action" to request(captureId, TagTarget.New("Brand new"), TagTarget.Existing(MERGED_ACTION)),
            "blank subject" to request(captureId, TagTarget.New("   "), TagTarget.New("brand new")),
            "subject with blank key" to request(captureId, TagTarget.New("the"), TagTarget.New("brand new")),
            "blank action" to request(captureId, TagTarget.New("Brand new"), TagTarget.New("!!")),
            "negative duration" to request(captureId, TagTarget.New("Brand new"), TagTarget.New("brand new"), durationSeconds = -1),
            "unknown capture" to request("no-such-capture", TagTarget.New("Brand new"), TagTarget.New("brand new")),
            "archived pair" to request(captureId, TagTarget.Existing(archivedSubject), TagTarget.New("Inspect")),
        )
        for ((label, request) in bad) {
            val before = snapshot()
            assertFailsWith<IllegalArgumentException>(label) { repository.acceptTagged(request) }
            assertEquals(before, snapshot(), "$label wrote something")
        }
        assertEquals(0, sql.count("subjects", "normalized_name = 'brandnew'"), "rolled back")
        assertEquals(0, sql.count("actions", "normalized_name = 'brandnew'"), "rolled back")
        assertEquals(ProcessingState.INTERPRETING, repository.getCapture(captureId)?.processingState)
    }

    @Test
    fun interpretationOfAnotherCaptureThrowsAndWritesNothing() = runBlocking<Unit> {
        val captureId = capture()
        val otherId = capture()
        val before = snapshot()
        val writer = ActivityLedgerWriter(db, ids)
        val interpretation = NewInterpretation(
            rawCaptureId = otherId, createdAt = NOW, interpreterVersion = "v", promptVersion = "p", schemaVersion = 4,
            operation = InterpretationOperation.LOG_ACTIVITY, activityResolution = ActivityResolution.NEW_ACTIVITY,
            matchedActivityId = null, proposedCanonicalName = null, activityState = null, temporalExpression = null,
            resolvedOccurredAt = null, timePrecision = null, modelConfidenceBand = null, candidateContextHash = null,
            structuredResultJson = null, validationStatus = ValidationStatus.VALID, validationReason = null,
        )

        assertFailsWith<IllegalArgumentException> {
            writer.acceptTagged(
                AcceptTaggedRequest(
                    rawCaptureId = captureId, interpretation = interpretation,
                    subject = TagRef.New("Furnace", "furnace"), action = TagRef.New("inspect", "inspect"),
                    occurredAt = CAPTURED, timePrecision = TimePrecision.EXACT, activityState = ActivityState.COMPLETED,
                    durationSeconds = null, subjectAlias = null, actionAlias = null, now = NOW,
                ),
            )
        }
        assertEquals(before, snapshot())
    }

    // --- alias learning --------------------------------------------------------

    @Test
    fun aliasIsLearnedWhenItDiffersFromTheTag() = runBlocking<Unit> {
        val occurrence = repository.acceptTagged(
            request(
                capture(), TagTarget.New("Hot tub"), TagTarget.New("change filter"),
                learnSubjectAlias = "  the Spa ", learnActionAlias = "swap filter",
            ),
        )
        val (subjectId, actionId) = pairOf(occurrence)

        sql.query("SELECT subject_id, alias_text, normalized_alias, source FROM subject_aliases").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(listOf(subjectId, "the Spa", "spa", AliasSource.AI_CONFIRMED.name), (0..3).map(c::getString))
        }
        sql.query("SELECT action_id, alias_text, normalized_alias, source FROM action_aliases").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(listOf(actionId, "swap filter", "swapfilter", AliasSource.AI_CONFIRMED.name), (0..3).map(c::getString))
        }
        val catalog = repository.loadTagCatalog()
        assertEquals(listOf("the Spa"), catalog.subjects.single().aliases)
        assertEquals(listOf("swap filter"), catalog.actions.single().aliases)
    }

    @Test
    fun redundantAliasesAreSkippedWithoutFailingTheSave() = runBlocking<Unit> {
        // Equal to the tag's own key.
        val first = repository.acceptTagged(
            request(capture(), TagTarget.New("WiFi"), TagTarget.New("reset"), learnSubjectAlias = "Wi-Fi", learnActionAlias = "resets"),
        )
        assertEquals(0, sql.count("subject_aliases"))
        assertEquals(0, sql.count("action_aliases"))
        val (subjectId, actionId) = pairOf(first)

        // Blank (whitespace, or only a determiner).
        repository.acceptTagged(
            request(capture(), TagTarget.Existing(subjectId), TagTarget.Existing(actionId), learnSubjectAlias = "  ", learnActionAlias = "the"),
        )
        assertEquals(0, sql.count("subject_aliases"))
        assertEquals(0, sql.count("action_aliases"))

        // Already an alias of that tag (same key, different wording).
        repository.acceptTagged(
            request(capture(), TagTarget.Existing(subjectId), TagTarget.Existing(actionId), learnSubjectAlias = "router"),
        )
        repository.acceptTagged(
            request(capture(), TagTarget.Existing(subjectId), TagTarget.Existing(actionId), learnSubjectAlias = "Routers"),
        )
        assertEquals(1, sql.count("subject_aliases"))
        assertEquals("router", stringColumn("SELECT alias_text FROM subject_aliases"))
        assertEquals(4, sql.count("activity_occurrences"))
    }

    // --- round trips ------------------------------------------------------------

    @Test
    fun extractionFieldsAndDurationRoundTrip() = runBlocking<Unit> {
        val captureId = capture()
        val occurrenceId = repository.acceptTagged(
            request(
                captureId, TagTarget.New("Hot tub"), TagTarget.New("change filter"), durationSeconds = 900,
                interpretation = record("the hot tub", "changed the filter", "fifteen minutes", 900),
            ),
        )

        sql.query(
            "SELECT extracted_subject, extracted_action, duration_expression, resolved_duration_seconds " +
                "FROM interpretations WHERE raw_capture_id = ?",
            arrayOf(captureId),
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(listOf("the hot tub", "changed the filter", "fifteen minutes"), (0..2).map(c::getString))
            assertEquals(900L, c.getLong(3))
        }
        assertEquals(900L, repository.getOccurrence(occurrenceId)?.durationSeconds)

        val occurrence = assertNotNull(repository.loadHistory().single().occurrence)
        assertEquals(occurrenceId, occurrence.occurrenceId)
        assertEquals("Hot tub", occurrence.subjectName)
        assertEquals("change filter", occurrence.actionName)
        assertEquals(900L, occurrence.durationSeconds)
        assertEquals("Hot tub change filter", occurrence.activityDisplayName)
    }

    @Test
    fun untaggedV3EntryHasNullTagFieldsAndRecordOutcomeStoresExtraction() = runBlocking<Unit> {
        val v3 = repository.acceptInterpretation(
            capture(), record(null, null, null, null), ActivityTarget.New("Walk dog"),
            Instant.ofEpochMilli(CAPTURED), TimePrecision.EXACT, ActivityState.COMPLETED,
        )
        val occurrence = assertNotNull(repository.loadHistory().single().occurrence)
        assertEquals(v3, occurrence.occurrenceId)
        assertNull(occurrence.subjectName)
        assertNull(occurrence.actionName)
        assertNull(occurrence.durationSeconds)
        assertNull(repository.getOccurrence(v3)?.durationSeconds)
        val activity = assertNotNull(repository.getActivity(occurrence.activityId))
        assertNull(activity.subjectId)
        assertNull(activity.actionId)
        assertEquals(1, sql.count("interpretations", "extracted_subject IS NULL AND resolved_duration_seconds IS NULL"))

        val pending = capture()
        repository.recordOutcome(pending, record("the gutters", "clean", "an hour", 3600), ProcessingState.NEEDS_REVIEW)
        assertEquals(
            1,
            sql.count(
                "interpretations",
                "raw_capture_id = ? AND extracted_subject = 'the gutters' AND extracted_action = 'clean' " +
                    "AND duration_expression = 'an hour' AND resolved_duration_seconds = 3600",
                pending,
            ),
        )
    }

    @Test
    fun historyWithTagNamesIsStillOneStatement() {
        val querySql = DaoWriteSurfaceGuardTest.querySql(RawCaptureDao::class.java, "loadHistory")
        assertTrue(querySql.contains("LEFT JOIN subjects"), querySql)
        assertTrue(querySql.contains("LEFT JOIN actions"), querySql)
        assertTrue(!querySql.contains(":"), "the query takes no per-row parameter: $querySql")
    }

    private companion object {
        const val NOW = 1_760_000_000_123L
        const val CAPTURED = 1_759_999_000_456L
        const val MERGED_SUBJECT = "subject-merged"
        const val MERGED_ACTION = "action-merged"
        val WRITTEN_TABLES = listOf(
            "raw_captures", "subjects", "actions", "subject_aliases", "action_aliases",
            "canonical_activities", "interpretations", "activity_occurrences", "corrections",
        )
    }
}
