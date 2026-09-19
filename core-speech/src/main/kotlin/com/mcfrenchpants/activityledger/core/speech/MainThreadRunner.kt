package com.mcfrenchpants.activityledger.core.speech

import android.os.Handler
import android.os.Looper

/**
 * Runs a block on the Android main thread.
 *
 * The platform `SpeechRecognizer` must be created and called on the main thread, and the thread
 * a flow happens to be collected on is nobody's promise -- a ViewModel could collect on a
 * background dispatcher and the recognizer would misbehave in ways that are awkward to
 * reproduce. So every touch of the recognizer goes through this, rather than through an
 * assumption.
 *
 * It is an injectable seam rather than `Dispatchers.Main` on purpose: `Dispatchers.Main` needs
 * the extra `kotlinx-coroutines-android` artifact on the runtime classpath and throws if it is
 * missing, whereas a `Handler` is already there. Tests substitute a runner that executes
 * inline.
 */
internal fun interface MainThreadRunner {

    /** Runs [block] on the main thread, now if already there, otherwise as soon as possible. */
    fun execute(block: () -> Unit)
}

/** The production runner: the real main looper. */
internal class LooperMainThreadRunner : MainThreadRunner {

    private val mainLooper: Looper = Looper.getMainLooper()
    private val handler = Handler(mainLooper)

    override fun execute(block: () -> Unit) {
        if (Looper.myLooper() == mainLooper) {
            block()
        } else {
            handler.post(block)
        }
    }
}
