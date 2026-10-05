package com.mcfrenchpants.activityledger.ui.explore

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.stats.ActivityRow
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreFilter
import com.mcfrenchpants.activityledger.core.domain.stats.SubjectGroup
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.filterTags
import com.mcfrenchpants.activityledger.ui.history.HistoryEntrySheets
import com.mcfrenchpants.activityledger.ui.history.HistoryViewModel
import com.mcfrenchpants.activityledger.ui.history.HistoryViewModelFactory
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Test tag of the Explore screen's root (its one scrolling list). */
const val EXPLORE_SCREEN_TAG = "ExploreScreen"

/** Test tags of the search box and its controls. */
const val EXPLORE_INPUT_TAG = "ExploreInput"
const val EXPLORE_MIC_TAG = "ExploreMic"
const val EXPLORE_CLEAR_TEXT_TAG = "ExploreClearText"
const val EXPLORE_LISTENING_TAG = "ExploreListening"
const val EXPLORE_PARTIAL_TAG = "ExplorePartial"
const val EXPLORE_PENDING_TAG = "ExplorePending"
const val EXPLORE_SUGGESTIONS_TAG = "ExploreSuggestions"

/** Test tags of the filter row. */
const val EXPLORE_DATE_CHIP_TAG = "ExploreDateChip"
const val EXPLORE_SUBJECT_CHIP_TAG = "ExploreSubjectChip"
const val EXPLORE_ACTION_CHIP_TAG = "ExploreActionChip"
const val EXPLORE_WORDS_CHIP_TAG = "ExploreWordsChip"
const val EXPLORE_CLEAR_ALL_TAG = "ExploreClearAll"
const val EXPLORE_PICKER_TAG = "ExplorePicker"

/** Test tag of the ✕ beside the chip of [kind]. */
fun exploreChipClearTag(kind: ExploreFilterKind): String = "ExploreChipClear:${kind.name}"

/**
 * Everything the stateless [ExploreContent] can ask for. Every callback defaults to doing nothing
 * so tests pass only the ones they check.
 */
internal class ExploreCallbacks(
    val onInputChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onChooseSuggestion: (ExploreSuggestion) -> Unit = {},
    val onMicrophone: () -> Unit = {},
    val onSetRange: (DateRangeSelection) -> Unit = {},
    val onSetSubject: (String?) -> Unit = {},
    val onSetAction: (String?) -> Unit = {},
    val onClearFilter: (ExploreFilterKind) -> Unit = {},
    val onClearAll: () -> Unit = {},
    val onSetView: (ExploreView) -> Unit = {},
    val onSetEntrySort: (EntrySort) -> Unit = {},
    val onSetActivitySort: (ActivitySort) -> Unit = {},
    val onSetGroupBySubject: (Boolean) -> Unit = {},
    val onSetChartAsList: (Boolean) -> Unit = {},
    val onOpenActivity: (ActivityRow) -> Unit = {},
    val onOpenSubjectGroup: (SubjectGroup) -> Unit = {},
    val onOpenEntry: (captureId: String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * The Explore destination: one place to ask about, search and count the logged history (it
 * replaced the Ask tab). Read only: nothing here writes, except through the History screen's own
 * Edit sheet, which a tagged entry row opens exactly as on History.
 *
 * Owns the microphone permission request exactly as the Log screen does: asked on the first tap
 * only, never at launch. System Back first restores earlier filters (after a drill-down or a
 * question) before it leaves the tab.
 */
@Composable
fun ExploreScreen(
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = defaultExploreViewModel(),
    historyViewModel: HistoryViewModel = defaultHistoryViewModel(),
    canAskAgain: () -> Boolean = defaultCanAskAgain(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel, historyViewModel) {
        viewModel.onStart()
        historyViewModel.onStart()
        // A voice session must not keep listening once the screen is no longer showing.
        onStopOrDispose { viewModel.stopListening() }
    }

    // The shared Edit sheet may change or remove an entry: recount once it closes.
    val editOpen = historyState.edit != null
    var sawEditOpen by remember { mutableStateOf(false) }
    LaunchedEffect(editOpen) {
        if (editOpen) {
            sawEditOpen = true
        } else if (sawEditOpen) {
            sawEditOpen = false
            viewModel.onStart()
        }
    }

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when {
            granted -> viewModel.startListening()
            canAskAgain() -> viewModel.onMicrophonePermissionDenied()
            else -> viewModel.onMicrophonePermissionBlocked()
        }
    }

    val callbacks = remember(viewModel, historyViewModel) {
        ExploreCallbacks(
            onInputChange = viewModel::onInputChange,
            onSubmit = viewModel::submit,
            onChooseSuggestion = viewModel::chooseSuggestion,
            onMicrophone = {
                when {
                    viewModel.state.value.isListening -> viewModel.stopListening()
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> viewModel.startListening()
                    else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onSetRange = viewModel::setRange,
            onSetSubject = viewModel::setSubject,
            onSetAction = viewModel::setAction,
            onClearFilter = viewModel::clearFilter,
            onClearAll = viewModel::clearAll,
            onSetView = viewModel::setView,
            onSetEntrySort = viewModel::setEntrySort,
            onSetActivitySort = viewModel::setActivitySort,
            onSetGroupBySubject = viewModel::setGroupBySubject,
            onSetChartAsList = viewModel::setChartAsList,
            onOpenActivity = viewModel::openActivity,
            onOpenSubjectGroup = viewModel::openSubjectGroup,
            onOpenEntry = historyViewModel::openEdit,
            onDismissMessage = viewModel::dismissMessage,
            onBack = { viewModel.back() },
        )
    }

    ExploreContent(state = state, callbacks = callbacks, modifier = modifier)
    HistoryEntrySheets(viewModel = historyViewModel, state = historyState)
}

@Composable
private fun defaultCanAskAgain(): () -> Boolean {
    val activity = LocalActivity.current
    return remember(activity) {
        { activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true }
    }
}

@Composable
private fun defaultExploreViewModel(): ExploreViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = ExploreViewModelFactory(application))
}

@Composable
private fun defaultHistoryViewModel(): HistoryViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = HistoryViewModelFactory(application))
}

