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

/** Traction control end-to-end: profile → SafetyGate → vehicle script (emulated) → status back in the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TractionControlE2ETest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(ms: Long = 30_000, c: () -> Boolean) { val e = System.currentTimeMillis() + ms; while (!c()) { check(System.currentTimeMillis() < e) { "timeout" }; ShadowLooper.idleMainLooper(); Thread.sleep(50) } }

    @Test fun modeWithTractionControlIsSentAndIntervenes() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("tc", "TC MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)); g.selectVehicle("tc") }
        g.connectTo("sim-script-parked")
        waitFor { g.connection.scriptStatus.value?.tcPercent != null && g.connection.linkHealthy.value && g.connection.backup.value != null && g.connection.drivetrain.gearRatio == 8.0 }
        val sim = g.connection.transport as SimulatedVescTransport
        val r = runBlocking { g.applyProfile(RideProfile.SPORT.copy(id = "sport_tc", name = "SPORT TC", tractionControl = 0.4)) }
        assertTrue(r.startsWith("Applied"), r); assertTrue("Traction control 40 %" in r, r)
        assertEquals(40, sim.tcPercent)
        // wheel spins → the vehicle reduces current and reports it
        sim.wheelSpin = true
        waitFor { g.connection.scriptStatus.value?.tcActive == true }
        // airborne → no intervention
        sim.airborne = true
        waitFor { g.connection.scriptStatus.value?.tcActive == false }
        sim.wheelSpin = false; sim.airborne = false
        // back to a mode without TC at standstill → TC switched off
        val r2 = runBlocking { g.applyProfile(RideProfile.ECO) }
        assertTrue(r2.startsWith("Applied"), r2); assertEquals(0, sim.tcPercent)
        g.disconnect()
    }
}
