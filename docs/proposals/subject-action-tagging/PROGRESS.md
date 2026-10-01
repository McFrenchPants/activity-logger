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
| TG1.4 | Replay, score, report; Pixel 10 Pro recording | todo | needs owner + phone for a short session; STOP after this |
| TG1.5+ | Stages 2-4 | todo | detail written after the Stage 1 report |

## Session log

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
