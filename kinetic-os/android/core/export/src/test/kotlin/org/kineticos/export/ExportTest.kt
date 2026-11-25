package org.kineticos.export

import kotlinx.serialization.json.*
import org.kineticos.telemetry.*
import java.io.File
import kotlin.test.*

private fun gps(i: Int, lat: Double = 48.0 + i * 0.0001, lon: Double = 11.0, alt: Double? = 500.0, speed: Double? = 11.0, acc: Double? = 3.0) =
    GpsFix(lat, lon, alt, speed, null, acc, 1_700_000_000_000L + i * 1000L)

private fun sample(i: Int, sim: Boolean = false, g: GpsFix? = gps(i), speed: Q = Q(11.0, Quality.MEASURED),
                   power: Q = Q(500.0, Quality.CALCULATED), soc: Q = Q(80.0, Quality.ESTIMATED), src: String = "vesc",
                   wh: Q = Q.NA, whc: Q = Q.NA) =
    TelemetrySample(t = 1000L * i, simulated = sim, speedMps = speed, speedSource = src, batteryPowerW = power, socPct = soc,
        gps = g, wattHours = wh, wattHoursCharged = whc)

class CsvExporterTest {
    private fun parseCsv(s: String): List<List<String>> {
        // minimal RFC 4180 reader for the round-trip test
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val f = StringBuilder(); var q = false; var i = 0
        while (i < s.length) {
            val c = s[i]
            if (q) { if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c) }
            else when (c) { '"' -> q = true; ',' -> { row.add(f.toString()); f.clear() }
                '\r' -> {}; '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = ArrayList() }; else -> f.append(c) }
            i++
        }
        return rows
    }

    @Test fun headerHasQualityColumnPerQField() {
        val h = CsvExporter.header
        assertEquals(h.size, h.toSet().size)
        listOf("speed_mps", "battery_power_w", "soc_pct", "pitch_deg").forEach {
            assertTrue(it in h); assertEquals(h.indexOf(it) + 1, h.indexOf(it + "_quality"))
        }
        assertTrue("simulated" in h && "gps_lat" in h && "gps_time_ms" in h)
    }

    @Test fun valuesQualityAndDecimalPoint() {
        val csv = CsvExporter.toCsv(listOf(sample(1, speed = Q(12.5, Quality.MEASURED)), sample(2, sim = true, g = null, speed = Q.NA)))
        val rows = parseCsv(csv)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.size == CsvExporter.header.size })
        val h = CsvExporter.header
        assertEquals("12.5", rows[1][h.indexOf("speed_mps")]); assertEquals("MEASURED", rows[1][h.indexOf("speed_mps_quality")])
        assertEquals("false", rows[1][h.indexOf("simulated")]); assertEquals("true", rows[2][h.indexOf("simulated")])
        assertEquals("", rows[2][h.indexOf("speed_mps")]); assertEquals("UNAVAILABLE", rows[2][h.indexOf("speed_mps_quality")])
        assertEquals("", rows[2][h.indexOf("gps_lat")])
        assertEquals("48.0001", rows[1][h.indexOf("gps_lat")])
        assertFalse(',' in "12.5" && csv.contains("12,5"))
        assertTrue(csv.contains("\r\n"))
    }

    @Test fun smallAndLargeNumbersNotScientific() {
        assertEquals("0.00001", CsvExporter.num(1e-5)); assertEquals("100000000", CsvExporter.num(1e8))
        assertEquals("0", CsvExporter.num(0.0)); assertEquals("0", CsvExporter.num(-0.0)); assertEquals("", CsvExporter.num(Double.NaN))
    }

    @Test fun rfc4180Quoting() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("\"l1\nl2\"", CsvExporter.escape("l1\nl2"))
        val s = sample(1).copy(faultName = "FAULT, \"OVER\"\nVOLT")
        val rows = parseCsv(CsvExporter.toCsv(listOf(s)))
        assertEquals("FAULT, \"OVER\"\nVOLT", rows[1][CsvExporter.header.indexOf("fault_name")])
        assertEquals(CsvExporter.header.size, rows[1].size)
    }

    @Test fun emptyListWritesHeaderOnly() {
        assertEquals(1, parseCsv(CsvExporter.toCsv(emptyList())).size)
    }
}

