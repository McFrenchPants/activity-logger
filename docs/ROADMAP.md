# Roadmap

## 1. Philosophy

The roadmap protects the MVP from becoming a general productivity platform.

Features are organized by dependency and product value, not by novelty.

## 2. MVP — Local Activity Ledger

### Capture

- phone voice capture
- Wear OS capture
- durable watch queue
- watch acknowledgement

### Local intelligence

- on-device speech recognition
- Gemini Nano semantic interpretation
- structured output
- canonical activity matching
- new activity creation
- basic completed/in-progress state
- temporal phrase handling

### Data

- Room
- immutable raw capture
- interpretation provenance
- canonical activity catalog
- activity occurrences
- correction audit trail

### History

- chronological history
- activity detail
- correction flow

### Questions

- last occurrence
- occurrence list
- count in date range
- interval/frequency summary

### Engineering

- semantic corpus
- migration tests
- watch transport idempotency
- runtime capability checks

## 3. Post-MVP A — Data quality tooling

Likely high-value next step.

Features:

- dedicated review queue
- bulk review of low-confidence entries
- canonical activity merge tool
- alias management
- compare original vs interpreted text
- re-run old captures through newer interpreter
- flag suspected duplicate activities

This may live in a separate phone, desktop, or web tool.

The MVP schema must support it.

## 4. Post-MVP B — Cloud synchronization

Goal:

Allow activity history to exist beyond one phone while preserving local-first behavior.

Potential capabilities:

- encrypted/authenticated cloud account
- multi-device synchronization
- backup/restore
- conflict resolution
- web/desktop read access
- remote repair tooling

Important:

Cloud sync should synchronize the established domain model.

It should not turn the cloud into a prerequisite for capture.

## 5. Post-MVP C — Semantic retrieval improvements

Only when measurements justify it:

- local embeddings
- semantic candidate retrieval
- larger catalogs
- richer synonym/alias learning
- personalized disambiguation

Do not add embeddings merely because they are fashionable.

## 6. Post-MVP D — Analytics

Examples:

- typical interval between repeated activities
- frequency trends
- seasonal patterns
- last/next likely maintenance interval
- activity summaries by period

These are analysis features, not task planning.

**Delivered by Explore (2026-10-05, ADR-053):** typical interval ("usually every N days"), entries over time per day/week/month, comparison with the previous period, activity summaries by period, weekday and part-of-day patterns, and time mentioned per activity. Not built: seasonal patterns and "next likely" predictions.

## 7. Post-MVP E — Additional capture surfaces

Potential:

- desktop quick capture
- browser extension
- web app
- Android quick settings tile
- assistant integration
- widgets
- notification action
- share-to Activity Ledger

All should write to the same domain model.

## 8. Future planning product

Planning/task-management capability may eventually be:

- a separate product
- a separate module
- a carefully bounded extension

Do not conflate it with Activity Ledger's historical purpose.

## 9. Specifically deferred

- reminders
- recurrence engine
- due dates
- projects
- priorities
- teams
- gamification
- automatic productivity recommendations
- calendar scheduling
- location automation
- cloud AI fallback

## 10. Architecture checkpoints before cloud sync

Before adding cloud synchronization, decide:

- identity/account provider
- encryption model
- conflict-resolution policy
- deletion/tombstone policy
- correction-history merge behavior
- canonical-activity merge behavior
- offline write precedence
- schema versioning across clients
- web repair-tool authorization
