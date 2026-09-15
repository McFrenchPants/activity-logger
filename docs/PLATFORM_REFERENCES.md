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

## Baseline date

Documentation package generated in September 2026.

API versions and compatibility assumptions must not be treated as permanent.
