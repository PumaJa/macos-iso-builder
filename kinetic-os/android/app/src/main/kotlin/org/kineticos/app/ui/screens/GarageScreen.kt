package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineticos.app.data.MaintenanceEntity
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.vehicle.*
import java.io.File
import java.util.UUID

@Composable
fun GarageScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val vehicles by g.vehicles.collectAsStateWithLifecycle()
    val active by g.activeVehicle.collectAsStateWithLifecycle()
    val caps by g.connection.capabilities.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val myVehicleText = stringResource(R.string.garage_my_vehicle)
    var edit by remember { mutableStateOf<VehicleProfile?>(null) }
    edit?.let { VehicleEditor(it) { edit = null }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.garage_title, vehicles.size))
        vehicles.forEach { v ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { scope.launch { g.selectVehicle(v.id) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (v.photoUri != null) AsyncImage(v.photoUri, v.name, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(v.name, style = labelStyle(16), color = if (active?.id == v.id) K.Accent else p.text)
                        Text("${v.manufacturer} ${v.model} · ${v.type.uiLabel()}", color = p.textDim)
                        Text(stringResource(R.string.garage_specs, v.motorPolePairs, v.gearRatio, v.wheelDiameterM * 1000, v.kmhPerKiloErpm), color = p.textDim, style = labelStyle(10))
                    }
                    BigButton(stringResource(R.string.garage_edit)) { edit = v }
                }
            }
        }
        BigButton(stringResource(R.string.garage_add), Modifier.fillMaxWidth().padding(top = 8.dp)) { edit = VehicleProfile(UUID.randomUUID().toString(), myVehicleText, controllerCount = 1 + caps.canIds.size, canIds = caps.canIds, lastVescUuid = caps.firmware?.uuidHex) }
        active?.let { v -> Maintenance(v) }
    }
}

@Composable
private fun Maintenance(v: VehicleProfile) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val items by g.db.nav().maintenance(v.id).collectAsStateWithLifecycle(emptyList())
    val rides by g.db.rides().all().collectAsStateWithLifecycle(emptyList())
    val liveOdo = g.connection.sample.collectAsStateWithLifecycle().value?.takeIf { !it.simulated }?.odometerKm?.value
    val recordedKm = remember(rides) { rides.filter { it.vehicleId == v.id && !it.simulated }.sumOf { r -> r.summaryJson?.let { runCatching { org.kineticos.app.core.AppJson.decodeFromString<org.kineticos.export.RideSummary>(it).distanceM }.getOrNull() } ?: 0.0 } / 1000 }
    val odo = liveOdo ?: recordedKm
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var interval by remember { mutableStateOf("") }
    SectionTitle(stringResource(R.string.maint_title, v.name, odo, stringResource(if (liveOdo != null) R.string.maint_src_vesc else R.string.maint_src_rides)))
    items.forEach { m ->
        val due = m.lastDoneKm + m.intervalKm - odo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${m.name}: " + if (due <= 0) stringResource(R.string.maint_due) else stringResource(R.string.maint_in, due), color = if (due <= 0) K.Danger else p.text, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.maint_done)) { scope.launch { g.db.nav().upsertMaintenance(m.copy(lastDoneKm = odo)) } }; Spacer(Modifier.width(4.dp))
            BigButton("✕", color = K.Danger) { scope.launch { g.db.nav().deleteMaintenance(m.pk) } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(stringResource(R.string.maint_item), name, { name = it }, Modifier.weight(1f)); Spacer(Modifier.width(4.dp))
        Field(stringResource(R.string.maint_every), interval, { interval = it }, Modifier.weight(0.6f), number = true)
        BigButton(stringResource(R.string.maint_add)) { interval.toD()?.let { iv -> if (name.isNotBlank()) scope.launch { g.db.nav().upsertMaintenance(MaintenanceEntity(vehicleId = v.id, name = name, intervalKm = iv, lastDoneKm = odo)); name = ""; interval = "" } } }
    }
}

