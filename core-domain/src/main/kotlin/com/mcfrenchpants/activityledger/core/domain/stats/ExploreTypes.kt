package com.mcfrenchpants.activityledger.core.domain.stats

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * One accepted, visible occurrence as the Explore screen sees it. Callers pass ACTIVE rows only;
 * the calculator does not filter visibility.
 *
 * @property occurrenceId Id of the occurrence; the final deterministic tie-break.
 * @property occurredAt When the thing happened (grouping uses this, never capture time).
 * @property timePrecision How precisely [occurredAt] is known; DATE_ONLY rows are left out of
 *   part-of-day counts.
 * @property durationSeconds How long it took, if mentioned.
 * @property activityId Canonical activity id (always present).
 * @property activityName Canonical activity display name (always present).
 * @property subjectId Subject tag id; null on old untagged rows.
 * @property subjectName CURRENT subject tag name; null on old untagged rows.
 * @property actionId Action tag id; null on old untagged rows.
 * @property actionName CURRENT action tag name; null on old untagged rows.
 * @property rawText The recorded capture words (read-only), if any.
 * @property subjectAliases Aliases of the subject tag, for word search.
 * @property actionAliases Aliases of the action tag, for word search.
 */
data class ExploreEntry(
    val occurrenceId: String,
    val occurredAt: Instant,
    val timePrecision: TimePrecision,
    val durationSeconds: Long?,
    val activityId: String,
    val activityName: String,
    val subjectId: String?,
    val subjectName: String?,
    val actionId: String?,
    val actionName: String?,
    val rawText: String?,
    val subjectAliases: List<String> = emptyList(),
    val actionAliases: List<String> = emptyList(),
)

/**
 * The Explore screen's filters.
 *
 * @property range The date range; counts are within it, "last time" and typical gap are not.
 * @property subjectId Only entries tagged with this subject (untagged rows never match).
 * @property actionId Only entries tagged with this action (untagged rows never match).
 * @property words Case-insensitive substring search; blank means no word filter.
 */
data class ExploreFilter(
    val range: DateRangeSelection = DateRangeSelection.Preset(DateRangePreset.LAST_30_DAYS),
    val subjectId: String? = null,
    val actionId: String? = null,
    val words: String? = null,
) {
    /** True when nothing differs from the screen's opening state. */
    val isDefault: Boolean
        get() = range == DateRangeSelection.Preset(DateRangePreset.LAST_30_DAYS) &&
            subjectId == null && actionId == null && words.isNullOrBlank()

    /** The kind of scope these filters describe. */
    val scopeKind: ScopeKind
        get() = when {
            subjectId != null && actionId != null -> ScopeKind.ONE_ACTIVITY
            subjectId != null -> ScopeKind.ONE_SUBJECT
            actionId != null -> ScopeKind.ONE_ACTION
            else -> ScopeKind.MANY
        }
}

/** What the filters narrow the screen to; decided only by the tag filters. */
enum class ScopeKind { MANY, ONE_SUBJECT, ONE_ACTION, ONE_ACTIVITY }

/** Sort order for [ActivityRow]s and [SubjectGroup]s. */
enum class ActivitySort {
    /** Highest count in period first. */
    MOST_LOGGED,

    /** Most recent "last time" first. */
    LAST_DONE,

    /** Oldest "last time" first. */
    LONGEST_SINCE,

    /** Display name, case-insensitive. */
    NAME,
}

/** Sort order for the entry list. */
enum class EntrySort { NEWEST, OLDEST }

/** Chart bucket width. */
enum class BucketSize { DAY, WEEK, MONTH }

/**
 * One chart bar.
 *
 * @property start First local date of the bucket (a week start or the 1st of a month for wider
 *   buckets; may precede the range start for the first WEEK bucket).
 * @property count In-period entries in the bucket.
 */
data class ChartBucket(val start: LocalDate, val count: Int)

/**
 * The chart for the period.
 *
 * @property buckets Every bucket covering the range, empty ones included, oldest first.
 * @property average Mean count over buckets with count > 0; null when every bucket is empty.
 */
data class ChartSeries(val bucketSize: BucketSize, val buckets: List<ChartBucket>, val average: Double? = null)

