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
    val adc: Boolean = false,
    val odometer: Boolean = false,
    val script: Boolean = false,          // lispbm/kinetic.lisp answers on CUSTOM_APP_DATA
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
    /** Persists "last time this app wrote limits to controller <uuid>" (survives app restarts). */
    private val writeLog: WriteLog = WriteLog.InMemory(),
) {
    /** Optional phone accelerometer for G-force / airtime (set by AppGraph). */
    var motion: MotionSource? = null
    interface WriteLog {
        fun lastWrite(uuid: String): Long
        fun mark(uuid: String, t: Long)
        class InMemory : WriteLog { private val m = HashMap<String, Long>(); override fun lastWrite(uuid: String) = m[uuid] ?: 0L; override fun mark(uuid: String, t: Long) { m[uuid] = t } }
    }
    private var reachedOnline = false
    fun markWrite() { _caps.value.firmware?.uuidHex?.let { writeLog.mark(it, System.currentTimeMillis()) } }
    suspend fun refreshScript(): ScriptStatus? = client?.let { c -> queryScript(c)?.also { _script.value = it } }
    private val _status = MutableStateFlow<ConnStatus>(ConnStatus.Idle); val status: StateFlow<ConnStatus> = _status
    private val _caps = MutableStateFlow(Capabilities()); val capabilities: StateFlow<Capabilities> = _caps
    private val _sample = MutableStateFlow<TelemetrySample?>(null); val sample: StateFlow<TelemetrySample?> = _sample
    private val _lastValues = MutableStateFlow<VescValues?>(null); val lastValues: StateFlow<VescValues?> = _lastValues
    private val _backup = MutableStateFlow<McConfBackup?>(null); val backup: StateFlow<McConfBackup?> = _backup
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32); val events: SharedFlow<String> = _events
    private val _linkHealthy = MutableStateFlow(false); val linkHealthy: StateFlow<Boolean> = _linkHealthy
    private val _script = MutableStateFlow<ScriptStatus?>(null); val scriptStatus: StateFlow<ScriptStatus?> = _script
    private val _batteryCut = MutableStateFlow<BatteryCut?>(null); val batteryCut: StateFlow<BatteryCut?> = _batteryCut
    fun updateScript(s: ScriptStatus?) { _script.value = s }
    private suspend fun queryScript(c: VescClient): ScriptStatus? =
        runCatching { OnVehicleScript.parseStatus(c.customAppQuery(OnVehicleScript.wrap(OnVehicleScript.statusRequest()))) }.getOrNull()

    var client: VescClient? = null; private set
    var transport: VescTransport? = null; private set
    private var job: Job? = null
    var engine: TelemetryEngine? = null; private set
    var drivetrain = Drivetrain(7, 1.0, 0.5, configured = false)
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
                    clearLive()
                    client?.stop(); client = null
                    withContext(NonCancellable) { runCatching { t.disconnect() } }
                }
                if (!autoReconnect) { _status.value = ConnStatus.Idle; break }
                if (reachedOnline) { attempt = 1; reachedOnline = false } // a session that was online restarts the backoff
                val wait = (1000L shl (attempt.coerceAtMost(5) - 1).coerceAtLeast(0)).coerceAtMost(15_000)
                _status.value = ConnStatus.Reconnecting("link lost", attempt, wait)
                delay(wait)
            }
        }
    }

    /**
     * Decide which stored limits are authoritative. The backup must reflect the controller's *stored* configuration:
     * - VESC rebooted since our last write (uptime) → the freshly read limits ARE the stored config → (re)backup them.
     * - We wrote since boot → fresh values are our own RAM reductions → keep the backup from this boot.
     * - Uptime unknown (old FW) → take the element-wise minimum (fail-safe: never above what the controller now allows).
     */
    private suspend fun reconcileBackup(fw: FirmwareInfo, fresh: McConfTemp, uptimeMs: Long?): McConfBackup {
        val existing = findBackup(fw.uuidHex)
        if (existing == null) return backups(fw, fresh).also { _events.tryEmit("Stored limits backed up (${fw.uuidHex.take(8)})") }
        val now = System.currentTimeMillis()
        return when {
            uptimeMs != null && writeLog.lastWrite(fw.uuidHex) < now - uptimeMs ->
                if (fresh != existing.conf || existing.firmware != fw.versionString) backups(fw, fresh).also { _events.tryEmit("Controller limits changed since last backup – backup updated") } else existing
            uptimeMs != null -> existing
            else -> {
                val e = existing.conf
                val m = e.copy(currentMaxScale = minOf(e.currentMaxScale, fresh.currentMaxScale), maxErpm = minOf(e.maxErpm, fresh.maxErpm),
                    minErpm = maxOf(e.minErpm, fresh.minErpm), wattMax = minOf(e.wattMax, fresh.wattMax), wattMin = maxOf(e.wattMin, fresh.wattMin),
                    inCurrentMax = minOf(e.inCurrentMax, fresh.inCurrentMax), inCurrentMin = maxOf(e.inCurrentMin, fresh.inCurrentMin))
                if (m != e) backups(fw, m).also { _events.tryEmit("Limits capped to current controller values (power-cycle the VESC to restore stock)") } else existing
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _status.value = ConnStatus.Idle; clearLive() }

    /** Never show values of a previous link as if they were live. */
    private fun clearLive() { _linkHealthy.value = false; _script.value = null; _sample.value = null; _lastValues.value = null }

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
            adc = probe { c.decodedAdc() },
            odometer = probe { require(c.setupValues().odometerM != null) },
        )
        // the script may be busy right after boot → several tries before concluding "not installed"
        var sc: ScriptStatus? = null
        for (i in 0 until 3) { sc = queryScript(c); if (sc != null) break; delay(200) }
        _script.value = sc
        caps = caps.copy(script = sc != null)
        val uptimeMs = runCatching { c.setupValues().uptimeMs }.getOrNull()
        _batteryCut.value = runCatching { c.batteryCut() }.getOrNull()
        // Configuration backup BEFORE any write is ever possible.
        var mc: McConfTemp? = null
        caps = caps.copy(mcConfTemp = probe { mc = c.mcConfTemp() })
        _caps.value = caps
        mc?.let { conf -> _backup.value = reconcileBackup(fw, conf, uptimeMs) }
        reachedOnline = true
        motion?.detector?.reset()
        _status.value = ConnStatus.Online("${fw.hardwareName} · FW ${fw.versionString}" + if (ids.isNotEmpty()) " · CAN ${ids.joinToString()}" else "", t.kind == TransportKind.SIMULATION)
        val eng = TelemetryEngine(drivetrain, simulated = t.kind == TransportKind.SIMULATION).also { engine = it }
        val poller = AdaptivePoller(minMs = 1000L / 50, start = 1000L / pollHz.coerceIn(1, 50))
        var misses = 0; var tick = 0; var scriptMisses = 0
        var imu: ImuData? = null; var bms: BmsValues? = null; var adc: DecodedAdc? = null; var odo: Long? = null
        while (isActive && t.state.value is LinkState.Connected) {
            val t0 = System.currentTimeMillis()
            try {
                val v = c.values()
                if (caps.imu && tick % 2 == 0) imu = runCatching { c.imu() }.getOrNull()
                if (caps.bms && tick % 10 == 0) bms = runCatching { c.bms() }.getOrNull()
                if (caps.adc && tick % 2 == 1) adc = runCatching { c.decodedAdc() }.getOrNull()
                if (tick % 20 == 5 && (caps.script || tick % 100 == 5)) {
                    val q = queryScript(c)
                    if (q != null) { _script.value = q; scriptMisses = 0; if (!caps.script) { caps = caps.copy(script = true); _caps.value = caps } }
                    else if (caps.script && ++scriptMisses >= 3) _script.value = null // AppGraph then refuses writes instead of bypassing the script
                }
                if (caps.odometer && tick % 50 == 0) odo = runCatching { c.setupValues().odometerM }.getOrNull() ?: odo
                val rtt = System.currentTimeMillis() - t0
                poller.onSuccess(rtt); misses = 0; _linkHealthy.value = true
                _lastValues.value = v
                val base = eng.process(System.currentTimeMillis(), v, gps.fix.value, imu, bms, 1 + caps.canIds.size, adc, odo)
                val m = motion
                _sample.value = if (m == null) base else base.copy(
                    gForce = Q.of(m.takePeakG(), if (base.simulated) Quality.SIMULATED else Quality.MEASURED),
                    airtimeMs = m.detector.totalAirtimeMs,
                )
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
