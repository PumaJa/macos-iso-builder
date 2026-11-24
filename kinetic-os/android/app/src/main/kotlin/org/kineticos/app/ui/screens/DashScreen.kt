package org.kineticos.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.data.PowerUnit
import org.kineticos.app.data.SpeedUnit
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.telemetry.Calc
import org.kineticos.telemetry.Q
import org.kineticos.telemetry.Quality
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashScreen(go: (Screen) -> Unit) {
    val g = LocalContext.current.graph
    val p = LocalPalette.current
    val s by g.connection.sample.collectAsStateWithLifecycle()
    val status by g.connection.status.collectAsStateWithLifecycle()
    val settings by g.settings.collectAsStateWithLifecycle()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val applied by g.appliedProfile.collectAsStateWithLifecycle()
    val rec by g.recorder.active.collectAsStateWithLifecycle()
    val profiles by g.profiles.collectAsStateWithLifecycle()
    val gpsFix by g.gps.fix.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    val speedScale = if (settings.speedUnit == SpeedUnit.KMH) 3.6 else 2.236936
    val speedUnit = if (settings.speedUnit == SpeedUnit.KMH) "km/h" else "mph"
    val power = s?.batteryPowerW ?: Q.NA
    val pw = power.value ?: 0.0
    val (pScale, pUnit) = when (settings.powerUnit) { PowerUnit.KW -> 1 / 1000.0 to "kW"; PowerUnit.PS -> 1 / 735.49875 to "PS"; PowerUnit.HP -> 1 / 745.699872 to "hp" }
    val maxP = vehicle?.maxPowerW ?: 10_000.0
    val simulated = s?.simulated == true
    val engine = g.connection.engine
    val range = s?.let { engine?.rangeKm(it) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // status strip
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val (txt, col) = when (val st = status) {
                is ConnStatus.Online -> (if (st.simulated) "SIM" else "VESC") to (if (st.simulated) K.Sim else K.Regen)
                is ConnStatus.Connecting -> "CONNECTING" to K.Warn
                is ConnStatus.Reconnecting -> "RECONNECT ${st.inMs / 1000}s" to K.Warn
                is ConnStatus.Error -> "ERROR" to K.Danger
                ConnStatus.Idle -> "OFFLINE" to p.textDim
            }
            Pill(txt, col, Modifier.padding(end = 6.dp))
            Pill(if (gpsFix != null) "GPS ±%.0fm".format(gpsFix!!.accuracyM ?: 0.0) else "NO GPS", if (gpsFix != null) K.Accent else p.textDim, Modifier.padding(end = 6.dp))
            if (rec != null) Pill("● REC", K.Danger)
            Spacer(Modifier.weight(1f))
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), style = numStyle(22), color = p.text)
        }
        if (status !is ConnStatus.Online) {
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Text("No controller connected", style = labelStyle(14), color = p.text)
                Text("Values below are not live. Connect a VESC via Bluetooth, USB or WiFi.", color = p.textDim)
                Spacer(Modifier.height(8.dp))
                BigButton("CONNECT", Modifier.fillMaxWidth()) { go(Screen.CONNECT) }
            }
        }
        val main: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth(0.95f).aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
                    PowerArc((kotlin.math.abs(pw) / maxP).toFloat(), pw < 0, Modifier.fillMaxSize(), simulated)
                    val sp = s?.speedMps ?: Q.NA
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmt(sp, 0, speedScale), style = numStyle(if (landscape) 110 else 96, FontWeight.Thin), color = qColor(sp.quality, p.text), maxLines = 1)
                        Text("$speedUnit · ${s?.speedSource?.uppercase() ?: "—"}", style = labelStyle(12), color = p.textDim)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(power, 1, pScale), style = numStyle(40), color = qColor(power.quality, if (pw < 0) K.Regen else K.Accent))
                    Text(" $pUnit  ${if (pw < 0) "REGEN" else "POWER"}", style = labelStyle(12), color = p.textDim)
                }
            }
        }
        val side: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val soc = s?.socPct ?: Q.NA
                    Text(fmt(soc, 0) + "%", style = numStyle(34), color = qColor(soc.quality, p.text))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        BatteryBar(soc.value, soc.quality, Modifier.fillMaxWidth())
                        Text("${qTag(soc.quality)} · ${range?.let { "≈%.0f km range (EST)".format(it) } ?: "range n/a"}", style = labelStyle(10), color = p.textDim)
                    }
                }
                Row {
                    Metric("Volt", s?.voltageV ?: Q.NA, "V", 1, modifier = Modifier.weight(1f))
                    Metric("Batt A", s?.batteryCurrentA ?: Q.NA, "A", 0, modifier = Modifier.weight(1f))
                    Metric("Motor A", s?.motorCurrentA ?: Q.NA, "A", 0, modifier = Modifier.weight(1f))
                }
                Row {
                    Metric("FET", s?.tempMosC ?: Q.NA, "°C", 0, modifier = Modifier.weight(1f))
                    Metric("Motor", s?.tempMotorC ?: Q.NA, "°C", 0, modifier = Modifier.weight(1f))
                    Metric("Trip", Q.of(engine?.distanceM?.div(1000), if (simulated) Quality.SIMULATED else Quality.CALCULATED), "km", 1, modifier = Modifier.weight(1f))
                }
                if ((s?.faultCode ?: 0) != 0) Pill("FAULT: ${s?.faultName ?: s?.faultCode}", K.Danger, Modifier.padding(4.dp))
            }
        }
        if (landscape) Row(Modifier.weight(1f)) { main(Modifier.weight(1.3f).fillMaxHeight()); side(Modifier.weight(1f).padding(start = 8.dp)) }
        else { main(Modifier.fillMaxWidth().weight(1f)); side(Modifier.fillMaxWidth()) }

        // vehicle + quick modes
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vehicle?.photoUri != null) AsyncImage(vehicle!!.photoUri, vehicle!!.name, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.padding(horizontal = 6.dp).widthIn(max = 96.dp)) {
                Text(vehicle?.name ?: "No vehicle", style = labelStyle(12), color = p.text, maxLines = 1)
                Text(applied?.name ?: "STOCK", style = labelStyle(10), color = K.Accent)
            }
            profiles.take(4).forEach { pr ->
                BigButton(pr.name, Modifier.weight(1f).padding(horizontal = 2.dp), selected = applied?.id == pr.id, compact = true) { scope.launch { g.toast(g.applyProfile(pr)) } }
            }
        }
    }
}
