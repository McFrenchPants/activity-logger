# Manual test notes — phone voice capture

For the owner to fill in. Write whatever you notice next to each item, in
whatever words — "fine", "felt slow", "got it wrong, said X heard Y". Anything
you write here gets read at the start of the next session and turned into
backlog items or fixes.

Build: `feature/voice-capture` (not merged). Installed on the Pixel 10 Pro
2026-09-20.

**The single most useful thing you can do: log real things you actually did,
over a few days, rather than test phrases.** Three invented sentences tell us
much less than twenty real ones.

---

## 1. Does it hear you correctly?

The thing we have no measurement of at all. For each capture, if the words came
out wrong, note what you *said* and what it *wrote*.

| What you said | What it heard | OK? |
|---|---|---|
|  |  |  |
|  |  |  |
|  |  |  |

Worth trying deliberately: talking at normal volume from arm's length; outdoors
or with background noise; a long sentence; an activity with an unusual word in
it.

## 2. Does it file things in the right place?

Separate question from whether it heard you. It can hear perfectly and still
choose the wrong activity.

| What you said | Where it filed it | Right? |
|---|---|---|
|  |  |  |
|  |  |  |

We know from earlier testing that it confidently picks wrong sometimes
(edging → mowing, raking → mowing). If you see that, note it — it feeds
backlog item 13.

## 3. The moments that might annoy you

- Is the 8-second window before the card disappears too short, too long?
- Does the pause between finishing speaking and seeing the result feel
  acceptable, or too long?
- Is the microphone button where you want it, at the size you want?
- Anything that made you think "that's not what I expected"?

## 4. Known rough edges — confirm or dismiss

Things we suspect but haven't seen in real use. No need to hunt for these;
just note if you hit one.

- A capture made while the app is in the background won't be categorised until
  you next open the app. Did you ever notice that happening?
- If the phone is busy running AI for something else, a capture can take up to
  ~6 seconds longer. Ever seen it?
- **You cannot correct an entry once its 8-second window has passed.** This is
  a real gap, not a bug — the screen for it isn't built. Note how often you
  wanted to.

## 5. One specific thing to check for me

Make one voice capture, then tell me. I want to confirm a fix landed: the app
should now record "confidence unknown" rather than a fake zero. Takes me ten
seconds to verify once there's a fresh capture in the database.

---

## Anything else

Free space. Including "I stopped using it after a day because…" — that's the
most valuable note of all.
