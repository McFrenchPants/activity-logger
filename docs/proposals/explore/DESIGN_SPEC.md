# Design spec — Explore: Ask, search and dashboard in one screen (work item DH1, backlog item 19)

Status: **design approved by the owner 2026-10-04; ready for implementation
planning. No code has been changed.** This document is meant to be
self-contained: a fresh session should be able to plan and build from it plus
the project's standing docs (AGENTS.md and the docs it lists).

History of this design (all 2026-10-04):
- v1: a separate Dashboard tab (owner asked for a metrics/reporting page).
- v2: owner chose to fold the dashboard into the Ask tab — one screen for
  asking, manual search/filtering and metrics.
- v2 approved: tab named **Explore**; the **"usually every N days"**
  indicator is in; no separate "longer than usual" label (see Decisions).

Mockup: [`mockup.html`](mockup.html) (open in a browser; two states of the
screen — default overview and after a question).

## Plain-English summary

The Ask tab becomes **Explore**: one place to find out anything about what
has been logged. Three layers, top to bottom:

1. **One search box** that takes either a question in plain words ("how many
   times did I mow in August?") or a few words to look up ("hot tub").
2. **A row of filters** — date range, subject, action — that everything below
   obeys. A question typed into the box *sets these filters*, and they stay
   visible and editable, so the owner can always see what the app understood
   and fix it with a tap.
3. **Results for the current filters**: an answer line, three numbers, one
   chart, then a switch between **Entries** (matching list, sortable),
   **Activities** (most logged / recurring, "usually every N days") and
   **Patterns** (when things happen, time mentioned).

With no filters it opens on "last 30 days, everything" — the dashboard.
Filtering to one activity turns the same space into that activity's report.

All numbers are counted by program logic from the saved history. The AI only
turns a typed or spoken question into filter values; it never produces an
answer, count or date (ADR-011, ADR-038, ADR-051).

## Decisions

| # | Decision | By |
|---|---|---|
| D1 | Combine Ask + search + dashboard in one screen replacing the Ask tab; app keeps four tabs (Log · History · Tags · Explore). | Owner |
| D2 | Tab and screen are named **Explore**. | Owner |
| D3 | Show **"usually every N days"** (typical gap) on activities with ≥3 entries, and as one of the three numbers when one activity is in scope. Plain text, neutral colour. | Owner |
| D4 | No "longer than usual" / overdue label or colouring in this version (it edges toward a reminder). The owner can compare "37 days ago" with "usually every 30 days" themselves. Revisit only if the owner asks. | Developer, per owner's answer to the label question |
| D5 | No streaks, scores, goals, badges, green/red good-bad colouring, reminders or notifications. | Product rules (PRODUCT_SPEC §8, UX_SPEC §15, UX_VISUAL_SPEC §7) |
| D6 | Averages and typical gaps are allowed on Explore (previously excluded on activity detail, UX_VISUAL_SPEC §4.5). | Owner request = approval of the design change |
| D7 | Charts drawn with Compose `Canvas`; no chart library. | Developer |
| D8 | Counting is done in Kotlin over a read of entry rows (personal-scale data), not in SQL, so all rules live in one PC-testable place. | Developer |
| D9 | Backlog item 18 (counting and date-window questions) is absorbed into this work. | Developer, owner informed |

## Why one screen works without getting busy

- **One shared scope.** Every part of the screen answers "what does the
  history say for *these* filters?". Nothing has its own date picker or search.
- **Questions become filters.** Asking is a fast way to fill the filter row;
  there is no separate "AI mode". The chips double as the explanation of what
  the question was read as, which also exposes AI misreads.
- **Progressive disclosure.** Always visible: search box, filter row, answer
  line, three numbers, one chart. Detail sits behind a three-way switch, so
  only one detailed view shows at a time.
- **The screen adapts to the scope** rather than showing every metric always.

## Research summary (why these metrics)

Sources: comparable logging apps ("Days Since"-style interval trackers,
Track & Graph, Day History, LifeTracker); Choe et al., *Characterizing
Visualization Insights from Quantified-Selfers' Personal Data Presentations*
(VIS 2015) and *Personal Visualization and Personal Visual Analytics* (IEEE
CG&A 2015); recent field studies on streak mechanics.

1. **"How long since" and "typical gap" define occurrence logs** — apps for
   one-off events lead with them; they match the household/maintenance use.
2. **Insight types people look for**: trend over time, comparison between
   periods, distribution (when things happen), summary, outliers. One view
   per type with plain labels beats many charts.
3. **Common failures**: overload, unexplained numbers, colour-only meaning,
   charts too small for a phone → few sections, a sentence with each chart,
   text alternatives.
4. **Streaks/scores backfire** (a broken streak makes people likelier to
   quit, more so when highlighted) → supports D5.

## Screen design

### 1. Search box (top, always visible)

- Placeholder: "Ask or search your history". Mic button inside the box (keep
  the current Ask screen's mic and permission flow, which mirrors the Log
  screen's; spoken questions use the existing phone speech path).
- While typing, a suggestion list appears under the box:
  - matching **subjects** and **actions** (by display name and "also called"
    aliases, case-insensitive, prefix-then-substring) — tapping one sets that
    filter chip; no AI;
  - **"Search your words for '<text>'"** — sets a Words filter; no AI;
  - **"Ask: <text>"** — sends it to the question reader.
- Enter / search key: if the text looks like a question (existing detector,
  ADR-051: trailing "?" or a first word from the provisional list) it is
  asked; otherwise it becomes a word search. No mode toggle.
- An asked question: the question reader returns words (subject, action, and
  — after stage 4 — date-window words and question kind); program logic
  resolves them to tags (existing resolver) and a date range (existing
  temporal resolver), then **sets the chips** and shows the answer line.
  - Words resolve to nothing / no matching entries → existing "There isn't
    enough history yet to answer that." message; filters unchanged.
  - Named subject that matches no existing tag → same message (ADR-051
    TG4.7 amendment stays).
  - Statement instead of question → existing copy "That sounds like something
    you did, not a question." with the pointer to the Log tab.
  - Model unavailable / busy / failed → existing Ask messages; manual
    filtering still works (it needs no AI).
- The question text is never saved or logged (as today).

### 2. Filter row (always visible, one line, scrolls sideways if needed)

- **Date chip**: Last 7 days · Last 30 days (default) · Last 12 months · All
  time · Custom range (M3 date-range picker). A month from a question ("in
  August") becomes a custom range labelled "August 2026"; "this year" →
  "2026"; etc.
- **Subject chip** and **Action chip**: searchable single-choice pickers over
  existing tags. A near (closest-candidate) match from a question is set as
  that tag, and the answer line adds the existing closest-match note.
- **Words chip** appears only when a word search is active ("Words: hot").
- Each set chip has an ✕; a "Clear" action appears when anything differs from
  the default. Clear restores Last 30 days, no tags, no words.
- Filters survive tab switches and rotation while the app runs; they reset to
  default on app restart. No saved searches.

### 3. Answer line

- After a question: the database fact first (UX_SPEC §9 style). Examples:
  - count: "You mowed the lawn 4 times in August 2026. Last: August 28."
  - last time: "Last changed the furnace filter September 15 — 19 days ago."
  - how often: "You usually change the oil every 4 months. Last: June 2."
- Without a question: a plain scope summary, e.g. "47 entries in the last 30
  days." / "Hot tub: 12 entries in the last 30 days."
- Polite live region for screen readers.

### 4. Three numbers (react to the filters)

| Scope | Number 1 | Number 2 | Number 3 |
|---|---|---|---|
| Many activities (no tag filter, or words only) | Entries (+ "previous: 41" plain text) | Days with an entry ("18 of 30") | Different activities |
| One subject (subject set, action not) | Entries | Different actions | Last time ("2 days ago") |
| One action across subjects (action set, subject not) | Entries | Different subjects | Last time |
| One activity (subject + action set) | Times in period | Last time ("37 days ago") | Usually every ("7 days"; "—" if <3 entries all time) |

"Previous" is the same-length period immediately before; hidden for All time
and for custom ranges longer than a year. Never coloured up/down.

### 5. One chart — entries over time

- Bars per day (range ≤31 days), per week (≤ about a year), per month
  (longer, incl. All time).
- One sentence under it, e.g. "Average 2.6 per day on days you logged
  something" (per-week / per-month wording for those bucket sizes).
- *Show as list* toggle renders the same buckets as text rows.
- Content description summarises it: "Entries per day, last 30 days. Most: 6
  on September 21. 12 days with no entries."

### 6. Three-way switch: Entries · Activities · Patterns

- Default view: **Activities** when no tag/words filter is set; **Entries**
  once a question is asked or a tag/words filter is set.
- **Entries**: matching entries using the existing History row
  (`ui/components/HistoryRow.kt`) and tap-to-open occurrence sheet, grouped
  under date headers. Sort: Newest (default) · Oldest.
- **Activities**: activities (subject + action pairs) in scope with count in
  period and last time; "usually every N days" under the name when ≥3 entries
  all time. Sort: Most logged (default) · Last done · Longest since · Name.
  *Group by subject* toggle rolls rows up ("Hot tub — 12 entries, 3 kinds").
  Tapping a row sets it as the filter (drill down); system Back restores the
  previous filters before leaving the tab.
- **Patterns**: entries by weekday (7 bars), by part of day (morning 05–12,
  afternoon 12–17, evening 17–22, night 22–05), and "Time you mentioned"
  (total stated duration per activity, with entry count). Each section says
  how many entries it left out ("4 entries without a time of day not shown").

### Empty states

- Nothing logged at all: UX_SPEC §13 History empty copy ("No activities
  logged yet. Tap the microphone and say what you just did.").
- Nothing in the current filters: "Nothing logged for these filters." plus a
  *Clear filters* button.

### Wireframes

Default (no filters):

```
 [ Ask or search your history            🎤 ]
 (Last 30 days ▾) (Subject ▾) (Action ▾)
 47 entries in the last 30 days.
 [ 47 entries · prev 41 ] [ 18 of 30 days ] [ 14 activities ]
 ▁▃ ▅▂ ▁▇▃  ▂▅▁ ▃▂▆ ▁ ▃▄▂ ▅▁▂ ▃
 Average 2.6 per day on days you logged something
 [ Entries | •Activities | Patterns ]      Sort: Most logged ▾
 Hot tub · add chlorine     9   2 days ago   usually every 3 days
 Dogs · walk                8   today
 Lawn · mow                 4   6 days ago   usually every 7 days
```

After "how many times did I mow in August?":

```
 [ how many times did I mow in August?   ✕ ]
 (August 2026 ✕) (Lawn ✕) (mow ✕)              Clear
 You mowed the lawn 4 times in August 2026. Last: August 28.
 [ 4 times ] [ 37 days since last ] [ usually every 7 days ]
 ▂ ▂ ▂ ▂   (per week)
 [ •Entries | Activities | Patterns ]       Sort: Newest ▾
 Aug 28  Lawn · mow   "Mowed the front and back"
 Aug 21  Lawn · mow   "Cut the grass"
```

## Counting rules (deterministic, PC-testable)

- Count only accepted, visible entries (`activity_occurrences.visibility_status
  = 'ACTIVE'`). Removed entries and captures still in Needs review are not
  counted. Corrections are already reflected in the occurrence row.
- Group by **occurred** time (when it happened), not capture time, in the
  phone's current time zone. Day boundaries are local midnight; weeks start on
  the locale's first day of week.
- Day-only time precision: counted in per-day/weekday views, left out of part
  of day (with the left-out count shown).
- Activity identity = subject + action pair (canonical activity). Subject
  roll-up groups by subject tag. Old untagged rows (pre-tag v3 path, null
  subject/action) group under their canonical activity name.
- Typical gap ("usually every") = median of consecutive gaps between entries
  of the same activity over **all time**, regardless of the date filter, so a
  short window cannot hide the pattern. Needs ≥3 entries (≥2 gaps). Even gap
  count → mean of the two middle gaps. Display rounding: < 2 days in hours,
  < 60 days in days, < 2 years in months (30.44-day months), else years;
  "every day" / "every week" wording for 1 and 7 days.
- "Days since / last time" uses the newest entry over all time for that
  scope, not only within the filter (so "last time" is always truthful);
  count numbers are within the filter.
- Preset ranges include today ("Last 30 days" = today and the 29 days
  before). Custom range bounds are inclusive local dates.
- Word search: case-insensitive substring over the entry's recorded words
  (raw capture text, read-only), subject/action names and aliases. Read-only;
  nothing is modified.

## Architecture

### New / changed code

- **core-domain, new package `stats`** (pure Kotlin): `ExploreFilter` (date
  range, subjectId?, actionId?, words?), `ExploreEntry` (occurrence id,
  occurred-at, time precision, duration seconds, canonical activity id and
  name, subject id/name?, action id/name?, raw words), and
  `ExploreCalculator(rows, filter, zone, now) → ExploreSummary` (scope kind,
  three numbers, chart buckets, activity rows with typical gap, patterns,
  filtered + sorted entries). All counting rules above live here.
- **core-domain `lookup`**: extend `LookupService` so a question returns
  filter values plus answer facts (an `ExploreFilter` + question kind),
  computed via `stats`. Keep the existing tier ranking (`HistoryLookup`) for
  the watch's top answer and for the closest-match note.
- **core-domain `repository`**: a read method returning `ExploreEntry` rows,
  ideally as a `Flow` so the screen refreshes when entries are logged,
  corrected or removed. The existing `TagRepository.loadLookupEntries()`
  returns `LookupEntry` (no time precision, no raw words, no untagged rows),
  so a new read is needed rather than reuse.
- **core-data**: the DAO query behind it (occurrence → canonical activity →
  subject/action left joins → raw capture text; active rows only) in
  `RoomActivityRepository`/its DAOs; in-memory fake in
  `core-testing/InMemoryActivityRepository.kt`. No schema change, no
  migration.
- **core-ai (stage 4)**: `GeminiNanoQuestionExtractor` prompt gains
  date-window words and a question kind (last time / count / how often /
  list). New prompt version (q2), response schema version bump, drift-guard
  test updated (ADR-052 rules: words only, untrusted output, length caps,
  same readiness/failure behaviour, no logging of question text). Dates are
  resolved by `core-domain/temporal/TemporalResolver.kt` (extended with
  ranges such as "in August", "this year", "last month"), never by the model.
  Also wrapped by `app-phone/pipeline/BusyRetryQuestionExtractor.kt` as today.
- **app-phone**: rebuild `ui/ask/` (`AskScreen`, `AskViewModel`,
  `AskUiState`, `AskAnswerFormatting`, factory) as the Explore screen —
  rename the package/classes to `explore` as part of the work. Nav: the
  `ASK` entry in `ui/navigation/LedgerNavigation.kt` `TopLevelDestination`
  becomes `EXPLORE`; string `nav_ask` ("Ask") → `nav_explore` ("Explore");
  icon may stay `ic_nav_ask` or get an outline "search/insights" icon in the
  same style. Update `MainActivityNavigationTest`.
- **Accessibility**: chart descriptions + list alternative; chips announce
  e.g. "Date range, last 30 days, double-tap to change"; 48 dp targets for
  chips, sort and switch; 200% text reflows (three numbers stack vertically);
  state never colour-only.
- **Privacy**: nothing new leaves the phone (speech recognition as today);
  no logging of activity names, questions or search text (AGENTS.md #11).

### Rules that still apply

- Phone owns the database and interpretation; the watch is unaffected.
- Model output is untrusted; the model never emits SQL, counts or dates
  (verification widen tier `natural_language_query_execution` — the verifier
  must check stage 4 and the lookup changes).
- Raw capture text is only read, never modified.
- ML Kit stays inside `core-ai` (ADR-023).

## Docs to update during the work

- `docs/UX_VISUAL_SPEC.md`: §3 D1 (fourth tab is Explore), §4.5 / §7
  (averages and typical gaps allowed on Explore; streaks/scores still
  excluded), the Ask thread section (replaced by Explore), new section for
  Explore. `docs/UX_SPEC.md` §9 Ask History: point to Explore.
- `docs/DECISIONS.md`: new ADR for Explore (D1–D9 + counting rules); amend
  ADR-051 (question returns filters; counting/date windows now answered) and
  ADR-052 (prompt q2) when stage 4 lands; note ADR-019's tab change.
- `docs/ROADMAP.md` "Post-MVP D — Analytics": note what Explore delivered.
- `docs/REQUIREMENTS.md` QRY-003 intents: mark count/occurrences in range/
  interval summary as delivered by Explore.
- `BACKLOG.md` item 19 status; `docs/PROJECT_STATUS.md`.

## Testing

- JVM unit tests for every counting rule: fixed clock and zone, DST spring and
  fall days, midnight edges, day-only precision, removed entries excluded,
  untagged rows, median with odd/even gaps, typical-gap rounding words,
  previous-period edges, scope kinds, sorts, word search incl. aliases.
- DAO test (in-memory Room): only active rows, joins, untagged rows, raw text.
- Question → filter: a **recorded question corpus** (subject / action / date
  window / kind, incl. "how many times did I mow in August?", "how often do I
  change the oil?", "when did I last clean the gutters?" with no gutters
  logged) replayed on the PC through the real resolver and temporal logic,
  gated like the capture corpus (ADR record-and-replay). Recorded on the
  Pixel 10 Pro in one short session. This also covers item 18's missing
  regression guard for the question reader.
- Compose UI tests: suggestion list, chip set/clear, Clear, scope switching
  of the three numbers, default view choice, sorts, drill-down and Back,
  list alternative, empty states.
- Device pass: Moto G 2025 (no Gemini Nano) for everything except asking —
  confirms manual filters work with no AI; Pixel 10 Pro for asked questions,
  **including asking by voice** (never yet tried on a device; carried over
  from item 18).
- No change to the capture prompt, so the capture semantic-regression
  baseline must stay green and untouched.

## Stages

1. `stats` counting logic + filter model in core-domain, with tests.
2. Repository read (+ Flow), DAO query, in-memory fake, with tests.
3. Explore screen with manual filters, suggestions, word search, numbers,
   chart and the three views; tab renamed; existing subject/action questions
   set the chips (no AI change). **Shippable on its own.**
4. Question reader q2: date windows + question kind, temporal range
   resolution, answer lines for count / how often; question corpus recorded
   on the Pixel and replay gate.
5. Device pass (Moto G, Pixel incl. voice), doc/ADR updates, owner report.

## Out of scope

- Calendar heatmap (UX_VISUAL_SPEC §7 excludes calendar grids; a year grid of
  filled/empty days reads as a streak display).
- Multi-select tags, saved searches, export/share, widgets.
- Any watch change.
- "Longer than usual" / overdue labels (D4).
