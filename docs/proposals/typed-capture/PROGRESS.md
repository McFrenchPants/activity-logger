# Progress — Typed capture and history screens

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — owner sign-off 2026-09-19.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/typed-capture`, off `main` at 78cab15.

This work item is **sdlc-tracked** (`UI1` in `.sdlc/state.json`). Verification
tier: spec §6 (verifier for UI1.1 and UI1.3).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| UI1.1 | Ledger reads and hide for the screens | done | Verifier pass. `loadHistory` one SQL statement; `hideOccurrence` is a ledger transaction, no correction row |
| UI1.2 | App shell, theme, time display | done | Spot-check. Robolectric UI tests at SDK 35 |
| UI1.3 | Log screen: typed capture, result cards, picker | done | Verifier pass + follow-up fixes. 74 app-phone host tests |
| UI1.4 | History screen | done | Spot-check. 90 app-phone host tests |
| UI1.5 | Device pass and documentation | done | Pixel 10 Pro pass 2026-09-19; fix: keyboard closes on submit; ADR-034 |

## Session log

### 2026-09-19 — UI1.5 done; UI1 complete, awaiting owner merge

Orchestrator device pass on the Pixel 10 Pro (Wi-Fi ADB, debug build, owner
approved light mode + 200% font with restore; restored to dark + 1.0 and
re-checked). Driven with `uiautomator dump` + `input`. Covered: empty Log;
first capture with an empty catalog -> Needs review (no suggestions) ->
Create new activity "Mow lawn" -> Saved card; "just mowed the grass" ->
auto-accepted Mow lawn, card expired after ~8 s; Undo (row disappears from
Recent and History); "mowed it again" -> Needs review with Mow lawn
suggested -> Decide later; Change activity -> picker -> New activity "Trim
hedges" -> card and Recent show Trim hedges, words unchanged; History: 4
rows, hidden one omitted, filters Needs review (1 row) / Not categorized
(empty-state copy), resolving from the row sheet (suggestions Mow lawn +
Trim hedges) -> Mow lawn; light theme; 200% font on Log, History (chips wrap
to two lines) and a Needs-review card (buttons stack, nothing clipped).
Interpretation ~2-5 s per call.

Found and fixed (orchestrator inline, LogScreen `CaptureField`): the IME
stayed open after *Log it* / Done and covered the result card; submit now
clears focus when `canSubmit`. `:app-phone:testDebugUnitTest` 90/90 green,
`assembleDebug` green, re-checked on device (IME hidden, card visible).
Contrast: computed from `ui/theme/Color.kt` for every text/background pair
the two screens use -- min 4.59:1 light (onSurfaceVariant on
reviewContainer), 5.04:1 dark. Not re-checked on device: rotation mid-call,
touch-to-pause on the Undo bar, TalkBack reading order (adb-driven pass
can't hold a touch or listen), the AI-not-ready row (Pixel is READY; the
Moto G would show it). Minor, not fixed: state-tag icon does not scale with
font size; resolving a Needs-review card turns it into a Saved card with
Undo/Change (reasonable, not in the spec text).

Docs: PROJECT_STATUS (UI1 status, open decisions on hide/review list
resolved, adb-driving lesson), UX_VISUAL_SPEC (D1 built-so-far note: no Ask,
no Settings icon; D5 Change activity + ADR-034; contrast open item closed),
BACKLOG 10 -> done (awaiting merge), 11 annotated, ADR-034 (hide is a
visibility change, not a correction). state.json: UI1/UI1.5 approved ->
implementing -> verifying, each step validated. Note for agents:
`validate-state.mjs` against HEAD also reports "released requires evidence"
for every older released item -- pre-existing, lite mode keeps no
`.sdlc/evidence/`; not introduced here.

Test data left on the Pixel's app install: 7 typed entries (Mow lawn x3,
Trim hedges x2, one hidden, one "did a thing" waiting in review) and the
activities Mow lawn, Trim hedges.

Next: owner merges `feature/typed-capture` into `main` by hand (lite mode).

### 2026-09-19 — UI1.4 done; run stops before the device pass

Implementer (packet `.sdlc/task-packets/UI1.4.packet.json`), orchestrator
spot-check (diff skimmed, build + 90 host tests re-run). History screen with
single-select chips All / Needs review / Not categorized (no counts), shared
`ui.components.HistoryRow`, resolution bottom sheet (suggestions, picker, Decide
later) through ReviewResolutionService; `ui.review` now holds suggestions,
refusal messages, UserMessage and PickerState for both screens. History reloads
on every start. New copy: 'Nothing needs review.', 'Nothing is waiting to be
categorized.', "Couldn't load your history. Try again." Known quirks for the
device pass: picker replaces the sheet while open; a refusal arriving after
Decide later shows above the list; no source-device icon on rows yet.

Next: UI1.5 needs the Pixel 10 Pro (owner hardware) -- stopped here.

### 2026-09-19 — UI1.3 done

Implementer (packet `.sdlc/task-packets/UI1.3.packet.json`), verifier pass.
`LogViewModel` (typed capture -> raw capture PHONE_TEXT/`log_typed`/CAPTURED ->
orchestrator; fixed outcome->card mapping), `LogScreen`, result cards (Saved
with Undo + Change activity and the D5 window; Needs review with <=3
suggestions; Not categorized), shared `ActivityPicker`, Recent (3 rows + All
history), AI-not-ready row. Verifier's non-blocking findings sent back to the
same implementer and fixed: storage errors in undo/change/resolve/picker no
longer crash (one guarded `runAction`), second tap while an action is in
flight is ignored and buttons disabled, test for process() throwing after the
capture is stored, unused string removed, hidden AlreadyHasOccurrence reloads
Recent. Orchestrator re-ran build + tests (green).

Copy notes for UI1.5 docs: the Not-categorized follow-up sentence ("They'll be
categorized ... when on-device AI is available") is omitted because nothing
re-categorizes yet. Invented copy: "Log it", "Categorizing your words", picker
texts, refusal/failure messages. Not built: highlighting the new Recent row.
Untested automatically: rotation-vs-leaving-Log and touch-to-pause (UI1.5
device pass). Coroutines-test 1.9.0 added (resolved version on the test
classpath).

### 2026-09-19 — UI1.2 done

Implementer (packet `.sdlc/task-packets/UI1.2.packet.json`), orchestrator
spot-check (diff read, build + 25 host tests re-run, merged manifest still has
no INTERNET). `ActivityLedgerApplication` owns the one `CapturePipeline` and the
review/correction services (lazy). D3 theme incl. extended review colours and
bundled variable fonts; `StateTag` (Canvas icons, dashed outline for Not
categorized), `EvidenceText`; `OccurrenceTimeFormatter` (TemporalResolver's
part-of-day bands match 05/12/17/21); NavHost Log/History, no Ask tab.
Decisions worth knowing: M3 secondary/tertiary roles reuse primary (one accent,
so the nav indicator stays green); a window theme in res/values matches D3
background before Compose draws; 'Today' and part-of-day words are English
literals in the formatter. Still open: 4.5:1 contrast check on device (UI1.5).

### 2026-09-19 — UI1.1 done

Implementer (packet `.sdlc/task-packets/UI1.1.packet.json`), verifier pass.
`ActivityRepository.loadHistory()` (captures with an ACTIVE occurrence or none;
newest first by occurred_at/captured_at, capture-id tie-break;
`pendingMatchedActivityId` from the latest interpretation) and
`hideOccurrence()` (ACTIVE->HIDDEN + updated_at, no-op if hidden, no
correction row) in Room and in-memory. No schema change. Verifier notes
(non-blocking): equal-`created_at` interpretation ties may pick differently in
Room (greatest UUIDv7) vs in-memory (last inserted); in-memory ids like
`capture-10` sort before `capture-9`. Implementer accidentally cat'ed the
forbidden `db/entity` dir while surveying (read only, nothing written) -- the
packet was over-strict: entities are fine to read, only writes should be
forbidden. Stale "two @Transaction operations" comment in
ActivityLedgerDatabase.kt left as is.

### 2026-09-19 — UI1 scaffolded

SR1 merged to `main` at the owner's request (639ff39, bookkeeping 78cab15).
Owner picked backlog 10 over the small fixes (14) and prompt work (13).
Owner answers: scope as proposed (typed Log, History + filters, review
resolution, Undo; no voice/Ask/detail/edit/Settings); Saved card gets
**Undo + Change activity** (core of backlog 11); **download the fonts now**.
Fonts downloaded from google/fonts `main` (~1.05 MB), provenance in
`app-phone/licenses/fonts/README.md`.
