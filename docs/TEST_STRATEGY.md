# Test Strategy

## 1. Objective

Activity Ledger has conventional software risks plus a unique semantic correctness risk.

A build can compile and still be wrong if:

- synonymous phrases fragment into separate activities
- distinct activities collapse together
- time expressions resolve incorrectly
- corrections destroy original evidence

Testing must therefore treat semantic behavior as first-class product logic.

## 2. Test layers

### Unit tests

Target:

- activity resolution rules
- temporal resolver
- validation
- confidence policy
- canonical naming validation
- correction logic
- query intent validation
- idempotency helpers

### Repository/database tests

Target:

- Room DAOs
- transactions
- occurrence queries
- correction audit trail
- raw capture immutability behavior
- migrations

### AI contract tests

Target:

- prompt schema decoding
- invalid IDs
- missing fields
- unsupported enum values
- capability unavailable paths

### Semantic device tests

Run against supported Gemini Nano devices/emulators where possible.

Target:

- actual model behavior
- regression corpus
- prompt-version comparisons

### Wear transport tests

Target:

- duplicate delivery
- reconnect
- queue persistence
- acknowledgement
- phone unavailable
- process restart

### UI tests

Target:

- capture state transitions
- history
- correction
- ask-history
- watch status UX

## 3. Semantic regression corpus

Every semantic case should specify:

```text
name
existing canonical activities
raw utterance
capture time/time zone
expected activity resolution
expected activity ID or new canonical name
expected state
expected temporal expression
allowed alternatives
must-not-match activities
```

Store corpus in machine-readable format such as JSON/YAML plus readable documentation.

Implemented (work item SR1): the corpus is `core-testing/src/main/resources/semantic-corpus/corpus.json`, with every field above required per case. A model's answers are recorded once (on the phone, or with a local stand-in model for iteration) and replayed through the real pipeline on every `:core-testing:test` run, with a regression gate over a human-written baseline (ADR-033). How it works, how to add a case and how to run it: [SEMANTIC_CORPUS.md](SEMANTIC_CORPUS.md).

## 4. Seed corpus

### Mow lawn: equivalent

- "I mowed the lawn."
- "I cut the grass."
- "Finished mowing."
- "Just mowed."
- "I did the grass."
- "The lawn is cut."

Expected canonical:

`Mow lawn`

### Edge lawn: distinct

- "I edged the lawn."
- "Finished edging."
- "Did the lawn edges."

Expected:

`Edge lawn`

Must not match:

`Mow lawn`

### Furnace filter: equivalent

- "Changed the furnace filter."
- "Replaced the HVAC filter."
- "Put a new filter in the furnace."
- "Swapped the air filter."

Expected:

`Replace furnace filter`

### Gutter cleaning

- "Cleaned the gutters."
- "Cleared leaves out of the gutters."
- "Did the gutters."

Expected:

`Clean gutters`

### Dryer vent

- "Cleaned the dryer vent."
- "Cleared lint out of the dryer pipe."

Expected:

`Clean dryer vent`

Potential ambiguity should be tested for generic "dryer" phrasing.

## 5. Temporal regression corpus

Set fixed capture context:

```text
2026-09-15T20:00:00-04:00
America/Detroit
```

Cases:

- "Mowed yesterday."
- "Mowed this morning."
- "Mowed Saturday."
- "Mowed about an hour ago."
- "Just finished mowing."
- "Mowed on September 1st."

Verify both:

- extracted expression
- deterministic resolution

Do not assert false precision when phrase is approximate.

## 6. Ambiguity corpus

Inputs:

- "Worked on the yard."
- "Did the furnace thing."
- "Handled the filter."
- "Fixed that thing outside."

Expected:

- ambiguous/unresolved unless context genuinely makes it safe

Do not reward aggressive guessing.

## 7. Query corpus

Existing history:

- Mow lawn Sep 1
- Mow lawn Sep 8
- Mow lawn Sep 15
- Replace furnace filter Jun 1
- Replace furnace filter Sep 1

Questions:

- "When did I last cut the grass?"
- "How many times did I mow this month?"
- "When was the previous furnace filter change?"
- "How often am I changing the furnace filter?"

Expected query intents and deterministic results should be asserted.

## 8. Correction tests

Scenario:

Raw:

> "I edged the lawn."

Original interpretation:

`Mow lawn`

User corrects to:

`Edge lawn`

Assertions:

- raw text unchanged
- original Interpretation remains
- Correction created
- occurrence effective activity becomes Edge lawn
- Mow lawn history no longer counts corrected occurrence as effective
- audit can reconstruct old interpretation

## 9. Watch idempotency tests

Send same capture envelope 3 times.

Expected:

- one RawCapture
- one effective ActivityOccurrence
- repeated acknowledgements allowed
- no duplicate activity history

## 10. Process-death tests

### Watch

- queue capture
- kill process
- restart
- reconnect phone
- capture eventually delivered once

### Phone

- persist RawCapture
- terminate during inference
- restart
- processing state allows safe retry

## 11. Migration tests

Every production schema version change must:

- migrate representative data
- preserve raw captures
- preserve canonical activities
- preserve interpretations
- preserve corrections
- preserve occurrence relationships

## 12. Performance measurements

Record on supported device:

- speech completion to transcript
- transcript to AI result
- total capture-to-save latency
- query interpretation latency
- DB query latency

Do not optimize against guessed bottlenecks.

## 13. Prompt comparison process

When changing prompt version:

1. run full semantic corpus on old prompt
2. run full corpus on new prompt
3. compare passes/regressions
4. review ambiguous behavior
5. document material changes
6. only then promote prompt version

## 14. Production semantic bug policy

Every real-world semantic bug becomes a regression case before or alongside its fix.

This is mandatory.

Over time, the corpus becomes a key product asset.

## 15. Acceptance test for core product

Given canonical activity:

`Mow lawn`

And the user says on phone:

> "I cut the grass this morning."

Then the system must:

- preserve exactly recognized raw text
- match Mow lawn
- interpret completed state
- resolve morning without false precision
- store occurrence
- expose it in history
- answer "When did I last mow?" using that occurrence

The same must be possible for a watch-originated capture when phone connectivity is available or restored.
