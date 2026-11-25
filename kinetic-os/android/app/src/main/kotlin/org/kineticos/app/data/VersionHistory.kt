package org.kineticos.app.data

import android.content.Context

/** App and vehicle (VESC firmware / hardware) version history, shown in Settings → About. Stored locally only. */
object VersionHistory {
    data class Entry(val ts: Long, val app: String, val firmware: String?, val hardware: String?)
    private const val PREFS = "version_history"; private const val KEY = "entries"; private const val MAX = 30

    fun entries(ctx: Context): List<Entry> = ctx.getSharedPreferences(PREFS, 0).getString(KEY, "")!!.lines().mapNotNull { l ->
        l.split('|').takeIf { it.size == 4 }?.let { Entry(it[0].toLongOrNull() ?: return@mapNotNull null, it[1], it[2].ifBlank { null }, it[3].ifBlank { null }) }
    }

    /** Adds an entry only when something changed compared to the newest entry. Returns true if added. */
    fun record(ctx: Context, app: String, firmware: String?, hardware: String?, now: Long = System.currentTimeMillis()): Boolean {
        val all = entries(ctx)
        val last = all.lastOrNull()
        if (last != null && last.app == app && (firmware == null || (last.firmware == firmware && last.hardware == hardware))) return false
        val e = Entry(now, app, firmware ?: last?.firmware, hardware ?: last?.hardware)
        val clean = { s: String? -> s?.replace('|', '/')?.replace('\n', ' ') ?: "" }
        val txt = (all + e).takeLast(MAX).joinToString("\n") { "${it.ts}|${clean(it.app)}|${clean(it.firmware)}|${clean(it.hardware)}" }
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, txt).apply()
        return true
    }
}
