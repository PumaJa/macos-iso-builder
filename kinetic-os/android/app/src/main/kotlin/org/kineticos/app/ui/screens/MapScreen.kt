package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineticos.app.data.RouteEntity
import org.kineticos.app.data.WaypointEntity
import org.kineticos.app.graph
import org.kineticos.app.nav.RouteGuide
import org.kineticos.app.nav.RoutePoint
import org.kineticos.app.ui.*
import org.kineticos.export.GpxImporter
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.UUID

enum class MapTool { NONE, WAYPOINT, MEASURE, PLAN, NAVIGATE }

@Composable
fun MapScreen() {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val fix by g.gps.fix.collectAsStateWithLifecycle()
    val members by g.socket.members.collectAsStateWithLifecycle()
    val settings by g.settings.collectAsStateWithLifecycle()
    val waypoints by g.db.nav().waypoints().collectAsStateWithLifecycle(emptyList())
    val routes by g.db.nav().routes().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf<Style?>(null) }
    var follow by remember { mutableStateOf(true) }
    var headingUp by remember { mutableStateOf(true) }
    var tool by remember { mutableStateOf(MapTool.NONE) }
    val plan = remember { mutableStateListOf<RoutePoint>() }
    var activeRoute by remember { mutableStateOf<List<RoutePoint>?>(null) }
    val guide = remember(activeRoute) { activeRoute?.let { RouteGuide(it) } }
    val guidance = remember(fix, guide) { fix?.let { f -> guide?.update(f.lat, f.lon) } }
    var info by remember { mutableStateOf<String?>(null) }
    var turn by remember { mutableStateOf<org.kineticos.app.nav.TurnByTurn?>(null) }
    var destination by remember { mutableStateOf<RoutePoint?>(null) }
    var lastReroute by remember { mutableLongStateOf(0L) }
    val turnGuidance = remember(fix, turn) { fix?.let { f -> turn?.update(f.lat, f.lon) } }
    suspend fun requestRoute(to: RoutePoint) {
        val f = fix ?: run { g.toast(ctx.getString(R.string.grp_no_fix)); return }
        runCatching { org.kineticos.app.nav.OsrmParser.parse(g.api.route(listOf(f.lat to f.lon, to.lat to to.lon))) }
            .onSuccess { r -> turn = org.kineticos.app.nav.TurnByTurn(r); activeRoute = r.points; destination = to
                info = "%.1f km · %.0f min".format(r.distanceM / 1000, r.durationS / 60) + r.warnings.joinToString("") { "\n⚠ $it" } }
            .onFailure { e -> g.toast(when (e) { is org.kineticos.app.net.ApiException -> if (e.code == 503) ctx.getString(R.string.map_routing_503) else e.message ?: ctx.getString(R.string.map_routing_failed); else -> e.message ?: ctx.getString(R.string.map_routing_failed) }) }
    }
    // automatic re-routing when off route (at most every 20 s)
    LaunchedEffect(turnGuidance?.offRoute) {
        val d = destination
        if (turnGuidance?.offRoute == true && d != null && System.currentTimeMillis() - lastReroute > 20_000) { lastReroute = System.currentTimeMillis(); requestRoute(d) }
        if (turnGuidance?.arrived == true) { g.toast(ctx.getString(R.string.map_arrived)); turn = null; destination = null }
    }
    val night = p.night
    val trail = remember { mutableStateListOf<RoutePoint>() }
    LaunchedEffect(fix) { fix?.let { trail += RoutePoint(it.lat, it.lon); if (trail.size > 3000) trail.removeAt(0) } }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
                val gpx = GpxImporter.parse(text)
                val pts = (gpx.tracks.flatten() + gpx.routes.flatten()).map { RoutePoint(it.lat, it.lon, it.ele) }
                gpx.waypoints.forEach { g.db.nav().addWaypoint(WaypointEntity(name = it.name ?: "WPT", lat = it.lat, lon = it.lon, ele = it.ele)) }
                if (pts.size >= 2) { g.db.nav().upsertRoute(RouteEntity(UUID.randomUUID().toString(), uri.lastPathSegment ?: "Route", text, System.currentTimeMillis())); activeRoute = pts }
                ctx.getString(R.string.map_imported, pts.size, gpx.waypoints.size)
            }.onSuccess { g.toast(it) }.onFailure { g.toast(ctx.getString(R.string.map_gpx_failed, it.message)) }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { (ctx.applicationContext as org.kineticos.app.KineticApp).ensureMap(); MapView(ctx).apply { onCreate(null) } }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_START -> mapView.onStart(); Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause(); Lifecycle.Event.ON_STOP -> mapView.onStop(); else -> {} }
        }
        lifecycle.addObserver(obs); mapView.onStart(); mapView.onResume()
        onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    LaunchedEffect(mapView, night) {
        mapView.getMapAsync { m ->
            map = m
            mapView.addOnDidFailLoadingMapListener { err -> info = ctx.getString(R.string.map_unavailable, err.toString()) }
            m.uiSettings.isCompassEnabled = true; m.uiSettings.isAttributionEnabled = true; m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(if (night) settings.mapStyleNight else settings.mapStyleDay)) { st ->
                fun src(id: String) = st.addSource(GeoJsonSource(id))
                listOf("trail", "route", "plan", "wpts", "members", "me").forEach { src(it) }
                st.addLayer(LineLayer("route-l", "route").withProperties(lineColor("#E0E0E0"), lineWidth(5f), lineOpacity(0.85f)))
                st.addLayer(LineLayer("plan-l", "plan").withProperties(lineColor("#9E9E9E"), lineWidth(4f), lineDasharray(arrayOf(2f, 1f))))
                st.addLayer(LineLayer("trail-l", "trail").withProperties(lineColor("#FFFFFF"), lineWidth(4f)))
                st.addLayer(CircleLayer("wpts-c", "wpts").withProperties(circleColor("#9E9E9E"), circleRadius(7f), circleStrokeColor("#000000"), circleStrokeWidth(2f)))
                st.addLayer(CircleLayer("members-c", "members").withProperties(circleColor("#D0D0D0"), circleRadius(9f), circleStrokeColor("#000000"), circleStrokeWidth(2f)))
                st.addLayer(SymbolLayer("members-t", "members").withProperties(textField("{name}"), textOffset(arrayOf(0f, 1.6f)), textSize(13f), textColor(if (night) "#FFFFFF" else "#000000"), textHaloColor(if (night) "#000000" else "#FFFFFF"), textHaloWidth(1.5f)))
                st.addLayer(CircleLayer("me-c", "me").withProperties(circleColor("#FFFFFF"), circleRadius(10f), circleStrokeColor("#000000"), circleStrokeWidth(3f)))
                styleReady = st
            }
            m.addOnMapClickListener { ll ->
                when (tool) {
                    MapTool.WAYPOINT -> { scope.launch { g.db.nav().addWaypoint(WaypointEntity(name = "WP ${waypoints.size + 1}", lat = ll.latitude, lon = ll.longitude, ele = null)) }; true }
                    MapTool.MEASURE, MapTool.PLAN -> { plan += RoutePoint(ll.latitude, ll.longitude); info = "%.2f km".format(RouteGuide.polylineLengthM(plan) / 1000); true }
                    MapTool.NAVIGATE -> { tool = MapTool.NONE; scope.launch { requestRoute(RoutePoint(ll.latitude, ll.longitude)) }; true }
                    MapTool.NONE -> { follow = false; false }
                }
            }
        }
    }
    // update sources
    LaunchedEffect(styleReady, fix, members, waypoints, activeRoute, plan.size, trail.size) {
        val st = styleReady ?: return@LaunchedEffect
        fun line(pts: List<RoutePoint>) = if (pts.size < 2) FeatureCollection.fromFeatures(emptyList()) else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })))
        st.getSourceAs<GeoJsonSource>("trail")?.setGeoJson(line(trail.toList()))
        st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(line(activeRoute ?: emptyList()))
        st.getSourceAs<GeoJsonSource>("plan")?.setGeoJson(line(plan.toList()))
        st.getSourceAs<GeoJsonSource>("wpts")?.setGeoJson(FeatureCollection.fromFeatures(waypoints.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("name", it.name) } }))
        st.getSourceAs<GeoJsonSource>("members")?.setGeoJson(FeatureCollection.fromFeatures(members.values.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("name", it.displayName + (it.vehicleName?.let { v -> " · $v" } ?: "") + (it.speedKmh?.let { s -> " %.0f".format(s) } ?: "")) }
        }))
        fix?.let { f -> st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat))) }
        val m = map
        if (m != null && follow && fix != null) {
            val f = fix!!
            m.cameraPosition = CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(m.cameraPosition.zoom.coerceAtLeast(14.0))
                .bearing(if (headingUp) f.bearingDeg ?: m.cameraPosition.bearing else 0.0).build()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            turnGuidance?.let { tg ->
                Card {
                    Text(if (tg.offRoute) stringResource(R.string.map_off_route_recalc) else "%s  ·  %s".format(if (tg.distanceToTurnM < 1000) "%.0f m".format(tg.distanceToTurnM) else "%.1f km".format(tg.distanceToTurnM / 1000), tg.next.instruction),
                        style = labelStyle(16), color = if (tg.offRoute) K.Danger else K.Accent)
                    tg.then?.let { Text(stringResource(R.string.map_then, it.instruction), style = labelStyle(11), color = p.textDim) }
                    Text(stringResource(R.string.map_remaining, tg.remainingM / 1000), style = labelStyle(11), color = p.textDim)
                }
            }
            if (turnGuidance == null) guidance?.let { gd ->
                Card {
                    Text(if (gd.offRoute) stringResource(R.string.map_off_route, gd.distanceToRouteM) else stringResource(R.string.map_next, gd.distanceToNextM, gd.bearingToNextDeg), style = labelStyle(14), color = if (gd.offRoute) K.Danger else K.Accent)
                    Text(stringResource(R.string.map_remaining_progress, gd.remainingM / 1000, gd.progressFrac * 100), style = labelStyle(11), color = p.textDim)
                }
            }
            info?.let { Card(Modifier.padding(top = 6.dp)) { Text(it, style = labelStyle(13), color = p.text) } }
            activeRoute?.let { r -> RouteGuide(r).elevationProfile().takeIf { it.size > 2 }?.let { prof ->
                Card(Modifier.padding(top = 6.dp).width(220.dp).height(90.dp)) { Text(stringResource(R.string.map_elevation), style = labelStyle(9), color = p.textDim); LineChart(listOf(K.Warn to prof.map { it.second.toFloat() }), Modifier.fillMaxSize()) }
            } }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg.copy(alpha = 0.7f)).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(stringResource(if (follow) R.string.map_follow_on else R.string.map_follow), selected = follow) { follow = !follow }
            BigButton(stringResource(if (headingUp) R.string.map_heading_up else R.string.map_north_up), selected = headingUp) { headingUp = !headingUp }
            BigButton(stringResource(if (turn != null) R.string.map_stop_nav else R.string.map_navigate), selected = tool == MapTool.NAVIGATE || turn != null) {
                if (turn != null) { turn = null; destination = null; activeRoute = null; info = null }
                else { tool = MapTool.NAVIGATE; g.toast(ctx.getString(R.string.map_tap_dest)) }
            }
            BigButton(stringResource(R.string.map_gpx_import)) { gpxPicker.launch("*/*") }
            BigButton(stringResource(R.string.map_waypoint), selected = tool == MapTool.WAYPOINT) { tool = if (tool == MapTool.WAYPOINT) MapTool.NONE else MapTool.WAYPOINT }
            BigButton(stringResource(R.string.map_measure), selected = tool == MapTool.MEASURE) { if (tool == MapTool.MEASURE) { tool = MapTool.NONE; plan.clear(); info = null } else { plan.clear(); tool = MapTool.MEASURE } }
            BigButton(stringResource(R.string.map_plan), selected = tool == MapTool.PLAN) {
                if (tool == MapTool.PLAN) { tool = MapTool.NONE; if (plan.size >= 2) { activeRoute = plan.toList(); scope.launch {
                    val gpx = org.kineticos.app.nav.Gpx.fromPoints("Planned route", plan.toList())
                    g.db.nav().upsertRoute(RouteEntity(UUID.randomUUID().toString(), ctx.getString(R.string.map_planned, java.text.SimpleDateFormat("dd.MM HH:mm").format(java.util.Date())), gpx, System.currentTimeMillis()))
                    g.toast(ctx.getString(R.string.map_route_saved)) } }; plan.clear(); info = null }
                else { plan.clear(); tool = MapTool.PLAN }
            }
            if (activeRoute != null && turn == null) BigButton(stringResource(R.string.map_end_route), color = K.Danger) { activeRoute = null }
            routes.take(5).forEach { r -> BigButton(r.name.take(14)) {
                runCatching { GpxImporter.parse(r.gpx) }.getOrNull()?.let { gd -> activeRoute = (gd.tracks.flatten() + gd.routes.flatten()).map { RoutePoint(it.lat, it.lon, it.ele) } }
            } }
            BigButton(stringResource(R.string.map_offline_area)) {
                val m = map ?: return@BigButton
                val b: LatLngBounds = m.projection.visibleRegion.latLngBounds
                val def = OfflineTilePyramidRegionDefinition(if (night) settings.mapStyleNight else settings.mapStyleDay, b, m.cameraPosition.zoom.coerceAtMost(12.0), 16.0, ctx.resources.displayMetrics.density)
                OfflineManager.getInstance(ctx).createOfflineRegion(def, "area".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) { if (status.isComplete) { g.toast(ctx.getString(R.string.map_offline_ready, status.completedResourceCount)); offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) } }
                            override fun onError(error: OfflineRegionError) { g.toast(ctx.getString(R.string.map_offline_dl_err, error.message)) }
                            override fun mapboxTileCountLimitExceeded(limit: Long) { g.toast(ctx.getString(R.string.map_too_large, limit)) }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE); g.toast(ctx.getString(R.string.map_downloading))
                    }
                    override fun onError(error: String) { g.toast(ctx.getString(R.string.map_offline_err, error)) }
                })
            }
        }
    }
}
