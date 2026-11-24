package org.kineticos.vehicle

import org.kineticos.vesc.codec.VescReader
import org.kineticos.vesc.model.*
import kotlin.test.*

class SafetyGateTest {
    private val fw = FirmwareInfo(6, 6, "60", "aabb", true, 0, 0, 0, false, 0, 0, 0, "fw", 0)
    private val conf = McConfTemp(0.0, 1.0, -30000.0, 60000.0, -0.95, 0.95, -5000.0, 20000.0, -40.0, 200.0, 14, 8.0, 0.48)
    private val gate = SafetyGate(clock = { 0 })
    private val backup = gate.backup(fw, conf)
    private val vehicle = VehicleProfile("v", "Test", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)
    private val still = LiveState(0.0, 0.0, 0, true, 30.0, 30.0, 80.0)
    private val moving = still.copy(erpm = 30000.0, speedKmh = 40.0)

    private fun decodeTemp(p: ByteArray): List<Double> {
        val r = VescReader(p); assertEquals(CommPacketId.SET_MCCONF_TEMP.id, r.u8())
        assertEquals(0, r.u8(), "store must be false"); r.u8(); r.u8(); r.u8()
        return List(10) { r.f32Auto() }
    }

    @Test fun ecoAtStandstillAllowedAndNeverExceedsBackup() {
        val d = gate.planRideMode(fw, backup, null, RideProfile.ECO, vehicle, still)
        assertIs<GateDecision.Allowed>(d)
        val v = decodeTemp(d.payload)
        assertEquals(0.5, v[1], 1e-6); assertTrue(v[3] <= conf.maxErpm); assertEquals(20000.0 * 0.35, v[7], 1e-2); assertEquals(200.0 * 0.4, v[9], 1e-4)
    }

    @Test fun raceProfileNeverExceedsStoredConfig() {
        val d = gate.planRideMode(fw, backup, RideProfile.ECO, RideProfile.RACE, vehicle, still) as GateDecision.Allowed
        val v = decodeTemp(d.payload)
        assertTrue(v[1] <= 1.0); assertTrue(v[3] <= conf.maxErpm + 1); assertTrue(v[7] <= conf.wattMax); assertTrue(v[9] <= conf.inCurrentMax)
    }

    @Test fun raisingPowerWhileMovingDenied() {
        val d = gate.planRideMode(fw, backup, RideProfile.ECO, RideProfile.RACE, vehicle, moving)
        assertIs<GateDecision.Denied>(d)
    }

    @Test fun reducingPowerWhileMovingAllowed() {
        assertIs<GateDecision.Allowed>(gate.planRideMode(fw, backup, RideProfile.RACE, RideProfile.ECO, vehicle, moving))
    }

    @Test fun deniedWithoutBackupFaultOrBadLinkOrWrongController() {
        assertIs<GateDecision.Denied>(gate.planRideMode(fw, null, null, RideProfile.ECO, vehicle, still))
        assertIs<GateDecision.Denied>(gate.planRideMode(fw, backup, null, RideProfile.ECO, vehicle, still.copy(faultCode = 5)))
        assertIs<GateDecision.Denied>(gate.planRideMode(fw, backup, null, RideProfile.ECO, vehicle, still.copy(linkHealthy = false)))
        assertIs<GateDecision.Denied>(gate.planRideMode(fw.copy(uuidHex = "other"), backup, null, RideProfile.ECO, vehicle, still))
        assertIs<GateDecision.Denied>(gate.planRideMode(fw.copy(major = 3), backup, null, RideProfile.ECO, vehicle, still))
    }

    @Test fun unvalidatedFeaturesDenied() {
        assertIs<GateDecision.Denied>(gate.planRideMode(fw, backup, null, RideProfile.ECO.copy(tractionControl = 0.5), vehicle, still))
        assertIs<GateDecision.Denied>(gate.planRideMode(fw, backup, null, RideProfile.ECO.copy(virtualFlywheel = 0.5), vehicle, still))
    }

    @Test fun deratingReducesLimits() {
        val d = gate.planRideMode(fw, backup, null, RideProfile.RACE, vehicle, still, deratingFactor = 0.5) as GateDecision.Allowed
        assertEquals(0.5, decodeTemp(d.payload)[1], 1e-6)
        val (f, why) = Derating.factor(82.5, 30.0, 80.0)
        assertEquals(0.5, f, 1e-9); assertEquals(listOf("MOSFET temperature"), why)
        assertEquals(0.2, Derating.factor(200.0, 30.0, 80.0).first)
    }

    @Test fun restoreWritesBackupExactly() {
        val d = gate.planRestore(backup, still) as GateDecision.Allowed
        val v = decodeTemp(d.payload)
        assertEquals(conf.maxErpm, v[3], 1.0); assertEquals(conf.wattMax, v[7], 1.0)
    }

    @Test fun throttleCurveValidation() {
        assertFailsWith<IllegalArgumentException> { ThrottleCurve(listOf(0.2, 1.0)) }
        assertFailsWith<IllegalArgumentException> { ThrottleCurve(listOf(0.0, 0.8, 0.5)) }
        assertEquals(0.25, ThrottleCurve.expo(2.0).map(0.5), 1e-9)
        MotorCharacter.entries.forEach { ch -> for (i in 0..20) assertTrue(CharacterMaps.torqueMultiplier(ch, i / 20.0) in 0.0..1.0) }
        assertTrue(MotorCharacter.TWO_STROKE_125.approximate)
    }

    @Test fun vehicleValidation() {
        assertTrue(VehicleValidator.validate(vehicle).none { it.blocking })
        assertTrue(VehicleValidator.validate(vehicle.copy(name = "", motorPolePairs = 0)).count { it.blocking } == 2)
        assertTrue(VehicleValidator.validate(vehicle.copy(batteryCellsSeries = 20, batteryNominalV = 200.0)).any { !it.blocking })
    }
}
