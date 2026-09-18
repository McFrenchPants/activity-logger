package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.Flow

/**
 * The one seam between this module's own logic and ML Kit's runtime client.
 *
 * It exists because `Generation.getClient()` needs a real device with AICore behind it and so
 * cannot run in a host JVM unit test. Every behaviour of [OnDeviceModelCapability] is therefore
 * written against this interface and exercised in tests through a fake implementation, with no
 * device involved.
 *
 * It declares exactly the members this project uses and nothing else -- narrowing ML Kit's much
 * larger `GenerativeModel` surface to the parts we depend on, so an upstream change to anything
 * else cannot reach us. It is `internal`, and stays internal: ADR-023 forbids an ML Kit type
 * (which `generateContent` and `download` both name) appearing in a hand-written public
 * signature of this module.
 */
internal interface GenerativeModelSession {

    /** ML Kit's feature status for the model, as one of its `FeatureStatus` integers. */
    suspend fun checkStatus(): Int

    /** Whether this device's model build can produce a schema-constrained response. */
    suspend fun isStructuredOutputFeatureAvailable(): Boolean

    /**
     * Starts an explicit model download and reports its progress.
     *
     * Never called as a side effect of anything else -- see [OnDeviceModelCapability.download].
     */
    fun download(): Flow<DownloadStatus>

    /**
     * Pre-loads the model so the first real inference is not also the one that pays for
     * loading. Expensive; meant to be done once per process, not per capture.
     */
    suspend fun warmup()

    /**
     * Runs one schema-constrained generation. Declared here so AI1.4's interpreter can use this
     * same seam (and the same client instance); this task does not call it.
     */
    suspend fun <T : Any> generateContent(
        request: GenerateTypedContentRequest<T>,
    ): GenerateTypedContentResponse<T>

    /** Releases the underlying client. Calling it more than once must be harmless. */
    fun close()
}

/**
 * Creates a [GenerativeModelSession]. The indirection is what lets a test substitute a fake
 * client and count how many were created, without a device.
 */
internal fun interface GenerativeModelSessionFactory {
    fun create(): GenerativeModelSession
}

/** The real seam implementation: a thin, behaviour-free delegation to ML Kit's client. */
internal class MlKitGenerativeModelSession(
    private val model: GenerativeModel,
) : GenerativeModelSession {

    override suspend fun checkStatus(): Int = model.checkStatus()

    override suspend fun isStructuredOutputFeatureAvailable(): Boolean =
        model.isStructuredOutputFeatureAvailable()

    override fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun warmup() = model.warmup()

    override suspend fun <T : Any> generateContent(
        request: GenerateTypedContentRequest<T>,
    ): GenerateTypedContentResponse<T> = model.generateContent(request)

    override fun close() = model.close()
}

/** The production factory: ML Kit's own default client. */
internal object MlKitGenerativeModelSessionFactory : GenerativeModelSessionFactory {
    override fun create(): GenerativeModelSession =
        MlKitGenerativeModelSession(Generation.getClient())
}
