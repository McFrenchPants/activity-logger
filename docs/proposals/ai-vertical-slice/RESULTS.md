# AI vertical slice — first real-device run

What the first run of the Step 4 vertical slice on real hardware did, and what it does and does not prove.

## The run

| | |
|---|---|
| Date | 2026-09-17 |
| Device | Pixel 10 Pro (primary AI test device; see `docs/PROJECT_STATUS.md` "Environment lessons") |
| Test | `CaptureVerticalSliceTest` (`app-phone` instrumented test) |
| Result | **Passed** — 1 test, 0 failures |
| Versions in source when this note was written | `PROMPT_VERSION = "2"`, `INTERPRETATION_SCHEMA_VERSION = 1`, `INTERPRETER_VERSION = "gemini-nano-1"` (the run's own record of which versions it used was not captured here) |

The test uses the production path: `CapturePipeline.create(...)` wiring the real `GeminiNanoActivityInterpreter` over `OnDeviceModelCapability`, the real `CaptureInterpretationOrchestrator`, and the real Room database.

- **Input:** the hard-coded sentence "I cut the grass yesterday.", with the catalog seeded with one existing activity, "Mow lawn".
- **Outcome:** `AutoAccepted`. The interpretation matched the existing "Mow lawn" activity (no new activity was created), the occurrence was dated to the start of the previous local day with `DATE_ONLY` precision, the occurrence is linked to its capture, and the stored raw text is byte-identical to the input.
- **Timings:** interpretation 6552 ms; capture to saved occurrence 6920 ms. Single measurement, not a benchmark.
- **Raw model output:** none is stored (`structuredResultJson` is null by design). On the device the typed result confirmed that ML Kit's typed API hands back an already-decoded object, with no raw JSON text available.

## How it got there

Two obstacles had to be resolved before the run passed.

### 1. The model was not installed

The first attempts found the device `NOT_INSTALLED`. Downloading is explicit by design (ADR-031), so a dedicated download test was used:

1. A 50-minute download attempt over Wi-Fi debugging was **inconclusive**: the phone dropped off the debug connection.
2. A second, bounded 20-minute attempt emitted **zero** download events. During it the app logged being refused `ACCESS_NETWORK_STATE` by the connectivity service; ADR-025 had stripped that permission from the phone app along with `INTERNET`.
3. With `ACCESS_NETWORK_STATE` left in place (and `INTERNET` still removed), the device reported the model and its structured-output feature downloaded and ready.

Whether restoring the permission caused the download to complete, or the earlier attempt simply finished in the background in the meantime, **cannot be separated after the fact**. The phone app keeps `ACCESS_NETWORK_STATE` permanently anyway, since it cannot transmit anything and a model that silently never downloads is a broken app (ADR-025, amended 2026-09-17). The guarantee that capture text cannot leave the device rests on `INTERNET` being absent, and that is unchanged.

### 2. The app was not in the foreground

With the model ready, the pipeline still failed in 245 ms with `INTERPRETER_FAILED`. The test had no foreground Activity, and Google permits GenAI calls only from the top foreground app. Launching `MainActivity` and holding it resumed around `process()` made the test pass. This is now a standing constraint (ADR-029): interpretation cannot run from a background service or worker.

## What is proven, and what is not

Proven, once, on one device:

- The production Gemini Nano path works end to end on real hardware: readiness check, one structured generation, decoding, deterministic validation and time resolution, and a single-transaction save to Room.
- For this one sentence the model returned a decodable, schema-conforming answer that the validator auto-accepted: correct existing-activity match and a time phrase that resolved to yesterday. Because auto-accept requires confidence band `HIGH` and no other review reason (ADR-027), the answer must also have carried `HIGH` and a completed/in-progress state; the test did not inspect those fields individually.
- The raw capture is preserved exactly.

**Not** proven:

- **Accuracy.** This is one sentence, and it is also one of the worked examples in the prompt itself. The model got nothing wrong on it, but that is not evidence of general accuracy, of near-neighbour discrimination ("edged the lawn" vs "Mow lawn"), of new-activity naming, or of ambiguity handling. Measuring that is Step 5's job.
- **Repeatability and performance.** One run, one timing. Determinism across runs and typical latency are unmeasured.
- **The download fix.** The role of `ACCESS_NETWORK_STATE` in the download is suggestive, not established. The download path from a fresh, not-installed state has not been observed completing with the permission present from the start.
- **Other devices.** Nothing is known about devices other than this Pixel 10 Pro, including the "AI unavailable" paths on real hardware.

## For Step 5 (semantic regression)

- Run the corpus through the same production path (`CapturePipeline`), from the foreground (hold an Activity resumed, as `CaptureVerticalSliceTest` does), on the Pixel 10 Pro, with the model already installed.
- Expect roughly 6–7 seconds per sentence from this single measurement; budget test time accordingly and record real per-case timings.
- Use sentences that are **not** the prompt's own worked examples, so results are not flattered by them.
- Treat the provisional choices as things to measure, not assume: schema included in the prompt (`includeSchemaInPrompt = true`), one-shot decoding with no repair (ADR-030), and the `HIGH`-only auto-accept rule (ADR-027). Count `MALFORMED` outcomes explicitly, since ADR-030 is to be revisited only on that evidence.
- A background refusal currently looks like any other runtime error (`INTERPRETER_FAILED`), so a corpus run that loses the foreground mid-way will produce misleading failures; check for that before reading results as semantic.
