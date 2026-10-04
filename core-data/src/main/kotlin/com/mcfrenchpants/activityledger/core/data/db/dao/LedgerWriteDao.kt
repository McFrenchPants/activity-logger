package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectEntity
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptInterpretationRequest
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptTaggedRequest
import com.mcfrenchpants.activityledger.core.data.ledger.NewCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.ledger.NewInterpretation
import com.mcfrenchpants.activityledger.core.data.ledger.NewTagAlias
import com.mcfrenchpants.activityledger.core.data.ledger.OccurrenceChanges
import com.mcfrenchpants.activityledger.core.data.ledger.TagCorrectionWrite
import com.mcfrenchpants.activityledger.core.data.ledger.TagRef
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TAG_MERGE_REASON
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind

/**
 * The ONLY place occurrences and corrections are written.
 *
 * Encapsulation: every primitive write (occurrence insert/update, correction
 * insert, and the copies of the interpretation/activity/tag/alias/raw-capture
 * writes the operations need) is `protected abstract`, so nothing outside this
 * class (or Room's generated subclass) can call it. The only callable entry points
 * are the nine `@Transaction` operations below (acceptInterpretation, acceptTagged,
 * applyCorrection, applyCorrectionCreatingActivity, correctTags, recordOutcome, hideOccurrence,
 * renameTag, mergeTags);
 * Room runs each in ONE database
 * transaction, so any exception rolls back every write it made. Application code
 * reaches them through core.data.ledger.ActivityLedgerWriter, which supplies the
 * IdFactory.
 *
 * Exception messages carry ids and enum names only, never user-entered text.
 */
@Dao
internal abstract class LedgerWriteDao {

    // --- primitives (not callable from outside this class) -----------------

    @Query("SELECT * FROM raw_captures WHERE id = :id")
    protected abstract fun findRawCapture(id: String): RawCaptureEntity?

    @Query("SELECT * FROM activity_occurrences WHERE raw_capture_id = :rawCaptureId")
    protected abstract fun findOccurrenceForRawCapture(rawCaptureId: String): ActivityOccurrenceEntity?

    @Query("SELECT * FROM activity_occurrences WHERE id = :id")
    protected abstract fun findOccurrence(id: String): ActivityOccurrenceEntity?

    @Query("SELECT raw_capture_id FROM interpretations WHERE id = :interpretationId")
    protected abstract fun findRawCaptureIdOfInterpretation(interpretationId: String): String?

    @Query("SELECT * FROM canonical_activities WHERE id = :id")
    protected abstract fun findCanonicalActivity(id: String): CanonicalActivityEntity?

    @Query("SELECT status FROM canonical_activities WHERE id = :activityId")
    protected abstract fun findCanonicalActivityStatus(activityId: String): CanonicalActivityStatus?

    @Query("SELECT * FROM subjects WHERE id = :id")
    protected abstract fun findSubject(id: String): SubjectEntity?

    @Query("SELECT * FROM actions WHERE id = :id")
    protected abstract fun findAction(id: String): ActionEntity?