/**
 * One activity (subject + action pair, or canonical activity for untagged rows) with at least one
 * entry in the period.
 *
 * @property countInPeriod Entries in the period.
 * @property lastTime Newest entry over all time (within the tag/word filters).
 * @property typicalGap Median gap over all time; null with fewer than 3 entries.
 * @property entriesAllTime Entries over all time (within the tag/word filters).
 */
data class ActivityRow(
    val activityId: String,
    val activityName: String,
    val subjectId: String?,
    val subjectName: String?,
    val actionId: String?,
    val actionName: String?,
    val countInPeriod: Int,
    val lastTime: Instant,
    val typicalGap: TypicalGap?,
    val entriesAllTime: Int,
)

/**
 * Activity rows rolled up by subject. Untagged rows roll up under their activity name.
 *
 * @property subjectId Subject tag id; null for an untagged roll-up.
 * @property subjectName Subject name, or the activity name for an untagged roll-up.
 * @property countInPeriod Entries in the period across the group.
 * @property kinds Number of distinct activities in the group.
 * @property lastTime Newest entry over all time across the group.
 */
data class SubjectGroup(
    val subjectId: String?,
    val subjectName: String,
    val countInPeriod: Int,
    val kinds: Int,
    val lastTime: Instant,
)

/** Part of the local day an entry happened in. */
enum class PartOfDay {
    /** 05:00-11:59. */
    MORNING,

    /** 12:00-16:59. */
    AFTERNOON,

    /** 17:00-21:59. */
    EVENING,

    /** 22:00-04:59. */
    NIGHT,
}

/**
 * Total mentioned time for one activity in the period.
 *
 * @property totalSeconds Sum of mentioned durations.
 * @property entryCount Entries that mentioned a duration.
 */
data class TimeMentionedRow(
    val activityId: String,
    val activityName: String,
    val subjectName: String?,
    val actionName: String?,
    val totalSeconds: Long,
    val entryCount: Int,
)

/**
 * Patterns over in-period entries.
 *
 * @property byWeekday Seven counts starting at the first day of week; DATE_ONLY entries included.
 * @property byPartOfDay Counts per part of day, every key present; DATE_ONLY entries excluded.
 * @property partOfDayLeftOut In-period DATE_ONLY entries (left out of [byPartOfDay]).
 * @property timeMentioned Per-activity mentioned time, largest total first.
 * @property timeMentionedLeftOut In-period entries without a duration.
 */
data class Patterns(
    val byWeekday: List<Pair<DayOfWeek, Int>>,
    val byPartOfDay: Map<PartOfDay, Int>,
    val partOfDayLeftOut: Int,
    val timeMentioned: List<TimeMentionedRow>,
    val timeMentionedLeftOut: Int,
)

/**
 * Everything the Explore screen shows for one filter. See [ExploreCalculator] for the rules.
 *
 * @property totalEntriesEver Every entry passed in, ignoring filters.
 * @property entriesInPeriod In-scope entries within the range.
 * @property previousPeriodEntries In-scope entries in the same-length period just before; null for
 *   ALL_TIME and for ranges longer than 366 days.
 * @property daysWithEntry Distinct local dates with an in-period entry.
 * @property lastTime Newest in-scope entry over all time.
 * @property typicalGap Only for [ScopeKind.ONE_ACTIVITY]: median gap over all time.
 * @property subjectGroups Filled only when grouping by subject was requested.
 * @property entries In-period entries in the requested order.
 */
data class ExploreSummary(
    val scopeKind: ScopeKind,
    val range: ResolvedRange,
    val totalEntriesEver: Int,
    val entriesInPeriod: Int,
    val previousPeriodEntries: Int?,
    val daysWithEntry: Int,
    val distinctActivities: Int,
    val distinctSubjects: Int,
    val distinctActions: Int,
    val lastTime: Instant?,
    val typicalGap: TypicalGap?,
    val chart: ChartSeries,
    val activities: List<ActivityRow>,
    val subjectGroups: List<SubjectGroup>,
    val patterns: Patterns,
    val entries: List<ExploreEntry>,
)
