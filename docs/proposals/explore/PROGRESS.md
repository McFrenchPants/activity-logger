# Progress — Explore (Ask, search and dashboard in one screen)

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch `feature/explore`. Design approved by the owner 2026-10-04.
Registered in `.sdlc/state.json` as DH1.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| DH1.1 | `stats` package: filter model + calculator (core-domain) | done | Spot-check + full core-domain run (379 tests); orchestrator changed gap rounding: < 20 h in hours, else days min 1 (spec said < 2 days, contradicting its every-day wording) |
| DH2.1 | `loadExploreEntries()` repository read, DAO, Room, fakes | done | Verifier pass attempt 1; 3 queries in one read transaction; reuses existing alias DAOs |
| DH3.1 | Explore view model + state (phone, no UI) in new ui/explore | done | Verifier pass attempt 1; 26 tests; ExploreEntry gained captureId; ui/ask still present until DH3.2 |
| DH3.2 | Explore screen UI + tab rename | todo | after DH3.1 |
| DH3.3 | Device check on Moto G 2025; merge stage 3 | todo | after DH3.2 |
| DH4.1 | Temporal ranges ("in August", "this year") | todo | after DH3.3 |
| DH4.2 | Question reader q2 (core-ai) + domain seam | todo | after DH4.1; verifier |
| DH4.3 | Lookup -> Explore question result (count / how often) | todo | after DH4.2; verifier |
| DH4.4 | Question corpus + replay gate; Pixel recording | todo | after DH4.3; needs Pixel 10 Pro |
| DH5.1 | Device pass incl. voice; docs/ADRs; owner report | todo | needs owner to speak |

## Session log

### 2026-10-04 - DH3.1 done
ExploreViewModel/ExploreUiState/factory in ui/explore beside ui/ask. Questions -> chips via LookupOutcome.Answer tag ids (All time) + LastTime fact from the top DB entry; failures leave filters unchanged; any filter change drops a pending question; manual chip changes keep the back stack, drill-down and Answer push; clearAll keeps sorts; stale-result guard by sequence number; load failure reuses history_not_loaded. ExploreEntry.captureId added (raw_capture_id). Verifier pass. Note for DH3.2: a failure answer persists until the next filter change; ExploreViewModel uses ask_* strings. Next: DH3.2.

### 2026-10-04 - DH2.1 done
`TagRepository.loadExploreEntries()`: one JOIN (occurrence -> canonical activity, LEFT JOIN subjects/actions with status ACTIVE in the ON clause, LEFT JOIN raw_captures) + existing listForActiveSubjects/Actions alias queries, all in one read transaction. 11 Room tests incl. full-database snapshot unchanged. Verifier pass. No schema change. Next: DH3.1 (also adds captureId to ExploreEntry so Explore can open History's edit sheet).

### 2026-10-04 - DH1.1 done
`core.domain.stats`: ExploreEntry, DateRangeSelection/resolve, ExploreFilter, ScopeKind, ExploreCalculator -> ExploreSummary (numbers, previous period, chart buckets, activity rows + typical gap, subject groups, patterns, sorted entries), TypicalGap. 49 new tests. Implementer decisions: ALL_TIME starts at earliest IN-SCOPE entry; 'all time' values (lastTime, typical gap) honour tag+word filters but not dates; untagged rows keyed by activityId. Spec fix by orchestrator: typical gap shown in hours only below 20 hours (spec said below 2 days, contradicting its own 'every day' wording). Gaps across DST are +-1 h (instants); display rounding absorbs it. Next: DH2.1.

### 2026-10-04 - Plan written
Implementation plan written from the approved design. Plan-level decisions P1-P4 (no Flow; questions without date words set All time; stage 3 needs no lookup change; presentation split) in the plan. Starting DH1.1.
