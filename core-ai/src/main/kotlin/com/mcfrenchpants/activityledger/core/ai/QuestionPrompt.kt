package com.mcfrenchpants.activityledger.core.ai

/**
 * Identifier for the exact QUESTION-extraction prompt text below, recorded as provenance with
 * every question extraction (ADR-052).
 *
 * Separate from [EXTRACTION_PROMPT_VERSION] on purpose: the question prompt is a different,
 * much smaller prompt for a different task, and the activity prompt's corpus-gated baseline must
 * not move because of it.
 *
 * Prompt text is product logic: ANY material change to [QUESTION_SYSTEM_INSTRUCTION] or to
 * [buildQuestionPrompt]'s output must bump this value and update the drift-guard fixture in
 * `QuestionPromptDriftTest`.
 */
internal const val QUESTION_PROMPT_VERSION: String = "q1"

/**
 * The fixed system instruction the on-device model is given for every question.
 *
 * Terse and numbered, like [EXTRACTION_SYSTEM_INSTRUCTION]. Field-level wording lives in
 * [QuestionResponse]'s `@Guide` descriptions; the per-question prompt carries the worked
 * examples.
 */
internal const val QUESTION_SYSTEM_INSTRUCTION: String =
    """You pull the words out of a short question the user asks about their own past activities.

1. Extract only. You are shown no list of activities or tags: never match, invent or rename one.
2. Subject: the thing the question is about, a short noun phrase in the user's own words, without articles or possessives. Keep its full name.
3. Action: what was done, a short verb phrase with the verb in its plain form, keeping the words that tell it apart from similar actions.
4. Leave the subject or the action empty when the question does not name it; never guess one.
5. Never answer the question, never compute a date, and write nothing but the two fields."""

/**
 * Builds the per-question prompt.
 *
 * Pure: it reads nothing but [questionText]. No clock, no locale, no randomness, no I/O and no
 * logging (AGENTS.md #11 -- question text is never logged). There is deliberately no activity or
 * tag section: the model extracts the user's own words and deterministic domain logic resolves
 * them (ADR-052).
 *
 * The worked examples do not occur in the core-testing corpora (`QuestionPromptTest` asserts it).
 */
internal fun buildQuestionPrompt(questionText: String): String = buildString {
    append("## Task\n")
    append(
        "Extract the words of the one user question at the end of this prompt: what it is " +
            "about and what was done. Do not answer it. You are not shown any list of " +
            "activities or tags.\n",
    )
    append('\n')

    append("## Rules\n")
    append(
        "- Subject is the thing the question is about: a short noun phrase in the user's own " +
            "words, with no article and no possessive. Keep the full name.\n",
    )
    append(
        "- Action is what was done: a short verb phrase with the verb in its plain form. Keep " +
            "the words that tell it apart from similar actions.\n",
    )
    append("- Leave a field empty when the question does not name it. Never guess.\n")
    append('\n')

    append("### Worked examples\n")
    append("- \"When did I last clean the gutters?\" -> subject \"gutters\", action \"clean\".\n")
    append("- \"Did I ever service the generator?\" -> subject \"generator\", action \"service\".\n")
    append(
        "- \"When did I last change the oil in the tractor?\" -> subject \"tractor\", action " +
            "\"change oil\".\n",
    )
    append("- \"What did I do on Monday?\" -> no subject, no action.\n")
    append('\n')

    append("## User question\n")
    append("<<<QUESTION\n")
    append(questionText)
    append("\nQUESTION>>>\n")
}
