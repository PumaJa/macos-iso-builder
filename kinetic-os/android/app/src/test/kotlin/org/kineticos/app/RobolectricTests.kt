package org.kineticos.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.core.RideRecorder
import org.kineticos.app.data.*
import org.kineticos.app.ui.KineticTheme
import org.kineticos.app.ui.screens.*
import org.kineticos.telemetry.*
import org.kineticos.vehicle.RideProfile
import org.kineticos.vehicle.VehicleProfile
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DbAndRecorderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun db() = Room.inMemoryDatabaseBuilder(ctx, KineticDb::class.java).allowMainThreadQueries().build()
    private fun sample(t: Long, sim: Boolean) = TelemetrySample(t = t, simulated = sim, speedMps = Q(10.0, if (sim) Quality.SIMULATED else Quality.CALCULATED),
        wattHours = Q(t / 1000.0, Quality.MEASURED), gps = GpsFix(47.0 + t * 1e-7, 8.0, 400.0, 10.0, 0.0, 3.0, t))

    @Test fun recordsResumesAndNeverMixesSimulatedData() = runBlocking {
        val db = db(); val scope = CoroutineScope(Dispatchers.IO)
        val rec = RideRecorder(db, scope)
        rec.start("v1", "ECO", simulated = false, hz = 10)
        val id = rec.active.value!!.id
        val base = System.currentTimeMillis() - 60_000
        for (i in 0 until 120) rec.offer(sample(base + i * 100, sim = false))
        rec.offer(sample(base + 99_999, sim = true)) // must be rejected
        rec.flush()
        assertEquals(120, db.rides().sampleCount(id))
        // simulate process death: new recorder resumes the unfinished ride
        val rec2 = RideRecorder(db, scope)
        rec2.start("v1", "ECO", simulated = false)
        assertEquals(id, rec2.active.value!!.id)
        rec2.stop()
        val r = db.rides().get(id)!!
        assertTrue(r.finished); assertNotNull(r.summaryJson); assertFalse(r.simulated)
        assertTrue(rec2.loadSamples(id).none { it.simulated })
        db.close(); scope.cancel()
    }

    @Test fun latestAuthoritativeBackupIsUsedPerController() = runBlocking {
        val db = db()
        db.backups().insert(BackupEntity(vescUuid = "a", takenAt = 1, json = "first"))
        db.backups().insert(BackupEntity(vescUuid = "a", takenAt = 2, json = "second"))
        assertEquals("second", db.backups().first("a")!!.json); assertNull(db.backups().first("b"))
        db.close()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSimulationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout" }; ShadowLooper.idleMainLooper(); Thread.sleep(50) }
    }
    private fun shot(name: String) {
        runCatching {
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(500)
            val v = compose.activity.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.mainClock.autoAdvance = true
        }.onFailure { System.err.println("SCREENSHOT $name failed: $it"); compose.mainClock.autoAdvance = true }
    }

    @Test fun dashboardOfflineThenSimulatorLabelledAndRideModeThroughSafetyGate() {
        val g = ctx.graph
        g.startRuntime()
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { DashScreen {} } }
        compose.onNodeWithText("No controller connected").assertExists()
        shot("dash_offline")
        runBlocking { g.saveVehicle(VehicleProfile("veh1", "Test MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48, batteryCellsSeries = 20, batteryCapacityAh = 30.0, batteryNominalV = 72.0, maxPowerW = 15000.0)) }
        g.connectTo("sim")
        waitFor { g.connection.status.value is ConnStatus.Online && g.connection.sample.value != null && g.connection.backup.value != null && g.connection.drivetrain.gearRatio == 8.0 }
        Thread.sleep(1500)
        assertTrue((g.connection.status.value as ConnStatus.Online).simulated)
        assertTrue(g.connection.sample.value!!.simulated)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("SIM").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("SIM")[0].assertExists()
        compose.waitForIdle(); Thread.sleep(800)
        shot("dash_simulation")
        // ECO is less powerful than stock → allowed; the safety gate writes RAM-only limits
        val msg = runBlocking { g.applyProfile(RideProfile.ECO) }
        assertTrue(msg.startsWith("Applied"), msg)
        // RACE while moving (simulator drives itself) must be refused
        waitFor { (g.connection.sample.value?.speedMps?.value ?: 0.0) * 3.6 > 3 }
        val msg2 = runBlocking { g.applyProfile(RideProfile.RACE) }
        assertTrue(msg2.contains("standstill"), msg2)
        g.disconnect()
    }

    @Test fun telemetryAndModesScreensRenderWithSimulator() {
        val g = ctx.graph
        g.connectTo("sim")
        waitFor { g.connection.sample.value != null }
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { LiveScreen() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("MOSFET", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Efficiency", substring = true).assertExists()
        shot("telemetry_simulation")
        g.disconnect()
    }

    @Test fun modesGarageSettingsRender() {
        var screen by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { when (screen) { 0 -> ModesScreen(); 1 -> GarageScreen(); 2 -> SettingsScreen(); else -> ConnectScreen() } } }
        compose.onNodeWithText("RESTORE STOCK").assertExists(); shot("modes")
        screen = 1; compose.waitForIdle(); compose.onNodeWithText("+ ADD VEHICLE").assertExists(); shot("garage")
        screen = 2; compose.waitForIdle(); compose.onNodeWithText("Glove mode", substring = true).assertExists(); shot("settings")
        screen = 3; compose.waitForIdle(); compose.onNodeWithText("START SIMULATOR").assertExists(); shot("connect")
    }

    @Test fun modesGoThroughOnVehicleScriptWhenInstalledAndSetupCheckRenders() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("veh2", "Script MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48, batteryCellsSeries = 20,
            batteryCapacityAh = 30.0, batteryNominalV = 72.0, maxPowerW = 15000.0)); g.selectVehicle("veh2") }
        g.connectTo("sim-script")
        waitFor { g.connection.capabilities.value.script && g.connection.backup.value != null && g.connection.drivetrain.gearRatio == 8.0 }
        val msg = runBlocking { g.applyProfile(RideProfile.ECO) }
        assertTrue(msg.startsWith("Applied") && msg.contains("on-vehicle script"), msg)
        val st = g.connection.scriptStatus.value!!
        assertTrue(st.mode == 3 || st.mode == 4, "custom slot selected: $st"); assertEquals(0, st.result)
        assertEquals(0.5, st.effectiveScale, 0.01)
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { SetupScreen() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("On-vehicle script", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("kinetic.lisp answers", substring = true).assertExists()
        shot("vesc_setup_check")
        g.disconnect()
    }

    @Test fun antiTheftLockEndToEndWithSimulatedVehicleScript() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("veh3", "Lock MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)); g.selectVehicle("veh3") }
        g.connectTo("sim-script-parked")
        waitFor { g.connection.scriptStatus.value?.lock != null && g.connection.backup.value != null && g.connection.sample.value?.speedMps?.isAvailable == true }
        val sim = g.connection.transport as org.kineticos.vesc.sim.SimulatedVescTransport
        assertTrue(runBlocking { g.lockAction("lock", "1234") }.contains("Set a PIN first"))
        assertEquals("PIN saved on the vehicle", runBlocking { g.lockAction("setpin", "", "1234") })
        assertTrue(runBlocking { g.lockAction("lock", "9999") }.contains("wrong PIN"))
        assertEquals("Vehicle LOCKED", runBlocking { g.lockAction("lock", "1234") })
        assertTrue(sim.locked)
        // locked: ride modes are refused by the vehicle, output stays disabled
        assertTrue(runBlocking { g.applyProfile(RideProfile.RACE) }.contains("locked"), "mode while locked")
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { androidx.compose.foundation.layout.Box { DashScreen {}; LockedOverlay() } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("VEHICLE LOCKED").fetchSemanticsNodes().isNotEmpty() }
        // someone pushes the bike → tamper flag → warning
        sim.externalErpm = 2000.0
        waitFor(30_000) { g.connection.scriptStatus.value?.lock?.tamper == true }
        waitFor { g.warnings.history.value.any { it.key == "theft" } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("MOVEMENT DETECTED", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("lock_overlay_tamper")
        sim.externalErpm = 0.0
        // 5 wrong PINs → lockout
        repeat(5) { runBlocking { g.lockAction("unlock", "0000".replace("0", "1")) } }
        assertTrue(runBlocking { g.lockAction("unlock", "1234") }.contains("wait"), "lockout")
        assertTrue(sim.locked)
        g.disconnect()
    }
}
