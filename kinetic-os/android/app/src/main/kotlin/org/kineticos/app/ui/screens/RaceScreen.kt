package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

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
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val tracks by g.tracks.collectAsStateWithLifecycle()
    val timer by g.lapTimer.collectAsStateWithLifecycle()
    val laps by g.laps.collectAsStateWithLifecycle()
    val fix by g.gps.fix.collectAsStateWithLifecycle()
    val hz by g.gps.updateHz.collectAsStateWithLifecycle()
    val sample by g.connection.sample.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastEvent by remember { mutableStateOf<String?>(null) }
    val defaultTrackName = stringResource(R.string.race_track_n, tracks.size + 1)
    var name by remember { mutableStateOf(defaultTrackName) }
    val noGpsText = stringResource(R.string.race_no_gps)
    val headingText = stringResource(R.string.race_heading)
    val savedText = stringResource(R.string.race_saved)
    val pendingSectors = remember { mutableStateListOf<GateLine>() }
    var accel by remember { mutableStateOf<AccelRun?>(null) }
    var accelRes by remember { mutableStateOf<AccelResult?>(null) }
    var board by remember { mutableStateOf<List<String>>(emptyList()) }
    var lapStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fix) { now = fix?.timeMs ?: 0L }
    LaunchedEffect(Unit) { g.lapEvents.collect { e -> lastEvent = when (e) {
        is LapEvent.LapStarted -> { lapStart = e.timeMs; ctx.getString(R.string.race_lap_started, e.lapNumber) }
        is LapEvent.SectorSplit -> "S${e.sectorIndex + 1}: ${Leaderboard.formatLapTime(e.segmentMs)}"
        is LapEvent.LapCompleted -> { lapStart = e.lap.endMs; ctx.getString(R.string.race_lap_done, e.lap.number, Leaderboard.formatLapTime(e.lap.lapMs), if (e.isBest) ctx.getString(R.string.race_best) else "", if (!e.lap.valid) ctx.getString(R.string.race_invalid_paren) else "") }
    } } }
    LaunchedEffect(accel) { val a = accel ?: return@LaunchedEffect; g.connection.sample.filterNotNull().collect { s -> s.speedMps.value?.let { a.feed(s.t, it) }; accelRes = a.result() } }
    val detected = remember(fix, tracks) { fix?.let { TrackDetector.detect(tracks, it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.race_title))
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.race_gps, hz, fix?.accuracyM?.let { "%.0f".format(it) } ?: "?"), color = p.text)
            run {
                val gHz = hz.coerceAtLeast(0.1)
                val est = LapTimer.estimateUncertaintyS(gHz, fix?.accuracyM, 15.0)
                Text(stringResource(R.string.race_accuracy_note, gHz, 0.5 / gHz, est, fix?.accuracyM?.let { "%.1f m".format(it) } ?: "—"), color = p.textDim, style = labelStyle(10))
            }
        }
        timer?.let { t ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(t.track.name, style = labelStyle(14), color = K.Accent)
                Text(if (lapStart > 0) Leaderboard.formatLapTime((now - lapStart).coerceAtLeast(0)) else "--:--.---", style = numStyle(64, FontWeight.Thin), color = p.text)
                t.bestLap?.let { Text(stringResource(R.string.race_best_lap, Leaderboard.formatLapTime(it.lapMs)), color = K.Regen, style = labelStyle(14)) }
                t.liveDeltaMs?.let { Text("Δ %+.2f s".format(it / 1000.0), color = if (it <= 0) K.Regen else K.Danger, style = numStyle(32)) }
                lastEvent?.let { Text(it, color = K.Warn) }
                laps.reversed().take(10).forEach { l -> Text(stringResource(R.string.race_lap_row, l.number, Leaderboard.formatLapTime(l.lapMs), l.sectorMs.joinToString(" | ") { Leaderboard.formatLapTime(it) }, if (!l.valid) stringResource(R.string.race_invalid) else ""), color = p.textDim) }
                BigButton(stringResource(R.string.race_stop), Modifier.fillMaxWidth().padding(top = 6.dp), color = K.Danger) { g.armLapTimer(null) }
            }
        }
        if (timer == null) {
            detected?.let { Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.race_detected, it.name), color = K.Regen); BigButton(stringResource(R.string.race_start), Modifier.fillMaxWidth()) { g.armLapTimer(it) } } }
            SectionTitle(stringResource(R.string.race_tracks))
            tracks.forEach { t -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.race_track_row, t.name, t.sectors.size), color = p.text, modifier = Modifier.weight(1f))
                BigButton(stringResource(R.string.race_time)) { g.armLapTimer(t) }; Spacer(Modifier.width(4.dp))
                BigButton(stringResource(R.string.race_board)) { scope.launch { board = runCatching { g.api.leaderboard(t.id).map { e -> val o = e.jsonObject; "${o["displayName"]?.jsonPrimitive?.content}: ${Leaderboard.formatLapTime(o["bestLapMs"]?.jsonPrimitive?.long ?: 0)}" } }.getOrElse { listOf(ctx.getString(R.string.race_board_needs_server, it.message)) } } }
                Spacer(Modifier.width(4.dp)); BigButton("✕", color = K.Danger) { scope.launch { g.deleteTrack(t.id) } }
            } }
            board.forEachIndexed { i, b -> Text("${i + 1}. $b", color = p.text) }
            SectionTitle(stringResource(R.string.race_create))
            Field(stringResource(R.string.edit_name), name, { name = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton(stringResource(R.string.race_add_sector), Modifier.weight(1f)) { fix?.let { f -> pendingSectors += GateLine.perpendicular(f.lat, f.lon, f.bearingDeg ?: 0.0, 25.0); g.toast(ctx.getString(R.string.race_sector_set, pendingSectors.size)) } ?: g.toast(noGpsText) }
                BigButton(stringResource(R.string.race_save_sf), Modifier.weight(1.4f)) {
                    val f = fix ?: run { g.toast(noGpsText); return@BigButton }
                    if (f.bearingDeg == null) g.toast(headingText)
                    else scope.launch { g.saveTrack(TrackDefinition(UUID.randomUUID().toString(), name, GateLine.perpendicular(f.lat, f.lon, f.bearingDeg!!, 25.0), pendingSectors.toList())); pendingSectors.clear(); g.toast(savedText) }
                }
            }
        }
        SectionTitle(stringResource(R.string.race_accel))
        Card(Modifier.fillMaxWidth()) {
            if (accel == null) BigButton(stringResource(R.string.race_arm), Modifier.fillMaxWidth()) { accel = AccelRun(); accelRes = null }
            else {
                val r = accelRes
                Text(stringResource(if (r?.started == true) R.string.race_running else R.string.race_ready), color = K.Warn)
                Text(stringResource(R.string.race_0_50, r?.zeroTo50S?.let { "%.2f s".format(it) } ?: "—", r?.zeroTo100S?.let { "%.2f s".format(it) } ?: "—"), color = p.text)
                Text(stringResource(R.string.race_100m, r?.to100mS?.let { "%.2f s".format(it) } ?: "—", r?.to201mS?.let { "%.2f s".format(it) } ?: "—"), color = p.text)
                r?.note?.let { Text(it, color = p.textDim, style = labelStyle(10)) }
                Text(stringResource(R.string.race_speed_src, sample?.speedSource ?: "—"), color = p.textDim, style = labelStyle(10))
                BigButton(stringResource(R.string.race_reset), Modifier.fillMaxWidth()) { accel = null }
            }
        }
    }
}
