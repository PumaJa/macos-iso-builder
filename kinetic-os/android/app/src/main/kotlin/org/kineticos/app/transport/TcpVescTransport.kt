package org.kineticos.app.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kineticos.vesc.transport.*
import java.net.InetSocketAddress
import java.net.Socket

/** TCP transport (VESC Express WiFi / VESC Tool TCP bridge, default port 65102). Same packet framing as UART. */
class TcpVescTransport(val host: String, val port: Int = 65102) : VescTransport {
    override val kind = TransportKind.TCP
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: Flow<ByteArray> = rx
    override val maxChunk = 1024
    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun connect() = withContext(Dispatchers.IO) {
        _state.value = LinkState.Connecting
        val s = Socket(); s.tcpNoDelay = true
        try { s.connect(InetSocketAddress(host, port), 5000) } catch (e: Exception) { _state.value = LinkState.Failed(e.message ?: "TCP"); throw e }
        socket = s
        scope.launch {
            val input = s.getInputStream(); val buf = ByteArray(2048)
            while (isActive) { val n = runCatching { input.read(buf) }.getOrDefault(-1); if (n < 0) break; rx.emit(buf.copyOf(n)) }
            _state.value = LinkState.Failed("TCP closed")
        }
        _state.value = LinkState.Connected("TCP $host:$port")
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { socket?.getOutputStream()?.write(bytes) ?: throw IllegalStateException("Not connected"); Unit }
    override suspend fun disconnect() { runCatching { socket?.close() }; socket = null; _state.value = LinkState.Disconnected }
}
