package com.mcfrenchpants.activityledger.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupOutcome
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions of the "Ask your history" screen.
 *
 * - The thread of questions and answers lives in [state] only. Nothing is written to the
 *   database, no repository write is ever called from here, and nothing is logged (AGENTS.md #11).
 *   Messages carry string resource ids only, never the user's words.
 * - One question at a time: [ask] is ignored while an answer is pending. [clear] drops the thread
 *   and any pending answer; an answer that arrives afterwards changes nothing.
 * - Voice follows the Log view model: one listening session at a time, identified by a token, and
 *   every event from a finished session is dropped. The session's final transcript goes into the
 *   input field and, when it is not blank, is asked at once.
 */
class AskViewModel(
    private val lookup: LookupService,
    private val transcriber: SpeechTranscriber,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private var nextTurnId = 1L

    /** The running answer, if any; cancelled by [clear] and when the view model is cleared. */
    private var askJob: Job? = null

    /** Identifies the one listening session allowed to change anything, or null when none is. */
    private var listeningSession: Any? = null
    private var listeningJob: Job? = null

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    /** Asks the trimmed input. Blank input and a second question while one is pending do nothing. */
    fun ask() {
        val current = _state.value
        val question = current.input.trim()
        if (question.isEmpty() || current.isAsking) return
        cancelListening()

        val turnId = nextTurnId++
        _state.update {
            it.copy(
                thread = it.thread + AskTurn(turnId, question, outcome = null),
                input = "",
                isAsking = true,
                message = null,
            )
        }
        askJob = viewModelScope.launch {
            val outcome = try {
                lookup.ask(question).toUi()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                AskOutcome.TryAgainLater
            }
            _state.update { s ->
                // A clear() removed the turn: the late answer changes nothing.
                if (s.thread.none { it.id == turnId }) {
                    s
                } else {
                    s.copy(
                        thread = s.thread.map { if (it.id == turnId) it.copy(outcome = outcome) else it },
                        isAsking = false,
                    )
                }
            }
        }
    }

    /** Opens one listening session; ignored while one is running or an answer is pending. */
    fun startListening() {
        val current = _state.value
        if (current.isListening || current.isAsking) return
        cancelListening()
        val session = Any()
        listeningSession = session
        _state.update { it.copy(isListening = true, partialTranscript = "", message = null) }
        listeningJob = viewModelScope.launch {
            try {
                transcriber.listen().collect { event -> onSpeechEvent(session, event) }
            } finally {
                endListeningState(session)
            }
        }
    }

    fun stopListening() {
        cancelListening()
    }

    /** Empties the thread and the message, stops listening and ignores a still-pending answer. */
    fun clear() {
        cancelListening()
        askJob?.cancel()
        askJob = null
        _state.update { it.copy(thread = emptyList(), isAsking = false, message = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun onMicrophonePermissionDenied() {
        _state.update {
            it.copy(isListening = false, partialTranscript = "", message = UserMessage(R.string.ask_mic_permission_denied))
        }
    }

    fun onMicrophonePermissionBlocked() {
        _state.update {
            it.copy(isListening = false, partialTranscript = "", message = UserMessage(R.string.ask_mic_permission_blocked))
        }
    }

    override fun onCleared() {
        cancelListening()
        askJob?.cancel()
    }

    private fun onSpeechEvent(session: Any, event: SpeechEvent) {
        if (listeningSession !== session) return
        when (event) {
            is SpeechEvent.PartialTranscript -> _state.update { it.copy(partialTranscript = event.text) }
            is SpeechEvent.FinalTranscript -> {
                endListeningState(session)
                _state.update { it.copy(input = event.text) }
                if (event.text.isNotBlank()) ask()
            }
            is SpeechEvent.Failed -> {
                endListeningState(session)
                showSpeechFailure(event.failure)
            }
        }
    }

    private fun showSpeechFailure(failure: SpeechFailure) {
        val text = when (failure) {
            SpeechFailure.NOTHING_HEARD, SpeechFailure.ENGINE_ERROR -> R.string.ask_voice_nothing_heard
            SpeechFailure.PERMISSION_MISSING -> R.string.ask_mic_permission_denied
            SpeechFailure.NO_ON_DEVICE_ENGINE -> R.string.ask_voice_unavailable
            SpeechFailure.RECOGNIZER_BUSY -> R.string.log_voice_busy
            SpeechFailure.CANCELLED -> return
        }
        _state.update { it.copy(message = UserMessage(text)) }
    }

    private fun endListeningState(session: Any) {
        if (listeningSession !== session) return
        listeningSession = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }

    private fun cancelListening() {
        listeningSession = null
        listeningJob?.cancel()
        listeningJob = null
        _state.update { it.copy(isListening = false, partialTranscript = "") }
    }
}

private fun LookupOutcome.toUi(): AskOutcome = when (this) {
    LookupOutcome.NotAQuestion -> AskOutcome.NotAQuestion
    LookupOutcome.Unavailable -> AskOutcome.AiUnavailable
    LookupOutcome.Busy, LookupOutcome.Failed -> AskOutcome.TryAgainLater
    LookupOutcome.NotEnoughHistory -> AskOutcome.NotEnoughHistory
    is LookupOutcome.Answer -> AskOutcome.Answered(result)
}
