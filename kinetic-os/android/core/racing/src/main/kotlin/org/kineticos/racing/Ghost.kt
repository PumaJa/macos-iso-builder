package org.kineticos.racing

import org.kineticos.telemetry.GpsFix

data class GhostPosition(val lat: Double, val lon: Double, val distM: Double)

/**
 * A recorded lap replayed as a "ghost". Built from the lap's fixes (first fix = lap start).
 * Distances are cumulative haversine along the recorded fixes.
 */
class GhostLap(val fixes: List<GpsFix>) {
    init { require(fixes.size >= 2) { "ghost needs at least 2 fixes" } }

    private val times = DoubleArray(fixes.size) { (fixes[it].timeMs - fixes[0].timeMs).toDouble() }
    private val dists = DoubleArray(fixes.size).also { d ->
        for (i in 1 until fixes.size) d[i] = d[i - 1] + Geo.haversineM(fixes[i - 1], fixes[i])
    }
    val durationMs: Long get() = times.last().toLong()
    val lengthM: Double get() = dists.last()

    private fun interp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var lo = 0; var hi = xs.size - 1 // smallest i with xs[i] >= x
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (xs[mid] >= x) hi = mid else lo = mid + 1 }
        val x0 = xs[lo - 1]; val x1 = xs[lo]
        return ys[lo - 1] + (ys[lo] - ys[lo - 1]) * (x - x0) / (x1 - x0)
    }

    /** Distance along the ghost lap at elapsed time (clamped to lap bounds). */
    fun distanceAt(elapsedMs: Long): Double = interp(times, dists, elapsedMs.toDouble())

    /** Elapsed ms at which the ghost reached [distM] (clamped). */
    fun timeAtDistance(distM: Double): Double = interp(dists, times, distM)

    fun positionAt(elapsedMs: Long): GhostPosition {
        val t = elapsedMs.toDouble().coerceIn(0.0, times.last())
        var lo = 0; var hi = times.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (times[mid] >= t) hi = mid else lo = mid + 1 }
        val i = maxOf(lo, 1)
        val span = times[i] - times[i - 1]
        val f = if (span <= 0) 0.0 else (t - times[i - 1]) / span
        val a = fixes[i - 1]; val b = fixes[i]
        return GhostPosition(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, dists[i - 1] + (dists[i] - dists[i - 1]) * f)
    }

    /** Time gap in ms: positive = rider is behind the ghost (reached this distance later). */
    fun gapMs(riderDistM: Double, riderElapsedMs: Long): Double = riderElapsedMs - timeAtDistance(riderDistM)

    /** Distance gap in m: positive = rider is behind the ghost at the same elapsed time. */
    fun gapM(riderDistM: Double, riderElapsedMs: Long): Double = distanceAt(riderElapsedMs) - riderDistM

    companion object {
        fun fromLap(lap: Lap) = GhostLap(lap.fixes)
    }
}
