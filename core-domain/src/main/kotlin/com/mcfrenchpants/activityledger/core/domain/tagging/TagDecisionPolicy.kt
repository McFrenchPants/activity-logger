package com.mcfrenchpants.activityledger.core.domain.tagging

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck

/** What happens to a capture after its tags are decided. */
enum class TagDecisionOutcome {
    /** Logged silently, with no question. */
    AUTO_SAVE,

    /** A quick "did you mean X?" card; nothing is saved until the user answers. */
    CONFIRM,

    /** Nothing is logged; the capture waits for the user to review it. */
    NEEDS_REVIEW,
}

/**
 * Why a capture was not auto-saved.
 *
 * Constant names may be persisted as text later: renaming or removing one is then a data change.
 */
enum class TagDecisionReason {
    /** The extraction's operation is not LOG_ACTIVITY (a question or unsupported request). */
    NOT_A_LOG,

    /** No usable action words. */
    ACTION_MISSING,

    /** The action is a content-free verb ("do", "handle", "work on") that says nothing. */
    VAGUE_ACTION,

    /** The subject or action contains a filler word ("thing", "things", "stuff"). */
    FILLER_WORDS,

    /** A new tag name fails the new-name rules ([NewActivityNameCheck]). */
    NEW_NAME_REJECTED,

    /** No subject words, and no single known combination implies one. */
    SUBJECT_MISSING,

    /**
     * The subject words are close to, but not exactly, an existing subject -- by spelling, a
     * shared word, or one of the tiny subject synonym groups ([TagResolver.SUBJECT_SYNONYM_GROUPS]).
     * Also present whenever [SUBJECT_ONLY_OBJECT] offers a subject.
     */
    SUBJECT_NEAR_EXISTING,

    /**
     * The action words are close to, but not exactly, an existing action -- by spelling, a
     * shared object word, the same head verb, or one of the tiny verb synonym groups
     * ([TagResolver.ACTION_SYNONYM_GROUPS]).
     */
    ACTION_NEAR_EXISTING,

    /**
     * The subject words are only the action's object ("filter" with "change filter", or
     * "filter" with "replace" where "replace filter" exists): the real subject was probably
     * dropped. Never inferred silently (TG1.4b rule 4).
     */
    SUBJECT_ONLY_OBJECT,
}

/**
 * The tag decision for one capture.
 *
 * @property outcome What happens to the capture.
 * @property subject How the subject words resolved (after inference, see [subjectInferred]).
 * @property action How the action words resolved.
 * @property subjectInferred True when the user gave no subject and it was filled in from the
 *   only known combination with the resolved action.
 * @property reasons Why it was not auto-saved; empty exactly when [outcome] is AUTO_SAVE.
 * @property resplit True when the subject's trailing word(s) were moved into the action: either
 *   because that made both sides exact existing tags ("furnace filter" / "change" -> "furnace" /
 *   "change filter"; TG1.4b rule 2), or by the object-noun guard (rule 5, ADR-049: "hot tub
 *   filter" / "change" -> "hot tub" / "change filter"). [subject] and [action] are then the
 *   re-split resolutions.
 */
data class TagDecision(
    val outcome: TagDecisionOutcome,
    val subject: TagResolution,
    val action: TagResolution,
    val subjectInferred: Boolean,
    val reasons: Set<TagDecisionReason>,
    val resplit: Boolean = false,
) {
    init {
        require(reasons.isEmpty() == (outcome == TagDecisionOutcome.AUTO_SAVE)) {
            "reasons must be empty exactly when the outcome is AUTO_SAVE"
        }
    }
}

