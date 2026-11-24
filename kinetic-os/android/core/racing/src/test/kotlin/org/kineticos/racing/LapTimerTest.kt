package org.kineticos.racing

import kotlin.math.PI
import kotlin.test.*

class LapTimerTest {
    private fun run(timer: LapTimer, hz: Int, laps: Double, speeds: List<Double>, ccw: Boolean = true) =
        circleFixes(hz, laps, speeds, ccw = ccw).flatMap { timer.feed(it) }

    private val period20 = 2 * PI * RADIUS / 20.0 * 1000 // ms

    @Test fun detectsLapsAt1Hz() {
        val t = LapTimer(circleTrack())
        run(t, 1, 3.5, listOf(20.0))
        assertEquals(3, t.laps.size)
        t.laps.forEach { assertEquals(period20, it.lapMs.toDouble(), 30.0); assertTrue(it.valid) }
        assertEquals(listOf(1, 2, 3), t.laps.map { it.number })
        assertEquals(20.0, t.laps[0].maxSpeedMps, 0.01)
    }

    @Test fun detectsLapsAt10Hz() {
        val t = LapTimer(circleTrack())
        run(t, 10, 3.5, listOf(20.0))
        assertEquals(3, t.laps.size)
        t.laps.forEach { assertEquals(period20, it.lapMs.toDouble(), 5.0) }
    }

    @Test fun directionAgnostic() {
        val t = LapTimer(circleTrack())
        run(t, 10, 2.5, listOf(20.0), ccw = false)
        assertEquals(2, t.laps.size)
        assertEquals(period20, t.laps[0].lapMs.toDouble(), 5.0)
    }

    @Test fun eventsEmittedInOrder() {
        val t = LapTimer(circleTrack(listOf(PI / 2)))
        val ev = run(t, 10, 1.5, listOf(20.0))
        assertIs<LapEvent.LapStarted>(ev[0])
        assertIs<LapEvent.SectorSplit>(ev[1])
        assertIs<LapEvent.LapCompleted>(ev[2])
        assertIs<LapEvent.LapStarted>(ev[3])
    }

    @Test fun sectorsSumToLapTime() {
        val t = LapTimer(circleTrack(listOf(PI / 2, PI)))
        run(t, 10, 2.5, listOf(20.0))
        assertEquals(2, t.laps.size)
        for (lap in t.laps) {
            assertEquals(3, lap.sectorMs.size)
            assertEquals(lap.lapMs, lap.sectorMs.sum())
            assertEquals(period20 / 4, lap.sectorMs[0].toDouble(), 10.0)
            assertEquals(period20 / 4, lap.sectorMs[1].toDouble(), 10.0)
            assertEquals(period20 / 2, lap.sectorMs[2].toDouble(), 10.0)
            assertTrue(lap.valid)
        }
    }

    @Test fun sectorsAt1Hz() {
        val t = LapTimer(circleTrack(listOf(PI)))
        run(t, 1, 2.5, listOf(20.0))
        assertEquals(2, t.laps.size)
        assertEquals(period20 / 2, t.laps[0].sectorMs[0].toDouble(), 100.0)
        assertEquals(t.laps[0].lapMs, t.laps[0].sectorMs.sum())
    }

    @Test fun missedSectorMakesLapInvalid() {
        // sector gate placed off the circle -> never crossed
        val offGate = GateLine.perpendicular(enu(500.0, 500.0).first, enu(500.0, 500.0).second, 0.0, 20.0)
        val track = TrackDefinition("x", "x", radialGate(0.0), listOf(offGate))
        val t = LapTimer(track)
        run(t, 10, 2.5, listOf(20.0))
        assertEquals(2, t.laps.size)
        assertFalse(t.laps[0].valid)
        assertNull(t.bestLap)
    }

    @Test fun minLapIgnoresQuickRecrossing() {
        // 6 s laps with minLap 10 s: crossings alternate ignored/accepted -> laps of ~12 s
        val t = LapTimer(circleTrack(), minLapMs = 10_000)
        val v = 2 * PI * RADIUS / 6.0
        run(t, 10, 3.2, listOf(v))
        assertEquals(1, t.laps.size)
        assertEquals(12_000.0, t.laps[0].lapMs.toDouble(), 30.0)
        // lowering minLap makes them all count
        val t2 = LapTimer(circleTrack(), minLapMs = 2_000)
        run(t2, 10, 3.2, listOf(v))
        assertEquals(3, t2.laps.size)
        assertEquals(6_000.0, t2.laps[0].lapMs.toDouble(), 30.0)
    }

    @Test fun gpsGapInvalidatesLap() {
        val t = LapTimer(circleTrack(), maxGapMs = 5_000)
        val fixes = circleFixes(1, 2.5, listOf(20.0)).filterIndexed { i, _ -> i < 10 || i > 18 } // 9 s hole in lap 1
        fixes.forEach { t.feed(it) }
        assertTrue(t.laps.isNotEmpty())
        assertFalse(t.laps[0].valid)
    }

    @Test fun bestLapAndLiveDelta() {
        // lap1 20 m/s (31.4 s), lap2 25 m/s (25.1 s), lap3 20 m/s
        val t = LapTimer(circleTrack())
        val fixes = circleFixes(10, 3.6, listOf(20.0, 25.0, 20.0, 20.0))
        var deltaMidLap3: Long? = null
        var sawNullDeltaBeforeBest = false
        for (f in fixes) {
            t.feed(f)
            if (t.bestLap == null && t.isLapActive) sawNullDeltaBeforeBest = sawNullDeltaBeforeBest || t.liveDeltaMs == null
            if (t.laps.size == 2 && t.currentLapDistanceM in 313.0..315.0) deltaMidLap3 = t.liveDeltaMs
        }
        assertTrue(sawNullDeltaBeforeBest)
        assertEquals(3, t.laps.size)
        val best = assertNotNull(t.bestLap)
        assertEquals(2, best.number)
        assertEquals(2 * PI * RADIUS / 25.0 * 1000, best.lapMs.toDouble(), 40.0)
        // half way (314 m): current 15.7 s vs best 12.57 s -> +3.14 s
        assertEquals(3140.0, assertNotNull(deltaMidLap3).toDouble(), 200.0)
    }

    @Test fun ghostFromBestLap() {
        val t = LapTimer(circleTrack())
        run(t, 10, 2.5, listOf(20.0))
        val ghost = GhostLap.fromLap(assertNotNull(t.bestLap))
        assertEquals(period20, ghost.durationMs.toDouble(), 10.0)
        assertEquals(2 * PI * RADIUS, ghost.lengthM, 3.0)
    }

    @Test fun nonMonotonicTimeIgnored() {
        val t = LapTimer(circleTrack())
        val f = circleFixes(10, 1.5, listOf(20.0))
        f.forEachIndexed { i, fix -> t.feed(fix); if (i == 5) t.feed(f[2]) }
        assertEquals(1, t.laps.size)
    }

    @Test fun accuracyNote() {
        val u1 = LapTimer.estimateUncertaintyS(1.0, 2.0, 15.0)
        val u10 = LapTimer.estimateUncertaintyS(10.0, 2.0, 15.0)
        assertTrue(u10 < u1)
        assertTrue(LapTimer.estimateUncertaintyS(1.0, 0.0, 15.0) < 0.5) // interpolation reduces raw ±0.5 s
        val note = LapTimer.accuracyNote(1.0, 3.0)
        assertTrue("±~0.50 s" in note, note)
        assertTrue("estimate" in note)
        assertTrue("1 Hz" in note)
    }
}
