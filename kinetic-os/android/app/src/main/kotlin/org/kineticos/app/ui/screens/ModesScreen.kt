package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
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
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
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
            SectionTitle(stringResource(R.string.modes_title))
            // M1…M5 quick slots (first five modes) – one tap applies through the SafetyGate
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.take(5).forEachIndexed { i, pr ->
                    BigButton("M${i + 1}", Modifier.weight(1f), selected = applied?.id == pr.id, compact = true) { scope.launch { result = g.applyProfile(pr) } }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.modes_fw, caps.firmware?.versionString ?: "—", stringResource(if (caps.firmwareSupportedForWrites) R.string.modes_supported else R.string.modes_blocked)), color = p.text)
                Text(stringResource(R.string.modes_backup, backup?.let { stringResource(R.string.modes_taken, java.text.SimpleDateFormat("dd.MM.yy HH:mm").format(java.util.Date(it.takenAtMs))) } ?: stringResource(R.string.modes_backup_none)), color = p.textDim)
                Text(stringResource(R.string.modes_hint), color = p.textDim, style = labelStyle(10))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.modes_restore), Modifier.weight(1f), color = K.Warn) { scope.launch { result = g.restoreStock() } }
                    BigButton(stringResource(R.string.modes_new), Modifier.weight(1f)) { editing = RideProfile(UUID.randomUUID().toString(), ctx.getString(R.string.modes_custom, profiles.size - 3), powerFraction = 0.6, motorCurrentFraction = 0.7, batteryCurrentFraction = 0.6) }
                }
                result?.let { Text(it, color = if (it.startsWith("Applied") || it.startsWith("Stored")) K.Regen else K.Warn, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        items(profiles, key = { it.id }) { pr ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(pr.name, style = labelStyle(16), color = if (applied?.id == pr.id) K.Accent else p.text)
                        Text(stringResource(R.string.modes_row, pr.powerFraction * 100, pr.motorCurrentFraction * 100, pr.batteryCurrentFraction * 100, pr.regenFraction * 100,
                            pr.speedLimitKmh?.let { stringResource(R.string.modes_row_speed, it) } ?: ""), color = p.textDim, style = labelStyle(10))
                        Text(pr.character.uiLabel() + if (pr.character.approximate) stringResource(R.string.modes_approx) else "", color = p.textDim, style = labelStyle(10))
                    }
                    CurvePreview(pr, Modifier.size(90.dp, 54.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    BigButton(stringResource(R.string.modes_apply), Modifier.weight(1f), selected = applied?.id == pr.id) { scope.launch { result = g.applyProfile(pr) } }
                    BigButton(stringResource(if (pr.builtIn) R.string.modes_duplicate else R.string.modes_edit), Modifier.weight(1f)) { editing = if (pr.builtIn) pr.copy(id = UUID.randomUUID().toString(), name = pr.name + " +", builtIn = false) else pr }
                    if (!pr.builtIn) BigButton(stringResource(R.string.modes_delete), Modifier.weight(0.8f), color = K.Danger) { scope.launch { g.deleteProfile(pr.id) } }
                }
            }
        }
    }
}

