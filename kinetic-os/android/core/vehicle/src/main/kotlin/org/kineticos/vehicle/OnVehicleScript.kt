package org.kineticos.vehicle

import org.kineticos.vesc.client.WriteToken
import org.kineticos.vesc.codec.Commands
import org.kineticos.vesc.codec.VescReader
import org.kineticos.vesc.codec.VescWriter
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Status reported by lispbm/kinetic.lisp (protocol v2, see lispbm/protocol.md). */
data class ScriptStatus(
    val version: Int, val echoedCommand: Int, val result: Int, val mode: Int, val effectiveScale: Double,
    val derateMosfet: Boolean, val derateMotor: Boolean, val derateVoltage: Boolean, val lastReject: Int,
    val tempMosC: Int, val tempMotorC: Int, val voltageV: Double, val erpm: Int,
    /** Anti-theft lock (reply bytes 17–18; null = script without lock support). */
    val lock: LockState? = null,
    /** Neutral (drive disabled) and parental limit (reply bytes 19–21; null = older script). */
    val neutral: Boolean? = null,
    val parentalScale: Double? = null,
    /** Traction control (reply bytes 22–23; null = script without TC). */
    val tcPercent: Int? = null,
    val tcActive: Boolean? = null,
    val airborne: Boolean? = null,
) {
    val parentalActive get() = parentalScale != null && parentalScale < 1.0
    val derating get() = derateMosfet || derateMotor || derateVoltage
    val resultText get() = OnVehicleScript.resultText(result)
}

/** Immobilizer state as reported by the vehicle (see lispbm/protocol.md, cmd 4–6). */
data class LockState(val locked: Boolean, val pinSet: Boolean, val tamper: Boolean, val lockedOut: Boolean, val lockoutRemainingS: Int)

/**
 * Codec for the on-vehicle LispBM script protocol. When the script runs on the VESC, ride modes are sent to the script
 * (which keeps its own boot-time ceiling and derating) instead of writing SET_MCCONF_TEMP directly – otherwise app and
 * script would overwrite each other's limits.
 */
object OnVehicleScript {
    const val MAGIC = 0x4B
    const val VERSION = 2
    const val SLOT_A = 3; const val SLOT_B = 4

    private fun base(cmd: Int) = VescWriter(16).u8(MAGIC).u8(VERSION).u8(cmd)
    fun statusRequest(): ByteArray = base(3).toByteArray()
    fun setMode(index: Int): ByteArray { require(index in 0..4); return base(1).u8(index).toByteArray() }