/**
 * Decides, deterministically, what to do with one extraction's subject and action words.
 *
 * Both sides are resolved with [TagResolver] against [TagCatalog]; then the first rule that
 * applies decides:
 * 1. operation is not LOG_ACTIVITY -> NEEDS_REVIEW ([TagDecisionReason.NOT_A_LOG]); questions
 *    are handled by a later stage.
 * 2. action resolves Empty -> NEEDS_REVIEW (ACTION_MISSING).
 * 3. the action is vague ([isVagueAction]: a content-free verb alone, or followed only by
 *    pronouns, e.g. "do", "fix it", "work on", "take care of it") -> NEEDS_REVIEW (VAGUE_ACTION).
 * 4. subject or action words contain a whole filler word ([FILLER_WORDS]) -> NEEDS_REVIEW
 *    (FILLER_WORDS).
 *
 * Then the words are repaired (TG1.4b; none of these ever leads to a silent save that the
 * plain words would not also have earned with an exact match on both sides):
 * - **Junk subject** ([isJunkSubject], rule 3): a subject that is only the action's verb
 *   ("edging" with "edging"/"edge") or only time words ([TIME_ONLY_WORDS]: "this morning",
 *   "Saturday") is treated as absent -- but ONLY when those words resolve to a NEW subject. A
 *   subject that matches an existing tag exactly or closely is always kept: "June" may be the
 *   dog, "Edging" may be a real subject.
 * - **Re-split** (rule 2): when the action is one word, the subject has 2+ words and the two
 *   are not already both exact, the subject's trailing word(s) are tried as the action's
 *   object, fewest first ("furnace filter" / "change" -> "furnace" / "change filter"). The
 *   first split that makes BOTH sides Exact is used ([TagDecision.resplit]); else nothing changes.
 * - **Object-noun guard** (rule 5, TG3.8): when rule 2 did not fire, the action is a bare one-word
 *   verb, the subject has 2+ words that resolve New (neither Exact nor Near: an existing subject
 *   such as "air filter" is kept, like the junk-subject rule), and the subject's last word
 *   (singularised) is in [OBJECT_NOUNS], that word moves into the action ("hot tub filter" /
 *   "change" -> "hot tub" / "change filter"; the verb is kept as given). Both new word groups
 *   are resolved against the catalog and [TagDecision.resplit] is set; rules below then decide
 *   as usual, so a repair never earns a silent save the repaired words would not earn alone. One-word
 *   subjects, multi-word actions, and nouns outside [OBJECT_NOUNS] are never touched. The set
 *   excludes ambiguous nouns (pump, tank, cover, screen, bag, light, mower, door) that can be
 *   the thing acted on by themselves.
 * - **Subject is only the object** (rule 4): when the subject is neither Exact nor absent and
 *   either (a) all its words are among the action's object words ("filter" / "change filter")
 *   or (b) the action is not Exact and "action + subject" means an existing action
 *   ([TagResolver.equivalentActions]: "replace" + "filter", or via a verb synonym "swap" +
 *   "filter"), the action becomes the resolution of those combined words and the subject is
 *   never inferred silently: if the actions meant are paired with exactly one distinct subject,
 *   the subject becomes Near(that subject) -> CONFIRM (SUBJECT_ONLY_OBJECT,
 *   SUBJECT_NEAR_EXISTING, plus ACTION_NEAR_EXISTING if the action is Near); otherwise
 *   NEEDS_REVIEW (SUBJECT_ONLY_OBJECT, SUBJECT_MISSING). A rule-4 subject's own words are
 *   never saved, so they are not name-checked in step 5.
 *
 * 5. a side resolving to [TagResolution.New] has a name that [NewActivityNameCheck] rejects
 *    -> NEEDS_REVIEW (NEW_NAME_REJECTED). Near names are not checked: they are only offered
 *    on a confirmation card, never saved silently.
 * 6. subject resolves Empty: if the action is Exact and exactly one distinct subject is paired
 *    with it in [TagCatalog.pairs], that subject is used (Exact via NAME, `subjectInferred`);
 *    otherwise NEEDS_REVIEW (SUBJECT_MISSING).
 * 7. either side is [TagResolution.Near] -> CONFIRM (SUBJECT_NEAR_EXISTING and/or
 *    ACTION_NEAR_EXISTING). Synonym and head-verb matches are Near, so they always land here.
 * 8. otherwise (each side Exact or New, nothing close) -> AUTO_SAVE with no reasons.
 *
 * Model confidence is never consulted (an extraction has none, ADR-038). Time wording,
 * duration and activity state are NOT judged here: resolving and checking time stays the job
 * of `TemporalResolver` (ADR-028), grounding them in the sentence is `ExtractionGrounding`, and
 * combining both decisions is a later pipeline stage. Pure: no I/O, no clock, no logging.
 */
object TagDecisionPolicy {

    /** Whole words that make a subject or action too vague to log. */
    val FILLER_WORDS: Set<String> = setOf("thing", "things", "stuff")

    /**
     * Content-free verbs: an action consisting of exactly one of these (and nothing else) is
     * vague. "fix" is vague; "fix fence" is not.
     */
    val VAGUE_VERBS: Set<String> = setOf(
        "do", "did", "done", "doing", "handle", "handled", "work", "worked",
        "fix", "fixed", "deal", "dealt", "take care", "sort", "sorted",
    )

