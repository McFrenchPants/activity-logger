# Design spec — Dashboard (work item DH1, backlog item 19)

Status: **initial design, 2026-10-04. Not started; awaiting owner sign-off on
the two product questions at the end.** No code has been changed.

## Plain-English summary

A phone screen that shows, at a glance, what the owner has logged: how many
entries per day over a chosen period, which activities come up most, how often
recurring things get done and how long since the last time, and when in the
week/day things tend to happen. Every number is counted by program logic from
the saved history. The AI is not involved at all, so the screen is instant,
works offline and is always exactly right about what was logged.

It complements Ask (which answers one question at a time) by showing the whole
picture without having to know what to ask.

## Research summary (what is useful for this kind of app)

Sources looked at: comparable logging apps ("Days Since"-style interval
trackers, Track & Graph, Day History, LifeTracker), personal-visualization
research (Choe et al., *Characterizing Visualization Insights from
Quantified-Selfers' Personal Data Presentations*, VIS 2015; Choe et al.,
*Personal Visualization and Personal Visual Analytics*, IEEE CG&A 2015) and
recent work on streak mechanics.

1. **"How long since" is the defining metric of occurrence logs.** Apps built
   around one-off events (as opposed to habits) lead with *days since last
   time* and *typical gap between times*. That matches this app's household /
   maintenance use (furnace filter, oil change, hot tub chemicals) and the
   product spec's own example questions.
2. **People look for a small set of insight types** in their own data: trend
   over time, comparison between periods, distribution (when things happen),
   data summary, and outliers. A good personal dashboard gives one view per
   type, with plain labels, rather than many charts.
3. **Common failures** of self-tracking dashboards: information overload,
   unexplained numbers, colour-only meaning, and charts too small to read on a
   phone. Mitigation: few sections, a sentence under each chart, text
   alternatives for every chart.
4. **Streaks and scores backfire for many people.** Field studies show a
   broken streak makes people *less* likely to continue (the "what the hell"
   effect), more so when the app highlights the break. This independently
   supports the existing product rule: no streaks, no scores, no goals
   (PRODUCT_SPEC §8, UX_SPEC §15, UX_VISUAL_SPEC §7).

## Goals

- One new phone screen, **Dashboard**, with a period selector:
  **7 days · 30 days · 12 months · All time** (default 30 days).
