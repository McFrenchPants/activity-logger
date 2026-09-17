package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Guide
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Enum-drift guard for the Structured Output schema.
 *
 * The domain enums are the source of truth; the schema's `@Guide(enumValues = ...)` lists
 * are a hand-written copy the model is constrained by, and a copy can rot. These tests
 * read the annotation back at RUNTIME (the annotation is `@Retention(RUNTIME)`) and
 * reflect over the enums' own entries, so adding, renaming or reordering a constant in
 * core-domain without updating the schema fails here instead of silently teaching the
 * model a spelling the decoder will reject.
 *
 * Equality is asserted as an exact ordered list, not as a set: order is how the choices
 * are presented to the model, so a reorder is a real (if small) prompt change and should
 * be made deliberately.
 */
class InterpretationResponseSchemaTest {

    @Test
    fun `operation enumValues match InterpretationOperation entries exactly`() {
        assertEquals(
            InterpretationOperation.entries.map { it.name },
            guideFor("operation").enumValues.toList(),
        )
    }

    @Test
    fun `activityResolution enumValues match ActivityResolution entries exactly`() {
        assertEquals(
            ActivityResolution.entries.map { it.name },
            guideFor("activityResolution").enumValues.toList(),
        )
    }

    @Test
    fun `activityState enumValues match ActivityState entries exactly`() {
        assertEquals(
            ActivityState.entries.map { it.name },
            guideFor("activityState").enumValues.toList(),
        )
    }

    @Test
    fun `confidenceBand enumValues match ConfidenceBand entries exactly`() {
        assertEquals(
            ConfidenceBand.entries.map { it.name },
            guideFor("confidenceBand").enumValues.toList(),
        )
    }

    @Test
    fun `every schema field carries a model-facing description`() {
        FIELD_ORDER.forEach { field ->
            assertTrue(
                guideFor(field).description.isNotBlank(),
                "field $field has no @Guide description",
            )
        }
    }

    @Test
    fun `free-text fields pin no enumValues`() {
        listOf("matchedActivityId", "proposedCanonicalName", "temporalExpression").forEach {
            assertTrue(guideFor(it).enumValues.isEmpty(), "field $it should be free text")
        }
    }

    private companion object {
        /**
         * Constructor-parameter order of [InterpretationResponse]. Java reflection exposes
         * parameter annotations positionally, and parameter *names* are not retained by
         * default, so the mapping from name to position lives here.
         */
        val FIELD_ORDER = listOf(
            "operation",
            "activityResolution",
            "matchedActivityId",
            "proposedCanonicalName",
            "activityState",
            "temporalExpression",
            "confidenceBand",
        )

        fun guideFor(field: String): Guide {
            val index = FIELD_ORDER.indexOf(field)
            assertTrue(index >= 0, "unknown field $field")
            val constructor = InterpretationResponse::class.java.getDeclaredConstructor(
                *Array(FIELD_ORDER.size) { String::class.java },
            )
            val guide = constructor.parameterAnnotations[index]
                .filterIsInstance<Guide>()
                .singleOrNull()
            assertNotNull(guide, "field $field carries no @Guide annotation")
            return guide
        }
    }
}
