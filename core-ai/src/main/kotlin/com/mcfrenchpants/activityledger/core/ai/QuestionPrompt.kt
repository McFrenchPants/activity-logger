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
internal const val QUESTION_PROMPT_VERSION: String = "q2"

/**
 * The fixed system instruction the on-device model is given for every question.
 *
 * Terse and numbered, like [EXTRACTION_SYSTEM_INSTRUCTION]. Version q2 adds the date words and
 * the question kind; the model still only copies words and never computes a date or an answer.
 * Field-level wording lives in [QuestionResponse]'s `@Guide` descriptions; the per-question
 * prompt carries the worked examples.
 */
internal const val QUESTION_SYSTEM_INSTRUCTION: String =
    """You pull the words out of a short question the user asks about their own past activities.

1. Extract only. You are shown no list of activities or tags: never match, invent or rename one.
2. Subject: the thing the question is about, a short noun phrase in the user's own words, without articles or possessives. Keep its full name.
3. Action: what was done, a short verb phrase with the verb in its plain form, keeping the words that tell it apart from similar actions.
4. Leave the subject or the action empty when the question does not name it; never guess one.
5. Date words: copy the words that say when exactly as written, such as "last month"; never turn them into a date. Leave them empty when the question does not say when.
6. Kind: LAST_TIME, COUNT, HOW_OFTEN or LIST for what the question asks; leave it empty when unclear.
7. Never answer the question, never compute a date, and write nothing but the four fields."""

/**
 * Builds the per-question prompt.
 *
 * Pure: it reads nothing but [questionText] (in particular, never today's date: the date words
 * are copied, and resolving them is domain logic). No clock, no locale, no randomness, no I/O
 * and no logging (AGENTS.md #11 -- question text is never logged). There is deliberately no
 * activity or tag section: the model extracts the user's own words and deterministic domain
 * logic resolves them (ADR-052).
 *
 * The worked examples do not occur in the core-testing corpora (`QuestionPromptTest` asserts it).
 */
internal fun buildQuestionPrompt(questionText: String): String = buildString {
    append("## Task\n")
    append(
        "Extract four things from the one user question at the end of this prompt: what it is " +
            "about, what was done, the words that say when, and what kind of question it is. " +
            "Do not answer it. You are not shown any list of activities or tags.\n",
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
    append(
        "- Date words are the words that say when, copied exactly as written. Never turn them " +
            "into a date.\n",
    )
    append(
        "- Kind is LAST_TIME (when was it last done, or was it ever done), COUNT (how many " +
            "times), HOW_OFTEN (how often or how regularly) or LIST (what was done).\n",
    )
    append("- Leave a field empty when the question does not name it. Never guess.\n")
    append('\n')

    append("### Worked examples\n")
    append(
        "- \"When did I last clean the gutters?\" -> subject \"gutters\", action \"clean\", " +
            "no date words, kind LAST_TIME.\n",
    )
    append(
        "- \"How many times did I water the tomatoes last month?\" -> subject \"tomatoes\", " +
            "action \"water\", date words \"last month\", kind COUNT.\n",
    )
    append(
        "- \"How often do I descale the kettle?\" -> subject \"kettle\", action \"descale\", " +
            "no date words, kind HOW_OFTEN.\n",
    )
    append(
        "- \"Did I service the generator in May?\" -> subject \"generator\", action " +
            "\"service\", date words \"in May\", kind LAST_TIME.\n",
    )
    append(
        "- \"What did I do on Monday?\" -> no subject, no action, date words \"on Monday\", " +
            "kind LIST.\n",
    )
    append('\n')

    append("## User question\n")
    append("<<<QUESTION\n")
    append(questionText)
    append("\nQUESTION>>>\n")
}
