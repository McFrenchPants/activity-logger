package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagNormalizer
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolver

/**
 * Deterministic clean-up of the question reader's UNTRUSTED words (DH4.5), applied by
 * [LookupService.ask] before the scope and the tags are worked out. Same spirit as the tag
 * policy repairs (ADR-039 amendment TG1.4b): program logic decides, never the model.
 *
 * Every rule only REMOVES the user's words or RECOMBINES them; nothing is invented, and every
 * recombined phrase must resolve to an EXISTING tag through [TagResolver]. Pure: the catalog is
 * passed in; nothing is logged. Words are compared normalized ([NameNormalizer.normalize], curly
 * apostrophes made plain). Rules, in order:
 * 1. **Last-time phrasing is not a date**: date words that are only "last", "last time", "the
 *    last time", "ever", "at all" or "before" are dropped ("last week" is untouched).
 * 2. **Placeholder subject**: a subject that is only "I", "me", "something", "stuff", ... is
 *    dropped, unless it is exactly an existing subject tag.
 * 3. **Subject made of date words**: when date words remain and occur in the question, a subject
 *    whose every word is among them ("holidays" from "around the holidays") is dropped, unless
 *    it is exactly an existing subject tag.
 * 4. **Generic action**: "do", "did", "get done", "do anything with", ... is dropped, unless it is
 *    exactly an existing action tag.
 * 5. **Re-join**: a subject that matches no tag at all plus an action whose joined phrase
 *    "<action> <subject>" is an existing action ("add" + "chlorine" -> "add chlorine") becomes
 *    that action alone.
 * 6. **Re-split**: a multi-word subject that is not exactly a tag, whose words minus the last are
 *    exactly an existing subject and whose last word joined to the action is an existing action
 *    ("furnace filter" + "change" -> "furnace" + "change filter"), is split that way.
 *
 * In rules 5 and 6 the joined phrase counts as an existing action when it resolves Exact, or Near
 * with a closest candidate that has a form (name or alias) with the SAME object words as the
 * phrase ("replace filter" ~ "change filter"). A Near that only shares the verb ("clean gutters" ~
 * "clean") does not count: that would quietly drop a named subject the user never logged.
 */
internal object QuestionWords {

    /** Normalized date words that only say "the last time" / "ever": rule 1. */
    val LAST_TIME_DATE_WORDS: Set<String> = setOf("last", "last time", "the last time", "ever", "at all", "before")

    /** Normalized subjects that name nothing in particular: rule 2. */
    val PLACEHOLDER_SUBJECTS: Set<String> = setOf(
        "i", "me", "my", "myself", "we", "us", "it", "something", "anything", "everything", "stuff", "thing", "things",
    )

    /** Normalized actions that say nothing about what was done: rule 4. */
    val GENERIC_ACTIONS: Set<String> = setOf(
        "do", "did", "done", "doing", "get done", "got done", "do anything", "do anything with", "do something",
        "do something with", "did anything", "work on", "worked on",
    )

    /** Rule 1 only: the candidate without date words that are only the last-time phrasing. */
    fun withoutLastTimeDateWords(candidate: QuestionCandidate): QuestionCandidate {
        val window = candidate.dateWindow ?: return candidate
        return if (normalize(window) in LAST_TIME_DATE_WORDS) candidate.copy(dateWindow = null) else candidate
    }

    /** Rules 1-6, in order, on [candidate] for [questionText] against [catalog]. Returns a copy. */
    fun clean(questionText: String, candidate: QuestionCandidate, catalog: TagCatalog): QuestionCandidate {
        var c = withoutLastTimeDateWords(candidate)

        // Rule 2.
        val subject2 = c.subject
        if (subject2 != null && normalize(subject2) in PLACEHOLDER_SUBJECTS && !isExact(subject2, TagKind.SUBJECT, catalog)) {
            c = c.copy(subject = null)
        }

        // Rule 3.
        val subject3 = c.subject
        val dateWords = saidDateWords(questionText, c.dateWindow)
        if (subject3 != null && dateWords != null) {
            val subjectWords = words(subject3)
            if (subjectWords.isNotEmpty() && subjectWords.all { it in dateWords } && !isExact(subject3, TagKind.SUBJECT, catalog)) {
                c = c.copy(subject = null)
            }
        }

        // Rule 4.
        val action4 = c.action
        if (action4 != null && normalize(action4) in GENERIC_ACTIONS && !isExact(action4, TagKind.ACTION, catalog)) {
            c = c.copy(action = null)
        }

        // Rule 5.
        val subject5 = c.subject
        val action5 = c.action
        if (subject5 != null && hasWords(action5) &&
            TagResolver.resolve(subject5, TagKind.SUBJECT, catalog) is TagResolution.New
        ) {
            // The subject's leading determiners ("the oil") are not part of an action's words.
            val objectWords = TagNormalizer.cleanName(subject5)?.let(::normalize)
            val joined = objectWords?.let { "${normalize(action5!!)} $it" }
            if (joined != null && isExistingAction(joined, catalog)) c = c.copy(subject = null, action = joined)
        }

        // Rule 6.
        val subject6 = c.subject
        val action6 = c.action
        if (subject6 != null && hasWords(action6)) {
            val subjectWords = words(subject6)
            if (subjectWords.size >= 2 && !isExact(subject6, TagKind.SUBJECT, catalog)) {
                val shorter = subjectWords.dropLast(1).joinToString(" ")
                val joined = "${normalize(action6!!)} ${subjectWords.last()}"
                if (isExact(shorter, TagKind.SUBJECT, catalog) && isExistingAction(joined, catalog)) {
                    c = c.copy(subject = shorter, action = joined)
                }
            }
        }
        return c
    }

    /** The words of [window] when it is non-blank and occurs in [questionText] (as [LookupService] checks), else null. */
    private fun saidDateWords(questionText: String, window: String?): Set<String>? {
        if (window == null) return null
        val said = normalize(window)
        if (said.isEmpty() || !normalize(questionText).contains(said)) return null
        return words(window).toSet()
    }

    private fun isExact(words: String, kind: TagKind, catalog: TagCatalog): Boolean =
        TagResolver.resolve(words, kind, catalog) is TagResolution.Exact

    /** Rules 5 and 6: [phrase] is an existing action (see the object description). */
    private fun isExistingAction(phrase: String, catalog: TagCatalog): Boolean =
        when (val resolution = TagResolver.resolve(phrase, TagKind.ACTION, catalog)) {
            is TagResolution.Exact -> true
            is TagResolution.Near -> sameObjects(phrase, resolution.candidates.first())
            TagResolution.Empty, is TagResolution.New -> false
        }

    private fun sameObjects(phrase: String, tag: KnownTag): Boolean {
        val objects = TagNormalizer.actionObjects(phrase)
        return objects.isNotEmpty() && (listOf(tag.displayName) + tag.aliases).any { TagNormalizer.actionObjects(it) == objects }
    }

    private fun hasWords(text: String?): Boolean = text != null && normalize(text).isNotEmpty()

    private fun words(text: String): List<String> =
        normalize(text).split(' ').map(NameNormalizer::normalize).filter { it.isNotEmpty() }

    /** Lowercase, whitespace collapsed, edge punctuation trimmed, curly apostrophes made plain. */
    private fun normalize(text: String): String = NameNormalizer.normalize(text.replace('’', '\''))
}
