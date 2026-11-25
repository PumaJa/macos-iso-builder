package org.kineticos.vehicle

import org.kineticos.vesc.codec.VescReader
import org.kineticos.vesc.model.*
import kotlin.test.*

class OnVehicleScriptTest {
    @Test fun encodesExactlyAsProtocolMdExample() {
        // protocol.md: set slot 3 to 120000 W (v2): 4B 02 02 03 01F4 0001D4C0 00FA 0000C350
        val b = OnVehicleScript.setCustom(3, 500, 120000, 250, 50000)
        assertEquals("4b02020301f40001d4c000fa0000c350", b.joinToString("") { "%02x".format(it) })
        assertEquals("4b020102", OnVehicleScript.setMode(2).joinToString("") { "%02x".format(it) })
        assertEquals("4b0203", OnVehicleScript.statusRequest().joinToString("") { "%02x".format(it) })
    }

    @Test fun parsesStatusReply() {
        val p = org.kineticos.vesc.codec.VescWriter().u8(0x4B).u8(2).u8(1).u8(3).u8(2).i16(735).u8(0b011).u8(3).u8(-5 and 0xFF).u8(95).i16(781).i32(-1200).toByteArray()
        val s = OnVehicleScript.parseStatus(p)!!
        assertEquals(0.735, s.effectiveScale); assertTrue(s.derateMosfet && s.derateMotor && !s.derateVoltage)
        assertEquals(-5, s.tempMosC); assertEquals(78.1, s.voltageV, 1e-9); assertEquals(-1200, s.erpm); assertTrue(s.resultText.contains("moving"))
        assertNull(OnVehicleScript.parseStatus(byteArrayOf(0x41, 2, 3)))
    }

    @Test fun scriptPlanUsesFreeSlotAndFractionsOfBackup() {
        val fw = FirmwareInfo(6, 6, "x", "u", true, 0, 0, 0, false, 0, 0, 0, "f", 0)
        val conf = McConfTemp(0.0, 1.0, -30000.0, 60000.0, -0.95, 0.95, -5000.0, 20000.0, -40.0, 200.0, 14, 8.0, 0.48)
        val gate = SafetyGate(clock = { 0 }); val b = gate.backup(fw, conf)
        val v = VehicleProfile("v", "MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)
        val d = gate.planScriptMode(fw, b, null, RideProfile.ECO, v, LiveState(0.0, 0.0, 0, true, 30.0, 30.0, 80.0), activeSlot = 3)
        val plan = (d as ScriptDecision.Plan).plan
        assertEquals(4, plan.slot) // active slot 3 is never edited in place
        val r = VescReader(plan.setParams, 1); r.u8(); r.u8(); r.u8()
        assertEquals(4, r.u8()); assertEquals(500, r.u16()); assertEquals(7000L, r.u32()); assertEquals(800, r.u16())
        assertTrue(r.u32() < 60000) // ECO speed limit 45 km/h → below stored max ERPM
        val moving = gate.planScriptMode(fw, b, RideProfile.ECO, RideProfile.RACE, v, LiveState(30000.0, 40.0, 0, true, 30.0, 30.0, 80.0), 3)
        assertIs<ScriptDecision.Denied>(moving)
    }
}
