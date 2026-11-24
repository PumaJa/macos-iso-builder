package org.kineticos.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String?, val rssi: Int, val advertisesNus: Boolean)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    /** Scans for all BLE devices; those advertising the Nordic UART service are flagged (VESC Express does). */
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: run { close(IllegalStateException("Bluetooth unavailable or off")); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val nus = r.scanRecord?.serviceUuids?.contains(ParcelUuid(BleVescTransport.NUS_SERVICE)) == true
                trySend(FoundDevice(r.device.address, r.scanRecord?.deviceName ?: r.device.name, r.rssi, nus))
            }
            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("Scan failed: $errorCode")) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
