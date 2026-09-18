# Semantic regression recordings

Replayed by `com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`
(`./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`).

| File | Written by | Effect |
|---|---|---|
| `device-latest.json` | the on-device recorder | replayed; report `core-testing/build/reports/semantic-corpus/device.md`; gated by `baseline.json` |
| `baseline.json` | a human, from a reviewed device run | JSON array of case ids that must stay CORRECT, e.g. `["case-a", "case-b"]` |
| `standin-latest.json` | the local stand-in recorder | replayed; report `standin.md`; never fails on scores |

Recording format: see the header comment of `SemanticRecording.kt` (`formatVersion` 1).
Missing files make the corresponding test skip. Only real recordings belong here -- never
hand-written device or baseline files.
