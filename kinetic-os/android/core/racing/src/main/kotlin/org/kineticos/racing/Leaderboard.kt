package org.kineticos.racing

data class LeaderboardEntry(
    val name: String, val lapMs: Long, val trackId: String? = null,
    val timestampMs: Long = 0, val valid: Boolean = true,
)

data class RankedEntry(val rank: Int, val entry: LeaderboardEntry, val gapToLeaderMs: Long)

object Leaderboard {
    /** Fastest first; ties broken by earlier timestamp then name. Invalid laps dropped unless [includeInvalid]. Equal times share a rank. */
    fun sort(entries: List<LeaderboardEntry>, trackId: String? = null, includeInvalid: Boolean = false): List<RankedEntry> {
        val sorted = entries
            .filter { (trackId == null || it.trackId == trackId) && (includeInvalid || it.valid) }
            .sortedWith(compareBy({ !it.valid }, { it.lapMs }, { it.timestampMs }, { it.name }))
        val leader = sorted.firstOrNull()?.lapMs ?: 0L
        var rank = 0
        return sorted.mapIndexed { i, e ->
            if (i == 0 || e.lapMs != sorted[i - 1].lapMs || e.valid != sorted[i - 1].valid) rank = i + 1
            RankedEntry(rank, e, e.lapMs - leader)
        }
    }

    fun formatLapTime(ms: Long): String {
        val m = ms / 60000; val s = (ms % 60000) / 1000; val r = ms % 1000
        return "%d:%02d.%03d".format(m, s, r)
    }
}
