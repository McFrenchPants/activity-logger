package com.mcfrenchpants.activityledger.ui.explore

import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreSummary
import com.mcfrenchpants.activityledger.core.domain.stats.ResolvedRange
import com.mcfrenchpants.activityledger.core.domain.stats.ScopeKind
import com.mcfrenchpants.activityledger.core.domain.stats.TypicalGap
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of the list under the search box while the user types. No AI is used except by [Ask]. */
sealed interface ExploreSuggestion {
    /**
     * An existing subject tag whose name or an alias matches the typed text.
     *
     * @property matchedAlias The alias that matched, or null when the display name matched.
     */
    data class Subject(val tagId: String, val name: String, val matchedAlias: String?) : ExploreSuggestion

    /**
     * An existing action tag whose name or an alias matches the typed text.
     *
     * @property matchedAlias The alias that matched, or null when the display name matched.
     */
    data class Action(val tagId: String, val name: String, val matchedAlias: String?) : ExploreSuggestion

    /** Search the recorded words for [text] (sets the Words chip). */
    data class SearchWords(val text: String) : ExploreSuggestion

    /** Send [text] to the question reader. */
    data class Ask(val text: String) : ExploreSuggestion
}

/** Why a question's answer is only the closest match (the UI words the note). */
enum class ClosestMatch {
    /** A side of the question resolved to a near tag, not an exact one. */
    NOT_EXACT,

    /** Every side was exact, but the best entry only matched the subject or only the action. */
    PARTIAL,
}

/**
 * The answer line. Structured facts only; the UI turns them into words. Nothing here is model
 * output: every value comes from the database or from the filters.
 */
sealed interface ExploreAnswer {
    /**
     * No question is showing: a plain summary of the current scope.
     *
     * @property range The date chip's selection.
     * @property resolvedRange That selection pinned to dates.
     * @property entriesInPeriod Entries in scope within the range.
     * @property subjectName Current name of the subject chip, if set.
     * @property actionName Current name of the action chip, if set.
     * @property words The words chip, if set.
     * @property datesNotUnderstood Set after a question about a stretch of time whose date words
     *   could not be understood, so the range fell back to all time (a note says so).
     */
    data class Scope(
        val scopeKind: ScopeKind,
        val range: DateRangeSelection,
        val resolvedRange: ResolvedRange,
        val entriesInPeriod: Int,
        val subjectName: String?,
        val actionName: String?,
        val words: String?,
        val datesNotUnderstood: Boolean = false,
    ) : ExploreAnswer

    /**
     * A "when did I last ..." question was answered from the best matching database entry.
     *
     * @property subjectName The entry's current subject name.
     * @property actionName The entry's current action name.
     * @property lastTime When that entry happened.
     * @property closestMatch Set when the entry is only the closest match; null for an exact one.
     * @property datesNotUnderstood The question's date words could not be understood (all time).
     */
    data class LastTime(
        val subjectName: String?,
        val actionName: String?,
        val lastTime: Instant,
        val closestMatch: ClosestMatch?,
        val datesNotUnderstood: Boolean = false,
    ) : ExploreAnswer

    /**
     * A "how many times ..." question (or any question with understood date words), counted by
     * [com.mcfrenchpants.activityledger.core.domain.stats.ExploreCalculator] for the new filters.
     *
     * @property subjectName Name of the subject the count is narrowed to, if any.
     * @property actionName Name of the action the count is narrowed to, if any.
     * @property count Entries in [range].
     * @property range The date chip's selection the count covers.
     * @property lastTime Newest matching entry over all time; null when there is none.
     * @property closestMatch Set when the tags are only the closest match.
     * @property datesNotUnderstood The question's date words could not be understood (all time).
     */
    data class Count(
        val subjectName: String?,
        val actionName: String?,
        val count: Int,
        val range: DateRangeSelection,
        val lastTime: Instant?,
        val closestMatch: ClosestMatch?,
        val datesNotUnderstood: Boolean = false,
    ) : ExploreAnswer

