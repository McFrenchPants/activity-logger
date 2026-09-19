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
| UI1.4 | History screen | in-progress | Spot-check. After UI1.1, UI1.3 |
| UI1.5 | Device pass and documentation | todo | Needs Pixel 10 Pro. After UI1.1-UI1.4 |

## Session log

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
