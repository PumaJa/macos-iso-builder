package org.kineticos.app.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.common.Effect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kineticos.telemetry.TelemetrySample
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val file: File) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Burns the telemetry overlay into the ride video on-device (Media3 Transformer, hardware/software H.264 encoder).
 * Audio is kept. The overlay is redrawn only when the telemetry sample changes.
 */
@UnstableApi
class VideoOverlayExporter(private val ctx: Context) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle); val state: StateFlow<ExportState> = _state
    private var transformer: Transformer? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(video: Uri, samples: List<TelemetrySample>, offsetMs: Long, maxPowerW: Double) = main.post {
        val timeline = TelemetryTimeline(samples, offsetMs)
        val renderer = OverlayRenderer(720, 300, maxPowerW)
        val overlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap = renderer.render(timeline.at(presentationTimeUs / 1000))
            // bottom-left corner, scaled to ~40% of the video width
            override fun getOverlaySettings(presentationTimeUs: Long) = OverlaySettings.Builder()
                .setOverlayFrameAnchor(-1f, -1f).setBackgroundFrameAnchor(-0.95f, -0.9f).setScale(0.8f, 0.8f).build()
        }
        val effects = Effects(ImmutableList.of(), ImmutableList.of<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(video)).setEffects(effects).build()
        val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "kinetic-overlay-${System.currentTimeMillis()}.mp4")
        val t = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { _state.value = ExportState.Done(out); transformer = null }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                _state.value = ExportState.Failed(exportException.message ?: exportException.errorCodeName); out.delete(); transformer = null
            }
        }).build()
        transformer = t
        _state.value = ExportState.Running(0)
        t.start(item, out.absolutePath)
        pollProgress()
    }

    private fun pollProgress() {
        val t = transformer ?: return
        val h = ProgressHolder()
        if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) _state.value = ExportState.Running(h.progress)
        main.postDelayed({ pollProgress() }, 500)
    }

    fun cancel() = main.post { transformer?.cancel(); transformer = null; _state.value = ExportState.Idle }
}
