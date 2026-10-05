package com.mcfrenchpants.activityledger.core.domain.lookup

/**
 * What a question asks for, as read by a [QuestionExtractor] (ADR-052). Untrusted model output:
 * it only steers how program logic phrases and computes an answer, never the answer itself.
 */
enum class QuestionKind {
    /** When was something last done, or was it ever done ("when did I last", "did I ever"). */
    LAST_TIME,

    /** How many times, or how much ("how many times", "how much"). */
    COUNT,

    /** How often or how regularly something is done. */
    HOW_OFTEN,

    /** What was done ("what did I do", "show me"). */
    LIST,

    /** Not said, or unclear. */
    UNKNOWN,
}
