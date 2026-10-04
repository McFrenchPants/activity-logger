package com.mcfrenchpants.activityledger.ui.log

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.DurationFormatter
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.components.StateTag
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tags of the result cards. */
const val SAVED_CARD_TAG = "SavedCard"
const val CHECK_CARD_TAG = "CheckCard"
const val RECOGNITION_FAILED_CARD_TAG = "RecognitionFailedCard"

/** Test tag of the Check card's Save button. */
const val CHECK_SAVE_TAG = "CheckSave"

/** Test tag of the Check card's "Decide later" button. */
const val CHECK_DECIDE_LATER_TAG = "CheckDecideLater"

/** Test tag of one side of the Check card ("CheckSide_SUBJECT" / "CheckSide_ACTION"). */
fun checkSideTag(kind: TagKind): String = "CheckSide_${kind.name}"

/** Test tag of the "Choose subject/action" or "Change" button of one side of the Check card. */
fun checkPickTag(kind: TagKind): String = "CheckPick_${kind.name}"

/** Test tag of the "Keep mine" option of one side of the Check card. */
fun checkKeepMineTag(kind: TagKind): String = "CheckKeepMine_${kind.name}"

/** Test tag of a close-match tag option of one side of the Check card. */
fun checkCandidateTag(kind: TagKind, tagId: String): String = "CheckCandidate_${kind.name}_$tagId"

/** Test tags of the Saved card's change buttons. */
const val SAVED_CHANGE_SUBJECT_TAG = "SavedChangeSubject"
const val SAVED_CHANGE_ACTION_TAG = "SavedChangeAction"

/**
 * Saved (UX_VISUAL_SPEC 4.1, D5): primaryContainer, "{subject} · {action} — {duration} —
 * {time}" with a tick (no duration part when there is none), the user's words, Undo, Change
 * subject and Change action, and the draining undo bar. A polite live region.
 */
@Composable
internal fun SavedCard(
    card: ResultCard.Saved,
    enabled: Boolean,
    onUndo: () -> Unit,
    onChangeSubject: () -> Unit,
    onChangeAction: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val resources = LocalContext.current.resources
    val duration = card.durationSeconds?.let { DurationFormatter.format(resources, it) }
    val title: String
    val description: String
    if (duration != null) {
        title = stringResource(R.string.log_saved_title_with_duration, card.subjectName, card.actionName, duration, card.time)
        description = stringResource(
            R.string.log_saved_tags_a11y_with_duration, card.subjectName, card.actionName, duration, card.time,
        )
    } else {
        title = stringResource(R.string.log_saved_title_no_duration, card.subjectName, card.actionName, card.time)
        description = stringResource(R.string.log_saved_tags_a11y_no_duration, card.subjectName, card.actionName, card.time)
    }
    val colors = MaterialTheme.colorScheme
    CardSurface(
        tag = SAVED_CARD_TAG,
        description = description,
        container = colors.primaryContainer,
        onTouched = onTouched,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onPrimaryContainer,
        )
        EvidenceText(card.rawText, color = colors.onPrimaryContainer)
        CardActions {
            CardTextButton(stringResource(R.string.log_undo), onUndo, enabled)
            CardTextButton(
                stringResource(R.string.log_change_subject), onChangeSubject, enabled, Modifier.testTag(SAVED_CHANGE_SUBJECT_TAG),
            )
            CardTextButton(
                stringResource(R.string.log_change_action), onChangeAction, enabled, Modifier.testTag(SAVED_CHANGE_ACTION_TAG),
            )
        }
        LinearProgressIndicator(
            progress = { card.undoFractionRemaining },
            modifier = Modifier
                .fillMaxWidth()
                // The remaining time is already announced by "Undo available"; the bar is visual.
                .clearAndSetSemantics {},
            // No stop-indicator dot at the bar's end: a draining bar needs no target marker.
            drawStopIndicator = {},
        )
    }
}

/**
 * The Check card (UX_VISUAL_SPEC 4.1, 6): reviewContainer, the state tag, the owner's words, the
 * reassurance, then two sides -- Subject and Action. Nothing is saved until Save, and Save needs
 * both sides chosen. One card serves a close match, a needs-review or rejected result, and an
 * unavailable AI (both sides blank).
 */
