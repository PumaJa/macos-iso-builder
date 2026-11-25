package org.kineticos.vehicle

import kotlinx.serialization.Serializable
import org.kineticos.vesc.client.WriteToken
import org.kineticos.vesc.codec.Commands
import org.kineticos.vesc.model.FirmwareInfo
import org.kineticos.vesc.model.McConfTemp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

@Serializable
data class McConfBackup(val vescUuid: String, val takenAtMs: Long, val firmware: String, val conf: McConfTemp)

/** Snapshot of live state used to decide whether a write is allowed right now. */
data class LiveState(
    val erpm: Double?, val speedKmh: Double?, val faultCode: Int, val linkHealthy: Boolean,
    val tempMosC: Double?, val tempMotorC: Double?, val socPct: Double?,
)

sealed interface GateDecision {
    /** [effective] = what the controller will actually run (may differ from the request, e.g. regen held while moving). */
    data class Allowed(val payload: ByteArray, val token: WriteToken, val summary: String, val warnings: List<String>, val effective: RideProfile? = null) : GateDecision
    data class Denied(val reasons: List<String>) : GateDecision
}

/**
 * All configuration writes go through here. Rules:
 * 1. Firmware must be on the allow-list of versions whose SET_MCCONF_TEMP(_SETUP) layout was verified (>= 5.03).
 * 2. A backup of the stored runtime limits (GET_MCCONF_TEMP) must exist for this exact controller UUID.
 * 3. No new limit may exceed the backup (only reductions relative to what VESC Tool configured).
 * 4. Torque-raising changes only while (practically) stationary; reductions are always allowed.
 * 5. No writes while a fault is active or the link is unhealthy.
 * 6. store=false always: changes live in RAM, a VESC power cycle restores the stored configuration.
 * 7. Features needing unvalidated hardware (flywheel, TC, wheelie) are refused for riding.
 */
