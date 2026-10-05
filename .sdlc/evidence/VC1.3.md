# VC1.3 -- release evidence (backfilled 2026-10-05)

Task of work item VC1 (Phone voice capture (build-guide Step 6)): Mic button, listening state and failure card on Log

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 0a1b697 2026-09-19 VC1.3: microphone control, listening state and recognition-failure card
- 29671c1 2026-09-19 VC1.3: task packet
- 22ca222 2026-09-19 VC1.1: on-device speech recognition probes for watch and phone

## Tracking row

- `docs/proposals/voice-capture/PROGRESS.md`: | VC1.3 | Mic button, listening state, failure card on Log | done | Spot-check. `ResultCard` restructured into `ForCapture`/`Unresolved` + `RecognitionFailed`. 21 tests |
