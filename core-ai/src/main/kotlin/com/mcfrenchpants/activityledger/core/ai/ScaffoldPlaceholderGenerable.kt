package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * SCAFFOLD PLACEHOLDER -- no product meaning.
 *
 * This type models nothing. It exists only so the ML Kit GenAI schema compiler
 * (`com.google.mlkit:genai-schema-compiler`, alpha) has one `@Generable` class to
 * process, proving the annotation processor actually runs as a KSP processor in
 * this build. The real Structured Output schema for interpretation results is a
 * separate backlog item and is deliberately NOT guessed at here.
 *
 * ADR-023 containment: this class, and every ML Kit import in this repository,
 * must stay inside `core-ai`.
 *
 * It is `public` and not `internal`, which is forced, not preferred. The schema
 * compiler generates a `public class <Name>_GeneratedProvider` in this same package
 * whose members reference the annotated type, so an `internal` @Generable class
 * fails to compile: "'public' property exposes its 'internal' type argument". A
 * @Generable type is therefore always part of this module's public surface. Its own
 * members must stay plain Kotlin types (they do here: one String), and no
 * hand-written API of core-ai may accept or return one, so the ML Kit surface still
 * ends at this module's boundary.
 *
 * Delete this file when the real schema lands.
 */
@Generable(description = "Scaffold placeholder with no product meaning.")
data class ScaffoldPlaceholderGenerable(
    // Explicit `@param:` target: Kotlin 2.3 warns that a bare constructor-parameter
    // annotation will also apply to the backing field in a future release, and the
    // schema compiler reads it from the value parameter.
    @param:Guide(description = "Scaffold placeholder field with no product meaning.")
    val placeholder: String,
)
