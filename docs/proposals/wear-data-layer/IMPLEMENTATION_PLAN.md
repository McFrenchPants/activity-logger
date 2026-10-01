# Implementation plan — Wear Data Layer round-trip probe (work item WD1)

Backlog item 16. Small tier: no design spec. Branch `feature/wear-data-layer`,
stacked on `feature/voice-capture` (unmerged; item 15 touched the same manifests).
Tracking: sdlc-tracked (`WD1` in `.sdlc/state.json`); table in [`PROGRESS.md`](PROGRESS.md).

## WD1.1 — Data Layer probe code (phone test + debug-only watch echo)

**Scope.** Prove, on the paired Pixel 10 Pro and OnePlus Watch 3, that the Wear
Data Layer connects the two apps: pairing visible, shared `applicationId`, a
message arrives each way, a `DataItem` arrives each way.

- Add `com.google.android.gms:play-services-wearable` via the version catalog
  (no literal versions in build files) to `app-phone` and `app-wear`.
- `app-wear/src/debug/`: a debug-only `WearableListenerService` (declared in a
  debug manifest, never in release) that echoes any message on path
  `/probe/ping` back to the sender on `/probe/pong`, and any DataItem at
  `/probe/item` back as a DataItem at `/probe/item-echo`. Payloads are fixed
  probe strings; no user content.
- `app-phone/src/androidTest/.../wear/WearDataLayerProbeTest`: hand-invoked
  measurement (like the speech probes). Reports: connected nodes (count only,
  never names/ids in output beyond "watch present"), message round trip,
  DataItem round trip, each with a bounded wait; "no watch connected" is a
  reported result, not a crash. Registers listeners dynamically and removes them
  in `finally`.
- Merged manifests of both apps must still contain no INTERNET /
  ACCESS_NETWORK_STATE (ADR-025); verify and report.

**Acceptance criteria.**
- `./gradlew :app-phone:assembleDebug :app-wear:assembleDebug :app-phone:assembleDebugAndroidTest` succeeds; `./gradlew test` still passes.
- Echo service exists only under `app-wear/src/debug`; release manifest of app-wear has no such service.
- No `com.google.mlkit` coordinate added to either module (ADR-023); no dependency from app-wear on core-ai.
- No INTERNET permission in either merged debug manifest.
- Probe logs contain no capture text or device identifiers (AGENTS.md #11).

## WD1.2 — Device run (orchestrator, no implementer)

Install both debug builds, run the probe, record results in `RESULTS.md`, amend
backlog item 16 and `docs/ARCHITECTURE.md` §21 if the answer changes anything.
Blocked while the devices are offline.
