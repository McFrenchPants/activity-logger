# Requirements

## 1. Requirement conventions

Priority:

- **MUST** — required for MVP
- **SHOULD** — expected unless a documented constraint prevents it
- **MAY** — optional

IDs are stable references for tests, issues, and implementation plans.

---

# 2. Capture requirements

### CAP-001 — Phone voice capture

The phone application MUST provide a direct voice-capture action.

Acceptance criteria:

- user can begin capture with one intentional UI action
- recognized text is visible or briefly reviewable
- successful high-confidence capture requires no explicit Save action

### CAP-002 — Wear OS voice capture

The Wear OS application MUST provide a low-friction voice-capture flow.

Acceptance criteria:

- capture can be launched from the watch app
- design supports tile and/or complication entry
- successful capture can complete without category selection or form entry

### CAP-003 — Raw text preservation

Every accepted voice capture MUST preserve the original recognized text as an immutable raw capture field.

The raw text MUST NOT be replaced when:

- semantic interpretation changes
- activity canonical name changes
- user makes a correction
- a database migration occurs

### CAP-004 — Capture provenance

Every raw capture MUST include:

- globally unique capture ID
- capture timestamp
- source device type
- source application surface when available
- recognized text
- speech confidence/alternatives when available and practical
- processing status

### CAP-005 — Idempotency

Repeated delivery of the same capture ID MUST NOT create duplicate activity occurrences.

---

# 3. Speech recognition requirements

### STT-001 — Separation from semantic inference

Speech recognition MUST be implemented behind a distinct interface from semantic interpretation.

### STT-002 — Local operation

MVP speech recognition MUST operate without requiring a remote server for normal supported-device use.

### STT-003 — Raw recognition result

The speech recognizer MUST return raw text without replacing it with semantic/canonical wording.

### STT-004 — Failure handling

Recognition failures MUST be distinguishable from semantic interpretation failures.

### STT-005 — Alternatives

If the selected speech API exposes recognition alternatives/confidence economically, the implementation SHOULD preserve useful metadata for later diagnostics.

---

# 4. Semantic interpretation requirements

### AI-001 — On-device inference

MVP semantic interpretation MUST execute on the phone using supported Gemini Nano / AICore-backed Android APIs.

### AI-002 — No watch inference

Wear OS MUST NOT be responsible for semantic inference.

### AI-003 — Structured result

Semantic interpretation MUST produce a typed structured result.

Arbitrary prose MUST NOT be directly persisted as authoritative application state.

### AI-004 — Existing activity preference

The interpreter MUST prefer a semantically equivalent existing canonical activity over creating a new one.

### AI-005 — New activity proposal

When no existing activity is semantically equivalent, the interpreter MAY propose a new canonical activity.

### AI-006 — Candidate validation

Any activity identifier returned by the model MUST exist in the candidate set supplied to the model.

Invalid identifiers MUST be rejected.

### AI-007 — Confidence policy

The application MUST define deterministic policy for:

- auto-accept
- accept with low-confidence marker
- require correction/review
- fail interpretation

The model's self-reported confidence MUST NOT be the sole policy input.

### AI-008 — Temporal extraction

The interpreter SHOULD extract:

- explicit date/time phrases
- relative phrases such as "yesterday"
- state language such as "I'm mowing" vs "I mowed"

Date/time resolution SHOULD be performed deterministically by application code wherever practical.

### AI-009 — Immutable original

Semantic inference MUST never alter the raw capture text.

### AI-010 — Provenance

Each interpretation MUST store enough information to audit:

- interpreter version
- prompt/schema version
- model/API capability version when available
- candidate set version/hash or relevant context hash
- raw structured model response or normalized equivalent where safe
- interpretation timestamp
- validation outcome

---

# 5. Canonical activity requirements

### ACT-001 — Stable identity

A canonical activity MUST have a stable internal ID independent of its display name.

### ACT-002 — Display name

A canonical activity MUST have a concise human-readable name.

Examples:

- Mow lawn
- Replace furnace filter
- Clean gutters

### ACT-003 — Alias support

The domain model MUST support alternate phrases associated with a canonical activity.

### ACT-004 — No destructive rename

Renaming a canonical activity MUST NOT modify historical raw captures.

### ACT-005 — Merge-ready design

The data model SHOULD permit future merging of duplicate canonical activities without losing occurrence provenance.

### ACT-006 — Distinguish nearby concepts

The system MUST support semantically related but distinct activities.

Example:

- `Mow lawn`
- `Edge lawn`

must be allowed to exist independently.

---

# 6. Activity occurrence requirements

### OCC-001 — Occurrence record

Each logged event MUST be represented as an activity occurrence.

### OCC-002 — Canonical reference

An occurrence MUST reference a canonical activity.

### OCC-003 — Raw capture linkage

An occurrence MUST link back to the raw capture that produced it when created from voice input.

### OCC-004 — Capture time vs occurrence time

The schema MUST distinguish:

- when the utterance was captured
- when the activity occurred

### OCC-005 — State

MVP SHOULD support at least:

- completed
- in-progress

If an implementation milestone deliberately launches completed-only, that deviation MUST be documented.

### OCC-006 — Delete policy

Deleting/hiding an occurrence from normal history SHOULD be represented in an auditable way rather than erasing raw evidence immediately.

---

# 7. Correction and audit requirements

### COR-001 — Manual correction

The phone app MUST permit correction of an incorrectly interpreted activity occurrence.

### COR-002 — Preserve original

Correction MUST NOT alter:

- raw dictated text
- original interpretation record

### COR-003 — Effective interpretation

The system MUST distinguish between:

