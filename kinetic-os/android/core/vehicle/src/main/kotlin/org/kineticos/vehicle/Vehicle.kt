package org.kineticos.vehicle

import kotlinx.serialization.Serializable
import kotlin.math.PI

@Serializable
enum class VehicleType { E_MOTORCYCLE, E_MOTOCROSS, E_BIKE, E_SCOOTER, E_KART, CUSTOM_BUILD, RC, MULTI_MOTOR }

@Serializable
data class VehicleProfile(
    val id: String,
    val name: String,
    val type: VehicleType = VehicleType.E_MOTOCROSS,
    val manufacturer: String = "",
    val model: String = "",
    val photoUri: String? = null,
    val motorName: String = "",
    val motorKv: Double? = null,
    val motorPolePairs: Int = 7,
    val maxMotorRpm: Double? = null,
    val gearRatio: Double = 1.0,           // motor turns per wheel turn
    val wheelDiameterM: Double = 0.5,
    val weightKg: Double? = null,
    val batteryCapacityAh: Double? = null,
    val batteryNominalV: Double? = null,
    val batteryCellsSeries: Int? = null,
    val maxPowerW: Double? = null,
    val maxMotorCurrentA: Double? = null,
    val maxBatteryCurrentA: Double? = null,
    val controllerCount: Int = 1,
    val canIds: List<Int> = emptyList(),
    val lastVescUuid: String? = null,
    val lastBleAddress: String? = null,
) {
    /** km/h per 1000 ERPM – useful sanity check shown in the garage. */
    val kmhPerKiloErpm: Double get() = 1000.0 / motorPolePairs / gearRatio * PI * wheelDiameterM * 60 / 1000
    val batteryWh: Double? get() = if (batteryCapacityAh != null && batteryNominalV != null) batteryCapacityAh * batteryNominalV else null
}

data class ValidationIssue(val field: String, val message: String, val blocking: Boolean)

object VehicleValidator {
    fun validate(v: VehicleProfile): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        fun err(f: String, m: String) = out.add(ValidationIssue(f, m, true))
        fun warn(f: String, m: String) = out.add(ValidationIssue(f, m, false))
        if (v.name.isBlank()) err("name", "Name required")
        if (v.motorPolePairs !in 1..50) err("motorPolePairs", "Pole pairs must be 1–50")
        if (v.gearRatio !in 0.1..100.0) err("gearRatio", "Gear ratio must be 0.1–100")
        if (v.wheelDiameterM !in 0.02..2.0) err("wheelDiameterM", "Wheel diameter must be 0.02–2 m")
        v.motorKv?.let { if (it !in 1.0..5000.0) err("motorKv", "KV must be 1–5000") }
        v.batteryCellsSeries?.let { if (it !in 1..40) err("batteryCellsSeries", "Cells in series must be 1–40") }
        v.batteryNominalV?.let { if (it !in 3.0..200.0) err("batteryNominalV", "Voltage must be 3–200 V") }
        if (v.batteryCellsSeries != null && v.batteryNominalV != null) {
            val perCell = v.batteryNominalV / v.batteryCellsSeries
            if (perCell !in 2.0..4.4) warn("batteryNominalV", "%.2f V per cell looks unusual".format(perCell))
        }
        v.batteryCapacityAh?.let { if (it !in 0.1..2000.0) err("batteryCapacityAh", "Capacity must be 0.1–2000 Ah") }
        v.maxMotorCurrentA?.let { if (it !in 1.0..2000.0) err("maxMotorCurrentA", "Motor current must be 1–2000 A") }
        v.maxBatteryCurrentA?.let { if (it !in 1.0..2000.0) err("maxBatteryCurrentA", "Battery current must be 1–2000 A") }
        v.maxPowerW?.let { if (it !in 10.0..500_000.0) err("maxPowerW", "Power must be 10 W – 500 kW") }
        if (v.controllerCount !in 1..16) err("controllerCount", "1–16 controllers")
        v.canIds.forEach { if (it !in 0..254) err("canIds", "CAN id $it out of range") }
        return out
    }
}
