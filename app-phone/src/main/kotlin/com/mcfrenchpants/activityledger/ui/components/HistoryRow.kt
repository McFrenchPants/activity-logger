package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.ui.time.OccurrenceTimeFormatter
import java.time.Instant
import java.util.Locale

/**
 * One history row (UX_VISUAL_SPEC 4.2), shared by Log's Recent and the History screen.
 *
 * @property activityName The current activity's name, or null for an uninterpreted capture.
 * @property time The occurrence time (or, for an uninterpreted capture, the capture time),
 *   already formatted.
 * @property state The row's state tag, or null when none applies.
 */
data class HistoryRowModel(
    val captureId: String,
    val activityName: String?,
    val time: String,
    val state: RowState?,
    val rawText: String,
) {
    /** True for a capture with no occurrence yet (Needs review or Not categorized). */
    val isAwaitingActivity: Boolean get() = activityName == null
}

/**
 * The one mapping from a [HistoryEntry] to its row. An occurrence shows its activity, its own
 * time and precision, and "In progress" when underway; a capture without one shows "Needs
 * review" (processing state NEEDS_REVIEW) or "Not categorized yet" (any other state) and its
 * capture moment -- the only time known for words not yet logged.
 */
fun HistoryEntry.toHistoryRow(now: Instant, locale: Locale): HistoryRowModel {
    val occurrence = occurrence
    return if (occurrence != null) {
        HistoryRowModel(
            captureId = captureId,
            activityName = occurrence.activityDisplayName,
            time = OccurrenceTimeFormatter.format(
                occurrence.occurredAt, occurrence.timePrecision, zoneId, now, locale,
            ),
            state = if (occurrence.activityState == ActivityState.IN_PROGRESS) RowState.IN_PROGRESS else null,
            rawText = rawText,
        )
    } else {
        HistoryRowModel(
            captureId = captureId,
            activityName = null,
            time = OccurrenceTimeFormatter.format(capturedAt, TimePrecision.EXACT, zoneId, now, locale),
            state = if (processingState == ProcessingState.NEEDS_REVIEW) {
                RowState.NEEDS_REVIEW
            } else {
                RowState.NOT_CATEGORIZED
            },
            rawText = rawText,
        )
    }
}

/**
 * One row per UX_VISUAL_SPEC 4.2, read by screen readers as one sentence (name or state, time,
 * "Your words: ..."). No source icon yet: every capture so far is typed on the phone.
 *
 * With [onClick] the row is a button whose action is announced as [onClickLabel]; without it
 * the row is plain text and does not look or act clickable.
 */
@Composable
fun HistoryRow(
    row: HistoryRowModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val words = stringResource(R.string.log_row_your_words, row.rawText)
    val stateLabel = row.state?.let { stringResource(it.labelRes()) }
    val sentence = if (row.activityName != null) {
        val time = if (stateLabel != null) "${row.time}, $stateLabel" else row.time
        stringResource(R.string.log_row_interpreted_a11y, row.activityName, time, words)
    } else {
        stringResource(R.string.log_row_uninterpreted_a11y, stateLabel.orEmpty(), row.time, words)
    }
    val interaction = if (onClick != null) {
        Modifier
            .clearAndSetSemantics {
                contentDescription = sentence
                role = Role.Button
                onClick(label = onClickLabel) { onClick(); true }
            }
            // Pointer handling only: its semantics are already replaced above.
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier.clearAndSetSemantics { contentDescription = sentence }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(interaction)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (row.activityName != null) {
            Text(row.activityName, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.state?.let { StateTag(it) }
            }
            EvidenceText(row.rawText)
        } else {
            row.state?.let { StateTag(it) }
            EvidenceText(row.rawText)
            Text(row.time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
