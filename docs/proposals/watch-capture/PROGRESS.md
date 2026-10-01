# Progress - Watch capture

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) (owner signed off 2026-10-01).
Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Branch `feature/watch-capture`,
stacked on `feature/wear-data-layer` on `feature/voice-capture` (both unmerged).
sdlc-tracked as `WC1`.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| WC1.1 | Protocol module: envelope, ack, state machine | done | Spot-check. 16 JVM tests; max text 4096 chars, 5 alternatives. NEEDS_REVIEW maps to non-terminal PROCESSED: WC1.2 must treat PROCESSED as complete/removable. WC1.3 must pin SOURCE_WATCH_VOICE == CaptureSource.WATCH_VOICE.name with a test |
| WC1.2 | Watch outbox (durable queue + retry) | done | Verifier pass, no blocking issues. Pure JVM in core-wear-protocol/outbox: FileOutboxStore (atomic temp+move), Outbox, OutboxPolicy (5s,30s,2m,10m,30m,1h cap, never permanent), cap 200 no eviction. 18 new tests. Follow-ups for WC1.4/1.5: PHONE_RECEIVED is never due, so a lost SAVED/NEEDS_REVIEW ack leaves a stuck pending item (no data loss; add staleness resend with same captureId); markSendStarted result must be checked by the sender; UI must let owner discard FAILED (they count toward cap) |
| WC1.3 | Repository: caller-supplied capture id (idempotent receive) | done | Verifier pass. NewRawCapture.id (nullable); Room does exists-check+insert in one transaction; no schema change; InMemory mirrors. Watch captureId becomes the raw capture id |
| WC1.3b | Phone receiver (WearableListenerService, idempotent processing, ack) | done | Verifier pass. WatchCaptureReceiver (pure, mutex-serialised) + WatchCaptureListenerService + manifest filter on /capture/. Acks RECEIVED then SAVED/NEEDS_REVIEW/FAILED_RETRYABLE; InterpreterUnavailable acks NEEDS_REVIEW (capture stored on phone). Unverified on a real device until WC1.6 |
| WC1.4a | Watch speech adapter (ordinary recognizer, prefer-offline); offline check later removed (ADR-036) | done | Spot-check. `PlatformSpeechTranscriber.preferringOffline(context)`, `OfflineSpeechCheck` (ON_DEVICE_CONFIRMED only if the language model is installed; any doubt = NOT_CONFIRMED), 7 JVM tests. Platform path unverified on the watch until WC1.6 |
| WC1.4b | Watch capture UI states, session controller, haptics, permission | done | Spot-check. 20 JVM tests. PlaceholderCaptureSink discards transcripts: WC1.5 must replace it with the outbox and call controller.onAck. UI/haptics only compile-checked; ambient hook deferred to WC1.6 (no androidx.wear dependency yet). New copy: Listening…, Allow microphone to capture., Voice capture isn't available on this watch. |
| WC1.5 | Wire UI to outbox and transport; end-to-end fake test | in-progress | Split: 5a done, 5b todo |
| WC1.5a | Watch send engine (OutboxCaptureSink, OutboxSender, AckHandler, stale-ack recovery) + end-to-end fake test | done | Verifier pass, no blocking. Pure JVM; Data Layer is the `CaptureTransport` interface. Outbox gained ack deadline (2 min), PHONE_RECEIVED resend edge, `recoverStale`, `earliestPendingAttemptAt`. Sender returns next wake time for the scheduler. Non-blocking: `drain()` ends its pass if a still-due record refuses markSendStarted; a SAVED ack after NEEDS_REVIEW is for an already-removed record so the screen stays on NeedsReview (decide in 5b/1.6) |
| WC1.5b | Android wiring: DataClient transport (attempt key, retract), ack listener service, deferred retry wake-up from `drain()` result, Application holder for Outbox/FileOutboxStore, MainActivity uses OutboxCaptureSink + AckHandler->controller.onAck, remove PlaceholderCaptureSink, discard-FAILED path | todo | Needs a deferred-retry mechanism (WorkManager would be a new dependency: decide and record an ADR). Must run recoverOnStart at launch |
| WC1.6 | Device pass and documentation | todo | Needs phone + watch online |

## Session log

### 2026-10-01 - WC1.5a done
Send engine built and verified: capture is saved before sending, resends reuse the same id with a changing attempt value, lost acks no longer leave captures stuck, bad records fail without blocking others. Next: WC1.5b (real Data Layer link, ack listener, retry scheduling, screen wiring).

### 2026-10-01 - Offline concern dropped (ADR-036)
Owner: internet use by speech is fine, no notices. Removed OfflineSpeechCheck and the watch notice; recorded ADR-036; softened AGENTS.md/CLAUDE.md/REQUIREMENTS wording. WC1.6 should not test for offline behaviour.

### 2026-10-01 - WC1.4b done
Watch screens, controller, haptics, mic permission built (nothing sent yet). Next: WC1.5 wires the outbox and transport to the screen and the phone acks back.

### 2026-10-01 - WC1.4a done
Split WC1.4 in two. Speech adapter and offline check built (core-speech). Next: WC1.4b watch screens.

### 2026-10-01 - WC1.3b done
Phone receiver built and verified. Follow-ups for WC1.5/WC1.6: (1) watch MUST put a changing CAPTURE_ATTEMPT_KEY value in the DataItem on every resend, or the Data Layer will not fire onDataChanged for identical content; (2) DataItems are never deleted (unbounded growth) - add cleanup after final ack; (3) runBlocking on the listener thread holds the callback during interpretation - revisit on device; (4) re-acks of an already-saved capture carry no activity name; (5) PHONE_RECEIVED stuck-item handling (see WC1.2). Next: WC1.4.

### 2026-10-01 - WC1.3 done
Split the phone side in two: WC1.3 (repository idempotent on supplied id) done; WC1.3b (listener + ack) next.

### 2026-10-01 - WC1.2 done
Watch outbox built and verified (see table). Next: WC1.3 (phone receiver).

### 2026-10-01 - WC1.1 done
Protocol contract in core-wear-protocol: CaptureEnvelope, CaptureAck, paths (/capture/<id>, /capture-ack), strict total codec with payload-free failure kinds, pure OutboxTransitions. Next: WC1.2 (outbox) and WC1.3 (phone receiver).

### 2026-10-01 - WC1 scaffolded
Owner chose the watch capture app after the watch was shown to transcribe offline. Signed off: launcher entry first (complication is a later item), watch copy as in WATCH_SPEC section 11.
