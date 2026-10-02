# Implementation plan — Subject + action tagging (TG1)

Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md). Branch `feature/subject-action-tagging` (off `main`).
Tracking: [`PROGRESS.md`](PROGRESS.md). Register `TG1` in `.sdlc/state.json` when the first
task packet is generated. Verifier is required for tasks touching persistence (Stage 2) and
for the decision policy (TG1.3), per `.sdlc/project.yaml` floor/widen tiers.

**Stages end with a stop and a plain-English report to the owner.** Do not run past a stage
boundary without their go-ahead.

## Stage 1 — Measure, no database change

### TG1.1 — New test cases from real entries
Extend the semantic corpus (`core-testing`, see `docs/SEMANTIC_CORPUS.md`) with the entries
below plus 6-8 hot-tub-style siblings (same subject/different action and the reverse). Each
case gets expected subject, expected action, expected duration. Include start-from-empty
cases (first entry with no catalog). Raw texts are the watch's actual transcriptions, speech
errors included.

| Heard text | Expected subject | Expected action | Notes |
|---|---|---|---|
| I just changed the furnace filter | furnace | change filter | interpreter once failed on this |
| I just changed the hot tub filter | hot tub | change filter | was filed under furnace |
| Yesterday I changed the oil in the tractor | tractor | change oil | was filed under hot tub |
| It just went and got gas for the lawn mower | lawn mower | get gas | speech: "It" for "I"; was Mow lawn |
| I weeded the garden for half an hour | garden | weed | duration 30 min, not time |
| Yesterday I replaced the headlight on James Durango | Durango (vehicle) | replace headlight | speech error; was Mow lawn |
| I just walked the dogs for about 30 minutes | dogs | walk | duration 30 min |
| Add to reboot the Wi-Fi | Wi-Fi | reboot | must merge with "WiFi" spelling |
| I just cleaned the hot tub | hot tub | clean | was filed under Dishes |
| Spent 40 minutes mowing the lawn | lawn | mow | duration 40 min |
| I just put all the dishes away | dishes | put away | |
| Add sanitizer to the hot tub | hot tub | add sanitizer | |

### TG1.2 — Extraction interpreter + recorder
New extraction prompt (version 4) and response schema in `core-ai` (no candidate list; fields
subject / action / time wording / duration / state). Extend the corpus recorders (device and
stand-in) to record extraction answers. The stand-in is for iteration only; official numbers
come from the Pixel 10 Pro.

### TG1.3 — Tag resolver and decision policy (`core-domain`)
Pure-Kotlin normalizer, resolver and decision policy per spec requirements 2-3, with unit
tests covering empty catalog, Wi-Fi/WiFi, plurals, speech slips, near-miss asks. Replaces the
confidence gate in `InterpretationValidator` for the new path (new ADR amends ADR-027).

### TG1.4 — Replay, score, report (JVM)
Replay recorded extraction answers through resolver + policy; score against expected
subject/action; report unsafe saves against the 7-of-48 baseline. Rebuild the baseline.
**Gate:** clearly fewer unsafe silent saves than the baseline, and none of the 9 wrong watch
entries saved silently under a wrong tag. Needs one short Pixel 10 Pro recording session
(ask the owner when the phone is available).

**STOP after TG1.4:** report the numbers and a go/no-go for Stage 2.

## Stage 2 — Data (detail written 2026-10-01 after the Stage 1 report)

Owner go-ahead for Stage 2 given 2026-10-01 ("I accept that the current test data in the
application database will be cleared"). Verifier required for every task (data-persistence floor).

**Data-shape decision (ADR-040, written in TG2.1).** A *pair* is the existing
`canonical_activities` row: it gains nullable `subject_id` / `action_id` (unique together).
Occurrences, interpretations and corrections keep pointing at a canonical activity, so the v3
path keeps compiling and working until Stage 3 switches the pipeline, and Stage 4 lookup is a
join (both tags = the pair; subject-only = `subject_id`; action-only = `action_id`). New
tables `subjects`, `actions`, `subject_aliases`, `action_aliases`. The columns stay nullable
because rows written by the v3 path have no tags; tightening is a later cleanup once v3 is
deleted. **Migration 1 -> 2 is additive and data-preserving** (DB-003 and the migration harness
stand). The owner's clean start is NOT code: when the Stage 3 build is installed, the owner
clears the app's storage once (Android settings), which the owner accepted on 2026-10-01. No
in-app "delete everything" feature is built. Schema v2 has never been installed anywhere, so
Stage 3 may still amend v2 before it ships (regenerate 2.json) rather than adding v3.

### TG2.1 — Schema v2 + migration 1 -> 2
Entities/DAOs for subjects, actions and their aliases (status ACTIVE/MERGED via new domain enum
`TagStatus`, self merge pointer, normalized name = `TagNormalizer` key); new nullable columns:
`canonical_activities.subject_id/action_id` (FKs, unique pair index, action index),
`activity_occurrences.duration_seconds`, `interpretations.extracted_subject/extracted_action/
duration_expression/resolved_duration_seconds`, `corrections.previous/new_duration_seconds`.
Explicit migration, exported `2.json`, v1 -> v2 data-survival test through the harness, schema
integrity/version/write-surface guard tests updated. Docs: ADR-040, DATA_MODEL.md v2.

### TG2.2 — Repository: tag catalog + saving a tagged entry
`loadTagCatalog()` -> `TagCatalog` (ACTIVE tags with aliases; pairs = ACTIVE activities with both
tags). `acceptTagged(...)`: subject and action each Existing(id) or New(name); in one
transaction create new tags (a New name whose key equals an ACTIVE tag's name key reuses that
tag), find-or-create the pair, store the interpretation (with extraction fields) and the
occurrence (with duration), mark the capture PERSISTED; idempotent per capture. Optional alias
words to learn (used when the user accepts a "did you mean X?" card). History/occurrence views
carry subject/action names and duration.

### TG2.3 — Corrections teach
`correctTags(...)`: change subject and/or action (Existing or New) and/or duration of an
occurrence as one correction row (pair change + duration), and store the user's original words
(the effective interpretation's extracted subject/action) as aliases of the chosen tags when
they differ from the tag's name and existing aliases (source USER_CORRECTION). Raw captures
never change.

### TG2.4 — Rename and merge tags (data operations only; UI is Stage 3)
Rename a subject/action (old name kept as an alias; refuse a name that equals another ACTIVE
tag's key -- the caller offers merge instead). Merge tag A into B: A becomes MERGED pointing at
B, A's name and aliases become B's aliases, each pair (A, x) is merged into the found-or-created
pair (B, x), and every occurrence on a merged pair is moved by a correction row. All in one
transaction; history stays auditable.

**STOP after TG2.4:** plain-English report to the owner; Stage 3 needs their go-ahead.

## Stage 3 — App (outline)
Wire phone and watch capture to the new pipeline; confirm-close-match card; merge/rename
tags; Saved card shows subject and action. Device pass on phone and watch.

## Stage 4 — Lookup (outline)
Ranked query logic in `core-domain` (both tags, then subject-only, then action-only; newest
first), unit-tested; watch top-result helper. No Ask UI.