class SafetyGate(
    private val stationaryKmh: Double = 2.0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun firmwareSupported(fw: FirmwareInfo): Boolean = fw.atLeast(5, 3) && fw.major <= 7

    fun planRideMode(
        fw: FirmwareInfo?, backup: McConfBackup?, current: RideProfile?, requested: RideProfile, vehicle: VehicleProfile,
        live: LiveState, deratingFactor: Double = 1.0,
    ): GateDecision {
        val cur = current ?: STOCK
        val speedNow = live.speedKmh ?: live.erpm?.let { abs(it) * vehicle.kmhPerKiloErpm / 1000 }
        val moving = speedNow == null || speedNow > stationaryKmh
        // While moving, a mode change may reduce drive power, but never increase regen braking or reverse speed:
        // those parts are held at the current values and apply at the next standstill.
        val target = if (moving) requested.copy(
            regenFraction = minOf(requested.regenFraction, cur.regenFraction),
            reverseSpeedLimitKmh = listOfNotNull(requested.reverseSpeedLimitKmh, cur.reverseSpeedLimitKmh).minOrNull(),
        ) else requested
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (fw == null) reasons += "Firmware not identified"
        else if (!firmwareSupported(fw)) reasons += "Firmware ${fw.versionString} not on verified list (5.03–7.x)"
        if (backup == null) reasons += "No configuration backup for this controller"
        else if (backup.vescUuid.isBlank() || (fw != null && fw.uuidHex.isBlank())) reasons += "Controller did not report a UUID – cannot bind the backup safely"
        else if (fw != null && backup.vescUuid != fw.uuidHex) reasons += "Backup belongs to a different controller"
        if (!live.linkHealthy) reasons += "Link unstable"
        if (live.faultCode != 0) reasons += "Controller reports fault ${live.faultCode}"
        if (target.virtualFlywheel > 0 || target.wheelieControl > 0)
            reasons += "Virtual flywheel / wheelie control need hardware validation and are not enabled for riding"
        if (target.tractionControl > 0)
            reasons += "Traction control runs only in the vehicle script (kinetic.vescpkg) – direct mode writes cannot do it"
        listOf(target.powerFraction, target.motorCurrentFraction, target.batteryCurrentFraction, target.regenFraction).forEach {
            if (it.isNaN() || it !in 0.0..1.0) reasons += "Profile fraction $it outside 0..1"
        }
        if (target.powerFraction < RideProfile.MIN_POWER_FRACTION) reasons += "Power below the minimum of ${(RideProfile.MIN_POWER_FRACTION * 100).toInt()} %"
        target.speedLimitKmh?.let { if (!it.isFinite() || it <= 0 || it > 400) reasons += "Speed limit $it km/h invalid" }
        target.reverseSpeedLimitKmh?.let { if (!it.isFinite() || it < 0 || it > 100) reasons += "Reverse limit $it km/h invalid" }
        backup?.conf?.let { c -> if (listOf(c.wattMax, c.inCurrentMax, c.maxErpm, c.currentMaxScale).any { !it.isFinite() }) reasons += "Backup contains invalid values" }
        // No mode applied yet = controller runs its stored (stock) limits, i.e. the most powerful state.
        val raising = isMorePowerful(target, cur)
        if (raising && moving)
            reasons += "Increasing power is only allowed at standstill (< ${stationaryKmh} km/h)"
        if (reasons.isNotEmpty() || backup == null) return GateDecision.Denied(reasons)

        val b = backup.conf
        val d = deratingFactor.coerceIn(0.0, 1.0)
        val maxScale = min(b.currentMaxScale, b.currentMaxScale * target.motorCurrentFraction * d)
        val wattMax = b.wattMax * target.powerFraction * d
        val wattMin = b.wattMin * target.regenFraction          // negative number, magnitude scaled
        val inMax = b.inCurrentMax * target.batteryCurrentFraction * d
        val inMin = b.inCurrentMin * target.regenFraction
        // speed limits in m/s; derive backup's speed ceiling from its own pole/gear/wheel setup
        val fact = if (b.wheelDiameterM > 0 && b.gearRatio > 0 && b.motorPoles > 0) (b.motorPoles / 2.0) * 60.0 * b.gearRatio / (b.wheelDiameterM * PI) else 0.0
        val backupMaxMps = if (fact > 0) b.maxErpm / fact else Double.MAX_VALUE
        val backupMinMps = if (fact > 0) b.minErpm / fact else -Double.MAX_VALUE
        val maxMps = min(backupMaxMps, target.speedLimitKmh?.div(3.6) ?: backupMaxMps)
        val minMps = maxOf(backupMinMps, target.reverseSpeedLimitKmh?.let { -it / 3.6 } ?: backupMinMps)
        if (fact <= 0) warnings += "Controller has no wheel/gear setup (si_*) – speed limit not applied"
        if (target.regenFraction < requested.regenFraction) warnings += "Stronger regen of ${requested.name} applies at the next standstill"
        if (target.engineBrakeFraction > 0) warnings += "Engine brake needs the LispBM throttle script; not applied by this write"
        if (target.character.approximate) warnings += "${target.character.label} is an approximation, not a validated engine model"

        // Raw ERPM via SET_MCCONF_TEMP (no unit conversion on the controller; forwarded unchanged over CAN).
        val maxErpm = if (fact > 0) maxMps * fact else b.maxErpm
        val minErpm = if (fact > 0) minMps * fact else b.minErpm
        val payload = Commands.setMcConfTemp(
            currentMinScale = b.currentMinScale, currentMaxScale = maxScale,
            minErpm = minErpm, maxErpm = maxErpm, minDuty = b.minDuty, maxDuty = b.maxDuty,
            wattMin = wattMin, wattMax = wattMax, inCurrentMin = inMin, inCurrentMax = inMax,
            store = false, forwardCan = true, ack = true,
        )
        val summary = "%s: motor %.0f%%, power %.1f kW, battery %.0f A, Vmax %s".format(
            target.name, maxScale * 100, wattMax / 1000, inMax, target.speedLimitKmh?.let { "%.0f km/h".format(it) } ?: "stock")
        return GateDecision.Allowed(payload, WriteToken.issue("SafetyGate", summary), summary, warnings, target)
    }

    /** Restore exactly the backed-up limits. Restoring is a power *increase* from any reduced mode → standstill only. */
    fun planRestore(backup: McConfBackup, live: LiveState, vehicle: VehicleProfile? = null): GateDecision {
        if (!live.linkHealthy) return GateDecision.Denied(listOf("Link unstable"))
        if (live.faultCode != 0) return GateDecision.Denied(listOf("Controller reports fault ${live.faultCode}"))
        val speed = live.speedKmh ?: vehicle?.let { v -> live.erpm?.let { abs(it) * v.kmhPerKiloErpm / 1000 } }
        if (speed == null || speed > stationaryKmh) return GateDecision.Denied(listOf("Restoring stock power is only allowed at standstill (< ${stationaryKmh} km/h)"))
        val b = backup.conf
        val p = Commands.setMcConfTemp(b.currentMinScale, b.currentMaxScale, b.minErpm, b.maxErpm, b.minDuty, b.maxDuty,
            b.wattMin, b.wattMax, b.inCurrentMin, b.inCurrentMax, store = false)
        return GateDecision.Allowed(p, WriteToken.issue("SafetyGate", "restore backup"), "Restore stored limits", emptyList())
    }

    /** Any increase of drive torque/power/speed, regen braking force or reverse speed counts as "more powerful". */
    fun isMorePowerful(a: RideProfile, b: RideProfile): Boolean =
        a.powerFraction > b.powerFraction || a.motorCurrentFraction > b.motorCurrentFraction ||
            a.batteryCurrentFraction > b.batteryCurrentFraction || a.regenFraction > b.regenFraction ||
            (a.speedLimitKmh ?: Double.MAX_VALUE) > (b.speedLimitKmh ?: Double.MAX_VALUE) ||
            (a.reverseSpeedLimitKmh ?: Double.MAX_VALUE) > (b.reverseSpeedLimitKmh ?: Double.MAX_VALUE)

    companion object { val STOCK = RideProfile("stock", "STOCK", builtIn = true) }

    fun backup(fw: FirmwareInfo, conf: McConfTemp) = McConfBackup(fw.uuidHex, clock(), fw.versionString, conf)
}

