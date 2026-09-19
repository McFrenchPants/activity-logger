# Results — first device recording and baseline (SR1.7)

Recorded 2026-09-18 on the owner's Pixel 10 Pro (USB), Gemini Nano via AICore,
interpreter `gemini-nano-1`, prompt version `2`, schema version `1`, corpus
SHA-256 `63c9ff80…abafb` (matches current). Recording:
`core-testing/src/test/resources/semantic-corpus/recordings/device-latest.json`.
Baseline: `recordings/baseline.json` (the 32 CORRECT case ids of this run).

## Numbers

| | CORRECT | SAFE_MISS | UNSAFE_MISS | Failures |
|---|---|---|---|---|
| **Device** (Gemini Nano, Pixel 10 Pro) | **32** (66.7%) | 6 | **10** | 0 |
| Stand-in (gemma3n:e4b via Ollama, not official) | 20 | 20 | 8 | 0 |

By category (device): SYNONYM 16/17 correct, NEAR_NEIGHBOUR 1/8 (5 unsafe),
NEW_ACTIVITY 0/4 (2 unsafe, 2 safe), TEMPORAL 10/11, AMBIGUITY 3/5 (2 unsafe),
STATE 2/3 (1 unsafe).

Latency: median 5.1 s, max 6.2 s per call (excluding throttle waits). Total
recorder time 255 s for 48 cases, plus waits.

Confidence bands: 46 of 48 answers HIGH, 1 MEDIUM, 1 LOW. **All 10 unsafe
misses are HIGH.** The band does not separate right from wrong on this model,
so the provisional auto-accept policy (ADR-027: HIGH + valid → auto-accept) is
not safe on its own.

## What the device gets wrong (unsafe — a wrong entry would be saved)

1. **New activity matched to an existing one** (6): "I edged the lawn" /
   "Finished edging" with no edging activity in the catalog (→ mowing);
   "Emptied the dryer lint trap" (→ dryer vent); "Raked the leaves" (→ blow
   leaves); "Washed the car" (→ wax car); "Flushed the water heater" and
   "Replaced the smoke detector batteries" (→ some existing activity). The model
   strongly prefers MATCHED_EXISTING over NEW when anything nearby is offered.
2. **Ambiguous sentence auto-accepted** (2): "Cleaned the dryer", "Did the
   furnace thing" — matched with HIGH confidence instead of AMBIGUOUS.
3. **State case** (1): "Just finished edging the lawn" matched the wrong
   activity and time.

Safe misses (6): two `TIME_UNRESOLVABLE` where the model put a non-time phrase
in the temporal field ("Finished edging", "Changed the furnace filter"), one
`STATE_MISSING`, two `EXISTING_ACTIVITY_NOT_SUPPLIED` on the empty catalog (the
model claimed a match with nothing offered — correctly rejected by the
validator), and the known resolver gap (`time-mowed-saturday-morning`).

## Stand-in vs device

- The stand-in's dominant failure (17 × `STATE_MISSING`) is **not** a device
  problem: the device left state empty once. Prompt changes aimed at it would
  have been tuning to the stand-in.
- Both models over-match to existing activities on near-neighbours; the
  edging-vs-mowing and leaves confusions appear in both, on different cases.
- 19 cases differ in class between the two. The stand-in is useful for
  iterating on prompts but its scores do not predict the device's; only a
  device run counts.

## Provisional choices, as measured

- **Confidence policy (ADR-027)**: not discriminating — see above. Needs a
  change before real use (a policy or prompt change; candidate backlog item).
- **Schema in prompt / one-shot decoding (ADR-030)**: 0 malformed answers in
  48; the structured output works on device.
- **Temporal**: 10/11; the only miss is the known resolver gap.

## Throttling finding (recorder change)

The first attempt the same evening recorded 20 cases normally, then 27 of the
remaining 28 failed within ~80-170 ms: AICore returns `GenAiException` BUSY
(code 9) with zero retry delay once an app sends requests back to back; the
interpreter maps that to `InterpreterFailureKind.OTHER`. That recording was
discarded. The recorder now retries fast refusals (under 1 s, kind OTHER or
RETRYABLE) with backoff 5/10/20/30/60/60 s and records the count in the
recording's `notes` (`busy retries: 17` for this run). Verifier: pass.

Production follow-up (not changed here, `core-ai/src/main` is out of scope):
the app's interpreter would report a throttled capture as a generic failure.
Unlikely for a person logging by hand, but BUSY should probably map to
RETRYABLE — candidate backlog item.
