package org.kineticos.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.kineticos.app.MainActivity
import org.kineticos.app.R
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.core.VehicleConnection
import org.kineticos.app.data.AppSettings
import org.kineticos.telemetry.RideWarning
import org.kineticos.telemetry.WarnLevel
import org.kineticos.telemetry.WarningEngine
import java.util.Locale

/**
 * Rider warnings on the cockpit phone: spoken (TextToSpeech), vibration, and – for critical ones – a heads-up
 * notification that brings the cockpit back even when a navigation app is in front. Keeps a history for the UI.
 */
class WarningCenter(private val ctx: Context, private val scope: CoroutineScope, private val conn: VehicleConnection, private val settings: StateFlow<AppSettings>) {
    private val engine = WarningEngine()
    private val _history = MutableStateFlow<List<RideWarning>>(emptyList()); val history: StateFlow<List<RideWarning>> = _history
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpeedKmh = 0.0

    fun start() {
        scope.launch { conn.sample.filterNotNull().collect { s -> lastSpeedKmh = (s.speedMps.value ?: 0.0) * 3.6; engine.evaluate(s).forEach { emit(it) } } }
        scope.launch {
            conn.status.collect { st ->
                when (st) {
                    is ConnStatus.Reconnecting -> engine.linkLost(System.currentTimeMillis(), lastSpeedKmh, conn.isSimulated)?.let { emit(it) }
                    is ConnStatus.Online -> engine.linkRestored()
                    else -> {}
                }
            }
        }
        // anti-theft: tamper flag from the vehicle script (rising edge) → critical warning
        scope.launch {
            conn.scriptStatus.map { it?.lock?.let { l -> l.locked && l.tamper } ?: false }.distinctUntilChanged().collect { t ->
                if (t) emit(RideWarning(System.currentTimeMillis(), WarnLevel.CRITICAL, "theft", "Vehicle is being moved while locked", conn.isSimulated))
            }
        }
    }

    fun clearHistory() { _history.value = emptyList() }

    private fun emit(w: RideWarning) {
        _history.update { (listOf(w) + it).take(200) }
        val s = settings.value
        if (s.voiceAlerts) speak((if (w.simulated) "Simulation. " else "") + w.text)
        if (s.vibrateAlerts) vibrate(w.level)
        if (w.level == WarnLevel.CRITICAL) notifyCritical(w)
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null) { tts = TextToSpeech(ctx) { st -> ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) { tts?.language = Locale.getDefault(); tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "w") } }; return }
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_ADD, null, "w${System.nanoTime()}")
    }

    private fun vibrate(level: WarnLevel) = runCatching {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return@runCatching
        val pattern = if (level == WarnLevel.CRITICAL) longArrayOf(0, 300, 150, 300, 150, 600) else longArrayOf(0, 250)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
    }

    private fun notifyCritical(w: RideWarning) = runCatching {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("ride_critical", "Critical ride warnings", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("screen", "DASH").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "ride_critical").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle((if (w.simulated) "[SIM] " else "") + w.text).setContentText("Tap to open the cockpit")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(pi).build()
        NotificationManagerCompat.from(ctx).notify(w.key.hashCode(), n)
    }
}
