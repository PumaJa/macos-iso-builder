package org.kineticos.racing

import org.kineticos.telemetry.GpsFix
import kotlin.test.*

class GeoTest {
    @Test fun haversineKnownDistance() {
        // 1 degree of latitude ≈ 111.195 km
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 50.0)
        // Berlin -> Paris ≈ 878 km
        assertEquals(878_000.0, Geo.haversineM(52.5200, 13.4050, 48.8566, 2.3522), 3_000.0)
        assertEquals(0.0, Geo.haversineM(10.0, 10.0, 10.0, 10.0), 1e-9)
    }

    @Test fun bearings() {
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(0.0, 1.0, 0.0, 0.0), 1e-6)
    }

    @Test fun destinationRoundTrip() {
        val (lat, lon) = Geo.destination(48.0, 11.0, 45.0, 1000.0)
        assertEquals(1000.0, Geo.haversineM(48.0, 11.0, lat, lon), 0.01)
        assertEquals(45.0, Geo.bearingDeg(48.0, 11.0, lat, lon), 0.05)
    }

    @Test fun perpendicularGate() {
        val g = GateLine.perpendicular(48.0, 11.0, 0.0, 20.0)
        assertEquals(20.0, g.lengthM, 0.01)
        val gateBearing = Geo.bearingDeg(g.lat1, g.lon1, g.lat2, g.lon2)
        assertTrue(abs(gateBearing - 90) < 0.1 || abs(gateBearing - 270) < 0.1)
        assertEquals(48.0, g.midLat, 1e-9)
    }

    private fun abs(d: Double) = kotlin.math.abs(d)

    @Test fun crossingFractionAndTime() {
        val gate = GateLine.perpendicular(48.0, 11.0, 0.0, 40.0) // east-west line through (48,11)
        val (aLat, aLon) = enu(0.0, 0.0).let { 48.0 - 10.0 / 111_194.9 to 11.0 }
        val bLat = 48.0 + 30.0 / 111_194.9
        val a = GpsFix(aLat, aLon, null, null, null, null, 1000)
        val b = GpsFix(bLat, aLon, null, null, null, null, 2000)
        val c = assertNotNull(Geo.segmentCrossing(a, b, gate))
        assertEquals(0.25, c.t, 0.002)
        assertEquals(0.5, c.u, 0.002)
        assertEquals(1250L, Geo.crossingTimeMs(a, b, c.t), 3)
    }

    private fun assertEquals(e: Long, a: Long, tol: Long) = assertTrue(kotlin.math.abs(e - a) <= tol, "$e vs $a")

    @Test fun noCrossingWhenOutsideGateOrParallel() {
        val gate = GateLine.perpendicular(48.0, 11.0, 0.0, 10.0)
        val far = Geo.destination(48.0, 11.0, 90.0, 50.0) // 50 m east of the gate: segment passes the line outside it
        val a = GpsFix(far.first - 0.0001, far.second, null, null, null, null, 0)
        val b = GpsFix(far.first + 0.0001, far.second, null, null, null, null, 1000)
        assertNull(Geo.segmentCrossing(a, b, gate))
        // parallel
        val p1 = GpsFix(48.0001, 10.999, null, null, null, null, 0)
        val p2 = GpsFix(48.0001, 11.001, null, null, null, null, 1000)
        assertNull(Geo.segmentCrossing(p1, p2, gate))
        // segment stops before the line
        val s1 = GpsFix(47.9990, 11.0, null, null, null, null, 0)
        val s2 = GpsFix(47.9995, 11.0, null, null, null, null, 1000)
        assertNull(Geo.segmentCrossing(s1, s2, gate))
    }

    @Test fun fixExactlyOnLineCountedOnce() {
        val gate = GateLine.perpendicular(48.0, 11.0, 0.0, 40.0)
        val a = GpsFix(47.9999, 11.0, null, null, null, null, 0)
        val on = GpsFix(48.0, 11.0, null, null, null, null, 1000)
        val c = GpsFix(48.0001, 11.0, null, null, null, null, 2000)
        val n = listOfNotNull(Geo.segmentCrossing(a, on, gate), Geo.segmentCrossing(on, c, gate)).size
        assertEquals(1, n)
    }

    @Test fun trackDetection() {
        val near = TrackDefinition("a", "A", GateLine.perpendicular(48.0, 11.0, 0.0), emptyList())
        val other = TrackDefinition("b", "B", GateLine.perpendicular(48.5, 11.0, 0.0), emptyList())
        val (lat, lon) = Geo.destination(48.0, 11.0, 90.0, 250.0)
        val fix = GpsFix(lat, lon, null, null, null, null, 0)
        assertEquals("a", TrackDetector.detect(listOf(other, near), fix)?.id)
        val (lat2, lon2) = Geo.destination(48.0, 11.0, 90.0, 400.0)
        assertNull(TrackDetector.detect(listOf(other, near), GpsFix(lat2, lon2, null, null, null, null, 0)))
        // closest wins
        val near2 = TrackDefinition("c", "C", GateLine.perpendicular(48.001, 11.0, 0.0), emptyList())
        assertEquals("c", TrackDetector.detect(listOf(near, near2), GpsFix(48.0009, 11.0, null, null, null, null, 0))?.id)
    }
}
