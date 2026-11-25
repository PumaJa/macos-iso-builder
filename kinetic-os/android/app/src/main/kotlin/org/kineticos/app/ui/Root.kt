package org.kineticos.app.ui

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kineticos.app.KeyAction
import org.kineticos.app.MainActivity
import org.kineticos.app.graph
import org.kineticos.app.ui.screens.*
import org.kineticos.app.ui.screens.LockedOverlay

enum class Screen(val label: String, val icon: ImageVector) {
    DASH("Dash", Icons.Filled.Speed), MAP("Map", Icons.Filled.Map), MODES("Modes", Icons.Filled.Tune),
    LIVE("Telemetry", Icons.Filled.MonitorHeart), RIDES("Rides", Icons.Filled.Timeline), GROUP("Group", Icons.Filled.Groups),
    RACE("Race", Icons.Filled.Flag), GARAGE("Garage", Icons.Filled.TwoWheeler), CONNECT("Connect", Icons.Filled.Bluetooth),
    REMOTE("Remote", Icons.Filled.Satellite), SETUP("VESC Setup", Icons.Filled.Build), SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun KineticRoot(activity: MainActivity) {
    val g = activity.graph
    val settings by g.settings.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.DASH) }
    var dimmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { g.toasts.collect { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(Unit) { activity.deepLinkJoin.collect { screen = Screen.GROUP } }
    LaunchedEffect(Unit) { activity.openScreen.collect { n -> Screen.entries.firstOrNull { it.name == n }?.let { screen = it } } }
    // Idle dimming (only while stationary): saves battery on a fixed cockpit phone
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        activity.keyActions.collect { a ->
            lastInteraction = System.currentTimeMillis(); dimmed = false
            val order = listOf(Screen.DASH, Screen.MAP, Screen.LIVE, Screen.MODES, Screen.RACE)
            when (a) {
                KeyAction.NEXT_SCREEN -> screen = order[(order.indexOf(screen).coerceAtLeast(0) + 1) % order.size]
                KeyAction.PREV_SCREEN -> screen = order[(order.indexOf(screen).coerceAtLeast(0) + order.size - 1) % order.size]
                KeyAction.MODE_UP, KeyAction.MODE_DOWN -> scope.launch {
                    val ps = g.profiles.value; val cur = ps.indexOfFirst { it.id == (g.appliedProfile.value?.id ?: settings.activeProfileId) }.coerceAtLeast(0)
                    val next = ps[(cur + if (a == KeyAction.MODE_UP) 1 else ps.size - 1) % ps.size]
                    g.toast(g.applyProfile(next))
                }
                KeyAction.LAP_MARK -> g.toast(if (g.recorder.active.value != null) "Marker" else "Not recording")
                KeyAction.WAKE -> {}
            }
        }
    }
    LaunchedEffect(settings.dimAfterIdleS) {
        while (true) {
            delay(5000)
            val moving = (g.connection.sample.value?.speedMps?.value ?: 0.0) > 1.0
            if (settings.dimAfterIdleS > 0 && !moving && System.currentTimeMillis() - lastInteraction > settings.dimAfterIdleS * 1000L) dimmed = true
            if (moving) dimmed = false
        }
    }
    LaunchedEffect(dimmed) {
        val lp = activity.window.attributes
        lp.screenBrightness = if (dimmed) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
    }

    val pip by activity.inPip.collectAsStateWithLifecycle()
    if (pip) { KineticTheme(settings.theme, false) { PipDash() }; return }
    KineticTheme(settings.theme, settings.gloveMode) {
        val p = LocalPalette.current
        val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        val status by g.connection.status.collectAsStateWithLifecycle()
        val simulated = (status as? org.kineticos.app.core.ConnStatus.Online)?.simulated == true
        Surface(color = p.bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                if (simulated) SimBanner()
                if (landscape) Row(Modifier.weight(1f)) {
                    NavigationRail(containerColor = p.surface) {
                        Screen.entries.forEach { s -> NavigationRailItem(selected = s == screen, onClick = { screen = s; lastInteraction = System.currentTimeMillis() },
                            icon = { Icon(s.icon, s.label) }, label = { Text(s.label, style = labelStyle(9)) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = K.Accent, indicatorColor = K.Accent.copy(alpha = 0.15f))) }
                    }
                    Box(Modifier.weight(1f)) { ScreenHost(screen, activity) { screen = it } }
                } else {
                    Box(Modifier.weight(1f)) { ScreenHost(screen, activity) { screen = it } }
                    val primary = listOf(Screen.DASH, Screen.MAP, Screen.MODES, Screen.LIVE)
                    var more by remember { mutableStateOf(false) }
                    NavigationBar(containerColor = p.surface) {
                        primary.forEach { s -> NavigationBarItem(selected = s == screen, onClick = { screen = s }, icon = { Icon(s.icon, s.label) }, label = { Text(s.label, style = labelStyle(9)) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = K.Accent, indicatorColor = K.Accent.copy(alpha = 0.15f))) }
                        NavigationBarItem(selected = screen !in primary, onClick = { more = true }, icon = { Icon(Icons.Filled.Apps, "More") }, label = { Text("More", style = labelStyle(9)) })
                    }
                    if (more) AlertDialog(onDismissRequest = { more = false }, confirmButton = {}, containerColor = p.surface, title = { Text("Menu") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Screen.entries.filter { it !in primary }.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { s -> BigButton(s.label, Modifier.weight(1f), selected = s == screen) { screen = s; more = false } } }
                            }
                        }
                    })
                }
            }
            LockedOverlay()
            if (dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)))
        }
    }
}

