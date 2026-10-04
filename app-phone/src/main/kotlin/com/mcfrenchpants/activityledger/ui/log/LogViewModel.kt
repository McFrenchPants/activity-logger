package com.mcfrenchpants.activityledger.ui.log

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.OccurrenceTime
import com.mcfrenchpants.activityledger.core.domain.services.ServiceRefusal
import com.mcfrenchpants.activityledger.core.domain.services.TagRefusal
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProposal
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.ui.components.toHistoryRow
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.refusalMessage
import com.mcfrenchpants.activityledger.ui.review.tagRefusalMessage
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

/** `sourceSurface` recorded on raw captures spoken on the Log screen. */
const val LOG_VOICE_SOURCE_SURFACE: String = "log_voice"

/** D5's undo window before accessibility adjustment. */
const val DEFAULT_UNDO_TIMEOUT_MILLIS: Long = 8_000L

/** How often the undo window's draining bar is advanced. */
const val UNDO_TICK_MILLIS: Long = 100L

/** Most rows shown under "Recent". */
private const val RECENT_ROWS = 3

/**
 * State holder of the Log screen: typed and spoken capture through the subject + action tag pipeline, the
 * result cards, the shared tag picker and the Recent list.
 *
 * Built from plain collaborators so it runs on the JVM without Android ([LogViewModelFactory]
 * wires the real ones; ADR-032, no DI framework).
 *
 * Rules it keeps:
 * - Typed *and* spoken words become an immutable raw capture first, then go through
 *   [orchestrator] only. Each [TaggedProcessingOutcome] maps to exactly one card; the outcome's
 *   reasons are never inspected (ADR-010).
 * - There is exactly one place in this class that writes to the ledger -- [captureAndInterpret]'s
 *   single `createRawCapture` call -- and no call anywhere that could update, rewrite or delete a
 *   raw capture afterwards. Typed and spoken words differ only in the [NewRawCapture] handed to
 *   it, so a voice capture reaches the model on exactly the path a typed one does (ADR-007,
 *   AGENTS.md #4).
 * - Speech is a session at a time, never continuous: one session exists only while
 *   [listeningSession] is set, and it is ended by its own result, by the user, by the screen
 *   stopping, by leaving Log, or by the view model being cleared. Partial transcripts are shown
 *   and then dropped: they are never stored, never sent to [orchestrator] and never survive the
 *   session that produced them (AGENTS.md #11).
 * - Submitting is only possible while the screen is started (ADR-029). Work already running
 *   lives in [viewModelScope], so neither backgrounding nor rotation cancels it, and its card is
 *   there when the user comes back.
 * - The Saved card's undo window (UX_VISUAL_SPEC D5) drains in [UNDO_TICK_MILLIS] steps on the
 *   coroutine clock, and pauses while the card is touched, the picker is open or the screen is
 *   stopped. Running out only dismisses the card; the occurrence stays saved.
 * - The AI-readiness check is asked once per start and never downloads anything.
 * - Nothing is logged (AGENTS.md #11).
 *
 * @param transcriber Turns spoken words into text, one session at a time. A plain interface, so
 *   these tests run on the JVM with a fake and nothing here knows about the platform recognizer.
 * @param isAiReady Whether on-device interpretation can run now. Must be cheap and must never
 *   start a download.
 * @param zone The zone recorded on new captures.
 * @param locale The locale times are formatted in.
 */
