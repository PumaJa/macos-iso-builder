package org.kineticos.app.transport

import android.content.Context
import android.hardware.usb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kineticos.vesc.transport.*

/**
 * USB-OTG transport for VESC hardware enumerating as CDC-ACM (STM32 VCP, VID 0x0483 PID 0x5740) – no extra driver library.
 * Also works with CDC-ACM USB-UART bridges. (CP210x/CH34x/FTDI bridges need vendor protocols and are not supported.)
 */
class UsbVescTransport(private val context: Context, val device: UsbDevice, private val baud: Int = 115200) : VescTransport {
    override val kind = TransportKind.USB
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: Flow<ByteArray> = rx
    override val maxChunk = 512
    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null; private var epOut: UsbEndpoint? = null
    private var ifaces = listOf<UsbInterface>()
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        fun isCandidate(d: UsbDevice) = (d.vendorId == 0x0483 && d.productId == 0x5740) ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val mgr = context.getSystemService(UsbManager::class.java)
        if (!mgr.hasPermission(device)) throw SecurityException("USB permission not granted")
        _state.value = LinkState.Connecting
        val c = mgr.openDevice(device) ?: throw IllegalStateException("Cannot open USB device")
        var control: UsbInterface? = null; var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) { UsbConstants.USB_CLASS_COMM -> control = itf; UsbConstants.USB_CLASS_CDC_DATA -> data = itf }
        }
        val d = data ?: throw IllegalStateException("No CDC data interface")
        for (e in 0 until d.endpointCount) {
            val ep = d.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep }
        }
        if (epIn == null || epOut == null) throw IllegalStateException("No bulk endpoints")
        ifaces = listOfNotNull(control, d).onEach { c.claimInterface(it, true) }
        val ctrlIndex = control?.id ?: 0
        // SET_LINE_CODING (baud, 1 stop, no parity, 8 data) and SET_CONTROL_LINE_STATE (DTR|RTS)
        val lc = byteArrayOf((baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(), (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(), 0, 0, 8)
        c.controlTransfer(0x21, 0x20, 0, ctrlIndex, lc, lc.size, 1000)
        c.controlTransfer(0x21, 0x22, 0x03, ctrlIndex, null, 0, 1000)
        conn = c
        readJob = scope.launch {
            val buf = ByteArray(epIn!!.maxPacketSize.coerceAtLeast(64) * 4)
            while (isActive) {
                val n = c.bulkTransfer(epIn, buf, buf.size, 200)
                if (n > 0) rx.emit(buf.copyOf(n))
                else if (n < 0 && !mgr.deviceList.containsValue(device)) { _state.value = LinkState.Failed("USB device removed"); break }
            }
        }
        _state.value = LinkState.Connected("USB ${device.productName ?: device.deviceName}")
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val c = conn ?: throw IllegalStateException("Not connected")
        var off = 0
        while (off < bytes.size) {
            val n = c.bulkTransfer(epOut, bytes, off, bytes.size - off, 1000)
            if (n <= 0) throw IllegalStateException("USB write failed")
            off += n
        }
    }

    override suspend fun disconnect() {
        readJob?.cancel(); conn?.let { c -> ifaces.forEach { runCatching { c.releaseInterface(it) } }; c.close() }; conn = null
        _state.value = LinkState.Disconnected
    }
}
