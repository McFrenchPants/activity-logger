package com.mcfrenchpants.activityledger.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcfrenchpants.activityledger.wear.capture.RetryScheduling

/**
 * Deferred retry using only AlarmManager: an inexact alarm (no exact-alarm permission) that fires
 * [RetryAlarmReceiver]. One alarm id; scheduling replaces it, null cancels it.
 */
class RetryScheduler(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : RetryScheduling {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun scheduleAt(timeMillis: Long?) {
        val intent = pendingIntent()
        if (timeMillis == null) {
            alarmManager.cancel(intent)
            return
        }
        val at = maxOf(timeMillis, now() + MIN_DELAY_MILLIS)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(appContext, RetryAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val ALARM_ID = 1
        const val MIN_DELAY_MILLIS = 1_000L
    }
}

/** Not exported. Wakes the send engine when the retry alarm fires. Never logs. */
class RetryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        try {
            val runtime = (context.applicationContext as WatchApplication).runtime
            runtime.ensureStarted()
            runtime.wake().invokeOnCompletion { pending.finish() }
        } catch (e: Exception) {
            pending.finish()
        }
    }
}
