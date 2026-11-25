package org.kineticos.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import org.kineticos.app.BuildConfig
import org.kineticos.app.data.*
import org.kineticos.app.graph
import org.kineticos.app.ui.*

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val s by g.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { g.settingsRepo.update(f) }
    var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var user by remember { mutableStateOf(s.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Display")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeMode.entries.forEach { m -> BigButton(m.name, Modifier.weight(1f), selected = s.theme == m) { upd { it.copy(theme = m) } } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SpeedUnit.entries.forEach { u -> BigButton(u.name, Modifier.weight(1f), selected = s.speedUnit == u) { upd { it.copy(speedUnit = u) } } }
            PowerUnit.entries.forEach { u -> BigButton(u.name, Modifier.weight(1f), selected = s.powerUnit == u) { upd { it.copy(powerUnit = u) } } }
        }
        SectionTitle("Dash")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PrimaryValue.entries.forEach { v -> BigButton(v.name.replace('_', ' '), Modifier.weight(1f), selected = s.primaryValue == v, compact = true) { upd { it.copy(primaryValue = v) } } } }
        Text("Dash tiles (tap to add/remove, max 6, order = tap order): " + s.dashTiles.joinToString { DASH_TILE_CATALOG[it] ?: it }, color = p.textDim, style = labelStyle(10))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DASH_TILE_CATALOG.forEach { (k, label) -> BigButton(label, selected = k in s.dashTiles, compact = true) {
                upd { st -> st.copy(dashTiles = if (k in st.dashTiles) st.dashTiles - k else (st.dashTiles + k).takeLast(6)) } } }
        }
        Toggle("Spoken warnings", s.voiceAlerts, { v -> upd { it.copy(voiceAlerts = v) } }, "Battery steps, temperatures before cut-off, faults, connection loss")
        Toggle("Vibrate on warnings", s.vibrateAlerts, { v -> upd { it.copy(vibrateAlerts = v) } })
        Toggle("Mini-cockpit when leaving the app (picture-in-picture)", s.pipOnLeave, { v -> upd { it.copy(pipOnLeave = v) } }, "Keep a navigation app in front and still see speed, power, battery and mode")
        Toggle("Glove mode (large controls)", s.gloveMode, { v -> upd { it.copy(gloveMode = v) } })
        Toggle("Keep screen on", s.keepScreenOn, { v -> upd { it.copy(keepScreenOn = v) } })
        LabeledSlider("Dim when idle & stationary", s.dimAfterIdleS.toFloat(), { v -> upd { it.copy(dimAfterIdleS = v.toInt()) } }, 0f..600f, { if (it < 1) "off" else "%.0f s".format(it) })
        SectionTitle("Cockpit")
        Toggle("Start on boot", s.autoStartOnBoot, { v -> upd { it.copy(autoStartOnBoot = v) } }, "Starts the background service and auto-connects. Android may require the app to be the HOME app to show the UI automatically.")
        Toggle("Kiosk mode (screen pinning / lock task)", s.kiosk, { v -> upd { it.copy(kiosk = v) } }, "Full kiosk: adb shell dpm set-device-owner org.kineticos.app/.service.KioskAdminReceiver on a reset phone (INSTALL.md). Otherwise Android screen pinning is used.")
        Toggle("Auto-record rides", s.autoRecord, { v -> upd { it.copy(autoRecord = v) } })
        Toggle("Power saver", s.powerSaver, { v -> upd { it.copy(powerSaver = v) } }, "Telemetry ≤ 4 Hz, telematics every 30 s")
        LabeledSlider("Telemetry poll rate", s.pollHz.toFloat(), { v -> upd { it.copy(pollHz = v.toInt()) } }, 1f..50f, { "%.0f Hz".format(it) })
        Text("The app adapts the rate down automatically if the link cannot keep up (BLE typically 10–20 Hz).", color = p.textDim, style = labelStyle(10))
        BigButton("BATTERY OPTIMISATION EXCEPTION", Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) g.toast("Already excluded")
            else runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
        }
        Text("Samsung: also set Settings → Battery → Background usage limits → Never sleeping apps → Kinetic OS.", color = p.textDim, style = labelStyle(10))
        SectionTitle("Maps")
        Field("Day style URL", s.mapStyleDay, { v -> upd { it.copy(mapStyleDay = v) } }, Modifier.fillMaxWidth())
        Field("Night style URL", s.mapStyleNight, { v -> upd { it.copy(mapStyleNight = v) } }, Modifier.fillMaxWidth())
        Text("Default: OpenFreeMap (OpenStreetMap data © OpenStreetMap contributors, ODbL). Respect the tile provider's terms for offline downloads.", color = p.textDim, style = labelStyle(10))
        SectionTitle("Account (self-hosted server)")
        Field("Server URL (https://…)", server, { server = it }, Modifier.fillMaxWidth())
        Field("Username", user, { user = it }, Modifier.fillMaxWidth())
        Field("Password", pass, { pass = it }, Modifier.fillMaxWidth(), password = true)
        Field("Display name (register only)", display, { display = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("LOGIN", Modifier.weight(1f)) { scope.launch {
                g.settingsRepo.update { it.copy(serverUrl = server) }
                runCatching { g.api.login(user, pass) }.onSuccess { r -> g.settingsRepo.update { it.copy(authToken = r.token, username = r.user.username) }; msg = "Logged in as ${r.user.displayName}"; pass = "" }.onFailure { msg = "Login failed: ${it.message}" }
            } }
            BigButton("REGISTER", Modifier.weight(1f)) { scope.launch {
                g.settingsRepo.update { it.copy(serverUrl = server) }
                runCatching { g.api.register(user, pass, display.ifBlank { user }) }.onSuccess { r -> g.settingsRepo.update { it.copy(authToken = r.token, username = r.user.username) }; msg = "Registered"; pass = "" }.onFailure { msg = "Register failed: ${it.message}" }
            } }
            BigButton("LOGOUT", Modifier.weight(1f), color = K.Danger) { upd { it.copy(authToken = null) }; g.socket.stop(); msg = "Logged out" }
        }
        msg?.let { Text(it, color = K.Accent) }
        Text(if (s.authToken != null) "Signed in as ${s.username}" else "Not signed in", color = p.textDim)
        SectionTitle("About")
        Text("Kinetic OS ${BuildConfig.VERSION_NAME} – independent open cockpit for VESC vehicles. Not affiliated with, endorsed by or compatible with Stark Future. VESC is a trademark of Benjamin Vedder.", color = p.textDim, style = labelStyle(10))
    }
}
