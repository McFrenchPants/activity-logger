package com.mcfrenchpants.activityledger.ui.log

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side tests of [LogViewModel] over the REAL ledger (an in-memory Room database from
 * `createInMemoryActivityRepository`), the real tagged orchestrator and services, a scripted
 * extractor, a fixed clock and a test Main dispatcher (virtual time drives the undo window).
 * Tag meaning is never re-invented here: every outcome comes from the real domain rules.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val repository = RecordingLedger(ledger)
    private val extractor = ScriptedExtractor()
    private val transcriber = ScriptedTranscriber()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()
    private var aiReady = true
    private var readyChecks = 0

    /** Words and answers: an entry whose subject and action are both new, with a duration. */
    private val filterWords = "Changed the hot tub filter for 30 minutes"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        extractor.on(filterWords, Extracted.log("Hot tub", "Change filter", duration = "30 minutes"))
    }

    @After
    fun tearDown() {
        store.clear() // cancels viewModelScope, including any undo window still draining
        Dispatchers.resetMain()
    }

    private fun viewModel(): LogViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(
                repository = repository,
                orchestrator = TaggedCaptureOrchestrator(repository, extractor, clock),
                resolution = TaggedResolutionService(repository, clock),
                correction = TaggedCorrectionService(repository, clock),
                transcriber = transcriber,
                clock = clock,
                isAiReady = { readyChecks++; aiReady },
                zone = { zone },
                locale = { Locale.US },
            ) as T
        }
        return ViewModelProvider(store, factory)[LogViewModel::class.java]
    }

    private fun started(): LogViewModel =
        viewModel().also {
            it.onStart()
            scheduler.runCurrent()
        }

    private fun LogViewModel.type(text: String) {
        onInputChange(text)
        submit()
        scheduler.runCurrent()
    }

    /** Starts one listening session that emits [events] as soon as it is collected. */
    private fun LogViewModel.listen(vararg events: SpeechEvent) {
        transcriber.willEmit(*events)
        startListening()
        scheduler.runCurrent()
    }

    private fun partial(text: String) = SpeechEvent.PartialTranscript(text)

    private fun final(
        text: String,
        confidence: Float? = null,
        alternatives: List<String> = emptyList(),
    ) = SpeechEvent.FinalTranscript(text, confidence, alternatives)

    private fun failed(failure: SpeechFailure) = SpeechEvent.Failed(failure)

    private val LogViewModel.card get() = state.value.card

    private fun <T> read(block: suspend () -> T): T = runSuspend(block)

    private fun catalog() = read { ledger.loadTagCatalog() }

    private fun subjectNames() = catalog().subjects.map { it.displayName }

    private fun actionNames() = catalog().actions.map { it.displayName }

    private fun subjectId(name: String) = catalog().subjects.single { it.displayName == name }.id

    private fun actionId(name: String) = catalog().actions.single { it.displayName == name }.id

    private fun history() = read { ledger.loadHistory() }

    private fun onlyCaptureId(): String = history().single().captureId

    private fun occurrenceOf(captureId: String) =
        read { ledger.getOccurrence(history().single { it.captureId == captureId }.occurrence!!.occurrenceId) }!!

    private fun seed(subject: String, action: String) = seedTags(ledger, clock, zone, subject, action)

    /** Scripts [words] to extract as [subject] / [action] and types them. */
    private fun LogViewModel.typeEntry(
        words: String,
        subject: String?,
        action: String?,
        time: String? = null,
        duration: String? = null,
        state: ActivityState? = ActivityState.COMPLETED,
    ) {
        extractor.on(words, Extracted.log(subject, action, state, time, duration))
        type(words)
    }

    // ---- Raw capture ------------------------------------------------------------------------

    @Test
    fun typedTextIsStoredAsATrimmedPhoneTextCaptureThenExtracted() {
        val vm = started()

        vm.type("   $filterWords  ")

        val stored = repository.created.single()
        assertEquals(CaptureSource.PHONE_TEXT, stored.source)
        assertEquals(LOG_TYPED_SOURCE_SURFACE, stored.sourceSurface)
        assertEquals("log_typed", stored.sourceSurface)
        assertEquals(ProcessingState.CAPTURED, stored.processingState)
        assertEquals(filterWords, stored.rawText)
        assertEquals(now, stored.capturedAt)
        assertEquals(zone, stored.zoneId)
        assertNull(stored.speechConfidence)
        assertNull(stored.speechAlternativesJson)
        assertEquals(filterWords, extractor.received.single().rawText)
        assertEquals("", vm.state.value.input)
        assertFalse(vm.state.value.isCapturing)
    }

    @Test
    fun blankInputIsIgnored() {
        val vm = started()
        vm.type("   ")
        assertTrue(repository.created.isEmpty())
        assertNull(vm.card)
        assertEquals(0, extractor.callCount)
    }

    @Test
    fun submitIsBlockedUntilStartedAndAfterStop() {
        val vm = viewModel()
        vm.type(filterWords)
        assertTrue(repository.created.isEmpty())
        assertFalse(vm.state.value.canSubmit)

        vm.onStart()
        vm.onStop()
        scheduler.runCurrent()
        vm.type(filterWords)
        assertTrue(repository.created.isEmpty())
        assertEquals(filterWords, vm.state.value.input)
    }

    // ---- Saved card -------------------------------------------------------------------------

    @Test
    fun anAutoSavedEntryShowsTheSavedCardWithSubjectActionDurationTimeAndWords() {
        val vm = started()
        vm.type(filterWords)

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Hot tub", saved.subjectName)
        assertEquals("Change filter", saved.actionName)
        assertEquals(1_800L, saved.durationSeconds)
        assertEquals("Today, 8:00 PM", saved.time)
        assertEquals(filterWords, saved.rawText)
        assertEquals(1f, saved.undoFractionRemaining)
        assertEquals(saved.occurrenceId, history().single().occurrence?.occurrenceId)
        assertEquals(1_800L, occurrenceOf(saved.captureId).durationSeconds)
    }

    @Test
    fun anEntryWithoutADurationShowsNoDuration() {
        val vm = started()
        vm.typeEntry("Fed the dog", "Dog", "Feed")
        assertNull(assertIs<ResultCard.Saved>(vm.card).durationSeconds)
    }

    @Test
    fun aRepeatEntryReusesTheSameTags() {
        val vm = started()
        vm.type(filterWords)
        val first = assertIs<ResultCard.Saved>(vm.card)
        val subjectsBefore = catalog().subjects
        val actionsBefore = catalog().actions

        vm.typeEntry("Changed the hot tub filter again for 20 minutes", "Hot tub", "Change filter", duration = "20 minutes")

        val second = assertIs<ResultCard.Saved>(vm.card)
        assertEquals(first.subjectName, second.subjectName)
        assertEquals(first.actionName, second.actionName)
        assertEquals(1_200L, second.durationSeconds)
        assertEquals(subjectsBefore, catalog().subjects)
        assertEquals(actionsBefore, catalog().actions)
        assertEquals(2, history().count { it.occurrence != null })
    }

    @Test
    fun anOmittedSubjectIsFilledInFromTheOnlyKnownCombination() {
        seed("Hot tub", "Change filter")
        val vm = started()
        vm.typeEntry("Changed the filter", null, "Change filter")
        assertEquals("Hot tub", assertIs<ResultCard.Saved>(vm.card).subjectName)
    }

    @Test
    fun alreadyHasOccurrenceShowsTheSavedCardForTheExistingEntry() {
        val resolution = TaggedResolutionService(repository, clock)
        repository.afterCreate = { id -> resolution.resolve(id, TagChoice.New("Hot tub"), TagChoice.New("Change filter")) }
        val vm = started()
        vm.type(filterWords)

        assertEquals(0, extractor.callCount) // the orchestrator returned AlreadyHasOccurrence
        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals(history().single().occurrence?.occurrenceId, saved.occurrenceId)
        assertEquals("Hot tub", saved.subjectName)
    }

    @Test
    fun alreadyHasOccurrenceThatIsHiddenShowsNoCardAndRefreshesRecent() {
        val resolution = TaggedResolutionService(repository, clock)
        repository.afterCreate = { id ->
            val resolved = resolution.resolve(id, TagChoice.New("Hot tub"), TagChoice.New("Change filter"))
            ledger.hideOccurrence(assertIs<TaggedResolutionResult.Resolved>(resolved).occurrenceId)
        }
        val vm = started()
        vm.type(filterWords)

        assertNull(vm.card)
        assertFalse(vm.state.value.isCapturing)
        assertTrue(vm.state.value.recentLoaded)
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun submittingANewCaptureDismissesTheCurrentCardFirst() {
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
        val vm = started()
        vm.type("one")
        assertIs<ResultCard.Check>(vm.card)

        vm.onInputChange(filterWords)
        vm.submit() // not yet run: the old card is already gone and submit is disabled
        assertNull(vm.card)
        assertTrue(vm.state.value.isCapturing)
        assertFalse(vm.state.value.canSubmit)
        vm.submit() // ignored while a capture is running
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        assertEquals(2, repository.created.size)
    }

    // ---- Foreground rule ----------------------------------------------------------------------

    @Test
    fun inFlightCaptureSurvivesStopAndItsCardShowsAfterStart() {
        extractor.holdAnswers()
        val vm = started()
        vm.type(filterWords)
        assertTrue(vm.state.value.isCapturing)

        vm.onStop()
        extractor.release()
        scheduler.runCurrent()

        // Finished while in the background: exactly one outcome, card waiting, undo window paused.
        assertEquals(1, history().count { it.occurrence != null })
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(1f, assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining)

        vm.onStart()
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        assertEquals(1, extractor.callCount)
    }

    @Test
    fun leavingLogDismissesTheSavedCardButTheEntryStays() {
        val vm = started()
        vm.type(filterWords)
        val captureId = onlyCaptureId()
        vm.onLeftLog()
        assertNull(vm.card)
        assertEquals(VisibilityStatus.ACTIVE, occurrenceOf(captureId).visibilityStatus)
    }

    // ---- Saved card: Undo, Change subject, Change action ---------------------------------------

    @Test
    fun undoHidesTheEntryKeepsTheCaptureAndDropsItFromRecent() {
        val vm = started()
        vm.type(filterWords)
        val captureId = onlyCaptureId()
        val occurrenceId = assertIs<ResultCard.Saved>(vm.card).occurrenceId
        assertEquals(1, vm.state.value.recent.size)

        vm.undo()
        scheduler.runCurrent()

        assertNull(vm.card)
        assertEquals(VisibilityStatus.HIDDEN, read { ledger.getOccurrence(occurrenceId) }?.visibilityStatus)
        val capture = assertNotNull(read { ledger.getCapture(captureId) })
        assertEquals(filterWords, capture.rawText)
        assertTrue(vm.state.value.recent.none { it.captureId == captureId })
    }

    @Test
    fun changeSubjectToAnExistingSubjectAppliesOneCorrectionAndRefreshesTheCard() {
        seed("Furnace", "Change filter")
        val vm = started()
        vm.type(filterWords)
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
        val before = assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        val picker = assertNotNull(vm.state.value.picker)
        assertEquals(TagKind.SUBJECT, picker.kind)
        assertEquals(setOf("Furnace", "Hot tub"), picker.tags.map { it.displayName }.toSet())
        vm.onPickerChoice(TagChoice.Existing(subjectId("Furnace")))
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Furnace", saved.subjectName)
        assertEquals("Change filter", saved.actionName)
        assertEquals(before, saved.undoFractionRemaining) // the window stays as is
        assertNull(vm.state.value.picker)
        assertEquals(1, repository.corrections.size)
    }

    @Test
    fun changeActionToANewNameCreatesItAndRefreshesTheCard() {
        val vm = started()
        vm.type(filterWords)

        vm.openPicker(TagKind.ACTION)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.New("Drain"))
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Hot tub", saved.subjectName)
        assertEquals("Drain", saved.actionName)
        assertEquals(1, repository.corrections.size)
        assertTrue("Drain" in actionNames())
    }

    @Test
    fun changingToTheSameTagChangesNothing() {
        val vm = started()
        vm.type(filterWords)
        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.Existing(subjectId("Hot tub")))
        scheduler.runCurrent()

        assertNull(vm.state.value.message)
        assertEquals("Hot tub", assertIs<ResultCard.Saved>(vm.card).subjectName)
        assertTrue(subjectNames().size == 1)
    }

    @Test
    fun aRefusedChangeShowsAPlainWordsMessageAndKeepsTheCard() {
        val vm = started()
        vm.type(filterWords)

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.New("I mowed the lawn"))
        scheduler.runCurrent()

        assertEquals(UserMessage(R.string.refusal_name_first_person), vm.state.value.message)
        assertEquals("Hot tub", assertIs<ResultCard.Saved>(vm.card).subjectName)
        assertTrue(repository.corrections.isEmpty() || subjectNames() == listOf("Hot tub"))
        assertEquals(listOf("Hot tub"), subjectNames())
    }

    @Test
    fun aSecondChangeWhileOneIsInFlightIsIgnored() {
        val vm = started()
        vm.type(filterWords)
        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()

        vm.onPickerChoice(TagChoice.New("Pool"))
        vm.onPickerChoice(TagChoice.New("Sauna"))
        scheduler.runCurrent()

        assertEquals(1, repository.corrections.size)
        assertEquals("Pool", assertIs<ResultCard.Saved>(vm.card).subjectName)
        assertNull(vm.state.value.message)
    }

    // ---- Undo window --------------------------------------------------------------------------

    @Test
    fun undoWindowExpiresAfterTheGivenTimeoutAndTheEntryStays() {
        val vm = started()
        vm.setUndoTimeoutMillis(8_000)
        vm.type(filterWords)
        val captureId = onlyCaptureId()

        scheduler.advanceTimeBy(4_000)
        scheduler.runCurrent()
        assertEquals(0.5f, assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining, 0.02f)
        scheduler.advanceTimeBy(3_800)
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()
        assertNull(vm.card)
        assertEquals(VisibilityStatus.ACTIVE, occurrenceOf(captureId).visibilityStatus)
    }

    @Test
    fun undoWindowHonoursALongerAccessibilityTimeout() {
        val vm = started()
        vm.setUndoTimeoutMillis(30_000)
        vm.type(filterWords)
        scheduler.advanceTimeBy(20_000)
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(10_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun undoWindowPausesWhileTheCardIsTouched() {
        val vm = started()
        vm.type(filterWords)
        vm.setCardTouched(true)
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(1f, assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining)

        vm.setCardTouched(false)
        scheduler.advanceTimeBy(8_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun undoWindowPausesWhileThePickerIsOpen() {
        val vm = started()
        vm.type(filterWords)
        vm.openPicker(TagKind.ACTION)
        scheduler.runCurrent()
        assertNotNull(vm.state.value.picker)
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)

        vm.closePicker()
        scheduler.advanceTimeBy(8_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    // ---- Check card: a close match ------------------------------------------------------------

    private val hvacWords = "The hvac filter needs changing, done 20 minutes"

    /** A catalog with Furnace / Change filter, and words whose subject is only a close match. */
    private fun LogViewModel.typeCloseMatch(time: String? = null, words: String = hvacWords) {
        seed("Furnace", "Change filter")
        typeEntry(words, "HVAC", "Change filter", time = time, duration = "20 minutes")
    }

    @Test
    fun aCloseMatchShowsTheCheckCardWithCandidatesAndSavesNothing() {
        val vm = started()
        vm.typeCloseMatch()

        val card = assertIs<ResultCard.Check>(vm.card)
        assertEquals(hvacWords, card.rawText)
        assertEquals(listOf("Furnace"), card.subject.candidates.map { it.displayName })
        assertNull(card.subject.chosen)
        assertEquals("hvac", card.subject.keepMine?.lowercase())
        assertEquals("Change filter", card.action.chosen?.name)
        assertEquals(TagChoice.Existing(actionId("Change filter")), card.action.chosen?.choice)
        assertFalse(card.canSave)
        assertEquals(1_200L, card.durationSeconds)
        // Nothing saved: the words wait, with no entry.
        assertTrue(history().all { it.occurrence == null })

        vm.save() // not possible with a side unchosen
        scheduler.runCurrent()
        assertIs<ResultCard.Check>(vm.card)
        assertTrue(history().all { it.occurrence == null })
    }

    @Test
    fun pickingTheCandidateSavesUnderTheExistingTagAndLearnsTheAlias() {
        val vm = started()
        vm.typeCloseMatch()
        val furnace = subjectId("Furnace")

        vm.chooseCandidate(TagKind.SUBJECT, furnace)
        assertTrue(assertIs<ResultCard.Check>(vm.card).canSave)
        assertTrue(history().all { it.occurrence == null }) // still nothing saved
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Furnace", saved.subjectName)
        assertEquals("Change filter", saved.actionName)
        assertEquals(1_200L, saved.durationSeconds)
        assertEquals(listOf("Furnace"), subjectNames()) // no new subject
        val aliases = catalog().subjects.single { it.id == furnace }.aliases
        assertTrue(aliases.any { it.equals("hvac", ignoreCase = true) }, "alias learned: $aliases")
        // The undo window starts once the entry is saved.
        scheduler.advanceTimeBy(8_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun keepMineCreatesANewTag() {
        val vm = started()
        vm.typeCloseMatch()

        vm.keepMine(TagKind.SUBJECT)
        assertTrue(assertIs<ResultCard.Check>(vm.card).canSave)
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("hvac", saved.subjectName.lowercase())
        assertEquals(2, subjectNames().size)
        assertTrue("Furnace" in subjectNames())
    }

    @Test
    fun theProposalTimeIsUsedWhenItIsClean() {
        val vm = started()
        vm.typeCloseMatch(time = "yesterday morning", words = "$hvacWords yesterday morning")

        val card = assertIs<ResultCard.Check>(vm.card)
        assertNotNull(card.time)
        vm.chooseCandidate(TagKind.SUBJECT, subjectId("Furnace"))
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        val occurrence = occurrenceOf(saved.captureId)
        assertTrue(occurrence.occurredAt.isBefore(now.minus(Duration.ofHours(12))))
        assertEquals(TimePrecision.APPROXIMATE, occurrence.timePrecision)
    }

    @Test
    fun aTimeInTheFutureIsDroppedAndTheCaptureTimeIsUsed() {
        seed("Hot tub", "Change filter")
        val vm = started()
        vm.typeEntry("Change the hot tub filter tomorrow", "Hot tub", "Change filter", time = "tomorrow")

        val card = assertIs<ResultCard.Check>(vm.card)
        assertNull(card.time)
        // Both sides were understood exactly, so both start chosen.
        assertNotNull(card.subject.chosen)
        assertNotNull(card.action.chosen)
        assertTrue(card.canSave)
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        val occurrence = occurrenceOf(saved.captureId)
        assertEquals(now, occurrence.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, occurrence.timePrecision)
    }

    @Test
    fun aMissingStateGivesACheckCardAndSavesAsCompleted() {
        seed("Hot tub", "Change filter")
        val vm = started()
        vm.typeEntry("Hot tub filter change", "Hot tub", "Change filter", state = null)

        val card = assertIs<ResultCard.Check>(vm.card)
        assertTrue(card.canSave)
        assertNull(card.activityState)
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals(ActivityState.COMPLETED, occurrenceOf(saved.captureId).activityState)
    }

    @Test
    fun decideLaterDismissesTheCardAndTheCaptureStaysWaitingWithoutAnEntry() {
        val vm = started()
        vm.typeCloseMatch()

        vm.decideLater()

        assertNull(vm.card)
        val capture = assertNotNull(read { ledger.getCapture(onlyCaptureId()) })
        assertEquals(ProcessingState.NEEDS_REVIEW, capture.processingState)
        assertFalse(capture.hasOccurrence)
        assertTrue(history().all { it.occurrence == null })
    }

    // ---- Check card: a side with nothing understood --------------------------------------------

    @Test
    fun anEmptySubjectNeedsAPickBeforeSaveIsPossible() {
        val vm = started()
        vm.typeEntry("Fixed the fence", null, "Fix fence")

        var card = assertIs<ResultCard.Check>(vm.card)
        assertNull(card.subject.chosen)
        assertEquals("Fix fence", card.action.chosen?.name)
        assertFalse(card.canSave)
        vm.save()
        scheduler.runCurrent()
        assertIs<ResultCard.Check>(vm.card)
        assertTrue(history().all { it.occurrence == null })

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        assertEquals(TagKind.SUBJECT, vm.state.value.picker?.kind)
        vm.onPickerChoice(TagChoice.New("Fence"))
        card = assertIs<ResultCard.Check>(vm.card)
        assertEquals("Fence", card.subject.chosen?.name)
        assertNull(vm.state.value.picker)
        assertTrue(card.canSave)

        vm.save()
        scheduler.runCurrent()
        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Fence", saved.subjectName)
        assertEquals("Fix fence", saved.actionName)
    }

    @Test
    fun anUnavailableInterpreterShowsTheCheckCardWithBothSidesBlankAndSaveWorksAfterTwoPicks() {
        seed("Hot tub", "Change filter")
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
        val vm = started()
        vm.type("cut grass")

        val card = assertIs<ResultCard.Check>(vm.card)
        assertEquals("cut grass", card.rawText)
        assertEquals(onlyCaptureId(), card.captureId)
        assertNull(card.subject.chosen)
        assertNull(card.action.chosen)
        assertTrue(card.subject.candidates.isEmpty())
        assertFalse(card.canSave)
        val capture = assertNotNull(read { ledger.getCapture(card.captureId) })
        assertEquals(ProcessingState.FAILED_RETRYABLE, capture.processingState)

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        assertEquals(listOf("Hot tub"), vm.state.value.picker?.tags?.map { it.displayName })
        vm.onPickerChoice(TagChoice.Existing(subjectId("Hot tub")))
        assertFalse(assertIs<ResultCard.Check>(vm.card).canSave) // only one side so far
        vm.openPicker(TagKind.ACTION)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.Existing(actionId("Change filter")))
        assertTrue(assertIs<ResultCard.Check>(vm.card).canSave)
        vm.save()
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Hot tub", saved.subjectName)
        assertEquals("Change filter", saved.actionName)
        assertEquals("cut grass", saved.rawText)
        scheduler.advanceTimeBy(8_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun aRejectedOrMalformedAnswerShowsTheSameCheckCardWithBothSidesBlank() {
        extractor.on("one", Extracted.failure(InterpreterFailureKind.MALFORMED))
        extractor.on("two", Extracted.failure(InterpreterFailureKind.OTHER))
        extractor.on("three", Extracted.failure(InterpreterFailureKind.RETRYABLE))
        val vm = started()
        listOf("one", "two", "three").forEach { words ->
            vm.type(words)
            val card = assertIs<ResultCard.Check>(vm.card)
            assertEquals(words, card.rawText)
            assertNull(card.subject.chosen)
            assertNull(card.action.chosen)
        }
        assertTrue(history().all { it.occurrence == null })
    }

    @Test
    fun anExtractorThatThrowsAfterTheWordsAreStoredStillShowsTheCheckCard() {
        extractor.throwOnExtract = IllegalStateException("extractor blew up")
        val vm = started()
        vm.type(filterWords)

        val card = assertIs<ResultCard.Check>(vm.card)
        assertEquals(filterWords, card.rawText)
        assertEquals(onlyCaptureId(), card.captureId)
        assertFalse(vm.state.value.isCapturing)
        assertTrue(history().all { it.occurrence == null })
        val capture = assertNotNull(read { ledger.getCapture(card.captureId) })
        assertEquals(filterWords, capture.rawText)
        assertEquals(CaptureSource.PHONE_TEXT, capture.source)
    }

    @Test
    fun aQuestionOrNotALogShowsTheCheckCard() {
        extractor.fallback = com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult.Success(
            com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate(
                operation = com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation.UNSUPPORTED,
                subject = null,
                action = null,
                activityState = null,
                temporalExpression = null,
                durationExpression = null,
            ),
        )
        val vm = started()
        vm.type("hmm")
        val card = assertIs<ResultCard.Check>(vm.card)
        assertNull(card.subject.chosen)
        assertNull(card.action.chosen)
    }

    // ---- Refusals and failures ----------------------------------------------------------------

    @Test
    fun aRefusedNewNameOnSaveKeepsTheCardAndShowsAPlainMessage() {
        val vm = started()
        vm.typeEntry("Fixed the fence", null, "Fix fence")
        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.New("Yard stuff"))
        val card = vm.card

        vm.save()
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertEquals(UserMessage(R.string.refusal_name_filler_word), vm.state.value.message)
        assertTrue(history().all { it.occurrence == null })
    }

    @Test
    fun savingWordsThatWereAlreadyLoggedSaysSoAndKeepsTheCard() {
        val vm = started()
        vm.typeCloseMatch()
        vm.chooseCandidate(TagKind.SUBJECT, subjectId("Furnace"))
        // Behind the card's back, the same capture gets logged.
        read {
            TaggedResolutionService(ledger, clock).resolve(
                onlyCaptureId(), TagChoice.Existing(subjectId("Furnace")), TagChoice.Existing(actionId("Change filter")),
            )
        }

        vm.save()
        scheduler.runCurrent()

        assertIs<ResultCard.Check>(vm.card)
        assertEquals(UserMessage(R.string.refusal_capture_already_logged), vm.state.value.message)
    }

    @Test
    fun failedUndoKeepsTheCardAndSaysSo() {
        val vm = started()
        vm.type(filterWords)
        val captureId = onlyCaptureId()
        repository.failOn = setOf(FailPoint.HIDE)

        vm.undo()
        scheduler.runCurrent()

        assertIs<ResultCard.Saved>(vm.card)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertEquals(VisibilityStatus.ACTIVE, occurrenceOf(captureId).visibilityStatus)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedChangeKeepsTheCardAndSaysSo() {
        val vm = started()
        vm.type(filterWords)
        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        repository.failOn = setOf(FailPoint.CORRECT)

        vm.onPickerChoice(TagChoice.New("Pool"))
        scheduler.runCurrent()

        assertEquals("Hot tub", assertIs<ResultCard.Saved>(vm.card).subjectName)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedSaveKeepsTheCardAndSaysSo() {
        val vm = started()
        vm.typeCloseMatch()
        vm.keepMine(TagKind.SUBJECT)
        val card = vm.card
        repository.failOn = setOf(FailPoint.ACCEPT)

        vm.save()
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertTrue(history().all { it.occurrence == null })
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun aServiceRaceThatThrowsIllegalArgumentIsTreatedLikeAnyStorageFailure() {
        val vm = started()
        vm.typeCloseMatch()
        vm.keepMine(TagKind.SUBJECT)
        val card = vm.card
        repository.failOn = setOf(FailPoint.ACCEPT)
        repository.failWith = { IllegalArgumentException(it) }

        vm.save()
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedPickerLoadKeepsTheCardAndSaysSo() {
        val vm = started()
        vm.typeCloseMatch()
        val card = vm.card
        repository.failOn = setOf(FailPoint.CATALOG)

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertNull(vm.state.value.picker)
        assertEquals(UserMessage(R.string.log_tags_not_loaded), vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun aSecondSaveWhileOneIsInFlightIsIgnored() {
        val vm = started()
        vm.typeCloseMatch()
        vm.keepMine(TagKind.SUBJECT)

        vm.save()
        assertTrue(vm.state.value.actionInFlight)
        vm.save()
        scheduler.runCurrent()

        assertEquals(1, history().count { it.occurrence != null })
        assertIs<ResultCard.Saved>(vm.card)
        assertNull(vm.state.value.message)
    }

    @Test
    fun noMessageEverCarriesTheOwnersWords() {
        val vm = started()
        vm.typeEntry("Fixed the fence", null, "Fix fence")
        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        vm.onPickerChoice(TagChoice.New("Yard stuff"))
        vm.save()
        scheduler.runCurrent()
        val message = assertNotNull(vm.state.value.message)
        assertTrue(message.args.isEmpty())
    }

    // ---- Recent and AI readiness ---------------------------------------------------------------

    @Test
    fun recentShowsTheThreeNewestRowsWithStates() {
        extractor.on("second", Extracted.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()
        assertTrue(vm.state.value.recentLoaded)
        assertTrue(vm.state.value.recent.isEmpty())
        clock.advance(Duration.ofMinutes(1))
        vm.type(filterWords)
        clock.advance(Duration.ofMinutes(1))
        vm.type("second")
        clock.advance(Duration.ofMinutes(1))
        vm.typeEntry("Changed the hot tub filter again", "Hot tub", "Change filter")

        val recent = vm.state.value.recent
        assertEquals(listOf("Changed the hot tub filter again", "second", filterWords), recent.map { it.rawText })
        assertEquals("Hot tub Change filter", recent[0].activityName)
        assertNull(recent[0].state)
        assertEquals(RowState.NOT_CATEGORIZED, recent[1].state)
        assertNull(recent[1].activityName)
    }

    @Test
    fun aiNotReadyRowShowsWhenTheCheckSaysNoAndTypedCapturesStillWork() {
        aiReady = false
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
        val vm = started()
        assertTrue(vm.state.value.showAiNotReady)
        assertTrue(vm.state.value.canSubmit.not()) // nothing typed yet
        vm.type("cut grass")
        assertIs<ResultCard.Check>(vm.card)
    }

    @Test
    fun aiReadyCheckRunsOncePerStart() {
        val vm = started()
        assertFalse(vm.state.value.showAiNotReady)
        assertEquals(1, readyChecks)
        vm.onStop()
        aiReady = false
        vm.onStart()
        scheduler.runCurrent()
        assertEquals(2, readyChecks)
        assertTrue(vm.state.value.showAiNotReady)
    }

    // ---- Voice capture: the listening state -----------------------------------------------------

    @Test
    fun startListeningBlocksTypedSubmitAndDropsAnyCard() {
        val vm = started()
        vm.type(filterWords)
        assertIs<ResultCard.Saved>(vm.card)
        vm.onInputChange("more words")

        vm.startListening()

        assertTrue(vm.state.value.isListening)
        assertNull(vm.card)
        assertFalse(vm.state.value.canSubmit)
        // The typed words are not thrown away, only held: submit is blocked, the field is intact.
        assertEquals("more words", vm.state.value.input)
        vm.submit()
        scheduler.runCurrent()
        assertTrue(repository.created.size == 1)
    }

    @Test
    fun theMicrophoneIsUnavailableWhileStoppedAndWhileCapturing() {
        val vm = viewModel()
        assertFalse(vm.state.value.canUseMicrophone)
        vm.startListening()
        assertFalse(vm.state.value.isListening)

        vm.onStart()
        scheduler.runCurrent()
        assertTrue(vm.state.value.canUseMicrophone)
    }

    @Test
    fun stopListeningHandsTheScreenBackToTypingWithoutWaitingForAnEvent() {
        val vm = started()
        vm.listen(partial("I cut the"))
        assertEquals("I cut the", vm.state.value.partialTranscript)

        vm.stopListening()

        // Cancelling the collection emits no event, so the idle state is restored here and now.
        assertFalse(vm.state.value.isListening)
        assertEquals("", vm.state.value.partialTranscript)
        scheduler.runCurrent()
        assertEquals(1, transcriber.sessionsCancelled)
        assertFalse(transcriber.isOpen)
        vm.type(filterWords)
        assertIs<ResultCard.Saved>(vm.card)
    }

    @Test
    fun partialsAreShownButNeverStoredNeverExtractedAndNeverOutliveTheSession() {
        val vm = started()
        vm.listen(partial("I cut the"))

        assertEquals("I cut the", vm.state.value.partialTranscript)
        // Display only: no capture exists and the extractor has not been asked anything.
        assertTrue(repository.created.isEmpty())
        assertEquals(0, extractor.callCount)

        transcriber.emitNow(failed(SpeechFailure.NOTHING_HEARD))
        scheduler.runCurrent()

        assertEquals("", vm.state.value.partialTranscript)
        assertTrue(repository.created.isEmpty())
        assertEquals(0, extractor.callCount)
    }

    // ---- Voice capture: the recognition-failure card -------------------------------------------

    @Test
    fun recognitionFailureShowsACardWithNoCaptureAndSavesNothing() {
        val vm = started()

        vm.listen(failed(SpeechFailure.NOTHING_HEARD))

        assertEquals(ResultCard.RecognitionFailed, vm.card)
        assertFalse(vm.state.value.isListening)
        // Nothing was heard, so nothing was stored: no raw capture exists at all.
        assertTrue(repository.created.isEmpty())
        assertTrue(history().isEmpty())
    }

    @Test
    fun tryAgainListensAgainAndTypeInsteadJustDropsTheCard() {
        val vm = started()
        vm.listen(failed(SpeechFailure.NOTHING_HEARD))

        vm.retryListening()
        scheduler.runCurrent()
        assertTrue(vm.state.value.isListening)
        assertEquals(2, transcriber.sessionsStarted)
        assertNull(vm.card)

        transcriber.emitNow(failed(SpeechFailure.NOTHING_HEARD))
        scheduler.runCurrent()
        vm.dismissRecognitionFailure()
        assertNull(vm.card)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun theFailureCardIsNeitherSavableNorCorrectable() {
        val vm = started()
        vm.listen(failed(SpeechFailure.NOTHING_HEARD))

        // There is no capture behind it, so the check and change paths must simply not apply.
        vm.save()
        scheduler.runCurrent()
        assertEquals(ResultCard.RecognitionFailed, vm.card)

        vm.decideLater()
        assertEquals(ResultCard.RecognitionFailed, vm.card)

        vm.openPicker(TagKind.SUBJECT)
        scheduler.runCurrent()
        assertNull(vm.state.value.picker)

        vm.onPickerChoice(TagChoice.New("Anything"))
        scheduler.runCurrent()
        assertEquals(ResultCard.RecognitionFailed, vm.card)
        assertTrue(history().isEmpty())
    }

    // ---- Voice capture: microphone permission outcomes -----------------------------------------

    @Test
    fun refusingTheMicrophoneSaysSoAndLeavesTypingWorking() {
        val vm = started()

        vm.onMicrophonePermissionDenied()

        assertFalse(vm.state.value.isListening)
        assertEquals(UserMessage(R.string.log_mic_permission_denied), vm.state.value.message)
        vm.type(filterWords)
        assertIs<ResultCard.Saved>(vm.card)
    }

    @Test
    fun refusingTheMicrophoneForGoodSaysSoAndLeavesTypingWorking() {
        val vm = started()

        vm.onMicrophonePermissionBlocked()

        assertFalse(vm.state.value.isListening)
        assertEquals(UserMessage(R.string.log_mic_permission_blocked), vm.state.value.message)
        vm.type(filterWords)
        assertIs<ResultCard.Saved>(vm.card)
    }

    // ---- Voice capture: spoken words reaching the ledger ---------------------------------------

    @Test
    fun oneRecognitionStoresExactlyOneVoiceCaptureAndShowsItsCard() {
        val vm = started()

        vm.listen(partial("I cut"), final(filterWords, confidence = 0.82f, alternatives = listOf("I cut the gas")))

        val stored = repository.created.single()
        assertEquals(CaptureSource.PHONE_VOICE, stored.source)
        assertEquals(LOG_VOICE_SOURCE_SURFACE, stored.sourceSurface)
        assertEquals("log_voice", stored.sourceSurface)
        assertEquals(ProcessingState.CAPTURED, stored.processingState)
        assertEquals(filterWords, stored.rawText)
        assertEquals(now, stored.capturedAt)
        assertEquals(zone, stored.zoneId)
        assertEquals(0.82f.toDouble(), stored.speechConfidence)
        assertEquals("[\"I cut the gas\"]", stored.speechAlternativesJson)
        // The final transcript is what reached the extractor -- never the partial.
        assertEquals(filterWords, extractor.received.single().rawText)
        assertIs<ResultCard.Saved>(vm.card)
        assertFalse(vm.state.value.isListening)
        assertEquals("", vm.state.value.partialTranscript)
        assertFalse(vm.state.value.isCapturing)
    }

    @Test
    fun anUnknownConfidenceStaysUnknownAndNoAlternativesMeansNoJson() {
        val vm = started()

        vm.listen(final(filterWords, confidence = null, alternatives = emptyList()))

        val stored = repository.created.single()
        assertNull(stored.speechConfidence)
        assertNull(stored.speechAlternativesJson)
    }

    @Test
    fun severalAlternativesAreStoredAsAJsonArrayWithTheirQuotesEscaped() {
        val vm = started()

        vm.listen(final(filterWords, alternatives = listOf("I cut the gas", "I \"cut\" the grass")))

        assertEquals(
            "[\"I cut the gas\",\"I \\\"cut\\\" the grass\"]",
            repository.created.single().speechAlternativesJson,
        )
    }

    @Test
    fun theRawCaptureIsCreatedOnceAndOnlyReadAfterwards() {
        val vm = started()

        vm.listen(final(filterWords, confidence = 0.5f))

        // One create for the whole session, and the stored capture is still exactly what was
        // created: the repository offers no way to rewrite one, and nothing here tries (ADR-007).
        val created = repository.created.single()
        val captureId = assertIs<ResultCard.Saved>(vm.card).captureId
        val stored = assertNotNull(read { ledger.getCapture(captureId) })
        assertEquals(created.rawText, stored.rawText)
        assertEquals(created.source, stored.source)
        assertEquals(created.capturedAt, stored.capturedAt)
        assertEquals(created.speechConfidence, stored.speechConfidence)
        assertEquals(1, history().size)
    }

    @Test
    fun spokenAndTypedWordsProduceTheSameCardThroughTheSamePath() {
        val vm = started()

        vm.listen(final(filterWords, confidence = 0.9f, alternatives = listOf("I cut the gas")))
        val spoken = assertIs<ResultCard.Saved>(vm.card)
        vm.type(filterWords)
        val typed = assertIs<ResultCard.Saved>(vm.card)

        // Same card, identifiers aside: no branch anywhere depends on how the words arrived.
        assertEquals(
            typed.copy(captureId = "", occurrenceId = ""),
            spoken.copy(captureId = "", occurrenceId = ""),
        )
        assertEquals(2, extractor.callCount)
        assertEquals(listOf(filterWords, filterWords), extractor.received.map { it.rawText })
    }

    @Test
    fun spokenWordsCanReachTheCheckCardToo() {
        val vm = started()
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)

        vm.listen(final("cut grass"))

        val card = assertIs<ResultCard.Check>(vm.card)
        assertEquals("cut grass", card.rawText)
        assertNull(card.subject.chosen)
        assertNull(card.action.chosen)
    }

    @Test
    fun aStorageFailureOnASpokenCaptureGivesTheWordsBackInsteadOfLosingThem() {
        val vm = started()
        repository.failOn = setOf(FailPoint.CREATE)

        vm.listen(final(filterWords))

        assertTrue(repository.created.isEmpty())
        assertTrue(history().isEmpty())
        // Nothing was saved, so the words are handed back to the capture field, not lost.
        assertEquals(filterWords, vm.state.value.input)
        assertEquals(UserMessage(R.string.log_capture_not_saved), vm.state.value.message)
        assertFalse(vm.state.value.isCapturing)
        assertNull(vm.card)
    }

    // ---- Voice capture: failed sessions store nothing -------------------------------------------

    @Test
    fun engineErrorShowsTheFailureCardAndStoresNothing() {
        val vm = started()

        vm.listen(partial("I cut the"), failed(SpeechFailure.ENGINE_ERROR))

        assertEquals(ResultCard.RecognitionFailed, vm.card)
        assertTrue(repository.created.isEmpty())
        assertEquals("", vm.state.value.partialTranscript)
    }

    @Test
    fun aMissingPermissionAndAPhoneWithNoEngineSaySoWithNoCardAndStoreNothing() {
        val vm = started()

        vm.listen(failed(SpeechFailure.PERMISSION_MISSING))
        assertNull(vm.card)
        assertEquals(UserMessage(R.string.log_mic_permission_denied), vm.state.value.message)
        assertFalse(vm.state.value.isListening)
        assertTrue(repository.created.isEmpty())

        vm.listen(failed(SpeechFailure.NO_ON_DEVICE_ENGINE))
        assertNull(vm.card)
        assertEquals(UserMessage(R.string.log_voice_unavailable), vm.state.value.message)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun aBusyRecognizerSaysTryAgainInAMomentAndStoresNothing() {
        val vm = started()

        vm.listen(failed(SpeechFailure.RECOGNIZER_BUSY))

        assertNull(vm.card)
        assertEquals(UserMessage(R.string.log_voice_busy), vm.state.value.message)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun aCancelledSessionShowsNothingAtAllAndStoresNothing() {
        val vm = started()

        vm.listen(failed(SpeechFailure.CANCELLED))

        assertNull(vm.card)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.isListening)
        assertTrue(repository.created.isEmpty())
        assertTrue(history().isEmpty())
    }

    @Test
    fun aResultAlreadyOnItsWayWhenTheUserStopsIsDroppedAndStoresNothing() {
        val vm = started()
        transcriber.willEmit(final(filterWords))
        vm.startListening()

        // The result is queued but not yet delivered when the user taps stop.
        vm.stopListening()
        scheduler.runCurrent()

        assertTrue(repository.created.isEmpty())
        assertNull(vm.card)
        assertFalse(vm.state.value.isListening)
        assertEquals(0, extractor.callCount)
    }

    // ---- Voice capture: one session at a time, and none that outlives the screen ----------------

    @Test
    fun aSecondTapWhileListeningNeverOpensASecondSession() {
        val vm = started()
        vm.listen(partial("I cut the"))

        vm.startListening()
        scheduler.runCurrent()

        assertEquals(1, transcriber.sessionsStarted)
        assertTrue(vm.state.value.isListening)
        assertEquals("I cut the", vm.state.value.partialTranscript)
    }

    @Test
    fun listeningCannotStartWhileTheScreenIsStoppedOrWhileACaptureIsProcessed() {
        extractor.holdAnswers()
        val vm = viewModel()
        vm.startListening()
        scheduler.runCurrent()
        assertEquals(0, transcriber.sessionsStarted) // stopped: ADR-029
        assertFalse(vm.state.value.isListening)

        vm.onStart()
        scheduler.runCurrent()
        vm.type(filterWords) // still in flight: the extractor is held
        assertTrue(vm.state.value.isCapturing)

        vm.startListening()
        scheduler.runCurrent()
        assertEquals(0, transcriber.sessionsStarted)
        assertFalse(vm.state.value.isListening)

        extractor.release()
        scheduler.runCurrent()
    }

    @Test
    fun theSessionEndsWhenTheScreenStopsWhenLogIsLeftAndWhenTheViewModelIsCleared() {
        val vm = started()
        vm.listen(partial("I cut the"))

        vm.onStop()
        scheduler.runCurrent()
        assertFalse(vm.state.value.isListening)
        assertEquals("", vm.state.value.partialTranscript)
        assertFalse(transcriber.isOpen)
        assertEquals(1, transcriber.sessionsCancelled)

        vm.onStart()
        scheduler.runCurrent()
        vm.listen(partial("I cut the"))
        vm.onLeftLog()
        scheduler.runCurrent()
        assertFalse(vm.state.value.isListening)
        assertFalse(transcriber.isOpen)
        assertEquals(2, transcriber.sessionsCancelled)

        vm.listen(partial("I cut the"))
        assertTrue(transcriber.isOpen)
        store.clear() // the screen is gone for good
        scheduler.runCurrent()
        assertFalse(transcriber.isOpen)
        assertEquals(3, transcriber.sessionsCancelled)
        assertTrue(repository.created.isEmpty())
    }
}
