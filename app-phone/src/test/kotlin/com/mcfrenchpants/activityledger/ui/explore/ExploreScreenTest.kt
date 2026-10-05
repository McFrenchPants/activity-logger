package com.mcfrenchpants.activityledger.ui.explore

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.stats.ActivityRow
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.BucketSize
import com.mcfrenchpants.activityledger.core.domain.stats.ChartBucket
import com.mcfrenchpants.activityledger.core.domain.stats.ChartSeries
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreSummary
import com.mcfrenchpants.activityledger.core.domain.stats.PartOfDay
import com.mcfrenchpants.activityledger.core.domain.stats.Patterns
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.stats.ResolvedRange
import com.mcfrenchpants.activityledger.core.domain.stats.ScopeKind
import com.mcfrenchpants.activityledger.core.domain.stats.SubjectGroup
import com.mcfrenchpants.activityledger.core.domain.stats.TimeMentionedRow
import com.mcfrenchpants.activityledger.core.domain.stats.TypicalGap
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-side (Robolectric) tests of [ExploreContent] on scripted state: every section's copy and
 * every control's callback. A tall screen keeps most of the one list composed; [find] scrolls to
 * anything further down.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
@Suppress("LargeClass")
class ExploreScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val today = LocalDate.of(2026, 9, 15)
    private val last30 = ResolvedRange.of(today.minusDays(29), today)

    private var state by mutableStateOf(ExploreUiState())

    /** Every callback the content made, as (name, argument). */
    private val calls = mutableListOf<Pair<String, Any?>>()

    private val callbacks = ExploreCallbacks(
        onInputChange = { calls += "input" to it; state = state.copy(input = it) },
        onSubmit = { calls += "submit" to null },
        onChooseSuggestion = { calls += "suggestion" to it },
        onMicrophone = { calls += "mic" to null },
        onSetRange = { calls += "range" to it },
        onSetSubject = { calls += "subject" to it },
        onSetAction = { calls += "action" to it },
        onClearFilter = { calls += "clearFilter" to it },
        onClearAll = { calls += "clearAll" to null },
        onSetView = { calls += "view" to it },
        onSetEntrySort = { calls += "entrySort" to it },
        onSetActivitySort = { calls += "activitySort" to it },
        onSetGroupBySubject = { calls += "group" to it },
        onSetChartAsList = { calls += "chartAsList" to it },
        onOpenActivity = { calls += "openActivity" to it },
        onOpenSubjectGroup = { calls += "openGroup" to it },
        onOpenEntry = { calls += "openEntry" to it },
        onDismissMessage = { calls += "dismiss" to null },
        onBack = { calls += "back" to null },
    )

    private fun show(initial: ExploreUiState) {
        state = initial
        composeRule.setContent {
            ActivityLedgerTheme {
                ExploreContent(state = state, callbacks = callbacks, locale = Locale.US)
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertCalls(vararg expected: Pair<String, Any?>) {
        assertEquals(expected.toList(), calls.toList())
    }

    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(EXPLORE_SCREEN_TAG).performScrollToNode(matcher)
        return composeRule.onNode(matcher)
    }

    private fun findText(text: String) = find(hasText(text))

    private fun tile(text: String) = composeRule.onNode(hasAnyAncestor(hasTestTag(EXPLORE_NUMBERS_TAG)) and hasText(text))

    // ---- Fixtures --------------------------------------------------------------------------

    private val emptyPatterns = Patterns(
        byWeekday = DayOfWeek.entries.map { it to 0 },
        byPartOfDay = PartOfDay.entries.associateWith { 0 },
        partOfDayLeftOut = 0,
        timeMentioned = emptyList(),
        timeMentionedLeftOut = 0,
    )

    private fun dayChart(range: ResolvedRange = last30, counts: Map<LocalDate, Int> = emptyMap(), average: Double? = null) =
        ChartSeries(
            BucketSize.DAY,
            (0 until range.dayCount.toInt()).map { range.start.plusDays(it.toLong()).let { d -> ChartBucket(d, counts[d] ?: 0) } },
            average,
        )

    @Suppress("LongParameterList")
    private fun summary(
        scopeKind: ScopeKind = ScopeKind.MANY,
        range: ResolvedRange = last30,
        total: Int = 50,
        inPeriod: Int = 47,
        previous: Int? = null,
        days: Int = 18,
        activities: Int = 14,
        subjects: Int = 5,
        actions: Int = 6,
        lastTime: Instant? = now.minus(Duration.ofDays(2)),
        gap: TypicalGap? = null,
        chart: ChartSeries = dayChart(range),
        activityRows: List<ActivityRow> = emptyList(),
        groups: List<SubjectGroup> = emptyList(),
        patterns: Patterns = emptyPatterns,
        entries: List<ExploreEntry> = emptyList(),
    ) = ExploreSummary(
        scopeKind = scopeKind, range = range, totalEntriesEver = total, entriesInPeriod = inPeriod,
        previousPeriodEntries = previous, daysWithEntry = days, distinctActivities = activities,
        distinctSubjects = subjects, distinctActions = actions, lastTime = lastTime, typicalGap = gap,
        chart = chart, activities = activityRows, subjectGroups = groups, patterns = patterns, entries = entries,
    )

    private fun scope(s: ExploreSummary, filter: ExploreFilter = ExploreFilter(), subject: String? = null, action: String? = null) =
        ExploreAnswer.Scope(s.scopeKind, filter.range, s.range, s.entriesInPeriod, subject, action, filter.words)

    private fun loaded(
        s: ExploreSummary = summary(),
        filter: ExploreFilter = ExploreFilter(),
        answer: ExploreAnswer? = null,
        view: ExploreView = ExploreView.ACTIVITIES,
        subjectChip: String? = null,
        actionChip: String? = null,
    ) = ExploreUiState(
        filter = filter,
        summary = s,
        isLoading = false,
        subjectChipName = subjectChip,
        actionChipName = actionChip,
        answer = answer ?: scope(s, filter, subjectChip, actionChip),
        view = view,
        zone = zone,
        now = now,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    private fun activityRow(subject: String, action: String, count: Int, daysAgo: Long, gap: TypicalGap? = null) = ActivityRow(
        activityId = "act-$subject-$action", activityName = "$action $subject", subjectId = "s-$subject",
        subjectName = subject, actionId = "a-$action", actionName = action, countInPeriod = count,
        lastTime = now.minus(Duration.ofDays(daysAgo)), typicalGap = gap, entriesAllTime = count,
    )

    private fun entry(id: String, at: Instant, subject: String?, action: String?, words: String) = ExploreEntry(
        occurrenceId = "o$id", captureId = "c$id", occurredAt = at, timePrecision = TimePrecision.EXACT,
        durationSeconds = null, activityId = "act$id", activityName = "Old activity $id",
        subjectId = subject?.let { "s-$it" }, subjectName = subject, actionId = action?.let { "a-$it" },
        actionName = action, rawText = words,
    )

    // ---- Search box and suggestions --------------------------------------------------------

    @Test
    fun `the suggestion list shows each kind and choosing one calls back with it`() {
        val subject = ExploreSuggestion.Subject("s1", "Hot tub", null)
        val action = ExploreSuggestion.Action("a1", "Heat", "hot water")
        val words = ExploreSuggestion.SearchWords("hot")
        val ask = ExploreSuggestion.Ask("hot")
        show(loaded().copy(input = "hot", suggestions = listOf(subject, action, words, ask)))

        composeRule.onNode(hasText("Hot tub") and hasText("Subject")).assertIsDisplayed().performClick()
        composeRule.onNode(hasText("Heat") and hasText("Action") and hasText("also called hot water")).performClick()
        composeRule.onNodeWithText("Search your words for “hot”").performClick()
        composeRule.onNodeWithText("Ask: hot").performClick()
        assertEquals(listOf(subject, action, words, ask), calls.filter { it.first == "suggestion" }.map { it.second })
    }

    @Test
    fun `no suggestion list while the box is blank`() {
        show(loaded().copy(input = "", suggestions = listOf(ExploreSuggestion.Ask("x"))))
        composeRule.onNodeWithTag(EXPLORE_SUGGESTIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun `the search key submits and typing reports the text`() {
        show(loaded())
        composeRule.onNodeWithTag(EXPLORE_INPUT_TAG).performTextInput("hot tub")
        composeRule.onNodeWithTag(EXPLORE_INPUT_TAG).performImeAction()
        assertTrue("input" to "hot tub" in calls)
        assertEquals(1, calls.count { it.first == "submit" })
    }

    @Test
    fun `the box shows the asked question and its clear button puts it away`() {
        show(loaded(answer = ExploreAnswer.NotEnoughHistory).copy(askedQuestion = "When did I last mow?"))
        composeRule.onNodeWithTag(EXPLORE_INPUT_TAG).assert(hasText("When did I last mow?"))
        composeRule.onNodeWithTag(EXPLORE_CLEAR_TEXT_TAG).performClick()
        assertCalls("clearAll" to null)
    }

    @Test
    fun `the clear button empties typed text`() {
        show(loaded().copy(input = "hot"))
        composeRule.onNodeWithContentDescription("Clear the search box").performClick()
        assertCalls("input" to "")
    }

    @Test
    fun `the microphone toggles and shows the listening state`() {
        show(loaded())
        composeRule.onNodeWithContentDescription("Ask by voice").assertIsDisplayed().performClick()
        assertCalls("mic" to null)
        composeRule.onNodeWithTag(EXPLORE_LISTENING_TAG).assertDoesNotExist()

        state = state.copy(isListening = true, partialTranscript = "when did I")
        composeRule.onNodeWithTag(EXPLORE_LISTENING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EXPLORE_PARTIAL_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    @Test
    fun `a pending question shows a described progress indicator`() {
        show(loaded().copy(isAsking = true))
        composeRule.onNodeWithContentDescription("Looking through your history").assertIsDisplayed()
    }

    @Test
    fun `a message is shown and can be dismissed`() {
        show(loaded().copy(message = UserMessage(R.string.explore_voice_nothing_heard)))
        composeRule.onNodeWithText("I didn't catch that. Try again, or type your question.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertCalls("dismiss" to null)
    }

    // ---- Filter row ------------------------------------------------------------------------

    private fun dateChipShows(range: DateRangeSelection, label: String) {
        state = loaded(filter = ExploreFilter(range = range))
        composeRule.onNodeWithTag(EXPLORE_DATE_CHIP_TAG)
            .assert(hasText(label))
            .assert(hasContentDescription("Date range, $label, double-tap to change"))
    }

    @Test
    fun `the date chip names each preset, a month, a year and a custom range`() {
        show(loaded())
        dateChipShows(DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS), "Last 7 days")
        dateChipShows(DateRangeSelection.Preset(DateRangePreset.LAST_30_DAYS), "Last 30 days")
        dateChipShows(DateRangeSelection.Preset(DateRangePreset.LAST_12_MONTHS), "Last 12 months")
        dateChipShows(DateRangeSelection.Preset(DateRangePreset.ALL_TIME), "All time")
        val aug = YearMonth.of(2026, 8)
        dateChipShows(DateRangeSelection.Custom(aug.atDay(1), aug.atEndOfMonth(), RangeLabel.Month(aug)), "August 2026")
        dateChipShows(DateRangeSelection.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), RangeLabel.Year(2026)), "2026")
        dateChipShows(DateRangeSelection.Custom(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20)), "Aug 1 – Aug 20, 2026")
    }

    @Test
    fun `the date menu offers the presets and opens the custom range picker`() {
        show(loaded())
        composeRule.onNodeWithTag(EXPLORE_DATE_CHIP_TAG).performClick()
        composeRule.onNodeWithText("Last 7 days").assertIsDisplayed()
        composeRule.onNodeWithText("All time").assertIsDisplayed()
        composeRule.onNodeWithText("Last 12 months").performClick()
        assertCalls("range" to DateRangeSelection.Preset(DateRangePreset.LAST_12_MONTHS))

        composeRule.onNodeWithTag(EXPLORE_DATE_CHIP_TAG).performClick()
        composeRule.onNodeWithText("Custom range…").performClick()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("OK").assertDoesNotExist()
        assertEquals(1, calls.size)
    }

    @Test
    fun `the subject chip opens a searchable picker and choosing sets the subject`() {
        show(
            loaded().copy(
                subjectTags = listOf(
                    KnownTag("s1", TagKind.SUBJECT, "Hot tub", listOf("spa")),
                    KnownTag("s2", TagKind.SUBJECT, "Lawn", emptyList()),
                ),
            ),
        )
        composeRule.onNodeWithTag(EXPLORE_SUBJECT_CHIP_TAG)
            .assert(hasContentDescription("Subject, any, double-tap to change"))
            .performClick()
        composeRule.onNodeWithTag(EXPLORE_PICKER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Choose a subject").assertIsDisplayed()
        composeRule.onNodeWithText("Lawn").assertIsDisplayed()
        composeRule.onNodeWithText("Search subjects").performTextInput("spa")
        composeRule.onNodeWithText("Lawn").assertDoesNotExist()
        composeRule.onNodeWithText("Hot tub").performClick()
        assertCalls("subject" to "s1")
        composeRule.onNodeWithTag(EXPLORE_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun `the action chip opens the action picker`() {
        show(loaded().copy(actionTags = listOf(KnownTag("a1", TagKind.ACTION, "Mow", emptyList()))))
        composeRule.onNodeWithTag(EXPLORE_ACTION_CHIP_TAG).performClick()
        composeRule.onNodeWithText("Choose an action").assertIsDisplayed()
        composeRule.onNodeWithText("Mow").performClick()
        assertCalls("action" to "a1")
    }

    @Test
    fun `set chips show their values and each x clears only its chip`() {
        val filter = ExploreFilter(
            range = DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS),
            subjectId = "s1",
            actionId = "a1",
            words = "front",
        )
        show(loaded(s = summary(scopeKind = ScopeKind.ONE_ACTIVITY), filter = filter, subjectChip = "Lawn", actionChip = "Mow"))
        composeRule.onNodeWithTag(EXPLORE_SUBJECT_CHIP_TAG)
            .assert(hasText("Lawn"))
            .assert(hasContentDescription("Subject, Lawn, double-tap to change"))
        composeRule.onNodeWithTag(EXPLORE_ACTION_CHIP_TAG).assert(hasText("Mow"))
        composeRule.onNodeWithTag(EXPLORE_WORDS_CHIP_TAG).assert(hasText("Words: front"))

        ExploreFilterKind.entries.forEach { composeRule.onNodeWithTag(exploreChipClearTag(it)).performClick() }
        assertEquals<List<Pair<String, Any?>>>(ExploreFilterKind.entries.map { "clearFilter" to it }, calls)
    }

    @Test
    fun `no words chip and no x buttons on the default filter`() {
        show(loaded())
        composeRule.onNodeWithTag(EXPLORE_WORDS_CHIP_TAG).assertDoesNotExist()
        ExploreFilterKind.entries.forEach { composeRule.onNodeWithTag(exploreChipClearTag(it)).assertDoesNotExist() }
    }

    @Test
    fun `Clear shows only when the filter is not default or a question answer is shown`() {
        show(loaded())
        composeRule.onNodeWithTag(EXPLORE_CLEAR_ALL_TAG).assertDoesNotExist()

        state = loaded(filter = ExploreFilter(subjectId = "s1"), subjectChip = "Lawn")
        composeRule.onNodeWithTag(EXPLORE_CLEAR_ALL_TAG).assertIsDisplayed().performClick()
        assertCalls("clearAll" to null)

        state = loaded(answer = ExploreAnswer.NotEnoughHistory)
        composeRule.onNodeWithTag(EXPLORE_CLEAR_ALL_TAG).assertIsDisplayed()
    }

    // ---- Answer line -----------------------------------------------------------------------

    @Test
    fun `the scope answer is worded per scope`() {
        show(loaded())
        composeRule.onNodeWithText("47 entries in the last 30 days.").assertIsDisplayed()

        val one = summary(inPeriod = 1)
        state = loaded(s = one)
        composeRule.onNodeWithText("1 entry in the last 30 days.").assertIsDisplayed()

        val words = ExploreFilter(words = "hot")
        state = loaded(s = summary(inPeriod = 3), filter = words)
        composeRule.onNodeWithText("3 entries with “hot” in the last 30 days.").assertIsDisplayed()

        val subject = ExploreFilter(subjectId = "s1", range = DateRangeSelection.Preset(DateRangePreset.ALL_TIME))
        state = loaded(s = summary(scopeKind = ScopeKind.ONE_SUBJECT, inPeriod = 12), filter = subject, subjectChip = "Hot tub")
        composeRule.onNodeWithText("Hot tub: 12 entries in all your history.").assertIsDisplayed()

        val action = ExploreFilter(actionId = "a1")
        state = loaded(s = summary(scopeKind = ScopeKind.ONE_ACTION, inPeriod = 5), filter = action, actionChip = "Mow")
        composeRule.onNodeWithText("Mow: 5 entries in the last 30 days.").assertIsDisplayed()

        val aug = YearMonth.of(2026, 8)
        val activity = ExploreFilter(
            range = DateRangeSelection.Custom(aug.atDay(1), aug.atEndOfMonth(), RangeLabel.Month(aug)),
            subjectId = "s1",
            actionId = "a1",
        )
        state = loaded(s = summary(scopeKind = ScopeKind.ONE_ACTIVITY, inPeriod = 4), filter = activity, subjectChip = "Lawn", actionChip = "Mow")
        composeRule.onNodeWithText("Lawn · Mow: 4 times in August 2026.").assertIsDisplayed()
    }

    @Test
    fun `a last-time answer states the database fact with its date and how long ago`() {
        val lastTime = ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant()
        show(loaded(answer = ExploreAnswer.LastTime("Lawn", "Mow", lastTime, closestMatch = null)))
        composeRule.onNodeWithTag(EXPLORE_ANSWER_TAG).assert(hasText("Last logged: Lawn · Mow — September 14 (yesterday)"))
        composeRule.onNodeWithText("Closest match:", substring = true).assertDoesNotExist()

        state = loaded(answer = ExploreAnswer.LastTime("Lawn", "Mow", now.minus(Duration.ofDays(37)), ClosestMatch.NOT_EXACT))
        composeRule.onNodeWithText("Last logged: Lawn · Mow — August 9 (37 days ago)").assertIsDisplayed()
        composeRule.onNodeWithText("Closest match: your question did not match a logged item exactly.").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.LastTime("Lawn", "Mow", now, ClosestMatch.PARTIAL))
        composeRule.onNodeWithText("Last logged: Lawn · Mow — September 15 (today)").assertIsDisplayed()
        composeRule.onNodeWithText("Closest match: showing entries that only partly match your question.").assertIsDisplayed()
    }

    private val yesterdayMow: Instant = ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant()
    private val augustRange = YearMonth.of(2026, 8).let {
        DateRangeSelection.Custom(it.atDay(1), it.atEndOfMonth(), RangeLabel.Month(it))
    }

    @Test
    fun `a count answer states the count in the range and the last time`() {
        show(loaded(answer = ExploreAnswer.Count("Lawn", "Mow", 4, augustRange, yesterdayMow, closestMatch = null)))
        composeRule.onNodeWithTag(EXPLORE_ANSWER_TAG)
            .assert(hasText("Lawn · Mow: 4 times in August 2026. Last: September 14 (yesterday)."))
        composeRule.onNodeWithText("Closest match:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Couldn't tell which dates", substring = true).assertDoesNotExist()

        state = loaded(answer = ExploreAnswer.Count("Lawn", null, 1, DateRangeSelection.Preset(DateRangePreset.ALL_TIME), null, null))
        composeRule.onNodeWithText("Lawn: 1 time in all your history.").assertIsDisplayed()
    }

    @Test
    fun `a count of zero says none and still gives the last time`() {
        show(loaded(answer = ExploreAnswer.Count("Lawn", "Mow", 0, augustRange, yesterdayMow, closestMatch = null)))
        composeRule.onNodeWithText("Lawn · Mow: none in August 2026. Last: September 14 (yesterday).").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.Count(null, "Mow", 0, augustRange, null, closestMatch = null))
        composeRule.onNodeWithText("Mow: none in August 2026.").assertIsDisplayed()
    }

    @Test
    fun `a how-often answer gives the usual gap or says there are too few entries`() {
        val gap = TypicalGap.of(Duration.ofDays(3))
        show(loaded(answer = ExploreAnswer.HowOften("Lawn", "Mow", gap, 9, yesterdayMow, closestMatch = null)))
        composeRule.onNodeWithText("Lawn · Mow: usually every 3 days. Last: September 14 (yesterday).").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.HowOften("Lawn", "Mow", TypicalGap.of(Duration.ofDays(7)), 9, yesterdayMow, null))
        composeRule.onNodeWithText("Lawn · Mow: usually every week. Last: September 14 (yesterday).").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.HowOften("Lawn", "Mow", null, 2, yesterdayMow, closestMatch = null))
        composeRule.onNodeWithText(
            "Lawn · Mow: not enough entries yet to say how often (2 so far). Last: September 14 (yesterday).",
        ).assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.HowOften("Lawn", null, null, 1, yesterdayMow, closestMatch = null))
        composeRule.onNodeWithText(
            "Lawn: not enough entries yet to say how often (1 so far). Last: September 14 (yesterday).",
        ).assertIsDisplayed()
    }

    @Test
    fun `a how-often answer over several activities asks to pick one`() {
        show(loaded(answer = ExploreAnswer.HowOften("Lawn", null, null, 7, yesterdayMow, null, activityCount = 3)))
        composeRule.onNodeWithText(
            "Lawn: logged for 3 different activities. Pick one below to see how often. Last: September 14 (yesterday).",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("usually", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("not enough entries", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the dates-not-understood note follows the closest-match note`() {
        val note = "Couldn't tell which dates you meant, so this covers all your history."
        val allTime = DateRangeSelection.Preset(DateRangePreset.ALL_TIME)
        show(
            loaded(
                answer = ExploreAnswer.Count("Lawn", "Mow", 2, allTime, yesterdayMow, ClosestMatch.NOT_EXACT, datesNotUnderstood = true),
            ),
        )
        composeRule.onNodeWithTag(EXPLORE_ANSWER_TAG).assert(
            SemanticsMatcher("closest-match note, then the dates note") { node ->
                val texts = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .map { it.text }
                val closest = texts.indexOf("Closest match: your question did not match a logged item exactly.")
                closest >= 0 && texts.indexOf(note) == closest + 1
            },
        )

        state = loaded(answer = ExploreAnswer.HowOften("Lawn", "Mow", null, 2, yesterdayMow, null, datesNotUnderstood = true))
        composeRule.onNodeWithText(note).assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.LastTime("Lawn", "Mow", yesterdayMow, null, datesNotUnderstood = true))
        composeRule.onNodeWithText(note).assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.LastTime("Lawn", "Mow", yesterdayMow, null))
        composeRule.onNodeWithText(note).assertDoesNotExist()
    }

    @Test
    fun `a scope line with unclear dates shows the note once under it`() {
        val note = "Couldn't tell which dates you meant, so this covers all your history."
        val filter = ExploreFilter(range = DateRangeSelection.Preset(DateRangePreset.ALL_TIME))
        val s = summary(inPeriod = 47)
        show(loaded(s = s, filter = filter, answer = scope(s, filter).copy(datesNotUnderstood = true)))
        composeRule.onAllNodesWithText("47 entries in all your history.").assertCountEquals(1)
        composeRule.onAllNodesWithText(note).assertCountEquals(1)
        composeRule.onNodeWithTag(EXPLORE_ANSWER_TAG).assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ),
        )

        state = loaded(s = s, filter = filter)
        composeRule.onNodeWithText(note).assertDoesNotExist()
    }

    @Test
    fun `the failure answers use the existing copy`() {
        show(loaded(answer = ExploreAnswer.NotEnoughHistory))
        composeRule.onNodeWithText("There isn't enough history yet to answer that.").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.NotAQuestion)
        composeRule.onNodeWithText("That sounds like something you did, not a question.").assertIsDisplayed()
        composeRule.onNodeWithText("To log it, use the Log tab.").assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.AiUnavailable)
        composeRule.onNodeWithText("This phone can't read questions on its own yet", substring = true).assertIsDisplayed()

        state = loaded(answer = ExploreAnswer.TryAgainLater)
        composeRule.onNodeWithText("I couldn't read that question just now. Try again in a moment.").assertIsDisplayed()
    }

    @Test
    fun `the answer line is a polite live region`() {
        show(loaded())
        composeRule.onNodeWithTag(EXPLORE_ANSWER_TAG).assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ),
        )
    }

    // ---- Three numbers ---------------------------------------------------------------------

    @Test
    fun `many activities show entries with previous, days with an entry and different activities`() {
        show(loaded(s = summary(previous = 41)))
        tile("47").assert(hasText("Entries")).assert(hasText("previous: 41"))
        tile("18 of 30").assert(hasText("Days with an entry"))
        tile("14").assert(hasText("Different activities"))

        state = loaded(s = summary(previous = null))
        composeRule.onNodeWithText("previous:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `one subject shows entries, different actions and last time`() {
        show(loaded(s = summary(scopeKind = ScopeKind.ONE_SUBJECT, inPeriod = 12, actions = 3), filter = ExploreFilter(subjectId = "s"), subjectChip = "Hot tub"))
        tile("12").assert(hasText("Entries"))
        tile("3").assert(hasText("Different actions"))
        tile("2 days ago").assert(hasText("Last time"))
    }

    @Test
    fun `one action shows entries, different subjects and last time`() {
        show(loaded(s = summary(scopeKind = ScopeKind.ONE_ACTION, inPeriod = 9, subjects = 4), filter = ExploreFilter(actionId = "a"), actionChip = "Clean"))
        tile("9").assert(hasText("Entries"))
        tile("4").assert(hasText("Different subjects"))
        tile("2 days ago").assert(hasText("Last time"))
    }

    @Test
    fun `one activity shows times, last time and the usual gap or a dash`() {
        val filter = ExploreFilter(subjectId = "s", actionId = "a")
        show(loaded(s = summary(scopeKind = ScopeKind.ONE_ACTIVITY, inPeriod = 4, lastTime = now.minus(Duration.ofDays(37))), filter = filter))
        tile("4").assert(hasText("Times in period"))
        tile("37 days ago").assert(hasText("Last time"))
        tile("—").assert(hasText("Usually"))

        state = loaded(s = summary(scopeKind = ScopeKind.ONE_ACTIVITY, inPeriod = 4, gap = TypicalGap.of(Duration.ofDays(7))), filter = filter)
        tile("every week").assert(hasText("Usually"))
        state = loaded(s = summary(scopeKind = ScopeKind.ONE_ACTIVITY, inPeriod = 4, gap = TypicalGap.of(Duration.ofDays(3)), lastTime = now), filter = filter)
        tile("every 3 days").assert(hasText("Usually"))
        tile("today").assert(hasText("Last time"))
    }

    @Test
    fun `the three numbers sit in a row and stack at 200 percent text`() {
        show(loaded(s = summary(previous = 41)))
        val a = tile("47").fetchSemanticsNode().boundsInRoot
        val b = tile("18 of 30").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top)
        assertTrue(b.left >= a.right)
    }

    @Test
    fun `at 200 percent text the three numbers stack vertically`() {
        state = loaded(s = summary(previous = 41))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ActivityLedgerTheme {
                    ExploreContent(state = state, callbacks = callbacks, locale = Locale.US)
                }
            }
        }
        composeRule.waitForIdle()
        val a = tile("47").fetchSemanticsNode().boundsInRoot
        val b = tile("18 of 30").fetchSemanticsNode().boundsInRoot
        val c = tile("14").fetchSemanticsNode().boundsInRoot
        assertTrue(b.top >= a.bottom && c.top >= b.bottom, "tiles did not stack: $a $b $c")
    }

    // ---- Chart -----------------------------------------------------------------------------

    private val chartCounts = mapOf(LocalDate.of(2026, 9, 10) to 6, LocalDate.of(2026, 9, 12) to 2, LocalDate.of(2026, 9, 14) to 1)

    @Test
    fun `the chart is described in one sentence and has a sentence under it`() {
        show(loaded(s = summary(chart = dayChart(counts = chartCounts, average = 3.0))))
        composeRule.onNodeWithTag(EXPLORE_CHART_TAG).assert(
            hasContentDescription("Entries per day, last 30 days. Most: 6 on September 10. 27 days with no entries."),
        )
        composeRule.onNodeWithText("Average 3 per day on days you logged something").assertIsDisplayed()
        composeRule.onNodeWithText("Aug 17").assertIsDisplayed()
        composeRule.onNodeWithText("Sep 15").assertIsDisplayed()
    }

    @Test
    fun `the chart toggles to a list of the same buckets and back`() {
        show(loaded(s = summary(chart = dayChart(counts = chartCounts, average = 3.0))))
        composeRule.onNodeWithText("Show as list").performClick()
        assertCalls("chartAsList" to true)

        state = state.copy(chartAsList = true)
        composeRule.onNodeWithTag(EXPLORE_CHART_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(EXPLORE_CHART_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Sep 10 — 6").assertIsDisplayed()
        composeRule.onNodeWithText("Sep 11 — 0").assertIsDisplayed()
        composeRule.onNodeWithText("Show as chart").performClick()
        assertEquals<Pair<String, Any?>>("chartAsList" to false, calls.last())
    }

    @Test
    fun `week and month charts use their own wording and no average means no sentence`() {
        val weeks = ChartSeries(BucketSize.WEEK, listOf(ChartBucket(LocalDate.of(2026, 8, 31), 2), ChartBucket(LocalDate.of(2026, 9, 7), 0)), 2.0)
        show(loaded(s = summary(chart = weeks), filter = ExploreFilter(range = DateRangeSelection.Preset(DateRangePreset.LAST_12_MONTHS))))
        composeRule.onNodeWithTag(EXPLORE_CHART_TAG).assert(
            hasContentDescription("Entries per week, last 12 months. Most: 2 in the week of August 31. 1 week with no entries."),
        )
        composeRule.onNodeWithText("Average 2 per week in weeks you logged something").assertIsDisplayed()

        val months = ChartSeries(BucketSize.MONTH, listOf(ChartBucket(LocalDate.of(2026, 8, 1), 0)), null)
        state = loaded(s = summary(chart = months))
        composeRule.onNodeWithText("Average", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(EXPLORE_CHART_TAG).assert(hasContentDescription("Entries per month, last 30 days. No entries."))
    }

    // ---- Switch, sorts, group by subject ---------------------------------------------------

    @Test
    fun `the switch shows the current view and tapping a segment changes it`() {
        show(loaded(view = ExploreView.ACTIVITIES))
        find(hasTestTag(exploreViewTag(ExploreView.ACTIVITIES))).assertIsSelected()
        composeRule.onNodeWithTag(exploreViewTag(ExploreView.ENTRIES)).assertIsNotSelected()
        composeRule.onNodeWithTag(exploreViewTag(ExploreView.PATTERNS)).performClick()
        composeRule.onNodeWithTag(exploreViewTag(ExploreView.ENTRIES)).performClick()
        assertCalls("view" to ExploreView.PATTERNS, "view" to ExploreView.ENTRIES)
    }

    @Test
    fun `the entries sort menu offers newest and oldest`() {
        show(loaded(view = ExploreView.ENTRIES).copy(entrySort = EntrySort.NEWEST))
        find(hasTestTag(EXPLORE_SORT_TAG))
            .assert(hasText("Sort: Newest ▾"))
            .assert(hasContentDescription("Sort, Newest, double-tap to change"))
            .performClick()
        composeRule.onNodeWithText("Newest").assertIsDisplayed()
        composeRule.onNodeWithText("Oldest").performClick()
        assertCalls("entrySort" to EntrySort.OLDEST)
        composeRule.onNodeWithTag(EXPLORE_GROUP_TAG).assertDoesNotExist()
    }

    @Test
    fun `the activities sort menu offers its four orders`() {
        show(loaded(view = ExploreView.ACTIVITIES))
        find(hasTestTag(EXPLORE_SORT_TAG)).assert(hasText("Sort: Most logged ▾")).performClick()
        composeRule.onNodeWithText("Most logged").assertIsDisplayed()
        composeRule.onNodeWithText("Last done").assertIsDisplayed()
        composeRule.onNodeWithText("Name").assertIsDisplayed()
        composeRule.onNodeWithText("Longest since").performClick()
        assertCalls("activitySort" to ActivitySort.LONGEST_SINCE)
    }

    @Test
    fun `no sort menu on patterns`() {
        show(loaded(view = ExploreView.PATTERNS))
        composeRule.onNodeWithTag(EXPLORE_SORT_TAG).assertDoesNotExist()
    }

    @Test
    fun `group by subject toggles and shows subject rows that drill down`() {
        val group = SubjectGroup("s-Hot tub", "Hot tub", countInPeriod = 12, kinds = 3, lastTime = now.minus(Duration.ofDays(1)))
        show(loaded(s = summary(groups = listOf(group)), view = ExploreView.ACTIVITIES))
        find(hasTestTag(EXPLORE_GROUP_TAG)).performClick()
        assertCalls("group" to true)

        state = state.copy(groupBySubject = true)
        find(hasContentDescription("Hot tub — 12 entries, 3 kinds, yesterday")).performClick()
        assertEquals<Pair<String, Any?>>("openGroup" to group, calls.last())
    }

    // ---- Activities ------------------------------------------------------------------------

    @Test
    fun `an activity row reads as one sentence and tapping it drills down`() {
        val tub = activityRow("Hot tub", "add chlorine", 9, daysAgo = 2, gap = TypicalGap.of(Duration.ofDays(3)))
        val dogs = activityRow("Dogs", "walk", 1, daysAgo = 0)
        show(loaded(s = summary(activityRows = listOf(tub, dogs)), view = ExploreView.ACTIVITIES))
        find(hasContentDescription("Hot tub · add chlorine, 9 entries, last 2 days ago, usually every 3 days"))
            .assert(hasClickAction())
            .performClick()
        find(hasContentDescription("Dogs · walk, 1 entry, last today")).performClick()
        assertCalls("openActivity" to tub, "openActivity" to dogs)
    }

    // ---- Entries ---------------------------------------------------------------------------

    @Test
    fun `entries sit under date headers and only tagged rows are clickable`() {
        val tagged = entry("1", ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant(), "Lawn", "Mow", "Mowed the front")
        val untagged = entry("2", ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, zone).toInstant(), null, null, "did the old thing")
        show(loaded(s = summary(entries = listOf(tagged, untagged)), view = ExploreView.ENTRIES))
        findText("Monday, September 14").assertIsDisplayed()
        findText("Sunday, September 13").assertIsDisplayed()

        find(hasContentDescription("Your words: Mowed the front", substring = true)).assert(hasClickAction()).performClick()
        assertCalls("openEntry" to "c1")
        find(hasContentDescription("Your words: did the old thing", substring = true)).assert(!hasClickAction())
    }

    // ---- Patterns --------------------------------------------------------------------------

    @Test
    fun `patterns show weekday and part-of-day bars with values and the left-out sentences`() {
        val patterns = Patterns(
            byWeekday = DayOfWeek.entries.mapIndexed { i, d -> d to i },
            byPartOfDay = mapOf(PartOfDay.MORNING to 2, PartOfDay.AFTERNOON to 5, PartOfDay.EVENING to 1, PartOfDay.NIGHT to 0),
            partOfDayLeftOut = 4,
            timeMentioned = listOf(TimeMentionedRow("act", "Clean Hot tub", "Hot tub", "Clean", totalSeconds = 5400, entryCount = 3)),
            timeMentionedLeftOut = 2,
        )
        show(loaded(s = summary(patterns = patterns), view = ExploreView.PATTERNS))
        find(hasText("By day of the week")).assertIsDisplayed()
        find(hasContentDescription("Monday: 0 entries")).assertExists()
        find(hasContentDescription("Tuesday: 1 entry")).assertExists()
        find(hasContentDescription("Sunday: 6 entries")).assertExists()
        find(hasText("By time of day")).assertIsDisplayed()
        find(hasContentDescription("Afternoon: 5 entries")).assertExists()
        find(hasContentDescription("Night: 0 entries")).assertExists()
        findText("4 entries without a time of day not shown").assertIsDisplayed()
        findText("Time you mentioned").assertIsDisplayed()
        find(hasText("Hot tub · Clean") and hasText("1 h 30 min") and hasText("3 entries")).assertIsDisplayed()
        findText("2 entries without a mentioned time not shown").assertIsDisplayed()
    }

    @Test
    fun `patterns with nothing left out and no mentioned time say so plainly`() {
        show(loaded(view = ExploreView.PATTERNS))
        findText("No times were mentioned in this period.").assertIsDisplayed()
        composeRule.onNodeWithText("not shown", substring = true).assertDoesNotExist()
    }

    // ---- Empty states ----------------------------------------------------------------------

    @Test
    fun `nothing logged at all shows the UX_SPEC sentence and no numbers`() {
        show(loaded(s = summary(total = 0, inPeriod = 0, days = 0, activities = 0, lastTime = null)))
        composeRule.onNodeWithText("No activities logged yet. Tap the microphone and say what you just did.").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPLORE_NUMBERS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(EXPLORE_CLEAR_FILTERS_TAG).assertDoesNotExist()
    }

    @Test
    fun `nothing for these filters offers Clear filters`() {
        show(loaded(s = summary(inPeriod = 0, days = 0, activities = 0), filter = ExploreFilter(words = "kite")))
        composeRule.onNodeWithText("Nothing logged for these filters.").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPLORE_NUMBERS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(EXPLORE_CLEAR_FILTERS_TAG).assertIsDisplayed().performClick()
        assertCalls("clearAll" to null)
    }

    @Test
    fun `before the first count a progress bar shows`() {
        show(ExploreUiState())
        composeRule.onNodeWithTag(EXPLORE_LOADING_TAG).assertIsDisplayed()
    }
}
