package org.kineticos.app.nav

import org.kineticos.racing.Geo

data class RoutePoint(val lat: Double, val lon: Double, val ele: Double? = null)

data class Guidance(
    val distanceToRouteM: Double, val nextPointIndex: Int, val distanceToNextM: Double, val bearingToNextDeg: Double,
    val remainingM: Double, val offRoute: Boolean, val progressFrac: Double,
)

/** Follows a GPX route / planned waypoint line (offroad-capable; no road routing engine). */
class RouteGuide(val points: List<RoutePoint>, private val offRouteM: Double = 50.0) {
    private val cum = DoubleArray(points.size).also { c -> for (i in 1 until points.size) c[i] = c[i - 1] + Geo.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon) }
    val totalM: Double get() = cum.lastOrNull() ?: 0.0
    private var lastIndex = 0

    fun update(lat: Double, lon: Double): Guidance? {
        if (points.size < 2) return null
        // search a window ahead of the last matched segment (monotonic progress, avoids jumping on loops)
        var best = Double.MAX_VALUE; var bestSeg = lastIndex; var bestT = 0.0
        val from = (lastIndex - 2).coerceAtLeast(0); val to = (lastIndex + 60).coerceAtMost(points.size - 2)
        for (i in from..to) {
            val (d, t) = distToSegment(lat, lon, points[i], points[i + 1])
            if (d < best) { best = d; bestSeg = i; bestT = t }
        }
        lastIndex = bestSeg
        val next = points[bestSeg + 1]
        val along = cum[bestSeg] + (cum[bestSeg + 1] - cum[bestSeg]) * bestT
        val dNext = Geo.haversineM(lat, lon, next.lat, next.lon)
        return Guidance(best, bestSeg + 1, dNext, Geo.bearingDeg(lat, lon, next.lat, next.lon), (totalM - along).coerceAtLeast(0.0), best > offRouteM, if (totalM > 0) along / totalM else 0.0)
    }

    fun elevationProfile(): List<Pair<Double, Double>> = points.indices.mapNotNull { i -> points[i].ele?.let { cum[i] to it } }

    companion object {
        fun distToSegment(lat: Double, lon: Double, a: RoutePoint, b: RoutePoint): Pair<Double, Double> {
            val k = Math.cos(Math.toRadians(lat)) * 111_320.0; val m = 110_540.0
            val ax = (a.lon - lon) * k; val ay = (a.lat - lat) * m; val bx = (b.lon - lon) * k; val by = (b.lat - lat) * m
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            return Math.sqrt(px * px + py * py) to t
        }
        fun polylineLengthM(p: List<RoutePoint>) = p.zipWithNext().sumOf { (a, b) -> Geo.haversineM(a.lat, a.lon, b.lat, b.lon) }
    }
}
