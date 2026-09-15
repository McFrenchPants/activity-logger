# Activity Ledger

Activity Ledger is a local-first Android and Wear OS application for frictionless logging of everyday activities.

The product is intentionally **not a task manager**. Its purpose is to record what the user did, is doing, or has just done; normalize different phrasings into consistent activity concepts; and make that historical record easy to inspect later.

Examples:

- "I mowed the lawn."
- "I cut the grass."
- "Finished mowing."
- "Changed the furnace filter yesterday."
- "I'm blowing leaves off the patio."

Equivalent phrases should resolve to the same canonical activity when they refer to the same real-world action.

## Product goal

The normal interaction should be:

1. User intentionally invokes capture on the phone or Wear OS watch.
2. User speaks naturally.
3. Speech is converted to text.
4. The phone interprets the text locally using Gemini Nano.
5. The utterance is matched to an existing canonical activity or a new activity is proposed.
6. A structured activity occurrence is stored locally.
7. The user receives a brief success acknowledgement.
8. No save button or category selection is required for high-confidence captures.

The desired UX principle is:

> **One intentional action before speaking, zero required actions afterward.**

## Core principles

1. **Logging, not planning.**
2. **Capture first; organization is automatic.**
3. **Natural language is the primary input.**
4. **Equivalent language resolves to the same canonical activity.**
5. **The original user utterance is preserved permanently.**
6. **AI interprets data; it does not own the data model.**
7. **The phone is the authoritative processor and database owner.**
8. **The watch is a capture peripheral, not an inference device.**
9. **MVP must work without Internet access.**
10. **Cloud synchronization is intentionally deferred beyond MVP.**
11. **Historical correctness is more important than conversational convenience.**
12. **User corrections must be auditable and must never destroy the original capture.**

## MVP

The MVP includes:

- Android phone application.
- Wear OS capture application.
- Voice capture.
- On-device speech-to-text on the phone.
- On-device semantic interpretation using Gemini Nano through supported Android ML Kit GenAI APIs.
- Canonical activity matching.
- Activity occurrence storage in Room.
- Preservation of raw utterance and interpretation metadata.
- History browsing.
- Activity detail/history.
- Basic correction capability on the phone.
- Natural-language historical questions that are translated into deterministic database queries.
- Watch-to-phone communication and temporary watch-side queueing.
- Offline operation.
- Semantic regression testing.

See:

- `docs/PRODUCT_SPEC.md`
- `docs/REQUIREMENTS.md`
- `docs/ARCHITECTURE.md`
- `docs/AI_INTERPRETATION_SPEC.md`

## Explicit non-goals for MVP

MVP does **not** include:

- Todo lists.
- Due dates.
- Project planning.
- Recurring task scheduling.
- Calendar views.
- Productivity scoring.
- Team collaboration.
- Cloud database ownership.
- Web application.
- Desktop application.
- Multi-user accounts.
- Push reminders.
- Automatic task planning.
- Automatic background activity detection.

## Technology direction

Primary implementation direction:

- Kotlin
- Jetpack Compose
- Compose for Wear OS
- Room
- Wear OS Data Layer API
- ML Kit GenAI Prompt API backed by Gemini Nano / AICore
- Android-supported on-device speech recognition
- Coroutines / Flow
- Repository-based data access

The exact API versions must be verified at implementation time and pinned by the implementation agent.

## Documentation map

| File | Purpose |
|---|---|
| `docs/PRODUCT_SPEC.md` | Product goals, scope, principles, user value |
| `docs/REQUIREMENTS.md` | Functional and non-functional requirements |
| `docs/UX_SPEC.md` | Phone/watch interaction behavior |
| `docs/UX_VISUAL_SPEC.md` | Visual design system, navigation and UI decisions, mockups |
| `docs/DOMAIN_MODEL.md` | Product/domain concepts |
| `docs/AI_INTERPRETATION_SPEC.md` | Gemini Nano role, contracts, prompt behavior |
| `docs/ARCHITECTURE.md` | System structure and boundaries |
| `docs/DATA_MODEL.md` | Persistence schema and audit strategy |
| `docs/WATCH_SPEC.md` | Wear OS capture and transport behavior |
| `docs/TEST_STRATEGY.md` | Test architecture and semantic regression plan |
| `docs/ROADMAP.md` | MVP boundaries and post-MVP evolution |
| `docs/DECISIONS.md` | Architectural/product decisions |
| `docs/PROJECT_STATUS.md` | Implementation status template |
| `AGENTS.md` | Instructions for coding agents |

## Implementation status

Documentation baseline is defined. No implementation is assumed to exist yet.

Before writing production code, implementation agents must read `AGENTS.md`, `docs/PRODUCT_SPEC.md`, `docs/REQUIREMENTS.md`, `docs/ARCHITECTURE.md`, and `docs/DECISIONS.md`.
