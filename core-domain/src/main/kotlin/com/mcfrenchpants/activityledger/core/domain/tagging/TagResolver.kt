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
     * The words are an existing tag (same key, or an unambiguous verb match; see [TagResolver]).
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
 * **Comparison form.** Both kinds compare by [TagNormalizer.key]/[TagNormalizer.tokens]. Actions
 * additionally match on their VERB (first word): an inflected verb is compared through its
 * possible base forms ([TagNormalizer.verbForms] / [TagNormalizer.verbsMatch], TG1.4b rule 1),
 * so "cleaning" is the existing "clean" and "edging" is the existing "edge"; two different
 * uninflected verbs ("tap"/"tape") never match. Subjects are never verb-reduced.
 *
 * Order, first that applies:
 * 1. No usable words -> [TagResolution.Empty].
 * 2. The words' key equals a tag's display-name key -> Exact via NAME.
 * 3. It equals an alias key -> Exact via ALIAS.
 * 4. Actions only -- **verb match**: the tags with a form (name or alias) whose object words
 *    ([TagNormalizer.actionObjects]) are identical and whose verb matches
 *    ([TagNormalizer.verbsMatch]). Exactly ONE such tag -> Exact (via NAME if its name matched,
 *    else ALIAS). Two or more ("taped" with both "tap" and "tape") -> [TagResolution.Near] over
 *    exactly those tags (by name, then id): ambiguous, so the user is asked.
 * 5. One or more tags are close (below) -> [TagResolution.Near].
 * 6. Otherwise -> [TagResolution.New].
 * If several tags match in step 2 (or 3), the first in catalog order wins.
 *
 * **Close-match rules** (provisional, tuned on recordings -- ADR-039 and its TG1.4b
 * amendment). A tag is close when ANY of its forms (display name or an alias) is close to the
 * words by:
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
 * - (d) **Head verb, actions** (TG1.4b rule 6): the words add object words after a verb that
 *   matches the verb of a SINGLE-WORD form ("blow off driveway" ~ "blow").
 * - (e) **Synonym, actions** (TG1.4b rule 5): the verbs are synonyms ([synonymVerbs]) and
 *   either the object words are identical (both empty included: "cut" ~ "mow", "swap filter" ~
 *   "replace filter"; "change oil" is NOT ~ "replace filter") or the form is a single word and
 *   the words add object words -- rule (d) through a synonym ("clear leaves" ~ "clean").
 * - (f) **Synonym, subjects** (TG1.4b rule 5): the two keys are different members of one of
 *   [SUBJECT_SYNONYM_GROUPS] ("grass" ~ "lawn", "HVAC" ~ "furnace").
 * The synonym groups are deliberately tiny named constants, not a dictionary. A synonym match
 * is ONLY ever a Near candidate -- never Exact, never a silent save. The rules lean towards
 * "close": a needless confirmation card is safe, a missed near-duplicate saved silently is not.
 *
 * **Candidate order** (deterministic): real close matches (rules (a)-(d)) before synonym-only
 * matches (rules (e)/(f)); then smallest key edit distance, then most shared words (rules
 * (b)/(c)), then display name (case-insensitive, then exact), then id.
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

    /**
     * Verbs that may mean the same thing (TG1.4b rule 5), in base form; an inflected verb joins
     * a group through [TagNormalizer.verbsMatch]. Deliberately tiny: each group was seen in a
     * real recording. A match only ever asks "did you mean ...?" (Near), never saves silently.
     */
    val ACTION_SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("mow", "cut"),
        setOf("replace", "change", "swap"),
        setOf("clean", "clear"),
    )

    /**
     * Subject words that may name the same thing (TG1.4b rule 5), compared by
     * [TagNormalizer.key]. Deliberately tiny; a match only ever asks "did you mean ...?".
     */
    val SUBJECT_SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("lawn", "grass", "yard"),
        setOf("furnace", "hvac"),
    )

    private val SUBJECT_SYNONYM_KEYS: List<Set<String>> =
        SUBJECT_SYNONYM_GROUPS.map { g -> g.mapTo(HashSet()) { TagNormalizer.key(it) } }

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

        if (kind == TagKind.ACTION) {
            val verbMatched = verbMatches(words, tags)
            if (verbMatched.size == 1) return TagResolution.Exact(verbMatched[0].first, verbMatched[0].second)
            if (verbMatched.size > 1) {
                val ordered = verbMatched.map { it.first }
                    .sortedWith(compareBy<KnownTag> { it.displayName.lowercase() }.thenBy { it.displayName }.thenBy { it.id })
                return TagResolution.Near(name, ordered)
            }
        }

        val parsed = Parsed.of(words)
        val near = tags.mapNotNull { tag -> closeness(key, parsed, tag, kind)?.let { tag to it } }
        if (near.isEmpty()) return TagResolution.New(name)
        val ranked = near.sortedWith(
            compareBy<Pair<KnownTag, Closeness>> { it.second.synonymOnly }
                .thenBy { it.second.distance }
                .thenByDescending { it.second.sharedTokens }
                .thenBy { it.first.displayName.lowercase() }
                .thenBy { it.first.displayName }
                .thenBy { it.first.id },
        ).map { it.first }
        return TagResolution.Near(name, ranked)
    }

    /**
     * The existing ACTION tags that [words] MEAN, as opposed to the looser Near rules: tags with
     * the same [TagNormalizer.key] or a verb match with identical object words (resolution
     * steps 2-4, ALL of them even when ambiguous), or -- only when there is none -- tags whose
     * verb is a synonym ([synonymVerbs]) with identical object words ("swap filter" means
     * "replace filter"). Catalog order. Used by [TagDecisionPolicy]'s subject-is-only-the-object
     * rule (TG1.4b rule 4).
     */
    fun equivalentActions(words: String?, catalog: TagCatalog): List<KnownTag> {
        val key = TagNormalizer.key(words)
        if (key.isEmpty()) return emptyList()
        val byKey = catalog.actions.filter { tag -> forms(tag).any { TagNormalizer.key(it) == key } }
        if (byKey.isNotEmpty()) return byKey
        val byVerb = verbMatches(words, catalog.actions).map { it.first }
        if (byVerb.isNotEmpty()) return byVerb
        val p = Parsed.of(words)
        val verb = p.verb ?: return emptyList()
        return catalog.actions.filter { tag ->
            forms(tag).any { form ->
                val f = Parsed.of(form)
                f.verb != null && synonymVerbs(verb, f.verb) && p.objects == f.objects
            }
        }
    }

    /**
     * True when the lowercase verbs [a] and [b] are synonyms: they do not match each other
     * ([TagNormalizer.verbsMatch]) but each matches a member of the same one of
     * [ACTION_SYNONYM_GROUPS] ("cut"/"mowed", "swapped"/"change").
     */
    fun synonymVerbs(a: String, b: String): Boolean =
        !TagNormalizer.verbsMatch(a, b) &&
            ACTION_SYNONYM_GROUPS.any { g -> g.any { TagNormalizer.verbsMatch(a, it) } && g.any { TagNormalizer.verbsMatch(b, it) } }

    /** True when subject keys [a] and [b] are different members of one of [SUBJECT_SYNONYM_GROUPS]. */
    fun synonymSubjects(a: String, b: String): Boolean =
        a != b && SUBJECT_SYNONYM_KEYS.any { a in it && b in it }

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

    /** An action's words split for verb matching: raw verb, singularized object words, all tokens. */
    private class Parsed(val verb: String?, val objects: List<String>, val tokens: List<String>) {
        companion object {
            fun of(words: String?) = Parsed(
                TagNormalizer.actionVerb(words),
                TagNormalizer.actionObjects(words),
                TagNormalizer.tokens(words),
            )
        }
    }

    /** Step 4: every tag with a form whose objects are identical and whose verb matches; NAME before ALIAS. */
    private fun verbMatches(words: String?, tags: List<KnownTag>): List<Pair<KnownTag, TagMatchVia>> {
        val p = Parsed.of(words)
        val verb = p.verb ?: return emptyList()
        fun matches(form: String): Boolean {
            val f = Parsed.of(form)
            return f.verb != null && f.objects == p.objects && TagNormalizer.verbsMatch(verb, f.verb)
        }
        return tags.mapNotNull { tag ->
            when {
                matches(tag.displayName) -> tag to TagMatchVia.NAME
                tag.aliases.any(::matches) -> tag to TagMatchVia.ALIAS
                else -> null
            }
        }
    }

    private fun forms(tag: KnownTag): List<String> = listOf(tag.displayName) + tag.aliases

    private class Closeness(val synonymOnly: Boolean, val distance: Int, val sharedTokens: Int)

    private val CLOSENESS_ORDER: Comparator<Closeness> =
        compareBy<Closeness> { it.synonymOnly }.thenBy { it.distance }.thenByDescending { it.sharedTokens }

    private fun closeness(key: String, words: Parsed, tag: KnownTag, kind: TagKind): Closeness? {
        var best: Closeness? = null
        for (form in forms(tag)) {
            val formKey = TagNormalizer.key(form)
            if (formKey.isEmpty()) continue
            val f = Parsed.of(form)
            val shared = sharedTokens(kind, words.tokens, f.tokens).size
            val real = spellingClose(key, formKey) || shared > 0 || headVerbClose(kind, words, f)
            val synonym = !real && synonymClose(kind, key, words, formKey, f)
            if (!real && !synonym) continue
            val candidate = Closeness(synonymOnly = !real, distance = editDistance(key, formKey), sharedTokens = shared)
            val current = best
            if (current == null || CLOSENESS_ORDER.compare(candidate, current) < 0) best = candidate
        }
        return best
    }

    /** Rule (d): object words added after a verb matching a single-word action form. */
    private fun headVerbClose(kind: TagKind, words: Parsed, form: Parsed): Boolean =
        kind == TagKind.ACTION && words.verb != null && form.verb != null &&
            words.objects.isNotEmpty() && form.objects.isEmpty() && TagNormalizer.verbsMatch(words.verb, form.verb)

    /** Rules (e) and (f). */
    private fun synonymClose(kind: TagKind, key: String, words: Parsed, formKey: String, form: Parsed): Boolean =
        when (kind) {
            TagKind.SUBJECT -> synonymSubjects(key, formKey)
            TagKind.ACTION -> words.verb != null && form.verb != null && synonymVerbs(words.verb, form.verb) &&
                (words.objects == form.objects || (form.objects.isEmpty() && words.objects.isNotEmpty()))
        }
}
