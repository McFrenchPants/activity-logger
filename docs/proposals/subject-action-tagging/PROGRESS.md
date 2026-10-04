# Progress — Subject + action tagging

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch `feature/subject-action-tagging`. Owner approved the plan 2026-10-01.
Registered in `.sdlc/state.json` as TG1.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| TG1.1 | New corpus cases from real watch entries | done | Separate `tag-corpus.json` (72 cases); old corpus/recordings untouched |
| TG1.2 | Extraction interpreter (prompt v4) + recorders | done | Beside v3 (app still on v3); verifier pass; never yet run against a model |
| TG1.3 | Tag resolver + decision policy in core-domain | done | Verifier pass on attempt 2; oracle 49/49, no gaps |
| TG1.4a | On-phone debug-only runner, APK via owner's Google Drive | done | APK in G:/My Drive/ActivityLogger; awaiting owner's run |
| TG1.4 | Replay, score, report, gate; Pixel 10 Pro recording | done | Device recording via in-app runner; tag-baseline.json set after TG1.4b |
| TG1.4b | Resolver/policy fixes from the device findings | done | Verifier pass (attempt 2); device UNSAFE 14 -> 0; tag-baseline.json set. STAGE 1 COMPLETE |
| TG2.1 | Schema v2 + migration 1 -> 2 (tag tables, pair columns, duration, extraction fields) | done | Verifier pass attempt 1; additive ALTER (no rebuild); ADR-040 |
| TG2.2 | Repository: tag catalog + saving a tagged entry | done | Verifier pass attempt 1; LedgerRepository = ActivityRepository + TagRepository |
| TG2.3 | Corrections teach (correction stores the user's words as aliases) | done | Verifier pass attempt 1; ADR-041 |
| TG2.4 | Rename and merge tags (data only) | done | Verifier pass attempt 1; ADR-042. STAGE 2 COMPLETE |
| TG3.1 | Tagged capture orchestrator (core-domain) | done | Verifier pass attempt 1; ADR-043; NeedsReview carries `reasons` (tag decision) AND `problems` (ValidationReason) |
| TG3.2 | Tagged resolution + correction + tag management services (core-domain) | done | Verifier pass attempt 1; ADR-044 |
| TG3.3 | Phone wiring (pipeline, application, watch receiver) | done | Verifier pass attempt 1; ADR-045; not yet run on a device |
| TG3.4 | Log screen on tags (cards, confirm, correct) | todo | |
| TG3.5 | History on tags | todo | |
| TG3.6 | Tag management screen (rename, merge) | todo | |
| TG3.7 | Device pass (Pixel 10 Pro + watch) | todo | STOP for owner report after |
| TG4+ | Stage 4 | todo | detail after the Stage 3 report |

## Session log

### 2026-10-04 — TG3.3 done
`BusyRetryExtractor`; `CapturePipeline` now has repository as `LedgerRepository`, `extractor` and `taggedOrchestrator` beside the unchanged v3 members (screens still use v3 until TG3.4/3.5); `ActivityLedgerApplication` exposes `taggedResolutionService`, `taggedCorrectionService`, `tagManagementService`; `WatchCaptureReceiver` maps tagged outcomes (AutoSaved/AlreadyHasOccurrence -> SAVED with the pair label; confirm/review/rejected/unavailable -> NEEDS_REVIEW). 10 + 25 tests. ADR-045. Verifier pass. The watch path is therefore LIVE on tags once installed; JVM-proven only. Next: TG3.4 Log screen.

### 2026-10-04 — TG3.2 done
`TaggedResolutionService` (resolve a waiting capture), `TaggedCorrectionService` (correct subject/action/duration, same file), `TagManagementService` (rename -> Renamed/NothingChanged/NameInUse(otherId)/Refused, merge, listTags with pair counts), new `TagRefusal` (ServiceRefusal untouched). Two additive TagRepository reads: `loadExtractedWordsForCapture` / `loadExtractedWordsForOccurrence` (`ExtractedWords`). Aliases only via CorrectionAliases; unchanged side never learns. 15 + 14 tests. ADR-044. Verifier pass. For the UI: Existing tag unknown/merged/wrong-kind is one code (TagNotFound); a tag merged between check and save surfaces as IllegalArgumentException from resolve (screens must catch like v3). Next: TG3.3 phone wiring.

### 2026-10-04 — TG3.1 done
`TaggedCaptureOrchestrator.process` (core-domain services) + `TaggedProcessingOutcome` (AutoSaved, NeedsConfirm, NeedsReview(reasons, problems, proposal), Rejected, InterpreterUnavailable, AlreadyHasOccurrence) + `TaggedProposal` (user's words, both resolutions, resolved time/duration/state; never logged). Extract -> ground -> decide -> time/duration; AUTO_SAVE with future/unresolvable time or missing state downgrades to NeedsReview. 18 fake-ledger tests + 2 Room end-to-end tests. ADR-043. Verifier pass. Next: TG3.2 (resolution/correction/tag-management services).

### 2026-10-04 — Stage 3 started (owner go-ahead; phone and watch on adb)
Stage 3 detailed into TG3.1-TG3.7 (see plan). v3 pipeline stays in code but the app stops calling it.

### 2026-10-04 — TG2.4 done; STAGE 2 COMPLETE (stop for owner go-ahead for Stage 3)
`TagRepository.renameTag` (Renamed / NothingChanged / ConflictsWith; old name kept as MANUAL alias; pair labels refreshed) and `mergeTags` (from -> MERGED; name + aliases copied to target; pairs merged into found-or-created targets; every occurrence incl. HIDDEN moved by one correction row, reason TAG_MERGE, source USER; raw captures/interpretations untouched). One LedgerWriteDao transaction each; write-surface guard extended; 12 new tests, all tables snapshot-checked on every error path. ADR-042, DATA_MODEL s8. Verifier pass. Non-blocking notes: rename's alias insert doesn't check whether another tag already holds that key (oldest-first resolution covers it); one test name mentions 'merged tag' but only tests cross-kind. Next: owner report, then Stage 3 (app wiring).

### 2026-10-04 — TG2.3 done
`CorrectionAliases.aliasesToLearn` (core-domain, pure): learns the user's original words as an alias only
when that side's tag changed and the words are not another tag's name/alias, not a junk subject, not
just the action's object, not an equivalent existing action, not filler (the 'furnace -> hot tub' and
'filter / change filter' cases). `TagRepository.correctTags` (one transaction; NothingChanged and all
errors write nothing; one corrections row for pair and/or duration; aliases USER_CORRECTION; raw
captures untouched). Shared update query gained duration_seconds; old callers pass it back. ADR-041.
Verifier pass. Notes: CorrectionAliases.kt/test appeared in the tree mid-run and the implementer
could not say who wrote them (timing suggests itself); content verified on merit. Minor: alias
rule uses catalog order, repository convergence uses oldest-first -- edge case errs safe. Stage 3:
caller must apply CorrectionAliases before passing learn* words to correctTags or acceptTagged.

### 2026-10-01 — TG2.2 done
`TagRepository` (loadTagCatalog, acceptTagged) + `LedgerRepository`; `createActivityRepository`
returns LedgerRepository. acceptTagged: one LedgerWriteDao transaction, idempotent per capture,
New-name convergence (name key, then alias key, oldest first), find-or-create pair (label
"<subject> <action>", a cache), duration + extraction fields stored, AI_CONFIRMED alias
learning. History/occurrence views carry tag names + duration (still one statement). Verifier
pass. Stage 3 notes: (a) v3 `loadCatalog` will list tagged pairs under the cached label --
decide whether to exclude them when switching; (b) a learned alias whose key equals ANOTHER tag's
name/alias is not refused by the repository -- the caller must apply the TG2.3 safe-alias rules
before passing learn* words to acceptTagged too (ADR-041). Implementer attempt 1 was spawned
without the packet text by mistake and stopped before any edit; attempt counted as 1.

### 2026-10-01 — Stage 2 started; TG2.1 done
Owner go-ahead for Stage 2 (accepts test data being cleared). Data shape decided (ADR-040):
pair = canonical_activities row with nullable subject_id/action_id (unique together); new
subjects/actions/+aliases tables; duration + extraction columns. Migration 1 -> 2 is additive
(ALTER ADD COLUMN ... DEFAULT NULL REFERENCES passes Room validation; no rebuild); harness runs
1 -> 2; MigrationV1ToV2Test added. Clean start = owner clears app storage when the Stage 3
build is installed (no wipe code). Verifier pass (attempt 1); fixed a KDoc typo and an ADR-040
citation myself. Risks for TG2.2: never write half-tagged pairs (NULL-distinct unique index);
repository enforces one ACTIVE tag per key. Next: TG2.2 (packet already written).

### 2026-10-01 — TG1.4b done; STAGE 1 COMPLETE (stop for owner go/no-go)
Rules: candidate-set verb matching (only inflected forms reduced; Exact only if exactly one
existing action matches, else Near), subject/action re-split (only when both sides Exact),
junk subjects (verb gerund / time-only words) dropped only when the subject resolves New,
subject-is-only-the-action's-object -> CONFIRM with the single paired subject or review (never
silent inference), tiny synonym groups and head-verb -> Near only, ExtractionGrounding drops
time/duration words not in the sentence (duration also not when followed by "ago"). Attempt 1
failed the verifier: junk-subject rule discarded Exact subjects ("Walked June" -> dogs) and the
stemmer over-merged base verbs (hose/hoe, tap/tape). Fixed in attempt 2, verifier pass.
Device (Pixel 10 Pro, prompt v4): CORRECT 62, SAFE_MISS 6, NAME_MISMATCH 4, UNSAFE 0, FAILED 0
(before: 49/5/4/14/0; v3 device baseline: 10 of 48 unsafe, 9 of 15 real entries wrong).
REAL_ENTRY 11 correct, 1 NAME_MISMATCH (Durango -> "headlight"). Stand-in: 53/7/8/3/1 (3 unsafe:
"edg" truncation, HVAC/air filter x2 -- stand-in only). `tag-baseline.json` committed: 62
mustStayCorrect, maxUnsafe 0, maxUnsafeRealEntry 0; TagRegressionGateTest now gates.
Follow-ups (not blocking): (a) Stage 3: a time dropped by grounding should go to a confirm
card, not default to capture time; (b) "taped" -> Exact "tap" when only "tap" exists (silent-e
base vs short CVC verb) -- consider keeping only the "+e" form for short CVC stems; (c) short
garbled verbs (<5 letters) are never close to an existing verb; (d) Durango-style speech errors
lose the real subject -- NAME_MISMATCH, consider CONFIRM for brand-new subject when the raw text
has an unused capitalised word. Next: owner go/no-go for Stage 2 (data).

