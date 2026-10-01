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

    /** The subject words are close to, but not exactly, an existing subject. */
    SUBJECT_NEAR_EXISTING,

    /** The action words are close to, but not exactly, an existing action. */
    ACTION_NEAR_EXISTING,
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
 */
data class TagDecision(
    val outcome: TagDecisionOutcome,
    val subject: TagResolution,
    val action: TagResolution,
    val subjectInferred: Boolean,
    val reasons: Set<TagDecisionReason>,
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
 * 5. a side resolving to [TagResolution.New] has a name that [NewActivityNameCheck] rejects
 *    -> NEEDS_REVIEW (NEW_NAME_REJECTED). Near names are not checked: they are only offered
 *    on a confirmation card, never saved silently.
 * 6. subject resolves Empty: if the action is Exact and exactly one distinct subject is paired
 *    with it in [TagCatalog.pairs], that subject is used (Exact via NAME, `subjectInferred`);
 *    otherwise NEEDS_REVIEW (SUBJECT_MISSING).
 * 7. either side is [TagResolution.Near] -> CONFIRM (SUBJECT_NEAR_EXISTING and/or
 *    ACTION_NEAR_EXISTING).
 * 8. otherwise (each side Exact or New, nothing close) -> AUTO_SAVE with no reasons.
 *
 * Model confidence is never consulted (an extraction has none, ADR-038). Time wording,
 * duration and activity state are NOT judged here: resolving and checking time stays the job
 * of `TemporalResolver` (ADR-028) and the later pipeline stage that combines both decisions.
 * Pure: no I/O, no clock, no logging.
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

    private val VAGUE_PREFIXES: List<List<String>> =
        (VAGUE_VERBS + VAGUE_PHRASES).map { it.split(' ') }.sortedByDescending { it.size }

    /** Decides what to do with [extraction] given the existing tags in [catalog]. */
    fun decide(extraction: ExtractionCandidate, catalog: TagCatalog): TagDecision {
        val subject = TagResolver.resolve(extraction.subject, TagKind.SUBJECT, catalog)
        val action = TagResolver.resolve(extraction.action, TagKind.ACTION, catalog)

        fun review(reason: TagDecisionReason) =
            TagDecision(TagDecisionOutcome.NEEDS_REVIEW, subject, action, false, setOf(reason))

        if (extraction.operation != InterpretationOperation.LOG_ACTIVITY) return review(TagDecisionReason.NOT_A_LOG)
        if (action is TagResolution.Empty) return review(TagDecisionReason.ACTION_MISSING)
        if (isVagueAction(extraction.action)) return review(TagDecisionReason.VAGUE_ACTION)
        if (hasFiller(extraction.subject) || hasFiller(extraction.action)) return review(TagDecisionReason.FILLER_WORDS)
        if (newNameRejected(subject) || newNameRejected(action)) return review(TagDecisionReason.NEW_NAME_REJECTED)

        var finalSubject = subject
        var inferred = false
        if (subject is TagResolution.Empty) {
            val inferredTag = (action as? TagResolution.Exact)?.let { inferSubject(it.tag, catalog) }
                ?: return review(TagDecisionReason.SUBJECT_MISSING)
            finalSubject = TagResolution.Exact(inferredTag, TagMatchVia.NAME)
            inferred = true
        }

        val reasons = buildSet {
            if (finalSubject is TagResolution.Near) add(TagDecisionReason.SUBJECT_NEAR_EXISTING)
            if (action is TagResolution.Near) add(TagDecisionReason.ACTION_NEAR_EXISTING)
        }
        val outcome = if (reasons.isEmpty()) TagDecisionOutcome.AUTO_SAVE else TagDecisionOutcome.CONFIRM
        return TagDecision(outcome, finalSubject, action, inferred, reasons)
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

    private fun newNameRejected(resolution: TagResolution): Boolean =
        resolution is TagResolution.New && NewActivityNameCheck.check(resolution.name) !is NewActivityNameCheck.Result.Ok

    private fun inferSubject(action: KnownTag, catalog: TagCatalog): KnownTag? {
        val subjectIds = catalog.pairs.filter { it.actionId == action.id }.map { it.subjectId }.distinct()
        return subjectIds.singleOrNull()?.let { catalog.tag(TagKind.SUBJECT, it) }
    }
}
