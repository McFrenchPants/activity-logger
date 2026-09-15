# Implementation Handoff

## 1. Purpose

This document is the recommended starting brief for Codex, Claude Code, or another implementation agent.

## 2. First instruction to the agent

Read all repository documentation before implementing.

Treat the documents as product constraints, not brainstorming notes.

Do not redesign the product into a generic task manager.

## 3. Initial implementation objective

Build the MVP using the intended production architecture:

- native Android phone app
- native Wear OS app
- Kotlin
- Compose
- Room
- Wear Data Layer
- on-device speech
- Gemini Nano semantic interpretation
- no cloud backend

Do not create a cloud-first prototype.

## 4. First engineering phase

Before substantial UI work:

1. Verify current official Android API support.
2. Confirm target phone supports required Gemini Nano Prompt API behavior.
3. Confirm Structured Output support and runtime detection.
4. Confirm on-device speech recognition approach.
5. Document chosen SDK/dependency versions.
6. Add any resulting constraints to `DECISIONS.md`.
7. Update `PROJECT_STATUS.md`.

## 5. Recommended implementation sequence

### Step 1 — Scaffold

Create:

- phone app
- wear app
- shared domain/protocol code as appropriate
- test infrastructure

### Step 2 — Persistence

Implement Room schema for:

- raw captures
- canonical activities
- aliases
- interpretations
- occurrences
- corrections

Add migration tests from schema v1 baseline infrastructure.

### Step 3 — Domain services

Implement interfaces for:

- activity repository
- temporal resolver
- candidate selector
- interpretation validator
- correction service

### Step 4 — AI vertical slice

Use hardcoded text input first, but production Gemini Nano path.

Example:

> "I cut the grass yesterday."

Verify:

- structured output
- Mow lawn match
- temporal extraction
- Room persistence
- raw text retention

### Step 5 — Semantic regression

Automate seed corpus.

Do not move on until core synonym and near-neighbor cases are measurable.

### Step 6 — Phone voice capture

Add on-device STT behind `SpeechTranscriber`.

Connect to same capture pipeline.

### Step 7 — Phone UX

Build:

- capture
- history
- activity detail
- correction
- ask-history

### Step 8 — Wear capture

Implement:

- watch capture
- durable outbox
- Data Layer transport
- acknowledgement
- idempotency

### Step 9 — Query interpreter

Implement constrained query intents and deterministic Room execution.

### Step 10 — Hardening

- process death
- offline behavior
- capability unavailable states
- database migrations
- privacy/logging review
- performance measurement

## 6. Do not shortcut these requirements

Do not:

- store only normalized activity text
- throw away raw utterances
- let the model generate SQL
- put semantic inference on the watch
- add cloud AI as fallback
- add Supabase/Firebase because synchronization may exist later
- combine speech recognition and semantic interpretation into one opaque component
- accept arbitrary model-generated activity IDs
- use destructive Room migrations
- add Todoist-style features

## 7. Definition of first end-to-end success

A user can say on the phone:

> "I cut the grass this morning."

And the app:

- captures raw recognized text
- stores RawCapture
- resolves to existing `Mow lawn`
- stores structured interpretation provenance
- resolves occurrence time appropriately
- stores ActivityOccurrence
- shows it in history
- answers "When did I last mow?" correctly
- allows correction without changing raw text

Then the same logical operation can originate on the watch and survive temporary phone disconnection.
