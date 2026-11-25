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

class BatteryEstimationTest {
    private fun v(volt: Double, ah: Double, ahChg: Double = 0.0, iIn: Double = 0.5, erpm: Double = 0.0, wh: Double = 0.0) =
        org.kineticos.vesc.model.VescValues(30.0, 30.0, 0.0, iIn, 0.0, 0.0, 0.0, erpm, volt, ah, ahChg, wh, 0.0, 0, 0, 0, null, null, null, null, null, null, null, null)

    @kotlin.test.Test fun coulombCountingFollowsAhNotVoltageSag() {
        val dt = Drivetrain(7, 8.0, 0.5, batteryCellsSeries = 20, batteryCapacityAh = 20.0, batteryNominalV = 72.0)
        val e = TelemetryEngine(dt, false)
        val s0 = e.process(0, v(82.0, 0.0), null, null, null)          // 4.1 V/cell at rest → 95 %
        kotlin.test.assertEquals(95.0, s0.socPct.value!!, 0.01); kotlin.test.assertEquals("coulomb", e.socSource)
        // heavy load: voltage sags to 3.6 V/cell, but only 2 Ah used → 95 − 10 = 85 % (voltage-only would say 25 %)
        val s1 = e.process(1000, v(72.0, 2.0, iIn = 150.0), null, null, null)
        kotlin.test.assertEquals(85.0, s1.socPct.value!!, 0.01); kotlin.test.assertEquals(Quality.ESTIMATED, s1.socPct.quality)
        // regen gives charge back
        kotlin.test.assertEquals(87.5, e.process(2000, v(80.0, 2.0, ahChg = 0.5, iIn = -20.0), null, null, null).socPct.value!!, 0.01)
    }

    @kotlin.test.Test fun lfpCurveIsUsedForFlatChemistry() {
        kotlin.test.assertEquals(70.0, Calc.socFromCellVoltage(3.3, Chemistry.LFP), 1e-9)
        kotlin.test.assertTrue(Calc.socFromCellVoltage(3.3, Chemistry.NMC) < 10)
        val e = TelemetryEngine(Drivetrain(7, 8.0, 0.5, batteryCellsSeries = 16, chemistry = Chemistry.LFP), false)
        kotlin.test.assertEquals(70.0, e.process(0, v(52.8, 0.0), null, null, null).socPct.value!!, 0.01)
        kotlin.test.assertEquals("voltage", e.socSource) // no capacity → voltage only
    }

    @kotlin.test.Test fun rangeUsesRollingConsumption() {
        val dt = Drivetrain(7, 8.0, 0.5, batteryCellsSeries = 20, batteryCapacityAh = 20.0, batteryNominalV = 72.0)
        val e = TelemetryEngine(dt, false)
        val erpm = 56000.0; val mps = Calc.wheelSpeedMps(erpm, dt) // ≈26 m/s
        var t = 0L; var wh = 0.0; var last: TelemetrySample? = null
        // first 3 km at 30 Wh/km, then 3 km at 90 Wh/km → rolling window must reflect the recent higher consumption
        repeat(3600) { i -> t += 100; val km = mps * 0.1 / 1000; wh += km * (if (e.distanceM < 3000) 30.0 else 90.0)
            last = e.process(t, v(82.0, 0.0, wh = wh, iIn = 0.5, erpm = erpm), null, null, null) }
        val rolling = e.rollingWhPerKm()!!
        kotlin.test.assertTrue(rolling > 85, "rolling $rolling")
        kotlin.test.assertTrue(e.whPerKm(last!!)!! < rolling)
        kotlin.test.assertNotNull(e.rangeKm(last!!))
    }
}

class WarningEngineTest {
    private fun s(t: Long, soc: Double? = null, fet: Double? = null, mot: Double? = null, fault: Int = 0) = TelemetrySample(t = t, simulated = false,
        socPct = Q.of(soc, Quality.ESTIMATED), tempMosC = Q.of(fet, Quality.MEASURED), tempMotorC = Q.of(mot, Quality.MEASURED), faultCode = fault,
        faultName = if (fault == 6) "OVER_TEMP_MOTOR" else null)

