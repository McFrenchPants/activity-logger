# Implementation plan — Explore (work item DH1, backlog item 19)

Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) (owner-approved 2026-10-04). Mockup:
[`mockup.html`](mockup.html). Branch `feature/explore`. Tracking:
[`PROGRESS.md`](PROGRESS.md); registered in `.sdlc/state.json` as DH1.

Stages follow the spec's "Stages" section. Stage 3 is shippable on its own and
is merged to `main` when it passes (standing owner rule).

## Plan-level developer decisions (2026-10-04)

- **P1 No Flow.** The repository has no reactive reads today (History and Log
  call `loadHistory()` on demand). Explore loads `loadExploreEntries()` on
  demand: when the screen is first shown, each time the Explore tab is
  re-entered / the activity resumes, and after nothing else (Explore never
  writes). Avoids building Room-Flow plumbing for one screen. Revisit if a
  second screen needs live updates.
- **P2 Questions without date words set "All time".** A "when did I last …"
  question must show the entry even if it is older than 30 days; so a question
  whose words carry no date window sets the date chip to All time. (Stage 3:
  every question; stage 4: only when no date window was extracted.)
- **P3 Stage 3 needs no core-domain lookup change.** `LookupOutcome.Answer`
  already carries `LookupTarget` (tag ids + exact flag); the Explore view model
  maps it to filter chips and computes the answer line through `stats`. The
  lookup service gains date windows and question kind only in stage 4.
- **P4 Presentation split.** core-domain returns structured values (e.g. a
  typical gap as unit + quantity, buckets with their local date, scope kind);
  all wording/localisation lives in app-phone strings.

## Stage 1 — Counting logic (core-domain)

### DH1.1 — `stats` package: filter model + calculator
Pure Kotlin in `core-domain/.../core/domain/stats/`. `ExploreFilter`,
`DateRangeSelection` (presets Last 7 / Last 30 / Last 12 months / All time,
custom inclusive local-date range, named month/year from a question),
`ExploreEntry`, `ExploreCalculator.calculate(entries, filter, zone, now,
firstDayOfWeek) → ExploreSummary` (scope kind, three numbers incl. previous
period, chart buckets with bucket size, activity rows with typical gap,
patterns, filtered entries), sort enums, `TypicalGap` value with display
unit. Every counting rule of the spec. Exhaustive JVM tests (fixed clock/zone,
DST, midnight edges, day-only precision, untagged rows, median odd/even,
rounding units, previous-period edges, scope kinds, sorts, word search incl.
aliases). Verifier: no (pure logic, not a floor/widen category) — orchestrator
spot-check + full core-domain test run.

## Stage 2 — Data read

### DH2.1 — `loadExploreEntries()` (repository, DAO, Room, fake)
New `TagRepository.loadExploreEntries(): List<ExploreEntry>` (active
occurrences only; occurrence → canonical activity → subject/action left joins
→ raw capture text; aliases of the subject/action tags for word search), Room
implementation, in-memory fake in core-testing plus any core-domain test fakes
that implement `TagRepository`. In-memory Room DAO tests. No schema change, no
migration. Verifier: yes (persistence floor).

## Stage 3 — Explore screen (shippable)

### DH3.1 — Explore view model + state (phone, no UI)
Rename `ui/ask` → `ui/explore` (`ExploreViewModel`, `ExploreUiState`,
factory). Filter state, suggestions (subjects/actions by name + alias,
prefix-then-substring; "Search your words"; "Ask"), Enter routing via
`QuestionDetector`, asked question → existing `LookupService.ask` → chips
(P2, P3) + answer line facts, word search, Clear, default view choice, sorts,
group-by-subject, drill-down with a back stack, reload (P1), mic flow carried
over from `AskViewModel`. Questions/search text never persisted or logged.
Verifier: yes (`natural_language_query_execution` — question → filter path).

### DH3.2 — Explore screen UI + tab rename
Compose UI per spec §1–6 and empty states; Canvas bar chart with list
alternative and content description; nav `ASK` → `EXPLORE`, string
`nav_explore`, icon; navigation test. Robolectric Compose tests. Verifier: no
(UI) — orchestrator rerun + device look.

### DH3.3 — Device check (Moto G 2025, no Gemini Nano)
Orchestrator installs and drives: manual filters, word search, numbers, chart,
three views, drill-down/Back, empty states, AI-unavailable message for a
question. Fixes as needed. Then merge stage 3 to `main`, push, install.

## Stage 4 — Counting and date-window questions (absorbs backlog 18)

### DH4.1 — Temporal ranges
`TemporalResolver` (or a sibling `TemporalRangeResolver`) resolves range words
("in August", "this year", "last month", "last week", "this month", "since
June", "in 2025") to a `DateRangeSelection`. Pure, tested. Verifier: no.

### DH4.2 — Question reader q2 (core-ai) + domain seam
`QuestionCandidate` gains `dateWindow: String?` and `kind` (LAST_TIME / COUNT /
HOW_OFTEN / LIST / unknown). Prompt q2, schema version bump, drift-guard test,
ADR-052 rules. Verifier: yes (`ai_output_validation_and_persistence`,
`natural_language_query_execution`).

### DH4.3 — Lookup → Explore question result
`LookupService` returns filter + kind + facts (via `stats`); view model uses it
for chips (date window or All time) and the count / how-often answer lines.
Verifier: yes (`natural_language_query_execution`).

### DH4.4 — Question corpus + replay gate
Recorded question corpus (spec "Testing"), JVM replay through resolver +
temporal logic, gated like the capture corpus; recorder for the Pixel.
Recording needs the Pixel 10 Pro (one short session). Verifier: yes.

## Stage 5 — Finish

### DH5.1 — Device pass + docs
Pixel 10 Pro: asked questions typed and **by voice** (needs the owner to
speak). Docs per spec "Docs to update" (UX_VISUAL_SPEC, UX_SPEC §9, ADR for
Explore + ADR-051/052 amendments, ROADMAP, REQUIREMENTS QRY-003,
PROJECT_STATUS, BACKLOG item 19). Owner report.
