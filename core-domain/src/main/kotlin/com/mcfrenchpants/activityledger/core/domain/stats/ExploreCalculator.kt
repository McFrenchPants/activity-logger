/**
 * Explore screen statistics: the filter model and every counting rule behind the Explore screen
 * (one screen merging the dashboard and "Ask"). The rules implemented here are the ones in the
 * Explore design, docs/proposals/explore/DESIGN_SPEC.md, section "Counting rules".
 *
 * Pure Kotlin: no Android, no I/O, no logging, no clock reads. The current instant, time zone and
 * first day of week are always parameters, so results are deterministic and JVM-testable.
 */
package com.mcfrenchpants.activityledger.core.domain.stats

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Computes an [ExploreSummary] from entries and a filter.
 *
 * Rules:
 * - "In scope" = tag filters (id equality; untagged rows never match) and the word filter
 *   (case-insensitive substring of the trimmed words over raw text, activity/subject/action names
 *   and subject/action aliases). "In period" = in scope and the entry's local date (in the zone)
 *   inside the resolved range.
 * - Grouping is by occurred time in the given zone; days end at local midnight; weeks start on
 *   the given first day of week.
 * - Counts are in period; "last time", typical gap and all-time counts use every in-scope entry.
 * - Activity identity = subject + action pair; rows with neither tag use their canonical activity.
 * - ALL_TIME starts at the earliest in-scope entry's local date (today when there is none).
 * - Tie-breaks: entries by occurredAt then occurrenceId (ascending id in both entry sorts);
 *   activities/groups by display name case-insensitively, then id.
 */
object ExploreCalculator {

    private const val MAX_PREVIOUS_PERIOD_DAYS = 366L
    private const val MAX_DAY_BUCKET_DAYS = 31L
    private const val MAX_WEEK_BUCKET_DAYS = 371L

    fun calculate(
        entries: List<ExploreEntry>,
        filter: ExploreFilter,
        zone: ZoneId,
        now: Instant,
        firstDayOfWeek: DayOfWeek,
        activitySort: ActivitySort = ActivitySort.MOST_LOGGED,
        entrySort: EntrySort = EntrySort.NEWEST,
        groupBySubject: Boolean = false,
    ): ExploreSummary {
        val today = now.atZone(zone).toLocalDate()
        val words = filter.words?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

        val inScope = entries.filter { matchesTags(it, filter) && (words == null || matchesWords(it, words)) }
        val earliest = inScope.minOfOrNull { localDate(it, zone) }
        val range = resolve(filter.range, today, earliest)
        val inPeriod = inScope.filter { localDate(it, zone) in range }

        val isAllTime = filter.range == DateRangeSelection.Preset(DateRangePreset.ALL_TIME)
        val previous = if (isAllTime || range.dayCount > MAX_PREVIOUS_PERIOD_DAYS) {
            null
        } else {
            val prev = ResolvedRange.of(range.start.minusDays(range.dayCount), range.start.minusDays(1))
            inScope.count { localDate(it, zone) in prev }
        }

        val allTimeByActivity = inScope.groupBy(::activityKey)
        val activityRows = buildActivityRows(inPeriod, allTimeByActivity)

        val scopeKind = filter.scopeKind
        return ExploreSummary(
            scopeKind = scopeKind,
            range = range,
            totalEntriesEver = entries.size,
            entriesInPeriod = inPeriod.size,
            previousPeriodEntries = previous,
            daysWithEntry = inPeriod.map { localDate(it, zone) }.distinct().size,
            distinctActivities = inPeriod.map(::activityKey).distinct().size,
            distinctSubjects = inPeriod.mapNotNull { it.subjectId }.distinct().size,
            distinctActions = inPeriod.mapNotNull { it.actionId }.distinct().size,
            lastTime = inScope.maxOfOrNull { it.occurredAt },
            typicalGap = if (scopeKind == ScopeKind.ONE_ACTIVITY) TypicalGap.fromTimes(inScope.map { it.occurredAt }) else null,
            chart = buildChart(inPeriod, range, zone, firstDayOfWeek),
            activities = sortActivities(activityRows, activitySort),
            subjectGroups = if (groupBySubject) buildSubjectGroups(activityRows, activitySort) else emptyList(),
            patterns = buildPatterns(inPeriod, zone, firstDayOfWeek),
            entries = sortEntries(inPeriod, entrySort),
        )
    }

