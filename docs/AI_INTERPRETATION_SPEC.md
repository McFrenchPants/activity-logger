# AI Interpretation Specification

## 1. Purpose

Gemini Nano is used as a constrained semantic interpreter.

It is **not** the system of record.

Its job is to convert a short natural-language utterance into a small typed candidate structure that application code can validate.

The inference pipeline runs on the Android phone.

Wear OS does not perform semantic inference.

## 2. Primary interpretation task

Given:

- raw user transcript
- current local date/time/time zone
- a bounded set of relevant existing canonical activities
- optional known aliases
- interpretation rules

Return a structured result describing:

- whether the user logged an activity
- whether it matches an existing activity
- proposed new canonical name if needed
- completed/in-progress state
- temporal expression
- optional normalized notes/entities if later required
- model confidence indicator

## 3. Example

Raw input:

> I cut the grass earlier this afternoon.

Candidate activities:

- `act_001` — Mow lawn
- `act_002` — Edge lawn
- `act_003` — Blow leaves

Expected structured result:

```json
{
  "operation": "LOG_ACTIVITY",
  "activityResolution": "EXISTING_ACTIVITY",
  "activityId": "act_001",
  "proposedCanonicalName": null,
  "state": "COMPLETED",
  "temporalExpression": "earlier this afternoon",
  "confidenceBand": "HIGH"
}
```

Application code then resolves the temporal phrase.

## 4. Structured output

Use the supported ML Kit Prompt API Structured Output mechanism where available.

The implementation must:

- define a Kotlin output type
- use enums instead of unconstrained strings wherever possible
- restrict optional fields
- validate activity IDs against supplied candidates
- reject impossible combinations

Example impossible result:

```text
activityResolution = EXISTING_ACTIVITY
activityId = null
```

must fail validation.

## 5. System instruction principles

The interpreter system instruction should communicate:

1. The user is logging real-world activities.
2. Prefer an existing semantically equivalent activity.
3. Do not merge merely related activities.
4. Propose a concise verb-first canonical name only when no equivalent activity exists.
5. Preserve distinctions such as mowing vs edging.
6. Extract temporal wording rather than fabricating a precise timestamp.
7. Return only the requested structure.
8. Do not invent IDs.
9. When meaning is insufficient, return ambiguous/unresolved.

## 6. Prompt design

Gemini Nano prompts should be:

- concise
- strongly structured
- example-driven
- low-temperature for deterministic classification
- short-output oriented

Recommended sections:

```text
## Task
...

## Rules
...

## Current context
...

## Candidate activities
...

## User utterance
...
```

Few-shot examples should include:

- synonym match
- near-neighbor rejection
- new activity
- ambiguous activity
- completed/in-progress
- relative time phrase

## 7. Candidate activity selection

Do not assume the entire canonical catalog can be placed in the prompt forever.

Initial small catalogs may include all activities.

As the catalog grows, implement a deterministic/semantic pre-selection layer.

Potential strategies:

1. normalized token/alias matching
2. lightweight local lexical scoring
3. future embedding similarity
4. recent/frequent activity priors

The pre-selector returns a bounded candidate list.

Gemini Nano performs final semantic selection.

Semantic embeddings are explicitly deferred from MVP unless required by measured catalog scale/performance.

## 8. Existing vs new activity rule

The model should prefer an existing activity only when meaning is genuinely equivalent.

Examples:

### Match

- "cut the grass" -> Mow lawn
- "mowed" -> Mow lawn
- "put a new HVAC filter in" -> Replace furnace filter

### Do not match

- "edged the lawn" != Mow lawn
- "raked leaves" != Blow leaves
- "washed the car" != Wax car

False merges are particularly damaging because they silently corrupt historical analysis.

## 9. Canonical naming

For new activities:

- concise
- verb-first
- singular concept
- no date/time
- no completion status
- no filler language

Good:

- Flush water heater
- Clean dryer vent
- Replace smoke detector battery

Bad:

- I flushed the water heater today
- Water heater stuff
- Completed dryer cleaning activity

## 10. Temporal handling

The model should primarily extract the phrase and broad semantics.

Examples:

> yesterday

> Saturday

> this morning

> about an hour ago

> just now

Application temporal code should resolve these relative to:

- `capturedAt`
- device time zone
- locale if needed