/**
 * The Explore screen's stateless body: one scrolling column with the search box, the filter
 * row, then the results for the current filters.
 */
@Composable
internal fun ExploreContent(
    state: ExploreUiState,
    callbacks: ExploreCallbacks,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
) {
    BackHandler(enabled = state.canGoBack) { callbacks.onBack() }
    val zone = state.zone ?: ZoneId.systemDefault()
    val now = state.now ?: Instant.now()
    var picker by rememberSaveable { mutableStateOf<TagKind?>(null) }
    var choosingRange by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(EXPLORE_SCREEN_TAG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.nav_explore),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { heading() },
            )
        }
        item(key = "search") { SearchSection(state, callbacks) }
        item(key = "filters") {
            FilterRow(
                state = state,
                locale = locale,
                callbacks = callbacks,
                onOpenPicker = { picker = it },
                onCustomRange = { choosingRange = true },
            )
        }
        exploreResults(state = state, callbacks = callbacks, zone = zone, now = now, locale = locale)
    }

    picker?.let { kind ->
        ExploreTagPicker(
            kind = kind,
            tags = if (kind == TagKind.SUBJECT) state.subjectTags else state.actionTags,
            onChoose = { tagId ->
                picker = null
                if (kind == TagKind.SUBJECT) callbacks.onSetSubject(tagId) else callbacks.onSetAction(tagId)
            },
            onDismiss = { picker = null },
        )
    }
    if (choosingRange) {
        CustomRangeDialog(
            initialStart = state.summary?.range?.start,
            initialEnd = state.summary?.range?.endInclusive,
            onConfirm = { start, end ->
                choosingRange = false
                callbacks.onSetRange(DateRangeSelection.Custom(start, end))
            },
            onDismiss = { choosingRange = false },
        )
    }
}

// ---- Search box ------------------------------------------------------------------------------

@Composable
private fun SearchSection(state: ExploreUiState, callbacks: ExploreCallbacks) {
    // After a question the box shows it; typing replaces it.
    val shown = state.input.ifEmpty { state.askedQuestion.orEmpty() }
    // Sending a question or search puts the keyboard away so the results can be seen.
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = callbacks.onInputChange,
            placeholder = { Text(stringResource(R.string.explore_search_placeholder)) },
            singleLine = true,
            shape = LedgerShapes.field,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    callbacks.onSubmit()
                },
            ),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown.isNotEmpty()) {
                        GlyphButton(
                            glyph = "✕",
                            description = stringResource(R.string.explore_clear_text),
                            // A typed text is just emptied; a shown question is put away with its answer.
                            onClick = { if (state.input.isNotEmpty()) callbacks.onInputChange("") else callbacks.onClearAll() },
                            tag = EXPLORE_CLEAR_TEXT_TAG,
                        )
                    }
                    IconButton(
                        onClick = callbacks.onMicrophone,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(EXPLORE_MIC_TAG),
                    ) {
                        Icon(
                            painter = painterResource(if (state.isListening) R.drawable.ic_mic_stop else R.drawable.ic_mic),
                            contentDescription = stringResource(
                                if (state.isListening) R.string.explore_mic_stop else R.string.explore_mic,
                            ),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EXPLORE_INPUT_TAG),
        )

        if (state.isListening) {
            Text(
                text = stringResource(R.string.explore_listening),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag(EXPLORE_LISTENING_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.partialTranscript.isNotBlank()) {
                EvidenceText(state.partialTranscript, modifier = Modifier.testTag(EXPLORE_PARTIAL_TAG))
            }
        }

        if (state.isAsking) {
            val description = stringResource(R.string.explore_pending)
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(EXPLORE_PENDING_TAG)
                    .semantics {
                        contentDescription = description
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }

        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = callbacks.onDismissMessage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.explore_dismiss))
                }
            }
        }

        if (state.input.isNotBlank() && state.suggestions.isNotEmpty()) {
            Suggestions(state.suggestions) { suggestion ->
                focusManager.clearFocus()
                callbacks.onChooseSuggestion(suggestion)
            }
        }
    }
}

