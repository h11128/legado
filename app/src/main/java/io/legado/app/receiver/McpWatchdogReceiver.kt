package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.constant.PreferKey
import io.legado.app.service.McpService
import io.legado.app.service.McpWatchdog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.web.mcp.McpChannelGuard

/**
 * AlarmManager tick: if the user left MCP enabled but the process/service died, restore it.
 * Re-arms the next one-shot while the preference remains on.
 */
class McpWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TICK) return
        val app = context.applicationContext
        val pending = goAsync()
        try {
            McpService.restoreIfEnabled(app)
            // Recover wedged debug/check gates left by hung tool calls (thread 59f4efb9).
            McpChannelGuard.forceReleaseStale()
            if (app.getPrefBoolean(PreferKey.mcpService, false)) {
                McpWatchdog.schedule(app)
            }
        } finally {
            pending.finish()
        }
    }

    companion object {
        const val ACTION_TICK = "io.legado.app.action.MCP_WATCHDOG_TICK"
    }
}
