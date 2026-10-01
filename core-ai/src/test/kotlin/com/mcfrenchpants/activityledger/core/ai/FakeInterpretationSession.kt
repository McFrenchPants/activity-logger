package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentResponse
import com.google.mlkit.genai.prompt.TypedCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stand-in client for the interpreter's tests.
 *
 * Separate from AI1.3's [FakeGenerativeModelSession], which deliberately throws from
 * `generateContent` because that task never called it. This one supplies a scripted generation
 * outcome instead, records the request it was given so the generation settings can be asserted,
 * and counts every call so "exactly once" and "never downloaded" are proved by a counter rather
 * than by reading the code.
 *
 * Also serves the extractor's tests: script it with [WELL_FORMED_EXTRACTION] (or any
 * [ExtractionResponse]) in [GenerationOutcome.Responses]. The default outcome is unchanged.
 */
internal class FakeInterpretationSession(
    var status: Int = FeatureStatus.AVAILABLE,
    var structuredOutputAvailable: Boolean = true,
    /** What `generateContent` should do. Defaults to returning one well-formed response. */
    var outcome: GenerationOutcome = GenerationOutcome.Responses(listOf(WELL_FORMED_RESPONSE)),
) : GenerativeModelSession {

    var generateContentCalls = 0
        private set
    var downloadCalls = 0
        private set
    var warmupCalls = 0
        private set

    /** The request handed to the most recent `generateContent` call, if any. */
    var lastRequest: GenerateTypedContentRequest<*>? = null
        private set

    override suspend fun checkStatus(): Int = status

    override suspend fun isStructuredOutputFeatureAvailable(): Boolean = structuredOutputAvailable

    override fun download(): Flow<DownloadStatus> {
        downloadCalls++
        return emptyFlow()
    }

    override suspend fun warmup() {
        warmupCalls++
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> generateContent(
        request: GenerateTypedContentRequest<T>,
    ): GenerateTypedContentResponse<T> {
        generateContentCalls++
        lastRequest = request
        return when (val scripted = outcome) {
            is GenerationOutcome.Responses ->
                typedResponse(scripted.responses) as GenerateTypedContentResponse<T>

            is GenerationOutcome.Throws -> throw scripted.throwable
        }
    }

    override fun close() = Unit
}

/** What a scripted `generateContent` call does. */
internal sealed interface GenerationOutcome {
    /**
     * Return these responses as the candidate list, in order. An empty list is legitimate.
     *
     * Element type is `Any` so the same fake serves both the interpreter (an
     * [InterpretationResponse]) and the extractor (an [ExtractionResponse]); a test supplies the
     * type its subject asked for.
     */
    data class Responses(val responses: List<Any>) : GenerationOutcome

    /** Throw this instead of answering. */
    data class Throws(val throwable: Throwable) : GenerationOutcome
}

/** A response that decodes cleanly, used wherever the test does not care about the content. */
internal val WELL_FORMED_RESPONSE = InterpretationResponse(
    operation = "LOG_ACTIVITY",
    activityResolution = "NEW_ACTIVITY",
    matchedActivityId = null,
    proposedCanonicalName = "mow lawn",
    activityState = "COMPLETED",
    temporalExpression = "yesterday",
    confidenceBand = "HIGH",
)

/** An extraction response that decodes cleanly, for the extractor's tests. */
internal val WELL_FORMED_EXTRACTION = ExtractionResponse(
    operation = "LOG_ACTIVITY",
    subject = "coffee maker",
    action = "descale",
    activityState = "COMPLETED",
    temporalExpression = "yesterday",
    durationExpression = null,
)

/**
 * Builds ML Kit's typed response types reflectively.
 *
 * Their constructors take a trailing `DefaultConstructorMarker`, so Kotlin cannot call them at
 * all; reflection is the only way to produce one off-device. The alternative -- wrapping the
 * generation call in a narrower abstraction of our own purely so it could be faked -- would add
 * an indirection to production code that exists only for the test, which is the worse trade.
 * The empty-candidate-list and first-candidate-selection paths are worth reaching, so this
 * builder exists and lives entirely in test code.
 */
@Suppress("UNCHECKED_CAST")
internal fun typedResponse(
    responses: List<Any>,
): GenerateTypedContentResponse<Any> {
    val candidates = responses.map { response ->
        val constructor = TypedCandidate::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        constructor.newInstance(response, null, null) as TypedCandidate<Any>
    }
    val constructor = GenerateTypedContentResponse::class.java.declaredConstructors.single()
    constructor.isAccessible = true
    return constructor.newInstance(candidates, null)
        as GenerateTypedContentResponse<Any>
}
