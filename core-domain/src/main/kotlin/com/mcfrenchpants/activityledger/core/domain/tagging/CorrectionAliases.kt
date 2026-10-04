package com.mcfrenchpants.activityledger.core.domain.tagging

import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck

/** The tag (of one kind) a correction moves an entry to: an existing tag, or a tag by name. */
sealed interface TagChoice {
    /** An existing tag of the side's kind, by id. */
    data class Existing(val tagId: String) : TagChoice

    /**
     * A tag with this display name. Like the repository, an existing tag of the same kind whose
     * name key or alias key equals the name's [TagNormalizer.key] is that tag (so "Wi-Fi" for an
     * existing "WiFi" is not a change); otherwise it is a brand-new tag.
     */
    data class New(val name: String) : TagChoice
}

/**
 * The user's original words that may safely be remembered as aliases after a correction.
 *
 * @property subjectAlias Words to learn for the corrected subject tag, or null for none.
 * @property actionAlias Words to learn for the corrected action tag, or null for none.
 */
data class LearnedAliases(val subjectAlias: String?, val actionAlias: String?) {
    companion object {
        /** Nothing to learn. */
        val NONE: LearnedAliases = LearnedAliases(subjectAlias = null, actionAlias = null)
    }
}

/**
 * Decides WHICH of the words the AI extracted from the user's sentence may be learned as aliases
 * after the user corrects an entry's subject and/or action tag (design-spec requirement 4,
 * ADR-041). Learning blindly is dangerous: if the AI heard "furnace" (an existing subject) and
 * the user corrects the entry to "hot tub", "furnace" must NOT become an alias of hot tub, or
 * every later furnace entry would silently save under hot tub. A silent wrong save is far worse
 * than an extra question, so every rule below errs towards learning nothing.
 *
 * Split of responsibility: the CALLER (the correction flow) runs this rule and passes the result
 * to `TagRepository.correctTags` as its learn* words; the repository only stores what it is
 * given (with its own redundant-alias skips) and applies no product policy. The same rules
 * should be applied before passing learn* words to `TagRepository.acceptTagged`.
 *
 * Each side (subject, action) is decided independently. Words are learned for a side only if
 * ALL of these hold:
 * 1. The side's tag CHANGED: the chosen tag (an [TagChoice.Existing] id, or the tag a
 *    [TagChoice.New] name converges on by key / alias key, else a brand-new tag) differs from
 *    the previous tag. A null previous id (an untagged entry) counts as changed.
 *    An [TagChoice.Existing] id missing from [TagCatalog] (unknown or merged) learns nothing.
 * 2. The extracted words are non-blank and their [TagNormalizer.key] is non-empty.
 * 3. The key is neither the chosen tag's name key nor one of its alias keys (nothing new to
 *    learn: "Wi-Fi" for "WiFi").
 * 4. The key is not the name key or an alias key of ANY other tag of the same kind in the
 *    catalog (the "furnace -> hot tub" case: the words already mean another tag).
 * 5. The words pass the new-name rules ([NewActivityNameCheck] on [TagNormalizer.cleanName],
 *    as [TagDecisionPolicy] applies them) and contain no [TagDecisionPolicy.hasFiller] word:
 *    "thing"/"stuff" or time and completion words are never learned.
 * 6. Action only: [TagResolver.equivalentActions] of the words names no action other than the
 *    chosen one -- i.e. the verb is not an inflection of another action's verb with identical
 *    object words ("changing oil" when "change oil" exists), nor (when there is no such match) a
 *    verb synonym of one with identical object words. Those words already mean, or are about
 *    to be asked about as, another action.
 * 7. Subject only: [TagDecisionPolicy.isJunkSubject] (the action's own verb, or only time
 *    words) is false, and NOT every word of the subject is among the extracted action's object
 *    words ([TagNormalizer.actionObjects]): in "filter" / "change filter" the real subject was
 *    dropped (ADR-039 amendment rule 4), so "filter" must never become an alias of "hot tub".
 *
 * The returned words are the extracted words trimmed (the user's wording; the repository
 * computes keys itself). Pure and deterministic: no I/O, no clock, no logging.
 */
