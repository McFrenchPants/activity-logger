package com.mcfrenchpants.activityledger.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.services.ManagedTag
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.filterTags
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tags of the Tags screen. */
const val TAGS_SCREEN_TAG = "TagsScreen"
const val TAGS_LIST_TAG = "TagsList"
const val TAGS_EMPTY_TAG = "TagsEmpty"
const val TAGS_RETRY_TAG = "TagsRetry"
const val TAGS_NOTICE_TAG = "TagsNotice"
const val RENAME_DIALOG_TAG = "TagsRenameDialog"
const val RENAME_FIELD_TAG = "TagsRenameField"
const val RENAME_SAVE_TAG = "TagsRenameSave"
const val RENAME_CANCEL_TAG = "TagsRenameCancel"
const val RENAME_MERGE_INSTEAD_TAG = "TagsRenameMergeInstead"
const val MERGE_CHOOSER_TAG = "TagsMergeChooser"
const val MERGE_CHOOSER_SEARCH_TAG = "TagsMergeChooserSearch"
const val MERGE_CONFIRM_DIALOG_TAG = "TagsMergeConfirmDialog"
const val MERGE_CONFIRM_TAG = "TagsMergeConfirm"
const val MERGE_CANCEL_TAG = "TagsMergeCancel"

/** Test tag of the selector option for [kind]. */
fun tagsKindTag(kind: TagKind): String = "TagsKind:${kind.name}"

/** Test tag of the row of tag [tagId]. */
fun tagRowTag(tagId: String): String = "TagRow:$tagId"

/** Test tag of the Rename button of tag [tagId]. */
fun tagRenameTag(tagId: String): String = "TagRename:$tagId"

/** Test tag of the "Merge into..." button of tag [tagId]. */
fun tagMergeTag(tagId: String): String = "TagMerge:$tagId"

/** Test tag of the chooser row for target [tagId]. */
fun mergeTargetTag(tagId: String): String = "TagMergeTarget:$tagId"

/** At most this many other names are listed on a row; the rest are counted. */
private const val MAX_SHOWN_NAMES = 3

/**
 * The Tags destination (ADR-048): the owner's subjects and actions, each with Rename and
 * "Merge into...". Renaming and merging only happen through the dialogs below.
 */
@Composable
fun TagsScreen(
    modifier: Modifier = Modifier,
    viewModel: TagsViewModel = defaultTagsViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { }
    }

    TagsContent(
        state = state,
        onSelectKind = viewModel::selectKind,
        onRename = viewModel::openRename,
        onMerge = viewModel::openMerge,
        onRetry = viewModel::onStart,
        modifier = modifier,
    )
    TagsDialogs(state, viewModel)
}

@Composable
private fun defaultTagsViewModel(): TagsViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = TagsViewModelFactory(application))
}

/** The Tags screen's stateless body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsContent(
    state: TagsUiState,
    onSelectKind: (TagKind) -> Unit,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subjectKind = state.kind == TagKind.SUBJECT
    val selectorLabel = stringResource(R.string.tags_kind_selector_a11y)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAGS_SCREEN_TAG)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_tags),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(top = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = selectorLabel },
        ) {
            val kinds = listOf(
                TagKind.SUBJECT to R.string.tags_kind_subjects,
                TagKind.ACTION to R.string.tags_kind_actions,
            )
            kinds.forEachIndexed { index, (kind, label) ->
                SegmentedButton(
                    selected = state.kind == kind,
                    onClick = { onSelectKind(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(tagsKindTag(kind)),
                ) {
                    Text(stringResource(label))
                }
            }
        }

        if (state.notice != null && !state.dialogOpen) {
            val text = when (val notice = state.notice) {
                TagsNotice.Renamed -> stringResource(R.string.tags_renamed)
                is TagsNotice.Merged ->
                    pluralStringResource(R.plurals.tags_merged, notice.movedEntries, notice.movedEntries)
                null -> ""
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .testTag(TAGS_NOTICE_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!state.dialogOpen) {
            state.message?.let { ScreenMessage(it) }
        }
        if (state.loadFailed) {
            Text(
                text = stringResource(R.string.log_tags_not_loaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(TAGS_RETRY_TAG)) {
                Text(stringResource(R.string.tags_retry))
            }
        }

        val tags = state.tags
        if (state.loaded && tags.isEmpty()) {
            Text(
                text = stringResource(if (subjectKind) R.string.tags_empty_subjects else R.string.tags_empty_actions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp).testTag(TAGS_EMPTY_TAG),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAGS_LIST_TAG),
        ) {
            items(tags, key = { it.id }) { tag ->
                TagRow(tag, state.kind, enabled = !state.actionInFlight, onRename = onRename, onMerge = onMerge)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ScreenMessage(message: UserMessage) {
    Text(
        text = message.resolve(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TagRow(
    tag: ManagedTag,
    kind: TagKind,
    enabled: Boolean,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
) {
    val usedWith = pluralStringResource(
        if (kind == TagKind.SUBJECT) R.plurals.tags_used_with_actions else R.plurals.tags_used_with_subjects,
        tag.pairCount,
        tag.pairCount,
    )
    val alsoCalled = alsoCalledText(tag.aliases)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tagRowTag(tag.id))) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(text = tag.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = usedWith,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (alsoCalled != null) {
                Text(
                    text = alsoCalled,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val renameLabel = stringResource(R.string.tags_rename_a11y, tag.name)
        val mergeLabel = stringResource(R.string.tags_merge_a11y, tag.name)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onRename(tag.id) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(tagRenameTag(tag.id))
                    .semantics { contentDescription = renameLabel },
            ) { Text(stringResource(R.string.tags_rename)) }
            TextButton(
                onClick = { onMerge(tag.id) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(tagMergeTag(tag.id))
                    .semantics { contentDescription = mergeLabel },
            ) { Text(stringResource(R.string.tags_merge_into)) }
        }
    }
}

/** "Also called: a, b, c +N more", or null when the tag has no other names. */
@Composable
private fun alsoCalledText(aliases: List<String>): String? {
    if (aliases.isEmpty()) return null
    val shown = aliases.take(MAX_SHOWN_NAMES)
    val base = stringResource(R.string.tags_also_called, shown.joinToString(", "))
    val extra = aliases.size - shown.size
    return if (extra > 0) "$base ${pluralStringResource(R.plurals.tags_more_names, extra, extra)}" else base
}

