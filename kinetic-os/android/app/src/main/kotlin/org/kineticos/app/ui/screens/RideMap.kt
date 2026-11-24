package org.kineticos.app.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.kineticos.app.graph
import org.kineticos.app.ui.LocalPalette
import org.kineticos.telemetry.TelemetrySample
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Small map showing a recorded ride's track and the replay cursor. */
@Composable
fun RideMap(samples: List<TelemetrySample>, cursor: Float, modifier: Modifier) {
    val ctx = LocalContext.current; val g = ctx.graph; val night = LocalPalette.current.night
    val pts = remember(samples) { samples.mapNotNull { it.gps } }
    if (pts.size < 2) return
    var style by remember { mutableStateOf<Style?>(null) }
    val mv = remember { (ctx.applicationContext as org.kineticos.app.KineticApp).ensureMap(); MapView(ctx).apply { onCreate(null); onStart(); onResume() } }
    DisposableEffect(Unit) { onDispose { mv.onPause(); mv.onStop(); mv.onDestroy() } }
    LaunchedEffect(Unit) {
        mv.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(if (night) g.settings.value.mapStyleNight else g.settings.value.mapStyleDay)) { st ->
                st.addSource(GeoJsonSource("track", Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))))
                st.addSource(GeoJsonSource("cursor"))
                st.addLayer(LineLayer("track-l", "track").withProperties(lineColor("#00E0FF"), lineWidth(4f)))
                st.addLayer(CircleLayer("cursor-c", "cursor").withProperties(circleColor("#FFB020"), circleRadius(8f), circleStrokeColor("#000000"), circleStrokeWidth(2f)))
                val b = LatLngBounds.Builder(); pts.forEach { b.include(LatLng(it.lat, it.lon)) }
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 40)) }
                style = st
            }
        }
    }
    LaunchedEffect(style, cursor) {
        val s = samples.getOrNull((cursor * (samples.size - 1)).toInt())?.gps ?: return@LaunchedEffect
        style?.getSourceAs<GeoJsonSource>("cursor")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)))
    }
    AndroidView({ mv }, modifier)
}