- Sections, top to bottom (each hidden when it has nothing to show):
  1. **Summary tiles** — entries in period; days with at least one entry
     ("18 of 30 days"); different activities logged; previous equal period as
     a plain number ("previous 30 days: 41"), never coloured up/down.
  2. **Entries over time** — bar chart: per day (7/30 days), per week
     (12 months), per month (all time). One line of text under it: "Average
     2.1 entries per day on days you logged something".
  3. **Most logged** — top activities (subject + action, e.g. "Hot tub ·
     add chlorine") with count in period and last time. Toggle to group by
     **subject** ("Hot tub — 12 entries, 3 kinds") so groupings emerge from
     tags as decided for item 13. Tap opens the existing activity detail.
  4. **Recurring things** — activities logged at least 3 times (all time):
     last time, days since, typical gap (median of gaps between entries),
     ordered by days since. This is the "maintenance view".
  5. **When you log things** — entries by weekday (7 bars) and by part of day
     (morning / afternoon / evening / night).
  6. **Time spent** — total stated duration per activity, only for entries
     where a duration was said ("walked the dogs for 30 minutes"). Labelled
     "time you mentioned", since most entries have none.
- Reachable from the bottom navigation (see question 1).

## Non-goals

- No streaks, scores, goals, targets, badges, "personal bests", completion
  percentages, or green/red good-bad colouring. Numbers are facts, not grades.
- No reminders or notifications of any kind, including from "Recurring
  things" (see question 2 for the only related display).
- No calendar heatmap in this version: UX_VISUAL_SPEC §7 excludes calendar
  grids, and a year grid of filled/empty days reads as a streak display.
  Revisit only if the owner asks.
- No watch screen. The watch stays a capture device.
- No AI, no model call, no exporting/sharing of the dashboard.
- Not answering Ask counting questions — that is backlog item 18, which can
  later reuse this work's counting logic.

## Product-rule changes this needs (owner's request is the approval)

- UX_VISUAL_SPEC §4.5 says activity detail shows "no averages, streaks, or
  scores". The dashboard shows *averages and typical gaps*; streaks and scores
  stay excluded. Amend §4.5/§7 to say averages and typical gaps are allowed on
  the Dashboard (and may later be added to activity detail), and record an ADR.
- This brings forward part of ROADMAP "Post-MVP D — Analytics" (typical
  interval, frequency trends, summaries by period). Note it there.

## Counting rules (deterministic, PC-testable)

- Count only accepted, visible entries (`activity_occurrences` with
  `visibility_status = 'ACTIVE'`). Removed entries and entries still waiting
  in Needs review are not counted. Corrections are already reflected in the
  occurrence's effective interpretation.
- Group by the entry's **occurred** time (when the user said it happened), not
  capture time, in the phone's current time zone. Day boundaries are local
  midnight; weeks start on the locale's first day of week.
- Entries whose time is only known to the day count for per-day/weekday views
  but are left out of "part of day". Each view says how many were left out if
  any ("4 entries without a time of day not shown").
- Activity identity = the subject + action pair (canonical activity). Subject
  roll-up groups by subject tag. Entries on old single-activity rows without
  tags are grouped under their activity name.
- Typical gap = median of consecutive gaps between entries of the same
  activity (median, not mean, so one long pause does not distort it). Needs at
  least 3 entries (2 gaps). Same-day duplicates count as separate entries but
  produce a zero gap; median absorbs this.
- "Previous period" = the same length immediately before the selected one;
  not shown for "All time".
- Period boundaries are inclusive of today; "30 days" = today and the 29 days
  before it.

## Architecture

- **core-domain `stats` package** (new, pure Kotlin): input = a list of plain
  entry rows (occurrence id, occurred-at, time precision, duration, activity
  id, subject id/name, action id/name) + period + time zone + "now"; output =
  one `DashboardSummary` value. All rules above live here and are unit-tested
  on the PC with a fixed clock and zone, including DST transitions and
  midnight edges.
- **core-data**: one read-only DAO query returning those rows for a time
  window (joins occurrence → canonical activity → subject/action), exposed as
  a `Flow` so the screen updates when an entry is logged, corrected or
  removed. Personal-scale data (thousands of rows) is aggregated in Kotlin;
  no SQL-side aggregation is needed, which keeps the counting rules in one
  testable place. No schema change, no migration.
- **app-phone**: `DashboardViewModel` + `DashboardScreen` (Compose). Charts
  are drawn with Compose `Canvas` — no new chart library (decided: three
  simple bar charts do not justify a dependency). Bars use tabular figures for
  labels and the existing design tokens.
- **Accessibility**: every chart has a one-sentence content description
  ("Entries per day, last 30 days. Most: 6 on September 21. 12 days with no
  entries.") and a *Show as list* toggle that renders the same data as text
  rows. Bars are never colour-only; numbers reflow at 200% text.
- **Privacy**: nothing new leaves the phone; no logging of activity names or
  user words (AGENTS.md #11).

### Wireframe (30 days)

```
 Dashboard                                   [7d][30d][12m][All]
 ┌────────────┐ ┌────────────┐ ┌────────────┐
 │ 47 entries │ │ 18 of 30   │ │ 14         │
 │ prev 30: 41│ │ days logged│ │ activities │
 └────────────┘ └────────────┘ └────────────┘
 Entries per day
 ▁▃ ▅▂ ▁▇▃  ▂▅▁ ▃▂▆ ▁ ▃▄▂ ▅▁▂ ▃
 Average 2.6 per day on days you logged something     [Show as list]

 Most logged                         [Activities | Subjects]
 Hot tub · add chlorine        9   last: 2 days ago
 Dogs · walk                   8   last: today
 Lawn · mow                    4   last: 6 days ago

 Recurring things
 Furnace · change filter   last 41 days ago · usually every 30 days
 Tractor · change oil      last 3 months ago · usually every 4 months

 When you log things
 Mon ▃  Tue ▂  Wed ▂  Thu ▃  Fri ▂  Sat ▇  Sun ▅
 Morning ▅  Afternoon ▃  Evening ▆  Night ▁

 Time you mentioned
 Dogs · walk        4 h 10 min (8 entries)
```

Empty state (nothing logged in the period): "Nothing logged in the last 30
days." All time with no entries: UX_SPEC §13 History empty-state copy.

## Testing

- JVM unit tests for every counting rule (fixed clock and zone, DST spring and
  fall days, midnight, day-only precision, removed entries, median with even
  and odd gap counts, previous-period edges).
- DAO test with an in-memory database: only active entries returned, window
  bounds inclusive, tag names joined.
- Compose UI test: period switch, empty state, list toggle exposes the same
  numbers.
- Device pass on the Moto G (no AI needed — the dashboard does not use the
  model), using real history pulled from the Pixel if available.
- No semantic-regression impact: no prompt, model or matching change.

## Proposed stages

1. Counting logic in core-domain + tests (no UI).
2. DAO query + repository method + tests.
3. Dashboard screen, navigation entry, accessibility, UI tests.
4. Device pass, spec/ADR updates (UX_VISUAL_SPEC §4.5/§7 amendment, ROADMAP
   note, new ADR for the counting rules).

## Questions for the owner

1. **Where does it live?** Recommended: a fifth bottom tab, **Dashboard**,
   after Ask (Log · History · Tags · Ask · Dashboard). Five is the most the
   bottom bar allows, so a future screen would have to go elsewhere.
   Alternative: an icon in the History screen's top bar, keeping four tabs but
   making the dashboard one tap deeper.
2. **"Longer than usual" hint in Recurring things?** E.g. marking "Furnace ·
   change filter — 41 days, usually every 30" with a small *longer than usual*
   label. Useful for maintenance, but it edges toward a reminder, which the
   product deliberately avoids. Recommended: show the plain numbers only in
   the first version, and add the label later if the owner wants it.
