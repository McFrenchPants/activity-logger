package com.mcfrenchpants.activityledger.wear.capture

/** What happened to one send attempt, as far as the watch can tell. */
enum class SendOutcome {
    /** The capture was handed to the link to the phone; the phone's ack is still awaited. */
    DELIVERED_TO_LINK,

    /** Could not hand it over now (phone not connected, link error); try again later. */
    TRANSIENT_FAILURE,

    /** The capture can never be sent (for example it is refused outright). */
    PERMANENT_FAILURE,
}

/** The watch-to-phone link. The real Data Layer implementation arrives with WC1.5b. */
interface CaptureTransport {
    /**
     * Sends [envelopeJson] for [captureId]. [attempt] differs on every call: the Data Layer drops a
     * DataItem whose content is unchanged (see CAPTURE_ATTEMPT_KEY), so it must be written into the item.
     */
    suspend fun send(captureId: String, envelopeJson: String, attempt: Long): SendOutcome

    /** Removes the capture's DataItem after a final outcome. Must never throw into the sender. */
    suspend fun retract(captureId: String)
}
