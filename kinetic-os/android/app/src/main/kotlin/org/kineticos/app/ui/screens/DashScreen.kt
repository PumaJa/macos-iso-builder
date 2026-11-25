package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import androidx.compose.foundation.Image
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.data.PowerUnit
import org.kineticos.app.data.PrimaryValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val script by g.connection.scriptStatus.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // status strip (left gap = floating back arrow)
        Row(Modifier.fillMaxWidth().padding(start = 46.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val (txt, col) = when (val st = status) {
                is ConnStatus.Online -> (if (st.simulated) stringResource(R.string.q_sim) else "VESC") to (if (st.simulated) K.Sim else K.Regen)
                is ConnStatus.Connecting -> stringResource(R.string.dash_st_connecting) to K.Warn
                is ConnStatus.Reconnecting -> stringResource(R.string.dash_st_reconnect, st.inMs / 1000) to K.Warn
                is ConnStatus.Error -> stringResource(R.string.dash_st_error) to K.Danger
                ConnStatus.Idle -> stringResource(R.string.dash_st_offline) to p.textDim
            }
            Pill(txt, col, Modifier.padding(end = 6.dp))
            Pill(if (gpsFix != null) stringResource(R.string.dash_gps, gpsFix!!.accuracyM ?: 0.0) else stringResource(R.string.dash_no_gps), if (gpsFix != null) K.Accent else p.textDim, Modifier.padding(end = 6.dp))
            if (rec != null) Pill(stringResource(R.string.dash_rec), K.Danger)
            Spacer(Modifier.weight(1f))
            if (landscape) { TcPill(); NeutralPill(); LockPill(); Spacer(Modifier.width(8.dp)) }
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), style = numStyle(26, FontWeight.Bold), color = p.text, maxLines = 1)
        }
        // second strip: riding time, neutral, lock (own row so nothing gets squeezed)
        if (!landscape) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            g.connection.engine?.movingTimeMs?.takeIf { status is ConnStatus.Online }?.let { ms ->
                Pill("⏱ %d:%02d".format(ms / 3_600_000, ms / 60_000 % 60), p.text, Modifier.padding(end = 6.dp)) }
            Spacer(Modifier.weight(1f))
            TcPill(); NeutralPill()
            LockPill()
        }
        if (status !is ConnStatus.Online) {
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dash_no_ctrl), style = labelStyle(14), color = p.text)
                Text(stringResource(R.string.dash_no_ctrl_sub), color = p.textDim)
                Spacer(Modifier.height(8.dp))
                BigButton(stringResource(R.string.dash_connect), Modifier.fillMaxWidth()) { go(Screen.CONNECT) }
            }
        }
        // Swipe left/right over the gauge = next/previous ride mode (same SafetyGate rules as every other mode change)
        val swipeMod = Modifier.pointerInput(profiles) {
            var acc = 0f
            detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                if (kotlin.math.abs(acc) > size.width * 0.25f && profiles.isNotEmpty()) {
                    val cur = profiles.indexOfFirst { it.id == applied?.id }.let { if (it < 0) profiles.size - 1 else it }
                    val next = profiles[(cur + if (acc < 0) 1 else profiles.size - 1) % profiles.size]
                    scope.launch { g.toast(g.applyProfile(next)) }
                }
            }) { _, dx -> acc += dx }
        }
        val main: @Composable (Modifier, Boolean) -> Unit = { m0, showBadge ->
            val m = m0.then(swipeMod)
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth(0.95f).aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
                    PowerArc((kotlin.math.abs(pw) / maxP).toFloat(), pw < 0, Modifier.fillMaxSize(), simulated)
                    val (mainQ, mainScale, mainLabel) = when (settings.primaryValue) {
                        PrimaryValue.SPEED -> Triple(s?.speedMps ?: Q.NA, speedScale, "$speedUnit · " + when (s?.speedSource) {
                            "vesc" -> "VESC"; "gps" -> "GPS"; else -> stringResource(R.string.dash_speed_source_none) })
                        PrimaryValue.POWER -> Triple(power, pScale, "$pUnit · ${qTag(power.quality)}")
                        PrimaryValue.BATTERY_CURRENT -> Triple(s?.batteryCurrentA ?: Q.NA, 1.0, stringResource(R.string.dash_a_battery, qTag((s?.batteryCurrentA ?: Q.NA).quality)))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmt(mainQ, 0, mainScale), style = numStyle(if (landscape) 130 else 120, FontWeight.Bold), color = qColor(mainQ.quality, p.text), maxLines = 1)
                        Text(mainLabel, style = labelStyle(12), color = p.textDim)
                    }
                }
                if (showBadge) Spacer(Modifier.height(8.dp))
                if (showBadge) Row(verticalAlignment = Alignment.Bottom) {
                    if (settings.primaryValue == PrimaryValue.SPEED) {
                        Text(fmt(power, 1, pScale), style = numStyle(40), color = qColor(power.quality, if (pw < 0) K.Regen else K.Accent))
                        Text(" $pUnit  ${stringResource(if (pw < 0) R.string.dash_regen else R.string.dash_power)}", style = labelStyle(12), color = p.textDim)
                    } else {
                        Text(fmt(s?.speedMps ?: Q.NA, 0, speedScale), style = numStyle(40), color = qColor((s?.speedMps ?: Q.NA).quality, K.Accent))
                        Text(" $speedUnit", style = labelStyle(12), color = p.textDim)
                    }
                }
                if (showBadge) ModeBadge(applied, script)
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
                        Text("${qTag(soc.quality)} · ${range?.let { stringResource(R.string.dash_range_est, it) } ?: stringResource(R.string.dash_range_na)}", style = labelStyle(10), color = p.textDim)
                    }
                }
                settings.dashTiles.take(6).chunked(3).forEach { row ->
                    Row { row.forEach { key -> DashTile(key, s, engine, simulated, Modifier.weight(1f)) }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
                if ((s?.faultCode ?: 0) != 0) Pill(stringResource(R.string.dash_fault, (s?.faultName ?: s?.faultCode).toString()), K.Danger, Modifier.padding(4.dp))
            }
        }
        if (landscape) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // left: mode · riding time · power (large, readable at a glance)
            Column(Modifier.weight(0.75f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                BigStat(stringResource(R.string.home_mode), (applied?.name ?: stringResource(R.string.mode_stock)).uppercase(), if (applied == null) p.textDim else p.text)
                val ms = engine?.movingTimeMs ?: 0L
                BigStat(stringResource(R.string.home_riding_time), "%d:%02d".format(ms / 3_600_000, ms / 60_000 % 60), p.text)
                BigStat("$pUnit · " + stringResource(if (pw < 0) R.string.dash_regen else R.string.dash_power), fmt(power, 1, pScale), qColor(power.quality, p.text))
            }
            // centre: gauge + main value
            main(Modifier.weight(1.5f).fillMaxHeight(), false)
            // right: battery + selected tiles
            Column(Modifier.weight(0.85f).fillMaxHeight().padding(start = 8.dp), verticalArrangement = Arrangement.Center) {
                val soc = s?.socPct ?: Q.NA
                Text(stringResource(R.string.home_battery), style = labelStyle(10), color = p.textDim)
                Text(fmt(soc, 0) + "%", style = numStyle(64, FontWeight.Bold), color = qColor(soc.quality, p.text))
                BatteryBar(soc.value, soc.quality, Modifier.fillMaxWidth())
                Text("${qTag(soc.quality)} · ${range?.let { stringResource(R.string.dash_range_est, it) } ?: stringResource(R.string.dash_range_na)}", style = labelStyle(10), color = p.textDim)
                Spacer(Modifier.height(6.dp))
                settings.dashTiles.take(2).chunked(2).forEach { row ->
                    Row { row.forEach { key -> DashTile(key, s, engine, simulated, Modifier.weight(1f)) }; repeat(2 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
                if ((s?.faultCode ?: 0) != 0) Pill(stringResource(R.string.dash_fault, (s?.faultName ?: s?.faultCode).toString()), K.Danger, Modifier.padding(4.dp))
            }
        }
        else { main(Modifier.fillMaxWidth().weight(1f), true); side(Modifier.fillMaxWidth()) }

        // vehicle + quick modes
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vehicle?.photoUri != null) VehiclePhoto(vehicle!!.photoUri, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.padding(horizontal = 6.dp).widthIn(max = 96.dp)) {
                Text(vehicle?.name ?: stringResource(R.string.dash_no_vehicle), style = labelStyle(12), color = p.text, maxLines = 2)
                Text(applied?.name ?: stringResource(R.string.mode_stock), style = labelStyle(10), color = K.Accent)
            }
            profiles.take(4).forEach { pr ->
                BigButton(pr.name, Modifier.weight(1f).padding(horizontal = 2.dp), selected = applied?.id == pr.id, compact = true) { scope.launch { g.toast(g.applyProfile(pr)) } }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    Column {
        Text(label.uppercase(), style = labelStyle(10), color = p.textDim, maxLines = 1)
        Text(value, style = numStyle(46, FontWeight.Bold), color = color, maxLines = 1)
    }
}

/** Catalog of selectable dash tiles (Settings → Dash tiles). */
val DASH_TILE_CATALOG: Map<String, Int> = linkedMapOf(
    "VOLT" to R.string.tilecat_volt, "BATT_A" to R.string.tilecat_batt_a, "MOTOR_A" to R.string.tilecat_motor_a, "FET" to R.string.tilecat_fet, "MOTOR_T" to R.string.tilecat_motor_t, "TRIP" to R.string.tilecat_trip, "ODO" to R.string.tilecat_odo, "WHKM" to R.string.tilecat_whkm, "RANGE" to R.string.tilecat_range, "DUTY" to R.string.tilecat_duty, "THROTTLE" to R.string.tilecat_throttle, "LEAN" to R.string.tilecat_lean, "ALT" to R.string.tilecat_alt, "PS" to R.string.tilecat_ps, "WH" to R.string.tilecat_wh, "REGEN" to R.string.tilecat_regen, "AH" to R.string.tilecat_ah,
)

@Composable
fun DashTile(key: String, s: org.kineticos.telemetry.TelemetrySample?, engine: org.kineticos.telemetry.TelemetryEngine?, simulated: Boolean, modifier: Modifier) {
    val cq = if (simulated) Quality.SIMULATED else Quality.CALCULATED
    val eq = if (simulated) Quality.SIMULATED else Quality.ESTIMATED
    when (key) {
        "VOLT" -> Metric(stringResource(R.string.tile_volt), s?.voltageV ?: Q.NA, "V", 1, modifier = modifier)
        "BATT_A" -> Metric(stringResource(R.string.tile_batt_a), s?.batteryCurrentA ?: Q.NA, "A", 0, modifier = modifier)
        "MOTOR_A" -> Metric(stringResource(R.string.tile_motor_a), s?.motorCurrentA ?: Q.NA, "A", 0, modifier = modifier)
        "FET" -> Metric(stringResource(R.string.tile_fet), s?.tempMosC ?: Q.NA, "°C", 0, modifier = modifier)
        "MOTOR_T" -> Metric(stringResource(R.string.tile_motor_t), s?.tempMotorC ?: Q.NA, "°C", 0, modifier = modifier)
        "TRIP" -> Metric(stringResource(R.string.tile_trip), Q.of(engine?.distanceM?.div(1000), cq), "km", 1, modifier = modifier)
        "ODO" -> Metric(stringResource(R.string.tile_odo), s?.odometerKm ?: Q.NA, "km", 0, modifier = modifier)
        "WHKM" -> Metric(stringResource(R.string.tile_whkm), Q.of(engine?.rollingWhPerKm(), cq), "", 0, modifier = modifier)
        "RANGE" -> Metric(stringResource(R.string.tile_range), Q.of(s?.let { engine?.rangeKm(it) }, eq), "km", 0, modifier = modifier)
        "DUTY" -> Metric(stringResource(R.string.tile_duty), s?.duty ?: Q.NA, "%", 0, 100.0, modifier = modifier)
        "THROTTLE" -> Metric(stringResource(R.string.tile_throttle), s?.throttle ?: Q.NA, "%", 0, 100.0, modifier = modifier)
        "LEAN" -> Metric(stringResource(R.string.tile_lean), s?.leanDeg ?: Q.NA, "°", 0, modifier = modifier)
        "ALT" -> Metric(stringResource(R.string.tile_alt), Q.of(s?.gps?.altM, Quality.MEASURED), "m", 0, modifier = modifier)
        "PS" -> Metric(stringResource(R.string.tile_ps), Q.of(s?.batteryPowerW?.value?.let { org.kineticos.telemetry.Calc.ps(it) }, s?.batteryPowerW?.quality ?: Quality.UNAVAILABLE), "PS", 0, modifier = modifier)
        "WH" -> Metric(stringResource(R.string.tile_wh), s?.wattHours ?: Q.NA, "Wh", 0, modifier = modifier)
        "REGEN" -> Metric(stringResource(R.string.tile_regen), s?.wattHoursCharged ?: Q.NA, "Wh", 0, modifier = modifier)
        "AH" -> Metric(stringResource(R.string.tile_ah), s?.ampHours ?: Q.NA, "Ah", 2, modifier = modifier)
        else -> Spacer(modifier)
    }
}

/** Large, colour-coded mode indicator (readable at a glance) incl. on-vehicle derating state. */
@Composable
fun ModeBadge(applied: org.kineticos.vehicle.RideProfile?, script: org.kineticos.vehicle.ScriptStatus?) {
    val p = LocalPalette.current
    val name = applied?.name ?: stringResource(R.string.mode_stock)
    val col = if (applied == null) p.textDim else K.Accent
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, col, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(name.uppercase(), style = labelStyle(16).copy(letterSpacing = 4.sp), color = col)
        }
        applied?.let { Text(stringResource(R.string.mode_regen_char, it.regenFraction * 100, it.character.uiLabel().uppercase()), style = labelStyle(10), color = p.textDim) }
        script?.let { sc ->
            val why = listOfNotNull(stringResource(R.string.derate_fet).takeIf { sc.derateMosfet }, stringResource(R.string.derate_motor).takeIf { sc.derateMotor }, stringResource(R.string.derate_voltage).takeIf { sc.derateVoltage })
            Text(if (why.isEmpty()) stringResource(R.string.mode_script, sc.effectiveScale * 100) else stringResource(R.string.mode_derated, sc.effectiveScale * 100, why.joinToString("/")),
                style = labelStyle(10), color = if (why.isEmpty()) p.textDim else K.Warn)
        }
    }
}