### 2026-10-01 — TG1.4 done (scorer, report, gate, DurationResolver)
`TagReplay`/`TagReport`/`TagGate` + `TagRegressionGateTest` (reports `tag-device.md`,
`tag-standin.md`; gate skips until `tag-baseline.json` exists). `DurationResolver` in
core-domain temporal. Device (Pixel 10 Pro, prompt v4, TG1.3 policy): CORRECT 49, SAFE_MISS 5,
NAME_MISMATCH 4, UNSAFE 14, FAILED 0; REAL_ENTRY unsafe 1 (real-mowed-lawn-40-minutes
WRONG_TIME, model invented "yesterday"); also p-time-mowed-about-an-hour-ago WRONG_DURATION.
Stand-in: 39/7/8/17/1. Scorer is stricter than the oracle on inferred subjects (must be an
acceptable id) -- kept, safer. Stale KDoc in TagRecordingFilesTest (says no scoring) -> fix
in TG1.4b. Next: TG1.4b.

### 2026-10-01 — first DEVICE tag recording (Pixel 10 Pro, in-app runner)
Owner ran the debug "AI test set" screen and shared the file via Drive; imported with
`import-tag-recording.sh` (72/72 answered, 27 busy retries). Throwaway scorer (subject/action
only; time/duration not scored): CORRECT 51, NAMING 4, SAFE_MISS 5, UNSAFE 12, FAILED 0.
REAL_ENTRY: 0 unsafe (v3 device: 9 of 15 wrong); 2 safe misses (furnace/hot tub filter split as
"X filter / change" -> CONFIRM), Durango -> "headlight / replace" (NAMING; speech-error subject
lost). UNSAFE (11 ported + 1 sibling), all silent duplicates: inflected verbs ("cleaning",
"cleaned", "edging" x3); subject = action gerund ("edging / edging"); subject = action's object
with real subject dropped ("filter / change filter" for the hot tub -- single-pair inference
would wrongly pick furnace, so must NOT infer there); head verb + extra words ("blow off
driveway" vs blow); synonyms (cut/mow, grass/lawn, swap|replace / replace filter, HVAC/air
filter -> "filter", clear leaves/clean). NEW finding, not in the scratch score: time/duration
hallucination -- "Spent 40 minutes mowing the lawn" -> time "yesterday"; "Mowed about an hour
ago" -> duration "for an hour". Plan: TG1.4 scorer (incl. time/duration) first, then TG1.4b
fixes (stemming, re-split, junk subjects, head-verb near, small synonym list producing CONFIRM
only, grounding check that time/duration words appear in the raw text) measured by replay;
deterministic fixes need no new phone run.

