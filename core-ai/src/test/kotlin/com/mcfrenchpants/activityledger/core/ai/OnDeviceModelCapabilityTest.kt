package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behaviour of the device-capability API, driven entirely through a fake client so it runs on a
 * host JVM. Every call is wrapped in `runBlocking` rather than a coroutines test dispatcher:
 * nothing here is time- or scheduler-dependent, so no extra test dependency is needed.
 */
class OnDeviceModelCapabilityTest {

    // ---- status -> readiness mapping -------------------------------------------------------

    @Test
    fun `installed model with structured output is ready`() {
        val fake = FakeGenerativeModelSession(
            status = FeatureStatus.AVAILABLE,
            structuredOutputAvailable = true,
        )

        assertEquals(ModelReadiness.READY, readiness(fake))
    }

    @Test
    fun `installed model without structured output is not ready`() {
        val fake = FakeGenerativeModelSession(
            status = FeatureStatus.AVAILABLE,
            structuredOutputAvailable = false,
        )

        assertEquals(ModelReadiness.STRUCTURED_OUTPUT_UNSUPPORTED, readiness(fake))
    }

    @Test
    fun `downloadable model reports not installed`() {
        assertEquals(
            ModelReadiness.NOT_INSTALLED,
            readiness(FakeGenerativeModelSession(status = FeatureStatus.DOWNLOADABLE)),
        )
    }

    @Test
    fun `downloading model reports download in progress`() {
        assertEquals(
            ModelReadiness.DOWNLOAD_IN_PROGRESS,
            readiness(FakeGenerativeModelSession(status = FeatureStatus.DOWNLOADING)),
        )
    }

    @Test
    fun `unavailable model reports unsupported device`() {
        assertEquals(
            ModelReadiness.UNSUPPORTED_DEVICE,
            readiness(FakeGenerativeModelSession(status = FeatureStatus.UNAVAILABLE)),
        )
    }

    @Test
    fun `every status maps to a distinct answer`() {
        val answers = ALL_STATUSES.map { readiness(FakeGenerativeModelSession(status = it)) } +
            readiness(
                FakeGenerativeModelSession(
                    status = FeatureStatus.AVAILABLE,
                    structuredOutputAvailable = false,
                ),
            )

        assertEquals(answers.size, answers.toSet().size)
    }

    // ---- structured output is only consulted when installed --------------------------------

    @Test
    fun `structured output is not consulted unless the model is installed`() {
        listOf(
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING,
            FeatureStatus.UNAVAILABLE,
        ).forEach { status ->
            val fake = FakeGenerativeModelSession(status = status)

            readiness(fake)

            assertEquals(0, fake.structuredOutputCalls, "status $status")
        }
    }

    @Test
    fun `structured output is consulted when the model is installed`() {
        val fake = FakeGenerativeModelSession(status = FeatureStatus.AVAILABLE)

        readiness(fake)

        assertEquals(1, fake.structuredOutputCalls)
    }

    // ---- the check never throws ------------------------------------------------------------

    @Test
    fun `a failing status check yields check failed`() {
        val fake = FakeGenerativeModelSession(statusFailure = IllegalStateException("boom"))

        assertEquals(ModelReadiness.CHECK_FAILED, readiness(fake))
    }

    @Test
    fun `a failing structured output check yields check failed`() {
        val fake = FakeGenerativeModelSession(
            status = FeatureStatus.AVAILABLE,
            structuredOutputFailure = GenAiException(RuntimeException(), 8),
        )

        assertEquals(ModelReadiness.CHECK_FAILED, readiness(fake))
    }

    @Test
    fun `an unrecognised status yields check failed rather than throwing`() {
        listOf(-7, 4, 99, Int.MIN_VALUE, Int.MAX_VALUE).forEach { status ->
            assertEquals(
                ModelReadiness.CHECK_FAILED,
                readiness(FakeGenerativeModelSession(status = status)),
                "status $status",
            )
        }
    }

    @Test
    fun `a client that cannot be created yields check failed`() {
        val capability = OnDeviceModelCapability(
            object : GenerativeModelSessionFactory {
                override fun create(): GenerativeModelSession = error("no AICore here")
            },
        )

        assertEquals(ModelReadiness.CHECK_FAILED, runBlocking { capability.readiness() })
    }

    // ---- nothing downloads by itself --------------------------------------------------------

    @Test
    fun `a readiness check never starts a download for any status`() {
        (ALL_STATUSES + listOf(-1)).forEach { status ->
            val fake = FakeGenerativeModelSession(status = status)

            readiness(fake)

            assertEquals(0, fake.downloadCalls, "status $status")
        }
    }

    @Test
    fun `a readiness check never downloads even when the model is merely downloadable`() {
        val fake = FakeGenerativeModelSession(status = FeatureStatus.DOWNLOADABLE)
        val capability = OnDeviceModelCapability(CountingSessionFactory(fake))

        repeat(3) { runBlocking { capability.readiness() } }

        assertEquals(ModelReadiness.NOT_INSTALLED, runBlocking { capability.readiness() })
        assertEquals(0, fake.downloadCalls)
    }

    @Test
    fun `download is a separate call and only starts when collected`() {
        val fake = FakeGenerativeModelSession(
            downloadStatuses = flowOf(DownloadStatus.DownloadCompleted),
        )
        val capability = OnDeviceModelCapability(CountingSessionFactory(fake))

        val flow = capability.download()
        assertEquals(0, fake.downloadCalls, "asking for the flow must not start anything")

        runBlocking { flow.toList() }
        assertEquals(1, fake.downloadCalls)
    }

    // ---- download progress mapping ----------------------------------------------------------

