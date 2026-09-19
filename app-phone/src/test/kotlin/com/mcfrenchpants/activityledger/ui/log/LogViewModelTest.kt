package com.mcfrenchpants.activityledger.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.ResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
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
 * JVM tests of [LogViewModel] over the real orchestrator and services, the in-memory repository,
 * a scripted interpreter, a fixed clock and a test Main dispatcher (virtual time drives the undo
 * window).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val memory = InMemoryActivityRepository(clock)
    private val repository = RecordingRepository(memory)
    private val fake = FakeActivityInterpreter()
    private val transcriber = ScriptedTranscriber()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()
    private var aiReady = true
    private var readyChecks = 0

    private val mowLawn = memory.seedActivity("Mow lawn")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear() // cancels viewModelScope, including any undo window still draining
        Dispatchers.resetMain()
    }

    private fun viewModel(interpreter: ActivityInterpreter = fake): LogViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(
                repository = repository,
                orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock),
                reviewResolutionService = ReviewResolutionService(repository, clock),
                correctionService = CorrectionService(repository, clock),
                transcriber = transcriber,
                clock = clock,
                isAiReady = { readyChecks++; aiReady },
                zone = { zone },
                locale = { Locale.US },
            ) as T
        }
        return ViewModelProvider(store, factory)[LogViewModel::class.java]
    }

    private fun started(interpreter: ActivityInterpreter = fake): LogViewModel =
        viewModel(interpreter).also {
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

    private fun onlyCaptureId(): String = read { memory.loadHistory() }.single().captureId

    // ---- Raw capture ------------------------------------------------------------------------

    @Test
    fun typedTextIsStoredAsATrimmedPhoneTextCaptureThenProcessedByTheOrchestrator() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.type("   I cut the grass  ")

        val stored = repository.created.single()
        assertEquals(CaptureSource.PHONE_TEXT, stored.source)
        assertEquals(LOG_TYPED_SOURCE_SURFACE, stored.sourceSurface)
        assertEquals("log_typed", stored.sourceSurface)
        assertEquals(ProcessingState.CAPTURED, stored.processingState)
        assertEquals("I cut the grass", stored.rawText)
        assertEquals(now, stored.capturedAt)
        assertEquals(zone, stored.zoneId)
        assertNull(stored.speechConfidence)
        assertNull(stored.speechAlternativesJson)
        assertEquals("I cut the grass", fake.receivedInputs.single().rawText)
        assertEquals("", vm.state.value.input)
        assertFalse(vm.state.value.isCapturing)
    }

    @Test
    fun blankInputIsIgnored() {
        val vm = started()
        vm.type("   ")
        assertTrue(repository.created.isEmpty())
        assertNull(vm.card)
        assertEquals(0, fake.callCount)
    }

    @Test
    fun submitIsBlockedUntilStartedAndAfterStop() {
        val vm = viewModel()
        vm.type("I cut the grass")
        assertTrue(repository.created.isEmpty())
        assertFalse(vm.state.value.canSubmit)

        vm.onStart()
        vm.onStop()
        scheduler.runCurrent()
        vm.type("I cut the grass")
        assertTrue(repository.created.isEmpty())
        assertEquals("I cut the grass", vm.state.value.input)
    }

    // ---- Outcome mapping --------------------------------------------------------------------

    @Test
    fun autoAcceptedShowsSavedCardWithNameTimeAndWords() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Mow lawn", saved.activityName)
        assertEquals("Today, 8:00 PM", saved.time)
        assertEquals("I cut the grass", saved.rawText)
        assertEquals(memory.occurrences.single().id, saved.occurrenceId)
        assertEquals(1f, saved.undoFractionRemaining)
    }

    @Test
    fun alreadyHasOccurrenceShowsSavedCardForTheExistingOccurrence() {
        val review = ReviewResolutionService(repository, clock)
        repository.afterCreate = { id -> review.resolve(id, ActivityTarget.Existing(mowLawn)) }
        val vm = started()
        vm.type("I cut the grass")

        assertEquals(0, fake.callCount) // the orchestrator returned AlreadyHasOccurrence
        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals(memory.occurrences.single().id, saved.occurrenceId)
        assertEquals("Mow lawn", saved.activityName)
    }

    @Test
    fun needsReviewShowsNeedsReviewCard() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        val card = assertIs<ResultCard.NeedsReview>(vm.card)
        assertEquals("did the yard", card.rawText)
        assertTrue(memory.occurrences.isEmpty())
    }

    @Test
    fun rejectedAnswerShowsNeedsReviewCard() {
        fake.enqueue(Results.invalid)
        val vm = started()
        vm.type("hmm")
        assertIs<ResultCard.NeedsReview>(vm.card)
    }

    @Test
    fun malformedOrFailedInterpreterShowsNeedsReviewCard() {
        fake.enqueue(Results.failure(InterpreterFailureKind.MALFORMED), Results.failure(InterpreterFailureKind.OTHER))
        val vm = started()
        vm.type("one")
        assertIs<ResultCard.NeedsReview>(vm.card)
        vm.type("two")
        assertIs<ResultCard.NeedsReview>(vm.card)
    }

    @Test
    fun unavailableOrRetryableInterpreterShowsNotCategorizedCard() {
        fake.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE), Results.failure(InterpreterFailureKind.RETRYABLE))
        val vm = started()
        vm.type("one")
        assertEquals(ResultCard.NotCategorized(onlyCaptureId(), "one"), vm.card)
        vm.type("two")
        assertIs<ResultCard.NotCategorized>(vm.card)
    }

    @Test
    fun submittingANewCaptureDismissesTheCurrentCardFirst() {
        fake.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE), Results.existing(mowLawn))
        val vm = started()
        vm.type("one")
        assertIs<ResultCard.NotCategorized>(vm.card)

        vm.onInputChange("two")
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
        val gated = GatedInterpreter(fake)
        fake.enqueue(Results.existing(mowLawn))
        val vm = started(gated)
        vm.type("I cut the grass")
        assertTrue(vm.state.value.isCapturing)

        vm.onStop()
        gated.release()
        scheduler.runCurrent()

        // Finished while in the background: exactly one outcome, card waiting, undo window paused.
        assertEquals(1, memory.occurrences.size)
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(1f, assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining)

        vm.onStart()
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun leavingLogDismissesTheSavedCardButTheOccurrenceStays() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        vm.onLeftLog()
        assertNull(vm.card)
        assertEquals(VisibilityStatus.ACTIVE, memory.occurrences.single().visibilityStatus)
    }

    // ---- Saved card: Undo and Change activity -------------------------------------------------

    @Test
    fun undoHidesTheOccurrenceKeepsTheCaptureAndDropsItFromRecent() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        val captureId = onlyCaptureId()
        assertEquals(1, vm.state.value.recent.size)

        vm.undo()
        scheduler.runCurrent()

        assertNull(vm.card)
        assertEquals(VisibilityStatus.HIDDEN, memory.occurrences.single().visibilityStatus)
        val capture = assertNotNull(read { memory.getCapture(captureId) })
        assertEquals("I cut the grass", capture.rawText)
        assertTrue(vm.state.value.recent.none { it.captureId == captureId })
    }

    @Test
    fun changeActivityToExistingAppliesAUserCorrectionAndUpdatesTheCard() {
        val walk = memory.seedActivity("Walk dog")
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
        val before = assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining

        vm.changeActivity(ActivityTarget.Existing(walk))
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Walk dog", saved.activityName)
        assertEquals(before, saved.undoFractionRemaining) // the window stays as is
        assertEquals(walk, memory.occurrences.single().canonicalActivityId)
        assertEquals(CorrectionSource.USER, memory.corrections.single().source)
        assertEquals("Walk dog", vm.state.value.recent.first().activityName)
    }

    @Test
    fun changeActivityToNewCreatesItAndUpdatesTheCard() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")

        vm.changeActivity(ActivityTarget.New("Trim hedge"))
        scheduler.runCurrent()

        assertEquals("Trim hedge", assertIs<ResultCard.Saved>(vm.card).activityName)
        assertEquals(1, memory.corrections.size)
        assertTrue(memory.activities.any { it.displayName == "Trim hedge" })
    }

    @Test
    fun changeActivityToTheSameActivityChangesNothing() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        vm.changeActivity(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()
        assertTrue(memory.corrections.isEmpty())
        assertNull(vm.state.value.message)
        assertEquals("Mow lawn", assertIs<ResultCard.Saved>(vm.card).activityName)
    }

    @Test
    fun refusedChangeShowsAPlainWordsMessageAndKeepsTheCard() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")

        vm.changeActivity(ActivityTarget.New("mow  LAWN"))
        scheduler.runCurrent()
        assertEquals(UserMessage(R.string.refusal_name_matches_existing, listOf("Mow lawn")), vm.state.value.message)
        assertIs<ResultCard.Saved>(vm.card)

        vm.changeActivity(ActivityTarget.New("I mowed the lawn"))
        scheduler.runCurrent()
        assertEquals(UserMessage(R.string.refusal_name_first_person), vm.state.value.message)
        assertTrue(memory.corrections.isEmpty())
    }

    // ---- Undo window --------------------------------------------------------------------------

    @Test
    fun undoWindowExpiresAfterTheGivenTimeoutAndTheOccurrenceStays() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.setUndoTimeoutMillis(8_000)
        vm.type("I cut the grass")

        scheduler.advanceTimeBy(4_000)
        scheduler.runCurrent()
        assertEquals(0.5f, assertIs<ResultCard.Saved>(vm.card).undoFractionRemaining, 0.02f)
        scheduler.advanceTimeBy(3_800)
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()
        assertNull(vm.card)
        assertEquals(VisibilityStatus.ACTIVE, memory.occurrences.single().visibilityStatus)
    }

    @Test
    fun undoWindowHonoursALongerAccessibilityTimeout() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.setUndoTimeoutMillis(30_000)
        vm.type("I cut the grass")
        scheduler.advanceTimeBy(20_000)
        scheduler.runCurrent()
        assertIs<ResultCard.Saved>(vm.card)
        scheduler.advanceTimeBy(10_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun undoWindowPausesWhileTheCardIsTouched() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
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
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        vm.openPicker()
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

    // ---- Needs review / Not categorized -------------------------------------------------------

    @Test
    fun resolveNeedsReviewToExistingShowsSavedCardWithUndoWindow() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        vm.resolve(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()

        val saved = assertIs<ResultCard.Saved>(vm.card)
        assertEquals("Mow lawn", saved.activityName)
        assertEquals("did the yard", saved.rawText)
        scheduler.advanceTimeBy(8_200)
        scheduler.runCurrent()
        assertNull(vm.card)
    }

    @Test
    fun resolveNeedsReviewToNewActivityShowsSavedCard() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("flushed the water heater")
        vm.onPickerChoice(ActivityTarget.New("Flush water heater"))
        scheduler.runCurrent()
        assertEquals("Flush water heater", assertIs<ResultCard.Saved>(vm.card).activityName)
    }

    @Test
    fun refusedResolveKeepsTheCardAndShowsTheMessage() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        val card = vm.card

        vm.resolve(ActivityTarget.New("Yard stuff"))
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertEquals(UserMessage(R.string.refusal_name_filler_word), vm.state.value.message)
        assertTrue(memory.occurrences.isEmpty())
    }

    @Test
    fun decideLaterDismissesAndTheCaptureStaysInReview() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        vm.decideLater()
        assertNull(vm.card)
        val capture = assertNotNull(read { memory.getCapture(onlyCaptureId()) })
        assertEquals(ProcessingState.NEEDS_REVIEW, capture.processingState)
        assertTrue(memory.occurrences.isEmpty())
    }

    @Test
    fun notCategorizedCanBeResolvedThroughThePicker() {
        fake.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()
        vm.type("cut grass")
        vm.openPicker()
        scheduler.runCurrent()
        assertEquals(listOf("Mow lawn"), vm.state.value.picker?.activities?.map { it.displayName })

        vm.onPickerChoice(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()
        assertNull(vm.state.value.picker)
        assertEquals("Mow lawn", assertIs<ResultCard.Saved>(vm.card).activityName)
    }

    @Test
    fun notCategorizedDecideLaterDismisses() {
        fake.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()
        vm.type("cut grass")
        vm.decideLater()
        assertNull(vm.card)
        val capture = assertNotNull(read { memory.getCapture(onlyCaptureId()) })
        assertEquals(ProcessingState.FAILED_RETRYABLE, capture.processingState)
    }

    // ---- Suggestions ----------------------------------------------------------------------------

    @Test
    fun suggestionsPutThePendingMatchFirstThenSelectorOrderCappedAtThree() {
        val walk = memory.seedActivity("Walk dog")
        memory.seedActivity("Fix fence")
        memory.seedActivity("Bake bread")
        fake.enqueue(Results.needsReview(walk))
        val vm = started()
        vm.type("took the dog out")

        val card = assertIs<ResultCard.NeedsReview>(vm.card)
        // Pending match first, then CandidateSelector's order (normalized name), capped at 3.
        assertEquals(listOf("Walk dog", "Bake bread", "Fix fence"), card.suggestions.map { it.displayName })
    }

    @Test
    fun suggestionsAreDeduplicated() {
        val bake = memory.seedActivity("Bake bread")
        fake.enqueue(Results.needsReview(bake))
        val vm = started()
        vm.type("made a loaf")
        val card = assertIs<ResultCard.NeedsReview>(vm.card)
        assertEquals(listOf("Bake bread", "Mow lawn"), card.suggestions.map { it.displayName })
        assertEquals(listOf(bake, mowLawn), card.suggestions.map { it.activityId })
    }

    @Test
    fun noSuggestionsWhenTheCatalogIsEmpty() {
        val repo = InMemoryActivityRepository(clock)
        val vm = LogViewModel(
            repository = repo,
            orchestrator = CaptureInterpretationOrchestrator(repo, fake, clock),
            reviewResolutionService = ReviewResolutionService(repo, clock),
            correctionService = CorrectionService(repo, clock),
            transcriber = transcriber,
            clock = clock,
            isAiReady = { true },
            zone = { zone },
            locale = { Locale.US },
        )
        fake.enqueue(Results.invalid)
        vm.onStart()
        scheduler.runCurrent()
        vm.type("something")
        assertEquals(emptyList(), assertIs<ResultCard.NeedsReview>(vm.card).suggestions)
    }

    @Test
    fun suggestionPickResolvesToThatActivity() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        val suggestion = assertIs<ResultCard.NeedsReview>(vm.card).suggestions.first()
        vm.resolve(ActivityTarget.Existing(suggestion.activityId))
        scheduler.runCurrent()
        assertEquals(mowLawn, memory.occurrences.single().canonicalActivityId)
    }

    // ---- Storage failures and double taps -------------------------------------------------------

    @Test
    fun processThrowingAfterTheCaptureIsStoredShowsNotCategorized() {
        fake.respondWith { throw IllegalStateException("interpreter blew up") }
        val vm = started()
        vm.type("I cut the grass")

        assertEquals(ResultCard.NotCategorized(onlyCaptureId(), "I cut the grass"), vm.card)
        assertFalse(vm.state.value.isCapturing)
        assertTrue(memory.occurrences.isEmpty())
        val capture = assertNotNull(read { memory.getCapture(onlyCaptureId()) })
        assertEquals("I cut the grass", capture.rawText)
        assertEquals(now, capture.capturedAt)
        assertEquals(CaptureSource.PHONE_TEXT, capture.source)
        assertFalse(capture.hasOccurrence)
    }

    @Test
    fun alreadyHasOccurrenceThatIsHiddenShowsNoCardAndRefreshesRecent() {
        val review = ReviewResolutionService(repository, clock)
        repository.afterCreate = { id ->
            val resolved = review.resolve(id, ActivityTarget.Existing(mowLawn))
            memory.hideOccurrence(assertIs<ResolutionResult.Resolved>(resolved).occurrenceId)
        }
        val vm = started()
        vm.type("I cut the grass")

        assertNull(vm.card)
        assertFalse(vm.state.value.isCapturing)
        assertTrue(vm.state.value.recentLoaded)
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun failedUndoKeepsTheCardAndSaysSo() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        repository.failOn = setOf(FailPoint.HIDE)

        vm.undo()
        scheduler.runCurrent()

        assertIs<ResultCard.Saved>(vm.card)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertEquals(VisibilityStatus.ACTIVE, memory.occurrences.single().visibilityStatus)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedChangeActivityKeepsTheCardAndSaysSo() {
        val walk = memory.seedActivity("Walk dog")
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
        repository.failOn = setOf(FailPoint.CORRECT)

        vm.changeActivity(ActivityTarget.Existing(walk))
        scheduler.runCurrent()

        assertEquals("Mow lawn", assertIs<ResultCard.Saved>(vm.card).activityName)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertEquals(mowLawn, memory.occurrences.single().canonicalActivityId)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedResolveKeepsTheCardAndSaysSo() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        val card = vm.card
        repository.failOn = setOf(FailPoint.ACCEPT)

        vm.resolve(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertTrue(memory.occurrences.isEmpty())
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun failedPickerLoadKeepsTheCardAndSaysSo() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")
        val card = vm.card
        repository.failOn = setOf(FailPoint.CATALOG)

        vm.openPicker()
        scheduler.runCurrent()

        assertEquals(card, vm.card)
        assertNull(vm.state.value.picker)
        assertEquals(UserMessage(R.string.log_activities_not_loaded), vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun aSecondResolveWhileOneIsInFlightIsIgnored() {
        fake.enqueue(Results.needsReview(mowLawn))
        val vm = started()
        vm.type("did the yard")

        vm.resolve(ActivityTarget.Existing(mowLawn))
        assertTrue(vm.state.value.actionInFlight)
        vm.resolve(ActivityTarget.Existing(mowLawn))
        vm.onPickerChoice(ActivityTarget.New("Yard work"))
        scheduler.runCurrent()

        assertEquals(1, memory.occurrences.size)
        assertIs<ResultCard.Saved>(vm.card)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun aSecondChangeWhileOneIsInFlightIsIgnored() {
        val walk = memory.seedActivity("Walk dog")
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")

        vm.changeActivity(ActivityTarget.Existing(walk))
        vm.changeActivity(ActivityTarget.New("Trim hedge"))
        scheduler.runCurrent()

        assertEquals(1, memory.corrections.size)
        assertEquals("Walk dog", assertIs<ResultCard.Saved>(vm.card).activityName)
        assertNull(vm.state.value.message)
    }

    // ---- Recent and AI readiness ---------------------------------------------------------------

    @Test
    fun recentShowsTheThreeNewestRowsWithStates() {
        fake.enqueue(
            Results.existing(mowLawn),
            Results.needsReview(mowLawn),
            Results.failure(InterpreterFailureKind.UNAVAILABLE),
            Results.existing(mowLawn),
        )
        val vm = started()
        assertTrue(vm.state.value.recentLoaded)
        assertTrue(vm.state.value.recent.isEmpty())
        listOf("first", "second", "third", "fourth").forEach { words ->
            clock.advance(java.time.Duration.ofMinutes(1))
            vm.type(words)
        }

        val recent = vm.state.value.recent
        assertEquals(listOf("fourth", "third", "second"), recent.map { it.rawText })
        assertEquals("Mow lawn", recent[0].activityName)
        assertNull(recent[0].state)
        assertEquals(RowState.NOT_CATEGORIZED, recent[1].state)
        assertNull(recent[1].activityName)
        assertEquals(RowState.NEEDS_REVIEW, recent[2].state)
        assertEquals("Today, 8:02 PM", recent[2].time)
    }

    @Test
    fun aiNotReadyRowShowsWhenTheCheckSaysNoAndTypedCapturesStillWork() {
        aiReady = false
        fake.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()
        assertTrue(vm.state.value.showAiNotReady)
        assertTrue(vm.state.value.canSubmit.not()) // nothing typed yet
        vm.type("cut grass")
        assertIs<ResultCard.NotCategorized>(vm.card)
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

    // ---- Voice capture: the listening state (VC1.3 stubs, no recognizer) ---------------------

    @Test
    fun startListeningBlocksTypedSubmitAndDropsAnyCard() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        vm.type("I cut the grass")
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
        fake.enqueue(Results.existing(mowLawn))
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
        vm.type("I cut the grass")
        assertIs<ResultCard.Saved>(vm.card)
    }

    @Test
    fun partialsAreShownButNeverStoredNeverInterpretedAndNeverOutliveTheSession() {
        val vm = started()
        vm.listen(partial("I cut the"))

        assertEquals("I cut the", vm.state.value.partialTranscript)
        // Display only: no capture exists and the interpreter has not been asked anything.
        assertTrue(repository.created.isEmpty())
        assertEquals(0, fake.callCount)

        transcriber.emitNow(failed(SpeechFailure.NOTHING_HEARD))
        scheduler.runCurrent()

        assertEquals("", vm.state.value.partialTranscript)
        assertTrue(repository.created.isEmpty())
        assertEquals(0, fake.callCount)
    }

    // ---- Voice capture: the recognition-failure card -----------------------------------------

    @Test
    fun recognitionFailureShowsACardWithNoCaptureAndSavesNothing() {
        val vm = started()

        vm.listen(failed(SpeechFailure.NOTHING_HEARD))

        assertEquals(ResultCard.RecognitionFailed, vm.card)
        assertFalse(vm.state.value.isListening)
        // Nothing was heard, so nothing was stored: no raw capture exists at all.
        assertTrue(repository.created.isEmpty())
        assertTrue(read { memory.loadHistory() }.isEmpty())
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
    fun theFailureCardIsNeitherResolvableNorCorrectable() {
        val vm = started()
        vm.listen(failed(SpeechFailure.NOTHING_HEARD))

        // There is no capture behind it, so the review paths must simply not apply to it.
        vm.resolve(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()
        assertEquals(ResultCard.RecognitionFailed, vm.card)

        vm.decideLater()
        assertEquals(ResultCard.RecognitionFailed, vm.card)

        vm.onPickerChoice(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()
        assertEquals(ResultCard.RecognitionFailed, vm.card)
        assertTrue(read { memory.loadHistory() }.isEmpty())
    }

    // ---- Voice capture: microphone permission outcomes ---------------------------------------

    @Test
    fun refusingTheMicrophoneSaysSoAndLeavesTypingWorking() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.onMicrophonePermissionDenied()

        assertFalse(vm.state.value.isListening)
        assertEquals(UserMessage(R.string.log_mic_permission_denied), vm.state.value.message)
        vm.type("I cut the grass")
        assertIs<ResultCard.Saved>(vm.card)
    }

    @Test
    fun refusingTheMicrophoneForGoodSaysSoAndLeavesTypingWorking() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.onMicrophonePermissionBlocked()

        assertFalse(vm.state.value.isListening)
        assertEquals(UserMessage(R.string.log_mic_permission_blocked), vm.state.value.message)
        vm.type("I cut the grass")
        assertIs<ResultCard.Saved>(vm.card)
    }

    // ---- Voice capture: spoken words reaching the ledger --------------------------------------

    @Test
    fun oneRecognitionStoresExactlyOneVoiceCaptureAndShowsItsCard() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.listen(partial("I cut"), final("I cut the grass", confidence = 0.82f, alternatives = listOf("I cut the gas")))

        val stored = repository.created.single()
        assertEquals(CaptureSource.PHONE_VOICE, stored.source)
        assertEquals(LOG_VOICE_SOURCE_SURFACE, stored.sourceSurface)
        assertEquals("log_voice", stored.sourceSurface)
        assertEquals(ProcessingState.CAPTURED, stored.processingState)
        assertEquals("I cut the grass", stored.rawText)
        assertEquals(now, stored.capturedAt)
        assertEquals(zone, stored.zoneId)
        assertEquals(0.82f.toDouble(), stored.speechConfidence)
        assertEquals("[\"I cut the gas\"]", stored.speechAlternativesJson)
        // The final transcript is what reached the model -- never the partial.
        assertEquals("I cut the grass", fake.receivedInputs.single().rawText)
        assertIs<ResultCard.Saved>(vm.card)
        assertFalse(vm.state.value.isListening)
        assertEquals("", vm.state.value.partialTranscript)
        assertFalse(vm.state.value.isCapturing)
    }

    @Test
    fun anUnknownConfidenceStaysUnknownAndNoAlternativesMeansNoJson() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.listen(final("I cut the grass", confidence = null, alternatives = emptyList()))

        val stored = repository.created.single()
        assertNull(stored.speechConfidence)
        assertNull(stored.speechAlternativesJson)
    }

    @Test
    fun severalAlternativesAreStoredAsAJsonArrayWithTheirQuotesEscaped() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.listen(final("I cut the grass", alternatives = listOf("I cut the gas", "I \"cut\" the grass")))

        assertEquals(
            "[\"I cut the gas\",\"I \\\"cut\\\" the grass\"]",
            repository.created.single().speechAlternativesJson,
        )
    }

    @Test
    fun theRawCaptureIsCreatedOnceAndOnlyReadAfterwards() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.listen(final("I cut the grass", confidence = 0.5f))

        // One create for the whole session, and the stored capture is still exactly what was
        // created: the repository offers no way to rewrite one, and nothing here tries (ADR-007).
        val created = repository.created.single()
        val captureId = assertIs<ResultCard.Saved>(vm.card).captureId
        val stored = assertNotNull(read { memory.getCapture(captureId) })
        assertEquals(created.rawText, stored.rawText)
        assertEquals(created.source, stored.source)
        assertEquals(created.capturedAt, stored.capturedAt)
        assertEquals(created.speechConfidence, stored.speechConfidence)
        assertEquals(1, read { memory.loadHistory() }.size)
    }

    @Test
    fun spokenAndTypedWordsProduceTheSameCardThroughTheSamePath() {
        fake.enqueue(Results.existing(mowLawn))
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()

        vm.listen(final("I cut the grass", confidence = 0.9f, alternatives = listOf("I cut the gas")))
        val spoken = assertIs<ResultCard.Saved>(vm.card)
        vm.type("I cut the grass")
        val typed = assertIs<ResultCard.Saved>(vm.card)

        // Same card, identifiers aside: no branch anywhere depends on how the words arrived.
        assertEquals(
            typed.copy(captureId = "", occurrenceId = ""),
            spoken.copy(captureId = "", occurrenceId = ""),
        )
        // And both went through the orchestrator's one interpreter call with the same words.
        assertEquals(2, fake.callCount)
        assertEquals(listOf("I cut the grass", "I cut the grass"), fake.receivedInputs.map { it.rawText })
    }

    @Test
    fun aStorageFailureOnASpokenCaptureGivesTheWordsBackInsteadOfLosingThem() {
        val vm = started()
        repository.failOn = setOf(FailPoint.CREATE)

        vm.listen(final("I cut the grass"))

        assertTrue(repository.created.isEmpty())
        assertTrue(read { memory.loadHistory() }.isEmpty())
        // Nothing was saved, so the words are handed back to the capture field, not lost.
        assertEquals("I cut the grass", vm.state.value.input)
        assertEquals(UserMessage(R.string.log_capture_not_saved), vm.state.value.message)
        assertFalse(vm.state.value.isCapturing)
        assertNull(vm.card)
    }

    // ---- Voice capture: failed sessions store nothing ------------------------------------------

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
        assertTrue(read { memory.loadHistory() }.isEmpty())
    }

    @Test
    fun aResultAlreadyOnItsWayWhenTheUserStopsIsDroppedAndStoresNothing() {
        fake.enqueue(Results.existing(mowLawn))
        val vm = started()
        transcriber.willEmit(final("I cut the grass"))
        vm.startListening()

        // The result is queued but not yet delivered when the user taps stop.
        vm.stopListening()
        scheduler.runCurrent()

        assertTrue(repository.created.isEmpty())
        assertNull(vm.card)
        assertFalse(vm.state.value.isListening)
        assertEquals(0, fake.callCount)
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
        val gated = GatedInterpreter(fake)
        val vm = viewModel(gated)
        vm.startListening()
        scheduler.runCurrent()
        assertEquals(0, transcriber.sessionsStarted) // stopped: ADR-029
        assertFalse(vm.state.value.isListening)

        vm.onStart()
        scheduler.runCurrent()
        fake.enqueue(Results.existing(mowLawn))
        vm.type("I cut the grass") // still in flight: the interpreter is gated
        assertTrue(vm.state.value.isCapturing)

        vm.startListening()
        scheduler.runCurrent()
        assertEquals(0, transcriber.sessionsStarted)
        assertFalse(vm.state.value.isListening)

        gated.release()
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
