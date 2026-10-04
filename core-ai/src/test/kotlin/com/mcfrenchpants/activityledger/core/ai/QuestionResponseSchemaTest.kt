package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shape guard for the question Structured Output schema ([QuestionResponse]). */
class QuestionResponseSchemaTest {

    @Test
    fun `fields are exactly subject then action`() {
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
    fun `every field carries a model-facing description and is free text`() {
        FIELD_ORDER.forEachIndexed { index, field ->
            val constructor = QuestionResponse::class.java.getDeclaredConstructor(
                *Array(FIELD_ORDER.size) { String::class.java },
            )
            val guide = constructor.parameterAnnotations[index].filterIsInstance<Guide>().singleOrNull()
            assertNotNull(guide, "field $field carries no @Guide annotation")
            assertTrue(guide.description.isNotBlank(), "field $field has no description")
            assertTrue(guide.enumValues.isEmpty(), "field $field should be free text")
        }
    }

    @Test
    fun `schema version is 1`() {
        assertEquals(1, QUESTION_SCHEMA_VERSION)
    }

    private companion object {
        val FIELD_ORDER = listOf("subject", "action")
    }
}
