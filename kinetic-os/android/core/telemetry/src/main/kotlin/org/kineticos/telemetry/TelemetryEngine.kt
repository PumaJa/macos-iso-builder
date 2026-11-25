package org.kineticos.telemetry

import org.kineticos.vesc.model.*
import kotlin.math.*

/** Drivetrain parameters needed to turn ERPM into speed. Supplied from the active VehicleProfile. */
data class Drivetrain(
    val polePairs: Int, val gearRatio: Double, val wheelDiameterM: Double,
    val batteryCellsSeries: Int? = null, val batteryCapacityAh: Double? = null, val batteryNominalV: Double? = null,
    /** false = no vehicle profile yet → wheel speed / motor rpm cannot be derived and are reported UNAVAILABLE. */
    val configured: Boolean = true,
    val chemistry: Chemistry = Chemistry.NMC,
)

/** Cell chemistry → open-circuit-voltage curve. LFP/LTO curves are flat, so voltage-only SoC is poor; coulomb counting is used when capacity is known. */
enum class Chemistry(val label: String, val curve: List<Pair<Double, Double>>) {
    NMC("Li-ion NMC/NCA", listOf(3.0 to 0.0, 3.3 to 5.0, 3.5 to 15.0, 3.6 to 25.0, 3.7 to 45.0, 3.8 to 60.0, 3.9 to 72.0, 4.0 to 85.0, 4.1 to 95.0, 4.2 to 100.0)),
    LFP("LiFePO4", listOf(2.5 to 0.0, 3.0 to 5.0, 3.2 to 20.0, 3.25 to 40.0, 3.3 to 70.0, 3.35 to 90.0, 3.4 to 97.0, 3.6 to 100.0)),
    LTO("LTO", listOf(1.8 to 0.0, 2.1 to 10.0, 2.3 to 40.0, 2.4 to 60.0, 2.5 to 85.0, 2.6 to 95.0, 2.75 to 100.0)),
}

object Calc {
    fun motorRpm(erpm: Double, polePairs: Int) = erpm / polePairs
    fun wheelSpeedMps(erpm: Double, d: Drivetrain) = erpm / d.polePairs / d.gearRatio / 60.0 * PI * d.wheelDiameterM
    fun kw(w: Double) = w / 1000.0
    fun hp(w: Double) = w / 745.699872         // mechanical horsepower
    fun ps(w: Double) = w / 735.49875          // metric PS
    /**
     * Li-ion SoC estimate from resting cell voltage (piecewise-linear typical NMC curve).
     * Only an ESTIMATE – under load voltage sags; use BMS SoC when available.
     */
    fun socFromCellVoltage(v: Double, chem: Chemistry = Chemistry.NMC): Double {
        val t = chem.curve
        if (v <= t.first().first) return 0.0
        if (v >= t.last().first) return 100.0
        val i = t.indexOfLast { it.first <= v }
        val (v0, s0) = t[i]; val (v1, s1) = t[i + 1]
        return s0 + (s1 - s0) * (v - v0) / (v1 - v0)
    }
}

/**
 * Fuses VESC values (+ optional GPS, IMU, BMS) into [TelemetrySample]s and keeps running ride statistics.
 * Never fabricates values: anything not supplied by hardware is UNAVAILABLE.
 */
class TelemetryEngine(var drivetrain: Drivetrain, private val simulated: Boolean) {
    private var whAtStart: Double? = null; private var whChgAtStart: Double? = null; private var ahAtStart: Double? = null
    private var tachAtStart: Long? = null
    // coulomb counting anchor: SoC estimated from (ideally resting) voltage at connect + raw VESC Ah counters at that moment
    private var socAnchor: Double? = null; private var ahAnchor = 0.0; private var ahChgAnchor = 0.0
    // rolling consumption window: (distance m, net Wh) checkpoints
    private val window = ArrayDeque<Pair<Double, Double>>()
    var socSource: String = "none"; private set
    private var anchoredAtRest = false
    private var lastSpeed: Double? = null; private var lastT: Long? = null
    private var smoothedAccel = 0.0

    var maxSpeedMps = 0.0; private set
    var distanceM = 0.0; private set
    var movingTimeMs = 0L; private set
    var maxPowerW = 0.0; private set

    fun resetTrip() { whAtStart = null; whChgAtStart = null; ahAtStart = null; tachAtStart = null; maxSpeedMps = 0.0; distanceM = 0.0; movingTimeMs = 0; maxPowerW = 0.0 }

