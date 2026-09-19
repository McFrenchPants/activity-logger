package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tag of the picker sheet's content. */
const val ACTIVITY_PICKER_TAG = "ActivityPicker"

/**
 * The ACTIVE activities whose display name contains [query] (case-insensitive), in the given
 * order. A blank query matches everything.
 */
fun filterActivities(activities: List<CatalogActivity>, query: String): List<CatalogActivity> {
    val needle = query.trim()
    if (needle.isEmpty()) return activities
    return activities.filter { it.displayName.contains(needle, ignoreCase = true) }
}

/**
 * The shared activity picker (UX_VISUAL_SPEC D1): a modal bottom sheet with a search field over
 * [activities], the matching activities, and a "New activity" row that switches to a name field
 * (prefilled with the search text).
 *
 * Deliberately screen-agnostic: it only reports the choice through [onChoose] as an
 * [ActivityTarget]; the caller applies it (a correction, a review resolution, ...) and shows any
 * refusal. [onDismiss] means "closed without choosing".
 *
 * @param activities The ACTIVE catalog to offer (e.g. `ActivityRepository.loadCatalog()`).
 * @param startWithNewActivity Open straight on the new-activity name field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityPicker(
    activities: List<CatalogActivity>,
    onChoose: (ActivityTarget) -> Unit,
    onDismiss: () -> Unit,
    startWithNewActivity: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ActivityPickerContent(
            activities = activities,
            onChoose = onChoose,
            startWithNewActivity = startWithNewActivity,
        )
    }
}

/** The picker's body, without the sheet (also usable directly in tests). */
@Composable
fun ActivityPickerContent(
    activities: List<CatalogActivity>,
    onChoose: (ActivityTarget) -> Unit,
    startWithNewActivity: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(startWithNewActivity) }
    var newName by rememberSaveable { mutableStateOf("") }
    val matches = remember(activities, query) { filterActivities(activities, query) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ACTIVITY_PICKER_TAG)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.titleLarge)
        if (creating) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.picker_new_activity_name)) },
                singleLine = true,
                shape = LedgerShapes.field,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onChoose(ActivityTarget.New(newName)) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { creating = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_back))
                }
                Button(
                    onClick = { onChoose(ActivityTarget.New(newName)) },
                    shape = LedgerShapes.button,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.picker_create))
                }
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.picker_search)) },
                singleLine = true,
                shape = LedgerShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(matches, key = { it.id }) { activity ->
                    PickerRow(
                        text = activity.displayName,
                        onClick = { onChoose(ActivityTarget.Existing(activity.id)) },
                    )
                    HorizontalDivider()
                }
                if (matches.isEmpty() && activities.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.picker_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                item {
                    PickerRow(
                        text = stringResource(R.string.picker_new_activity),
                        emphasized = true,
                        onClick = {
                            newName = query.trim()
                            creating = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit, emphasized: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