    /** Whole action phrases that are vague even though they have a second word. */
    val VAGUE_PHRASES: Set<String> = setOf(
        "work on", "worked on", "deal with", "dealt with", "take care of", "sort out", "sorted out",
    )

    /**
     * Pronouns that name no object. A vague verb or phrase followed only by these is still vague
     * ("fix it", "did that", "deal with them").
     */
    val VAGUE_PRONOUNS: Set<String> = setOf("it", "that", "this", "them", "those", "these")

    /** Verbs whose particle may follow the pronoun ("sorted that out"). */
    val SPLIT_PARTICLE_VERBS: Set<String> = setOf("sort", "sorted")

    /** The particle allowed after the pronoun for [SPLIT_PARTICLE_VERBS]. */
    const val SPLIT_PARTICLE: String = "out"

    /**
     * Words that only say WHEN (TG1.4b rule 3b). A subject made up entirely of these
     * ("this morning", "Saturday", "Saturday morning", "last night") is time wording the model
     * put in the wrong field, and is treated as absent. Compared lowercased as whole words,
     * leading determiners removed, not singularized. Written here on purpose rather than shared
     * with the new-name rules: that list rejects names containing a time word anywhere, this one
     * recognises a subject that is nothing but time.
     */
    val TIME_ONLY_WORDS: Set<String> = setOf(
        "today", "yesterday", "tomorrow", "tonight", "morning", "afternoon", "evening", "night", "noon",
        "now", "just", "earlier", "recently", "ago", "this", "last", "week", "weekend",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
    )

    /**
     * Part and consumable nouns that are almost always the OBJECT of the action and almost never
     * the thing acted on by themselves when another noun phrase precedes them ("hot tub filter",
     * "mower blade", "car battery"). Used by rule 5 (see class KDoc). Singular, lowercase;
     * compared against the singularised last subject word. Deliberately conservative: nouns that
     * are often a thing in their own right ("pump", "tank", "cover", "screen", "bag", "light",
     * "mower", "door") are left out, because "sump pump" / "replace" must keep subject
     * "sump pump". Extend by adding words.
     */
    val OBJECT_NOUNS: Set<String> = setOf(
        "filter", "oil", "battery", "bulb", "belt", "hose", "tire", "fuse",
        "blade", "wiper", "gasket", "cartridge", "strainer",
    )

    private val VAGUE_PREFIXES: List<List<String>> =
        (VAGUE_VERBS + VAGUE_PHRASES).map { it.split(' ') }.sortedByDescending { it.size }

