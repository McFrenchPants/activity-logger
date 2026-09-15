# Product Specification

## 1. Product name

Working name: **Activity Ledger**

The name may change. Product behavior defined here is authoritative regardless of final branding.

## 2. Product summary

Activity Ledger is a personal, local-first activity history application for Android and Wear OS.

Its primary purpose is to make recording everyday actions so frictionless that the user can realistically log many small activities throughout the day.

It then normalizes varied natural-language descriptions into a stable activity catalog so the user can later answer questions such as:

- When did I last change the furnace filter?
- How often have I mowed the lawn this summer?
- What did I do around the house last Saturday?
- How many times have I cleaned the pool this year?
- What was the previous time I changed the oil?

The application is not primarily about future planning. It is about creating a reliable historical record.

## 3. Problem statement

Humans rarely use identical words every time they describe the same action.

For example:

- "I cut the grass."
- "I mowed the lawn."
- "Finished mowing."
- "Did the backyard."
- "The grass is done."

A literal text log treats these as unrelated strings.

A useful activity ledger should understand that many of them refer to the same underlying canonical activity, while still preserving exactly what the user originally said.

The application therefore needs both:

1. **immutable source evidence** — the original dictated/transcribed phrase
2. **normalized structured meaning** — the interpreted activity record

## 4. Product principles

### 4.1 Logging, not planning

The app records activity history.

Planning features are deliberately excluded from MVP.

### 4.2 Capture first

The user should not stop to:

- choose a category
- choose a project
- type a title
- select a timestamp
- choose tags
- click Save

The system should infer what it safely can.

### 4.3 Natural language is the primary interface

Users should speak normally rather than learn command syntax.

Good:

> "I changed the furnace filter yesterday."

Not required:

> "Log activity. Category home maintenance. Action replace HVAC filter. Date yesterday."

### 4.4 Equivalent language maps to canonical activity concepts

The system should maintain stable concepts such as:

- Mow lawn
- Replace furnace filter
- Clean gutters
- Change vehicle oil

Individual occurrences reference those concepts.

### 4.5 Preserve original evidence forever

The raw dictated/transcribed text is a first-class domain artifact.

The original capture is never replaced by:

- an AI-normalized phrase
- a correction
- a renamed canonical activity
- a future migration

This enables future auditing and repair tooling.

### 4.6 AI is an interpreter, not the database

The model converts ambiguous human language into structured candidate meaning.

Application code validates and persists that meaning.

### 4.7 Local-first

Normal capture must not require Internet access.

MVP data resides on the phone.

### 4.8 Phone is authoritative

The phone owns:

- speech interpretation pipeline
- semantic inference
- canonical activity catalog
- database
- query processing

The watch is a capture interface.

### 4.9 Quiet success

High-confidence captures should complete without making the user manage them.

The ideal confirmation is a brief visual/haptic acknowledgement.

### 4.10 Corrections improve trust

AI will sometimes be wrong.

The system must make errors recoverable and auditable.

## 5. Primary user

MVP is designed as a single-user personal application.

No account or multi-user model is required.

## 6. Primary use cases

### UC-01 Log a completed activity

User says:

> "I changed the furnace filter."

System records a completed occurrence of `Replace furnace filter`.

### UC-02 Log a completed activity in the past

User says:

> "I cleaned the gutters Saturday."

System stores:

- capture time = now
- occurrence time/date = Saturday, as deterministically resolved from current context

### UC-03 Log an activity currently underway

User says:

> "I'm mowing the lawn."

System stores an occurrence with an in-progress state if the domain model supports it.

### UC-04 Resolve synonymous wording

Existing canonical activity:

`Mow lawn`

User says:

> "I cut the grass."

System should match the existing canonical activity instead of creating `Cut grass`.

### UC-05 Create a new canonical activity

User says:

> "I flushed the water heater."

No semantically equivalent existing activity exists.

System proposes or creates `Flush water heater`, subject to confidence policy.

### UC-06 Ask historical question

User asks:

> "When did I last mow the lawn?"

System identifies `Mow lawn`, performs a deterministic database query, and returns the latest matching occurrence.

### UC-07 Correct a bad interpretation

Original:

> "I edged the lawn."

Incorrect AI interpretation:

`Mow lawn`

User corrects it to:

`Edge lawn`

System:

- preserves raw utterance
- records the correction
- updates the effective interpretation
- keeps enough provenance to audit the original model output

## 7. Core success metric

A normal activity capture should require:

> **One intentional action before speech and zero required actions afterward.**

This applies when:

- speech was recognized
- semantic interpretation is valid
- confidence/policy threshold is satisfied
- persistence succeeds

## 8. MVP scope

### Included

- Android phone application
- Wear OS capture application
- one-tap/tile/complication-oriented capture entry
- local speech recognition
- Gemini Nano semantic interpretation on phone
- structured model output
- canonical activity catalog
- aliases/synonym learning support
- activity occurrence history
- immutable raw capture storage
- interpretation provenance
- manual correction on phone
- natural-language historical questions
- deterministic database query execution
- local Room storage
- watch queue when phone temporarily unavailable
- watch-to-phone synchronization
- offline-first operation
- semantic regression corpus
- export-ready schema design

### Excluded

- cloud sync
- desktop input
- browser input
- reminders
- recurring schedules
- task planning
- due dates
- projects
- collaboration
- accounts
- gamification
- productivity scores
- inferred background activities
- location-triggered automatic logs
- external calendar integration
- automatic health/fitness tracking
- cross-platform iOS support

## 9. Key product risks

### 9.1 Misclassification

If the AI routinely maps different activities together, historical data becomes unreliable.

Mitigation:

- preserve raw captures
- use constrained structured inference
- maintain semantic regression corpus
- support corrections
- avoid over-aggressive canonical matching

### 9.2 Catalog fragmentation

If synonymous phrases create separate canonical activities, later analysis becomes inaccurate.

Mitigation:

- present existing candidate activities to the interpreter
- prefer matching before creating
- maintain aliases
- use correction history later to improve matching

### 9.3 Silent temporal errors

If "yesterday" or "Saturday" is incorrectly resolved, history becomes misleading.

Mitigation:

- treat captured time and occurred time separately
- use deterministic date/time code where possible
- have model extract temporal phrase, not invent arbitrary timestamps
- test temporal cases extensively

### 9.4 Device capability variance

Gemini Nano capabilities differ by supported hardware/software/model availability.

Mitigation:

- feature detection at runtime
- explicit unsupported state
- device compatibility documented
- no hidden cloud fallback in MVP

### 9.5 Watch connectivity

Phone may not be immediately reachable.

Mitigation:

- durable watch-side pending capture queue
- retry later
- clear queued/sent/acknowledged states
- idempotent capture IDs

## 10. Product philosophy for future expansion

The database should be designed so future tools can:

- synchronize to cloud storage
- inspect raw utterances
- review AI interpretations
- bulk-correct canonical mappings
- retrain/refine matching logic
- perform richer statistics
- provide web/desktop access

Those future capabilities must not require changing the meaning of existing records.

The MVP should therefore capture provenance now, even if it does not expose every field in the current UI.