    /**
     * A "how often ..." question, answered with the calculator's typical gap.
     *
     * @property subjectName Name of the subject, if any.
     * @property actionName Name of the action, if any.
     * @property typicalGap Median gap over all time of the one activity in scope; null when it has
     *   too few entries or when several activities are in scope.
     * @property entriesAllTime Matching entries over all time.
     * @property lastTime Newest matching entry over all time; null when there is none.
     * @property closestMatch Set when the tags are only the closest match.
     * @property datesNotUnderstood The question's date words could not be understood (all time).
     * @property activityCount Activities in scope: 1 when the gap (or "too few") is about one
     *   activity; more when the scope spans several and the user should pick one (no gap then).
     */
    data class HowOften(
        val subjectName: String?,
        val actionName: String?,
        val typicalGap: TypicalGap?,
        val entriesAllTime: Int,
        val lastTime: Instant?,
        val closestMatch: ClosestMatch?,
        val datesNotUnderstood: Boolean = false,
        val activityCount: Int = 1,
    ) : ExploreAnswer

    /** The question was understood but nothing logged matches it. */
    data object NotEnoughHistory : ExploreAnswer

    /** The words were not a question about the history. */
    data object NotAQuestion : ExploreAnswer

    /** This phone cannot run the on-device model that reads questions. */
    data object AiUnavailable : ExploreAnswer

    /** The model was busy or the question could not be read this time. */
    data object TryAgainLater : ExploreAnswer
}

/** The three-way switch under the chart. */
enum class ExploreView { ENTRIES, ACTIVITIES, PATTERNS }

/**
 * Everything the Explore screen shows. Held in memory only: the typed text, questions and word
 * searches are never stored or logged (AGENTS.md #11). No display strings: data and string
 * resource ids only.
 *
 * @property filter The current chips.
 * @property summary The counts for [filter]; null until the first load has been computed.
 * @property isLoading Whether a load or a computation is in progress.
 * @property subjectChipName Current display name of the subject chip's tag, if set and known.
 * @property actionChipName Current display name of the action chip's tag, if set and known.
 * @property input The search box's current text.
 * @property suggestions What the list under the box offers for [input]; empty for blank input.
 * @property answer The answer line; null before the first computation.
 * @property askedQuestion The last question asked, shown in the box after asking (memory only).
 * @property view Which of the three views is showing.
 * @property canGoBack Whether [ExploreViewModel.back] has earlier filters to restore.
 * @property isAsking Whether a question is being answered right now.
 * @property isListening Whether a voice session is open.
 * @property partialTranscript Live words from the open voice session (display only).
 * @property message A one-off notice, as string resources only.
 * @property zone The time zone [summary] was computed in (for wording dates).
 * @property now The instant [summary] was computed at (for "2 days ago").
 * @property firstDayOfWeek The first day of week [summary] was computed with.
 * @property subjectTags Every subject tag of the last loaded catalog (for the Subject chip's picker).
 * @property actionTags Every action tag of the last loaded catalog (for the Action chip's picker).
 */
data class ExploreUiState(
    val filter: ExploreFilter = ExploreFilter(),
    val summary: ExploreSummary? = null,
    val isLoading: Boolean = true,
    val subjectChipName: String? = null,
    val actionChipName: String? = null,
    val input: String = "",
    val suggestions: List<ExploreSuggestion> = emptyList(),
    val answer: ExploreAnswer? = null,
    val askedQuestion: String? = null,
    val view: ExploreView = ExploreView.ACTIVITIES,
    val activitySort: ActivitySort = ActivitySort.MOST_LOGGED,
    val entrySort: EntrySort = EntrySort.NEWEST,
    val groupBySubject: Boolean = false,
    val chartAsList: Boolean = false,
    val canGoBack: Boolean = false,
    val isAsking: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val message: UserMessage? = null,
    val zone: ZoneId? = null,
    val now: Instant? = null,
    val firstDayOfWeek: DayOfWeek? = null,
    val subjectTags: List<KnownTag> = emptyList(),
    val actionTags: List<KnownTag> = emptyList(),
) {
    /** Today's local date when [summary] was computed, or null before that. */
    val today: LocalDate?
        get() {
            val z = zone ?: return null
            return now?.atZone(z)?.toLocalDate()
        }
}

/** Which chip [ExploreViewModel.clearFilter] removes. */
enum class ExploreFilterKind {
    /** Back to the default date range (Last 30 days). */
    RANGE,
    SUBJECT,
    ACTION,
    WORDS,
}