    /** PIN = 4–8 decimal digits (1000..99999999). */
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() } && pin.toLong() >= 1000
    fun lock(pin: String): ByteArray { require(validPin(pin)); return base(4).u32(pin.toLong()).toByteArray() }
    fun unlock(pin: String): ByteArray { require(validPin(pin)); return base(5).u32(pin.toLong()).toByteArray() }
    fun neutral(on: Boolean): ByteArray = base(7).u8(if (on) 1 else 0).toByteArray()
    /** cmd 9: traction-control intervention 0..100 % (0 = off). The script can only reduce current with it. */
    fun traction(percent: Int): ByteArray { require(percent in 0..100); return base(9).u8(percent).toByteArray() }
    /** Parental ceiling (PIN-protected). scale 1.0 and maxErpm 0 clear it. */
    fun parental(pin: String, maxScale: Double, maxErpm: Long): ByteArray {
        require(validPin(pin)); return base(8).u32(pin.toLong()).i16((maxScale.coerceIn(0.05, 1.0) * 1000).toInt()).u32(maxErpm.coerceIn(0, 0xFFFFFFFFL)).toByteArray()
    }
    fun setPin(oldPin: String?, newPin: String): ByteArray {
        require(validPin(newPin)); val old = oldPin?.takeIf { validPin(it) }?.toLong() ?: 0L
        return base(6).u32(old).u32(newPin.toLong()).toByteArray()
    }
    fun setCustom(slot: Int, scalePermille: Int, watts: Long, batteryCurrentDeciA: Int, maxErpm: Long): ByteArray {
        require(slot == SLOT_A || slot == SLOT_B)
        return base(2).u8(slot).i16(scalePermille.coerceIn(0, 1000)).u32(watts.coerceIn(0, 0xFFFFFFFFL))
            .i16(batteryCurrentDeciA.coerceIn(0, 65535)).u32(maxErpm.coerceIn(0, 0xFFFFFFFFL)).toByteArray()
    }

    /** Parses the data part of a COMM_CUSTOM_APP_DATA reply (without the command id byte); null if not ours. */
    fun parseStatus(p: ByteArray): ScriptStatus? {
        if (p.size < 17 || (p[0].toInt() and 0xFF) != MAGIC) return null
        val r = VescReader(p)
        r.u8(); val ver = r.u8(); if (ver !in 1..2) return null
        val cmd = r.u8(); val res = r.u8(); val mode = r.u8(); val scale = r.u16() / 1000.0; val flags = r.u8(); val rej = r.u8()
        val tm = r.u8().toByte().toInt(); val tmo = r.u8().toByte().toInt(); val v = r.u16() / 10.0; val erpm = r.i32()
        val lock = if (r.remaining >= 2) { val lf = r.u8(); LockState(lf and 1 != 0, lf and 2 != 0, lf and 4 != 0, lf and 8 != 0, r.u8()) } else null
        var neutral: Boolean? = null; var parental: Double? = null
        if (r.remaining >= 3) { val f2 = r.u8(); neutral = f2 and 1 != 0; val ps = r.u16() / 1000.0; parental = if (f2 and 2 != 0) ps else 1.0 }
        var tc: Int? = null; var tcAct: Boolean? = null; var air: Boolean? = null
        if (r.remaining >= 2) { tc = r.u8().coerceIn(0, 100); val f3 = r.u8(); tcAct = f3 and 1 != 0; air = f3 and 2 != 0 }
        return ScriptStatus(ver, cmd, res, mode, scale, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, rej, tm, tmo, v, erpm, lock, neutral, parental, tc, tcAct, air)
    }

    fun resultText(code: Int) = when (code) {
        0 -> "OK"; 2 -> "bad mode index or value"; 3 -> "vehicle moving – power increase refused by the vehicle"
        4 -> "packet too short"; 5 -> "unknown command"; 6 -> "invalid slot"; 7 -> "wrong PIN"; 8 -> "no PIN set yet"
        9 -> "too many wrong PINs – wait"; 10 -> "PIN must be 4–8 digits"; 11 -> "vehicle is locked"; else -> "code $code"
    }

    fun wrap(data: ByteArray) = Commands.customAppData(data)
}

/** Two packets (store params in a free custom slot, then select it), each with its own single-use token. */
data class ScriptModePlan(val setParams: ByteArray, val select: ByteArray, val tokens: Pair<WriteToken, WriteToken>, val summary: String, val warnings: List<String>, val slot: Int, val effective: RideProfile? = null)

