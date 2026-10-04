package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.DurationChange
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.services.TagManagementService
import com.mcfrenchpants.activityledger.core.domain.services.TagMergeResult
import com.mcfrenchpants.activityledger.core.domain.services.TagRefusal
import com.mcfrenchpants.activityledger.core.domain.services.TagRenameResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The tag resolution, correction and management services (TG3.2, ADR-044) and the two
 * extracted-words reads, against the real (in-memory) Room ledger. Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class RoomTagUserServicesTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var resolution: TaggedResolutionService
    private lateinit var corrections: TaggedCorrectionService
    private lateinit var management: TagManagementService
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x50_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC"))
        repository = RoomActivityRepository(db, ids, clock, Dispatchers.Unconfined)
        resolution = TaggedResolutionService(repository, clock)
        corrections = TaggedCorrectionService(repository, clock)
        management = TagManagementService(repository)
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
        subject: String? = "synthetic subject",
        action: String? = "synthetic action",
        validation: ValidationStatus = ValidationStatus.VALID,
        createdAt: Long = NOW - 5,
        duration: String? = null,
    ) = InterpretationRecord(
        createdAt = Instant.ofEpochMilli(createdAt),
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 4,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.UNRESOLVED,
        matchedActivityId = null,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = null,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = validation,
        validationReason = null,
        extractedSubject = subject,
        extractedAction = action,
        durationExpression = duration,
        resolvedDurationSeconds = null,
    )

    /** A capture waiting for the user, with an earlier interpretation holding the extracted words. */
    private suspend fun waiting(subject: String?, action: String?): String {
        val id = capture()
        repository.recordOutcome(id, record(subject, action, ValidationStatus.NEEDS_REVIEW), ProcessingState.NEEDS_REVIEW)
        return id
    }

    private suspend fun acceptNew(subject: String, action: String, subjectWords: String = "synthetic subject", actionWords: String = "synthetic action"): String =
        repository.acceptTagged(
            TaggedAcceptRequest(
                captureId = capture(),
                interpretation = record(subjectWords, actionWords),
                subject = TagTarget.New(subject),
                action = TagTarget.New(action),
                occurredAt = Instant.ofEpochMilli(CAPTURED - 60_000),
                timePrecision = TimePrecision.APPROXIMATE,
                activityState = ActivityState.COMPLETED,
                durationSeconds = null,
            ),
        )

    private suspend fun acceptUntagged(): String = repository.acceptInterpretation(
        capture(), record(null, null), ActivityTarget.New("Walk dog"),
        Instant.ofEpochMilli(CAPTURED), TimePrecision.EXACT, ActivityState.COMPLETED,
    )

    private fun strings(query: String, vararg args: Any?): List<String> =
        sql.query(query, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun string(query: String, vararg args: Any?): String? = strings(query, *args).firstOrNull()

    private fun pairOf(occurrenceId: String): Pair<String?, String?> {
        val activity = checkNotNull(string("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", occurrenceId))
        return string("SELECT subject_id FROM canonical_activities WHERE id = ?", activity) to
            string("SELECT action_id FROM canonical_activities WHERE id = ?", activity)
    }

    private suspend fun subjectId(name: String): String =
        checkNotNull(repository.loadTagCatalog().subjects.single { it.displayName == name }).id

    private suspend fun actionId(name: String): String =
        checkNotNull(repository.loadTagCatalog().actions.single { it.displayName == name }).id

    private fun subjectAliases(subjectId: String) =
        strings("SELECT alias_text FROM subject_aliases WHERE subject_id = ? ORDER BY alias_text", subjectId)

    private fun actionAliases(actionId: String) =
        strings("SELECT alias_text FROM action_aliases WHERE action_id = ? ORDER BY alias_text", actionId)

    private fun refusedOf(result: TaggedResolutionResult) = assertIs<TaggedResolutionResult.Refused>(result).refusal

    private fun refusedOf(result: TaggedCorrectionResult) = assertIs<TaggedCorrectionResult.Refused>(result).refusal

    // --- extracted-words reads ------------------------------------------------

    @Test
    fun extractedWordsReadsReturnTheLatestWordsAndNullForUnknownIds() = runBlocking<Unit> {
        assertNull(repository.loadExtractedWordsForCapture("no-such-capture"))
        assertNull(repository.loadExtractedWordsForOccurrence("no-such-occurrence"))

        val id = capture()
        assertNull(repository.loadExtractedWordsForCapture(id), "no interpretation yet")
        repository.recordOutcome(id, record("first subject", "first action", ValidationStatus.NEEDS_REVIEW, createdAt = NOW - 50), ProcessingState.NEEDS_REVIEW)
        repository.recordOutcome(id, record("second subject", null, ValidationStatus.NEEDS_REVIEW, createdAt = NOW - 40, duration = "20 min"), ProcessingState.NEEDS_REVIEW)
        // A newer interpretation without any extracted words does not hide the older words.
        repository.recordOutcome(id, record(null, null, ValidationStatus.INVALID, createdAt = NOW - 30), ProcessingState.NEEDS_REVIEW)
        assertEquals(ExtractedWords("second subject", null, "20 min"), repository.loadExtractedWordsForCapture(id))

        val occurrence = acceptNew("Hot tub", "Drain", "tub words", "drain words")
        assertEquals(ExtractedWords("tub words", "drain words", null), repository.loadExtractedWordsForOccurrence(occurrence))
        val untagged = acceptUntagged()
        assertNull(repository.loadExtractedWordsForOccurrence(untagged), "an untagged entry has no words")
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    fun resolveWithExistingTagsSavesTheOccurrenceAndLearnsASafeAlias() = runBlocking<Unit> {
        acceptNew("Hot tub", "Change filter")
        val hotTub = subjectId("Hot tub")
        val changeFilter = actionId("Change filter")
        val id = waiting("jacuzzi", "change filter")

        val result = resolution.resolve(id, TagChoice.Existing(hotTub), TagChoice.Existing(changeFilter), durationSeconds = 600)

        val occurrenceId = assertIs<TaggedResolutionResult.Resolved>(result).occurrenceId
        assertEquals(hotTub to changeFilter, pairOf(occurrenceId))
        assertEquals(listOf("jacuzzi"), subjectAliases(hotTub), "the user's own safe wording is learned")
        assertEquals(emptyList(), actionAliases(changeFilter), "the words equal the action's own name")
        assertEquals(ProcessingState.PERSISTED, repository.getCapture(id)?.processingState)
        assertEquals(600L, repository.getOccurrence(occurrenceId)?.durationSeconds)
        assertEquals(TimePrecision.INFERRED_NOW, repository.getOccurrence(occurrenceId)?.timePrecision)
        assertEquals(2, sql.count("interpretations", "raw_capture_id = ?", id), "the earlier interpretation is kept")
        assertEquals(1, sql.count("activity_occurrences", "raw_capture_id = ?", id))
    }

    @Test
    fun resolveNeverLearnsWordsThatAlreadyMeanAnotherTag() = runBlocking<Unit> {
        acceptNew("Hot tub", "Change filter")
        acceptNew("Furnace", "Change filter")
        val hotTub = subjectId("Hot tub")
        val id = waiting("furnace", "change filter")

        assertIs<TaggedResolutionResult.Resolved>(
            resolution.resolve(id, TagChoice.Existing(hotTub), TagChoice.Existing(actionId("Change filter"))),
        )
        assertEquals(emptyList(), subjectAliases(hotTub), "furnace is another subject; never an alias of hot tub")
    }

    @Test
    fun resolveWithNewTagsCreatesThemAndLearnsTheWords() = runBlocking<Unit> {
        val id = waiting("steam room", "scrub")

        val result = resolution.resolve(id, TagChoice.New(" Sauna "), TagChoice.New("Clean"))

        val occurrenceId = assertIs<TaggedResolutionResult.Resolved>(result).occurrenceId
        val sauna = subjectId("Sauna")
        val clean = actionId("Clean")
        assertEquals(sauna to clean, pairOf(occurrenceId))
        assertEquals(1, sql.count("subjects"))
        assertEquals(1, sql.count("actions"))
        assertEquals(1, sql.count("canonical_activities"))
        assertEquals(listOf("steam room"), subjectAliases(sauna))
        assertEquals(listOf("scrub"), actionAliases(clean))
    }

    @Test
    fun resolvingTwiceIsRefusedAndAddsNoRows() = runBlocking<Unit> {
        val id = waiting("steam room", "scrub")
        assertIs<TaggedResolutionResult.Resolved>(resolution.resolve(id, TagChoice.New("Sauna"), TagChoice.New("Clean")))
        val counts = listOf("subjects", "actions", "canonical_activities", "interpretations", "activity_occurrences", "subject_aliases", "action_aliases")
            .map { sql.count(it) }

        val again = resolution.resolve(id, TagChoice.New("Sauna"), TagChoice.New("Wash"))

        assertEquals(TagRefusal.CaptureAlreadyHasOccurrence, refusedOf(again))
        assertEquals(counts, listOf("subjects", "actions", "canonical_activities", "interpretations", "activity_occurrences", "subject_aliases", "action_aliases").map { sql.count(it) })
    }

    @Test
    fun resolveRefusalsWriteNothing() = runBlocking<Unit> {
        val id = waiting("steam room", "scrub")
        assertEquals(TagRefusal.CaptureNotFound, refusedOf(resolution.resolve("nope", TagChoice.New("Sauna"), TagChoice.New("Clean"))))
        assertEquals(TagRefusal.TagNotFound, refusedOf(resolution.resolve(id, TagChoice.Existing("gone"), TagChoice.New("Clean"))))
        assertEquals(
            TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_FILLER_WORD),
            refusedOf(resolution.resolve(id, TagChoice.New("Sauna"), TagChoice.New("clean stuff"))),
        )
        assertEquals(TagRefusal.InvalidDuration, refusedOf(resolution.resolve(id, TagChoice.New("Sauna"), TagChoice.New("Clean"), durationSeconds = -1)))
        assertEquals(0, sql.count("subjects"))
        assertEquals(0, sql.count("activity_occurrences"))
        assertEquals(1, sql.count("interpretations"))
    }

    // --- correct ---------------------------------------------------------------

    @Test
    fun correctSubjectOnlyKeepsTheActionAndLearnsForTheChangedSideOnly() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "Change filter", "tub cover", "swap filter")
        acceptNew("Hot tub", "Drain")
        val hotTub = subjectId("Hot tub")
        val changeFilter = actionId("Change filter")

        val result = corrections.correct(entry, subject = TagChoice.Existing(hotTub), reason = "synthetic")

        assertIs<TaggedCorrectionResult.Applied>(result)
        assertEquals(hotTub to changeFilter, pairOf(entry))
        assertEquals(1, sql.count("corrections"))
        assertEquals(listOf("tub cover"), subjectAliases(hotTub))
        assertEquals(emptyList(), actionAliases(changeFilter), "the action did not change")
    }

    @Test
    fun correctToANewNameThatConvergesOnTheCurrentTagChangesNothing() = runBlocking<Unit> {
        val entry = acceptNew("Hot tub", "Change filter", "tub", "swap filter")
        val aliasesBefore = sql.count("subject_aliases")

        val result = corrections.correct(entry, subject = TagChoice.New("  hot TUB "))

        assertEquals(TaggedCorrectionResult.NothingChanged, result)
        assertEquals(0, sql.count("corrections"))
        assertEquals(aliasesBefore, sql.count("subject_aliases"))
        assertEquals(1, sql.count("subjects"))
    }

    @Test
    fun correctRefusesHiddenAndMissingOccurrences() = runBlocking<Unit> {
        val entry = acceptNew("Hot tub", "Change filter")
        repository.hideOccurrence(entry)
        assertEquals(TagRefusal.OccurrenceHidden, refusedOf(corrections.correct(entry, subject = TagChoice.New("Sauna"))))
        assertEquals(TagRefusal.OccurrenceNotFound, refusedOf(corrections.correct("nope", subject = TagChoice.New("Sauna"))))
        assertEquals(0, sql.count("corrections"))
        assertEquals(1, sql.count("subjects"))
    }

    @Test
    fun correctOfAnUntaggedEntryNeedsBothTags() = runBlocking<Unit> {
        val entry = acceptUntagged()
        acceptNew("Hot tub", "Drain")
        val hotTub = subjectId("Hot tub")
        val drain = actionId("Drain")

        assertEquals(TagRefusal.BothTagsNeeded, refusedOf(corrections.correct(entry, subject = TagChoice.Existing(hotTub))))
        assertEquals(0, sql.count("corrections"))

        val result = corrections.correct(entry, subject = TagChoice.Existing(hotTub), action = TagChoice.Existing(drain))
        assertIs<TaggedCorrectionResult.Applied>(result)
        assertEquals(hotTub to drain, pairOf(entry))
        assertEquals(1, sql.count("corrections"))
    }

    @Test
    fun correctDurationOnly() = runBlocking<Unit> {
        val entry = acceptNew("Hot tub", "Drain")
        val result = corrections.correct(entry, duration = DurationChange(1800))
        assertIs<TaggedCorrectionResult.Applied>(result)
        assertEquals(1800L, repository.getOccurrence(entry)?.durationSeconds)
        assertEquals(TagRefusal.InvalidDuration, refusedOf(corrections.correct(entry, duration = DurationChange(-1))))
        assertEquals(1, sql.count("corrections"))
    }

    // --- management ------------------------------------------------------------

    @Test
    fun renameHappyPathConflictNothingChangedAndBadNames() = runBlocking<Unit> {
        acceptNew("Hot tub", "Drain")
        acceptNew("Furnace", "Drain")
        val hotTub = subjectId("Hot tub")
        val furnace = subjectId("Furnace")

        assertEquals(TagRenameResult.NameInUse(furnace), management.rename(TagKind.SUBJECT, hotTub, " furnace "))
        assertEquals(TagRenameResult.NothingChanged, management.rename(TagKind.SUBJECT, hotTub, "Hot tub"))
        assertIs<TagRenameResult.Refused>(management.rename(TagKind.SUBJECT, hotTub, "   "))
        assertEquals(
            TagRenameResult.Refused(TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_TIME_WORD)),
            management.rename(TagKind.SUBJECT, hotTub, "tub today"),
        )
        assertEquals(TagRenameResult.Refused(TagRefusal.TagNotFound), management.rename(TagKind.SUBJECT, "gone", "Spa"))
        assertEquals(TagRenameResult.Renamed, management.rename(TagKind.SUBJECT, hotTub, "  Spa "))
        assertEquals("Spa", string("SELECT display_name FROM subjects WHERE id = ?", hotTub))
    }

    @Test
    fun mergeMovesPairsAndRefusesSameTagAndMergedTargets() = runBlocking<Unit> {
        val a = acceptNew("Hot tub", "Drain")
        val b = acceptNew("Spa", "Drain")
        val hotTub = subjectId("Hot tub")
        val spa = subjectId("Spa")

        assertEquals(TagMergeResult.Refused(TagRefusal.SameTag), management.merge(TagKind.SUBJECT, hotTub, hotTub))
        val merged = assertIs<TagMergeResult.Merged>(management.merge(TagKind.SUBJECT, spa, hotTub))
        assertEquals(1, merged.movedOccurrences)
        assertEquals(1, merged.mergedPairs)
        assertEquals(hotTub, pairOf(b).first)
        assertEquals(hotTub, pairOf(a).first)
        // The merged-away tag can no longer be a merge target.
        assertEquals(TagMergeResult.Refused(TagRefusal.TagNotFound), management.merge(TagKind.SUBJECT, hotTub, spa))
    }

    @Test
    fun listTagsReturnsNamesAliasesAndPairCounts() = runBlocking<Unit> {
        acceptNew("Hot tub", "Drain")
        acceptNew("Hot tub", "Change filter")
        acceptNew("Furnace", "Change filter")
        val furnace = subjectId("Furnace")
        // Rename gives the old name as an alias.
        management.rename(TagKind.SUBJECT, furnace, "Boiler")

        val subjects = management.listTags(TagKind.SUBJECT)
        assertEquals(listOf("Boiler", "Hot tub"), subjects.map { it.name })
        assertEquals(listOf(1, 2), subjects.map { it.pairCount })
        assertEquals(listOf("Furnace"), subjects.first().aliases)
        val actions = management.listTags(TagKind.ACTION)
        assertEquals(listOf("Change filter", "Drain"), actions.map { it.name })
        assertEquals(listOf(2, 1), actions.map { it.pairCount })
    }

    private companion object {
        const val NOW = 1_760_000_000_123L
        const val CAPTURED = 1_759_999_000_456L
    }
}
