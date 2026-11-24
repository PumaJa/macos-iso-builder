package org.kineticos.racing

import org.kineticos.telemetry.GpsFix
import kotlin.test.*

class GhostTest {
    // straight line north at 10 m/s, 1 Hz, 11 fixes (100 m)
    private fun line(v: Double, n: Int = 11) = List(n) {
        val (lat, lon) = enu(0.0, v * it); GpsFix(lat, lon, null, v, null, null, 5_000L + it * 1000L)
    }

    private val ghost = GhostLap(line(10.0))

    @Test fun basics() {
        assertEquals(10_000, ghost.durationMs)
        assertEquals(100.0, ghost.lengthM, 0.1)
    }

    @Test fun positionInterpolated() {
        val p = ghost.positionAt(2_500)
        assertEquals(25.0, p.distM, 0.05)
        val (lat, lon) = enu(0.0, 25.0)
        assertEquals(lat, p.lat, 1e-7); assertEquals(lon, p.lon, 1e-7)
        assertEquals(0.0, ghost.positionAt(-100).distM, 1e-9)
        assertEquals(100.0, ghost.positionAt(99_999).distM, 0.1)
    }

    @Test fun gapByDistance() {
        // rider at 50 m after 6 s; ghost was there at 5 s -> 1 s behind
        assertEquals(1000.0, ghost.gapMs(50.0, 6_000), 1.0)
        // rider reached 50 m after 4 s -> 1 s ahead
        assertEquals(-1000.0, ghost.gapMs(50.0, 4_000), 1.0)
        // spatial gap: at 6 s ghost is at 60 m, rider at 50 -> 10 m behind
        assertEquals(10.0, ghost.gapM(50.0, 6_000), 0.1)
    }

    @Test fun stationaryGhostSegmentHandled() {
        val f = line(10.0, 4) + line(10.0, 4).map { it.copy(timeMs = it.timeMs + 4000) }.map { it.copy(lat = line(10.0,4).last().lat, lon = line(10.0,4).last().lon) }
        val g = GhostLap(f)
        assertTrue(g.timeAtDistance(15.0) in 0.0..3000.0)
        assertTrue(g.timeAtDistance(30.0).isFinite())
    }

    @Test fun needsTwoFixes() {
        assertFailsWith<IllegalArgumentException> { GhostLap(line(10.0, 1)) }
    }
}

class AccelRunTest {
    /** Constant a = 5 m/s², standing for 2 s first. Distance from exact kinematics. */
    private fun run(hz: Int, withDistance: Boolean = true, a: Double = 5.0, seconds: Double = 12.0): AccelRun {
        val r = AccelRun()
        var i = 0
        while (i * 1.0 / hz <= seconds) {
            val t = i * 1.0 / hz
            val m = (t - 2.0).coerceAtLeast(0.0)
            r.feed((t * 1000).toLong(), a * m, if (withDistance) 0.5 * a * m * m else null)
            i++
        }
        return r
    }

    private val tStart = 1.0 / 3.6 / 5.0 // threshold crossing after launch

    @Test fun tenHz() {
        val res = run(10).result()
        assertEquals(13.8889 / 5 - tStart, res.zeroTo50S!!, 0.02)
        assertEquals(27.7778 / 5 - tStart, res.zeroTo100S!!, 0.02)
        assertEquals(Math.sqrt(200.0 / 5) - tStart, res.to100mS!!, 0.03)
        assertEquals(Math.sqrt(2 * 201.168 / 5) - tStart, res.to201mS!!, 0.03)
        assertEquals(10.0, res.sampleHz!!, 0.01)
    }

    @Test fun oneHzWithNote() {
        val res = run(1).result()
        assertEquals(13.8889 / 5 - tStart, res.zeroTo50S!!, 0.02) // speed is linear so interpolation is exact
        assertEquals(Math.sqrt(200.0 / 5) - tStart, res.to100mS!!, 0.15)
        assertTrue("Low GPS rate" in res.note, res.note)
        assertTrue("1.0 Hz" in res.note)
    }

    @Test fun integratedDistanceFallback() {
        val res = run(10, withDistance = false).result()
        assertEquals(Math.sqrt(200.0 / 5) - tStart, res.to100mS!!, 0.05)
        assertTrue("integrated" in res.note)
    }

    @Test fun incompleteRun() {
        val r = run(10, seconds = 5.0) // reaches 15 m/s: 50 km/h only
        val res = r.result()
        assertNotNull(res.zeroTo50S); assertNull(res.zeroTo100S); assertNull(res.to201mS)
        assertFalse(r.isComplete)
    }

    @Test fun noStartWithoutStandstill() {
        val r = AccelRun()
        for (i in 0..50) r.feed(i * 100L, 5.0 + i * 0.5, null) // already rolling
        val res = r.result()
        assertNull(res.zeroTo50S); assertFalse(res.started)
    }

    @Test fun falseStartRearms() {
        val r = AccelRun()
        listOf(0.0, 0.0, 1.0, 0.5, 0.0, 0.0).forEachIndexed { i, v -> r.feed(i * 100L, v, null) }
        val t0 = 600L
        for (i in 0..200) { val t = i * 0.1; r.feed(t0 + i * 100L, 5.0 * t, 0.5 * 5.0 * t * t) }
        val res = r.result()
        assertEquals(13.8889 / 5 - tStart, res.zeroTo50S!!, 0.05) // measured from the second, real start
    }
}

class LeaderboardTest {
    @Test fun sortsAndRanks() {
        val e = listOf(
            LeaderboardEntry("c", 61_000, "t", 3), LeaderboardEntry("a", 59_500, "t", 2),
            LeaderboardEntry("b", 59_500, "t", 1), LeaderboardEntry("x", 50_000, "t", 4, valid = false),
            LeaderboardEntry("o", 40_000, "other", 5),
        )
        val r = Leaderboard.sort(e, "t")
        assertEquals(listOf("b", "a", "c"), r.map { it.entry.name })
        assertEquals(listOf(1, 1, 3), r.map { it.rank }.let { it }.also { })
        assertEquals(listOf(0L, 0L, 1500L), r.map { it.gapToLeaderMs })
    }

    @Test fun includeInvalidLast() {
        val r = Leaderboard.sort(listOf(LeaderboardEntry("x", 50_000, valid = false), LeaderboardEntry("y", 70_000)), includeInvalid = true)
        assertEquals(listOf("y", "x"), r.map { it.entry.name })
    }

    @Test fun formats() {
        assertEquals("1:02.345", Leaderboard.formatLapTime(62_345))
        assertTrue(Leaderboard.sort(emptyList()).isEmpty())
    }
}
