package org.kineticos.vehicle

import org.kineticos.vesc.model.BatteryCut
import org.kineticos.vesc.model.FirmwareInfo
import org.kineticos.vesc.model.McConfTemp
import kotlin.math.PI
import kotlin.math.abs

enum class CheckState { OK, WARN, FAIL, INFO }
data class CheckItem(val state: CheckState, val title: String, val detail: String, val fix: String? = null)

data class SetupInputs(
    val fw: FirmwareInfo?, val conf: McConfTemp?, val cut: BatteryCut?, val vehicle: VehicleProfile?,
    val canIds: List<Int>, val imu: Boolean, val bms: Boolean, val adc: Boolean, val script: Boolean, val odometer: Boolean,
)

/**
 * Read-only readiness check of a connected VESC for Kinetic OS. Never writes anything – every finding comes with the
 * fix to apply in VESC Tool (or in the Kinetic garage).
 */
object VescSetupCheck {
    fun run(i: SetupInputs): List<CheckItem> = buildList {
        val fw = i.fw
        if (fw == null) { add(CheckItem(CheckState.FAIL, "Controller", "Not connected / firmware unknown", "Connect via Bluetooth, USB or WiFi")); return@buildList }
        add(when {
            fw.atLeast(6, 0) && fw.major <= 7 -> CheckItem(CheckState.OK, "Firmware ${fw.versionString}", "${fw.hardwareName} – fully supported")
            fw.atLeast(5, 3) -> CheckItem(CheckState.WARN, "Firmware ${fw.versionString}", "Supported, but some telemetry (status, odometer, LispBM) needs 6.x", "Update with VESC Tool → Firmware")
            else -> CheckItem(CheckState.FAIL, "Firmware ${fw.versionString}", "Too old: ride-mode writes are blocked", "Update with VESC Tool → Firmware (≥ 6.0 recommended)")
        })
        val c = i.conf
        if (c == null) add(CheckItem(CheckState.FAIL, "Runtime limits", "GET_MCCONF_TEMP not answered – ride modes unavailable", "Update firmware"))
        else {
            add(if (c.wattMax > 0 && c.inCurrentMax > 0) CheckItem(CheckState.OK, "Stored limits", "Power %.1f kW · battery %.0f A · motor scale %.0f %%".format(c.wattMax / 1000, c.inCurrentMax, c.currentMaxScale * 100))
                else CheckItem(CheckState.WARN, "Stored limits", "Power or battery current limit is 0", "Set Motor → Current/Power limits in VESC Tool"))
            if (c.currentMaxScale < 1.0) add(CheckItem(CheckState.INFO, "Current scale %.0f %%".format(c.currentMaxScale * 100), "The stored config already reduces motor current – Kinetic modes can only go lower"))
            val haveSetup = c.motorPoles > 0 && c.gearRatio > 0 && c.wheelDiameterM > 0
            if (!haveSetup) add(CheckItem(CheckState.WARN, "Speed setup in VESC", "Motor poles / gear ratio / wheel diameter not set – speed limits in modes cannot be applied",
                "VESC Tool → Motor → Additional info → Setup (poles, gearing, wheel diameter)"))
            i.vehicle?.let { v ->
                val vescPp = c.motorPoles / 2
                val diffs = mutableListOf<String>()
                if (haveSetup && vescPp != v.motorPolePairs) diffs += "pole pairs VESC $vescPp ≠ garage ${v.motorPolePairs}"
                if (haveSetup && abs(c.gearRatio - v.gearRatio) / v.gearRatio > 0.02) diffs += "gear VESC %.2f ≠ garage %.2f".format(c.gearRatio, v.gearRatio)
                if (haveSetup && abs(c.wheelDiameterM - v.wheelDiameterM) / v.wheelDiameterM > 0.02) diffs += "wheel VESC %.0f mm ≠ garage %.0f mm".format(c.wheelDiameterM * 1000, v.wheelDiameterM * 1000)
                add(if (diffs.isEmpty()) CheckItem(CheckState.OK, "Drivetrain matches garage", "${v.name}: %d pp, gear %.2f, Ø %.0f mm".format(v.motorPolePairs, v.gearRatio, v.wheelDiameterM * 1000))
                    else CheckItem(CheckState.WARN, "Drivetrain differs from garage", diffs.joinToString("; "), "Tap IMPORT FROM VESC (garage) or fix VESC Tool setup – otherwise speed and speed limits disagree"))
                if (haveSetup) {
                    val fact = (c.motorPoles / 2.0) * 60.0 * c.gearRatio / (c.wheelDiameterM * PI)
                    add(CheckItem(CheckState.INFO, "Top speed from ERPM limit", "%.0f km/h (max ERPM %.0f)".format(c.maxErpm / fact * 3.6, c.maxErpm)))
                }
            } ?: add(CheckItem(CheckState.WARN, "Garage", "No vehicle profile – speed is taken from GPS only", "Garage → ADD VEHICLE (or IMPORT FROM VESC)"))
        }
        val cells = i.vehicle?.batteryCellsSeries
        i.cut?.let { cut ->
            if (cells != null && cells > 0) {
                val s = cut.startV / cells; val e = cut.endV / cells
                val (lo, hi) = when (i.vehicle.batteryChemistry) { "LFP" -> 2.5 to 3.0; "LTO" -> 1.8 to 2.3; else -> 3.0 to 3.5 }
                add(if (e in lo..hi && s >= e) CheckItem(CheckState.OK, "Battery cut-off", "Start %.2f V/cell, end %.2f V/cell".format(s, e))
                    else CheckItem(CheckState.WARN, "Battery cut-off", "Start %.2f V/cell, end %.2f V/cell – outside %.1f–%.1f V/cell".format(s, e, lo, hi),
                        "VESC Tool → Motor → Voltage: set battery cut start/end for ${i.vehicle.batteryChemistry} ($cells S)"))
            } else add(CheckItem(CheckState.INFO, "Battery cut-off", "Start %.1f V, end %.1f V (enter cell count in garage to verify)".format(cut.startV, cut.endV)))
        }
        val expected = i.vehicle?.controllerCount ?: 1
        val found = 1 + i.canIds.size
        add(if (found >= expected) CheckItem(CheckState.OK, "Controllers", "$found found" + if (i.canIds.isNotEmpty()) " (CAN ${i.canIds.joinToString()})" else "")
            else CheckItem(CheckState.WARN, "Controllers", "Garage expects $expected, found $found", "Check CAN wiring, unique controller IDs and CAN baud rate"))
        add(if (i.script) CheckItem(CheckState.OK, "On-vehicle script", "kinetic.lisp answers – modes and derating run on the VESC")
            else CheckItem(CheckState.INFO, "On-vehicle script", "Not installed – modes are written as RAM-only limits by the app; no local derating without the phone",
                "Install kinetic.vescpkg (VESC Tool → VESC Packages) or upload lispbm/kinetic.lisp"))
        add(if (i.adc) CheckItem(CheckState.OK, "Throttle readout", "ADC app answers (valid if the throttle is on the VESC ADC input)") else CheckItem(CheckState.INFO, "Throttle readout", "Not available"))
        add(if (i.imu) CheckItem(CheckState.OK, "IMU", "Lean/pitch/acceleration available") else CheckItem(CheckState.INFO, "IMU", "No IMU data – lean angle unavailable"))
        add(if (i.bms) CheckItem(CheckState.OK, "BMS", "Cell voltages and SoC from BMS") else CheckItem(CheckState.INFO, "BMS", "No VESC BMS – SoC is estimated (coulomb counting / voltage)"))
        if (!i.odometer) add(CheckItem(CheckState.INFO, "Odometer", "VESC odometer not available (firmware) – app uses recorded rides"))
    }

    /** Builds a garage profile update from the VESC's own setup values (read-only import). */
    fun importDrivetrain(v: VehicleProfile, c: McConfTemp): VehicleProfile? =
        if (c.motorPoles > 0 && c.gearRatio > 0 && c.wheelDiameterM > 0) v.copy(motorPolePairs = c.motorPoles / 2, gearRatio = c.gearRatio, wheelDiameterM = c.wheelDiameterM) else null
}