@Composable
internal fun CheckCard(
    card: ResultCard.Check,
    enabled: Boolean,
    onChooseCandidate: (TagKind, String) -> Unit,
    onKeepMine: (TagKind) -> Unit,
    onPick: (TagKind) -> Unit,
    onSave: () -> Unit,
    onDecideLater: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val extended = ActivityLedgerTheme.extendedColors
    CardSurface(
        tag = CHECK_CARD_TAG,
        description = stringResource(R.string.log_check_a11y),
        container = extended.reviewContainer,
        onTouched = onTouched,
    ) {
        StateTag(RowState.NEEDS_REVIEW)
        Text(
            text = stringResource(R.string.log_check_title),
            style = MaterialTheme.typography.titleMedium,
            color = extended.onReviewContainer,
        )
        EvidenceText(card.rawText, color = extended.onReviewContainer)
        Text(
            text = stringResource(R.string.log_needs_review_reassurance),
            style = MaterialTheme.typography.bodyMedium,
            color = extended.onReviewContainer,
        )
        CheckSideSection(card.subject, enabled, onChooseCandidate, onKeepMine, onPick, extended.onReviewContainer)
        CheckSideSection(card.action, enabled, onChooseCandidate, onKeepMine, onPick, extended.onReviewContainer)
        card.durationSeconds?.let { seconds ->
            Text(
                text = stringResource(
                    R.string.log_check_duration,
                    DurationFormatter.format(LocalContext.current.resources, seconds),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = extended.onReviewContainer,
            )
        }
        CardActions {
            Button(
                onClick = onSave,
                enabled = enabled && card.canSave,
                shape = LedgerShapes.button,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CHECK_SAVE_TAG),
            ) {
                Text(stringResource(R.string.log_check_save))
            }
            CardTextButton(
                stringResource(R.string.log_decide_later), onDecideLater, modifier = Modifier.testTag(CHECK_DECIDE_LATER_TAG),
            )
        }
    }
}

@Composable
private fun CheckSideSection(
    side: CheckSide,
    enabled: Boolean,
    onChooseCandidate: (TagKind, String) -> Unit,
    onKeepMine: (TagKind) -> Unit,
    onPick: (TagKind) -> Unit,
    color: Color,
) {
    val subject = side.kind == TagKind.SUBJECT
    val label = stringResource(if (subject) R.string.log_check_subject_label else R.string.log_check_action_label)
    val chosen = side.chosen
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(checkSideTag(side.kind)),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
        if (chosen != null) {
            Text(text = chosen.name, style = MaterialTheme.typography.titleMedium, color = color)
            if (chosen.choice is TagChoice.New) {
                Text(
                    text = stringResource(R.string.log_check_new_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
            if (side.assumed) {
                Text(
                    text = stringResource(R.string.log_check_assumed),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.log_check_nothing_chosen),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        side.words?.takeIf { it.isNotBlank() && side.candidates.isNotEmpty() }?.let { EvidenceText(it, color = color) }
        if (side.candidates.isNotEmpty()) {
            Text(
                text = stringResource(
                    if (subject) R.string.log_check_did_you_mean_subject else R.string.log_check_did_you_mean_action,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            CardActions {
                side.candidates.forEach { tag ->
                    val selected = chosen?.choice == TagChoice.Existing(tag.id)
                    OptionButton(
                        text = tag.displayName,
                        selected = selected,
                        enabled = enabled,
                        onClick = { onChooseCandidate(side.kind, tag.id) },
                        modifier = Modifier.testTag(checkCandidateTag(side.kind, tag.id)),
                    )
                }
                side.keepMine?.let { mine ->
                    OptionButton(
                        text = stringResource(R.string.log_check_keep_mine, mine),
                        selected = chosen?.choice == TagChoice.New(mine),
                        enabled = enabled,
                        onClick = { onKeepMine(side.kind) },
                        modifier = Modifier.testTag(checkKeepMineTag(side.kind)),
                    )
                }
            }
        }
        CardTextButton(
            text = stringResource(
                when {
                    chosen != null -> R.string.log_check_change
                    subject -> R.string.log_check_choose_subject
                    else -> R.string.log_check_choose_action
                },
            ),
            onClick = { onPick(side.kind) },
            enabled = enabled,
            modifier = Modifier.testTag(checkPickTag(side.kind)),
        )
    }
}

/** A one-tap option; the chosen one is marked with a tick and announced as selected. */
@Composable
private fun OptionButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosenLabel = stringResource(R.string.log_check_chosen)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = LedgerShapes.button,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { if (selected) stateDescription = chosenLabel },
    ) {
        Text(if (selected) "\u2713 $text" else text)
    }
}

/**
 * Recognition failed (UX_VISUAL_SPEC 6): errorContainer, one sentence, and a way out either way.
 *
 * Nothing was heard, so nothing was captured and nothing was saved. This card therefore shows no
 * quoted words at all -- not a best guess, not a partial, not an empty pair of quotes -- which is
 * why [ResultCard.RecognitionFailed] carries no capture id and no text to begin with.
 */
@Composable
internal fun RecognitionFailedCard(
    enabled: Boolean,
    onTryAgain: () -> Unit,
    onTypeInstead: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    CardSurface(
        tag = RECOGNITION_FAILED_CARD_TAG,
        description = stringResource(R.string.log_recognition_failed_a11y),
        container = colors.errorContainer,
        onTouched = onTouched,
    ) {
        Text(
            text = stringResource(R.string.log_recognition_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
        )
        CardActions {
            CardTextButton(stringResource(R.string.log_try_again), onTryAgain, enabled)
            CardTextButton(stringResource(R.string.log_type_instead), onTypeInstead)
        }
    }
}

/**
 * A result card's frame: 16dp shape, min-height only, a polite live region announcing
 * [description], and touch tracking so the undo window can pause while the card is held.
 */
@Composable
private fun CardSurface(
    tag: String,
    description: String,
    container: Color,
    onTouched: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = container,
        shape = LedgerShapes.card,
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            }
            .pointerInput(onTouched) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onTouched(true)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onTouched(false)
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun CardTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text)
    }
}
