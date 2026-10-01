# Progress — Subject + action tagging

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch `feature/subject-action-tagging`. Owner approved the plan 2026-10-01.
Not yet registered in `.sdlc/state.json` (do it when generating TG1.1's packet).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| TG1.1 | New corpus cases from real watch entries | todo | Case table is in the plan |
| TG1.2 | Extraction interpreter (prompt v4) + recorders | todo | after TG1.1 |
| TG1.3 | Tag resolver + decision policy in core-domain | todo | verifier required |
| TG1.4 | Replay, score, report; Pixel 10 Pro recording | todo | needs owner + phone for a short session; STOP after this |
| TG1.5+ | Stages 2-4 | todo | detail written after the Stage 1 report |

## Session log

### 2026-10-01 — analysis, plan approved
Owner picked backlog 13 and asked for a high-level rethink. Pulled the phone database
(read-only) and found 9 of 15 watch entries filed wrongly, all at HIGH confidence, mostly
because the catalog is nearly empty and the model over-matches. Owner chose subject + action
tags, auto-created tags that prefer close existing ones, working from an empty catalog.
Wrote analysis, spec and plan; approved. Next session: `/continue-development` starts TG1.1.
The phone data snapshot was kept only in the session scratchpad (not committed).
