# SS1.5 -- release evidence (backfilled 2026-10-05)

Task of work item SS1 (Gradle project scaffold (backlog item 2)): app-phone and app-wear

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 1616d21 2026-09-16 SS1.5: app-phone and app-wear; compileSdk 37 (ADR-021 amended)
- dd5b0a6 2026-09-16 Amend ADR-023, add ADR-025, and fix how agents report to the owner
- f900e36 2026-09-16 SS1.1: Gradle 9.7.1 foundation, version catalog, eight modules

## Tracking row

- `docs/proposals/gradle-scaffold/PROGRESS.md`: | SS1.5 | `app-phone`, `app-wear` | done | `compileSdk` raised to 37 (ADR-021 amended) because the pinned Compose BOM requires it; `targetSdk` stays 36. Both merged manifests verified free of `INTERNET`/`ACCESS_NETWORK_STATE`. |
