# WC1.6 -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Device pass and documentation

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- b85f772 2026-10-01 WC1.6: device pass; keep pending/finished capture on wake, tap result to capture again

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.6 | Device pass and documentation | done | Pixel 10 Pro + OnePlus Watch 3, owner spoke 3 captures: all reached the phone, were saved and acked; watch showed Saved / Saved-review-on-phone. Fixed on device: screen restarted listening on every wake (dropped the pending ack and showed a spurious failure) -> `resume()` keeps Queued/Saved/NeedsReview; tap on a result screen starts a new capture. NOT verified: retry with phone unreachable, ambient mode, background interpretation (ADR-029: phone app must be foreground) |