/** Derating computed in the app for display/planning. The authoritative on-vehicle derating is in lispbm/kinetic.lisp. */
object Derating {
    fun factor(tempMos: Double?, tempMotor: Double?, socPct: Double?, p: DeratingParams = DeratingParams()): Pair<Double, List<String>> {
        val reasons = mutableListOf<String>()
        fun lin(v: Double?, start: Double, end: Double, label: String): Double {
            if (v == null) return 1.0
            val f = if (start < end) 1 - ((v - start) / (end - start)).coerceIn(0.0, 1.0) else 1 - ((start - v) / (start - end)).coerceIn(0.0, 1.0)
            if (f < 1.0) reasons += label
            return f
        }
        val f = minOf(lin(tempMos, p.mosStart, p.mosEnd, "MOSFET temperature"), lin(tempMotor, p.motorStart, p.motorEnd, "Motor temperature"),
            lin(socPct, p.socStart, p.socEnd, "Low battery"))
        return maxOf(f, p.floor) to reasons
    }
}

data class DeratingParams(
    val mosStart: Double = 75.0, val mosEnd: Double = 90.0,
    val motorStart: Double = 100.0, val motorEnd: Double = 120.0,
    val socStart: Double = 15.0, val socEnd: Double = 3.0, val floor: Double = 0.2,
)
