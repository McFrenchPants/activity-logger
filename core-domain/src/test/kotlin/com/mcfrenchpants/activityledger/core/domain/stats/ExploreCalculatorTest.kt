package com.mcfrenchpants.activityledger.core.domain.stats

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreCalculatorTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")
    private val today: LocalDate = LocalDate.of(2026, 11, 15) // a Sunday
    private val now: Instant = today.atTime(12, 0).atZone(zone).toInstant()

    private fun at(date: LocalDate, time: LocalTime = LocalTime.NOON): Instant =
        LocalDateTime.of(date, time).atZone(zone).toInstant()

    private fun daysAgo(n: Long, time: LocalTime = LocalTime.NOON) = at(today.minusDays(n), time)

    private fun e(
        id: String,
        occurredAt: Instant,
        subject: String? = "furnace",
        action: String? = "change",
        precision: TimePrecision = TimePrecision.EXACT,
        duration: Long? = null,
        raw: String? = null,
        activityId: String = if (subject == null && action == null) "act-$id" else "act-$subject-$action",
        activityName: String = if (subject == null && action == null) "legacy $id" else "$action $subject",
        subjectAliases: List<String> = emptyList(),
        actionAliases: List<String> = emptyList(),
    ) = ExploreEntry(
        occurrenceId = id,
        occurredAt = occurredAt,
        timePrecision = precision,
        durationSeconds = duration,
        activityId = activityId,
        activityName = activityName,
        subjectId = subject,
        subjectName = subject?.let { "Name $it" },
        actionId = action,
        actionName = action?.let { "Name $it" },
        rawText = raw,
        subjectAliases = subjectAliases,
        actionAliases = actionAliases,
    )

    private fun calc(
        entries: List<ExploreEntry>,
        filter: ExploreFilter = ExploreFilter(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        activitySort: ActivitySort = ActivitySort.MOST_LOGGED,
        entrySort: EntrySort = EntrySort.NEWEST,
        groupBySubject: Boolean = false,
        zoneId: ZoneId = zone,
        nowAt: Instant = now,
    ) = ExploreCalculator.calculate(entries, filter, zoneId, nowAt, firstDayOfWeek, activitySort, entrySort, groupBySubject)

    private fun custom(start: LocalDate, end: LocalDate) = ExploreFilter(range = DateRangeSelection.Custom(start, end))

    private fun preset(kind: DateRangePreset) = ExploreFilter(range = DateRangeSelection.Preset(kind))

    private fun ids(s: ExploreSummary) = s.entries.map { it.occurrenceId }

    // ---- time zone, DST, edges ----

    @Test
    fun `spring forward day groups by local date`() {
        val spring = LocalDate.of(2026, 3, 8)
        val entries = listOf(
            e("before", at(spring.minusDays(1), LocalTime.of(23, 59))),
            e("early", at(spring, LocalTime.of(0, 30))),
            e("afterJump", at(spring, LocalTime.of(3, 30))),
            e("late", at(spring, LocalTime.of(23, 59))),
            e("next", at(spring.plusDays(1), LocalTime.MIDNIGHT)),
        )
        val s = calc(entries, custom(spring, spring))
        assertEquals(listOf("late", "afterJump", "early"), ids(s))
        assertEquals(1, s.daysWithEntry)
        assertEquals(listOf(ChartBucket(spring, 3)), s.chart.buckets)
    }

    @Test
    fun `fall back 0130 is counted once on the right date`() {
        val fall = LocalDate.of(2026, 11, 1)
        val first = ZonedDateTime.of(fall, LocalTime.of(1, 30), zone).withEarlierOffsetAtOverlap().toInstant()
        val s = calc(listOf(e("x", first)), custom(fall.minusDays(1), fall.plusDays(1)))
        assertEquals(1, s.entriesInPeriod)
        assertEquals(1, s.daysWithEntry)
        assertEquals(listOf(0, 1, 0), s.chart.buckets.map { it.count })
        assertEquals(fall, s.chart.buckets[1].start)
        assertEquals(1, s.patterns.byPartOfDay[PartOfDay.NIGHT])

        // The second 01:30 (after the clocks go back) is a different instant, also on Nov 1.
        val second = ZonedDateTime.of(fall, LocalTime.of(1, 30), zone).withLaterOffsetAtOverlap().toInstant()
        val s2 = calc(listOf(e("x", first), e("y", second)), custom(fall, fall))
        assertEquals(2, s2.entriesInPeriod)
        assertEquals(1, s2.daysWithEntry)
    }

    @Test
    fun `entries at 2359 and 0000 around range edges`() {
        val start = today.minusDays(6)
        val entries = listOf(
            e("outBefore", at(start.minusDays(1), LocalTime.of(23, 59))),
            e("inStart", at(start, LocalTime.MIDNIGHT)),
            e("inEnd", at(today, LocalTime.of(23, 59))),
            e("outAfter", at(today.plusDays(1), LocalTime.MIDNIGHT)),
        )
        val s = calc(entries, preset(DateRangePreset.LAST_7_DAYS))
        assertEquals(setOf("inStart", "inEnd"), ids(s).toSet())
    }

    @Test
    fun `grouping uses the given zone`() {
        // 03:00 UTC on Nov 10 is Nov 9 evening in Toronto.
        val instant = Instant.parse("2026-11-10T03:00:00Z")
        val toronto = calc(listOf(e("x", instant)), custom(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9)))
        assertEquals(1, toronto.entriesInPeriod)
        val utc = calc(listOf(e("x", instant)), custom(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9)), zoneId = ZoneId.of("UTC"))
        assertEquals(0, utc.entriesInPeriod)
    }

    @Test
    fun `presets include today`() {
        val s = calc(listOf(e("today", daysAgo(0, LocalTime.of(0, 1)))), preset(DateRangePreset.LAST_7_DAYS))
        assertEquals(1, s.entriesInPeriod)
        assertEquals(today, s.range.endInclusive)
        val s30 = calc(listOf(e("a", daysAgo(29)), e("b", daysAgo(30))))
        assertEquals(listOf("a"), ids(s30))
        assertEquals(30, s30.range.dayCount)
    }

    @Test
    fun `custom range bounds are inclusive`() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 31)
        val entries = listOf(
            e("first", at(start, LocalTime.MIDNIGHT)),
            e("last", at(end, LocalTime.of(23, 59, 59))),
            e("before", at(start.minusDays(1), LocalTime.of(23, 59, 59))),
            e("after", at(end.plusDays(1), LocalTime.MIDNIGHT)),
        )
        assertEquals(setOf("first", "last"), ids(calc(entries, custom(start, end))).toSet())
    }

    @Test
    fun `all time starts at the earliest in-scope entry`() {
        val entries = listOf(
            e("old", at(LocalDate.of(2025, 1, 10))),
            e("other", at(LocalDate.of(2024, 1, 10)), subject = "car", action = "wash"),
            e("new", daysAgo(1)),
        )
        val s = calc(entries, ExploreFilter(range = DateRangeSelection.Preset(DateRangePreset.ALL_TIME), subjectId = "furnace"))
        assertEquals(LocalDate.of(2025, 1, 10), s.range.start)
        assertEquals(today, s.range.endInclusive)
        assertEquals(2, s.entriesInPeriod)

        val empty = calc(emptyList(), preset(DateRangePreset.ALL_TIME))
        assertEquals(ResolvedRange(today, today, 1), empty.range)
    }

    // ---- totals, previous period, distinct counts ----

    @Test
    fun `total entries ever ignores filters`() {
        val entries = listOf(e("a", daysAgo(1)), e("b", daysAgo(400), subject = "car", action = "wash"))
        val s = calc(entries, ExploreFilter(subjectId = "nothing"))
        assertEquals(2, s.totalEntriesEver)
        assertEquals(0, s.entriesInPeriod)
        assertNull(s.lastTime)
        assertEquals(0, calc(emptyList()).totalEntriesEver)
    }

    @Test
    fun `previous period counts the same length just before`() {
        // Last 7 days = Nov 9..15; previous = Nov 2..8.
        val entries = listOf(
            e("in", daysAgo(0)),
            e("prevStart", at(LocalDate.of(2026, 11, 2), LocalTime.MIDNIGHT)),
            e("prevEnd", at(LocalDate.of(2026, 11, 8), LocalTime.of(23, 59))),
            e("tooOld", at(LocalDate.of(2026, 11, 1), LocalTime.of(23, 59))),
            e("otherScope", at(LocalDate.of(2026, 11, 3)), subject = "car", action = "wash"),
        )
        val s = calc(entries, ExploreFilter(range = DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS), subjectId = "furnace"))
        assertEquals(1, s.entriesInPeriod)
        assertEquals(2, s.previousPeriodEntries)

        val custom = calc(entries, custom(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10)))
        // previous = Nov 7..8 -> only prevEnd
        assertEquals(1, custom.previousPeriodEntries)
    }

    @Test
    fun `previous period is null for all time and long ranges`() {
        val entries = listOf(e("a", daysAgo(1)))
        assertNull(calc(entries, preset(DateRangePreset.ALL_TIME)).previousPeriodEntries)
        assertNull(calc(entries, custom(today.minusDays(366), today)).previousPeriodEntries) // 367 days
        assertEquals(0, calc(entries, custom(today.minusDays(365), today)).previousPeriodEntries) // 366 days
        assertEquals(0, calc(entries, preset(DateRangePreset.LAST_12_MONTHS)).previousPeriodEntries)
    }

    @Test
    fun `days with entry and distinct counts`() {
        val entries = listOf(
            e("1", daysAgo(1, LocalTime.of(8, 0))),
            e("2", daysAgo(1, LocalTime.of(20, 0)), action = "clean"),
            e("3", daysAgo(2), subject = "car", action = "change"),
            e("4", daysAgo(3), subject = null, action = null),
            e("5", daysAgo(3), subject = null, action = null),
            e("old", daysAgo(100), subject = "boat", action = "paint"),
        )
        val s = calc(entries)
        assertEquals(5, s.entriesInPeriod)
        assertEquals(3, s.daysWithEntry)
        assertEquals(5, s.distinctActivities) // furnace·change, furnace·clean, car·change, 2 untagged
        assertEquals(2, s.distinctSubjects)
        assertEquals(2, s.distinctActions)
    }

    // ---- scope kinds, last time, typical gap ----

    @Test
    fun `every scope kind`() {
        val entries = listOf(e("a", daysAgo(1)))
        assertEquals(ScopeKind.MANY, calc(entries).scopeKind)
        assertEquals(ScopeKind.MANY, calc(entries, ExploreFilter(words = "furnace")).scopeKind)
        assertEquals(ScopeKind.ONE_SUBJECT, calc(entries, ExploreFilter(subjectId = "furnace")).scopeKind)
        assertEquals(ScopeKind.ONE_ACTION, calc(entries, ExploreFilter(actionId = "change")).scopeKind)
        assertEquals(ScopeKind.ONE_ACTIVITY, calc(entries, ExploreFilter(subjectId = "furnace", actionId = "change")).scopeKind)
    }

    @Test
    fun `last time uses all time even outside the period`() {
        val old = daysAgo(200)
        val entries = listOf(e("old", old), e("otherNewer", daysAgo(1), subject = "car", action = "wash"))
        val s = calc(entries, ExploreFilter(subjectId = "furnace"))
        assertEquals(0, s.entriesInPeriod)
        assertEquals(old, s.lastTime)
        assertTrue(s.activities.isEmpty())
    }

    @Test
    fun `typical gap only for one activity over all time`() {
        val entries = listOf(
            e("1", daysAgo(300)),
            e("2", daysAgo(200)),
            e("3", daysAgo(100)),
            e("4", daysAgo(5)),
            e("noise", daysAgo(2), subject = "car", action = "wash"),
        )
        val one = calc(entries, ExploreFilter(subjectId = "furnace", actionId = "change"))
        val gap = assertNotNull(one.typicalGap)
        // Gaps are about 100, 100 and 95 days (DST shifts some by an hour): median ~100 days.
        assertTrue(gap.duration > Duration.ofDays(99) && gap.duration <= Duration.ofDays(100))
        assertEquals(GapUnit.MONTHS, gap.unit)
        assertEquals(3, gap.amount)
        assertEquals(1, one.entriesInPeriod)

        assertNull(calc(entries, ExploreFilter(subjectId = "furnace")).typicalGap)
        assertNull(calc(entries).typicalGap)
        assertNull(calc(entries.take(2), ExploreFilter(subjectId = "furnace", actionId = "change")).typicalGap)
    }

    // ---- activity rows and sorts ----

    @Test
    fun `activity rows use all-time last time gap and count`() {
        val entries = listOf(
            e("1", daysAgo(40)),
            e("2", daysAgo(20)),
            e("3", daysAgo(10)),
            e("4", daysAgo(1)),
            e("old", daysAgo(400), subject = "boat", action = "paint"),
        )
        val s = calc(entries)
        val row = s.activities.single()
        assertEquals("act-furnace-change", row.activityId)
        assertEquals("furnace", row.subjectId)
        assertEquals("Name furnace", row.subjectName)
        assertEquals(3, row.countInPeriod)
        assertEquals(4, row.entriesAllTime)
        assertEquals(daysAgo(1), row.lastTime)
        // gaps 20, 10, 9 days (one crosses the DST change, +1h) -> median 10 days
        assertEquals(GapUnit.DAYS, row.typicalGap?.unit)
        assertEquals(10, row.typicalGap?.amount)
    }

    private val sortEntries = listOf(
        // b: 3 in period, last 5 days ago
        e("b1", daysAgo(5), subject = "b", action = "x"),
        e("b2", daysAgo(6), subject = "b", action = "x"),
        e("b3", daysAgo(7), subject = "b", action = "x"),
        // a: 1 in period, last 1 day ago
        e("a1", daysAgo(1), subject = "a", action = "x"),
        // c: 1 in period, last 10 days ago, and older ones outside
        e("c1", daysAgo(10), subject = "c", action = "x"),
        // D (untagged, capital name): 1 in period, last 10 days ago (ties c)
        e("d1", daysAgo(10), subject = null, action = null, activityName = "Name c · Name w"),
    )

    @Test
    fun `most logged sort with name tie-break`() {
        val s = calc(sortEntries, activitySort = ActivitySort.MOST_LOGGED)
        // b first (3); then ties at 1 by display name: "Name a · Name x", "Name c · Name w" (untagged), "Name c · Name x"
        assertEquals(listOf("act-b-x", "act-a-x", "act-d1", "act-c-x"), s.activities.map { it.activityId })
    }

    @Test
    fun `last done sort`() {
        val s = calc(sortEntries, activitySort = ActivitySort.LAST_DONE)
        assertEquals(listOf("act-a-x", "act-b-x", "act-d1", "act-c-x"), s.activities.map { it.activityId })
    }

    @Test
    fun `longest since sort`() {
        val s = calc(sortEntries, activitySort = ActivitySort.LONGEST_SINCE)
        assertEquals(listOf("act-d1", "act-c-x", "act-b-x", "act-a-x"), s.activities.map { it.activityId })
    }

    @Test
    fun `name sort is case-insensitive then id`() {
        val entries = listOf(
            e("1", daysAgo(1), subject = null, action = null, activityId = "z", activityName = "beta"),
            e("2", daysAgo(1), subject = null, action = null, activityId = "y", activityName = "Alpha"),
            e("3", daysAgo(1), subject = null, action = null, activityId = "x", activityName = "alpha"),
            e("4", daysAgo(1), subject = "Bravo", action = "a"), // "Name Bravo · Name a"
        )
        val s = calc(entries, activitySort = ActivitySort.NAME)
        assertEquals(listOf("x", "y", "z", "act-Bravo-a"), s.activities.map { it.activityId })
    }

    @Test
    fun `entry sorts with tie-breaks`() {
        val t = daysAgo(2)
        val entries = listOf(e("b", t), e("a", t), e("c", daysAgo(1)), e("z", daysAgo(3)))
        assertEquals(listOf("c", "a", "b", "z"), ids(calc(entries, entrySort = EntrySort.NEWEST)))
        assertEquals(listOf("z", "a", "b", "c"), ids(calc(entries, entrySort = EntrySort.OLDEST)))
    }

    @Test
    fun `calculation is deterministic regardless of input order`() {
        val a = calc(sortEntries, groupBySubject = true)
        val b = calc(sortEntries.reversed(), groupBySubject = true)
        assertEquals(a, b)
    }

    // ---- subject roll-up ----

    @Test
    fun `group by subject rolls up kinds and untagged by activity name`() {
        val entries = listOf(
            e("1", daysAgo(1), subject = "furnace", action = "change"),
            e("2", daysAgo(2), subject = "furnace", action = "change"),
            e("3", daysAgo(3), subject = "furnace", action = "clean"),
            e("4", daysAgo(4), subject = "car", action = "wash"),
            e("5", daysAgo(5), subject = null, action = null, activityId = "L1", activityName = "Walked dog"),
            e("6", daysAgo(6), subject = null, action = null, activityId = "L2", activityName = "walked dog"),
        )
        assertTrue(calc(entries).subjectGroups.isEmpty())
        val s = calc(entries, groupBySubject = true)
        assertEquals(3, s.subjectGroups.size)
        val furnace = s.subjectGroups[0]
        assertEquals("furnace", furnace.subjectId)
        assertEquals("Name furnace", furnace.subjectName)
        assertEquals(3, furnace.countInPeriod)
        assertEquals(2, furnace.kinds)
        assertEquals(daysAgo(1), furnace.lastTime)
        val legacy = s.subjectGroups[1]
        assertNull(legacy.subjectId)
        assertEquals("Walked dog", legacy.subjectName)
        assertEquals(2, legacy.countInPeriod)
        assertEquals(2, legacy.kinds)
        assertEquals("car", s.subjectGroups[2].subjectId)

        val byLast = calc(entries, groupBySubject = true, activitySort = ActivitySort.LONGEST_SINCE)
        assertEquals(listOf(null, "car", "furnace"), byLast.subjectGroups.map { it.subjectId })
        val byName = calc(entries, groupBySubject = true, activitySort = ActivitySort.NAME)
        assertEquals(listOf("car", "furnace", null), byName.subjectGroups.map { it.subjectId })
    }

    // ---- untagged rows ----

    @Test
    fun `untagged rows count as their own activity and never match tag filters`() {
        val entries = listOf(
            e("u1", daysAgo(1), subject = null, action = null, activityId = "L", activityName = "Old thing"),
            e("u2", daysAgo(2), subject = null, action = null, activityId = "L", activityName = "Old thing"),
            e("t", daysAgo(3)),
        )
        val all = calc(entries)
        assertEquals(2, all.distinctActivities)
        val legacyRow = all.activities.first { it.activityId == "L" }
        assertEquals(2, legacyRow.countInPeriod)
        assertNull(legacyRow.subjectId)
        assertEquals(listOf("t"), ids(calc(entries, ExploreFilter(subjectId = "furnace"))))
        assertEquals(listOf("t"), ids(calc(entries, ExploreFilter(actionId = "change"))))
    }

    // ---- word search ----

    @Test
    fun `word search over raw text names and aliases case-insensitively`() {
        val entries = listOf(
            e("raw", daysAgo(1), subject = "x1", action = "y1", activityName = "n1", raw = "Changed the FILTER today"),
            e("act", daysAgo(1), subject = null, action = null, activityName = "Filter swap"),
            e("subj", daysAgo(1), subject = "filterbox", action = "y2", activityName = "n2"),
            e("sAlias", daysAgo(1), subject = "x3", action = "y3", activityName = "n3", subjectAliases = listOf("Air FILTER")),
            e("aAlias", daysAgo(1), subject = "x4", action = "y4", activityName = "n4", actionAliases = listOf("refilter")),
            e("none", daysAgo(1), subject = "x5", action = "y5", activityName = "n5", raw = "nothing here"),
        )
        val s = calc(entries, ExploreFilter(words = "  fIlTeR "))
        assertEquals(setOf("raw", "act", "subj", "sAlias", "aAlias"), ids(s).toSet())
        assertEquals(ScopeKind.MANY, s.scopeKind)
        assertEquals(6, calc(entries, ExploreFilter(words = "   ")).entriesInPeriod)
        // action name match ("Name y5")
        assertEquals(listOf("none"), ids(calc(entries, ExploreFilter(words = "name Y5"))))
    }

    @Test
    fun `word filter combines with tag filter`() {
        val entries = listOf(
            e("a", daysAgo(1), raw = "blue filter"),
            e("b", daysAgo(1), raw = "red filter"),
            e("c", daysAgo(1), subject = "car", action = "wash", raw = "blue car"),
        )
        assertEquals(listOf("a"), ids(calc(entries, ExploreFilter(subjectId = "furnace", words = "blue"))))
    }

    // ---- chart ----

    @Test
    fun `bucket size thresholds`() {
        val entries = listOf(e("a", daysAgo(0)))
        assertEquals(BucketSize.DAY, calc(entries, custom(today.minusDays(30), today)).chart.bucketSize) // 31 days
        assertEquals(31, calc(entries, custom(today.minusDays(30), today)).chart.buckets.size)
        assertEquals(BucketSize.WEEK, calc(entries, custom(today.minusDays(31), today)).chart.bucketSize) // 32
        assertEquals(BucketSize.WEEK, calc(entries, custom(today.minusDays(370), today)).chart.bucketSize) // 371
        assertEquals(BucketSize.MONTH, calc(entries, custom(today.minusDays(371), today)).chart.bucketSize) // 372
    }

    @Test
    fun `week buckets honour Monday as first day`() {
        // Range Tue Nov 3 .. Sun Dec 6 (34 days) -> weeks start Mon Nov 2, 9, 16, 23, 30.
        val start = LocalDate.of(2026, 11, 3)
        val end = LocalDate.of(2026, 12, 6)
        val entries = listOf(
            e("outside", at(LocalDate.of(2026, 11, 2))), // Monday, same week as start but outside range
            e("tue", at(start)),
            e("sun", at(LocalDate.of(2026, 11, 8))),
            e("mon", at(LocalDate.of(2026, 11, 9))),
        )
        val chart = calc(entries, custom(start, end), firstDayOfWeek = DayOfWeek.MONDAY, nowAt = at(end)).chart
        assertEquals(BucketSize.WEEK, chart.bucketSize)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30)),
            chart.buckets.map { it.start },
        )
        assertEquals(listOf(2, 1, 0, 0, 0), chart.buckets.map { it.count })
        assertEquals(1.5, chart.average)
    }

    @Test
    fun `week buckets honour Sunday as first day`() {
        val start = LocalDate.of(2026, 11, 3)
        val end = LocalDate.of(2026, 12, 6)
        val entries = listOf(
            e("tue", at(start)),
            e("sun", at(LocalDate.of(2026, 11, 8))),
            e("mon", at(LocalDate.of(2026, 11, 9))),
        )
        val chart = calc(entries, custom(start, end), firstDayOfWeek = DayOfWeek.SUNDAY, nowAt = at(end)).chart
        assertEquals(LocalDate.of(2026, 11, 1), chart.buckets.first().start)
        assertEquals(LocalDate.of(2026, 12, 6), chart.buckets.last().start)
        assertEquals(listOf(1, 2, 0, 0, 0, 0), chart.buckets.map { it.count })
        assertTrue(chart.buckets.all { it.start.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun `month buckets start on the first and cover the range`() {
        val start = LocalDate.of(2025, 1, 15)
        val end = LocalDate.of(2026, 3, 10)
        val entries = listOf(e("a", at(LocalDate.of(2025, 1, 20))), e("b", at(LocalDate.of(2026, 3, 1))), e("c", at(LocalDate.of(2026, 3, 9))))
        val chart = calc(entries, custom(start, end), nowAt = at(end)).chart
        assertEquals(BucketSize.MONTH, chart.bucketSize)
        assertEquals(15, chart.buckets.size)
        assertEquals(LocalDate.of(2025, 1, 1), chart.buckets.first().start)
        assertEquals(LocalDate.of(2026, 3, 1), chart.buckets.last().start)
        assertEquals(1, chart.buckets.first().count)
        assertEquals(2, chart.buckets.last().count)
        assertEquals(1.5, chart.average)
    }

    @Test
    fun `average over non-empty buckets and null when empty`() {
        val entries = listOf(e("a", daysAgo(1)), e("b", daysAgo(1)), e("c", daysAgo(1)), e("d", daysAgo(3)))
        val chart = calc(entries, preset(DateRangePreset.LAST_7_DAYS)).chart
        assertEquals(BucketSize.DAY, chart.bucketSize)
        assertEquals(7, chart.buckets.size)
        assertEquals(today.minusDays(6), chart.buckets.first().start)
        assertEquals(2.0, chart.average)
        assertNull(calc(emptyList(), preset(DateRangePreset.LAST_7_DAYS)).chart.average)
    }

    // ---- patterns ----

    @Test
    fun `date-only entries count in weekday but not part of day`() {
        val sunday = today
        val entries = listOf(
            e("exact", at(sunday, LocalTime.of(9, 0))),
            e("dateOnly", at(sunday, LocalTime.MIDNIGHT), precision = TimePrecision.DATE_ONLY),
            e("approx", at(sunday.minusDays(6), LocalTime.of(14, 0)), precision = TimePrecision.APPROXIMATE), // Monday
        )
        val p = calc(entries, preset(DateRangePreset.LAST_7_DAYS)).patterns
        assertEquals(DayOfWeek.MONDAY, p.byWeekday.first().first)
        assertEquals(7, p.byWeekday.size)
        assertEquals(1, p.byWeekday.first { it.first == DayOfWeek.MONDAY }.second)
        assertEquals(2, p.byWeekday.first { it.first == DayOfWeek.SUNDAY }.second)
        assertEquals(1, p.byPartOfDay[PartOfDay.MORNING])
        assertEquals(1, p.byPartOfDay[PartOfDay.AFTERNOON])
        assertEquals(0, p.byPartOfDay[PartOfDay.NIGHT])
        assertEquals(1, p.partOfDayLeftOut)

        val sundayFirst = calc(entries, preset(DateRangePreset.LAST_7_DAYS), firstDayOfWeek = DayOfWeek.SUNDAY).patterns
        assertEquals(
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            sundayFirst.byWeekday.map { it.first },
        )
    }

    @Test
    fun `part of day boundaries and all keys present`() {
        val d = today.minusDays(1)
        val times = listOf(
            "04:59" to PartOfDay.NIGHT, "05:00" to PartOfDay.MORNING, "11:59" to PartOfDay.MORNING,
            "12:00" to PartOfDay.AFTERNOON, "16:59" to PartOfDay.AFTERNOON, "17:00" to PartOfDay.EVENING,
            "21:59" to PartOfDay.EVENING, "22:00" to PartOfDay.NIGHT,
        )
        val entries = times.mapIndexed { i, (t, _) -> e("e$i", at(d, LocalTime.parse(t))) }
        val p = calc(entries).patterns
        assertEquals(PartOfDay.entries.toSet(), p.byPartOfDay.keys)
        assertEquals(mapOf(PartOfDay.MORNING to 2, PartOfDay.AFTERNOON to 2, PartOfDay.EVENING to 2, PartOfDay.NIGHT to 2), p.byPartOfDay)
        assertEquals(0, p.partOfDayLeftOut)
        assertEquals(PartOfDay.entries.associateWith { 0 }, calc(emptyList()).patterns.byPartOfDay)
    }

    @Test
    fun `time mentioned sums durations per activity with left-out count`() {
        val entries = listOf(
            e("1", daysAgo(1), duration = 600),
            e("2", daysAgo(2), duration = 1200),
            e("3", daysAgo(3)),
            e("4", daysAgo(1), subject = "car", action = "wash", duration = 3600),
            e("5", daysAgo(1), subject = null, action = null),
            e("old", daysAgo(100), duration = 99_999),
        )
        val p = calc(entries).patterns
        assertEquals(
            listOf(
                TimeMentionedRow("act-car-wash", "wash car", "Name car", "Name wash", 3600, 1),
                TimeMentionedRow("act-furnace-change", "change furnace", "Name furnace", "Name change", 1800, 2),
            ),
            p.timeMentioned,
        )
        assertEquals(2, p.timeMentionedLeftOut)
    }
}