/** Translates an already gate-approved ride profile into script parameters (fractions of the backed-up stored limits). */
fun SafetyGate.planScriptMode(
    fw: org.kineticos.vesc.model.FirmwareInfo?, backup: McConfBackup?, current: RideProfile?, target: RideProfile, vehicle: VehicleProfile,
    live: LiveState, activeSlot: Int?,
): ScriptDecision {
    // Traction control is executed by the vehicle script (it can only reduce current) and is sent separately (planTraction).
    val d = planRideMode(fw, backup, current?.copy(tractionControl = 0.0), target.copy(tractionControl = 0.0), vehicle, live, 1.0) // script does its own derating
    if (d is GateDecision.Denied) return ScriptDecision.Denied(d.reasons)
    d as GateDecision.Allowed
    val b = backup!!.conf
    val fact = (b.motorPoles / 2.0) * 60.0 * b.gearRatio / (b.wheelDiameterM * PI)
    val erpm = target.speedLimitKmh?.takeIf { fact > 0 }?.let { (it / 3.6 * fact).coerceAtMost(b.maxErpm) } ?: b.maxErpm
    val slot = if (activeSlot == OnVehicleScript.SLOT_A) OnVehicleScript.SLOT_B else OnVehicleScript.SLOT_A // never edit the active slot
    val p1 = OnVehicleScript.wrap(OnVehicleScript.setCustom(slot, (target.motorCurrentFraction * 1000).roundToInt(),
        (b.wattMax * target.powerFraction).roundToLong(), (b.inCurrentMax * target.batteryCurrentFraction * 10).roundToInt(), erpm.roundToLong()))
    val p2 = OnVehicleScript.wrap(OnVehicleScript.setMode(slot))
    return ScriptDecision.Plan(ScriptModePlan(p1, p2, WriteToken.issue("SafetyGate", "script params") to WriteToken.issue("SafetyGate", "script select"), d.summary + " (via on-vehicle script)",
        d.warnings + "Regen fraction is not handled by the script protocol (stored regen limits stay active)", slot, d.effective))
}

sealed interface ScriptDecision {
    data class Denied(val reasons: List<String>) : ScriptDecision
    data class Plan(val plan: ScriptModePlan) : ScriptDecision
}

/** Lock-related writes go through the gate as well (single-use tokens, preconditions). */
sealed interface LockDecision {
    data class Denied(val reason: String) : LockDecision
    data class Allowed(val payload: ByteArray, val token: WriteToken) : LockDecision
}

fun SafetyGate.planLock(action: String, pin: String, newPin: String? = null, status: ScriptStatus?, live: LiveState, stationaryKmh: Double = 2.0): LockDecision {
    val st = status ?: return LockDecision.Denied("On-vehicle script not installed – the lock lives on the VESC (install kinetic.vescpkg)")
    val lock = st.lock ?: return LockDecision.Denied("Installed script is too old for the anti-theft lock – update kinetic.vescpkg")
    if (!live.linkHealthy) return LockDecision.Denied("Link unstable")
    return when (action) {
        "lock" -> {
            val speed = live.speedKmh ?: return LockDecision.Denied("Speed unknown – cannot verify standstill")
            when {
                !lock.pinSet -> LockDecision.Denied("Set a PIN first")
                lock.locked -> LockDecision.Denied("Already locked")
                speed > stationaryKmh -> LockDecision.Denied("Locking is only possible at standstill")
                !OnVehicleScript.validPin(pin) -> LockDecision.Denied("PIN must be 4–8 digits")
                else -> LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.lock(pin)), WriteToken.issue("SafetyGate", "lock"))
            }
        }
        "unlock" -> when {
            !lock.locked -> LockDecision.Denied("Not locked")
            lock.lockedOut -> LockDecision.Denied("Too many wrong PINs – wait ${lock.lockoutRemainingS} s")
            !OnVehicleScript.validPin(pin) -> LockDecision.Denied("PIN must be 4–8 digits")
            else -> LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.unlock(pin)), WriteToken.issue("SafetyGate", "unlock"))
        }
        "setpin" -> when {
            lock.locked -> LockDecision.Denied("Unlock first")
            newPin == null || !OnVehicleScript.validPin(newPin) -> LockDecision.Denied("New PIN must be 4–8 digits")
            lock.pinSet && !OnVehicleScript.validPin(pin) -> LockDecision.Denied("Enter the current PIN")
            else -> LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.setPin(if (lock.pinSet) pin else null, newPin)), WriteToken.issue("SafetyGate", "set pin"))
        }
        else -> LockDecision.Denied("unknown action")
    }
}

