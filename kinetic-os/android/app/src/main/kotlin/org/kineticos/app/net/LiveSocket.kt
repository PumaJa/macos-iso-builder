package org.kineticos.app.net

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.kineticos.app.core.AppJson

sealed interface SocketState { data object Off : SocketState; data object Connecting : SocketState; data object Open : SocketState; data class Retry(val inMs: Long, val reason: String) : SocketState }

data class MemberPosition(val groupId: String, val userId: String, val displayName: String, val vehicleName: String?, val lat: Double, val lon: Double,
                          val speedKmh: Double?, val headingDeg: Double?, val ts: Long, val socPct: Double?, val powerW: Double?)

/** WebSocket to the self-hosted server with exponential-backoff reconnect and automatic re-join of groups. */
class LiveSocket(private val api: Api, private val baseUrl: () -> String, private val token: () -> String?, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<SocketState>(SocketState.Off); val state: StateFlow<SocketState> = _state
    private val _messages = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256); val messages: SharedFlow<JsonObject> = _messages
    private val _members = MutableStateFlow<Map<String, MemberPosition>>(emptyMap()); val members: StateFlow<Map<String, MemberPosition>> = _members
    private var ws: WebSocket? = null
    private var job: Job? = null
    private val joined = mutableSetOf<String>(); private val subscribed = mutableSetOf<String>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val tk = token(); val base = baseUrl()
                if (tk == null || base.isBlank()) { _state.value = SocketState.Off; delay(5000); continue }
                _state.value = SocketState.Connecting
                val closed = CompletableDeferred<String>()
                val wsUrl = base.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=" + tk
                ws = api.http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0; _state.value = SocketState.Open
                        synchronized(joined) { joined.forEach { sendRaw(webSocket, buildJsonObject { put("type", "join_group"); put("groupId", it) }) } }
                        synchronized(subscribed) { subscribed.forEach { sendRaw(webSocket, buildJsonObject { put("type", "subscribe_vehicle"); put("vehicleId", it) }) } }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val o = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        when (o["type"]?.jsonPrimitive?.contentOrNull) {
                            "ping" -> sendRaw(webSocket, buildJsonObject { put("type", "pong") })
                            "member_position" -> parseMember(o)?.let { m -> _members.update { it + ((m.groupId + ":" + m.userId) to m) } }
                            "presence" -> if (o["online"]?.jsonPrimitive?.booleanOrNull == false) {
                                val k = o["groupId"]?.jsonPrimitive?.content + ":" + o["userId"]?.jsonPrimitive?.content
                                _members.update { it - k }
                            }
                        }
                        _messages.tryEmit(o)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete("closed $code") }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(t.message ?: "failure ${response?.code}") }
                })
                val reason = closed.await()
                attempt++
                val wait = (1000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30_000)
                _state.value = SocketState.Retry(wait, reason)
                delay(wait)
            }
        }
    }
    fun stop() { job?.cancel(); ws?.close(1000, "bye"); ws = null; _state.value = SocketState.Off }

    private fun sendRaw(w: WebSocket, o: JsonObject) = w.send(AppJson.encodeToString(JsonObject.serializer(), o))
    fun send(o: JsonObject): Boolean = ws?.let { sendRaw(it, o) } ?: false

    fun joinGroup(id: String) { synchronized(joined) { joined += id }; send(buildJsonObject { put("type", "join_group"); put("groupId", id) }) }
    fun leaveGroup(id: String) { synchronized(joined) { joined -= id }; _members.update { m -> m.filterKeys { !it.startsWith("$id:") } }; send(buildJsonObject { put("type", "leave_group"); put("groupId", id) }) }
    fun joinedGroups(): Set<String> = synchronized(joined) { joined.toSet() }
    fun subscribeVehicle(id: String) { synchronized(subscribed) { subscribed += id }; send(buildJsonObject { put("type", "subscribe_vehicle"); put("vehicleId", id) }) }

    private fun parseMember(o: JsonObject): MemberPosition? = runCatching {
        fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        val tel = o["telemetry"] as? JsonObject
        MemberPosition(o["groupId"]!!.jsonPrimitive.content, o["userId"]!!.jsonPrimitive.content, o["displayName"]?.jsonPrimitive?.content ?: "?",
            o["vehicleName"]?.jsonPrimitive?.contentOrNull, d("lat")!!, d("lon")!!, d("speedKmh"), d("headingDeg"), o["ts"]?.jsonPrimitive?.longOrNull ?: 0,
            tel?.get("socPct")?.jsonPrimitive?.doubleOrNull, tel?.get("powerW")?.jsonPrimitive?.doubleOrNull)
    }.getOrNull()
}
