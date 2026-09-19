package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/**
 * The row-level states from the D3 state vocabulary that carry a visible tag. ("Saved" has no
 * row tag and "Queued" exists only on the watch, so neither is here.)
 */
enum class RowState {
    /** The activity is still underway (`ActivityState.IN_PROGRESS`). */
    IN_PROGRESS,

    /** The interpretation was unsafe: the words are saved, nothing was guessed. */
    NEEDS_REVIEW,

    /** Captured but not interpreted yet (on-device AI unavailable). */
    NOT_CATEGORIZED,

    /** Nothing was saved. Always shown next to a retry action. */
    CAPTURE_FAILED,
}

/**
 * A state tag: icon shape + text label + colour, so the state stays readable with colour
 * removed (UX_VISUAL_SPEC D3, section 5). The icon is decorative -- the label carries the
 * meaning for screen readers -- and the tag is one merged semantics node.
 *
 * - In progress: half-filled circle, primary.
 * - Needs review: question in circle, review on reviewContainer.
 * - Not categorized yet: dashed circle with clock, onSurfaceVariant, dashed outline.
 * - Couldn't capture: exclamation in circle, error on errorContainer.
 */
@Composable
fun StateTag(state: RowState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = ActivityLedgerTheme.extendedColors
    val (content, container) = when (state) {
        RowState.IN_PROGRESS -> colors.primary to Color.Transparent
        RowState.NEEDS_REVIEW -> extended.review to extended.reviewContainer
        RowState.NOT_CATEGORIZED -> colors.onSurfaceVariant to Color.Transparent
        RowState.CAPTURE_FAILED -> colors.error to colors.errorContainer
    }
    val labelColor = when (state) {
        RowState.NEEDS_REVIEW -> extended.onReviewContainer
        RowState.CAPTURE_FAILED -> colors.onErrorContainer
        else -> content
    }
    val label = stringResource(
        when (state) {
            RowState.IN_PROGRESS -> R.string.state_in_progress
            RowState.NEEDS_REVIEW -> R.string.state_needs_review
            RowState.NOT_CATEGORIZED -> R.string.state_not_categorized
            RowState.CAPTURE_FAILED -> R.string.state_capture_failed
        },
    )

    val outline: Modifier = when (state) {
        RowState.NOT_CATEGORIZED -> Modifier.drawBehind {
            val strokePx = 1.dp.toPx()
            val radius = 8.dp.toPx()
            drawRoundRect(
                color = content,
                topLeft = Offset(strokePx / 2, strokePx / 2),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
        RowState.IN_PROGRESS -> Modifier.border(1.dp, content, LedgerShapes.chip)
        else -> Modifier
    }

    Row(
        modifier = modifier
            .testTag("StateTag:${state.name}")
            .semantics(mergeDescendants = true) {}
            .clip(LedgerShapes.chip)
            .background(container)
            .then(outline)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateIcon(state = state, color = content, modifier = Modifier.size(16.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = labelColor)
    }
}

/** The state's icon shape, drawn in [color]. Decorative: no content description. */
@Composable
private fun StateIcon(state: RowState, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val radius = size.minDimension / 2 - stroke / 2
        when (state) {
            RowState.IN_PROGRESS -> {
                drawCircle(color, radius, style = Stroke(stroke))
                // Left half filled.
                drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
            }
            RowState.NEEDS_REVIEW -> {
                drawCircle(color, radius, style = Stroke(stroke))
                drawQuestionMark(color, stroke)
            }
            RowState.NOT_CATEGORIZED -> {
                val dash = (2 * Math.PI.toFloat() * radius) / 16f
                drawCircle(
                    color,
                    radius,
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
                // Clock hands.
                drawLine(color, center, Offset(center.x, center.y - radius * 0.55f), stroke, StrokeCap.Round)
                drawLine(color, center, Offset(center.x + radius * 0.4f, center.y), stroke, StrokeCap.Round)
            }
            RowState.CAPTURE_FAILED -> {
                drawCircle(color, radius, style = Stroke(stroke))
                drawLine(
                    color,
                    Offset(center.x, center.y - radius * 0.5f),
                    Offset(center.x, center.y + radius * 0.1f),
                    stroke * 1.2f,
                    StrokeCap.Round,
                )
                drawCircle(color, stroke * 0.7f, Offset(center.x, center.y + radius * 0.48f))
            }
        }
    }
}

private fun DrawScope.drawQuestionMark(color: Color, stroke: Float) {
    val r = size.minDimension * 0.16f
    val arcCenter = Offset(center.x, center.y - size.minDimension * 0.1f)
    drawArc(
        color,
        startAngle = 180f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color,
        Offset(center.x, arcCenter.y + r),
        Offset(center.x, center.y + size.minDimension * 0.12f),
        stroke,
        StrokeCap.Round,
    )
    drawCircle(color, stroke * 0.7f, Offset(center.x, center.y + size.minDimension * 0.27f))
}
