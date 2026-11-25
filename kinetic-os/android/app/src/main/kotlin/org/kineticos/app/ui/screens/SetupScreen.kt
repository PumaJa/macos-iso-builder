package org.kineticos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.kineticos.app.graph
import org.kineticos.app.ui.*
import org.kineticos.vehicle.*

/** Read-only VESC readiness check with fixes (nothing is written to the controller from here). */
@Composable
fun SetupScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val caps by g.connection.capabilities.collectAsStateWithLifecycle()
    val backup by g.connection.backup.collectAsStateWithLifecycle()
    val cut by g.connection.batteryCut.collectAsStateWithLifecycle()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val items = remember(caps, backup, cut, vehicle) {
        VescSetupCheck.run(SetupInputs(caps.firmware, backup?.conf, cut, vehicle, caps.canIds, caps.imu, caps.bms, caps.adc, caps.script, caps.odometer))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("VESC setup check")
        if (g.connection.isSimulated) Pill("SIMULATION – results describe the virtual VESC", K.Sim, Modifier.padding(bottom = 4.dp))
        Text("Reads the controller only – nothing is changed. Fixes are applied in VESC Tool or in the garage.", color = p.textDim, style = labelStyle(10))
        val bad = items.count { it.state == CheckState.FAIL }; val warn = items.count { it.state == CheckState.WARN }
        Pill(if (bad > 0) "$bad PROBLEM(S)" else if (warn > 0) "$warn WARNING(S)" else "READY", if (bad > 0) K.Danger else if (warn > 0) K.Warn else K.Regen, Modifier.padding(vertical = 6.dp))
        items.forEach { it ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                val col = when (it.state) { CheckState.OK -> K.Regen; CheckState.WARN -> K.Warn; CheckState.FAIL -> K.Danger; CheckState.INFO -> p.textDim }
                Text((when (it.state) { CheckState.OK -> "✓ "; CheckState.WARN -> "! "; CheckState.FAIL -> "✗ "; CheckState.INFO -> "i " }) + it.title, style = labelStyle(13), color = col)
                Text(it.detail, color = p.text)
                it.fix?.let { f -> Text("Fix: $f", color = p.textDim, style = labelStyle(10)) }
            }
        }
        val conf = backup?.conf
        if (conf != null) {
            val base = vehicle ?: VehicleProfile(java.util.UUID.randomUUID().toString(), caps.firmware?.hardwareName ?: "My vehicle")
            VescSetupCheck.importDrivetrain(base, conf)?.let { imported ->
                BigButton("IMPORT DRIVETRAIN FROM VESC → GARAGE", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    scope.launch {
                        val r = g.saveVehicle(imported.copy(controllerCount = maxOf(imported.controllerCount, 1 + caps.canIds.size), canIds = caps.canIds, lastVescUuid = caps.firmware?.uuidHex))
                        g.toast(if (r.none { it.blocking }) "Garage updated from VESC" else r.joinToString { it.message })
                    }
                }
            }
        }
    }
}
