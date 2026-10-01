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

## Stage 2 — Data (outline; detail after Stage 1)
Tag tables, migration and migration test; repository; corrections store aliases;
wipe/start-clean path; ADR for the data shape. Verifier required.

## Stage 3 — App (outline)
Wire phone and watch capture to the new pipeline; confirm-close-match card; merge/rename
tags; Saved card shows subject and action. Device pass on phone and watch.

## Stage 4 — Lookup (outline)
Ranked query logic in `core-domain` (both tags, then subject-only, then action-only; newest
first), unit-tested; watch top-result helper. No Ask UI.
