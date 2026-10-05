# WC1.4a -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Watch speech adapter (ordinary recognizer, prefer-offline) + offline assurance check

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- ea1731d 2026-10-01 WC1.4a: watch speech adapter (ordinary recognizer, prefer-offline) and offline assurance check

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.4a | Watch speech adapter (ordinary recognizer, prefer-offline); offline check later removed (ADR-036) | done | Spot-check. `PlatformSpeechTranscriber.preferringOffline(context)`, `OfflineSpeechCheck` (ON_DEVICE_CONFIRMED only if the language model is installed; any doubt = NOT_CONFIRMED), 7 JVM tests. Platform path unverified on the watch until WC1.6 |
