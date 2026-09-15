# Architectural and Product Decisions

This file contains lightweight ADRs.

---

## ADR-001 — Product is an activity logger, not a task planner

**Status:** Accepted

Activity Ledger records historical/current activity.

Todoist-style planning features are outside MVP.

**Reason:** Frictionless historical logging is the core value and would be diluted by planning workflows.

---

## ADR-002 — Phone is authoritative database owner

**Status:** Accepted

The Android phone owns authoritative activity history.

**Reason:** It provides sufficient compute, storage, and platform APIs while keeping Wear OS simple.

---

## ADR-003 — Wear OS is a capture peripheral

**Status:** Accepted

The watch captures input and communicates with the phone.

It does not own semantic inference or canonical activity data.

**Reason:** Lower complexity, lower battery cost, clearer consistency model.

---

## ADR-004 — Gemini Nano is primary semantic interpreter

**Status:** Accepted

MVP uses supported on-device Gemini Nano/AICore-backed Android APIs.

**Reason:** Enables semantic interpretation without cloud dependency.

---

## ADR-005 — No hidden cloud AI fallback

**Status:** Accepted

If on-device AI is unavailable, MVP does not silently send content to a remote AI provider.

**Reason:** Offline-first behavior and predictable privacy are product requirements.

---

## ADR-006 — Speech recognition is separate from semantic interpretation

**Status:** Accepted

STT produces text.

Semantic inference consumes text.

**Reason:** Different responsibilities, failure modes, test requirements, and future replaceability.

---

## ADR-007 — Raw capture is immutable evidence

**Status:** Accepted

The original recognized utterance is stored separately and never overwritten by AI normalization or correction.

**Reason:** Future auditing, repair, reclassification, and debugging depend on preserving source evidence.

---

## ADR-008 — Corrections are additive/auditable

**Status:** Accepted

User corrections change effective interpretation while preserving original interpretation and raw input.

**Reason:** Historical trust and future repair tooling.

---

## ADR-009 — Canonical activity is distinct from occurrence

**Status:** Accepted

`Mow lawn` is a reusable concept.

`Mowed lawn on Sep 15` is an occurrence.

**Reason:** Required for frequency/history analysis and synonym normalization.

---

## ADR-010 — AI output is structured and validated

**Status:** Accepted

Model results must map to typed schema and pass deterministic validation.

**Reason:** Generated prose is not a safe database contract.

---

## ADR-011 — Natural-language queries execute deterministic database operations

**Status:** Accepted

AI may interpret a question into a constrained query intent.

Application code performs the Room query.

**Reason:** Prevents hallucinated historical answers and arbitrary SQL execution.

---

## ADR-012 — Room is MVP authoritative persistence

**Status:** Accepted

Structured history is stored locally in Room.

**Reason:** Relational model, migrations, query capability, Android integration.

---

## ADR-013 — Cloud synchronization is post-MVP

**Status:** Accepted

MVP stores authoritative data only on the phone.

**Reason:** Cloud sync introduces identity, security, conflicts, cost, and complexity unrelated to proving core value.

**Consequence:** Phone is required for authoritative processing/storage in MVP.

---

## ADR-014 — Schema must be cloud-ready

**Status:** Accepted

Use stable globally unique IDs and preserve provenance.

**Reason:** Future synchronization should not require redesigning identity/history.

---

## ADR-015 — Semantic embeddings are deferred

**Status:** Accepted

MVP first uses candidate matching and Gemini Nano.

Embeddings are added only if catalog scale/performance requires them.

**Reason:** Avoid unnecessary ML subsystem complexity.

---

## ADR-016 — Semantic bugs become regression tests

**Status:** Accepted

Any discovered semantic error must be represented in the test corpus.

**Reason:** Interpretation quality should improve monotonically rather than regress unpredictably.

---

## ADR-017 — False merges are treated as high-risk

**Status:** Accepted

The system should be conservative when deciding that two phrases mean the same canonical activity.

**Reason:** Merging distinct activities silently corrupts statistics and is harder to notice than creating a duplicate.

---

## ADR-018 — Temporal precision must not be fabricated

**Status:** Accepted

Phrases such as "this morning" or "yesterday" should preserve appropriate uncertainty/precision.

**Reason:** Historical truth is more important than artificial timestamp precision.

---

## ADR-019 — Phone app uses single-activity navigation with Log as start destination

**Status:** Accepted

The phone app is one Android `Activity` hosting one Compose Navigation `NavHost`.

Top-level destinations in an M3 `NavigationBar`: **Log** (start; capture and recent history on one screen), **History**, **Ask**. Settings/diagnostics opens from the Log top bar. Occurrences open as a bottom sheet; Activity detail and Edit interpretation are pushed screens. Needs-review items are a History filter, not a destination or inbox.

Details and navigation graph: `docs/UX_VISUAL_SPEC.md` §3 D1.

**Reason:** Capture must require no navigation on launch, so the mic lives on the start destination. History and Ask stay one tap away. A single activity keeps deep links and process-death handling in one place, and keeping review inside History avoids inbox patterns excluded by UX_SPEC §15.

---

## ADR-020 — First Wear OS entry surfaces are the launcher and a complication; Tile is deferred

**Status:** Accepted

The Step 8 Wear milestone ships the app launcher entry and a watch-face complication that opens capture directly in the listening state. The complication may show a queued-capture count. A Tile is deferred to a later milestone.

Details: `docs/UX_VISUAL_SPEC.md` §3 D2.

**Reason:** A complication is one tap from the watch face the user is already looking at, which is the fastest practical capture path (WATCH_SPEC §3) and satisfies "one intentional action before speaking". It is a small data source with a tap action; a Tile adds a swipe before the tap and a separate ProtoLayout surface to build.
