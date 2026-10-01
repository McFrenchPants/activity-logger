# Backlog

Status vocabulary:

- `idea` — proposed, not yet analyzed
- `needs research` — requires investigation before it can be scoped
- `ready` — scoped enough to turn into a design spec / implementation plan
- `in progress` — actively being implemented (see its proposal folder or the
  root `PROGRESS.md` for detail)
- `done` — shipped

## Items

1. **Platform/API validation and version pinning** — `done`
   Completed 2026-09-15. Outcome: ADR-021 (SDK baselines), ADR-022 (Kotlin/KSP
   toolchain pin), ADR-023 (ML Kit GenAI Prompt API + Structured Output,
   contained in `core-ai`), ADR-024 (platform on-device `SpeechRecognizer`);
   `docs/PROJECT_STATUS.md` updated. Items 2-4 are unblocked. Two constraints
   worth carrying forward: Kotlin is pinned to 2.3.21 because KSP has no 2.4.x
   release, and ML Kit Structured Output is alpha with no SLA. Original entry
   below for reference.
   Verify current official support for: Android API baseline (minSdk/targetSdk),
   Gemini Nano / AICore Prompt API availability + Structured Output support on
   the target phone, on-device speech recognition API, Wear OS minimum API.
   Document findings and pin exact dependency versions in `docs/DECISIONS.md`;
   update `docs/PROJECT_STATUS.md`. Source: `docs/IMPLEMENTATION_HANDOFF.md`
   section 4 ("First engineering phase"), `docs/PROJECT_STATUS.md` "Open
   implementation decisions". This is a research task, not code — its output
   (pinned versions/constraints) gates the two items below.

   Known test hardware (2026-09-15):
   - Pixel 10 Pro (primary) — launch device for the latest Gemini Nano
     generation; expected AICore-eligible for both the Prompt API and
     Structured Output. Primary real-device AI test target.
   - Pixel 7 Pro (secondary) — Tensor G2, predates Gemini Nano's Pixel 8 Pro
     hardware baseline; almost certainly NOT AICore-capable. Useful as the
     "AI unavailable" capability-detection test device (ARCHITECTURE.md §21)
     and for routine non-AI development, not for validating the AI path.
   - OnePlus Watch 3 (ordered, arriving soon) — Wear OS. Enable Developer
     options (Settings > System > About > tap Build number) then ADB/Wi-Fi
     debugging as soon as it arrives; do a trivial Data Layer round-trip
     before Step 8 depends on it.
   - Bootloader confirmed locked on the Pixel 10 Pro (2026-09-15):
     `adb shell getprop ro.boot.flash.locked` printed `1`. Gemini Nano APIs
     hard-refuse on an unlocked bootloader, so this precondition is met and
     AI testing on that device is unblocked. Pixel 7 Pro not checked — it is
     a test-only device and not an AI target.
   - No Play Store distribution needed for MVP dev/testing — sideload via
     `adb`/Android Studio Run to both phones and the watch once paired.

   Given this hardware, real Gemini Nano validation does not need to wait —
   it can be attempted directly on the Pixel 10 Pro whenever Step 4 is
   reached.

2. **Gradle project scaffold** — `done`
   Completed and merged to `main` 2026-09-16 (work item SS1, see
   `docs/proposals/gradle-scaffold/`). Original entry below for reference.
   Create the multi-module Android/Wear OS project structure per
   `docs/ARCHITECTURE.md` section 3 (`app-phone`, `app-wear`, `core-domain`,
   `core-data`, `core-ai`, `core-speech`, `core-wear-protocol`,
   `core-testing`), plus test infrastructure. Source:
   `docs/IMPLEMENTATION_HANDOFF.md` "Step 1 — Scaffold". Item 1 is done; use the
   versions pinned in ADR-021 and ADR-022 exactly, and keep every ML Kit
   dependency inside `core-ai` per ADR-023.

3. **Room schema v1 + migration test infrastructure** — `done`
   Completed and merged to `main` 2026-09-16 (work item DB1, see
   `docs/proposals/room-schema/`). Original entry below for reference.
   Implement the Room schema for raw captures, canonical activities, aliases,
   interpretations, occurrences, and corrections per `docs/DATA_MODEL.md`, plus
   baseline migration tests. Source: `docs/IMPLEMENTATION_HANDOFF.md`
   "Step 2 — Persistence". Depends on item 2 (needs the `core-data` module to
   exist).

