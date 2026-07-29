package io.legado.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.legado.app.receiver.McpWatchdogReceiver
import splitties.systemservices.alarmManager

/**
 * Lightweight AlarmManager watchdog (VoiceLog uses WorkManager; Legado avoids that dep).
 * While PreferKey.mcpService is on, schedule the next tick ~3 minutes out (one-shot,
 * re-armed by [McpWatchdogReceiver]) so Doze is less likely to stretch a repeating alarm
 * and wedged debug/check channels (STALE_* ≈ 180s) are cleared soon after.
 */
object McpWatchdog {
    private const val REQUEST_CODE = 0x4D4350 // 'MCP'
    const val INTERVAL_MS = 3 * 60 * 1000L

    fun schedule(context: Context) {
        val am = alarmManager
        val pi = pendingIntent(context)
        am.cancel(pi)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, McpWatchdogReceiver::class.java).apply {
            action = McpWatchdogReceiver.ACTION_TICK
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
