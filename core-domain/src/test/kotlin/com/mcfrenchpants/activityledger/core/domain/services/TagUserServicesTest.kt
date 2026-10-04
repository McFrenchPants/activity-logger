package com.mcfrenchpants.activityledger.core.domain.services

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
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityView
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.DurationChange
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.OccurrenceView
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records every tag write; reads and the activity contract come from the in-memory fake or scripts. */
private class RecordingLedger(
    private val base: InMemoryActivityRepository,
    var catalog: TagCatalog,
) : LedgerRepository, ActivityRepository by base {
    val acceptRequests = mutableListOf<TaggedAcceptRequest>()
    val correctRequests = mutableListOf<TagCorrectionRequest>()
    val renames = mutableListOf<Triple<TagKind, String, String>>()
    val merges = mutableListOf<Triple<TagKind, String, String>>()
    var captureWords: ExtractedWords? = null
    var occurrenceWords: ExtractedWords? = null
    var captureWordsReads = 0
    var occurrenceView: OccurrenceView? = null
    var activityView: ActivityView? = null
    var correctOutcome: CorrectionOutcome = CorrectionOutcome.Applied("corr-1")
    var renameOutcome: RenameOutcome = RenameOutcome.Renamed
    var failWithIllegalArgument = false

    override suspend fun loadTagCatalog(): TagCatalog = catalog

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String {
        acceptRequests += request
        return base.acceptInterpretation(
            request.captureId, request.interpretation, ActivityTarget.New("pair ${acceptRequests.size}"),
            request.occurredAt, request.timePrecision, request.activityState,
        )
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome {
        correctRequests += request
        return correctOutcome
    }

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome {
        renames += Triple(kind, tagId, newDisplayName)
        if (failWithIllegalArgument) throw IllegalArgumentException("unknown tag $tagId")
        return renameOutcome
    }

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome {
        merges += Triple(kind, fromTagId, intoTagId)
        if (failWithIllegalArgument) throw IllegalArgumentException("unknown tag")
        return MergeOutcome(movedOccurrences = 3, mergedPairs = 2)
    }

    override suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords? {
        captureWordsReads++
        return captureWords
    }

    override suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords? = occurrenceWords

    override suspend fun getOccurrence(id: String): OccurrenceView? = occurrenceView?.takeIf { it.id == id }

    override suspend fun getActivity(id: String): ActivityView? = activityView?.takeIf { it.id == id }
}

class TagUserServicesTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val nowAt: Instant = capturedAt.plusSeconds(60)
    private val clock = MutableClock(nowAt, zone)
    private val base = InMemoryActivityRepository(clock)

    private val hotTub = KnownTag("subject-1", TagKind.SUBJECT, "Hot tub", emptyList())
    private val furnace = KnownTag("subject-2", TagKind.SUBJECT, "Furnace", emptyList())
    private val changeFilter = KnownTag("action-1", TagKind.ACTION, "Change filter", emptyList())
    private val drain = KnownTag("action-2", TagKind.ACTION, "Drain", emptyList())
    private val catalog = TagCatalog(
        listOf(hotTub, furnace),
        listOf(changeFilter, drain),
        listOf(KnownPair("subject-1", "action-1"), KnownPair("subject-2", "action-1"), KnownPair("subject-1", "action-2")),
    )
    private val repo = RecordingLedger(base, catalog)
    private val resolution = TaggedResolutionService(repo, clock)
    private val corrections = TaggedCorrectionService(repo, clock)
    private val management = TagManagementService(repo)

    private fun capture(at: Instant = capturedAt): String = runSuspend {
        base.createRawCapture(NewRawCapture(CaptureSource.PHONE_VOICE, null, at, zone, "synthetic words", null, null, ProcessingState.NEEDS_REVIEW))
    }

    private fun refused(result: TaggedResolutionResult): TagRefusal = assertIs<TaggedResolutionResult.Refused>(result).refusal

    private fun correctRefused(result: TaggedCorrectionResult): TagRefusal =
        assertIs<TaggedCorrectionResult.Refused>(result).refusal

    // --- resolve -------------------------------------------------------------

    @Test
    fun `resolve with existing tags saves through acceptTagged with defaults`() {
        val id = capture()
        repo.captureWords = ExtractedWords("tub", "swap filter", "20 minutes")
        val result = runSuspend {
            resolution.resolve(id, TagChoice.Existing("subject-1"), TagChoice.Existing("action-1"), durationSeconds = 1200)
        }

        val resolved = assertIs<TaggedResolutionResult.Resolved>(result)
        val request = repo.acceptRequests.single()
        assertEquals(id, request.captureId)
        assertEquals(TagTarget.Existing("subject-1"), request.subject)
        assertEquals(TagTarget.Existing("action-1"), request.action)
        assertEquals(capturedAt, request.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, request.timePrecision)
        assertEquals(ActivityState.COMPLETED, request.activityState)
        assertEquals(1200L, request.durationSeconds)
        assertEquals(resolved.occurrenceId, base.occurrences.single().id)

        val record = request.interpretation
        assertEquals(ValidationStatus.VALID, record.validationStatus)
        assertNull(record.validationReason)
        assertEquals(USER_RESOLUTION_INTERPRETER_VERSION, record.interpreterVersion)
        assertEquals(USER_RESOLUTION_PROMPT_VERSION, record.promptVersion)
        assertEquals(USER_RESOLUTION_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(InterpretationOperation.LOG_ACTIVITY, record.operation)
        assertEquals(ActivityResolution.UNRESOLVED, record.activityResolution)
        assertEquals(nowAt, record.createdAt)
        assertNull(record.modelConfidenceBand)
        assertEquals("tub", record.extractedSubject)
        assertEquals("swap filter", record.extractedAction)
        assertEquals("20 minutes", record.durationExpression)
        assertEquals(1200L, record.resolvedDurationSeconds)
        // "tub" and "swap filter" are safe words for tags that are new to them.
        assertEquals("tub", request.learnSubjectAlias)
        assertEquals("swap filter", request.learnActionAlias)
    }

    @Test
    fun `resolve does not learn words that already mean another tag`() {
        val id = capture()
        repo.captureWords = ExtractedWords("furnace", "change filter", null)
        runSuspend { resolution.resolve(id, TagChoice.Existing("subject-1"), TagChoice.Existing("action-1")) }
        val request = repo.acceptRequests.single()
        assertNull(request.learnSubjectAlias, "furnace is another subject, never an alias of hot tub")
        assertNull(request.learnActionAlias, "the words equal the chosen action's own name")
    }

    @Test
    fun `resolve passes explicit words and trims new names`() {
        val id = capture()
        repo.captureWords = ExtractedWords("never read", "never read", null)
        runSuspend {
            resolution.resolve(
                id, TagChoice.New("  Sauna  "), TagChoice.Existing("action-2"),
                time = OccurrenceTime(capturedAt.minusSeconds(600), TimePrecision.EXACT),
                activityState = ActivityState.IN_PROGRESS,
                words = ExtractedWords("steam room", null, null),
            )
        }
        val request = repo.acceptRequests.single()
        assertEquals(TagTarget.New("Sauna"), request.subject)
        assertEquals(capturedAt.minusSeconds(600), request.occurredAt)
        assertEquals(TimePrecision.EXACT, request.timePrecision)
        assertEquals(ActivityState.IN_PROGRESS, request.activityState)
        assertEquals("steam room", request.learnSubjectAlias)
        assertEquals(0, repo.captureWordsReads, "explicit words replace the stored read")
    }

    @Test
    fun `resolve refusals write nothing`() {
        val id = capture()
        val existing = TagChoice.Existing("subject-1")
        val existingAction = TagChoice.Existing("action-1")
        assertEquals(TagRefusal.CaptureNotFound, refused(runSuspend { resolution.resolve("nope", existing, existingAction) }))
        assertEquals(
            TagRefusal.OccurredAfterNow,
            refused(runSuspend { resolution.resolve(id, existing, existingAction, time = OccurrenceTime(nowAt.plusSeconds(1), TimePrecision.EXACT)) }),
        )
        assertEquals(TagRefusal.InvalidDuration, refused(runSuspend { resolution.resolve(id, existing, existingAction, durationSeconds = -1) }))
        assertEquals(TagRefusal.TagNotFound, refused(runSuspend { resolution.resolve(id, TagChoice.Existing("gone"), existingAction) }))
        // An action id is not a subject id (wrong kind).
        assertEquals(TagRefusal.TagNotFound, refused(runSuspend { resolution.resolve(id, TagChoice.Existing("action-1"), existingAction) }))
        assertEquals(TagRefusal.TagNotFound, refused(runSuspend { resolution.resolve(id, existing, TagChoice.Existing("gone")) }))
        assertEquals(
            TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_TIME_WORD),
            refused(runSuspend { resolution.resolve(id, TagChoice.New("tub today"), existingAction) }),
        )
        assertEquals(
            TagRefusal.InvalidName(NewActivityNameCheck.Reason.EMPTY),
            refused(runSuspend { resolution.resolve(id, existing, TagChoice.New("   ")) }),
        )
        assertTrue(repo.acceptRequests.isEmpty())
        assertTrue(base.occurrences.isEmpty())
    }

    @Test
    fun `resolving twice refuses the second and creates no second occurrence`() {
        val id = capture()
        runSuspend { resolution.resolve(id, TagChoice.Existing("subject-1"), TagChoice.Existing("action-1")) }
        val again = runSuspend { resolution.resolve(id, TagChoice.Existing("subject-1"), TagChoice.Existing("action-1")) }
        assertEquals(TagRefusal.CaptureAlreadyHasOccurrence, refused(again))
        assertEquals(1, repo.acceptRequests.size)
        assertEquals(1, base.occurrences.size)
    }

    @Test
    fun `a new name equal to an existing tag is not refused`() {
        val id = capture()
        val result = runSuspend { resolution.resolve(id, TagChoice.New("hot TUB"), TagChoice.Existing("action-1")) }
        assertIs<TaggedResolutionResult.Resolved>(result)
        assertEquals(TagTarget.New("hot TUB"), repo.acceptRequests.single().subject)
    }

    // --- correct -------------------------------------------------------------

    private fun occurrence(visibility: VisibilityStatus = VisibilityStatus.ACTIVE) = OccurrenceView(
        id = "occ-1", canonicalActivityId = "pair-1", rawCaptureId = "cap-1", effectiveInterpretationId = "int-1",
        capturedAt = capturedAt, occurredAt = capturedAt, timePrecision = TimePrecision.EXACT,
        activityState = ActivityState.COMPLETED, visibilityStatus = visibility,
    )

    private fun pair(subjectId: String?, actionId: String?) = ActivityView(
        "pair-1", "Pair", "pair", CanonicalActivityStatus.ACTIVE, subjectId, actionId,
    )

    private fun tagged() {
        repo.occurrenceView = occurrence()
        repo.activityView = pair("subject-1", "action-1")
    }

    @Test
    fun `correct subject only keeps the action and learns for the changed side only`() {
        tagged()
        repo.occurrenceWords = ExtractedWords("tub cover", "change filter", null)
        val result = runSuspend { corrections.correct("occ-1", subject = TagChoice.Existing("subject-2"), reason = "synthetic") }

        assertEquals("corr-1", assertIs<TaggedCorrectionResult.Applied>(result).correctionId)
        val request = repo.correctRequests.single()
        assertEquals(TagTarget.Existing("subject-2"), request.subject)
        assertNull(request.action)
        assertNull(request.duration)
        assertEquals("tub cover", request.learnSubjectAlias)
        assertNull(request.learnActionAlias, "the action did not change")
        assertEquals(CorrectionSource.USER, request.source)
        assertEquals("synthetic", request.reason)
        assertEquals(nowAt, request.now)
    }

    @Test
    fun `correct with a new name that equals the current tag learns nothing`() {
        tagged()
        repo.occurrenceWords = ExtractedWords("tub", "swap filter", null)
        repo.correctOutcome = CorrectionOutcome.NothingChanged
        val result = runSuspend { corrections.correct("occ-1", subject = TagChoice.New(" hot tub ")) }
        assertEquals(TaggedCorrectionResult.NothingChanged, result)
        val request = repo.correctRequests.single()
        assertEquals(TagTarget.New("hot tub"), request.subject)
        assertNull(request.learnSubjectAlias)
        assertNull(request.learnActionAlias)
    }

    @Test
    fun `correct duration only passes the change and skips the alias lookup`() {
        tagged()
        runSuspend { corrections.correct("occ-1", duration = DurationChange(900)) }
        val request = repo.correctRequests.single()
        assertEquals(DurationChange(900), request.duration)
        assertNull(request.subject)
        assertNull(request.action)
        assertNull(request.learnSubjectAlias)
        assertNull(request.learnActionAlias)
        runSuspend { corrections.correct("occ-1", duration = DurationChange(null)) }
        assertEquals(DurationChange(null), repo.correctRequests.last().duration)
    }

    @Test
    fun `correct refusals write nothing`() {
        assertEquals(TagRefusal.OccurrenceNotFound, correctRefused(runSuspend { corrections.correct("nope", subject = TagChoice.Existing("subject-1")) }))
        repo.occurrenceView = occurrence(VisibilityStatus.HIDDEN)
        repo.activityView = pair("subject-1", "action-1")
        assertEquals(TagRefusal.OccurrenceHidden, correctRefused(runSuspend { corrections.correct("occ-1", subject = TagChoice.Existing("subject-2")) }))
        tagged()
        assertEquals(TagRefusal.InvalidDuration, correctRefused(runSuspend { corrections.correct("occ-1", duration = DurationChange(-5)) }))
        assertEquals(TagRefusal.TagNotFound, correctRefused(runSuspend { corrections.correct("occ-1", subject = TagChoice.Existing("gone")) }))
        assertEquals(TagRefusal.TagNotFound, correctRefused(runSuspend { corrections.correct("occ-1", action = TagChoice.Existing("subject-1")) }))
        assertEquals(
            TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_FILLER_WORD),
            correctRefused(runSuspend { corrections.correct("occ-1", action = TagChoice.New("stuff")) }),
        )
        assertTrue(repo.correctRequests.isEmpty())
    }

    @Test
    fun `an untagged entry needs both tags`() {
        repo.occurrenceView = occurrence()
        repo.activityView = pair(null, null)
        assertEquals(TagRefusal.BothTagsNeeded, correctRefused(runSuspend { corrections.correct("occ-1", subject = TagChoice.Existing("subject-1")) }))
        assertEquals(TagRefusal.BothTagsNeeded, correctRefused(runSuspend { corrections.correct("occ-1", duration = DurationChange(60)) }))
        assertTrue(repo.correctRequests.isEmpty())

        val result = runSuspend {
            corrections.correct("occ-1", subject = TagChoice.Existing("subject-1"), action = TagChoice.Existing("action-2"))
        }
        assertIs<TaggedCorrectionResult.Applied>(result)
        assertEquals(TagTarget.Existing("action-2"), repo.correctRequests.single().action)
    }

    // --- tag management -------------------------------------------------------

    @Test
    fun `rename trims and maps outcomes`() {
        assertEquals(TagRenameResult.Renamed, runSuspend { management.rename(TagKind.SUBJECT, "subject-1", "  Spa  ") })
        assertEquals(Triple(TagKind.SUBJECT, "subject-1", "Spa"), repo.renames.single())
        repo.renameOutcome = RenameOutcome.NothingChanged
        assertEquals(TagRenameResult.NothingChanged, runSuspend { management.rename(TagKind.SUBJECT, "subject-1", "Spa") })
        repo.renameOutcome = RenameOutcome.ConflictsWith("subject-2")
        assertEquals(TagRenameResult.NameInUse("subject-2"), runSuspend { management.rename(TagKind.SUBJECT, "subject-1", "Furnace") })
    }

    @Test
    fun `rename refuses bad names and unknown tags`() {
        val blank = runSuspend { management.rename(TagKind.SUBJECT, "subject-1", "   ") }
        assertEquals(TagRenameResult.Refused(TagRefusal.InvalidName(NewActivityNameCheck.Reason.EMPTY)), blank)
        val time = runSuspend { management.rename(TagKind.ACTION, "action-1", "do it today") }
        assertEquals(TagRenameResult.Refused(TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_TIME_WORD)), time)
        assertTrue(repo.renames.isEmpty())
        repo.failWithIllegalArgument = true
        assertEquals(TagRenameResult.Refused(TagRefusal.TagNotFound), runSuspend { management.rename(TagKind.SUBJECT, "gone", "Spa") })
    }

    @Test
    fun `merge maps results and refusals`() {
        assertEquals(TagMergeResult.Merged(3, 2), runSuspend { management.merge(TagKind.ACTION, "action-2", "action-1") })
        assertEquals(Triple(TagKind.ACTION, "action-2", "action-1"), repo.merges.single())
        assertEquals(TagMergeResult.Refused(TagRefusal.SameTag), runSuspend { management.merge(TagKind.ACTION, "action-1", "action-1") })
        assertEquals(1, repo.merges.size, "same tag never reaches the repository")
        repo.failWithIllegalArgument = true
        assertEquals(TagMergeResult.Refused(TagRefusal.TagNotFound), runSuspend { management.merge(TagKind.SUBJECT, "subject-1", "gone") })
    }

    @Test
    fun `listTags counts pairs per tag`() {
        val subjects = runSuspend { management.listTags(TagKind.SUBJECT) }
        assertEquals(listOf(ManagedTag("subject-1", "Hot tub", emptyList(), 2), ManagedTag("subject-2", "Furnace", emptyList(), 1)), subjects)
        val actions = runSuspend { management.listTags(TagKind.ACTION) }
        assertEquals(listOf(2, 1), actions.map { it.pairCount })
    }
}
