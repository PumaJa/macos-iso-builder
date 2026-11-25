package org.kineticos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.kineticos.app.R
import org.kineticos.app.core.ConnStatus
import org.kineticos.app.graph
import org.kineticos.app.ui.*

/** Start hub: vehicle, battery, riding time and large tiles for the main areas. Own design. */
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // hero: vehicle photo with gradient, name, connection
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(p.surface)) {
            vehicle?.photoUri?.let { AsyncImage(it, vehicle?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Filled.TwoWheeler, null, Modifier.size(120.dp).align(Alignment.Center), tint = p.textDim)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, p.bg.copy(alpha = 0.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(vehicle?.name ?: stringResource(R.string.dash_no_vehicle), style = labelStyle(20).copy(fontWeight = FontWeight.Bold), color = p.text)
                val (txt, col) = when (val st = status) {
                    is ConnStatus.Online -> (if (st.simulated) "SIM" else "VESC") to (if (st.simulated) K.Sim else K.Regen)
                    is ConnStatus.Connecting, is ConnStatus.Reconnecting -> stringResource(R.string.home_connecting) to K.Warn
                    else -> stringResource(R.string.home_offline) to p.textDim
                }
                Pill(txt, col, Modifier.padding(top = 4.dp))
            }
        }
        // battery · mode · riding time
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val soc = s?.socPct
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_battery), style = labelStyle(10), color = p.textDim)
                Text((soc?.let { fmt(it, 0) } ?: "—") + " %", style = numStyle(30), color = soc?.let { qColor(it.quality, K.Regen) } ?: p.textDim)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_mode), style = labelStyle(10), color = p.textDim)
                Text(applied?.name ?: stringResource(R.string.mode_stock), style = numStyle(26, FontWeight.Medium), color = K.Accent)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.home_riding_time), style = labelStyle(10), color = p.textDim)
                Text("%dh %02dm".format(rideMs / 3_600_000, rideMs / 60_000 % 60), style = numStyle(30), color = p.text)
            }
        }
        val tiles = listOf(
            Triple(R.string.home_tile_cockpit, Icons.Filled.Speed, Screen.DASH), Triple(R.string.home_tile_stats, Icons.Filled.BarChart, Screen.STATS),
            Triple(R.string.home_tile_modes, Icons.Filled.Bolt, Screen.MODES), Triple(R.string.home_tile_trails, Icons.Filled.Place, Screen.MAP),
            Triple(R.string.home_tile_tracks, Icons.Filled.SportsScore, Screen.RACE), Triple(R.string.home_tile_rides, Icons.Filled.History, Screen.RIDES),
            Triple(R.string.home_tile_group, Icons.Filled.Groups, Screen.GROUP), Triple(R.string.home_tile_setup, Icons.Filled.Build, Screen.SETUP),
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, icon, target) -> HomeTile(stringResource(label), icon, Modifier.weight(1f)) { go(target) } }
            }
        }
    }
}

@Composable
private fun HomeTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.height(130.dp).clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.grid, RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(p.surface2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = K.Accent, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(label.uppercase(), style = labelStyle(12).copy(fontWeight = FontWeight.Bold), color = p.text)
    }
}
