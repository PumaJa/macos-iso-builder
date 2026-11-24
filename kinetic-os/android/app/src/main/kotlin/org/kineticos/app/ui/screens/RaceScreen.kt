package org.kineticos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.racing.*
import java.util.UUID

@Composable
fun RaceScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val tracks by g.tracks.collectAsStateWithLifecycle()
    val timer by g.lapTimer.collectAsStateWithLifecycle()
    val laps by g.laps.collectAsStateWithLifecycle()
    val fix by g.gps.fix.collectAsStateWithLifecycle()
    val hz by g.gps.updateHz.collectAsStateWithLifecycle()
    val sample by g.connection.sample.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastEvent by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("Track ${tracks.size + 1}") }
    val pendingSectors = remember { mutableStateListOf<GateLine>() }
    var accel by remember { mutableStateOf<AccelRun?>(null) }
    var accelRes by remember { mutableStateOf<AccelResult?>(null) }
    var board by remember { mutableStateOf<List<String>>(emptyList()) }
    var lapStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fix) { now = fix?.timeMs ?: 0L }
    LaunchedEffect(Unit) { g.lapEvents.collect { e -> lastEvent = when (e) {
        is LapEvent.LapStarted -> { lapStart = e.timeMs; "Lap ${e.lapNumber} started" }
        is LapEvent.SectorSplit -> "S${e.sectorIndex + 1}: ${Leaderboard.formatLapTime(e.segmentMs)}"
        is LapEvent.LapCompleted -> { lapStart = e.lap.endMs; "Lap ${e.lap.number}: ${Leaderboard.formatLapTime(e.lap.lapMs)}${if (e.isBest) " BEST" else ""}${if (!e.lap.valid) " (invalid)" else ""}" }
    } } }
    LaunchedEffect(accel) { val a = accel ?: return@LaunchedEffect; g.connection.sample.filterNotNull().collect { s -> s.speedMps.value?.let { a.feed(s.t, it) }; accelRes = a.result() } }
    val detected = remember(fix, tracks) { fix?.let { TrackDetector.detect(tracks, it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Racing – closed circuits only")
        Card(Modifier.fillMaxWidth()) {
            Text("GPS: %.1f Hz · ±%s m".format(hz, fix?.accuracyM?.let { "%.0f".format(it) } ?: "?"), color = p.text)
            Text(LapTimer.accuracyNote(hz.coerceAtLeast(0.1), fix?.accuracyM), color = p.textDim, style = labelStyle(10))
        }
        timer?.let { t ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(t.track.name, style = labelStyle(14), color = K.Accent)
                Text(if (lapStart > 0) Leaderboard.formatLapTime((now - lapStart).coerceAtLeast(0)) else "--:--.---", style = numStyle(64, FontWeight.Thin), color = p.text)
                t.bestLap?.let { Text("Best ${Leaderboard.formatLapTime(it.lapMs)}", color = K.Regen, style = labelStyle(14)) }
                t.liveDeltaMs?.let { Text("Δ %+.2f s".format(it / 1000.0), color = if (it <= 0) K.Regen else K.Danger, style = numStyle(32)) }
                lastEvent?.let { Text(it, color = K.Warn) }
                laps.reversed().take(10).forEach { l -> Text("Lap ${l.number}  ${Leaderboard.formatLapTime(l.lapMs)}  ${l.sectorMs.joinToString(" | ") { Leaderboard.formatLapTime(it) }}${if (!l.valid) "  invalid" else ""}", color = p.textDim) }
                BigButton("STOP TIMING", Modifier.fillMaxWidth().padding(top = 6.dp), color = K.Danger) { g.armLapTimer(null) }
            }
        }
        if (timer == null) {
            detected?.let { Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Track detected: ${it.name}", color = K.Regen); BigButton("START TIMING", Modifier.fillMaxWidth()) { g.armLapTimer(it) } } }
            SectionTitle("Tracks")
            tracks.forEach { t -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.name} (${t.sectors.size} sectors)", color = p.text, modifier = Modifier.weight(1f))
                BigButton("TIME") { g.armLapTimer(t) }; Spacer(Modifier.width(4.dp))
                BigButton("BOARD") { scope.launch { board = runCatching { g.api.leaderboard(t.id).map { e -> val o = e.jsonObject; "${o["displayName"]?.jsonPrimitive?.content}: ${Leaderboard.formatLapTime(o["bestLapMs"]?.jsonPrimitive?.long ?: 0)}" } }.getOrElse { listOf("Leaderboard needs server: ${it.message}") } } }
                Spacer(Modifier.width(4.dp)); BigButton("✕", color = K.Danger) { scope.launch { g.deleteTrack(t.id) } }
            } }
            board.forEachIndexed { i, b -> Text("${i + 1}. $b", color = p.text) }
            SectionTitle("Create track (stand on the line, facing race direction)")
            Field("Name", name, { name = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton("+ SECTOR HERE", Modifier.weight(1f)) { fix?.let { f -> pendingSectors += GateLine.perpendicular(f.lat, f.lon, f.bearingDeg ?: 0.0, 25.0); g.toast("Sector ${pendingSectors.size} set") } ?: g.toast("No GPS") }
                BigButton("SAVE START/FINISH HERE", Modifier.weight(1.4f)) {
                    val f = fix ?: run { g.toast("No GPS"); return@BigButton }
                    if (f.bearingDeg == null) g.toast("Heading unknown – roll forward a few metres first")
                    else scope.launch { g.saveTrack(TrackDefinition(UUID.randomUUID().toString(), name, GateLine.perpendicular(f.lat, f.lon, f.bearingDeg!!, 25.0), pendingSectors.toList())); pendingSectors.clear(); g.toast("Track saved") }
                }
            }
        }
        SectionTitle("Acceleration")
        Card(Modifier.fillMaxWidth()) {
            if (accel == null) BigButton("ARM (stop, then launch)", Modifier.fillMaxWidth()) { accel = AccelRun(); accelRes = null }
            else {
                val r = accelRes
                Text(if (r?.started == true) "RUNNING" else "READY – waiting for launch", color = K.Warn)
                Text("0–50 km/h: ${r?.zeroTo50S?.let { "%.2f s".format(it) } ?: "—"}   0–100 km/h: ${r?.zeroTo100S?.let { "%.2f s".format(it) } ?: "—"}", color = p.text)
                Text("100 m: ${r?.to100mS?.let { "%.2f s".format(it) } ?: "—"}   201 m: ${r?.to201mS?.let { "%.2f s".format(it) } ?: "—"}", color = p.text)
                r?.note?.let { Text(it, color = p.textDim, style = labelStyle(10)) }
                Text("Speed source: ${sample?.speedSource ?: "—"} (VESC wheel speed assumes no wheel slip)", color = p.textDim, style = labelStyle(10))
                BigButton("RESET", Modifier.fillMaxWidth()) { accel = null }
            }
        }
    }
}