4. **AI vertical slice (hardcoded text -> Gemini Nano -> Room)** — `done`
   Completed and merged to `main` 2026-09-17 (work item AI1, see
   `docs/proposals/ai-vertical-slice/`; passed once on the Pixel 10 Pro,
   `RESULTS.md`). Original entry below for reference.
   Using hardcoded text input (e.g. "I cut the grass yesterday.") but the
   production Gemini Nano path, verify structured output, canonical-activity
   matching, temporal extraction, Room persistence, and raw-text retention
   end-to-end. Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 4 — AI vertical
   slice". Depends on items 1-3 and 8.

5. **Phone/Wear visual spec and UI decisions** — `done`
   Visual design system, screen mockups, and the UI decisions the UX specs
   left open (phone navigation, first Wear entry surface, theming,
   Settings/diagnostics contents, undo, Ask History shape). Completed
   2026-09-15 and approved by the owner as the visual reference through MVP:
   `docs/UX_VISUAL_SPEC.md`, mockups in `docs/design/visual-spec/`, ADR-019
   (navigation), ADR-020 (Wear entry surface). Carried into implementation:
   verify color contrast with final Compose values during Step 7; the watch
   Listening screen depends on item 1's on-device speech findings.

6. **Compare ML Kit Advanced-mode speech against the platform recognizer** — `idea`
   ADR-024 pins `SpeechRecognizer.createOnDeviceSpeechRecognizer()` for the MVP
   because `com.google.mlkit:genai-speech-recognition` is alpha and its
   Advanced mode runs only on Pixel 10/11, so the Pixel 7 Pro and the watch
   would need the platform path regardless. Once Step 6 provides a real capture
   pipeline, measure both against the semantic seed corpus and decide whether
   the quality gain justifies a second transcription implementation on the
   phone. ~~Blocked until Step 6.~~ Source: ADR-024.
   **2026-09-19: unblocked.** Step 6 shipped (item 15), so a real capture
   pipeline now exists and both paths can be measured against the corpus.
   Note ADR-035 removes one of the original arguments *against* ML Kit: the
   watch cannot use the platform on-device recognizer at all, so "the watch
   needs the platform path anyway" is no longer true. It does not become an
   argument *for* ML Kit either -- Advanced mode is Pixel 10/11 only, so it
   cannot serve the watch. Still `idea`; worth doing only if phone
   transcription quality turns out to be a real source of wrong entries.

7. **Verify on-device speech on the OnePlus Watch 3** — `done` (speech half; Data Layer half is now item 16)
   The designed watch Listening screen assumes in-app on-device recognition
   works on Wear OS 5. That is expected but unverified — the watch had not
   arrived when item 1 ran. When it does: enable Developer options, pair over
   ADB, and do a real `createOnDeviceSpeechRecognizer()` round-trip plus the
   trivial Data Layer round-trip already noted in item 1. Do this before Step 8
   depends on either. If on-device recognition turns out to be unavailable
   there, ADR-024 and the Wear Listening screen (ADR-020, UX_VISUAL_SPEC §3 D2)
   both need revisiting. ~~Blocked on hardware.~~

   **Answered 2026-09-19 (VC1.1), and the answer is no.** The watch was paired
   and probed on hardware: `isOnDeviceRecognitionAvailable` is **false** and
   `createOnDeviceSpeechRecognizer()` throws `UnsupportedOperationException`,
   even though a Wear build of the Google TTS recognizer is installed and is
   the default recognition service. So the second half of this item landed
   exactly as it feared: ADR-024 does not hold for the watch, and the designed
   Wear Listening screen cannot be built on it. Recorded as **ADR-035**, which
   lists four options and picks none -- that choice belongs to the Wear work
   item. The Data Layer round-trip named in this item is still unverified and
   moves to item 16.

8. **Domain services** — `done`
   Completed and merged to `main` 2026-09-17 (work item DS1, see
   `docs/proposals/domain-services/`). Original entry below for reference.
   Added 2026-09-17; it was the one build-guide step with no backlog entry, and
   item 4 depends on it. Candidate selector, temporal resolver, interpretation
   validator, capture orchestrator, correction service, review resolution, and
   the `ActivityRepository` boundary between `core-domain` and `core-data`.
   Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 3 — Domain services". Work
   item DS1, see `docs/proposals/domain-services/`. Owner decision
   2026-09-17: no "save but mark for review" tier — anything short of
   confident goes to Needs review and nothing is logged until the user picks.

