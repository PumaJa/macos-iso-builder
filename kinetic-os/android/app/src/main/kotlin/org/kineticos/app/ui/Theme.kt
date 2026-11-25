package org.kineticos.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.kineticos.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.kineticos.app.data.ThemeMode

object K {
    /**
     * Monochrome brand: the accent is white on dark themes and near-black on light themes (set by [KineticTheme]).
     * The whole UI is greyscale (black → white). Status is carried by brightness, labels and icons, never by hue alone:
     * simulated values are grey AND tagged "SIM" + banner; warnings carry text/icons.
     */
    var Accent by mutableStateOf(Color(0xFFF2F2F2)); internal set
    var OnAccent by mutableStateOf(Color(0xFF000000)); internal set
    var AccentDim by mutableStateOf(Color(0xFF9A9A9E)); internal set
    var Regen by mutableStateOf(Color(0xFFD0D0D0)); internal set
    var Warn by mutableStateOf(Color(0xFFBDBDBD)); internal set
    var Danger by mutableStateOf(Color(0xFFFFFFFF)); internal set
    /** Simulation: mid grey + mandatory "SIM" tags/banner (never shown like real data). */
    var Sim by mutableStateOf(Color(0xFF8E8E93)); internal set
    var Estimated by mutableStateOf(Color(0xFFA0A0A0)); internal set
}

data class Palette(val bg: Color, val surface: Color, val surface2: Color, val text: Color, val textDim: Color, val grid: Color, val night: Boolean, val sun: Boolean = false)
val NightPalette = Palette(Color(0xFF000000), Color(0xFF121212), Color(0xFF1E1E20), Color(0xFFFFFFFF), Color(0xFF8E8E93), Color(0xFF2C2C2E), true)
/** Direct sunlight: pure white/black, no greys for numbers. */
val SunPalette = Palette(Color.White, Color.White, Color(0xFFEDEDED), Color.Black, Color(0xFF202020), Color(0xFF9A9A9A), night = false, sun = true)
val DayPalette = Palette(Color(0xFFF2F2F4), Color(0xFFFFFFFF), Color(0xFFE8E8EC), Color(0xFF0A0A0A), Color(0xFF5E5E66), Color(0xFFD4D4DA), false)
val LocalPalette = staticCompositionLocalOf { NightPalette }
val LocalGlove = staticCompositionLocalOf { true }
/** User branding: own logo image (file path) and name shown next to it. */
data class Brand(val logo: String? = null, val name: String = "KINETIC")
val LocalBrand = staticCompositionLocalOf { Brand() }

val Mono = FontFamily.Monospace
/** Barlow (SIL OFL 1.1, docs/licenses) – one calm, modern grotesk for everything. */
val Display = FontFamily(
    Font(R.font.barlow_light, FontWeight.Light), Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium), Font(R.font.barlow_semibold, FontWeight.SemiBold),
)
/** Numbers: light for large values (premium instrument look), medium only when asked for emphasis. */
fun numStyle(size: Int, weight: FontWeight = FontWeight.Light) =
    TextStyle(fontFamily = Display, fontWeight = if (size >= 40) FontWeight.Light else if (weight.weight >= 600) FontWeight.Medium else FontWeight.Normal,
        fontSize = size.sp, letterSpacing = if (size >= 40) (-1).sp else 0.sp)
/** Small uppercase labels with generous tracking. */
fun labelStyle(size: Int = 12) = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = size.sp, letterSpacing = 1.6.sp)
/** Titles: light at large sizes, regular below. */
fun titleStyle(size: Int = 28) = TextStyle(fontFamily = Display, fontWeight = if (size >= 30) FontWeight.Light else FontWeight.Normal, fontSize = size.sp, letterSpacing = 0.3.sp)

@Composable
fun KineticTheme(mode: ThemeMode, glove: Boolean, accentLevel: Float = 0.95f, brand: Brand = Brand(), content: @Composable () -> Unit) {
    val night = when (mode) { ThemeMode.NIGHT -> true; ThemeMode.DAY, ThemeMode.SUN -> false; ThemeMode.AUTO -> isSystemInDarkTheme() || isNightHour() }
    val p = if (mode == ThemeMode.SUN) SunPalette else if (night) NightPalette else DayPalette
    val scheme = if (night) darkColorScheme(primary = Color(0xFFF2F2F2), onPrimary = Color.Black, background = p.bg, surface = p.surface, surfaceVariant = p.surface2,
        onSurface = p.text, onSurfaceVariant = p.textDim, onBackground = p.text, secondary = K.AccentDim, outline = p.grid, surfaceContainer = p.surface)
    else lightColorScheme(primary = Color(0xFF111111), onPrimary = Color.White, background = p.bg, surface = p.surface, surfaceVariant = p.surface2,
        onSurface = p.text, onSurfaceVariant = p.textDim, onBackground = p.text, secondary = K.AccentDim, outline = p.grid, surfaceContainer = p.surface)
    SideEffect {
        val lv = accentLevel.coerceIn(0.4f, 1f)
        if (night) { K.Accent = Color(lv, lv, lv); K.OnAccent = Color.Black; K.AccentDim = Color(0xFF9A9A9E)
            K.Regen = Color(0xFFD0D0D0); K.Warn = Color(0xFFBDBDBD); K.Danger = Color.White; K.Sim = Color(0xFF8E8E93); K.Estimated = Color(0xFFA0A0A0) }
        else { K.Accent = Color(0xFF111111); K.OnAccent = Color.White; K.AccentDim = Color(0xFF55555A)
            K.Regen = Color(0xFF333333); K.Warn = Color(0xFF444444); K.Danger = Color.Black; K.Sim = Color(0xFF6E6E73); K.Estimated = Color(0xFF666666) }
    }
    CompositionLocalProvider(LocalPalette provides p, LocalGlove provides glove, LocalBrand provides brand) {
        MaterialTheme(colorScheme = scheme, typography = Typography().let { t ->
            t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = Display), bodyMedium = t.bodyMedium.copy(fontFamily = Display), bodySmall = t.bodySmall.copy(fontFamily = Display),
                titleLarge = t.titleLarge.copy(fontFamily = Display), titleMedium = t.titleMedium.copy(fontFamily = Display), labelLarge = t.labelLarge.copy(fontFamily = Display))
        }, content = content)
    }
}

private fun isNightHour(): Boolean { val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h < 7 || h >= 20 }
