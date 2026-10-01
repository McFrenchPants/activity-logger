package com.mcfrenchpants.activityledger.core.domain.tagging

/*
 * Tagging: the deterministic half of the subject + action redesign (ADR-038, ADR-039).
 *
 * The AI only extracts the user's words (extraction package). Everything in this package turns
 * those words into tag decisions with plain program logic: no I/O, no clock, no logging, no AI,
 * and the same answer for the same input every time.
 */

/** Which of the two tag kinds a tag is. Tags are only ever compared with tags of the same kind. */
enum class TagKind {
    /** What something was done to ("hot tub", "furnace"). */
    SUBJECT,

    /** What was done ("change filter", "mow"). */
    ACTION,
}

/**
 * One existing tag the resolver can match words against.
 *
 * @property id Stable identifier of the tag.
 * @property kind Whether this is a subject or an action tag.
 * @property displayName The tag's name as the user sees it.
 * @property aliases Other names that mean the same tag; matched like the display name.
 */
data class KnownTag(
    val id: String,
    val kind: TagKind,
    val displayName: String,
    val aliases: List<String>,
)

/**
 * A known subject + action combination (what the earlier design called an "activity"), used to
 * infer a subject the user left out ("finished mowing" -> lawn).
 *
 * @property subjectId Id of a subject tag in the same catalog.
 * @property actionId Id of an action tag in the same catalog.
 */
data class KnownPair(
    val subjectId: String,
    val actionId: String,
)

/**
 * Every existing tag and known combination the resolver and decision policy may use.
 *
 * Checked on construction: every tag in [subjects] has kind [TagKind.SUBJECT] and every tag in
 * [actions] has kind [TagKind.ACTION]; ids are non-blank and unique within each kind; every
 * pair refers to an existing subject id and an existing action id. A violation throws
 * [IllegalArgumentException]; the message names ids only, never tag names.
 *
 * @property subjects Existing subject tags.
 * @property actions Existing action tags.
 * @property pairs Known subject + action combinations.
 */
data class TagCatalog(
    val subjects: List<KnownTag>,
    val actions: List<KnownTag>,
    val pairs: List<KnownPair>,
) {
    init {
        subjects.forEach { require(it.kind == TagKind.SUBJECT) { "subject ${it.id} has kind ${it.kind}" } }
        actions.forEach { require(it.kind == TagKind.ACTION) { "action ${it.id} has kind ${it.kind}" } }
        (subjects + actions).forEach { require(it.id.isNotBlank()) { "tag id must not be blank" } }
        requireUniqueIds(subjects, TagKind.SUBJECT)
        requireUniqueIds(actions, TagKind.ACTION)
        val subjectIds = subjects.mapTo(HashSet()) { it.id }
        val actionIds = actions.mapTo(HashSet()) { it.id }
        pairs.forEach { pair ->
            require(pair.subjectId in subjectIds) { "pair refers to unknown subject ${pair.subjectId}" }
            require(pair.actionId in actionIds) { "pair refers to unknown action ${pair.actionId}" }
        }
    }

    /** The tags of [kind], in catalog order. */
    fun tagsOf(kind: TagKind): List<KnownTag> = when (kind) {
        TagKind.SUBJECT -> subjects
        TagKind.ACTION -> actions
    }

    /** The tag of [kind] with [id], or null. */
    fun tag(kind: TagKind, id: String): KnownTag? = tagsOf(kind).firstOrNull { it.id == id }

    companion object {
        /** A catalog with no tags and no pairs: the state of a brand-new install. */
        val EMPTY: TagCatalog = TagCatalog(subjects = emptyList(), actions = emptyList(), pairs = emptyList())

        private fun requireUniqueIds(tags: List<KnownTag>, kind: TagKind) {
            val duplicates = tags.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate $kind ids: ${duplicates.sorted()}" }
        }
    }
}
