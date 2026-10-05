package com.mcfrenchpants.activityledger.ui.explore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.log.ScriptedTranscriber
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ExploreViewModel] keeps itself up to date while the screen is showing: a ledger change (a
 * [MutableSharedFlow] standing in for the database's change reports) leads, after the changes
 * settle, to a quiet reload that keeps everything the person chose. Real in-memory ledger behind
 * the write-refusing wrapper, real lookup service and calculator, virtual time.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreLiveReloadTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val tags = WriteRefusingExploreTags(ledger)
    private val extractor = ExploreQuestionExtractor()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private val august = YearMonth.of(2026, 8)
    private val augustRange = DateRangeSelection.Custom(august.atDay(1), august.atEndOfMonth(), RangeLabel.Month(august))
    private val allTime = DateRangeSelection.Preset(DateRangePreset.ALL_TIME)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** Lawn/Mow on Aug 1; Hot tub/Clean, Furnace/Change filter and Lawn/Mow on Sep 14; now is Sep 15. */
    private fun seed() {
        logAt(ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, zone), "Lawn", "Mow")
        clock.currentInstant = ZonedDateTime.of(2026, 9, 14, 9, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Hot tub", "Clean")
        clock.advance(Duration.ofHours(1))
        logExploreEntry(ledger, clock, zone, "Furnace", "Change filter")
        clock.advance(Duration.ofHours(1))
        logExploreEntry(ledger, clock, zone, "Lawn", "Mow")
        clock.currentInstant = now
    }

    /** Saves an entry at [at] (behind the view model's back, like the background capture does). */
    private fun logAt(at: ZonedDateTime, subject: String, action: String) {
        clock.currentInstant = at.toInstant()
        logExploreEntry(ledger, clock, zone, subject, action)
        clock.currentInstant = now
    }

    private fun logToday(subject: String, action: String) = logAt(now.atZone(zone).minusHours(1), subject, action)

    private fun id(kind: TagKind, name: String) = tagIdOf(ledger, kind, name)

    private fun viewModel(compute: CoroutineDispatcher = dispatcher): ExploreViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExploreViewModel(
                repository = tags,
                lookup = LookupService(tags, extractor),
                transcriber = ScriptedTranscriber(),
                clock = clock,
                zoneProvider = { zone },
                firstDayOfWeekProvider = { DayOfWeek.MONDAY },
                computeDispatcher = compute,
                changes = changes,
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[ExploreViewModel::class.java]
        vm.onStart()
        scheduler.runCurrent()
        return vm
    }

    private val ExploreViewModel.s get() = state.value

    private fun ExploreViewModel.settle(action: ExploreViewModel.() -> Unit) {
        action()
        scheduler.runCurrent()
    }

    /** The ledger reports a change. */
    private fun changed() {
        check(changes.tryEmit(Unit))
        scheduler.runCurrent()
    }

    /** Lets virtual time pass. */
    private fun pass(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** Past the settle time: a pending quiet reload runs. */
    private fun settled() = pass(SETTLE_MS + 1)

    @Test
    fun `a change while showing reloads after the changes settle`() {
        seed()
        val vm = viewModel()
        assertEquals(3, vm.s.summary?.entriesInPeriod)
        assertEquals(1, tags.exploreLoads)

        logToday("Hot tub", "Clean")
        changed()
        pass(SETTLE_MS - 1)
        assertEquals(1, tags.exploreLoads, "nothing reloads before the changes settle")

        pass(2)
        assertEquals(2, tags.exploreLoads)
        assertEquals(4, vm.s.summary?.entriesInPeriod, "the new entry is counted")
        assertEquals(5, vm.s.summary?.totalEntriesEver)
        assertFalse(vm.s.isLoading)
        assertTrue(tags.writeAttempts.isEmpty())
    }

    @Test
    fun `filters, drill-down, sorts, view and the box text survive a quiet reload`() {
        seed()
        val vm = viewModel()
        val lawn = id(TagKind.SUBJECT, "Lawn")
        val mow = id(TagKind.ACTION, "Mow")
        val row = assertNotNull(vm.s.summary).activities.first { it.subjectId == lawn }
        vm.settle { openActivity(row) }
        vm.settle { setEntrySort(EntrySort.OLDEST) }
        vm.settle { setView(ExploreView.PATTERNS) }
        vm.settle { onInputChange("hot") }
        val before = vm.s
        assertEquals(1, before.summary?.entriesInPeriod)

        logToday("Lawn", "Mow")
        changed()
        settled()

        val s = vm.s
        assertEquals(ExploreFilter(before.filter.range, lawn, mow, null), s.filter)
        assertTrue(s.canGoBack, "the drill-down step is kept")
        assertEquals(before.entrySort, s.entrySort)
        assertEquals(ExploreView.PATTERNS, s.view)
        assertEquals("hot", s.input)
        assertEquals(before.suggestions, s.suggestions)
        assertEquals("Lawn", s.subjectChipName)
        assertEquals(2, s.summary?.entriesInPeriod, "the new Lawn / Mow entry is counted")
        assertIs<ExploreAnswer.Scope>(s.answer)

        assertTrue(vm.back(), "back still leads to the filters before the drill-down")
        scheduler.runCurrent()
        assertEquals(ExploreFilter(), vm.s.filter)
    }

    @Test
    fun `a count answer survives a quiet reload and is re-counted from the new data`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August", kind = QuestionKind.COUNT)
        val vm = viewModel()
        val text = "How many times did I mow the lawn in August?"
        vm.settle { ask(text) }
        assertEquals(1, assertIs<ExploreAnswer.Count>(vm.s.answer).count)
        val filter = vm.s.filter

        logAt(ZonedDateTime.of(2026, 8, 20, 10, 0, 0, 0, zone), "Lawn", "Mow")
        changed()
        settled()

        val s = vm.s
        assertEquals(filter, s.filter)
        assertEquals(text, s.askedQuestion)
        assertTrue(s.canGoBack)
        val count = assertIs<ExploreAnswer.Count>(s.answer)
        assertEquals(2, count.count)
        assertEquals(augustRange, count.range)
        assertEquals("Lawn", count.subjectName)
        assertEquals(ExploreView.ENTRIES, s.view)
    }

    private fun askLastMow(vm: ExploreViewModel): ExploreAnswer.LastTime {
        extractor.answers("lawn", "mow", kind = QuestionKind.LAST_TIME)
        vm.settle { ask("When did I last mow the lawn?") }
        return assertIs<ExploreAnswer.LastTime>(vm.s.answer)
    }

    @Test
    fun `a last-time answer is unchanged by a quiet reload after an unrelated change`() {
        seed()
        val vm = viewModel()
        val answer = askLastMow(vm)
        assertEquals(ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant(), answer.lastTime)
        val filter = vm.s.filter

        logToday("Hot tub", "Clean")
        changed()
        settled()

        assertEquals(answer, vm.s.answer)
        assertEquals(filter, vm.s.filter)
        assertEquals(2, tags.exploreLoads)
    }

    @Test
    fun `a last-time answer moves to a newer matching entry after a quiet reload`() {
        seed()
        val vm = viewModel()
        val answer = askLastMow(vm)
        val filter = vm.s.filter
        val view = vm.s.view

        val mowedAt = now.atZone(zone).minusHours(1)
        logAt(mowedAt, "Lawn", "Mow")
        changed()
        settled()

        val updated = assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        assertEquals(answer.copy(lastTime = mowedAt.toInstant()), updated, "only the date moves; names and notes stay")
        assertEquals(filter, vm.s.filter)
        assertEquals(view, vm.s.view)
        assertEquals("When did I last mow the lawn?", vm.s.askedQuestion)
        assertTrue(vm.s.canGoBack)
    }

    /** "When did I last mow the hot tub?": no Hot tub / Mow entry exists, so the best entry is a partial match. */
    private fun askPartial(vm: ExploreViewModel): ExploreAnswer.LastTime {
        extractor.answers("hot tub", "mow", kind = QuestionKind.LAST_TIME)
        vm.settle { ask("When did I last mow the hot tub?") }
        val answer = assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        assertEquals(ClosestMatch.PARTIAL, answer.closestMatch)
        assertEquals(ExploreFilter(allTime, id(TagKind.SUBJECT, "Hot tub"), id(TagKind.ACTION, "Mow"), null), vm.s.filter)
        return answer
    }

    @Test
    fun `a partial-match last-time answer is unchanged by an unrelated quiet reload`() {
        seed()
        val vm = viewModel()
        val answer = askPartial(vm)

        logToday("Furnace", "Change filter")
        changed()
        settled()

        assertEquals(answer, vm.s.answer)
    }

    @Test
    fun `a partial-match last-time answer advances with a newer entry of the same pair`() {
        seed()
        val vm = viewModel()
        val answer = askPartial(vm)
        val subject = assertNotNull(answer.subjectName)
        val action = assertNotNull(answer.actionName)

        val at = now.atZone(zone).minusHours(1)
        logAt(at, subject, action)
        changed()
        settled()

        assertEquals(answer.copy(lastTime = at.toInstant()), vm.s.answer, "same names and note, newer date")
    }

    @Test
    fun `logging the full target pair later does not mix names and dates`() {
        seed()
        val vm = viewModel()
        val answer = askPartial(vm)

        logToday("Hot tub", "Mow")
        changed()
        settled()

        // The answer names the partial entry's pair; a Hot tub / Mow entry is not that pair.
        assertEquals(answer, vm.s.answer)
        assertEquals(1, vm.s.summary?.entriesInPeriod, "the chips themselves count the new entry")
    }

    @Test
    fun `coming back to the screen advances a showing last-time answer`() {
        seed()
        val vm = viewModel()
        val answer = askLastMow(vm)

        vm.onStop()
        val at = now.atZone(zone).minusHours(1)
        logAt(at, "Lawn", "Mow")
        vm.settle { onStart() }

        assertEquals(answer.copy(lastTime = at.toInstant()), vm.s.answer)
        assertFalse(vm.s.isLoading)
    }

    @Test
    fun `coming back to the screen re-counts a showing count answer`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August", kind = QuestionKind.COUNT)
        val vm = viewModel()
        vm.settle { ask("How many times did I mow the lawn in August?") }
        assertEquals(1, assertIs<ExploreAnswer.Count>(vm.s.answer).count)

        vm.onStop()
        logAt(ZonedDateTime.of(2026, 8, 20, 10, 0, 0, 0, zone), "Lawn", "Mow")
        vm.settle { onStart() }

        assertEquals(2, assertIs<ExploreAnswer.Count>(vm.s.answer).count)
    }

    @Test
    fun `the first answer of a question still comes from the question reader`() {
        seed()
        val vm = viewModel()
        val first = askLastMow(vm)
        assertEquals(ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant(), first.lastTime)

        // A newer mow the screen has not loaded yet: a new question is answered by the reader,
        // which reads the ledger itself, not rebuilt from the screen's older data.
        val at = now.atZone(zone).minusHours(1)
        logAt(at, "Lawn", "Mow")
        val second = askLastMow(vm)
        assertEquals(at.toInstant(), second.lastTime)
        assertEquals(1, tags.exploreLoads, "no reload happened")
    }

    @Test
    fun `a last-time answer whose entries were all removed falls back to the scope line`() {
        seed()
        val vm = viewModel()
        askLastMow(vm)
        val filter = vm.s.filter

        runSuspend {
            ledger.loadExploreEntries().filter { it.subjectName == "Lawn" }.forEach { ledger.hideOccurrence(it.occurrenceId) }
        }
        changed()
        settled()

        val scope = assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertEquals(0, scope.entriesInPeriod)
        assertEquals(filter, vm.s.filter, "the chips stay")

        logToday("Lawn", "Mow")
        changed()
        settled()
        assertIs<ExploreAnswer.Scope>(vm.s.answer, "a dropped answer does not come back by itself")
    }

    @Test
    fun `after onStop changes are not handled, and onStart watches again`() {
        seed()
        val vm = viewModel()

        vm.onStop()
        logToday("Hot tub", "Clean")
        changed()
        settled()
        assertEquals(1, tags.exploreLoads)
        assertEquals(3, vm.s.summary?.entriesInPeriod)

        vm.settle { onStart() }
        assertEquals(2, tags.exploreLoads)
        assertEquals(4, vm.s.summary?.entriesInPeriod)

        logToday("Lawn", "Mow")
        changed()
        settled()
        assertEquals(3, tags.exploreLoads, "watching again after onStart")
        assertEquals(5, vm.s.summary?.entriesInPeriod)
    }

    @Test
    fun `a second onStart does not start a second watcher`() {
        seed()
        val vm = viewModel()
        vm.settle { onStart() }
        assertEquals(2, tags.exploreLoads)

        changed()
        settled()

        assertEquals(3, tags.exploreLoads, "one change, one quiet reload")
    }

    @Test
    fun `a burst of changes causes one reload`() {
        seed()
        val vm = viewModel()

        repeat(5) {
            logToday("Hot tub", "Clean")
            changed()
            pass(100)
        }
        assertEquals(1, tags.exploreLoads)
        settled()

        assertEquals(2, tags.exploreLoads)
        assertEquals(8, vm.s.summary?.entriesInPeriod)
    }

    @Test
    fun `a quiet reload never turns the loading state on`() {
        seed()
        val compute = ManualDispatcher()
        val vm = viewModel(compute = compute)
        compute.runAt(0)
        scheduler.runCurrent()
        assertFalse(vm.s.isLoading)
        val seen = mutableListOf<Boolean>()
        val recorder = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            vm.state.collect { seen += it.isLoading }
        }

        logToday("Hot tub", "Clean")
        changed()
        settled()
        assertEquals(1, compute.queue.size, "the quiet reload's count is computing")
        assertFalse(vm.s.isLoading, "no loading state while it computes")
        compute.runAt(0)
        scheduler.runCurrent()
        recorder.cancel()

        assertEquals(4, vm.s.summary?.entriesInPeriod)
        assertTrue(seen.none { it }, "isLoading was never true: $seen")
    }

    @Test
    fun `a quiet reload whose load finishes after a newer filter change does not overwrite it`() {
        seed()
        val vm = viewModel()
        val lawn = id(TagKind.SUBJECT, "Lawn")
        val gate = CompletableDeferred<Unit>()
        tags.loadGate = gate

        logToday("Lawn", "Mow")
        changed()
        settled()
        assertEquals(2, tags.exploreLoads, "the quiet load is in flight")

        vm.settle { setSubject(lawn) }
        assertEquals(lawn, vm.s.filter.subjectId)
        assertEquals(1, vm.s.summary?.entriesInPeriod, "counted from the data loaded before")

        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(ExploreFilter(subjectId = lawn), vm.s.filter)
        assertEquals(ExploreView.ENTRIES, vm.s.view)
        assertEquals(2, vm.s.summary?.entriesInPeriod, "the newer filter, counted over the new data")
        assertFalse(vm.s.isLoading)
    }

    @Test
    fun `a quiet reload's count that finishes after a newer filter's count is dropped`() {
        seed()
        val compute = ManualDispatcher()
        val vm = viewModel(compute = compute)
        compute.runAt(0)
        scheduler.runCurrent()
        val lawn = id(TagKind.SUBJECT, "Lawn")

        logToday("Hot tub", "Clean")
        changed()
        settled()
        vm.settle { setSubject(lawn) }
        assertEquals(2, compute.queue.size)

        compute.runAt(1)
        scheduler.runCurrent()
        val newer = vm.s.summary
        assertEquals(lawn, vm.s.filter.subjectId)
        compute.runAt(0)
        scheduler.runCurrent()

        assertEquals(newer, vm.s.summary, "the stale count does not overwrite the newer one")
        assertEquals(ExploreFilter(subjectId = lawn), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
    }

    @Test
    fun `a failed quiet reload keeps what is shown`() {
        seed()
        val vm = viewModel()
        val before = vm.s

        tags.failLoads = true
        changed()
        settled()

        assertEquals(before.summary, vm.s.summary)
        assertEquals(before.filter, vm.s.filter)
        assertFalse(vm.s.isLoading)
        assertEquals(UserMessage(R.string.history_not_loaded), vm.s.message)

        tags.failLoads = false
        changed()
        settled()
        assertEquals(null, vm.s.message, "a later successful reload clears the not-loaded message")
    }

    @Test
    fun `a failed quiet reload does not replace another message`() {
        seed()
        val vm = viewModel()
        vm.settle { onMicrophonePermissionDenied() }
        val shown = vm.s.message

        tags.failLoads = true
        changed()
        settled()

        assertEquals(shown, vm.s.message)
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}
