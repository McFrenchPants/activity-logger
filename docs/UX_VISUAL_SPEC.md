# UX Visual Specification

## 1. Purpose

`docs/UX_SPEC.md` and `docs/WATCH_SPEC.md` define behavior, flows, and copy.
This document defines the **visual layer and the UI decisions those specs left
open**: phone navigation, the first Wear entry surface, the design system,
Settings/diagnostics contents, undo mechanics, and the Ask History interaction
shape.

It is a constraint document for Step 7 (phone UX) and Step 8 (Wear capture) of
`docs/IMPLEMENTATION_HANDOFF.md`. Where this document and `UX_SPEC.md`
disagree on behavior, `UX_SPEC.md` wins and the discrepancy must be recorded
(AGENTS.md §8). Where they disagree on visuals, this document wins.

Nothing here changes the data model or an architectural invariant.

## 2. Mockups

The mockups live in [`docs/design/visual-spec/`](design/visual-spec/) and are
the visual reference for every screen named below. See that folder's
`README.md` for how to view and regenerate them.

| Canvas page | Artboards |
|---|---|
| Phone · Capture | Log ready, listening, saved (with undo), needs review (§4.2), AI unavailable (§4.4), recognition failure (§4.3) |
| Phone · History, Ask, Correction | History (§6), occurrence sheet, Activity detail (§7), Edit interpretation (§8), Ask thread (§9, §10), empty states (§13) |
| Phone · Settings & accessibility | Settings with model downloading, Settings on an unsupported device, accessibility (dark, 200% text, TalkBack notes) |
| Watch | Listening, Queued, Success, Needs review, Failure — each in interactive and ambient rendering; capture complication |
| Design system & decisions | Tokens, type, state vocabulary; decisions D1–D7 |

Sample data in the mockups is illustrative, not seed data.

## 3. Decisions

### D1 — Phone navigation

See ADR-019.

- One `Activity`, one Compose Navigation `NavHost` with type-safe routes.
- M3 `NavigationBar` with three top-level destinations:
  - **Log** — start destination. Capture and recent history on one screen
    (UX_SPEC §3 "home may combine capture and recent history").
  - **History**
  - **Ask**
- Each top-level destination keeps its own back stack. System Back from
  History or Ask returns to Log.
- *Built so far (UI1, 2026-09-19):* Log and History only. Ask is left out of
  the bar until it exists (build-guide Step 9) rather than shown disabled; the
  Settings icon is also not built yet.
- **Settings / diagnostics** opens from an icon button in the Log top bar.
- Tapping an occurrence (History row, Recent row, Activity detail row) opens
  an **occurrence bottom sheet**: the user's words first, then activity,
  when, state, captured time/source, and actions *Edit interpretation*,
  *Open activity*, *Remove from history*.
- **Activity detail** and **Edit interpretation** are pushed full screens.
- **Review is not a destination** (see D7).
- Deep link `activityledger://capture` opens Log already listening (for a
  launcher shortcut or assistant entry).

Navigation graph:

```text
Log (start) ──► Settings
History ──► Occurrence sheet ──► Edit interpretation
        └─► Activity detail ──► Occurrence sheet
Ask ──► Activity detail (from an answer's source)
Needs-review card "Choose another activity" ──► shared activity picker
```

The activity picker is one component shared by the needs-review card, the
Ask "Choose existing" action, and Edit interpretation.

### D2 — First Wear entry surface

See ADR-020.

- Step 8 ships the **app launcher** entry (required regardless) and a
  **watch-face complication**. **Tile is deferred.**