/** Neutral on is always allowed (it only disables drive); neutral off (= enabling drive) only at standstill and never while locked. */
fun SafetyGate.planNeutral(on: Boolean, status: ScriptStatus?, live: LiveState, stationaryKmh: Double = 2.0): LockDecision {
    val st = status ?: return LockDecision.Denied("Neutral needs the vehicle script (kinetic.vescpkg)")
    if (st.neutral == null) return LockDecision.Denied("Installed script is too old for neutral – update kinetic.vescpkg")
    if (!live.linkHealthy) return LockDecision.Denied("Link unstable")
    if (!on) {
        if (st.lock?.locked == true) return LockDecision.Denied("Vehicle is locked")
        val speed = live.speedKmh ?: return LockDecision.Denied("Speed unknown – cannot verify standstill")
        if (speed > stationaryKmh) return LockDecision.Denied("Leaving neutral is only possible at standstill")
    }
    return LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.neutral(on)), WriteToken.issue("SafetyGate", "neutral $on"))
}

/**
 * Traction control on the vehicle (cmd 9). More intervention is always allowed (it can only reduce power);
 * less intervention only at standstill (like any power increase).
 */
fun SafetyGate.planTraction(percent: Int, status: ScriptStatus?, live: LiveState, stationaryKmh: Double = 2.0): LockDecision {
    val st = status ?: return LockDecision.Denied("Traction control needs the vehicle script (kinetic.vescpkg)")
    val cur = st.tcPercent ?: return LockDecision.Denied("Installed script is too old for traction control – update kinetic.vescpkg")
    if (percent !in 0..100) return LockDecision.Denied("Traction control must be 0–100 %")
    if (!live.linkHealthy) return LockDecision.Denied("Link unstable")
    if (percent < cur) {
        val speed = live.speedKmh ?: return LockDecision.Denied("Speed unknown – less traction control only at standstill")
        if (speed > stationaryKmh) return LockDecision.Denied("Less traction control only at standstill")
    }
    return LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.traction(percent)), WriteToken.issue("SafetyGate", "traction $percent"))
}

/** Parental limit: PIN-protected ceiling on the vehicle. Lowering always allowed; raising/clearing only at standstill. */
fun SafetyGate.planParental(pin: String, maxScale: Double, maxSpeedKmh: Double?, status: ScriptStatus?, backup: McConfBackup?, live: LiveState, stationaryKmh: Double = 2.0): LockDecision {
    val st = status ?: return LockDecision.Denied("Parental limit needs the vehicle script (kinetic.vescpkg)")
    if (st.parentalScale == null) return LockDecision.Denied("Installed script is too old for the parental limit – update kinetic.vescpkg")
    if (st.lock?.pinSet != true) return LockDecision.Denied("Set a lock PIN first (used for the parental limit too)")
    if (!OnVehicleScript.validPin(pin)) return LockDecision.Denied("PIN must be 4–8 digits")
    if (!maxScale.isFinite() || maxScale !in 0.05..1.0) return LockDecision.Denied("Limit must be 5–100 %")
    if (!live.linkHealthy) return LockDecision.Denied("Link unstable")
    val raising = maxScale > (st.parentalScale ?: 1.0) || maxSpeedKmh == null
    val speed = live.speedKmh
    if (raising && (speed == null || speed > stationaryKmh)) return LockDecision.Denied("Raising or clearing the limit is only possible at standstill")
    val b = backup?.conf
    val fact = if (b != null && b.wheelDiameterM > 0 && b.gearRatio > 0 && b.motorPoles > 0) (b.motorPoles / 2.0) * 60.0 * b.gearRatio / (b.wheelDiameterM * kotlin.math.PI) else 0.0
    val erpm = if (maxSpeedKmh == null) 0L else { if (fact <= 0) return LockDecision.Denied("Speed limit needs the VESC wheel/gear setup"); (maxSpeedKmh / 3.6 * fact).toLong().coerceAtLeast(1) }
    return LockDecision.Allowed(OnVehicleScript.wrap(OnVehicleScript.parental(pin, maxScale, erpm)), WriteToken.issue("SafetyGate", "parental"))
}
