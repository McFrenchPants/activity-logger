package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import java.time.Duration
import java.time.Instant

/**
 * One logged occurrence on a tagged subject + action pair, as the lookup sees it.
 *
 * @property occurrenceId Id of the occurrence; the final deterministic tie-break.
 * @property subjectId Id of the subject tag.
 * @property subjectName CURRENT display name of the subject tag.
 * @property actionId Id of the action tag.
 * @property actionName CURRENT display name of the action tag.
 * @property occurredAt When the thing happened.
 * @property durationSeconds How long it took, if known.
 */
data class LookupEntry(
    val occurrenceId: String,
    val subjectId: String,
    val subjectName: String,
    val actionId: String,
    val actionName: String,
    val occurredAt: Instant,
    val durationSeconds: Long?,
)

/**
 * One side of a [LookupTarget], already resolved to a tag.
 *
 * @property id The tag id.
 * @property exact True for an exact tag match, false for a near (closest-candidate) match.
 */
data class LookupTag(val id: String, val exact: Boolean)

/**
 * What the user's question was about, resolved to tags. At least one side is present.
 *
 * @property subject The subject tag asked about, or null.
 * @property action The action tag asked about, or null.
 */
data class LookupTarget(val subject: LookupTag?, val action: LookupTag?) {
    init {
        require(subject != null || action != null) { "A lookup target needs a subject or an action" }
    }

    companion object {
        /**
         * Builds a target from the resolution of the question's subject words and action words.
         * Exact -> exact tag; Near -> the FIRST (closest, see TagResolver) candidate as a near tag;
         * Empty or New -> that side is absent. Null when both sides are absent.
         */
        fun fromResolutions(subject: TagResolution, action: TagResolution): LookupTarget? {
            val s = tagOf(subject)
            val a = tagOf(action)
            if (s == null && a == null) return null
            return LookupTarget(s, a)
        }

        private fun tagOf(resolution: TagResolution): LookupTag? = when (resolution) {
            is TagResolution.Exact -> LookupTag(resolution.tag.id, exact = true)
            is TagResolution.Near -> LookupTag(resolution.candidates.first().id, exact = false)
            TagResolution.Empty, is TagResolution.New -> null
        }
    }
}

/** How much of the target an entry matched, best first. */
enum class LookupTier {
    /** Subject and action both match (only possible when the target has both sides). */
    BOTH,

    /** Only the subject matches. */
    SUBJECT_ONLY,

    /** Only the action matches. */
    ACTION_ONLY,
}

/**
 * One ranked entry.
 *
 * @property entry The occurrence.
 * @property tier How much of the target it matched.
 * @property exact True when every matching side of the target is an exact tag.
 */
data class LookupMatch(val entry: LookupEntry, val tier: LookupTier, val exact: Boolean)

/** Ranked matches, best first (see [HistoryLookup.rank]). */
data class LookupResult(val matches: List<LookupMatch>) {

    /** True when nothing matched. */
    val isEmpty: Boolean get() = matches.isEmpty()

    /** The best match, or null when there is none (the watch's top-result helper). */
    val top: LookupMatch? get() = matches.firstOrNull()

    /**
     * The next match after [top] in the same tier and with the same exactness, or null. Since
     * matches are ordered, it is never newer than [top].
     */
    val previous: LookupMatch?
        get() {
            val first = top ?: return null
            return matches.drop(1).firstOrNull { it.tier == first.tier && it.exact == first.exact }
        }

    /** Time from [previous] to [top] (never negative), or null when there is no previous. */
    val intervalFromPrevious: Duration?
        get() {
            val first = top ?: return null
            val prev = previous ?: return null
            val d = Duration.between(prev.entry.occurredAt, first.entry.occurredAt)
            return if (d.isNegative) Duration.ZERO else d
        }
}

/**
 * Ranks logged occurrences against a question's target. Pure and deterministic: the same input
 * always gives the same order.
 *
 * Tiers, best first: [LookupTier.BOTH], [LookupTier.SUBJECT_ONLY], [LookupTier.ACTION_ONLY]. A
 * side matches when the entry's tag id equals the target's tag id for that side; a side absent
 * from the target never matches. Within a tier: entries matched through exact target tags first,
 * then newest [LookupEntry.occurredAt], then [LookupEntry.occurrenceId] descending. Entries that
 * match nothing are dropped.
 */
object HistoryLookup {

    /** Ranks [entries] against [target]. */
    fun rank(entries: List<LookupEntry>, target: LookupTarget): LookupResult {
        val matches = entries.mapNotNull { entry ->
            val subjectHit = target.subject?.takeIf { it.id == entry.subjectId }
            val actionHit = target.action?.takeIf { it.id == entry.actionId }
            val tier = when {
                subjectHit != null && actionHit != null -> LookupTier.BOTH
                subjectHit != null -> LookupTier.SUBJECT_ONLY
                actionHit != null -> LookupTier.ACTION_ONLY
                else -> return@mapNotNull null
            }
            LookupMatch(entry, tier, exact = (subjectHit?.exact ?: true) && (actionHit?.exact ?: true))
        }.sortedWith(
            compareBy<LookupMatch> { it.tier.ordinal }
                .thenBy { !it.exact }
                .thenByDescending { it.entry.occurredAt }
                .thenByDescending { it.entry.occurrenceId },
        )
        return LookupResult(matches)
    }
}

/**
 * Decides whether a captured sentence is a question (to look up) rather than a statement (to
 * log). Deterministic. Speech-to-text rarely adds a question mark, so the first word matters.
 *
 * Known false positive: a statement that starts with a listed word, such as "Did the laundry",
 * is treated as a question. Deliberately not special-cased.
 */
object QuestionDetector {

    /**
     * First words that make a sentence a question. PROVISIONAL and deliberately small: extend
     * only with evidence from real recordings.
     */
    val QUESTION_WORDS: Set<String> = setOf(
        "when", "how", "what", "which", "where", "who",
        "did", "do", "does", "have", "has", "show", "tell", "list",
    )

    /** True when [text], trimmed, ends with '?' or starts with one of [QUESTION_WORDS]. */
    fun isQuestion(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith('?')) return true
        val first = trimmed.split(Regex("\\s+")).first().filter { it.isLetter() }.lowercase()
        return first in QUESTION_WORDS
    }
}
