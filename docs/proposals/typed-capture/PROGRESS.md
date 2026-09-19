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
| UI1.1 | Ledger reads and hide for the screens | todo | Verifier |
| UI1.2 | App shell, theme, time display | todo | Spot-check. Independent of UI1.1 |
| UI1.3 | Log screen: typed capture, result cards, picker | todo | Verifier. After UI1.1, UI1.2 |
| UI1.4 | History screen | todo | Spot-check. After UI1.1, UI1.3 |
| UI1.5 | Device pass and documentation | todo | Needs Pixel 10 Pro. After UI1.1-UI1.4 |

## Session log

### 2026-09-19 — UI1 scaffolded

SR1 merged to `main` at the owner's request (639ff39, bookkeeping 78cab15).
Owner picked backlog 10 over the small fixes (14) and prompt work (13).
Owner answers: scope as proposed (typed Log, History + filters, review
resolution, Undo; no voice/Ask/detail/edit/Settings); Saved card gets
**Undo + Change activity** (core of backlog 11); **download the fonts now**.
Fonts downloaded from google/fonts `main` (~1.05 MB), provenance in
`app-phone/licenses/fonts/README.md`.
