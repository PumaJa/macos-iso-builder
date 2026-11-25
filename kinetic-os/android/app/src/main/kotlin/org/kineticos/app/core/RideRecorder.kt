package org.kineticos.app.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import org.kineticos.app.data.*
import org.kineticos.export.RideSummary
import org.kineticos.telemetry.TelemetrySample
import java.util.UUID

/**
 * Records synchronised samples into Room in batches. Rides survive process death: an unfinished ride is resumed
 * (same id) when recording restarts within [resumeWindowMs]. Simulated rides are flagged.
 */
class RideRecorder(private val db: KineticDb, private val scope: CoroutineScope, private val resumeWindowMs: Long = 30 * 60_000) {
    private val _active = MutableStateFlow<RideEntity?>(null); val active: StateFlow<RideEntity?> = _active
    private val buffer = ArrayList<SampleEntity>()
    private val writeMutex = kotlinx.coroutines.sync.Mutex()
    private val inflight = ArrayList<Job>()
    private var lastSampleT = 0L
    private var minIntervalMs = 100L

    suspend fun start(vehicleId: String?, profile: String?, simulated: Boolean, hz: Int = 10) {
        if (_active.value != null) return
        minIntervalMs = 1000L / hz.coerceIn(1, 50)
        val u = db.rides().unfinished()
        val ride = if (u != null && System.currentTimeMillis() - (u.endedAt ?: u.startedAt) < resumeWindowMs && u.simulated == simulated) u
        else {
            u?.let { finishRide(it) }
            RideEntity(UUID.randomUUID().toString(), vehicleId, profile, System.currentTimeMillis(), null, simulated, null, false).also { db.rides().upsert(it) }
        }
        _active.value = ride
    }

    fun offer(s: TelemetrySample) {
        val r = _active.value ?: return
        if (s.simulated != r.simulated) return // never mix simulated and real data
        if (s.t - lastSampleT < minIntervalMs) return
        lastSampleT = s.t
        synchronized(buffer) { buffer += SampleEntity(rideId = r.id, t = s.t, json = AppJson.encodeToString(s)) }
        if (synchronized(buffer) { buffer.size } >= 50) {
            val j = scope.launch { flushBuffered() }
            synchronized(inflight) { inflight.removeAll { it.isCompleted }; inflight += j }
        }
    }

    /** Writes everything offered so far – waits for background batch writes, so callers see all samples. */
    suspend fun flush() {
        synchronized(inflight) { inflight.toList() }.forEach { it.join() }
        flushBuffered()
    }

    private suspend fun flushBuffered() = writeMutex.withLock {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isNotEmpty()) {
            db.rides().insertSamples(batch)
            _active.value?.let { db.rides().upsert(it.copy(endedAt = batch.last().t)) }
        }
    }

    suspend fun stop() {
        flush()
        _active.value?.let { finishRide(it) }
        _active.value = null
    }

    private suspend fun finishRide(r: RideEntity) {
        val samples = loadSamples(r.id)
        val summary = if (samples.isNotEmpty()) RideSummary.compute(samples) else null
        if (samples.isEmpty()) { db.rides().delete(r.id); return }
        db.rides().upsert(r.copy(finished = true, endedAt = samples.last().t, summaryJson = summary?.let { AppJson.encodeToString(it) }))
    }

    suspend fun loadSamples(rideId: String): List<TelemetrySample> =
        db.rides().samples(rideId).mapNotNull { runCatching { AppJson.decodeFromString<TelemetrySample>(it.json) }.getOrNull() }
}
