package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository

/** An existing activity offered when the user decides which activity a capture was. */
data class Suggestion(val activityId: String, val displayName: String)

/**
 * Deterministic "Which activity was this?" suggestions, shared by the Log card and the History
 * resolution sheet. No model call; pure Kotlin over the repository.
 */
object ReviewSuggestions {

    /** Most suggestions offered at once. */
    const val MAX_SUGGESTIONS: Int = 3

    /**
     * The capture's pending matched activity if it is still in the ACTIVE catalog, then
     * [CandidateSelector]'s candidates in order; de-duplicated, at most [MAX_SUGGESTIONS]. None
     * when the catalog is empty.
     */
    suspend fun suggest(repository: ActivityRepository, captureId: String, rawText: String): List<Suggestion> {
        val catalog = repository.loadCatalog()
        if (catalog.isEmpty()) return emptyList()
        val byId = catalog.associateBy { it.id }
        val pending = repository.loadHistory().firstOrNull { it.captureId == captureId }?.pendingMatchedActivityId
        val ids = buildList {
            if (pending != null && pending in byId) add(pending)
            CandidateSelector().select(catalog, rawText).candidates.forEach { add(it.id) }
        }
        return ids.distinct().take(MAX_SUGGESTIONS).map { Suggestion(it, byId.getValue(it).displayName) }
    }
}
