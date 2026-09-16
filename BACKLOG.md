# Backlog

Status vocabulary:

- `idea` — proposed, not yet analyzed
- `needs research` — requires investigation before it can be scoped
- `ready` — scoped enough to turn into a design spec / implementation plan
- `in progress` — actively being implemented (see its proposal folder or the
  root `PROGRESS.md` for detail)
- `done` — shipped

## Items

1. **Platform/API validation and version pinning** — `idea`
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

2. **Gradle project scaffold** — `idea`
   Create the multi-module Android/Wear OS project structure per
   `docs/ARCHITECTURE.md` section 3 (`app-phone`, `app-wear`, `core-domain`,
   `core-data`, `core-ai`, `core-speech`, `core-wear-protocol`,
   `core-testing`), plus test infrastructure. Source:
   `docs/IMPLEMENTATION_HANDOFF.md` "Step 1 — Scaffold". Depends on item 1 for
   pinned SDK/Compose/Kotlin versions.

3. **Room schema v1 + migration test infrastructure** — `idea`
   Implement the Room schema for raw captures, canonical activities, aliases,
   interpretations, occurrences, and corrections per `docs/DATA_MODEL.md`, plus
   baseline migration tests. Source: `docs/IMPLEMENTATION_HANDOFF.md`
   "Step 2 — Persistence". Depends on item 2 (needs the `core-data` module to
   exist).

4. **AI vertical slice (hardcoded text -> Gemini Nano -> Room)** — `idea`
   Using hardcoded text input (e.g. "I cut the grass yesterday.") but the
   production Gemini Nano path, verify structured output, canonical-activity
   matching, temporal extraction, Room persistence, and raw-text retention
   end-to-end. Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 4 — AI vertical
   slice". Depends on items 1-3.

5. **Phone/Wear visual spec and UI decisions** — `done`
   Visual design system, screen mockups, and the UI decisions the UX specs
   left open (phone navigation, first Wear entry surface, theming,
   Settings/diagnostics contents, undo, Ask History shape). Completed
   2026-09-15 and approved by the owner as the visual reference through MVP:
   `docs/UX_VISUAL_SPEC.md`, mockups in `docs/design/visual-spec/`, ADR-019
   (navigation), ADR-020 (Wear entry surface). Carried into implementation:
   verify color contrast with final Compose values during Step 7; the watch
   Listening screen depends on item 1's on-device speech findings.
