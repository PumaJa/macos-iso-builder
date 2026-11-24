package org.kineticos.app.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.kineticos.vesc.transport.*
import java.util.UUID

/**
 * VESC BLE over Nordic UART Service (used by VESC Express, NRF51/52 VESC modules and most VESC-compatible BLE dongles).
 * RX characteristic (phone → VESC, write) 6e400002…, TX characteristic (VESC → phone, notify) 6e400003….
 */
@SuppressLint("MissingPermission")
class BleVescTransport(private val context: Context, val address: String) : VescTransport {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleVesc"
    }

    override val kind = TransportKind.BLE
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: Flow<ByteArray> = rx
    @Volatile override var maxChunk: Int = 20; private set

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()
    private var writeDone: CompletableDeferred<Boolean>? = null
    private var ready: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = if (status == BluetoothGatt.GATT_SUCCESS) LinkState.Disconnected else LinkState.Failed("GATT status $status")
                ready?.completeExceptionally(IllegalStateException("Disconnected ($status)"))
                writeDone?.complete(false)
                g.close(); if (gatt === g) gatt = null
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxChunk = (mtu - 3).coerceIn(20, 244)
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE)
            val rxC = svc?.getCharacteristic(NUS_RX); val txC = svc?.getCharacteristic(NUS_TX)
            if (rxC == null || txC == null) { ready?.completeExceptionally(IllegalStateException("Nordic UART service not found – not a VESC BLE module?")); g.disconnect(); return }
            rxChar = rxC
            g.setCharacteristicNotification(txC, true)
            val d = txC.getDescriptor(CCCD)
            if (d == null) { ready?.complete(Unit); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready?.complete(Unit) else ready?.completeExceptionally(IllegalStateException("CCCD write failed $status"))
        }
        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { rx.tryEmit(it.copyOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { rx.tryEmit(value.copyOf()) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    override suspend fun connect() {
        val mgr = context.getSystemService(BluetoothManager::class.java) ?: throw IllegalStateException("No Bluetooth")
        val adapter = mgr.adapter ?: throw IllegalStateException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        _state.value = LinkState.Connecting
        val dev = adapter.getRemoteDevice(address)
        ready = CompletableDeferred()
        gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(context, false, callback)
        try {
            withTimeout(15_000) { ready!!.await() }
            _state.value = LinkState.Connected("BLE ${dev.name ?: address} · MTU chunk $maxChunk")
        } catch (e: Exception) {
            _state.value = LinkState.Failed(e.message ?: "connect failed"); disconnect(); throw e
        }
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IllegalStateException("Not connected"); val c = rxChar ?: throw IllegalStateException("Not ready")
        var off = 0
        while (off < bytes.size) {
            val chunk = bytes.copyOfRange(off, minOf(bytes.size, off + maxChunk))
            val d = CompletableDeferred<Boolean>(); writeDone = d
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else @Suppress("DEPRECATION") {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = chunk; g.writeCharacteristic(c)
            }
            if (!ok || withTimeoutOrNull(2000) { d.await() } != true) throw IllegalStateException("BLE write failed")
            off += chunk.size
        }
    }

    override suspend fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }; gatt = null; rxChar = null
        _state.value = LinkState.Disconnected
    }
}
