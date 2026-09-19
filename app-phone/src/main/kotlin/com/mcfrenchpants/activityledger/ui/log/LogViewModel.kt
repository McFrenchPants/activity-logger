package com.mcfrenchpants.activityledger.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.domain.services.CorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.services.CorrectionResult
import com.mcfrenchpants.activityledger.core.domain.services.CorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.ResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.domain.services.ServiceRefusal
import com.mcfrenchpants.activityledger.ui.components.toHistoryRow
import com.mcfrenchpants.activityledger.ui.review.PickerState
import com.mcfrenchpants.activityledger.ui.review.ReviewSuggestions
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.refusalMessage
import com.mcfrenchpants.activityledger.ui.review.refusalMessageFor
import com.mcfrenchpants.activityledger.ui.time.OccurrenceTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

/** `sourceSurface` recorded on raw captures typed on the Log screen. */
const val LOG_TYPED_SOURCE_SURFACE: String = "log_typed"

/** D5's undo window before accessibility adjustment. */
const val DEFAULT_UNDO_TIMEOUT_MILLIS: Long = 8_000L

/** How often the undo window's draining bar is advanced. */
const val UNDO_TICK_MILLIS: Long = 100L

/** Most rows shown under "Recent". */
private const val RECENT_ROWS = 3

/**
 * State holder of the Log screen: typed capture through the real capture pipeline, the result
 * cards, the shared activity picker and the Recent list.
 *
 * Built from plain collaborators so it runs on the JVM without Android ([LogViewModelFactory]
 * wires the real ones; ADR-032, no DI framework).
 *
 * Rules it keeps:
 * - Typed words become an immutable raw capture first, then go through [orchestrator] only.
 *   Each [CaptureProcessingOutcome] maps to exactly one card; the outcome's reasons are never
 *   inspected (ADR-010).
 * - Submitting is only possible while the screen is started (ADR-029). Work already running
 *   lives in [viewModelScope], so neither backgrounding nor rotation cancels it, and its card is
 *   there when the user comes back.
 * - The Saved card's undo window (UX_VISUAL_SPEC D5) drains in [UNDO_TICK_MILLIS] steps on the
 *   coroutine clock, and pauses while the card is touched, the picker is open or the screen is
 *   stopped. Running out only dismisses the card; the occurrence stays saved.
 * - The AI-readiness check is asked once per start and never downloads anything.
 * - Nothing is logged (AGENTS.md #11).
 *
 * @param isAiReady Whether on-device interpretation can run now. Must be cheap and must never
 *   start a download.
 * @param zone The zone recorded on new captures.
 * @param locale The locale times are formatted in.
 */
