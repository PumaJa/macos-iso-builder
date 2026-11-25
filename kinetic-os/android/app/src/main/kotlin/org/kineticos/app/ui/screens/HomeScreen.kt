package org.kineticos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import org.kineticos.app.R
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.graph
import org.kineticos.app.ui.*

/** Start hub: logo, full-bleed vehicle hero, key numbers and large tiles. Own design (Kinetic OS design system). */
@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val s by g.connection.sample.collectAsStateWithLifecycle()
    val status by g.connection.status.collectAsStateWithLifecycle()
    val applied by g.appliedProfile.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val rideMs = remember(tick) { g.connection.engine?.movingTimeMs ?: 0L }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            KineticLogo(Modifier.weight(1f))
            val (txt, col) = when (val st = status) {
                is ConnStatus.Online -> (if (st.simulated) "SIM" else "VESC") to (if (st.simulated) K.Sim else K.Regen)
                is ConnStatus.Connecting, is ConnStatus.Reconnecting -> stringResource(R.string.home_connecting) to K.Warn
                else -> stringResource(R.string.home_offline) to p.textDim
            }
            Pill(txt, col, Modifier.clickable { go(Screen.CONNECT) })
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.surface2).clickable { go(Screen.SETTINGS) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings), tint = p.text, modifier = Modifier.size(22.dp))
            }
        }
    }
    val hero: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(24.dp)).background(Brush.radialGradient(listOf(p.surface2, p.surface), radius = 900f))) {
            if (vehicle?.photoUri != null) VehiclePhoto(vehicle?.photoUri, Modifier.fillMaxSize())
            else Icon(Icons.Filled.TwoWheeler, null, Modifier.size(140.dp).align(Alignment.Center), tint = p.text.copy(alpha = 0.15f))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text((vehicle?.let { listOf(it.manufacturer, it.model).filter { m -> m.isNotBlank() }.joinToString(" · ") } ?: "").uppercase(), style = labelStyle(11), color = Color.White.copy(alpha = 0.6f))
                Text(vehicle?.name ?: stringResource(R.string.dash_no_vehicle), style = titleStyle(40), color = Color.White, maxLines = 1)
            }
        }
    }
    val numbers: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(vertical = 14.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val soc = s?.socPct
            KeyNumber(stringResource(R.string.home_battery), (soc?.let { fmt(it, 0) } ?: "—") + "%", soc?.let { qColor(it.quality, p.text) } ?: p.textDim, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(40.dp).background(p.grid))
            KeyNumber(stringResource(R.string.home_mode), applied?.name ?: stringResource(R.string.mode_stock), K.Accent, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(40.dp).background(p.grid))
            KeyNumber(stringResource(R.string.home_riding_time), "%d:%02d".format(rideMs / 3_600_000, rideMs / 60_000 % 60), p.text, Modifier.weight(1f))
        }
    }
    val settings by g.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pickFor by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val key = pickFor; pickFor = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        scope.launch { org.kineticos.app.data.ImageStore.import(ctx, uri, "tile_$key", 1200)?.let { path -> g.settingsRepo.update { it.copy(tileImages = it.tileImages + (key to path)) } } }
    }
    val tiles = settings.homeTiles.mapNotNull { k -> HOME_TILE_CATALOG[k]?.let { k to it } }
    val cockpit: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, p.grid, RoundedCornerShape(16.dp)).clickable { go(Screen.DASH) }.padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_tile_cockpit), style = titleStyle(24), color = p.text)
                Text((applied?.name ?: stringResource(R.string.mode_stock)).uppercase(), style = labelStyle(10), color = p.textDim)
            }
            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(K.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = K.OnAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (landscape) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp)) {
            header(); hero(Modifier.fillMaxWidth().weight(1f)); numbers()
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
            cockpit(); Spacer(Modifier.height(12.dp))
            tiles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (k, t) -> HomeTile(stringResource(t.label), t.icon, settings.tileImages[k], Modifier.weight(1f), 120, { pickFor = k; picker.launch("image/*") }) { go(t.screen) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        header(); hero(Modifier.fillMaxWidth().height(280.dp)); numbers()
        Spacer(Modifier.height(12.dp)); cockpit(); Spacer(Modifier.height(12.dp))
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (k, t) -> HomeTile(stringResource(t.label), t.icon, settings.tileImages[k], Modifier.weight(1f), 150, { pickFor = k; picker.launch("image/*") }) { go(t.screen) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeyNumber(label: String, value: String, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = numStyle(30), color = color, maxLines = 1)
        Text(label.uppercase(), style = labelStyle(10), color = p.textDim)
    }
}

data class HomeTileDef(val label: Int, val icon: ImageVector, val screen: Screen)

/** All tiles the user can put on the start screen (Settings → Design). */
val HOME_TILE_CATALOG: Map<String, HomeTileDef> = linkedMapOf(
    "STATS" to HomeTileDef(R.string.home_tile_stats, Icons.Filled.BarChart, Screen.STATS),
    "MODES" to HomeTileDef(R.string.home_tile_modes, Icons.Filled.Bolt, Screen.MODES),
    "TRAILS" to HomeTileDef(R.string.home_tile_trails, Icons.Filled.Terrain, Screen.MAP),
    "TRACKS" to HomeTileDef(R.string.home_tile_tracks, Icons.Filled.SportsScore, Screen.RACE),
    "RIDES" to HomeTileDef(R.string.home_tile_rides, Icons.Filled.History, Screen.RIDES),
    "GROUP" to HomeTileDef(R.string.home_tile_group, Icons.Filled.Groups, Screen.GROUP),
    "GARAGE" to HomeTileDef(R.string.nav_garage, Icons.Filled.Garage, Screen.GARAGE),
    "LIVE" to HomeTileDef(R.string.nav_telemetry, Icons.Filled.MonitorHeart, Screen.LIVE),
    "REMOTE" to HomeTileDef(R.string.nav_remote, Icons.Filled.Satellite, Screen.REMOTE),
    "SETUP" to HomeTileDef(R.string.home_tile_setup, Icons.Filled.Build, Screen.SETUP),
    "CONNECT" to HomeTileDef(R.string.nav_connect, Icons.Filled.Bluetooth, Screen.CONNECT),
    "SETTINGS" to HomeTileDef(R.string.nav_settings, Icons.Filled.Settings, Screen.SETTINGS),
)

/** Large tile; own photo if set (long-press = choose photo), otherwise a large faded glyph as artwork. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeTile(label: String, icon: ImageVector, image: String?, modifier: Modifier, height: Int, onLongClick: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(modifier.height(height.dp).clip(RoundedCornerShape(16.dp))
        .background(p.surface).border(1.dp, p.grid.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        .combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        if (image != null) {
            VehiclePhoto(image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        } else {
            Icon(icon, null, tint = p.textDim, modifier = Modifier.padding(16.dp).size(22.dp).align(Alignment.TopStart))
        }
        Text(label, style = titleStyle(20), color = if (image != null) Color.White else p.text, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = (if (image != null) Color.White else p.textDim).copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(16.dp))
    }
}
