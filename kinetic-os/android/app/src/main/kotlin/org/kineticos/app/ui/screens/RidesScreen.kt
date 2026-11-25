package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineticos.app.core.AppJson
import org.kineticos.app.data.RideEntity
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.export.*
import org.kineticos.telemetry.TelemetrySample
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RidesScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val rides by g.db.rides().all().collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf<RideEntity?>(null) }
    val compare = remember { mutableStateListOf<String>() }
    var comparing by remember { mutableStateOf(false) }
    open?.let { RideDetail(it) { open = null }; return }
    if (comparing) { CompareRides(compare.toList()) { comparing = false }; return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.rides_title, rides.size)); Spacer(Modifier.weight(1f))
                if (compare.size >= 2) BigButton(stringResource(R.string.rides_compare, compare.size)) { comparing = true }
            }
        }
        items(rides, key = { it.id }) { r ->
            val sum = r.summaryJson?.let { runCatching { AppJson.decodeFromString<RideSummary>(it) }.getOrNull() }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(SimpleDateFormat("EEE dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(r.startedAt)) + (if (!r.finished) stringResource(R.string.rides_in_progress) else ""), color = p.text)
                        Text(sum?.let { stringResource(R.string.rides_summary, it.distanceM / 1000, dur(it.durationMs), (it.maxSpeedMps ?: 0.0) * 3.6, it.energyUsedWh ?: 0.0) } ?: "—", color = p.textDim, style = labelStyle(11))
                        r.profileName?.let { Text(stringResource(R.string.rides_mode, it), color = p.textDim, style = labelStyle(10)) }
                    }
                    if (r.simulated) Pill(stringResource(R.string.q_sim), K.Sim)
                    Spacer(Modifier.width(6.dp))
                    BigButton(if (r.id in compare) "✓" else "+", selected = r.id in compare) { if (r.id in compare) compare -= r.id else compare += r.id }
                }
            }
        }
    }
}

fun dur(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }

@Composable
private fun RideDetail(r: RideEntity, back: () -> Unit) {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    var samples by remember { mutableStateOf<List<TelemetrySample>>(emptyList()) }
    var cursor by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(r.id) { samples = g.recorder.loadSamples(r.id) }
    LaunchedEffect(playing) { while (playing && cursor < 1f) { delay(100); cursor = (cursor + 1f / samples.size.coerceAtLeast(1)).coerceAtMost(1f) }; playing = false }
    val sum = remember(samples) { if (samples.isNotEmpty()) RideSummary.compute(samples) else null }
    val cur = samples.getOrNull((cursor * (samples.size - 1)).toInt().coerceAtLeast(0))
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { video = it }

    if (video != null) { VideoOverlay(video!!, samples) { video = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { BigButton(stringResource(R.string.btn_back)) { back() }; Spacer(Modifier.width(8.dp)); if (r.simulated) Pill(stringResource(R.string.rides_sim_ride), K.Sim) }
        sum?.let { s ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.rides_dist, s.distanceM / 1000, s.distanceSource, dur(s.movingMs), dur(s.durationMs)), color = p.text)
                Text(stringResource(R.string.rides_speeds, (s.avgSpeedMps ?: 0.0) * 3.6, (s.maxSpeedMps ?: 0.0) * 3.6, (s.maxPowerW ?: 0.0) / 1000), color = p.text)
                Text(stringResource(R.string.rides_energy, s.energyUsedWh ?: 0.0, s.energyRegenWh ?: 0.0, s.whPerKm?.let { "%.0f".format(it) } ?: "—", s.elevationGainM, s.elevationLossM), color = p.textDim)
            }
        }
        if (samples.size > 2) {
            RideMap(samples, cursor, Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp))
            LabeledSlider(stringResource(R.string.rides_replay), cursor, { cursor = it }, display = { cur?.let { s -> "%s · %.0f km/h · %.1f kW".format(SimpleDateFormat("HH:mm:ss").format(Date(s.t)), (s.speedMps.value ?: 0.0) * 3.6, (s.batteryPowerW.value ?: 0.0) / 1000) } ?: "" })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton(stringResource(if (playing) R.string.rides_pause else R.string.rides_play), Modifier.weight(1f)) { if (cursor >= 1f) cursor = 0f; playing = !playing }
                BigButton(stringResource(R.string.rides_video), Modifier.weight(1f)) { videoPicker.launch("video/*") }
            }
            chart(stringResource(R.string.ch_speed), listOf(K.Accent to samples.map { it.speedMps.value?.times(3.6)?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_power), listOf(K.Accent to samples.map { it.batteryPowerW.value?.div(1000)?.toFloat() }, K.Estimated to samples.map { it.motorPowerW.value?.div(1000)?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_current), listOf(K.Accent to samples.map { it.batteryCurrentA.value?.toFloat() }, K.Warn to samples.map { it.motorCurrentA.value?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_voltage), listOf(K.Regen to samples.map { it.voltageV.value?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_temp), listOf(K.Warn to samples.map { it.tempMosC.value?.toFloat() }, K.Danger to samples.map { it.tempMotorC.value?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_energy), listOf(K.Accent to samples.map { it.wattHours.value?.toFloat() }, K.Regen to samples.map { it.wattHoursCharged.value?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_accel), listOf(K.Accent to samples.map { it.accelG.value?.toFloat() }), cursor)
            chart(stringResource(R.string.ch_alt), listOf(K.Estimated to samples.map { it.gps?.altM?.toFloat() }), cursor)
        } else Text(stringResource(R.string.rides_loading), color = p.textDim)
        SectionTitle(stringResource(R.string.rides_export))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("CSV", Modifier.weight(1f)) { scope.launch { share(ctx, "ride-${r.id.take(8)}.csv", "text/csv") { CsvExporter.write(samples, it) } } }
            BigButton("JSON", Modifier.weight(1f)) { scope.launch { share(ctx, "ride-${r.id.take(8)}.json", "application/json") { JsonExporter.write(samples, it, mapOf("profile" to (r.profileName ?: ""))) } } }
            BigButton("GPX", Modifier.weight(1f)) { scope.launch { share(ctx, "ride-${r.id.take(8)}.gpx", "application/gpx+xml") { GpxExporter.write("Kinetic ride", samples, it) } } }
            BigButton("SRT", Modifier.weight(1f)) { scope.launch { share(ctx, "ride-${r.id.take(8)}.srt", "application/x-subrip") { it.append(SrtOverlay.build(samples)) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            BigButton(stringResource(R.string.rides_delete), Modifier.weight(1f), color = K.Danger) { scope.launch { g.db.rides().deleteSamples(r.id); g.db.rides().delete(r.id); back() } }
        }
    }
}

@Composable
private fun chart(title: String, series: List<Pair<Color, List<Float?>>>, cursor: Float) {
    Card(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
        Text(title.uppercase(), style = labelStyle(10), color = LocalPalette.current.textDim)
        LineChart(series.map { it.first to downsample(it.second, 600) }, Modifier.fillMaxSize(), cursor)
    }
}
fun downsample(d: List<Float?>, n: Int): List<Float?> = if (d.size <= n) d else List(n) { d[it * d.size / n] }

suspend fun share(ctx: Context, name: String, mime: String, write: (Appendable) -> Unit) {
    val f = withContext(Dispatchers.IO) { File(ctx.cacheDir, "exports").apply { mkdirs() }.resolve(name).also { file -> file.bufferedWriter().use { write(it) } } }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), ctx.getString(R.string.rides_export_chooser, name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Telemetry as SRT subtitles – any video editor/player can burn it into the ride video. */
object SrtOverlay {
    fun build(s: List<TelemetrySample>, everyMs: Long = 500): String = buildString {
        if (s.isEmpty()) return@buildString
        val t0 = s.first().t; var n = 1; var next = t0
        fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
        for (x in s) {
            if (x.t < next) continue
            val a = x.t - t0
            append(n++).append('\n').append(ts(a)).append(" --> ").append(ts(a + everyMs)).append('\n')
            append(fmt(x.speedMps, 0, 3.6)).append(" km/h  ").append(fmt(x.batteryPowerW, 1, 0.001)).append(" kW  ").append(fmt(x.socPct, 0)).append("%")
            if (x.simulated) append("  [SIMULATION]")
            append("\n\n"); next = x.t + everyMs
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoOverlay(uri: Uri, samples: List<TelemetrySample>, back: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val exporter = remember { org.kineticos.app.video.VideoOverlayExporter(ctx) }
    val export by exporter.state.collectAsStateWithLifecycle()
    val maxP = ctx.graph.activeVehicle.collectAsStateWithLifecycle().value?.maxPowerW ?: 10_000.0
    DisposableEffect(Unit) { onDispose { if (exporter.state.value is org.kineticos.app.video.ExportState.Running) exporter.cancel() } }
    var posMs by remember { mutableLongStateOf(0L) }
    var offsetS by remember { mutableFloatStateOf(0f) }
    var vv by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(vv) { while (true) { vv?.let { posMs = it.currentPosition.toLong() }; delay(100) } }
    val t0 = samples.firstOrNull()?.t ?: 0
    val target = t0 + posMs + (offsetS * 1000).toLong()
    val cur = samples.minByOrNull { kotlin.math.abs(it.t - target) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ c -> VideoView(c).apply { setVideoURI(uri); setOnPreparedListener { it.isLooping = false; start() }; vv = this } }, Modifier.fillMaxSize())
            cur?.let { s ->
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp).background(Color.Black.copy(alpha = 0.45f)).padding(10.dp)) {
                    Text(fmt(s.speedMps, 0, 3.6) + " km/h", style = numStyle(48), color = Color.White)
                    Text(fmt(s.batteryPowerW, 1, 0.001) + " kW · " + fmt(s.socPct, 0) + "% · " + fmt(s.tempMotorC, 0) + "°C", style = labelStyle(14), color = K.Accent)
                    if (s.simulated) Text(stringResource(R.string.sim_short), style = labelStyle(12), color = K.Sim)
                }
            }
        }
        LabeledSlider(stringResource(R.string.vid_sync), offsetS, { offsetS = it }, -120f..120f, { "%+.1f s".format(it) })
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(stringResource(R.string.btn_back)) { vv?.stopPlayback(); back() }
            BigButton(stringResource(R.string.vid_playpause)) { vv?.let { if (it.isPlaying) it.pause() else it.start() } }
            when (val e = export) {
                is org.kineticos.app.video.ExportState.Running -> BigButton(stringResource(R.string.vid_cancel, e.percent), color = K.Warn) { exporter.cancel() }
                is org.kineticos.app.video.ExportState.Done -> BigButton(stringResource(R.string.vid_share), color = K.Regen) {
                    val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", e.file)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), ctx.getString(R.string.rides_share_video)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                else -> BigButton(stringResource(R.string.vid_export)) { vv?.pause(); exporter.start(uri, samples, (offsetS * 1000).toLong(), maxP) }
            }
        }
        (export as? org.kineticos.app.video.ExportState.Failed)?.let { Text(stringResource(R.string.vid_failed, it.message ?: ""), color = K.Danger, modifier = Modifier.padding(horizontal = 8.dp)) }
        Row(Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.vid_hint), color = p.textDim, style = labelStyle(10))
        }
    }
}

@Composable
private fun CompareRides(ids: List<String>, back: () -> Unit) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    var data by remember { mutableStateOf<Map<String, List<TelemetrySample>>>(emptyMap()) }
    LaunchedEffect(ids) { data = ids.associateWith { g.recorder.loadSamples(it) } }
    val cols = listOf(K.Accent, K.Warn, K.Regen, K.Sim, K.Danger)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        BigButton(stringResource(R.string.btn_back)) { back() }
        val sums = data.mapValues { RideSummary.compute(it.value) }
        sums.entries.forEachIndexed { i, (id, s) ->
            Text("■ %s  %.1f km · max %.0f km/h · %.0f Wh/km · %s".format(id.take(6), s.distanceM / 1000, (s.maxSpeedMps ?: 0.0) * 3.6, s.whPerKm ?: 0.0, data[id]?.firstOrNull()?.let { if (it.simulated) stringResource(R.string.q_sim) else "" } ?: ""), color = cols[i % cols.size])
        }
        listOf<Pair<String, (TelemetrySample) -> Double?>>(stringResource(R.string.cmp_speed) to { it.speedMps.value?.times(3.6) }, stringResource(R.string.cmp_power) to { it.batteryPowerW.value?.div(1000) },
            stringResource(R.string.cmp_fet) to { it.tempMosC.value }, stringResource(R.string.cmp_wh) to { it.wattHours.value }).forEach { (title, f) ->
            chart(stringResource(R.string.cmp_normalised, title), data.values.mapIndexed { i, l -> cols[i % cols.size] to downsample(l.map { s -> f(s)?.toFloat() }, 400) }, -1f)
        }
        Text(stringResource(R.string.cmp_hint), color = p.textDim, style = labelStyle(10))
    }
}
