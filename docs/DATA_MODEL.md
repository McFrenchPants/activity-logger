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

## 2. Entities (Room schema version 2)

Schema version 2 is implemented in `core-data`; the exported Room schema
(`core-data/schemas/com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase/2.json`)
is the authoritative definition, and `1.json` stays tracked for the 1 -> 2 migration (section 11). The tables below mirror version 2. The domain services (work item DS1) added data-layer operations and usage rules but no schema change; version 2 (TG2.1, ADR-040) added the subject/action tag tables, the nullable subject/action pair on `canonical_activities` and the duration columns. Columns new in version 2 are marked `-- v2` below.

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
subject_id TEXT NULL        -- v2
action_id TEXT NULL         -- v2
```

Since version 2 a canonical activity is also the **pair** of a subject tag and an action tag (ADR-040). `subject_id` / `action_id` are **nullable until the v3 capture path is removed**: rows written by the v3 path carry no tags. Tightening them to NOT NULL is a later cleanup.

Indexes:

- `normalized_name` (non-unique)
- `status`
- `merged_into_activity_id` (foreign-key child)
- UNIQUE `(subject_id, action_id)` — a tagged pair exists at most once. SQLite treats NULLs as distinct, so untagged (v3) rows are not constrained. This index also serves the `subject_id` foreign key.
- `action_id` (foreign-key child)

`normalized_name` is deliberately **not** unique: merged or archived activities may share a name with an active one, and Room cannot declare a partial unique index (e.g. unique only where `status = 'ACTIVE'`). Preventing duplicate active activities is resolution policy, not a schema constraint.

Foreign keys:

- `merged_into_activity_id` -> `canonical_activities.id`
- `subject_id` -> `subjects.id`
- `action_id` -> `actions.id`

### subjects and actions

New in version 2. A subject is what an activity was done to ("furnace", "hot tub"); an action is what was done ("change filter", "mow"). The two tables have identical shape; `actions` uses `merged_into_action_id` where `subjects` uses `merged_into_subject_id`.

```text
id TEXT PRIMARY KEY
display_name TEXT NOT NULL
normalized_name TEXT NOT NULL
status TEXT NOT NULL
merged_into_subject_id TEXT NULL     -- actions: merged_into_action_id
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

`normalized_name` is the `TagNormalizer.key` of the display name (ADR-039). Like `canonical_activities.normalized_name` it is deliberately **not** unique: uniqueness among `ACTIVE` tags is enforced by the repository, not the schema. `status` is a `TagStatus`.

Indexes:

- `normalized_name` (non-unique)
- `status`
- `merged_into_subject_id` / `merged_into_action_id` (foreign-key child)

Foreign keys:

- `merged_into_subject_id` -> `subjects.id` / `merged_into_action_id` -> `actions.id`

### subject_aliases and action_aliases

New in version 2. Alternative wordings of a subject or action tag. Identical shape; `action_aliases` has `action_id` where `subject_aliases` has `subject_id`.

```text
id TEXT PRIMARY KEY
subject_id TEXT NOT NULL             -- action_aliases: action_id
alias_text TEXT NOT NULL
normalized_alias TEXT NOT NULL
source TEXT NOT NULL
created_at INTEGER NOT NULL
```

`normalized_alias` is the `TagNormalizer.key` of `alias_text`; `source` is an `AliasSource`. There is no confidence column.

Indexes:

- UNIQUE `(subject_id, normalized_alias)` / `(action_id, normalized_alias)` — also serves the foreign-key child column.

Foreign keys:

- `subject_id` -> `subjects.id` / `action_id` -> `actions.id`

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
extracted_subject TEXT NULL          -- v2
extracted_action TEXT NULL           -- v2
duration_expression TEXT NULL        -- v2
resolved_duration_seconds INTEGER NULL  -- v2
```

The four version-2 columns hold, for the tag path (ADR-038), the subject, action and duration words the model extracted and the duration resolved from them. They are null for v3 rows.

The normalized fields support queries.

The optional structured JSON supports audit/debug, but should not replace typed columns.

Rules applied by the domain services (work item DS1; no schema change):

- `matched_activity_id` holds the model's activity id only if that id is in the ACTIVE catalog loaded for that processing run; otherwise it is null. It is a foreign key, so storing an invented id would make the write fail and the capture would never reach review. `structured_result_json` holds whatever raw answer text the interpreter supplies; the Gemini Nano adapter always supplies null, because ML Kit's typed API returns an already-decoded object and never the model's raw text (ARCHITECTURE §6/§13). Validation judges the unmodified decoded answer (ADR-027).
- `validation_reason` is null when there are no reasons; otherwise it is the `ValidationReason` constant names (`core-domain`, `...core.domain.validation`), sorted alphabetically and comma-joined without spaces, e.g. `CONFIDENCE_NOT_HIGH,TIME_UNRESOLVABLE`. The names are persisted text: renaming or removing a constant is a data change.
- `validation_status` is `VALID` for accepted interpretations, `NEEDS_REVIEW` for answers awaiting review, and `INVALID` for rejected answers and for interpreter failures `MALFORMED` / `OTHER` (stored with `operation = UNSUPPORTED`, `activity_resolution = UNRESOLVED` and reason `INTERPRETER_OUTPUT_MALFORMED` / `INTERPRETER_FAILED`).
- **User-resolution interpretations.** When the user resolves a capture that is awaiting review (or failed), `ReviewResolutionService` inserts a new interpretation with `interpreter_version = "user-resolution"`, `prompt_version = "none"`, `schema_version = 1`, `operation = LOG_ACTIVITY`, `validation_status = VALID`, the chosen activity and time, and null `temporal_expression`, `model_confidence_band`, `candidate_context_hash` and `structured_result_json`; that interpretation is then accepted. Earlier interpretations of the capture are never modified.

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
duration_seconds INTEGER NULL   -- v2
```

