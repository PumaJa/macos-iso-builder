package org.kineticos.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.kineticos.app.data.ThemeMode

object K {
    val Accent = Color(0xFF00E0FF)
    val AccentDim = Color(0xFF007A8C)
    val Regen = Color(0xFF39FF88)
    val Warn = Color(0xFFFFB020)
    val Danger = Color(0xFFFF3B4E)
    val Sim = Color(0xFFFF4FD8)     // magenta = simulation, used everywhere simulated data is shown
    val Estimated = Color(0xFFB0B8C0)
}

data class Palette(val bg: Color, val surface: Color, val surface2: Color, val text: Color, val textDim: Color, val grid: Color, val night: Boolean, val sun: Boolean = false)
val NightPalette = Palette(Color(0xFF000000), Color(0xFF0D1114), Color(0xFF161C21), Color(0xFFF2F6F8), Color(0xFF7D8A94), Color(0xFF22303A), true)
/** Direct sunlight: pure white/black, no greys for numbers. */
val SunPalette = Palette(Color.White, Color.White, Color(0xFFEDEDED), Color.Black, Color(0xFF202020), Color(0xFF9A9A9A), night = false, sun = true)
val DayPalette = Palette(Color(0xFFF3F5F7), Color(0xFFFFFFFF), Color(0xFFE6EAEE), Color(0xFF0A0F12), Color(0xFF55626C), Color(0xFFD0D7DD), false)
val LocalPalette = staticCompositionLocalOf { NightPalette }
val LocalGlove = staticCompositionLocalOf { true }

val Mono = FontFamily.Monospace
fun numStyle(size: Int, weight: FontWeight = FontWeight.Light) = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, letterSpacing = (-1).sp)
fun labelStyle(size: Int = 12) = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = size.sp, letterSpacing = 2.sp)

@Composable
fun KineticTheme(mode: ThemeMode, glove: Boolean, content: @Composable () -> Unit) {
    val night = when (mode) { ThemeMode.NIGHT -> true; ThemeMode.DAY, ThemeMode.SUN -> false; ThemeMode.AUTO -> isSystemInDarkTheme() || isNightHour() }
    val p = if (mode == ThemeMode.SUN) SunPalette else if (night) NightPalette else DayPalette
    val scheme = if (night) darkColorScheme(primary = K.Accent, background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text, secondary = K.AccentDim)
    else lightColorScheme(primary = K.AccentDim, background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text, secondary = K.AccentDim)
    CompositionLocalProvider(LocalPalette provides p, LocalGlove provides glove) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private fun isNightHour(): Boolean { val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h < 7 || h >= 20 }
