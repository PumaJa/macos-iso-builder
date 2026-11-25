package org.kineticos.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { AUTO, DAY, NIGHT }
enum class SpeedUnit { KMH, MPH }
enum class PowerUnit { KW, PS, HP }

data class AppSettings(
    val activeVehicleId: String? = null,
    val activeProfileId: String = "sport",
    val autoConnect: Boolean = true,
    val lastTransport: String? = null,      // "ble:AA:BB..", "usb", "tcp:host:port", "sim"
    val autoStartOnBoot: Boolean = false,
    val autoRecord: Boolean = true,
    val keepScreenOn: Boolean = true,
    val dimAfterIdleS: Int = 120,
    val kiosk: Boolean = false,
    val theme: ThemeMode = ThemeMode.AUTO,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val powerUnit: PowerUnit = PowerUnit.KW,
    val gloveMode: Boolean = true,
    val pollHz: Int = 10,
    val serverUrl: String = "",
    val authToken: String? = null,
    val username: String? = null,
    val telematicsEnabled: Boolean = false,
    val shareTelemetryInGroups: Boolean = false,
    val mapStyleDay: String = "https://tiles.openfreemap.org/styles/liberty",
    val mapStyleNight: String = "https://tiles.openfreemap.org/styles/dark",
    val powerSaver: Boolean = false,
    val alertNotifications: Boolean = true,
)

class SettingsRepo(private val ctx: Context) {
    private object K {
        val vehicle = stringPreferencesKey("vehicle"); val profile = stringPreferencesKey("profile")
        val autoConnect = booleanPreferencesKey("autoConnect"); val lastTransport = stringPreferencesKey("lastTransport")
        val boot = booleanPreferencesKey("boot"); val autoRecord = booleanPreferencesKey("autoRecord")
        val screenOn = booleanPreferencesKey("screenOn"); val dim = intPreferencesKey("dim"); val kiosk = booleanPreferencesKey("kiosk")
        val theme = stringPreferencesKey("theme"); val speed = stringPreferencesKey("speedUnit"); val power = stringPreferencesKey("powerUnit")
        val glove = booleanPreferencesKey("glove"); val poll = intPreferencesKey("pollHz"); val server = stringPreferencesKey("server")
        val token = stringPreferencesKey("token"); val user = stringPreferencesKey("user"); val tele = booleanPreferencesKey("telematics")
        val share = booleanPreferencesKey("share"); val styleDay = stringPreferencesKey("styleDay"); val styleNight = stringPreferencesKey("styleNight")
        val saver = booleanPreferencesKey("saver"); val alerts = booleanPreferencesKey("alerts")
    }
    val flow: Flow<AppSettings> = ctx.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            p[K.vehicle], p[K.profile] ?: d.activeProfileId, p[K.autoConnect] ?: d.autoConnect, p[K.lastTransport],
            p[K.boot] ?: d.autoStartOnBoot, p[K.autoRecord] ?: d.autoRecord, p[K.screenOn] ?: d.keepScreenOn, p[K.dim] ?: d.dimAfterIdleS,
            p[K.kiosk] ?: d.kiosk, p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            p[K.speed]?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() } ?: d.speedUnit,
            p[K.power]?.let { runCatching { PowerUnit.valueOf(it) }.getOrNull() } ?: d.powerUnit,
            p[K.glove] ?: d.gloveMode, p[K.poll] ?: d.pollHz, p[K.server] ?: d.serverUrl, p[K.token]?.let { TokenCipher.decrypt(it) }, p[K.user],
            p[K.tele] ?: d.telematicsEnabled, p[K.share] ?: d.shareTelemetryInGroups,
            p[K.styleDay] ?: d.mapStyleDay, p[K.styleNight] ?: d.mapStyleNight, p[K.saver] ?: d.powerSaver, p[K.alerts] ?: d.alertNotifications,
        )
    }
    suspend fun current() = flow.first()
    suspend fun update(f: (AppSettings) -> AppSettings) {
        val n = f(current())
        ctx.store.edit { p ->
            fun <T> put(k: Preferences.Key<T>, v: T?) { if (v == null) p.remove(k) else p[k] = v }
            put(K.vehicle, n.activeVehicleId); put(K.profile, n.activeProfileId); put(K.autoConnect, n.autoConnect); put(K.lastTransport, n.lastTransport)
            put(K.boot, n.autoStartOnBoot); put(K.autoRecord, n.autoRecord); put(K.screenOn, n.keepScreenOn); put(K.dim, n.dimAfterIdleS)
            put(K.kiosk, n.kiosk); put(K.theme, n.theme.name); put(K.speed, n.speedUnit.name); put(K.power, n.powerUnit.name)
            put(K.glove, n.gloveMode); put(K.poll, n.pollHz.coerceIn(1, 50)); put(K.server, n.serverUrl.trim().trimEnd('/')); put(K.token, n.authToken?.let { TokenCipher.encrypt(it) })
            put(K.user, n.username); put(K.tele, n.telematicsEnabled); put(K.share, n.shareTelemetryInGroups)
            put(K.styleDay, n.mapStyleDay); put(K.styleNight, n.mapStyleNight); put(K.saver, n.powerSaver); put(K.alerts, n.alertNotifications)
        }
    }
}
