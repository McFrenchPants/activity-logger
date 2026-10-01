package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Guide
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shape and enum-drift guard for the extraction Structured Output schema ([ExtractionResponse]).
 *
 * Reads the `@Guide` annotations back at runtime, by constructor position proven against the
 * class's own getters, so a renamed, reordered, added or removed field -- or a domain enum
 * constant the schema forgot -- fails here instead of silently changing what the model is asked.
 */
class ExtractionResponseSchemaTest {

    @Test
    fun `fields are exactly these six, in this order, with no confidence`() {
        val constructor = ExtractionResponse::class.java.declaredConstructors
            .single { it.parameterCount == FIELD_ORDER.size && it.parameterTypes.all { t -> t == String::class.java } }
        val markers = Array(FIELD_ORDER.size) { "p$it" }
        val probe = constructor.newInstance(*markers)
        FIELD_ORDER.forEachIndexed { index, name ->
            val getter = ExtractionResponse::class.java.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
            assertEquals(markers[index], getter.invoke(probe), "field $name is at position $index")
        }
        val stringGetters = ExtractionResponse::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
            .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }
            .toSet()
        assertEquals(FIELD_ORDER.toSet(), stringGetters)
        assertTrue(stringGetters.none { it.contains("confidence", ignoreCase = true) })
    }

    @Test
    fun `operation enumValues match InterpretationOperation entries exactly`() {
        assertEquals(
            InterpretationOperation.entries.map { it.name },
            guideFor("operation").enumValues.toList(),
        )
    }

    @Test
    fun `activityState enumValues match ActivityState entries exactly`() {
        assertEquals(ActivityState.entries.map { it.name }, guideFor("activityState").enumValues.toList())
    }

    @Test
    fun `every schema field carries a model-facing description`() {
        FIELD_ORDER.forEach { field ->
            assertTrue(guideFor(field).description.isNotBlank(), "field $field has no @Guide description")
        }
    }

    @Test
    fun `free-text fields pin no enumValues`() {
        listOf("subject", "action", "temporalExpression", "durationExpression").forEach {
            assertTrue(guideFor(it).enumValues.isEmpty(), "field $it should be free text")
        }
    }

    private companion object {
        /** Constructor-parameter order of [ExtractionResponse]. */
        val FIELD_ORDER = listOf(
            "operation",
            "subject",
            "action",
            "activityState",
            "temporalExpression",
            "durationExpression",
        )

        fun guideFor(field: String): Guide {
            val index = FIELD_ORDER.indexOf(field)
            assertTrue(index >= 0, "unknown field $field")
            val constructor = ExtractionResponse::class.java.getDeclaredConstructor(
                *Array(FIELD_ORDER.size) { String::class.java },
            )
            val guide = constructor.parameterAnnotations[index].filterIsInstance<Guide>().singleOrNull()
            assertNotNull(guide, "field $field carries no @Guide annotation")
            return guide
        }
    }
}
