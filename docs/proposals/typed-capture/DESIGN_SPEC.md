# Design spec — Typed capture and history screens (work item UI1)

Backlog item 10. First slice of build-guide Step 7
(`docs/IMPLEMENTATION_HANDOFF.md`), taken ahead of Step 6 (voice) by owner
choice on 2026-09-18.
Branch: `feature/typed-capture`, off `main` at 78cab15.

**Approval.** Owner chose this item and signed off the scope and §8 answers
on 2026-09-19 (see §9).

## 1. Problem

The app has no user interface. Everything below the screen exists and is
tested — Room ledger, domain services, the Gemini Nano interpreter wired
through `CapturePipeline`, and a measured accuracy baseline (SR1: 35/48
correct, 7/48 wrong entries auto-saved at HIGH confidence). Nobody can use
it by hand yet. This slice is the first point where the owner can type what
they did on the Pixel 10 Pro and see it land in their history.

## 2. Goals

- G1. **Log screen, typed.** Text field ("Say what you just did."), submit,
  and the result card of UX_VISUAL_SPEC §4.1: Saved / Needs review / Not
  categorized. Recent list (3 rows + *All history*). The RawCapture records
  the input as typed (`CaptureSource` phone, typed surface).
- G2. **History screen.** Newest first, rows per UX_VISUAL_SPEC §4.2, time
  display per §4.3 (ADR-018), filter chips *All* / *Needs review* /
  *Not categorized* (D7), empty state copy (UX_SPEC §13, adjusted for typing).
- G3. **Review resolution.** From the Needs-review card and from a
  needs-review / not-categorized History row: pick one of the offered
  candidates, *Choose another activity* (shared activity picker), *Create
  new activity*, *Decide later*. Goes through the existing
  `ReviewResolutionService`; nothing is logged until the user picks.
- G4. **Undo** inside the Saved card, 8 s draining bar, pause on touch,
  accessibility-recommended timeout (D5). Undo hides the occurrence;
  raw capture and interpretation are kept.
- G5. **App shell.** One activity, Compose Navigation with type-safe routes,
  M3 NavigationBar (Log, History), static brand colour scheme light/dark
  (D3 tokens), state vocabulary icons + labels (D3), accessibility rules
  of UX_VISUAL_SPEC §5 that apply to these screens.
- G6. **Repository reads** the screens need, added to the
  `ActivityRepository` contract and `core-data`: history rows (occurrence +
  activity name + raw text, visible only, newest first, as a Flow), captures
  awaiting review / not categorized, candidate ids offered at review time,
  and hide-occurrence. Read-only additions plus the one visibility write
  that D5 already specifies; no schema change expected (if one is needed it
  is a migration with a test, never destructive).

## 3. Non-goals

- Voice capture (Step 6), the Ask screen (Step 9), the watch (Step 8).
  Ask is left out of the NavigationBar until it exists rather than shown
  disabled.
- Activity detail, Edit interpretation / correction UI, occurrence sheet
  beyond what Undo needs. Next slice.
- Settings / diagnostics screen and the model-download button (ADR-031).
  The Pixel 10 Pro already has the model; when AI is not ready, Log shows
  the slim "On-device AI isn't ready. Captures are still saved." row with no
  link yet. Uncategorized captures are resolved by hand from History.
- Automatically re-interpreting not-categorized captures when AI comes back.
- Changing the prompt or the confidence policy (backlog 13), except as
  decided by the owner in §8 Q1.

## 4. Requirements

- R1. **One pipeline.** Typed text goes through the same
  `CapturePipeline` / `CaptureInterpretationOrchestrator` path as every
  other capture. The UI never interprets, judges or rewrites model output
  (ADR-010) and never edits a raw capture (ADR-007).
- R2. **Foreground only.** Interpretation is started only from the visible
  Log screen (ADR-029). If the app leaves the foreground mid-call, the
  capture must still end in exactly one recorded outcome (the orchestrator
  already guarantees this; the UI must not drop it).
- R3. **State survives.** Result card and in-flight capture survive
  rotation / configuration change (ViewModel). Process death mid-capture
  leaves the raw capture stored; it shows in History under its processing
  state.
- R4. **No logging** of typed text, activity names or model output anywhere
  (AGENTS.md #11). No new permission; `INTERNET` stays absent (ADR-025).
- R5. **Copy** verbatim from UX_SPEC §4, §13, §14 and UX_VISUAL_SPEC §6.
- R6. **Accessibility.** Result cards are polite live regions; rows use
  merged semantics read as one sentence; state never by colour alone; 48dp
  targets; text in sp, reflows at 200%.
- R7. **Tests.** JVM tests for the view-state logic (ViewModels against the
  in-memory repository and a fake interpreter) and for time formatting
  (ADR-018 table). Room tests for the new queries. Compose UI tests where
  cheap on the JVM (Robolectric is not in the project — decide in the
  plan); a short manual pass on the Pixel 10 Pro at the end.

## 5. Constraints

- No DI framework (ADR-032): screens get their collaborators from the one
  `CapturePipeline` owned by the Application.
- ML Kit stays inside `core-ai` (ADR-023).
- New libraries (navigation-compose, lifecycle-viewmodel-compose,
  kotlinx-serialization for routes if needed) go in the version catalog,
  versions verified, not recalled.
- Fonts: D3 specifies bundled Source Serif 4 Italic and Instrument Sans
  (SIL OFL, no downloadable fonts). See §8 Q2.

## 6. Verification tier

G6 adds repository queries and a visibility write (floor:
`data_persistence_migrations`), and the Log view-state consumes
orchestrator outcomes (widen: `ai_output_validation_and_persistence`).
Those tasks go to the verifier agent; pure UI tasks get an orchestrator
spot-check plus a device check.

## 7. Risks

- The owner will now see wrong auto-saved matches in real use (SR1: 7 of 48
  at HIGH confidence). See §8 Q1.
- Compose UI testing on the JVM needs Robolectric or device tests; adding
  it is a build-tooling cost decided in the plan.

## 8. Questions for the owner (product)

- Q1. **What happens right after a confident match.** Designed behaviour:
  a "✓ Mow lawn — this morning" card with an Undo button for 8 seconds.
  The owner's own idea (backlog 11) adds a "pick a different activity"
  button during that window. Adding it here is small (it reuses the
  activity picker built for review). Options: Undo only as designed; or
  Undo plus "Change activity" in the same card.
- Q2. **Fonts.** The approved design uses two free fonts that must be
  downloaded once (from Google Fonts' official repository, ~1 MB total)
  and bundled in the app. Options: download them now; or use the phone's
  default font for this slice and add them later.

## 9. Owner decisions

2026-09-19, asked in plain English:

- Scope as in §2/§3 confirmed ("smallest version you can use by hand").
- Q1: **Undo + Change activity.** The Saved card carries both actions for
  the D5 window. *Change activity* opens the shared activity picker (can
  create new) and applies a user correction to the occurrence through
  `CorrectionService` — a correction recorded alongside the original, never
  an edit of the raw capture or the interpretation. This takes the core of
  backlog 11 into this slice; 11's countdown/auto-save timing and watch
  questions stay open there.
- Q2: **Download the fonts now** — Source Serif 4 Italic and Instrument Sans
  from Google Fonts' official repository, bundled as font resources.
