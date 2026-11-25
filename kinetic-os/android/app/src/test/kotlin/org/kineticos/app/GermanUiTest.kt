package org.kineticos.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.data.ThemeMode
import org.kineticos.app.ui.KineticTheme
import org.kineticos.app.ui.SimBanner
import org.kineticos.app.ui.screens.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GermanUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun shot(name: String) {
        compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeBy(500)
        val v = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun germanDashAndModesRender() {
        val g = ctx.graph
        g.connectTo("sim")
        val end = System.currentTimeMillis() + 20_000
        while (g.connection.sample.value == null && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        var screen by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { KineticTheme(ThemeMode.NIGHT, true) { androidx.compose.foundation.layout.Column { SimBanner(); when (screen) { 0 -> DashScreen {}; else -> ModesScreen() } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("KEINE ECHTEN FAHRZEUGDATEN", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("de_dash")
        screen = 1; compose.waitForIdle()
        compose.onNodeWithText("SERIE LADEN").assertExists()
        shot("de_modes")
        g.disconnect()
    }
}
