package org.kineticos.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Entities keep the domain objects as JSON (kotlinx.serialization) to stay schema-stable while models evolve. */
@Entity(tableName = "vehicles") data class VehicleEntity(@PrimaryKey val id: String, val json: String, val updatedAt: Long)
@Entity(tableName = "ride_profiles") data class RideProfileEntity(@PrimaryKey val id: String, val vehicleId: String?, val json: String, val sort: Int)
@Entity(tableName = "backups") data class BackupEntity(@PrimaryKey(autoGenerate = true) val pk: Long = 0, val vescUuid: String, val takenAt: Long, val json: String)
@Entity(tableName = "rides", indices = [Index("vehicleId"), Index("startedAt")])
data class RideEntity(
    @PrimaryKey val id: String, val vehicleId: String?, val profileName: String?, val startedAt: Long, val endedAt: Long?,
    val simulated: Boolean, val summaryJson: String?, val finished: Boolean, val uploaded: Boolean = false,
)
@Entity(tableName = "samples", indices = [Index(value = ["rideId", "t"])])
data class SampleEntity(@PrimaryKey(autoGenerate = true) val pk: Long = 0, val rideId: String, val t: Long, val json: String)
@Entity(tableName = "tracks") data class TrackEntity(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "laps", indices = [Index("trackId")])
data class LapEntity(@PrimaryKey(autoGenerate = true) val pk: Long = 0, val trackId: String, val rideId: String?, val lapMs: Long, val sectorsJson: String, val valid: Boolean, val ts: Long, val gpsHz: Double, val fixesJson: String)
@Entity(tableName = "waypoints") data class WaypointEntity(@PrimaryKey(autoGenerate = true) val pk: Long = 0, val name: String, val lat: Double, val lon: Double, val ele: Double?)
@Entity(tableName = "routes") data class RouteEntity(@PrimaryKey val id: String, val name: String, val gpx: String, val importedAt: Long)
@Entity(tableName = "maintenance") data class MaintenanceEntity(@PrimaryKey(autoGenerate = true) val pk: Long = 0, val vehicleId: String, val name: String, val intervalKm: Double, val lastDoneKm: Double)

@Dao interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY updatedAt DESC") fun all(): Flow<List<VehicleEntity>>
    @Query("SELECT * FROM vehicles WHERE id = :id") suspend fun get(id: String): VehicleEntity?
    @Upsert suspend fun upsert(v: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun delete(id: String)
}
@Dao interface ProfileDao {
    @Query("SELECT * FROM ride_profiles ORDER BY sort, id") fun all(): Flow<List<RideProfileEntity>>
    @Query("SELECT COUNT(*) FROM ride_profiles") suspend fun count(): Int
    @Upsert suspend fun upsert(p: RideProfileEntity)
    @Query("DELETE FROM ride_profiles WHERE id = :id") suspend fun delete(id: String)
}
@Dao interface BackupDao {
    @Insert suspend fun insert(b: BackupEntity)
    /** Latest authoritative backup for the controller (see VehicleConnection.reconcileBackup). */
    @Query("SELECT * FROM backups WHERE vescUuid = :uuid ORDER BY takenAt DESC, pk DESC LIMIT 1") suspend fun first(uuid: String): BackupEntity?
    @Query("SELECT * FROM backups ORDER BY takenAt DESC") fun all(): Flow<List<BackupEntity>>
}
@Dao interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startedAt DESC") fun all(): Flow<List<RideEntity>>
    @Query("SELECT * FROM rides WHERE id = :id") suspend fun get(id: String): RideEntity?
    @Query("SELECT * FROM rides WHERE finished = 0 ORDER BY startedAt DESC LIMIT 1") suspend fun unfinished(): RideEntity?
    @Upsert suspend fun upsert(r: RideEntity)
    @Query("DELETE FROM rides WHERE id = :id") suspend fun delete(id: String)
    @Insert suspend fun insertSamples(s: List<SampleEntity>)
    @Query("SELECT * FROM samples WHERE rideId = :rideId ORDER BY t") suspend fun samples(rideId: String): List<SampleEntity>
    @Query("DELETE FROM samples WHERE rideId = :rideId") suspend fun deleteSamples(rideId: String)
    @Query("SELECT COUNT(*) FROM samples WHERE rideId = :rideId") suspend fun sampleCount(rideId: String): Int
}
@Dao interface TrackDao {
    @Query("SELECT * FROM tracks") fun all(): Flow<List<TrackEntity>>
    @Upsert suspend fun upsert(t: TrackEntity)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun delete(id: String)
    @Insert suspend fun insertLap(l: LapEntity)
    @Query("SELECT * FROM laps WHERE trackId = :trackId ORDER BY lapMs") fun laps(trackId: String): Flow<List<LapEntity>>
}
@Dao interface NavDao {
    @Query("SELECT * FROM waypoints") fun waypoints(): Flow<List<WaypointEntity>>
    @Insert suspend fun addWaypoint(w: WaypointEntity)
    @Query("DELETE FROM waypoints WHERE pk = :pk") suspend fun deleteWaypoint(pk: Long)
    @Query("SELECT * FROM routes ORDER BY importedAt DESC") fun routes(): Flow<List<RouteEntity>>
    @Upsert suspend fun upsertRoute(r: RouteEntity)
    @Query("DELETE FROM routes WHERE id = :id") suspend fun deleteRoute(id: String)
    @Query("SELECT * FROM maintenance WHERE vehicleId = :vid") fun maintenance(vid: String): Flow<List<MaintenanceEntity>>
    @Upsert suspend fun upsertMaintenance(m: MaintenanceEntity)
    @Query("DELETE FROM maintenance WHERE pk = :pk") suspend fun deleteMaintenance(pk: Long)
}

@Database(
    entities = [VehicleEntity::class, RideProfileEntity::class, BackupEntity::class, RideEntity::class, SampleEntity::class,
        TrackEntity::class, LapEntity::class, WaypointEntity::class, RouteEntity::class, MaintenanceEntity::class],
    version = 1, exportSchema = true,
)
abstract class KineticDb : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun profiles(): ProfileDao
    abstract fun backups(): BackupDao
    abstract fun rides(): RideDao
    abstract fun tracks(): TrackDao
    abstract fun nav(): NavDao
    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, KineticDb::class.java, "kinetic.db").build()
    }
}