    fun process(
        t: Long, v: VescValues?, gps: GpsFix?, imu: ImuData?, bms: BmsValues?, controllers: Int = 1,
        adc: DecodedAdc? = null, odometerM: Long? = null,
    ): TelemetrySample {
        val mq = if (simulated) Quality.SIMULATED else Quality.MEASURED
        val cq = if (simulated) Quality.SIMULATED else Quality.CALCULATED
        val eq = if (simulated) Quality.SIMULATED else Quality.ESTIMATED

        val vescSpeed = v?.erpm?.takeIf { !it.isNaN() && drivetrain.configured }?.let { Calc.wheelSpeedMps(it, drivetrain) }
        val gpsSpeed = gps?.speedMps?.takeIf { (gps.accuracyM ?: 99.0) < 25 }
        val (speed, src, sq) = when {
            vescSpeed != null -> Triple(abs(vescSpeed), "vesc", cq)
            gpsSpeed != null -> Triple(gpsSpeed, "gps", mq)
            else -> Triple(null, "none", Quality.UNAVAILABLE)
        }
        val battP = v?.let { it.inputVoltageV * it.inputCurrentA }
        // Motor (phase) power ≈ battery power minus controller losses; we don't know losses → only battery power is
        // computed; mechanical output is ESTIMATED from duty·V·Imotor (ignores copper/iron losses).
        val motorP = v?.let { it.dutyCycle * it.inputVoltageV * it.motorCurrentA }

        if (v != null) {
            if (whAtStart == null) { whAtStart = v.wattHours; whChgAtStart = v.wattHoursCharged; ahAtStart = v.ampHours; tachAtStart = v.tachometerAbs.toLong() }
            battP?.let { if (it > maxPowerW) maxPowerW = it }
        }
        speed?.let { if (it > maxSpeedMps) maxSpeedMps = it }
        val dtMs = lastT?.let { t - it } ?: 0
        if (speed != null && dtMs in 1..5000) {
            distanceM += speed * dtMs / 1000.0
            if (speed > 0.5) movingTimeMs += dtMs
            lastSpeed?.let { ls -> val a = (speed - ls) / (dtMs / 1000.0) / 9.80665; smoothedAccel += (a - smoothedAccel) * 0.3 }
        }
        lastSpeed = speed; lastT = t

        val cells = drivetrain.batteryCellsSeries
        val cap = drivetrain.batteryCapacityAh
        if (v != null && cells != null && socAnchor == null && !v.inputVoltageV.isNaN()) {
            // prefer a resting anchor (|I| < 2 A); otherwise accept the first sample and re-anchor when the pack rests
            socAnchor = Calc.socFromCellVoltage(v.inputVoltageV / cells, drivetrain.chemistry); ahAnchor = v.ampHours; ahChgAnchor = v.ampHoursCharged
            anchoredAtRest = abs(v.inputCurrentA) < 2.0
        } else if (v != null && cells != null && !anchoredAtRest && abs(v.inputCurrentA) < 2.0) {
            socAnchor = Calc.socFromCellVoltage(v.inputVoltageV / cells, drivetrain.chemistry); ahAnchor = v.ampHours; ahChgAnchor = v.ampHoursCharged; anchoredAtRest = true
        }
        val soc: Q = when {
            bms != null && bms.soc in 0.0..1.0 -> { socSource = "bms"; Q(bms.soc * 100, mq) }
            v != null && socAnchor != null && cap != null && cap > 0 -> {
                socSource = "coulomb"
                val used = (v.ampHours - ahAnchor) - (v.ampHoursCharged - ahChgAnchor)
                Q.of((socAnchor!! - used / cap * 100).coerceIn(0.0, 100.0), eq)
            }
            v != null && cells != null -> { socSource = "voltage"; Q.of(Calc.socFromCellVoltage(v.inputVoltageV / cells, drivetrain.chemistry), eq) }
            else -> { socSource = "none"; Q.NA }
        }
        if (v != null) {
            val net = (v.wattHours - (whAtStart ?: 0.0)) - (v.wattHoursCharged - (whChgAtStart ?: 0.0))
            if (window.isEmpty() || distanceM - window.last().first >= 100) { window.addLast(distanceM to net); while (window.size > 51) window.removeFirst() }
        }
        val accelG = imu?.accX?.takeIf { !it.isNaN() }?.let { Q(it, mq) } ?: if (speed != null && dtMs > 0) Q(smoothedAccel, cq) else Q.NA
        return TelemetrySample(
            t = t, simulated = simulated,
            speedMps = Q.of(speed, sq), speedSource = src,
            motorRpm = Q.of(v?.erpm?.takeIf { drivetrain.configured }?.let { Calc.motorRpm(it, drivetrain.polePairs) }, cq),
            erpm = Q.of(v?.erpm, mq),
            motorPowerW = Q.of(motorP, eq), batteryPowerW = Q.of(battP, cq),
            motorCurrentA = Q.of(v?.motorCurrentA, mq), batteryCurrentA = Q.of(v?.inputCurrentA, mq),
            voltageV = Q.of(v?.inputVoltageV, mq), socPct = soc,
            ampHours = Q.of(v?.let { it.ampHours - (ahAtStart ?: 0.0) }, mq),
            wattHours = Q.of(v?.let { it.wattHours - (whAtStart ?: 0.0) }, mq),
            wattHoursCharged = Q.of(v?.let { it.wattHoursCharged - (whChgAtStart ?: 0.0) }, mq),
            ampHoursCharged = Q.of(v?.ampHoursCharged, mq),
            tempMosC = Q.of(v?.tempMosC, mq), tempMotorC = Q.of(v?.tempMotorC?.takeIf { it > -50 }, mq),
            duty = Q.of(v?.dutyCycle, mq),
            // ADC app decoded level (COMM_GET_DECODED_ADC). Only meaningful if the throttle is wired to the VESC ADC input.
            throttle = Q.of(adc?.level?.coerceIn(-1.0, 1.0), mq),
            odometerKm = Q.of(odometerM?.div(1000.0), mq),
            faultCode = v?.fault ?: 0, faultName = v?.faultCode?.name,
            tachAbs = v?.tachometerAbs?.toLong(), gps = gps,
            accelG = accelG,
            lateralG = imu?.accY?.takeIf { !it.isNaN() }?.let { Q(it, mq) } ?: Q.NA,
            leanDeg = imu?.rollRad?.takeIf { !it.isNaN() }?.let { Q(Math.toDegrees(it), mq) } ?: Q.NA,
            pitchDeg = imu?.pitchRad?.takeIf { !it.isNaN() }?.let { Q(Math.toDegrees(it), mq) } ?: Q.NA,
            bms = bms?.let { BmsSnapshot(it.cellVoltages, it.temps, it.soc * 100, it.soh * 100, it.cellMin, it.cellMax, it.cellDelta, it.status) },
            controllers = controllers,
        )
    }

