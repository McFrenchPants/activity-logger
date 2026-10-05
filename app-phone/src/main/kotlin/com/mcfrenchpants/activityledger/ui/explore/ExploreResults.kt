package com.mcfrenchpants.activityledger.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.stats.ActivityRow
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.ChartBucket
import com.mcfrenchpants.activityledger.core.domain.stats.ChartSeries
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreSummary
import com.mcfrenchpants.activityledger.core.domain.stats.PartOfDay
import com.mcfrenchpants.activityledger.core.domain.stats.Patterns
import com.mcfrenchpants.activityledger.core.domain.stats.ScopeKind
import com.mcfrenchpants.activityledger.core.domain.stats.SubjectGroup
import com.mcfrenchpants.activityledger.ui.components.DurationFormatter
import com.mcfrenchpants.activityledger.ui.components.HistoryRow
import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes
import com.mcfrenchpants.activityledger.ui.time.OccurrenceTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Test tags of the results. */
const val EXPLORE_LOADING_TAG = "ExploreLoading"
const val EXPLORE_ANSWER_TAG = "ExploreAnswer"
const val EXPLORE_NUMBERS_TAG = "ExploreNumbers"
const val EXPLORE_CHART_TAG = "ExploreChart"
const val EXPLORE_CHART_LIST_TAG = "ExploreChartList"
const val EXPLORE_CHART_TOGGLE_TAG = "ExploreChartToggle"
const val EXPLORE_SORT_TAG = "ExploreSort"
const val EXPLORE_GROUP_TAG = "ExploreGroupBySubject"
const val EXPLORE_EMPTY_TAG = "ExploreEmpty"
const val EXPLORE_CLEAR_FILTERS_TAG = "ExploreClearFilters"
const val EXPLORE_PATTERNS_TAG = "ExplorePatterns"

/** Test tag of the switch segment for [view]. */
fun exploreViewTag(view: ExploreView): String = "ExploreView:${view.name}"

/** Narrowest a bar label may be, so the bars line up (text may still grow wider). */
private val BAR_LABEL_MIN_WIDTH = 96.dp

/** Everything under the filter row, as items of the screen's one list. */
internal fun LazyListScope.exploreResults(
    state: ExploreUiState,
    callbacks: ExploreCallbacks,
    zone: ZoneId,
    now: Instant,
    locale: Locale,
) {
    val summary = state.summary
    val answer = state.answer
    if (summary == null) {
        if (state.isLoading) {
            item(key = "loading") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(EXPLORE_LOADING_TAG))
            }
        }
        if (answer != null && answer !is ExploreAnswer.Scope) item(key = "answer") { AnswerLine(answer, zone, now, locale) }
        return
    }
    val today = now.atZone(zone).toLocalDate()

    if (summary.totalEntriesEver == 0) {
        if (answer != null && answer !is ExploreAnswer.Scope) item(key = "answer") { AnswerLine(answer, zone, now, locale) }
        item(key = "empty") { EmptyText(R.string.explore_empty_never) }
        return
    }
    if (answer != null && !(answer is ExploreAnswer.Scope && summary.entriesInPeriod == 0)) {
        item(key = "answer") { AnswerLine(answer, zone, now, locale) }
    }
    if (summary.entriesInPeriod == 0) {
        item(key = "empty") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyText(R.string.explore_empty_filters)
                OutlinedButton(
                    onClick = callbacks.onClearAll,
                    shape = LedgerShapes.button,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(EXPLORE_CLEAR_FILTERS_TAG),
                ) { Text(stringResource(R.string.explore_clear_filters)) }
            }
        }
        return
    }

    item(key = "numbers") { Numbers(summary, zone, now) }
    item(key = "chart") {
        Chart(summary.chart, state.filter.range, state.chartAsList, today, locale, callbacks.onSetChartAsList)
    }
    item(key = "switch") { ViewSwitch(state, callbacks) }
    when (state.view) {
        ExploreView.ENTRIES -> entriesItems(summary.entries, zone, now, today, locale, callbacks.onOpenEntry)
        ExploreView.ACTIVITIES -> if (state.groupBySubject) {
            itemsIndexed(summary.subjectGroups, key = { index, _ -> "group-$index" }) { _, group ->
                SubjectGroupRow(group, zone, now) { callbacks.onOpenSubjectGroup(group) }
            }
        } else {
            itemsIndexed(summary.activities, key = { index, row -> "activity-$index-${row.activityId}" }) { _, row ->
                ActivityRowView(row, zone, now) { callbacks.onOpenActivity(row) }
            }
        }
        ExploreView.PATTERNS -> item(key = "patterns") { PatternsView(summary.patterns, locale) }
    }
}

