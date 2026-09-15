# Data Model

## 1. Data design objectives

The database must support:

- canonical activity history
- immutable source utterances
- AI interpretation provenance
- corrections
- future re-interpretation
- future cloud synchronization
- future external audit/repair tooling

The key rule is:

> **Never overwrite the evidence to make the interpretation look correct.**

## 2. Proposed entities

### raw_captures

Represents the original captured/transcribed user input.

Suggested fields:

```text
id TEXT PRIMARY KEY
source TEXT NOT NULL
source_surface TEXT NULL
captured_at INTEGER NOT NULL
raw_text TEXT NOT NULL
speech_confidence REAL NULL
speech_alternatives_json TEXT NULL
processing_state TEXT NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

`raw_text` is logically immutable.

If a later transcription correction is needed, represent it separately.

Indexes:

- `captured_at`
- `processing_state`

### canonical_activities

```text
id TEXT PRIMARY KEY
display_name TEXT NOT NULL
normalized_name TEXT NOT NULL
status TEXT NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
merged_into_activity_id TEXT NULL
```

Indexes:

- normalized name
- status

### activity_aliases

```text
id TEXT PRIMARY KEY
canonical_activity_id TEXT NOT NULL
alias_text TEXT NOT NULL
normalized_alias TEXT NOT NULL
source TEXT NOT NULL
confidence REAL NULL
created_at INTEGER NOT NULL
```

Possible source values:

- `USER_CORRECTION`
- `AI_CONFIRMED`
- `SEEDED`
- `MANUAL`

Unique/index policy should avoid duplicate normalized aliases for the same canonical activity.

### interpretations

```text
id TEXT PRIMARY KEY
raw_capture_id TEXT NOT NULL
created_at INTEGER NOT NULL
interpreter_version TEXT NOT NULL
prompt_version TEXT NOT NULL
schema_version INTEGER NOT NULL
operation TEXT NOT NULL
activity_resolution TEXT NOT NULL
matched_activity_id TEXT NULL
proposed_canonical_name TEXT NULL
activity_state TEXT NULL
temporal_expression TEXT NULL
resolved_occurred_at INTEGER NULL
time_precision TEXT NULL
model_confidence_band TEXT NULL
candidate_context_hash TEXT NULL
structured_result_json TEXT NULL
validation_status TEXT NOT NULL
validation_reason TEXT NULL
```

The normalized fields support queries.

The optional structured JSON supports audit/debug, but should not replace typed columns.

### activity_occurrences

```text
id TEXT PRIMARY KEY
canonical_activity_id TEXT NOT NULL
raw_capture_id TEXT NOT NULL
effective_interpretation_id TEXT NOT NULL
captured_at INTEGER NOT NULL
occurred_at INTEGER NOT NULL
time_precision TEXT NOT NULL
activity_state TEXT NOT NULL
visibility_status TEXT NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

Indexes:

- `(canonical_activity_id, occurred_at DESC)`
- `occurred_at DESC`
- `raw_capture_id`

### corrections

```text
id TEXT PRIMARY KEY
occurrence_id TEXT NOT NULL
created_at INTEGER NOT NULL
source TEXT NOT NULL
reason TEXT NULL

previous_canonical_activity_id TEXT NULL
new_canonical_activity_id TEXT NULL

previous_occurred_at INTEGER NULL
new_occurred_at INTEGER NULL

previous_time_precision TEXT NULL
new_time_precision TEXT NULL

previous_activity_state TEXT NULL
new_activity_state TEXT NULL

previous_effective_interpretation_id TEXT NULL
new_effective_interpretation_id TEXT NULL
```

Corrections create an audit trail.

### watch_capture_outbox

Watch-local table, not phone authoritative schema.

```text
capture_id TEXT PRIMARY KEY
raw_text TEXT NOT NULL
captured_at INTEGER NOT NULL
speech_confidence REAL NULL
status TEXT NOT NULL
attempt_count INTEGER NOT NULL
last_attempt_at INTEGER NULL
created_at INTEGER NOT NULL
```

This can be Room/DataStore/another durable local mechanism appropriate to Wear OS.

## 3. Relationship overview

```text
RawCapture
   |
   +------< Interpretation
   |
   +------1 ActivityOccurrence >------1 CanonicalActivity
                        |
                        +------< Correction

CanonicalActivity
   |
   +------< ActivityAlias
```

## 4. Why RawCapture is separate

Do not store only:

```text
ActivityOccurrence.originalText
```

even though that seems simpler.

A separate RawCapture allows:

- failed interpretations to exist before an occurrence
- multiple interpretations of one capture
- reprocessing
- transport state
- provenance
- future transcription correction
- audit tooling

## 5. Raw text immutability

Application repository code should not expose a generic update method that allows `raw_text` replacement.

Prefer APIs such as:

```text
createRawCapture(...)
updateProcessingState(...)
```

rather than:

```text
updateRawCapture(arbitrary object)
```

Database triggers are optional; application-layer immutability plus tests may be sufficient.

## 6. Original speech vs corrected transcription

Future repair tooling may determine that speech recognition itself was wrong.

Example:

Original STT:

> "I mode the lawn."

Human-corrected transcript:

> "I mowed the lawn."

Do not replace the original.

A future table may be added:

```text
transcription_corrections
```

or a generalized evidence annotation model.

This is post-MVP but should remain possible.

## 7. Time representation

Persist timestamps as machine-safe instants, commonly epoch milliseconds, plus enough context where required.

For user interpretation, preserve:

- capture instant
- occurrence instant/date
- precision
- original temporal expression in Interpretation

`time_precision` examples:

- `EXACT`
- `APPROXIMATE`
- `DATE_ONLY`
- `INFERRED_NOW`

Avoid manufacturing false precision.

## 8. Activity merges

Future duplicate repair may merge:

`Replace HVAC filter` -> `Replace furnace filter`

Do not rewrite raw evidence.

Possible approach:

- mark old canonical activity `MERGED`
- set `merged_into_activity_id`
- update effective occurrences transactionally or resolve aliases at query time
- preserve correction/merge history

Exact merge implementation is post-MVP.

## 9. Deletion

Because audit history matters, distinguish:

- user-visible removal
- hard deletion

Recommended MVP:

`visibility_status = ACTIVE | HIDDEN`

Hard deletion may exist for privacy, but must be deliberate and cascade safely.

## 10. Export and future cloud sync

Every domain record should have:

- globally unique ID
- created timestamp
- updated timestamp where mutable
- stable foreign keys

A future sync layer may add:

- version
- device origin
- tombstone
- sync state

Do not add these until needed unless implementation cost is negligible.

## 11. Database migrations

Rules:

- explicit Room migrations
- migration tests
- no destructive fallback in release
- preserve raw captures
- preserve correction history
- document schema version changes

## 12. Example lifecycle

User says:

> "I cut the grass yesterday."

### Step 1

Insert RawCapture:

```text
id = cap_A
raw_text = "I cut the grass yesterday."
captured_at = Sep 15
```

### Step 2

Insert Interpretation:

```text
id = int_A
matched_activity_id = act_mow
temporal_expression = "yesterday"
```

### Step 3

Insert ActivityOccurrence:

```text
id = occ_A
canonical_activity_id = act_mow
raw_capture_id = cap_A
effective_interpretation_id = int_A
occurred_at = Sep 14
```

### Later correction

Suppose the user says it actually meant `Trim lawn`.

Create:

- a correction record
- optionally a user-generated interpretation record
- update occurrence effective reference

Do not modify `cap_A.raw_text`.
