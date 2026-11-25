package org.kineticos.app.ui.screens

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.graph
import org.kineticos.app.transport.BleScanner
import org.kineticos.app.transport.FoundDevice
import org.kineticos.app.ui.*

@Composable
fun ConnectScreen() {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val status by g.connection.status.collectAsStateWithLifecycle()
    val settings by g.settings.collectAsStateWithLifecycle()
    val found = remember { mutableStateMapOf<String, FoundDevice>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var host by remember { mutableStateOf("192.168.4.1:65102") }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Connection")
        Card(Modifier.fillMaxWidth()) {
            Text(when (val s = status) {
                is ConnStatus.Online -> "Online: ${s.description}"; is ConnStatus.Connecting -> "Connecting (${s.target}), attempt ${s.attempt}"
                is ConnStatus.Reconnecting -> "Lost link – retry ${s.attempt} in ${s.inMs / 1000}s"; is ConnStatus.Error -> s.message; ConnStatus.Idle -> "Not connected"
            }, color = p.text)
            Text("Last: ${settings.lastTransport ?: "—"}", color = p.textDim)
            Toggle("Auto-connect on start", settings.autoConnect, { v -> scope.launch { g.settingsRepo.update { it.copy(autoConnect = v) } } })
            BigButton("DISCONNECT", Modifier.fillMaxWidth(), color = K.Danger) { g.disconnect() }
            if (status is ConnStatus.Online) Text("Tip: More → VESC Setup checks the controller configuration.", color = p.textDim, style = labelStyle(10))
        }
        SectionTitle("Bluetooth LE")
        BigButton(if (scanJob == null) "SCAN" else "STOP SCAN", Modifier.fillMaxWidth(), selected = scanJob != null) {
            if (scanJob != null) { scanJob?.cancel(); scanJob = null }
            else { found.clear(); scanJob = scope.launch { runCatching { BleScanner(ctx).scan().collect { found[it.address] = it } }.onFailure { g.toast(it.message ?: "scan failed") }; scanJob = null } }
        }
        found.values.sortedWith(compareByDescending<FoundDevice> { it.advertisesNus }.thenByDescending { it.rssi }).forEach { d ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((d.name ?: "Unknown") + if (d.advertisesNus) "  · VESC/UART" else "", color = if (d.advertisesNus) K.Accent else p.text)
                    Text("${d.address} · ${d.rssi} dBm", color = p.textDim, style = labelStyle(10))
                }
                BigButton("CONNECT") { scanJob?.cancel(); scanJob = null; g.connectTo("ble:${d.address}") }
            }
        }
        SectionTitle("USB-OTG")
        BigButton("CONNECT USB VESC", Modifier.fillMaxWidth()) {
            val um = ctx.getSystemService(UsbManager::class.java); val dev = g.findUsb()
            when {
                dev == null -> g.toast("No CDC-ACM VESC attached (OTG cable?)")
                !um.hasPermission(dev) -> um.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, Intent("org.kineticos.USB_PERMISSION").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE))
                else -> g.connectTo("usb")
            }
        }
        Text("After granting USB permission tap again.", color = p.textDim, style = labelStyle(10))
        SectionTitle("WiFi / TCP (VESC Express, VESC Tool bridge)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("host:port", host, { host = it }, Modifier.weight(1f)); BigButton("CONNECT") { g.connectTo("tcp:$host") }
        }
        SectionTitle("Simulation")
        Text("Starts a virtual VESC for testing. All data is marked SIMULATION and is never sent as real telematics.", color = p.textDim)
        BigButton("START SIMULATOR", Modifier.fillMaxWidth(), color = K.Sim) { g.connectTo("sim") }
        BigButton("SIMULATOR + VEHICLE SCRIPT", Modifier.fillMaxWidth().padding(top = 6.dp), color = K.Sim) { g.connectTo("sim-script") }
    }
}