@Composable
private fun Suggestions(suggestions: List<ExploreSuggestion>, onChoose: (ExploreSuggestion) -> Unit) {
    Surface(
        shape = LedgerShapes.listContainer,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(EXPLORE_SUGGESTIONS_TAG),
    ) {
        Column {
            suggestions.forEachIndexed { index, suggestion ->
                if (index > 0) HorizontalDivider()
                when (suggestion) {
                    is ExploreSuggestion.Subject -> SuggestionRow(
                        text = suggestion.name,
                        detail = suggestion.matchedAlias?.let { stringResource(R.string.explore_suggestion_alias, it) },
                        kind = stringResource(R.string.explore_suggestion_subject),
                        onClick = { onChoose(suggestion) },
                    )
                    is ExploreSuggestion.Action -> SuggestionRow(
                        text = suggestion.name,
                        detail = suggestion.matchedAlias?.let { stringResource(R.string.explore_suggestion_alias, it) },
                        kind = stringResource(R.string.explore_suggestion_action),
                        onClick = { onChoose(suggestion) },
                    )
                    is ExploreSuggestion.SearchWords -> SuggestionRow(
                        text = stringResource(R.string.explore_suggestion_search_words, suggestion.text),
                        onClick = { onChoose(suggestion) },
                    )
                    is ExploreSuggestion.Ask -> SuggestionRow(
                        text = stringResource(R.string.explore_suggestion_ask, suggestion.text),
                        onClick = { onChoose(suggestion) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit, detail: String? = null, kind: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        kind?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A 48dp button showing a text glyph (the app has no icon library), described for screen readers. */
@Composable
internal fun GlyphButton(glyph: String, description: String, onClick: () -> Unit, tag: String) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
            .semantics { contentDescription = description },
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
    }
}

// ---- Filter row ------------------------------------------------------------------------------

@Composable
private fun FilterRow(
    state: ExploreUiState,
    locale: Locale,
    callbacks: ExploreCallbacks,
    onOpenPicker: (TagKind) -> Unit,
    onCustomRange: () -> Unit,
) {
    val filter = state.filter
    val questionShown = state.askedQuestion != null || (state.answer != null && state.answer !is ExploreAnswer.Scope)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        // Pairs sit further apart than a chip and its own clear button, so each ✕ reads as part of its chip.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChipWithClear(
            clear = if (filter.range != ExploreFilter().range) {
                ChipClear(stringResource(R.string.explore_chip_clear_range), ExploreFilterKind.RANGE)
            } else {
                null
            },
            callbacks = callbacks,
        ) {
            DateChip(filter, locale, callbacks, onCustomRange)
        }

        val any = stringResource(R.string.explore_chip_any)
        val subjectName = state.subjectChipName
        ChipWithClear(
            clear = if (filter.subjectId != null) {
                ChipClear(stringResource(R.string.explore_chip_clear_subject), ExploreFilterKind.SUBJECT)
            } else {
                null
            },
            callbacks = callbacks,
        ) {
            ExploreChip(
                label = subjectName ?: stringResource(R.string.explore_chip_subject),
                selected = filter.subjectId != null,
                description = stringResource(R.string.explore_chip_subject_a11y, subjectName ?: any),
                onClick = { onOpenPicker(TagKind.SUBJECT) },
                tag = EXPLORE_SUBJECT_CHIP_TAG,
                showArrow = filter.subjectId == null,
            )
        }

        val actionName = state.actionChipName
        ChipWithClear(
            clear = if (filter.actionId != null) {
                ChipClear(stringResource(R.string.explore_chip_clear_action), ExploreFilterKind.ACTION)
            } else {
                null
            },
            callbacks = callbacks,
        ) {
            ExploreChip(
                label = actionName ?: stringResource(R.string.explore_chip_action),
                selected = filter.actionId != null,
                description = stringResource(R.string.explore_chip_action_a11y, actionName ?: any),
                onClick = { onOpenPicker(TagKind.ACTION) },
                tag = EXPLORE_ACTION_CHIP_TAG,
                showArrow = filter.actionId == null,
            )
        }

        val words = filter.words
        if (!words.isNullOrBlank()) {
            ChipWithClear(
                clear = ChipClear(stringResource(R.string.explore_chip_clear_words), ExploreFilterKind.WORDS),
                callbacks = callbacks,
            ) {
                ExploreChip(
                    label = stringResource(R.string.explore_chip_words, words),
                    selected = true,
                    description = stringResource(R.string.explore_chip_words_a11y, words),
                    // Puts the words back in the box to be changed.
                    onClick = { callbacks.onInputChange(words) },
                    tag = EXPLORE_WORDS_CHIP_TAG,
                    showArrow = false,
                )
            }
        }

        if (!filter.isDefault || questionShown) {
            TextButton(
                onClick = callbacks.onClearAll,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(EXPLORE_CLEAR_ALL_TAG),
            ) {
                Text(stringResource(R.string.explore_clear_all))
            }
        }
    }
}

private class ChipClear(val description: String, val kind: ExploreFilterKind)

/**
 * A chip with its clear button tucked against it. The button keeps its 48dp touch target but is
 * pulled in under the chip's own touch margin, so the ✕ sits right beside the chip it clears.
 */
@Composable
private fun ChipWithClear(clear: ChipClear?, callbacks: ExploreCallbacks, chip: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        chip()
        if (clear != null) {
            Box(modifier = Modifier.offset(x = -CLEAR_TUCK)) {
                GlyphButton(
                    "✕",
                    clear.description,
                    { callbacks.onClearFilter(clear.kind) },
                    exploreChipClearTag(clear.kind),
                )
            }
        }
    }
}

private val CLEAR_TUCK = 8.dp

@Composable
private fun DateChip(filter: ExploreFilter, locale: Locale, callbacks: ExploreCallbacks, onCustomRange: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = rangeChipLabel(filter.range, locale)
    Box {
        ExploreChip(
            label = label,
            selected = filter.range != ExploreFilter().range,
            description = stringResource(R.string.explore_chip_date_a11y, label),
            onClick = { open = true },
            tag = EXPLORE_DATE_CHIP_TAG,
            showArrow = true,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DateRangePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.labelRes())) },
                    onClick = {
                        open = false
                        callbacks.onSetRange(DateRangeSelection.Preset(preset))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explore_range_custom)) },
                onClick = {
                    open = false
                    onCustomRange()
                },
            )
        }
    }
}

