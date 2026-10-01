# Semantic regression recordings

Replayed by `com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`
(`./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest`);
the `tag-*` files by `com.mcfrenchpants.activityledger.core.testing.corpus.TagRegressionGateTest`.

| File | Written by | Effect |
|---|---|---|
| `device-latest.json` | the on-device recorder | replayed; report `core-testing/build/reports/semantic-corpus/device.md`; gated by `baseline.json` |
| `baseline.json` | a human, from a reviewed device run | JSON array of case ids that must stay CORRECT, e.g. `["case-a", "case-b"]` |
| `standin-latest.json` | the local stand-in recorder | replayed; report `standin.md`; never fails on scores |
| `tag-device-latest.json` | the on-device TAG recorder (`scripts/semantic/run-device-corpus.sh --tags`, or `import-tag-recording.sh` for the in-app runner) | tag corpus, extraction prompt v4; structural check (`TagRecordingFilesTest`), then replayed by `TagRegressionGateTest`; report `core-testing/build/reports/semantic-corpus/tag-device.md`; gated by `tag-baseline.json` |
| `tag-baseline.json` | a human, from a reviewed device tag run | JSON object `{ "mustStayCorrect": [case ids], "maxUnsafe": N, "maxUnsafeRealEntry": N }`, every key required, unknown keys rejected (see `TagGate.kt`); absent until the first reviewed device run, and while absent the tag gate is skipped |
| `tag-standin-latest.json` | the local stand-in TAG recorder (`scripts/semantic/run-standin-corpus.sh --tags`) | tag corpus, extraction prompt v4; structural check, then replayed by `TagRegressionGateTest`; report `tag-standin.md`; never gated; NOT official |

Recording format: see the header comment of `SemanticRecording.kt` (`formatVersion` 1). The two
`tag-*` files use the separate tag recording format in `TagRecording.kt` (`formatVersion` 1).
Missing files make the corresponding test skip. Only real recordings belong here -- never
hand-written device or baseline files.

How the corpus, recordings, gate and baseline fit together: [`docs/SEMANTIC_CORPUS.md`](../../../../../../docs/SEMANTIC_CORPUS.md).
