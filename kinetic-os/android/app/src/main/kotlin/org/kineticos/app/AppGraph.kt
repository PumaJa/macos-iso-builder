package org.kineticos.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.kineticos.app.core.*
import org.kineticos.app.data.*
import org.kineticos.app.net.*
import org.kineticos.app.transport.*
import org.kineticos.racing.*
import org.kineticos.telemetry.Drivetrain
import org.kineticos.telemetry.GpsFix
import org.kineticos.vehicle.*
import org.kineticos.vesc.model.FirmwareInfo
import org.kineticos.vesc.model.McConfTemp
import org.kineticos.vesc.sim.SimulatedVescTransport
import org.kineticos.vesc.transport.VescTransport

/** Manual DI container + application-level orchestration. Lives as long as the process. */
class AppGraph(val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = KineticDb.create(ctx)
    val settingsRepo = SettingsRepo(ctx)
    val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val gps = GpsSource(ctx)
    val gate = SafetyGate()

    val connection = VehicleConnection(scope, gps,
        backups = { fw: FirmwareInfo, conf: McConfTemp ->
            val b = gate.backup(fw, conf)
            db.backups().insert(BackupEntity(vescUuid = b.vescUuid, takenAt = b.takenAtMs, json = AppJson.encodeToString(b)))
            b
        },
        findBackup = { uuid -> db.backups().first(uuid)?.let { AppJson.decodeFromString<McConfBackup>(it.json) } },
    )
    val recorder = RideRecorder(db, scope)
    val api = Api({ settings.value.serverUrl }, { settings.value.authToken })
    val socket = LiveSocket(api, { settings.value.serverUrl }, { settings.value.authToken }, scope)
    val warnings = org.kineticos.app.alerts.WarningCenter(ctx, scope, connection, settings)

    val vehicles: StateFlow<List<VehicleProfile>> = db.vehicles().all()
        .map { l -> l.mapNotNull { runCatching { AppJson.decodeFromString<VehicleProfile>(it.json) }.getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val activeVehicle: StateFlow<VehicleProfile?> = combine(vehicles, settings) { v, s -> v.firstOrNull { it.id == s.activeVehicleId } ?: v.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val profiles: StateFlow<List<RideProfile>> = db.profiles().all()
        .map { l -> RideProfile.BUILT_IN + l.mapNotNull { runCatching { AppJson.decodeFromString<RideProfile>(it.json) }.getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, RideProfile.BUILT_IN)
    private val _appliedProfile = MutableStateFlow<RideProfile?>(null); val appliedProfile: StateFlow<RideProfile?> = _appliedProfile
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 16); val toasts: SharedFlow<String> = _toast
    fun toast(s: String) { _toast.tryEmit(s) }

    // Racing
    val tracks: StateFlow<List<TrackDefinition>> = db.tracks().all().map { l -> l.mapNotNull { runCatching { AppJson.decodeFromString<TrackDefinition>(it.json) }.getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _lapTimer = MutableStateFlow<LapTimer?>(null); val lapTimer: StateFlow<LapTimer?> = _lapTimer
    private val _lapEvents = MutableSharedFlow<LapEvent>(extraBufferCapacity = 32); val lapEvents: SharedFlow<LapEvent> = _lapEvents
    private val _laps = MutableStateFlow<List<Lap>>(emptyList()); val laps: StateFlow<List<Lap>> = _laps

    private var started = false

    fun startRuntime() {
        if (started) return; started = true
        gps.start()
        scope.launch { connection.events.collect { _toast.emit(it) } }
        warnings.start()
        // keep drivetrain in sync with the active vehicle
        scope.launch { activeVehicle.collect { v -> v?.let { connection.drivetrain = Drivetrain(it.motorPolePairs, it.gearRatio, it.wheelDiameterM, it.batteryCellsSeries, it.batteryCapacityAh, it.batteryNominalV,
                chemistry = runCatching { org.kineticos.telemetry.Chemistry.valueOf(it.batteryChemistry) }.getOrDefault(org.kineticos.telemetry.Chemistry.NMC)) } } }
        scope.launch { settings.collect { connection.pollHz = if (it.powerSaver) minOf(it.pollHz, 4) else it.pollHz } }
        // auto record: start when online, stop when link is gone for good
        scope.launch {
            connection.status.collect { st ->
                if (st is ConnStatus.Online && settings.value.autoRecord) recorder.start(activeVehicle.value?.id, appliedProfile.value?.name, st.simulated, settings.value.pollHz)
                if (st is ConnStatus.Idle) recorder.stop()
            }
        }
        scope.launch { connection.sample.filterNotNull().collect { recorder.offer(it) } }
        // derating watchdog: if live derating drops below applied mode, push a reduction (always allowed)
        scope.launch { deratingLoop() }
        // telematics + group position broadcast
        scope.launch { telematicsLoop() }
        scope.launch { groupBroadcastLoop() }
        scope.launch { lapLoop() }
        socket.start()
        scope.launch { alertsLoop() }
        // auto-connect to last transport
        scope.launch {
            val s = settingsRepo.current()
            if (s.autoConnect && s.lastTransport != null) connectTo(s.lastTransport)
        }
    }

    // ---------- connection ----------
    fun connectTo(spec: String) {
        val factory: () -> VescTransport
        when {
            spec == "sim" -> factory = { SimulatedVescTransport() }
            spec == "sim-script" -> factory = { SimulatedVescTransport(scriptInstalled = true) }
            spec == "sim-script-parked" -> factory = { SimulatedVescTransport(scriptInstalled = true).apply { manualThrottle = 0.0 } }
            spec.startsWith("ble:") -> { val addr = spec.removePrefix("ble:"); factory = { BleVescTransport(ctx, addr) } }
            spec.startsWith("tcp:") -> {
                val parts = spec.removePrefix("tcp:").split(":"); val h = parts[0]; val port = parts.getOrNull(1)?.toIntOrNull() ?: 65102
                factory = { TcpVescTransport(h, port) }
            }
            spec == "usb" -> {
                val dev = findUsb() ?: run { toast("No USB VESC found"); return }
                factory = { UsbVescTransport(ctx, dev) }
            }
            else -> return
        }
        _appliedProfile.value = null
        connection.start(factory)
        scope.launch { settingsRepo.update { it.copy(lastTransport = spec) } }
    }
    fun disconnect() { connection.stop(); scope.launch { settingsRepo.update { it.copy(lastTransport = null) } } }
    fun findUsb(): UsbDevice? = ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { UsbVescTransport.isCandidate(it) }

    // ---------- garage ----------
    suspend fun saveVehicle(v: VehicleProfile): List<ValidationIssue> {
        val issues = VehicleValidator.validate(v)
        if (issues.none { it.blocking }) {
            db.vehicles().upsert(VehicleEntity(v.id, AppJson.encodeToString(v), System.currentTimeMillis()))
            if (settings.value.activeVehicleId == null) settingsRepo.update { it.copy(activeVehicleId = v.id) }
            syncVehicleToServer(v)
        }
        return issues
    }
    suspend fun deleteVehicle(id: String) { db.vehicles().delete(id) }
    suspend fun selectVehicle(id: String) = settingsRepo.update { it.copy(activeVehicleId = id) }

    private fun syncVehicleToServer(v: VehicleProfile) {
        if (settings.value.authToken == null) return
        scope.launch { runCatching {
            api.putVehicle(v.id, buildJsonObject { put("name", v.name); put("type", v.type.name); put("make", v.manufacturer); put("model", v.model) })
            v.photoUri?.let { p -> java.io.File(p).takeIf { it.exists() }?.let { api.uploadPhoto(v.id, it) } }
        } }
    }

    // ---------- ride profiles ----------
    suspend fun saveProfile(p: RideProfile): String? {
        if (p.builtIn) return "Built-in modes cannot be edited – duplicate it instead"
        if (db.profiles().count() >= RideProfile.MAX_PROFILES) return "Maximum of ${RideProfile.MAX_PROFILES} custom profiles reached"
        db.profiles().upsert(RideProfileEntity(p.id, activeVehicle.value?.id, AppJson.encodeToString(p), 100))
        return null
    }
    suspend fun deleteProfile(id: String) = db.profiles().delete(id)

    /** Apply a ride mode through the SafetyGate. Returns a human readable result. */
    suspend fun applyProfile(target: RideProfile): String {
        val c = connection.client ?: return "Not connected"
        val v = activeVehicle.value ?: return "Create a vehicle in the garage first"
        val s = connection.sample.value
        val live = LiveState(connection.lastValues.value?.erpm, s?.speedMps?.value?.times(3.6), s?.faultCode ?: 0, connection.linkHealthy.value,
            s?.tempMosC?.value, s?.tempMotorC?.value, s?.socPct?.value)
        connection.scriptStatus.value?.let { st -> return applyViaScript(c, target, v, live, st) }
        val (derate, _) = if (target.tempDerate || target.socDerate) Derating.factor(if (target.tempDerate) live.tempMosC else null, if (target.tempDerate) live.tempMotorC else null, if (target.socDerate) live.socPct else null) else (1.0 to emptyList())
        return when (val d = gate.planRideMode(connection.capabilities.value.firmware, connection.backup.value, appliedProfile.value, target, v, live, derate)) {
            is GateDecision.Denied -> "Not applied: " + d.reasons.joinToString("; ")
            is GateDecision.Allowed -> try {
                c.writeAckedChecked(d.payload, d.token)
                _appliedProfile.value = target
                settingsRepo.update { it.copy(activeProfileId = target.id) }
                "Applied " + d.summary + (if (d.warnings.isNotEmpty()) "\n" + d.warnings.joinToString("\n") else "")
            } catch (e: Exception) { "Write failed – controller keeps previous limits (${e.message})" }
        }
    }

    /** On-vehicle script present: send the mode to the script (it keeps its ceiling and does local derating). */
    private suspend fun applyViaScript(c: org.kineticos.vesc.client.VescClient, target: RideProfile, v: VehicleProfile, live: LiveState, st: ScriptStatus): String =
        when (val d = gate.planScriptMode(connection.capabilities.value.firmware, connection.backup.value, appliedProfile.value, target, v, live, st.mode)) {
            is ScriptDecision.Denied -> "Not applied: " + d.reasons.joinToString("; ")
            is ScriptDecision.Plan -> try {
                val p = d.plan
                val r1 = OnVehicleScript.parseStatus(c.writeAckedChecked(p.setParams, p.tokens.first))
                if (r1 == null || r1.result != 0) "Vehicle script refused parameters: ${r1?.resultText ?: "no reply"}"
                else {
                    val r2 = OnVehicleScript.parseStatus(c.writeAckedChecked(p.select, p.tokens.second))
                    r2?.let { connection.updateScript(it) }
                    if (r2 == null || r2.result != 0) "Vehicle script refused mode: ${r2?.resultText ?: "no reply"}"
                    else {
                        _appliedProfile.value = target
                        settingsRepo.update { it.copy(activeProfileId = target.id) }
                        "Applied " + p.summary + (if (p.warnings.isNotEmpty()) "\n" + p.warnings.joinToString("\n") else "")
                    }
                }
            } catch (e: Exception) { "Write failed – vehicle keeps previous mode (${e.message})" }
        }

    // ---------- anti-theft lock (lives on the VESC script; the phone only sends PIN-protected commands) ----------
    suspend fun lockAction(action: String, pin: String, newPin: String? = null): String {
        val c = connection.client ?: return "Not connected"
        val s = connection.sample.value
        val live = LiveState(connection.lastValues.value?.erpm, s?.speedMps?.value?.times(3.6), s?.faultCode ?: 0, connection.linkHealthy.value, null, null, null)
        return when (val d = gate.planLock(action, pin, newPin, connection.scriptStatus.value, live)) {
            is LockDecision.Denied -> d.reason
            is LockDecision.Allowed -> try {
                val st = OnVehicleScript.parseStatus(c.writeAckedChecked(d.payload, d.token)) ?: return "No reply from vehicle script"
                connection.updateScript(st)
                if (st.result != 0) "Refused by vehicle: ${st.resultText}"
                else when (action) { "lock" -> "Vehicle LOCKED"; "unlock" -> "Vehicle unlocked"; else -> "PIN saved on the vehicle" }
            } catch (e: Exception) { "No confirmation from vehicle (${e.message}) – check lock state before leaving" }
        }
    }

    suspend fun restoreStock(): String {
        val c = connection.client ?: return "Not connected"
        val b = connection.backup.value ?: return "No backup available"
        connection.scriptStatus.value?.let { st ->
            val v = activeVehicle.value ?: return "Create a vehicle first"
            // full fractions = the script's boot ceiling = stored limits; reductions are always allowed by the gate
            val r = applyViaScript(c, SafetyGate.STOCK, v, LiveState(null, 0.0, 0, connection.linkHealthy.value, null, null, null), st)
            if (r.startsWith("Applied")) _appliedProfile.value = null
            return r
        }
        return when (val d = gate.planRestore(b, LiveState(null, null, 0, connection.linkHealthy.value, null, null, null))) {
            is GateDecision.Denied -> d.reasons.joinToString()
            is GateDecision.Allowed -> runCatching { c.writeAckedChecked(d.payload, d.token); _appliedProfile.value = null; "Stored limits restored" }.getOrElse { "Restore failed: ${it.message}" }
        }
    }

    private suspend fun deratingLoop() {
        var lastFactor = 1.0
        while (true) {
            delay(2000)
            val p = appliedProfile.value ?: continue
            if (connection.scriptStatus.value != null) continue // the on-vehicle script derates locally
            val s = connection.sample.value ?: continue
            val (f, why) = Derating.factor(if (p.tempDerate) s.tempMosC.value else null, if (p.tempDerate) s.tempMotorC.value else null, if (p.socDerate) s.socPct.value else null)
            val stationary = (s.speedMps.value ?: 0.0) * 3.6 < 2.0
            if (f < lastFactor - 0.05 || (f > lastFactor + 0.1 && stationary)) {
                // Reductions are always allowed; recovery only at standstill (gate enforces).
                val r = applyProfile(p)
                if (!r.startsWith("Not applied")) { lastFactor = f; if (why.isNotEmpty()) toast("Power reduced: ${why.joinToString()}") }
            }
        }
    }

    // ---------- telematics ----------
    private suspend fun telematicsLoop() {
        val pending = ArrayList<VehicleState>()
        while (true) {
            val s = settings.value
            delay(if (s.powerSaver) 30_000 else 5_000)
            val v = activeVehicle.value ?: continue
            if (!s.telematicsEnabled || s.authToken == null) continue
            val online = connection.status.value is ConnStatus.Online
            val sample = connection.sample.value
            pending += TelematicsMapper.toState(if (online) sample else null, online && connection.linkHealthy.value,
                sample?.odometerKm?.value, connection.isSimulated, gps.fix.value, connection.scriptStatus.value?.lock)
            if (pending.size > 500) pending.subList(0, pending.size - 500).clear()
            runCatching { api.pushStates(v.id, pending.toList()); pending.clear() }   // offline → kept and retried
        }
    }

    private suspend fun groupBroadcastLoop() {
        while (true) {
            delay(1000)
            val groups = socket.joinedGroups(); if (groups.isEmpty()) continue
            val f = gps.fix.value ?: continue
            val s = connection.sample.value
            val share = settings.value.shareTelemetryInGroups
            groups.forEach { gid ->
                socket.send(buildJsonObject {
                    put("type", "position"); put("groupId", gid); put("lat", f.lat); put("lon", f.lon); f.altM?.let { put("alt", it) }
                    (s?.speedMps?.value ?: f.speedMps)?.let { put("speedKmh", it * 3.6) }; f.bearingDeg?.let { put("headingDeg", it) }; put("ts", System.currentTimeMillis())
                    activeVehicle.value?.let { put("vehicleName", it.name) }
                    if (share && s != null) put("telemetry", buildJsonObject { s.socPct.value?.let { put("socPct", it) }; s.batteryPowerW.value?.let { put("powerW", it) } })
                })
            }
        }
    }

    // ---------- telematics alerts as notifications ----------
    private suspend fun alertsLoop() {
        // keep WorkManager schedule in sync with the setting / login state
        scope.launch { settings.map { it.alertNotifications && it.authToken != null }.distinctUntilChanged().collect { org.kineticos.app.alerts.AlertNotifier.schedule(ctx, it) } }
        // subscribe all own vehicles for live alerts whenever the vehicle list changes
        scope.launch { combine(vehicles, settings.map { it.authToken }.distinctUntilChanged()) { v, t -> if (t != null) v else emptyList() }.collect { list -> list.forEach { socket.subscribeVehicle(it.id) } } }
        socket.messages.collect { o ->
            if (o["type"]?.jsonPrimitive?.contentOrNull != "alert" || !settings.value.alertNotifications) return@collect
            val vid = o["vehicleId"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val a = runCatching { AppJson.decodeFromJsonElement<Alert>(o["alert"]!!) }.getOrNull() ?: return@collect
            org.kineticos.app.alerts.AlertNotifier.show(ctx, vehicles.value.firstOrNull { it.id == vid }?.name ?: "Vehicle", vid, a)
        }
    }

    // ---------- racing ----------
    fun armLapTimer(track: TrackDefinition?) { _lapTimer.value = track?.let { LapTimer(it) }; _laps.value = emptyList() }
    suspend fun saveTrack(t: TrackDefinition) = db.tracks().upsert(TrackEntity(t.id, AppJson.encodeToString(t)))
    suspend fun deleteTrack(id: String) = db.tracks().delete(id)
    private suspend fun lapLoop() {
        gps.fix.filterNotNull().collect { f ->
            val lt = _lapTimer.value ?: return@collect
            for (e in lt.feed(f)) {
                _lapEvents.emit(e)
                if (e is LapEvent.LapCompleted) {
                    _laps.update { it + e.lap }
                    db.tracks().insertLap(LapEntity(trackId = lt.track.id, rideId = recorder.active.value?.id, lapMs = e.lap.lapMs,
                        sectorsJson = AppJson.encodeToString(e.lap.sectorMs), valid = e.lap.valid, ts = e.lap.endMs, gpsHz = gps.updateHz.value,
                        fixesJson = AppJson.encodeToString(e.lap.fixes)))
                    if (e.lap.valid && settings.value.authToken != null && !connection.isSimulated) activeVehicle.value?.let { v ->
                        runCatching { api.postLap(lt.track.id, e.lap.lapMs, e.lap.sectorMs, v.id, gps.updateHz.value) }
                    }
                }
            }
        }
    }

    fun injectGps(f: GpsFix) = gps.inject(f)
}