    /** The oldest (then lowest-id) ACTIVE subject whose normalized_name is [key], or null. */
    @Query(
        "SELECT * FROM subjects WHERE status = 'ACTIVE' AND normalized_name = :key " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findActiveSubjectByName(key: String): SubjectEntity?

    /** The oldest (then lowest-id) ACTIVE action whose normalized_name is [key], or null. */
    @Query(
        "SELECT * FROM actions WHERE status = 'ACTIVE' AND normalized_name = :key " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findActiveActionByName(key: String): ActionEntity?

    /** The oldest (then lowest-id) ACTIVE subject having an alias whose key is [key], or null. */
    @Query(
        "SELECT * FROM subjects WHERE status = 'ACTIVE' AND EXISTS (" +
            "SELECT 1 FROM subject_aliases WHERE subject_aliases.subject_id = subjects.id " +
            "AND subject_aliases.normalized_alias = :key) " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findActiveSubjectByAlias(key: String): SubjectEntity?

    /** The oldest (then lowest-id) ACTIVE action having an alias whose key is [key], or null. */
    @Query(
        "SELECT * FROM actions WHERE status = 'ACTIVE' AND EXISTS (" +
            "SELECT 1 FROM action_aliases WHERE action_aliases.action_id = actions.id " +
            "AND action_aliases.normalized_alias = :key) " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findActiveActionByAlias(key: String): ActionEntity?

    @Query("SELECT COUNT(*) FROM subject_aliases WHERE subject_id = :subjectId AND normalized_alias = :key")
    protected abstract fun countSubjectAlias(subjectId: String, key: String): Int

    @Query("SELECT COUNT(*) FROM action_aliases WHERE action_id = :actionId AND normalized_alias = :key")
    protected abstract fun countActionAlias(actionId: String, key: String): Int

    /** The canonical activity that is the pair ([subjectId], [actionId]), any status, or null. */
    @Query("SELECT * FROM canonical_activities WHERE subject_id = :subjectId AND action_id = :actionId")
    protected abstract fun findPair(subjectId: String, actionId: String): CanonicalActivityEntity?

    @Insert
    protected abstract fun insertSubject(row: SubjectEntity)

    @Insert
    protected abstract fun insertAction(row: ActionEntity)

    @Insert
    protected abstract fun insertSubjectAlias(row: SubjectAliasEntity)

    @Insert
    protected abstract fun insertActionAlias(row: ActionAliasEntity)

    @Insert
    protected abstract fun insertInterpretation(row: InterpretationEntity)

    @Insert
    protected abstract fun insertCanonicalActivity(row: CanonicalActivityEntity)

    @Insert
    protected abstract fun insertOccurrence(row: ActivityOccurrenceEntity)

    @Insert
    protected abstract fun insertCorrection(row: CorrectionEntity)

    @Query("UPDATE raw_captures SET processing_state = :state, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateRawCaptureProcessingState(id: String, state: ProcessingState, updatedAt: Long): Int

    /**
     * Touches only the correctable columns (never raw_capture_id, captured_at, created_at,
     * visibility). Callers that do not correct the duration pass the current value back.
     */
    @Query(
        "UPDATE activity_occurrences SET canonical_activity_id = :canonicalActivityId, " +
            "occurred_at = :occurredAt, time_precision = :timePrecision, activity_state = :activityState, " +
            "effective_interpretation_id = :effectiveInterpretationId, duration_seconds = :durationSeconds, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    protected abstract fun updateOccurrenceCorrectableFields(
        id: String,
        canonicalActivityId: String,
        occurredAt: Long,
        timePrecision: TimePrecision,
        activityState: ActivityState,
        effectiveInterpretationId: String,
        durationSeconds: Long?,
        updatedAt: Long,
    ): Int

    /** Touches only visibility_status and updated_at of one occurrence. */
    @Query("UPDATE activity_occurrences SET visibility_status = :visibility, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateOccurrenceVisibility(id: String, visibility: VisibilityStatus, updatedAt: Long): Int

    // --- tag rename / merge primitives (TG2.4) ---------------------------------

    /** The oldest ACTIVE subject other than [excludeId] whose normalized_name is [key], or null. */
    @Query(
        "SELECT * FROM subjects WHERE status = 'ACTIVE' AND id <> :excludeId AND normalized_name = :key " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findOtherActiveSubjectByName(key: String, excludeId: String): SubjectEntity?

    /** The oldest ACTIVE action other than [excludeId] whose normalized_name is [key], or null. */
    @Query(
        "SELECT * FROM actions WHERE status = 'ACTIVE' AND id <> :excludeId AND normalized_name = :key " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findOtherActiveActionByName(key: String, excludeId: String): ActionEntity?

    /** The oldest ACTIVE subject other than [excludeId] having an alias whose key is [key], or null. */
    @Query(
        "SELECT * FROM subjects WHERE status = 'ACTIVE' AND id <> :excludeId AND EXISTS (" +
            "SELECT 1 FROM subject_aliases WHERE subject_aliases.subject_id = subjects.id " +
            "AND subject_aliases.normalized_alias = :key) " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findOtherActiveSubjectByAlias(key: String, excludeId: String): SubjectEntity?

    /** The oldest ACTIVE action other than [excludeId] having an alias whose key is [key], or null. */
    @Query(
        "SELECT * FROM actions WHERE status = 'ACTIVE' AND id <> :excludeId AND EXISTS (" +
            "SELECT 1 FROM action_aliases WHERE action_aliases.action_id = actions.id " +
            "AND action_aliases.normalized_alias = :key) " +
            "ORDER BY created_at ASC, id ASC LIMIT 1",
    )
    protected abstract fun findOtherActiveActionByAlias(key: String, excludeId: String): ActionEntity?

    @Query("SELECT * FROM subject_aliases WHERE subject_id = :subjectId ORDER BY created_at ASC, id ASC")
    protected abstract fun findSubjectAliases(subjectId: String): List<SubjectAliasEntity>

    @Query("SELECT * FROM action_aliases WHERE action_id = :actionId ORDER BY created_at ASC, id ASC")
    protected abstract fun findActionAliases(actionId: String): List<ActionAliasEntity>

    /** Every pair (any status) that uses the subject. */
    @Query("SELECT * FROM canonical_activities WHERE subject_id = :subjectId ORDER BY id ASC")
    protected abstract fun findPairsOfSubject(subjectId: String): List<CanonicalActivityEntity>

    /** Every pair (any status) that uses the action. */
    @Query("SELECT * FROM canonical_activities WHERE action_id = :actionId ORDER BY id ASC")
    protected abstract fun findPairsOfAction(actionId: String): List<CanonicalActivityEntity>

    /** Every occurrence (any visibility) of a canonical activity. */
    @Query("SELECT * FROM activity_occurrences WHERE canonical_activity_id = :activityId ORDER BY id ASC")
    protected abstract fun findOccurrencesOfActivity(activityId: String): List<ActivityOccurrenceEntity>

    /** Touches only display_name, normalized_name and updated_at of one subject. */
    @Query("UPDATE subjects SET display_name = :displayName, normalized_name = :normalizedName, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateSubjectName(id: String, displayName: String, normalizedName: String, updatedAt: Long): Int

    /** Touches only display_name, normalized_name and updated_at of one action. */
    @Query("UPDATE actions SET display_name = :displayName, normalized_name = :normalizedName, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateActionName(id: String, displayName: String, normalizedName: String, updatedAt: Long): Int

    /** Touches only status, merged_into_subject_id and updated_at of one subject. */
    @Query("UPDATE subjects SET status = :status, merged_into_subject_id = :mergedIntoId, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateSubjectMerge(id: String, status: TagStatus, mergedIntoId: String?, updatedAt: Long): Int

    /** Touches only status, merged_into_action_id and updated_at of one action. */
    @Query("UPDATE actions SET status = :status, merged_into_action_id = :mergedIntoId, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateActionMerge(id: String, status: TagStatus, mergedIntoId: String?, updatedAt: Long): Int

    /** Touches only the cached label columns and updated_at of one canonical activity. */
    @Query("UPDATE canonical_activities SET display_name = :displayName, normalized_name = :normalizedName, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateActivityLabel(id: String, displayName: String, normalizedName: String, updatedAt: Long): Int

    /** Touches only status, merged_into_activity_id and updated_at of one canonical activity. */
    @Query("UPDATE canonical_activities SET status = :status, merged_into_activity_id = :mergedIntoId, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateActivityMerge(id: String, status: CanonicalActivityStatus, mergedIntoId: String?, updatedAt: Long): Int

    // --- transactional operations -------------------------------------------

    /**
     * Renames a tag (ADR-042). See TagRepository.renameTag for the rules. [newKey] is the
     * TagNormalizer key of [newDisplayName], precomputed by the caller. All writes (name, alias
     * of the old name, refreshed pair labels) happen in this one transaction; the no-op and
     * conflict outcomes write nothing.
     *
     * @throws IllegalArgumentException if the tag is unknown, of the other kind or not ACTIVE, or
     *   [newKey] is blank. Nothing is written.
     */
    @Transaction
    open fun renameTag(
        idFactory: IdFactory,
        kind: TagKind,
        tagId: String,
        newDisplayName: String,
        newKey: String,
        now: Long,
    ): RenameOutcome {
        val tag = requireActiveTag(kind, tagId)
        require(newKey.isNotBlank()) { "New ${kind.name} name has a blank key (tag $tagId)" }
        val trimmed = newDisplayName.trim()
        if (trimmed == tag.displayName) return RenameOutcome.NothingChanged

        val conflict = when (kind) {
            TagKind.SUBJECT -> (findOtherActiveSubjectByName(newKey, tagId) ?: findOtherActiveSubjectByAlias(newKey, tagId))?.id
            TagKind.ACTION -> (findOtherActiveActionByName(newKey, tagId) ?: findOtherActiveActionByAlias(newKey, tagId))?.id
        }
        if (conflict != null) return RenameOutcome.ConflictsWith(conflict)

        val updated = when (kind) {
            TagKind.SUBJECT -> updateSubjectName(tagId, trimmed, newKey, now)
            TagKind.ACTION -> updateActionName(tagId, trimmed, newKey, now)
        }
        check(updated == 1) { "${kind.name} $tagId was not updated" }
        // The words used before keep resolving exactly (skipped when only case/punctuation changed).
        addAlias(idFactory, kind, tagId, newKey, tag.displayName, tag.normalizedName, AliasSource.MANUAL, now)

        val renamed = ResolvedTag(tagId, trimmed, newKey, TagStatus.ACTIVE)
        for (pair in pairsOf(kind, tagId)) {
            refreshPairLabel(pair, kind, renamed, now)
        }
        return RenameOutcome.Renamed
    }

    /**
     * Merges tag [fromId] into [intoId] (ADR-042). See TagRepository.mergeTags for the rules.
     * Write order: from-tag MERGED, aliases, then per ACTIVE pair: target pair (if created),
     * correction + occurrence update per occurrence, pair MERGED.
     *
     * @throws IllegalArgumentException if the ids are equal, either tag is unknown, of the other
     *   kind or not ACTIVE, or a target pair exists but is not ACTIVE. Everything written by
     *   this call is rolled back.
     */
    @Transaction
    open fun mergeTags(
        idFactory: IdFactory,
        kind: TagKind,
        fromId: String,
        intoId: String,
        now: Long,
    ): MergeOutcome {
        require(fromId != intoId) { "Cannot merge ${kind.name} $fromId into itself" }
        val from = requireActiveTag(kind, fromId)
        val into = requireActiveTag(kind, intoId)

        val mergeUpdated = when (kind) {
            TagKind.SUBJECT -> updateSubjectMerge(fromId, TagStatus.MERGED, intoId, now)
            TagKind.ACTION -> updateActionMerge(fromId, TagStatus.MERGED, intoId, now)
        }
        check(mergeUpdated == 1) { "${kind.name} $fromId was not updated" }

        addAlias(idFactory, kind, intoId, into.normalizedName, from.displayName, from.normalizedName, AliasSource.MANUAL, now)
        val fromAliases = when (kind) {
            TagKind.SUBJECT -> findSubjectAliases(fromId).map { Triple(it.aliasText, it.normalizedAlias, it.source) }
            TagKind.ACTION -> findActionAliases(fromId).map { Triple(it.aliasText, it.normalizedAlias, it.source) }
        }
        for ((text, key, source) in fromAliases) {
            addAlias(idFactory, kind, intoId, into.normalizedName, text, key, source, now)
        }

        var mergedPairs = 0
        var moved = 0
        for (pair in pairsOf(kind, fromId)) {
            if (pair.status != CanonicalActivityStatus.ACTIVE) continue
            val otherId = checkNotNull(if (kind == TagKind.SUBJECT) pair.actionId else pair.subjectId) {
                "Canonical activity ${pair.id} has no ${if (kind == TagKind.SUBJECT) "action" else "subject"}"
            }
            val subjectId = if (kind == TagKind.SUBJECT) intoId else otherId
            val actionId = if (kind == TagKind.SUBJECT) otherId else intoId
            val target = findPair(subjectId, actionId)
            val targetId = if (target != null) {
                require(target.status == CanonicalActivityStatus.ACTIVE) {
                    "Canonical activity ${target.id} (subject $subjectId, action $actionId) is ${target.status}, not ACTIVE"
                }
                target.id
            } else {
                val subjectName = if (kind == TagKind.SUBJECT) into.displayName else requireNotNull(findSubject(subjectId)).displayName
                val actionName = if (kind == TagKind.ACTION) into.displayName else requireNotNull(findAction(actionId)).displayName
                val label = pairDisplayName(subjectName, actionName)
                val newId = idFactory.newId()
                insertCanonicalActivity(
                    CanonicalActivityEntity(
                        id = newId,
                        displayName = label,
                        normalizedName = NameNormalizer.normalize(label),
                        status = CanonicalActivityStatus.ACTIVE,
                        createdAt = now,
                        updatedAt = now,
                        mergedIntoActivityId = null,
                        subjectId = subjectId,
                        actionId = actionId,
                    ),
                )
                newId
            }

            for (occurrence in findOccurrencesOfActivity(pair.id)) {
                insertCorrection(
                    CorrectionEntity(
                        id = idFactory.newId(),
                        occurrenceId = occurrence.id,
                        createdAt = now,
                        source = CorrectionSource.USER,
                        reason = TAG_MERGE_REASON,
                        previousCanonicalActivityId = pair.id,
                        newCanonicalActivityId = targetId,
                        previousOccurredAt = null,
                        newOccurredAt = null,
                        previousTimePrecision = null,
                        newTimePrecision = null,
                        previousActivityState = null,
                        newActivityState = null,
                        previousEffectiveInterpretationId = null,
                        newEffectiveInterpretationId = null,
                        previousDurationSeconds = null,
                        newDurationSeconds = null,
                    ),
                )
                val updated = updateOccurrenceCorrectableFields(
                    id = occurrence.id,
                    canonicalActivityId = targetId,
                    occurredAt = occurrence.occurredAt,
                    timePrecision = occurrence.timePrecision,
                    activityState = occurrence.activityState,
                    effectiveInterpretationId = occurrence.effectiveInterpretationId,
                    durationSeconds = occurrence.durationSeconds,
                    updatedAt = now,
                )
                check(updated == 1) { "Occurrence ${occurrence.id} was not updated" }
                moved++
            }
            check(updateActivityMerge(pair.id, CanonicalActivityStatus.MERGED, targetId, now) == 1) {
                "Canonical activity ${pair.id} was not updated"
            }
            mergedPairs++
        }
        return MergeOutcome(movedOccurrences = moved, mergedPairs = mergedPairs)
    }

    /**
     * Accepts an interpretation of a raw capture as its occurrence. Idempotent:
     * if the capture already has an occurrence, its id is returned and nothing
     * is written (the rest of the request, including any new activity, is ignored).
     *
     * Write order: interpretation, new canonical activity (if requested),
     * occurrence, raw capture processing_state = PERSISTED.
     *
     * @throws IllegalArgumentException if not exactly one of newActivity /
     *   canonicalActivityId is given, the interpretation names a different raw
     *   capture, the raw capture does not exist, or the existing canonical activity
     *   is not ACTIVE (merged/archived). Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row
     *   (e.g. the canonical activity) does not exist; everything is rolled back.
     *   (A missing activity deliberately stays a foreign-key failure here, not a
     *   precondition, so the historical contract of this operation is unchanged;
     *   the repository layer translates it.)
     */
    @Transaction
    open fun acceptInterpretation(idFactory: IdFactory, request: AcceptInterpretationRequest): String {
        findOccurrenceForRawCapture(request.rawCaptureId)?.let { return it.id }

        require((request.newActivity == null) != (request.canonicalActivityId == null)) {
            "Exactly one of newActivity and canonicalActivityId must be given (raw capture ${request.rawCaptureId})"
        }
        require(request.interpretation.rawCaptureId == request.rawCaptureId) {
            "Interpretation belongs to raw capture ${request.interpretation.rawCaptureId}, " +
                "not ${request.rawCaptureId}"
        }
        val capture = requireNotNull(findRawCapture(request.rawCaptureId)) {
            "Unknown raw capture ${request.rawCaptureId}"
        }
        request.canonicalActivityId?.let { activityId ->
            val status = findCanonicalActivityStatus(activityId)
            require(status == null || status == CanonicalActivityStatus.ACTIVE) {
                "Canonical activity $activityId is $status, not ACTIVE"
            }
        }

        val interpretationId = idFactory.newId()
        insertInterpretation(request.interpretation.toEntity(interpretationId))

        val newActivity = request.newActivity
        val activityId = if (newActivity != null) {
            insertNewActiveActivity(idFactory, newActivity, request.now)
        } else {
            checkNotNull(request.canonicalActivityId)
        }

        val occurrenceId = idFactory.newId()
        insertOccurrence(
            ActivityOccurrenceEntity(
                id = occurrenceId,
                canonicalActivityId = activityId,
                rawCaptureId = capture.id,
                effectiveInterpretationId = interpretationId,
                capturedAt = capture.capturedAt,
                occurredAt = request.occurredAt,
                timePrecision = request.timePrecision,
                activityState = request.activityState,
                visibilityStatus = VisibilityStatus.ACTIVE,
                createdAt = request.now,
                updatedAt = request.now,
            ),
        )
        check(updateRawCaptureProcessingState(capture.id, ProcessingState.PERSISTED, request.now) == 1) {
            "Raw capture ${capture.id} was not updated"
        }
        return occurrenceId
    }

    /**
     * Accepts an interpretation of a raw capture as an occurrence of a subject + action pair
     * (ADR-040). Idempotent: if the capture already has an occurrence, its id is returned and
     * nothing is written (no tag, pair, alias or interpretation).
     *
     * Each [TagRef] is resolved among tags of its OWN kind only:
     * - [TagRef.Existing]: the tag must exist and be ACTIVE.
     * - [TagRef.New]: the key must be non-blank; reuse the oldest (then lowest-id) ACTIVE tag
     *   whose normalized_name is the key, else the oldest (then lowest-id) ACTIVE tag with an
     *   alias of that key, else insert a new ACTIVE tag (display_name = the given name
     *   trimmed, normalized_name = key). Concurrent "Wi-Fi" / "WiFi" captures converge.
     *
     * The pair is the canonical_activities row with that (subject_id, action_id): reused if
     * ACTIVE, refused if not ACTIVE, else inserted ACTIVE with display_name [pairDisplayName]
     * and normalized_name = NameNormalizer of that label.
     *
     * Write order: tags (if created), pair (if created), interpretation, occurrence (with
     * duration_seconds), raw capture processing_state = PERSISTED, then aliases. An alias is
     * inserted (source AI_CONFIRMED, text trimmed) only when its key is non-blank, differs
     * from the tag's normalized_name and is not already an alias key of that tag; otherwise
     * it is silently skipped.
     *
     * @throws IllegalArgumentException if the interpretation names a different raw capture,
     *   the raw capture does not exist, the duration is negative, an Existing tag is unknown
     *   or not ACTIVE, a New key is blank, or the existing pair is not ACTIVE. Everything
     *   already written by this call is rolled back.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row (e.g. the
     *   interpretation's matched activity) does not exist; everything is rolled back.
     */
    @Transaction
    open fun acceptTagged(idFactory: IdFactory, request: AcceptTaggedRequest): String {
        findOccurrenceForRawCapture(request.rawCaptureId)?.let { return it.id }

        require(request.interpretation.rawCaptureId == request.rawCaptureId) {
            "Interpretation belongs to raw capture ${request.interpretation.rawCaptureId}, " +
                "not ${request.rawCaptureId}"
        }
        val capture = requireNotNull(findRawCapture(request.rawCaptureId)) {
            "Unknown raw capture ${request.rawCaptureId}"
        }
        val duration = request.durationSeconds
        require(duration == null || duration >= 0) { "Duration must be null or >= 0 (raw capture ${capture.id})" }

        val subject = resolveSubject(idFactory, request.subject, request.now)
        val action = resolveAction(idFactory, request.action, request.now)
        val activityId = resolvePair(idFactory, subject, action, request.now)

        val interpretationId = idFactory.newId()
        insertInterpretation(request.interpretation.toEntity(interpretationId))

        val occurrenceId = idFactory.newId()
        insertOccurrence(
            ActivityOccurrenceEntity(
                id = occurrenceId,
                canonicalActivityId = activityId,
                rawCaptureId = capture.id,
                effectiveInterpretationId = interpretationId,
                capturedAt = capture.capturedAt,
                occurredAt = request.occurredAt,
                timePrecision = request.timePrecision,
                activityState = request.activityState,
                visibilityStatus = VisibilityStatus.ACTIVE,
                createdAt = request.now,
                updatedAt = request.now,
                durationSeconds = duration,
            ),
        )
        check(updateRawCaptureProcessingState(capture.id, ProcessingState.PERSISTED, request.now) == 1) {
            "Raw capture ${capture.id} was not updated"
        }

        learnSubjectAlias(idFactory, subject, request.subjectAlias, AliasSource.AI_CONFIRMED, request.now)
        learnActionAlias(idFactory, action, request.actionAlias, AliasSource.AI_CONFIRMED, request.now)
        return occurrenceId
    }

    /**
     * Applies a correction to an occurrence and records it in ONE corrections row:
     * for each field whose requested value is non-null and differs from the
     * current value, previous = current and new = requested; unchanged fields are
     * null on both sides.
     *
     * Returns the new correction id, or **null if no requested field differs from
     * the current value** -- in that case nothing at all is written.
     *
     * @throws IllegalArgumentException if the occurrence does not exist, the
     *   requested effective interpretation is unknown or belongs to another raw
     *   capture, or a changed canonical activity does not exist or is not ACTIVE.
     *   Nothing is written.
     */
    @Transaction
    open fun applyCorrection(
        idFactory: IdFactory,
        occurrenceId: String,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String? {
        val current = requireNotNull(findOccurrence(occurrenceId)) { "Unknown occurrence $occurrenceId" }
        return correct(idFactory, current, changes, source, reason, now)
    }

    /**
     * In ONE transaction: creates [newActivity] as an ACTIVE canonical activity, then
     * applies [changes] with the occurrence's canonical activity set to the new id
     * (exactly as [applyCorrection]). Because the new activity always differs from the
     * current one, a corrections row is always written. Returns the correction id.
     *
     * Write order: canonical activity, correction, occurrence update.
     *
     * @throws IllegalArgumentException if [changes] already names a canonical activity,
     *   the occurrence does not exist, or the requested effective interpretation is
     *   invalid (see [applyCorrection]). Nothing is written.
     */
    @Transaction
    open fun applyCorrectionCreatingActivity(
        idFactory: IdFactory,
        occurrenceId: String,
        newActivity: NewCanonicalActivity,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String {
        require(changes.canonicalActivityId == null) {
            "canonicalActivityId must be null when creating an activity (occurrence $occurrenceId)"
        }
        val current = requireNotNull(findOccurrence(occurrenceId)) { "Unknown occurrence $occurrenceId" }
        val activityId = insertNewActiveActivity(idFactory, newActivity, now)
        return checkNotNull(
            correct(idFactory, current, changes.copy(canonicalActivityId = activityId), source, reason, now),
        ) { "Correction of occurrence $occurrenceId to new activity $activityId wrote nothing" }
    }

    /**
     * Corrects the subject + action pair and/or the duration of an occurrence and records it in
     * ONE corrections row. Returns the new correction id, or **null if neither the pair nor the
     * duration changes** -- in that case nothing at all is written (no tag, pair, alias or
     * correction row).
     *
     * Targets resolve as in [acceptTagged] (see [TagRef]); a null target keeps the occurrence's
     * current tag of that kind. An untagged (v3) occurrence can only be retagged with BOTH
     * targets. If the pair changes, a kept tag must still be ACTIVE. The target pair is the
     * canonical_activities row for (subject, action): reused if ACTIVE, refused if not ACTIVE,
     * else inserted ACTIVE. Whether the pair changes is decided BEFORE anything is inserted (a
     * New name that needs a new tag always changes it).
     *
     * The corrections row has previous/new canonical activity only if the pair changed and
     * previous/new duration_seconds only if the duration changed (null on both sides
     * otherwise). The occurrence's canonical_activity_id, duration_seconds and updated_at are
     * updated; the raw capture, captured_at, effective interpretation and visibility never are.
     * Then aliases are inserted (source USER_CORRECTION, text trimmed) only when the key is
     * non-blank, differs from the final tag's normalized_name and is not already its alias;
     * otherwise silently skipped. Which words deserve to be learned is the caller's policy.
     *
     * Write order: tags (if created), pair (if created), correction, occurrence update, aliases.
     *
     * @throws IllegalArgumentException if the occurrence does not exist, an Existing tag is
     *   unknown, of the other kind or not ACTIVE, a New key is blank, an untagged occurrence
     *   gets only one tag, the duration is negative, or the target pair is not ACTIVE. Nothing
     *   is written.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row is missing;
     *   everything is rolled back.
     */
    @Transaction
    open fun correctTags(idFactory: IdFactory, request: TagCorrectionWrite): String? {
        val current = requireNotNull(findOccurrence(request.occurrenceId)) {
            "Unknown occurrence ${request.occurrenceId}"
        }
        val newDuration = request.duration?.seconds
        require(newDuration == null || newDuration >= 0) {
            "Duration must be null or >= 0 (occurrence ${current.id})"
        }
        val currentActivity = checkNotNull(findCanonicalActivity(current.canonicalActivityId)) {
            "Occurrence ${current.id} has no canonical activity ${current.canonicalActivityId}"
        }
        val currentSubjectId = currentActivity.subjectId
        val currentActionId = currentActivity.actionId
        val subjectRef = request.subject
        val actionRef = request.action
        require((currentSubjectId != null && currentActionId != null) || (subjectRef != null && actionRef != null)) {
            "Occurrence ${current.id} is untagged; both a subject and an action are required"
        }

        // Look targets up without creating anything: a miss means a tag would have to be created.
        val foundSubject = if (subjectRef != null) lookupSubject(subjectRef) else keptSubject(currentSubjectId)
        val foundAction = if (actionRef != null) lookupAction(actionRef) else keptAction(currentActionId)
        val existingPair = if (foundSubject != null && foundAction != null) {
            findPair(foundSubject.id, foundAction.id)?.also { pair ->
                require(pair.status == CanonicalActivityStatus.ACTIVE) {
                    "Canonical activity ${pair.id} (subject ${foundSubject.id}, action ${foundAction.id}) " +
                        "is ${pair.status}, not ACTIVE"
                }
            }
        } else {
            null
        }
        val pairChanged = existingPair == null || existingPair.id != current.canonicalActivityId
        val durationChanged = request.duration != null && newDuration != current.durationSeconds
        if (!pairChanged && !durationChanged) return null

        // From here on something is written (an error still rolls everything back).
        val subject = foundSubject ?: createSubject(idFactory, subjectRef as TagRef.New, request.now)
        val action = foundAction ?: createAction(idFactory, actionRef as TagRef.New, request.now)
        val activityId = if (pairChanged) {
            if (subjectRef == null) requireActive(subject, "Subject")
            if (actionRef == null) requireActive(action, "Action")
            existingPair?.id ?: resolvePair(idFactory, subject, action, request.now)
        } else {
            current.canonicalActivityId
        }

        val correctionId = idFactory.newId()
        insertCorrection(
            CorrectionEntity(
                id = correctionId,
                occurrenceId = current.id,
                createdAt = request.now,
                source = request.source,
                reason = request.reason,
                previousCanonicalActivityId = if (pairChanged) current.canonicalActivityId else null,
                newCanonicalActivityId = if (pairChanged) activityId else null,
                previousOccurredAt = null,
                newOccurredAt = null,
                previousTimePrecision = null,
                newTimePrecision = null,
                previousActivityState = null,
                newActivityState = null,
                previousEffectiveInterpretationId = null,
                newEffectiveInterpretationId = null,
                previousDurationSeconds = if (durationChanged) current.durationSeconds else null,
                newDurationSeconds = if (durationChanged) newDuration else null,
            ),
        )
        val updated = updateOccurrenceCorrectableFields(
            id = current.id,
            canonicalActivityId = activityId,
            occurredAt = current.occurredAt,
            timePrecision = current.timePrecision,
            activityState = current.activityState,
            effectiveInterpretationId = current.effectiveInterpretationId,
            durationSeconds = if (durationChanged) newDuration else current.durationSeconds,
            updatedAt = request.now,
        )
        check(updated == 1) { "Occurrence ${current.id} was not updated" }

        learnSubjectAlias(idFactory, subject, request.subjectAlias, AliasSource.USER_CORRECTION, request.now)
        learnActionAlias(idFactory, action, request.actionAlias, AliasSource.USER_CORRECTION, request.now)
        return correctionId
    }

    /**
     * Records a non-accepting outcome of a raw capture: optionally stores
     * [interpretation] and sets the capture's processing_state to [processingState]
     * (updated_at = [now]), in ONE transaction. Never creates an occurrence.
     * Returns the new interpretation id, or null if none was given.
     *
     * Write order: processing-state update, then interpretation insert (so a failing
     * interpretation insert provably rolls back the already-applied state change).
     *
     * @throws IllegalArgumentException if the raw capture does not exist, already has
     *   an occurrence (an accepted capture's outcome is never rewritten),
     *   [processingState] is not NEEDS_REVIEW, FAILED_RETRYABLE or FAILED_FINAL, or the
     *   interpretation names a different raw capture. Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if the interpretation
     *   references a missing row (e.g. matched activity); everything is rolled back.
     */
    @Transaction
    open fun recordOutcome(
        idFactory: IdFactory,
        rawCaptureId: String,
        interpretation: NewInterpretation?,
        processingState: ProcessingState,
        now: Long,
    ): String? {
        val capture = requireNotNull(findRawCapture(rawCaptureId)) { "Unknown raw capture $rawCaptureId" }
        require(findOccurrenceForRawCapture(capture.id) == null) {
            "Raw capture ${capture.id} already has an occurrence; its outcome cannot be rewritten"
        }
        require(processingState in OUTCOME_STATES) {
            "Processing state $processingState is not an outcome state (raw capture ${capture.id})"
        }
        if (interpretation != null) {
            require(interpretation.rawCaptureId == capture.id) {
                "Interpretation belongs to raw capture ${interpretation.rawCaptureId}, not ${capture.id}"
            }
        }

        check(updateRawCaptureProcessingState(capture.id, processingState, now) == 1) {
            "Raw capture ${capture.id} was not updated"
        }
        if (interpretation == null) return null
        val interpretationId = idFactory.newId()
        insertInterpretation(interpretation.toEntity(interpretationId))
        return interpretationId
    }

    /**
     * Hides an occurrence: visibility ACTIVE -> HIDDEN, updated_at = [now]. Returns true if
     * the occurrence was hidden by this call, false if it was already HIDDEN (then nothing is
     * written). Touches no other table and no other column; hiding is not a correction, so
     * no corrections row is written.
     *
     * @throws IllegalArgumentException if the occurrence does not exist. Nothing is written.
     */
    @Transaction
    open fun hideOccurrence(occurrenceId: String, now: Long): Boolean {
        val current = requireNotNull(findOccurrence(occurrenceId)) { "Unknown occurrence $occurrenceId" }
        if (current.visibilityStatus == VisibilityStatus.HIDDEN) return false
        check(updateOccurrenceVisibility(occurrenceId, VisibilityStatus.HIDDEN, now) == 1) {
            "Occurrence $occurrenceId was not updated"
        }
        return true
    }

    // --- shared logic (not callable from outside this class) ----------------

    /** A tag of either kind after [acceptTagged] resolved it. */
    private class ResolvedTag(val id: String, val displayName: String, val normalizedName: String, val status: TagStatus)

    private fun resolveSubject(idFactory: IdFactory, ref: TagRef, now: Long): ResolvedTag =
        lookupSubject(ref) ?: createSubject(idFactory, ref as TagRef.New, now)

    private fun resolveAction(idFactory: IdFactory, ref: TagRef, now: Long): ResolvedTag =
        lookupAction(ref) ?: createAction(idFactory, ref as TagRef.New, now)

    /**
     * The subject [ref] names, or null when a [TagRef.New] needs a new tag. Writes nothing.
     * Existing: must exist and be ACTIVE. New: the key must be non-blank; reuses an ACTIVE
     * subject with that name key, else one with that alias key.
     */
    private fun lookupSubject(ref: TagRef): ResolvedTag? = when (ref) {
        is TagRef.Existing -> {
            val row = requireNotNull(findSubject(ref.tagId)) { "Unknown subject ${ref.tagId}" }
            require(row.status == TagStatus.ACTIVE) { "Subject ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
        }
        is TagRef.New -> {
            require(ref.key.isNotBlank()) { "New subject name has a blank key" }
            (findActiveSubjectByName(ref.key) ?: findActiveSubjectByAlias(ref.key))
                ?.let { ResolvedTag(it.id, it.displayName, it.normalizedName, it.status) }
        }
    }

    /** As [lookupSubject], for actions. */
    private fun lookupAction(ref: TagRef): ResolvedTag? = when (ref) {
        is TagRef.Existing -> {
            val row = requireNotNull(findAction(ref.tagId)) { "Unknown action ${ref.tagId}" }
            require(row.status == TagStatus.ACTIVE) { "Action ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
        }
        is TagRef.New -> {
            require(ref.key.isNotBlank()) { "New action name has a blank key" }
            (findActiveActionByName(ref.key) ?: findActiveActionByAlias(ref.key))
                ?.let { ResolvedTag(it.id, it.displayName, it.normalizedName, it.status) }
        }
    }

    /** The occurrence's current subject (any status), kept by a correction that names none. */
    private fun keptSubject(subjectId: String?): ResolvedTag? = subjectId?.let { id ->
        val row = checkNotNull(findSubject(id)) { "Unknown subject $id" }
        ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
    }

    /** The occurrence's current action (any status), kept by a correction that names none. */
    private fun keptAction(actionId: String?): ResolvedTag? = actionId?.let { id ->
        val row = checkNotNull(findAction(id)) { "Unknown action $id" }
        ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
    }

    /** The tag [tagId] of [kind]; it must exist in that kind's table and be ACTIVE. */
    private fun requireActiveTag(kind: TagKind, tagId: String): ResolvedTag = when (kind) {
        TagKind.SUBJECT -> {
            val row = requireNotNull(findSubject(tagId)) { "Unknown subject $tagId" }
            require(row.status == TagStatus.ACTIVE) { "Subject ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
        }
        TagKind.ACTION -> {
            val row = requireNotNull(findAction(tagId)) { "Unknown action $tagId" }
            require(row.status == TagStatus.ACTIVE) { "Action ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
        }
    }

    /**
     * Inserts an alias (text trimmed) of the tag [tagId] unless [key] is blank, equals the tag's
     * own key [tagKey] or is already an alias key of that tag.
     */
    private fun addAlias(
        idFactory: IdFactory,
        kind: TagKind,
        tagId: String,
        tagKey: String,
        aliasText: String,
        key: String,
        source: AliasSource,
        now: Long,
    ) {
        if (key.isBlank() || key == tagKey) return
        when (kind) {
            TagKind.SUBJECT -> {
                if (countSubjectAlias(tagId, key) != 0) return
                insertSubjectAlias(SubjectAliasEntity(idFactory.newId(), tagId, aliasText.trim(), key, source, now))
            }
            TagKind.ACTION -> {
                if (countActionAlias(tagId, key) != 0) return
                insertActionAlias(ActionAliasEntity(idFactory.newId(), tagId, aliasText.trim(), key, source, now))
            }
        }
    }

    private fun pairsOf(kind: TagKind, tagId: String): List<CanonicalActivityEntity> = when (kind) {
        TagKind.SUBJECT -> findPairsOfSubject(tagId)
        TagKind.ACTION -> findPairsOfAction(tagId)
    }

    /** Recomputes the cached label of [pair] from the renamed tag and the pair's other tag. */
    private fun refreshPairLabel(pair: CanonicalActivityEntity, kind: TagKind, renamed: ResolvedTag, now: Long) {
        val subjectName: String?
        val actionName: String?
        if (kind == TagKind.SUBJECT) {
            subjectName = renamed.displayName
            actionName = pair.actionId?.let { findAction(it)?.displayName }
        } else {
            subjectName = pair.subjectId?.let { findSubject(it)?.displayName }
            actionName = renamed.displayName
        }
        if (subjectName == null || actionName == null) return
        val label = pairDisplayName(subjectName, actionName)
        check(updateActivityLabel(pair.id, label, NameNormalizer.normalize(label), now) == 1) {
            "Canonical activity ${pair.id} was not updated"
        }
    }

    private fun requireActive(tag: ResolvedTag, label: String) {
        require(tag.status == TagStatus.ACTIVE) { "$label ${tag.id} is ${tag.status}, not ACTIVE" }
    }

    private fun createSubject(idFactory: IdFactory, ref: TagRef.New, now: Long): ResolvedTag {
        val row = SubjectEntity(
            id = idFactory.newId(),
            displayName = ref.displayName.trim(),
            normalizedName = ref.key,
            status = TagStatus.ACTIVE,
            mergedIntoSubjectId = null,
            createdAt = now,
            updatedAt = now,
        )
        insertSubject(row)
        return ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
    }

    private fun createAction(idFactory: IdFactory, ref: TagRef.New, now: Long): ResolvedTag {
        val row = ActionEntity(
            id = idFactory.newId(),
            displayName = ref.displayName.trim(),
            normalizedName = ref.key,
            status = TagStatus.ACTIVE,
            mergedIntoActionId = null,
            createdAt = now,
            updatedAt = now,
        )
        insertAction(row)
        return ResolvedTag(row.id, row.displayName, row.normalizedName, row.status)
    }

    /** Inserts [alias] for [subject] unless it is null, blank-keyed, the tag's own key or already an alias. */
    private fun learnSubjectAlias(
        idFactory: IdFactory,
        subject: ResolvedTag,
        alias: NewTagAlias?,
        source: AliasSource,
        now: Long,
    ) {
        if (alias == null || !isNewAlias(alias, subject.normalizedName) || countSubjectAlias(subject.id, alias.key) != 0) return
        insertSubjectAlias(
            SubjectAliasEntity(
                id = idFactory.newId(),
                subjectId = subject.id,
                aliasText = alias.aliasText.trim(),
                normalizedAlias = alias.key,
                source = source,
                createdAt = now,
            ),
        )
    }

    /** As [learnSubjectAlias], for an action. */
    private fun learnActionAlias(
        idFactory: IdFactory,
        action: ResolvedTag,
        alias: NewTagAlias?,
        source: AliasSource,
        now: Long,
    ) {
        if (alias == null || !isNewAlias(alias, action.normalizedName) || countActionAlias(action.id, alias.key) != 0) return
        insertActionAlias(
            ActionAliasEntity(
                id = idFactory.newId(),
                actionId = action.id,
                aliasText = alias.aliasText.trim(),
                normalizedAlias = alias.key,
                source = source,
                createdAt = now,
            ),
        )
    }

    /** The id of the ACTIVE pair (subject, action), inserted if it does not exist yet. */
    private fun resolvePair(idFactory: IdFactory, subject: ResolvedTag, action: ResolvedTag, now: Long): String {
        findPair(subject.id, action.id)?.let { pair ->
            require(pair.status == CanonicalActivityStatus.ACTIVE) {
                "Canonical activity ${pair.id} (subject ${subject.id}, action ${action.id}) is ${pair.status}, not ACTIVE"
            }
            return pair.id
        }
        val label = pairDisplayName(subject.displayName, action.displayName)
        val id = idFactory.newId()
        insertCanonicalActivity(
            CanonicalActivityEntity(
                id = id,
                displayName = label,
                normalizedName = NameNormalizer.normalize(label),
                status = CanonicalActivityStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                mergedIntoActivityId = null,
                subjectId = subject.id,
                actionId = action.id,
            ),
        )
        return id
    }

    /** False when [alias] has a blank key or the tag's own key [tagKey] (existing aliases are checked separately). */
    private fun isNewAlias(alias: NewTagAlias, tagKey: String): Boolean =
        alias.key.isNotBlank() && alias.key != tagKey

    private fun insertNewActiveActivity(idFactory: IdFactory, newActivity: NewCanonicalActivity, now: Long): String {
        val id = idFactory.newId()
        insertCanonicalActivity(
            CanonicalActivityEntity(
                id = id,
                displayName = newActivity.displayName,
                normalizedName = newActivity.normalizedName,
                status = CanonicalActivityStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                mergedIntoActivityId = null,
            ),
        )
        return id
    }

    /** The body of [applyCorrection] once the occurrence is known. Must run inside a transaction. */
    private fun correct(
        idFactory: IdFactory,
        current: ActivityOccurrenceEntity,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String? {
        val occurrenceId = current.id
        val newActivity = changes.canonicalActivityId?.takeIf { it != current.canonicalActivityId }
        val newOccurredAt = changes.occurredAt?.takeIf { it != current.occurredAt }
        val newPrecision = changes.timePrecision?.takeIf { it != current.timePrecision }
        val newState = changes.activityState?.takeIf { it != current.activityState }
        val newInterpretation = changes.effectiveInterpretationId?.takeIf { it != current.effectiveInterpretationId }

        if (newActivity == null && newOccurredAt == null && newPrecision == null &&
            newState == null && newInterpretation == null
        ) {
            return null
        }
        if (newActivity != null) {
            val status = findCanonicalActivityStatus(newActivity)
            require(status == CanonicalActivityStatus.ACTIVE) {
                if (status == null) "Unknown canonical activity $newActivity"
                else "Canonical activity $newActivity is $status, not ACTIVE"
            }
        }
        if (newInterpretation != null) {
            val owner = findRawCaptureIdOfInterpretation(newInterpretation)
            require(owner == current.rawCaptureId) {
                "Interpretation $newInterpretation does not belong to raw capture ${current.rawCaptureId} " +
                    "of occurrence $occurrenceId"
            }
        }

        val correctionId = idFactory.newId()
        insertCorrection(
            CorrectionEntity(
                id = correctionId,
                occurrenceId = occurrenceId,
                createdAt = now,
                source = source,
                reason = reason,
                previousCanonicalActivityId = newActivity?.let { current.canonicalActivityId },
                newCanonicalActivityId = newActivity,
                previousOccurredAt = newOccurredAt?.let { current.occurredAt },
                newOccurredAt = newOccurredAt,
                previousTimePrecision = newPrecision?.let { current.timePrecision },
                newTimePrecision = newPrecision,
                previousActivityState = newState?.let { current.activityState },
                newActivityState = newState,
                previousEffectiveInterpretationId = newInterpretation?.let { current.effectiveInterpretationId },
                newEffectiveInterpretationId = newInterpretation,
            ),
        )
        val updated = updateOccurrenceCorrectableFields(
            id = occurrenceId,
            canonicalActivityId = newActivity ?: current.canonicalActivityId,
            occurredAt = newOccurredAt ?: current.occurredAt,
            timePrecision = newPrecision ?: current.timePrecision,
            activityState = newState ?: current.activityState,
            effectiveInterpretationId = newInterpretation ?: current.effectiveInterpretationId,
            durationSeconds = current.durationSeconds,
            updatedAt = now,
        )
        check(updated == 1) { "Occurrence $occurrenceId was not updated" }
        return correctionId
    }
}

/**
 * The display_name cached on a newly created subject + action pair: "<subject> <action>",
 * e.g. "Furnace change filter". Only legacy (v3) screens read it; tag-aware reads (history)
 * use the tags' current names. renameTag (ADR-042) refreshes it for every pair of the renamed tag.
 */
internal fun pairDisplayName(subjectDisplayName: String, actionDisplayName: String): String =
    "$subjectDisplayName $actionDisplayName"

/** The only processing states [LedgerWriteDao.recordOutcome] may set. */
private val OUTCOME_STATES = setOf(
    ProcessingState.NEEDS_REVIEW,
    ProcessingState.FAILED_RETRYABLE,
    ProcessingState.FAILED_FINAL,
)
