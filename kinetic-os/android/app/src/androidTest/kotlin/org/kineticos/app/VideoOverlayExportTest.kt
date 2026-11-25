package org.kineticos.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.video.ExportState
import org.kineticos.app.video.VideoOverlayExporter
import org.kineticos.telemetry.Q
import org.kineticos.telemetry.Quality
import org.kineticos.telemetry.TelemetrySample
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/** Runs the real Media3 pipeline on a device/emulator: 4 s test video → MP4 with burned-in telemetry overlay. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoOverlayExportTest {
    @Test fun burnsOverlayIntoVideo() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val input = File(ctx.cacheDir, "in.mp4").apply { testCtx.assets.open("test_ride.mp4").use { i -> outputStream().use { i.copyTo(it) } } }
        val samples = (0..40).map { i -> TelemetrySample(t = 1_000_000L + i * 100, simulated = true,
            speedMps = Q(10.0 + i, Quality.SIMULATED), batteryPowerW = Q(5000.0, Quality.SIMULATED), socPct = Q(80.0, Quality.SIMULATED)) }
        val exp = VideoOverlayExporter(ctx)
        exp.start(Uri.fromFile(input), samples, 0, 10_000.0)
        val end = withTimeout(15 * 60_000) { exp.state.first { it is ExportState.Done || it is ExportState.Failed } }
        assertTrue(end is ExportState.Done, "export failed: $end")
        val out = end.file
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.absolutePath) }
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue(dur in 3500..4600, "duration $dur")
        val frameOut = mmr.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        val mIn = MediaMetadataRetriever().apply { setDataSource(input.absolutePath) }
        val frameIn = mIn.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        // overlay sits bottom-left: that region must differ, the top-right corner must stay (almost) the same
        fun diff(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var d = 0L; var n = 0
            for (y in y0 until y1 step 4) for (x in x0 until x1 step 4) {
                val a = frameIn.getPixel(x * frameIn.width / frameOut.width, y * frameIn.height / frameOut.height); val b = frameOut.getPixel(x, y)
                d += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)); n++
            }
            return d.toDouble() / n
        }
        val w = frameOut.width; val h = frameOut.height
        val overlayDiff = diff(w / 20, h * 6 / 10, w * 4 / 10, h * 95 / 100)
        val untouchedDiff = diff(w * 7 / 10, h / 20, w * 95 / 100, h * 3 / 10)
        File(ctx.getExternalFilesDir(null), "overlay_frame.png").outputStream().use { frameOut.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(overlayDiff > 25, "overlay region diff $overlayDiff")
        assertTrue(untouchedDiff < 20, "untouched region diff $untouchedDiff")
        assertTrue(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes", "audio kept")
    }
}
