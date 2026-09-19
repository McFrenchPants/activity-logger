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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.components.StateTag
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tags of the result cards. */
const val SAVED_CARD_TAG = "SavedCard"
const val NEEDS_REVIEW_CARD_TAG = "NeedsReviewCard"
const val NOT_CATEGORIZED_CARD_TAG = "NotCategorizedCard"
const val RECOGNITION_FAILED_CARD_TAG = "RecognitionFailedCard"

/**
 * Saved (UX_VISUAL_SPEC 4.1, D5): primaryContainer, "✓ {name} — {time}", the user's words, Undo
 * and Change activity, and the draining undo bar. A polite live region.
 */
@Composable
internal fun SavedCard(
    card: ResultCard.Saved,
    enabled: Boolean,
    onUndo: () -> Unit,
    onChangeActivity: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val description = stringResource(R.string.log_saved_a11y, card.activityName, card.time)
    val colors = MaterialTheme.colorScheme
    CardSurface(
        tag = SAVED_CARD_TAG,
        description = description,
        container = colors.primaryContainer,
        onTouched = onTouched,
    ) {
        Text(
            text = stringResource(R.string.log_saved_title, card.activityName, card.time),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onPrimaryContainer,
        )
        EvidenceText(card.rawText, color = colors.onPrimaryContainer)
        CardActions {
            CardTextButton(stringResource(R.string.log_undo), onUndo, enabled)
            CardTextButton(stringResource(R.string.log_change_activity), onChangeActivity, enabled)
        }
        LinearProgressIndicator(
            progress = { card.undoFractionRemaining },
            modifier = Modifier
                .fillMaxWidth()
                // The remaining time is already announced by "Undo available"; the bar is visual.
                .clearAndSetSemantics {},
        )
    }
}

/**
 * Needs review (UX_VISUAL_SPEC 4.1): reviewContainer, the state tag, the words, the reassurance,
 * suggestions, Choose another activity, Create new activity and Decide later.
 */
@Composable
internal fun NeedsReviewCard(
    card: ResultCard.NeedsReview,
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
    onChooseActivity: () -> Unit,
    onCreateActivity: () -> Unit,
    onDecideLater: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val extended = ActivityLedgerTheme.extendedColors
    CardSurface(
        tag = NEEDS_REVIEW_CARD_TAG,
        description = stringResource(R.string.log_needs_review_a11y),
        container = extended.reviewContainer,
        onTouched = onTouched,
    ) {
        StateTag(RowState.NEEDS_REVIEW)
        EvidenceText(card.rawText, color = extended.onReviewContainer)
        Text(
            text = stringResource(R.string.log_needs_review_reassurance),
            style = MaterialTheme.typography.bodyMedium,
            color = extended.onReviewContainer,
        )
        Text(
            text = stringResource(R.string.log_which_activity),
            style = MaterialTheme.typography.titleMedium,
            color = extended.onReviewContainer,
        )
        if (card.suggestions.isNotEmpty()) {
            CardActions {
                card.suggestions.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { onSuggestion(suggestion.activityId) },
                        enabled = enabled,
                        shape = LedgerShapes.button,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(suggestion.displayName)
                    }
                }
            }
        }
        CardActions {
            CardTextButton(stringResource(R.string.log_choose_another_activity), onChooseActivity, enabled)
            CardTextButton(stringResource(R.string.log_create_new_activity), onCreateActivity, enabled)
            CardTextButton(stringResource(R.string.log_decide_later), onDecideLater)
        }
    }
}

/**
 * Not categorized (UX_VISUAL_SPEC 4.1): dashed outline, the two-step indicator, the words and
 * "Saved your words, but couldn't categorize them yet." The spec's follow-up sentence about
 * later categorization is left out until that exists. Offers Choose an activity and Decide later.
 */
@Composable
internal fun NotCategorizedCard(
    card: ResultCard.NotCategorized,
    enabled: Boolean,
    onChooseActivity: () -> Unit,
    onDecideLater: () -> Unit,
    onTouched: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.outline
    CardSurface(
        tag = NOT_CATEGORIZED_CARD_TAG,
        description = stringResource(R.string.log_not_categorized_a11y),
        container = colors.surface,
        onTouched = onTouched,
        modifier = Modifier.drawBehind {
            val stroke = 1.5.dp.toPx()
            val radius = 16.dp.toPx()
            drawRoundRect(
                color = outline,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
            )
        },
    ) {
        Text(
            text = stringResource(R.string.log_not_categorized_steps),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
        )
        EvidenceText(card.rawText)
        Text(
            text = stringResource(R.string.log_not_categorized_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        CardActions {
            CardTextButton(stringResource(R.string.log_choose_an_activity), onChooseActivity, enabled)
            CardTextButton(stringResource(R.string.log_decide_later), onDecideLater)
        }
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
private fun CardTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text)
    }
}
