package com.mcfrenchpants.activityledger.wear.capture

import android.content.Context
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.FileOutboxStore
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import com.mcfrenchpants.activityledger.wear.RetryScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One per process, created lazily by the Application: owns the outbox and the send engine. */
class CaptureRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = DataLayerCaptureTransport(appContext)
    private val clock: () -> Long = System::currentTimeMillis

    val outbox = Outbox(FileOutboxStore(File(appContext.filesDir, "outbox")), clock)

    private val mutableAcks = MutableSharedFlow<CaptureAck>(extraBufferCapacity = 16)

    /** Acks the phone sent that matched a known capture; the screen forwards them to its controller. */
    val acks: SharedFlow<CaptureAck> = mutableAcks

    val ackHandler = AckHandler(outbox, transport) { mutableAcks.tryEmit(it) }
    private val sender = OutboxSender(outbox, transport, clock)
    private val drainLoop = DrainLoop(scope, sender::drain, RetryScheduler(appContext))

    private val startLock = Any()
    private var started = false

    /** Requests one serialized drain; a request during a drain causes exactly one more pass. */
    fun wake(): Job = drainLoop.wake()

    /** Idempotent: recovers records stuck mid-send once per process, then wakes the sender. */
    fun ensureStarted() {
        synchronized(startLock) {
            if (started) return
            started = true
            outbox.recoverOnStart()
        }
        wake()
    }

    fun newSink(): CaptureSink =
        OutboxCaptureSink(outbox, { UUID.randomUUID().toString() }, clock, onEnqueued = { wake() })
}