@Composable
private fun EmptyText(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .testTag(EXPLORE_EMPTY_TAG),
    )
}

@Composable
private fun QuietText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Answer line -----------------------------------------------------------------------------

/** The database fact first; a polite live region so a screen reader hears each new answer. */
@Composable
private fun AnswerLine(answer: ExploreAnswer, zone: ZoneId, now: Instant, locale: Locale) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(EXPLORE_ANSWER_TAG)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        when (answer) {
            is ExploreAnswer.Scope -> Text(scopeText(answer, locale), style = MaterialTheme.typography.bodyLarge)
            is ExploreAnswer.LastTime -> {
                val today = now.atZone(zone).toLocalDate()
                val date = ExploreDates.longDate(answer.lastTime.atZone(zone).toLocalDate(), today, locale)
                val ago = relativeDayText(answer.lastTime, now, zone)
                val subject = answer.subjectName
                val action = answer.actionName
                val text = when {
                    subject != null && action != null -> stringResource(R.string.explore_last_logged, subject, action, date, ago)
                    else -> stringResource(R.string.explore_last_logged_one_name, subject ?: action.orEmpty(), date, ago)
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
                when (answer.closestMatch) {
                    ClosestMatch.NOT_EXACT -> QuietText(stringResource(R.string.explore_note_not_exact))
                    ClosestMatch.PARTIAL -> QuietText(stringResource(R.string.explore_note_partial))
                    null -> Unit
                }
            }
            ExploreAnswer.NotEnoughHistory -> Text(stringResource(R.string.explore_not_enough_history), style = MaterialTheme.typography.bodyLarge)
            ExploreAnswer.NotAQuestion -> {
                Text(stringResource(R.string.explore_not_a_question), style = MaterialTheme.typography.bodyLarge)
                QuietText(stringResource(R.string.explore_not_a_question_pointer))
            }
            ExploreAnswer.AiUnavailable -> Text(stringResource(R.string.explore_ai_unavailable), style = MaterialTheme.typography.bodyLarge)
            ExploreAnswer.TryAgainLater -> Text(stringResource(R.string.explore_try_again_later), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "47 entries in the last 30 days." / "Hot tub: 12 entries in August 2026." / "Lawn · Mow: 4 times in 2026." */
@Composable
private fun scopeText(scope: ExploreAnswer.Scope, locale: Locale): String {
    val n = scope.entriesInPeriod
    val phrase = rangePhrase(scope.range, locale)
    val subject = scope.subjectName
    val action = scope.actionName
    val words = scope.words?.takeIf { it.isNotBlank() }
    return when {
        scope.scopeKind == ScopeKind.ONE_ACTIVITY && subject != null && action != null ->
            pluralStringResource(R.plurals.explore_scope_activity, n, subject, action, n, phrase)
        scope.scopeKind == ScopeKind.ONE_SUBJECT && subject != null ->
            pluralStringResource(R.plurals.explore_scope_one_tag, n, subject, n, phrase)
        scope.scopeKind == ScopeKind.ONE_ACTION && action != null ->
            pluralStringResource(R.plurals.explore_scope_one_tag, n, action, n, phrase)
        words != null -> pluralStringResource(R.plurals.explore_scope_words, n, n, words, phrase)
        else -> pluralStringResource(R.plurals.explore_scope_many, n, n, phrase)
    }
}

// ---- Three numbers ---------------------------------------------------------------------------

private data class NumberTile(val value: String, val label: String, val extra: String? = null)

/** Three plain numbers chosen by the scope; they stack at large text or on a narrow screen. */
@Composable
private fun Numbers(summary: ExploreSummary, zone: ZoneId, now: Instant) {
    val none = stringResource(R.string.explore_none)
    val lastTime = summary.lastTime?.let { relativeDayText(it, now, zone) } ?: none
    val entries = NumberTile(
        value = summary.entriesInPeriod.toString(),
        label = stringResource(R.string.explore_number_entries),
    )
    val lastTile = NumberTile(lastTime, stringResource(R.string.explore_number_last_time))
    val tiles = when (summary.scopeKind) {
        ScopeKind.MANY -> listOf(
            entries.copy(extra = summary.previousPeriodEntries?.let { stringResource(R.string.explore_number_previous, it) }),
            NumberTile(
                stringResource(R.string.explore_number_days_value, summary.daysWithEntry, summary.range.dayCount.toInt()),
                stringResource(R.string.explore_number_days),
            ),
            NumberTile(summary.distinctActivities.toString(), stringResource(R.string.explore_number_activities)),
        )
        ScopeKind.ONE_SUBJECT -> listOf(
            entries,
            NumberTile(summary.distinctActions.toString(), stringResource(R.string.explore_number_actions)),
            lastTile,
        )
        ScopeKind.ONE_ACTION -> listOf(
            entries,
            NumberTile(summary.distinctSubjects.toString(), stringResource(R.string.explore_number_subjects)),
            lastTile,
        )
        ScopeKind.ONE_ACTIVITY -> listOf(
            NumberTile(summary.entriesInPeriod.toString(), stringResource(R.string.explore_number_times)),
            lastTile,
            NumberTile(summary.typicalGap?.let { gapText(it) } ?: none, stringResource(R.string.explore_number_usually)),
        )
    }
    val largeText = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag(EXPLORE_NUMBERS_TAG)) {
        if (largeText || maxWidth < NARROW_WIDTH) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { Tile(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                tiles.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

private const val LARGE_FONT_SCALE = 1.5f
private val NARROW_WIDTH = 300.dp

@Composable
private fun Tile(tile: NumberTile, modifier: Modifier) {
    Card(
        shape = LedgerShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.semantics(mergeDescendants = true) { },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tile.value, style = MaterialTheme.typography.titleLarge)
            Text(tile.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tile.extra?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Chart -----------------------------------------------------------------------------------

@Composable
private fun Chart(
    series: ChartSeries,
    range: DateRangeSelection,
    asList: Boolean,
    today: LocalDate,
    locale: Locale,
    onSetChartAsList: (Boolean) -> Unit,
) {
    val buckets = series.buckets
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (buckets.isNotEmpty()) {
            if (asList) {
                Column(modifier = Modifier.fillMaxWidth().testTag(EXPLORE_CHART_LIST_TAG)) {
                    buckets.forEach { bucket ->
                        Text(
                            stringResource(R.string.explore_chart_list_row, bucketLabel(bucket, series.bucketSize, today, locale), bucket.count),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            } else {
                val description = chartDescription(series, range, today, locale)
                BarChart(
                    buckets = buckets,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT)
                        .testTag(EXPLORE_CHART_TAG)
                        .semantics { contentDescription = description },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuietSmall(bucketLabel(buckets.first(), series.bucketSize, today, locale))
                    if (buckets.size > 1) QuietSmall(bucketLabel(buckets.last(), series.bucketSize, today, locale))
                }
            }
        }
        chartSentence(series, locale)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(
            onClick = { onSetChartAsList(!asList) },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(EXPLORE_CHART_TOGGLE_TAG),
        ) {
            Text(stringResource(if (asList) R.string.explore_chart_show_chart else R.string.explore_chart_show_list))
        }
    }
}

private val CHART_HEIGHT = 120.dp

@Composable
private fun QuietSmall(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Plain bars in the theme's primary colour over a baseline; the count is the only meaning. */
@Composable
private fun BarChart(buckets: List<ChartBucket>, modifier: Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val max = (buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        val slot = size.width / buckets.size
        val barWidth = (slot * BAR_FRACTION).coerceAtLeast(1f)
        val baseline = 1.dp.toPx()
        val usable = size.height - baseline
        buckets.forEachIndexed { index, bucket ->
            val h = usable * bucket.count / max
            if (h > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, usable - h),
                    size = Size(barWidth, h),
                )
            }
        }
        drawLine(baseColor, Offset(0f, size.height - baseline / 2f), Offset(size.width, size.height - baseline / 2f), baseline)
    }
}

private const val BAR_FRACTION = 0.7f

// ---- Switch, sorts and group by subject ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(state: ExploreUiState, callbacks: ExploreCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ExploreView.entries.forEachIndexed { index, view ->
                SegmentedButton(
                    selected = state.view == view,
                    onClick = { callbacks.onSetView(view) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ExploreView.entries.size),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(exploreViewTag(view)),
                    label = { Text(stringResource(view.labelRes())) },
                )
            }
        }
        when (state.view) {
            ExploreView.ENTRIES -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SortMenu(state.entrySort, EntrySort.entries, { it.labelRes() }, callbacks.onSetEntrySort)
            }
            ExploreView.ACTIVITIES -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = state.groupBySubject,
                            role = Role.Switch,
                            onValueChange = callbacks.onSetGroupBySubject,
                        )
                        .testTag(EXPLORE_GROUP_TAG),
                ) {
                    Switch(checked = state.groupBySubject, onCheckedChange = null)
                    Text(stringResource(R.string.explore_group_by_subject), style = MaterialTheme.typography.bodyMedium)
                }
                SortMenu(state.activitySort, ActivitySort.entries, { it.labelRes() }, callbacks.onSetActivitySort)
            }
            ExploreView.PATTERNS -> Unit
        }
    }
}

