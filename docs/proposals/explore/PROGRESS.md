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
| DH3.2 | Explore screen UI + tab rename | done | Orchestrator rerun (all module tests, assembleDebug); 331 app-phone tests; ui/ask deleted; not yet seen on a device |
| DH3.3 | Device check (Pixel 10 Pro or Moto G 2025); merge stage 3 | done | Pixel 10 Pro 2026-10-04: all checks passed incl. typed question; 4 visual fixes by orchestrator (chip clear tucked to its chip, keyboard closes on submit, bar width cap 24dp, range picker stops at today); fixes covered by tests, installed but not re-looked at (owner was using the phone); large font not checked (would need a settings change) |
| DH4.1 | Temporal ranges ("in August", "this year") | done | Orchestrator spot-check + full core-domain run (410 tests); new TemporalRangeResolver beside TemporalResolver (unchanged) |
| DH4.2 | Question reader q2 (core-ai) + domain seam | done | Verifier pass attempt 1; prompt q2 / schema 2; not yet run on the real model (DH4.4) |
| DH4.3 | Lookup -> Explore question result (count / how often) | done | Verifier pass attempt 1 (after one orchestrator follow-up); not yet seen on a device; edge case for DH4.4 in the log |
| DH4.4 | Question corpus + replay gate; Pixel recording | todo | after DH4.3; needs Pixel 10 Pro |
| DH5.1 | Device pass incl. voice; docs/ADRs; owner report | todo | needs owner to speak |

## Session log

### 2026-10-05 - DH4.3 done
`LookupService.ask(text, today, firstDayOfWeek)`: kind = `QuestionKindDetector` (text rules: how many/how much/number of times -> COUNT; how often/regularly/frequently -> HOW_OFTEN; when did I (last)/last time I/did|have I ever -> LAST_TIME; starts with what did|have|else did I, show me, list -> LIST) ?: model kind ?: UNKNOWN. Model date words used only if they occur in the normalized question text, then `TemporalRangeResolver` (Resolved USED / NoWindow ALL_TIME NONE / Unrecognised ALL_TIME NOT_UNDERSTOOD / Future -> NotEnoughHistory, before tag resolution). `Answer` carries `QuestionScope`; new `Browse(scope)` for no-subject/no-action questions with date words or LIST. Explore: chips from scope; HOW_OFTEN -> HowOften, COUNT or date words -> Count, else LastTime; Count/HowOften facts built from the ExploreSummary of the new filter. Orchestrator decisions during the task: answer names only the filtered side(s); HowOften count = in-scope all-time count (not totalEntriesEver); HowOften gap from the one activity row when the scope is one subject/action with a single activity; several activities -> "logged for N different activities. Pick one below" + Activities view. New copy: explore_answer_count(_none), explore_answer_last, explore_answer_how_often(_too_few/_several), explore_note_dates_not_understood. Tests: core-domain 433, app-phone 354; assembleDebug ok.
Known edge case (verifier, non-blocking; add a case in DH4.4 and fix then): HOW_OFTEN + date words + nothing logged in that window -> "not enough entries yet ... (0 so far)" even when older entries exist (activity rows only cover the window). Next: DH4.4 (question corpus + replay gate; needs the Pixel 10 Pro).

### 2026-10-05 - DH4.2 done
`QuestionKind` (LAST_TIME/COUNT/HOW_OFTEN/LIST/UNKNOWN) and `QuestionCandidate.dateWindow`/`kind` (defaults keep callers unchanged). core-ai: `QuestionResponse` subject, action, dateWindow, kind (kind pinned by enumValues), schema 2; decoder caps dateWindow at 60 and maps kind case-insensitively, anything else UNKNOWN; prompt q2 (7 numbered rules, five worked examples: gutters LAST_TIME, tomatoes last month COUNT, kettle HOW_OFTEN, generator in May LAST_TIME, Monday LIST), drift guard re-pinned. LookupService still ignores the new fields. Verifier pass; notes for DH4.3: QuestionKind.COUNT KDoc says "or how much" but the prompt only says "how many times" (harmless); prompt q2 unmeasured on the Pixel until DH4.4. Tests: core-domain 411, core-ai 219 (2 old skips), app-phone 333; assembleDebug ok. Next: DH4.3.

### 2026-10-05 - DH4.1 done
`core.domain.temporal.TemporalRangeResolver.resolve(expression, today, firstDayOfWeek) -> TemporalRange` (Resolved(DateRangeSelection) / NoWindow / Future / Unrecognised). Rules: all time; rolling "last/past N days|weeks|months|years" (7d/1w, 30d, 12m/1y map to presets); today/yesterday/this|last week|month|year ("last X" without "the" = previous calendar period, "the last X"/"past X" = rolling); named month (most recent started, label Month) with/without year; year alone (label Year); "since X"; "from/between X to Y" spans; single weekday / month-day. Ends clamped to today; windows after today are Future. 31 new tests. TemporalResolver untouched (capture baseline unaffected). Implementer extensions accepted: "couple of" without "a"; span month-vs-year sides share the year; yearless "from august to june" crosses the year boundary (per packet). Next: DH4.2 (question reader q2) -- needs the verifier.