### 2026-10-01 — TG1.4a done (no verifier: debug-only, no persistence/auth/release)
Debug source set only: `debugtools/TagCorpusRun` (same fast-refusal backoff as the
instrumented recorder), `TagCorpusRunnerActivity` + stateless screen (launcher label "AI test
set", never downloads, keep-screen-on, cancel on stop, atomic write to files/tag-corpus/, share
via FileProvider limited to that folder), `debugImplementation(core-testing)`. Release APK
checked to contain none of it. `scripts/semantic/import-tag-recording.sh` (check, back up,
copy to tag-device-latest.json, run TagRecordingFilesTest); success path not yet exercised.
174 app-phone unit tests pass. Debug APK copied to `G:/My Drive/ActivityLogger/
ActivityLedger-debug-ai-test.apk`. Owner installs from Drive, runs, shares the JSON back to
Drive; then `import-tag-recording.sh`. Next on PC: resolver fixes from the stand-in findings.

### 2026-10-01 — first stand-in tag recording (unofficial)
Owner's Pixel is their primary phone and rarely free; no emulator can run Gemini Nano. Ran
`run-standin-corpus.sh --tags` (gemma3n:e4b, prompt v4): 71/72 answered, 1 transport failure.
Throwaway orchestrator scorer (not committed; TG1.4 builds the real one) through
TagDecisionPolicy: CORRECT 39, NAMING 8 (new tag, odd name), SAFE_MISS 7, UNSAFE 17, FAILED 1.
REAL_ENTRY: 0 unsafe (v3 on device mis-filed 9 of 15). PORTED: 15 of 48 unsafe (v3 stand-in 8,
device 10) -- all silent duplicates, no wrong existing tag. Causes, most fixable without the
phone: (1) model often leaves verbs inflected ("mowed", "cleaned", "cleaning", "washed",
"changed", even "edg") -> needs verb stemming on action keys; (2) object noun lands in the
subject ("furnace filter / change", "filter / changed") -> try re-splitting subject tail into
the action and prefer an all-Exact split; (3) junk subjects ("mowing", "this morning",
"Saturday", "edging") -> treat time words / gerund of the action as no subject, then infer;
(4) true synonyms (grass/lawn, HVAC/air filter/furnace, cut/mow, swap/replace, yard) -- needs
a decision (aliases via corrections, a small synonym list, or ask when the catalog is
non-empty and both tags are new). Recording committed as `tag-standin-latest.json`.
Owner asked about installing a build from Google Drive; proposed an in-app debug-only test
runner that shares its recording file to Drive (pending owner OK).

