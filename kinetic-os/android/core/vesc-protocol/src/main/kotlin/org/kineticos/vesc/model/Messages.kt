package org.kineticos.vesc.model

import kotlinx.serialization.Serializable

@Serializable
data class FirmwareInfo(
    val major: Int,
    val minor: Int,
    val hardwareName: String,
    val uuidHex: String,
    val pairingDone: Boolean?,
    val testVersion: Int?,
    val hwType: Int?,          // 0 = VESC, 1 = VESC BMS, 2 = custom module (HW_TYPE enum)
    val customConfigCount: Int?,
    val hasPhaseFilters: Boolean?,
    val qmlHw: Int?,
    val qmlApp: Int?,
    val nrfFlags: Int?,
    val firmwareName: String?,
    val hwCrc: Long?,
) {
    val versionString: String get() = "%d.%02d".format(major, minor) + (if ((testVersion ?: 0) > 0) "-beta$testVersion" else "")
    fun atLeast(maj: Int, min: Int) = major > maj || (major == maj && minor >= min)
}

/** COMM_GET_VALUES reply. Fields absent in older firmware are null (never zero-filled). */
@Serializable
data class VescValues(
    val tempMosC: Double,
    val tempMotorC: Double,
    val motorCurrentA: Double,
    val inputCurrentA: Double,
    val idA: Double,
    val iqA: Double,
    val dutyCycle: Double,       // -1..1
    val erpm: Double,
    val inputVoltageV: Double,
    val ampHours: Double,
    val ampHoursCharged: Double,
    val wattHours: Double,
    val wattHoursCharged: Double,
    val tachometer: Int,
    val tachometerAbs: Int,
    val fault: Int,
    val pidPos: Double?,
    val controllerId: Int?,
    val tempMos1C: Double?, val tempMos2C: Double?, val tempMos3C: Double?,
    val vdV: Double?, val vqV: Double?,
    val status: Int?,            // bit0 timeout, bit1 kill switch
) {
    val faultCode: FaultCode? get() = FaultCode.of(fault)
    val timeoutActive: Boolean? get() = status?.let { it and 1 != 0 }
    val killSwitchActive: Boolean? get() = status?.let { it and 2 != 0 }
}

/** COMM_GET_VALUES_SETUP reply – values in configured units, summed over CAN where firmware does so. */
@Serializable
data class VescSetupValues(
    val tempMosC: Double, val tempMotorC: Double,
    val currentTotalA: Double, val currentInTotalA: Double,
    val dutyCycle: Double, val erpm: Double,
    val speedMps: Double, val inputVoltageV: Double,
    val batteryLevel: Double,   // 0..1, firmware estimate
    val ampHoursTotal: Double, val ampHoursChargedTotal: Double,
    val wattHoursTotal: Double, val wattHoursChargedTotal: Double,
    val distanceM: Double, val distanceAbsM: Double,
    val pidPos: Double, val fault: Int, val controllerId: Int,
    val numVescs: Int, val wattHoursLeft: Double,
    val odometerM: Long?, val uptimeMs: Long?,
)

@Serializable
data class ImuData(
    val rollRad: Double, val pitchRad: Double, val yawRad: Double,
    val accX: Double, val accY: Double, val accZ: Double,      // g
    val gyroX: Double, val gyroY: Double, val gyroZ: Double,   // deg/s
    val magX: Double, val magY: Double, val magZ: Double,
    val q0: Double, val q1: Double, val q2: Double, val q3: Double,
)

@Serializable
data class BmsValues(
    val vTot: Double, val vCharge: Double, val iIn: Double, val iInIc: Double,
    val ahCnt: Double, val whCnt: Double,
    val cellVoltages: List<Double>, val balancing: List<Boolean>,
    val temps: List<Double>, val tempIc: Double, val tempHum: Double, val humidity: Double,
    val tempMaxCell: Double, val soc: Double, val soh: Double, val canId: Int,
    val ahChargeTotal: Double?, val whChargeTotal: Double?, val ahDischargeTotal: Double?, val whDischargeTotal: Double?,
    val pressure: Double?, val dataVersion: Int?, val status: String?,
) {
    val cellMax get() = cellVoltages.maxOrNull()
    val cellMin get() = cellVoltages.minOrNull()
    val cellDelta get() = if (cellVoltages.isEmpty()) null else cellVoltages.max() - cellVoltages.min()
}

/** COMM_GET_MCCONF_TEMP reply: the runtime limits that SET_MCCONF_TEMP can change. */
@Serializable
data class McConfTemp(
    val currentMinScale: Double, val currentMaxScale: Double,
    val minErpm: Double, val maxErpm: Double,
    val minDuty: Double, val maxDuty: Double,
    val wattMin: Double, val wattMax: Double,
    val inCurrentMin: Double, val inCurrentMax: Double,
    val motorPoles: Int, val gearRatio: Double, val wheelDiameterM: Double,
)

@Serializable
data class VescStats(
    val speedAvg: Double?, val speedMax: Double?, val powerAvg: Double?, val powerMax: Double?,
    val currentAvg: Double?, val currentMax: Double?, val tempMosAvg: Double?, val tempMosMax: Double?,
    val tempMotorAvg: Double?, val tempMotorMax: Double?, val countTimeS: Double?,
)

@Serializable
data class BatteryCut(val startV: Double, val endV: Double)

@Serializable
data class GnssData(
    val lat: Double?, val lon: Double?, val heightM: Double?, val speedMps: Double?, val hdop: Double?,
    val msToday: Int?,
)

/** COMM_GET_DECODED_ADC: decoded throttle (0..1, or -1..1 in centered modes) and raw ADC voltages of the ADC app. */
@Serializable
data class DecodedAdc(val level: Double, val voltageV: Double, val level2: Double, val voltage2V: Double)

/** COMM_GET_DECODED_PPM: decoded PPM level (-1..1) and last pulse length (ms). */
@Serializable
data class DecodedPpm(val level: Double, val pulseLenMs: Double)
