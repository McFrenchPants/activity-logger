package com.mcfrenchpants.activityledger.core.speech

/**
 * Test doubles for the recognizer seam, so a whole listening session can be driven on a host JVM
 * with no device, no microphone and no audio.
 *
 * Everything here runs inline on the calling thread: the runner executes immediately and the
 * fake recognizer plays its script the moment it is started. That makes a session deterministic
 * and removes any need for virtual time or a scheduler in the tests.
 */

/** A [MainThreadRunner] that runs the block where it stands, recording that it was used. */
internal class InlineMainThreadRunner : MainThreadRunner {

    var executions: Int = 0
        private set

    override fun execute(block: () -> Unit) {
        executions++
        block()
    }
}

/** A fixed batch of transcripts, standing in for one platform results `Bundle`. */
internal data class FakeResults(
    override val transcripts: List<String>,
    override val confidenceScores: List<Float>? = null,
) : RecognitionResults

/**
 * A recognizer that plays [script] when started and records its own lifecycle.
 *
 * [calls] holds "start", "cancel" and "destroy" in the order they happened, which is how the
 * tests assert that the engine is always cancelled before it is destroyed.
 */
internal class FakeRecognizerHandle(
    private val throwOnStart: Boolean = false,
    private val script: (RecognizerCallbacks) -> Unit = {},
) : RecognizerHandle {

    val calls = mutableListOf<String>()

    override fun start(callbacks: RecognizerCallbacks) {
        calls += "start"
        if (throwOnStart) throw IllegalStateException("fake start failure")
        script(callbacks)
    }

    override fun cancel() {
        calls += "cancel"
    }

    override fun destroy() {
        calls += "destroy"
    }
}

/**
 * A factory that hands back [handle], or instead fails the way the packet's two real failure
 * shapes do: returning null (no local engine) or throwing [failure] (a device whose factory
 * refuses, as a OnePlus Watch 3 was observed to do).
 */
internal class FakeRecognizerFactory(
    private val handle: FakeRecognizerHandle? = null,
    private val failure: Throwable? = null,
) : RecognizerFactory {

    override fun create(): RecognizerHandle? {
        failure?.let { throw it }
        return handle
    }
}

/** Builds the transcriber under test with both seams faked. */
internal fun transcriberWith(
    factory: RecognizerFactory,
    runner: MainThreadRunner = InlineMainThreadRunner(),
): PlatformSpeechTranscriber = PlatformSpeechTranscriber(factory, runner)
