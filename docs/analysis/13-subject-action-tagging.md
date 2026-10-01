# Analysis 13 — Subject + action tagging instead of single-activity matching

Backlog item 13 ("Cut down wrong confident matches"), broadened by the owner on
2026-10-01. Status: **analysis agreed with owner in chat; plan below awaits
owner sign-off before any code.** No code has been changed.

## Plain-English summary

Today the AI must pick one existing activity from a list, or invent a new one.
With a nearly empty catalog it grabs whatever is closest, however unrelated, and
says it is confident. The owner's direction: describe every entry with two tags,
a **subject** (what it was done to: furnace, hot tub, lawn mower) and an
**action** (what was done: change filter, add gas, mow). Lookups match on both,
rank the best, and show close alternatives. New subjects and actions may be
created on the fly, but the app must prefer an existing one that looks close.

## Evidence (real data, pulled from the Pixel 10 Pro 2026-10-01)

Catalog at that point: Mow lawn, Trim hedges, Dishes, Hot tub, Walk dogs,
Furnace maintenance, Reboot WiFi, Weed garden. Watch entries, all with model
confidence HIGH:

| Heard by the watch | Result |
|---|---|
| I just changed the furnace filter | interpreter FAILED; owner created "Furnace maintenance" |
| I just changed the hot tub filter | Furnace maintenance (wrong subject) |
| Yesterday I changed the oil in the tractor | Hot tub (unrelated) |
| It just went and got gas for the lawn mower | Mow lawn (right subject, wrong action) |
| I weeded the garden for half an hour | Mow lawn, then review: "half an hour" read as the time |
| Yesterday I replaced the headlight on James Durango | Mow lawn (speech error: Dodge Durango) |
| I just walked the dogs for about 30 minutes | Mow lawn, then review: duration read as the time |
| Add to reboot the Wi-Fi | not recognized; owner created "Reboot WiFi" |
| I just cleaned the hot tub | Dishes |

Earlier phone entries show the same: "Did the dishes Saturday morning" and
"Add sanitizer to the hot tub" both first matched Mow lawn / Dishes.

Findings:

1. A flat list with few entries makes the model over-match; it always answers
   HIGH, so confidence (ADR-027) separates nothing.
2. The model is asked to do extraction and the hard "is this the same" judgment
   in one call. Only the first is something a small model is good at.
3. Duration is mistaken for time wording (two review bounces). Duration is not
   stored at all.
4. Speech errors ("It just", "James Durango") need tolerance; the raw text is
   immutable (AGENTS.md) so tolerance belongs in matching, not in editing text.
5. One interpreter failure (furnace filter) on the first try; ADR-030's
   one-shot rule means it is not retried.

## Owner decisions (2026-10-01)

- Two tags per entry: subject and action, both tags rather than a fixed activity.
- Lookup ("when did I last change the furnace filter?"): top result matches both
  subject and action, newest first; below it close matches (same subject other
  action, same action other subject) so the owner can judge.
- Watch shows only the top answer; phone shows the ranked list and details.
- Groupings such as "lawn maintenance" emerge from tags rather than being
  hand-built.
- New subjects/actions may be created dynamically, but processing must favor an
  existing tag that looks close. Must work from an **empty catalog**: after
  testing the owner will wipe everything and start fresh, so the first weeks
  have almost nothing to match against. Phone corrections must be easy.

## Proposed design (for sign-off)

**Model.** Each entry (occurrence) carries a subject tag, an action tag and an
optional duration. Tags are small records with a display name, normalized name,
aliases and a status, mirroring today's canonical activity record, which stays
the "specific description" (e.g. "Change furnace filter") that now points at one
subject and one action. Raw captures stay immutable; a tag change is a
correction record, as today.

**Intake in three steps.**

1. *Extract* (AI, one small question): subject, action, time wording, duration,
   state, each as the user's own words. No list is shown to the model, so there
   is nothing to over-match against. Duration becomes its own field, fixing
   finding 3.
2. *Resolve tags* (plain program logic, testable on the PC): normalize and
   compare against known subjects and actions by exact name, alias, and close
   spelling/stem match; tolerate speech slips. Result is one of: matched
   existing / near an existing (ask) / nothing close.
3. *Decide.* Auto-save only when both tags resolve to existing ones, or the
   tag is new but the extraction is clean and **nothing existing is close**.
   Anything with a close-but-not-exact existing tag goes to a quick confirm
   ("Hot tub or Furnace?") rather than a silent new tag, which stops duplicates
   like Reboot WiFi / Reboot Wi-Fi. The model's self-reported confidence is no
   longer a gate.

**Bootstrapping from empty.** New-tag creation is allowed and expected at the
start. To limit mess: subjects are normalized (lowercase, singular, article and
"the" dropped, simple plural/spelling merge); a new tag is created automatically
only when nothing existing is close; close-but-different asks first; the phone
offers merge and rename of tags in one or two taps; every correction adds the
user's wording as an alias so the same phrase never needs the AI again.

**Lookup.** Query returns ranked results: both tags match, then subject only,
then action only, newest first. Watch shows the top one; phone shows the list.
(The Ask screen itself is a later work item; this plan delivers the data and
the ranking logic, not the Ask UI.)

## Risks and costs

- Database change: new tag tables and columns, plus an upgrade path for the
  current schema. The owner intends to wipe test data, so the migration only
  needs to be safe, not to carry history. Still needs a migration test.
- All interpretation prompts and the 48-case test set change; the baseline
  must be rebuilt. New cases come from the real entries above.
- Tag mess over time (the owner accepted this). Mitigated by close-match
  preference, aliases, merge.
- Extraction quality on the small model is unmeasured; stage 1 measures it
  before the database work.

## Proposed plan (size: significant, needs a design spec)

Branch `feature/subject-action-tagging`, work item TG1.

- **Stage 1 — measure, no database change.** Add the real entries above and
  hot-tub-style cases to the test set; add an extraction recorder; run on the
  Pixel 10 Pro (short session); build the program-logic tag resolver against it.
  Gate: clearly fewer wrong silent saves than the 7 of 48 baseline.
  Stop and report to owner.
- **Stage 2 — data.** Tag tables, migration, repository, corrections and
  aliases, wipe/start-clean path.
- **Stage 3 — app.** Wire capture on phone and watch, confirm-close-match card,
  merge/rename tags.
- **Stage 4 — lookup.** Ranked query logic (UI later).

Open owner-level questions: none blocking. Naming ("subject", "action") will
stay as is unless the owner prefers other words in the screens.
