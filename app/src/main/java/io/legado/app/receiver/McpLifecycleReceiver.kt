package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.service.McpService

/**
 * Boot / APK-update restore for MCP (VoiceLog BootReceiver pattern).
 *
 * PreferKey.mcpService keeps user intent across process death and package replace.
 * Uses goAsync + FGS start so work outlives the receiver and is allowed from
 * exempt background-start paths (BOOT_COMPLETED / MY_PACKAGE_REPLACED).
 */
class McpLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        try {
            McpService.restoreIfEnabled(context.applicationContext)
        } finally {
            pending.finish()
        }
    }
}
