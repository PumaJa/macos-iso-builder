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
) {
    val derating get() = derateMosfet || derateMotor || derateVoltage
    val resultText get() = OnVehicleScript.resultText(result)
}

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
        return ScriptStatus(ver, cmd, res, mode, scale, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, rej, tm, tmo, v, erpm)
    }

    fun resultText(code: Int) = when (code) {
        0 -> "OK"; 2 -> "bad mode index"; 3 -> "vehicle moving – power increase refused by the vehicle"
        4 -> "packet too short"; 5 -> "unknown command"; 6 -> "invalid slot"; else -> "code $code"
    }

    fun wrap(data: ByteArray) = Commands.customAppData(data)
}

/** Two packets (store params in a free custom slot, then select it), each with its own single-use token. */
data class ScriptModePlan(val setParams: ByteArray, val select: ByteArray, val tokens: Pair<WriteToken, WriteToken>, val summary: String, val warnings: List<String>, val slot: Int)

/** Translates an already gate-approved ride profile into script parameters (fractions of the backed-up stored limits). */
fun SafetyGate.planScriptMode(
    fw: org.kineticos.vesc.model.FirmwareInfo?, backup: McConfBackup?, current: RideProfile?, target: RideProfile, vehicle: VehicleProfile,
    live: LiveState, activeSlot: Int?,
): ScriptDecision {
    val d = planRideMode(fw, backup, current, target, vehicle, live, 1.0) // script does its own derating
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
        d.warnings + "Regen fraction is not handled by the script protocol (stored regen limits stay active)", slot))
}

sealed interface ScriptDecision {
    data class Denied(val reasons: List<String>) : ScriptDecision
    data class Plan(val plan: ScriptModePlan) : ScriptDecision
}
