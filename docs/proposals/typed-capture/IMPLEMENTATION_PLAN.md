# Implementation plan — Typed capture and history screens (UI1)

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) (owner sign-off 2026-09-19).
Branch: `feature/typed-capture`. Work item `UI1` in `.sdlc/state.json`.

Flat task list; each task depends on the ones listed in its Notes.

## Orchestrator decisions (technical, recorded here)

- **No Flow in core-domain.** `core-domain` has no coroutines dependency and
  stays that way. New repository reads are `suspend` functions; screens
  reload after each write they make and on resume. Single-process app, every
  write comes from the UI, so nothing is missed.
- **Hiding is not a correction** (DATA_MODEL §corrections: corrections have no
  visibility columns). Undo is a new `ActivityRepository.hideOccurrence`
  write that only flips `visibility_status` ACTIVE → HIDDEN (and
  `updated_at`), idempotent. No schema change.
- **Suggestions on a needs-review capture** = the capture's latest
  interpretation's matched activity (if still ACTIVE), then the
  deterministic `CandidateSelector` over the raw text, de-duplicated, at most
  3. No model call. Computed in the phone app from repository reads + the
  domain selector; the selector is not re-implemented.
- **"Not categorized"** = a capture with no occurrence whose processing state
  is not NEEDS_REVIEW (FAILED_RETRYABLE, FAILED_FINAL, or stuck in
  CAPTURED / INTERPRETING after process death). **"Needs review"** =
  NEEDS_REVIEW with no occurrence. Both can be resolved by the same
  `ReviewResolutionService.resolve` path.
- **Change activity** (owner Q1) = `CorrectionService.correct` with
  `CorrectionRequest(activity = …)`, source USER. Only offered while the
  Saved card's window is open; later corrections are the next slice.
- **Libraries** (verified on Google Maven 2026-09-19): navigation-compose
  2.10.1 (type-safe routes via the existing kotlinx-serialization plugin),
  lifecycle-viewmodel-compose / lifecycle-runtime-compose 2.11.0 (latest
  stable). Compose UI tests on the JVM via the Robolectric already in the
  catalog (4.17) plus compose `ui-test-junit4` from the BOM.
- **Fonts** already downloaded by the orchestrator with owner permission:
  `app-phone/src/main/res/font/instrument_sans.ttf` (variable wdth/wght) and
  `source_serif_4_italic.ttf` (variable opsz/wght), OFL texts in
  `app-phone/licenses/fonts/`.
- **Wiring**: an `Application` subclass owns the single `CapturePipeline`
  (ADR-032, one per process) and builds `ReviewResolutionService` and
  `CorrectionService` from its repository and clock. ViewModels get these
  through a small hand-written factory.

## Tasks

### UI1.1 — Ledger reads and hide for the screens (verifier)

Scope: `core-domain` repository contract + types, `core-data` Room
implementation and DAO queries, `core-testing` `InMemoryActivityRepository`
parity, tests.

Add to `ActivityRepository`:
- `suspend fun loadHistory(): List<HistoryEntry>` — one entry per raw
  capture that has an ACTIVE-visibility occurrence, or has no occurrence at
  all. Captures whose only occurrence is HIDDEN are omitted. Entry carries:
  capture id, raw text, capture source, captured-at, zone, processing state;
  for an occurrence: occurrence id, activity id, activity display name,
  occurred-at, time precision, activity state; for a capture with no
  occurrence: the latest interpretation's matched activity id (nullable).
  Ordered newest first by occurred-at (occurrence) or captured-at (no
  occurrence), ties by capture id descending. Bounded query count (no
  per-row query).
- `suspend fun hideOccurrence(occurrenceId: String)` — ACTIVE → HIDDEN,
  sets updated_at from the repository clock; already HIDDEN is a no-op;
  unknown id throws `IllegalArgumentException` (existing error contract).
  Raw capture, interpretations and corrections untouched.

Acceptance:
1. Both functions implemented in Room and in-memory repositories with the
   same behaviour; a shared behaviour test (or mirrored tests) proves it.
2. Room tests: ordering, hidden omission, no-occurrence captures in each
   processing state, matched-activity id from the latest interpretation,
   occurrence moved by a correction shows the corrected activity name,
   hide idempotence, unknown id throws, hide never touches raw_captures or
   interpretations.
3. No schema change (schema JSON unchanged, `SchemaVersionConsistencyTest`
   green). If a query needs an index, stop and report instead.
4. The existing DAO write-surface guard still passes; the hide write lives
   with the other ledger writes, not as a free `@Update` on a read DAO.
5. No logging of raw text or names. Exception messages carry ids only.
6. `./gradlew :core-domain:test :core-data:testDebugUnitTest :core-testing:test` green.

### UI1.2 — App shell, theme, time display (spot-check)

Notes: independent of UI1.1.

Scope: `app-phone` only (+ version catalog).
- Catalog + `app-phone` deps: navigation-compose, lifecycle-viewmodel-compose,
  lifecycle-runtime-compose, kotlin-serialization plugin; test deps
  robolectric, compose ui-test-junit4 / ui-test-manifest.
