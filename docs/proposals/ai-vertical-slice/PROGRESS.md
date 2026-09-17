# Progress — AI vertical slice

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — approved by owner 2026-09-17.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/ai-vertical-slice`, off `main` at a949941.

This work item is **sdlc-tracked** (`AI1` in `.sdlc/state.json`). Verification
tier: spec §7 (verifier for every non-doc task).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| AI1.1 | Structured output type and decode mapping | done | Verifier pass. Schema type `InterpretationResponse` (7 nullable String fields); decoder internal, returns Decoded/Failed with value-free reason codes |
| AI1.2 | Prompt: system instruction, builder, versioning | done | Verifier pass; orchestrator sent back two fixes (empty-candidate wording, weak few-shot assertions). Prompt version 2 |
| AI1.3 | Capability detection and model client lifecycle | done | Verifier pass. `OnDeviceModelCapability`; download flow is cold so nothing starts implicitly. Orchestrator made cancellation propagate |
| AI1.4 | GeminiNanoActivityInterpreter | done | Verifier pass. One shot, no retry; structuredResultJson is honestly null; coroutines now declared explicitly |
| AI1.5 | Phone wiring and the on-device vertical slice | todo | Adds androidTest infrastructure; device run needs the Pixel 10 Pro |
| AI1.6 | Documentation | todo | Must not claim a device run that did not happen |

## Open items (carry forward)

- The real device run (Pixel 10 Pro, USB) is the actual proof of this work
  item and needs the owner present. Until it happens, AI1.5 is code that
  builds, not behaviour that is verified.
- `includeSchemaInPrompt` on vs off, and the exact temperature/topK/seed, can
  only be settled against a real run (spec §6).
- `includeSchemaInPrompt` is provisionally on; only a real device run can settle
  whether it earns its prompt tokens.
- Two coroutines entries now exist in the version catalog (1.8.1 for Room,
  1.7.3 for `core-ai`'s ML Kit constraint). Revisit whenever the ML Kit beta
  moves.
- `ModelDownloadProgress.Failed` carries ML Kit's raw numeric error code, which
  is not fit to show a user. Step 7 needs a mapping, or a decision to show only
  generic failure text.

## Session log

### 2026-09-17 — AI1.4 done

`GeminiNanoActivityInterpreter` implements `ActivityInterpreter` over ML Kit's
typed generation, composing AI1.1's schema, AI1.2's prompt and AI1.3's client.
Readiness gates the call: anything but READY returns
`Failure(UNAVAILABLE)` without touching the model and without downloading.
Generation settings are deterministic-classification constants (temperature 0,
topK 1, candidateCount 1, seed fixed, maxOutputTokens 256, thinking off) and the
tests read them back off the captured request. One `generateContent` call per
interpret, proved by a counter — no retry, no repair prompt.

Decisions worth remembering:

- **`structuredResultJson` is always null, deliberately.** The typed API returns
  an already-decoded object, never the model's raw text, so re-serialising it
  would record what the app understood dressed up as what the model said. That
  would be a fabrication in an audit field. Documented in code; revisit only if
  ML Kit exposes the raw response.
- A `GenAiException` with a zero retry delay maps to OTHER, not RETRYABLE: no
  delay means ML Kit is making no promise a later attempt would work.
- `Error` is not caught. A broken process is not a failed model answer.
- Warmup is a separate explicit `warmUp()`, never a side effect of `interpret`.
- **The coroutines carry-forward is closed, with a wrinkle.** `core-ai` resolves
  coroutines to 1.7.3 through ML Kit's own version constraint, while the
  catalog's existing entry is 1.8.1 for Room. Reusing that entry would have
  silently upgraded `core-ai`, so a second, separately-pinned catalog alias was
  added at 1.7.3, with comments on both entries explaining why they must not be
  merged without checking both modules. The resolved classpath is unchanged.

96 core-ai tests (26 new), verifier pass.

### 2026-09-17 — AI1.3 done

`OnDeviceModelCapability` owns the one model client per app process (created
lazily, `close()` idempotent) and answers `readiness()` with a plain enum:
READY, NOT_INSTALLED, DOWNLOAD_IN_PROGRESS, UNSUPPORTED_DEVICE,
STRUCTURED_OUTPUT_UNSUPPORTED, CHECK_FAILED. Structured-output support is
consulted only when the model reports itself installed, so AVAILABLE alone
never yields READY. An internal `GenerativeModelSession` seam (with a fake)
makes all of it host-testable; the ML Kit-shaped members stay internal, and the
module's public surface is plain Kotlin.

The owner's never-download-implicitly rule is enforced structurally: `download()`
returns a **cold** flow that does not even obtain the client until collected.
The implementer's first version opened the client eagerly and its own test
caught it. Tests assert zero download calls after a readiness check in every
status case, including the downloadable one.

Orchestrator fix after the verifier pass: `readiness()` caught `Exception`
broadly, which in Kotlin swallows `CancellationException` — a cancelled caller
would have been reported as "could not check". Cancellation now propagates;
everything else still becomes CHECK_FAILED. KDoc updated to match.

Carry-forward: `core-ai` uses `kotlinx.coroutines` without declaring it,
relying on it arriving transitively through the ML Kit library. Correct today,
fragile if ML Kit's dependencies change. AI1.5 touches the version catalog
anyway, so the explicit declaration belongs there.

70 core-ai tests (27 new), verifier pass.

### 2026-09-17 — AI1.2 done

`InterpretationPrompt.kt`: the system instruction (nine principles as a
numbered list), a pure `buildInterpretationPrompt(InterpretationInput)` with the
five required sections in order, and `PROMPT_VERSION`. Worked examples and the
six few-shot cases live under `## Rules`. The utterance is fenced verbatim; the
capture's local time and zone are rendered with an explicit pattern and
`Locale.ROOT`, and the prompt tells the model not to compute a date itself.
Drift is guarded by pinned SHA-256 digests plus the version constant, with a
failure message that says prompt text is product logic and the version must be
bumped.

