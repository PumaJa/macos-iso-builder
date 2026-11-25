package org.kineticos.vehicle

import org.kineticos.vesc.model.*
import kotlin.test.*

class VescSetupCheckTest {
    private val fw = FirmwareInfo(6, 6, "75_300", "aa", true, 0, 0, 0, false, 0, 0, 0, "fw", 0)
    private val conf = McConfTemp(0.0, 1.0, -30000.0, 60000.0, -0.95, 0.95, -5000.0, 20000.0, -40.0, 200.0, 14, 8.0, 0.48)
    private val veh = VehicleProfile("v", "MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48, batteryCellsSeries = 20)
    private fun run(f: FirmwareInfo? = fw, c: McConfTemp? = conf, cut: BatteryCut? = BatteryCut(66.0, 62.0), v: VehicleProfile? = veh, can: List<Int> = emptyList(), script: Boolean = false) =
        VescSetupCheck.run(SetupInputs(f, c, cut, v, can, imu = true, bms = false, adc = true, script = script, odometer = true))

    @Test fun healthySetup() {
        val r = run()
        assertTrue(r.none { it.state == CheckState.FAIL || it.state == CheckState.WARN }, r.filter { it.state == CheckState.WARN }.toString())
        assertTrue(r.any { it.title.startsWith("Top speed") && it.detail.startsWith("97 km/h") }, r.toString()) // 60000 / (7*60*8/(0.48π)) *3.6 = 96.9
    }

    @Test fun detectsMismatchesAndBadCutoff() {
        val r = run(v = veh.copy(gearRatio = 6.0), cut = BatteryCut(80.0, 56.0))
        assertTrue(r.any { it.title == "Drivetrain differs from garage" && it.detail.contains("gear") })
        assertTrue(r.any { it.title == "Battery cut-off" && it.state == CheckState.WARN })   // 2.8 V/cell end for NMC
        assertEquals(CheckState.OK, run(v = veh.copy(batteryChemistry = "LFP", batteryCellsSeries = 24), cut = BatteryCut(72.0, 62.4)).first { it.title == "Battery cut-off" }.state)
    }

    @Test fun oldFirmwareAndMissingControllers() {
        assertEquals(CheckState.FAIL, run(f = fw.copy(major = 4, minor = 1)).first().state)
        assertTrue(run(v = veh.copy(controllerCount = 2)).any { it.title == "Controllers" && it.state == CheckState.WARN })
        assertTrue(run(v = veh.copy(controllerCount = 2), can = listOf(2)).any { it.title == "Controllers" && it.state == CheckState.OK })
        assertEquals(CheckState.FAIL, run(f = null).single().state)
    }

    @Test fun importTakesVescSetup() {
        val v = VescSetupCheck.importDrivetrain(veh.copy(motorPolePairs = 3, gearRatio = 1.0), conf)!!
        assertEquals(7, v.motorPolePairs); assertEquals(8.0, v.gearRatio); assertEquals(0.48, v.wheelDiameterM)
        assertNull(VescSetupCheck.importDrivetrain(veh, conf.copy(motorPoles = 0)))
    }
}
