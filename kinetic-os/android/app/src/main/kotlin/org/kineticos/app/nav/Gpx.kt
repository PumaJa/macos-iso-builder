package org.kineticos.app.nav

object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun fromPoints(name: String, pts: List<RoutePoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Kinetic OS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("<rte><name>${esc(name)}</name>\n")
        pts.forEach { append("<rtept lat=\"${it.lat}\" lon=\"${it.lon}\">${it.ele?.let { e -> "<ele>$e</ele>" } ?: ""}</rtept>\n") }
        append("</rte>\n</gpx>\n")
    }
}
