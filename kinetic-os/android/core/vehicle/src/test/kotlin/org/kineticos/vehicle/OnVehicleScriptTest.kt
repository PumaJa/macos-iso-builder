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

class LockProtocolTest {
    private val gate = SafetyGate(clock = { 0 })
    private val still = LiveState(0.0, 0.0, 0, true, null, null, null)
    private fun status(lock: LockState?) = ScriptStatus(2, 3, 0, 1, 1.0, false, false, false, 0, 30, 30, 80.0, 0, lock)

    @Test fun bytesMatchProtocol() {
        assertEquals("4b02040000162e", OnVehicleScript.lock("5678").joinToString("") { "%02x".format(it) })
        assertEquals("4b02050000162e", OnVehicleScript.unlock("5678").joinToString("") { "%02x".format(it) })
        assertEquals("4b020600000000000004d2", OnVehicleScript.setPin(null, "1234").joinToString("") { "%02x".format(it) })
        assertFalse(OnVehicleScript.validPin("123")); assertFalse(OnVehicleScript.validPin("0999")); assertFalse(OnVehicleScript.validPin("12a4")); assertTrue(OnVehicleScript.validPin("00001234".trimStart('0')))
    }

    @Test fun parsesLockBytesAndStaysCompatibleWith17ByteReplies() {
        val base = org.kineticos.vesc.codec.VescWriter().u8(0x4B).u8(2).u8(4).u8(0).u8(1).i16(0).u8(0).u8(0).u8(30).u8(30).i16(800).i32(0)
        assertNull(OnVehicleScript.parseStatus(base.toByteArray())!!.lock)
        val l = OnVehicleScript.parseStatus(base.u8(0b1111).u8(42).toByteArray())!!.lock!!
        assertTrue(l.locked && l.pinSet && l.tamper && l.lockedOut); assertEquals(42, l.lockoutRemainingS)
    }

    @Test fun gateRules() {
        val unlocked = LockState(false, true, false, false, 0)
        assertIs<LockDecision.Denied>(gate.planLock("lock", "1234", null, null, still))                                   // no script
        assertIs<LockDecision.Denied>(gate.planLock("lock", "1234", null, status(null), still))                           // old script
        assertIs<LockDecision.Denied>(gate.planLock("lock", "1234", null, status(unlocked.copy(pinSet = false)), still))   // no PIN
        assertIs<LockDecision.Denied>(gate.planLock("lock", "1234", null, status(unlocked), still.copy(speedKmh = 12.0))) // moving
        assertIs<LockDecision.Denied>(gate.planLock("lock", "1234", null, status(unlocked), still.copy(speedKmh = null))) // speed unknown
        assertIs<LockDecision.Allowed>(gate.planLock("lock", "1234", null, status(unlocked), still))
        val locked = unlocked.copy(locked = true)
        assertIs<LockDecision.Allowed>(gate.planLock("unlock", "1234", null, status(locked), still.copy(speedKmh = 5.0)))
        assertIs<LockDecision.Denied>(gate.planLock("unlock", "1234", null, status(locked.copy(lockedOut = true, lockoutRemainingS = 30)), still))
        assertIs<LockDecision.Denied>(gate.planLock("setpin", "1234", "9999", status(locked), still))
        assertIs<LockDecision.Allowed>(gate.planLock("setpin", "", "9999", status(unlocked.copy(pinSet = false)), still))
    }
}
