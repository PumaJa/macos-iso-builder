package org.kineticos.app

import android.app.Application
import org.maplibre.android.MapLibre

class KineticApp : Application() {
    /** Created on first use (keeps Application.onCreate cheap → faster cold start on old devices). */
    val graph: AppGraph by lazy { AppGraph(this) }
    private var mapInit = false
    /** MapLibre native engine is initialised lazily by the map screens. */
    fun ensureMap() { if (!mapInit) { runCatching { MapLibre.getInstance(this) }; mapInit = true } }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as KineticApp).graph
