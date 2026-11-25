package org.kineticos.app.nav

import kotlinx.serialization.json.*
import org.kineticos.racing.Geo

data class TurnStep(val instruction: String, val distanceM: Double, val lat: Double, val lon: Double, val type: String, val modifier: String?)
data class RoadRoute(val points: List<RoutePoint>, val distanceM: Double, val durationS: Double, val steps: List<TurnStep>, val warnings: List<String>)

/** Parses an OSRM `/route/v1` response (as returned by the Kinetic server's `/api/v1/route`). */
object OsrmParser {
    fun parse(o: JsonObject): RoadRoute {
        val code = o["code"]?.jsonPrimitive?.contentOrNull
        require(code == "Ok") { "Routing failed: ${code ?: "unknown"} ${o["message"]?.jsonPrimitive?.contentOrNull ?: ""}".trim() }
        val route = o["routes"]!!.jsonArray.first().jsonObject
        val coords = route["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { c -> c.jsonArray.let { RoutePoint(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } }
        val steps = route["legs"]!!.jsonArray.flatMap { leg -> leg.jsonObject["steps"]!!.jsonArray.map { st ->
            val s = st.jsonObject; val m = s["maneuver"]!!.jsonObject
            val loc = m["location"]!!.jsonArray
            val type = m["type"]!!.jsonPrimitive.content; val mod = m["modifier"]?.jsonPrimitive?.contentOrNull
            TurnStep(instruction(type, mod, s["name"]?.jsonPrimitive?.contentOrNull, m["exit"]?.jsonPrimitive?.intOrNull),
                s["distance"]!!.jsonPrimitive.double, loc[1].jsonPrimitive.double, loc[0].jsonPrimitive.double, type, mod)
        } }
        // OSRM snaps every waypoint to the nearest road – warn when that is far away (outside map data / offroad target)
        val warnings = o["waypoints"]?.jsonArray?.mapIndexedNotNull { i, w ->
            w.jsonObject["distance"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 200 }?.let { "Point ${i + 1} is %.1f km from the nearest routable road".format(it / 1000) }
        } ?: emptyList()
        return RoadRoute(coords, route["distance"]!!.jsonPrimitive.double, route["duration"]!!.jsonPrimitive.double, steps, warnings)
    }

    fun instruction(type: String, modifier: String?, name: String?, exit: Int?): String {
        val onto = name?.takeIf { it.isNotBlank() }?.let { " onto $it" } ?: ""
        val dir = when (modifier) {
            "left" -> "left"; "right" -> "right"; "slight left" -> "slightly left"; "slight right" -> "slightly right"
            "sharp left" -> "sharp left"; "sharp right" -> "sharp right"; "uturn" -> "a U-turn"; "straight" -> "straight"; else -> null
        }
        return when (type) {
            "depart" -> "Start" + (name?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "")
            "arrive" -> "Arrive at destination"
            "turn", "end of road" -> if (dir == "straight") "Continue straight$onto" else "Turn ${dir ?: ""}$onto".replace("  ", " ")
            "continue", "new name" -> "Continue${if (dir != null && dir != "straight") " $dir" else ""}$onto"
            "merge" -> "Merge${dir?.let { " $it" } ?: ""}$onto"
            "on ramp" -> "Take the ramp${dir?.let { " on the $it" } ?: ""}$onto"
            "off ramp" -> "Take the exit${dir?.let { " on the $it" } ?: ""}$onto"
            "fork" -> "Keep ${dir ?: "ahead"} at the fork$onto"
            "roundabout", "rotary" -> "At the roundabout take exit ${exit ?: "?"}$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            else -> "Continue$onto"
        }
    }
}

data class TurnGuidance(val next: TurnStep, val distanceToTurnM: Double, val then: TurnStep?, val remainingM: Double, val offRoute: Boolean, val arrived: Boolean)

/** Turn-by-turn state on top of [RouteGuide]: next maneuver ahead of the rider, distance to it, off-route detection. */
class TurnByTurn(val route: RoadRoute, offRouteM: Double = 40.0) {
    private val guide = RouteGuide(route.points, offRouteM)
    private var stepIdx = 1 // step 0 is "depart"

    fun update(lat: Double, lon: Double): TurnGuidance? {
        val g = guide.update(lat, lon) ?: return null
        // advance past maneuvers we have reached (within 25 m) – never go backwards
        while (stepIdx < route.steps.size - 1 && Geo.haversineM(lat, lon, route.steps[stepIdx].lat, route.steps[stepIdx].lon) < 25.0) stepIdx++
        val next = route.steps.getOrNull(stepIdx) ?: route.steps.last()
        val d = Geo.haversineM(lat, lon, next.lat, next.lon)
        val arrived = next.type == "arrive" && d < 30.0
        return TurnGuidance(next, d, route.steps.getOrNull(stepIdx + 1), g.remainingM, g.offRoute, arrived)
    }
}
