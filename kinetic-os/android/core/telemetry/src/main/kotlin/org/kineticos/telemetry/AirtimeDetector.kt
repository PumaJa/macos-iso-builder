package org.kineticos.telemetry

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
data class Jump(val startMs: Long, val endMs: Long) { val durationMs get() = endMs - startMs }

/**
 * Detects airtime (jumps) from accelerometer magnitude (phone mounted on the bike, or VESC IMU).
 * In free fall the measured specific force drops towards 0 g. A jump starts after |a| stayed below [enterG] for
 * [minMs] and ends when |a| rises above [exitG]. Detection is only armed above [minSpeedKmh] (a dropped phone at
 * standstill is not a jump). Values are CALCULATED from MEASURED sensor data.
 */
class AirtimeDetector(
    private val enterG: Double = 0.35, private val exitG: Double = 0.6,
    private val minMs: Long = 150, private val maxMs: Long = 6000, private val minSpeedKmh: Double = 5.0,
) {
    private var lowSince: Long? = null
    private var inAir = false
    private var airStart = 0L
    private val _jumps = ArrayList<Jump>()
    val jumps: List<Jump> get() = _jumps
    var peakG = 0.0; private set
    val totalAirtimeMs get() = _jumps.sumOf { it.durationMs }
    val airborne get() = inAir

    /** Feed one accelerometer sample (m/s² components). [speedKmh] null = unknown (detection allowed). */
    fun feed(tMs: Long, ax: Double, ay: Double, az: Double, speedKmh: Double?): Jump? = feedG(tMs, sqrt(ax * ax + ay * ay + az * az) / 9.80665, speedKmh)

    fun feedG(tMs: Long, g: Double, speedKmh: Double?): Jump? {
        if (g > peakG) peakG = g
        val armed = speedKmh == null || speedKmh >= minSpeedKmh || inAir
        if (!inAir) {
            if (armed && g < enterG) {
                val s = lowSince ?: tMs.also { lowSince = it }
                if (tMs - s >= minMs) { inAir = true; airStart = s }
            } else lowSince = null
            return null
        }
        if (g > exitG) {
            inAir = false; lowSince = null
            val d = tMs - airStart
            return if (d in minMs..maxMs) Jump(airStart, tMs).also { _jumps += it } else null // > maxMs = phone fell / sensor glitch
        }
        return null
    }

    fun reset() { lowSince = null; inAir = false; _jumps.clear(); peakG = 0.0 }
}