@Composable
private fun ScreenHost(screen: Screen, activity: MainActivity, go: (Screen) -> Unit) {
    AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
        when (s) {
            Screen.DASH -> DashScreen(go)
            Screen.MAP -> MapScreen()
            Screen.MODES -> ModesScreen()
            Screen.LIVE -> LiveScreen()
            Screen.RIDES -> RidesScreen()
            Screen.GROUP -> GroupScreen(activity)
            Screen.RACE -> RaceScreen()
            Screen.GARAGE -> GarageScreen()
            Screen.CONNECT -> ConnectScreen()
            Screen.REMOTE -> RemoteScreen()
            Screen.SETUP -> SetupScreen()
            Screen.SETTINGS -> SettingsScreen()
        }
    }
}

/** Compact cockpit for picture-in-picture: speed, power, battery, mode, warnings. */
@Composable
fun PipDash() {
    val g = androidx.compose.ui.platform.LocalContext.current.graph
    val p = LocalPalette.current
    val s by g.connection.sample.collectAsStateWithLifecycle()
    val applied by g.appliedProfile.collectAsStateWithLifecycle()
    val warn by g.warnings.history.collectAsStateWithLifecycle()
    val recent = warn.firstOrNull()?.takeIf { System.currentTimeMillis() - it.ts < 15_000 }
    Box(Modifier.fillMaxSize().background(if (recent?.level == org.kineticos.telemetry.WarnLevel.CRITICAL) K.Danger else p.bg).padding(6.dp)) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(s?.speedMps ?: org.kineticos.telemetry.Q.NA, 0, 3.6), style = numStyle(44), color = qColor((s?.speedMps ?: org.kineticos.telemetry.Q.NA).quality, p.text))
                Text(" km/h", style = labelStyle(9), color = p.textDim)
                Spacer(Modifier.weight(1f))
                Text(applied?.name ?: "STOCK", style = labelStyle(12), color = K.Accent)
            }
            Row {
                Text(fmt(s?.batteryPowerW ?: org.kineticos.telemetry.Q.NA, 1, 0.001) + " kW", style = labelStyle(12), color = K.Accent)
                Spacer(Modifier.weight(1f))
                Text(fmt(s?.socPct ?: org.kineticos.telemetry.Q.NA, 0) + "%", style = labelStyle(12), color = p.text)
            }
            recent?.let { Text(it.text, style = labelStyle(10), color = Color.White, maxLines = 1) }
            if (s?.simulated == true) Text("SIMULATION", style = labelStyle(9), color = K.Sim)
        }
    }
}