    // ---- matching ----

    private fun matchesTags(e: ExploreEntry, f: ExploreFilter): Boolean =
        (f.subjectId == null || e.subjectId == f.subjectId) && (f.actionId == null || e.actionId == f.actionId)

    private fun matchesWords(e: ExploreEntry, lowerWords: String): Boolean {
        val haystack = sequenceOf(e.rawText, e.activityName, e.subjectName, e.actionName) +
            e.subjectAliases.asSequence() + e.actionAliases.asSequence()
        return haystack.any { it != null && it.lowercase(Locale.ROOT).contains(lowerWords) }
    }

    private fun localDate(e: ExploreEntry, zone: ZoneId): LocalDate = e.occurredAt.atZone(zone).toLocalDate()

    /** Activity identity: subject + action pair, or the canonical activity for untagged rows. */
    private fun activityKey(e: ExploreEntry): ActivityKey =
        if (e.subjectId == null && e.actionId == null) {
            ActivityKey(activityId = e.activityId, subjectId = null, actionId = null)
        } else {
            ActivityKey(activityId = null, subjectId = e.subjectId, actionId = e.actionId)
        }

    private data class ActivityKey(val activityId: String?, val subjectId: String?, val actionId: String?)

    // ---- activities ----

    private fun displayName(r: ActivityRow): String =
        if (r.subjectName != null && r.actionName != null) "${r.subjectName} · ${r.actionName}" else r.activityName

    private fun buildActivityRows(
        inPeriod: List<ExploreEntry>,
        allTimeByActivity: Map<ActivityKey, List<ExploreEntry>>,
    ): List<ActivityRow> =
        inPeriod.groupBy(::activityKey).map { (key, periodEntries) ->
            val all = allTimeByActivity.getValue(key)
            val newest = all.maxWith(ENTRY_ORDER)
            ActivityRow(
                activityId = newest.activityId,
                activityName = newest.activityName,
                subjectId = newest.subjectId,
                subjectName = newest.subjectName,
                actionId = newest.actionId,
                actionName = newest.actionName,
                countInPeriod = periodEntries.size,
                lastTime = newest.occurredAt,
                typicalGap = TypicalGap.fromTimes(all.map { it.occurredAt }),
                entriesAllTime = all.size,
            )
        }

    private fun sortActivities(rows: List<ActivityRow>, sort: ActivitySort): List<ActivityRow> {
        val byName = compareBy<ActivityRow, String>(String.CASE_INSENSITIVE_ORDER) { displayName(it) }
            .thenBy { it.activityId }
        val primary: Comparator<ActivityRow> = when (sort) {
            ActivitySort.MOST_LOGGED -> compareByDescending { it.countInPeriod }
            ActivitySort.LAST_DONE -> compareByDescending { it.lastTime }
            ActivitySort.LONGEST_SINCE -> compareBy { it.lastTime }
            ActivitySort.NAME -> Comparator { _, _ -> 0 }
        }
        return rows.sortedWith(primary.then(byName))
    }

