# Design spec — Explore: Ask, search and dashboard in one screen (work item DH1, backlog item 19)

Status: **design v2, 2026-10-04. Not started.** v1 (a separate Dashboard
tab) was replaced the same day at the owner's direction: fold the dashboard
into the Ask tab so one screen covers asking, manual search/filtering and
metrics. No code has been changed.

## Plain-English summary

The Ask tab becomes one place to find out anything about what has been
logged. It has three layers, top to bottom:

1. **One search box** that takes either a question in plain words ("how many
   times did I mow in August?") or a few words to look up ("hot tub").
2. **A row of filters** — date range, subject, action — that everything below
   obeys. A question typed into the box simply *sets these filters* and they
   stay visible and editable, so the owner can always see what the app
   understood and fix it with a tap.
3. **Results for the current filters**: a short answer line, three numbers,
   one chart, then a switch between **Entries** (the matching list, sortable),
   **Activities** (most logged / recurring) and **Patterns** (when things
   happen, time mentioned).

With no filters set it opens on "last 30 days, everything" — which is the
dashboard. Filtering down to one activity turns the same space into that
activity's report (last time, usually every N days, every entry).

All numbers are counted by program logic from the saved history. The AI is
used only to turn a typed or spoken question into filter values; it never
produces an answer or a number (ADR-011).

## Why one screen works without getting busy

- **One shared scope.** Every part of the screen answers "what does the
  history say for *these* filters?". Nothing has its own separate date picker
  or search.
- **Questions become filters.** Asking is just a fast way to fill in the
  filter row; there is no separate "AI mode". The filter chips double as the
  explanation of what the question was read as — which also guards against
  AI misreads (the owner sees "Subject: Hot tub" and can change it).
- **Progressive disclosure.** Always visible: search box, filter row, answer
  line, three numbers. One chart. Everything else sits behind a three-way
  switch, so only one detailed view is on screen at a time.
- **The screen adapts to the scope** instead of showing every metric always:
  "everything" shows totals and top activities; one activity shows last
  time, typical gap and its entries.

## Research summary (what is useful for this kind of app)

Sources: comparable logging apps ("Days Since"-style interval trackers,
Track & Graph, Day History, LifeTracker), personal-visualization research
(Choe et al., *Characterizing Visualization Insights from Quantified-Selfers'
Personal Data Presentations*, VIS 2015; Choe et al., *Personal Visualization
and Personal Visual Analytics*, IEEE CG&A 2015) and recent work on streaks.

1. **"How long since" is the defining metric of occurrence logs.** Apps built
   around one-off events lead with days since last time and typical gap.
   Matches the household/maintenance use and the product spec's examples.
2. **People look for a few insight types**: trend over time, comparison
   between periods, distribution (when things happen), summary, outliers. One
   view per type with plain labels beats many charts.
3. **Common failures**: information overload, unexplained numbers,
   colour-only meaning, charts too small for a phone. Hence few sections, a
   sentence with each chart, and text alternatives.
4. **Streaks and scores backfire** (a broken streak makes people likelier to
   quit, more so when highlighted). Supports the existing rule: no streaks,
   scores or goals (PRODUCT_SPEC §8, UX_SPEC §15, UX_VISUAL_SPEC §7).

## Screen design

### 1. Search box (top, always visible)

- Placeholder: "Ask or search your history". Mic button inside it (spoken
  questions use the existing phone speech path).
- While typing, a suggestion list appears under the box:
  - matching **subjects** and **actions** (by name and "also called"
    aliases) — tapping one adds it as a filter chip, no AI;
  - **"Search your words for 'hot'"** — matches the entries' recorded words
    and tag names, no AI;
  - **"Ask: <text>"** — sends it to the question reader.
- Enter / search key: if the text looks like a question (existing rule,
  ADR-051: trailing "?" or a question word first) it is asked; otherwise it is
  a word search. No mode toggle.
- An asked question: the question reader (ADR-052, extended per backlog item
  18 to also return a date window and question type) produces words; program
  logic resolves them to tags and dates and **sets the filter chips**. The
  answer line then states the database fact. If the words resolve to nothing,
  the existing "not enough history" message shows and filters are unchanged.

### 2. Filter row (always visible, one line, scrolls sideways if needed)

- **Date chip**: Last 7 days · Last 30 days (default) · Last 12 months · All
  time · Custom range (date-range picker). Month names from a question
  ("in August") appear as a custom range labelled "August 2026".
- **Subject chip** and **Action chip**: searchable pickers over existing tags,
  single choice each in v1. Near matches from a question are shown as the
  chosen tag with "closest match" under the answer line (same rule as today's
  Ask).
- **Words chip** appears only when a word search is active ("words: hot").
- Each set chip has an ✕; a "Clear" link appears when anything is set.
- Filters persist while the app is open; they reset to the default when the
  app is restarted.

### 3. Answer line (shown after a question; otherwise a plain scope summary)

- After a question: the fact, database first (UX_SPEC §9 style), e.g. "You
  mowed the lawn 4 times in August 2026. Last: August 28." or "Last changed
  the furnace filter September 15 — 19 days ago."
- Without a question: "47 entries in the last 30 days."

### 4. Three numbers (react to the filters)

- **Scope = many activities**: Entries (with previous equal period as a
  plain number, never coloured), Days with an entry ("18 of 30"), Different
  activities.
- **Scope = one activity** (subject + action set, or a subject with one
  action): Times in period, Last time ("19 days ago"), Usually every ("30
  days", needs ≥3 entries all time; otherwise "—").
- **Scope = one subject**: Entries, Different actions, Last time.

### 5. One chart

Entries over time for the scope: bars per day (≤31 days), per week (≤1 year),
per month (longer). One sentence under it: "Average 2.6 per day on days you
logged something." *Show as list* toggle gives the same data as text rows.

### 6. Three-way switch: Entries · Activities · Patterns

- **Entries** (default after a question or filter): the matching entries,
  same row design and tap-to-open sheet as History. Sort: Newest (default) ·
  Oldest. Grouped under date headers.
- **Activities** (default with no filters): activities in scope with count
  and last time. Sort: Most logged (default) · Last done · Longest since ·
  Name. Toggle *Group by subject* rolls them up ("Hot tub — 12 entries, 3
  kinds"). Activities with ≥3 entries show "usually every N days". Tap sets
  that activity as the filter (drill down); Back restores the previous
  filters.
- **Patterns**: entries by weekday, by part of day (morning / afternoon /
  evening / night), and "time you mentioned" (total stated duration per
  activity). Each says how many entries it had to leave out (e.g. no time of
  day).

### Wireframe — no filters (opens like this)

```
 [ Ask or search your history            🎤 ]
 (Last 30 days ▾) (Subject ▾) (Action ▾)
 47 entries in the last 30 days.
 ┌──────────┐ ┌──────────┐ ┌──────────┐
 │ 47       │ │ 18 of 30 │ │ 14       │
 │ entries  │ │ days     │ │ activities│
 │ prev: 41 │ │          │ │          │
 └──────────┘ └──────────┘ └──────────┘
 ▁▃ ▅▂ ▁▇▃  ▂▅▁ ▃▂▆ ▁ ▃▄▂ ▅▁▂ ▃
 Average 2.6 per day on days you logged something
 [ Entries | •Activities | Patterns ]      Sort: Most logged ▾
 Hot tub · add chlorine     9   2 days ago   usually every 3 days
 Dogs · walk                8   today
 Lawn · mow                 4   6 days ago   usually every 7 days
```

### Wireframe — after "how many times did I mow in August?"

```
 [ how many times did I mow in August?   ✕ ]
 (August 2026 ✕) (Lawn ✕) (mow ✕)              Clear
 You mowed the lawn 4 times in August 2026. Last: August 28.
 ┌──────────┐ ┌──────────┐ ┌──────────┐
 │ 4 times  │ │ Last     │ │ Usually  │
 │          │ │ 37 d ago │ │ every 7 d│
 └──────────┘ └──────────┘ └──────────┘
 ▂ ▂ ▂ ▂   (per week)
 [ •Entries | Activities | Patterns ]       Sort: Newest ▾
 Aug 28  Lawn · mow   "Mowed the front and back"
 Aug 21  Lawn · mow   "Cut the grass"
 ...
```

## Non-goals

- No streaks, scores, goals, targets, badges, personal bests, completion
  percentages or green/red colouring. Numbers are facts, not grades.
- No reminders or notifications, including from "usually every N days".
- No calendar heatmap (UX_VISUAL_SPEC §7 excludes calendar grids; a year grid
  of filled/empty days reads as a streak display).
- No multi-select of tags in v1 (one subject, one action), no saved
  searches, no export.
- No watch changes. The watch's top-answer lookup is unaffected.
- The AI never computes counts, dates or answers; it only supplies words.

## Product-rule changes this needs (owner request = approval)

- UX_VISUAL_SPEC §4.5 says activity detail shows "no averages, streaks, or
  scores". This screen shows averages and typical gaps; streaks and scores
  stay excluded. Amend §4.5/§7 and record an ADR.
- UX_VISUAL_SPEC §3 D1 / §9 (Ask thread shape): Ask becomes this screen; the
  conversational thread is replaced by search box + answer line. The tab may
  be renamed (see question below). Record in an ADR amending ADR-019/ADR-051's
  screen notes.
- Brings forward part of ROADMAP "Post-MVP D — Analytics". Note it there.
- Absorbs backlog item 18 (counting and date-window questions): the question
  reader gains a date window and question type, and counting comes from this
  work's counting logic.

## Counting rules (deterministic, PC-testable)

- Count only accepted, visible entries (`activity_occurrences` with
  `visibility_status = 'ACTIVE'`). Removed entries and entries still waiting
  in Needs review are not counted. Corrections are already reflected in the
  occurrence's effective interpretation.
- Group by the entry's **occurred** time, not capture time, in the phone's
  current time zone. Day boundaries are local midnight; weeks start on the
  locale's first day of week.
- Entries whose time is only known to the day count for per-day/weekday views
  but are left out of "part of day", with a count of how many were left out.
- Activity identity = the subject + action pair (canonical activity). Subject
  roll-up groups by subject tag. Old untagged rows group under their activity
  name.
- Typical gap ("usually every") = median of consecutive gaps between entries
  of the same activity, over all time regardless of the date filter (so a
  short window cannot hide the habit). Needs ≥3 entries.
- "Previous period" = same length immediately before; not shown for All time
  or custom ranges over a year.
- Preset ranges include today ("30 days" = today and the 29 days before).
- Word search: case-insensitive substring over the entry's recorded words
  (raw capture text, read-only), subject/action names and their aliases.
  Searching never modifies anything.

## Architecture

- **core-domain `stats` package** (pure Kotlin): `ExploreFilter` (date range,
  subject id?, action id?, words?) + rows + zone + now → `ExploreSummary`
  (answer-line facts, the three numbers by scope kind, chart buckets,
  activity list, patterns, filtered entries). All rules above live here.
- **core-domain `lookup`**: the question service (ADR-051) returns a filter
  plus answer facts instead of only a ranked list. Last-time ranking by tag
  tier stays for the watch and for near matches.
- **core-data**: one read-only query returning entry rows (occurrence →
  canonical activity → subject/action, plus raw words for search) as a `Flow`,
  so the screen updates when entries are logged, corrected or removed.
  Personal-scale data is filtered and aggregated in Kotlin. No schema change.
- **core-ai**: question prompt gains date-window and question-type fields
  (new prompt version, item 18). Dates are still resolved by program logic
  (existing temporal resolver), never by the model.
- **app-phone**: `AskScreen`/`AskViewModel` rebuilt as the Explore screen.
  Charts drawn with Compose `Canvas` (decided: no chart library for a few bar
  charts).
- **Accessibility**: each chart has a one-sentence description and a list
  alternative; chips announce "Date range, last 30 days, double-tap to
  change"; sort and switch controls 48 dp; 200% text reflows (the three
  numbers stack vertically).
- **Privacy**: nothing leaves the phone except speech recognition as today;
  no logging of activity names, questions or searches (AGENTS.md #11).

## Testing

- JVM tests for every counting rule (fixed clock and zone, DST days,
  midnight, day-only precision, removed entries, median edges, previous
  period, word search incl. aliases).
- Question → filter mapping: replayed recordings for a new question corpus
  (subject/action/date window), gated like the capture corpus; Pixel session
  to record.
- DAO test (in-memory): only active rows, window bounds, joins.
- Compose UI tests: suggestion list, chip set/clear, scope switching of the
  three numbers, sort, drill-down and Back, list alternative.
- Device pass: Moto G for everything except asking; Pixel 10 Pro for asked
  questions.

## Proposed stages

1. Counting logic + filter model in core-domain, with tests.
2. Data query + repository, with tests.
3. Explore screen with manual filters, search suggestions, word search,
   numbers, chart and the three views (no AI changes; existing subject/action
   questions set chips).
4. Question reader extended with date windows and counting (item 18), new
   question corpus and recording on the Pixel.
5. Device pass, spec/ADR updates.

Stage 3 is already useful on its own: the dashboard and manual search ship
before any AI change.

## Open product questions

1. **Tab name.** "Ask" undersells it once it holds search and metrics.
   Recommended: **Explore**. Alternatives: Insights, Ask (keep).
2. **"Longer than usual" label** on activities whose time since last exceeds
   their usual gap (e.g. 41 days, usually every 30). Recommended: plain
   numbers only in v1, since it edges toward a reminder.
