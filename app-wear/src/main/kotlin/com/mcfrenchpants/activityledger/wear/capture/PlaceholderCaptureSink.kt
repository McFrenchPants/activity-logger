package com.mcfrenchpants.activityledger.wear.capture

import java.util.UUID

/**
 * Returns a fresh id and stores nothing. Replaced by the durable outbox in WC1.5; until then a
 * spoken capture goes nowhere.
 */
class PlaceholderCaptureSink : CaptureSink {
    override suspend fun enqueue(transcript: WatchTranscript): String = UUID.randomUUID().toString()
}
