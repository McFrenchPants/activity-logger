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
   phone. Blocked until Step 6. Source: ADR-024.

7. **Verify on-device speech on the OnePlus Watch 3** — `idea`
   The designed watch Listening screen assumes in-app on-device recognition
   works on Wear OS 5. That is expected but unverified — the watch had not
   arrived when item 1 ran. When it does: enable Developer options, pair over
   ADB, and do a real `createOnDeviceSpeechRecognizer()` round-trip plus the
   trivial Data Layer round-trip already noted in item 1. Do this before Step 8
   depends on either. If on-device recognition turns out to be unavailable
   there, ADR-024 and the Wear Listening screen (ADR-020, UX_VISUAL_SPEC §3 D2)
   both need revisiting. Blocked on hardware.

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

9. **Semantic regression corpus, automated** — `done` (on branch `feature/semantic-regression`, not yet merged)
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

10. **Typed capture and history screens on the phone** — `idea`
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

12. **Corpus expectations: which near matches are acceptable** — `idea`
    Added 2026-09-18. The owner judged some "unsafe" device answers as
    acceptable (e.g. "Washed the car" filed under Wax car). Corpus cases
    whose product-correct answer is really "either" should list the existing
    activity as an allowed answer, so the score measures real mistakes.
    Owner to confirm which cases.
