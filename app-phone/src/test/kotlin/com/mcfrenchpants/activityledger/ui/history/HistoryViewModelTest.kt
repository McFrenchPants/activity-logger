package com.mcfrenchpants.activityledger.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.log.FailPoint
import com.mcfrenchpants.activityledger.ui.log.RecordingRepository
import com.mcfrenchpants.activityledger.ui.log.Results
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests of [HistoryViewModel] over the in-memory repository, the real review-resolution
 * service, a fixed clock and a test Main dispatcher. Captures are seeded through the real
 * orchestrator and a scripted interpreter so every processing state is the real one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val memory = InMemoryActivityRepository(clock)
    private val repository = RecordingRepository(memory)
    private val fake = FakeActivityInterpreter()
    private val orchestrator = CaptureInterpretationOrchestrator(memory, fake, clock)
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()

    private val mowLawn = memory.seedActivity("Mow lawn")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): HistoryViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(
                repository = repository,
                reviewResolutionService = ReviewResolutionService(repository, clock),
                clock = clock,
                locale = { Locale.US },
            ) as T
        }
        return ViewModelProvider(store, factory)[HistoryViewModel::class.java]
    }

    private fun started(): HistoryViewModel = viewModel().also {
        it.onStart()
        scheduler.runCurrent()
    }

    /**
     * Stores [text] one minute after the previous capture and, when [result] is given, runs it
     * through the orchestrator with that interpreter answer. Returns the capture id.
     */
    private fun capture(text: String, result: InterpretationResult? = null): String = runSuspend {
        clock.advance(Duration.ofMinutes(1))
        val id = memory.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = "log_typed",
                capturedAt = clock.instant(),
                zoneId = zone,
                rawText = text,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        if (result != null) {
            fake.enqueue(result)
            orchestrator.process(id)
        }
        id
    }

    private fun HistoryViewModel.open(captureId: String) {
        openResolution(captureId)
        scheduler.runCurrent()
    }

    private val HistoryViewModel.ui get() = state.value

    // ---- Loading and filters ----------------------------------------------------------------

    /** Interpreted, Needs review, Not categorized (AI unavailable) and plain Captured, oldest first. */
    private fun seedMix(): List<String> = listOf(
        capture("cut the grass", Results.existing(mowLawn)),
        capture("did the yard", Results.needsReview(mowLawn)),
        capture("fixed it", Results.failure(InterpreterFailureKind.UNAVAILABLE)),
        capture("something"),
    )

    @Test
    fun rowsKeepLoadHistoryOrderNewestFirst() {
        seedMix()
        val vm = started()

        val expected = runSuspend { memory.loadHistory() }.map { it.captureId }
        assertTrue(vm.ui.loaded)
        assertEquals(expected, vm.ui.rows.map { it.captureId })
        assertEquals(listOf("something", "fixed it", "did the yard", "cut the grass"), vm.ui.rows.map { it.rawText })
        assertEquals("Mow lawn", vm.ui.rows.last().activityName)
        assertEquals("Today, 8:01 PM", vm.ui.rows.last().time)
    }

    @Test
    fun eachFilterShowsItsRows() {
        val (interpreted, review, failed, captured) = seedMix()
        assertEquals(
            ProcessingState.FAILED_RETRYABLE,
            runSuspend { memory.getCapture(failed) }?.processingState,
        )
        val vm = started()
        assertEquals(HistoryFilter.ALL, vm.ui.filter)
        assertEquals(listOf(captured, failed, review, interpreted), vm.ui.rows.map { it.captureId })

        vm.selectFilter(HistoryFilter.NEEDS_REVIEW)
        assertEquals(listOf(review), vm.ui.rows.map { it.captureId })
        assertEquals(RowState.NEEDS_REVIEW, vm.ui.rows.single().state)

        vm.selectFilter(HistoryFilter.NOT_CATEGORIZED)
        assertEquals(listOf(captured, failed), vm.ui.rows.map { it.captureId })
        assertTrue(vm.ui.rows.all { it.state == RowState.NOT_CATEGORIZED && it.activityName == null })

        vm.selectFilter(HistoryFilter.ALL)
        assertEquals(4, vm.ui.rows.size)
    }

    @Test
    fun hiddenOccurrencesAreAbsent() {
        val kept = capture("cut the grass", Results.existing(mowLawn))
        val hidden = capture("mowed again", Results.existing(mowLawn))
        runSuspend {
            val occurrenceId = memory.loadHistory().first { it.captureId == hidden }.occurrence!!.occurrenceId
            memory.hideOccurrence(occurrenceId)
        }
        val vm = started()
        assertEquals(listOf(kept), vm.ui.rows.map { it.captureId })
    }

    @Test
    fun loadFailureShowsAMessageWithoutCrashing() {
        seedMix()
        repository.failOn = setOf(FailPoint.HISTORY)
        val vm = started()

        assertFalse(vm.ui.loaded)
        assertTrue(vm.ui.rows.isEmpty())
        assertEquals(UserMessage(R.string.history_not_loaded), vm.ui.message)

        repository.failOn = emptySet()
        vm.onStart()
        scheduler.runCurrent()
        assertTrue(vm.ui.loaded)
        assertEquals(4, vm.ui.rows.size)
        assertNull(vm.ui.message)
    }

    // ---- Opening a row ------------------------------------------------------------------------

    @Test
    fun openingARowPutsThePendingMatchFirstThenSelectorOrderCappedAtThree() {
        val walk = memory.seedActivity("Walk dog")
        memory.seedActivity("Fix fence")
        memory.seedActivity("Bake bread")
        val id = capture("took the dog out", Results.needsReview(walk))
        val vm = started()

        vm.open(id)

        val resolution = assertNotNull(vm.ui.resolution)
        assertEquals(id, resolution.captureId)
        assertEquals("took the dog out", resolution.rawText)
        assertTrue(resolution.needsReview)
        assertEquals(listOf("Walk dog", "Bake bread", "Fix fence"), resolution.suggestions.map { it.displayName })
    }

    @Test
    fun suggestionsAreDeduplicated() {
        val bake = memory.seedActivity("Bake bread")
        val id = capture("made a loaf", Results.needsReview(bake))
        val vm = started()

        vm.open(id)

        assertEquals(listOf(bake, mowLawn), assertNotNull(vm.ui.resolution).suggestions.map { it.activityId })
    }

    @Test
    fun notCategorizedRowsOpenWithoutTheReviewFlagAndInterpretedRowsDoNotOpen() {
        val interpreted = capture("cut the grass", Results.existing(mowLawn))
        val failed = capture("fixed it", Results.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()

        vm.open(interpreted)
        assertNull(vm.ui.resolution)

        vm.open(failed)
        assertFalse(assertNotNull(vm.ui.resolution).needsReview)
    }

    // ---- Resolving ----------------------------------------------------------------------------

    @Test
    fun resolveToExistingMakesTheRowInterpretedAndLeavesTheNeedsReviewFilter() {
        val id = capture("did the yard", Results.needsReview(mowLawn))
        val vm = started()
        vm.selectFilter(HistoryFilter.NEEDS_REVIEW)
        vm.open(id)

        vm.resolve(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()

        assertNull(vm.ui.resolution)
        assertFalse(vm.ui.actionInFlight)
        assertTrue(vm.ui.rows.isEmpty())
        val row = vm.ui.allRows.single()
        assertEquals("Mow lawn", row.activityName)
        assertFalse(row.isAwaitingActivity)
        assertEquals(mowLawn, memory.occurrences.single().canonicalActivityId)
    }

    @Test
    fun resolveToNewActivityViaThePickerMakesTheRowInterpreted() {
        val id = capture("fixed it", Results.failure(InterpreterFailureKind.UNAVAILABLE))
        val vm = started()
        vm.selectFilter(HistoryFilter.NOT_CATEGORIZED)
        vm.open(id)
        vm.openPicker(startWithNewActivity = true)
        scheduler.runCurrent()
        assertTrue(assertNotNull(vm.ui.picker).startWithNewActivity)

        vm.onPickerChoice(ActivityTarget.New("Fix fence"))
        scheduler.runCurrent()

        assertNull(vm.ui.picker)
        assertNull(vm.ui.resolution)
        assertTrue(vm.ui.rows.isEmpty())
        assertEquals("Fix fence", vm.ui.allRows.single().activityName)
    }

    @Test
    fun refusedResolveShowsAMessageAndKeepsTheSheet() {
        val id = capture("did the yard", Results.needsReview(mowLawn))
        val vm = started()
        vm.open(id)
        val resolution = vm.ui.resolution

        vm.resolve(ActivityTarget.New("Mow lawn"))
        scheduler.runCurrent()

        assertEquals(resolution, vm.ui.resolution)
        assertEquals(UserMessage(R.string.refusal_name_matches_existing, listOf("Mow lawn")), vm.ui.message)
        assertTrue(memory.occurrences.isEmpty())
        assertFalse(vm.ui.actionInFlight)
    }

    @Test
    fun storageFailureOnResolveShowsAMessageAndKeepsTheSheet() {
        val id = capture("did the yard", Results.needsReview(mowLawn))
        val vm = started()
        vm.open(id)
        repository.failOn = setOf(FailPoint.ACCEPT)

        vm.resolve(ActivityTarget.Existing(mowLawn))
        scheduler.runCurrent()

        assertNotNull(vm.ui.resolution)
        assertEquals(UserMessage(R.string.log_action_failed), vm.ui.message)
        assertTrue(memory.occurrences.isEmpty())
        assertFalse(vm.ui.actionInFlight)
    }

    @Test
    fun aSecondResolveWhileOneIsInFlightIsIgnored() {
        val id = capture("did the yard", Results.needsReview(mowLawn))
        val vm = started()
        vm.open(id)

        vm.resolve(ActivityTarget.Existing(mowLawn))
        assertTrue(vm.ui.actionInFlight)
        vm.resolve(ActivityTarget.Existing(mowLawn))
        vm.onPickerChoice(ActivityTarget.New("Yard work"))
        scheduler.runCurrent()

        assertEquals(1, memory.occurrences.size)
        assertEquals(mowLawn, memory.occurrences.single().canonicalActivityId)
        assertNull(vm.ui.message)
        assertFalse(vm.ui.actionInFlight)
    }

    @Test
    fun decideLaterClosesWithoutWriting() {
        val id = capture("did the yard", Results.needsReview(mowLawn))
        val vm = started()
        vm.open(id)
        val writes = memory.writeCount

        vm.decideLater()
        scheduler.runCurrent()

        assertNull(vm.ui.resolution)
        assertNull(vm.ui.picker)
        assertEquals(writes, memory.writeCount)
        assertEquals(RowState.NEEDS_REVIEW, vm.ui.rows.single().state)
    }
}
