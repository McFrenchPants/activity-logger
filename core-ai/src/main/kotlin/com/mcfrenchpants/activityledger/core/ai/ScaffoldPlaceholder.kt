package com.mcfrenchpants.activityledger.core.ai

/**
 * SCAFFOLD PLACEHOLDER -- no product meaning.
 *
 * The only public surface of `core-ai` in this scaffold, and deliberately trivial.
 * It exists to demonstrate the ADR-023 shape the real module must keep: everything
 * ML Kit stays `internal`, and only plain Kotlin/domain types cross the module
 * boundary, so a backward-incompatible change in the alpha Structured Output
 * library is a repair confined to this one module.
 *
 * No prompting, inference, response parsing or validation lives here yet -- that is
 * the AI vertical-slice backlog item. Nothing here logs or transmits user content.
 */
object ScaffoldPlaceholder {

    /** Returns a fixed marker string. Carries no product meaning. */
    fun marker(): String = "core-ai scaffold placeholder"

    /**
     * Placeholder for ADR-023 capability detection, which will use ML Kit's
     * `checkStatus()` plus `isStructuredOutputFeatureAvailable()`. Nothing is
     * detected yet; this always reports "not available" so no caller can mistake
     * the scaffold for a working feature. Note the return type is a plain Kotlin
     * Boolean, not an ML Kit status type -- that is the containment rule in
     * miniature.
     */
    fun isScaffoldCapabilityAvailable(): Boolean = false
}
