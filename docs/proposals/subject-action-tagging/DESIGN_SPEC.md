# Design spec — Subject + action tagging (work item TG1)

Backlog item 13. Full reasoning and the real-device evidence are in
[`docs/analysis/13-subject-action-tagging.md`](../../analysis/13-subject-action-tagging.md) —
read it first. **Owner approved this direction and the staged plan on 2026-10-01.**

## Goals

- Every logged entry carries a **subject** tag (what it was done to: furnace, hot tub,
  lawn mower) and an **action** tag (what was done: change filter, add gas, mow), plus an
  optional **duration**.
- Cut wrong confident saves: the 7-of-48 unsafe baseline, and the 9-of-15 wrong watch
  entries recorded 2026-10-01 (listed in the implementation plan as new test cases).
- Work from an **empty catalog** (the owner wipes test data and starts fresh). New
  subjects/actions may be created automatically, but an existing close tag is always preferred.
- Lookup ("when did I last change the furnace filter?") ranks entries matching both tags
  first, newest first, then subject-only and action-only matches. Watch shows the top result
  only; phone shows the ranked list.

## Non-goals

- The Ask screen/UI (later work item). This work delivers the data and ranking logic only.
- Changing time resolution (ADR-028, TM1) beyond keeping duration out of time wording.
- Any cloud use for interpretation (ADR-013 stands).

## Requirements

1. **Extraction, not selection.** The AI is asked only to extract subject, action, time wording,
   duration, state — as the user's own words. It is NOT shown the existing activity list.
2. **Tag resolution is deterministic program logic** in `core-domain` (testable on the PC):
   normalize (case, articles, plural, hyphens like Wi-Fi/WiFi), then exact name -> alias ->
   close-match; tolerate speech slips ("It just", "James Durango"). Outcomes: existing /
   near-existing (ask the user) / nothing close (new tag allowed).
3. **Decision policy replaces ADR-027's confidence gate.** Auto-save only when both tags are
   existing, or a tag is new AND nothing existing is close AND extraction is clean. Close-but-not-
   exact goes to a quick confirm card. The model's self-reported confidence is not a gate.
4. **Corrections teach.** Every user correction stores the user's wording as an alias.
5. **Tag hygiene on the phone:** merge and rename subjects/actions in one or two taps.
6. **Raw captures stay immutable** (AGENTS.md); a tag change is a correction record.
7. Phone owns the database and interpretation; watch never runs inference (AGENTS.md).
8. No capture text in logs (AGENTS.md #11). ML Kit stays inside `core-ai` (ADR-023).
9. Semantic-regression gate: corpus + baseline rebuilt for the new model; gate must pass.

## Constraints

- Room schema change needs a migration test; test data may be wiped, so the migration needs
  to be safe, not history-preserving.
- Prompt text is product logic: bump `PROMPT_VERSION`, update the drift-guard test, re-run corpus.
- On-device AI runs only in the foreground, one call per capture, BUSY is retryable (ADR-029/030).
- Pixel 10 Pro is the only AI test phone and is rarely available: keep device sessions short
  and batch them (see memory notes on test devices).

## Open questions (decide during implementation, record as ADRs)

- Exact data shape: new tag tables referenced from `canonical_activities`, vs tags directly on
  occurrences. Stage 2 decides after Stage 1 shows what resolution needs.
- Close-match threshold/algorithm — tune on the corpus in Stage 1.
- Screen words ("subject"/"action") — keep unless the owner asks otherwise.
