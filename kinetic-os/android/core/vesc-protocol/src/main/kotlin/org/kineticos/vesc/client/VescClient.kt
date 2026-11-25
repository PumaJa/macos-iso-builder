package org.kineticos.vesc.client

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kineticos.vesc.codec.*
import org.kineticos.vesc.model.*
import org.kineticos.vesc.transport.LinkState
import org.kineticos.vesc.transport.VescTransport

class VescTimeoutException(msg: String) : Exception(msg)

data class ClientStats(val sent: Long = 0, val received: Long = 0, val timeouts: Long = 0, val crcErrors: Long = 0, val retries: Long = 0)

/**
 * Request/response VESC client.
 * - One request in flight at a time (VESC replies carry only the command id; serialising keeps CAN-forwarded
 *   replies unambiguous and matches what VESC Tool does over BLE).
 * - Timeouts and bounded retries for idempotent reads. Writes are never retried automatically.
 * - Unsolicited packets (COMM_PRINT, CUSTOM_APP_DATA from LispBM, ...) are exposed via [unsolicited].
 */
class VescClient(
    private val transport: VescTransport,
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = 600,
    private val readRetries: Int = 2,
) {
    private val decoder = PacketDecoder()
    private val mutex = Mutex()
    private var pending: Pair<Int, CompletableDeferred<ByteArray>>? = null
    private val _unsolicited = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val unsolicited: SharedFlow<ByteArray> = _unsolicited
    private val _stats = MutableStateFlow(ClientStats())
    val stats: StateFlow<ClientStats> = _stats
    val linkState: StateFlow<LinkState> get() = transport.state
    private var readerJob: Job? = null
    @Volatile private var resetRequested = false

    fun start() {
        if (readerJob?.isActive == true) return
        readerJob = scope.launch {
            transport.incoming.collect { frag ->
                if (resetRequested) { resetRequested = false; decoder.reset() }
                val packets = decoder.feed(frag)
                _stats.update { it.copy(crcErrors = decoder.crcErrors) }
                for (p in packets) dispatch(p)
            }
        }
    }

    fun stop() { readerJob?.cancel(); readerJob = null; decoder.reset() }

    private fun dispatch(payload: ByteArray) {
        _stats.update { it.copy(received = it.received + 1) }
        val id = payload[0].toInt() and 0xFF
        val p = pending
        if (p != null && p.first == id && !p.second.isCompleted) {
            p.second.complete(payload.copyOfRange(1, payload.size))
        } else {
            _unsolicited.tryEmit(payload)
        }
    }

    private suspend fun sendFrame(payload: ByteArray) {
        val frame = PacketCodec.encode(payload)
        var off = 0
        while (off < frame.size) {
            val n = minOf(transport.maxChunk, frame.size - off)
            transport.write(frame.copyOfRange(off, off + n))
            off += n
        }
        _stats.update { it.copy(sent = it.sent + 1) }
    }

    /** Fire-and-forget (no reply expected by firmware). */
    suspend fun send(payload: ByteArray) = mutex.withLock { sendFrame(payload) }

    /**
     * Send [payload] and await the reply whose first byte equals [replyId] (defaults to the request id,
     * or the inner id for FORWARD_CAN).
     */
    suspend fun request(payload: ByteArray, replyId: Int = inferReplyId(payload), timeoutMs: Long = defaultTimeoutMs, retries: Int = readRetries): ByteArray {
        var attempt = 0
        while (true) {
            val r = mutex.withLock {
                val d = CompletableDeferred<ByteArray>()
                pending = replyId to d
                try {
                    sendFrame(payload)
                    withTimeoutOrNull(timeoutMs) { d.await() }
                } finally { pending = null }
            }
            if (r != null) return r
            _stats.update { it.copy(timeouts = it.timeouts + 1) }
            resetRequested = true // reader drops partial/garbage bytes before the next fragment
            if (attempt++ >= retries) throw VescTimeoutException("No reply to command ${CommPacketId.of(replyId) ?: replyId} after ${attempt} attempt(s)")
            _stats.update { it.copy(retries = it.retries + 1) }
        }
    }

    private fun inferReplyId(payload: ByteArray): Int {
        val id = payload[0].toInt() and 0xFF
        return if (id == CommPacketId.FORWARD_CAN.id && payload.size > 2) payload[2].toInt() and 0xFF else id
    }

    private fun fwd(canId: Int?, p: ByteArray) = if (canId == null) p else Commands.forwardCan(canId, p)

    suspend fun firmware(canId: Int? = null) = Parsers.fwVersion(request(fwd(canId, Commands.fwVersion()), timeoutMs = 1200))
    suspend fun values(canId: Int? = null) = Parsers.values(request(fwd(canId, Commands.getValues())))
    suspend fun setupValues(canId: Int? = null) = Parsers.setupValues(request(fwd(canId, Commands.getValuesSetup())))
    suspend fun imu(canId: Int? = null) = Parsers.imu(request(fwd(canId, Commands.getImu())))
    suspend fun bms() = Parsers.bms(request(Commands.getBms()))
    suspend fun mcConfTemp(canId: Int? = null) = Parsers.mcConfTemp(request(fwd(canId, Commands.getMcConfTemp())))
    suspend fun stats(canId: Int? = null) = Parsers.stats(request(fwd(canId, Commands.getStats())))
    suspend fun batteryCut() = Parsers.batteryCut(request(Commands.getBatteryCut()))
    suspend fun gnss() = Parsers.gnss(request(Commands.getGnss()))
    suspend fun decodedAdc(canId: Int? = null) = Parsers.decodedAdc(request(fwd(canId, Commands.getDecodedAdc()), retries = 0))
    suspend fun decodedPpm(canId: Int? = null) = Parsers.decodedPpm(request(fwd(canId, Commands.getDecodedPpm()), retries = 0))
    /** PING_CAN scans the whole bus; firmware takes up to a few seconds. */
    suspend fun pingCan() = Parsers.pingCan(request(Commands.pingCan(), timeoutMs = 5000, retries = 0))

    /** Raw write with acknowledgement. Never retried. Use only via the security layer (RideModeApplier). */
    internal suspend fun writeAcked(payload: ByteArray, timeoutMs: Long = 1500) = request(payload, timeoutMs = timeoutMs, retries = 0)
    suspend fun writeAckedChecked(payload: ByteArray, token: WriteToken): ByteArray { token.consume(); return writeAcked(payload) }
    /** Read-only query of the on-vehicle script (COMM_CUSTOM_APP_DATA); returns the raw reply data or throws on timeout. */
    suspend fun customAppQuery(payload: ByteArray, timeoutMs: Long = 800) = request(payload, replyId = CommPacketId.CUSTOM_APP_DATA.id, timeoutMs = timeoutMs, retries = 0)
}

/**
 * Single-use capability object proving a write was validated by the safety layer.
 * Only code in the vehicle/security module creates tokens (see SafetyGate). Prevents accidental writes from UI code.
 */
class WriteToken internal constructor(val reason: String) {
    private var used = false
    internal fun consume() { check(!used) { "WriteToken reused" }; used = true }
    companion object {
        /** Restricted factory: callers must pass a validated reason. Kept public for the vehicle module. */
        fun issue(validatedBy: String, reason: String): WriteToken {
            require(validatedBy.isNotBlank() && reason.isNotBlank())
            return WriteToken("$validatedBy: $reason")
        }
    }
}
