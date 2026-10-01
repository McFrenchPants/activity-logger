# Progress — Wear Data Layer round-trip probe

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Branch `feature/wear-data-layer`
(stacked on `feature/voice-capture`). sdlc-tracked as `WD1`. Verification: spot-check
(a hand-run probe, no product behaviour, no persistence).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| WD1.1 | Data Layer probe code (phone test + debug-only watch echo) | done | Spot-check. Builds and 594+ unit tests green; probe never run on hardware. Echo service is debug-only (absent from release manifest). Phone's ACCESS_NETWORK_STATE is pre-existing (ML Kit telemetry libs, kept deliberately); INTERNET absent everywhere |
| WD1.2 | Device run and write-up | done | Both round trips passed on hardware; see RESULTS.md |
| WD1.3 | Debug-only dictation probe on the watch | done | Spot-check. Owner ran it in airplane mode: both paths worked; see RESULTS.md |

## Session log

### 2026-10-01 — WD1.3 done: watch transcribes offline
After the owner clarified Internet avoidance is a preference, the dictation question was measured instead of decided on paper. Debug-only `DictationProbeActivity` built; owner ran both buttons online and in airplane mode (Bluetooth off): accurate text both times. Log showed Google's on-device SODA engine. Recorded in ADR-035, backlog item 17 (now done), UX_VISUAL_SPEC §9 and ARCHITECTURE §21. Open risk for the Wear work: EXTRA_PREFER_OFFLINE is a hint -- prevent or visibly surface a silent network fallback. Branch still unmerged; merge feature/voice-capture first.

### 2026-10-01 — WD1.2 done; WD1 complete
Device run passed. Pairing took several attempts: the phone's pairing popup closes when the app is backgrounded, so it needed split-screen with the chat. platform-tools updated 35.0.2 -> 37.0.1 (not the cause). Awaiting owner merge (after feature/voice-capture).

### 2026-09-30 — WD1.1 done; WD1.2 blocked on devices
Probe code written and compiled: `WearDataLayerProbeTest` (phone androidTest) sends a message and a data item and waits for the watch's debug-only echo service to answer; 'no watch connected' is a reported, passing result. play-services-wearable 19.0.0 added via the catalog. Run by hand when both devices are online: install debug `app-wear` on the watch, then `./gradlew :app-phone:connectedDebugAndroidTest --tests '*WearDataLayerProbeTest'` and read `adb logcat -d -s WearDataLayerProbe`.

### 2026-09-30 — WD1 scaffolded
Owner chose backlog item 16 after VC1 finished. No devices were connected at start.