class LogViewModel(
    private val repository: ActivityRepository,
    private val orchestrator: CaptureInterpretationOrchestrator,
    private val reviewResolutionService: ReviewResolutionService,
    private val correctionService: CorrectionService,
    private val clock: Clock,
    private val isAiReady: suspend () -> Boolean,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale = { Locale.getDefault() },
) : ViewModel() {

    private val _state = MutableStateFlow(LogUiState())

    /** The screen's single UI state. */
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    private var undoTimeoutMillis: Long = DEFAULT_UNDO_TIMEOUT_MILLIS
    private var undoElapsedMillis: Long = 0L
    private var undoJob: Job? = null
    private var cardTouched = false

    // ---- Lifecycle (ADR-029) --------------------------------------------------------------

    /** The Log screen came to the foreground. Reloads Recent and asks the AI-readiness check once. */
    fun onStart() {
        _state.update { it.copy(isStarted = true) }
        viewModelScope.launch { reloadRecent() }
        viewModelScope.launch {
            val ready = try {
                isAiReady()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                false
            }
            _state.update { it.copy(showAiNotReady = !ready) }
        }
    }

    /** The Log screen went to the background. Nothing running is cancelled; submit is blocked. */
    fun onStop() {
        _state.update { it.copy(isStarted = false) }
    }

    /** The user left the Log destination: the Saved card is dismissed (the occurrence stays). */
    fun onLeftLog() {
        if (_state.value.card is ResultCard.Saved) dismissCard()
    }

    // ---- Capture ------------------------------------------------------------------------

    /** The capture field changed. */
    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    /** Stores the typed words as a raw capture and runs them through the pipeline. */
    fun submit() {
        val current = _state.value
        if (!current.isStarted || current.isCapturing || current.isListening) return
        val text = current.input.trim()
        if (text.isEmpty()) return

        dismissCard()
        _state.update { it.copy(input = "", isCapturing = true, message = null) }
        viewModelScope.launch {
            val captureId = try {
                repository.createRawCapture(
                    NewRawCapture(
                        source = CaptureSource.PHONE_TEXT,
                        sourceSurface = LOG_TYPED_SOURCE_SURFACE,
                        capturedAt = clock.instant(),
                        zoneId = zone(),
                        rawText = text,
                        speechConfidence = null,
                        speechAlternativesJson = null,
                        processingState = ProcessingState.CAPTURED,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                // Nothing was saved: give the words back so nothing is lost.
                _state.update {
                    it.copy(
                        isCapturing = false,
                        input = it.input.ifEmpty { text },
                        message = UserMessage(R.string.log_capture_not_saved),
                    )
                }
                return@launch
            }

            val card = try {
                cardFor(captureId, text, orchestrator.process(captureId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                // The words are stored, but no outcome was recorded: that is exactly
                // "saved your words, but couldn't categorize them yet".
                ResultCard.NotCategorized(captureId, text)
            }
            _state.update { it.copy(isCapturing = false) }
            showCard(card)
            reloadRecent()
        }
    }

    /** The one mapping from pipeline outcome to card. Reasons are never inspected (ADR-010). */
    private suspend fun cardFor(captureId: String, rawText: String, outcome: CaptureProcessingOutcome): ResultCard? =
        when (outcome) {
            is CaptureProcessingOutcome.AutoAccepted -> savedCard(outcome.occurrenceId)
            CaptureProcessingOutcome.AlreadyHasOccurrence ->
                // If that occurrence is not in history (e.g. hidden), show no card and refresh Recent.
                existingOccurrenceId(captureId)?.let { savedCard(it) } ?: run { reloadRecent(); null }
            is CaptureProcessingOutcome.NeedsReview,
            is CaptureProcessingOutcome.Rejected,
            -> ResultCard.NeedsReview(captureId, rawText, suggestionsFor(captureId, rawText))
            is CaptureProcessingOutcome.InterpreterUnavailable -> ResultCard.NotCategorized(captureId, rawText)
        }

    private suspend fun existingOccurrenceId(captureId: String): String? =
        repository.loadHistory().firstOrNull { it.captureId == captureId }?.occurrence?.occurrenceId

    // ---- Saved card ---------------------------------------------------------------------

    /** Hides the saved occurrence (Undo). The raw capture and its interpretations are kept. */
    fun undo() {
        val card = _state.value.card as? ResultCard.Saved ?: return
        runAction {
            try {
                repository.hideOccurrence(card.occurrenceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: IllegalArgumentException) {
                // The repository no longer knows the occurrence: keep the card, say so.
                _state.update { it.copy(message = refusalMessage(ServiceRefusal.OccurrenceNotFound)) }
                return@runAction
            }
            // Dismissed only once the hide is stored, so a failed Undo leaves the card in place.
            if ((_state.value.card as? ResultCard.Saved)?.occurrenceId == card.occurrenceId) dismissCard()
            reloadRecent()
        }
    }

    /** Moves the saved occurrence to [target] as a user correction. The undo window keeps running. */
    fun changeActivity(target: ActivityTarget) {
        val card = _state.value.card as? ResultCard.Saved ?: return
        runAction(closePicker = true) {
            when (val result = correctionService.correct(card.occurrenceId, CorrectionRequest(activity = target))) {
                is CorrectionResult.Applied -> {
                    val refreshed = savedCard(card.occurrenceId)
                    _state.update { current ->
                        val shown = current.card as? ResultCard.Saved
                        if (refreshed != null && shown?.occurrenceId == card.occurrenceId) {
                            current.copy(
                                card = shown.copy(activityName = refreshed.activityName, time = refreshed.time),
                            )
                        } else {
                            current
                        }
                    }
                    reloadRecent()
                }
                CorrectionResult.NothingChanged -> Unit
                is CorrectionResult.Refused -> showRefusal(result.refusal)
            }
        }
    }

    /** Sets the undo window length (D5: the accessibility-recommended timeout for 8 s). */
    fun setUndoTimeoutMillis(timeoutMillis: Long) {
        undoTimeoutMillis = timeoutMillis.coerceAtLeast(UNDO_TICK_MILLIS)
        publishUndoProgress()
    }

    /** Whether the user is touching the result card; the undo window pauses meanwhile. */
    fun setCardTouched(touched: Boolean) {
        cardTouched = touched
    }

    private fun startUndoWindow() {
        undoJob?.cancel()
        undoElapsedMillis = 0L
        undoJob = viewModelScope.launch {
            while (undoElapsedMillis < undoTimeoutMillis) {
                delay(UNDO_TICK_MILLIS)
                if (!undoPaused()) {
                    undoElapsedMillis += UNDO_TICK_MILLIS
                    publishUndoProgress()
                }
            }
            undoJob = null
            if (_state.value.card is ResultCard.Saved) dismissCard()
        }
    }

    private fun undoPaused(): Boolean {
        val current = _state.value
        return cardTouched || current.picker != null || !current.isStarted
    }

    private fun publishUndoProgress() {
        val remaining = (1f - undoElapsedMillis.toFloat() / undoTimeoutMillis.toFloat()).coerceIn(0f, 1f)
        _state.update { current ->
            val saved = current.card as? ResultCard.Saved ?: return@update current
            current.copy(card = saved.copy(undoFractionRemaining = remaining))
        }
    }

    // ---- Needs review / Not categorized -------------------------------------------------

    /** Logs the card's capture as [target] via [ReviewResolutionService]. */
    fun resolve(target: ActivityTarget) {
        // Only an unresolved card has a capture waiting for an activity; the type says so.
        val card = _state.value.card as? ResultCard.Unresolved ?: return
        runAction(closePicker = true) {
            when (val result = reviewResolutionService.resolve(card.captureId, target)) {
                is ResolutionResult.Resolved -> {
                    showCard(savedCard(result.occurrenceId))
                    reloadRecent()
                }
                is ResolutionResult.Refused -> showRefusal(result.refusal)
            }
        }
    }

    /** Dismisses the card; the capture stays waiting for review (reachable from History). */
    fun decideLater() {
        if (_state.value.card !is ResultCard.Unresolved) return
        dismissCard()
    }

    // ---- Voice capture (VC1.3: state only) ----------------------------------------------

    // These four methods own the listening state and nothing else. VC1.4 replaces their bodies
    // with real transcriber calls; deliberately nothing here knows about speech recognition, so
    // the screen's listening behaviour is unit-testable on the JVM today.

    /**
     * The user asked to log by voice: dismisses any card, clears the last partial and blocks
     * typed submit while listening. Ignored while stopped or while a capture is being processed.
     */
    fun startListening() {
        val current = _state.value
        if (!current.canUseMicrophone || current.isListening) return
        dismissCard()
        _state.update { it.copy(isListening = true, partialTranscript = "", message = null) }
    }

    /** The user (or, later, the recognizer) ended listening. Typing is available again. */
    fun stopListening() {
        if (!_state.value.isListening) return
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }

    /** "Try again" on the recognition-failure card: drops the card and listens again. */
    fun retryListening() {
        if (_state.value.card !is ResultCard.RecognitionFailed) return
        dismissCard()
        startListening()
    }

    /** "Type instead": drops the recognition-failure card so only the capture field is left. */
    fun dismissRecognitionFailure() {
        if (_state.value.card !is ResultCard.RecognitionFailed) return
        dismissCard()
    }

    /**
     * The words heard so far. Never logged and never stored -- it is shown, then replaced
     * (AGENTS.md #11). Ignored when nothing is listening.
     */
    fun onPartialTranscript(text: String) {
        if (!_state.value.isListening) return
        _state.update { it.copy(partialTranscript = text) }
    }

    /** Nothing usable was heard: stops listening and shows the recognition-failure card. */
    fun showRecognitionFailure() {
        _state.update { it.copy(isListening = false, partialTranscript = "") }
        showCard(ResultCard.RecognitionFailed)
    }

    /**
     * The microphone permission was refused this time. Says so in plain words and leaves the
     * capture field exactly as it was, so typing is still available.
     */
    fun onMicrophonePermissionDenied() {
        _state.update {
            it.copy(
                isListening = false,
                partialTranscript = "",
                message = UserMessage(R.string.log_mic_permission_denied),
            )
        }
    }

    /** The microphone permission can no longer be asked for. Same rule: typing stays available. */
    fun onMicrophonePermissionBlocked() {
        _state.update {
            it.copy(
                isListening = false,
                partialTranscript = "",
                message = UserMessage(R.string.log_mic_permission_blocked),
            )
        }
    }

    // ---- Picker -------------------------------------------------------------------------

    /** Opens the activity picker over the ACTIVE catalog (optionally on the new-name field). */
    fun openPicker(startWithNewActivity: Boolean = false) {
        if (_state.value.card == null) return
        runAction(failure = UserMessage(R.string.log_activities_not_loaded)) {
            val catalog = repository.loadCatalog()
            _state.update {
                if (it.card == null) it else it.copy(picker = PickerState(catalog, startWithNewActivity))
            }
        }
    }

    /** The picker was closed without a choice. */
    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /** The picker returned [target]: a correction for a Saved card, a resolution otherwise. */
    fun onPickerChoice(target: ActivityTarget) {
        when (_state.value.card) {
            is ResultCard.Saved -> changeActivity(target)
            is ResultCard.Unresolved -> resolve(target)
            // No picker can be opened over a card with no capture, or over no card at all.
            ResultCard.RecognitionFailed, null -> closePicker()
        }
    }

    // ---- Internals ----------------------------------------------------------------------

    /**
     * Runs one card action (Undo, Change activity, resolve, open picker) unless another is
     * already running -- a second tap while one is in flight is ignored. A storage failure keeps
     * the current card and shows [failure]; messages never contain the user's words.
     */
    private fun runAction(
        closePicker: Boolean = false,
        failure: UserMessage = UserMessage(R.string.log_action_failed),
        block: suspend () -> Unit,
    ) {
        if (_state.value.actionInFlight) return
        _state.update {
            it.copy(actionInFlight = true, message = null, picker = if (closePicker) null else it.picker)
        }
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                _state.update { it.copy(message = failure) }
            } finally {
                _state.update { it.copy(actionInFlight = false) }
            }
        }
    }

    private fun showCard(card: ResultCard?) {
        undoJob?.cancel()
        undoJob = null
        cardTouched = false
        _state.update { it.copy(card = card, picker = null) }
        if (card is ResultCard.Saved) startUndoWindow()
    }

    private fun dismissCard() {
        undoJob?.cancel()
        undoJob = null
        cardTouched = false
        _state.update { it.copy(card = null, picker = null, message = null) }
    }

    private suspend fun showRefusal(refusal: ServiceRefusal) {
        val message = refusalMessageFor(repository, refusal)
        _state.update { it.copy(message = message) }
    }

    private suspend fun savedCard(occurrenceId: String): ResultCard.Saved? {
        val occurrence = repository.getOccurrence(occurrenceId) ?: return null
        val activity = repository.getActivity(occurrence.canonicalActivityId) ?: return null
        val capture = repository.getCapture(occurrence.rawCaptureId) ?: return null
        return ResultCard.Saved(
            captureId = capture.id,
            rawText = capture.rawText,
            occurrenceId = occurrence.id,
            activityName = activity.displayName,
            time = OccurrenceTimeFormatter.format(
                occurrence.occurredAt, occurrence.timePrecision, capture.zoneId, clock.instant(), locale(),
            ),
        )
    }

    /** Suggestions per [ReviewSuggestions] (shared with History). */
    private suspend fun suggestionsFor(captureId: String, rawText: String) =
        ReviewSuggestions.suggest(repository, captureId, rawText)

    private suspend fun reloadRecent() {
        val history = try {
            repository.loadHistory()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            return
        }
        val now = clock.instant()
        val rows = history.take(RECENT_ROWS).map { it.toHistoryRow(now, locale()) }
        _state.update { it.copy(recent = rows, recentLoaded = true) }
    }
}
