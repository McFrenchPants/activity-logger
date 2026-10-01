package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind

/** Converts tag corpus catalog fixtures into the domain [TagCatalog] the tag resolver uses. */
object TagCorpusCatalogs {

    /**
     * The domain catalog for [fixture]: subjects and actions keep their ids, display names,
     * aliases and order; pairs keep their ids (the pair display name is not part of the domain
     * catalog). Throws [IllegalArgumentException] if the fixture breaks a [TagCatalog] rule.
     */
    fun toTagCatalog(fixture: TagCatalogFixture): TagCatalog = TagCatalog(
        subjects = fixture.subjects.map { KnownTag(it.id, TagKind.SUBJECT, it.displayName, it.aliases) },
        actions = fixture.actions.map { KnownTag(it.id, TagKind.ACTION, it.displayName, it.aliases) },
        pairs = fixture.pairs.map { KnownPair(it.subjectId, it.actionId) },
    )
}
