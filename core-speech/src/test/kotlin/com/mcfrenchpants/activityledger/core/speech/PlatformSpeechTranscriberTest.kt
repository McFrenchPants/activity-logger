package com.mcfrenchpants.activityledger.core.speech

import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session rules, driven through the faked recognizer seam so they run on a host JVM: one
 * terminal event and then completion, partials never promoted to a result, and the engine always
 * cancelled and then destroyed on every exit path.
 *
 * No Android runtime is involved. The only Android name here is `SpeechRecognizer`'s error
 * constants, which are compile-time integers.
 */
class PlatformSpeechTranscriberTest {

    // ---- the happy path --------------------------------------------------------------------

    @Test
    fun `partials are forwarded and the session ends with one final result`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onPartialTranscripts(FakeResults(listOf("walked")))
            callbacks.onPartialTranscripts(FakeResults(listOf("walked the")))
            callbacks.onFinalTranscripts(
                FakeResults(
                    transcripts = listOf("walked the dog", "walk the dog"),
                    confidenceScores = listOf(0.9f, 0.3f),
                ),
            )
        }

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(
            listOf(
                SpeechEvent.PartialTranscript("walked"),
                SpeechEvent.PartialTranscript("walked the"),
                SpeechEvent.FinalTranscript(
                    text = "walked the dog",
                    confidence = 0.9f,
                    alternatives = listOf("walk the dog"),
                ),
            ),
            events,
        )
        assertEquals(listOf("start", "cancel", "destroy"), handle.calls)
    }

    // ---- partials are never promoted -------------------------------------------------------

    @Test
    fun `partials followed by an empty final result end as nothing heard`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onPartialTranscripts(FakeResults(listOf("walked the")))
            // The engine finishes having settled on nothing at all.
            callbacks.onFinalTranscripts(FakeResults(emptyList()))
        }

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(
            listOf(
                SpeechEvent.PartialTranscript("walked the"),
                SpeechEvent.Failed(SpeechFailure.NOTHING_HEARD),
            ),
            events,
        )
        // The point of the test: the partial text exists nowhere in the terminal event.
        assertTrue(events.none { it is SpeechEvent.FinalTranscript })
    }

    @Test
    fun `partials followed by a no-match error end as nothing heard`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onPartialTranscripts(FakeResults(listOf("mmm")))
            callbacks.onError(SpeechRecognizer.ERROR_NO_MATCH)
        }

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(SpeechEvent.Failed(SpeechFailure.NOTHING_HEARD), events.last())
        assertTrue(events.none { it is SpeechEvent.FinalTranscript })
    }

    // ---- exactly one terminal event --------------------------------------------------------

    @Test
    fun `an error after a result does not produce a second terminal event`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onFinalTranscripts(FakeResults(listOf("coffee")))
            callbacks.onError(SpeechRecognizer.ERROR_AUDIO)
            callbacks.onFinalTranscripts(FakeResults(listOf("tea")))
        }

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(1, events.size)
        assertEquals(
            SpeechEvent.FinalTranscript("coffee", confidence = null, alternatives = emptyList()),
            events.single(),
        )
    }

    @Test
    fun `a partial arriving after the session settled is dropped`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            callbacks.onPartialTranscripts(FakeResults(listOf("too late")))
        }

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.RECOGNIZER_BUSY)), events)
    }

    // ---- failures that happen before a session even starts ----------------------------------

    @Test
    fun `an UnsupportedOperationException from the factory is mapped, not propagated`() = runTest {
        val factory = FakeRecognizerFactory(failure = UnsupportedOperationException("no engine"))

        val events = transcriberWith(factory).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.NO_ON_DEVICE_ENGINE)), events)
    }

    @Test
    fun `a device with no on-device engine reports so`() = runTest {
        val events = transcriberWith(FakeRecognizerFactory(handle = null)).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.NO_ON_DEVICE_ENGINE)), events)
    }

    @Test
    fun `a SecurityException from the factory is reported as a missing permission`() = runTest {
        val factory = FakeRecognizerFactory(failure = SecurityException("no mic"))

        val events = transcriberWith(factory).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.PERMISSION_MISSING)), events)
    }

    @Test
    fun `any other creation failure is a generic engine error`() = runTest {
        val factory = FakeRecognizerFactory(failure = IllegalStateException("boom"))

        val events = transcriberWith(factory).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.ENGINE_ERROR)), events)
    }

    @Test
    fun `a recognizer that fails to start is still cancelled and destroyed`() = runTest {
        val handle = FakeRecognizerHandle(throwOnStart = true)

        val events = transcriberWith(FakeRecognizerFactory(handle)).listen().toList()

        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.ENGINE_ERROR)), events)
        assertEquals(listOf("start", "cancel", "destroy"), handle.calls)
    }

    // ---- the engine is always released -------------------------------------------------------

    @Test
    fun `cancelling the collector cancels and destroys the recognizer`() = runTest {
        val handle = FakeRecognizerHandle { callbacks ->
            // A live session that never settles: only the collector walking away ends it.
            callbacks.onPartialTranscripts(FakeResults(listOf("still talking")))
        }

        // first() takes one event and then cancels the flow.
        val firstEvent = transcriberWith(FakeRecognizerFactory(handle)).listen().first()

        assertEquals(SpeechEvent.PartialTranscript("still talking"), firstEvent)
        assertEquals(listOf("start", "cancel", "destroy"), handle.calls)
    }

    @Test
    fun `nothing is created until the flow is collected`() = runTest {
        val handle = FakeRecognizerHandle()
        val transcriber = transcriberWith(FakeRecognizerFactory(handle))

        transcriber.listen()

        assertEquals(emptyList(), handle.calls)
    }

    // ---- threading ---------------------------------------------------------------------------

    @Test
    fun `creation, start and teardown all go through the main-thread runner`() = runTest {
        val runner = InlineMainThreadRunner()
        val handle = FakeRecognizerHandle { callbacks ->
            callbacks.onFinalTranscripts(FakeResults(listOf("done")))
        }

        transcriberWith(FakeRecognizerFactory(handle), runner).listen().toList()

        // One hop to create and start, one to release. Nothing touches the recognizer outside
        // them: the handle only ever recorded calls made from inside a runner block.
        assertEquals(2, runner.executions)
        assertEquals(listOf("start", "cancel", "destroy"), handle.calls)
    }
}
