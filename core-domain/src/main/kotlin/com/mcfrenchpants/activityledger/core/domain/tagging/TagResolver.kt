package com.mcfrenchpants.activityledger.core.domain.tagging

/** Which part of an existing tag the words matched exactly. */
enum class TagMatchVia {
    /** The tag's display name. */
    NAME,

    /** One of the tag's aliases. */
    ALIAS,
}

/** What the user's words for one tag resolved to. Produced by [TagResolver.resolve]. */
sealed interface TagResolution {

    /** There were no usable words (null, blank, punctuation only or determiners only). */
    data object Empty : TagResolution

    /**
     * The words are an existing tag (same [TagNormalizer.key]).
     *
     * @property tag The matched tag.
     * @property via Whether the display name or an alias matched.
     */
    data class Exact(val tag: KnownTag, val via: TagMatchVia) : TagResolution

    /**
     * The words are not an existing tag but are close to one or more. Never saved silently:
     * the user is asked whether they meant one of [candidates] or want [newName].
     *
     * @property newName The display name a new tag would get ([TagNormalizer.cleanName]).
     * @property candidates The close tags, closest first (see [TagResolver]); never empty.
     */
    data class Near(val newName: String, val candidates: List<KnownTag>) : TagResolution {
        init {
            require(candidates.isNotEmpty()) { "Near needs at least one candidate" }
        }
    }

    /**
     * Nothing existing is exact or close: the words name a new tag.
     *
     * @property name The display name of the new tag ([TagNormalizer.cleanName]).
     */
    data class New(val name: String) : TagResolution
}

/**
 * Resolves the user's words for a subject or action against the existing tags of the SAME kind.
 *
 * Order, first that applies:
 * 1. No usable words -> [TagResolution.Empty].
 * 2. The words' [TagNormalizer.key] equals a tag's display-name key -> Exact via NAME.
 * 3. It equals an alias key -> Exact via ALIAS.
 * 4. One or more tags are close (below) -> [TagResolution.Near].
 * 5. Otherwise -> [TagResolution.New].
 * If several tags match in step 2 (or 3), the first in catalog order wins.
 *
 * **Close-match rules** (provisional, to be tuned on recordings -- ADR-039). A tag is close
 * when ANY of its forms (display name or an alias) is close to the words by:
 * - (a) **Spelling**: the optimal-string-alignment (Damerau) edit distance between the keys
 *   is at most [SPELLING_MAX_DISTANCE] when the shorter key has at least
 *   [SPELLING_MIN_KEY_LENGTH] characters, and at most [SPELLING_MAX_DISTANCE_LONG] when it has
 *   at least [SPELLING_LONG_KEY_LENGTH]. Shorter keys are never spelling-close, so "wash" is
 *   not close to "walk" or "wax".
 * - (b) **Shared word, subjects**: the two share a comparison word ([TagNormalizer.tokens]) of
 *   at least [MIN_SHARED_TOKEN_LENGTH] letters ("lawn mower" ~ "lawn"; "dryer lint trap" ~
 *   "dryer vent").
 * - (c) **Shared word, actions**: the two share ANY comparison word other than the first word
 *   of each phrase (the verb) -- no length minimum and no stop-word list. So "change oil" is
 *   NOT close to "change filter" and "replace batteries" is NOT close to "replace filter",
 *   while "replace filter" IS close to "change filter" and "put out" IS close to "take out".
 * There is no synonym list ("grass" is not close to "lawn"). The rules lean towards "close":
 * a needless confirmation card is safe, a missed near-duplicate saved silently is not.
 *
 * **Candidate order** (deterministic): smallest key edit distance first, then most shared
 * words (rules (b)/(c)), then display name (case-insensitive, then exact), then id.
 */
object TagResolver {

    /** Shortest key (in characters) that can be spelling-close at all. */
    const val SPELLING_MIN_KEY_LENGTH: Int = 5

    /** Largest edit distance counted as close for keys of [SPELLING_MIN_KEY_LENGTH]+ characters. */
    const val SPELLING_MAX_DISTANCE: Int = 1