private fun ExploreView.labelRes(): Int = when (this) {
    ExploreView.ENTRIES -> R.string.explore_view_entries
    ExploreView.ACTIVITIES -> R.string.explore_view_activities
    ExploreView.PATTERNS -> R.string.explore_view_patterns
}

@Composable
private fun <T> SortMenu(current: T, options: List<T>, labelRes: (T) -> Int, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = stringResource(labelRes(current))
    val description = stringResource(R.string.explore_sort_a11y, currentLabel)
    androidx.compose.foundation.layout.Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(EXPLORE_SORT_TAG)
                .semantics { contentDescription = description },
        ) {
            Text(stringResource(R.string.explore_sort_label, currentLabel) + " ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes(option))) },
                    onClick = {
                        open = false
                        onChoose(option)
                    },
                )
            }
        }
    }
}

// ---- Entries ---------------------------------------------------------------------------------

private fun LazyListScope.entriesItems(
    entries: List<ExploreEntry>,
    zone: ZoneId,
    now: Instant,
    today: LocalDate,
    locale: Locale,
    onOpenEntry: (String) -> Unit,
) {
    groupByDate(entries, zone).forEachIndexed { index, (date, dayEntries) ->
        item(key = "header-$index-$date") {
            Text(
                text = ExploreDates.header(date, today, locale),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { heading() },
            )
        }
        items(dayEntries, key = { "entry-${it.occurrenceId}" }) { entry ->
            val row = entry.toRowModel(zone, now, locale)
            Column {
                if (row.isTagged) {
                    HistoryRow(row, onClick = { onOpenEntry(row.captureId) }, onClickLabel = stringResource(R.string.history_row_action_edit))
                } else {
                    HistoryRow(row)
                }
                HorizontalDivider()
            }
        }
    }
}

