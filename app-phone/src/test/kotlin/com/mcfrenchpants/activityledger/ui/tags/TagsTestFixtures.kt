package com.mcfrenchpants.activityledger.ui.tags

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.services.TagManagementService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.log.Extracted
import com.mcfrenchpants.activityledger.ui.log.ScriptedExtractor
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** The real ledger with switches that make the tag calls fail like a storage error and count writes. */
internal class TagsFailingLedger(private val inner: LedgerRepository) : LedgerRepository by inner {
    var failCatalog = false
    var failRename = false
    var failMerge = false
    var renameCalls = 0
    var mergeCalls = 0

    override suspend fun loadTagCatalog(): TagCatalog {
        if (failCatalog) throw IllegalStateException("simulated storage failure")
        return inner.loadTagCatalog()
    }

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome {
        renameCalls++
        if (failRename) throw IllegalStateException("simulated storage failure")
        return inner.renameTag(kind, tagId, newDisplayName)
    }

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome {
        mergeCalls++
        if (failMerge) throw IllegalStateException("simulated storage failure")
        return inner.mergeTags(kind, fromTagId, intoTagId)
    }
}

/** The real in-memory ledger, the real tag management service and a scripted extractor for seeding. */
internal class TagsWorld {
    val zone: ZoneId = ZoneId.of("America/Detroit")
    val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    val failing = TagsFailingLedger(ledger)
    val service = TagManagementService(failing)
    private val extractor = ScriptedExtractor().also { it.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE) }
    private val orchestrator = TaggedCaptureOrchestrator(ledger, extractor, clock)
    private var counter = 0

    /** Saves one tagged entry (new tags are created as needed), through the real orchestrator. */
    fun log(subject: String, action: String): String = runSuspend {
        counter++
        val words = "entry number $counter"
        extractor.on(words, Extracted.log(subject, action, duration = "10 minutes"))
        clock.advance(Duration.ofMinutes(1))
        val id = ledger.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = "log_typed",
                capturedAt = clock.instant(),
                zoneId = zone,
                rawText = words,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        orchestrator.process(id)
        id
    }

    fun catalog(): TagCatalog = runSuspend { ledger.loadTagCatalog() }

    fun subjectId(name: String): String = catalog().subjects.single { it.displayName == name }.id

    fun actionId(name: String): String = catalog().actions.single { it.displayName == name }.id

    /** The subject names of every visible entry in History. */
    fun entrySubjects(): List<String?> = runSuspend { ledger.loadHistory().map { it.occurrence?.subjectName } }
}
