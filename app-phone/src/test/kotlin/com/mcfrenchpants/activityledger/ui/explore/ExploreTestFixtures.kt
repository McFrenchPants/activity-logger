package com.mcfrenchpants.activityledger.ui.explore

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/** A [QuestionExtractor] that answers what a test scripts, optionally held back until [release]. */
internal class ExploreQuestionExtractor : QuestionExtractor {
    override val provenance = InterpreterProvenance("test-question-extractor", "test-prompt", 1)

    private var gate: CompletableDeferred<Unit>? = null

    var result: QuestionExtractionResult = QuestionExtractionResult.Failure(InterpreterFailureKind.OTHER)

    /** When set, [extract] throws instead of answering. */
    var throwing = false

    /** Every question that reached the extractor, in order. */
    val received = mutableListOf<String>()

    fun answers(
        subject: String?,
        action: String?,
        dateWindow: String? = null,
        kind: QuestionKind = QuestionKind.UNKNOWN,
    ) {
        result = QuestionExtractionResult.Success(QuestionCandidate(subject, action, dateWindow, kind))
    }

    fun fails(kind: InterpreterFailureKind) {
        result = QuestionExtractionResult.Failure(kind)
    }

    fun holdAnswers() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun extract(questionText: String): QuestionExtractionResult {
        received += questionText
        gate?.await()
        check(!throwing) { "scripted failure" }
        return result
    }
}

/**
 * The real ledger seen through [TagRepository], refusing (and recording) every write so a test can
 * prove the Explore view model never stores anything. [failLoads] makes the Explore read throw;
 * [loadGate] holds it back.
 */
internal class WriteRefusingExploreTags(private val inner: LedgerRepository) : TagRepository by inner {
    val writeAttempts = mutableListOf<String>()
    var failLoads = false
    var exploreLoads = 0

    /** When set, the Explore read waits for it before reading (a load "in flight"). */
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun loadExploreEntries(): List<ExploreEntry> {
        exploreLoads++
        loadGate?.await()
        check(!failLoads) { "scripted load failure" }
        return inner.loadExploreEntries()
    }

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String {
        writeAttempts += "acceptTagged"
        error("the Explore view model must not write")
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome {
        writeAttempts += "correctTags"
        error("the Explore view model must not write")
    }

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome {
        writeAttempts += "renameTag"
        error("the Explore view model must not write")
    }

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome {
        writeAttempts += "mergeTags"
        error("the Explore view model must not write")
    }
}

/** A dispatcher that only runs work when the test says so, in any order it chooses. */
internal class ManualDispatcher : CoroutineDispatcher() {
    val queue = mutableListOf<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    fun runAt(index: Int) {
        queue.removeAt(index).run()
    }
}

/**
 * Saves a visible entry for [subject]/[action] at the clock's current instant, reusing the tags
 * if the ledger already knows them. Returns the occurrence id.
 */
internal fun logExploreEntry(
    ledger: LedgerRepository,
    clock: Clock,
    zone: ZoneId,
    subject: String,
    action: String,
): String = runSuspend {
    val catalog = ledger.loadTagCatalog()
    fun choice(known: List<KnownTag>, name: String): TagChoice =
        known.firstOrNull { it.displayName == name }?.let { TagChoice.Existing(it.id) } ?: TagChoice.New(name)
    val captureId = ledger.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = "explore-test",
            capturedAt = clock.instant(),
            zoneId = zone,
            rawText = "$action $subject",
            speechConfidence = null,
            speechAlternativesJson = null,
            processingState = ProcessingState.CAPTURED,
        ),
    )
    val resolved = TaggedResolutionService(ledger, clock).resolve(
        captureId,
        choice(catalog.subjects, subject),
        choice(catalog.actions, action),
    )
    check(resolved is TaggedResolutionResult.Resolved) { "logging refused: $resolved" }
    resolved.occurrenceId
}

/** Id of the tag of [kind] currently named [name]. */
internal fun tagIdOf(ledger: LedgerRepository, kind: TagKind, name: String): String = runSuspend {
    ledger.loadTagCatalog().tagsOf(kind).first { it.displayName == name }.id
}
