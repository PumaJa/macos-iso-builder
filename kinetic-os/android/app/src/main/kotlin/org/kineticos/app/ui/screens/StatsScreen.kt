package org.kineticos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kineticos.app.R
import org.kineticos.app.core.AppJson
import org.kineticos.app.data.RideEntity
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.export.RideSummary
import org.kineticos.telemetry.TelemetrySample
import java.text.SimpleDateFormat
import java.util.*

/** Riding statistics: totals over all rides and per ride (incl. G-force and airtime from the accelerometer). */
@Composable
fun StatsScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val rides by g.db.rides().all().collectAsStateWithLifecycle(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var includeSim by remember { mutableStateOf(false) }
    val list = rides.filter { it.finished && (includeSim || !it.simulated) }
    val sums = remember(list) { list.mapNotNull { r -> r.summaryJson?.let { runCatching { AppJson.decodeFromString<RideSummary>(it) }.getOrNull() }?.let { r to it } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(stringResource(R.string.stats_overall), Modifier.weight(1f), selected = tab == 0) { tab = 0 }
            BigButton(stringResource(R.string.stats_rides), Modifier.weight(1f), selected = tab == 1) { tab = 1 }
        }
        Toggle(stringResource(R.string.stats_include_sim), includeSim, { includeSim = it })
        if (sums.isEmpty()) { Text(stringResource(R.string.stats_empty), color = p.textDim, modifier = Modifier.padding(top = 16.dp)); return@Column }
        if (tab == 0) {
            val totalMs = sums.sumOf { it.second.movingMs }; val dist = sums.sumOf { it.second.distanceM }
            val air = sums.mapNotNull { it.second.airtimeMs }.let { if (it.isEmpty()) null else it.sum() }
            StatBlock(stringResource(R.string.stats_section_ride)) {
                BigStat(stringResource(R.string.stats_time), "%d:%02d".format(totalMs / 3_600_000, totalMs / 60_000 % 60), "h:mm")
                BigStat(stringResource(R.string.stats_distance), "%.1f".format(dist / 1000), "km")
                BigStat(stringResource(R.string.stats_airtime), air?.let { "%.1f".format(it / 60_000.0) } ?: "—", stringResource(R.string.unit_minutes))
                BigStat(stringResource(R.string.stats_rides_count), sums.size.toString(), "")
            }
            StatBlock(stringResource(R.string.stats_section_performance)) {
                BigStat(stringResource(R.string.stats_max_speed), sums.mapNotNull { it.second.maxSpeedMps }.maxOrNull()?.let { "%.0f".format(it * 3.6) } ?: "—", "km/h")
                BigStat(stringResource(R.string.stats_max_power), sums.mapNotNull { it.second.maxPowerW }.maxOrNull()?.let { "%.1f".format(it / 1000) } ?: "—", "kW")
                BigStat(stringResource(R.string.stats_max_g), sums.mapNotNull { it.second.maxG }.maxOrNull()?.let { "%.1f".format(it) } ?: "—", "g")
                BigStat(stringResource(R.string.stats_energy), "%.0f".format(sums.sumOf { it.second.energyUsedWh ?: 0.0 }), "Wh")
            }
        } else {
            var open by remember { mutableStateOf<RideEntity?>(null) }
            sums.forEach { (r, s) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(SimpleDateFormat("EEE dd.MM.yy HH:mm", Locale.getDefault()).format(Date(r.startedAt)), color = p.text)
                            Text("%.1f km · %s · max %.0f km/h · %s".format(s.distanceM / 1000, dur(s.movingMs), (s.maxSpeedMps ?: 0.0) * 3.6,
                                s.airtimeMs?.let { "%.1f s air".format(it / 1000.0) } ?: "—"), style = labelStyle(10), color = p.textDim)
                        }
                        if (r.simulated) Pill("SIM", K.Sim)
                        Spacer(Modifier.width(6.dp))
                        BigButton(if (open?.id == r.id) "▲" else "▼", compact = true) { open = if (open?.id == r.id) null else r }
                    }
                    if (open?.id == r.id) RideStatsDetail(r)
                }
            }
        }
    }
}

@Composable
private fun RideStatsDetail(r: RideEntity) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    var samples by remember { mutableStateOf<List<TelemetrySample>>(emptyList()) }
    LaunchedEffect(r.id) { samples = g.recorder.loadSamples(r.id) }
    if (samples.size < 3) { Text(stringResource(R.string.rides_loading), color = p.textDim); return }
    val gSeries = samples.map { it.gForce.value?.toFloat() }
    Text(stringResource(R.string.stats_gforce_chart), style = labelStyle(10), color = p.textDim, modifier = Modifier.padding(top = 8.dp))
    if (gSeries.any { it != null }) LineChart(listOf(K.Accent to downsample(gSeries, 400)), Modifier.fillMaxWidth().height(140.dp))
    else Text(stringResource(R.string.stats_no_accel), color = p.textDim, style = labelStyle(10))
    // airtime per jump: increments of the cumulative airtime counter
    val jumps = samples.mapNotNull { it.airtimeMs }.zipWithNext().map { (a, b) -> (b - a) / 1000f }.filter { it > 0f }
    Text(stringResource(R.string.stats_airtime_chart, jumps.size), style = labelStyle(10), color = p.textDim, modifier = Modifier.padding(top = 8.dp))
    if (jumps.isNotEmpty()) LineChart(listOf(K.Warn to jumps.map { it }), Modifier.fillMaxWidth().height(110.dp))
    Text(stringResource(R.string.stats_accel_note), color = p.textDim, style = labelStyle(9))
}

@Composable
private fun StatBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title.uppercase(), style = labelStyle(12).copy(fontWeight = FontWeight.Bold), color = p.textDim, modifier = Modifier.align(Alignment.CenterHorizontally))
        content()
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Text(label, color = p.text); if (unit.isNotEmpty()) Text(unit, style = labelStyle(10), color = p.textDim) }
        Text(value, style = numStyle(42, FontWeight.Bold), color = p.text)
    }
}
