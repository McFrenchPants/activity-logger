package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.insertRow
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TAG_MERGE_REASON
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RoomActivityRepository.renameTag / mergeTags (TG2.4, ADR-042) on the real (in-memory) Room
 * database. Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class RoomTagRenameMergeTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var seedRepository: LedgerRepository
    private lateinit var repository: LedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x40_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        seedRepository = RoomActivityRepository(
            db, ids, Clock.fixed(Instant.ofEpochMilli(SEED), ZoneId.of("UTC")), Dispatchers.Unconfined,
        )
        repository = RoomActivityRepository(
            db, ids, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC")), Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun capture(): String = seedRepository.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = null,
            capturedAt = Instant.ofEpochMilli(CAPTURED),
            zoneId = ZoneId.of("America/New_York"),
            rawText = "synthetic words",
            speechConfidence = null,
            speechAlternativesJson = null,
            processingState = ProcessingState.INTERPRETING,
        ),
    )

    private fun record() = InterpretationRecord(
        createdAt = Instant.ofEpochMilli(SEED - 5),
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 4,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.NEW_ACTIVITY,
        matchedActivityId = null,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = ConfidenceBand.HIGH,
        candidateContextHash = null,
        structuredResultJson = "{}",
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
        extractedSubject = "synthetic subject",
        extractedAction = "synthetic action",
        durationExpression = null,
        resolvedDurationSeconds = null,
    )

    private suspend fun acceptNew(subject: String, action: String): String =
        seedRepository.acceptTagged(
            TaggedAcceptRequest(
                captureId = capture(),
                interpretation = record(),
                subject = TagTarget.New(subject),
                action = TagTarget.New(action),
                occurredAt = Instant.ofEpochMilli(CAPTURED - 60_000),
                timePrecision = TimePrecision.APPROXIMATE,
                activityState = ActivityState.COMPLETED,
                durationSeconds = null,
            ),
        )

    private fun snapshot(): Map<String, List<String>> = TABLES.associateWith { dump(it) }

    private fun dump(table: String): List<String> =
        sql.query("SELECT * FROM $table ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "<null>" else c.getString(i) })
                }
            }
        }

    private fun stringColumn(query: String, vararg args: Any?): String? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun tagId(table: String, name: String): String =
        checkNotNull(stringColumn("SELECT id FROM $table WHERE display_name = ?", name)) { "no $name" }

    private fun subjectId(name: String) = tagId("subjects", name)
    private fun actionId(name: String) = tagId("actions", name)

    private fun aliasRows(table: String, idColumn: String, id: String): List<Triple<String, String, String>> =
        sql.query(
            "SELECT alias_text, normalized_alias, source FROM $table WHERE $idColumn = ? ORDER BY created_at, id",
            arrayOf(id),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2)))
            }
        }

    private fun subjectAliases(id: String) = aliasRows("subject_aliases", "subject_id", id)
    private fun actionAliases(id: String) = aliasRows("action_aliases", "action_id", id)

    private fun insertSubjectAlias(subjectId: String, text: String, source: AliasSource = AliasSource.SEEDED) =
        sql.insertRow(
            "subject_aliases",
            mapOf(
                "id" to "sa-${ids.newId()}", "subject_id" to subjectId, "alias_text" to text,
                "normalized_alias" to TagNormalizer.key(text), "source" to source, "created_at" to SEED,
            ),
        )

    private fun insertActionAlias(actionId: String, text: String, source: AliasSource = AliasSource.SEEDED) =
        sql.insertRow(
            "action_aliases",
            mapOf(
                "id" to "aa-${ids.newId()}", "action_id" to actionId, "alias_text" to text,
                "normalized_alias" to TagNormalizer.key(text), "source" to source, "created_at" to SEED,
            ),
        )

    private fun pairLabels(): List<String> =
        sql.query("SELECT display_name FROM canonical_activities ORDER BY display_name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private suspend fun unchanged(block: suspend () -> Unit) {
        val before = snapshot()
        block()
        assertEquals(before, snapshot(), "no table may change")
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        val before = snapshot()
        assertFailsWith<IllegalArgumentException> { block() }
        assertEquals(before, snapshot(), "an error must write nothing")
    }

    // --- rename --------------------------------------------------------------

    @Test
    fun renameUpdatesNameKeyAliasAndPairLabels() = runBlocking<Unit> {
        val entry = acceptNew("Furnace", "change filter")
        acceptNew("Furnace", "inspect")
        acceptNew("Boiler", "inspect")
        val furnace = subjectId("Furnace")
        val captureBefore = dump("raw_captures")
        val interpretationsBefore = dump("interpretations")
        val occurrencesBefore = dump("activity_occurrences")

        val outcome = repository.renameTag(TagKind.SUBJECT, furnace, "  Heating unit ")

        assertEquals(RenameOutcome.Renamed, outcome)
        assertEquals("Heating unit", stringColumn("SELECT display_name FROM subjects WHERE id = ?", furnace))
        assertEquals(TagNormalizer.key("Heating unit"), stringColumn("SELECT normalized_name FROM subjects WHERE id = ?", furnace))
        assertEquals(NOW.toString(), stringColumn("SELECT updated_at FROM subjects WHERE id = ?", furnace))
        assertEquals(listOf(Triple("Furnace", TagNormalizer.key("Furnace"), "MANUAL")), subjectAliases(furnace))
        assertEquals(listOf("Boiler inspect", "Heating unit change filter", "Heating unit inspect"), pairLabels())
        assertEquals(
            NOW.toString(),
            stringColumn("SELECT updated_at FROM canonical_activities WHERE display_name = 'Heating unit inspect'"),
        )
        assertEquals(
            TagNormalizer.key("Heating unit"),
            repository.loadTagCatalog().subjects.single { it.id == furnace }.let { TagNormalizer.key(it.displayName) },
        )
        assertTrue(repository.loadTagCatalog().subjects.any { it.displayName == "Heating unit" })
        // Not a correction; nothing else touched.
        assertEquals(0, sql.count("corrections"))
        assertEquals(captureBefore, dump("raw_captures"))
        assertEquals(interpretationsBefore, dump("interpretations"))
        assertEquals(occurrencesBefore, dump("activity_occurrences"))
        assertEquals("Heating unit", repository.loadHistory().single { it.occurrence?.occurrenceId == entry }.occurrence?.subjectName)
        // The old words still resolve exactly.
        val again = acceptNew("Furnace", "change filter")
        assertNotNull(again)
        assertEquals(2, sql.count("subjects"), "New(old name) reused the renamed tag")
    }

    @Test
    fun actionRenameMirrorsSubjectRename() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        val action = actionId("change filter")

        assertEquals(RenameOutcome.Renamed, repository.renameTag(TagKind.ACTION, action, "swap filter"))

        assertEquals(listOf(Triple("change filter", TagNormalizer.key("change filter"), "MANUAL")), actionAliases(action))
        assertEquals(listOf("Furnace swap filter"), pairLabels())
    }

    @Test
    fun caseAndPunctuationOnlyRenameChangesDisplayWithoutAlias() = runBlocking<Unit> {
        acceptNew("wifi", "reset")
        val subject = subjectId("wifi")

        assertEquals(RenameOutcome.Renamed, repository.renameTag(TagKind.SUBJECT, subject, "Wi-Fi"))

        assertEquals("Wi-Fi", stringColumn("SELECT display_name FROM subjects WHERE id = ?", subject))
        assertEquals(emptyList(), subjectAliases(subject))
        assertEquals(listOf("Wi-Fi reset"), pairLabels())
    }

    @Test
    fun renameToOneOfOwnAliasesLeavesTheAlias() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        val furnace = subjectId("Furnace")
        insertSubjectAlias(furnace, "Heater")

        assertEquals(RenameOutcome.Renamed, repository.renameTag(TagKind.SUBJECT, furnace, "Heater"))

        val aliases = subjectAliases(furnace)
        assertEquals(setOf("Heater", "Furnace"), aliases.map { it.first }.toSet())
        assertEquals(2, aliases.size)
    }

    @Test
    fun renameToTheSameNameChangesNothing() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        val furnace = subjectId("Furnace")
        unchanged {
            assertEquals(RenameOutcome.NothingChanged, repository.renameTag(TagKind.SUBJECT, furnace, " Furnace "))
        }
    }

    @Test
    fun renameConflictingWithAnotherTagNameOrAliasWritesNothing() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        acceptNew("Boiler", "inspect")
        val furnace = subjectId("Furnace")
        val boiler = subjectId("Boiler")
        insertSubjectAlias(boiler, "Heating plant")

        unchanged {
            assertEquals(
                RenameOutcome.ConflictsWith(boiler),
                repository.renameTag(TagKind.SUBJECT, furnace, "BOILER!"),
            )
            assertEquals(
                RenameOutcome.ConflictsWith(boiler),
                repository.renameTag(TagKind.SUBJECT, furnace, "heating plant"),
            )
        }
    }

    @Test
    fun renameAgainstATagOfAnotherKindOrAMergedTagDoesNotConflict() = runBlocking<Unit> {
        acceptNew("Furnace", "inspect")
        val furnace = subjectId("Furnace")
        // "inspect" is an ACTION name: no conflict for a subject.
        assertEquals(RenameOutcome.Renamed, repository.renameTag(TagKind.SUBJECT, furnace, "Inspect"))
    }

    @Test
    fun renameErrorsWriteNothing() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        acceptNew("Boiler", "inspect")
        val furnace = subjectId("Furnace")
        val boiler = subjectId("Boiler")
        repository.mergeTags(TagKind.SUBJECT, boiler, furnace)
        rejected { repository.renameTag(TagKind.SUBJECT, boiler, "Anything") } // MERGED
        rejected { repository.renameTag(TagKind.SUBJECT, "no-such-tag", "Anything") }
        rejected { repository.renameTag(TagKind.ACTION, furnace, "Anything") } // wrong kind
        rejected { repository.renameTag(TagKind.SUBJECT, furnace, "  ") }
        rejected { repository.renameTag(TagKind.SUBJECT, furnace, "!!!") }
    }

    // --- merge ---------------------------------------------------------------

    private data class Seeded(
        val replaceEntry: String,
        val changeEntry: String,
        val hiddenEntry: String,
        val hvac: String,
        val furnace: String,
    )

    private suspend fun seedSubjects(): Seeded {
        val furnaceEntry = acceptNew("furnace", "change filter")
        val replaceEntry = acceptNew("HVAC", "replace filter")
        val changeEntry = acceptNew("HVAC", "change filter")
        val hiddenEntry = acceptNew("HVAC", "change filter")
        seedRepository.hideOccurrence(hiddenEntry)
        val hvac = subjectId("HVAC")
        val furnace = subjectId("furnace")
        insertSubjectAlias(hvac, "heater", AliasSource.USER_CORRECTION)
        insertSubjectAlias(hvac, "heating system", AliasSource.AI_CONFIRMED)
        insertSubjectAlias(hvac, "Furnace", AliasSource.SEEDED) // same key as the target's name
        insertSubjectAlias(furnace, "Heater", AliasSource.SEEDED) // duplicate of an HVAC alias
        assertNotNull(furnaceEntry)
        return Seeded(replaceEntry, changeEntry, hiddenEntry, hvac, furnace)
    }

    @Test
    fun mergeSubjectsMovesPairsOccurrencesAndWords() = runBlocking<Unit> {
        val s = seedSubjects()
        val captures = dump("raw_captures")
        val interpretations = dump("interpretations")
        val occurrenceCountBefore = sql.count("activity_occurrences")
        val furnaceChangePair = checkNotNull(
            stringColumn("SELECT id FROM canonical_activities WHERE subject_id = ? AND action_id = ?", s.furnace, actionId("change filter")),
        )
        val hvacChangePair = checkNotNull(
            stringColumn("SELECT id FROM canonical_activities WHERE subject_id = ? AND action_id = ?", s.hvac, actionId("change filter")),
        )
        val interpretationOfReplace = stringColumn(
            "SELECT effective_interpretation_id FROM activity_occurrences WHERE id = ?", s.replaceEntry,
        )

        val outcome = repository.mergeTags(TagKind.SUBJECT, s.hvac, s.furnace)

        assertEquals(MergeOutcome(movedOccurrences = 3, mergedPairs = 2), outcome)
        assertEquals("MERGED", stringColumn("SELECT status FROM subjects WHERE id = ?", s.hvac))
        assertEquals(s.furnace, stringColumn("SELECT merged_into_subject_id FROM subjects WHERE id = ?", s.hvac))
        assertEquals(NOW.toString(), stringColumn("SELECT updated_at FROM subjects WHERE id = ?", s.hvac))

        // Words: furnace had {Heater(SEEDED)}; HVAC contributes its name and the rest, minus skips.
        val words = subjectAliases(s.furnace)
        assertEquals(
            listOf(
                Triple("Heater", TagNormalizer.key("Heater"), "SEEDED"),
                Triple("HVAC", TagNormalizer.key("HVAC"), "MANUAL"),
                Triple("heating system", TagNormalizer.key("heating system"), "AI_CONFIRMED"),
            ),
            words,
        )
        assertEquals(3, subjectAliases(s.hvac).size, "the merged tag keeps its own alias rows")

        // Pairs: (furnace, change filter) reused; (furnace, replace filter) created.
        val createdPair = checkNotNull(
            stringColumn("SELECT id FROM canonical_activities WHERE subject_id = ? AND action_id = ?", s.furnace, actionId("replace filter")),
        )
        assertEquals("furnace replace filter", stringColumn("SELECT display_name FROM canonical_activities WHERE id = ?", createdPair))
        assertEquals("ACTIVE", stringColumn("SELECT status FROM canonical_activities WHERE id = ?", createdPair))
        assertEquals("MERGED", stringColumn("SELECT status FROM canonical_activities WHERE id = ?", hvacChangePair))
        assertEquals(furnaceChangePair, stringColumn("SELECT merged_into_activity_id FROM canonical_activities WHERE id = ?", hvacChangePair))
        assertEquals(
            createdPair,
            stringColumn(
                "SELECT merged_into_activity_id FROM canonical_activities WHERE subject_id = ? AND action_id = ?",
                s.hvac, actionId("replace filter"),
            ),
        )
        assertEquals(4, sql.count("canonical_activities"), "furnace x2 plus HVAC x2 pairs")

        // Occurrences, including the hidden one, moved by exactly one correction row each.
        assertEquals(occurrenceCountBefore, sql.count("activity_occurrences"))
        assertEquals(3, sql.count("corrections"))
        val moved = mapOf(s.replaceEntry to createdPair, s.changeEntry to furnaceChangePair, s.hiddenEntry to furnaceChangePair)
        for ((occurrence, target) in moved) {
            assertEquals(target, stringColumn("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", occurrence))
            val correction = db.correctionDao().listForOccurrence(occurrence).single()
            assertEquals(TAG_MERGE_REASON, correction.reason)
            assertEquals(CorrectionSource.USER, correction.source)
            assertEquals(target, correction.newCanonicalActivityId)
            assertEquals(NOW, correction.createdAt)
        }
        assertEquals(
            "HIDDEN",
            stringColumn("SELECT visibility_status FROM activity_occurrences WHERE id = ?", s.hiddenEntry),
        )
        assertEquals(
            interpretationOfReplace,
            stringColumn("SELECT effective_interpretation_id FROM activity_occurrences WHERE id = ?", s.replaceEntry),
        )

        // Raw captures and interpretations are byte-identical.
        assertEquals(captures, dump("raw_captures"))
        assertEquals(interpretations, dump("interpretations"))

        // Catalog and history.
        val catalog = repository.loadTagCatalog()
        assertEquals(listOf(s.furnace), catalog.subjects.map { it.id })
        assertEquals(
            setOf(KnownPair(s.furnace, actionId("change filter")), KnownPair(s.furnace, actionId("replace filter"))),
            catalog.pairs.toSet(),
        )
        val history = repository.loadHistory().mapNotNull { it.occurrence?.subjectName }
        assertEquals(listOf("furnace", "furnace", "furnace"), history, "the hidden entry is not shown")

        // The old words now converge on the target.
        val entries = sql.count("subjects")
        acceptNew("HVAC", "inspect")
        assertEquals(entries, sql.count("subjects"), "New(HVAC) resolved to furnace")
        assertEquals(
            s.furnace,
            stringColumn(
                "SELECT subject_id FROM canonical_activities WHERE action_id = ? AND status = 'ACTIVE'", actionId("inspect"),
            ),
        )
    }

    @Test
    fun mergeActionsIsSymmetric() = runBlocking<Unit> {
        val a1 = acceptNew("Furnace", "swap filter")
        val a2 = acceptNew("Boiler", "swap filter")
        val keep = acceptNew("Furnace", "change filter")
        val from = actionId("swap filter")
        val into = actionId("change filter")
        insertActionAlias(from, "filter swap")

        val outcome = repository.mergeTags(TagKind.ACTION, from, into)

        assertEquals(MergeOutcome(movedOccurrences = 2, mergedPairs = 2), outcome)
        assertEquals("MERGED", stringColumn("SELECT status FROM actions WHERE id = ?", from))
        assertEquals(into, stringColumn("SELECT merged_into_action_id FROM actions WHERE id = ?", from))
        assertEquals(
            listOf(
                Triple("swap filter", TagNormalizer.key("swap filter"), "MANUAL"),
                Triple("filter swap", TagNormalizer.key("filter swap"), "SEEDED"),
            ),
            actionAliases(into),
        )
        assertEquals("Boiler change filter", stringColumn("SELECT display_name FROM canonical_activities WHERE subject_id = ? AND action_id = ?", subjectId("Boiler"), into))
        for (entry in listOf(a1, a2, keep)) {
            val pair = checkNotNull(stringColumn("SELECT canonical_activity_id FROM activity_occurrences WHERE id = ?", entry))
            assertEquals(into, stringColumn("SELECT action_id FROM canonical_activities WHERE id = ?", pair))
        }
        assertEquals(2, sql.count("corrections"))
        assertEquals(listOf(into), repository.loadTagCatalog().actions.map { it.id })
        assertEquals(2, repository.loadTagCatalog().pairs.size)
        assertEquals(setOf("change filter"), repository.loadHistory().mapNotNull { it.occurrence?.actionName }.toSet())
    }

    @Test
    fun mergeOfATagWithoutPairsChangesOnlyTagAndAliases() = runBlocking<Unit> {
        acceptNew("Furnace", "change filter")
        // A subject that no pair uses, inserted directly.
        sql.insertRow(
            "subjects",
            mapOf(
                "id" to "subject-unused", "display_name" to "Unused", "normalized_name" to TagNormalizer.key("Unused"),
                "status" to "ACTIVE", "merged_into_subject_id" to null, "created_at" to SEED, "updated_at" to SEED,
            ),
        )
        val furnace = subjectId("Furnace")
        val pairs = dump("canonical_activities")
        val occurrences = dump("activity_occurrences")

        val outcome = repository.mergeTags(TagKind.SUBJECT, "subject-unused", furnace)

        assertEquals(MergeOutcome(0, 0), outcome)
        assertEquals(pairs, dump("canonical_activities"))
        assertEquals(occurrences, dump("activity_occurrences"))
        assertEquals(0, sql.count("corrections"))
        assertEquals(listOf("Unused"), subjectAliases(furnace).map { it.first })
        assertEquals("MERGED", stringColumn("SELECT status FROM subjects WHERE id = ?", "subject-unused"))
    }

    @Test
    fun mergeErrorsRollBackEverything() = runBlocking<Unit> {
        val s = seedSubjects()
        acceptNew("Boiler", "inspect")
        val boiler = subjectId("Boiler")
        repository.mergeTags(TagKind.SUBJECT, boiler, s.furnace)

        rejected { repository.mergeTags(TagKind.SUBJECT, s.furnace, s.furnace) }
        rejected { repository.mergeTags(TagKind.SUBJECT, boiler, s.furnace) } // from MERGED
        rejected { repository.mergeTags(TagKind.SUBJECT, s.furnace, boiler) } // into MERGED
        rejected { repository.mergeTags(TagKind.SUBJECT, "no-such-tag", s.furnace) }
        rejected { repository.mergeTags(TagKind.SUBJECT, s.furnace, "no-such-tag") }
        rejected { repository.mergeTags(TagKind.ACTION, s.hvac, s.furnace) } // subject ids as actions
        rejected { repository.mergeTags(TagKind.ACTION, actionId("replace filter"), s.furnace) }

        // Guard branch: make the target pair non-ACTIVE, then a merge that needs it must fail midway.
        sql.insertRow(
            "canonical_activities",
            mapOf(
                "id" to "blocked-pair", "display_name" to "furnace replace filter", "normalized_name" to "furnace replace filter",
                "status" to "ARCHIVED", "created_at" to SEED, "updated_at" to SEED,
                "merged_into_activity_id" to null, "subject_id" to s.furnace, "action_id" to actionId("replace filter"),
            ),
        )
        rejected { repository.mergeTags(TagKind.SUBJECT, s.hvac, s.furnace) }
        assertTrue(sql.count("subjects") > 0)
    }
}

private const val SEED = 1_759_000_000_000L
private const val NOW = 1_760_000_000_123L
private const val CAPTURED = 1_759_999_000_456L
private val TABLES = listOf(
    "raw_captures", "subjects", "actions", "subject_aliases", "action_aliases",
    "canonical_activities", "interpretations", "activity_occurrences", "corrections",
)
