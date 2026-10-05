# DH1 (Explore) release evidence -- 2026-10-05

- Merged to main: e28efd3 (stage 3 merged earlier on 2026-10-04; stage 4 d9c7342; live refresh 33d4ce3).
- Verification: verifier pass on DH2.1, DH3.1, DH4.2, DH4.3, DH4.4, DH4.5, DH4.6 (attempt 2); orchestrator spot-checks on DH1.1, DH3.2, DH4.1.
- Tests at release: core-domain, core-ai, core-testing, core-data (180), app-phone (398) all green; assembleDebug/assembleRelease OK.
- Question corpus: Pixel 10 Pro recording replayed 46/46 CORRECT; gated by recordings/question-baseline.json (maxWrong 0).
- Device: Pixel 10 Pro checks by the orchestrator (DH3.3) and by the owner (2026-10-05: live refresh; spoken question answered correctly).
- Tracking: docs/proposals/explore/PROGRESS.md.
