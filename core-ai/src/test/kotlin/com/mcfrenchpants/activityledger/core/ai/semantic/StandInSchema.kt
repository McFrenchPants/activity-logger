package com.mcfrenchpants.activityledger.core.ai.semantic

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import com.mcfrenchpants.activityledger.core.ai.ExtractionResponse
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

/** One field of a `@Generable` response class, as its `@Guide` annotation declares it. */
internal data class StandInSchemaField(
    val name: String,
    val description: String,
    /** The `@Guide(enumValues = ...)` list, in declared order; empty for free-text fields. */
    val enumValues: List<String>,
)

/**
 * The stand-in's view of one device response schema, derived AT RUNTIME from the annotations on
 * a `@Generable` response class ([InterpretationResponse] or [ExtractionResponse]) -- never
 * hand-copied, so it cannot drift from what ML Kit is given.
 *
 * - [jsonSchema] is the JSON Schema handed to Ollama's `format` (constrained decoding, the
 *   stand-in's equivalent of ML Kit's schema-constrained generation).
 * - [promptRendering] is appended to the user prompt when `INCLUDE_SCHEMA_IN_PROMPT` is on. It
 *   APPROXIMATES ML Kit's `includeSchemaInPrompt` text, whose exact wording is not public.
 *
 * @param type The response class; every constructor parameter must be a `String?`.
 * @param fieldOrder Its constructor-parameter order. Java reflection exposes parameter
 *   annotations positionally and does not retain names, so this list maps position to name;
 *   [fields] proves the mapping against the class's own getters every time it is built.
 */
internal class StandInResponseSchema(
    private val type: Class<*>,
    private val fieldOrder: List<String>,
) {

    /** Every schema field, in constructor order. */
    val fields: List<StandInSchemaField> by lazy { readFields() }

    /** The class-level `@Generable` description, if the annotation is visible at runtime. */
    val objectDescription: String? by lazy {
        type.getAnnotation(Generable::class.java)
            ?.description
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * JSON Schema for one answer: an object with exactly the class's properties, each a string
     * or null, enum fields restricted to their `@Guide` values (or null). Every key is required
     * so the model always emits all of them; a value may still be null, mirroring the nullable
     * `String?` fields of the response class.
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

    private fun readFields(): List<StandInSchemaField> {
        val constructor = type.getDeclaredConstructor(*Array(fieldOrder.size) { String::class.java })

        // Prove fieldOrder against the class itself: construct it with each position holding
        // its own name, then read every property back through its getter.
        val probe = constructor.newInstance(*fieldOrder.toTypedArray())
        fieldOrder.forEach { name ->
            val getter = type.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
            check(getter.invoke(probe) == name) {
                "StandInSchema field order is out of step with ${type.simpleName} at '$name'"
            }
        }

        return fieldOrder.mapIndexed { index, name ->
            val guide = constructor.parameterAnnotations[index].filterIsInstance<Guide>().singleOrNull()
                ?: error("${type.simpleName}.$name carries no runtime-visible @Guide")
            StandInSchemaField(name, guide.description, guide.enumValues.toList())
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}

/**
 * The stand-in's response schemas.
 *
 * Its own members ([fields], [objectDescription], [jsonSchema], [promptRendering],
 * [schemaEnumValues]) are the [interpretation] schema -- exactly what they were before the
 * extraction schema was added -- so the v3 stand-in recorder is unchanged. [extraction] is the
 * prompt-v4 schema derived from [ExtractionResponse] (ADR-038).
 */
internal object StandInSchema {

    /** Derived from [InterpretationResponse] (prompt v3). */
    val interpretation: StandInResponseSchema = StandInResponseSchema(
        InterpretationResponse::class.java,
        listOf(
            "operation",
            "activityResolution",
            "matchedActivityId",
            "proposedCanonicalName",
            "activityState",
            "temporalExpression",
            "confidenceBand",
        ),
    )

    /** Derived from [ExtractionResponse] (prompt v4). */
    val extraction: StandInResponseSchema = StandInResponseSchema(
        ExtractionResponse::class.java,
        listOf(
            "operation",
            "subject",
            "action",
            "activityState",
            "temporalExpression",
            "durationExpression",
        ),
    )

    /** [interpretation]'s fields, in constructor order. */
    val fields: List<StandInSchemaField> get() = interpretation.fields

    /** [interpretation]'s class-level description. */
    val objectDescription: String? get() = interpretation.objectDescription

    /** [interpretation]'s JSON Schema. */
    val jsonSchema: JsonObject get() = interpretation.jsonSchema

    /** [interpretation]'s schema spelled out for the prompt. */
    val promptRendering: String get() = interpretation.promptRendering

    /** The enum values [interpretation]'s schema declares for [fieldName] (null excluded), in order. */
    fun schemaEnumValues(fieldName: String): List<String> = interpretation.schemaEnumValues(fieldName)
}
