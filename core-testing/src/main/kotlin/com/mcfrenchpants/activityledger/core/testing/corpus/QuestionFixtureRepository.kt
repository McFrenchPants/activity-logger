package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.lookup.LookupEntry
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind

/**
 * A READ-ONLY [TagRepository] over one question corpus catalog fixture, for replaying questions
 * through the real `LookupService`. Only [loadTagCatalog] and [loadLookupEntries] are supported;
 * every other method throws [UnsupportedOperationException] (the lookup never writes).
 *
 * - The catalog keeps the fixture's ids, display names, aliases and order; its pairs are the
 *   distinct subject + action combinations that have entries (fixture order of first use).
 * - Lookup entries carry the current display names, no duration, and are ordered newest first,
 *   occurrence id descending as tie-break -- the order the real repository promises.
 */
class QuestionFixtureRepository(fixture: QuestionCatalogFixture) : TagRepository {

    private val catalog: TagCatalog = catalogOf(fixture)
    private val entries: List<LookupEntry> = lookupEntriesOf(fixture)

    override suspend fun loadTagCatalog(): TagCatalog = catalog

    override suspend fun loadLookupEntries(): List<LookupEntry> = entries

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String = readOnly()

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome = readOnly()

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome = readOnly()

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome = readOnly()

    override suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords? = readOnly()

    override suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords? = readOnly()

    override suspend fun loadExploreEntries(): List<ExploreEntry> = readOnly()

    private fun readOnly(): Nothing =
        throw UnsupportedOperationException("the question corpus fixture repository is read-only")

    companion object {
        /** The domain catalog of [fixture]. */
        fun catalogOf(fixture: QuestionCatalogFixture): TagCatalog = TagCatalog(
            subjects = fixture.subjects.map { KnownTag(it.id, TagKind.SUBJECT, it.displayName, it.aliases) },
            actions = fixture.actions.map { KnownTag(it.id, TagKind.ACTION, it.displayName, it.aliases) },
            pairs = fixture.entries.map { KnownPair(it.subjectId, it.actionId) }.distinct(),
        )

        /** The lookup rows of [fixture], newest first, occurrence id descending as tie-break. */
        fun lookupEntriesOf(fixture: QuestionCatalogFixture): List<LookupEntry> {
            val subjects = fixture.subjects.associateBy { it.id }
            val actions = fixture.actions.associateBy { it.id }
            return fixture.entries.map { e ->
                LookupEntry(
                    occurrenceId = e.id,
                    subjectId = e.subjectId,
                    subjectName = requireNotNull(subjects[e.subjectId]) { "entry ${e.id} has an unknown subject" }.displayName,
                    actionId = e.actionId,
                    actionName = requireNotNull(actions[e.actionId]) { "entry ${e.id} has an unknown action" }.displayName,
                    occurredAt = e.occurredDateTime.toInstant(),
                    durationSeconds = null,
                )
            }.sortedWith(compareByDescending<LookupEntry> { it.occurredAt }.thenByDescending { it.occurrenceId })
        }
    }
}
