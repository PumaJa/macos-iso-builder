package org.kineticos.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.kineticos.app.core.AppJson
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val error: String, message: String) : Exception(message)

@Serializable data class User(val id: String, val username: String, val displayName: String, val createdAt: Long? = null)
@Serializable data class AuthResponse(val token: String, val user: User)
@Serializable data class Member(val userId: String, val displayName: String, val role: String = "member", val online: Boolean = false)
@Serializable data class Group(val id: String, val name: String, val code: String, val visibility: String, val ownerId: String, val createdAt: Long? = null, val members: List<Member>? = null)
@Serializable data class Friend(val id: String, val username: String, val displayName: String, val online: Boolean = false, val lastSeen: Long? = null)
@Serializable data class FriendRequest(val id: String, val from: User? = null, val to: User? = null, val status: String)
@Serializable data class Invitation(val id: String, val group: Group, val from: User? = null)
@Serializable data class VehicleState(
    val ts: Long, val lat: Double? = null, val lon: Double? = null, val alt: Double? = null, val speedKmh: Double? = null,
    val headingDeg: Double? = null, val socPct: Double? = null, val voltage: Double? = null, val powerW: Double? = null,
    val tempMosC: Double? = null, val tempMotorC: Double? = null, val odometerKm: Double? = null, val faultCode: Int? = null,
    val vescConnected: Boolean, val charging: Boolean? = null, val source: String,
    val locked: Boolean? = null, val tamper: Boolean? = null,
)
@Serializable data class Alert(val id: String, val ts: Long, val kind: String, val message: String)
@Serializable data class Geofence(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Double, val alertOn: String)
@Serializable data class RemoteState(val state: VehicleState? = null, val receivedAt: Long? = null, val online: Boolean = false)

