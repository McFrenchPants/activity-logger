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
import androidx.compose.ui.platform.LocalContext
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
 * @property subjectName The CURRENT subject tag name of a tagged entry, else null (old entries).
 * @property actionName The CURRENT action tag name of a tagged entry, else null (old entries).
 * @property durationSeconds How long it took, when known (shown beside the time).
 * @property occurrenceId The occurrence behind a saved row, or null for a waiting capture.
 */
data class HistoryRowModel(
    val captureId: String,
    val activityName: String?,
    val time: String,
    val state: RowState?,
    val rawText: String,
    val subjectName: String? = null,
    val actionName: String? = null,
    val durationSeconds: Long? = null,
    val occurrenceId: String? = null,
) {
    /** True for a capture with no occurrence yet (Needs review or Not categorized). */
    val isAwaitingActivity: Boolean get() = activityName == null

    /** True for a saved entry made on the subject + action tags (it has both names). */
    val isTagged: Boolean get() = subjectName != null && actionName != null && occurrenceId != null
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
            subjectName = occurrence.subjectName,
            actionName = occurrence.actionName,
            durationSeconds = occurrence.durationSeconds,
            occurrenceId = occurrence.occurrenceId,
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
 * "Your words: ..."). A tagged entry's title is "Subject · Action" with its duration beside the
 * time; an entry from the old pipeline keeps its activity name. No source icon yet: every
 * capture so far is typed on the phone.
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
    val duration = row.durationSeconds?.let { DurationFormatter.format(LocalContext.current.resources, it) }
    val sentence = if (row.isTagged) {
        val timeAndState = if (stateLabel != null) "${row.time}, $stateLabel" else row.time
        if (duration != null) {
            stringResource(R.string.history_row_tagged_a11y_with_duration, row.subjectName!!, row.actionName!!, duration, timeAndState, words)
        } else {
            stringResource(R.string.history_row_tagged_a11y, row.subjectName!!, row.actionName!!, timeAndState, words)
        }
    } else if (row.activityName != null) {
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
            val title = if (row.isTagged) {
                stringResource(R.string.history_row_tags_title, row.subjectName!!, row.actionName!!)
            } else {
                row.activityName
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                duration?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
