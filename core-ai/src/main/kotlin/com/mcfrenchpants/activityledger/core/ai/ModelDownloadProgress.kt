package com.mcfrenchpants.activityledger.core.ai

/**
 * Progress of an explicitly user-initiated model download, in plain Kotlin terms.
 *
 * A translation of ML Kit's own download status into types nothing outside core-ai needs ML Kit
 * to understand (ADR-023). Byte counts are passed through unchanged so a UI can show real
 * progress against the size it quoted the user.
 */
sealed interface ModelDownloadProgress {

    /** The download has begun. [bytesToDownload] is the total size ML Kit reported. */
    data class Started(val bytesToDownload: Long) : ModelDownloadProgress

    /** [totalBytesDownloaded] bytes have arrived so far. */
    data class Progress(val totalBytesDownloaded: Long) : ModelDownloadProgress

    /** The model finished downloading. Readiness still has to be re-checked separately. */
    data object Completed : ModelDownloadProgress

    /**
     * The download failed.
     *
     * Carries only ML Kit's numeric error code -- never an exception, a stack trace or a
     * message, because a message can contain arbitrary text and must not escape into a value
     * the app may surface or store (AGENTS.md #11). [errorCode] is opaque here on purpose: it
     * is for distinguishing failures, not for display.
     */
    data class Failed(val errorCode: Int) : ModelDownloadProgress
}
