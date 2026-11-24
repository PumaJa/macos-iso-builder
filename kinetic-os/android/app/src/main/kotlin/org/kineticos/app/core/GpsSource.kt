package org.kineticos.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kineticos.telemetry.GpsFix

/** Platform LocationManager (no Google Play Services needed → works on de-Googled and older devices). */
class GpsSource(private val ctx: Context) {
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix
    private val _hz = MutableStateFlow(0.0)
    val updateHz: StateFlow<Double> = _hz
    private var lastT = 0L
    private var running = false
    private val lm get() = ctx.getSystemService(LocationManager::class.java)

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val now = l.time
            if (lastT > 0 && now > lastT) _hz.value = _hz.value * 0.8 + 0.2 * (1000.0 / (now - lastT))
            lastT = now
            _fix.value = GpsFix(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
                if (l.hasSpeed()) l.speed.toDouble() else null, if (l.hasBearing()) l.bearing.toDouble() else null,
                if (l.hasAccuracy()) l.accuracy.toDouble() else null, l.time, l.provider ?: "gps")
        }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(minIntervalMs: Long = 100) {
        if (running) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minIntervalMs, 0f, listener, Looper.getMainLooper())
            running = true
        }
    }
    fun stop() { runCatching { lm.removeUpdates(listener) }; running = false }
    /** Inject fixes from VESC GNSS (COMM_GET_GNSS) or test. */
    fun inject(f: GpsFix) { _fix.value = f }
}
