package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.telemetry.Calc
import org.kineticos.telemetry.Q
import org.kineticos.telemetry.Quality

@Composable
fun LiveScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val s by g.connection.sample.collectAsStateWithLifecycle()
    val caps by g.connection.capabilities.collectAsStateWithLifecycle()
    val stats by g.connection.client?.stats?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(org.kineticos.vesc.client.ClientStats()) }
    val hist = remember { mutableStateListOf<org.kineticos.telemetry.TelemetrySample>() }
    LaunchedEffect(s) { s?.let { hist += it; if (hist.size > 300) hist.removeAt(0) } }
    val e = g.connection.engine
    val sim = s?.simulated == true
    val cq = if (sim) Quality.SIMULATED else Quality.CALCULATED
    val pw = s?.batteryPowerW ?: Q.NA

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row { Pill(stringResource(R.string.live_leg_meas, qTag(Quality.MEASURED)), p.text); Spacer(Modifier.width(4.dp)); Pill(stringResource(R.string.live_leg_calc, qTag(Quality.CALCULATED)), p.text); Spacer(Modifier.width(4.dp)); Pill(stringResource(R.string.live_leg_est, qTag(Quality.ESTIMATED)), K.Estimated); Spacer(Modifier.width(4.dp)); Pill(qTag(Quality.SIMULATED), K.Sim) }
        SectionTitle(stringResource(R.string.live_drive))
        FlowRow3 {
            Metric(stringResource(R.string.lm_speed), s?.speedMps ?: Q.NA, "km/h", 1, 3.6); Metric(stringResource(R.string.lm_avg_speed), Q.of(e?.let { if (it.movingTimeMs > 0) it.distanceM / (it.movingTimeMs / 1000.0) else null }, cq), "km/h", 1, 3.6)
            Metric(stringResource(R.string.lm_max_speed), Q.of(e?.maxSpeedMps, cq), "km/h", 1, 3.6); Metric(stringResource(R.string.lm_motor_rpm), s?.motorRpm ?: Q.NA, "rpm"); Metric(stringResource(R.string.lm_erpm), s?.erpm ?: Q.NA, "")
            Metric(stringResource(R.string.lm_duty), s?.duty ?: Q.NA, "%", 0, 100.0); Metric(stringResource(R.string.lm_throttle), s?.throttle ?: Q.NA, "%", 0, 100.0)
            Metric(stringResource(R.string.lm_accel), s?.accelG ?: Q.NA, "g", 2); Metric(stringResource(R.string.lm_lateral), s?.lateralG ?: Q.NA, "g", 2); Metric(stringResource(R.string.lm_lean), s?.leanDeg ?: Q.NA, "°", 0); Metric(stringResource(R.string.lm_pitch), s?.pitchDeg ?: Q.NA, "°", 0)
            Metric(stringResource(R.string.lm_altitude), Q.of(s?.gps?.altM, Quality.MEASURED), "m"); Metric(stringResource(R.string.lm_gps_speed), Q.of(s?.gps?.speedMps, Quality.MEASURED), "km/h", 1, 3.6)
            Metric(stringResource(R.string.lm_trip), Q.of(e?.distanceM, cq), "km", 2, 0.001); Metric(stringResource(R.string.lm_odometer), s?.odometerKm ?: Q.NA, "km", 1); Metric(stringResource(R.string.lm_ride_time), Q.of(e?.movingTimeMs?.div(60000.0), cq), "min", 1)
        }
        SectionTitle(stringResource(R.string.live_power_energy))
        FlowRow3 {
            Metric(stringResource(R.string.lm_battery_power), pw, "kW", 2, 0.001); Metric(stringResource(R.string.lm_battery_power), Q.of(pw.value?.let { Calc.ps(it) }, pw.quality), "PS", 1)
            Metric(stringResource(R.string.lm_motor_power), s?.motorPowerW ?: Q.NA, "kW", 2, 0.001)
            Metric(stringResource(R.string.lm_motor_current), s?.motorCurrentA ?: Q.NA, "A", 1); Metric(stringResource(R.string.lm_battery_current), s?.batteryCurrentA ?: Q.NA, "A", 1); Metric(stringResource(R.string.lm_voltage), s?.voltageV ?: Q.NA, "V", 1)
            Metric(stringResource(R.string.lm_used), s?.wattHours ?: Q.NA, "Wh", 1); Metric(stringResource(R.string.lm_regenerated), s?.wattHoursCharged ?: Q.NA, "Wh", 1); Metric(stringResource(R.string.lm_ah_used), s?.ampHours ?: Q.NA, "Ah", 2)
            Metric(stringResource(R.string.lm_consumption), Q.of(s?.let { e?.whPerKm(it) }, cq), "Wh/km", 0); Metric(stringResource(R.string.lm_range), Q.of(s?.let { e?.rangeKm(it) }, if (sim) Quality.SIMULATED else Quality.ESTIMATED), "km")
            Metric(stringResource(R.string.lm_soc), s?.socPct ?: Q.NA, "%")
        }
        Text(stringResource(R.string.live_throttle_note), color = p.textDim, style = labelStyle(10))
        Text(stringResource(R.string.live_eff_note), color = p.textDim, style = labelStyle(10))
        SectionTitle(stringResource(R.string.live_temps))
        FlowRow3 { Metric(stringResource(R.string.lm_mosfet), s?.tempMosC ?: Q.NA, "°C", 1); Metric(stringResource(R.string.lm_motor), s?.tempMotorC ?: Q.NA, "°C", 1) }
        if (hist.size > 5) Card(Modifier.fillMaxWidth().height(160.dp)) {
            LineChart(listOf(K.Accent to hist.map { it.batteryPowerW.value?.toFloat() }, K.Warn to hist.map { it.tempMosC.value?.toFloat() }, K.Regen to hist.map { it.speedMps.value?.toFloat() }),
                Modifier.fillMaxSize(), labels = listOf(stringResource(R.string.live_ch_power), stringResource(R.string.live_ch_fet), stringResource(R.string.live_ch_speed)))
        }
        s?.bms?.let { b ->
            SectionTitle(stringResource(R.string.live_bms_title, b.cells.size))
            FlowRow3 {
                val mq = if (sim) Quality.SIMULATED else Quality.MEASURED
                Metric(stringResource(R.string.lm_soc), Q.of(b.socPct, mq), "%"); Metric(stringResource(R.string.lm_soh), Q.of(b.sohPct, mq), "%")
                Metric(stringResource(R.string.lm_cell_min), Q.of(b.cellMin, mq), "V", 3); Metric(stringResource(R.string.lm_cell_max), Q.of(b.cellMax, mq), "V", 3); Metric(stringResource(R.string.lm_delta_cell), Q.of(b.cellDelta?.times(1000), if (sim) Quality.SIMULATED else Quality.CALCULATED), "mV")
            }
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val n = b.cells.size.coerceAtLeast(1); val w = size.width / n; val mn = 3.0; val mx = 4.25
                b.cells.forEachIndexed { i, v -> val h = ((v - mn) / (mx - mn)).toFloat().coerceIn(0.02f, 1f) * size.height
                    drawRect(if (v == b.cellMin) K.Warn else K.Accent, Offset(i * w + 1, size.height - h), Size(w - 2, h)) }
            }
 if (b.temps.isNotEmpty()) Text((if (sim) stringResource(R.string.live_sim_prefix) else "") + stringResource(R.string.live_pack_temps, b.temps.joinToString { "%.1f°C".format(it) }), color = p.text)
            b.status?.let { Text(stringResource(R.string.live_bms_status, it), color = p.textDim) }
        } ?: Text(stringResource(if (caps.bms) R.string.live_bms_waiting else R.string.live_bms_missing), color = p.textDim, modifier = Modifier.padding(top = 8.dp))
        val warnHist by g.warnings.history.collectAsStateWithLifecycle()
        SectionTitle(stringResource(R.string.live_warnings, warnHist.size))
        if (warnHist.isEmpty()) Text(stringResource(R.string.live_no_warnings), color = p.textDim)
        warnHist.take(20).forEach { w -> Text(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(w.ts)) + "  " + (if (w.simulated) stringResource(R.string.live_sim_prefix) else "") + w.text,
            color = when (w.level) { org.kineticos.telemetry.WarnLevel.CRITICAL -> K.Danger; org.kineticos.telemetry.WarnLevel.WARN -> K.Warn; else -> p.text }) }
        if (warnHist.isNotEmpty()) BigButton(stringResource(R.string.live_clear), Modifier.padding(top = 4.dp)) { g.warnings.clearHistory() }
        SectionTitle(stringResource(R.string.live_ctrl_link))
        Card(Modifier.fillMaxWidth()) {
            Text("HW ${caps.firmware?.hardwareName ?: "—"} · FW ${caps.firmware?.versionString ?: "—"} ${caps.firmware?.firmwareName ?: ""}", color = p.text)
            Text("UUID ${caps.firmware?.uuidHex ?: "—"}", color = p.textDim)
            Text(stringResource(R.string.live_can, if (caps.canIds.isEmpty()) stringResource(R.string.live_none) else caps.canIds.joinToString { id -> "$id (${caps.canFirmware[id]?.hardwareName ?: "?"})" }), color = p.text)
            Text(stringResource(R.string.live_features, yes(caps.imu), yes(caps.bms), yes(caps.stats), yes(caps.gnss), yes(caps.mcConfTemp), yes(caps.adc), yes(caps.odometer)), color = p.text)
            Text(stringResource(R.string.live_packets, stats.sent, stats.received, stats.timeouts, stats.crcErrors, stats.retries), color = p.textDim)
            Text(stringResource(R.string.live_fault, s?.faultName ?: stringResource(R.string.live_fault_none)), color = if ((s?.faultCode ?: 0) != 0) K.Danger else p.textDim)
        }
    }
}

private fun yes(b: Boolean) = if (b) "✓" else "✗"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRow3(content: @Composable () -> Unit) { FlowRow(Modifier.fillMaxWidth()) { content() } }
