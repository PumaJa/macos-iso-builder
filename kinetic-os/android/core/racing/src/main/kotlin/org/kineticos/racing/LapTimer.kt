package org.kineticos.racing

import org.kineticos.telemetry.GpsFix
import kotlin.math.*

/**
 * One lap. [sectorMs] holds the duration of each segment of the lap (start→gate1, gate1→gate2, … →finish), so a
 * complete lap on a track with n sector gates has n+1 entries. [fixes] includes interpolated start/end fixes.
 */
data class Lap(
    val number: Int, val startMs: Long, val endMs: Long, val lapMs: Long,
    val sectorMs: List<Long>, val maxSpeedMps: Double, val valid: Boolean,
    val fixes: List<GpsFix> = emptyList(),
)

sealed interface LapEvent {
    data class LapStarted(val lapNumber: Int, val timeMs: Long) : LapEvent
    data class SectorSplit(val lapNumber: Int, val sectorIndex: Int, val segmentMs: Long, val elapsedMs: Long) : LapEvent
    data class LapCompleted(val lap: Lap, val isBest: Boolean) : LapEvent
}

/**
 * Lap timing state machine. Feed GPS fixes in time order. The first start/finish crossing starts lap 1; each later
 * crossing (either direction) closes a lap unless it is within [minLapMs] of the lap start. Sector gates must be
 * crossed in order; a lap missing a sector or containing a GPS gap > [maxGapMs] is flagged invalid (and cannot be best).
 */
