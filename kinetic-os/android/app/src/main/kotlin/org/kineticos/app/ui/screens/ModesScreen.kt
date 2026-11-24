package org.kineticos.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.vehicle.*
import java.util.UUID

@Composable
fun ModesScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val profiles by g.profiles.collectAsStateWithLifecycle()
    val applied by g.appliedProfile.collectAsStateWithLifecycle()
    val caps by g.connection.capabilities.collectAsStateWithLifecycle()
    val backup by g.connection.backup.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RideProfile?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    editing?.let { e -> ProfileEditor(e, onDone = { saved -> editing = null; saved?.let { scope.launch { g.saveProfile(it)?.let { msg -> g.toast(msg) } } } }); return }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            SectionTitle("Ride modes")
            Card(Modifier.fillMaxWidth()) {
                Text("Firmware: ${caps.firmware?.versionString ?: "—"}  ·  writes ${if (caps.firmwareSupportedForWrites) "supported" else "blocked"}", color = p.text)
                Text("Limit backup: ${backup?.let { "taken ${java.text.SimpleDateFormat("dd.MM.yy HH:mm").format(java.util.Date(it.takenAtMs))}" } ?: "none (connect first)"}", color = p.textDim)
                Text("Modes only REDUCE the limits stored in the controller (VESC Tool config). Changes are RAM-only (never written to flash): power-cycling the VESC restores stock. Raising power is only accepted at standstill.", color = p.textDim, style = labelStyle(10))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("RESTORE STOCK", Modifier.weight(1f), color = K.Warn) { scope.launch { result = g.restoreStock() } }
                    BigButton("+ NEW MODE", Modifier.weight(1f)) { editing = RideProfile(UUID.randomUUID().toString(), "CUSTOM ${profiles.size - 3}", powerFraction = 0.6, motorCurrentFraction = 0.7, batteryCurrentFraction = 0.6) }
                }
                result?.let { Text(it, color = if (it.startsWith("Applied") || it.startsWith("Stored")) K.Regen else K.Warn, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        items(profiles, key = { it.id }) { pr ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(pr.name, style = labelStyle(16), color = if (applied?.id == pr.id) K.Accent else p.text)
                        Text("Power %.0f%% · Motor A %.0f%% · Batt A %.0f%% · Regen %.0f%%%s".format(pr.powerFraction * 100, pr.motorCurrentFraction * 100, pr.batteryCurrentFraction * 100, pr.regenFraction * 100,
                            pr.speedLimitKmh?.let { " · ≤ %.0f km/h".format(it) } ?: ""), color = p.textDim, style = labelStyle(10))
                        Text(pr.character.label + if (pr.character.approximate) " (approximation)" else "", color = p.textDim, style = labelStyle(10))
                    }
                    CurvePreview(pr, Modifier.size(90.dp, 54.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    BigButton("APPLY", Modifier.weight(1f), selected = applied?.id == pr.id) { scope.launch { result = g.applyProfile(pr) } }
                    BigButton(if (pr.builtIn) "DUPLICATE" else "EDIT", Modifier.weight(1f)) { editing = if (pr.builtIn) pr.copy(id = UUID.randomUUID().toString(), name = pr.name + " +", builtIn = false) else pr }
                    if (!pr.builtIn) BigButton("DELETE", Modifier.weight(0.8f), color = K.Danger) { scope.launch { g.deleteProfile(pr.id) } }
                }
            }
        }
    }
}

@Composable
fun CurvePreview(pr: RideProfile, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        drawRect(p.grid, style = Stroke(1f))
        val path = Path(); val path2 = Path()
        for (i in 0..40) {
            val x = i / 40.0
            val y = pr.throttleCurve.map(x) * pr.powerFraction
            val y2 = CharacterMaps.torqueMultiplier(pr.character, x)
            val px = (x * size.width).toFloat()
            if (i == 0) { path.moveTo(px, size.height - (y * size.height).toFloat()); path2.moveTo(px, size.height - (y2 * size.height).toFloat()) }
            else { path.lineTo(px, size.height - (y * size.height).toFloat()); path2.lineTo(px, size.height - (y2 * size.height).toFloat()) }
        }
        drawPath(path2, K.Warn.copy(alpha = 0.6f), style = Stroke(2f))
        drawPath(path, K.Accent, style = Stroke(3f))
    }
}

@Composable
private fun ProfileEditor(initial: RideProfile, onDone: (RideProfile?) -> Unit) {
    val p = LocalPalette.current
    var pr by remember { mutableStateOf(initial) }
    var curveExpo by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableStateOf(pr.speedLimitKmh?.toString() ?: "") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            SectionTitle("Edit mode")
            Field("Name", pr.name, { pr = pr.copy(name = it.take(16)) }, Modifier.fillMaxWidth())
            LabeledSlider("Power limit (of stored max)", pr.powerFraction.toFloat(), { pr = pr.copy(powerFraction = it.toDouble()) })
            LabeledSlider("Motor current / torque", pr.motorCurrentFraction.toFloat(), { pr = pr.copy(motorCurrentFraction = it.toDouble()) })
            LabeledSlider("Battery current", pr.batteryCurrentFraction.toFloat(), { pr = pr.copy(batteryCurrentFraction = it.toDouble()) })
            LabeledSlider("Regenerative braking", pr.regenFraction.toFloat(), { pr = pr.copy(regenFraction = it.toDouble()) })
            Field("Speed limit km/h (empty = stock)", speed, { speed = it; pr = pr.copy(speedLimitKmh = it.toD()?.coerceIn(1.0, 400.0)) }, Modifier.fillMaxWidth(), number = true)
            SectionTitle("Character (throttle response)")
            Text("Approximate characters are illustrative shapes, not validated engine models. Throttle-curve execution on the vehicle requires the LispBM script (see lispbm/README.md); without it only the limits above are applied.", color = p.textDim, style = labelStyle(10))
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MotorCharacter.entries.take(3).forEach { c -> BigButton(c.label, Modifier.weight(1f), selected = pr.character == c) { pr = pr.copy(character = c, throttleCurve = CharacterMaps.defaultCurve(c)) } }
            }
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MotorCharacter.entries.drop(3).forEach { c -> BigButton(c.label, Modifier.weight(1f), selected = pr.character == c) { pr = pr.copy(character = c, throttleCurve = CharacterMaps.defaultCurve(c)) } }
            }
            LabeledSlider("Throttle curve (expo)", curveExpo, { curveExpo = it; pr = pr.copy(throttleCurve = ThrottleCurve.expo(it.toDouble()), character = if (pr.character.approximate) pr.character else MotorCharacter.CUSTOM) }, 0.4f..3f, { "%.2f".format(it) })
            CurvePreview(pr, Modifier.fillMaxWidth().height(120.dp))
            SectionTitle("Assistance (locked)")
            Text("Virtual flywheel, traction control and wheelie control require validated sensors (wheel-speed / IMU) and on-vehicle control. They stay disabled for riding until a hardware validation is recorded.", color = p.textDim, style = labelStyle(10))
            LabeledSlider("Virtual flywheel", 0f, {}, enabled = false); LabeledSlider("Traction control", 0f, {}, enabled = false); LabeledSlider("Wheelie control", 0f, {}, enabled = false)
            Toggle("Temperature derating", pr.tempDerate, { pr = pr.copy(tempDerate = it) })
            Toggle("Low-battery derating", pr.socDerate, { pr = pr.copy(socDerate = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                BigButton("CANCEL", Modifier.weight(1f)) { onDone(null) }
                BigButton("SAVE", Modifier.weight(1f), selected = true) { onDone(pr) }
            }
        }
    }
}