@Composable
private fun ExploreChip(
    label: String,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    tag: String,
    showArrow: Boolean,
) {
    // M3 chips keep a 48dp touch target around their 32dp visual height.
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        trailingIcon = if (showArrow) {
            { Text("▾", modifier = Modifier.clearAndSetSemantics { }) }
        } else {
            null
        },
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = description },
    )
}

/** A read-only, searchable, single-choice list over the existing tags of [kind]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreTagPicker(kind: TagKind, tags: List<KnownTag>, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(tags, query) { filterTags(tags, query) }
    val subject = kind == TagKind.SUBJECT
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EXPLORE_PICKER_TAG)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (subject) R.string.explore_picker_subject_title else R.string.explore_picker_action_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = {
                    Text(stringResource(if (subject) R.string.explore_picker_subject_search else R.string.explore_picker_action_search))
                },
                singleLine = true,
                shape = LedgerShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.explore_picker_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(matches, key = { it.id }) { tag ->
                    Text(
                        text = tag.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onChoose(tag.id) }
                            .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Allows any day up to and including [today] (picker days are UTC midnights). */
@OptIn(ExperimentalMaterial3Api::class)
internal class UpToToday(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}

/** The Material 3 date-range picker in a dialog; both dates are inclusive local dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // The picker works in UTC midnights; the chosen days are read back the same way.
    fun millis(date: LocalDate?) = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    // History has nothing in the future, so the picker stops at today.
    val today = remember { LocalDate.now() }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = millis(initialStart),
        initialSelectedEndDateMillis = millis(initialEnd),
        yearRange = DatePickerDefaults.YearRange.first..today.year,
        selectableDates = remember(today) { UpToToday(today) },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis ?: return@TextButton
                    val end = pickerState.selectedEndDateMillis ?: start
                    onConfirm(date(start), date(end))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.explore_range_picker_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.explore_range_picker_cancel))
            }
        },
    ) {
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
    }
}
