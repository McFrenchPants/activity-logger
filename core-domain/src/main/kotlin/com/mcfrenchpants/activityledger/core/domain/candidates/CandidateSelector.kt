package com.mcfrenchpants.activityledger.core.domain.candidates

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import java.security.MessageDigest

/**
 * The bounded shortlist of existing activities offered to the interpreter for one capture.
 *
 * @property candidates Always ordered by normalized name, then id.
 * @property contextHash Lowercase hex SHA-256 identifying exactly which candidates (ids and
 *   display names, in order) were offered. Never derived from the raw text itself.
 */
data class CandidateSelection(
    val candidates: List<CandidateActivity>,
    val contextHash: String,
)

/**
 * Deterministically picks which catalog activities are shown to the interpreter.
 *
 * If the catalog fits within [bound], every entry is offered. Otherwise entries whose
 * normalized name or any normalized alias occurs in the normalized raw text as a whole-token
 * phrase ("text hits") are chosen first, and remaining slots are filled by most recent
 * occurrence (never-occurred last; ties by normalized name, then id). If the hits alone exceed
 * the bound they are truncated by that same recency order. Exact phrase matching only — no
 * fuzzy, stemmed or semantic matching.
 *
 * The final list is ordered by normalized name, then id, so recency changes alone do not
 * reorder the prompt.
 */
class CandidateSelector(val bound: Int = DEFAULT_BOUND) {
    init {
        require(bound >= 1) { "bound must be >= 1" }
    }

    fun select(catalog: List<CatalogActivity>, rawText: String): CandidateSelection {
        val chosen: List<CatalogActivity> = if (catalog.size <= bound) {
            catalog
        } else {
            val text = NameNormalizer.normalize(rawText)
            val (hits, rest) = catalog.partition { isTextHit(it, text) }
            val hitsByRecency = hits.sortedWith(RECENCY_ORDER)
            if (hitsByRecency.size >= bound) {
                hitsByRecency.take(bound)
            } else {
                hitsByRecency + rest.sortedWith(RECENCY_ORDER).take(bound - hitsByRecency.size)
            }
        }
        val ordered = chosen.sortedWith(NAME_ORDER)
        val candidates = ordered.map { CandidateActivity(it.id, it.displayName, it.normalizedAliases) }
        return CandidateSelection(candidates, contextHash(candidates))
    }

    private fun isTextHit(entry: CatalogActivity, text: String): Boolean =
        containsPhrase(text, entry.normalizedName) ||
            entry.normalizedAliases.any { containsPhrase(text, it) }

    companion object {
        const val DEFAULT_BOUND: Int = 40

        private const val UNIT_SEPARATOR = ''
        private const val RECORD_SEPARATOR = ''

        private val NAME_ORDER: Comparator<CatalogActivity> =
            compareBy<CatalogActivity> { it.normalizedName }.thenBy { it.id }

        private val RECENCY_ORDER: Comparator<CatalogActivity> =
            Comparator<CatalogActivity> { a, b ->
                val x = a.lastOccurredAt
                val y = b.lastOccurredAt
                when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> y.compareTo(x)
                }
            }.then(NAME_ORDER)

        /** True if [phrase] occurs in [text] bounded by string edges or non-letter/non-digit chars. */
        internal fun containsPhrase(text: String, phrase: String): Boolean {
            if (phrase.isEmpty()) return false
            var from = 0
            while (true) {
                val index = text.indexOf(phrase, from)
                if (index < 0) return false
                val end = index + phrase.length
                val startOk = index == 0 || !Character.isLetterOrDigit(text[index - 1])
                val endOk = end == text.length || !Character.isLetterOrDigit(text[end])
                if (startOk && endOk) return true
                from = index + 1
            }
        }

        /** Lowercase hex SHA-256 of `id US displayName RS` for each candidate, in order. */
        internal fun contextHash(candidates: List<CandidateActivity>): String {
            val sb = StringBuilder()
            for (c in candidates) {
                sb.append(c.id).append(UNIT_SEPARATOR).append(c.displayName).append(RECORD_SEPARATOR)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