/** The one mapping from an Explore entry to the shared History row (no state tag: all are saved). */
internal fun ExploreEntry.toRowModel(zone: ZoneId, now: Instant, locale: Locale): HistoryRowModel = HistoryRowModel(
    captureId = captureId,
    activityName = activityName,
    time = OccurrenceTimeFormatter.format(occurredAt, timePrecision, zone, now, locale),
    state = null,
    rawText = rawText.orEmpty(),
    subjectName = subjectName,
    actionName = actionName,
    durationSeconds = durationSeconds,
    occurrenceId = occurrenceId,
)

// ---- Activities ------------------------------------------------------------------------------

@Composable
private fun ActivityRowView(row: ActivityRow, zone: ZoneId, now: Instant, onClick: () -> Unit) {
    val name = pairName(row.subjectName, row.actionName, row.activityName)
    val count = pluralStringResource(R.plurals.explore_entries_count, row.countInPeriod, row.countInPeriod)
    val last = relativeDayText(row.lastTime, now, zone)
    val usually = row.typicalGap?.let { gapText(it) }
    val description = if (usually != null) {
        stringResource(R.string.explore_row_a11y_usually, name, count, last, usually)
    } else {
        stringResource(R.string.explore_row_a11y, name, count, last)
    }
    ClickableRow(description, stringResource(R.string.explore_activity_open), onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            usually?.let {
                Text(
                    stringResource(R.string.explore_activity_usually, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.countInPeriod.toString(), style = MaterialTheme.typography.titleMedium)
            Text(last, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubjectGroupRow(group: SubjectGroup, zone: ZoneId, now: Instant, onClick: () -> Unit) {
    val line = stringResource(
        R.string.explore_group_line,
        group.subjectName,
        pluralStringResource(R.plurals.explore_entries_count, group.countInPeriod, group.countInPeriod),
        pluralStringResource(R.plurals.explore_kinds_count, group.kinds, group.kinds),
    )
    val last = relativeDayText(group.lastTime, now, zone)
    ClickableRow(stringResource(R.string.explore_time_row, line, last), stringResource(R.string.explore_group_open), onClick) {
        Text(line, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(last, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row read as one sentence and announced as a button with [clickLabel] (like the History row). */
@Composable
private fun ClickableRow(
    description: String,
    clickLabel: String,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick(label = clickLabel) { onClick(); true }
                }
                .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        HorizontalDivider()
    }
}

// ---- Patterns --------------------------------------------------------------------------------

@Composable
private fun PatternsView(patterns: Patterns, locale: Locale) {
    val resources = LocalResources.current
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().testTag(EXPLORE_PATTERNS_TAG),
    ) {
        Section(stringResource(R.string.explore_patterns_weekday_title)) {
            val max = patterns.byWeekday.maxOfOrNull { it.second } ?: 0
            patterns.byWeekday.forEach { (day, count) ->
                BarRow(
                    label = day.getDisplayName(TextStyle.SHORT, locale),
                    spokenLabel = day.getDisplayName(TextStyle.FULL, locale),
                    count = count,
                    max = max,
                )
            }
        }
        Section(stringResource(R.string.explore_patterns_part_title)) {
            val max = patterns.byPartOfDay.values.maxOrNull() ?: 0
            PartOfDay.entries.forEach { part ->
                val label = stringResource(part.labelRes())
                BarRow(label = label, spokenLabel = label, count = patterns.byPartOfDay[part] ?: 0, max = max)
            }
            if (patterns.partOfDayLeftOut > 0) {
                QuietText(
                    pluralStringResource(R.plurals.explore_patterns_part_left_out, patterns.partOfDayLeftOut, patterns.partOfDayLeftOut),
                )
            }
        }
        Section(stringResource(R.string.explore_patterns_time_title)) {
            if (patterns.timeMentioned.isEmpty()) {
                QuietText(stringResource(R.string.explore_patterns_time_none))
            }
            patterns.timeMentioned.forEach { row ->
                val name = pairName(row.subjectName, row.actionName, row.activityName)
                val duration = DurationFormatter.format(resources, row.totalSeconds)
                val count = pluralStringResource(R.plurals.explore_entries_count, row.entryCount, row.entryCount)
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        QuietText(count)
                    }
                    Text(duration, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (patterns.timeMentionedLeftOut > 0) {
                QuietText(
                    pluralStringResource(R.plurals.explore_patterns_time_left_out, patterns.timeMentionedLeftOut, patterns.timeMentionedLeftOut),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** One bar with its label and its count written beside it, read as "Monday: 5 entries". */
@Composable
private fun BarRow(label: String, spokenLabel: String, count: Int, max: Int) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val spoken = stringResource(
        R.string.explore_bar_a11y,
        spokenLabel,
        pluralStringResource(R.plurals.explore_entries_count, count, count),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = BAR_LABEL_MIN_WIDTH))
        Canvas(modifier = Modifier.weight(1f).height(BAR_HEIGHT)) {
            drawRect(trackColor)
            if (max > 0 && count > 0) drawRect(barColor, size = Size(size.width * count / max, size.height))
        }
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

private val BAR_HEIGHT = 12.dp
