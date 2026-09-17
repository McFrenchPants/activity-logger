package com.mcfrenchpants.activityledger.core.domain.naming

import java.util.Locale

/**
 * Produces the normalized form of an activity name, used for matching and uniqueness.
 *
 * Steps: collapse every whitespace run to a single space and trim; lowercase with
 * [Locale.ROOT]; then repeatedly strip leading/trailing Unicode punctuation, re-trimming any
 * exposed whitespace, until stable. Internal punctuation ("re-caulk") is preserved.
 * Idempotent: `normalize(normalize(x)) == normalize(x)`.
 */
object NameNormalizer {
    fun normalize(text: String): String {
        var s = collapseWhitespace(text).lowercase(Locale.ROOT)
        while (true) {
            var start = 0
            var end = s.length
            while (start < end && (isPunctuation(s[start]) || isSpace(s[start]))) start++
            while (end > start && (isPunctuation(s[end - 1]) || isSpace(s[end - 1]))) end--
            val next = s.substring(start, end)
            if (next == s) return s
            s = next
        }
    }

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (isSpace(c)) {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