    /** Decides what to do with [extraction] given the existing tags in [catalog]. */
    fun decide(extraction: ExtractionCandidate, catalog: TagCatalog): TagDecision {
        var subject = TagResolver.resolve(extraction.subject, TagKind.SUBJECT, catalog)
        var action = TagResolver.resolve(extraction.action, TagKind.ACTION, catalog)
        var resplit = false

        fun review(vararg reasons: TagDecisionReason) =
            TagDecision(TagDecisionOutcome.NEEDS_REVIEW, subject, action, false, reasons.toSet(), resplit)

        if (extraction.operation != InterpretationOperation.LOG_ACTIVITY) return review(TagDecisionReason.NOT_A_LOG)
        if (action is TagResolution.Empty) return review(TagDecisionReason.ACTION_MISSING)
        if (isVagueAction(extraction.action)) return review(TagDecisionReason.VAGUE_ACTION)
        if (hasFiller(extraction.subject) || hasFiller(extraction.action)) return review(TagDecisionReason.FILLER_WORDS)

        // Rule 3: junk subjects are treated as absent -- never one that names an existing tag.
        var subjectWords = extraction.subject
        if (subject is TagResolution.New && isJunkSubject(subjectWords, extraction.action)) {
            subjectWords = null
            subject = TagResolution.Empty
        }

        // Rule 2: re-split a subject that swallowed the action's object.
        resplitOf(subjectWords, extraction.action, subject, action, catalog)?.let { (s, a) ->
            subject = s
            action = a
            resplit = true
        }

        // Rule 5 (TG3.8): an object noun at the end of a new subject belongs to the action.
        if (!resplit) {
            objectNounSplitOf(subjectWords, extraction.action, subject, catalog)?.let { (s, a) ->
                subject = s
                action = a
                resplit = true
            }
        }

        // Rule 4: a subject that is only the action's object.
        val objectOnly = if (resplit) null else objectOnlyOf(subjectWords, extraction.action, subject, action, catalog)
        if (objectOnly != null) action = objectOnly.action

        if ((objectOnly == null && newNameRejected(subject)) || newNameRejected(action)) {
            return review(TagDecisionReason.NEW_NAME_REJECTED)
        }

        if (objectOnly != null) {
            val offered = objectOnly.subjectCandidate
                ?: return review(TagDecisionReason.SUBJECT_ONLY_OBJECT, TagDecisionReason.SUBJECT_MISSING)
            val nearSubject = TagResolution.Near(TagNormalizer.cleanName(subjectWords) ?: offered.displayName, listOf(offered))
            val reasons = buildSet {
                add(TagDecisionReason.SUBJECT_ONLY_OBJECT)
                add(TagDecisionReason.SUBJECT_NEAR_EXISTING)
                if (action is TagResolution.Near) add(TagDecisionReason.ACTION_NEAR_EXISTING)
            }
            return TagDecision(TagDecisionOutcome.CONFIRM, nearSubject, action, false, reasons, resplit)
        }

        var inferred = false
        if (subject is TagResolution.Empty) {
            val inferredTag = (action as? TagResolution.Exact)?.let { inferSubject(it.tag, catalog) }
                ?: return review(TagDecisionReason.SUBJECT_MISSING)
            subject = TagResolution.Exact(inferredTag, TagMatchVia.NAME)
            inferred = true
        }

        val reasons = buildSet {
            if (subject is TagResolution.Near) add(TagDecisionReason.SUBJECT_NEAR_EXISTING)
            if (action is TagResolution.Near) add(TagDecisionReason.ACTION_NEAR_EXISTING)
        }
        val outcome = if (reasons.isEmpty()) TagDecisionOutcome.AUTO_SAVE else TagDecisionOutcome.CONFIRM
        return TagDecision(outcome, subject, action, inferred, reasons, resplit)
    }

    /**
     * True when [subjectWords] look like no subject at all (TG1.4b rule 3): (a) they are a single
     * word that is the action's verb or an inflection of it ([TagNormalizer.verbsMatch]:
     * "edging" with "edging" or "edge", "mowing" with "mow"), or (b) every word is one of
     * [TIME_ONLY_WORDS] ("this morning", "Saturday morning"). False for blank words (already
     * absent). Word shape only: [decide] applies it solely to a subject that resolved New.
     */
    fun isJunkSubject(subjectWords: String?, actionWords: String?): Boolean {
        val raw = TagNormalizer.rawTokens(subjectWords)
        if (raw.isEmpty()) return false
        if (raw.all { it in TIME_ONLY_WORDS }) return true
        val actionVerb = TagNormalizer.actionVerb(actionWords) ?: return false
        return raw.size == 1 && TagNormalizer.verbsMatch(raw.single(), actionVerb)
    }

    /**
     * True when [actionWords] are content-free. The words are compared lowercased, leading
     * determiners removed, not singularized. Vague when they start with one of [VAGUE_VERBS] or
     * [VAGUE_PHRASES] and what follows is:
     * - nothing ("fix", "work on"); or
     * - only [VAGUE_PRONOUNS] ("fix it", "did that", "take care of it", "deal with them"); or
     * - for [SPLIT_PARTICLE_VERBS], one or more pronouns then [SPLIT_PARTICLE] ("sorted that out").
     * A verb with a real object word is not vague ("fix fence", "work out").
     */
    fun isVagueAction(actionWords: String?): Boolean {
        val tokens = TagNormalizer.rawTokens(actionWords)
        return VAGUE_PREFIXES.any { prefix ->
            if (tokens.size < prefix.size || tokens.subList(0, prefix.size) != prefix) return@any false
            val rest = tokens.subList(prefix.size, tokens.size)
            rest.all { it in VAGUE_PRONOUNS } ||
                (prefix.first() in SPLIT_PARTICLE_VERBS && rest.size >= 2 && rest.last() == SPLIT_PARTICLE &&
                    rest.dropLast(1).all { it in VAGUE_PRONOUNS })
        }
    }

    /** True when [words] contain one of [FILLER_WORDS] as a whole word. */
    fun hasFiller(words: String?): Boolean = TagNormalizer.rawTokens(words).any { it in FILLER_WORDS }