    /** Key length (shorter of the two) from which [SPELLING_MAX_DISTANCE_LONG] applies. */
    const val SPELLING_LONG_KEY_LENGTH: Int = 9

    /** Largest edit distance counted as close for keys of [SPELLING_LONG_KEY_LENGTH]+ characters. */
    const val SPELLING_MAX_DISTANCE_LONG: Int = 2

    /** Shortest shared word (in letters) that makes two SUBJECTS close by rule (b). Not used for actions. */
    const val MIN_SHARED_TOKEN_LENGTH: Int = 3

    /** Resolves [words] against the [kind] tags of [catalog]. Pure and deterministic. */
    fun resolve(words: String?, kind: TagKind, catalog: TagCatalog): TagResolution {
        val key = TagNormalizer.key(words)
        if (key.isEmpty()) return TagResolution.Empty
        val name = TagNormalizer.cleanName(words) ?: return TagResolution.Empty
        val tags = catalog.tagsOf(kind)

        tags.firstOrNull { TagNormalizer.key(it.displayName) == key }
            ?.let { return TagResolution.Exact(it, TagMatchVia.NAME) }
        tags.firstOrNull { tag -> tag.aliases.any { TagNormalizer.key(it) == key } }
            ?.let { return TagResolution.Exact(it, TagMatchVia.ALIAS) }

        val tokens = TagNormalizer.tokens(words)
        val near = tags.mapNotNull { tag -> closeness(key, tokens, tag, kind)?.let { tag to it } }
        if (near.isEmpty()) return TagResolution.New(name)
        val ranked = near.sortedWith(
            compareBy<Pair<KnownTag, Closeness>> { it.second.distance }
                .thenByDescending { it.second.sharedTokens }
                .thenBy { it.first.displayName.lowercase() }
                .thenBy { it.first.displayName }
                .thenBy { it.first.id },
        ).map { it.first }
        return TagResolution.Near(name, ranked)
    }

    /**
     * True when keys [a] and [b] are close by spelling (rule (a)): edit distance within the
     * threshold for the shorter key's length; never for a shorter key under
     * [SPELLING_MIN_KEY_LENGTH].
     */
    fun spellingClose(a: String, b: String): Boolean {
        val shorter = minOf(a.length, b.length)
        val max = when {
            shorter >= SPELLING_LONG_KEY_LENGTH -> SPELLING_MAX_DISTANCE_LONG
            shorter >= SPELLING_MIN_KEY_LENGTH -> SPELLING_MAX_DISTANCE
            else -> return false
        }
        return editDistance(a, b) <= max
    }

    /**
     * Words shared by two token lists under rule (b) (subjects: words of at least
     * [MIN_SHARED_TOKEN_LENGTH] letters) or rule (c) (actions: any word except the first word of
     * each list).
     */
    fun sharedTokens(kind: TagKind, a: List<String>, b: List<String>): Set<String> = when (kind) {
        TagKind.SUBJECT -> a.filter { it.length >= MIN_SHARED_TOKEN_LENGTH }.toSet().intersect(b.toSet())
        TagKind.ACTION -> a.drop(1).toSet().intersect(b.drop(1).toSet())
    }

    /**
     * Optimal-string-alignment (restricted Damerau-Levenshtein) distance: insertions,
     * deletions, substitutions and swaps of two adjacent characters each cost 1.
     */
    fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }

    private class Closeness(val distance: Int, val sharedTokens: Int)

    private fun closeness(key: String, tokens: List<String>, tag: KnownTag, kind: TagKind): Closeness? {
        var best: Closeness? = null
        for (form in listOf(tag.displayName) + tag.aliases) {
            val formKey = TagNormalizer.key(form)
            if (formKey.isEmpty()) continue
            val shared = sharedTokens(kind, tokens, TagNormalizer.tokens(form)).size
            if (!spellingClose(key, formKey) && shared == 0) continue
            val candidate = Closeness(editDistance(key, formKey), shared)
            val current = best
            if (current == null ||
                candidate.distance < current.distance ||
                (candidate.distance == current.distance && candidate.sharedTokens > current.sharedTokens)
            ) {
                best = candidate
            }
        }
        return best
    }
}