    @kotlin.test.Test fun batteryStepsFireOnceDownward() {
        val w = WarningEngine()
        kotlin.test.assertTrue(w.evaluate(s(0, soc = 80.0)).isEmpty())
        kotlin.test.assertEquals(listOf("Battery 30 percent"), w.evaluate(s(1, soc = 29.0)).map { it.text })
        kotlin.test.assertTrue(w.evaluate(s(2, soc = 28.0)).isEmpty())
        val crit = w.evaluate(s(3, soc = 9.5)).single()
        kotlin.test.assertEquals(WarnLevel.CRITICAL, crit.level); kotlin.test.assertEquals("Battery 10 percent", crit.text)
    }

    @kotlin.test.Test fun motorTempEarlyWarningWithHysteresisAndFaults() {
        val w = WarningEngine()
        kotlin.test.assertEquals("Motor temperature 101 degrees", w.evaluate(s(0, mot = 101.0)).single().text)
        kotlin.test.assertTrue(w.evaluate(s(1, mot = 98.0)).isEmpty())     // within hysteresis → no re-fire later
        kotlin.test.assertTrue(w.evaluate(s(2, mot = 102.0)).isEmpty())
        w.evaluate(s(3, mot = 90.0))                                         // cleared
        kotlin.test.assertEquals(1, w.evaluate(s(4, mot = 100.5)).size)
        val f = w.evaluate(s(5, mot = 100.5, fault = 6)).single()
        kotlin.test.assertEquals(WarnLevel.CRITICAL, f.level); kotlin.test.assertEquals("Controller fault: over temp motor", f.text)
        kotlin.test.assertTrue(w.evaluate(s(6, fault = 6)).isEmpty())
    }

    @kotlin.test.Test fun linkLossOnlyWhenMoving() {
        val w = WarningEngine()
        kotlin.test.assertNull(w.linkLost(0, 0.0, false))
        kotlin.test.assertNotNull(w.linkLost(0, 30.0, false)); kotlin.test.assertNull(w.linkLost(1, 30.0, false))
        w.linkRestored(); kotlin.test.assertNotNull(w.linkLost(2, 30.0, false))
    }
}

class TelemetryEdgeCaseTest {
    @kotlin.test.Test fun staleGpsAndBadVoltageAreRejected() {
        val dt = Drivetrain(7, 8.0, 0.5, batteryCellsSeries = 20, batteryCapacityAh = 20.0, batteryNominalV = 72.0)
        val stale = TelemetryEngine(dt.copy(configured = false), false).process(10_000, null, GpsFix(47.0, 8.0, null, 0.0, null, 5.0, 0), null, null)
        kotlin.test.assertFalse(stale.speedMps.isAvailable) // 10 s old fix → not used (could fake "standstill")
        val v = org.kineticos.vesc.model.VescValues(30.0, 30.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, null, null, null, null, null, null, null, null)
        val s = TelemetryEngine(dt, false).process(0, v, null, null, null) // must not throw
        kotlin.test.assertFalse(s.socPct.isAvailable)
        kotlin.test.assertEquals(0.0, Calc.socFromCellVoltage(Double.NaN))
    }
}

class AirtimeDetectorTest {
    @kotlin.test.Test fun detectsJumpOnlyWhenRidingAndWithinPlausibleDuration() {
        val d = AirtimeDetector()
        var t = 0L
        fun run(g: Double, ms: Long, kmh: Double? = 30.0) { val end = t + ms; while (t < end) { d.feedG(t, g, kmh); t += 10 } }
        run(1.0, 500); run(0.1, 800); run(2.4, 100)                 // landing spike
        kotlin.test.assertEquals(1, d.jumps.size)
        kotlin.test.assertEquals(800.0, d.jumps[0].durationMs.toDouble(), 20.0)
        kotlin.test.assertEquals(2.4, d.peakG, 1e-9)
        run(1.0, 300); run(0.2, 100); run(1.0, 300)                  // 100 ms dip = bump, not a jump
        kotlin.test.assertEquals(1, d.jumps.size)
        run(1.0, 300, 0.0); run(0.0, 1000, 0.0); run(1.0, 300, 0.0)  // phone dropped at standstill
        kotlin.test.assertEquals(1, d.jumps.size)
        run(0.0, 8000); run(1.0, 100)                                // 8 s "free fall" = implausible
        kotlin.test.assertEquals(1, d.jumps.size)
        kotlin.test.assertEquals(800.0, d.totalAirtimeMs.toDouble(), 20.0)
    }
}