    /** Rule 2: the first re-split (fewest moved words) that makes both sides Exact, or null. */
    private fun resplitOf(
        subjectWords: String?,
        actionWords: String?,
        subject: TagResolution,
        action: TagResolution,
        catalog: TagCatalog,
    ): Pair<TagResolution, TagResolution>? {
        if (subject is TagResolution.Exact && action is TagResolution.Exact) return null
        if (TagNormalizer.tokens(actionWords).size != 1) return null
        val actionName = TagNormalizer.cleanName(actionWords) ?: return null
        val words = TagNormalizer.cleanName(subjectWords)?.split(' ') ?: return null
        if (words.size < 2) return null
        for (moved in 1 until words.size) {
            val s = TagResolver.resolve(words.dropLast(moved).joinToString(" "), TagKind.SUBJECT, catalog)
            val a = TagResolver.resolve(actionName + " " + words.takeLast(moved).joinToString(" "), TagKind.ACTION, catalog)
            if (s is TagResolution.Exact && a is TagResolution.Exact) return s to a
        }
        return null
    }

    /**
     * Object-noun guard (TG3.8), or null when it does not apply: the action is one word, the
     * subject is 2+ words that resolved New (not Exact, not Near, so an owner's real "air filter"
     * subject is kept), and its last word singularised is in [OBJECT_NOUNS]. That word moves
     * into the action ("hot tub filter" / "change" -> "hot tub" / "change filter"); both new
     * word groups are resolved against the catalog.
     */
    private fun objectNounSplitOf(
        subjectWords: String?,
        actionWords: String?,
        subject: TagResolution,
        catalog: TagCatalog,
    ): Pair<TagResolution, TagResolution>? {
        if (subject !is TagResolution.New) return null
        if (TagNormalizer.tokens(actionWords).size != 1) return null
        val actionName = TagNormalizer.cleanName(actionWords) ?: return null
        val words = TagNormalizer.cleanName(subjectWords)?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (words.size < 2) return null
        val last = words.last()
        if (TagNormalizer.tokens(last).singleOrNull() !in OBJECT_NOUNS) return null
        val s = TagResolver.resolve(words.dropLast(1).joinToString(" "), TagKind.SUBJECT, catalog)
        val a = TagResolver.resolve("$actionName $last", TagKind.ACTION, catalog)
        return s to a
    }

    /** What rule 4 found: the combined action resolution and the only subject paired with it, if one. */
    private class ObjectOnly(val action: TagResolution, val subjectCandidate: KnownTag?)

    /** Rule 4, or null when it does not apply. */
    private fun objectOnlyOf(
        subjectWords: String?,
        actionWords: String?,
        subject: TagResolution,
        action: TagResolution,
        catalog: TagCatalog,
    ): ObjectOnly? {
        if (subject is TagResolution.Exact || subject is TagResolution.Empty) return null
        val subjectTokens = TagNormalizer.tokens(subjectWords)
        val actionTokens = TagNormalizer.tokens(actionWords)
        if (subjectTokens.isEmpty() || actionTokens.isEmpty()) return null
        val combined = when {
            actionTokens.size >= 2 && actionTokens.drop(1).containsAll(subjectTokens) -> actionWords
            action !is TagResolution.Exact ->
                "${TagNormalizer.cleanName(actionWords)} ${TagNormalizer.cleanName(subjectWords)}"
            else -> return null
        }
        val meant = TagResolver.equivalentActions(combined, catalog)
        if (meant.isEmpty()) return null
        val meantIds = meant.mapTo(HashSet()) { it.id }
        val subjectIds = catalog.pairs.filter { it.actionId in meantIds }.map { it.subjectId }.distinct()
        val offered = subjectIds.singleOrNull()?.let { catalog.tag(TagKind.SUBJECT, it) }
        return ObjectOnly(TagResolver.resolve(combined, TagKind.ACTION, catalog), offered)
    }

    private fun newNameRejected(resolution: TagResolution): Boolean =
        resolution is TagResolution.New && NewActivityNameCheck.check(resolution.name) !is NewActivityNameCheck.Result.Ok

    private fun inferSubject(action: KnownTag, catalog: TagCatalog): KnownTag? {
        val subjectIds = catalog.pairs.filter { it.actionId == action.id }.map { it.subjectId }.distinct()
        return subjectIds.singleOrNull()?.let { catalog.tag(TagKind.SUBJECT, it) }
    }
}