- original interpretation
- corrected/effective interpretation

### COR-004 — Correction metadata

Correction MUST record:

- timestamp
- previous effective activity
- new activity
- reason/source if available
- user-originated flag

### COR-005 — Future external repair tools

The database design MUST support a future external/cloud/web review tool that can compare:

- what the user said
- what speech recognition produced
- what AI interpreted
- what the effective corrected activity became

No such external tool is required in MVP.

---

# 8. History requirements

### HIS-001 — Chronological history

The phone app MUST display a chronological activity history.

### HIS-002 — Activity detail

The user MUST be able to view occurrences for a canonical activity.

### HIS-003 — Original phrase visibility

The activity detail or correction flow MUST make the original raw phrase inspectable.

### HIS-004 — Search/filter

MVP SHOULD provide basic text/activity filtering in addition to natural-language queries.

---

# 9. Natural-language query requirements

### QRY-001 — Historical questions

The user MUST be able to ask supported historical questions in natural language.

### QRY-002 — Deterministic execution

The model SHOULD translate the question into a constrained query intent/parameter structure.

Application code MUST execute the actual database query.

### QRY-003 — Supported MVP query intents

At minimum:

- last occurrence of activity
- previous occurrences of activity
- count within time range
- occurrences within time range
- approximate interval/frequency summary

### QRY-004 — No arbitrary SQL from model

The application MUST NOT execute model-generated SQL directly.

### QRY-005 — Ambiguous activity handling

If a question could refer to multiple canonical activities, the system SHOULD ask the user to disambiguate rather than silently choosing when confidence is insufficient.

---

# 10. Wear OS transport requirements

### WEAR-001 — Phone authority

The watch MUST NOT become the authoritative database.

### WEAR-002 — Capture IDs

Each watch capture MUST be assigned a unique ID before transport.

### WEAR-003 — Durable pending queue

The watch MUST maintain a durable pending queue until the phone acknowledges receipt.

### WEAR-004 — Retry

Disconnected captures MUST retry when connectivity becomes available.

### WEAR-005 — Acknowledgement

The phone MUST acknowledge capture receipt/processing status using the capture ID.

### WEAR-006 — Duplicate safety

Retries MUST NOT create duplicate phone records.

### WEAR-007 — Minimal response

The watch SHOULD display only concise result states such as:

- Saved: Mow lawn
- Queued
- Could not understand
- Phone unavailable

---

# 11. Local database requirements

### DB-001 — Room

MVP MUST use a local structured database suitable for relational queries; Room is the selected implementation direction.

### DB-002 — Migration safety

All schema changes after initial release MUST use explicit migrations.

### DB-003 — No destructive migration in production

Production migrations MUST NOT use destructive fallback that would erase user history.

### DB-004 — Export-ready identifiers

Primary identifiers SHOULD use values safe for future cloud synchronization, such as UUIDs/ULIDs rather than phone-local autoincrement IDs as cross-system identity.

### DB-005 — Audit retention

Interpretation/correction provenance MUST be retained.

---

# 12. Offline requirements

### OFF-001 — Core capture offline

Normal supported-device capture MUST work without Internet access.

### OFF-002 — Query offline

Historical queries over locally stored data MUST work without Internet access.

### OFF-003 — No hidden cloud AI fallback

MVP MUST NOT silently send user activity text to a cloud AI service when Gemini Nano is unavailable.

### OFF-004 — Capability failure

If required local AI capability is unavailable, the app MUST clearly report the limitation and preserve raw capture if feasible.

---

# 13. Privacy requirements

### PRI-001 — Local content

MVP raw capture text and activity history MUST remain on-device except for phone/watch Data Layer transfer.

### PRI-002 — No content telemetry

Raw utterances MUST NOT be included in analytics or crash telemetry by default.

### PRI-003 — Debug logging

Production logs MUST avoid printing user activity text unless an explicit developer diagnostic mode is enabled.

---

# 14. Performance requirements

### PERF-001 — Capture responsiveness

The UI MUST immediately acknowledge that speech capture has started.

### PERF-002 — Asynchronous processing

Speech recognition, AI inference, and database writes MUST not block the main UI thread.

### PERF-003 — Short model output

Model output SHOULD be kept intentionally small and structured.

### PERF-004 — Candidate limits

The interpreter MUST avoid blindly placing an unbounded activity catalog into every prompt.

A candidate-selection strategy must be implemented if the catalog grows beyond practical prompt limits.

---

# 15. Reliability requirements

### REL-001 — Processing state machine

Raw captures MUST have explicit processing state sufficient to recover from interrupted processing.

Example states:

- captured
- awaiting_phone
- transcribed
- interpreting
- interpreted
- persisted
- needs_review
- failed

### REL-002 — Crash recovery

A captured utterance SHOULD survive process death once it has reached a durable local queue.

### REL-003 — Transactional persistence

Creation of interpretation and occurrence records SHOULD use transactions to avoid partial authoritative state.

---

# 16. Test requirements

### TST-001 — Semantic corpus

The repository MUST contain semantic regression cases.

### TST-002 — Temporal cases

The corpus MUST cover relative date/time expressions.

### TST-003 — Synonym cases

The corpus MUST cover semantically equivalent phrases.

### TST-004 — Near-neighbor cases

The corpus MUST cover related but distinct activities.

### TST-005 — Correction tests

Tests MUST verify that corrections never overwrite original raw captures.

### TST-006 — Transport idempotency

Tests MUST verify duplicate watch deliveries do not create duplicate occurrences.

### TST-007 — Room migrations

Released database migrations MUST have migration tests.
