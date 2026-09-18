package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Owns this app process's single on-device model client and answers two questions about it:
 * can this device run interpretation at all ([readiness]), and -- only when the user explicitly
 * asks -- fetch the model ([download]).
 *
 * ## One instance per process
 *
 * The underlying client is created lazily on first use and then reused for every later call.
 * Loading and warming a model is expensive, so this object is meant to be constructed once for
 * the whole app process and shared, never once per capture. AI1.4's interpreter composes with
 * this object rather than making its own client (see [session]).
 *
 * ## Nothing downloads by itself
 *
 * [readiness] never starts a download, not even when the answer is
 * [ModelReadiness.NOT_INSTALLED]. Downloading a multi-gigabyte model is a decision for the
 * person using the app, made after being told the size, so it is a separate call that nothing
 * on the interpretation path invokes.
 *
 * ## No ML Kit outside this module, no content in any result
 *
 * Everything this class returns is a plain Kotlin type (ADR-023). No result, state or error
 * value carries an exception message, prompt text or captured content, and this class logs
 * nothing at all (AGENTS.md #11).
 *
 * Instances are safe to use from several coroutines at once.
 */
class OnDeviceModelCapability internal constructor(
    private val sessionFactory: GenerativeModelSessionFactory,
) : AutoCloseable {

    /** Production constructor: uses ML Kit's own client. */
    constructor() : this(MlKitGenerativeModelSessionFactory)

    private val lock = Any()
    private var session: GenerativeModelSession? = null
    private var closed = false

    /**
     * Whether interpretation can run on this device right now.
     *
     * Installation is never taken to imply readiness: structured output is consulted only when
     * the model reports itself installed, and a model that is installed without it is still not
     * [ModelReadiness.READY].
     *
     * Never throws for any underlying failure, including an unrecognised status value: a failing
     * check is reported as [ModelReadiness.CHECK_FAILED], carrying no detail of the failure. The
     * single exception is cancellation of the calling coroutine, which propagates as usual rather
     * than being disguised as a failed check. Never starts a download.
     */
    suspend fun readiness(): ModelReadiness {
        val session = openSession() ?: return ModelReadiness.CHECK_FAILED
        return try {
            when (session.checkStatus()) {
                FeatureStatus.AVAILABLE ->
                    if (session.isStructuredOutputFeatureAvailable()) {
                        ModelReadiness.READY
                    } else {
                        ModelReadiness.STRUCTURED_OUTPUT_UNSUPPORTED
                    }

                FeatureStatus.DOWNLOADABLE -> ModelReadiness.NOT_INSTALLED
                FeatureStatus.DOWNLOADING -> ModelReadiness.DOWNLOAD_IN_PROGRESS
                FeatureStatus.UNAVAILABLE -> ModelReadiness.UNSUPPORTED_DEVICE

                // A status this version of the app does not know about. Refusing to guess is
                // the safe answer: an unknown status is never treated as runnable.
                else -> ModelReadiness.CHECK_FAILED
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is not a failed check, and reporting it as one would let a Settings
            // screen show "could not check" for what was really the caller going away. It is the
            // one exception this function rethrows.
            throw cancellation
        } catch (expected: Exception) {
            // Deliberately broad, and deliberately swallowed: the readiness check is a question,
            // and "I could not find out" is a legitimate answer to it rather than an error the
            // caller has to handle. Nothing about `expected` is retained or reported.
            ModelReadiness.CHECK_FAILED
        }
    }

    /**
     * Downloads the model, reporting progress.
     *
     * **Only ever call this because the user asked for it**, after telling them how large the
     * download is. Nothing on the interpretation path may call it, and this class never calls
     * it itself -- there is no implicit, retried or background-scheduled download anywhere.
     *
     * The returned flow is cold: nothing happens -- not even obtaining the client -- until it
     * is collected, and collecting it is what starts the download. A failure arrives as
     * [ModelDownloadProgress.Failed] with a numeric code -- never a message -- and ends the
     * flow; if the client cannot be obtained at all, or this object is closed, the flow is
     * simply empty.
     */
    fun download(): Flow<ModelDownloadProgress> = flow {
        val session = openSession() ?: return@flow
        emitAll(session.download().map { it.toProgress() })
    }.catch { emit(ModelDownloadProgress.Failed(it.errorCodeOrUnknown())) }

    /**
     * The one client this object owns, for AI1.4's interpreter to run `generateContent` on.
     *
     * Internal on purpose (ADR-023): it hands out an ML Kit-shaped seam. Returns the same
     * instance every time, creating it on first use, so the interpreter never constructs a
     * second client. Returns null if this object is closed or the client cannot be created.
     */
    internal fun session(): GenerativeModelSession? = openSession()

    /**
     * Releases the client, if one was ever created.
     *
     * Safe to call more than once and from any thread: later calls do nothing. After closing,
     * [readiness] reports [ModelReadiness.CHECK_FAILED] and [download] yields nothing.
     */
    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            session.also { session = null }
        }
        try {
            toClose?.close()
        } catch (expected: Exception) {
            // Releasing a client is best-effort; there is nothing useful to do or say about a
            // failure here, and nothing may be logged.
        }
    }

    /** The lazy, exactly-once client creation. Returns null when closed or creation fails. */
    private fun openSession(): GenerativeModelSession? = synchronized(lock) {
        if (closed) return null
        session?.let { return it }
        val created = try {
            sessionFactory.create()
        } catch (expected: Exception) {
            return null
        }
        session = created
        created
    }
}

/** ML Kit's download status, translated into the plain types callers outside core-ai see. */
private fun DownloadStatus.toProgress(): ModelDownloadProgress = when (this) {
    is DownloadStatus.DownloadStarted -> ModelDownloadProgress.Started(bytesToDownload)
    is DownloadStatus.DownloadProgress -> ModelDownloadProgress.Progress(totalBytesDownloaded)
    is DownloadStatus.DownloadCompleted -> ModelDownloadProgress.Completed
    is DownloadStatus.DownloadFailed -> ModelDownloadProgress.Failed(e.errorCode)
}

/**
 * The code reported when a failure has no ML Kit error code of its own. Matches ML Kit's own
 * "unknown" code. Only a code is ever reported -- never the throwable or its message.
 */
private const val UNKNOWN_DOWNLOAD_ERROR_CODE = 0

private fun Throwable.errorCodeOrUnknown(): Int =
    (this as? com.google.mlkit.genai.common.GenAiException)?.errorCode
        ?: UNKNOWN_DOWNLOAD_ERROR_CODE