### 2026-10-01 — TG1.3 done
New `core-domain` `tagging` package: `TagCatalog`/`KnownTag`/`KnownPair`, `TagNormalizer`
(cleanName, key, tokens; Wi-Fi=WiFi, lawn mower=lawnmower, naive singular), `TagResolver`
(Exact NAME/ALIAS, Near, New; OSA edit distance >=5 chars, subject shared word >=3 letters,
action shared word after the verb with no stop-word list), `TagDecisionPolicy` (NOT_A_LOG,
ACTION_MISSING, VAGUE_ACTION incl. vague verb + pronoun, FILLER_WORDS, NEW_NAME_REJECTED via
NewActivityNameCheck, single-pair subject inference / SUBJECT_MISSING, Near -> CONFIRM, else
AUTO_SAVE). `TagPolicyOracleTest` (core-testing): 49 tag-corpus cases with hand-written ideal
extractions all acceptable, KNOWN_POLICY_GAPS empty. ADR-039 amends ADR-027 for the tag path
only. Attempt 1 failed the verifier: implementer added a NON_CONTENT_TOKENS filter that made
"put out" vs "take out" New (silent near-duplicate); removed in attempt 2. Singularization
deviates from packet examples for idempotence (-sses; -as kept) -- verifier judged in intent.
Known for TG1.4: no synonyms (grass/lawn, HVAC/furnace go New = duplicate on ported cases);
particle sharing ("pick up"/"clean up") will add CONFIRM cards; naive singular mangles
headaches/movies. Next: TG1.4 needs owner + Pixel 10 Pro (short session); JVM replay/scorer
can be built first.

### 2026-10-01 — TG1.2 done
`GeminiNanoActivityExtractor` (core-ai) + `ExtractionTypes.kt` (core-domain `extraction`
package: `ExtractionInput`, untrusted `ExtractionCandidate`, `ExtractionResult`,
`ActivityExtractor`). Prompt v4 (`EXTRACTION_PROMPT_VERSION`), schema `ExtractionResponse`
(operation, subject, action, activityState, temporalExpression, durationExpression; no
confidence), shape-only decoder, provenance `gemini-nano-extract-1`/`4`/`1`. v3 path untouched
(only `isWorthRetrying` moved to `GenAiRetry.kt`). `TagRecording` format (`tagCorpusSha256`),
`TagCorpusExtractionInput`, `TagRecordingFilesTest` (structural, skips without files). Recorders:
device `TagCorpusRecorderTest` (`tagCorpus=true`), stand-in `StandInTagCorpusRecorderTest`
(`-PtagStandIn=true`); both scripts take `--tags`. ADR-038. Verifier: pass, all 9 criteria.
Implementer noted the packet's suggested prompt examples (water heater, smoke detector,
"Just finished mowing.") are corpus content, so the prompt uses bikes/porch light/coffee
maker/deck/piano/silverware/boiler instead; "just" is copied as "just" (not "just now").
Neither recorder has been run (no Ollama server, no AI phone): v4 accuracy is unmeasured.
Known nit: `GENERATION_MAX_OUTPUT_TOKENS` KDoc still describes v3's seven fields. Next: TG1.3
(resolver + decision policy, verifier required).

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
