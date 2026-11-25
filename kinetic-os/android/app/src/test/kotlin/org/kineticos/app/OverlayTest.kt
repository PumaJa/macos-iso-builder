package org.kineticos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.video.OverlayRenderer
import org.kineticos.app.video.TelemetryTimeline
import org.kineticos.telemetry.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTest {
    private fun s(t: Long, kmh: Double, sim: Boolean = false) = TelemetrySample(t = t, simulated = sim, speedMps = Q(kmh / 3.6, Quality.CALCULATED),
        batteryPowerW = Q(7300.0, Quality.CALCULATED), socPct = Q(64.0, Quality.MEASURED), tempMotorC = Q(71.0, Quality.MEASURED),
        leanDeg = Q(-23.0, Quality.MEASURED), gps = GpsFix(47.0, 8.0, 612.0, null, null, 3.0, t))

    @Test fun timelineMapsVideoTimeWithOffsetAndLimits() {
        val l = listOf(s(10_000, 10.0), s(10_100, 20.0), s(10_200, 30.0))
        val tl = TelemetryTimeline(l, offsetMs = 0)
        assertEquals(10_000, tl.at(0)!!.t); assertEquals(10_100, tl.at(120)!!.t); assertEquals(10_200, tl.at(2_000)!!.t)
        assertNull(tl.at(5_000)) // > 2 s beyond the ride → no overlay rather than stale data
        assertEquals(10_200, TelemetryTimeline(l, offsetMs = 200).at(0)!!.t)
        assertNull(TelemetryTimeline(emptyList(), 0).at(0))
    }

    @Test fun rendererReusesBitmapAndDrawsSomething() {
        val r = OverlayRenderer(720, 300, 15_000.0)
        val a = r.render(s(1, 87.0, sim = true))
        assertSame(a, r.render(s(1, 87.0, sim = true)), "unchanged sample → same bitmap instance (no GPU re-upload)")
        var opaque = 0
        for (y in 0 until a.height step 5) for (x in 0 until a.width step 5) if (android.graphics.Color.alpha(a.getPixel(x, y)) > 0) opaque++
        assertTrue(opaque > 500)
        File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }.resolve("video_overlay.png").outputStream().use { a.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val empty = r.render(null)
        assertEquals(0, android.graphics.Color.alpha(empty.getPixel(10, 10)))
    }
}
