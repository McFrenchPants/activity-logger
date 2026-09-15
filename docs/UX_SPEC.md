# UX Specification

## 1. UX objective

Activity Ledger succeeds only if logging is easier than deciding not to log.

The interface must minimize:

- taps
- decisions
- forms
- confirmation dialogs
- category choices
- typing

The primary UX target is:

> **One intentional action before speaking, zero required actions afterward.**

## 2. Mental model

The user should think:

> "I tell the app what I did."

Not:

> "I create and classify a database entry."

## 3. Phone information architecture

Recommended MVP surfaces:

1. **Capture / Home**
2. **History**
3. **Activity detail**
4. **Ask history**
5. **Review / correction**
6. **Settings / diagnostics**

The home screen may combine capture and recent history.

## 4. Phone capture flow

### 4.1 Happy path

1. User taps microphone.
2. App enters listening state immediately.
3. User speaks naturally.
4. App ends recognition automatically or user stops.
5. Raw transcript appears briefly.
6. Semantic interpretation runs.
7. Valid high-confidence result is persisted automatically.
8. UI shows compact success feedback.
9. App returns to ready state.

Example:

> "I cut the grass this morning."

Feedback:

> ✓ Mow lawn — this morning

No Save button is required.

### 4.2 Confidence/review path

If interpretation is unsafe:

> "I did the thing with the furnace."

The system should avoid confidently fabricating a category.

Possible response:

> Needs review  
> "I did the thing with the furnace."

Actions:

- choose activity
- create new activity
- retry voice
- keep uncategorized for later review

### 4.3 Recognition failure

If speech recognition fails:

- preserve audio only if product/privacy design explicitly allows it; audio retention is not required for MVP
- show concise error
- offer immediate retry
- do not create fabricated text

### 4.4 AI unavailable

If local Gemini Nano capability is temporarily unavailable:

- preserve raw transcript locally
- mark capture as awaiting interpretation
- retry when capability becomes available if appropriate
- do not send to cloud
- clearly indicate that logging was captured but not yet categorized

This distinction is important:

> "Captured" is different from "interpreted."

## 5. Wear OS flow

### 5.1 Happy path

Ideal flow:

1. Tap complication/tile/app action.
2. Dictation opens.
3. User speaks.
4. Watch obtains transcript or forwards supported voice input path.
5. Watch creates capture ID and durable pending record.
6. Watch sends capture to phone.
7. Phone acknowledges receipt.
8. Phone performs semantic interpretation and persistence.
9. Watch receives normalized result.
10. Watch gives short haptic success acknowledgement.
11. UI shows canonical activity briefly.

Example:

> ✓ Mow lawn

### 5.2 Phone unavailable

If the watch cannot reach the phone:

> Queued — will send when phone reconnects

The user should not be asked to repeat the entry.

### 5.3 Duplicate delivery

Transport retries should be invisible to the user.

The capture ID makes processing idempotent.

## 6. History screen

Default ordering:

- newest occurrence first

Each row should emphasize:

- canonical activity name
- occurrence date/time
- state if relevant

Secondary detail may show:

- original phrase
- source device
- low-confidence/review indicator

Example:

```text
Mow lawn
Today, afternoon
"I cut the grass this afternoon"

Replace furnace filter
Sep 12, 9:40 AM
"Changed the HVAC filter"
```

Times are shown only as precisely as the user said them (ADR-018): "this afternoon" displays as "afternoon", not a made-up clock time. See `docs/UX_VISUAL_SPEC.md` §4.3.

## 7. Activity detail

An activity detail screen should answer:

- When did I last do this?
- How many logged occurrences exist?
- What are recent occurrences?
- What phrases have mapped here?

MVP should show:

- canonical name
- occurrence count
- latest occurrence
- chronological occurrence list
- aliases if implemented visibly
- edit/rename action if safe
- correction entry point

## 8. Correction UX

Correction is important, but should not burden normal capture.

### 8.1 Correction entry

From an occurrence:

> Edit interpretation

The correction screen should show:

**Original capture**
> "I edged the lawn."

**Interpreted as**
> Mow lawn

Then permit:

- choose existing canonical activity
- create a new canonical activity
- correct occurrence timestamp if wrong
- correct state if wrong

### 8.2 Audit visibility

The UI does not need to expose every technical provenance field.

However, it must never make the original phrase disappear.

A future specialized review tool will use richer audit data.

## 9. Ask History

The query interface should look conversational, but execution should be constrained.

Examples:

> When did I last change the furnace filter?

> How many times did I mow in August?

> Show me when I cleaned the gutters this year.

> How often do I change the oil?

Response should prioritize database truth.

Example:

> Last logged furnace filter replacement: September 15, 2026 at 8:42 AM.

Optionally show:

- previous occurrence
- interval between latest two
- related occurrence list

## 10. New canonical activity UX

When a new concept is confidently inferred, the system may create it automatically under the configured policy.

If confidence is insufficient:

> New activity?
> "Flush water heater"

User can:

- accept
- select existing activity
- rename proposed activity

Do not ask for category/tag metadata in the MVP capture path.

## 11. Undo

After a successful phone capture, a short-lived Undo action is desirable.

Undo should:

- hide/retract the occurrence safely
- preserve raw capture/audit evidence according to retention policy

## 12. Accessibility

- support large text
- provide content descriptions
- do not rely on color alone for state
- use haptics appropriately on watch
- ensure microphone controls have clear state
- preserve reasonable touch target sizes
- support TalkBack

## 13. Empty states

History empty:

> No activities logged yet. Tap the microphone and say what you just did.

Ask History empty:

> There isn't enough history yet to answer that.

## 14. Error language

Prefer precise and recoverable messages.

Good:

> Saved your words, but couldn't categorize them yet.

Good:

> Phone unavailable. This entry is queued on your watch.

Avoid:

> Something went wrong.

## 15. Deliberate UX exclusions

Do not introduce:

- inboxes
- project assignment
- priority flags
- due dates
- streaks
- task completion checkboxes
- kanban boards
- calendar planning
- recurring task setup

Those patterns would shift the mental model toward task management.
