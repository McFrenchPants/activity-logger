# Platform References

These references are implementation aids, not frozen dependency specifications.

The implementation agent must re-check official documentation at coding time.

## Gemini Nano / AICore

Android Developers — Gemini Nano  
https://developer.android.com/ai/gemini-nano

Key relevance:

- Gemini Nano executes on-device through Android AICore.
- Appropriate for local inference where privacy/offline operation matters.

## ML Kit Prompt API

Google for Developers — Prompt API  
https://developers.google.com/ml-kit/genai/prompt/android/get-started

Key relevance:

- Android Prompt API for Gemini Nano.
- Requires runtime/device support.
- AICore model/service preparation can affect availability.

## Structured Output

Android Developers — Generate structured output  
https://developer.android.com/agents/skills/device-ai/ml-kit-genai-prompt-api/references/structured-output

Key relevance:

- Typed structured output for classification/entity extraction.
- Feature availability should be checked at runtime.

## Prompt design

Android Developers — Prompt design for Gemini Nano  
https://developer.android.com/agents/skills/device-ai/ml-kit-genai-prompt-api/references/prompt-design

Key relevance:

- concise prompts
- examples
- delimiters
- focused tasks
- short output
- low temperature for deterministic tasks

## ML Kit GenAI Speech Recognition

Google for Developers — Speech Recognition API  
https://developers.google.com/ml-kit/genai/speech-recognition/android

Key relevance:

- candidate on-device speech path
- supported feature/device matrix must be verified

## Wear OS Data Layer

Android Developers — Data Layer overview  
https://developer.android.com/training/wearables/data/overview

Android Developers — Sync data items  
https://developer.android.com/training/wearables/data/data-items

Key relevance:

- watch/phone app communication
- disconnected data can synchronize after reconnection
- Data Layer is transport, not primary authoritative storage

## Room

Android Developers — Room  
https://developer.android.com/jetpack/androidx/releases/room

Key relevance:

- SQLite abstraction
- DAO model
- migrations
- relational local persistence

## Local stand-in model (semantic corpus only)

Ollama  
https://ollama.com

Ollama API reference  
https://github.com/ollama/ollama/blob/main/docs/api.md

Ollama library — Gemma 3n  
https://ollama.com/library/gemma3n

Pinned for the stand-in recordings (verified 2026-09-18):

- Ollama 0.34.2
- model `gemma3n:e4b`, Q4_K_M quantization, 6.9B parameters
- digest `15cb39fd9394fd2549f6df9081cfc84dd134ecf2c9c5be911e5629920489ac32`
- run on an RTX 4070 Laptop GPU (8 GB)

Key relevance:

- test infrastructure only: records semantic corpus answers on a developer machine without the phone (`docs/SEMANTIC_CORPUS.md`, ADR-033)
- structured output by passing a JSON schema as the `/api/chat` request's `format`
- greedy settings (temperature 0, top-k 1, fixed seed) matching the on-device interpreter
- served on loopback only; never shipped in the app, never the official measurement

## Baseline date

Documentation package generated in September 2026.

API versions and compatibility assumptions must not be treated as permanent.
