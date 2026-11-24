package org.kineticos.racing

import kotlinx.serialization.Serializable
import org.kineticos.telemetry.GpsFix
import kotlin.math.*

/** A virtual line on the ground defined by two WGS84 points (e.g. start/finish or a sector gate). */
@Serializable
data class GateLine(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double) {
    val midLat get() = (lat1 + lat2) / 2
    val midLon get() = (lon1 + lon2) / 2
    val lengthM get() = Geo.haversineM(lat1, lon1, lat2, lon2)

    companion object {
        /** Gate centred on (lat, lon), perpendicular to [bearingDeg] (direction of travel), [widthM] wide. */
        fun perpendicular(lat: Double, lon: Double, bearingDeg: Double, widthM: Double = 20.0): GateLine {
            val (la1, lo1) = Geo.destination(lat, lon, bearingDeg - 90.0, widthM / 2)
            val (la2, lo2) = Geo.destination(lat, lon, bearingDeg + 90.0, widthM / 2)
            return GateLine(la1, lo1, la2, lo2)
        }
    }
}

@Serializable
data class TrackDefinition(
    val id: String,
    val name: String,
    val startFinish: GateLine,
    val sectors: List<GateLine> = emptyList(),
)

/** Result of a segment/gate intersection. [t] = fraction along the GPS segment, [u] = fraction along the gate. */
data class Crossing(val t: Double, val u: Double)

object Geo {
    const val EARTH_R = 6_371_008.8

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(a)))
    }

    fun haversineM(a: GpsFix, b: GpsFix) = haversineM(a.lat, a.lon, b.lat, b.lon)

    /** Initial bearing in degrees [0,360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point at [distM] along [bearingDeg] from (lat, lon) on a sphere. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val d = distM / EARTH_R; val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    /**
     * Intersection of GPS segment (a→b) with [gate] using a local equirectangular projection around [a].
     * Returns null when parallel/not intersecting. Half-open in t: (0, 1] so a fix exactly on the line is counted once.
     */
    fun segmentCrossing(aLat: Double, aLon: Double, bLat: Double, bLon: Double, gate: GateLine): Crossing? {
        val k = cos(Math.toRadians(aLat)) * EARTH_R * PI / 180.0 // metres per degree lon
        val m = EARTH_R * PI / 180.0                              // metres per degree lat
        fun x(lon: Double) = (lon - aLon) * k
        fun y(lat: Double) = (lat - aLat) * m
        val rx = x(bLon); val ry = y(bLat)
        val qx = x(gate.lon1); val qy = y(gate.lat1)
        val sx = x(gate.lon2) - qx; val sy = y(gate.lat2) - qy
        val denom = rx * sy - ry * sx
        if (abs(denom) < 1e-12) return null
        val t = (qx * sy - qy * sx) / denom
        val u = (qx * ry - qy * rx) / denom
        if (t <= 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
        return Crossing(t, u)
    }

    fun segmentCrossing(a: GpsFix, b: GpsFix, gate: GateLine) = segmentCrossing(a.lat, a.lon, b.lat, b.lon, gate)

    /** Linear interpolation of the crossing time between two fixes. */
    fun crossingTimeMs(a: GpsFix, b: GpsFix, t: Double): Long = a.timeMs + ((b.timeMs - a.timeMs) * t).roundToLong()

    /** Distance in metres from a point to a gate segment. */
    fun distanceToGateM(lat: Double, lon: Double, gate: GateLine): Double {
        val k = cos(Math.toRadians(lat)) * EARTH_R * PI / 180.0
        val m = EARTH_R * PI / 180.0
        val ax = (gate.lon1 - lon) * k; val ay = (gate.lat1 - lat) * m
        val bx = (gate.lon2 - lon) * k; val by = (gate.lat2 - lat) * m
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val u = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val c = u.coerceIn(0.0, 1.0)
        return hypot(ax + c * dx, ay + c * dy)
    }

    fun interpolate(a: GpsFix, b: GpsFix, t: Double): GpsFix {
        fun lerp(x: Double, y: Double) = x + (y - x) * t
        val aa = a.altM; val ba = b.altM; val asp = a.speedMps; val bsp = b.speedMps
        return GpsFix(
            lat = lerp(a.lat, b.lat), lon = lerp(a.lon, b.lon),
            altM = if (aa != null && ba != null) lerp(aa, ba) else aa,
            speedMps = if (asp != null && bsp != null) lerp(asp, bsp) else asp,
            bearingDeg = a.bearingDeg, accuracyM = a.accuracyM,
            timeMs = crossingTimeMs(a, b, t), provider = a.provider,
        )
    }
}

/** Auto track detection: the track whose start/finish line is within [radiusM] of the fix (closest wins). */
object TrackDetector {
    fun detect(tracks: List<TrackDefinition>, fix: GpsFix, radiusM: Double = 300.0): TrackDefinition? =
        tracks.map { it to Geo.distanceToGateM(fix.lat, fix.lon, it.startFinish) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }?.first
}
