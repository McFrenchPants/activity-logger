# TG1 -- release evidence (backfilled 2026-10-05)

Subject + action tagging (backlog 13)

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

Tasks (all status done): TG1.1, TG1.2, TG1.3, TG1.4a, TG1.4, TG1.4b, TG2.1, TG2.2, TG2.3, TG2.4, TG3.1, TG3.2, TG3.3, TG3.4, TG3.5, TG3.6, TG3.7, TG3.8, TG3.9, TG4.1, TG4.2, TG4.3, TG4.4, TG4.5, TG4.6, TG4.7

## Commits on main naming this id

- 244448d 2026-10-04 Housekeeping: backlog 13 done, project status and root progress updated for the tag pipeline, TG1 released
- 7b9decf 2026-10-01 TG1.4b: resolver fixes and time/duration grounding; device unsafe 14 -> 0; tag baseline
- 20a5eab 2026-10-01 TG1.4: tag replay, scorer, report and gate; DurationResolver
- 1b00a01 2026-10-01 TG1: first Pixel 10 Pro tag recording (in-app runner) and findings
- 9062941 2026-10-01 TG1.4a: debug-only on-phone "AI test set" runner, share-to-Drive, import script
- 52fb2ed 2026-10-01 TG1: first stand-in tag recording (gemma3n, prompt v4) and findings
- 04be238 2026-10-01 TG1.3: deterministic tag resolver and save/confirm/review policy, oracle test, ADR-039
- af29cd8 2026-10-01 TG1.2: extraction-only interpreter (prompt v4) beside v3, tag recording format and recorders
- aad2dcc 2026-10-01 TG1.1: tag corpus (subject + action) with real watch entries, siblings, empty-start cases and a port of the 48 activity cases
- 52135a6 2026-10-01 TG1: analysis, design spec and plan for subject+action tagging (backlog 13)

## Tracking row

- `PROGRESS.md`: | TG1 | Subject + action tagging (backlog item 13) | done | Stages 1-3 (TG1.1-TG3.9) merged to `main` 2026-10-04; phone and watch run on the tag pipeline, device-checked on the Pixel 10 Pro and the watch. Stage 4 (lookup) not started. Tracking: `docs/proposals/subject-action-tagging/PROGRESS.md`; ADR-038..050. |
