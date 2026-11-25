package org.kineticos.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.kineticos.telemetry.AirtimeDetector
import org.kineticos.telemetry.Jump
import kotlin.math.sqrt

/**
 * Phone accelerometer (~50 Hz) for G-force and airtime when the phone is mounted on the vehicle. Works without a
 * VESC IMU. Values are only meaningful with a rigid mount – the UI says so.
 */
class MotionSource(private val ctx: Context, private val speedKmh: () -> Double?) : SensorEventListener {
    val detector = AirtimeDetector()
    private val _jumps = MutableSharedFlow<Jump>(extraBufferCapacity = 16); val jumps: SharedFlow<Jump> = _jumps
    private val _available = MutableStateFlow(false); val available: StateFlow<Boolean> = _available
    @Volatile private var peakSinceRead = 0.0
    private var running = false

    fun start() {
        if (running) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        running = sm.registerListener(this, acc, 20_000 /* µs = 50 Hz */)
        _available.value = running
    }
    fun stop() { ctx.getSystemService(SensorManager::class.java)?.unregisterListener(this); running = false }

    /** Peak |a| in g since the previous call (null if no sensor data). */
    fun takePeakG(): Double? { val p = peakSinceRead; peakSinceRead = 0.0; return if (_available.value && p > 0) p else null }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g > peakSinceRead) peakSinceRead = g
        detector.feedG(System.currentTimeMillis(), g, speedKmh())?.let { _jumps.tryEmit(it) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Test hook (simulator / unit tests). */
    fun inject(tMs: Long, g: Double) { if (g > peakSinceRead) peakSinceRead = g; _available.value = true; detector.feedG(tMs, g, speedKmh())?.let { _jumps.tryEmit(it) } }
}
