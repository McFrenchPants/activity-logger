package com.mcfrenchpants.activityledger.core.ai.semantic

import com.mcfrenchpants.activityledger.core.ai.EXTRACTION_SYSTEM_INSTRUCTION
import com.mcfrenchpants.activityledger.core.ai.GENERATION_MAX_OUTPUT_TOKENS
import com.mcfrenchpants.activityledger.core.ai.GENERATION_SEED
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TEMPERATURE
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TOP_K
import com.mcfrenchpants.activityledger.core.ai.INCLUDE_SCHEMA_IN_PROMPT
import com.mcfrenchpants.activityledger.core.ai.buildExtractionPrompt
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stand-in client's EXTRACTION path (prompt v4) against an in-process fake Ollama bound to
 * 127.0.0.1 on an ephemeral port. No real model or server is ever contacted. The loopback-only
 * and server-check rules are shared with the interpretation path and covered by
 * [OllamaStandInClientTest].
 */
class OllamaStandInExtractionTest {

    private data class Recorded(val method: String, val path: String, val body: String)

    private var status = 200
    private var responseBody = ""
    private var delayMs = 0L
    private val requests = CopyOnWriteArrayList<Recorded>()
    private val executor = Executors.newCachedThreadPool()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = this@OllamaStandInExtractionTest.executor
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requests += Recorded(exchange.requestMethod, exchange.requestURI.path, body)
                if (delayMs > 0) Thread.sleep(delayMs)
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private val input = ExtractionInput(
        rawText = "Oiled the gate hinges this morning",
        capturedAt = Instant.parse("2026-06-01T15:30:00Z"),
        zoneId = ZoneId.of("America/Detroit"),
    )

    private fun chatReply(content: String): String = buildJsonObject {
        put("model", OllamaStandInClient.DEFAULT_MODEL)
        putJsonObject("message") {
            put("role", "assistant")
            put("content", content)
        }
        put("done", true)
    }.toString()

    private fun client(requestTimeout: Duration = OllamaStandInClient.DEFAULT_REQUEST_TIMEOUT) =
        OllamaStandInClient(baseUrl = baseUrl, requestTimeout = requestTimeout)

    @Test
    fun `request carries the extraction system instruction, prompt, greedy options, seed and schema`() {
        responseBody = chatReply("""{"operation":"LOG_ACTIVITY"}""")
        client().extract(input)

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/chat", request.path)
        val body = Json.parseToJsonElement(request.body) as JsonObject
        assertEquals(JsonPrimitive(OllamaStandInClient.DEFAULT_MODEL), body["model"])
        assertEquals(JsonPrimitive(false), body["stream"])

        val messages = body["messages"] as JsonArray
        assertEquals(2, messages.size)
        val system = messages[0] as JsonObject
        val user = messages[1] as JsonObject
        assertEquals("system", (system["role"] as JsonPrimitive).content)
        assertEquals(EXTRACTION_SYSTEM_INSTRUCTION, (system["content"] as JsonPrimitive).content)
        assertEquals("user", (user["role"] as JsonPrimitive).content)
        val userText = (user["content"] as JsonPrimitive).content
        val prompt = buildExtractionPrompt(input)
        assertTrue(userText.startsWith(prompt), "user message must start with the exact extraction prompt")
        assertEquals(
            prompt + if (INCLUDE_SCHEMA_IN_PROMPT) StandInSchema.extraction.promptRendering else "",
            userText,
        )

        val options = body["options"] as JsonObject
        assertEquals(GENERATION_TEMPERATURE.toDouble(), (options["temperature"] as JsonPrimitive).double)
        assertEquals(GENERATION_TOP_K, (options["top_k"] as JsonPrimitive).int)
        assertEquals(GENERATION_SEED, (options["seed"] as JsonPrimitive).int)
        assertEquals(GENERATION_MAX_OUTPUT_TOKENS, (options["num_predict"] as JsonPrimitive).int)

        val format = body["format"] as JsonObject
        assertEquals(StandInSchema.extraction.jsonSchema, format)
        val properties = format["properties"] as JsonObject
        assertFalse("confidenceBand" in properties.keys)
        mapOf(
            "operation" to InterpretationOperation.entries.map { it.name },
            "activityState" to ActivityState.entries.map { it.name },
        ).forEach { (field, expected) ->
            val enum = (properties[field] as JsonObject)["enum"] as JsonArray
            assertEquals(
                expected,
                enum.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content },
                "enum of $field",
            )
        }
    }

    @Test
    fun `the interpretation request is unchanged by the extraction path`() {
        // The v3 body still uses the interpretation schema and instruction, not the extraction ones.
        val body = client().requestBody(
            com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput(
                rawText = input.rawText,
                capturedAt = input.capturedAt,
                zoneId = input.zoneId,
                candidates = emptyList(),
            ),
        )
        assertEquals(StandInSchema.jsonSchema, body["format"])
        assertTrue(body["format"] != StandInSchema.extraction.jsonSchema)
    }

    @Test
    fun `a well-formed answer decodes to Success with the right candidate`() {
        responseBody = chatReply(
            buildJsonObject {
                put("operation", "LOG_ACTIVITY")
                put("subject", "gate")
                put("action", "oil hinges")
                put("activityState", "COMPLETED")
                put("temporalExpression", "this morning")
                put("durationExpression", null as String?)
            }.toString(),
        )
        assertEquals(
            ExtractionResult.Success(
                ExtractionCandidate(
                    operation = InterpretationOperation.LOG_ACTIVITY,
                    subject = "gate",
                    action = "oil hinges",
                    activityState = ActivityState.COMPLETED,
                    temporalExpression = "this morning",
                    durationExpression = null,
                ),
            ),
            client().extract(input),
        )
    }

    @Test
    fun `absent and non-string fields become null`() {
        responseBody = chatReply("""{"operation":"LOG_ACTIVITY","action":"oil hinges","durationExpression":42}""")
        val result = client().extract(input) as ExtractionResult.Success
        assertEquals("oil hinges", result.candidate.action)
        assertEquals(null, result.candidate.subject)
        assertEquals(null, result.candidate.durationExpression)
    }

    @Test
    fun `an invalid enum value is MALFORMED`() {
        responseBody = chatReply("""{"operation":"LOG_ACTIVITY","activityState":"PROBABLY"}""")
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), client().extract(input))
    }

    @Test
    fun `a missing operation is MALFORMED`() {
        responseBody = chatReply("""{"subject":"gate","action":"oil hinges"}""")
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), client().extract(input))
    }

    @Test
    fun `non-JSON content is MALFORMED`() {
        responseBody = chatReply("Sure! You oiled the gate.")
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), client().extract(input))
    }

    @Test
    fun `a reply that is not an Ollama chat reply is OTHER`() {
        responseBody = """{"unexpected":true}"""
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.OTHER), client().extract(input))
    }

    @Test
    fun `HTTP 500 is OTHER and is not retried`() {
        status = 500
        responseBody = """{"error":"boom"}"""
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.OTHER), client().extract(input))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a request timeout is OTHER and is not retried`() {
        delayMs = 2_000
        responseBody = chatReply("{}")
        val started = System.nanoTime()
        assertEquals(
            ExtractionResult.Failure(InterpreterFailureKind.OTHER),
            client(Duration.ofMillis(200)).extract(input),
        )
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_900, "timeout was not honoured")
        assertEquals(1, requests.size)
    }
}
