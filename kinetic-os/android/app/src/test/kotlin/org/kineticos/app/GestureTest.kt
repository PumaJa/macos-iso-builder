package org.kineticos.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.data.ThemeMode
import org.kineticos.app.ui.KineticTheme
import org.kineticos.app.ui.screens.CurveEditor
import org.kineticos.app.ui.screens.DashScreen
import org.kineticos.vehicle.ThrottleCurve
import org.kineticos.vehicle.VehicleProfile
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(ms: Long = 30_000, c: () -> Boolean) { val e = System.currentTimeMillis() + ms; while (!c()) { check(System.currentTimeMillis() < e) { "timeout" }; ShadowLooper.idleMainLooper(); Thread.sleep(50) } }
    private fun shot(name: String) {
        compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeBy(500)
        val v = compose.activity.window.decorView
        val b = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888); v.draw(android.graphics.Canvas(b))
        File(System.getProperty("user.dir"), "build/gallery").apply { mkdirs() }.resolve("$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun swipeOverGaugeChangesModeThroughSafetyGate() {
        val g = ctx.graph
        g.startRuntime()
        runBlocking { g.saveVehicle(VehicleProfile("sw", "Swipe MX", motorPolePairs = 7, gearRatio = 8.0, wheelDiameterM = 0.48)); g.selectVehicle("sw") }
        g.connectTo("sim-script-parked")
        waitFor { g.connection.backup.value != null && g.connection.linkHealthy.value && g.connection.drivetrain.gearRatio == 8.0 && g.connection.scriptStatus.value != null }
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { DashScreen {} } }
        compose.waitForIdle()
        assertNull(g.appliedProfile.value)
        compose.onRoot().performTouchInput { swipeLeft(startX = centerX + width * 0.4f, endX = centerX - width * 0.4f, durationMillis = 300) }
        waitFor { g.appliedProfile.value != null }
        assertEquals("eco", g.appliedProfile.value!!.id) // from stock, next mode in the list = first profile
        g.disconnect()
    }

    @Test fun dragCurvePoint() {
        var curve by androidx.compose.runtime.mutableStateOf(ThrottleCurve.LINEAR.resample(6))
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag("ed")) {
            CurveEditor(curve, androidx.compose.ui.Modifier.fillMaxWidth().height(300.dp)) { curve = it } } } }
        compose.onNodeWithTag("ed").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.6f)); moveTo(androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.1f)); up()
        }
        compose.waitForIdle()
        assertTrue(curve.points[2] > 0.8, "point 2 dragged up: ${curve.points}")
        assertTrue(curve.points.zipWithNext().all { (a, b) -> b >= a })
        shot("17_kurveneditor")
    }
}

