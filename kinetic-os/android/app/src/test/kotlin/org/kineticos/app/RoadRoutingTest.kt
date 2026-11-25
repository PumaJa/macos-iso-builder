package org.kineticos.app

import kotlinx.serialization.json.*
import org.kineticos.app.nav.*
import kotlin.test.*

class RoadRoutingTest {
    // Trimmed real-shaped OSRM response: north 0.002°, then right (east) 0.002°.
    private val json = """
    {"code":"Ok","waypoints":[{"distance":3.2,"location":[8.0,47.0]},{"distance":450.0,"location":[8.002,47.002]}],
     "routes":[{"distance":375.0,"duration":60.0,"geometry":{"type":"LineString","coordinates":[[8.0,47.0],[8.0,47.001],[8.0,47.002],[8.001,47.002],[8.002,47.002]]},
      "legs":[{"steps":[
        {"distance":222.0,"name":"Dorfstrasse","maneuver":{"type":"depart","location":[8.0,47.0]}},
        {"distance":152.0,"name":"Feldweg","maneuver":{"type":"turn","modifier":"right","location":[8.0,47.002]}},
        {"distance":0.0,"name":"","maneuver":{"type":"arrive","location":[8.002,47.002]}}]}]}]}"""

    @Test fun parsesGeometryStepsAndSnapWarning() {
        val r = OsrmParser.parse(Json.parseToJsonElement(json).jsonObject)
        assertEquals(5, r.points.size); assertEquals(47.001, r.points[1].lat); assertEquals(3, r.steps.size)
        assertEquals("Turn right onto Feldweg", r.steps[1].instruction)
        assertEquals(1, r.warnings.size); assertTrue(r.warnings[0].contains("0.5 km"))
    }

    @Test fun turnByTurnProgressesAndArrives() {
        val t = TurnByTurn(OsrmParser.parse(Json.parseToJsonElement(json).jsonObject))
        val g1 = t.update(47.0005, 8.0)!!
        assertEquals("Turn right onto Feldweg", g1.next.instruction); assertTrue(g1.distanceToTurnM in 160.0..175.0); assertFalse(g1.offRoute)
        val g2 = t.update(47.002, 8.0001)!!   // at the corner → next is arrival
        assertEquals("arrive", g2.next.type)
        assertTrue(t.update(47.002, 8.0019)!!.arrived)
        assertTrue(t.update(47.0, 8.003)!!.offRoute)
    }

    @Test fun errorsAndInstructions() {
        assertFailsWith<IllegalArgumentException> { OsrmParser.parse(buildJsonObject { put("code", "NoRoute") }) }
        assertEquals("At the roundabout take exit 2 onto A1", OsrmParser.instruction("roundabout", "right", "A1", 2))
        assertEquals("Keep slightly left at the fork", OsrmParser.instruction("fork", "slight left", null, null))
        assertEquals("Arrive at destination", OsrmParser.instruction("arrive", null, null, null))
    }
}
