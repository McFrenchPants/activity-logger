# Semantic regression recordings

Replayed by `com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`
(`./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`).

| File | Written by | Effect |
|---|---|---|
| `device-latest.json` | the on-device recorder | replayed; report `core-testing/build/reports/semantic-corpus/device.md`; gated by `baseline.json` |
| `baseline.json` | a human, from a reviewed device run | JSON array of case ids that must stay CORRECT, e.g. `["case-a", "case-b"]` |
| `standin-latest.json` | the local stand-in recorder | replayed; report `standin.md`; never fails on scores |
| `tag-device-latest.json` | the on-device TAG recorder (`scripts/semantic/run-device-corpus.sh --tags`) | tag corpus, extraction prompt v4; structural check only (`TagRecordingFilesTest`); no score or gate yet |
| `tag-standin-latest.json` | the local stand-in TAG recorder (`scripts/semantic/run-standin-corpus.sh --tags`) | tag corpus, extraction prompt v4; structural check only (`TagRecordingFilesTest`); NOT official |

Recording format: see the header comment of `SemanticRecording.kt` (`formatVersion` 1). The two
`tag-*` files use the separate tag recording format in `TagRecording.kt` (`formatVersion` 1).
Missing files make the corresponding test skip. Only real recordings belong here -- never
hand-written device or baseline files.

How the corpus, recordings, gate and baseline fit together: [`docs/SEMANTIC_CORPUS.md`](../../../../../../docs/SEMANTIC_CORPUS.md).
