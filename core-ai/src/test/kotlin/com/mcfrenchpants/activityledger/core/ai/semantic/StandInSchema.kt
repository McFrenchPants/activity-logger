package com.mcfrenchpants.activityledger.core.ai.semantic

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import com.mcfrenchpants.activityledger.core.ai.InterpretationResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One field of [InterpretationResponse], as its `@Guide` annotation declares it. */
internal data class StandInSchemaField(
    val name: String,
    val description: String,
    /** The `@Guide(enumValues = ...)` list, in declared order; empty for free-text fields. */
    val enumValues: List<String>,
)

/**
 * The stand-in's view of the device's response schema, derived AT RUNTIME from the annotations on
 * [InterpretationResponse] -- never hand-copied, so it cannot drift from what ML Kit is given.
 *
 * - [jsonSchema] is the JSON Schema handed to Ollama's `format` (constrained decoding, the
 *   stand-in's equivalent of ML Kit's schema-constrained generation).
 * - [promptRendering] is appended to the user prompt when `INCLUDE_SCHEMA_IN_PROMPT` is on. It
 *   APPROXIMATES ML Kit's `includeSchemaInPrompt` text, whose exact wording is not public.
 */
internal object StandInSchema {

    /**
     * Constructor-parameter order of [InterpretationResponse]. Java reflection exposes parameter
     * annotations positionally and does not retain names, so this list maps position to name;
     * [fields] proves the mapping against the class's own getters every time it is built.
     */
    private val FIELD_ORDER = listOf(
        "operation",
        "activityResolution",
        "matchedActivityId",
        "proposedCanonicalName",
        "activityState",
        "temporalExpression",
        "confidenceBand",
    )

    /** Every schema field, in constructor order. */
    val fields: List<StandInSchemaField> by lazy { readFields() }

    /** The class-level `@Generable` description, if the annotation is visible at runtime. */
    val objectDescription: String? by lazy {
        InterpretationResponse::class.java.getAnnotation(Generable::class.java)
            ?.description
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * JSON Schema for one answer: an object with exactly the seven properties, each a string or
     * null, enum fields restricted to their `@Guide` values (or null). Every key is required so
     * the model always emits all seven; a value may still be null, mirroring the nullable
     * `String?` fields of [InterpretationResponse].
     */
    val jsonSchema: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            objectDescription?.let { put("description", it) }
            putJsonObject("properties") {
                fields.forEach { field ->
                    putJsonObject(field.name) {
                        putJsonArray("type") {
                            add(JsonPrimitive("string"))
                            add(JsonPrimitive("null"))
                        }
                        put("description", field.description)
                        if (field.enumValues.isNotEmpty()) {
                            putJsonArray("enum") {
                                field.enumValues.forEach { add(JsonPrimitive(it)) }
                                add(JsonNull)
                            }
                        }
                    }
                }
            }
            put("required", JsonArray(fields.map { JsonPrimitive(it.name) }))
            put("additionalProperties", false)
        }
    }

    /**
     * The schema spelled out in the prompt text. An approximation of ML Kit's
     * `includeSchemaInPrompt` rendering (not public), kept plain and deterministic.
     */
    val promptRendering: String by lazy {
        "\n\nAnswer with one JSON object that conforms to this JSON schema, and nothing else:\n" +
            PRETTY_JSON.encodeToString(JsonObject.serializer(), jsonSchema) + "\n"
    }

    /** The enum values [jsonSchema] declares for [fieldName] (null excluded), in order. */
    fun schemaEnumValues(fieldName: String): List<String> {
        val property = (jsonSchema["properties"] as JsonObject)[fieldName] as JsonObject
        val enum = property["enum"] as? JsonArray ?: return emptyList()
        return enum.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    }

    private val PRETTY_JSON = Json { prettyPrint = true }

    private fun readFields(): List<StandInSchemaField> {
        val type = InterpretationResponse::class.java
        val constructor = type.getDeclaredConstructor(*Array(FIELD_ORDER.size) { String::class.java })

        // Prove FIELD_ORDER against the class itself: construct it with each position holding
        // its own name, then read every property back through its getter.
        val probe = constructor.newInstance(*FIELD_ORDER.toTypedArray())
        FIELD_ORDER.forEach { name ->
            val getter = type.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
            check(getter.invoke(probe) == name) {
                "StandInSchema.FIELD_ORDER is out of step with InterpretationResponse at '$name'"
            }
        }

        return FIELD_ORDER.mapIndexed { index, name ->
            val guide = constructor.parameterAnnotations[index].filterIsInstance<Guide>().singleOrNull()
                ?: error("InterpretationResponse.$name carries no runtime-visible @Guide")
            StandInSchemaField(name, guide.description, guide.enumValues.toList())
        }
    }
}
