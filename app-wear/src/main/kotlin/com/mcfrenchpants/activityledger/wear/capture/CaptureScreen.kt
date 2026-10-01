package com.mcfrenchpants.activityledger.wear.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.mcfrenchpants.activityledger.wear.R

/**
 * The watch capture screen: one glanceable message per state.
 *
 * @param ambient true draws black, outline-only, dimmed. The real ambient callback is deferred
 *   (WC1.6); callers currently pass false.
 * @param onRetry invoked by a tap anywhere, in [CaptureUiState.Failure] only.
 */
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    ambient: Boolean,
    onRetry: () -> Unit,
) {
    val textColor = if (ambient) Color(0xFF9E9E9E) else MaterialTheme.colorScheme.onBackground
    val glyphColor = if (ambient) Color(0xFF9E9E9E) else MaterialTheme.colorScheme.primary
    val base = Modifier
        .fillMaxSize()
        .background(if (ambient) Color.Black else MaterialTheme.colorScheme.background)
    val tap = if (state is CaptureUiState.Failure) base.clickable(onClick = onRetry) else base

    Column(
        modifier = tap.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state is CaptureUiState.Listening) {
            val description = stringResource(R.string.capture_listening_indicator)
            MicGlyph(
                color = glyphColor,
                ambient = ambient,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = description },
            )
            Message(stringResource(R.string.capture_listening), textColor, 18)
            state.partial?.takeIf { it.isNotBlank() }?.let { Message(it, textColor, 14) }
        } else {
            Message(messageFor(state), textColor, 20)
        }
    }
}

@Composable
private fun messageFor(state: CaptureUiState): String = when (state) {
    is CaptureUiState.Listening -> stringResource(R.string.capture_listening)
    CaptureUiState.Queued -> stringResource(R.string.capture_queued)
    is CaptureUiState.Saved ->
        if (state.activityName != null) {
            stringResource(R.string.capture_saved_named, state.activityName)
        } else {
            stringResource(R.string.capture_saved)
        }
    CaptureUiState.NeedsReview -> stringResource(R.string.capture_needs_review)
    CaptureUiState.Failure -> stringResource(R.string.capture_failure)
    is CaptureUiState.Unavailable -> when (state.reason) {
        UnavailableReason.PERMISSION_MISSING -> stringResource(R.string.capture_permission_missing)
        UnavailableReason.NO_ENGINE -> stringResource(R.string.capture_no_engine)
    }
}

@Composable
private fun Message(text: String, color: Color, sizeSp: Int) {
    Text(
        text = text,
        color = color,
        fontSize = sizeSp.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A simple microphone drawn on a canvas: capsule, cradle arc and stand. Outline only when ambient. */
@Composable
private fun MicGlyph(color: Color, ambient: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        val capsuleSize = Size(w * 0.36f, h * 0.5f)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.32f, h * 0.04f),
            size = capsuleSize,
            cornerRadius = CornerRadius(capsuleSize.width / 2f),
            style = if (ambient) stroke else Fill,
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.2f, h * 0.22f),
            size = Size(w * 0.6f, h * 0.56f),
            style = stroke,
        )
        drawLine(color, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.94f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.94f), Offset(w * 0.66f, h * 0.94f), stroke.width, StrokeCap.Round)
    }
}