@Composable
fun CurvePreview(pr: RideProfile, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        drawRect(p.surface2)
        val path = Path(); val path2 = Path()
        for (i in 0..40) {
            val x = i / 40.0
            val y = pr.throttleCurve.map(x) * pr.powerFraction
            val y2 = CharacterMaps.torqueMultiplier(pr.character, x)
            val px = (x * size.width).toFloat()
            if (i == 0) { path.moveTo(px, size.height - (y * size.height).toFloat()); path2.moveTo(px, size.height - (y2 * size.height).toFloat()) }
            else { path.lineTo(px, size.height - (y * size.height).toFloat()); path2.lineTo(px, size.height - (y2 * size.height).toFloat()) }
        }
        drawPath(path2, p.textDim.copy(alpha = 0.6f), style = Stroke(2f))
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
            SectionTitle(stringResource(R.string.edit_title))
            Field(stringResource(R.string.edit_name), pr.name, { pr = pr.copy(name = it.take(16)) }, Modifier.fillMaxWidth())
            val maxW = LocalContext.current.graph.connection.backup.collectAsStateWithLifecycle().value?.conf?.wattMax
            LabeledSlider(stringResource(R.string.edit_power), pr.powerFraction.toFloat(), { pr = pr.copy(powerFraction = it.toDouble()) },
                display = { f -> maxW?.let { "%.0f PS · %.1f kW (%.0f%%)".format(it * f / 735.49875, it * f / 1000, f * 100) } ?: "%.0f%%".format(f * 100) })
            LabeledSlider(stringResource(R.string.edit_motor), pr.motorCurrentFraction.toFloat(), { pr = pr.copy(motorCurrentFraction = it.toDouble()) })
            LabeledSlider(stringResource(R.string.edit_batt), pr.batteryCurrentFraction.toFloat(), { pr = pr.copy(batteryCurrentFraction = it.toDouble()) })
            LabeledSlider(stringResource(R.string.edit_regen), pr.regenFraction.toFloat(), { pr = pr.copy(regenFraction = it.toDouble()) })
            Field(stringResource(R.string.edit_speed), speed, { speed = it; pr = pr.copy(speedLimitKmh = it.toD()?.coerceIn(1.0, 400.0)) }, Modifier.fillMaxWidth(), number = true)
            SectionTitle(stringResource(R.string.edit_char))
            Text(stringResource(R.string.edit_char_hint), color = p.textDim, style = labelStyle(10))
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MotorCharacter.entries.take(3).forEach { c -> BigButton(c.uiLabel(), Modifier.weight(1f), selected = pr.character == c) { pr = pr.copy(character = c, throttleCurve = CharacterMaps.defaultCurve(c)) } }
            }
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MotorCharacter.entries.drop(3).forEach { c -> BigButton(c.uiLabel(), Modifier.weight(1f), selected = pr.character == c) { pr = pr.copy(character = c, throttleCurve = CharacterMaps.defaultCurve(c)) } }
            }
            LabeledSlider(stringResource(R.string.edit_expo), curveExpo, { curveExpo = it; pr = pr.copy(throttleCurve = ThrottleCurve.expo(it.toDouble()), character = if (pr.character.approximate) pr.character else MotorCharacter.CUSTOM) }, 0.4f..3f, { "%.2f".format(it) })
            Text(stringResource(R.string.edit_curve_points_hint), color = p.textDim, style = labelStyle(10))
            CurveEditor(pr.throttleCurve, Modifier.fillMaxWidth().height(180.dp)) { c -> pr = pr.copy(throttleCurve = c, character = if (pr.character.approximate) MotorCharacter.CUSTOM else pr.character) }
            SectionTitle(stringResource(R.string.edit_assist))
            Text(stringResource(R.string.edit_assist_hint), color = p.textDim, style = labelStyle(10))
            LabeledSlider(stringResource(R.string.edit_flywheel), 0f, {}, enabled = false); LabeledSlider(stringResource(R.string.edit_traction), 0f, {}, enabled = false); LabeledSlider(stringResource(R.string.edit_wheelie), 0f, {}, enabled = false)
            Toggle(stringResource(R.string.edit_temp_derate), pr.tempDerate, { pr = pr.copy(tempDerate = it) })
            Toggle(stringResource(R.string.edit_soc_derate), pr.socDerate, { pr = pr.copy(socDerate = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                BigButton(stringResource(R.string.btn_cancel), Modifier.weight(1f)) { onDone(null) }
                BigButton(stringResource(R.string.btn_save), Modifier.weight(1f), selected = true) { onDone(pr) }
            }
        }
    }
}

/** Drag the points of the throttle curve (6 points; closed throttle fixed at 0, curve stays monotonic). */
@Composable
fun CurveEditor(curve: ThrottleCurve, modifier: Modifier, onChange: (ThrottleCurve) -> Unit) {
    val p = LocalPalette.current
    val c6 = remember(curve) { if (curve.points.size == 6) curve else curve.resample(6) }
    var dragging by remember { mutableIntStateOf(-1) }
    Canvas(modifier.pointerInput(c6) {
        detectDragGestures(
            onDragStart = { o ->
                val i = ((o.x / size.width) * 5).roundToInt().coerceIn(1, 5)
                dragging = i
            },
            onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 },
        ) { change, _ ->
            val i = dragging; if (i < 1) return@detectDragGestures
            val v = 1.0 - (change.position.y / size.height).toDouble()
            onChange(c6.withPoint(i, v))
        }
    }) {
        for (k in 0..4) { val y = size.height * k / 4; drawLine(p.grid, Offset(0f, y), Offset(size.width, y), 1f) }
        val pts = c6.points.mapIndexed { i, v -> Offset(size.width * i / 5f, size.height * (1 - v.toFloat())) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, K.Accent, style = Stroke(4f))
        pts.forEachIndexed { i, o -> drawCircle(if (i == dragging) K.Warn else K.Accent, if (i == 0) 8f else 16f, o) }
    }
}
