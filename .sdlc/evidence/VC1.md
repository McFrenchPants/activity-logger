# VC1 -- release evidence (backfilled 2026-10-05)

Phone voice capture (build-guide Step 6)

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

Tasks (all status done): VC1.1, VC1.2, VC1.3, VC1.4, VC1.5

## Commits on main naming this id

- 1d98c83 2026-09-20 VC1: manual test notes scaffold for the owner
- 6bec2e3 2026-09-20 VC1.5: device pass results; VC1 complete
- f50cc9d 2026-09-19 VC1.5: two fixes the device pass found
- 6253d4f 2026-09-19 VC1.5 (documentation half): record what voice capture changed
- 17992d7 2026-09-19 VC1.4: spoken words become captures through the existing pipeline
- 64b1741 2026-09-19 VC1.4: task packet
- 0a1b697 2026-09-19 VC1.3: microphone control, listening state and recognition-failure card
- 29671c1 2026-09-19 VC1.3: task packet
- 497ab23 2026-09-19 VC1.2: real core-speech module -- SpeechTranscriber and platform adapter
- 10d030a 2026-09-19 VC1.2: task packet
- 22ca222 2026-09-19 VC1.1: on-device speech recognition probes for watch and phone
- 34d8e9b 2026-09-19 VC1: scaffold phone voice capture work item

## Tracking row

- `PROGRESS.md`: | VC1 | Phone voice capture (backlog item 15) | done | All five tasks done; now on `main` (merged with the stacked watch branches). Device pass 2026-09-20 on the Pixel 10 Pro (`docs/proposals/voice-capture/RESULTS.md`) found and fixed two device-only defects. Watch cannot do on-device recognition (ADR-035) — see backlog items 16, 17. |