    private fun buildSubjectGroups(rows: List<ActivityRow>, sort: ActivitySort): List<SubjectGroup> {
        val groups = rows.groupBy { r ->
            if (r.subjectId != null) "subject:${r.subjectId}" else "name:${r.activityName.trim().lowercase(Locale.ROOT)}"
        }.map { (_, members) ->
            val first = members.first()
            val name = if (first.subjectId != null) {
                members.firstNotNullOfOrNull { it.subjectName } ?: first.activityName
            } else {
                members.minWith(compareBy<ActivityRow> { it.activityName }.thenBy { it.activityId }).activityName
            }
            SubjectGroup(
                subjectId = first.subjectId,
                subjectName = name,
                countInPeriod = members.sumOf { it.countInPeriod },
                kinds = members.size,
                lastTime = members.maxOf { it.lastTime },
            )
        }
        val byName = compareBy<SubjectGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.subjectName }
            .thenBy { it.subjectId.orEmpty() }
        val primary: Comparator<SubjectGroup> = when (sort) {
            ActivitySort.MOST_LOGGED -> compareByDescending { it.countInPeriod }
            ActivitySort.LAST_DONE -> compareByDescending { it.lastTime }
            ActivitySort.LONGEST_SINCE -> compareBy { it.lastTime }
            ActivitySort.NAME -> Comparator { _, _ -> 0 }
        }
        return groups.sortedWith(primary.then(byName))
    }

    // ---- entries ----

    private val ENTRY_ORDER: Comparator<ExploreEntry> =
        compareBy<ExploreEntry> { it.occurredAt }.thenBy { it.occurrenceId }

    private fun sortEntries(list: List<ExploreEntry>, sort: EntrySort): List<ExploreEntry> = when (sort) {
        EntrySort.OLDEST -> list.sortedWith(ENTRY_ORDER)
        EntrySort.NEWEST -> list.sortedWith(compareByDescending<ExploreEntry> { it.occurredAt }.thenBy { it.occurrenceId })
    }

    // ---- chart ----

    private fun buildChart(
        inPeriod: List<ExploreEntry>,
        range: ResolvedRange,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
    ): ChartSeries {
        val size = when {
            range.dayCount <= MAX_DAY_BUCKET_DAYS -> BucketSize.DAY
            range.dayCount <= MAX_WEEK_BUCKET_DAYS -> BucketSize.WEEK
            else -> BucketSize.MONTH
        }
        fun bucketStart(d: LocalDate): LocalDate = when (size) {
            BucketSize.DAY -> d
            BucketSize.WEEK -> d.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            BucketSize.MONTH -> d.withDayOfMonth(1)
        }
        fun next(d: LocalDate): LocalDate = when (size) {
            BucketSize.DAY -> d.plusDays(1)
            BucketSize.WEEK -> d.plusWeeks(1)
            BucketSize.MONTH -> d.plusMonths(1)
        }
        val counts = inPeriod.groupingBy { bucketStart(localDate(it, zone)) }.eachCount()
        val buckets = ArrayList<ChartBucket>()
        var d = bucketStart(range.start)
        while (!d.isAfter(range.endInclusive)) {
            buckets += ChartBucket(d, counts[d] ?: 0)
            d = next(d)
        }
        val nonEmpty = buckets.filter { it.count > 0 }
        val average = if (nonEmpty.isEmpty()) null else nonEmpty.sumOf { it.count }.toDouble() / nonEmpty.size
        return ChartSeries(size, buckets, average)
    }

    // ---- patterns ----

    private fun partOfDay(hour: Int): PartOfDay = when (hour) {
        in 5..11 -> PartOfDay.MORNING
        in 12..16 -> PartOfDay.AFTERNOON
        in 17..21 -> PartOfDay.EVENING
        else -> PartOfDay.NIGHT
    }

    private fun buildPatterns(inPeriod: List<ExploreEntry>, zone: ZoneId, firstDayOfWeek: DayOfWeek): Patterns {
        val weekdayCounts = inPeriod.groupingBy { localDate(it, zone).dayOfWeek }.eachCount()
        val byWeekday = (0L until 7L).map { firstDayOfWeek.plus(it) }.map { it to (weekdayCounts[it] ?: 0) }

        val timed = inPeriod.filter { it.timePrecision != TimePrecision.DATE_ONLY }
        val partCounts = timed.groupingBy { partOfDay(it.occurredAt.atZone(zone).hour) }.eachCount()
        val byPartOfDay = PartOfDay.entries.associateWith { partCounts[it] ?: 0 }

        val withDuration = inPeriod.filter { it.durationSeconds != null }
        val timeMentioned = withDuration.groupBy(::activityKey).map { (_, list) ->
            val newest = list.maxWith(ENTRY_ORDER)
            TimeMentionedRow(
                activityId = newest.activityId,
                activityName = newest.activityName,
                subjectName = newest.subjectName,
                actionName = newest.actionName,
                totalSeconds = list.sumOf { it.durationSeconds ?: 0L },
                entryCount = list.size,
            )
        }.sortedWith(
            compareByDescending<TimeMentionedRow> { it.totalSeconds }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.activityName }
                .thenBy { it.activityId },
        )

        return Patterns(
            byWeekday = byWeekday,
            byPartOfDay = byPartOfDay,
            partOfDayLeftOut = inPeriod.size - timed.size,
            timeMentioned = timeMentioned,
            timeMentionedLeftOut = inPeriod.size - withDuration.size,
        )
    }
}
