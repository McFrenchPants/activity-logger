# Wear OS Specification

## 1. Role

The watch is a **capture peripheral**.

It exists to reduce the friction of recording an activity.

It is not the semantic processor or system of record.

## 2. Responsibilities

Wear OS SHOULD:

- launch capture quickly
- obtain voice-dictated text
- timestamp the capture
- create a unique capture ID
- store capture durably until acknowledged
- transmit capture to paired Android phone
- show queued/sent/saved/error state
- retry disconnected captures
- provide brief haptic feedback

Wear OS MUST NOT:

- run canonical semantic inference
- own canonical activities
- maintain authoritative activity history
- create final historical answers
- silently discard unsent captures

## 3. Entry surfaces

Target surfaces:

- app launcher
- Tile
- watch-face complication

The exact order of implementation may be milestone-based.

The fastest practical capture path should be prioritized.

## 4. Capture lifecycle

State model:

```text
CREATED
  -> QUEUED
  -> SENDING
  -> PHONE_RECEIVED
  -> PROCESSED
  -> ACKNOWLEDGED

Failure:
  -> RETRYABLE
  -> FAILED
```

The watch must preserve a queued capture through process death/restart.

## 5. Capture envelope

Recommended protocol shape:

```json
{
  "protocolVersion": 1,
  "captureId": "uuid",
  "rawText": "I changed the furnace filter",
  "capturedAtEpochMillis": 0,
  "source": "WEAR_OS",
  "sourceSurface": "COMPLICATION",
  "speechConfidence": 0.95
}
```

Keep protocol compact.

## 6. Phone acknowledgement

Recommended acknowledgement:

```json
{
  "protocolVersion": 1,
  "captureId": "uuid",
  "status": "SAVED",
  "canonicalActivityName": "Replace furnace filter",
  "occurredAtEpochMillis": 0,
  "needsReview": false
}
```

Possible status values:

- `RECEIVED`
- `SAVED`
- `NEEDS_REVIEW`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`

## 7. Idempotency

`captureId` is the idempotency key.

If the watch resends a capture:

- phone detects existing RawCapture
- phone does not create a duplicate occurrence
- phone may resend last known acknowledgement

## 8. Connectivity behavior

If phone unavailable:

> Queued

The user should be free to leave.

The watch retries later.

Do not block the capture screen waiting indefinitely for phone processing.

## 9. Data Layer

Use the Wear OS Data Layer API for watch/phone communication.

Implementation should choose DataClient/MessageClient patterns appropriate to:

- durable capture payload
- immediate acknowledgement

The protocol must not assume constant Bluetooth connectivity.

## 10. Privacy

Watch capture content is app-private.

Do not transmit activity text to third-party endpoints.

## 11. Watch UI

Design for glanceability.

Possible states:

### Listening

Large microphone/listening indicator.

### Queued

> Queued for phone

### Success

> ✓ Mow lawn

### Needs review

> Saved — review on phone

### Failure

> Couldn't capture. Tap to retry.

## 12. Haptics

Use concise haptic patterns to distinguish:

- accepted/queued
- completed/saved
- failure

Avoid excessive vibration.

## 13. Battery

The watch should not:

- keep long-running inference services
- continuously listen
- poll phone aggressively

Use platform-friendly transport and retry behavior.

## 14. Future possibility

A future version may permit standalone watch operation with cloud or local inference.

That is outside MVP and must not distort the initial architecture.