object CorrectionAliases {

    /**
     * The aliases to learn after correcting an entry from ([previousSubjectId],
     * [previousActionId]) to ([newSubject], [newAction]), given the words the AI extracted
     * ([extractedSubject], [extractedAction]) and the current [catalog]. See the class rules.
     */
    fun aliasesToLearn(
        extractedSubject: String?,
        extractedAction: String?,
        previousSubjectId: String?,
        previousActionId: String?,
        newSubject: TagChoice,
        newAction: TagChoice,
        catalog: TagCatalog,
    ): LearnedAliases = LearnedAliases(
        subjectAlias = learnable(TagKind.SUBJECT, extractedSubject, extractedAction, previousSubjectId, newSubject, catalog),
        actionAlias = learnable(TagKind.ACTION, extractedAction, extractedAction, previousActionId, newAction, catalog),
    )

    /** The chosen tag of one side: its id (null for a brand-new tag) and the keys it already answers to. */
    private class Chosen(val id: String?, val keys: Set<String>)

    private fun learnable(
        kind: TagKind,
        words: String?,
        actionWords: String?,
        previousId: String?,
        choice: TagChoice,
        catalog: TagCatalog,
    ): String? {
        val chosen = chosen(kind, choice, catalog) ?: return null
        // Rule 1: only a side whose tag changed may learn.
        if (chosen.id != null && chosen.id == previousId) return null
        // Rule 2.
        if (words.isNullOrBlank()) return null
        val key = TagNormalizer.key(words)
        if (key.isEmpty()) return null
        // Rule 3.
        if (key in chosen.keys) return null
        // Rule 4: the words already name another tag of this kind.
        val others = catalog.tagsOf(kind).filter { it.id != chosen.id }
        if (others.any { tag -> forms(tag).any { TagNormalizer.key(it) == key } }) return null
        // Rule 5.
        val name = TagNormalizer.cleanName(words) ?: return null
        if (NewActivityNameCheck.check(name) !is NewActivityNameCheck.Result.Ok) return null
        if (TagDecisionPolicy.hasFiller(words)) return null
        when (kind) {
            // Rule 6.
            TagKind.ACTION ->
                if (TagResolver.equivalentActions(words, catalog).any { it.id != chosen.id }) return null
            // Rule 7.
            TagKind.SUBJECT -> {
                if (TagDecisionPolicy.isJunkSubject(words, actionWords)) return null
                val objects = TagNormalizer.actionObjects(actionWords)
                if (objects.isNotEmpty() && objects.containsAll(TagNormalizer.tokens(words))) return null
            }
        }
        return words.trim()
    }

    /**
     * Resolves [choice] the way the repository will: an Existing id must be in the catalog (else
     * null: learn nothing); a New name converges on the first tag (catalog order) whose name key,
     * then alias key, equals its key; otherwise it is a brand-new tag known only by its name key.
     * A New name with an empty key gives null.
     */
    private fun chosen(kind: TagKind, choice: TagChoice, catalog: TagCatalog): Chosen? = when (choice) {
        is TagChoice.Existing -> catalog.tag(kind, choice.tagId)?.let(::chosenOf)
        is TagChoice.New -> {
            val key = TagNormalizer.key(choice.name)
            if (key.isEmpty()) {
                null
            } else {
                val tags = catalog.tagsOf(kind)
                val found = tags.firstOrNull { TagNormalizer.key(it.displayName) == key }
                    ?: tags.firstOrNull { tag -> tag.aliases.any { TagNormalizer.key(it) == key } }
                found?.let(::chosenOf) ?: Chosen(id = null, keys = setOf(key))
            }
        }
    }

    private fun chosenOf(tag: KnownTag): Chosen =
        Chosen(tag.id, forms(tag).mapTo(HashSet()) { TagNormalizer.key(it) })

    private fun forms(tag: KnownTag): List<String> = listOf(tag.displayName) + tag.aliases
}