The model should not be allowed to invent a precise minute when the user did not specify one.

If an exact time is unknown, the schema must allow appropriate precision/estimated-time representation.

### Recommended temporal representation

Consider storing:

- `occurredAt`
- `timePrecision` = EXACT / APPROXIMATE / DATE_ONLY / INFERRED_NOW
- `originalTemporalExpression`

This avoids pretending that "this morning" means exactly 9:00 AM.

## 11. Confidence policy

Do not blindly trust model confidence.

The app should calculate an application-level decision using:

- structured output validity
- existing candidate validity
- ambiguity status
- whether new activity creation is requested
- speech confidence if available
- semantic consistency
- temporal parse success
- model confidence band

Suggested behavior:

### Auto-accept

- valid structured output
- existing activity match
- no contradictory fields
- temporal parse valid
- high confidence

### Auto-accept with review marker

- result valid but uncertainty is moderate
- raw capture remains available

### Needs review

- ambiguous between activities
- proposed new activity with weak confidence
- temporal meaning materially uncertain
- transcript appears poor

### Reject/retry

- malformed output
- invalid activity ID
- unsupported operation
- inference failure

## 12. Interpretation provenance

Store:

- raw capture ID
- interpretation ID
- interpreter implementation version
- prompt version
- schema version
- structured-output capability version if known
- model/API metadata when exposed
- candidate IDs supplied
- candidate-context hash
- interpretation timestamp
- model result
- application validation result

This allows future tooling to compare old and new behavior.

## 13. Re-interpretation

A future repair tool may rerun old raw captures through a newer interpreter.

Re-interpretation must create a **new Interpretation record**.

It must not destroy the original interpretation.

Application policy then decides whether the new interpretation becomes effective.

## 14. Query interpretation

Natural-language questions should use a separate constrained schema.

Example:

> When did I last cut the grass?

Model output:

```json
{
  "intent": "LAST_OCCURRENCE",
  "activityId": "act_001",
  "startDate": null,
  "endDate": null
}
```

Application code performs the Room query.

Never execute generated SQL.

## 15. Supported MVP query intents

- `LAST_OCCURRENCE`
- `PREVIOUS_OCCURRENCES`
- `COUNT_OCCURRENCES`
- `LIST_OCCURRENCES`
- `INTERVAL_SUMMARY`

Potential structured fields:

- activity ID
- start date
- end date
- result limit

## 16. Runtime capability checks

Gemini Nano/ML Kit GenAI capabilities may vary by device configuration.

The implementation must check availability at runtime.

Structured Output availability must be checked where the API requires it.

Do not assume installation implies model readiness.

## 17. AICore unavailable behavior

If inference is unavailable:

1. retain raw capture
2. mark capture retryable where appropriate
3. do not fabricate interpretation
4. do not silently send to cloud
5. expose diagnostic state to the user

## 18. Privacy

Prompts include personal activity history/context.

MVP inference must remain on-device.

No prompt logging to remote services.

## 19. Prompt versioning

Prompt text is product logic.

Store it in version-controlled source and assign a version identifier.

Any material prompt change must:

- increment prompt version
- run semantic regression corpus
- document behavior changes

## 20. Seed semantic examples

### Equivalent

```text
Mow lawn
- I mowed the lawn.
- I cut the grass.
- Finished mowing.
- Just did the grass.
```

### Distinct

```text
Edge lawn
- I edged the lawn.
- Did the edging.

Mow lawn
- I mowed.
- Cut the grass.
```

### Temporal

```text
Changed the furnace filter yesterday.
Cleaned the gutters Saturday.
Mowed this morning.
Just finished mowing.
```

### Ambiguous

```text
Did the thing by the furnace.
Worked on the yard.
Took care of that filter thing.
```

The ambiguous set should not be forced into confident canonical matches.

## 21. Official platform references

Implementation agents should verify current details against official Android/Google documentation before coding:

- Android Gemini Nano / AICore documentation
- ML Kit GenAI Prompt API
- ML Kit Prompt API Structured Output
- Gemini Nano prompt-design guidance
- ML Kit GenAI Speech Recognition API

As of the documentation baseline in September 2026, Google's official documentation describes Gemini Nano as running through Android's AICore system service for on-device inference, and the Prompt API supports structured output on supported configurations. Runtime feature availability must still be checked.
