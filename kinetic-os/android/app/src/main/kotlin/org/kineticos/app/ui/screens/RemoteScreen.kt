package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

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
    val savedText = stringResource(R.string.remote_saved)
    val noPositionText = stringResource(R.string.remote_no_position)
    val fenceAddedText = stringResource(R.string.remote_fence_added)
    LaunchedEffect(vid, settings.authToken) {
        if (vid.isBlank() || settings.authToken == null) return@LaunchedEffect
        while (true) {
            runCatching { remote = g.api.remoteState(vid); alerts = g.api.alerts(vid); fences = g.api.geofences(vid); err = null }.onFailure { err = it.message }
            delay(5000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.remote_section))
        Toggle(stringResource(R.string.remote_upload), settings.telematicsEnabled, { v -> scope.launch { g.settingsRepo.update { it.copy(telematicsEnabled = v) } } }, stringResource(R.string.remote_upload_sub))
        Toggle(stringResource(R.string.remote_notif), settings.alertNotifications, { v -> scope.launch { g.settingsRepo.update { it.copy(alertNotifications = v) } } }, stringResource(R.string.remote_notif_sub))
        if (settings.authToken == null) { Text(stringResource(R.string.remote_login_first), color = K.Warn); return@Column }
        Row { vehicles.forEach { v -> BigButton(v.name, Modifier.padding(end = 4.dp), selected = v.id == vid) { vid = v.id } } }
        Field(stringResource(R.string.remote_vehicle_id), vid, { vid = it }, Modifier.fillMaxWidth())
        err?.let { Text(stringResource(R.string.remote_server_err, it), color = K.Danger) }
        remote?.let { r ->
            val st = r.state
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (r.online) Pill(stringResource(R.string.remote_live), K.Regen) else Pill(stringResource(R.string.remote_offline), K.Warn)
                if (st?.source == "simulated") Pill(stringResource(R.string.sim_short), K.Sim, Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.remote_last_update, r.receivedAt?.let { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: stringResource(R.string.remote_never)), color = p.text)
                st?.let { s ->
                    Text(stringResource(R.string.remote_position, s.lat?.let { "%.5f, %.5f".format(it, s.lon) } ?: stringResource(R.string.remote_unknown)), color = p.text)
                    Text(stringResource(R.string.remote_speed_line, s.speedKmh?.let { "%.0f km/h".format(it) } ?: "—", s.socPct?.let { "%.0f%%".format(it) } ?: "—", s.voltage?.let { "%.1f V".format(it) } ?: "—", if (s.charging == true) stringResource(R.string.remote_charging) else ""), color = p.text)
                    Text(stringResource(R.string.remote_temp_line, s.tempMosC?.let { "%.0f°C".format(it) } ?: "—", s.tempMotorC?.let { "%.0f°C".format(it) } ?: "—", stringResource(if (s.vescConnected) R.string.remote_vesc_connected else R.string.remote_vesc_not_connected), s.faultCode ?: 0), color = p.textDim)
                }
            }
        }
        SectionTitle(stringResource(R.string.remote_alert_settings))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.remote_low_batt), lowBatt, { lowBatt = it }, Modifier.weight(1f), true)
            BigButton(stringResource(R.string.remote_save)) { scope.launch { runCatching { g.api.telematicsSettings(vid, lowBatt.toIntOrNull() ?: 20, 0, true) }.onSuccess { g.toast(savedText) }.onFailure { g.toast(it.message ?: "") } } }
        }
        SectionTitle(stringResource(R.string.remote_geofences))
        fences.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.remote_fence_row, f.name, f.radiusM, f.alertOn), color = p.text, modifier = Modifier.weight(1f)); BigButton("✕", color = K.Danger) { scope.launch { runCatching { g.api.deleteGeofence(vid, f.id) } } } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.remote_radius), radius, { radius = it }, Modifier.weight(1f), true)
            BigButton(stringResource(R.string.remote_fence_here)) {
                val f = g.gps.fix.value ?: remote?.state?.let { s -> if (s.lat != null && s.lon != null) org.kineticos.telemetry.GpsFix(s.lat, s.lon, null, null, null, null, 0) else null }
                if (f == null) g.toast(noPositionText) else scope.launch { runCatching { g.api.addGeofence(vid, "Home", f.lat, f.lon, radius.toD() ?: 300.0, "exit") }.onSuccess { g.toast(fenceAddedText) }.onFailure { g.toast(it.message ?: "") } }
            }
        }
        SectionTitle(stringResource(R.string.remote_alerts))
        alerts.take(30).forEach { a -> Text("${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(a.ts))}  ${a.kind}: ${a.message}", color = if (a.kind.contains("geofence") || a.kind == "movement") K.Danger else p.text) }
        Text(stringResource(R.string.remote_web, settings.serverUrl.ifBlank { stringResource(R.string.remote_server_placeholder) }), color = p.textDim, modifier = Modifier.padding(top = 10.dp))
    }
}
