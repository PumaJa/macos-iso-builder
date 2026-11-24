package org.kineticos.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kineticos.app.MainActivity
import org.kineticos.app.graph

/**
 * Starts the cockpit after boot if enabled. Android 10+ blocks activity starts from the background, so only the
 * foreground service is started (it auto-connects); the UI opens when the user unlocks, or immediately when the app
 * is the device's HOME/kiosk launcher.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (context.graph.settingsRepo.current().autoStartOnBoot) {
                    runCatching { CockpitService.start(context) }
                    runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } finally { pending.finish() }
        }
    }
}