### 2026-10-04 - DH3.3 done (Pixel 10 Pro), stage 3 merged
Owner unlocked the Pixel. Checked on device (dark mode): default overview (16 entries, 2 of 30 days, 13 activities, chart, average line), Activities/Entries/Patterns, sort labels, date menu + custom range dialog, Subject picker sheet and choosing a subject, clearing a chip, activity drill-down + Back, tapping an entry opens the edit sheet on first tap (Done, no change), Clear restores default, typed question "When did I last change the furnace filter?" -> All time + furnace filter + change chips, "Last logged: furnace filter · change — October 4 (today)". Found and fixed in ExploreScreen/ExploreResults: (1) chip ✕ buttons floated midway between chips -> ChipWithClear pairs (row gap 12dp, ✕ tucked 8dp); (2) keyboard stayed open after Enter/suggestion -> clearFocus; (3) one-bucket chart drew a full-width block -> bar width capped 24dp; (4) range picker offered future days/months -> UpToToday SelectableDates + yearRange ending this year (UpToTodayTest, 2 tests). 333 app-phone tests pass, assembleDebug ok. Fixed build installed on the Pixel (install -r) but not re-inspected: the owner was typing entries on the phone at the time, so driving stopped; DB read confirmed no test text was captured. Not checked on device: large font (needs a settings change), empty states (covered by Robolectric tests). Back from Explore goes to the Log tab (normal tab behaviour). Next: DH4.1.

### 2026-10-04 - DH3.3 retry: both phones locked
Both phones were attached (Moto G by USB, Pixel over Wi-Fi ADB). Installed the current debug build (built from d0b9d78) on the Moto G with install -r. The Moto G was awake when checked but locked a few seconds later; a swipe brings up a PIN pad (screencap black = secure keyguard). Pixel dozing on its lock screen. Did not change any screen-timeout/stay-awake setting (owner rule). Both screens put back to sleep. No Android emulator in the SDK. Still waiting on an unlocked phone; device-check list unchanged (see entry below).

### 2026-10-04 - Stopped before DH3.3 (device check)
Run total: DH1.1, DH2.1, DH3.1, DH3.2 done. New debug build installed on the Pixel 10 Pro over Wi-Fi ADB (install -r, data kept), but the phone is locked, so nothing was looked at; screen put back to sleep. Moto G not attached. Stage 3 stays on feature/explore (not merged) until the device check passes. Device-check list: default overview, chip row scrolling, date-range dialog, tag picker sheet, chart look + list toggle, three views, drill-down + Back, tapping an entry opens the edit sheet (and counts refresh after), empty states, large font, dark mode, and a typed question on the Pixel ("When did I last change the furnace filter?").

### 2026-10-04 - DH3.2 done
ExploreScreen (search box + suggestions + mic, filter chips with date presets/custom picker/read-only tag picker sheet, answer line, three numbers, Canvas chart + list alternative, Entries/Activities/Patterns), HistoryEntrySheets extracted from HistoryScreen (Explore opens the same edit sheet via its own HistoryViewModel and reloads after it closes), nav tab Explore + ic_nav_explore, ui/ask deleted. ExploreUiState gained subjectTags/actionTags for the pickers. Orchestrator reworded explore_ai_unavailable (old copy said "Ask"). Known: a tap on a row before the History list loads does nothing (second tap works). Next: DH3.3 on the Pixel 10 Pro (connected now).

### 2026-10-04 - DH3.1 done
ExploreViewModel/ExploreUiState/factory in ui/explore beside ui/ask. Questions -> chips via LookupOutcome.Answer tag ids (All time) + LastTime fact from the top DB entry; failures leave filters unchanged; any filter change drops a pending question; manual chip changes keep the back stack, drill-down and Answer push; clearAll keeps sorts; stale-result guard by sequence number; load failure reuses history_not_loaded. ExploreEntry.captureId added (raw_capture_id). Verifier pass. Note for DH3.2: a failure answer persists until the next filter change; ExploreViewModel uses ask_* strings. Next: DH3.2.

### 2026-10-04 - DH2.1 done
`TagRepository.loadExploreEntries()`: one JOIN (occurrence -> canonical activity, LEFT JOIN subjects/actions with status ACTIVE in the ON clause, LEFT JOIN raw_captures) + existing listForActiveSubjects/Actions alias queries, all in one read transaction. 11 Room tests incl. full-database snapshot unchanged. Verifier pass. No schema change. Next: DH3.1 (also adds captureId to ExploreEntry so Explore can open History's edit sheet).

### 2026-10-04 - DH1.1 done
`core.domain.stats`: ExploreEntry, DateRangeSelection/resolve, ExploreFilter, ScopeKind, ExploreCalculator -> ExploreSummary (numbers, previous period, chart buckets, activity rows + typical gap, subject groups, patterns, sorted entries), TypicalGap. 49 new tests. Implementer decisions: ALL_TIME starts at earliest IN-SCOPE entry; 'all time' values (lastTime, typical gap) honour tag+word filters but not dates; untagged rows keyed by activityId. Spec fix by orchestrator: typical gap shown in hours only below 20 hours (spec said below 2 days, contradicting its own 'every day' wording). Gaps across DST are +-1 h (instants); display rounding absorbs it. Next: DH2.1.

### 2026-10-04 - Plan written
Implementation plan written from the approved design. Plan-level decisions P1-P4 (no Flow; questions without date words set All time; stage 3 needs no lookup change; presentation split) in the plan. Starting DH1.1.
