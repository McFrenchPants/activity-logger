package com.mcfrenchpants.activityledger.ui.explore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.stats.ActivityRow
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.stats.ScopeKind
import com.mcfrenchpants.activityledger.core.domain.stats.SubjectGroup
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.log.ScriptedTranscriber
import com.mcfrenchpants.activityledger.ui.log.seedTags
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side tests of [ExploreViewModel] over the REAL ledger (in-memory Room) seen through a
 * write-refusing wrapper, the real lookup service and calculator, a scripted question extractor
 * and a scripted transcriber.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class ExploreViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val tags = WriteRefusingExploreTags(ledger)
    private val extractor = ExploreQuestionExtractor()
    private val transcriber = ScriptedTranscriber()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()

    private val question = "When did I last mow the lawn?"
    private val newestMowAt: Instant = ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /**
     * Four entries: Lawn/Mow on Aug 1 (outside Last 30 days), Hot tub/Clean, Furnace/Change filter
     * (then renamed Heating unit, keeping "Furnace" as an alias) and Lawn/Mow on Sep 14; now is Sep 15.
     */
    private fun seed() {
        clock.currentInstant = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Lawn", "Mow")
        clock.currentInstant = ZonedDateTime.of(2026, 9, 14, 9, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Hot tub", "Clean")
        clock.advance(Duration.ofHours(1))
        logExploreEntry(ledger, clock, zone, "Furnace", "Change filter")
        clock.advance(Duration.ofHours(1))
        logExploreEntry(ledger, clock, zone, "Lawn", "Mow")
        runSuspend { ledger.renameTag(TagKind.SUBJECT, id(TagKind.SUBJECT, "Furnace"), "Heating unit") }
        clock.currentInstant = now
    }

    private fun id(kind: TagKind, name: String) = tagIdOf(ledger, kind, name)

    private fun viewModel(compute: CoroutineDispatcher = dispatcher, start: Boolean = true): ExploreViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExploreViewModel(
                repository = tags,
                lookup = LookupService(tags, extractor),
                transcriber = transcriber,
                clock = clock,
                zoneProvider = { zone },
                firstDayOfWeekProvider = { DayOfWeek.MONDAY },
                computeDispatcher = compute,
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[ExploreViewModel::class.java]
        if (start) {
            vm.onStart()
            scheduler.runCurrent()
        }
        return vm
    }

    private val ExploreViewModel.s get() = state.value

    private fun ExploreViewModel.settle(action: ExploreViewModel.() -> Unit) {
        action()
        scheduler.runCurrent()
    }

    private fun ExploreViewModel.listen(vararg events: SpeechEvent) {
        transcriber.willEmit(*events)
        startListening()
        scheduler.runCurrent()
    }

    private fun final(text: String) = SpeechEvent.FinalTranscript(text, null, emptyList())

    private fun allTime(subjectId: String?, actionId: String?) =
        ExploreFilter(DateRangeSelection.Preset(DateRangePreset.ALL_TIME), subjectId, actionId, null)

    // ---- Loading ---------------------------------------------------------------------------

    @Test
    fun `initial load gives the default filter and its summary`() {
        seed()
        val vm = viewModel()

        val s = vm.s
        assertEquals(ExploreFilter(), s.filter)
        val summary = assertNotNull(s.summary)
        assertEquals(4, summary.totalEntriesEver)
        assertEquals(3, summary.entriesInPeriod)
        assertFalse(s.isLoading)
        assertEquals(ExploreView.ACTIVITIES, s.view)
        val scope = assertIs<ExploreAnswer.Scope>(s.answer)
        assertEquals(ScopeKind.MANY, scope.scopeKind)
        assertEquals(3, scope.entriesInPeriod)
        assertEquals(zone, s.zone)
        assertEquals(now, s.now)
        assertEquals(now.atZone(zone).toLocalDate(), s.today)
        assertFalse(s.canGoBack)
        assertNull(s.message)
    }

    @Test
    fun `before onStart nothing is loaded`() {
        seed()
        val vm = viewModel(start = false)
        scheduler.runCurrent()

        assertNull(vm.s.summary)
        assertTrue(vm.s.isLoading)
        assertEquals(0, tags.exploreLoads)
    }

    @Test
    fun `onStart reloads and picks up an entry added after the first load`() {
        seed()
        val vm = viewModel()
        assertEquals(3, vm.s.summary?.entriesInPeriod)

        clock.advance(Duration.ofMinutes(5))
        logExploreEntry(ledger, clock, zone, "Hot tub", "Clean")
        vm.settle { onStart() }

        assertEquals(4, vm.s.summary?.entriesInPeriod)
        assertEquals(2, tags.exploreLoads)
    }

    @Test
    fun `a load failure keeps the previous data and shows a plain message`() {
        seed()
        val vm = viewModel()
        val before = vm.s.summary

        tags.failLoads = true
        vm.settle { onStart() }

        assertEquals(UserMessage(R.string.history_not_loaded), vm.s.message)
        assertEquals(before, vm.s.summary)
        assertFalse(vm.s.isLoading)

        tags.failLoads = false
        vm.settle { onStart() }
        assertNull(vm.s.message)
    }

    // ---- Suggestions -----------------------------------------------------------------------

    @Test
    fun `suggestions match names and aliases and end with search and ask`() {
        seed()
        val vm = viewModel()

        vm.onInputChange("  law ")
        assertEquals(
            listOf(
                ExploreSuggestion.Subject(id(TagKind.SUBJECT, "Lawn"), "Lawn", null),
                ExploreSuggestion.SearchWords("law"),
                ExploreSuggestion.Ask("law"),
            ),
            vm.s.suggestions,
        )

        vm.onInputChange("FURN")
        assertEquals(
            ExploreSuggestion.Subject(id(TagKind.SUBJECT, "Heating unit"), "Heating unit", "Furnace"),
            vm.s.suggestions.first(),
        )
        assertEquals("FURN", vm.s.input)

        vm.onInputChange("mo")
        assertEquals(ExploreSuggestion.Action(id(TagKind.ACTION, "Mow"), "Mow", null), vm.s.suggestions.first())
    }

    @Test
    fun `prefix matches come before substring matches, subjects before actions, then by name`() {
        seedTags(ledger, clock, zone, "Stove", "Scrub")
        seedTags(ledger, clock, zone, "Oven", "Overhaul")
        val vm = viewModel()

        vm.onInputChange("ov")

        assertEquals(
            listOf(
                ExploreSuggestion.Subject(id(TagKind.SUBJECT, "Oven"), "Oven", null),
                ExploreSuggestion.Action(id(TagKind.ACTION, "Overhaul"), "Overhaul", null),
                ExploreSuggestion.Subject(id(TagKind.SUBJECT, "Stove"), "Stove", null),
                ExploreSuggestion.SearchWords("ov"),
                ExploreSuggestion.Ask("ov"),
            ),
            vm.s.suggestions,
        )
    }

    @Test
    fun `at most five tag suggestions and blank input gives none`() {
        for (i in 1..4) seedTags(ledger, clock, zone, "Item $i", "Item act $i")
        val vm = viewModel()

        vm.onInputChange("item")

        val list = vm.s.suggestions
        assertEquals(7, list.size)
        assertEquals(5, list.count { it is ExploreSuggestion.Subject || it is ExploreSuggestion.Action })
        assertEquals(4, list.count { it is ExploreSuggestion.Subject })
        assertEquals(ExploreSuggestion.SearchWords("item"), list[5])
        assertEquals(ExploreSuggestion.Ask("item"), list[6])

        vm.onInputChange("   ")
        assertTrue(vm.s.suggestions.isEmpty())
    }

    @Test
    fun `choosing each suggestion kind`() {
        seed()
        val vm = viewModel()
        val lawn = id(TagKind.SUBJECT, "Lawn")
        val mow = id(TagKind.ACTION, "Mow")

        vm.onInputChange("law")
        vm.settle { chooseSuggestion(s.suggestions.first()) }
        assertEquals(lawn, vm.s.filter.subjectId)
        assertEquals("Lawn", vm.s.subjectChipName)
        assertEquals("", vm.s.input)
        assertTrue(vm.s.suggestions.isEmpty())
        assertEquals(ScopeKind.ONE_SUBJECT, vm.s.summary?.scopeKind)

        vm.onInputChange("mo")
        vm.settle { chooseSuggestion(s.suggestions.first()) }
        assertEquals(mow, vm.s.filter.actionId)
        assertEquals("Mow", vm.s.actionChipName)
        assertEquals("", vm.s.input)

        vm.onInputChange("tub")
        vm.settle { chooseSuggestion(ExploreSuggestion.SearchWords("tub")) }
        assertEquals("tub", vm.s.filter.words)
        assertEquals("", vm.s.input)
        assertEquals(0, extractor.received.size)

        extractor.answers("lawn", "mow")
        vm.settle { chooseSuggestion(ExploreSuggestion.Ask(question)) }
        assertEquals(listOf(question), extractor.received)
        assertEquals(allTime(lawn, mow), vm.s.filter)
    }

    // ---- Submit ----------------------------------------------------------------------------

    @Test
    fun `submit asks a question and turns anything else into a word search`() {
        seed()
        extractor.fails(InterpreterFailureKind.OTHER)
        val vm = viewModel()

        vm.onInputChange("   ")
        vm.settle { submit() }
        assertEquals(ExploreFilter(), vm.s.filter)
        assertEquals(0, extractor.received.size)

        vm.onInputChange("  hot tub  ")
        vm.settle { submit() }
        assertEquals("hot tub", vm.s.filter.words)
        assertEquals("", vm.s.input)
        assertEquals(0, extractor.received.size)
        assertEquals(1, vm.s.summary?.entriesInPeriod)

        vm.onInputChange("  $question ")
        vm.settle { submit() }
        assertEquals(listOf(question), extractor.received)
        assertEquals(question, vm.s.askedQuestion)
    }

    // ---- Questions -------------------------------------------------------------------------

    @Test
    fun `an answered question sets chips, all time, a last-time fact, entries view and a back step`() {
        seed()
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.settle { ask(question) }

        val s = vm.s
        val lawn = id(TagKind.SUBJECT, "Lawn")
        val mow = id(TagKind.ACTION, "Mow")
        assertEquals(allTime(lawn, mow), s.filter)
        assertEquals(ExploreAnswer.LastTime("Lawn", "Mow", newestMowAt, closestMatch = null), s.answer)
        assertEquals(ExploreView.ENTRIES, s.view)
        assertEquals(question, s.askedQuestion)
        assertTrue(s.canGoBack)
        assertFalse(s.isAsking)
        assertEquals("Lawn", s.subjectChipName)
        assertEquals("Mow", s.actionChipName)
        val summary = assertNotNull(s.summary)
        assertEquals(2, summary.entriesInPeriod)
        assertEquals(ScopeKind.ONE_ACTIVITY, summary.scopeKind)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(ExploreFilter(), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertNull(vm.s.askedQuestion)
        assertFalse(vm.s.canGoBack)
    }

    @Test
    fun `closest match notes`() {
        seed()
        val vm = viewModel()

        extractor.answers("grass", "mow")
        vm.settle { ask("When did I last mow the grass?") }
        val near = assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        assertEquals(ClosestMatch.NOT_EXACT, near.closestMatch)
        assertEquals(id(TagKind.SUBJECT, "Lawn"), vm.s.filter.subjectId)

        extractor.answers("hot tub", "mow")
        vm.settle { ask("When did I last mow the hot tub?") }
        val partial = assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        assertEquals(ClosestMatch.PARTIAL, partial.closestMatch)
        assertEquals(allTime(id(TagKind.SUBJECT, "Hot tub"), id(TagKind.ACTION, "Mow")), vm.s.filter)

        extractor.answers(null, "clean")
        vm.settle { ask("When did I last clean?") }
        val actionOnly = assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        // Same rule as the Ask screen's note: any tier other than BOTH is partial.
        assertEquals(ClosestMatch.PARTIAL, actionOnly.closestMatch)
        assertEquals(allTime(null, id(TagKind.ACTION, "Clean")), vm.s.filter)
        assertEquals("Hot tub", actionOnly.subjectName)
        assertEquals("Clean", actionOnly.actionName)
    }

    @Test
    fun `each failure outcome leaves the filters unchanged`() {
        seed()
        val vm = viewModel()
        val hotTub = id(TagKind.SUBJECT, "Hot tub")
        vm.settle { setSubject(hotTub) }
        vm.settle { setView(ExploreView.PATTERNS) }
        val filter = vm.s.filter

        val cases = listOf<Pair<() -> Unit, ExploreAnswer>>(
            { extractor.answers("garage", "paint") } to ExploreAnswer.NotEnoughHistory,
            { extractor.fails(InterpreterFailureKind.UNAVAILABLE) } to ExploreAnswer.AiUnavailable,
            { extractor.fails(InterpreterFailureKind.RETRYABLE) } to ExploreAnswer.TryAgainLater,
            { extractor.fails(InterpreterFailureKind.MALFORMED) } to ExploreAnswer.TryAgainLater,
            { extractor.fails(InterpreterFailureKind.OTHER) } to ExploreAnswer.TryAgainLater,
            { extractor.throwing = true } to ExploreAnswer.TryAgainLater,
        )
        for ((script, expected) in cases) {
            script()
            vm.settle { ask("When did I last paint the garage?") }
            assertEquals(expected, vm.s.answer)
            assertEquals(filter, vm.s.filter)
            assertEquals(ExploreView.PATTERNS, vm.s.view)
            assertFalse(vm.s.canGoBack)
            assertFalse(vm.s.isAsking)
        }
        extractor.throwing = false

        vm.settle { ask("I mowed the lawn") }
        assertEquals(ExploreAnswer.NotAQuestion, vm.s.answer)
        assertEquals(filter, vm.s.filter)
    }

    @Test
    fun `a late answer after clear all is dropped`() {
        seed()
        extractor.answers("lawn", "mow")
        extractor.holdAnswers()
        val vm = viewModel()
        vm.settle { ask(question) }
        assertTrue(vm.s.isAsking)
        assertEquals(question, vm.s.askedQuestion)

        vm.settle { clearAll() }
        extractor.release()
        scheduler.runCurrent()

        assertEquals(ExploreFilter(), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertNull(vm.s.askedQuestion)
        assertFalse(vm.s.isAsking)
        assertFalse(vm.s.canGoBack)
    }

    @Test
    fun `a late answer after a filter change is dropped`() {
        seed()
        extractor.answers("lawn", "mow")
        extractor.holdAnswers()
        val vm = viewModel()
        vm.settle { ask(question) }

        vm.settle { setWords("tub") }
        extractor.release()
        scheduler.runCurrent()

        assertEquals(ExploreFilter(words = "tub"), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertFalse(vm.s.isAsking)
    }

    @Test
    fun `a second question while one is pending is ignored`() {
        seed()
        extractor.answers("lawn", "mow")
        extractor.holdAnswers()
        val vm = viewModel()
        vm.settle { ask(question) }

        vm.settle { ask("When did I clean the hot tub?") }
        extractor.release()
        scheduler.runCurrent()

        assertEquals(listOf(question), extractor.received)
        assertEquals(question, vm.s.askedQuestion)
    }

    @Test
    fun `a manual change after a question switches the answer to scope`() {
        seed()
        extractor.answers("lawn", "mow")
        val vm = viewModel()
        vm.settle { ask(question) }
        assertIs<ExploreAnswer.LastTime>(vm.s.answer)

        vm.settle { setRange(DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS)) }

        val scope = assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertEquals(ScopeKind.ONE_ACTIVITY, scope.scopeKind)
        assertEquals(1, scope.entriesInPeriod)
        assertEquals("Lawn", scope.subjectName)
        assertEquals("Mow", scope.actionName)
        assertEquals(DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS), scope.range)
        assertNull(vm.s.askedQuestion)
        assertTrue(vm.s.canGoBack, "a manual chip change does not drop earlier back steps")
    }

    // ---- Questions with a kind and date words (DH4.3) --------------------------------------

    private val august = YearMonth.of(2026, 8)
    private val augustRange = DateRangeSelection.Custom(august.atDay(1), august.atEndOfMonth(), RangeLabel.Month(august))
    private val allTimeRange = DateRangeSelection.Preset(DateRangePreset.ALL_TIME)

    /** One more Lawn/Mow entry on Aug 21, so Lawn · Mow has three entries over all time. */
    private fun logThirdMow() {
        clock.currentInstant = ZonedDateTime.of(2026, 8, 21, 10, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Lawn", "Mow")
        clock.currentInstant = now
    }

    @Test
    fun `a count question with date words sets the month, the tags and a counted answer`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August", kind = QuestionKind.LIST)
        val vm = viewModel()
        val text = "How many times did I mow the lawn in August?"

        vm.settle { ask(text) }

        val s = vm.s
        val lawn = id(TagKind.SUBJECT, "Lawn")
        val mow = id(TagKind.ACTION, "Mow")
        assertEquals(ExploreFilter(augustRange, lawn, mow, null), s.filter)
        assertEquals(ExploreView.ENTRIES, s.view)
        assertEquals(text, s.askedQuestion)
        assertTrue(s.canGoBack)
        val summary = assertNotNull(s.summary)
        assertEquals(1, summary.entriesInPeriod)
        // The count and last time are the calculator's, for the new filter.
        assertEquals(
            ExploreAnswer.Count("Lawn", "Mow", summary.entriesInPeriod, augustRange, summary.lastTime, closestMatch = null),
            s.answer,
        )
        assertEquals(newestMowAt, summary.lastTime)
        assertFalse(s.isLoading)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(ExploreFilter(), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
    }

    @Test
    fun `a last-time question with date words is answered with a count`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in May", kind = QuestionKind.LAST_TIME)
        val vm = viewModel()

        vm.settle { ask("Did I mow the lawn in May?") }

        val may = YearMonth.of(2026, 5)
        val mayRange = DateRangeSelection.Custom(may.atDay(1), may.atEndOfMonth(), RangeLabel.Month(may))
        assertEquals(mayRange, vm.s.filter.range)
        val count = assertIs<ExploreAnswer.Count>(vm.s.answer)
        assertEquals(0, count.count)
        assertEquals(mayRange, count.range)
        assertEquals(newestMowAt, count.lastTime)
    }

    @Test
    fun `a last-time question without date words keeps the last-time answer and all time`() {
        seed()
        extractor.answers("lawn", "mow", kind = QuestionKind.LAST_TIME)
        val vm = viewModel()

        vm.settle { ask("Did I mow the lawn?") }

        assertEquals(allTime(id(TagKind.SUBJECT, "Lawn"), id(TagKind.ACTION, "Mow")), vm.s.filter)
        assertEquals(ExploreAnswer.LastTime("Lawn", "Mow", newestMowAt, closestMatch = null), vm.s.answer)
    }

    @Test
    fun `a how-often question with three entries shows the typical gap`() {
        seed()
        logThirdMow()
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.settle { ask("How often do I mow the lawn?") }

        val summary = assertNotNull(vm.s.summary)
        val gap = assertNotNull(summary.typicalGap)
        assertEquals(allTime(id(TagKind.SUBJECT, "Lawn"), id(TagKind.ACTION, "Mow")), vm.s.filter)
        assertEquals(
            ExploreAnswer.HowOften("Lawn", "Mow", gap, entriesAllTime = 3, lastTime = newestMowAt, closestMatch = null),
            vm.s.answer,
        )
    }

    @Test
    fun `a how-often question with date words and nothing logged in them still gives the all-time gap`() {
        seed()
        logThirdMow()
        extractor.answers("lawn", "mow", dateWindow = "in July")
        val vm = viewModel()

        vm.settle { ask("How often did I mow the lawn in July?") }

        // The chips keep the question's range (July, with no mowing in it) ...
        val july = DateRangeSelection.Custom(
            YearMonth.of(2026, 7).atDay(1),
            YearMonth.of(2026, 7).atEndOfMonth(),
            RangeLabel.Month(YearMonth.of(2026, 7)),
        )
        assertEquals(ExploreFilter(july, id(TagKind.SUBJECT, "Lawn"), id(TagKind.ACTION, "Mow"), null), vm.s.filter)
        assertEquals(0, assertNotNull(vm.s.summary).entriesInPeriod)
        // ... while the gap, the all-time count and the last time are all-time measures.
        val howOften = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertNotNull(howOften.typicalGap)
        assertEquals(3, howOften.entriesAllTime)
        assertEquals(1, howOften.activityCount)
        assertEquals(newestMowAt, howOften.lastTime)
        assertFalse(howOften.datesNotUnderstood)
    }

    @Test
    fun `a how-often question with date words that hold entries gives the same gap as without them`() {
        seed()
        logThirdMow()
        val vm = viewModel()
        extractor.answers("lawn", "mow")
        vm.settle { ask("How often do I mow the lawn?") }
        val withoutWindow = assertIs<ExploreAnswer.HowOften>(vm.s.answer)

        extractor.answers("lawn", "mow", dateWindow = "in August")
        vm.settle { ask("How often did I mow the lawn in August?") }

        assertEquals(YearMonth.of(2026, 8).atDay(1), (vm.s.filter.range as DateRangeSelection.Custom).start)
        assertEquals(2, assertNotNull(vm.s.summary).entriesInPeriod)
        val withWindow = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertNotNull(withoutWindow.typicalGap)
        assertEquals(withoutWindow, withWindow)
    }

    @Test
    fun `a how-often question with fewer than three entries has no gap and says how many`() {
        seed()
        extractor.answers("lawn", "mow", kind = QuestionKind.HOW_OFTEN)
        val vm = viewModel()

        vm.settle { ask("Lawn mowing, regularly?") }

        val howOften = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertNull(howOften.typicalGap)
        assertEquals(2, howOften.entriesAllTime)
        assertEquals(newestMowAt, howOften.lastTime)
    }

    @Test
    fun `an action-only how-often question over one activity uses that activity's gap`() {
        seed()
        logThirdMow()
        extractor.answers(null, "mow")
        val vm = viewModel()

        vm.settle { ask("How often do I mow?") }

        assertEquals(allTime(null, id(TagKind.ACTION, "Mow")), vm.s.filter)
        val summary = assertNotNull(vm.s.summary)
        assertEquals(ScopeKind.ONE_ACTION, summary.scopeKind)
        val row = summary.activities.single()
        val howOften = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertEquals(assertNotNull(row.typicalGap), howOften.typicalGap)
        assertEquals(3, howOften.entriesAllTime)
        assertEquals(1, howOften.activityCount)
        assertNull(howOften.subjectName)
        assertEquals("Mow", howOften.actionName)
        assertEquals(ExploreView.ENTRIES, vm.s.view)
    }

    @Test
    fun `an action-only how-often question over one activity with two entries says too few`() {
        seed()
        extractor.answers(null, "mow")
        val vm = viewModel()

        vm.settle { ask("How often do I mow?") }

        val howOften = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertNull(howOften.typicalGap)
        assertEquals(2, howOften.entriesAllTime)
        assertEquals(1, howOften.activityCount)
    }

    @Test
    fun `a how-often question over several activities asks to pick one in the activities view`() {
        seed()
        clock.currentInstant = ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Lawn", "Rake")
        clock.currentInstant = now
        extractor.answers("lawn", null)
        val vm = viewModel()

        vm.settle { ask("How often do I work on the lawn?") }

        assertEquals(allTime(id(TagKind.SUBJECT, "Lawn"), null), vm.s.filter)
        val howOften = assertIs<ExploreAnswer.HowOften>(vm.s.answer)
        assertEquals(2, howOften.activityCount)
        assertNull(howOften.typicalGap)
        assertEquals("Lawn", howOften.subjectName)
        assertNull(howOften.actionName)
        assertEquals(newestMowAt, howOften.lastTime)
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)
    }

    @Test
    fun `date words nobody understands fall back to all time with a note`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "back in the day")
        val vm = viewModel()

        vm.settle { ask("How many times did I mow the lawn back in the day?") }

        assertEquals(allTimeRange, vm.s.filter.range)
        val count = assertIs<ExploreAnswer.Count>(vm.s.answer)
        assertTrue(count.datesNotUnderstood)
        assertEquals(2, count.count)
        assertEquals(allTimeRange, count.range)
    }

    @Test
    fun `date words that are not in the question are ignored`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August")
        val vm = viewModel()

        vm.settle { ask("How many times did I mow the lawn?") }

        assertEquals(allTimeRange, vm.s.filter.range)
        val count = assertIs<ExploreAnswer.Count>(vm.s.answer)
        assertFalse(count.datesNotUnderstood)
        assertEquals(2, count.count)
    }

    @Test
    fun `future date words are not enough history and leave the filters alone`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "next week")
        val vm = viewModel()
        val before = vm.s.filter

        vm.settle { ask("How many times will I mow the lawn next week?") }

        assertEquals(ExploreAnswer.NotEnoughHistory, vm.s.answer)
        assertEquals(before, vm.s.filter)
        assertFalse(vm.s.canGoBack)
    }

    @Test
    fun `a question about a stretch of time sets only the range and back restores`() {
        seed()
        extractor.answers(null, null, dateWindow = "in August")
        val vm = viewModel()
        vm.settle { setSubject(id(TagKind.SUBJECT, "Lawn")) }
        val before = vm.s.filter

        vm.settle { ask("What did I do in August?") }

        assertEquals(ExploreFilter(range = augustRange), vm.s.filter)
        assertEquals(ExploreView.ENTRIES, vm.s.view)
        val scope = assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertEquals(1, scope.entriesInPeriod)
        assertFalse(scope.datesNotUnderstood)
        assertTrue(vm.s.canGoBack)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(before, vm.s.filter)
    }

    @Test
    fun `a list question with unclear dates browses all time with the note on the scope line`() {
        seed()
        extractor.answers(null, null, dateWindow = "way back")
        val vm = viewModel()

        vm.settle { ask("What did I do way back?") }

        assertEquals(ExploreFilter(range = allTimeRange), vm.s.filter)
        val scope = assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertTrue(scope.datesNotUnderstood)
        assertEquals(4, scope.entriesInPeriod)

        // The note belongs to the question: a manual change drops it.
        vm.settle { setRange(DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS)) }
        assertFalse(assertIs<ExploreAnswer.Scope>(vm.s.answer).datesNotUnderstood)
    }

    @Test
    fun `a manual chip change after a count answer switches to scope`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August")
        val vm = viewModel()
        vm.settle { ask("How many times did I mow the lawn in August?") }
        assertIs<ExploreAnswer.Count>(vm.s.answer)

        vm.settle { setRange(DateRangeSelection.Preset(DateRangePreset.LAST_30_DAYS)) }

        val scope = assertIs<ExploreAnswer.Scope>(vm.s.answer)
        assertEquals(1, scope.entriesInPeriod)
        assertNull(vm.s.askedQuestion)
    }

    @Test
    fun `a filter change before the count is computed drops the pending answer`() {
        seed()
        extractor.answers("lawn", "mow", dateWindow = "in August")
        val compute = ManualDispatcher()
        val vm = viewModel(compute = compute)
        compute.runAt(0)
        scheduler.runCurrent()

        vm.settle { ask("How many times did I mow the lawn in August?") }
        assertNull(vm.s.answer, "no count before the summary of the new filter arrives")
        assertTrue(vm.s.isLoading)

        vm.settle { setWords("tub") }
        while (compute.queue.isNotEmpty()) {
            compute.runAt(0)
            scheduler.runCurrent()
        }

        assertEquals(ExploreFilter(range = augustRange, subjectId = id(TagKind.SUBJECT, "Lawn"), actionId = id(TagKind.ACTION, "Mow"), words = "tub"), vm.s.filter)
        assertIs<ExploreAnswer.Scope>(vm.s.answer)
    }

    // ---- Views and sorts -------------------------------------------------------------------

    @Test
    fun `view follows the filter until chosen by hand, which sticks until the next filter change`() {
        seed()
        val vm = viewModel()
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)

        vm.settle { setView(ExploreView.PATTERNS) }
        vm.settle { setActivitySort(ActivitySort.NAME) }
        vm.settle { setChartAsList(true) }
        assertEquals(ExploreView.PATTERNS, vm.s.view)

        vm.settle { setSubject(id(TagKind.SUBJECT, "Lawn")) }
        assertEquals(ExploreView.ENTRIES, vm.s.view)

        vm.settle { setView(ExploreView.ACTIVITIES) }
        vm.settle { clearFilter(ExploreFilterKind.SUBJECT) }
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)
        assertNull(vm.s.subjectChipName)

        vm.settle { setWords("mow") }
        assertEquals(ExploreView.ENTRIES, vm.s.view)
        vm.settle { clearFilter(ExploreFilterKind.WORDS) }
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)

        vm.settle { setAction(id(TagKind.ACTION, "Mow")) }
        assertEquals(ExploreView.ENTRIES, vm.s.view)
        vm.settle { clearFilter(ExploreFilterKind.ACTION) }
        vm.settle { setRange(DateRangeSelection.Preset(DateRangePreset.ALL_TIME)) }
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)
        vm.settle { clearFilter(ExploreFilterKind.RANGE) }
        assertEquals(ExploreFilter(), vm.s.filter)
    }

    @Test
    fun `sorts and group by subject recompute`() {
        seed()
        val vm = viewModel()

        vm.settle { setActivitySort(ActivitySort.NAME) }
        assertEquals(ActivitySort.NAME, vm.s.activitySort)
        val names = assertNotNull(vm.s.summary).activities.map { it.subjectName }
        assertEquals(listOf("Heating unit", "Hot tub", "Lawn"), names)

        vm.settle { setEntrySort(EntrySort.OLDEST) }
        assertEquals(EntrySort.OLDEST, vm.s.entrySort)
        val times = assertNotNull(vm.s.summary).entries.map { it.occurredAt }
        assertEquals(times.sorted(), times)

        vm.settle { setEntrySort(EntrySort.NEWEST) }
        val newest = assertNotNull(vm.s.summary).entries.map { it.occurredAt }
        assertEquals(times.sortedDescending(), newest)

        assertTrue(assertNotNull(vm.s.summary).subjectGroups.isEmpty())
        vm.settle { setGroupBySubject(true) }
        assertTrue(vm.s.groupBySubject)
        assertEquals(3, assertNotNull(vm.s.summary).subjectGroups.size)

        vm.settle { setChartAsList(true) }
        assertTrue(vm.s.chartAsList)
    }

    // ---- Drill-down ------------------------------------------------------------------------

    @Test
    fun `drill down into a group and an activity, then back restores in order`() {
        seed()
        val vm = viewModel()
        vm.settle { setGroupBySubject(true) }
        vm.settle { setRange(DateRangeSelection.Preset(DateRangePreset.LAST_12_MONTHS)) }
        val f0 = vm.s.filter
        assertFalse(vm.s.canGoBack)

        val group = assertNotNull(vm.s.summary).subjectGroups.first { it.subjectName == "Lawn" }
        vm.settle { openSubjectGroup(group) }
        val lawn = id(TagKind.SUBJECT, "Lawn")
        assertEquals(f0.copy(subjectId = lawn), vm.s.filter)
        assertTrue(vm.s.canGoBack)
        assertEquals(ExploreView.ENTRIES, vm.s.view)

        val row = assertNotNull(vm.s.summary).activities.single()
        vm.settle { openActivity(row) }
        val f2 = vm.s.filter
        assertEquals(f0.copy(subjectId = lawn, actionId = id(TagKind.ACTION, "Mow")), f2)
        assertEquals(DateRangeSelection.Preset(DateRangePreset.LAST_12_MONTHS), f2.range)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(f0.copy(subjectId = lawn), vm.s.filter)
        assertTrue(vm.s.canGoBack)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(f0, vm.s.filter)
        assertEquals(ExploreView.ACTIVITIES, vm.s.view)
        assertFalse(vm.s.canGoBack)

        assertFalse(vm.back())
        assertEquals(f0, vm.s.filter)
    }

    @Test
    fun `drilling into an untagged row or group searches its name and clears the words otherwise`() {
        seed()
        val vm = viewModel()
        val untaggedRow = ActivityRow(
            activityId = "legacy", activityName = "Old thing", subjectId = null, subjectName = null,
            actionId = null, actionName = null, countInPeriod = 1, lastTime = now, typicalGap = null, entriesAllTime = 1,
        )

        vm.settle { openActivity(untaggedRow) }
        assertEquals(ExploreFilter(words = "Old thing"), vm.s.filter)

        val tagged = untaggedRow.copy(subjectId = id(TagKind.SUBJECT, "Lawn"), actionId = id(TagKind.ACTION, "Mow"))
        vm.settle { openActivity(tagged) }
        assertEquals(ExploreFilter(subjectId = tagged.subjectId, actionId = tagged.actionId), vm.s.filter)

        vm.settle { openSubjectGroup(SubjectGroup(null, "Old group", 1, 1, now)) }
        assertEquals(ExploreFilter(words = "Old group"), vm.s.filter)

        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(ExploreFilter(subjectId = tagged.subjectId, actionId = tagged.actionId), vm.s.filter)
        assertTrue(vm.back())
        assertTrue(vm.back())
        scheduler.runCurrent()
        assertEquals(ExploreFilter(), vm.s.filter)
        assertFalse(vm.back())
    }

    // ---- Clear -----------------------------------------------------------------------------

    @Test
    fun `clear all resets filters, answer, question, box and back stack`() {
        seed()
        extractor.answers("lawn", "mow")
        val vm = viewModel()
        vm.settle { setActivitySort(ActivitySort.NAME) }
        vm.settle { ask(question) }
        vm.settle { setWords("mow") }
        vm.onInputChange("typing")
        vm.onMicrophonePermissionDenied()

        vm.settle { clearAll() }

        val s = vm.s
        assertEquals(ExploreFilter(), s.filter)
        assertTrue(s.filter.isDefault)
        assertIs<ExploreAnswer.Scope>(s.answer)
        assertNull(s.askedQuestion)
        assertFalse(s.canGoBack)
        assertFalse(vm.back())
        assertEquals("", s.input)
        assertTrue(s.suggestions.isEmpty())
        assertNull(s.message)
        assertNull(s.subjectChipName)
        assertEquals(ExploreView.ACTIVITIES, s.view)
        assertEquals(3, s.summary?.entriesInPeriod)
        assertEquals(ActivitySort.NAME, s.activitySort, "sorts are not filters and stay")
    }

    // ---- Stale computation -----------------------------------------------------------------

    @Test
    fun `a stale computation cannot overwrite a newer filter's result`() {
        seed()
        val compute = ManualDispatcher()
        val vm = viewModel(compute = compute)
        assertEquals(1, compute.queue.size)
        val lawn = id(TagKind.SUBJECT, "Lawn")

        vm.settle { setSubject(lawn) }
        assertEquals(2, compute.queue.size)
        assertTrue(vm.s.isLoading)

        compute.runAt(1)
        scheduler.runCurrent()
        assertEquals(ScopeKind.ONE_SUBJECT, vm.s.summary?.scopeKind)
        assertFalse(vm.s.isLoading)

        compute.runAt(0)
        scheduler.runCurrent()
        assertEquals(ScopeKind.ONE_SUBJECT, vm.s.summary?.scopeKind)
        assertEquals(1, vm.s.summary?.entriesInPeriod)
        assertEquals(lawn, vm.s.filter.subjectId)
        assertEquals(ScopeKind.ONE_SUBJECT, assertIs<ExploreAnswer.Scope>(vm.s.answer).scopeKind)
    }

    // ---- Voice -----------------------------------------------------------------------------

    @Test
    fun `a spoken question is asked and spoken words that are not a question become a word search`() {
        seed()
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.listen(SpeechEvent.PartialTranscript("when did"), final(question))
        scheduler.runCurrent()
        assertEquals(listOf(question), extractor.received)
        assertIs<ExploreAnswer.LastTime>(vm.s.answer)
        assertFalse(vm.s.isListening)
        assertEquals("", vm.s.partialTranscript)

        vm.settle { clearAll() }
        vm.listen(final("hot tub"))
        scheduler.runCurrent()
        assertEquals("hot tub", vm.s.filter.words)
        assertEquals("", vm.s.input)
        assertEquals(1, extractor.received.size)

        vm.listen(final("   "))
        assertEquals("hot tub", vm.s.filter.words)
    }

    @Test
    fun `partial transcript, stop, failures and permission messages behave like Ask`() {
        val vm = viewModel()

        vm.listen(SpeechEvent.PartialTranscript("when did I"))
        assertTrue(vm.s.isListening)
        assertEquals("when did I", vm.s.partialTranscript)
        vm.settle { stopListening() }
        assertFalse(vm.s.isListening)
        assertFalse(transcriber.isOpen)

        val expected = mapOf(
            SpeechFailure.NOTHING_HEARD to R.string.explore_voice_nothing_heard,
            SpeechFailure.ENGINE_ERROR to R.string.explore_voice_nothing_heard,
            SpeechFailure.PERMISSION_MISSING to R.string.explore_mic_permission_denied,
            SpeechFailure.NO_ON_DEVICE_ENGINE to R.string.explore_voice_unavailable,
            SpeechFailure.RECOGNIZER_BUSY to R.string.log_voice_busy,
        )
        for ((failure, text) in expected) {
            vm.listen(SpeechEvent.Failed(failure))
            assertEquals(UserMessage(text), vm.s.message, "failure $failure")
            assertFalse(vm.s.isListening)
        }
        vm.dismissMessage()
        vm.listen(SpeechEvent.Failed(SpeechFailure.CANCELLED))
        assertNull(vm.s.message)

        vm.onMicrophonePermissionDenied()
        assertEquals(UserMessage(R.string.explore_mic_permission_denied), vm.s.message)
        vm.dismissMessage()
        assertNull(vm.s.message)
        vm.onMicrophonePermissionBlocked()
        assertEquals(UserMessage(R.string.explore_mic_permission_blocked), vm.s.message)

        vm.listen(SpeechEvent.PartialTranscript("x"))
        vm.settle { stopListening() }
        transcriber.emitNow(final(question))
        scheduler.runCurrent()
        assertEquals("", vm.s.input)
        assertEquals(0, extractor.received.size)
    }

    @Test
    fun `asking stops listening and clearing the view model closes the microphone`() {
        seed()
        extractor.fails(InterpreterFailureKind.OTHER)
        val vm = viewModel()
        vm.listen(SpeechEvent.PartialTranscript("x"))

        vm.settle { ask(question) }
        assertFalse(vm.s.isListening)
        assertFalse(transcriber.isOpen)

        vm.listen(SpeechEvent.PartialTranscript("y"))
        assertTrue(transcriber.isOpen)
        store.clear()
        scheduler.runCurrent()
        assertFalse(transcriber.isOpen)
    }

    // ---- Read only -------------------------------------------------------------------------

    @Test
    fun `nothing the screen does ever writes to the ledger`() {
        seed()
        val historyBefore = runSuspend { ledger.loadHistory() }
        val entriesBefore = runSuspend { ledger.loadExploreEntries() }
        val catalogBefore = runSuspend { ledger.loadTagCatalog() }
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.onInputChange("law")
        vm.settle { chooseSuggestion(s.suggestions.first()) }
        vm.onInputChange("I did something")
        vm.settle { submit() }
        vm.settle { ask(question) }
        vm.settle { setGroupBySubject(true) }
        vm.settle { openActivity(assertNotNull(s.summary).activities.first()) }
        vm.back()
        vm.listen(final("What did I do last?"))
        vm.settle { clearAll() }
        vm.settle { onStart() }

        assertTrue(tags.writeAttempts.isEmpty(), "writes attempted: ${tags.writeAttempts}")
        assertEquals(historyBefore, runSuspend { ledger.loadHistory() })
        assertEquals(entriesBefore, runSuspend { ledger.loadExploreEntries() })
        assertEquals(catalogBefore, runSuspend { ledger.loadTagCatalog() })
    }
}
