package org.kineticos.telemetry

import kotlinx.serialization.Serializable

@Serializable
enum class WarnLevel { INFO, WARN, CRITICAL }

@Serializable
data class RideWarning(val ts: Long, val level: WarnLevel, val key: String, val text: String, val simulated: Boolean)

data class WarningThresholds(
    val socSteps: List<Int> = listOf(30, 20, 10, 5),
    val fetWarnC: Double = 80.0, val motorWarnC: Double = 100.0,
    val hysteresisC: Double = 5.0, val cellDeltaWarnV: Double = 0.1,
)

/**
 * Turns telemetry into rider warnings (spoken/vibration/notification in the app) – including warnings *before* the
 * controller faults (e.g. motor temperature), which users report missing in OEM apps. Each condition fires once and
 * re-arms only after it cleared (hysteresis), so riders are not spammed.
 */
class WarningEngine(private val th: WarningThresholds = WarningThresholds()) {
    private val active = mutableSetOf<String>()
    private var lastSocStep: Int? = null
    private var lastFault = 0

    fun evaluate(s: TelemetrySample): List<RideWarning> {
        val out = mutableListOf<RideWarning>()
        fun fire(key: String, level: WarnLevel, text: String) { if (active.add(key)) out += RideWarning(s.t, level, key, text, s.simulated) }
        fun clear(key: String) { active.remove(key) }

        // battery steps (only downward; re-arm when charged 5 % above the step)
        s.socPct.value?.let { soc ->
            val step = th.socSteps.filter { soc <= it }.minOrNull()
            val prev = lastSocStep
            if (step != null && (prev == null || step < prev)) {
                if (prev != null || soc < th.socSteps.max()) out += RideWarning(s.t, if (step <= 10) WarnLevel.CRITICAL else WarnLevel.WARN, "soc$step", "Battery $step percent", s.simulated)
                lastSocStep = step
            } else if (prev != null && soc > prev + 5) lastSocStep = th.socSteps.filter { soc <= it }.minOrNull()
        }
        s.tempMosC.value?.let { if (it >= th.fetWarnC) fire("fet", WarnLevel.WARN, "Controller temperature %.0f degrees".format(it)) else if (it < th.fetWarnC - th.hysteresisC) clear("fet") }
        s.tempMotorC.value?.let { if (it >= th.motorWarnC) fire("motor", WarnLevel.WARN, "Motor temperature %.0f degrees".format(it)) else if (it < th.motorWarnC - th.hysteresisC) clear("motor") }
        s.bms?.cellDelta?.let { if (it >= th.cellDeltaWarnV) fire("cells", WarnLevel.WARN, "Cell imbalance %.0f millivolt".format(it * 1000)) else if (it < th.cellDeltaWarnV / 2) clear("cells") }
        if (s.faultCode != lastFault) {
            if (s.faultCode != 0) out += RideWarning(s.t, WarnLevel.CRITICAL, "fault${s.faultCode}", "Controller fault: " + (s.faultName ?: "code ${s.faultCode}").removePrefix("FAULT_CODE_").replace('_', ' ').lowercase(), s.simulated)
            lastFault = s.faultCode
        }
        return out
    }

    /** Link loss while moving is critical (the vehicle keeps its last limits; the rider should know). */
    fun linkLost(t: Long, wasMovingKmh: Double, simulated: Boolean): RideWarning? =
        if (wasMovingKmh > 3 && active.add("link")) RideWarning(t, WarnLevel.WARN, "link", "Controller connection lost", simulated) else null
    fun linkRestored() { active.remove("link") }
}
