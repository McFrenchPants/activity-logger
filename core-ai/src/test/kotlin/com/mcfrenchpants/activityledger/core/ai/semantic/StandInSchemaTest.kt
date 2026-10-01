package com.mcfrenchpants.activityledger.core.ai.semantic

import com.google.mlkit.genai.schema.annotations.Guide
import com.mcfrenchpants.activityledger.core.ai.ExtractionResponse
import com.mcfrenchpants.activityledger.core.ai.InterpretationResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift guard for the stand-in's JSON Schema: its enum constraints must be exactly the
 * `@Guide(enumValues = ...)` lists on [InterpretationResponse], read here independently of
 * [StandInSchema] (by getter-proven constructor position), so a schema change on the device side
 * can never leave the stand-in constraining the model to stale spellings.
 */
class StandInSchemaTest {

    @Test
    fun `schema enums equal the InterpretationResponse Guide enumValues for every field`() {
        val annotated = annotatedGuides()
        assertEquals(7, annotated.size)
        annotated.forEach { (name, guide) ->
            assertEquals(
                guide.enumValues.toList(),
                StandInSchema.schemaEnumValues(name),
                "enum drift on field $name",
            )
        }
        // The four enum fields really are constrained (guards against an all-empty comparison).
        listOf("operation", "activityResolution", "activityState", "confidenceBand").forEach {
            assertTrue(StandInSchema.schemaEnumValues(it).isNotEmpty(), "field $it lost its enum")
        }
    }

    @Test
    fun `schema has exactly the seven fields, all required, each string or null`() {
        val schema = StandInSchema.jsonSchema
        assertEquals(JsonPrimitive("object"), schema["type"])
        val properties = schema["properties"] as JsonObject
        val names = annotatedGuides().map { it.first }
        assertEquals(names, properties.keys.toList())
        assertEquals(names, (schema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        properties.forEach { (name, property) ->
            val type = ((property as JsonObject)["type"] as JsonArray).map { (it as JsonPrimitive).content }
            assertEquals(listOf("string", "null"), type, "type of $name")
        }
    }

    @Test
    fun `schema descriptions are the Guide descriptions`() {
        val properties = StandInSchema.jsonSchema["properties"] as JsonObject
        annotatedGuides().forEach { (name, guide) ->
            val description = ((properties[name] as JsonObject)["description"] as JsonPrimitive).content
            assertEquals(guide.description, description, "description of $name")
        }
    }

    @Test
    fun `prompt rendering contains the schema`() {
        val rendering = StandInSchema.promptRendering
        StandInSchema.fields.forEach { assertTrue(rendering.contains("\"${it.name}\""), it.name) }
    }

    // ---- extraction schema (prompt v4) ------------------------------------------------------

    @Test
    fun `extraction schema enums equal the ExtractionResponse Guide enumValues for every field`() {
        val annotated = annotatedGuides(ExtractionResponse::class.java, 6)
        assertEquals(6, annotated.size)
        annotated.forEach { (name, guide) ->
            assertEquals(
                guide.enumValues.toList(),
                StandInSchema.extraction.schemaEnumValues(name),
                "enum drift on extraction field $name",
            )
        }
        listOf("operation", "activityState").forEach {
            assertTrue(StandInSchema.extraction.schemaEnumValues(it).isNotEmpty(), "field $it lost its enum")
        }
        listOf("subject", "action", "temporalExpression", "durationExpression").forEach {
            assertTrue(StandInSchema.extraction.schemaEnumValues(it).isEmpty(), "field $it should be free text")
        }
    }

    @Test
    fun `extraction schema has exactly the six fields, all required, each string or null, with Guide descriptions`() {
        val schema = StandInSchema.extraction.jsonSchema
        assertEquals(JsonPrimitive("object"), schema["type"])
        val properties = schema["properties"] as JsonObject
        val annotated = annotatedGuides(ExtractionResponse::class.java, 6)
        val names = annotated.map { it.first }
        assertEquals(
            listOf("operation", "subject", "action", "activityState", "temporalExpression", "durationExpression"),
            names,
        )
        assertEquals(names, properties.keys.toList())
        assertEquals(names, (schema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        annotated.forEach { (name, guide) ->
            val property = properties[name] as JsonObject
            val type = (property["type"] as JsonArray).map { (it as JsonPrimitive).content }
            assertEquals(listOf("string", "null"), type, "type of $name")
            assertEquals(guide.description, (property["description"] as JsonPrimitive).content, "description of $name")
        }
    }

    @Test
    fun `extraction prompt rendering contains the extraction schema and differs from the interpretation one`() {
        val rendering = StandInSchema.extraction.promptRendering
        StandInSchema.extraction.fields.forEach { assertTrue(rendering.contains("\"${it.name}\""), it.name) }
        assertTrue(!rendering.contains("confidenceBand"))
        assertTrue(rendering != StandInSchema.promptRendering)
    }

    @Test
    fun `the facade still is the interpretation schema`() {
        assertEquals(StandInSchema.interpretation.jsonSchema, StandInSchema.jsonSchema)
        assertEquals(StandInSchema.interpretation.promptRendering, StandInSchema.promptRendering)
        assertEquals(StandInSchema.interpretation.fields, StandInSchema.fields)
    }

    /** (name, @Guide) pairs, positions proven by constructing the class and reading its getters. */
    private fun annotatedGuides(): List<Pair<String, Guide>> = annotatedGuides(InterpretationResponse::class.java, 7)

    /** As [annotatedGuides], for any all-String response class with [count] fields. */
    private fun annotatedGuides(type: Class<*>, count: Int): List<Pair<String, Guide>> {
        val constructor = type.getDeclaredConstructor(*Array(count) { String::class.java })
        val markers = Array(count) { "p$it" }
        val probe = constructor.newInstance(*markers)
        val getters = type.methods.filter {
            it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java
        }
        return markers.indices.map { index ->
            val getter = getters.single { it.invoke(probe) == markers[index] }
            val name = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            val guide = constructor.parameterAnnotations[index].filterIsInstance<Guide>().single()
            name to guide
        }
    }
}
