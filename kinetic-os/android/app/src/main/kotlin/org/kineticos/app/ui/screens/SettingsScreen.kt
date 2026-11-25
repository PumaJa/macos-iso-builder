package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.kineticos.app.BuildConfig
import org.kineticos.app.data.*
import org.kineticos.app.graph
import org.kineticos.app.ui.*

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val s by g.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { g.settingsRepo.update(f) }
    var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var user by remember { mutableStateOf(s.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val offText = stringResource(R.string.set_off)

    val bgPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { ImageStore.import(ctx, uri, "background")?.let { path -> g.settingsRepo.update { it.copy(bgImage = path) } } }
    }
    val logoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { ImageStore.import(ctx, uri, "logo", maxPx = 512, png = true)?.let { path -> g.settingsRepo.update { it.copy(logoImage = path) } } }
    }
    var brandName by remember(s.brandName) { mutableStateOf(s.brandName) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // ---- personalisation ----
        SectionTitle(stringResource(R.string.set_design))
        Text(stringResource(R.string.set_orientation), color = p.textDim, style = labelStyle(11))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            ScreenOrientation.entries.forEach { o -> BigButton(stringResource(when (o) { ScreenOrientation.AUTO -> R.string.orient_auto; ScreenOrientation.LANDSCAPE -> R.string.orient_landscape; ScreenOrientation.PORTRAIT -> R.string.orient_portrait }),
                Modifier.weight(1f), selected = s.orientation == o) { upd { it.copy(orientation = o) } } }
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(96.dp, 60.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(p.surface2)) { VehiclePhoto(s.bgImage, Modifier.fillMaxSize()) }
                Text(stringResource(R.string.set_bg_image), color = p.text, style = labelStyle(14), modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                BigButton(stringResource(R.string.set_pick), Modifier.weight(1f)) { bgPicker.launch("image/*") }
                BigButton(stringResource(R.string.set_remove), Modifier.weight(1f), enabled = s.bgImage != null) { ImageStore.delete(s.bgImage); upd { it.copy(bgImage = null) } }
            }
            LabeledSlider(stringResource(R.string.set_bg_dim), s.bgDim, { v -> upd { it.copy(bgDim = v) } }, 0f..1f)
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                KineticLogo(Modifier.weight(1f), size = 20)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                BigButton(stringResource(R.string.set_logo) + ": " + stringResource(R.string.set_pick), Modifier.weight(1f), compact = true) { logoPicker.launch("image/*") }
                BigButton(stringResource(R.string.set_remove), Modifier.weight(0.6f), enabled = s.logoImage != null, compact = true) { ImageStore.delete(s.logoImage); upd { it.copy(logoImage = null) } }
            }
            Field(stringResource(R.string.set_brand_name), brandName, { brandName = it.take(24); upd { st -> st.copy(brandName = brandName) } }, Modifier.fillMaxWidth())
        }
        LabeledSlider(stringResource(R.string.set_accent), s.accentLevel, { v -> upd { it.copy(accentLevel = v) } }, 0.4f..1f)
        Text(stringResource(R.string.set_design_hint), color = p.textDim, style = labelStyle(10))
        SectionTitle(stringResource(R.string.set_display))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeMode.entries.forEach { m -> BigButton(stringResource(when (m) { ThemeMode.NIGHT -> R.string.theme_night; ThemeMode.DAY -> R.string.theme_day; ThemeMode.SUN -> R.string.theme_sun; ThemeMode.AUTO -> R.string.theme_auto }), Modifier.weight(1f), selected = s.theme == m) { upd { it.copy(theme = m) } } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SpeedUnit.entries.forEach { u -> BigButton(u.name, Modifier.weight(1f), selected = s.speedUnit == u) { upd { it.copy(speedUnit = u) } } }
            PowerUnit.entries.forEach { u -> BigButton(u.name, Modifier.weight(1f), selected = s.powerUnit == u) { upd { it.copy(powerUnit = u) } } }
        }
        SectionTitle(stringResource(R.string.set_dash))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PrimaryValue.entries.forEach { v -> BigButton(stringResource(when (v) { PrimaryValue.SPEED -> R.string.pv_speed; PrimaryValue.POWER -> R.string.pv_power; PrimaryValue.BATTERY_CURRENT -> R.string.pv_battery_current }), Modifier.weight(1f), selected = s.primaryValue == v, compact = true) { upd { it.copy(primaryValue = v) } } } }
        Text(stringResource(R.string.set_tiles, s.dashTiles.map { DASH_TILE_CATALOG[it]?.let { id -> stringResource(id) } ?: it }.joinToString()), color = p.textDim, style = labelStyle(10))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DASH_TILE_CATALOG.entries.forEach { (k, label) -> BigButton(stringResource(label), selected = k in s.dashTiles, compact = true) {
                upd { st -> st.copy(dashTiles = if (k in st.dashTiles) st.dashTiles - k else (st.dashTiles + k).takeLast(6)) } } }
        }
        Toggle(stringResource(R.string.set_voice), s.voiceAlerts, { v -> upd { it.copy(voiceAlerts = v) } }, stringResource(R.string.set_voice_sub))
        Toggle(stringResource(R.string.set_vibrate), s.vibrateAlerts, { v -> upd { it.copy(vibrateAlerts = v) } })
        Toggle(stringResource(R.string.set_pip), s.pipOnLeave, { v -> upd { it.copy(pipOnLeave = v) } }, stringResource(R.string.set_pip_sub))
        Toggle(stringResource(R.string.set_glove), s.gloveMode, { v -> upd { it.copy(gloveMode = v) } })
        LabeledSlider(stringResource(R.string.settings_brightness), if (s.screenBrightness < 0) 0f else s.screenBrightness, { v -> upd { it.copy(screenBrightness = if (v < 0.05f) -1f else v) } },
            0f..1f, { if (it < 0.05f) ctx.getString(R.string.settings_brightness_auto) else "%.0f%%".format(it * 100) })
        Toggle(stringResource(R.string.set_keep_on), s.keepScreenOn, { v -> upd { it.copy(keepScreenOn = v) } })
        LabeledSlider(stringResource(R.string.set_dim), s.dimAfterIdleS.toFloat(), { v -> upd { it.copy(dimAfterIdleS = v.toInt()) } }, 0f..600f, { if (it < 1) offText else "%.0f s".format(it) })
        SectionTitle(stringResource(R.string.set_cockpit))
        Toggle(stringResource(R.string.set_boot), s.autoStartOnBoot, { v -> upd { it.copy(autoStartOnBoot = v) } }, stringResource(R.string.set_boot_sub))
        Toggle(stringResource(R.string.set_kiosk), s.kiosk, { v -> upd { it.copy(kiosk = v) } }, stringResource(R.string.set_kiosk_sub))
        Toggle(stringResource(R.string.set_autorec), s.autoRecord, { v -> upd { it.copy(autoRecord = v) } })
        Toggle(stringResource(R.string.set_saver), s.powerSaver, { v -> upd { it.copy(powerSaver = v) } }, stringResource(R.string.set_saver_sub))
        LabeledSlider(stringResource(R.string.set_poll), s.pollHz.toFloat(), { v -> upd { it.copy(pollHz = v.toInt()) } }, 1f..50f, { "%.0f Hz".format(it) })
        Text(stringResource(R.string.set_poll_hint), color = p.textDim, style = labelStyle(10))
        BigButton(stringResource(R.string.set_battopt), Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) g.toast(ctx.getString(R.string.set_already_excluded))
            else runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
        }
        Text(stringResource(R.string.set_samsung), color = p.textDim, style = labelStyle(10))
        SectionTitle(stringResource(R.string.set_maps))
        Field(stringResource(R.string.set_map_day), s.mapStyleDay, { v -> upd { it.copy(mapStyleDay = v) } }, Modifier.fillMaxWidth())
        Field(stringResource(R.string.set_map_night), s.mapStyleNight, { v -> upd { it.copy(mapStyleNight = v) } }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.set_map_hint), color = p.textDim, style = labelStyle(10))
        SectionTitle(stringResource(R.string.set_account))
        Field(stringResource(R.string.set_server_url), server, { server = it }, Modifier.fillMaxWidth())
        Field(stringResource(R.string.set_username), user, { user = it }, Modifier.fillMaxWidth())
        Field(stringResource(R.string.set_password), pass, { pass = it }, Modifier.fillMaxWidth(), password = true)
        Field(stringResource(R.string.set_display_name), display, { display = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(stringResource(R.string.set_login), Modifier.weight(1f)) { scope.launch {
                g.settingsRepo.update { it.copy(serverUrl = server) }
                runCatching { g.api.login(user, pass) }.onSuccess { r -> g.settingsRepo.update { it.copy(authToken = r.token, username = r.user.username) }; msg = ctx.getString(R.string.set_logged_in, r.user.displayName); pass = "" }.onFailure { msg = ctx.getString(R.string.set_login_failed, it.message) }
            } }
            BigButton(stringResource(R.string.set_register), Modifier.weight(1f)) { scope.launch {
                g.settingsRepo.update { it.copy(serverUrl = server) }
                runCatching { g.api.register(user, pass, display.ifBlank { user }) }.onSuccess { r -> g.settingsRepo.update { it.copy(authToken = r.token, username = r.user.username) }; msg = ctx.getString(R.string.set_registered); pass = "" }.onFailure { msg = ctx.getString(R.string.set_register_failed, it.message) }
            } }
            BigButton(stringResource(R.string.set_logout), Modifier.weight(1f), color = K.Danger) { upd { it.copy(authToken = null) }; g.socket.stop(); msg = ctx.getString(R.string.set_logged_out) }
        }
        msg?.let { Text(it, color = K.Accent) }
        Text(if (s.authToken != null) stringResource(R.string.set_signed_in, s.username ?: "") else stringResource(R.string.set_not_signed_in), color = p.textDim)
        SectionTitle(stringResource(R.string.set_about))
        Text(stringResource(R.string.set_about_text, BuildConfig.VERSION_NAME), color = p.textDim, style = labelStyle(10))
    }
}