    /** Wh/km over the trip (CALCULATED) – null until 100 m travelled. */
    fun whPerKm(s: TelemetrySample): Double? {
        val wh = s.wattHours.value ?: return null; val whc = s.wattHoursCharged.value ?: 0.0
        return if (distanceM < 100) null else (wh - whc) / (distanceM / 1000)
    }

    /** Consumption over the last ≈5 km (rolling), falling back to the trip average; null until 1 km of data. */
    fun rollingWhPerKm(): Double? {
        if (window.size < 2) return null
        val (d0, e0) = window.first(); val (d1, e1) = window.last()
        val km = (d1 - d0) / 1000
        return if (km < 1.0) null else ((e1 - e0) / km).takeIf { it > 1 }
    }

    /** Remaining range ESTIMATE: usable Wh left / rolling consumption (trip average if the window is too short). */
    fun rangeKm(s: TelemetrySample): Double? {
        val soc = s.socPct.value ?: return null
        val capWh = drivetrain.batteryCapacityAh?.let { ah -> drivetrain.batteryNominalV?.let { ah * it } } ?: return null
        val c = rollingWhPerKm() ?: whPerKm(s)?.takeIf { it > 1 } ?: return null
        return capWh * soc / 100 / c
    }
}

/** Adaptive polling: back off when replies time out, speed up when the link has headroom. */
class AdaptivePoller(private val minMs: Long = 50, private val maxMs: Long = 1000, start: Long = 100) {
    var intervalMs = start; private set
    fun onSuccess(rttMs: Long) { intervalMs = max(minMs, min(intervalMs, (rttMs * 2).coerceAtLeast(minMs))); if (rttMs * 3 < intervalMs) intervalMs = max(minMs, intervalMs - 10) }
    fun onTimeout() { intervalMs = min(maxMs, intervalMs * 2) }
}
