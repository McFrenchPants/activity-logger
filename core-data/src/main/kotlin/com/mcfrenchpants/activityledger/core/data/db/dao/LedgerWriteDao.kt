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

/**
 * The ONLY place occurrences and corrections are written.
 *
 * Encapsulation: every primitive write (occurrence insert/update, correction
 * insert, and the copies of the interpretation/activity/tag/alias/raw-capture
 * writes the operations need) is `protected abstract`, so nothing outside this
 * class (or Room's generated subclass) can call it. The only callable entry points
 * are the six `@Transaction` operations below (acceptInterpretation, acceptTagged,
 * applyCorrection, applyCorrectionCreatingActivity, recordOutcome, hideOccurrence);
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

    /** Touches only the correctable columns (never raw_capture_id, captured_at, created_at, visibility). */
    @Query(
        "UPDATE activity_occurrences SET canonical_activity_id = :canonicalActivityId, " +
            "occurred_at = :occurredAt, time_precision = :timePrecision, activity_state = :activityState, " +
            "effective_interpretation_id = :effectiveInterpretationId, updated_at = :updatedAt WHERE id = :id",
    )
    protected abstract fun updateOccurrenceCorrectableFields(
        id: String,
        canonicalActivityId: String,
        occurredAt: Long,
        timePrecision: TimePrecision,
        activityState: ActivityState,
        effectiveInterpretationId: String,
        updatedAt: Long,
    ): Int

    /** Touches only visibility_status and updated_at of one occurrence. */
    @Query("UPDATE activity_occurrences SET visibility_status = :visibility, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateOccurrenceVisibility(id: String, visibility: VisibilityStatus, updatedAt: Long): Int

    // --- transactional operations -------------------------------------------

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

        request.subjectAlias?.let { alias ->
            if (isNewAlias(alias, subject.normalizedName) && countSubjectAlias(subject.id, alias.key) == 0) {
                insertSubjectAlias(
                    SubjectAliasEntity(
                        id = idFactory.newId(),
                        subjectId = subject.id,
                        aliasText = alias.aliasText.trim(),
                        normalizedAlias = alias.key,
                        source = AliasSource.AI_CONFIRMED,
                        createdAt = request.now,
                    ),
                )
            }
        }
        request.actionAlias?.let { alias ->
            if (isNewAlias(alias, action.normalizedName) && countActionAlias(action.id, alias.key) == 0) {
                insertActionAlias(
                    ActionAliasEntity(
                        id = idFactory.newId(),
                        actionId = action.id,
                        aliasText = alias.aliasText.trim(),
                        normalizedAlias = alias.key,
                        source = AliasSource.AI_CONFIRMED,
                        createdAt = request.now,
                    ),
                )
            }
        }
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
    private class ResolvedTag(val id: String, val displayName: String, val normalizedName: String)

    private fun resolveSubject(idFactory: IdFactory, ref: TagRef, now: Long): ResolvedTag = when (ref) {
        is TagRef.Existing -> {
            val row = requireNotNull(findSubject(ref.tagId)) { "Unknown subject ${ref.tagId}" }
            require(row.status == TagStatus.ACTIVE) { "Subject ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName)
        }
        is TagRef.New -> {
            require(ref.key.isNotBlank()) { "New subject name has a blank key" }
            val found = findActiveSubjectByName(ref.key) ?: findActiveSubjectByAlias(ref.key)
            if (found != null) {
                ResolvedTag(found.id, found.displayName, found.normalizedName)
            } else {
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
                ResolvedTag(row.id, row.displayName, row.normalizedName)
            }
        }
    }

    private fun resolveAction(idFactory: IdFactory, ref: TagRef, now: Long): ResolvedTag = when (ref) {
        is TagRef.Existing -> {
            val row = requireNotNull(findAction(ref.tagId)) { "Unknown action ${ref.tagId}" }
            require(row.status == TagStatus.ACTIVE) { "Action ${row.id} is ${row.status}, not ACTIVE" }
            ResolvedTag(row.id, row.displayName, row.normalizedName)
        }
        is TagRef.New -> {
            require(ref.key.isNotBlank()) { "New action name has a blank key" }
            val found = findActiveActionByName(ref.key) ?: findActiveActionByAlias(ref.key)
            if (found != null) {
                ResolvedTag(found.id, found.displayName, found.normalizedName)
            } else {
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
                ResolvedTag(row.id, row.displayName, row.normalizedName)
            }
        }
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
            updatedAt = now,
        )
        check(updated == 1) { "Occurrence $occurrenceId was not updated" }
        return correctionId
    }
}

/**
 * The display_name cached on a newly created subject + action pair: "<subject> <action>",
 * e.g. "Furnace change filter". Only legacy (v3) screens read it; tag-aware reads (history)
 * use the tags' current names, so it is not refreshed when a tag is later renamed.
 */
internal fun pairDisplayName(subjectDisplayName: String, actionDisplayName: String): String =
    "$subjectDisplayName $actionDisplayName"

/** The only processing states [LedgerWriteDao.recordOutcome] may set. */
private val OUTCOME_STATES = setOf(
    ProcessingState.NEEDS_REVIEW,
    ProcessingState.FAILED_RETRYABLE,
    ProcessingState.FAILED_FINAL,
)
