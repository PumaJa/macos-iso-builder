package org.kineticos.racing

import org.kineticos.telemetry.GpsFix
import kotlin.math.*

const val LAT0 = 48.0
const val LON0 = 11.0
const val RADIUS = 100.0

/** Local ENU metres (east, north) → lat/lon around (LAT0, LON0). */
fun enu(e: Double, n: Double): Pair<Double, Double> {
    val m = Geo.EARTH_R * PI / 180
    return (LAT0 + n / m) to (LON0 + e / (m * cos(Math.toRadians(LAT0))))
}

/** Counter-clockwise circle of radius [RADIUS]; per-lap speeds; starts at angle [phase]. */
fun circleFixes(hz: Int, laps: Double, speeds: List<Double>, phase: Double = -0.1, ccw: Boolean = true,
                t0: Long = 1_700_000_000_000L): List<GpsFix> {
    val out = ArrayList<GpsFix>()
    var angle = phase; var i = 0
    val dt = 1.0 / hz
    while (true) {
        val lapIdx = (angle / (2 * PI)).toInt().coerceIn(0, speeds.size - 1)
        val v = speeds[lapIdx]
        val a = if (ccw) angle else -angle
        val (lat, lon) = enu(RADIUS * cos(a), RADIUS * sin(a))
        out += GpsFix(lat, lon, 500.0, v, null, 2.0, t0 + (i * 1000L / hz))
        if ((angle - phase) / (2 * PI) >= laps) break
        angle += v / RADIUS * dt; i++
    }
    return out
}

/** Radial gate at [angle] across the circle, width 30 m. */
fun radialGate(angle: Double): GateLine {
    val (cLat, cLon) = enu(RADIUS * cos(angle), RADIUS * sin(angle))
    val bearing = (Math.toDegrees(atan2(-sin(angle), cos(angle))) + 360) % 360 // tangent heading (ccw)
    return GateLine.perpendicular(cLat, cLon, bearing, 30.0)
}

fun circleTrack(sectorAngles: List<Double> = emptyList()) =
    TrackDefinition("circle", "Circle", radialGate(0.0), sectorAngles.map { radialGate(it) })
