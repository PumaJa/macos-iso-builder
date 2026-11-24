package org.kineticos.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.kineticos.app.MainActivity
import org.kineticos.app.R
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.graph

/**
 * Foreground service keeping the VESC link, GPS, recording and telematics alive while the screen is off or the app is
 * in the background. It never controls the motor – it only reads telemetry and (via SafetyGate) applies ride modes.
 */
class CockpitService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cockpit", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 1, build("Starting…"), type)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kinetic:cockpit").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        graph.startRuntime()
        scope.launch {
            combine(graph.connection.status, graph.recorder.active) { s, r -> s to r }.collect { (s, r) ->
                val text = when (s) {
                    is ConnStatus.Online -> (if (s.simulated) "SIMULATION · " else "") + s.description + if (r != null) " · recording" else ""
                    is ConnStatus.Connecting -> "Connecting (${s.target})…"
                    is ConnStatus.Reconnecting -> "Reconnecting in ${s.inMs / 1000}s"
                    is ConnStatus.Error -> s.message
                    ConnStatus.Idle -> "Not connected"
                }
                nm.notify(1, build(text))
            }
        }
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setContentTitle("Kinetic OS").setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).setContentIntent(pi).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { scope.launch { graph.recorder.stop(); stopSelf() } }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    companion object {
        const val CHANNEL = "cockpit"; const val ACTION_STOP = "stop"
        fun start(ctx: Context) {
            val i = Intent(ctx, CockpitService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
