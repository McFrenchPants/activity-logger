package com.mcfrenchpants.activityledger.ui.ask

import com.mcfrenchpants.activityledger.core.domain.lookup.LookupResult
import com.mcfrenchpants.activityledger.ui.review.UserMessage

/** What the Ask screen shows for one question once it has been dealt with. */
sealed interface AskOutcome {
    /** The history had matching entries; [result] carries them, best match first. */
    data class Answered(val result: LookupResult) : AskOutcome

    /** The words were not a question about the history. */
    data object NotAQuestion : AskOutcome

    /** The question was understood but nothing logged matches it. */
    data object NotEnoughHistory : AskOutcome

    /** This phone cannot run the on-device model that reads questions. */
    data object AiUnavailable : AskOutcome

    /** The model was busy or the question could not be read this time; asking again may work. */
    data object TryAgainLater : AskOutcome
}

/** One question in the thread; [outcome] is null while its answer is still being worked out. */
data class AskTurn(val id: Long, val question: String, val outcome: AskOutcome?)

/**
 * Everything the Ask screen shows. Held in memory only: questions and answers are never stored.
 *
 * @property thread the questions asked so far, oldest first.
 * @property input the text field's current text.
 * @property isAsking whether a question is being answered right now.
 * @property isListening whether a voice session is open.
 * @property partialTranscript live words from the open voice session (display only).
 * @property message a one-off notice, as string resources only.
 */
data class AskUiState(
    val thread: List<AskTurn> = emptyList(),
    val input: String = "",
    val isAsking: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val message: UserMessage? = null,
)