- Complication tap opens capture directly in the listening state.
- Complication type: monochromatic mic image. While captures are waiting for
  the phone it adds a short-text queued count (content description e.g. "Log
  activity. 2 entries waiting for phone."). It never shows a review count —
  review happens on the phone.
- The designed watch Listening screen applies to in-app speech recognition.
  If on-device recognition is not available on the watch (backlog item 1),
  the system dictation UI is used instead and the app's own screens start at
  Queued/Success/Needs review/Failure.

### D3 — Visual design system

- **Phone:** Jetpack Compose Material 3. **Watch:** Wear Compose Material 3.
- **Static brand color scheme; dynamic color off for MVP.** Dynamic color
  would re-tint primary per wallpaper and make "saved" vs "needs review"
  unpredictable.
- **Light and dark** themes; follow the system setting. Wear is dark only.
- **Typography — two voices:**
  - *Evidence* (anything the user actually said — raw capture text, alias
    phrases): **Source Serif 4 Italic**, always in curly quotes.
  - *Interpretation and UI* (canonical names, times, states, controls):
    **Instrument Sans**.
  - Evidence is never styled like app output, so the original phrase stays
    recognisable everywhere (UX_SPEC §8.2).
  - Both families are SIL OFL and **bundled as font resources** — no
    downloadable fonts, no network.
  - Watch uses the Wear M3 default typography.
- **One accent** (ledger green) plus one custom role (review ochre) added via
  an extended color scheme; error uses M3 `error`.

#### Color tokens

| Role | Light | Dark |
|---|---|---|
| background | `#F7F4EE` | `#161513` |
| surface | `#FFFDF9` | `#1E1C19` |
| surfaceContainer | `#EFEAE1` | `#282521` |
| surfaceContainerHigh | `#E7E1D6` | `#322E29` |
| outlineVariant | `#D6CEBF` | `#403B35` |
| outline | `#9C9486` | `#7A7368` |
| onSurface | `#22201C` | `#EDE8DF` |
| onSurfaceVariant | `#6B655B` | `#A69F93` |
| primary | `#2F6B55` | `#8FC9AE` |
| onPrimary | `#FFFFFF` | `#0B2A1E` |
| primaryContainer | `#D5E8DD` | `#1E4838` |
| onPrimaryContainer | `#0F3527` | `#CDEBDC` |
| review *(custom)* | `#855610` | `#E4BA6C` |
| reviewContainer *(custom)* | `#F3E4C4` | `#3B2E16` |
| onReviewContainer *(custom)* | `#3D2804` | `#F6E3BD` |
| error | `#A33B2C` | `#F0A193` |
| errorContainer | `#F6D9D3` | `#4B221B` |
| onErrorContainer | `#44120A` | `#FAD9D2` |

Text/background pairs must meet 4.5:1; verify during Step 7 with the final
Compose values.

Watch ambient palette: pure black ground, text and glyphs no brighter than
`#B8B2A8`, secondary `#6E6960`.

#### Type scale (sp)

| Role | Face · weight | Size / line height |
|---|---|---|
| headlineMedium | Instrument Sans 600 | 28 / 36 |
| titleLarge | Instrument Sans 600 | 22 / 28 |
| titleMedium | Instrument Sans 600 | 17 / 24 |
| bodyLarge | Instrument Sans 400 | 16 / 22 |
| bodyMedium | Instrument Sans 400 | 14 / 20 |
| labelLarge | Instrument Sans 600 | 15 / 20 |
| evidence | Source Serif 4 Italic 400 | 14–28, line height ≈ 1.4× |

Numerals use tabular figures.

#### Shape and spacing

- Cards 16dp radius; fields and list containers 12dp; chips 8dp; buttons
  full pill.
- Capture button 112dp; 96dp or 88dp when a feedback card is shown above it.
- 16dp screen gutter. 48dp minimum touch target. List rows min height 72dp.

#### State vocabulary

Every state is a distinct **icon shape + text label + color**. Removing color
must leave it readable.

| State | Icon | Label | Color | Meaning |
|---|---|---|---|---|
| Saved | check in circle | *(no row tag; "✓ …" in feedback)* | primary | Captured, interpreted, persisted |
| In progress | half-filled circle | In progress | primary | `ActivityState.IN_PROGRESS` |
| Needs review | question in circle | Needs review | review, on reviewContainer | Interpretation unsafe; words saved, nothing guessed |
| Not categorized yet | dashed circle with clock | Not categorized yet | onSurfaceVariant, dashed outline | Captured but not interpreted (AI unavailable) |
| Queued | up arrow over line | Queued | onSurfaceVariant | On the watch, waiting for the phone |
| Couldn't capture | exclamation in circle | Couldn't capture | error, on errorContainer | Nothing saved; always paired with retry |

No sparkle or "AI" iconography. An interpretation is presented as a plain fact
with its evidence beside it.

### D4 — Settings / diagnostics contents

Groups, in order:

1. **On-device interpretation**
   - Gemini Nano status: *Ready* / *Downloading model · n% of size* (with
     progress bar) / *Not supported*.
   - Structured Output availability.
   - Count of captures waiting to be categorized, linking to History filtered
     to *Not categorized*.
   - On an unsupported device, state plainly that captures are still saved,
     won't be categorized automatically, can be categorized from History, and
     that the app never falls back to cloud AI.
2. **Speech** — on-device recognizer availability and language.
3. **Watch** — paired device, connection state, last capture received, items
   queued; or "No watch paired".
4. **Privacy** — "Your words stay on this phone"; **Copy technical report**
   containing device/model/capability status only, **never activity text**
   (AGENTS.md §11).
5. Version line: app version, interpreter prompt version, database schema
   version.

These four capability rows are the visible surface for ARCHITECTURE.md §21
(AICore, Structured Output, speech, Data Layer). Export, backup choices, and
other settings are omitted until they have real behavior.

When AI is unavailable, Log also shows a slim row under the top bar ("On-device
AI isn't ready. Captures are still saved.") linking to Settings.

### D5 — Undo mechanics

- Undo lives **inside the inline success card** on Log (not a snackbar).
- Visible for **8 seconds**, shown by a draining progress bar.
- Timer pauses while the card is touched and is passed through
  `AccessibilityManager.getRecommendedTimeoutMillis(8000, FLAG_CONTENT_TEXT or
  FLAG_CONTENT_CONTROLS)` — effectively no timeout while TalkBack is on.
- Starting a new capture or leaving Log dismisses the card (the occurrence
  stays saved).
- Undo sets the occurrence's `visibilityStatus` to hidden. The RawCapture and
  Interpretation are kept (UX_SPEC §11).
- The card also carries **Change activity** (owner decision 2026-09-19, core
  of backlog 11): it opens the shared activity picker (with *New activity*)
  and applies a user correction to the occurrence; the card then shows the
  new name. Only offered while the card is visible.
- Undo is a visibility change, not a correction (ADR-034).
- After the window closes, the same effect is available as **Remove from
  history** in the occurrence sheet.
- No undo on the watch in MVP.

Rationale: the success card is where the user is already looking; a snackbar
would stack over the NavigationBar and compete with it. 8 s sits between M3's
short (4 s) and long (10 s) durations.

### D6 — Ask History interaction shape

- A **scrollable session thread**: question bubble → answer card.
- Answer cards state the database fact first (UX_SPEC §9 copy pattern), may
  show the previous occurrence and interval, and end with a tappable **source
  line** naming the canonical activity and scope (→ Activity detail).
- If the input is a statement of something done rather than a question, the
  thread shows the UX_SPEC §10 **"New activity?"** card (or a matched existing
  activity) with *Log it*, *Choose existing*, *Rename*. Logging from Ask
  requires this explicit confirmation because intent was ambiguous.
- Input bar: text field plus mic button.
- The thread is **in memory for the session only**; cleared by *Clear* or
  process death. Questions are not persisted.
- Insufficient data uses the §13 copy as an answer card.

### D7 — Review without an inbox

- Needs-review and not-categorized captures are reachable from **History
  filter chips** (*All* / *Needs review* / *Not categorized*) and from the
  Settings waiting count.
- No NavigationBar badge, no notification, no "clear all" goal, no unread
  state (UX_SPEC §15).

## 4. Screen rules

### 4.1 Log (capture + recent)

- Prompt: "Say what you just did." Capture button with "Tap to speak"; quiet
  secondary *Type instead* (see §6).

  > **Amended 2026-09-19 (owner decision, VC1).** This voice-first hero layout
  > was **not** built. The owner chose, after using the typed screen on the
  > phone, to keep the **text field as the primary control with a microphone
  > icon button beside it**, rather than a large capture button with typing as
  > a secondary path. The listening state, the result cards and the
  > recognition-failure card below are all as specified; only the resting
  > layout differs. The mockups still show the original arrangement — treat
  > this note, not the mockup, as current for the Log screen's input row.
- Listening: "Listening…" live region, live transcript in evidence style,
  stop button with rings, "Tap to stop"; recent list dimmed and hidden from
  accessibility.
- Result card replaces the prompt (**Log result cards (tags)**, ADR-046; the
  Log screen works on a subject and an action, not one activity name):
  - **Saved:** primaryContainer card, "✓ Hot tub · Change filter — 30 min —
    just now" (the duration part is left out when there is none), quoted words,
    *Undo* (D5), *Change subject* and *Change action* (each opens the tag
    picker; the card then shows the new names and the undo window keeps
    running). New row highlighted at top of Recent.
  - **Check this** (one card for a close match, a needs-review or rejected
    result, and an unavailable AI): reviewContainer card, "Needs review" tag,
    quoted words, "Your words are saved. Nothing was guessed." Then two sides,
    *Subject* and *Action*. A side that was understood exactly (or as a new
    name) starts chosen, with *Change*; a close match offers its existing tags
    as one-tap options plus *Keep mine: <words>*; a side with nothing
    understood says "Nothing chosen yet" and offers *Choose subject* / *Choose
    action*. *Save* is on only when both sides are chosen; nothing is saved
    before it. *Decide later* dismisses the card and the words stay waiting.
  - **Recognition failure:** errorContainer card, no fabricated text, *Try
    again* and *Type instead*.
- Recent shows three rows and *All history*.

### 4.2 Rows (History, Recent)

- Interpreted rows: canonical name (titleMedium) → time (+ state tag) →
  quoted words.
- *Tagged rows (ADR-047):* the title is "Subject · Action" and the duration,
  when known, sits beside the time. Entries from the old pipeline keep their
  activity name and are not clickable.
- Uninterpreted rows lead with the state tag and the user's words in place of
  a name.
- *History taps (ADR-047):* a waiting row opens a sheet with the same Check
  card as Log (both sides chosen before *Save*, close matches with *Keep mine*,
  *Decide later*); its starting point is rebuilt from the stored words
  without the model, and it saves at the capture time. A saved tagged row
  opens a small *Edit entry* sheet: the words, *Change subject*, *Change
  action* and *Remove from history* (hides it; the words stay saved).
- Trailing source-device icon (phone/watch) with content description.
- Newest first.

### 4.3 Time display (ADR-018)

| `timePrecision` | Display |
|---|---|
| EXACT / INFERRED_NOW | `Today, 3:12 PM` · `Sep 12, 9:40 AM` |
| APPROXIMATE | `Today, afternoon` · `Aug 23, morning` |
| DATE_ONLY | `Sat, Sep 12` |

Never render a clock time the user did not give or that was not the capture
moment.

### 4.4 Edit interpretation

- Top: two-column panel — **Original capture** (lock icon, quoted words,
  captured time and source) beside **Interpreted as** (activity, state, time).
  The panel stays visible while the form scrolls.
- Note: "The original stays exactly as captured. Your change is recorded
  alongside it."
- *Correct to*: activity (shared picker, can create new), when, state
  (Completed / In progress segmented control). *Save* in the top bar.

### 4.5 Activity detail

Canonical name; *Last logged* and *Logged n times*; "Words that mean this"
(alias phrases in evidence style); occurrences newest first; rename action in
top bar. No averages, streaks, or scores.

### 4.6 Watch

- Glanceable single message per state, copy exactly as WATCH_SPEC §11.
- Ambient rendering: black ground, outline-only glyphs, no brand fills, dimmed
  text, under ~10% lit pixels.
- Haptics: Queued — one short tick; Success and Needs review — two short
  ticks; Failure — one long pulse; Listening — none.

## 5. Accessibility (UX_SPEC §12)

- Capture button content descriptions: "Log by voice" / "Stop listening".
  Transcript and result cards are polite live regions (e.g. "Saved. Hot tub,
  Change filter, 30 min, just now. Undo available.").
- Rows use merged semantics read as one sentence: name, time, source, "Your
  words: …".
- State is never color alone (§3 D3 state vocabulary).
- NavigationBar labels always visible.
- Text in sp and reflows at 200%; containers use min-height, never fixed
  height; evidence text is never truncated without an expand affordance.
- 48dp minimum targets; capture button does not shrink with font scale.
- Undo timeout honors accessibility recommended timeouts (D5).

## 6. Copy

Use UX_SPEC §4, §5, §13, §14 and WATCH_SPEC §11 copy verbatim. Copy introduced
by this document where the specs gave none:

| Where | Copy |
|---|---|
| Log prompt | Say what you just did. |
| Needs review reassurance | Your words are saved. Nothing was guessed. |
| AI unavailable follow-up | They'll be categorized on this phone when on-device AI is available. |
| Phone recognition failure | Couldn't make out any words. Nothing was saved. |
| Ask statement detection | That sounds like something you did, not a question. |
| Unsupported device | This phone can't run on-device AI. |

"Type instead" is a typed-text entry into the same capture pipeline for noisy
places and speech-unavailable devices; the RawCapture records the input as
typed.

## 7. Exclusions

In addition to UX_SPEC §15: no checkboxes, unread/count badges, priority
flags, due-date pickers, streak or score widgets, kanban columns, calendar
grids, or "AI" sparkle iconography.

## 8. Resolved with the owner (2026-09-15)

- Mockups reviewed and approved as the visual reference through MVP delivery.
- Devices that can never run Gemini Nano (e.g. the Pixel 7 Pro) are test
  devices only, not a supported product target. The "not supported" Settings
  state and uncategorized captures are kept because capability detection
  requires an honest fallback (ARCHITECTURE.md §21), but no extra UX is built
  for that case.

## 9. Open items

- ~~Watch Listening screen depends on on-device speech availability on the
  OnePlus Watch 3 (backlog item 1).~~ **Answered 2026-09-19, and the answer is
  no** (ADR-035): the OnePlus Watch 3 reports no on-device recognition and
  `createOnDeviceSpeechRecognizer()` throws there. The watch Listening screen
  (§3 D2) **cannot be built as designed**, and the ordinary network-capable
  recognizer is not an acceptable substitute (ADR-005, AGENTS.md §11). **Update 2026-10-01:** superseded -- the ordinary in-app recognizer was measured working fully offline on the watch (ADR-035), so the Wear Listening screen is buildable again. ADR-035
  lists the options; choosing one is a product decision for the Wear work item.
- **Phone voice capture is built** (VC1, 2026-09-19): microphone control,
  listening state with a live partial transcript, and the recognition-failure
  card of §4.1/§6. Outstanding from that work, for a real-device pass: whether
  the "Listening…" announcement is genuinely useful to a screen reader as
  partials change, whether the Recent list's dimming reads correctly, the
  failure card's contrast in both themes, and where focus lands after
  *Type instead*. Each is reasoned and coded but not verified by a test.
- ~~Verify contrast ratios with final Compose color values.~~ Done
  2026-09-19: every text/background pair used by Log and History (body,
  secondary text, green actions, review colours, on both the page and the
  review card) is at least 4.6:1 in light and 5.0:1 in dark.
