package org.kineticos.app

import android.Manifest
import android.content.Context
import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.data.ThemeMode
import org.kineticos.vehicle.RideProfile
import org.kineticos.vehicle.VehicleProfile
import org.kineticos.vehicle.VehicleType
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders every screen of the real app shell (MainActivity + navigation) in German with simulator data and saves
 * screenshots to build/gallery/. All data shown is SIMULATED and labelled as such in the UI.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class GalleryTest {
    protected open val prefix = ""
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val out = File(System.getProperty("user.dir"), "build/gallery").apply { mkdirs() }

    private fun pump(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(40) } }
    private fun waitFor(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout" }; ShadowLooper.idleMainLooper(); Thread.sleep(50) }
    }
    private fun shot(name: String) {
        compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeBy(600)
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        out.resolve("$prefix$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.mainClock.autoAdvance = true
    }
    private fun open(screen: String, settleMs: Long = 1500) { compose.activity.openScreen.tryEmit(screen); pump(settleMs); compose.waitForIdle() }

    /** Simple original illustration used as the "vehicle photo" (placeholder – the user sets a real photo). */
    private fun drawnPhoto(): String {
        val w = 1200f; val h = 800f
        val b = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888); val c = Canvas(b)
        // studio backdrop
        c.drawPaint(Paint().apply { shader = RadialGradient(w * 0.55f, h * 0.45f, w * 0.7f, Color.rgb(58, 60, 66), Color.rgb(6, 6, 8), Shader.TileMode.CLAMP) })
        c.drawRect(0f, h * 0.78f, w, h, Paint().apply { shader = LinearGradient(0f, h * 0.78f, 0f, h, Color.rgb(28, 28, 32), Color.BLACK, Shader.TileMode.CLAMP) })
        val aa = Paint.ANTI_ALIAS_FLAG
        val tire = Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 34f; color = Color.rgb(18, 18, 20) }
        val rim = Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.rgb(150, 155, 165) }
        val rearC = PointF(330f, 560f); val frontC = PointF(900f, 560f); val r = 150f
        listOf(rearC, frontC).forEach { p -> c.drawCircle(p.x, p.y + 6, r, Paint(aa).apply { color = Color.argb(90, 0, 0, 0) }); c.drawCircle(p.x, p.y, r, tire); c.drawCircle(p.x, p.y, r - 30, rim)
            c.drawCircle(p.x, p.y, 26f, Paint(aa).apply { color = Color.rgb(90, 92, 100) }) }
        val metal = Paint(aa).apply { strokeWidth = 22f; color = Color.rgb(200, 204, 212); strokeCap = Paint.Cap.ROUND }
        c.drawLine(frontC.x, frontC.y, 780f, 250f, metal)                      // fork
        c.drawLine(rearC.x, rearC.y, 560f, 470f, metal)                        // swing arm
        val body = Paint(aa).apply { color = Color.rgb(235, 235, 238) }
        val path = Path().apply { moveTo(470f, 330f); lineTo(760f, 270f); lineTo(800f, 330f); lineTo(650f, 400f); lineTo(520f, 420f); close() }
        c.drawPath(path, body)                                                 // tank/shroud
        val seat = Path().apply { moveTo(300f, 330f); lineTo(520f, 320f); lineTo(500f, 350f); lineTo(280f, 360f); close() }
        c.drawPath(seat, Paint(aa).apply { color = Color.rgb(30, 30, 34) })
        c.drawRoundRect(RectF(480f, 400f, 660f, 520f), 18f, 18f, Paint(aa).apply { color = Color.rgb(40, 42, 48) }) // battery/motor
        c.drawLine(740f, 230f, 830f, 215f, Paint(aa).apply { strokeWidth = 14f; color = Color.rgb(20, 20, 22); strokeCap = Paint.Cap.ROUND }) // bars
        c.drawPath(Path().apply { moveTo(780f, 300f); lineTo(880f, 380f); lineTo(860f, 300f); close() }, body) // fender
        val f = File(ctx.filesDir, "photos").apply { mkdirs() }.resolve("gallery.jpg"); f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    @Test fun renderGallery() {
        Shadows.shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION)
        val g = ctx.graph
        g.startRuntime()
        runBlocking {
            g.saveVehicle(VehicleProfile("gal", "Kinetic MX 01", VehicleType.E_MOTOCROSS, "Eigenbau", "MX-75", drawnPhoto(), "QS 138 90H", 70.0, 7, 7000.0,
                8.0, 0.48, 98.0, 30.0, 72.0, 20, "NMC", 15000.0, 300.0, 200.0, 2))
            g.selectVehicle("gal")
            g.settingsRepo.update { it.copy(theme = ThemeMode.NIGHT, dimAfterIdleS = 0, orientation = org.kineticos.app.data.ScreenOrientation.AUTO) }
        }
        // 1) ride with the simulated vehicle (incl. emulated vehicle script) so rides/telemetry have data
        g.connectTo("sim-script")
        waitFor { g.connection.status.value is ConnStatus.Online && g.connection.backup.value != null && g.connection.drivetrain.gearRatio == 8.0 }
        pump(5000)
        // simulated jumps via the (injected) accelerometer – the phone sensor path is the same in real use
        var tj = System.currentTimeMillis()
        repeat(4) { k -> fun feed(gv: Double, ms: Long) { val e = tj + ms; while (tj < e) { g.motion.inject(tj, gv); tj += 10 } }
            feed(1.0, 400); feed(0.12, 500L + k * 250); feed(2.2 + k * 0.4, 60); feed(1.0, 400); pump(700) }
        pump(3000)
        runBlocking { g.applyProfile(RideProfile.ECO) }
        pump(6000)
        open("HOME", 4000); shot("00_start")
        open("DASH"); shot("01_cockpit")
        open("LIVE"); shot("02_telemetrie")
        open("MODES"); shot("03_fahrmodi")
        open("SETUP"); shot("04_vesc_setup_check")
        open("GARAGE"); shot("05_garage")
        open("CONNECT"); shot("06_verbinden")
        open("RACE"); shot("07_racing")
        open("REMOTE"); shot("08_fernueberwachung")
        open("GROUP"); shot("09_gruppenfahrt")
        open("SETTINGS"); shot("10_einstellungen")
        // finish the ride → ride list + detail with charts
        g.disconnect(); pump(3000)
        open("RIDES"); shot("11_fahrten")
        compose.onAllNodesWithText(" km · ", substring = true)[0].performClick()
        val end = System.currentTimeMillis() + 20_000
        while (compose.onAllNodesWithText("TEMPO", substring = true).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < end) pump(300)
        pump(1000); shot("12_fahrt_detail")
        open("STATS")
        // only simulator rides exist here → explicitly include them (they stay labelled as simulated)
        compose.onAllNodes(isToggleable())[0].performClick(); pump(1500); compose.waitForIdle()
        shot("12b_statistik_gesamt")
        // themes
        g.connectTo("sim"); waitFor { g.connection.sample.value != null }; pump(4000)
        runBlocking { g.settingsRepo.update { it.copy(theme = ThemeMode.DAY) } }; open("DASH"); shot("13_cockpit_tag")
        runBlocking { g.settingsRepo.update { it.copy(theme = ThemeMode.SUN, primaryValue = org.kineticos.app.data.PrimaryValue.POWER) } }; open("DASH"); shot("14_cockpit_sonne_leistung")
        runBlocking { g.settingsRepo.update { it.copy(theme = ThemeMode.NIGHT, primaryValue = org.kineticos.app.data.PrimaryValue.SPEED) } }
        // picture-in-picture mini cockpit
        compose.activity.inPip.value = true; pump(1500); shot("15_bild_im_bild"); compose.activity.inPip.value = false; pump(800)
        // anti-theft lock: parked vehicle, PIN, lock, someone pushes the bike
        g.disconnect(); pump(1000)
        g.connectTo("sim-script-parked")
        runCatching { waitFor { g.connection.scriptStatus.value?.lock != null && g.connection.linkHealthy.value && g.connection.sample.value?.speedMps?.isAvailable == true } }.onFailure {
            error("lock wait: status=${g.connection.status.value} script=${g.connection.scriptStatus.value} caps=${g.connection.capabilities.value.script} speed=${g.connection.sample.value?.speedMps}")
        }
        val r1 = runBlocking { g.lockAction("setpin", "", "2468") }; val r2 = runBlocking { g.lockAction("lock", "2468") }
        check(r2 == "Vehicle LOCKED") { "setpin=$r1 lock=$r2 lockState=${g.connection.scriptStatus.value?.lock} speed=${g.connection.sample.value?.speedMps}" }
        (g.connection.transport as org.kineticos.vesc.sim.SimulatedVescTransport).externalErpm = 2000.0
        waitFor { g.connection.scriptStatus.value?.lock?.tamper == true }
        open("DASH", 2500); shot("16_diebstahlsperre_alarm")
        g.disconnect(); pump(1000)
        // personalisation: own background image, own name next to the logo
        runBlocking { g.settingsRepo.update { it.copy(bgImage = drawnPhoto(), bgDim = 0.72f, brandName = "MX TEAM") } }
        open("HOME", 3000); shot("17_eigenes_design")
        open("SETTINGS", 2000); shot("18_design_einstellungen")
    }
}

/** Same gallery in landscape (handlebar mount). */
@Config(sdk = [34], qualifiers = "de-w891dp-h411dp-land-xxhdpi")
class GalleryLandscapeTest : GalleryTest() { override val prefix = "quer_" }
