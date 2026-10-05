package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer

/**
 * Reads a question's [QuestionKind] from its own words with fixed phrase rules (ADR-052). Program
 * logic: its result outranks the model's kind. Pure and stateless; it never stores or logs the text.
 *
 * Rules, checked in this order on the normalized text, matching whole words only:
 * 1. "how many", "how much", "number of times" -> [QuestionKind.COUNT]
 * 2. "how often", "how regularly", "how frequently" -> [QuestionKind.HOW_OFTEN]
 * 3. "when did i last", "when was the last", "last time i", "did i ever", "have i ever",
 *    "when did i" -> [QuestionKind.LAST_TIME]
 * 4. starts with "what did i", "what have i", "what else did i", "show me", "list" -> [QuestionKind.LIST]
 * 5. otherwise null.
 */
object QuestionKindDetector {

    private val COUNT = listOf("how many", "how much", "number of times")
    private val HOW_OFTEN = listOf("how often", "how regularly", "how frequently")
    private val LAST_TIME = listOf(
        "when did i last", "when was the last", "last time i", "did i ever", "have i ever", "when did i",
    )
    private val LIST = listOf("what did i", "what have i", "what else did i", "show me", "list")

    /** The kind [questionText] asks for by these rules, or null when no rule matches. */
    fun detect(questionText: String): QuestionKind? {
        val words = wordsOf(questionText)
        if (words.isEmpty()) return null
        val padded = " $words "
        fun contains(phrases: List<String>) = phrases.any { padded.contains(" $it ") }
        return when {
            contains(COUNT) -> QuestionKind.COUNT
            contains(HOW_OFTEN) -> QuestionKind.HOW_OFTEN
            contains(LAST_TIME) -> QuestionKind.LAST_TIME
            LIST.any { padded.startsWith(" $it ") } -> QuestionKind.LIST
            else -> null
        }
    }

    /**
     * The text as space-separated lowercase words: normalized, curly apostrophes made plain, and
     * every character other than a letter, digit or apostrophe treated as a word break.
     */
    private fun wordsOf(text: String): String {
        val normalized = NameNormalizer.normalize(text.replace('’', '\''))
        val sb = StringBuilder(normalized.length)
        for (c in normalized) sb.append(if (c.isLetterOrDigit() || c == '\'') c else ' ')
        return sb.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
