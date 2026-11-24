package org.kineticos.app

import org.kineticos.app.nav.Gpx
import org.kineticos.app.nav.RouteGuide
import org.kineticos.app.nav.RoutePoint
import org.kineticos.export.GpxImporter
import kotlin.test.*

class RouteGuideTest {
    private val route = (0..10).map { RoutePoint(47.0 + it * 0.001, 8.0, 400.0 + it) } // ~111 m per step northwards

    @Test fun onRouteProgress() {
        val g = RouteGuide(route)
        val gd = g.update(47.0035, 8.0)!!
        assertFalse(gd.offRoute); assertTrue(gd.distanceToRouteM < 1.0)
        assertEquals(4, gd.nextPointIndex); assertEquals(0.35, gd.progressFrac, 0.01)
        assertEquals(0.0, gd.bearingToNextDeg, 1.0)
    }
    @Test fun offRouteDetected() {
        val gd = RouteGuide(route).update(47.005, 8.002)!! // ~150 m east
        assertTrue(gd.offRoute); assertTrue(gd.distanceToRouteM in 130.0..170.0)
    }
    @Test fun lengthAndElevation() {
        assertEquals(1112.0, RouteGuide.polylineLengthM(route), 15.0)
        assertEquals(11, RouteGuide(route).elevationProfile().size)
    }
    @Test fun plannedRouteGpxRoundTrip() {
        val back = GpxImporter.parse(Gpx.fromPoints("A & B <test>", route))
        assertEquals(route.size, back.routes.single().size); assertEquals(47.01, back.routes.single().last().lat, 1e-9)
    }
}
