# Progress — Subject + action tagging

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch `feature/subject-action-tagging`. Owner approved the plan 2026-10-01.
Registered in `.sdlc/state.json` as TG1.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| TG1.1 | New corpus cases from real watch entries | done | Separate `tag-corpus.json` (72 cases); old corpus/recordings untouched |
| TG1.2 | Extraction interpreter (prompt v4) + recorders | todo | after TG1.1 |
| TG1.3 | Tag resolver + decision policy in core-domain | todo | verifier required |
| TG1.4 | Replay, score, report; Pixel 10 Pro recording | todo | needs owner + phone for a short session; STOP after this |
| TG1.5+ | Stages 2-4 | todo | detail written after the Stage 1 report |

## Session log

### 2026-10-01 — TG1.1 done
Built a separate tag corpus (`core-testing/.../semantic-corpus/tag-corpus.json`) rather than
editing `corpus.json`, so the existing device recording, baseline and gate stay valid until
TG1.4 rebuilds them. Shape: subject/action/pair fixtures (`empty`, `household`,
`household-no-edge`, `owner-2026-10-01`); cases in four groups (PORTED 48 = every corpus.json
case re-expressed as tags, same id with `p-` prefix; REAL_ENTRY 12; SIBLING 8; EMPTY_START 4);
outcomes AUTO_SAVE / CONFIRM / NEEDS_REVIEW with `allowedOutcomes`; duration separate from time
words. `TagCorpusIntegrityTest` + `TagCorpusTemporalTest` (no resolver gaps). 94 core-testing
tests pass. Decisions: `p-car-washed-the-car` now expects new action `wash` (old owner ruling
accepting Wax car was an artefact of single-activity matching); `p-dryer-emptied-the-lint-trap`
keeps the old ruling via `allowedExistingIds`. Amended SEMANTIC_CORPUS §10 so owner-approved
real sentences are allowed in the tag corpus only. TG1.4 scorer must treat a silently created
tag where `existingId` is set as a duplicate (unsafe). Next: TG1.2 (prompt v4 + recorders).

### 2026-10-01 — analysis, plan approved
Owner picked backlog 13 and asked for a high-level rethink. Pulled the phone database
(read-only) and found 9 of 15 watch entries filed wrongly, all at HIGH confidence, mostly
because the catalog is nearly empty and the model over-matches. Owner chose subject + action
tags, auto-created tags that prefer close existing ones, working from an empty catalog.
Wrote analysis, spec and plan; approved. Next session: `/continue-development` starts TG1.1.
The phone data snapshot was kept only in the session scratchpad (not committed).
