# Owner bug reports, 2026-10-14 (work item BF1)

| Task | Change | Status | Notes |
|---|---|---|---|
| BF1.1 | Log Recent rows open the entry in History (check sheet for awaiting, edit sheet for tagged) | done | Owner asked 2026-10-01 (question was left unanswered then) and 2026-10-14. One-shot in-memory request holder in LedgerNavigation, not a route argument (restoreState would re-deliver it). No verifier floor/widen trigger; 409 tests green. |
| BF1.2 | Watch captures received in the background are stored raw and acked NEEDS_REVIEW; pending CAPTURED/FAILED_RETRYABLE captures are interpreted on each 0->1 resumed-activity transition under one process-wide lock (receiver, catch-up, LogViewModel) | done | Phone DB 2026-10-14: last 3 WATCH_VOICE captures INTERPRETER_FAILED (ADR-029 background refusal). Signal is "resumed", not "started": a refusal maps to OTHER -> NEEDS_REVIEW and is never retried. Verifier pass (attempt 1). 429 tests green. Not yet confirmed on device with a real background watch capture. |
