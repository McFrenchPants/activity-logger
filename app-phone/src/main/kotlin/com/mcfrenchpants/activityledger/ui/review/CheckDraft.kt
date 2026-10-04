package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.services.OccurrenceTime
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.ui.log.CheckSide
import com.mcfrenchpants.activityledger.ui.log.ChosenTag
import com.mcfrenchpants.activityledger.ui.log.ResultCard
import com.mcfrenchpants.activityledger.ui.log.TagPickerState

/**
 * The pure draft rules of the Check card, shared by the Log screen and the History screen so
 * both finish a waiting capture the same way: how a side starts, and how a tap on a candidate,
 * "Keep mine" or a picker choice changes the draft. Save needs both sides chosen
 * ([ResultCard.Check.canSave]). No I/O, no clock, no logging.
 */
internal object CheckDraft {

    /** A draft with nothing chosen on either side (the words were stored but not understood). */
    fun blank(captureId: String, rawText: String): ResultCard.Check = ResultCard.Check(
        captureId = captureId,
        rawText = rawText,
        subject = CheckSide(TagKind.SUBJECT),
        action = CheckSide(TagKind.ACTION),
    )

    /**
     * One starting side. A side that resolved exactly or as a new name starts chosen; a close
     * match offers its candidates and starts unchosen; an empty side starts unchosen.
     *
     * @param inferred True when an exact side was filled in from the owner's usual combination.
     */
    fun sideOf(kind: TagKind, words: String?, resolution: TagResolution, inferred: Boolean): CheckSide =
        when (resolution) {
            TagResolution.Empty -> CheckSide(kind, words)
            is TagResolution.Exact -> CheckSide(
                kind, words, ChosenTag(TagChoice.Existing(resolution.tag.id), resolution.tag.displayName), assumed = inferred,
            )
            is TagResolution.New ->
                CheckSide(kind, words, ChosenTag(TagChoice.New(resolution.name), resolution.name), assumed = inferred)
            is TagResolution.Near ->
                CheckSide(kind, words, candidates = resolution.candidates, keepMine = resolution.newName)
        }

    /** A draft from decided sides plus the time, duration and state to save with. */
    fun from(
        captureId: String,
        rawText: String,
        subjectWords: String?,
        actionWords: String?,
        subject: TagResolution,
        action: TagResolution,
        subjectInferred: Boolean,
        time: OccurrenceTime? = null,
        durationSeconds: Long? = null,
        activityState: ActivityState? = null,
    ): ResultCard.Check = ResultCard.Check(
        captureId = captureId,
        rawText = rawText,
        subject = sideOf(TagKind.SUBJECT, subjectWords, subject, subjectInferred),
        action = sideOf(TagKind.ACTION, actionWords, action, false),
        time = time,
        durationSeconds = durationSeconds,
        activityState = activityState,
    )

    /** The offered close-match tag [tagId] on the [kind] side as a choice, or null if not offered. */
    fun candidateChoice(card: ResultCard.Check, kind: TagKind, tagId: String): ChosenTag? =
        card.side(kind).candidates.firstOrNull { it.id == tagId }
            ?.let { ChosenTag(TagChoice.Existing(it.id), it.displayName) }

    /** "Keep mine" on the [kind] side as a choice, or null if it is not offered. */
    fun keepMineChoice(card: ResultCard.Check, kind: TagKind): ChosenTag? =
        card.side(kind).keepMine?.let { ChosenTag(TagChoice.New(it), it) }

    /**
     * What the open [picker] returning [choice] chooses (with its shown name), or null when the
     * choice cannot be shown (an unknown tag or a blank new name).
     */
    fun pickedChoice(picker: TagPickerState, choice: TagChoice): ChosenTag? {
        val name = when (choice) {
            is TagChoice.Existing -> picker.tags.firstOrNull { it.id == choice.tagId }?.displayName
            is TagChoice.New -> choice.name.trim().takeIf { it.isNotEmpty() }
        } ?: return null
        return ChosenTag(if (choice is TagChoice.New) TagChoice.New(name) else choice, name)
    }
}