`duration_seconds` is how long the activity lasted, when stated; null otherwise and for v3 rows.

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

previous_duration_seconds INTEGER NULL   -- v2
new_duration_seconds INTEGER NULL        -- v2
```

Corrections create an audit trail.

A correction records previous/new values only for the fields that actually change, and updates the occurrence in the same transaction. It does **not** fabricate an interpretation row: a user correction writes a `corrections` row (with `source = USER`) and updates the occurrence. `effective_interpretation_id` changes only when a real new interpretation is accepted, and that change is recorded in `previous_effective_interpretation_id` / `new_effective_interpretation_id`. Whether a change was user-originated is carried by `corrections.source`, not by a synthetic interpretation.

A correction may move an occurrence to a **new** activity: the canonical activity is created `ACTIVE` in the same transaction as the correction and the occurrence update (`LedgerWriteDao.applyCorrectionCreatingActivity`), and `new_canonical_activity_id` is its id. Corrections to an existing activity require that activity to be `ACTIVE`. `CorrectionService` refuses corrections of `HIDDEN` occurrences; corrections have no visibility columns, so hiding/restoring an occurrence is not a correction.

**Review resolution is a different operation.** Resolving a capture that is awaiting review does not correct anything: the capture has no occurrence yet, so there is no row for a `corrections` record to refer to. It creates the capture's first occurrence through the normal accept operation, which requires an effective interpretation (`effective_interpretation_id` is NOT NULL). That is why review resolution does insert an interpretation — a user-resolution row, marked by `interpreter_version = "user-resolution"` (see interpretations above) — while a correction of an existing occurrence still never fabricates one.

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

This can be Room/DataStore/another durable local mechanism appropriate to Wear OS. It is not part of the phone schema.

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
| `AliasSource` | `USER_CORRECTION`, `AI_CONFIRMED`, `SEEDED`, `MANUAL` | `activity_aliases.source`, `subject_aliases.source`, `action_aliases.source` |
| `TagStatus` | `ACTIVE`, `MERGED` | `subjects.status`, `actions.status` |
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

Subject -----< CanonicalActivity (pair) >----- Action      (subject_id / action_id, nullable until v3 is removed)
   |                                             |
   +------< SubjectAlias                         +------< ActionAlias

Subject / Action ---0..1 merged_into (self)
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

The schema (versions 1 and 2) uses **no SQLite triggers**. Raw-text immutability is enforced by the shape of the data-layer API (there is no operation that replaces `raw_text`) plus tests. Triggers were rejected because Room does not track them in its exported schema, so every future migration would have to remember to recreate them by hand, and a forgotten one would silently remove the protection.

The only writes to occurrences and corrections are the transactional operations of `LedgerWriteDao` (`core-data`), each run in one database transaction so any failure rolls back every write it made. The domain reaches them through `ActivityRepository` (created for the app by `createActivityRepository(context, clock)`):

- `acceptInterpretation` — interpretation insert, new `ACTIVE` canonical activity if requested, occurrence insert, raw capture `processing_state = PERSISTED`. Idempotent per capture (an existing occurrence's id is returned and nothing is written). Refuses a non-`ACTIVE` existing target activity.
- `recordOutcome` — for a non-accepting outcome: raw capture `processing_state` update plus an optional interpretation insert, atomically. Only `NEEDS_REVIEW`, `FAILED_RETRYABLE` or `FAILED_FINAL` are allowed; refused when the capture already has an occurrence (an accepted capture's outcome is never rewritten). Never creates an occurrence.
- `applyCorrection` — one `corrections` row plus the occurrence update, only for fields that actually change; writes nothing when nothing changes. Refuses a non-`ACTIVE` target activity.
- `applyCorrectionCreatingActivity` — new `ACTIVE` canonical activity, then the correction and occurrence update as `applyCorrection`.

These operations were added without any schema change (in schema version 1); version 2 left them unchanged, and existing writers store NULL in every version-2 column.

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

Hard deletion may exist for privacy, but must be deliberate and cascade safely. Schema foreign keys never cascade (`NO ACTION`, versions 1 and 2), so any future hard-delete path must remove dependent rows explicitly and in order.

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

**Version 1 -> 2 (`MIGRATION_1_2`, ADR-040).** Purely additive: it creates `subjects`, `actions`, `subject_aliases`, `action_aliases` and their indices, adds the nullable columns marked `-- v2` in section 2, and adds the `(subject_id, action_id)` unique index and the `action_id` index to `canonical_activities`. Nothing is dropped or rebuilt; every version-1 row keeps every value and gets NULL in the new columns. The pair columns are added with `ALTER TABLE ... ADD COLUMN ... REFERENCES ...` (allowed by SQLite because their default is NULL), so `canonical_activities` is not rebuilt. The migration harness runs the version-1 seed through it and validates the result against `2.json`. The owner's clean start for the tag design is done outside the code (clearing app storage once when the Stage 3 build is installed); there is no wipe. Version 2 has not been installed anywhere yet, so it may still be amended in place before Stage 3 ships.

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
