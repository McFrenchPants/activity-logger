package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stand-in for ML Kit's real client, so every behaviour of [OnDeviceModelCapability] is testable
 * on a host JVM with no device and no AICore. It records how often each member was called, which
 * is how the "nothing downloads by itself" and "only one client ever" rules are asserted.
 */
internal class FakeGenerativeModelSession(
    var status: Int = FeatureStatus.AVAILABLE,
    var structuredOutputAvailable: Boolean = true,
    var statusFailure: Exception? = null,
    var structuredOutputFailure: Exception? = null,
    var downloadStatuses: Flow<DownloadStatus> = emptyFlow(),
) : GenerativeModelSession {

    var statusCalls = 0
        private set
    var structuredOutputCalls = 0
        private set
    var downloadCalls = 0
        private set
    var warmupCalls = 0
        private set
    var generateContentCalls = 0
        private set
    var closeCalls = 0
        private set

    override suspend fun checkStatus(): Int {
        statusCalls++
        statusFailure?.let { throw it }
        return status
    }

    override suspend fun isStructuredOutputFeatureAvailable(): Boolean {
        structuredOutputCalls++
        structuredOutputFailure?.let { throw it }
        return structuredOutputAvailable
    }

    override fun download(): Flow<DownloadStatus> {
        downloadCalls++
        return downloadStatuses
    }

    override suspend fun warmup() {
        warmupCalls++
    }

    /**
     * ML Kit's response type cannot be constructed off-device, so this records the call and
     * throws a marker instead. AI1.4 supplies its own behaviour here; this task only proves the
     * member is reachable on the one shared client.
     */
    override suspend fun <T : Any> generateContent(
        request: GenerateTypedContentRequest<T>,
    ): GenerateTypedContentResponse<T> {
        generateContentCalls++
        throw GenerateContentReached()
    }

    override fun close() {
        closeCalls++
    }
}

/** Marker thrown by [FakeGenerativeModelSession.generateContent]; carries no content. */
internal class GenerateContentReached : RuntimeException()

/** Hands out one fixed fake and counts how many times a client was asked for. */
internal class CountingSessionFactory(
    val session: GenerativeModelSession = FakeGenerativeModelSession(),
) : GenerativeModelSessionFactory {

    var creations = 0
        private set

    override fun create(): GenerativeModelSession {
        creations++
        return session
    }
}