class LapTimer(
    val track: TrackDefinition,
    val minLapMs: Long = 10_000,
    val maxGapMs: Long = 5_000,
) {
    private var prev: GpsFix? = null
    private var active = false
    private var lapNumber = 1
    private var lapStartMs = 0L
    private var lapFixes = ArrayList<GpsFix>()
    private var cumDist = 0.0
    private var lastGateMs = 0L
    private var nextSector = 0
    private var sectorTimes = ArrayList<Long>()
    private var maxSpeed = 0.0
    private var gap = false

    private val _laps = ArrayList<Lap>()
    val laps: List<Lap> get() = _laps
    var bestLap: Lap? = null; private set
    private var ghost: GhostLap? = null

    /** Live delta vs best lap in ms (positive = slower), by distance along the lap; null if unavailable. */
    var liveDeltaMs: Long? = null; private set
    val isLapActive get() = active
    val currentLapElapsedMs: Long get() = if (active) (prev?.timeMs ?: lapStartMs) - lapStartMs else 0L
    val currentLapDistanceM: Double get() = if (active) cumDist else 0.0

    fun reset() {
        prev = null; active = false; lapNumber = 1; _laps.clear(); bestLap = null; ghost = null; liveDeltaMs = null
        lapFixes = ArrayList(); cumDist = 0.0
    }

    private fun startLap(startFix: GpsFix) {
        active = true; lapStartMs = startFix.timeMs; lastGateMs = startFix.timeMs
        lapFixes = arrayListOf(startFix); cumDist = 0.0; nextSector = 0; sectorTimes = ArrayList()
        maxSpeed = 0.0; gap = false; liveDeltaMs = null
    }

    private fun addFix(f: GpsFix) {
        cumDist += Geo.haversineM(lapFixes.last(), f); lapFixes.add(f)
    }

    private data class Hit(val t: Double, val sector: Int) // sector -1 = start/finish

    fun feed(fix: GpsFix): List<LapEvent> {
        val p = prev
        if (p != null && fix.timeMs <= p.timeMs) return emptyList()
        prev = fix
        if (p == null) return emptyList()
        val events = ArrayList<LapEvent>()
        val segLen = Geo.haversineM(p, fix)
        val dtS = (fix.timeMs - p.timeMs) / 1000.0

        val hits = ArrayList<Hit>()
        Geo.segmentCrossing(p, fix, track.startFinish)?.let { hits += Hit(it.t, -1) }
        if (active && nextSector < track.sectors.size)
            Geo.segmentCrossing(p, fix, track.sectors[nextSector])?.let { hits += Hit(it.t, nextSector) }
        hits.sortBy { it.t }

        if (active) {
            val sp = fix.speedMps ?: (segLen / dtS)
            if (sp.isFinite()) maxSpeed = max(maxSpeed, sp)
            if (fix.timeMs - p.timeMs > maxGapMs) gap = true
        }

        for (h in hits) {
            val x = Geo.interpolate(p, fix, h.t)
            if (h.sector >= 0) {
                if (!active || h.sector != nextSector) continue
                addFix(x)
                val seg = x.timeMs - lastGateMs
                sectorTimes += seg; lastGateMs = x.timeMs; nextSector++
                events += LapEvent.SectorSplit(lapNumber, h.sector, seg, x.timeMs - lapStartMs)
            } else {
                if (!active) {
                    startLap(x); events += LapEvent.LapStarted(lapNumber, x.timeMs)
                } else if (x.timeMs - lapStartMs >= minLapMs) {
                    addFix(x)
                    sectorTimes += x.timeMs - lastGateMs
                    val lap = Lap(
                        lapNumber, lapStartMs, x.timeMs, x.timeMs - lapStartMs, sectorTimes.toList(), maxSpeed,
                        valid = nextSector == track.sectors.size && !gap, fixes = lapFixes.toList(),
                    )
                    _laps += lap
                    val best = bestLap
                    val isBest = lap.valid && (best == null || lap.lapMs < best.lapMs)
                    if (isBest) { bestLap = lap; ghost = GhostLap(lap.fixes) }
                    events += LapEvent.LapCompleted(lap, isBest)
                    lapNumber++
                    startLap(x); events += LapEvent.LapStarted(lapNumber, x.timeMs)
                }
                // else: crossing too soon after the start – ignored
            }
        }
        if (active) {
            addFix(fix)
            val g = ghost
            liveDeltaMs = if (g != null && lapFixes.size >= 2)
                g.gapMs(cumDist, fix.timeMs - lapStartMs).roundToLong() else null
        }
        return events
    }

    companion object {
        /**
         * Human-readable estimate of timing uncertainty. Assumptions (heuristic, not a guarantee):
         *  - without interpolation a crossing is only known to within one sample interval → ±0.5/Hz s;
         *  - linear interpolation between fixes removes most of that, leaving ~20 % of it (curvature, acceleration);
         *  - GPS position error maps to time error as accuracy/speed (partly cancels between start and finish
         *    crossings because the error is largely correlated, but we do not assume that).
         */
        fun estimateUncertaintyS(gpsHz: Double, accuracyM: Double?, speedMps: Double = 15.0): Double {
            require(gpsHz > 0)
            val interp = 0.5 / gpsHz * 0.2
            val pos = if (accuracyM != null && speedMps > 0.5) accuracyM / speedMps else 0.0
            return sqrt(interp * interp + pos * pos)
        }

        fun accuracyNote(gpsHz: Double, accuracyM: Double?, speedMps: Double = 15.0): String {
            val raw = 0.5 / gpsHz
            val est = estimateUncertaintyS(gpsHz, accuracyM, speedMps)
            val acc = accuracyM?.let { "%.1f m".format(java.util.Locale.ROOT, it) } ?: "unknown"
            return ("At %.0f Hz a crossing is only known to ±~%.2f s without interpolation; linear interpolation of the " +
                "crossing time reduces this, giving an estimated ±~%.2f s (GPS accuracy %s at %.0f km/h). This is an " +
                "estimate that assumes roughly constant speed between fixes and uncorrelated position error; " +
                "it is not a certified timing accuracy.")
                .format(java.util.Locale.ROOT, gpsHz, raw, est, acc, speedMps * 3.6)
        }
    }
}
