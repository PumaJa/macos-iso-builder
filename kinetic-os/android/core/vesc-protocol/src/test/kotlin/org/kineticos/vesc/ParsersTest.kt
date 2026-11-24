package org.kineticos.vesc

import org.kineticos.vesc.codec.*
import org.kineticos.vesc.model.FaultCode
import kotlin.test.*

class ParsersTest {
    @Test fun getValuesFullFw6Layout() {
        val w = VescWriter().f16(45.3, 1e1).f16(60.1, 1e1).f32(80.25, 1e2).f32(30.5, 1e2).f32(1.0, 1e2).f32(80.0, 1e2)
            .f16(0.456, 1e3).f32(25000.0, 1.0).f16(72.4, 1e1).f32(1.2345, 1e4).f32(0.1, 1e4).f32(88.8, 1e4).f32(2.5, 1e4)
            .i32(123456).i32(234567).u8(FaultCode.OVER_TEMP_FET.code).f32(0.0, 1e6).u8(3).f16(40.0, 1e1).f16(41.0, 1e1).f16(42.0, 1e1)
            .f32(1.5, 1e3).f32(2.5, 1e3).u8(2)
        val v = Parsers.values(w.toByteArray())
        assertEquals(45.3, v.tempMosC, 1e-9); assertEquals(72.4, v.inputVoltageV, 1e-9); assertEquals(25000.0, v.erpm)
        assertEquals(0.456, v.dutyCycle, 1e-9); assertEquals(FaultCode.OVER_TEMP_FET, v.faultCode)
        assertEquals(3, v.controllerId); assertEquals(true, v.killSwitchActive); assertEquals(false, v.timeoutActive)
    }

    @Test fun getValuesOldFirmwareMissingTailIsNullNotZero() {
        val w = VescWriter().f16(45.3, 1e1).f16(60.1, 1e1).f32(80.25, 1e2).f32(30.5, 1e2).f32(1.0, 1e2).f32(80.0, 1e2)
            .f16(0.456, 1e3).f32(25000.0, 1.0).f16(72.4, 1e1).f32(1.2345, 1e4).f32(0.1, 1e4).f32(88.8, 1e4).f32(2.5, 1e4)
            .i32(1).i32(1).u8(0)
        val v = Parsers.values(w.toByteArray())
        assertNull(v.controllerId); assertNull(v.status); assertNull(v.tempMos1C)
    }

    @Test fun fwVersionParsesStringsAndOptionalTail() {
        val w = VescWriter().u8(6).u8(6).bytes("60_MK5\u0000".toByteArray()).bytes(ByteArray(12) { it.toByte() })
            .u8(1).u8(0).u8(0).u8(2).u8(1).u8(0).u8(0).u8(0).bytes("vesc_fw\u0000".toByteArray()).u32(0xDEADBEEF)
        val f = Parsers.fwVersion(w.toByteArray())
        assertEquals("6.06", f.versionString); assertEquals("60_MK5", f.hardwareName); assertEquals("vesc_fw", f.firmwareName)
        assertEquals(0xDEADBEEF, f.hwCrc); assertTrue(f.atLeast(6, 5)); assertFalse(f.atLeast(7, 0))
        assertEquals("000102030405060708090a0b", f.uuidHex)
    }

    @Test fun fwVersionOldMinimal() {
        val f = Parsers.fwVersion(VescWriter().u8(3).u8(40).toByteArray())
        assertEquals("3.40", f.versionString); assertNull(f.firmwareName)
    }

    @Test fun bmsCells() {
        val w = VescWriter().f32(80.0, 1e6).f32(0.0, 1e6).f32(10.0, 1e6).f32(10.0, 1e6).f32(1.0, 1e3).f32(80.0, 1e3)
            .u8(3).f16(4.0, 1e3).f16(4.05, 1e3).f16(3.98, 1e3).u8(0).u8(1).u8(0)
            .u8(1).f16(30.0, 1e2).f16(31.0, 1e2).f16(0.0, 1e2).f16(0.0, 1e2).f16(32.0, 1e2).f16(0.8, 1e3).f16(0.95, 1e3).u8(10)
        val b = Parsers.bms(w.toByteArray())
        assertEquals(3, b.cellVoltages.size); assertEquals(0.07, b.cellDelta!!, 1e-9); assertEquals(listOf(false, true, false), b.balancing)
        assertNull(b.ahChargeTotal)
    }

    @Test fun mcConfTempSetupCommandLayout() {
        val p = Commands.setMcConfTempSetup(0.0, 0.5, -5.0, 20.0, -0.95, 0.95, -1000.0, 5000.0, -10.0, 50.0)
        val r = VescReader(p)
        assertEquals(49, r.u8()); assertEquals(0, r.u8()); assertEquals(1, r.u8()); assertEquals(1, r.u8()); assertEquals(1, r.u8())
        assertEquals(0.0, r.f32Auto()); assertEquals(0.5, r.f32Auto()); assertEquals(-5.0, r.f32Auto()); assertEquals(20.0, r.f32Auto())
        assertEquals(10 * 4 + 5, p.size)
    }

    @Test fun forwardCanWraps() {
        assertContentEquals(byteArrayOf(34, 5, 4), Commands.forwardCan(5, Commands.getValues()))
        assertFailsWith<IllegalArgumentException> { Commands.forwardCan(255, Commands.getValues()) }
    }
}
