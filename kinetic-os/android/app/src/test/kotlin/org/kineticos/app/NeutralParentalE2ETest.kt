package org.kineticos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.vehicle.RideProfile
import org.kineticos.vehicle.VehicleProfile
import org.kineticos.vesc.sim.SimulatedVescTransport
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NeutralParentalE2ETest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(ms: Long = 30_000, c: () -> Boolean) { val e = System.currentTimeMillis() + ms; while (!c()) { check(System.currentTimeMillis() < e) { "timeout" }; ShadowLooper.idleMainLooper(); Thread.sleep(50) } }

    @Test fun neutralAndParentalLimitThroughVehicleScript() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("np", "NP MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)); g.selectVehicle("np") }
        g.connectTo("sim-script-parked")
        waitFor { g.connection.scriptStatus.value?.neutral != null && g.connection.linkHealthy.value && g.connection.backup.value != null && g.connection.drivetrain.gearRatio == 8.0 }
        val sim = g.connection.transport as SimulatedVescTransport
        assertEquals("NEUTRAL – drive disabled", runBlocking { g.setNeutral(true) }); assertTrue(sim.neutral)
        assertEquals("Drive enabled", runBlocking { g.setNeutral(false) }); assertFalse(sim.neutral)
        // parental limit needs the PIN
        assertTrue(runBlocking { g.setParental("1234", 0.5, null) }.contains("PIN"))
        runBlocking { g.lockAction("setpin", "", "1234") }
        assertTrue(runBlocking { g.setParental("9999", 0.5, null) }.contains("wrong PIN"))
        assertEquals("Parental limit active: 50 %", runBlocking { g.setParental("1234", 0.5, 25.0) })
        // RACE now still capped at 50 % by the vehicle
        assertTrue(runBlocking { g.applyProfile(RideProfile.RACE) }.startsWith("Applied"))
        waitFor { (g.connection.scriptStatus.value?.effectiveScale ?: 1.0) <= 0.5 + 1e-6 }
        assertEquals("Parental limit cleared", runBlocking { g.setParental("1234", 1.0, null) })
        g.disconnect()
    }

    @Test fun phoneAccelerometerJumpsBecomeAirtimeInSamples() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("air", "Air MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)); g.selectVehicle("air") }
        g.connectTo("sim")
        waitFor { (g.connection.sample.value?.speedMps?.value ?: 0.0) * 3.6 > 6 }
        var t = 1_000_000L
        fun feed(gv: Double, ms: Long) { val end = t + ms; while (t < end) { g.motion.inject(t, gv); t += 10 } }
        feed(1.0, 300); feed(0.1, 700); feed(2.6, 60); feed(1.0, 300)
        waitFor { (g.connection.sample.value?.airtimeMs ?: 0) >= 650 }
        assertTrue((g.connection.sample.value?.gForce?.value ?: 0.0) >= 0.0)
        assertEquals(1, g.motion.detector.jumps.size)
        g.disconnect()
    }
}
