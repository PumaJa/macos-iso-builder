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
    data class Allowed(val payload: ByteArray, val token: WriteToken, val summary: String, val warnings: List<String>) : GateDecision
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
        fw: FirmwareInfo?, backup: McConfBackup?, current: RideProfile?, target: RideProfile, vehicle: VehicleProfile,
        live: LiveState, deratingFactor: Double = 1.0,
    ): GateDecision {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (fw == null) reasons += "Firmware not identified"
        else if (!firmwareSupported(fw)) reasons += "Firmware ${fw.versionString} not on verified list (5.03–7.x)"
        if (backup == null) reasons += "No configuration backup for this controller"
        else if (fw != null && backup.vescUuid != fw.uuidHex) reasons += "Backup belongs to a different controller"
        if (!live.linkHealthy) reasons += "Link unstable"
        if (live.faultCode != 0) reasons += "Controller reports fault ${live.faultCode}"
        if (target.virtualFlywheel > 0 || target.tractionControl > 0 || target.wheelieControl > 0)
            reasons += "Virtual flywheel / traction / wheelie control need hardware validation and are not enabled for riding"
        listOf(target.powerFraction, target.motorCurrentFraction, target.batteryCurrentFraction, target.regenFraction).forEach {
            if (it !in 0.0..1.0) reasons += "Profile fraction $it outside 0..1"
        }
        val raising = current == null || isMorePowerful(target, current)
        val speed = live.speedKmh ?: live.erpm?.let { abs(it) * vehicle.kmhPerKiloErpm / 1000 }
        if (raising && (speed == null || speed > stationaryKmh))
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
        val fact = (b.motorPoles / 2.0) * 60.0 * b.gearRatio / (b.wheelDiameterM * PI)
        val backupMaxMps = if (fact > 0) b.maxErpm / fact else Double.MAX_VALUE
        val backupMinMps = if (fact > 0) b.minErpm / fact else -Double.MAX_VALUE
        val maxMps = min(backupMaxMps, target.speedLimitKmh?.div(3.6) ?: backupMaxMps)
        val minMps = maxOf(backupMinMps, target.reverseSpeedLimitKmh?.let { -it / 3.6 } ?: backupMinMps)
        if (fact <= 0) warnings += "Controller has no wheel/gear setup (si_*) – speed limit not applied"
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
        return GateDecision.Allowed(payload, WriteToken.issue("SafetyGate", summary), summary, warnings)
    }

    /** Restore exactly the backed-up limits (always allowed when link is healthy – it can only restore stock). */
    fun planRestore(backup: McConfBackup, live: LiveState): GateDecision {
        if (!live.linkHealthy) return GateDecision.Denied(listOf("Link unstable"))
        val b = backup.conf
        val p = Commands.setMcConfTemp(b.currentMinScale, b.currentMaxScale, b.minErpm, b.maxErpm, b.minDuty, b.maxDuty,
            b.wattMin, b.wattMax, b.inCurrentMin, b.inCurrentMax, store = false)
        return GateDecision.Allowed(p, WriteToken.issue("SafetyGate", "restore backup"), "Restore stored limits", emptyList())
    }

    fun isMorePowerful(a: RideProfile, b: RideProfile): Boolean =
        a.powerFraction > b.powerFraction || a.motorCurrentFraction > b.motorCurrentFraction ||
            a.batteryCurrentFraction > b.batteryCurrentFraction ||
            (a.speedLimitKmh ?: Double.MAX_VALUE) > (b.speedLimitKmh ?: Double.MAX_VALUE)

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
