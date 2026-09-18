package com.mcfrenchpants.activityledger.core.ai.semantic

import com.mcfrenchpants.activityledger.core.ai.GENERATION_MAX_OUTPUT_TOKENS
import com.mcfrenchpants.activityledger.core.ai.GENERATION_SEED
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TEMPERATURE
import com.mcfrenchpants.activityledger.core.ai.GENERATION_TOP_K
import com.mcfrenchpants.activityledger.core.ai.INCLUDE_SCHEMA_IN_PROMPT
import com.mcfrenchpants.activityledger.core.ai.INTERPRETATION_SYSTEM_INSTRUCTION
import com.mcfrenchpants.activityledger.core.ai.buildInterpretationPrompt
import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stand-in client against an in-process fake Ollama bound to 127.0.0.1 on an ephemeral port.
 * No real model or server is ever contacted.
 */
class OllamaStandInClientTest {

    private data class Recorded(val method: String, val path: String, val body: String)

    private var status = 200
    private var responseBody = ""
    private var delayMs = 0L
    private val requests = CopyOnWriteArrayList<Recorded>()
    private val executor = Executors.newCachedThreadPool()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = this@OllamaStandInClientTest.executor
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

    private val input = InterpretationInput(
        rawText = "I cut the grass this morning",
        capturedAt = Instant.parse("2026-06-01T15:30:00Z"),
        zoneId = ZoneId.of("America/Detroit"),
        candidates = listOf(
            CandidateActivity("act-mow", "Mow lawn", listOf("cut the grass")),
            CandidateActivity("act-edge", "Edge lawn", emptyList()),
        ),
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
    fun `request carries the exact system instruction, prompt, greedy options, seed and schema`() {
        responseBody = chatReply("""{"operation":"LOG_ACTIVITY","activityResolution":"UNRESOLVED"}""")
        client().interpret(input)

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
        assertEquals(INTERPRETATION_SYSTEM_INSTRUCTION, (system["content"] as JsonPrimitive).content)
        assertEquals("user", (user["role"] as JsonPrimitive).content)
        val userText = (user["content"] as JsonPrimitive).content
        val prompt = buildInterpretationPrompt(input)
        assertTrue(userText.startsWith(prompt), "user message must start with the exact prompt")
        assertEquals(
            prompt + if (INCLUDE_SCHEMA_IN_PROMPT) StandInSchema.promptRendering else "",
            userText,
        )

        val options = body["options"] as JsonObject
        assertEquals(GENERATION_TEMPERATURE.toDouble(), (options["temperature"] as JsonPrimitive).double)
        assertEquals(GENERATION_TOP_K, (options["top_k"] as JsonPrimitive).int)
        assertEquals(GENERATION_SEED, (options["seed"] as JsonPrimitive).int)
        assertEquals(GENERATION_MAX_OUTPUT_TOKENS, (options["num_predict"] as JsonPrimitive).int)

        val format = body["format"] as JsonObject
        assertEquals(StandInSchema.jsonSchema, format)
        val properties = format["properties"] as JsonObject
        mapOf(
            "operation" to InterpretationOperation.entries.map { it.name },
            "activityResolution" to ActivityResolution.entries.map { it.name },
            "activityState" to ActivityState.entries.map { it.name },
            "confidenceBand" to ConfidenceBand.entries.map { it.name },
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
    fun `a well-formed answer decodes to Success with the right candidate`() {
        responseBody = chatReply(
            buildJsonObject {
                put("operation", "LOG_ACTIVITY")
                put("activityResolution", "EXISTING_ACTIVITY")
                put("matchedActivityId", "act-mow")
                put("proposedCanonicalName", null as String?)
                put("activityState", "COMPLETED")
                put("temporalExpression", "this morning")
                put("confidenceBand", "HIGH")
            }.toString(),
        )
        val result = client().interpret(input)
        assertEquals(
            InterpretationResult.Success(
                InterpretationCandidate(
                    operation = InterpretationOperation.LOG_ACTIVITY,
                    activityResolution = ActivityResolution.EXISTING_ACTIVITY,
                    matchedActivityId = "act-mow",
                    proposedCanonicalName = null,
                    activityState = ActivityState.COMPLETED,
                    temporalExpression = "this morning",
                    confidenceBand = ConfidenceBand.HIGH,
                ),
                structuredResultJson = null,
            ),
            result,
        )
    }

    @Test
    fun `absent and non-string fields become null`() {
        responseBody = chatReply(
            """{"operation":"LOG_ACTIVITY","activityResolution":"NEW_ACTIVITY","proposedCanonicalName":"Flush water heater","confidenceBand":42}""",
        )
        val result = client().interpret(input) as InterpretationResult.Success
        assertEquals("Flush water heater", result.candidate.proposedCanonicalName)
        assertEquals(null, result.candidate.confidenceBand)
        assertEquals(null, result.candidate.matchedActivityId)
    }

    @Test
    fun `an invalid enum value is MALFORMED`() {
        responseBody = chatReply("""{"operation":"LOG_ACTIVITY","activityResolution":"PROBABLY"}""")
        assertEquals(failure(InterpreterFailureKind.MALFORMED), client().interpret(input))
    }

    @Test
    fun `non-JSON content is MALFORMED`() {
        responseBody = chatReply("Sure! You mowed the lawn.")
        assertEquals(failure(InterpreterFailureKind.MALFORMED), client().interpret(input))
    }

    @Test
    fun `HTTP 500 is OTHER and is not retried`() {
        status = 500
        responseBody = """{"error":"boom"}"""
        assertEquals(failure(InterpreterFailureKind.OTHER), client().interpret(input))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a request timeout is OTHER and is not retried`() {
        delayMs = 2_000
        responseBody = chatReply("{}")
        val started = System.nanoTime()
        assertEquals(failure(InterpreterFailureKind.OTHER), client(Duration.ofMillis(200)).interpret(input))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_900, "timeout was not honoured")
        assertEquals(1, requests.size)
    }

    @Test
    fun `a non-loopback base URL is refused`() {
        listOf(
            "http://192.168.1.20:11434",
            "http://10.0.0.5:11434",
            "http://example.com:11434",
            "http://localhost.example.com:11434",
            "http://127.0.0.1.nip.io:11434",
            "ftp://127.0.0.1:11434",
            "not a url",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { OllamaStandInClient(baseUrl = url) }
        }
        listOf("http://127.0.0.1:11434", "http://localhost:11434/", "http://[::1]:11434").forEach { url ->
            OllamaStandInClient(baseUrl = url) // accepted
        }
    }

    @Test
    fun `an unreachable server fails fast with guidance naming the URL`() {
        val freePort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val url = "http://127.0.0.1:$freePort"
        val started = System.nanoTime()
        val error = assertFailsWith<StandInSetupException> {
            OllamaStandInClient(baseUrl = url).requireModel()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 15_000, "took ${elapsedMs}ms; must be well under the 120 s request timeout")
        val message = error.message.orEmpty()
        assertTrue(message.contains("$url/api/tags"), message)
        assertTrue(message.contains("ollama serve"), message)
    }

    @Test
    fun `a missing model fails with ollama pull guidance and nothing is pulled`() {
        responseBody = tags("llama3.2:latest" to "sha256:aaa")
        val error = assertFailsWith<StandInSetupException> { client().requireModel() }
        assertTrue(error.message.orEmpty().contains("ollama pull ${OllamaStandInClient.DEFAULT_MODEL}"))
        assertEquals(listOf("GET /api/tags"), requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `a present model is found with its digest`() {
        responseBody = tags("llama3.2:latest" to "sha256:aaa", OllamaStandInClient.DEFAULT_MODEL to "sha256:bbb")
        assertEquals(OllamaModelInfo(OllamaStandInClient.DEFAULT_MODEL, "sha256:bbb"), client().requireModel())
    }

    @Test
    fun `model names without a tag mean latest`() {
        assertTrue(OllamaStandInClient.sameModel("gemma3:latest", "gemma3"))
        assertTrue(OllamaStandInClient.sameModel("gemma3n:e4b", "gemma3n:e4b"))
        assertTrue(!OllamaStandInClient.sameModel("gemma3n:e2b", "gemma3n:e4b"))
    }

    private fun tags(vararg models: Pair<String, String>): String = buildJsonObject {
        putJsonArray("models") {
            models.forEach { (name, digest) ->
                add(
                    buildJsonObject {
                        put("name", name)
                        put("model", name)
                        put("digest", digest)
                    },
                )
            }
        }
    }.toString()

    private fun failure(kind: InterpreterFailureKind) =
        InterpretationResult.Failure(kind, structuredResultJson = null)
}
