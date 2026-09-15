# Domain Model

## 1. Overview

The domain model separates **evidence**, **interpretation**, and **effective history**.

This is the central integrity principle of the system.

A user utterance is evidence.

An AI interpretation is a hypothesis about that evidence.

A corrected interpretation may later supersede the hypothesis.

The original evidence remains unchanged.

## 2. RawCapture

Represents what the system received from the user before semantic normalization.

Core fields:

- `id`
- `source`
- `capturedAt`
- `rawText`
- `speechConfidence`
- `speechAlternatives`
- `deviceId` or anonymized source identifier where useful
- `processingState`
- `createdAt`

Invariant:

> `rawText` is immutable after capture finalization.

A typo produced by speech recognition is still part of the historical input evidence.

If later tooling wants to correct transcription separately, that correction should be represented independently rather than rewriting the original value.

## 3. CanonicalActivity

Represents a reusable semantic concept.

Examples:

- Mow lawn
- Edge lawn
- Replace furnace filter
- Clean gutters
- Change oil

Fields:

- `id`
- `displayName`
- `normalizedKey` if useful
- `createdAt`
- `updatedAt`
- `status` such as active/merged/archived

Identity must not depend on display name.

## 4. ActivityAlias

Represents a phrase known to refer to a canonical activity.

Examples for `Mow lawn`:

- cut grass
- mow grass
- mowing
- cut the lawn

Aliases may come from:

- curated seed data
- repeated high-confidence interpretations
- user corrections

The first MVP does not need to automatically convert every raw phrase into a durable alias. Alias promotion should be policy-driven.

## 5. Interpretation

Represents one semantic interpretation attempt for a RawCapture.

Fields may include:

- `id`
- `rawCaptureId`
- `interpreterVersion`
- `promptVersion`
- `schemaVersion`
- `createdAt`
- `operation`
- `activityResolution`
- `proposedCanonicalName`
- `matchedActivityId`
- `activityState`
- `temporalExpression`
- `resolvedOccurredAt`
- `modelConfidence`
- `validationStatus`
- `validationReason`
- `candidateContextHash`
- `isOriginalInterpretation`

A RawCapture may have more than one Interpretation over its lifetime if:

- the inference pipeline is rerun
- a new model/prompt is tested
- a repair process reinterprets older entries

## 6. ActivityOccurrence

Represents one actual logged occurrence in user history.

Fields:

- `id`
- `canonicalActivityId`
- `rawCaptureId`
- `effectiveInterpretationId`
- `capturedAt`
- `occurredAt`
- `state`
- `createdAt`
- `updatedAt`
- `visibilityStatus`

The occurrence is the primary record used by historical queries.

## 7. Correction

Represents a user-authorized correction to the effective meaning of an occurrence.

Fields:

- `id`
- `occurrenceId`
- `createdAt`
- `previousCanonicalActivityId`
- `newCanonicalActivityId`
- `previousOccurredAt`
- `newOccurredAt`
- `previousState`
- `newState`
- `reason`
- `source`

A Correction never overwrites RawCapture.

## 8. Activity state

MVP target states:

- `COMPLETED`
- `IN_PROGRESS`

Potential future states may exist but should not be added without need.

Planning-oriented states such as `TODO`, `SCHEDULED`, `OVERDUE` are intentionally excluded from MVP.

## 9. Activity resolution

Recommended interpretation outcomes:

- `EXISTING_ACTIVITY`
- `NEW_ACTIVITY`
- `AMBIGUOUS`
- `UNRESOLVED`

## 10. Processing state

Raw capture processing is separate from activity state.

Suggested states:

- `CAPTURED`
- `QUEUED_FOR_PHONE`
- `TRANSCRIBED`
- `INTERPRETING`
- `INTERPRETED`
- `PERSISTED`
- `NEEDS_REVIEW`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`

## 11. Historical time concepts

### capturedAt

When the user provided the utterance.

Example:

September 15, 2026 at 8:00 PM.

### occurredAt

When the activity is believed to have occurred.

If the user says:

> "I cleaned the gutters Saturday."

then:

- `capturedAt` = Monday
- `occurredAt` = Saturday

The exact resolution policy is documented in the AI/temporal specification.

## 12. Evidence vs effective state example

Raw capture:

> "I edged the lawn."

Original interpretation:

`Mow lawn`

Occurrence initially points to:

`Mow lawn`

User correction:

`Edge lawn`

After correction:

- RawCapture still says `"I edged the lawn."`
- original Interpretation still says `Mow lawn`
- Correction records `Mow lawn -> Edge lawn`
- effective ActivityOccurrence references `Edge lawn`

This is intentional.

## 13. Canonical merge concept

Future data repair may discover:

- `Replace HVAC filter`
- `Change furnace filter`

are duplicates.

The model should allow a merge operation where one canonical activity becomes an alias/redirect to another while preserving original IDs in historical provenance.

This feature is not required in MVP UI, but the schema should not make it impossible.

## 14. Why raw text is first-class

The raw phrase supports:

- debugging AI errors
- future reclassification
- bulk repair tools
- semantic test generation
- comparison of old/new interpreter versions
- understanding context AI lost
- auditing whether a correction was appropriate

The application should assume this data will become extremely valuable over time.
