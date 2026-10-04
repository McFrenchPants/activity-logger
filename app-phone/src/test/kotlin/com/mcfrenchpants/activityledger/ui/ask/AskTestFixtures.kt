package com.mcfrenchpants.activityledger.ui.ask

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupEntry
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagRepository
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
import java.time.ZoneId

/** A [QuestionExtractor] that answers what a test scripts, optionally held back until [release]. */
internal class ScriptedQuestionExtractor : QuestionExtractor {
    override val provenance = InterpreterProvenance("test-question-extractor", "test-prompt", 1)

    private var gate: CompletableDeferred<Unit>? = null

    /** What every question is answered with. */
    var result: QuestionExtractionResult = QuestionExtractionResult.Failure(InterpreterFailureKind.OTHER)

    /** Every question that reached the extractor, in order. */
    val received = mutableListOf<String>()

    fun answers(subject: String?, action: String?) {
        result = QuestionExtractionResult.Success(QuestionCandidate(subject, action))
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
        return result
    }
}

/**
 * The real ledger seen through [TagRepository], remembering which reads happened and refusing
 * (and recording) every write, so a test can prove the Ask view model never stores anything.
 */
internal class WriteRefusingTags(private val inner: LedgerRepository) : TagRepository by inner {
    val writeAttempts = mutableListOf<String>()

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String {
        writeAttempts += "acceptTagged"
        error("the Ask view model must not write")
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome {
        writeAttempts += "correctTags"
        error("the Ask view model must not write")
    }

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome {
        writeAttempts += "renameTag"
        error("the Ask view model must not write")
    }

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome {
        writeAttempts += "mergeTags"
        error("the Ask view model must not write")
    }
}

/**
 * Saves a visible entry for [subject]/[action] at the clock's current instant, reusing the tags
 * if the ledger already knows them (so a test advances the clock between entries to space them).
 */
internal fun logEntry(
    ledger: LedgerRepository,
    clock: Clock,
    zone: ZoneId,
    subject: String,
    action: String,
) {
    runSuspend {
        val catalog = ledger.loadTagCatalog()
        fun choice(known: List<KnownTag>, name: String): TagChoice =
            known.firstOrNull { it.displayName == name }?.let { TagChoice.Existing(it.id) } ?: TagChoice.New(name)
        val captureId = ledger.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = "ask-test",
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
    }
}

internal fun lookupEntriesOf(ledger: LedgerRepository): List<LookupEntry> =
    runSuspend { ledger.loadLookupEntries() }