/** The rename dialog, the merge chooser and the merge confirmation, whichever is open. */
@Composable
internal fun TagsDialogs(state: TagsUiState, viewModel: TagsViewModel) {
    val rename = state.rename
    val chooser = state.chooser
    val confirm = state.confirm
    if (rename != null) {
        RenameDialogView(
            dialog = rename,
            kind = state.kind,
            message = state.message,
            enabled = !state.actionInFlight,
            onText = viewModel::onRenameText,
            onSave = viewModel::saveRename,
            onMergeInstead = viewModel::mergeInsteadOfRename,
            onCancel = viewModel::cancelRename,
        )
    } else if (chooser != null) {
        MergeChooserSheet(
            chooser = chooser,
            kind = state.kind,
            tags = state.tags.filter { it.id != chooser.from.id },
            onChoose = viewModel::chooseMergeTarget,
            onDismiss = viewModel::cancelMergeChooser,
        )
    } else if (confirm != null) {
        MergeConfirmDialog(
            confirm = confirm,
            message = state.message,
            enabled = !state.actionInFlight,
            onMerge = viewModel::confirmMerge,
            onCancel = viewModel::cancelMerge,
        )
    }
}

@Composable
private fun RenameDialogView(
    dialog: RenameDialog,
    kind: TagKind,
    message: UserMessage?,
    enabled: Boolean,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onMergeInstead: () -> Unit,
    onCancel: () -> Unit,
) {
    val subject = kind == TagKind.SUBJECT
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(RENAME_DIALOG_TAG),
        title = {
            Text(stringResource(if (subject) R.string.tags_rename_title_subject else R.string.tags_rename_title_action))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.text,
                    onValueChange = onText,
                    label = { Text(stringResource(R.string.tags_rename_field)) },
                    singleLine = true,
                    shape = LedgerShapes.field,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(RENAME_FIELD_TAG),
                )
                dialog.conflict?.let { other ->
                    Text(
                        text = stringResource(
                            if (subject) R.string.tags_name_in_use_subject else R.string.tags_name_in_use_action,
                            other.name,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(
                        onClick = onMergeInstead,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(RENAME_MERGE_INSTEAD_TAG),
                    ) { Text(stringResource(R.string.tags_merge_instead)) }
                }
                message?.let { ScreenMessage(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = enabled && dialog.canSave,
                modifier = Modifier.heightIn(min = 48.dp).testTag(RENAME_SAVE_TAG),
            ) { Text(stringResource(R.string.tags_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(RENAME_CANCEL_TAG)) {
                Text(stringResource(R.string.tags_cancel))
            }
        },
    )
}

@Composable
private fun MergeConfirmDialog(
    confirm: MergeConfirm,
    message: UserMessage?,
    enabled: Boolean,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(MERGE_CONFIRM_DIALOG_TAG),
        title = { Text(stringResource(R.string.tags_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tags_confirm_body, confirm.from.name, confirm.into.name))
                message?.let { ScreenMessage(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onMerge,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag(MERGE_CONFIRM_TAG),
            ) { Text(stringResource(R.string.tags_confirm_merge)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(MERGE_CANCEL_TAG)) {
                Text(stringResource(R.string.tags_cancel))
            }
        },
    )
}

/**
 * The "Merge into..." chooser: a sheet with a search box over the OTHER tags of the same kind.
 * There is deliberately no "new name" row. Choosing only reports the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeChooserSheet(
    chooser: MergeChooser,
    kind: TagKind,
    tags: List<ManagedTag>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        var query by rememberSaveable { mutableStateOf("") }
        val known = remember(tags, kind) { tags.map { KnownTag(it.id, kind, it.name, it.aliases) } }
        val matches = remember(known, query) { filterTags(known, query) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MERGE_CHOOSER_TAG)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tags_chooser_title, chooser.from.name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (tags.isEmpty()) {
                Text(
                    text = stringResource(R.string.tags_chooser_nothing_else),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = {
                        Text(
                            stringResource(
                                if (kind == TagKind.SUBJECT) R.string.tags_chooser_search_subject else R.string.tags_chooser_search_action,
                            ),
                        )
                    },
                    singleLine = true,
                    shape = LedgerShapes.field,
                    modifier = Modifier.fillMaxWidth().testTag(MERGE_CHOOSER_SEARCH_TAG),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(matches, key = { it.id }) { tag ->
                        Text(
                            text = tag.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(mergeTargetTag(tag.id))
                                .clickable(role = Role.Button) { onChoose(tag.id) }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                    if (matches.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.tags_chooser_no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
