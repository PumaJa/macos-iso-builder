package org.kineticos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kineticos.app.graph
import org.kineticos.app.net.*
import org.kineticos.app.ui.*
import java.text.SimpleDateFormat
import java.util.*

/** Second-phone view: monitor a vehicle via the server (read-only, no motor control by design). */
@Composable
fun RemoteScreen() {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val vehicles by g.vehicles.collectAsStateWithLifecycle()
    val settings by g.settings.collectAsStateWithLifecycle()
    var vid by remember { mutableStateOf(vehicles.firstOrNull()?.id ?: "") }
    var remote by remember { mutableStateOf<RemoteState?>(null) }
    var alerts by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var fences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var radius by remember { mutableStateOf("300") }
    var lowBatt by remember { mutableStateOf("20") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vid, settings.authToken) {
        if (vid.isBlank() || settings.authToken == null) return@LaunchedEffect
        while (true) {
            runCatching { remote = g.api.remoteState(vid); alerts = g.api.alerts(vid); fences = g.api.geofences(vid); err = null }.onFailure { err = it.message }
            delay(5000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Telematics / remote monitoring")
        Toggle("Upload this cockpit's state to the server", settings.telematicsEnabled, { v -> scope.launch { g.settingsRepo.update { it.copy(telematicsEnabled = v) } } }, "Every 5 s while running (30 s in power-saver). Simulated data is flagged.")
        Toggle("Alert notifications on this phone", settings.alertNotifications, { v -> scope.launch { g.settingsRepo.update { it.copy(alertNotifications = v) } } }, "Live via server connection; in background checked every 15 min (Android minimum)")
        if (settings.authToken == null) { Text("Log in under Settings → Account first.", color = K.Warn); return@Column }
        Row { vehicles.forEach { v -> BigButton(v.name, Modifier.padding(end = 4.dp), selected = v.id == vid) { vid = v.id } } }
        Field("…or vehicle id", vid, { vid = it }, Modifier.fillMaxWidth())
        err?.let { Text("Server: $it", color = K.Danger) }
        remote?.let { r ->
            val st = r.state
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (r.online) Pill("LIVE", K.Regen) else Pill("OFFLINE – last known state", K.Warn)
                if (st?.source == "simulated") Pill("SIMULATION", K.Sim, Modifier.padding(top = 4.dp))
                Text("Last update: ${r.receivedAt?.let { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "never"}", color = p.text)
                st?.let { s ->
                    Text("Position: ${s.lat?.let { "%.5f, %.5f".format(it, s.lon) } ?: "unknown"}", color = p.text)
                    Text("Speed ${s.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"} · SoC ${s.socPct?.let { "%.0f%%".format(it) } ?: "—"} · ${s.voltage?.let { "%.1f V".format(it) } ?: "—"}${if (s.charging == true) " · charging" else ""}", color = p.text)
                    Text("FET ${s.tempMosC?.let { "%.0f°C".format(it) } ?: "—"} · Motor ${s.tempMotorC?.let { "%.0f°C".format(it) } ?: "—"} · VESC ${if (s.vescConnected) "connected" else "not connected"} · Fault ${s.faultCode ?: 0}", color = p.textDim)
                }
            }
        }
        SectionTitle("Alert settings")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Low battery %", lowBatt, { lowBatt = it }, Modifier.weight(1f), true)
            BigButton("SAVE") { scope.launch { runCatching { g.api.telematicsSettings(vid, lowBatt.toIntOrNull() ?: 20, 0, true) }.onSuccess { g.toast("Saved") }.onFailure { g.toast(it.message ?: "") } } }
        }
        SectionTitle("Geofences (theft / movement alerts)")
        fences.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${f.name}: %.0f m (%s)".format(f.radiusM, f.alertOn), color = p.text, modifier = Modifier.weight(1f)); BigButton("✕", color = K.Danger) { scope.launch { runCatching { g.api.deleteGeofence(vid, f.id) } } } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Radius m", radius, { radius = it }, Modifier.weight(1f), true)
            BigButton("FENCE AT CURRENT POSITION") {
                val f = g.gps.fix.value ?: remote?.state?.let { s -> if (s.lat != null && s.lon != null) org.kineticos.telemetry.GpsFix(s.lat, s.lon, null, null, null, null, 0) else null }
                if (f == null) g.toast("No position") else scope.launch { runCatching { g.api.addGeofence(vid, "Home", f.lat, f.lon, radius.toD() ?: 300.0, "exit") }.onSuccess { g.toast("Geofence added") }.onFailure { g.toast(it.message ?: "") } }
            }
        }
        SectionTitle("Alerts")
        alerts.take(30).forEach { a -> Text("${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(a.ts))}  ${a.kind}: ${a.message}", color = if (a.kind.contains("geofence") || a.kind == "movement") K.Danger else p.text) }
        Text("Web dashboard: ${settings.serverUrl.ifBlank { "<server>" }}/", color = p.textDim, modifier = Modifier.padding(top = 10.dp))
    }
}
