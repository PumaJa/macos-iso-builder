package org.kineticos.telemetry

import org.kineticos.vesc.model.VescValues
import kotlin.math.PI
import kotlin.test.*

class TelemetryTest {
    private val dt = Drivetrain(polePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.5, batteryCellsSeries = 20, batteryCapacityAh = 30.0, batteryNominalV = 72.0)
    private fun vals(erpm: Double, v: Double = 80.0, i: Double = 20.0, im: Double = 50.0, duty: Double = 0.5, wh: Double = 0.0) =
        VescValues(40.0, 50.0, im, i, 0.0, im, duty, erpm, v, 0.0, 0.0, wh, 0.0, 0, 0, 0, null, null, null, null, null, null, null, null)

    @Test fun speedFromErpm() {
        // 56000 erpm / 7 pp = 8000 rpm motor / 8 = 1000 rpm wheel → 1000/60 * π * 0.5 m/s
        assertEquals(1000.0 / 60 * PI * 0.5, Calc.wheelSpeedMps(56000.0, dt), 1e-9)
    }

    @Test fun powerUnits() {
        assertEquals(1.0, Calc.ps(735.49875), 1e-9); assertEquals(1.0, Calc.hp(745.699872), 1e-9); assertEquals(1.5, Calc.kw(1500.0))
    }

    @Test fun batteryPowerIsCalculatedMotorPowerEstimated() {
        val s = TelemetryEngine(dt, simulated = false).process(0, vals(10000.0), null, null, null)
        assertEquals(1600.0, s.batteryPowerW.value!!, 1e-9); assertEquals(Quality.CALCULATED, s.batteryPowerW.quality)
        assertEquals(Quality.ESTIMATED, s.motorPowerW.quality); assertEquals(Quality.ESTIMATED, s.socPct.quality)
        assertEquals(Quality.UNAVAILABLE, s.throttle.quality) // never invented
        assertEquals(Quality.UNAVAILABLE, s.leanDeg.quality)
    }

    @Test fun simulationIsAlwaysLabelled() {
        val s = TelemetryEngine(dt, simulated = true).process(0, vals(10000.0), null, null, null)
        assertTrue(s.simulated); assertEquals(Quality.SIMULATED, s.voltageV.quality); assertEquals(Quality.SIMULATED, s.speedMps.quality)
    }

    @Test fun noDataMeansUnavailable() {
        val s = TelemetryEngine(dt, false).process(0, null, null, null, null)
        assertFalse(s.speedMps.isAvailable); assertFalse(s.voltageV.isAvailable); assertEquals("none", s.speedSource)
    }

    @Test fun gpsFallbackWhenNoVesc() {
        val s = TelemetryEngine(dt, false).process(0, null, GpsFix(47.0, 8.0, 400.0, 10.0, 90.0, 5.0, 0), null, null)
        assertEquals(10.0, s.speedMps.value); assertEquals("gps", s.speedSource); assertEquals(Quality.MEASURED, s.speedMps.quality)
        val bad = TelemetryEngine(dt, false).process(0, null, GpsFix(47.0, 8.0, 400.0, 10.0, 90.0, 80.0, 0), null, null)
        assertFalse(bad.speedMps.isAvailable) // inaccurate fix rejected
    }

    @Test fun distanceAndTripEnergy() {
        val e = TelemetryEngine(dt, false)
        val erpm = 56000.0; val mps = Calc.wheelSpeedMps(erpm, dt)
        var last: TelemetrySample? = null
        for (k in 0..100) last = e.process(k * 100L, vals(erpm, wh = 10.0 + k * 0.01), null, null, null)
        assertEquals(mps * 10, e.distanceM, 1e-6)
        assertEquals(1.0, last!!.wattHours.value!!, 1e-6)
        assertNotNull(e.whPerKm(last)); assertNotNull(e.rangeKm(last))
    }

    @Test fun noVehicleProfileMeansNoWheelSpeed() {
        val s = TelemetryEngine(dt.copy(configured = false), false).process(0, vals(56000.0), null, null, null)
        assertFalse(s.speedMps.isAvailable); assertFalse(s.motorRpm.isAvailable); assertTrue(s.erpm.isAvailable)
    }

    @Test fun socCurveMonotonic() {
        var prev = -1.0
        for (mv in 2900..4300 step 10) { val s = Calc.socFromCellVoltage(mv / 1000.0); assertTrue(s >= prev); prev = s }
    }

    @Test fun adaptivePollerBacksOff() {
        val p = AdaptivePoller(start = 100); p.onTimeout(); assertEquals(200, p.intervalMs); repeat(10) { p.onTimeout() }; assertEquals(1000, p.intervalMs)
    }
}

class ThrottleOdometerTest {
    @kotlin.test.Test fun throttleAndOdometerMeasuredWhenProvided() {
        val dt = Drivetrain(7, 8.0, 0.5)
        val v = org.kineticos.vesc.model.VescValues(40.0, 50.0, 10.0, 5.0, 0.0, 10.0, 0.2, 1000.0, 80.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, null, null, null, null, null, null, null, null)
        val s = TelemetryEngine(dt, false).process(0, v, null, null, null, adc = org.kineticos.vesc.model.DecodedAdc(0.3, 1.5, 0.0, 0.0), odometerM = 12345)
        kotlin.test.assertEquals(0.3, s.throttle.value); kotlin.test.assertEquals(Quality.MEASURED, s.throttle.quality)
        kotlin.test.assertEquals(12.345, s.odometerKm.value!!, 1e-9)
        val none = TelemetryEngine(dt, false).process(0, v, null, null, null)
        kotlin.test.assertFalse(none.throttle.isAvailable); kotlin.test.assertFalse(none.odometerKm.isAvailable)
    }
}
