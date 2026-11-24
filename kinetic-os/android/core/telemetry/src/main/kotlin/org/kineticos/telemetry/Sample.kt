package org.kineticos.telemetry

import kotlinx.serialization.Serializable

/** Where a value comes from. Shown in the UI next to every number. */
@Serializable
enum class Quality { MEASURED, CALCULATED, ESTIMATED, SIMULATED, UNAVAILABLE }

@Serializable
data class Q(val value: Double?, val quality: Quality) {
    val isAvailable get() = value != null && !value.isNaN() && quality != Quality.UNAVAILABLE
    companion object {
        val NA = Q(null, Quality.UNAVAILABLE)
        fun of(v: Double?, q: Quality) = if (v == null || v.isNaN() || v.isInfinite()) NA else Q(v, q)
    }
}

@Serializable
data class GpsFix(
    val lat: Double, val lon: Double, val altM: Double?, val speedMps: Double?, val bearingDeg: Double?,
    val accuracyM: Double?, val timeMs: Long, val provider: String = "gps",
)

/**
 * One time-synchronised telemetry record (VESC + GPS + IMU + BMS) – the unit that is recorded, streamed and exported.
 * All numbers are SI-ish (V, A, W, °C, m/s, m). `simulated` marks samples coming from the simulator.
 */
@Serializable
data class TelemetrySample(
    val t: Long,
    val simulated: Boolean,
    val speedMps: Q = Q.NA,
    val speedSource: String = "none", // "vesc", "gps", "none"
    val motorRpm: Q = Q.NA,
    val erpm: Q = Q.NA,
    val motorPowerW: Q = Q.NA,
    val batteryPowerW: Q = Q.NA,
    val motorCurrentA: Q = Q.NA,
    val batteryCurrentA: Q = Q.NA,
    val voltageV: Q = Q.NA,
    val socPct: Q = Q.NA,
    val ampHours: Q = Q.NA,
    val ampHoursCharged: Q = Q.NA,
    val wattHours: Q = Q.NA,
    val wattHoursCharged: Q = Q.NA,
    val tempMosC: Q = Q.NA,
    val tempMotorC: Q = Q.NA,
    val duty: Q = Q.NA,
    val throttle: Q = Q.NA,
    val faultCode: Int = 0,
    val faultName: String? = null,
    val tachAbs: Long? = null,
    val gps: GpsFix? = null,
    val accelG: Q = Q.NA,       // longitudinal
    val lateralG: Q = Q.NA,
    val leanDeg: Q = Q.NA,
    val pitchDeg: Q = Q.NA,
    val bms: BmsSnapshot? = null,
    val controllers: Int = 1,
)

@Serializable
data class BmsSnapshot(
    val cells: List<Double>, val temps: List<Double>, val socPct: Double?, val sohPct: Double?,
    val cellMin: Double?, val cellMax: Double?, val cellDelta: Double?, val status: String?,
)
