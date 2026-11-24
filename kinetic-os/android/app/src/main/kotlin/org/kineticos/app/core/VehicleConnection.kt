package org.kineticos.app.core

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kineticos.telemetry.*
import org.kineticos.vehicle.*
import org.kineticos.vesc.client.VescClient
import org.kineticos.vesc.client.VescTimeoutException
import org.kineticos.vesc.model.*
import org.kineticos.vesc.transport.*

/** Capabilities auto-detected after connecting (by probing; a missing reply = not supported). */
data class Capabilities(
    val firmware: FirmwareInfo? = null,
    val canIds: List<Int> = emptyList(),
    val canFirmware: Map<Int, FirmwareInfo> = emptyMap(),
    val imu: Boolean = false,
    val bms: Boolean = false,
    val mcConfTemp: Boolean = false,
    val stats: Boolean = false,
    val gnss: Boolean = false,
    val firmwareSupportedForWrites: Boolean = false,
)

sealed interface ConnStatus {
    data object Idle : ConnStatus
    data class Connecting(val attempt: Int, val target: String) : ConnStatus
    data class Online(val description: String, val simulated: Boolean) : ConnStatus
    data class Reconnecting(val reason: String, val attempt: Int, val inMs: Long) : ConnStatus
    data class Error(val message: String) : ConnStatus
}

/**
 * Owns one VESC link end-to-end: connect → identify → back up limits → poll telemetry → reconnect with backoff.
 * Completely UI-independent; UI observes [status], [sample], [capabilities].
 */
class VehicleConnection(
    private val scope: CoroutineScope,
    private val gps: GpsSource,
    private val backups: suspend (FirmwareInfo, McConfTemp) -> McConfBackup,
    private val findBackup: suspend (String) -> McConfBackup?,
) {
    private val _status = MutableStateFlow<ConnStatus>(ConnStatus.Idle); val status: StateFlow<ConnStatus> = _status
    private val _caps = MutableStateFlow(Capabilities()); val capabilities: StateFlow<Capabilities> = _caps
    private val _sample = MutableStateFlow<TelemetrySample?>(null); val sample: StateFlow<TelemetrySample?> = _sample
    private val _lastValues = MutableStateFlow<VescValues?>(null); val lastValues: StateFlow<VescValues?> = _lastValues
    private val _backup = MutableStateFlow<McConfBackup?>(null); val backup: StateFlow<McConfBackup?> = _backup
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32); val events: SharedFlow<String> = _events
    private val _linkHealthy = MutableStateFlow(false); val linkHealthy: StateFlow<Boolean> = _linkHealthy

    var client: VescClient? = null; private set
    var transport: VescTransport? = null; private set
    private var job: Job? = null
    var engine: TelemetryEngine? = null; private set
    var drivetrain = Drivetrain(7, 1.0, 0.5)
        set(value) { field = value; engine?.drivetrain = value }
    var pollHz = 10
    val isSimulated get() = transport?.kind == TransportKind.SIMULATION

    fun start(factory: () -> VescTransport, autoReconnect: Boolean = true) {
        stop()
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val t = factory(); transport = t
                attempt++
                _status.value = ConnStatus.Connecting(attempt, t.kind.name)
                try {
                    t.connect()
                    val c = VescClient(t, this); client = c; c.start()
                    session(c, t)
                    attempt = 0
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Log.w("VehicleConn", "link error", e)
                    _events.tryEmit("Connection: ${e.message}")
                } finally {
                    _linkHealthy.value = false
                    client?.stop(); client = null
                    withContext(NonCancellable) { runCatching { t.disconnect() } }
                }
                if (!autoReconnect) { _status.value = ConnStatus.Idle; break }
                val wait = (1000L shl (attempt.coerceAtMost(5) - 1).coerceAtLeast(0)).coerceAtMost(15_000)
                _status.value = ConnStatus.Reconnecting("link lost", attempt, wait)
                delay(wait)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _status.value = ConnStatus.Idle; _linkHealthy.value = false }

    private suspend fun session(c: VescClient, t: VescTransport): Unit = coroutineScope {
        val fw = c.firmware()
        _events.tryEmit("Connected: ${fw.hardwareName} FW ${fw.versionString}")
        val gate = SafetyGate()
        var caps = Capabilities(firmware = fw, firmwareSupportedForWrites = gate.firmwareSupported(fw))
        suspend fun probe(block: suspend () -> Unit) = try { block(); true } catch (e: VescTimeoutException) { false } catch (e: Exception) { false }
        // CAN discovery (multi-controller vehicles)
        val ids = runCatching { c.pingCan() }.getOrDefault(emptyList())
        val canFw = ids.associateWith { id -> runCatching { c.firmware(id) }.getOrNull() }.filterValues { it != null }.mapValues { it.value!! }
        caps = caps.copy(canIds = ids, canFirmware = canFw)
        caps = caps.copy(
            imu = probe { c.imu() }, bms = probe { c.bms() }, stats = probe { c.stats() },
            gnss = probe { val g = c.gnss(); require(g.lat != null) },
        )
        // Configuration backup BEFORE any write is ever possible.
        var mc: McConfTemp? = null
        caps = caps.copy(mcConfTemp = probe { mc = c.mcConfTemp() })
        _caps.value = caps
        mc?.let { conf ->
            val existing = findBackup(fw.uuidHex)
            _backup.value = existing ?: backups(fw, conf).also { _events.tryEmit("Stored limits backed up (${fw.uuidHex.take(8)})") }
        }
        _status.value = ConnStatus.Online("${fw.hardwareName} · FW ${fw.versionString}" + if (ids.isNotEmpty()) " · CAN ${ids.joinToString()}" else "", t.kind == TransportKind.SIMULATION)
        val eng = TelemetryEngine(drivetrain, simulated = t.kind == TransportKind.SIMULATION).also { engine = it }
        val poller = AdaptivePoller(minMs = 1000L / 50, start = 1000L / pollHz.coerceIn(1, 50))
        var misses = 0; var tick = 0
        var imu: ImuData? = null; var bms: BmsValues? = null
        while (isActive && t.state.value is LinkState.Connected) {
            val t0 = System.currentTimeMillis()
            try {
                val v = c.values()
                if (caps.imu && tick % 2 == 0) imu = runCatching { c.imu() }.getOrNull()
                if (caps.bms && tick % 10 == 0) bms = runCatching { c.bms() }.getOrNull()
                val rtt = System.currentTimeMillis() - t0
                poller.onSuccess(rtt); misses = 0; _linkHealthy.value = true
                _lastValues.value = v
                _sample.value = eng.process(System.currentTimeMillis(), v, gps.fix.value, imu, bms, 1 + caps.canIds.size)
            } catch (e: VescTimeoutException) {
                poller.onTimeout(); misses++; _linkHealthy.value = misses < 2
                if (misses >= 5) throw IllegalStateException("VESC not responding")
            }
            tick++
            val target = maxOf(poller.intervalMs, 1000L / pollHz.coerceIn(1, 50))
            delay((target - (System.currentTimeMillis() - t0)).coerceAtLeast(5))
        }
        throw IllegalStateException("Link closed")
    }
}
