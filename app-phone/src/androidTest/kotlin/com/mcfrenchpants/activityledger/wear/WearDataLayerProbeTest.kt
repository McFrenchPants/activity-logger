package com.mcfrenchpants.activityledger.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hand-run measurement of the Wear Data Layer round trip (phone <-> watch), NOT a regression
 * gate. Needs the debug app-wear build (which carries the echo service) installed on a paired
 * watch. Output tag: `WearDataLayerProbe`; read it with `adb logcat -d -s WearDataLayerProbe`.
 *
 * Reports only whether a watch is connected (never ids or names), then whether
 * /probe/ping -> /probe/pong and /probe/item -> /probe/item-echo complete within a bounded wait.
 * "No watch connected" is a valid answer and passes. Payloads are fixed probe strings
 * (AGENTS.md #11: no user content, no device identifiers in any log line).
 */
@RunWith(AndroidJUnit4::class)
class WearDataLayerProbeTest {

    @Test
    fun reportWearDataLayerRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val nodeCount = try {
            Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                TIMEOUT_SECONDS, TimeUnit.SECONDS,
            ).size
        } catch (t: Throwable) {
            report("nodeLookup", "THREW_${t.javaClass.simpleName}")
            assertTrue("node lookup threw ${t.javaClass.simpleName}", false)
            return
        }
        if (nodeCount == 0) {
            report("watchConnected", "false (no watch connected)")
            return
        }
        report("watchConnected", "true")

        val pingOutcome = probeMessageRoundTrip(context)
        report("messagePingPong", pingOutcome)
        val itemOutcome = probeDataItemRoundTrip(context)
        report("dataItemEcho", itemOutcome)

        assertTrue(
            "message=$pingOutcome item=$itemOutcome",
            pingOutcome != OUTCOME_ERROR && itemOutcome != OUTCOME_ERROR,
        )
    }

    private fun probeMessageRoundTrip(context: Context): String {
        val client: MessageClient = Wearable.getMessageClient(context)
        val pong = CountDownLatch(1)
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == PATH_PONG) pong.countDown()
        }
        var registered = false
        try {
            Tasks.await(client.addListener(listener), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            registered = true
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                TIMEOUT_SECONDS, TimeUnit.SECONDS,
            )
            val start = System.nanoTime()
            for (node in nodes) {
                Tasks.await(
                    client.sendMessage(node.id, PATH_PING, PAYLOAD.toByteArray(Charsets.UTF_8)),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS,
                )
            }
            return if (pong.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "RECEIVED roundTripMs=${elapsedMs(start)}"
            } else {
                OUTCOME_TIMEOUT
            }
        } catch (t: Throwable) {
            Log.w(TAG, "message probe threw ${t.javaClass.simpleName}")
            return OUTCOME_ERROR
        } finally {
            if (registered) {
                removeQuietly {
                    Tasks.await(client.removeListener(listener), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun probeDataItemRoundTrip(context: Context): String {
        val client: DataClient = Wearable.getDataClient(context)
        val nonce = System.currentTimeMillis()
        val echoed = CountDownLatch(1)
        val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != PATH_ITEM_ECHO) continue
                if (DataMapItem.fromDataItem(event.dataItem).dataMap.getLong(KEY_NONCE) == nonce) {
                    echoed.countDown()
                }
            }
        }
        var registered = false
        try {
            Tasks.await(client.addListener(listener), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            registered = true
            val request = PutDataMapRequest.create(PATH_ITEM).apply {
                // Nonce makes every run a real change; identical items are not re-delivered.
                dataMap.putLong(KEY_NONCE, nonce)
                dataMap.putString(KEY_PAYLOAD, PAYLOAD)
            }.asPutDataRequest().setUrgent()
            val start = System.nanoTime()
            Tasks.await(client.putDataItem(request), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return if (echoed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "RECEIVED roundTripMs=${elapsedMs(start)}"
            } else {
                OUTCOME_TIMEOUT
            }
        } catch (t: Throwable) {
            Log.w(TAG, "data item probe threw ${t.javaClass.simpleName}")
            return OUTCOME_ERROR
        } finally {
            if (registered) {
                removeQuietly {
                    Tasks.await(client.removeListener(listener), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            // Clean up the probe items so repeated runs leave nothing behind.
            removeQuietly {
                val item = Uri.Builder().scheme("wear").authority("*").path(PATH_ITEM).build()
                val echo = Uri.Builder().scheme("wear").authority("*").path(PATH_ITEM_ECHO).build()
                Tasks.await(client.deleteDataItems(item), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Tasks.await(client.deleteDataItems(echo), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun removeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup threw ${t.javaClass.simpleName}")
        }
    }

    private fun report(check: String, result: String) {
        Log.i(TAG, "RESULT $check=$result")
    }

    private companion object {
        const val TAG = "WearDataLayerProbe"
        const val TIMEOUT_SECONDS = 10L
        const val PATH_PING = "/probe/ping"
        const val PATH_PONG = "/probe/pong"
        const val PATH_ITEM = "/probe/item"
        const val PATH_ITEM_ECHO = "/probe/item-echo"
        const val KEY_NONCE = "nonce"
        const val KEY_PAYLOAD = "payload"
        const val PAYLOAD = "wd1-probe-payload"
        const val OUTCOME_TIMEOUT = "TIMEOUT"
        const val OUTCOME_ERROR = "ERROR"
    }
}
