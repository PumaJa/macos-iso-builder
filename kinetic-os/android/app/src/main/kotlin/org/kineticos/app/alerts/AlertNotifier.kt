package org.kineticos.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.kineticos.app.MainActivity
import org.kineticos.app.R
import org.kineticos.app.graph
import org.kineticos.app.net.Alert
import java.util.concurrent.TimeUnit

/**
 * Telematics alerts (geofence, movement/theft, low battery, fault, maintenance) as Android notifications on the
 * monitoring phone. Two paths, both without any third-party push service:
 *  1. live: WebSocket `alert` messages while the app process runs (seconds latency),
 *  2. background: WorkManager polls `/telematics/{id}/alerts?since=` every 15 min (Android's minimum) – survives reboots.
 * Duplicate alerts are suppressed with a per-vehicle "last seen" timestamp.
 */
object AlertNotifier {
    const val CHANNEL = "telematics_alerts"
    private const val PREFS = "alert_state"
    private const val WORK = "telematics-alert-poll"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Vehicle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Geofence, movement, low battery and fault alerts from your vehicles"
            })
    }

    private fun lastSeen(ctx: Context, vid: String) = ctx.getSharedPreferences(PREFS, 0).getLong(vid, 0L)
    private fun setLastSeen(ctx: Context, vid: String, ts: Long) = ctx.getSharedPreferences(PREFS, 0).edit().putLong(vid, ts).apply()

    /** Posts a notification if this alert is newer than the last one shown for the vehicle. Returns true if shown. */
    fun show(ctx: Context, vehicleName: String, vehicleId: String, a: Alert): Boolean {
        if (a.ts <= lastSeen(ctx, vehicleId)) return false
        setLastSeen(ctx, vehicleId, a.ts)
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val title = when (a.kind) {
            "geofence_exit" -> "⚠ $vehicleName left the geofence"
            "geofence_enter" -> "$vehicleName entered a geofence"
            "movement" -> "⚠ $vehicleName is moving"
            "low_battery" -> "$vehicleName: battery low"
            "fault" -> "$vehicleName: controller fault"
            "maintenance" -> "$vehicleName: maintenance due"
            "theft" -> "🚨 $vehicleName is being moved while LOCKED"
            else -> "$vehicleName: ${a.kind}"
        }
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("screen", "REMOTE"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title)
            .setContentText(a.message).setStyle(NotificationCompat.BigTextStyle().bigText(a.message)).setWhen(a.ts).setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(pi).build()
        runCatching { NotificationManagerCompat.from(ctx).notify((vehicleId + a.id).hashCode(), n) }
        return true
    }

    /** Never throws: a WorkManager problem must not take down the cockpit runtime (live WS alerts still work). */
    fun schedule(ctx: Context, enabled: Boolean) = runCatching { scheduleInternal(ctx, enabled) }.isSuccess

    private fun scheduleInternal(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<AlertPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Polls all own vehicles once. Used by the worker and testable without WorkManager. */
    suspend fun pollOnce(ctx: Context): Int {
        val g = ctx.graph
        val s = g.settingsRepo.current()
        if (s.authToken == null || s.serverUrl.isBlank() || !s.alertNotifications) return 0
        var shown = 0
        for (v in g.vehicles.first()) {
            val since = lastSeen(ctx, v.id)
            val alerts = runCatching { g.api.alerts(v.id, since) }.getOrNull() ?: continue
            alerts.sortedBy { it.ts }.forEach { if (show(ctx, v.name, v.id, it)) shown++ }
        }
        return shown
    }
}

class AlertPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching { AlertNotifier.pollOnce(applicationContext); Result.success() }.getOrElse { Result.retry() }
}