- `ActivityLedgerApplication` owning one `CapturePipeline`, the two services,
  closed never (process lifetime); manifest `android:name`.
- Theme: D3 light/dark colour tokens exactly, extended review colours, type
  scale with bundled fonts (Instrument Sans for UI, Source Serif 4 Italic
  for evidence), shapes; dynamic colour off.
- Components: state tag (icon + label + colour, D3 state vocabulary, no
  colour-only state), evidence text (curly quotes, serif italic).
- `OccurrenceTimeFormatter` implementing the UX_VISUAL_SPEC §4.3 / ADR-018
  table exactly: EXACT/INFERRED_NOW `Today, 3:12 PM` or `Sep 12, 9:40 AM`;
  APPROXIMATE `Today, afternoon` or `Aug 23, morning`; DATE_ONLY
  `Sat, Sep 12`. Only "Today" is special-cased (no "Yesterday" — not in the
  table). Zone from the capture, locale-aware. Never shows a clock time for
  APPROXIMATE or DATE_ONLY. JVM tests.
- `MainActivity`: NavHost, type-safe routes, NavigationBar with Log and
  History (labels always visible), per-destination back stacks, Back from
  History returns to Log. Screens are placeholders here.

Acceptance: app builds (`:app-phone:assembleDebug`), unit tests green, the
placeholder MainActivity text is gone, no `INTERNET` permission, no ML Kit
coordinate in app-phone.

### UI1.3 — Log screen: typed capture, result cards, picker (verifier)

Notes: depends on UI1.1, UI1.2.

- `LogViewModel`: submit trims text, refuses blank; creates the raw capture
  (source PHONE, surface "typed"), runs `orchestrator.process` in
  `viewModelScope` (survives rotation); maps each
  `CaptureProcessingOutcome` to exactly one card: AutoAccepted → Saved;
  NeedsReview and Rejected → Needs review; InterpreterUnavailable → Not
  categorized; AlreadyHasOccurrence → Saved for the existing occurrence.
  Never re-judges the outcome. Only processes while the screen is started
  (ADR-029): submit disabled otherwise.
- Saved card: "✓ {Activity} — {time}", quoted words, **Undo** and **Change
  activity**, 8 s draining bar, pauses while touched, timeout via
  `AccessibilityManager.getRecommendedTimeoutMillis(8000, CONTENT_TEXT or
  CONTROLS)`; new capture or leaving Log dismisses it. Undo →
  `hideOccurrence`. Change activity → picker → `CorrectionService.correct`;
  card updates to the new name. Refusals shown in plain words.
- Needs-review card (copy per UX_VISUAL_SPEC §4.1/§6): up to 3 suggestions
  (orchestrator decision above), *Choose another activity*, *Create new
  activity* (name field, prefilled with the proposed name if any), *Decide
  later*. Resolves through `ReviewResolutionService`.
- Not-categorized card and the slim "On-device AI isn't ready. Captures are
  still saved." row when readiness is not READY (read once on start, no
  download).
- Shared **activity picker** (bottom sheet): searchable list of the active
  catalog, *New activity* entry with name validation surfaced from the
  service's refusal.
- Recent: 3 newest history rows + *All history* → History tab.
- Live regions, merged semantics, 48dp targets (UX_VISUAL_SPEC §5).

Acceptance: JVM ViewModel tests with `InMemoryActivityRepository` +
`FakeActivityInterpreter` for every outcome mapping, undo, change activity,
resolve (existing / new / refused), blank input, rotation (ViewModel reuse);
Robolectric Compose test that the Saved card shows both actions and the
Needs-review card shows the reassurance copy. No logging. Build + tests
green.

### UI1.4 — History screen (spot-check)

Notes: depends on UI1.1, UI1.3 (picker, resolution flow).

Rows per §4.2 (interpreted: name → time + state tag → quoted words;
uninterpreted: state tag + words), trailing source icon with content
description, filter chips All / Needs review / Not categorized, empty
states (UX_SPEC §13 with "Type what you just did" instead of the mic line
until voice exists — copy decision recorded in PROGRESS), tapping a
needs-review / not-categorized row opens the same resolution sheet as the
Log card. Reloads on resume and after resolution.

Acceptance: ViewModel tests for filtering and ordering; Robolectric test for
empty state and a row's merged content description; build + tests green.

### UI1.5 — Device pass and documentation (orchestrator + owner)

Notes: depends on UI1.1-UI1.4; needs the Pixel 10 Pro (short session).

Install debug build on the Pixel 10 Pro, type 4-5 entries covering saved,
needs review, change activity, undo; check History filters; light + dark;
200% font. Update PROJECT_STATUS, UX_VISUAL_SPEC (D5 now carries Change
activity; note the removed Ask tab until Step 9), BACKLOG 10 / 11, and an
ADR if anything above turned out to be a real architectural choice.