/** REST client for the self-hosted Kinetic OS server (see API_DOCUMENTATION.md). */
class Api(private val baseUrl: () -> String, private val token: () -> String?) {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String): String {
        val b = baseUrl().trimEnd('/')
        require(b.startsWith("https://") || b.startsWith("http://")) { "Server URL not configured" }
        return "$b/api/v1$path"
    }

    suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement? = withContext(Dispatchers.IO) {
        val rb = body?.let { AppJson.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) }
        val req = Request.Builder().url(url(path)).method(method, rb ?: if (method in listOf("POST", "PUT")) "{}".toRequestBody(jsonType) else null)
            .apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val e = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
                throw ApiException(r.code, e?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${r.code}", e?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}")
            }
            if (text.isBlank()) null else AppJson.parseToJsonElement(text)
        }
    }
    private inline fun <reified T> JsonElement?.to(): T = AppJson.decodeFromJsonElement(this ?: JsonNull)
    private fun obj(vararg p: Pair<String, Any?>) = buildJsonObject {
        p.forEach { (k, v) -> when (v) { null -> {}; is String -> put(k, v); is Number -> put(k, v); is Boolean -> put(k, v); is JsonElement -> put(k, v); else -> put(k, v.toString()) } }
    }

    suspend fun health() = call("GET", "/health")
    suspend fun register(u: String, p: String, d: String): AuthResponse = call("POST", "/auth/register", obj("username" to u, "password" to p, "displayName" to d)).to()
    suspend fun login(u: String, p: String): AuthResponse = call("POST", "/auth/login", obj("username" to u, "password" to p)).to()
    suspend fun me(): User = call("GET", "/me").to()
    suspend fun friends(): List<Friend> = call("GET", "/friends").to()
    suspend fun requestFriend(username: String) { call("POST", "/friends/requests", obj("username" to username)) }
    suspend fun friendRequests(): List<FriendRequest> = call("GET", "/friends/requests").to()
    suspend fun acceptFriend(id: String) { call("POST", "/friends/requests/$id/accept") }
    suspend fun removeFriend(userId: String) { call("DELETE", "/friends/$userId") }
    suspend fun groups(): List<Group> = call("GET", "/groups").to()
    suspend fun publicGroups(): List<Group> = call("GET", "/groups/public").to()
    suspend fun createGroup(name: String, public: Boolean): Group = call("POST", "/groups", obj("name" to name, "visibility" to if (public) "public" else "private")).to()
    suspend fun joinGroup(code: String): Group = call("POST", "/groups/join", obj("code" to code.trim().uppercase())).to()
    suspend fun group(id: String): Group = call("GET", "/groups/$id").to()
    suspend fun invite(groupId: String, username: String) { call("POST", "/groups/$groupId/invite", obj("username" to username)) }
    suspend fun invitations(): List<Invitation> = call("GET", "/invitations").to()
    suspend fun acceptInvitation(id: String): Group = call("POST", "/invitations/$id/accept").to()
    suspend fun leaveGroup(id: String) { call("DELETE", "/groups/$id/members/me") }
    suspend fun deleteGroup(id: String) { call("DELETE", "/groups/$id") }
    suspend fun setGroupRoute(id: String, name: String, gpx: String) { call("PUT", "/groups/$id/route", obj("name" to name, "gpx" to gpx)) }
    suspend fun groupRoute(id: String): JsonObject? = runCatching { call("GET", "/groups/$id/route")?.jsonObject }.getOrNull()
    suspend fun putVehicle(id: String, fields: JsonObject) { call("PUT", "/vehicles/$id", fields) }
    suspend fun uploadPhoto(id: String, file: File) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType())).build()
        val req = Request.Builder().url(url("/vehicles/$id/photo")).post(body).apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw ApiException(it.code, "upload", "Photo upload failed (${it.code})") }
    }
    suspend fun pushStates(vehicleId: String, states: List<VehicleState>) {
        call("POST", "/telematics/$vehicleId/state", buildJsonObject { put("states", AppJson.encodeToJsonElement(states)) })
    }
    suspend fun remoteState(vehicleId: String): RemoteState = call("GET", "/telematics/$vehicleId/state").to()
    suspend fun alerts(vehicleId: String, since: Long? = null): List<Alert> = call("GET", "/telematics/$vehicleId/alerts" + (since?.takeIf { it > 0 }?.let { "?since=$it" } ?: "")).to()
    suspend fun geofences(vehicleId: String): List<Geofence> = call("GET", "/telematics/$vehicleId/geofences").to()
    suspend fun addGeofence(vehicleId: String, name: String, lat: Double, lon: Double, r: Double, on: String) {
        call("POST", "/telematics/$vehicleId/geofences", obj("name" to name, "lat" to lat, "lon" to lon, "radiusM" to r, "alertOn" to on))
    }
    suspend fun deleteGeofence(vehicleId: String, gid: String) { call("DELETE", "/telematics/$vehicleId/geofences/$gid") }
    suspend fun telematicsSettings(vehicleId: String, lowBatteryPct: Int, maintenanceKm: Int, moveAlert: Boolean) {
        call("PUT", "/telematics/$vehicleId/settings", obj("lowBatteryPct" to lowBatteryPct, "maintenanceKm" to maintenanceKm, "moveAlert" to moveAlert))
    }
    /** Road route via the server's self-hosted OSRM (GET /route). Points as (lat, lon). */
    suspend fun route(points: List<Pair<Double, Double>>): JsonObject {
        require(points.size in 2..25) { "2–25 points" }
        val coords = points.joinToString(";") { (lat, lon) -> "%.6f,%.6f".format(java.util.Locale.ROOT, lon, lat) }
        return call("GET", "/route?coords=" + java.net.URLEncoder.encode(coords, "UTF-8") + "&profile=driving")!!.jsonObject
    }

    suspend fun uploadRide(body: JsonObject) { call("POST", "/rides", body) }
    suspend fun leaderboard(trackId: String): JsonArray = call("GET", "/tracks/$trackId/leaderboard")?.jsonArray ?: JsonArray(emptyList())
    suspend fun postLap(trackId: String, lapMs: Long, sectors: List<Long>, vehicleId: String, gpsHz: Double) {
        call("POST", "/tracks/$trackId/laps", buildJsonObject {
            put("lapMs", lapMs); put("sectorsMs", JsonArray(sectors.map { JsonPrimitive(it) })); put("vehicleId", vehicleId); put("gpsHz", gpsHz); put("ts", System.currentTimeMillis())
        })
    }
}
