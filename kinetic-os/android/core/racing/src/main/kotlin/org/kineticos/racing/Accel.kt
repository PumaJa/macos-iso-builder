package org.kineticos.racing

import kotlin.math.roundToInt

data class AccelResult(
    val zeroTo50S: Double?, val zeroTo100S: Double?, val to100mS: Double?, val to201mS: Double?,
    val sampleHz: Double?, val started: Boolean, val note: String,
)

/**
 * Acceleration measurement. Feed (timeMs, speed m/s, optional cumulative distance m) sequentially.
 * Start = speed rises above [startKmh] after having been at/below it (standstill); times are measured from the
 * interpolated threshold crossing. If no distance is supplied it is integrated from speed (trapezoid).
 * A false start (speed back to standstill before any target reached) re-arms the detector.
 */
class AccelRun(private val startKmh: Double = 1.0) {
    companion object {
        const val EIGHTH_MILE_M = 201.168
        private const val KMH = 1.0 / 3.6
    }

    private val thr = startKmh * KMH
    private var pt = 0L; private var pv = 0.0; private var pd = 0.0; private var have = false
    private var integ = 0.0
    private var armed = false; private var started = false
    private var t0 = 0.0; private var d0 = 0.0
    private var usedSuppliedDistance = true
    private val dts = ArrayList<Long>()
    private var r50: Double? = null; private var r100: Double? = null
    private var rd100: Double? = null; private var rd201: Double? = null

    val isComplete get() = r50 != null && r100 != null && rd100 != null && rd201 != null

    fun feed(tMs: Long, speedMps: Double, distanceM: Double? = null) {
        if (distanceM == null) usedSuppliedDistance = false
        if (!have) {
            have = true; pt = tMs; pv = speedMps; pd = distanceM ?: 0.0; armed = speedMps <= thr
            return
        }
        if (tMs <= pt) return
        if (dts.size < 10_000) dts += tMs - pt
        val d = distanceM ?: (pd + (pv + speedMps) / 2 * (tMs - pt) / 1000.0)
        // segment start (may be moved to the threshold crossing)
        var st = pt.toDouble(); var sv = pv; var sd = pd
        if (!started) {
            if (speedMps <= thr) armed = true
            else if (armed) {
                val f = (thr - pv) / (speedMps - pv)
                t0 = pt + f * (tMs - pt); d0 = pd + f * (d - pd)
                started = true; st = t0; sv = thr; sd = d0
            }
        } else if (speedMps <= thr && r50 == null && r100 == null && rd100 == null && rd201 == null) {
            started = false; armed = true
        }
        if (started) {
            val et = tMs.toDouble()
            fun hit(a: Double, b: Double, target: Double) = a < target && b >= target
            fun at(a: Double, b: Double, target: Double) = st + (et - st) * (target - a) / (b - a)
            for ((target, setter) in listOf(50.0 * KMH to 0, 100.0 * KMH to 1)) {
                if (hit(sv, speedMps, target)) {
                    val s = (at(sv, speedMps, target) - t0) / 1000.0
                    if (setter == 0 && r50 == null) r50 = s
                    if (setter == 1 && r100 == null) r100 = s
                }
            }
            val a = sd - d0; val b = d - d0
            if (rd100 == null && hit(a, b, 100.0)) rd100 = (at(a, b, 100.0) - t0) / 1000.0
            if (rd201 == null && hit(a, b, EIGHTH_MILE_M)) rd201 = (at(a, b, EIGHTH_MILE_M) - t0) / 1000.0
        }
        pt = tMs; pv = speedMps; pd = d
    }

    fun result(): AccelResult {
        val hz = if (dts.isEmpty()) null else dts.sorted()[dts.size / 2].let { 1000.0 / it }
        val res = hz?.let { 0.5 / it }
        val note = buildString {
            append(if (hz == null) "Not enough samples. " else "Sample rate ~%.1f Hz".format(java.util.Locale.ROOT, hz) +
                " (timing resolution ±~%.2f s before interpolation; crossings are linearly interpolated). ".format(java.util.Locale.ROOT, res))
            if (hz != null && hz < 5) append("Low GPS rate: results are rough estimates; use 10 Hz GPS or VESC speed for better accuracy. ")
            append(if (usedSuppliedDistance) "Distance as supplied (e.g. GPS/tacho). " else "Distance integrated from speed (error accumulates). ")
            append("Start detected at ${startKmh.roundToInt()} km/h from standstill; no rollout subtracted.")
        }
        return AccelResult(r50, r100, rd100, rd201, hz, started || isComplete || r50 != null, note)
    }
}
