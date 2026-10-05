package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shape guard for the question Structured Output schema ([QuestionResponse]). */
class QuestionResponseSchemaTest {

    @Test
    fun `fields are exactly subject, action, dateWindow, kind`() {
        val constructor = QuestionResponse::class.java.getDeclaredConstructor(
            *Array(FIELD_ORDER.size) { String::class.java },
        )
        val markers = Array(FIELD_ORDER.size) { "p$it" }
        val probe = constructor.newInstance(*markers)
        FIELD_ORDER.forEachIndexed { index, name ->
            val getter = QuestionResponse::class.java.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
            assertEquals(markers[index], getter.invoke(probe), "field $name is at position $index")
        }
        val stringGetters = QuestionResponse::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
            .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }
            .toSet()
        assertEquals(FIELD_ORDER.toSet(), stringGetters)
    }

    @Test
    fun `every field carries a model-facing description`() {
        FIELD_ORDER.forEach { field ->
            val guide = guideOf(field)
            assertTrue(guide.description.isNotBlank(), "field $field has no description")
        }
    }

    @Test
    fun `subject, action and dateWindow are free text`() {
        listOf("subject", "action", "dateWindow").forEach { field ->
            assertTrue(guideOf(field).enumValues.isEmpty(), "field $field should be free text")
        }
    }

    @Test
    fun `kind is pinned to exactly the four kind spellings in order`() {
        assertEquals(listOf("LAST_TIME", "COUNT", "HOW_OFTEN", "LIST"), guideOf("kind").enumValues.toList())
    }

    @Test
    fun `schema version is 2`() {
        assertEquals(2, QUESTION_SCHEMA_VERSION)
    }

    private fun guideOf(field: String): Guide {
        val constructor = QuestionResponse::class.java.getDeclaredConstructor(
            *Array(FIELD_ORDER.size) { String::class.java },
        )
        val guide = constructor.parameterAnnotations[FIELD_ORDER.indexOf(field)]
            .filterIsInstance<Guide>().singleOrNull()
        assertNotNull(guide, "field $field carries no @Guide annotation")
        return guide
    }

    private companion object {
        val FIELD_ORDER = listOf("subject", "action", "dateWindow", "kind")
    }
}
