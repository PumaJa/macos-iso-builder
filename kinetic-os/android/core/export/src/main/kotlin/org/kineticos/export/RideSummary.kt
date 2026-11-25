package org.kineticos.export

import org.kineticos.telemetry.TelemetrySample
import kotlin.math.*

@kotlinx.serialization.Serializable
data class RideSummary(
    val distanceM: Double,
    val distanceSource: String,       // "gps", "vesc-speed", "speed", "none"
    val durationMs: Long,
    val movingMs: Long,
    val avgSpeedMps: Double?,         // distance / moving time
    val maxSpeedMps: Double?,
    val maxPowerW: Double?,
    val energyUsedWh: Double?,        // gross energy drawn from the battery
    val energyRegenWh: Double?,       // energy returned to the battery (Wh charged)
    val whPerKm: Double?,             // NET (used - regen) per km; null if distance < 10 m or no energy data
    val elevationGainM: Double,
    val elevationLossM: Double,
    val airtimeMs: Long? = null,       // from the accelerometer (null = no sensor data)
    val maxG: Double? = null,
) {
    val netEnergyWh: Double? get() = if (energyUsedWh == null) null else energyUsedWh - (energyRegenWh ?: 0.0)

    companion object {
        const val MOVING_THRESHOLD_MPS = 0.5
        const val MAX_GAP_MS = 10_000L
        const val MAX_GPS_ACCURACY_M = 50.0
        const val ELEVATION_DEADBAND_M = 1.0

        /**
         * Timestamps `t` are ms. Power sign convention: battery power > 0 = discharging, < 0 = regenerating.
         * Energy prefers the VESC watt-hour counters (last - first); falls back to integrating battery power.
         */
        fun compute(samples: List<TelemetrySample>, elevationDeadbandM: Double = ELEVATION_DEADBAND_M): RideSummary {
            if (samples.isEmpty()) return RideSummary(0.0, "none", 0, 0, null, null, null, null, null, null, 0.0, 0.0)
            val sorted = samples.sortedBy { it.t }
            val duration = sorted.last().t - sorted.first().t
            fun speedOf(s: TelemetrySample): Double? =
                (if (s.speedMps.isAvailable) s.speedMps.value else s.gps?.speedMps)?.takeIf { it.isFinite() }

            // distance
            var dist = 0.0; var src = "none"
            val gps = sorted.mapNotNull { it.gps }.filter { (it.accuracyM ?: 0.0) <= MAX_GPS_ACCURACY_M }
            if (gps.size >= 2) {
                for (i in 1 until gps.size) dist += haversine(gps[i - 1].lat, gps[i - 1].lon, gps[i].lat, gps[i].lon)
                src = "gps"
            } else {
                val vescOnly = sorted.any { it.speedSource == "vesc" && it.speedMps.isAvailable }
                for (i in 1 until sorted.size) {
                    val a = sorted[i - 1]; val b = sorted[i]
                    val dt = b.t - a.t
                    if (dt <= 0 || dt > MAX_GAP_MS) continue
                    val sa = speedOfFor(a, vescOnly); val sb = speedOfFor(b, vescOnly)
                    if (sa != null && sb != null) dist += (sa + sb) / 2 * dt / 1000.0
                }
                if (dist > 0 || sorted.any { speedOfFor(it, vescOnly) != null }) src = if (vescOnly) "vesc-speed" else "speed"
            }

            // moving time, max speed
            var moving = 0L; var maxSpeed: Double? = null
            for (i in sorted.indices) {
                val s = speedOf(sorted[i])
                if (s != null) maxSpeed = max(maxSpeed ?: s, s)
                if (s != null && s > MOVING_THRESHOLD_MPS && i + 1 < sorted.size) {
                    val dt = sorted[i + 1].t - sorted[i].t
                    if (dt in 1..MAX_GAP_MS) moving += dt
                }
            }

            // power & energy
            fun powerOf(s: TelemetrySample): Double? =
                (if (s.batteryPowerW.isAvailable) s.batteryPowerW.value else if (s.motorPowerW.isAvailable) s.motorPowerW.value else null)
            val maxPower = sorted.mapNotNull { powerOf(it) }.maxOrNull()
            var used: Double? = null; var regen: Double? = null
            val whs = sorted.filter { it.wattHours.isAvailable }.map { it.wattHours.value!! }
            val whc = sorted.filter { it.wattHoursCharged.isAvailable }.map { it.wattHoursCharged.value!! }
            if (whs.size >= 2) {
                used = max(0.0, whs.last() - whs.first())
                regen = if (whc.size >= 2) max(0.0, whc.last() - whc.first()) else 0.0
            } else {
                var u = 0.0; var r = 0.0; var any = false
                for (i in 1 until sorted.size) {
                    val dt = sorted[i].t - sorted[i - 1].t
                    if (dt <= 0 || dt > MAX_GAP_MS) continue
                    val pa = powerOf(sorted[i - 1]); val pb = powerOf(sorted[i])
                    if (pa == null || pb == null) continue
                    any = true
                    val e = (pa + pb) / 2 * dt / 3_600_000.0
                    if (e >= 0) u += e else r -= e
                }
                if (any) { used = u; regen = r }
            }
            val whKm = if (used != null && dist >= 10.0) (used - (regen ?: 0.0)) / (dist / 1000.0) else null

            // elevation with dead-band to suppress GPS altitude noise
            var gain = 0.0; var loss = 0.0; var ref: Double? = null
            for (g in sorted.mapNotNull { it.gps?.altM }.filter { it.isFinite() }) {
                val r0 = ref
                if (r0 == null) { ref = g; continue }
                val d = g - r0
                if (d >= elevationDeadbandM) { gain += d; ref = g } else if (d <= -elevationDeadbandM) { loss -= d; ref = g }
            }
            val air = sorted.mapNotNull { it.airtimeMs }.let { if (it.isEmpty()) null else it.max() - it.min() }
            val maxG = sorted.mapNotNull { it.gForce.value?.takeIf { g -> g.isFinite() } }.maxOrNull()
            return RideSummary(dist, src, duration, moving, if (moving > 0) dist / (moving / 1000.0) else null, maxSpeed, maxPower,
                used, regen, whKm, gain, loss, air, maxG)
        }

        private fun speedOfFor(s: TelemetrySample, vescOnly: Boolean): Double? =
            if (vescOnly) (if (s.speedSource == "vesc" && s.speedMps.isAvailable) s.speedMps.value else null)
            else (if (s.speedMps.isAvailable) s.speedMps.value else s.gps?.speedMps)

        private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }
    }
}