class LogViewModel(
    private val repository: LedgerRepository,
    private val orchestrator: TaggedCaptureOrchestrator,
    private val resolution: TaggedResolutionService,
    private val correction: TaggedCorrectionService,
    private val transcriber: SpeechTranscriber,
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

    /**
     * Identifies the one listening session that is allowed to change anything, or null when none
     * is running. Every event carries the session it came from and is dropped unless it still
     * matches, so a result that arrives after the user stopped listening (or after the screen
     * stopped) changes nothing and stores nothing.
     */
    private var listeningSession: Any? = null

    /** The coroutine collecting [listeningSession]'s flow. Cancelling it ends the session. */
    private var listeningJob: Job? = null

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

    /**
     * The Log screen went to the background. A capture already being stored or interpreted keeps
     * running (its card is there on return) and submit is blocked, but listening stops: the
     * microphone is never open off-screen (ADR-029).
     */
    fun onStop() {
        _state.update { it.copy(isStarted = false) }
        cancelListening()
    }

    /** The user left the Log destination: listening stops and the Saved card is dismissed. */
    fun onLeftLog() {
        cancelListening()
        if (_state.value.card is ResultCard.Saved) dismissCard()
    }

    /** The screen is gone for good. No listening session may outlive the view model. */
    override fun onCleared() {
        cancelListening()
        super.onCleared()
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
            captureAndInterpret(
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
        }
    }

    /**
     * The one path from words to a card, whether they were typed or spoken.
     *
     * This is also the only place in this class that writes to the ledger: the raw capture is
     * created here, once, and from then on it is only ever read -- there is no update, rewrite or
     * delete of a capture anywhere in this class to find (ADR-007, AGENTS.md #4). Callers differ
     * only in the [NewRawCapture] they build, so spoken words reach [orchestrator] and [cardFor]
     * on exactly the path typed words do.
     *
     * If storage fails, nothing was saved and the words go back into the capture field rather
     * than being lost; if interpretation fails, the words are stored and the card says so.
     */
    private suspend fun captureAndInterpret(capture: NewRawCapture) {
        val text = capture.rawText
        val captureId = try {
            repository.createRawCapture(capture)
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
            return
        }

        val card = try {
            cardFor(captureId, text, orchestrator.process(captureId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            // The words are stored, but no outcome was recorded: the owner can still pick both
            // sides by hand, so this is the Check card with nothing chosen.
            blankCheckCard(captureId, text)
        }
        _state.update { it.copy(isCapturing = false) }
        showCard(card)
        reloadRecent()
    }

    /** The one mapping from pipeline outcome to card (or to a refreshed Recent list). */
    private suspend fun cardFor(captureId: String, rawText: String, outcome: TaggedProcessingOutcome): ResultCard? =
        when (outcome) {
            is TaggedProcessingOutcome.AutoSaved -> savedCard(outcome.occurrenceId)
            TaggedProcessingOutcome.AlreadyHasOccurrence ->
                // If that occurrence is not in history (e.g. hidden), show no card and refresh Recent.
                existingOccurrenceId(captureId)?.let { savedCard(it) } ?: run { reloadRecent(); null }
            is TaggedProcessingOutcome.NeedsConfirm -> checkCard(rawText, outcome.proposal, emptySet())
            is TaggedProcessingOutcome.NeedsReview ->
                outcome.proposal?.let { checkCard(rawText, it, outcome.problems) } ?: blankCheckCard(captureId, rawText)
            is TaggedProcessingOutcome.Rejected -> blankCheckCard(captureId, rawText)
            is TaggedProcessingOutcome.InterpreterUnavailable -> blankCheckCard(captureId, rawText)
        }

    private suspend fun existingOccurrenceId(captureId: String): String? =
        repository.loadHistory().firstOrNull { it.captureId == captureId }?.occurrence?.occurrenceId

    private fun blankCheckCard(captureId: String, rawText: String) = ResultCard.Check(
        captureId = captureId,
        rawText = rawText,
        subject = CheckSide(TagKind.SUBJECT),
        action = CheckSide(TagKind.ACTION),
    )

    /**
     * The Check card for [proposal]. A side that resolved exactly or as a new name starts chosen;
     * a close match offers its candidates and starts unchosen; an empty side starts unchosen.
     * The proposal's time is kept only when it is clean: present, and not flagged as in the
     * future or unresolvable by [problems].
     */
    private fun checkCard(rawText: String, proposal: TaggedProposal, problems: Set<ValidationReason>): ResultCard.Check {
        val occurredAt = proposal.occurredAt
        val precision = proposal.timePrecision
        val time = if (
            occurredAt != null && precision != null &&
            ValidationReason.TIME_IN_FUTURE !in problems && ValidationReason.TIME_UNRESOLVABLE !in problems
        ) {
            OccurrenceTime(occurredAt, precision)
        } else {
            null
        }
        return ResultCard.Check(
            captureId = proposal.captureId,
            rawText = rawText,
            subject = sideOf(TagKind.SUBJECT, proposal.extractedSubject, proposal.subject, proposal.subjectInferred),
            action = sideOf(TagKind.ACTION, proposal.extractedAction, proposal.action, false),
            time = time,
            durationSeconds = proposal.durationSeconds,
            activityState = proposal.activityState,
        )
    }

    private fun sideOf(kind: TagKind, words: String?, resolution: TagResolution, inferred: Boolean): CheckSide =
        when (resolution) {
            TagResolution.Empty -> CheckSide(kind, words)
            is TagResolution.Exact -> CheckSide(
                kind, words, ChosenTag(TagChoice.Existing(resolution.tag.id), resolution.tag.displayName), assumed = inferred,
            )
            is TagResolution.New ->
                CheckSide(kind, words, ChosenTag(TagChoice.New(resolution.name), resolution.name), assumed = inferred)
            is TagResolution.Near ->
                CheckSide(kind, words, candidates = resolution.candidates, keepMine = resolution.newName)
        }

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

    /**
     * Changes the saved entry's subject or action to [choice] as a user correction. The undo
     * window keeps running and the card shows the new names.
     */
    private fun changeTag(kind: TagKind, choice: TagChoice) {
        val card = _state.value.card as? ResultCard.Saved ?: return
        runAction(closePicker = true) {
            val result = if (kind == TagKind.SUBJECT) {
                correction.correct(card.occurrenceId, subject = choice)
            } else {
                correction.correct(card.occurrenceId, action = choice)
            }
            when (result) {
                is TaggedCorrectionResult.Applied -> {
                    val refreshed = savedCard(card.occurrenceId)
                    _state.update { current ->
                        val shown = current.card as? ResultCard.Saved
                        if (refreshed != null && shown?.occurrenceId == card.occurrenceId) {
                            current.copy(
                                card = shown.copy(
                                    subjectName = refreshed.subjectName,
                                    actionName = refreshed.actionName,
                                    time = refreshed.time,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                    reloadRecent()
                }
                TaggedCorrectionResult.NothingChanged -> Unit
                is TaggedCorrectionResult.Refused -> showTagRefusal(result.refusal)
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

    // ---- Check card -----------------------------------------------------------------------

    /** Chooses the offered close-match tag [tagId] for the [kind] side of the Check card. */
    fun chooseCandidate(kind: TagKind, tagId: String) {
        val card = _state.value.card as? ResultCard.Check ?: return
        val tag = card.side(kind).candidates.firstOrNull { it.id == tagId } ?: return
        updateCheck(card.captureId) { it.choose(kind, ChosenTag(TagChoice.Existing(tag.id), tag.displayName)) }
    }

    /** "Keep mine": chooses a new tag from the owner's words for the [kind] side. */
    fun keepMine(kind: TagKind) {
        val card = _state.value.card as? ResultCard.Check ?: return
        val name = card.side(kind).keepMine ?: return
        updateCheck(card.captureId) { it.choose(kind, ChosenTag(TagChoice.New(name), name)) }
    }

    private fun updateCheck(captureId: String, transform: (ResultCard.Check) -> ResultCard.Check) {
        _state.update { current ->
            val shown = current.card as? ResultCard.Check
            if (shown == null || shown.captureId != captureId) current else current.copy(card = transform(shown), picker = null)
        }
    }

    /**
     * Saves the Check card's capture with the chosen subject and action. Only possible with both
     * sides chosen; nothing is saved before this. The time is the proposal's only when it was
     * clean (see [checkCard]); otherwise it is the capture time.
     */
    fun save() {
        val card = _state.value.card as? ResultCard.Check ?: return
        val subject = card.subject.chosen ?: return
        val action = card.action.chosen ?: return
        runAction(closePicker = true) {
            val result = resolution.resolve(
                captureId = card.captureId,
                subject = subject.choice,
                action = action.choice,
                time = card.time,
                durationSeconds = card.durationSeconds,
                activityState = card.activityState ?: ActivityState.COMPLETED,
            )
            when (result) {
                is TaggedResolutionResult.Resolved -> {
                    showCard(savedCard(result.occurrenceId))
                    reloadRecent()
                }
                is TaggedResolutionResult.Refused -> showTagRefusal(result.refusal)
            }
        }
    }

    /** Dismisses the card; the capture stays waiting for the owner (reachable from History). */
    fun decideLater() {
        if (_state.value.card !is ResultCard.Check) return
        dismissCard()
    }

    // ---- Voice capture ------------------------------------------------------------------

    /**
     * The user asked to log by voice: dismisses any card, clears the last partial, blocks typed
     * submit and starts exactly one listening session. Ignored while the screen is stopped, while
     * a capture is being processed, and while a session is already running -- a second tap can
     * never open a second microphone.
     */
    fun startListening() {
        val current = _state.value
        if (!current.canUseMicrophone || current.isListening) return
        cancelListening() // belt and braces: no session may still be running when one starts
        dismissCard()

        val session = Any()
        listeningSession = session
        _state.update { it.copy(isListening = true, partialTranscript = "", message = null) }
        listeningJob = viewModelScope.launch {
            try {
                transcriber.listen().collect { event -> onSpeechEvent(session, event) }
            } finally {
                // However the session ended -- result, failure, or cancellation, which in Kotlin
                // emits no event at all -- no partial outlives it.
                endListeningState(session)
            }
        }
    }

    /**
     * The user ended listening. Cancelling the collection is what closes the microphone, and it
     * emits no event, so the screen is handed back to typing here rather than waiting for one.
     */
    fun stopListening() {
        cancelListening()
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
     * One event from [session]. Events from any other session -- a result that arrives after the
     * user stopped, or anything a misbehaving transcriber emits after its terminal event -- are
     * dropped without touching the screen or the ledger.
     */
    private fun onSpeechEvent(session: Any, event: SpeechEvent) {
        if (listeningSession !== session) return
        when (event) {
            // Shown, then replaced. Never stored, never sent anywhere (AGENTS.md #11).
            is SpeechEvent.PartialTranscript ->
                _state.update { it.copy(partialTranscript = event.text) }

            is SpeechEvent.FinalTranscript -> {
                endListeningState(session)
                captureSpokenWords(event)
            }

            is SpeechEvent.Failed -> {
                endListeningState(session)
                showSpeechFailure(event.failure)
            }
        }
    }

    /**
     * The session's one result becomes one raw capture, on the typed path's own terms: the same
     * [captureAndInterpret], the same [orchestrator] call, the same [cardFor] mapping. The speech
     * fields are recorded as the engine reported them -- an unknown confidence stays unknown, and
     * no alternatives means no JSON, never an empty list dressed up as one.
     */
    private fun captureSpokenWords(event: SpeechEvent.FinalTranscript) {
        val text = event.text.trim()
        if (text.isEmpty()) return // the contract says never blank; if it is, there is nothing to store
        _state.update { it.copy(isCapturing = true, message = null) }
        viewModelScope.launch {
            captureAndInterpret(
                NewRawCapture(
                    source = CaptureSource.PHONE_VOICE,
                    sourceSurface = LOG_VOICE_SOURCE_SURFACE,
                    capturedAt = clock.instant(),
                    zoneId = zone(),
                    rawText = text,
                    speechConfidence = event.confidence?.toDouble(),
                    speechAlternativesJson = jsonArrayOrNull(event.alternatives),
                    processingState = ProcessingState.CAPTURED,
                ),
            )
        }
    }

    /**
     * What a failed session says, in plain words. Nothing is stored on any of these paths.
     *
     * "Try again" is only offered where trying again could work. A missing permission or a phone
     * with no on-device engine would make that button a dead end, so those say what happened and
     * leave the capture field -- which is the whole screen anyway -- available.
     */
    private fun showSpeechFailure(failure: SpeechFailure) {
        when (failure) {
            SpeechFailure.NOTHING_HEARD, SpeechFailure.ENGINE_ERROR -> showCard(ResultCard.RecognitionFailed)
            SpeechFailure.PERMISSION_MISSING -> showMessage(R.string.log_mic_permission_denied)
            SpeechFailure.NO_ON_DEVICE_ENGINE -> showMessage(R.string.log_voice_unavailable)
            SpeechFailure.RECOGNIZER_BUSY -> showMessage(R.string.log_voice_busy)
            // The user stopped it. Saying anything about it would be noise.
            SpeechFailure.CANCELLED -> Unit
        }
    }

    /** Ends [session]'s listening state, dropping its partial. Later sessions are untouched. */
    private fun endListeningState(session: Any) {
        if (listeningSession !== session) return
        listeningSession = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }

    /** Ends whatever session is running, if any: the microphone closes and the partial is gone. */
    private fun cancelListening() {
        listeningSession = null
        listeningJob?.cancel()
        listeningJob = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
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

    /** Opens the tag picker for [kind] over the ACTIVE tags (optionally on the new-name field). */
    fun openPicker(kind: TagKind, startWithNewName: Boolean = false) {
        if (_state.value.card !is ResultCard.Saved && _state.value.card !is ResultCard.Check) return
        runAction(failure = UserMessage(R.string.log_tags_not_loaded)) {
            val tags = repository.loadTagCatalog().tagsOf(kind)
            _state.update {
                val card = it.card
                if (card is ResultCard.Saved || card is ResultCard.Check) {
                    it.copy(picker = TagPickerState(kind, tags, startWithNewName))
                } else {
                    it
                }
            }
        }
    }

    /** The picker was closed without a choice. */
    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /** The picker returned [choice]: a correction for a Saved card, a side choice for a Check card. */
    fun onPickerChoice(choice: TagChoice) {
        val picker = _state.value.picker ?: return
        when (val card = _state.value.card) {
            is ResultCard.Saved -> changeTag(picker.kind, choice)
            is ResultCard.Check -> {
                val name = when (choice) {
                    is TagChoice.Existing -> picker.tags.firstOrNull { it.id == choice.tagId }?.displayName
                    is TagChoice.New -> choice.name.trim().takeIf { it.isNotEmpty() }
                }
                if (name == null) {
                    closePicker()
                } else {
                    val stored = if (choice is TagChoice.New) TagChoice.New(name) else choice
                    updateCheck(card.captureId) { it.choose(picker.kind, ChosenTag(stored, name)) }
                }
            }
            // No picker can be opened over a card with no capture, or over no card at all.
            ResultCard.RecognitionFailed, null -> closePicker()
        }
    }

    // ---- Internals ----------------------------------------------------------------------

    /**
     * Runs one card action (Undo, Change subject/action, Save, open picker) unless another is
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

    /** Shows one plain-words message. Message text is always a resource, never a literal. */
    private fun showMessage(@StringRes messageRes: Int) {
        _state.update { it.copy(message = UserMessage(messageRes)) }
    }

    private fun showTagRefusal(refusal: TagRefusal) {
        _state.update { it.copy(message = tagRefusalMessage(refusal)) }
    }

    /**
     * The Saved card for [occurrenceId]: the subject and action names come from the occurrence's
     * pair, read once from the tag catalog (no per-row queries). Null if anything needed is gone.
     */
    private suspend fun savedCard(occurrenceId: String): ResultCard.Saved? {
        val occurrence = repository.getOccurrence(occurrenceId) ?: return null
        val activity = repository.getActivity(occurrence.canonicalActivityId) ?: return null
        val capture = repository.getCapture(occurrence.rawCaptureId) ?: return null
        val catalog = repository.loadTagCatalog()
        val subject = activity.subjectId?.let { catalog.tag(TagKind.SUBJECT, it) } ?: return null
        val action = activity.actionId?.let { catalog.tag(TagKind.ACTION, it) } ?: return null
        return ResultCard.Saved(
            captureId = capture.id,
            rawText = capture.rawText,
            occurrenceId = occurrence.id,
            subjectName = subject.displayName,
            actionName = action.displayName,
            durationSeconds = occurrence.durationSeconds,
            time = OccurrenceTimeFormatter.format(
                occurrence.occurredAt, occurrence.timePrecision, capture.zoneId, clock.instant(), locale(),
            ),
        )
    }

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

/**
 * [values] as a JSON array of strings, or null when there are none -- "no alternatives" is
 * recorded as no JSON at all, never as `[]`.
 *
 * Hand-written rather than pulled from a JSON library: the shape is a flat array of strings and
 * nothing else, and this keeps the escaping visible in one place a reader can check. Everything
 * JSON requires to be escaped is escaped, including control characters.
 */
private fun jsonArrayOrNull(values: List<String>): String? {
    if (values.isEmpty()) return null
    return values.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character == '\n' -> append("\\n")
            character == '\r' -> append("\\r")
            character == '\t' -> append("\\t")
            character == '\b' -> append("\\b")
            character == '' -> append("\\f")
            character < ' ' -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
            else -> append(character)
        }
    }
    append('"')
}
