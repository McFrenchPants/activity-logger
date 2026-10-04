package com.mcfrenchpants.activityledger.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.log.Extracted
import com.mcfrenchpants.activityledger.ui.log.FailPoint
import com.mcfrenchpants.activityledger.ui.log.RecordingLedger
import com.mcfrenchpants.activityledger.ui.log.ScriptedExtractor
import com.mcfrenchpants.activityledger.ui.log.seedTags
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side tests of [HistoryViewModel] over the REAL ledger (an in-memory Room database), the
 * real tagged services and orchestrator, a scripted extractor, a fixed clock and a test Main
 * dispatcher. Tag meaning is never re-invented here: every decision comes from the real domain
 * rules, and waiting captures get their stored words by running through the real orchestrator.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val repository = RecordingLedger(ledger)
    private val extractor = ScriptedExtractor()
    private val orchestrator = TaggedCaptureOrchestrator(repository, extractor, clock)
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()

    private val hvacWords = "The hvac filter needs changing, done 20 minutes"
    private val tubWords = "Changed the hot tub filter for 30 minutes"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
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
                resolution = TaggedResolutionService(repository, clock),
                correction = TaggedCorrectionService(repository, clock),
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

    private fun <T> read(block: suspend () -> T): T = runSuspend(block)

    private fun catalog() = read { ledger.loadTagCatalog() }

    private fun subjectId(name: String) = catalog().subjects.single { it.displayName == name }.id

    private fun actionId(name: String) = catalog().actions.single { it.displayName == name }.id

    private fun history() = read { ledger.loadHistory() }

    private fun seed(subject: String, action: String) = seedTags(ledger, clock, zone, subject, action)

    /** Stores [text] one minute after the previous capture. Runs the extractor over it when [process]. */
    private fun capture(text: String, process: Boolean = true): String = read {
        clock.advance(Duration.ofMinutes(1))
        val id = ledger.createRawCapture(
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
        if (process) orchestrator.process(id)
        id
    }

    /** A waiting capture whose subject is only a close match for the seeded Furnace. */
    private fun closeMatchCapture(): String {
        seed("Furnace", "Change filter")
        extractor.on(hvacWords, Extracted.log("HVAC", "Change filter", duration = "20 minutes"))
        return capture(hvacWords)
    }

    /** A saved tagged entry (both tags new), with a 30 minute duration. */
    private fun savedTubEntry(): String {
        extractor.on(tubWords, Extracted.log("Hot tub", "Change filter", duration = "30 minutes"))
        return capture(tubWords)
    }

    private fun HistoryViewModel.row(captureId: String) = state.value.allRows.single { it.captureId == captureId }

    private val HistoryViewModel.check get() = state.value.check

    private fun HistoryViewModel.act(block: HistoryViewModel.() -> Unit) {
        block()
        scheduler.runCurrent()
    }

    // ---- Rows -----------------------------------------------------------------------------

    @Test
    fun aSavedTaggedEntryShowsSubjectActionAndDuration() {
        val id = savedTubEntry()
        val vm = started()

        val row = vm.row(id)
        assertTrue(row.isTagged)
        assertEquals("Hot tub", row.subjectName)
        assertEquals("Change filter", row.actionName)
        assertEquals(1_800L, row.durationSeconds)
        assertFalse(row.isAwaitingActivity)
        assertNull(row.state)
    }

    @Test
    fun anEntryFromTheOldPipelineKeepsItsActivityName() {
        val id = read {
            clock.advance(Duration.ofMinutes(1))
            val captureId = ledger.createRawCapture(
                NewRawCapture(CaptureSource.PHONE_TEXT, "log_typed", clock.instant(), zone, "cut the grass", null, null, ProcessingState.CAPTURED),
            )
            ledger.acceptInterpretation(
                captureId,
                InterpretationRecord(
                    createdAt = clock.instant(),
                    interpreterVersion = "old",
                    promptVersion = "old",
                    schemaVersion = 1,
                    operation = com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation.LOG_ACTIVITY,
                    activityResolution = ActivityResolution.NEW_ACTIVITY,
                    matchedActivityId = null,
                    proposedCanonicalName = "Mow lawn",
                    activityState = ActivityState.COMPLETED,
                    temporalExpression = null,
                    resolvedOccurredAt = clock.instant(),
                    timePrecision = TimePrecision.EXACT,
                    modelConfidenceBand = null,
                    candidateContextHash = null,
                    structuredResultJson = null,
                    validationStatus = ValidationStatus.VALID,
                    validationReason = null,
                ),
                ActivityTarget.New("Mow lawn"),
                clock.instant(),
                TimePrecision.EXACT,
                ActivityState.COMPLETED,
            )
            captureId
        }
        val vm = started()

        val row = vm.row(id)
        assertFalse(row.isTagged)
        assertEquals("Mow lawn", row.activityName)
        assertNull(row.subjectName)
        // Not editable from History: opening it does nothing.
        vm.act { openEdit(id) }
        assertNull(vm.state.value.edit)
        vm.act { openCheck(id) }
        assertNull(vm.check)
    }

    @Test
    fun waitingRowsKeepTheirStatesAndFilters() {
        val waiting = closeMatchCapture()
        val unavailable = capture("did a thing for the house") // the scripted extractor fails
        val saved = savedTubEntry()
        val vm = started()

        assertEquals(RowState.NEEDS_REVIEW, vm.row(waiting).state)
        assertTrue(vm.row(waiting).isAwaitingActivity)
        assertTrue(vm.row(unavailable).isAwaitingActivity)
        assertEquals(setOf(waiting, unavailable, saved), vm.state.value.rows.map { it.captureId }.toSet())

        vm.act { selectFilter(HistoryFilter.NEEDS_REVIEW) }
        val review = vm.state.value.rows.map { it.captureId }
        assertTrue(waiting in review)
        assertFalse(saved in review)
        vm.act { selectFilter(HistoryFilter.NOT_CATEGORIZED) }
        val notCategorized = vm.state.value.rows.map { it.captureId }
        assertFalse(waiting in notCategorized)
        assertFalse(saved in notCategorized)
        assertTrue(unavailable in notCategorized)
        vm.act { selectFilter(HistoryFilter.ALL) }
        assertEquals(3, vm.state.value.rows.size)
    }

    // ---- Waiting capture: the Check sheet ------------------------------------------------------

    @Test
    fun aWaitingCaptureIsRebuiltFromItsStoredWordsWithoutTheModel() {
        val id = closeMatchCapture()
        val vm = started()
        val callsBefore = extractor.callCount

        vm.act { openCheck(id) }

        val card = assertNotNull(vm.check)
        assertEquals(id, card.captureId)
        assertEquals(hvacWords, card.rawText)
        assertEquals(listOf("Furnace"), card.subject.candidates.map { it.displayName })
        assertNull(card.subject.chosen)
        assertEquals("hvac", card.subject.keepMine?.lowercase())
        assertEquals("Change filter", card.action.chosen?.name)
        assertEquals(TagChoice.Existing(actionId("Change filter")), card.action.chosen?.choice)
        assertEquals(1_200L, card.durationSeconds)
        assertNull(card.time)
        assertFalse(card.canSave)
        assertEquals(callsBefore, extractor.callCount, "no model call")
        assertTrue(history().none { it.captureId == id && it.occurrence != null })
    }

    @Test
    fun pickingTheCandidateSavesUnderTheExistingTagAtCaptureTimeAndLearnsTheAlias() {
        val id = closeMatchCapture()
        val capturedAt = history().single { it.captureId == id }.capturedAt
        val vm = started()
        vm.act { openCheck(id) }
        val furnace = subjectId("Furnace")

        vm.act { save() } // a side is still unchosen
        assertNotNull(vm.check)
        assertTrue(history().none { it.captureId == id && it.occurrence != null })

        vm.act { chooseCandidate(TagKind.SUBJECT, furnace) }
        assertTrue(assertNotNull(vm.check).canSave)
        vm.act { save() }

        assertNull(vm.check)
        val row = vm.row(id)
        assertTrue(row.isTagged)
        assertEquals("Furnace", row.subjectName)
        assertEquals("Change filter", row.actionName)
        assertEquals(1_200L, row.durationSeconds)
        val occurrence = history().single { it.captureId == id }.occurrence!!
        assertEquals(capturedAt, occurrence.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, occurrence.timePrecision)
        assertEquals(listOf("Furnace"), catalog().subjects.map { it.displayName })
        val aliases = catalog().subjects.single { it.id == furnace }.aliases
        assertTrue(aliases.any { it.equals("hvac", ignoreCase = true) }, "alias learned: $aliases")
    }

    @Test
    fun keepMineCreatesANewTag() {
        val id = closeMatchCapture()
        val vm = started()
        vm.act { openCheck(id) }

        vm.act { keepMine(TagKind.SUBJECT) }
        assertTrue(assertNotNull(vm.check).canSave)
        vm.act { save() }

        assertEquals("hvac", vm.row(id).subjectName?.lowercase())
        assertEquals(2, catalog().subjects.size)
    }

    @Test
    fun blankSidesNeedPicksBeforeSave() {
        val id = capture("did a thing for the house") // no extracted words: the AI was unavailable
        val vm = started()
        val callsBefore = extractor.callCount

        vm.act { openCheck(id) }

        val card = assertNotNull(vm.check)
        assertNull(card.subject.chosen)
        assertNull(card.action.chosen)
        assertTrue(card.subject.candidates.isEmpty())
        assertNull(card.durationSeconds)
        assertEquals(callsBefore, extractor.callCount)

        vm.act { openPicker(TagKind.SUBJECT) }
        assertEquals(TagKind.SUBJECT, vm.state.value.picker?.kind)
        vm.act { onPickerChoice(TagChoice.New("House")) }
        assertNull(vm.state.value.picker)
        assertFalse(assertNotNull(vm.check).canSave)
        vm.act { save() }
        assertNotNull(vm.check)

        vm.act { openPicker(TagKind.ACTION) }
        vm.act { onPickerChoice(TagChoice.New("Tidy")) }
        assertTrue(assertNotNull(vm.check).canSave)
        vm.act { save() }

        assertNull(vm.check)
        assertEquals("House", vm.row(id).subjectName)
        assertEquals("Tidy", vm.row(id).actionName)
        assertNull(vm.row(id).durationSeconds)
    }

    @Test
    fun decideLaterClosesTheSheetAndKeepsTheCaptureWaiting() {
        val id = closeMatchCapture()
        val vm = started()
        vm.act { openCheck(id) }

        vm.act { decideLater() }

        assertNull(vm.check)
        assertTrue(vm.row(id).isAwaitingActivity)
        assertTrue(history().none { it.captureId == id && it.occurrence != null })
    }

    @Test
    fun aRefusedSaveKeepsTheSheetWithAPlainMessage() {
        val id = closeMatchCapture()
        val vm = started()
        vm.act { openCheck(id) }
        vm.act { chooseCandidate(TagKind.SUBJECT, subjectId("Furnace")) }
        vm.act { openPicker(TagKind.ACTION) }
        vm.act { onPickerChoice(TagChoice.New("")) } // a blank pick is not shown as a choice
        assertEquals("Change filter", vm.check?.action?.chosen?.name)

        // A new name that is only time words is refused by the domain's name rules.
        vm.act { openPicker(TagKind.ACTION) }
        vm.act { onPickerChoice(TagChoice.New("Yesterday morning")) }
        vm.act { save() }

        assertNotNull(vm.check)
        val message = assertNotNull(vm.state.value.message)
        assertTrue(message.args.none { it.toString().contains("hvac", ignoreCase = true) })
        assertTrue(history().none { it.captureId == id && it.occurrence != null })
    }

    @Test
    fun aStorageFailureOnSaveKeepsTheSheet() {
        val id = closeMatchCapture()
        val vm = started()
        vm.act { openCheck(id) }
        vm.act { chooseCandidate(TagKind.SUBJECT, subjectId("Furnace")) }
        repository.failOn = setOf(FailPoint.ACCEPT)

        vm.act { save() }

        assertNotNull(vm.check)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertFalse(vm.state.value.actionInFlight)
        repository.failOn = emptySet()
        vm.act { save() }
        assertNull(vm.check)
        assertTrue(vm.row(id).isTagged)
    }

    @Test
    fun aFailedTagLoadKeepsTheSheetAndSaysSo() {
        val id = closeMatchCapture()
        val vm = started()
        vm.act { openCheck(id) }
        repository.failOn = setOf(FailPoint.CATALOG)

        vm.act { openPicker(TagKind.SUBJECT) }

        assertNull(vm.state.value.picker)
        assertNotNull(vm.check)
        assertEquals(UserMessage(R.string.log_tags_not_loaded), vm.state.value.message)
    }

    @Test
    fun aSecondActionWhileOneIsRunningIsIgnored() {
        val id = closeMatchCapture()
        val vm = started()

        vm.openCheck(id) // not run yet: the action is in flight
        vm.openCheck(id)
        scheduler.runCurrent()

        assertNotNull(vm.check)
        assertFalse(vm.state.value.actionInFlight)
    }

    // ---- Saved entry: the Edit sheet -------------------------------------------------------------

    @Test
    fun changingTheSubjectFromTheEditSheetWritesOneCorrectionAndRefreshesTheNames() {
        seed("Furnace", "Change filter")
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }
        val edit = assertNotNull(vm.state.value.edit)
        assertEquals("Hot tub", edit.subjectName)
        assertEquals(tubWords, edit.rawText)

        vm.act { openPicker(TagKind.SUBJECT) }
        vm.act { onPickerChoice(TagChoice.Existing(subjectId("Furnace"))) }

        assertEquals(1, repository.corrections.size)
        assertEquals("Furnace", vm.state.value.edit?.subjectName)
        assertEquals("Change filter", vm.state.value.edit?.actionName)
        assertEquals("Furnace", vm.row(id).subjectName)
        assertNull(vm.state.value.picker)
    }

    @Test
    fun changingTheActionFromTheEditSheetRefreshesTheNames() {
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }

        vm.act { openPicker(TagKind.ACTION) }
        vm.act { onPickerChoice(TagChoice.New("Drain")) }

        assertEquals(1, repository.corrections.size)
        assertEquals("Drain", vm.state.value.edit?.actionName)
        assertEquals("Drain", vm.row(id).actionName)
        assertEquals("Hot tub", vm.row(id).subjectName)
    }

    @Test
    fun aRefusedCorrectionKeepsTheEditSheetWithAPlainMessage() {
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }
        vm.act { openPicker(TagKind.ACTION) }

        vm.act { onPickerChoice(TagChoice.New("")) }

        assertNotNull(vm.state.value.edit)
        assertEquals(UserMessage(R.string.refusal_name_empty), vm.state.value.message)
        assertTrue(repository.corrections.isEmpty())
        assertEquals("Change filter", vm.row(id).actionName)
    }

    @Test
    fun aStorageFailureOnCorrectionKeepsTheEditSheet() {
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }
        vm.act { openPicker(TagKind.ACTION) }
        repository.failOn = setOf(FailPoint.CORRECT)

        vm.act { onPickerChoice(TagChoice.New("Drain")) }

        assertNotNull(vm.state.value.edit)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertEquals("Change filter", vm.row(id).actionName)
    }

    @Test
    fun removeFromHistoryHidesTheEntryAndKeepsTheWords() {
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }

        vm.act { removeFromHistory() }

        assertNull(vm.state.value.edit)
        assertTrue(vm.state.value.allRows.none { it.captureId == id })
        val capture = read { ledger.getCapture(id) }
        assertNotNull(capture)
        assertEquals(tubWords, capture.rawText)
    }

    @Test
    fun aFailedRemovalKeepsTheEditSheet() {
        val id = savedTubEntry()
        val vm = started()
        vm.act { openEdit(id) }
        repository.failOn = setOf(FailPoint.HIDE)

        vm.act { removeFromHistory() }

        assertNotNull(vm.state.value.edit)
        assertEquals(UserMessage(R.string.log_action_failed), vm.state.value.message)
        assertTrue(vm.state.value.allRows.any { it.captureId == id })
    }

    @Test
    fun aStorageFailureOnLoadShowsAPlainMessageAndRecovers() {
        val vm = viewModel()
        repository.failOn = setOf(FailPoint.HISTORY)
        vm.act { onStart() }
        assertEquals(UserMessage(R.string.history_not_loaded), vm.state.value.message)
        assertFalse(vm.state.value.loaded)

        repository.failOn = emptySet()
        vm.act { onStart() }
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.message)
    }
}