9. **Semantic regression corpus, automated** — `done`
   Merged to `main` 2026-09-18 (work item SR1).
   First device run 2026-09-18: 32 of 48 correct, 10 wrong entries that would
   have been saved; see `docs/proposals/semantic-regression/RESULTS.md`.
   Work item SR1, see `docs/proposals/semantic-regression/`. Owner decision
   2026-09-17: tests run on the PC by recording model answers (short phone
   sessions, plus a local stand-in model) and replaying everything else.
   Added 2026-09-17. Build-guide Step 5, the stated next milestone in
   `docs/PROJECT_STATUS.md`. Encode the seed, temporal and ambiguity corpora of
   `docs/TEST_STRATEGY.md` §3-6 in a machine-readable file, plus a runner that
   pushes each case through the production interpreter + validator on the
   Pixel 10 Pro (Gemini Nano only runs on-device and in the foreground,
   ADR-029) and a deterministic JVM half for the temporal resolver and the
   validator. Output: a per-case report and summary, so the provisional
   choices (confidence policy ADR-027, schema-in-prompt, one-shot decoding
   ADR-030) get measured. The query corpus (§7) waits for Step 9. Satisfies
   REQUIREMENTS TST-001..004 and AGENTS.md §6. Build guide: "do not move on
   until core synonym and near-neighbor cases are measurable."

10. **Typed capture and history screens on the phone** — `done` (UI1, merged to
    `main` 2026-09-19; device pass 2026-09-19)
    Work item UI1, see `docs/proposals/typed-capture/` (branch
    `feature/typed-capture`). Owner chose it 2026-09-18; scope and the Saved
    card's *Change activity* button (core of item 11) signed off 2026-09-19.
    Added 2026-09-17. The earliest usable slice of build-guide Step 7: a
    Log screen with a text field, the History list, and the Needs-review
    list, per `docs/UX_VISUAL_SPEC.md`. First point at which the app can be
    used by hand. Skips ahead of Step 5, so interpretation accuracy would be
    unmeasured while it is built.

11. **Short countdown before the AI's match is saved** — `idea`
    Added 2026-09-18 by the owner, after the first device corpus run showed
    every wrong match at HIGH confidence (see
    `docs/proposals/semantic-regression/RESULTS.md`). After a capture, show
    the activity the AI picked for ~3 seconds with a countdown spinner; it
    saves automatically unless the user acts. One button opens the existing
    activities to pick a different one, or "New activity". Replaces
    "HIGH confidence -> silent auto-accept" (ADR-027) with a brief, cheap
    correction window, without falling back to confirming every entry.
    Owner flagged it may widen scope. Open questions for analysis: how it
    fits the watch capture flow (small screen, often glanced at, not
    watched); whether a correction after the window lands in the
    Needs-review list; how it interacts with raw captures being immutable
    (a correction is a new interpretation, not an edit); dependency on item
    10 (no phone capture screen exists yet).
    **2026-09-19:** the core of this shipped in item 10 -- the Saved card
    now has *Change activity* next to Undo for its 8 s window (a user
    correction, never an edit of the raw capture). Still open here: whether
    to wait ~3 s before saving (countdown) instead of saving immediately, and
    the watch flow.

12. **Corpus expectations: which near matches are acceptable** — `done` (SR1.8)
    Added 2026-09-18. The owner judged some "unsafe" device answers as
    acceptable (e.g. "Washed the car" filed under Wax car). Corpus cases
    whose product-correct answer is really "either" should list the existing
    activity as an allowed answer, so the score measures real mistakes.
    Owner confirmed 2026-09-18: wash→Wax car, lint trap→Clean dryer vent,
    "Cleaned the dryer"→Clean dryer vent.

13. **Cut down wrong confident matches** — `ready` (analysis written
    2026-10-01: `docs/analysis/13-subject-action-tagging.md`; direction is
    subject + action tagging, bootstrapped from an empty catalog; awaiting
    owner sign-off on the plan, then a design spec)
    Added 2026-09-18 from SR1.7/SR1.8 follow-up (a). The Pixel 10 Pro still
    files 7 of 48 corpus cases under the wrong existing activity, all at HIGH
    confidence (edging→mowing, raking→mowing, water heater, smoke-detector
    batteries, "the furnace thing"). Prompt wording and/or the confidence
    policy (ADR-027), measured against the SR1 baseline (35 correct). Needs
    short Pixel 10 Pro sessions to re-record. Overlaps item 11, which attacks
    the same problem from the UI side.

