package org.kineticos.app.video

import android.graphics.*
import org.kineticos.telemetry.TelemetrySample

/** Maps video time → telemetry sample (pure, unit-testable). */
class TelemetryTimeline(private val samples: List<TelemetrySample>, private val offsetMs: Long) {
    private val t0 = samples.firstOrNull()?.t ?: 0L
    private val times = LongArray(samples.size) { samples[it].t }

    /** Sample whose timestamp is closest to (ride start + video position + offset), or null outside the ride (±2 s). */
    fun at(videoMs: Long): TelemetrySample? {
        if (samples.isEmpty()) return null
        val target = t0 + videoMs + offsetMs
        var i = java.util.Arrays.binarySearch(times, target)
        if (i < 0) i = (-i - 1).coerceIn(0, samples.size - 1)
        val best = listOfNotNull(samples.getOrNull(i - 1), samples.getOrNull(i)).minBy { kotlin.math.abs(it.t - target) }
        return best.takeIf { kotlin.math.abs(it.t - target) <= 2000 }
    }
}

/** Draws the telemetry overlay (original Kinetic style) into a transparent bitmap of the given size. */
class OverlayRenderer(val width: Int, val height: Int, private val maxPowerW: Double) {
    private val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)
    private val u = height / 100f
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34 * u; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); setShadowLayer(3 * u / 4, 0f, 0f, Color.BLACK) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 7 * u; letterSpacing = 0.15f; setShadowLayer(u / 2, 0f, 0f, Color.BLACK) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 242, 242); textSize = 11 * u; setShadowLayer(u / 2, 0f, 0f, Color.BLACK) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * u; strokeCap = Paint.Cap.ROUND }
    private val bg = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private var lastKey: String? = null

    /** Returns the same Bitmap instance when nothing changed (Media3 then skips the GPU upload). */
    fun render(s: TelemetrySample?): Bitmap {
        val key = s?.let { "${it.t}" } ?: "none"
        if (key == lastKey) return bmp
        lastKey = key
        bmp.eraseColor(Color.TRANSPARENT)
        if (s == null) return bmp
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 6 * u, 6 * u, bg)
        val pad = 6 * u
        val spd = s.speedMps.value?.let { "%.0f".format(it * 3.6) } ?: "—"
        c.drawText(spd, pad, 42 * u, big)
        c.drawText("KM/H", pad + big.measureText(spd) + 2 * u, 42 * u, small)
        val pw = s.batteryPowerW.value
        c.drawText(pw?.let { "%.1f kW".format(it / 1000) } ?: "— kW", pad, 60 * u, accent)
        // power bar (cyan) / regen bar (green)
        val frac = ((kotlin.math.abs(pw ?: 0.0)) / maxPowerW).coerceIn(0.0, 1.0).toFloat()
        bar.color = Color.argb(120, 255, 255, 255); c.drawLine(pad, 70 * u, width - pad, 70 * u, bar)
        bar.color = if ((pw ?: 0.0) < 0) Color.rgb(170, 170, 170) else Color.rgb(242, 242, 242)
        if (frac > 0) c.drawLine(pad, 70 * u, pad + (width - 2 * pad) * frac, 70 * u, bar)
        val line = listOfNotNull(s.socPct.value?.let { "%.0f%%".format(it) }, s.tempMotorC.value?.let { "MOT %.0f°C".format(it) },
            s.leanDeg.value?.let { "LEAN %.0f°".format(it) }, s.gps?.altM?.let { "%.0f m".format(it) }).joinToString("   ")
        c.drawText(line, pad, 86 * u, small)
        if (s.simulated) { small.color = Color.rgb(160, 160, 160); c.drawText("SIMULATION", width - pad - small.measureText("SIMULATION"), 12 * u, small); small.color = Color.WHITE }
        return bmp
    }
}
