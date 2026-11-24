package org.kineticos.app.ui.screens

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
        Row { Pill("MEAS = measured", p.text); Spacer(Modifier.width(4.dp)); Pill("CALC = calculated", p.text); Spacer(Modifier.width(4.dp)); Pill("EST = estimate", K.Estimated); Spacer(Modifier.width(4.dp)); Pill("SIM", K.Sim) }
        SectionTitle("Drive")
        FlowRow3 {
            Metric("Speed", s?.speedMps ?: Q.NA, "km/h", 1, 3.6); Metric("Avg speed", Q.of(e?.let { if (it.movingTimeMs > 0) it.distanceM / (it.movingTimeMs / 1000.0) else null }, cq), "km/h", 1, 3.6)
            Metric("Max speed", Q.of(e?.maxSpeedMps, cq), "km/h", 1, 3.6); Metric("Motor RPM", s?.motorRpm ?: Q.NA, "rpm"); Metric("ERPM", s?.erpm ?: Q.NA, "")
            Metric("Duty", s?.duty ?: Q.NA, "%", 0, 100.0); Metric("Throttle", s?.throttle ?: Q.NA, "%", 0, 100.0)
            Metric("Accel", s?.accelG ?: Q.NA, "g", 2); Metric("Lateral", s?.lateralG ?: Q.NA, "g", 2); Metric("Lean", s?.leanDeg ?: Q.NA, "°", 0); Metric("Pitch", s?.pitchDeg ?: Q.NA, "°", 0)
            Metric("Altitude", Q.of(s?.gps?.altM, if (sim) Quality.SIMULATED else Quality.MEASURED), "m"); Metric("GPS speed", Q.of(s?.gps?.speedMps, Quality.MEASURED), "km/h", 1, 3.6)
            Metric("Trip", Q.of(e?.distanceM, cq), "km", 2, 0.001); Metric("Ride time", Q.of(e?.movingTimeMs?.div(60000.0), cq), "min", 1)
        }
        SectionTitle("Power & energy")
        FlowRow3 {
            Metric("Battery power", pw, "kW", 2, 0.001); Metric("Battery power", Q.of(pw.value?.let { Calc.ps(it) }, pw.quality), "PS", 1)
            Metric("Motor power", s?.motorPowerW ?: Q.NA, "kW", 2, 0.001)
            Metric("Motor current", s?.motorCurrentA ?: Q.NA, "A", 1); Metric("Battery current", s?.batteryCurrentA ?: Q.NA, "A", 1); Metric("Voltage", s?.voltageV ?: Q.NA, "V", 1)
            Metric("Used", s?.wattHours ?: Q.NA, "Wh", 1); Metric("Regenerated", s?.wattHoursCharged ?: Q.NA, "Wh", 1); Metric("Ah used", s?.ampHours ?: Q.NA, "Ah", 2)
            Metric("Consumption", Q.of(s?.let { e?.whPerKm(it) }, cq), "Wh/km", 0); Metric("Range", Q.of(s?.let { e?.rangeKm(it) }, if (sim) Quality.SIMULATED else Quality.ESTIMATED), "km")
            Metric("SoC", s?.socPct ?: Q.NA, "%")
        }
        Text("Efficiency (motor/battery) is not shown: VESC does not measure mechanical output, so a reliable value cannot be determined.", color = p.textDim, style = labelStyle(10))
        SectionTitle("Temperatures")
        FlowRow3 { Metric("MOSFET", s?.tempMosC ?: Q.NA, "°C", 1); Metric("Motor", s?.tempMotorC ?: Q.NA, "°C", 1) }
        if (hist.size > 5) Card(Modifier.fillMaxWidth().height(160.dp)) {
            LineChart(listOf(K.Accent to hist.map { it.batteryPowerW.value?.toFloat() }, K.Warn to hist.map { it.tempMosC.value?.toFloat() }, K.Regen to hist.map { it.speedMps.value?.toFloat() }),
                Modifier.fillMaxSize(), labels = listOf("Power", "FET °C", "Speed"))
        }
        s?.bms?.let { b ->
            SectionTitle("BMS (${b.cells.size} cells)")
            FlowRow3 {
                Metric("SoC", Q.of(b.socPct, if (sim) Quality.SIMULATED else Quality.MEASURED), "%"); Metric("SoH", Q.of(b.sohPct, if (sim) Quality.SIMULATED else Quality.MEASURED), "%")
                Metric("Cell min", Q.of(b.cellMin, Quality.MEASURED), "V", 3); Metric("Cell max", Q.of(b.cellMax, Quality.MEASURED), "V", 3); Metric("Δ cell", Q.of(b.cellDelta?.times(1000), Quality.MEASURED), "mV")
            }
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val n = b.cells.size.coerceAtLeast(1); val w = size.width / n; val mn = 3.0; val mx = 4.25
                b.cells.forEachIndexed { i, v -> val h = ((v - mn) / (mx - mn)).toFloat().coerceIn(0.02f, 1f) * size.height
                    drawRect(if (v == b.cellMin) K.Warn else K.Accent, Offset(i * w + 1, size.height - h), Size(w - 2, h)) }
            }
            if (b.temps.isNotEmpty()) Text("Pack temps: " + b.temps.joinToString { "%.1f°C".format(it) }, color = p.text)
            b.status?.let { Text("BMS status: $it", color = p.textDim) }
        } ?: Text(if (caps.bms) "BMS: waiting for data" else "BMS: not detected (no COMM_BMS_GET_VALUES reply)", color = p.textDim, modifier = Modifier.padding(top = 8.dp))
        SectionTitle("Controller & link")
        Card(Modifier.fillMaxWidth()) {
            Text("HW ${caps.firmware?.hardwareName ?: "—"} · FW ${caps.firmware?.versionString ?: "—"} ${caps.firmware?.firmwareName ?: ""}", color = p.text)
            Text("UUID ${caps.firmware?.uuidHex ?: "—"}", color = p.textDim)
            Text("CAN devices: ${if (caps.canIds.isEmpty()) "none" else caps.canIds.joinToString { id -> "$id (${caps.canFirmware[id]?.hardwareName ?: "?"})" }}", color = p.text)
            Text("Features: IMU ${yes(caps.imu)} · BMS ${yes(caps.bms)} · Stats ${yes(caps.stats)} · VESC GNSS ${yes(caps.gnss)} · Runtime limits ${yes(caps.mcConfTemp)}", color = p.text)
            Text("Packets sent ${stats.sent} · received ${stats.received} · timeouts ${stats.timeouts} · CRC errors ${stats.crcErrors} · retries ${stats.retries}", color = p.textDim)
            Text("Fault: ${s?.faultName ?: "NONE"}", color = if ((s?.faultCode ?: 0) != 0) K.Danger else p.textDim)
        }
    }
}

private fun yes(b: Boolean) = if (b) "✓" else "✗"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRow3(content: @Composable () -> Unit) { FlowRow(Modifier.fillMaxWidth()) { content() } }