Verifier pass, then two orchestrator-requested fixes:

- With an empty candidate list the prompt still said "copy matchedActivityId
  from exactly one of the ids listed above" — an instruction to copy one of
  zero ids. The id instruction is now branch-specific: no candidates means give
  no id and answer `NEW_ACTIVITY` or `UNRESOLVED`. Prompt version 1 → 2.
- The ambiguous and relative-time few-shot assertions only matched the example's
  input half, so the teaching half could have been deleted silently. Both now
  pin the whole line.

Worth carrying forward: re-deriving the pinned digest showed the populated-case
checksum had not moved at all, because only the empty-candidate branch changed —
the drift guard had no fixture for that branch, so it could not have caught this
class of bug. A second pinned fixture (same capture, empty candidate list) now
covers it. 43 core-ai tests.

### 2026-09-17 — AI1.1 done

`InterpretationResponse` (`@Generable`, seven nullable `String` fields, one per
`InterpretationCandidate` field) plus an internal decoder returning
`Decoded`/`Failed`. Failure reasons are a closed enum of six field-shaped codes
carrying no payload at all, so no captured value can reach a reason string even
by a later careless edit. The alpha schema compiler does accept nullable String
fields — the generated provider records `nullable = true` for all seven — so no
sentinel was needed. 21 tests; the three scaffold placeholder files are gone.
Verifier pass.

Two things worth carrying forward:

- **Packet defect, mine, repeated from DS1.6.** The packet listed `docs` in
  both `read_paths` and `forbidden_paths`, so the implementer correctly
  declined to read the spec it was pointed at. From AI1.2 on, `forbidden_paths`
  must forbid only what must not be *written*; `write_paths` already scopes
  writes, and the hook enforces it.
- The schema test maps field names to constructor positions via a
  hand-maintained list (Java reflection exposes parameter annotations
  positionally and Kotlin does not retain parameter names). Reordering the
  schema class's parameters without updating that list could assert the wrong
  field's guide. Flagged by the verifier, not blocking.

### 2026-09-17 — AI1 scaffolded

Nothing was in flight at the start of the run: `feature/domain-services` had
been merged into `main` by hand, so the run began with DS1's post-merge
bookkeeping (state `released`, backlog item 8 `done`, PROJECT_STATUS wording),
committed to `main` as a949941.

Backlog item 4 was the only unblocked actionable item; the owner chose it.
Design spec written and approved, with one owner decision recorded: when the
on-device model is downloadable but not installed, the app reports AI as
unavailable and never starts the download by itself (spec §5.4).

The ML Kit GenAI API surface in spec §5.1 was extracted from the pinned
artifacts in the local Gradle cache (`genai-prompt:1.0.0-beta4`,
`genai-common:1.0.0-beta4`, `genai-schema:1.0.0-alpha1`) with `javap`, rather
than recalled — the library is beta/alpha and its shape is the one thing task
packets most need to be right about.

Next: AI1.1.