    @Test
    fun `download progress maps onto plain values with byte counts preserved`() {
        val progress = downloadProgress(
            flowOf(
                DownloadStatus.DownloadStarted(2_048_000_000L),
                DownloadStatus.DownloadProgress(1L),
                DownloadStatus.DownloadProgress(2_048_000_000L),
                DownloadStatus.DownloadCompleted,
            ),
        )

        assertEquals(
            listOf(
                ModelDownloadProgress.Started(2_048_000_000L),
                ModelDownloadProgress.Progress(1L),
                ModelDownloadProgress.Progress(2_048_000_000L),
                ModelDownloadProgress.Completed,
            ),
            progress,
        )
    }

    @Test
    fun `a reported download failure carries a code and no message`() {
        val progress = downloadProgress(
            flowOf(
                DownloadStatus.DownloadStarted(10L),
                DownloadStatus.DownloadFailed(
                    GenAiException("disk full: /data/user/0/secret", RuntimeException(), 501),
                ),
            ),
        )

        assertEquals(
            listOf(ModelDownloadProgress.Started(10L), ModelDownloadProgress.Failed(501)),
            progress,
        )
        assertTrue(progress.none { it.toString().contains("secret") })
    }

    @Test
    fun `a thrown download failure becomes a failure code, not an exception`() {
        val progress = downloadProgress(
            flow {
                emit(DownloadStatus.DownloadStarted(10L))
                throw GenAiException("network down for user bob", RuntimeException(), 9)
            },
        )

        assertEquals(
            listOf(ModelDownloadProgress.Started(10L), ModelDownloadProgress.Failed(9)),
            progress,
        )
    }

    @Test
    fun `a non ML Kit download failure reports the unknown code`() {
        val progress = downloadProgress(flow { throw IllegalStateException("kaboom") })

        assertEquals(listOf(ModelDownloadProgress.Failed(0)), progress)
    }

    @Test
    fun `downloading after close yields nothing`() {
        val fake = FakeGenerativeModelSession(
            downloadStatuses = flowOf(DownloadStatus.DownloadCompleted),
        )
        val capability = OnDeviceModelCapability(CountingSessionFactory(fake))
        capability.close()

        assertEquals(emptyList(), runBlocking { capability.download().toList() })
        assertEquals(0, fake.downloadCalls)
    }

    // ---- one lazily created client ----------------------------------------------------------

    @Test
    fun `constructing the capability creates no client`() {
        val factory = CountingSessionFactory()

        OnDeviceModelCapability(factory)

        assertEquals(0, factory.creations)
    }

    @Test
    fun `many readiness checks share one client`() {
        val factory = CountingSessionFactory()
        val capability = OnDeviceModelCapability(factory)

        repeat(5) { runBlocking { capability.readiness() } }
        runBlocking { capability.download().toList() }

        assertEquals(1, factory.creations)
    }

    @Test
    fun `the interpreter reaches the same client without creating a second one`() {
        val fake = FakeGenerativeModelSession()
        val factory = CountingSessionFactory(fake)
        val capability = OnDeviceModelCapability(factory)

        runBlocking { capability.readiness() }
        val session = assertNotNull(capability.session())
        runBlocking {
            session.warmup()
            assertFailsWith<GenerateContentReached> { session.generateContent(typedRequest()) }
        }

        assertSame(fake, session)
        assertSame(session, capability.session())
        assertEquals(1, factory.creations)
        assertEquals(1, fake.warmupCalls)
        assertEquals(1, fake.generateContentCalls)
    }

    // ---- close ------------------------------------------------------------------------------

    @Test
    fun `close is safe to call twice and closes the client once`() {
        val fake = FakeGenerativeModelSession()
        val capability = OnDeviceModelCapability(CountingSessionFactory(fake))
        runBlocking { capability.readiness() }

        capability.close()
        capability.close()

        assertEquals(1, fake.closeCalls)
    }

    @Test
    fun `closing before any client exists closes nothing and still succeeds`() {
        val factory = CountingSessionFactory()
        val capability = OnDeviceModelCapability(factory)

        capability.close()
        capability.close()

        assertEquals(0, factory.creations)
    }

    @Test
    fun `a closed capability reports check failed and hands out no client`() {
        val factory = CountingSessionFactory()
        val capability = OnDeviceModelCapability(factory)
        capability.close()

        assertEquals(ModelReadiness.CHECK_FAILED, runBlocking { capability.readiness() })
        assertNull(capability.session())
        assertEquals(0, factory.creations)
    }

    @Test
    fun `a client that throws on close does not break close`() {
        val capability = OnDeviceModelCapability(
            CountingSessionFactory(
                object : GenerativeModelSession by FakeGenerativeModelSession() {
                    override fun close(): Unit = error("release failed")
                },
            ),
        )
        runBlocking { capability.readiness() }

        capability.close()
        capability.close()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun readiness(fake: FakeGenerativeModelSession): ModelReadiness =
        runBlocking { OnDeviceModelCapability(CountingSessionFactory(fake)).readiness() }

    private fun downloadProgress(statuses: Flow<DownloadStatus>): List<ModelDownloadProgress> {
        val capability = OnDeviceModelCapability(
            CountingSessionFactory(FakeGenerativeModelSession(downloadStatuses = statuses)),
        )
        return runBlocking { capability.download().toList() }
    }

    private fun typedRequest(): GenerateTypedContentRequest<InterpretationResponse> =
        GenerateTypedContentRequest.Builder(
            GenerateContentRequest.Builder(TextPart("irrelevant")).build(),
            InterpretationResponse::class,
        ).build()

    private companion object {
        val ALL_STATUSES = listOf(
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING,
            FeatureStatus.UNAVAILABLE,
        )
    }
}