class JsonExporterTest {
    @Test fun metaAndRoundTrip() {
        val samples = listOf(sample(1), sample(2, sim = true))
        val text = JsonExporter.toJson(samples, mapOf("name" to "Board \"X\"", "controllers" to "2"), prettyPrint = true)
        val root = Json.parseToJsonElement(text).jsonObject
        val meta = root["meta"]!!.jsonObject
        assertEquals("kinetic-os-ride", meta["format"]!!.jsonPrimitive.content)
        assertEquals(1, meta["version"]!!.jsonPrimitive.int)
        assertEquals(true, meta["simulated"]!!.jsonPrimitive.boolean) // any simulated sample taints the file
        assertEquals("Board \"X\"", meta["vehicle"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val back = JsonExporter.parse(text)
        assertEquals(samples, back.samples)
        assertEquals(2, back.meta.sampleCount)
    }

    @Test fun realRideNotMarkedSimulated() {
        assertFalse(JsonExporter.toRideFile(listOf(sample(1))).meta.simulated)
        val sb = StringBuilder(); JsonExporter.write(listOf(sample(1)), sb)
        assertFalse('\n' in sb)
    }

    @Test fun rejectsForeignOrNewerFiles() {
        assertFailsWith<IllegalArgumentException> { JsonExporter.parse("""{"meta":{"format":"other","version":1},"samples":[]}""") }
        assertFailsWith<IllegalArgumentException> { JsonExporter.parse("""{"meta":{"format":"kinetic-os-ride","version":2},"samples":[]}""") }
    }
}

class GpxTest {
    @Test fun exportIsValidXmlWithExpectedStructure() {
        val gpx = GpxExporter.toGpx("Morning <ride> & \"fun\"", listOf(sample(1), sample(2, g = null), sample(3)))
        val data = GpxImporter.parse(gpx) // parses => well-formed
        assertEquals(1, data.tracks.size); assertEquals(2, data.tracks[0].size)
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("xmlns:kinetic="))
        assertTrue(gpx.contains("<extensions><kinetic:speed>11</kinetic:speed><kinetic:power>500</kinetic:power><kinetic:soc>80</kinetic:soc></extensions>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:21Z</time>"), gpx)
        assertEquals("Morning <ride> & \"fun\"", data.trackNames[0])
    }

    @Test fun roundTrip() {
        val samples = (0 until 20).map { sample(it, g = gps(it, lat = 48.0 + it * 0.00012345, lon = 11.0 + it * 0.00007, alt = 500.0 + it * 0.5)) }
        val d = GpxImporter.parse(GpxExporter.toGpx("t", samples))
        val pts = d.tracks.single()
        assertEquals(20, pts.size)
        pts.forEachIndexed { i, p ->
            val g = samples[i].gps!!
            assertEquals(g.lat, p.lat, 1e-9); assertEquals(g.lon, p.lon, 1e-9); assertEquals(g.altM!!, p.ele!!, 1e-9)
            assertEquals(g.timeMs, p.timeMs)
            assertEquals(11.0, p.speedMps!!, 1e-9); assertEquals(500.0, p.powerW!!, 1e-9); assertEquals(80.0, p.socPct!!, 1e-9)
        }
    }

    @Test fun simulatedFlagInExtensions() {
        assertTrue(GpxExporter.toGpx("s", listOf(sample(1, sim = true))).contains("<kinetic:simulated>true"))
    }

    @Test fun escapingAndIllegalChars() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", GpxExporter.escape("a&b<c>d\"e'f"))
        assertEquals("ab", GpxExporter.escape("a\u0000\u0001b"))
        val d = GpxImporter.parse(GpxExporter.toGpx("x\u0000y", listOf(sample(1))))
        assertEquals("xy", d.trackNames[0])
    }

    @Test fun importsTrkRteWptWithNamesAndTimes() {
        val xml = """<?xml version="1.0"?>
          <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
            <wpt lat="48.1" lon="11.1"><ele>520.5</ele><name>Cafe</name><time>2024-05-01T10:00:00+02:00</time></wpt>
            <rte><name>Route A</name><rtept lat="1" lon="2"/><rtept lat="3" lon="4"><ele>7</ele></rtept></rte>
            <trk><name>T1</name>
              <trkseg><trkpt lat="10" lon="20"><ele>1.5</ele><time>2024-05-01T10:00:00.250Z</time></trkpt></trkseg>
              <trkseg><trkpt lat="11" lon="21"/></trkseg></trk>
            <trk><trkseg/></trk>
          </gpx>"""
        val d = GpxImporter.parse(xml)
        assertEquals(1, d.waypoints.size); assertEquals("Cafe", d.waypoints[0].name); assertEquals(520.5, d.waypoints[0].ele)
        assertEquals(Instant8.parse("2024-05-01T08:00:00Z"), d.waypoints[0].timeMs)
        assertEquals(listOf("Route A"), d.routeNames); assertEquals(2, d.routes[0].size); assertEquals(7.0, d.routes[0][1].ele)
        assertEquals(2, d.tracks.size); assertEquals(2, d.tracks[0].size) // segments merged
        assertEquals(Instant8.parse("2024-05-01T10:00:00.250Z"), d.tracks[0][0].timeMs)
        assertNull(d.tracks[0][1].ele); assertNull(d.tracks[0][1].timeMs)
        assertEquals(0, d.tracks[1].size)
    }

    private object Instant8 { fun parse(s: String) = java.time.Instant.parse(s).toEpochMilli() }

    @Test fun rejectsMalformedAndNonGpx() {
        assertFailsWith<GpxParseException> { GpxImporter.parse("<gpx><trk>") }
        assertFailsWith<GpxParseException> { GpxImporter.parse("<kml/>") }
        assertFailsWith<GpxParseException> { GpxImporter.parse("""<gpx><wpt lat="x" lon="1"/></gpx>""") }
        assertFailsWith<GpxParseException> { GpxImporter.parse("""<gpx><wpt lat="95" lon="1"/></gpx>""") }
    }

    @Test fun xxeExternalEntityBlocked() {
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOP-SECRET-CONTENT"); deleteOnExit() }
        val xml = """<?xml version="1.0"?>
          <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
          <gpx version="1.1"><wpt lat="1" lon="2"><name>&xxe;</name></wpt></gpx>"""
        val ex = assertFailsWith<GpxParseException> { GpxImporter.parse(xml) }
        assertFalse("TOP-SECRET-CONTENT" in (ex.message ?: ""))
    }

    @Test fun xxeParameterEntityAndBillionLaughsBlocked() {
        val evil = """<?xml version="1.0"?>
          <!DOCTYPE gpx [<!ENTITY % p SYSTEM "http://127.0.0.1:1/evil.dtd"> %p;]><gpx/>"""
        assertFailsWith<GpxParseException> { GpxImporter.parse(evil) }
        val lol = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
          <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]><gpx><wpt lat="1" lon="1"><name>&c;</name></wpt></gpx>"""
        assertFailsWith<GpxParseException> { GpxImporter.parse(lol) }
    }
}

class RideSummaryTest {
    @Test fun emptyAndSingle() {
        val e = RideSummary.compute(emptyList())
        assertEquals(0.0, e.distanceM); assertEquals("none", e.distanceSource); assertNull(e.avgSpeedMps)
        val one = RideSummary.compute(listOf(sample(1)))
        assertEquals(0L, one.durationMs); assertEquals(0.0, one.distanceM)
    }

    @Test fun gpsDistanceDurationAndSpeeds() {
        // 11 fixes every 0.0001 deg lat (≈11.12 m), 1 s apart
        val s = (0..10).map { sample(it) }
        val r = RideSummary.compute(s)
        assertEquals("gps", r.distanceSource)
        assertEquals(10 * 11.1195, r.distanceM, 0.1)
        assertEquals(10_000L, r.durationMs); assertEquals(10_000L, r.movingMs)
        assertEquals(11.1195, r.avgSpeedMps!!, 0.02)
        assertEquals(11.0, r.maxSpeedMps!!, 1e-9)
        assertEquals(500.0, r.maxPowerW!!, 1e-9)
    }

    @Test fun fallsBackToVescSpeedIntegration() {
        val s = (0..10).map { sample(it, g = null, speed = Q(10.0, Quality.MEASURED)) }
        val r = RideSummary.compute(s)
        assertEquals("vesc-speed", r.distanceSource); assertEquals(100.0, r.distanceM, 1e-6)
    }

    @Test fun lowAccuracyGpsFixesIgnored() {
        val s = (0..4).map { sample(it) } + sample(5, g = gps(5, lat = 49.0, acc = 500.0)) + (6..10).map { sample(it) }
        val r = RideSummary.compute(s)
        assertTrue(r.distanceM < 200, "was ${r.distanceM}")
    }

    @Test fun movingTimeExcludesStandstill() {
        val s = (0..9).map { sample(it, g = null, speed = Q(if (it < 5) 0.0 else 10.0, Quality.MEASURED)) }
        val r = RideSummary.compute(s)
        assertEquals(4_000L, r.movingMs) // intervals 5-6,6-7,7-8,8-9
        assertEquals(9_000L, r.durationMs)
    }

    @Test fun energyFromCountersAndWhPerKm() {
        val s = (0..10).map { i ->
            sample(i, speed = Q(10.0, Quality.MEASURED), wh = Q(5.0 + i * 1.0, Quality.MEASURED), whc = Q(1.0 + i * 0.1, Quality.MEASURED))
        }
        val r = RideSummary.compute(s)
        assertEquals(10.0, r.energyUsedWh!!, 1e-9); assertEquals(1.0, r.energyRegenWh!!, 1e-9)
        assertEquals(9.0, r.netEnergyWh!!, 1e-9)
        assertEquals(9.0 / (r.distanceM / 1000), r.whPerKm!!, 1e-9)
    }

    @Test fun energyFromIntegratedPower() {
        // 3600 W for 1 s then -1800 W for 2 s: used 1 Wh, regen 1 Wh
        val p = listOf(3600.0, 3600.0, -1800.0, -1800.0)
        val s = p.mapIndexed { i, w -> sample(i, power = Q(w, Quality.CALCULATED), g = null, speed = Q(10.0, Quality.MEASURED)) }
        val r = RideSummary.compute(s)
        // intervals: avg(3600,3600)=3600 W*1s=1 Wh used; avg(3600,-1800)=900 W*1s=0.25 Wh used; avg(-1800,-1800)*1s=-0.5 Wh regen
        assertEquals(1.25, r.energyUsedWh!!, 1e-9); assertEquals(0.5, r.energyRegenWh!!, 1e-9)
    }

    @Test fun noEnergyDataGivesNulls() {
        val r = RideSummary.compute((0..5).map { sample(it, power = Q.NA) })
        assertNull(r.energyUsedWh); assertNull(r.whPerKm); assertNull(r.maxPowerW)
    }

    @Test fun elevationGainLossWithDeadband() {
        val alts = listOf(100.0, 100.4, 99.8, 102.0, 105.0, 104.5, 101.0, 101.2)
        val s = alts.mapIndexed { i, a -> sample(i, g = gps(i, alt = a)) }
        val r = RideSummary.compute(s)
        // ref 100 -> 102 (+2) -> 105 (+3) -> 101 (-4); small wiggles ignored
        assertEquals(5.0, r.elevationGainM, 1e-9); assertEquals(4.0, r.elevationLossM, 1e-9)
        val noDead = RideSummary.compute(s, elevationDeadbandM = 0.0)
        assertTrue(noDead.elevationGainM > r.elevationGainM)
    }
}

class CsvInjectionTest {
    @org.junit.Test fun formulaCellsNeutralisedButNegativeNumbersUntouched() {
        kotlin.test.assertEquals("'=HYPERLINK(\"x\")", CsvExporter.text("=HYPERLINK(\"x\")"))
        kotlin.test.assertEquals("'@SUM(A1)", CsvExporter.text("@SUM(A1)"))
        kotlin.test.assertEquals("gps", CsvExporter.text("gps"))
        val s = org.kineticos.telemetry.TelemetrySample(t = 1, simulated = false, faultName = "=cmd|' /C calc'!A0",
            batteryCurrentA = org.kineticos.telemetry.Q(-12.5, org.kineticos.telemetry.Quality.MEASURED))
        val csv = CsvExporter.toCsv(listOf(s))
        kotlin.test.assertTrue(csv.contains("'=cmd"), csv)
        kotlin.test.assertTrue(csv.contains(",-12.5,"), csv)
    }
}
