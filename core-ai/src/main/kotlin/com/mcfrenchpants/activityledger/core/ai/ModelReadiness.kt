package com.mcfrenchpants.activityledger.core.ai

/**
 * Whether this device can actually run on-device interpretation right now.
 *
 * Deliberately a plain Kotlin enum with no ML Kit type anywhere in it: ADR-023 keeps every
 * ML Kit type sealed inside core-ai, and a later Settings screen (Step 7) lives outside this
 * module but must be able to tell the user which of these states they are in.
 *
 * Only [READY] permits interpretation. Everything else is a distinct reason it cannot run,
 * and the three "the model itself is not usable yet" reasons ([NOT_INSTALLED],
 * [DOWNLOAD_IN_PROGRESS], [UNSUPPORTED_DEVICE]) are kept apart on purpose, because they need
 * three different things said to the user: offer a download, wait, or give up on this device.
 *
 * No constant carries a message, an exception or any captured content (AGENTS.md #11).
 */
enum class ModelReadiness {
    /** The model is installed AND structured output is supported. The only runnable state. */
    READY,

    /**
     * The model could be fetched for this device but is not present.
     *
     * The app never acts on this by itself: downloading is an explicit, user-initiated choice
     * (see [OnDeviceModelCapability.download]).
     */
    NOT_INSTALLED,

    /** A download of the model is already under way. Nothing to do but wait. */
    DOWNLOAD_IN_PROGRESS,

    /** This device cannot run the model at all, in any configuration. */
    UNSUPPORTED_DEVICE,

    /**
     * The model is installed, but this device's build of it cannot produce structured output.
     *
     * Interpretation depends on a schema-constrained response, so installation alone is never
     * readiness (AI_INTERPRETATION_SPEC section 16).
     */
    STRUCTURED_OUTPUT_UNSUPPORTED,

    /**
     * The capability check itself failed -- the underlying call threw rather than answering.
     *
     * Carries a state, never the exception or its message. Treated as not ready; a later check
     * may still succeed.
     */
    CHECK_FAILED,
}
