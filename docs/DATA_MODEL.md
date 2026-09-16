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

## 2. Entities (Room schema version 1)

Schema version 1 is implemented in `core-data`; the exported Room schema
(`core-data/schemas/com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase/1.json`)
is the authoritative definition. The tables below mirror it.

General rules for all phone tables:

- Every `id` is app-generated UUIDv7 text (canonical lowercase, 36 characters), never an auto-increment integer (ADR-026).
- Enum-valued columns store the Kotlin enum constant's `name` as TEXT (see section 2.1). Reading back a stored value that is not a known constant name throws; it never falls back to a default.
- Every foreign key is `ON DELETE NO ACTION ON UPDATE NO ACTION` — there is no `CASCADE` and no `SET NULL`. Deleting a row that other rows still reference fails. Room turns on SQLite foreign-key enforcement itself.
- Each foreign-key child column is indexed (listed per table below), so reference checks and joins do not scan.

### raw_captures

Represents the original captured/transcribed user input.

Fields:

```text
id TEXT PRIMARY KEY
source TEXT NOT NULL
source_surface TEXT NULL
captured_at INTEGER NOT NULL
captured_zone_id TEXT NOT NULL
raw_text TEXT NOT NULL
speech_confidence REAL NULL
speech_alternatives_json TEXT NULL
processing_state TEXT NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

`raw_text` is logically immutable.

If a later transcription correction is needed, represent it separately.

`captured_zone_id` is the IANA time-zone id (e.g. `Europe/London`) in effect at capture time. It is what keeps `DATE_ONLY` and other resolved times meaningful after the user travels (ADR-018): the capture's occurrence and any corrections to it are resolved in this zone, so no other table carries a zone column.

Indexes:

- `captured_at`
- `processing_state`

Foreign keys: none.

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

- `normalized_name` (non-unique)
- `status`
- `merged_into_activity_id` (foreign-key child)

`normalized_name` is deliberately **not** unique: merged or archived activities may share a name with an active one, and Room cannot declare a partial unique index (e.g. unique only where `status = 'ACTIVE'`). Preventing duplicate active activities is resolution policy, not a schema constraint.

Foreign keys:

- `merged_into_activity_id` -> `canonical_activities.id`

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

Indexes:

- UNIQUE `(canonical_activity_id, normalized_alias)` — no duplicate normalized alias for the same canonical activity. This index also serves the foreign-key child column.

Foreign keys:

- `canonical_activity_id` -> `canonical_activities.id`

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

Indexes:

- `raw_capture_id` (non-unique — one capture may have several interpretations)
- `matched_activity_id`

Foreign keys:

- `raw_capture_id` -> `raw_captures.id`
- `matched_activity_id` -> `canonical_activities.id`

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

- `(canonical_activity_id ASC, occurred_at DESC)` — also serves the `canonical_activity_id` foreign key
- `occurred_at DESC`
- UNIQUE `raw_capture_id`
- `effective_interpretation_id`

`raw_capture_id` is unique: schema version 1 allows exactly one occurrence per raw capture. This is the cheapest guarantee that a retried acceptance or a duplicate watch delivery cannot log the same activity twice. Supporting several activities from one utterance later would be an ordinary migration.

Foreign keys:

- `canonical_activity_id` -> `canonical_activities.id`
- `raw_capture_id` -> `raw_captures.id`
- `effective_interpretation_id` -> `interpretations.id`

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

A correction records previous/new values only for the fields that actually change, and updates the occurrence in the same transaction. It does **not** fabricate an interpretation row: a user correction writes a `corrections` row (with `source = USER`) and updates the occurrence. `effective_interpretation_id` changes only when a real new interpretation is accepted, and that change is recorded in `previous_effective_interpretation_id` / `new_effective_interpretation_id`. Whether a change was user-originated is carried by `corrections.source`, not by a synthetic interpretation.

Indexes (one per foreign-key child column):

- `occurrence_id`
- `previous_canonical_activity_id`
- `new_canonical_activity_id`
- `previous_effective_interpretation_id`
- `new_effective_interpretation_id`

Foreign keys:

- `occurrence_id` -> `activity_occurrences.id`
- `previous_canonical_activity_id` -> `canonical_activities.id`
- `new_canonical_activity_id` -> `canonical_activities.id`
- `previous_effective_interpretation_id` -> `interpretations.id`
- `new_effective_interpretation_id` -> `interpretations.id`

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

This can be Room/DataStore/another durable local mechanism appropriate to Wear OS. It is not part of phone schema version 1.

### 2.1 Vocabularies

Each enum lives in `core-domain` (`com.mcfrenchpants.activityledger.core.domain.model`) and is stored as the constant name.

| Enum | Values | Columns |
|---|---|---|
| `ActivityState` | `COMPLETED`, `IN_PROGRESS` | `interpretations.activity_state`, `activity_occurrences.activity_state`, `corrections.previous_activity_state` / `new_activity_state` |
| `ActivityResolution` | `EXISTING_ACTIVITY`, `NEW_ACTIVITY`, `AMBIGUOUS`, `UNRESOLVED` | `interpretations.activity_resolution` |
| `ProcessingState` | `CAPTURED`, `QUEUED_FOR_PHONE`, `TRANSCRIBED`, `INTERPRETING`, `INTERPRETED`, `PERSISTED`, `NEEDS_REVIEW`, `FAILED_RETRYABLE`, `FAILED_FINAL` | `raw_captures.processing_state` |
| `TimePrecision` | `EXACT`, `APPROXIMATE`, `DATE_ONLY`, `INFERRED_NOW` | `interpretations.time_precision`, `activity_occurrences.time_precision`, `corrections.previous_time_precision` / `new_time_precision` |
| `VisibilityStatus` | `ACTIVE`, `HIDDEN` | `activity_occurrences.visibility_status` |
| `CanonicalActivityStatus` | `ACTIVE`, `MERGED`, `ARCHIVED` | `canonical_activities.status` |
| `AliasSource` | `USER_CORRECTION`, `AI_CONFIRMED`, `SEEDED`, `MANUAL` | `activity_aliases.source` |
| `CorrectionSource` | `USER`, `REINTERPRETATION` | `corrections.source` |
| `CaptureSource` | `PHONE_VOICE`, `PHONE_TEXT`, `WATCH_VOICE` | `raw_captures.source` |
| `InterpretationOperation` | `LOG_ACTIVITY`, `QUERY_HISTORY`, `UNSUPPORTED` | `interpretations.operation` |
| `ValidationStatus` | `VALID`, `INVALID`, `NEEDS_REVIEW` | `interpretations.validation_status` |
| `ConfidenceBand` | `HIGH`, `MEDIUM`, `LOW` | `interpretations.model_confidence_band` |

Adding, renaming, or removing a constant changes persisted data and needs a migration decision.

## 3. Relationship overview

```text
RawCapture
   |
   +------< Interpretation
   |
   +------0..1 ActivityOccurrence >------1 CanonicalActivity
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

Schema version 1 uses **no SQLite triggers**. Raw-text immutability is enforced by the shape of the data-layer API (there is no operation that replaces `raw_text`) plus tests. Triggers were rejected because Room does not track them in its exported schema, so every future migration would have to remember to recreate them by hand, and a forgotten one would silently remove the protection.

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

Hard deletion may exist for privacy, but must be deliberate and cascade safely. Schema version 1 foreign keys never cascade (`NO ACTION`), so any future hard-delete path must remove dependent rows explicitly and in order.

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
- migration tests (a reusable migration test harness exists; see `TEST_STRATEGY.md` section 11)
- no destructive fallback in any build — `fallbackToDestructiveMigration` is never used, and tests guard this
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

In one transaction:

- create a correction record (`source = USER`, previous/new canonical activity)
- update the occurrence's `canonical_activity_id`

No interpretation record is fabricated for the user's correction; `effective_interpretation_id` stays `int_A`. It changes only if a real new interpretation is later accepted, and that change is itself recorded in the correction's previous/new interpretation columns.

Do not modify `cap_A.raw_text`.
