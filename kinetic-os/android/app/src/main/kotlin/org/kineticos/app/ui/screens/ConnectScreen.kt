package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

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
    val scanFailedText = stringResource(R.string.conn_scan_failed)
    val usbNoneText = stringResource(R.string.conn_usb_none)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.conn_section))
        Card(Modifier.fillMaxWidth()) {
            Text(when (val s = status) {
                is ConnStatus.Online -> stringResource(R.string.conn_online, s.description); is ConnStatus.Connecting -> stringResource(R.string.conn_connecting, s.target, s.attempt)
                is ConnStatus.Reconnecting -> stringResource(R.string.conn_reconnecting, s.attempt, s.inMs / 1000); is ConnStatus.Error -> s.message; ConnStatus.Idle -> stringResource(R.string.conn_idle)
            }, color = p.text)
            Text(stringResource(R.string.conn_last, settings.lastTransport ?: "—"), color = p.textDim)
            Toggle(stringResource(R.string.conn_autoconnect), settings.autoConnect, { v -> scope.launch { g.settingsRepo.update { it.copy(autoConnect = v) } } })
            BigButton(stringResource(R.string.conn_disconnect), Modifier.fillMaxWidth(), color = K.Danger) { g.disconnect() }
            if (status is ConnStatus.Online) Text(stringResource(R.string.conn_tip), color = p.textDim, style = labelStyle(10))
        }
        SectionTitle("Bluetooth LE")
        BigButton(stringResource(if (scanJob == null) R.string.conn_scan else R.string.conn_stop_scan), Modifier.fillMaxWidth(), selected = scanJob != null) {
            if (scanJob != null) { scanJob?.cancel(); scanJob = null }
            else { found.clear(); scanJob = scope.launch { runCatching { BleScanner(ctx).scan().collect { found[it.address] = it } }.onFailure { g.toast(it.message ?: scanFailedText) }; scanJob = null } }
        }
        found.values.sortedWith(compareByDescending<FoundDevice> { it.advertisesNus }.thenByDescending { it.rssi }).forEach { d ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((d.name ?: stringResource(R.string.conn_unknown)) + if (d.advertisesNus) "  · VESC/UART" else "", color = if (d.advertisesNus) K.Accent else p.text)
                    Text("${d.address} · ${d.rssi} dBm", color = p.textDim, style = labelStyle(10))
                }
                BigButton(stringResource(R.string.conn_connect)) { scanJob?.cancel(); scanJob = null; g.connectTo("ble:${d.address}") }
            }
        }
        SectionTitle("USB-OTG")
        BigButton(stringResource(R.string.conn_usb_connect), Modifier.fillMaxWidth()) {
            val um = ctx.getSystemService(UsbManager::class.java); val dev = g.findUsb()
            when {
                dev == null -> g.toast(usbNoneText)
                !um.hasPermission(dev) -> um.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, Intent("org.kineticos.USB_PERMISSION").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE))
                else -> g.connectTo("usb")
            }
        }
        Text(stringResource(R.string.conn_usb_again), color = p.textDim, style = labelStyle(10))
        SectionTitle(stringResource(R.string.conn_wifi_section))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("host:port", host, { host = it }, Modifier.weight(1f)); BigButton(stringResource(R.string.conn_connect)) { g.connectTo("tcp:$host") }
        }
        SectionTitle(stringResource(R.string.conn_sim_section))
        Text(stringResource(R.string.conn_sim_text), color = p.textDim)
        BigButton(stringResource(R.string.conn_sim_start), Modifier.fillMaxWidth(), color = K.Sim) { g.connectTo("sim") }
        BigButton(stringResource(R.string.conn_sim_script), Modifier.fillMaxWidth().padding(top = 6.dp), color = K.Sim) { g.connectTo("sim-script") }
    }
}
