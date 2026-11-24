package org.kineticos.app

import android.app.Application
import org.maplibre.android.MapLibre

class KineticApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        graph = AppGraph(this)
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as KineticApp).graph
