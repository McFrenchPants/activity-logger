package com.mcfrenchpants.activityledger.core.ai.semantic

import com.mcfrenchpants.activityledger.core.ai.EXTRACTION_SYSTEM_INSTRUCTION
import com.mcfrenchpants.activityledger.core.ai.ExtractionDecodeResult
import com.mcfrenchpants.activityledger.core.ai.ExtractionResponse
import com.mcfrenchpants.activityledger.core.ai.ExtractionResponseDecoder
import com.mcfrenchpants.activityledger.core.ai.GENERATION_MAX_OUTPUT_TOKENS
import com.mcfrenchpants.activityledger.core.ai.GENERATION_SEED
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TEMPERATURE
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TOP_K
import com.mcfrenchpants.activityledger.core.ai.INCLUDE_SCHEMA_IN_PROMPT
import com.mcfrenchpants.activityledger.core.ai.INTERPRETATION_SYSTEM_INSTRUCTION
import com.mcfrenchpants.activityledger.core.ai.InterpretationDecodeResult
import com.mcfrenchpants.activityledger.core.ai.InterpretationResponse
import com.mcfrenchpants.activityledger.core.ai.InterpretationResponseDecoder
import com.mcfrenchpants.activityledger.core.ai.buildExtractionPrompt
import com.mcfrenchpants.activityledger.core.ai.buildInterpretationPrompt
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A stand-in problem the recorder cannot proceed past: server unreachable, model not pulled,
 * a refused URL. Its message is operator guidance only -- it never carries corpus text, prompt
 * text or model output (AGENTS.md #11).
 */
internal class StandInSetupException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** One model the local server has pulled. */
internal data class OllamaModelInfo(val name: String, val digest: String?)

/**
 * Minimal client for a model served LOCALLY by Ollama, used only by the stand-in recorder.
 *
 * It sends exactly what the device sends -- [INTERPRETATION_SYSTEM_INSTRUCTION] verbatim as the
 * system message, [buildInterpretationPrompt] (plus [StandInSchema.promptRendering] when
 * [INCLUDE_SCHEMA_IN_PROMPT]) as the user message, the interpreter's greedy generation settings
 * and seed -- and constrains the answer with [StandInSchema.jsonSchema]. The answer then goes
 * through the device's own [InterpretationResponseDecoder], mapped to results the way
 * `GeminiNanoActivityInterpreter` maps them. One call per case, never retried.
 *
 * [extract] is the prompt-v4 twin (ADR-038): [EXTRACTION_SYSTEM_INSTRUCTION], [buildExtractionPrompt]
 * (plus [StandInSchema.extraction]'s rendering when [INCLUDE_SCHEMA_IN_PROMPT]), the same
 * generation options, constrained by [StandInSchema.extraction], decoded through the device's own
 * [ExtractionResponseDecoder], and mapped to failures exactly as [interpret] maps them.
 *
 * Loopback only: a base URL whose host is not 127.0.0.1, ::1 or localhost is refused at
 * construction, no proxy is ever used and redirects are never followed, so corpus text cannot
 * leave the machine. Nothing here logs or prints.
 */
internal class OllamaStandInClient(
    baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    /** The validated base URL, without a trailing slash. */
    val baseUrl: String = requireLoopback(baseUrl)

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(connectTimeout)
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Lists the models the server has pulled (`GET /api/tags`). Throws [StandInSetupException]
     * naming the URL and how to start Ollama when the server does not answer.
     */
    fun listModels(): List<OllamaModelInfo> {
        val url = "$baseUrl/api/tags"
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(SERVER_CHECK_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
            )
        } catch (e: IOException) {
            throw StandInSetupException(unreachableMessage(url), e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StandInSetupException(unreachableMessage(url), e)
        }
        if (response.statusCode() != 200) {
            throw StandInSetupException(
                "The Ollama server at $url answered HTTP ${response.statusCode()} instead of a model list. " +
                    START_OLLAMA_HINT,
            )
        }
        val models = runCatching {
            (Json.parseToJsonElement(response.body()) as JsonObject)["models"] as? JsonArray
        }.getOrNull() ?: throw StandInSetupException(
            "The server at $url did not answer like Ollama (no model list). $START_OLLAMA_HINT",
        )
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: obj.string("model") ?: return@mapNotNull null
            OllamaModelInfo(name, obj.string("digest"))
        }
    }

    /**
     * Checks the server answers and [model] is pulled; returns the model's digest if the server
     * reports one. Never pulls anything: a missing model fails with `ollama pull` guidance.
     */
    fun requireModel(): OllamaModelInfo {
        val models = listModels()
        return models.firstOrNull { sameModel(it.name, model) }
            ?: throw StandInSetupException(
                "Model '$model' is not available on the Ollama server at $baseUrl. " +
                    "Pull it yourself first with:  ollama pull $model   (this recorder never downloads models).",
            )
    }

    /** The exact `/api/chat` request body for [input]. */
    fun requestBody(input: InterpretationInput): JsonObject =
        chatBody(INTERPRETATION_SYSTEM_INSTRUCTION, userMessage(input), StandInSchema.jsonSchema)

    /** The exact `/api/chat` request body for extracting from [input] (prompt v4). */
    fun extractionRequestBody(input: ExtractionInput): JsonObject =
        chatBody(EXTRACTION_SYSTEM_INSTRUCTION, extractionUserMessage(input), StandInSchema.extraction.jsonSchema)

    /** One chat request: system + user message, constrained by [format], device generation options. */
    private fun chatBody(system: String, user: String, format: JsonObject): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", false)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", user)
            }
        }
        put("format", format)
        putJsonObject("options") {
            put("temperature", GENERATION_TEMPERATURE)
            put("top_k", GENERATION_TOP_K)
            put("seed", GENERATION_SEED)
            put("num_predict", GENERATION_MAX_OUTPUT_TOKENS)
        }
    }

    /**
     * Interprets one capture with exactly one request. Never throws for a server or model
     * problem; returns a result the way the device interpreter would:
     *
     * - decodable answer -> [InterpretationResult.Success] with `structuredResultJson = null`
     * - answer that is not a JSON object, or that the decoder rejects -> MALFORMED
     * - HTTP error status, I/O error, timeout, or a reply that is not an Ollama chat reply -> OTHER
     */
    fun interpret(input: InterpretationInput): InterpretationResult =
        when (val reply = chat(requestBody(input))) {
            is ChatReply.Failed -> failure(reply.kind)
            is ChatReply.Answer -> when (val decoded = InterpretationResponseDecoder.decode(reply.json.toResponse())) {
                is InterpretationDecodeResult.Decoded ->
                    InterpretationResult.Success(decoded.candidate, structuredResultJson = null)
                is InterpretationDecodeResult.Failed -> failure(InterpreterFailureKind.MALFORMED)
            }
        }

    /**
     * Extracts from one capture (prompt v4) with exactly one request. Never throws for a server or
     * model problem; maps failures exactly as [interpret] does:
     *
     * - decodable answer -> [ExtractionResult.Success]
     * - answer that is not a JSON object, or that [ExtractionResponseDecoder] rejects -> MALFORMED
     * - HTTP error status, I/O error, timeout, or a reply that is not an Ollama chat reply -> OTHER
     */
    fun extract(input: ExtractionInput): ExtractionResult =
        when (val reply = chat(extractionRequestBody(input))) {
            is ChatReply.Failed -> ExtractionResult.Failure(reply.kind)
            is ChatReply.Answer -> when (val decoded = ExtractionResponseDecoder.decode(reply.json.toExtractionResponse())) {
                is ExtractionDecodeResult.Decoded -> ExtractionResult.Success(decoded.candidate)
                is ExtractionDecodeResult.Failed -> ExtractionResult.Failure(InterpreterFailureKind.MALFORMED)
            }
        }

    /** What one chat request produced: the model's JSON answer, or a failure kind. */
    private sealed interface ChatReply {
        data class Answer(val json: JsonObject) : ChatReply
        data class Failed(val kind: InterpreterFailureKind) : ChatReply
    }

    /** Sends [body] once to `/api/chat`; never retried, never throws for a server or model problem. */
    private fun chat(body: JsonObject): ChatReply {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), Charsets.UTF_8))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: IOException) {
            // Includes HttpTimeoutException and connection failures. Recorded once, not retried.
            return ChatReply.Failed(InterpreterFailureKind.OTHER)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return ChatReply.Failed(InterpreterFailureKind.OTHER)
        }
        if (response.statusCode() != 200) return ChatReply.Failed(InterpreterFailureKind.OTHER)

        // The envelope is Ollama's, not the model's: if it is not a chat reply, the server failed.
        val envelope = runCatching { Json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
            ?: return ChatReply.Failed(InterpreterFailureKind.OTHER)
        val message = envelope["message"] as? JsonObject ?: return ChatReply.Failed(InterpreterFailureKind.OTHER)

        // From here on it is the model's answer: anything unusable is MALFORMED, as on the device.
        val content = message.string("content") ?: return ChatReply.Failed(InterpreterFailureKind.MALFORMED)
        val answer = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
            ?: return ChatReply.Failed(InterpreterFailureKind.MALFORMED)
        return ChatReply.Answer(answer)
    }

    companion object {
        const val DEFAULT_MODEL: String = "gemma3n:e4b"
        const val DEFAULT_BASE_URL: String = "http://127.0.0.1:11434"
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
        private val SERVER_CHECK_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]", "localhost")

        private const val START_OLLAMA_HINT =
            "Install Ollama from https://ollama.com if needed, start it (open the Ollama app, or run " +
                "`ollama serve`), then try again."

        /** The user message: the device prompt, plus the schema text when the device adds it. */
        fun userMessage(input: InterpretationInput): String =
            buildInterpretationPrompt(input) + if (INCLUDE_SCHEMA_IN_PROMPT) StandInSchema.promptRendering else ""

        /** The extraction user message: the v4 device prompt, plus its schema text when the device adds it. */
        fun extractionUserMessage(input: ExtractionInput): String =
            buildExtractionPrompt(input) +
                if (INCLUDE_SCHEMA_IN_PROMPT) StandInSchema.extraction.promptRendering else ""

        /**
         * Returns [raw] without a trailing slash if it is an http(s) URL on a loopback host;
         * otherwise throws [IllegalArgumentException]. Corpus text must never leave the machine.
         */
        fun requireLoopback(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull()
            val host = uri?.host?.lowercase()
            require(uri != null && (uri.scheme == "http" || uri.scheme == "https") && host in LOOPBACK_HOSTS) {
                "Refusing stand-in base URL '$raw': only a loopback address (127.0.0.1, ::1 or " +
                    "localhost) over http(s) is allowed, so corpus text never leaves this machine."
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Refusing stand-in base URL '$raw': it must be a plain scheme://host:port base URL."
            }
            return raw.trim().trimEnd('/')
        }

        /** True when [served] (as Ollama lists it) is the model [wanted]; a bare name means `:latest`. */
        fun sameModel(served: String, wanted: String): Boolean {
            fun normal(name: String) = if (':' in name) name else "$name:latest"
            return normal(served) == normal(wanted)
        }

        private fun unreachableMessage(url: String): String =
            "No Ollama server answered at $url. $START_OLLAMA_HINT " +
                "(Nothing was recorded.)"

        private fun failure(kind: InterpreterFailureKind) =
            InterpretationResult.Failure(kind, structuredResultJson = null)

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Absent, null or non-string values become null; the decoder judges the rest. */
        private fun JsonObject.toResponse(): InterpretationResponse = InterpretationResponse(
            operation = string("operation"),
            activityResolution = string("activityResolution"),
            matchedActivityId = string("matchedActivityId"),
            proposedCanonicalName = string("proposedCanonicalName"),
            activityState = string("activityState"),
            temporalExpression = string("temporalExpression"),
            confidenceBand = string("confidenceBand"),
        )

        /** Absent, null or non-string values become null; the extraction decoder judges the rest. */
        private fun JsonObject.toExtractionResponse(): ExtractionResponse = ExtractionResponse(
            operation = string("operation"),
            subject = string("subject"),
            action = string("action"),
            activityState = string("activityState"),
            temporalExpression = string("temporalExpression"),
            durationExpression = string("durationExpression"),
        )
    }
}
