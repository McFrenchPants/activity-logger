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
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tag of the tag picker sheet's content. */
const val TAG_PICKER_TAG = "TagPicker"

/**
 * The tags whose display name or any alias contains [query] (case-insensitive), in the given
 * order. A blank query matches everything.
 */
fun filterTags(tags: List<KnownTag>, query: String): List<KnownTag> {
    val needle = query.trim()
    if (needle.isEmpty()) return tags
    return tags.filter { tag ->
        tag.displayName.contains(needle, ignoreCase = true) ||
            tag.aliases.any { it.contains(needle, ignoreCase = true) }
    }
}

/**
 * The shared tag picker for one [kind] (subject or action): a modal bottom sheet with a search
 * field over [tags], the matching tags by name, and a "New ..." row that switches to a name
 * field (prefilled with the search text). Large touch targets and full accessibility labels.
 *
 * Screen-agnostic: it only reports the choice through [onChoose] as a [TagChoice]; the caller
 * applies it and shows any refusal. [onDismiss] means "closed without choosing".
 *
 * @param tags The ACTIVE tags of [kind] to offer (from the tag catalog).
 * @param startWithNewName Open straight on the new-name field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPicker(
    kind: TagKind,
    tags: List<KnownTag>,
    onChoose: (TagChoice) -> Unit,
    onDismiss: () -> Unit,
    startWithNewName: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TagPickerContent(kind = kind, tags = tags, onChoose = onChoose, startWithNewName = startWithNewName)
    }
}

/** The picker's body, without the sheet (also usable directly in tests). */
@Composable
fun TagPickerContent(
    kind: TagKind,
    tags: List<KnownTag>,
    onChoose: (TagChoice) -> Unit,
    startWithNewName: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(startWithNewName) }
    var newName by rememberSaveable { mutableStateOf("") }
    val matches = remember(tags, query) { filterTags(tags, query) }
    val subject = kind == TagKind.SUBJECT

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_PICKER_TAG)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (subject) R.string.tag_picker_subject_title else R.string.tag_picker_action_title),
            style = MaterialTheme.typography.titleLarge,
        )
        if (creating) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = {
                    Text(
                        stringResource(
                            if (subject) R.string.tag_picker_new_subject_name else R.string.tag_picker_new_action_name,
                        ),
                    )
                },
                singleLine = true,
                shape = LedgerShapes.field,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) onChoose(TagChoice.New(newName)) }),
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
                    onClick = { onChoose(TagChoice.New(newName)) },
                    enabled = newName.isNotBlank(),
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
                label = {
                    Text(
                        stringResource(
                            if (subject) R.string.tag_picker_subject_search else R.string.tag_picker_action_search,
                        ),
                    )
                },
                singleLine = true,
                shape = LedgerShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(matches, key = { it.id }) { tag ->
                    TagPickerRow(text = tag.displayName, onClick = { onChoose(TagChoice.Existing(tag.id)) })
                    HorizontalDivider()
                }
                if (matches.isEmpty() && tags.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (subject) R.string.tag_picker_subject_no_matches else R.string.tag_picker_action_no_matches,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                item {
                    TagPickerRow(
                        text = stringResource(
                            if (subject) R.string.tag_picker_new_subject else R.string.tag_picker_new_action,
                        ),
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
private fun TagPickerRow(text: String, onClick: () -> Unit, emphasized: Boolean = false) {
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
