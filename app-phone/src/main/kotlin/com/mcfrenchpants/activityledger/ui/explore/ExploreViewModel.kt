package com.mcfrenchpants.activityledger.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.lookup.DateWords
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupOutcome
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupTier
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionDetector
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.stats.ActivityRow
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreCalculator
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreSummary
import com.mcfrenchpants.activityledger.core.domain.stats.ScopeKind
import com.mcfrenchpants.activityledger.core.domain.stats.SubjectGroup
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * State holder of the Explore screen: search box with suggestions, filter chips, answer line,
 * counts, chart and the Entries / Activities / Patterns views.
 *
 * Rules it keeps:
 * - Read only. It loads [TagRepository.loadExploreEntries] and [TagRepository.loadTagCatalog] on
 *   [onStart] (plan P1) and never calls a repository write.
 * - The typed text, questions and word searches live in [state] only: never stored, never
 *   logged (AGENTS.md #11). Messages carry string resource ids only.
 * - Counting is [ExploreCalculator]'s job, run on [computeDispatcher]; a result computed for an
 *   earlier request never overwrites a newer one.
 * - A question only sets filter chips from [LookupOutcome.Answer] (tag ids of the target, the
 *   range its date words resolved to, All time when none, plan P2) or [LookupOutcome.Browse]
 *   (range only). The answer line is a last-time fact from the best entry, or a count / typical
 *   gap taken from the [ExploreCalculator] summary of the new filters; failure outcomes leave the
 *   filters unchanged. The model never produces a count, a date or answer text.
 * - One question at a time; a clear or a filter change while a question is pending drops its
 *   late answer.
 * - Voice follows the Log view model: one session at a time identified by a token; the final
 *   transcript goes into the box and is submitted with [submit].
 */
@Suppress("TooManyFunctions")
class ExploreViewModel(
    private val repository: TagRepository,
    private val lookup: LookupService,
    private val transcriber: SpeechTranscriber,
    private val clock: Clock,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val firstDayOfWeekProvider: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())

    /** The screen's single UI state. */
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    private var entries: List<ExploreEntry>? = null
    private var catalog: TagCatalog = TagCatalog.EMPTY

    /** Earlier filters, newest last, restored by [back]. */
    private val backStack = ArrayDeque<ExploreFilter>()

    /** The answer of the last question, while it is showing; null means the answer line is Scope. */
    private var questionAnswer: ExploreAnswer? = null

    /** A Count / HowOften answer waiting for the summary of its filter; built in [recompute]. */
    private var pendingQuestion: PendingQuestion? = null

    /** The Scope line carries the "dates not understood" note (a browse question's words failed). */
    private var scopeDatesNotUnderstood = false

    /** Increments with every computation request; only the newest one may publish. */
    private var computeSeq = 0L

    /** Increments with every load; only the newest one may publish. */
    private var loadSeq = 0L

    /** Identifies the one pending question allowed to change anything, or null when none is. */
    private var askToken: Any? = null
    private var askJob: Job? = null

    /** Identifies the one listening session allowed to change anything, or null when none is. */
    private var listeningSession: Any? = null
    private var listeningJob: Job? = null

    // ---- Loading ---------------------------------------------------------------------------

    /** The screen came to the foreground: (re)loads every entry and the tag catalog. */
    fun onStart() {
        val seq = ++loadSeq
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val loaded = try {
                repository.loadExploreEntries() to repository.loadTagCatalog()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (seq == loadSeq) {
                    // Keep whatever was showing; say so in plain words (never the user's text).
                    _state.update { it.copy(isLoading = false, message = UserMessage(R.string.history_not_loaded)) }
                }
                return@launch
            }
            if (seq != loadSeq) return@launch
            entries = loaded.first
            catalog = loaded.second
            _state.update { s ->
                withChipNames(s).copy(
                    message = if (s.message == UserMessage(R.string.history_not_loaded)) null else s.message,
                    suggestions = suggestionsFor(s.input),
                    subjectTags = catalog.subjects,
                    actionTags = catalog.actions,
                )
            }
            recompute()
        }
    }

    // ---- Filters ---------------------------------------------------------------------------

    fun setRange(range: DateRangeSelection) {
        changeFilter(_state.value.filter.copy(range = range))
    }

    fun setSubject(tagId: String?) {
        changeFilter(_state.value.filter.copy(subjectId = tagId))
    }

    fun setAction(tagId: String?) {
        changeFilter(_state.value.filter.copy(actionId = tagId))
    }

    /** Sets the words chip; blank text removes it. */
    fun setWords(text: String?) {
        changeFilter(_state.value.filter.copy(words = text?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** Removes one chip ([ExploreFilterKind.RANGE] goes back to the default range). */
    fun clearFilter(kind: ExploreFilterKind) {
        val f = _state.value.filter
        changeFilter(
            when (kind) {
                ExploreFilterKind.RANGE -> f.copy(range = ExploreFilter().range)
                ExploreFilterKind.SUBJECT -> f.copy(subjectId = null)
                ExploreFilterKind.ACTION -> f.copy(actionId = null)
                ExploreFilterKind.WORDS -> f.copy(words = null)
            },
        )
    }

    /**
     * Back to the opening state: default filter (Last 30 days, no tags, no words), no question or
     * answer, empty back stack and box. Stops listening and drops a pending answer. Sorts stay.
     */
    fun clearAll() {
        cancelListening()
        cancelAsk()
        backStack.clear()
        questionAnswer = null
        pendingQuestion = null
        scopeDatesNotUnderstood = false
        val filter = ExploreFilter()
        _state.update {
            withChipNames(
                it.copy(
                    filter = filter,
                    view = defaultView(filter),
                    answer = null,
                    askedQuestion = null,
                    input = "",
                    suggestions = emptyList(),
                    canGoBack = false,
                    message = null,
                ),
            )
        }
        recompute()
    }

    // ---- Views and sorts -------------------------------------------------------------------

    /** Shows [view]; it sticks until the next filter change. */
    fun setView(view: ExploreView) {
        _state.update { it.copy(view = view) }
    }

    fun setActivitySort(sort: ActivitySort) {
        _state.update { it.copy(activitySort = sort) }
        recompute()
    }

    fun setEntrySort(sort: EntrySort) {
        _state.update { it.copy(entrySort = sort) }
        recompute()
    }

    fun setGroupBySubject(enabled: Boolean) {
        _state.update { it.copy(groupBySubject = enabled) }
        recompute()
    }

    fun setChartAsList(enabled: Boolean) {
        _state.update { it.copy(chartAsList = enabled) }
    }

    // ---- Drill-down ------------------------------------------------------------------------

    /** Narrows to one activity row, keeping the range; an untagged row becomes a word search. */
    fun openActivity(row: ActivityRow) {
        val f = _state.value.filter
        val next = if (row.subjectId == null && row.actionId == null) {
            f.copy(subjectId = null, actionId = null, words = row.activityName)
        } else {
            f.copy(subjectId = row.subjectId, actionId = row.actionId, words = null)
        }
        changeFilter(next, pushCurrent = true)
    }

    /** Narrows to one subject group, keeping the range; an untagged group becomes a word search. */
    fun openSubjectGroup(group: SubjectGroup) {
        val f = _state.value.filter
        val next = if (group.subjectId == null) {
            f.copy(subjectId = null, actionId = null, words = group.subjectName)
        } else {
            f.copy(subjectId = group.subjectId, actionId = null, words = null)
        }
        changeFilter(next, pushCurrent = true)
    }

    /** Restores the previous filters; false when there are none (the screen then lets Back leave). */
    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        changeFilter(previous)
        return true
    }

    // ---- Search box ------------------------------------------------------------------------

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, suggestions = suggestionsFor(text)) }
    }

    /** Acts on a suggestion; only [ExploreSuggestion.Ask] reaches the question reader. */
    fun chooseSuggestion(suggestion: ExploreSuggestion) {
        when (suggestion) {
            is ExploreSuggestion.Subject -> {
                clearInput()
                setSubject(suggestion.tagId)
            }
            is ExploreSuggestion.Action -> {
                clearInput()
                setAction(suggestion.tagId)
            }
            is ExploreSuggestion.SearchWords -> {
                clearInput()
                setWords(suggestion.text)
            }
            is ExploreSuggestion.Ask -> ask(suggestion.text)
        }
    }

    /** The Enter key: a question is asked, anything else becomes a word search; blank does nothing. */
    fun submit() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        if (QuestionDetector.isQuestion(text)) {
            ask(text)
        } else {
            clearInput()
            setWords(text)
        }
    }

    /** Asks [text]. Blank text and a second question while one is pending are ignored. */
    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.isAsking) return
        cancelListening()
        val token = Any()
        askToken = token
        _state.update {
            it.copy(input = "", suggestions = emptyList(), isAsking = true, askedQuestion = question, message = null)
        }
        askJob = viewModelScope.launch {
            val outcome = try {
                val zone = zoneProvider()
                lookup.ask(question, clock.instant().atZone(zone).toLocalDate(), firstDayOfWeekProvider())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            // A clear or a filter change dropped this question: the late answer changes nothing.
            if (askToken !== token) return@launch
            askToken = null
            askJob = null
            onAnswer(outcome)
        }
    }

    // ---- Voice -----------------------------------------------------------------------------

    /** Opens one listening session; ignored while one is running or an answer is pending. */
    fun startListening() {
        val current = _state.value
        if (current.isListening || current.isAsking) return
        cancelListening()
        val session = Any()
        listeningSession = session
        _state.update { it.copy(isListening = true, partialTranscript = "", message = null) }
        listeningJob = viewModelScope.launch {
            try {
                transcriber.listen().collect { event -> onSpeechEvent(session, event) }
            } finally {
                endListeningState(session)
            }
        }
    }

    fun stopListening() {
        cancelListening()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun onMicrophonePermissionDenied() {
        _state.update {
            it.copy(isListening = false, partialTranscript = "", message = UserMessage(R.string.explore_mic_permission_denied))
        }
    }

    fun onMicrophonePermissionBlocked() {
        _state.update {
            it.copy(isListening = false, partialTranscript = "", message = UserMessage(R.string.explore_mic_permission_blocked))
        }
    }

    override fun onCleared() {
        cancelListening()
        askJob?.cancel()
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun onAnswer(outcome: LookupOutcome?) {
        when (outcome) {
            is LookupOutcome.Answer -> onQuestionAnswer(outcome)
            is LookupOutcome.Browse -> onBrowse(outcome)
            else -> {
                val answer = when (outcome) {
                    LookupOutcome.NotEnoughHistory -> ExploreAnswer.NotEnoughHistory
                    LookupOutcome.NotAQuestion -> ExploreAnswer.NotAQuestion
                    LookupOutcome.Unavailable -> ExploreAnswer.AiUnavailable
                    LookupOutcome.Busy, LookupOutcome.Failed, null -> ExploreAnswer.TryAgainLater
                    is LookupOutcome.Answer, is LookupOutcome.Browse -> error("handled above")
                }
                questionAnswer = answer
                pendingQuestion = null
                scopeDatesNotUnderstood = false
                _state.update { it.copy(answer = answer, isAsking = false) }
            }
        }
    }

    /**
     * Sets the chips from the question (range from its date words, tags from the target) and picks
     * the answer by kind: HOW_OFTEN -> [ExploreAnswer.HowOften]; COUNT, or understood date words on
     * any other kind -> [ExploreAnswer.Count]; otherwise [ExploreAnswer.LastTime] from the best entry.
     * Count and HowOften are filled in [recompute] from the summary computed for the new filter.
     */
    private fun onQuestionAnswer(outcome: LookupOutcome.Answer) {
        backStack.addLast(_state.value.filter)
        val scope = outcome.scope
        val filter = ExploreFilter(
            range = scope.range,
            subjectId = outcome.target.subject?.id,
            actionId = outcome.target.action?.id,
            words = null,
        )
        val top = checkNotNull(outcome.result.top)
        val closest = when {
            !top.exact -> ClosestMatch.NOT_EXACT
            top.tier == LookupTier.SUBJECT_ONLY || top.tier == LookupTier.ACTION_ONLY -> ClosestMatch.PARTIAL
            else -> null
        }
        val datesNotUnderstood = scope.dateWords == DateWords.NOT_UNDERSTOOD
        val answerKind = when {
            scope.kind == QuestionKind.HOW_OFTEN -> PendingKind.HOW_OFTEN
            scope.kind == QuestionKind.COUNT || scope.dateWords == DateWords.USED -> PendingKind.COUNT
            else -> null
        }
        scopeDatesNotUnderstood = false
        if (answerKind == null) {
            pendingQuestion = null
            questionAnswer = ExploreAnswer.LastTime(
                subjectName = top.entry.subjectName,
                actionName = top.entry.actionName,
                lastTime = top.entry.occurredAt,
                closestMatch = closest,
                datesNotUnderstood = datesNotUnderstood,
            )
        } else {
            // Name only the sides the count is narrowed to, as the chips show them.
            val subjectName = outcome.target.subject?.let { catalog.tag(TagKind.SUBJECT, it.id)?.displayName ?: top.entry.subjectName }
            val actionName = outcome.target.action?.let { catalog.tag(TagKind.ACTION, it.id)?.displayName ?: top.entry.actionName }
            pendingQuestion = PendingQuestion(answerKind, subjectName, actionName, closest, datesNotUnderstood)
            questionAnswer = null
        }
        _state.update {
            withChipNames(
                it.copy(
                    filter = filter,
                    answer = questionAnswer,
                    view = ExploreView.ENTRIES,
                    isAsking = false,
                    canGoBack = backStack.isNotEmpty(),
                ),
            )
        }
        recompute()
    }

    /** A question about a stretch of time: sets only the range; the answer line is the scope. */
    private fun onBrowse(outcome: LookupOutcome.Browse) {
        backStack.addLast(_state.value.filter)
        questionAnswer = null
        pendingQuestion = null
        scopeDatesNotUnderstood = outcome.scope.dateWords == DateWords.NOT_UNDERSTOOD
        val filter = ExploreFilter(range = outcome.scope.range)
        _state.update {
            withChipNames(
                it.copy(
                    filter = filter,
                    answer = null,
                    view = ExploreView.ENTRIES,
                    isAsking = false,
                    canGoBack = backStack.isNotEmpty(),
                ),
            )
        }
        recompute()
    }

    /** Builds a Count / HowOften answer from the summary computed for the question's filter. */
    private fun answerFor(pending: PendingQuestion, summary: ExploreSummary, filter: ExploreFilter): ExploreAnswer =
        when (pending.kind) {
            PendingKind.COUNT -> ExploreAnswer.Count(
                subjectName = pending.subjectName,
                actionName = pending.actionName,
                count = summary.entriesInPeriod,
                range = filter.range,
                lastTime = summary.lastTime,
                closestMatch = pending.closestMatch,
                datesNotUnderstood = pending.datesNotUnderstood,
            )
            PendingKind.HOW_OFTEN -> howOften(pending, summary, filter)
        }

    /**
     * The "how often" answer from the summary (the calculator only gives a typical gap for one
     * activity):
     * 1. Scope ONE_ACTIVITY: the summary's gap and the in-scope all-time count.
     * 2. Any other scope with exactly one activity row: that row's gap and all-time count.
     * 3. Several activity rows: no gap; [ExploreAnswer.HowOften.activityCount] says how many, so
     *    the user picks one from the Activities view.
     * Activity rows only cover activities with entries in the period. A how-often question without
     * date words has the all-time range, so the rows are complete; with date words the rows (and
     * [entriesAllTime]'s fallback) only see activities logged in that range.
     */
    private fun howOften(pending: PendingQuestion, summary: ExploreSummary, filter: ExploreFilter): ExploreAnswer.HowOften {
        val rows = summary.activities
        val single = rows.singleOrNull()
        val (gap, entries, activityCount) = when {
            summary.scopeKind == ScopeKind.ONE_ACTIVITY -> Triple(summary.typicalGap, entriesAllTime(summary, filter), 1)
            single != null -> Triple(single.typicalGap, single.entriesAllTime, 1)
            rows.size > 1 -> Triple(null, entriesAllTime(summary, filter), rows.size)
            else -> Triple(null, entriesAllTime(summary, filter), 1)
        }
        return ExploreAnswer.HowOften(
            subjectName = pending.subjectName,
            actionName = pending.actionName,
            typicalGap = gap,
            entriesAllTime = entries,
            lastTime = summary.lastTime,
            closestMatch = pending.closestMatch,
            datesNotUnderstood = pending.datesNotUnderstood,
            activityCount = activityCount,
        )
    }

    /**
     * Matching entries over all time, from the summary: with an all-time range that is the period
     * count; otherwise the all-time counts of the activities seen in the period.
     */
    private fun entriesAllTime(summary: ExploreSummary, filter: ExploreFilter): Int =
        if (filter.range == DateRangeSelection.Preset(DateRangePreset.ALL_TIME)) {
            summary.entriesInPeriod
        } else {
            summary.activities.sumOf { it.entriesAllTime }
        }

    /**
     * Applies a filter the user chose (chip, suggestion, drill-down, back): drops a pending
     * question and any question answer (the chips are the truth), resets the view to the default
     * for [next] and recomputes. [pushCurrent] saves the current filter for [back].
     */
    private fun changeFilter(next: ExploreFilter, pushCurrent: Boolean = false) {
        cancelAsk()
        if (pushCurrent) backStack.addLast(_state.value.filter)
        questionAnswer = null
        pendingQuestion = null
        scopeDatesNotUnderstood = false
        _state.update {
            withChipNames(
                it.copy(
                    filter = next,
                    view = defaultView(next),
                    answer = null,
                    askedQuestion = null,
                    canGoBack = backStack.isNotEmpty(),
                ),
            )
        }
        recompute()
    }

    private fun recompute() {
        val all = entries ?: return
        val seq = ++computeSeq
        val request = _state.value
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val zone = zoneProvider()
            val firstDay = firstDayOfWeekProvider()
            val now = clock.instant()
            val summary = try {
                withContext(computeDispatcher) {
                    ExploreCalculator.calculate(
                        entries = all,
                        filter = request.filter,
                        zone = zone,
                        now = now,
                        firstDayOfWeek = firstDay,
                        activitySort = request.activitySort,
                        entrySort = request.entrySort,
                        groupBySubject = request.groupBySubject,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (seq == computeSeq) {
                    _state.update { it.copy(isLoading = false, message = UserMessage(R.string.history_not_loaded)) }
                }
                return@launch
            }
            // A newer request superseded this one: its result must not overwrite the newer one.
            if (seq != computeSeq) return@launch
            // A how-often answer over several activities shows the Activities view to pick one from.
            var pickView: ExploreView? = null
            pendingQuestion?.let { pending ->
                val built = answerFor(pending, summary, request.filter)
                if (built is ExploreAnswer.HowOften && built.activityCount > 1) pickView = ExploreView.ACTIVITIES
                questionAnswer = built
                pendingQuestion = null
            }
            _state.update { s ->
                s.copy(
                    view = pickView ?: s.view,
                    summary = summary,
                    isLoading = false,
                    zone = zone,
                    now = now,
                    firstDayOfWeek = firstDay,
                    answer = questionAnswer ?: scopeOf(summary, s),
                )
            }
        }
    }

    private fun scopeOf(summary: ExploreSummary, s: ExploreUiState) = ExploreAnswer.Scope(
        scopeKind = summary.scopeKind,
        range = s.filter.range,
        resolvedRange = summary.range,
        entriesInPeriod = summary.entriesInPeriod,
        subjectName = s.subjectChipName,
        actionName = s.actionChipName,
        words = s.filter.words,
        datesNotUnderstood = scopeDatesNotUnderstood,
    )

    private fun withChipNames(s: ExploreUiState): ExploreUiState = s.copy(
        subjectChipName = s.filter.subjectId?.let { catalog.tag(TagKind.SUBJECT, it)?.displayName },
        actionChipName = s.filter.actionId?.let { catalog.tag(TagKind.ACTION, it)?.displayName },
    )

    private fun defaultView(filter: ExploreFilter): ExploreView =
        if (filter.subjectId == null && filter.actionId == null && filter.words.isNullOrBlank()) {
            ExploreView.ACTIVITIES
        } else {
            ExploreView.ENTRIES
        }

    private fun clearInput() {
        _state.update { it.copy(input = "", suggestions = emptyList()) }
    }

    private fun suggestionsFor(text: String): List<ExploreSuggestion> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val needle = trimmed.lowercase(Locale.ROOT)
        val tags = (catalog.subjects.mapNotNull { match(it, needle, 0) } + catalog.actions.mapNotNull { match(it, needle, 1) })
            .sortedWith(compareBy<TagMatch>({ it.rank }, { it.kindOrder }, { it.tag.displayName.lowercase(Locale.ROOT) }, { it.tag.id }))
            .take(MAX_TAG_SUGGESTIONS)
            .map { m ->
                when (m.tag.kind) {
                    TagKind.SUBJECT -> ExploreSuggestion.Subject(m.tag.id, m.tag.displayName, m.alias)
                    TagKind.ACTION -> ExploreSuggestion.Action(m.tag.id, m.tag.displayName, m.alias)
                }
            }
        return tags + ExploreSuggestion.SearchWords(trimmed) + ExploreSuggestion.Ask(trimmed)
    }

    /**
     * How [tag] matches [needle]: rank 0 for a prefix of the name or an alias, 1 for a match
     * inside either; null for no match. The name wins over an alias of the same rank.
     */
    private fun match(tag: KnownTag, needle: String, kindOrder: Int): TagMatch? {
        val name = tag.displayName.lowercase(Locale.ROOT)
        val aliases = tag.aliases.filterNot { it.lowercase(Locale.ROOT) == name }
        val prefixAlias = aliases.firstOrNull { it.lowercase(Locale.ROOT).startsWith(needle) }
        val innerAlias = aliases.firstOrNull { it.lowercase(Locale.ROOT).contains(needle) }
        return when {
            name.startsWith(needle) -> TagMatch(tag, 0, kindOrder, null)
            prefixAlias != null -> TagMatch(tag, 0, kindOrder, prefixAlias)
            name.contains(needle) -> TagMatch(tag, 1, kindOrder, null)
            innerAlias != null -> TagMatch(tag, 1, kindOrder, innerAlias)
            else -> null
        }
    }

    private class TagMatch(val tag: KnownTag, val rank: Int, val kindOrder: Int, val alias: String?)

    private enum class PendingKind { COUNT, HOW_OFTEN }

    /** What a Count / HowOften answer needs besides the summary. Memory only; no question text. */
    private class PendingQuestion(
        val kind: PendingKind,
        val subjectName: String?,
        val actionName: String?,
        val closestMatch: ClosestMatch?,
        val datesNotUnderstood: Boolean,
    )

    private fun cancelAsk() {
        askToken = null
        askJob?.cancel()
        askJob = null
        _state.update { if (it.isAsking) it.copy(isAsking = false) else it }
    }

    private fun onSpeechEvent(session: Any, event: SpeechEvent) {
        if (listeningSession !== session) return
        when (event) {
            is SpeechEvent.PartialTranscript -> _state.update { it.copy(partialTranscript = event.text) }
            is SpeechEvent.FinalTranscript -> {
                endListeningState(session)
                onInputChange(event.text)
                if (event.text.isNotBlank()) submit()
            }
            is SpeechEvent.Failed -> {
                endListeningState(session)
                showSpeechFailure(event.failure)
            }
        }
    }

    private fun showSpeechFailure(failure: SpeechFailure) {
        val text = when (failure) {
            SpeechFailure.NOTHING_HEARD, SpeechFailure.ENGINE_ERROR -> R.string.explore_voice_nothing_heard
            SpeechFailure.PERMISSION_MISSING -> R.string.explore_mic_permission_denied
            SpeechFailure.NO_ON_DEVICE_ENGINE -> R.string.explore_voice_unavailable
            SpeechFailure.RECOGNIZER_BUSY -> R.string.log_voice_busy
            SpeechFailure.CANCELLED -> return
        }
        _state.update { it.copy(message = UserMessage(text)) }
    }

    private fun endListeningState(session: Any) {
        if (listeningSession !== session) return
        listeningSession = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }

    private fun cancelListening() {
        listeningSession = null
        listeningJob?.cancel()
        listeningJob = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }

    private companion object {
        const val MAX_TAG_SUGGESTIONS = 5
    }
}
