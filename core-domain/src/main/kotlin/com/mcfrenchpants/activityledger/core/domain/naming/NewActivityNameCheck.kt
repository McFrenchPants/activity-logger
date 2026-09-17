package com.mcfrenchpants.activityledger.core.domain.naming

/**
 * Decides whether a proposed name is acceptable for a NEW canonical activity.
 *
 * A good name is a short, reusable label for a repeatable activity ("Flush water heater"),
 * not a narrative of one occurrence ("I flushed the water heater today"). Rules, checked in
 * this order (the first failing rule is reported):
 * 1. Trimmed length is 1..[MAX_LENGTH] ([Reason.EMPTY] / [Reason.TOO_LONG]).
 * 2. The [NameNormalizer] form is non-empty ([Reason.NO_MEANINGFUL_TEXT]).
 * 3. Not digits only, ignoring spaces ([Reason.DIGITS_ONLY]).
 * 4. Does not start with a first-person narrative opener: "i ", "i've ", "i'm ", "we ",
 *    "we've " ([Reason.FIRST_PERSON_NARRATIVE]).
 * 5. Contains no date/time word as a whole word: today, yesterday, tomorrow, tonight, morning,
 *    afternoon, evening, ago, English weekday and month names ([Reason.CONTAINS_TIME_WORD]).
 * 6. Contains no completion-status word: completed, finished, done
 *    ([Reason.CONTAINS_COMPLETION_WORD]).
 * 7. Contains no filler word: stuff, things, thing ([Reason.CONTAINS_FILLER_WORD]).
 *
 * Conservative trade-off: word rules are applied without context, so some legitimate names
 * are rejected, e.g. "Morning walk" (contains "morning") or "May garden prep" (contains
 * "may"). Rejection only routes the capture to review; it never loses data, whereas a
 * polluted canonical name persists and spreads through matching.
 */
object NewActivityNameCheck {
    const val MAX_LENGTH: Int = 60

    /** Why a name was rejected. */
    enum class Reason {
        EMPTY,
        TOO_LONG,
        NO_MEANINGFUL_TEXT,
        DIGITS_ONLY,
        FIRST_PERSON_NARRATIVE,
        CONTAINS_TIME_WORD,
        CONTAINS_COMPLETION_WORD,
        CONTAINS_FILLER_WORD,
    }

    /** Result of [check]. */
    sealed interface Result {
        data object Ok : Result
        data class Invalid(val reason: Reason) : Result
    }

    private val FIRST_PERSON_OPENERS = listOf("i ", "i've ", "i'm ", "we ", "we've ")

    private val TIME_WORDS = setOf(
        "today", "yesterday", "tomorrow", "tonight", "morning", "afternoon", "evening", "ago",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
    )

    private val COMPLETION_WORDS = setOf("completed", "finished", "done")

    private val FILLER_WORDS = setOf("stuff", "things", "thing")

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    fun check(name: String): Result {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (trimmed.length > MAX_LENGTH) return Result.Invalid(Reason.TOO_LONG)

        // Treat typographic apostrophes like ASCII ones for opener/word matching.
        val normalized = NameNormalizer.normalize(trimmed).replace('’', '\'')
        if (normalized.isEmpty()) return Result.Invalid(Reason.NO_MEANINGFUL_TEXT)
        if (normalized.all { it.isDigit() || it == ' ' }) return Result.Invalid(Reason.DIGITS_ONLY)
        if (FIRST_PERSON_OPENERS.any { normalized.startsWith(it) }) {
            return Result.Invalid(Reason.FIRST_PERSON_NARRATIVE)
        }

        val words = WORD.findAll(normalized).map { it.value }.toList()
        if (words.any { it in TIME_WORDS }) return Result.Invalid(Reason.CONTAINS_TIME_WORD)
        if (words.any { it in COMPLETION_WORDS }) return Result.Invalid(Reason.CONTAINS_COMPLETION_WORD)
        if (words.any { it in FILLER_WORDS }) return Result.Invalid(Reason.CONTAINS_FILLER_WORD)
        return Result.Ok
    }
}