@Composable
private fun VehicleEditor(initial: VehicleProfile, done: () -> Unit) {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf(initial) }
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    val f = remember { mutableStateMapOf<String, String>() }
    fun num(key: String, cur: Number?) = f[key] ?: cur?.toString() ?: ""
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                val scale = 1280f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
                val sb = if (scale < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                File(ctx.filesDir, "photos").apply { mkdirs() }.resolve("${v.id}-${System.currentTimeMillis()}.jpg").also { file -> file.outputStream().use { sb.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            }
            v = v.copy(photoUri = out.absolutePath)
        }
    }
    fun err(field: String) = issues.firstOrNull { it.field == field }?.message
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.veh_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (v.photoUri != null) AsyncImage(v.photoUri, null, Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(8.dp)); BigButton(stringResource(R.string.veh_photo)) { photoPicker.launch("image/*") }
        }
        Field(stringResource(R.string.edit_name), v.name, { v = v.copy(name = it) }, Modifier.fillMaxWidth(), error = err("name"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { VehicleType.entries.take(4).forEach { t -> BigButton(t.uiLabel(), Modifier.weight(1f), selected = v.type == t, compact = true) { v = v.copy(type = t) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) { VehicleType.entries.drop(4).forEach { t -> BigButton(t.uiLabel(), Modifier.weight(1f), selected = v.type == t, compact = true) { v = v.copy(type = t) } } }
        Row { Field(stringResource(R.string.veh_manufacturer), v.manufacturer, { v = v.copy(manufacturer = it) }, Modifier.weight(1f)); Spacer(Modifier.width(6.dp)); Field(stringResource(R.string.veh_model), v.model, { v = v.copy(model = it) }, Modifier.weight(1f)) }
        SectionTitle(stringResource(R.string.veh_drivetrain))
        Field(stringResource(R.string.veh_motor_name), v.motorName, { v = v.copy(motorName = it) }, Modifier.fillMaxWidth())
        Row {
            Field(stringResource(R.string.veh_kv), num("kv", v.motorKv), { f["kv"] = it; v = v.copy(motorKv = it.toD()) }, Modifier.weight(1f), true, err("motorKv")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_pole_pairs), num("pp", v.motorPolePairs), { f["pp"] = it; it.toIntOrNull()?.let { n -> v = v.copy(motorPolePairs = n) } }, Modifier.weight(1f), true, err("motorPolePairs"))
        }
        Row {
            Field(stringResource(R.string.veh_max_rpm), num("rpm", v.maxMotorRpm), { f["rpm"] = it; v = v.copy(maxMotorRpm = it.toD()) }, Modifier.weight(1f), true); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_gear), num("gear", v.gearRatio), { f["gear"] = it; it.toD()?.let { d -> v = v.copy(gearRatio = d) } }, Modifier.weight(1f), true, err("gearRatio"))
        }
        Row {
            Field(stringResource(R.string.veh_wheel), f["wd"] ?: "%.0f".format(v.wheelDiameterM * 1000), { f["wd"] = it; it.toD()?.let { d -> v = v.copy(wheelDiameterM = d / 1000) } }, Modifier.weight(1f), true, err("wheelDiameterM")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_weight), num("kg", v.weightKg), { f["kg"] = it; v = v.copy(weightKg = it.toD()) }, Modifier.weight(1f), true)
        }
        SectionTitle(stringResource(R.string.veh_battery))
        Row {
            Field(stringResource(R.string.veh_capacity), num("ah", v.batteryCapacityAh), { f["ah"] = it; v = v.copy(batteryCapacityAh = it.toD()) }, Modifier.weight(1f), true, err("batteryCapacityAh")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_nominal), num("v", v.batteryNominalV), { f["v"] = it; v = v.copy(batteryNominalV = it.toD()) }, Modifier.weight(1f), true, err("batteryNominalV")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_cells), num("s", v.batteryCellsSeries), { f["s"] = it; v = v.copy(batteryCellsSeries = it.toIntOrNull()) }, Modifier.weight(1f), true, err("batteryCellsSeries"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            org.kineticos.telemetry.Chemistry.entries.forEach { c -> BigButton(c.label, Modifier.weight(1f), selected = v.batteryChemistry == c.name, compact = true) { v = v.copy(batteryChemistry = c.name) } }
        }
        Text(stringResource(R.string.veh_chem_hint), color = p.textDim, style = labelStyle(10))
        Row {
            Field(stringResource(R.string.veh_max_power), num("pw", v.maxPowerW), { f["pw"] = it; v = v.copy(maxPowerW = it.toD()) }, Modifier.weight(1f), true, err("maxPowerW")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_max_motor_a), num("ma", v.maxMotorCurrentA), { f["ma"] = it; v = v.copy(maxMotorCurrentA = it.toD()) }, Modifier.weight(1f), true, err("maxMotorCurrentA")); Spacer(Modifier.width(6.dp))
            Field(stringResource(R.string.veh_max_batt_a), num("ba", v.maxBatteryCurrentA), { f["ba"] = it; v = v.copy(maxBatteryCurrentA = it.toD()) }, Modifier.weight(1f), true, err("maxBatteryCurrentA"))
        }
        Field(stringResource(R.string.veh_controllers), num("cc", v.controllerCount), { f["cc"] = it; it.toIntOrNull()?.let { n -> v = v.copy(controllerCount = n) } }, Modifier.fillMaxWidth(), true, err("controllerCount"))
        Text(stringResource(R.string.veh_speed_check, v.kmhPerKiloErpm), color = p.textDim)
        issues.filter { !it.blocking }.forEach { Text("⚠ ${it.message}", color = K.Warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            BigButton(stringResource(R.string.btn_cancel), Modifier.weight(1f)) { done() }
            BigButton(stringResource(R.string.btn_delete), Modifier.weight(1f), color = K.Danger) { scope.launch { g.deleteVehicle(v.id); done() } }
            BigButton(stringResource(R.string.btn_save), Modifier.weight(1f), selected = true) { scope.launch { issues = g.saveVehicle(v); if (issues.none { it.blocking }) done() } }
        }
    }
}
