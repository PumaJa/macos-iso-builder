package org.kineticos.export

import org.kineticos.telemetry.TelemetrySample
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

const val KINETIC_NS = "https://kinetic-os.org/gpx/1"

object GpxExporter {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when {
            c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;"); c == '\'' -> sb.append("&apos;")
            c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c != '￾' && c != '￿' && !c.isSurrogate()) -> sb.append(c)
            c.isSurrogate() -> sb.append(c) // pairs are valid; lone surrogates are not checked
            else -> {} // drop characters illegal in XML 1.0
        }
        return sb.toString()
    }

    private fun n(v: Double) = BigDecimal.valueOf(v).let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }

    /** Samples without a GPS fix are skipped (a GPX point needs coordinates). */
    fun write(name: String, samples: List<TelemetrySample>, out: Appendable) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Kinetic OS\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            .append("xmlns:kinetic=\"$KINETIC_NS\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        out.append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n")
        out.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (s in samples) {
            val g = s.gps ?: continue
            if (!g.lat.isFinite() || !g.lon.isFinite()) continue
            out.append("      <trkpt lat=\"").append(n(g.lat)).append("\" lon=\"").append(n(g.lon)).append("\">")
            val alt = g.altM
            if (alt != null && alt.isFinite()) out.append("<ele>").append(n(alt)).append("</ele>")
            out.append("<time>").append(Instant.ofEpochMilli(g.timeMs).toString()).append("</time>")
            val speed = if (s.speedMps.isAvailable) s.speedMps.value else g.speedMps
            val ext = StringBuilder()
            if (speed != null && speed.isFinite()) ext.append("<kinetic:speed>").append(n(speed)).append("</kinetic:speed>")
            if (s.batteryPowerW.isAvailable) ext.append("<kinetic:power>").append(n(s.batteryPowerW.value!!)).append("</kinetic:power>")
            if (s.socPct.isAvailable) ext.append("<kinetic:soc>").append(n(s.socPct.value!!)).append("</kinetic:soc>")
            if (s.simulated) ext.append("<kinetic:simulated>true</kinetic:simulated>")
            if (ext.isNotEmpty()) out.append("<extensions>").append(ext).append("</extensions>")
            out.append("</trkpt>\n")
        }
        out.append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun toGpx(name: String, samples: List<TelemetrySample>): String = StringBuilder().also { write(name, samples, it) }.toString()
}

data class GpxPoint(
    val lat: Double, val lon: Double, val ele: Double? = null, val timeMs: Long? = null, val name: String? = null,
    val speedMps: Double? = null, val powerW: Double? = null, val socPct: Double? = null,
)

data class GpxData(
    val tracks: List<List<GpxPoint>>, val routes: List<List<GpxPoint>>, val waypoints: List<GpxPoint>,
    val trackNames: List<String?> = emptyList(), val routeNames: List<String?> = emptyList(),
)

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object GpxImporter {
    private fun factory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true; isXIncludeAware = false; isExpandEntityReferences = false
        isValidating = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // Reject any DTD outright: the strongest XXE / billion-laughs protection. The rest is defence in depth.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") } catch (_: IllegalArgumentException) {}
    }

    fun parse(text: String): GpxData {
        val doc = try {
            val b = factory().newDocumentBuilder()
            b.setEntityResolver { _, _ -> throw SAXException("external entities are not allowed") }
            b.setErrorHandler(null)
            b.parse(InputSource(StringReader(text.removePrefix("﻿"))))
        } catch (e: SAXException) {
            throw GpxParseException("invalid or unsafe GPX: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw GpxParseException("cannot read GPX: ${e.message}", e)
        }
        val root = doc.documentElement
        if (root.localName != "gpx") throw GpxParseException("root element is <${root.localName}>, expected <gpx>")

        val tracks = ArrayList<List<GpxPoint>>(); val trackNames = ArrayList<String?>()
        val routes = ArrayList<List<GpxPoint>>(); val routeNames = ArrayList<String?>()
        val wpts = ArrayList<GpxPoint>()
        for (e in root.children()) when (e.localName) {
            "wpt" -> wpts += point(e)
            "rte" -> { routes += e.children().filter { it.localName == "rtept" }.map { point(it) }; routeNames += e.childText("name") }
            "trk" -> {
                tracks += e.children().filter { it.localName == "trkseg" }.flatMap { seg -> seg.children().filter { it.localName == "trkpt" }.map { point(it) } }
                trackNames += e.childText("name")
            }
        }
        return GpxData(tracks, routes, wpts, trackNames, routeNames)
    }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>(); var c: Node? = firstChild
        while (c != null) { if (c is Element) out += c; c = c.nextSibling }
        return out
    }
    private fun Element.childText(local: String): String? = children().firstOrNull { it.localName == local }?.textContent?.trim()

    private fun point(e: Element): GpxPoint {
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: throw GpxParseException("point without valid lat")
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: throw GpxParseException("point without valid lon")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw GpxParseException("coordinate out of range: $lat,$lon")
        val ext = e.children().firstOrNull { it.localName == "extensions" }
        fun x(n: String) = ext?.children()?.firstOrNull { it.localName == n }?.textContent?.trim()?.toDoubleOrNull()
        return GpxPoint(lat, lon, e.childText("ele")?.toDoubleOrNull(), e.childText("time")?.let(::parseTime), e.childText("name"),
            x("speed"), x("power"), x("soc"))
    }

    private fun parseTime(s: String): Long? = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) {
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
}
