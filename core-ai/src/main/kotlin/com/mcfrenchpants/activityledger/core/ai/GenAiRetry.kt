package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.GenAiException

/**
 * True when ML Kit itself indicated that waiting and asking again could work: the request was
 * refused as [GenAiException.ErrorCode.BUSY] (whatever its retry delay), or it carries a
 * positive retry delay.
 *
 * Only the error code and the duration are read -- never the message or the cause.
 *
 * Shared by [GeminiNanoActivityInterpreter] and [GeminiNanoActivityExtractor] so both map a
 * refusal identically. `internal` because it names an ML Kit type (ADR-023).
 */
internal fun GenAiException.isWorthRetrying(): Boolean =
    errorCode == GenAiException.ErrorCode.BUSY ||
        (!retryDelay.isZero && !retryDelay.isNegative)