14. **Two small fixes the corpus turned up** — `done` (FX1, merged to `main`
    2026-09-19; see root `PROGRESS.md`)
    Added 2026-09-18 from SR1 follow-ups (b) and (c). (b) The production
    interpreter treats AICore's "busy" refusal (GenAiException BUSY,
    statusCode 9) as a permanent failure (OTHER) instead of retryable, so a
    real capture made while the model is busy would fail instead of waiting.
    (c) `TemporalResolver` has no weekday + part-of-day rule, so "Mowed
    Saturday morning." is unresolvable. Both JVM-testable; (c) moves one
    corpus case from miss to correct.

15. **Phone voice capture** — `done` (VC1, 2026-09-19; on branch
    `feature/voice-capture`, not yet merged)
    Added 2026-09-19 when the owner asked for voice logging before starting the
    watch app, having just paired the OnePlus Watch 3. Build-guide Step 6, the
    one step typed capture (item 10) deliberately skipped ahead of. Work item
    VC1, see `docs/proposals/voice-capture/`. Delivered: the real `core-speech`
    module (`SpeechTranscriber` plus a platform on-device adapter per ADR-024),
    a microphone control beside the Log screen's text field with the listening
    state and the recognition-failure card, the `RECORD_AUDIO` permission flow
    with no dead ends, and spoken words going through the *same* capture
    pipeline typed words already use. Owner decision 2026-09-19: text field
    stays primary with the mic beside it, rather than the voice-first layout
    the approved mockups show (UX_VISUAL_SPEC §4.1 amended).
    **Still outstanding: the real-device pass (VC1.5).** Both test devices went
    offline before it could run, so voice capture has never been exercised
    against a real recognizer — only against a scripted fake in tests.

16. **Wear Data Layer round-trip** — `done` (WD1, 2026-10-01; on branch
    `feature/wear-data-layer`, not yet merged). Measured: message and DataItem
    both round-trip phone<->watch (~1.2 s and ~0.2 s). See
    `docs/proposals/wear-data-layer/RESULTS.md`.
    Added 2026-09-19, split out of item 7. That item bundled two unrelated
    checks; its speech half is now answered (ADR-035) but the Data Layer half
    never ran. Before the watch app depends on it: a trivial phone-to-watch and
    watch-to-phone round-trip over the Wear Data Layer on the paired OnePlus
    Watch 3, confirming pairing, the shared `applicationId`, and that a message
    and a `DataItem` both arrive. Small, and it de-risks the whole Wear
    milestone. Both apps are already installed on their devices.

17. **Decide how the watch captures, now that it cannot listen on-device** —
    `done` (answered 2026-10-01; see the end of this item)
    Added 2026-09-19 from ADR-035. The watch app's entire designed entry point
    is voice, and the OnePlus Watch 3 cannot transcribe on-device. ADR-035 lists
    four options: Wear's own system dictation screen (needs measuring — if it
    can transcribe over the network it is disqualified by ADR-005), recording on
    the watch and transcribing on the phone, a tap-a-recent-activity capture
    with no voice at all, or different watch hardware. This blocks the Wear
    milestone (build-guide Step 8) and is a genuine product decision, not a
    technical one — it changes what the watch is for.
    **2026-10-01, owner:** avoiding the Internet is a preference, not a rule,
    so the system dictation screen is no longer disqualified if it uses the
    network (see the clarification in ADR-035). Proposed next step: measure
    that dictation screen on the watch (does it work in airplane mode?) before
    choosing. WD1 showed the phone<->watch link works, so recording on the
    watch and transcribing on the phone is also viable.
    **2026-10-01, answered (WD1.3):** with airplane mode on, both the system
    dictation screen and the ordinary in-app `SpeechRecognizer` transcribed
    accurately on the OnePlus Watch 3, using Google's on-device engine. So the
    designed in-app voice capture on the watch is buildable, with no phone and
    no Internet. Recommended mechanism and the one open risk (a silent
    network fallback must be prevented or made visible) are in ADR-035. The
    Wear milestone (build-guide Step 8) is unblocked. Next backlog item to
    create: the Wear capture app itself.
