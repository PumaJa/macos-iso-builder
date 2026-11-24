package org.kineticos.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.kineticos.app.net.*
import org.kineticos.telemetry.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NetTest {
    @Test fun loginSendsJsonAndParses() = runBlocking {
        val srv = MockWebServer(); srv.start()
        srv.enqueue(MockResponse().setBody("""{"token":"T1","user":{"id":"u1","username":"jan","displayName":"Jan","createdAt":1}}"""))
        srv.enqueue(MockResponse().setBody("""[{"id":"g","name":"Ride","code":"ABC123","visibility":"private","ownerId":"u1"}]"""))
        var token: String? = null
        val api = Api({ srv.url("/").toString() }, { token })
        val r = api.login("jan", "secret123"); token = r.token
        assertEquals("T1", r.token)
        val req = srv.takeRequest(); assertEquals("/api/v1/auth/login", req.path); assertTrue(req.body.readUtf8().contains("\"username\":\"jan\""))
        assertEquals("ABC123", api.groups().single().code)
        assertEquals("Bearer T1", srv.takeRequest().getHeader("Authorization"))
        srv.shutdown()
    }

    @Test fun errorsMappedToApiException() = runBlocking {
        val srv = MockWebServer(); srv.start()
        srv.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden","message":"Not a member"}"""))
        val e = assertFailsWith<ApiException> { Api({ srv.url("/").toString() }, { "x" }).group("g1") }
        assertEquals(403, e.code); assertEquals("forbidden", e.error); assertEquals("Not a member", e.message)
        srv.shutdown()
    }

    @Test fun refusesUnconfiguredServer() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { Api({ "" }, { null }).health() }
    }

    @Test fun websocketJoinsGroupsParsesMembersAnswersPingAndReconnects() = runBlocking<Unit> {
        val srv = MockWebServer(); srv.start()
        val fromClient = LinkedBlockingQueue<String>()
        val serverSockets = LinkedBlockingQueue<WebSocket>()
        repeat(2) {
            srv.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.put(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { fromClient.put(text) }
            }))
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val api = Api({ srv.url("/").toString() }, { "TOKEN" })
        val ls = LiveSocket(api, { srv.url("/").toString() }, { "TOKEN" }, scope)
        ls.joinGroup("g1"); ls.start()
        val ws = serverSockets.poll(10, TimeUnit.SECONDS)!!
        assertTrue(srv.takeRequest().path!!.startsWith("/ws?token=TOKEN"))
        assertTrue(fromClient.poll(5, TimeUnit.SECONDS)!!.contains("join_group"))
        ws.send("""{"type":"ping"}"""); assertTrue(fromClient.poll(5, TimeUnit.SECONDS)!!.contains("pong"))
        ws.send("""{"type":"member_position","groupId":"g1","userId":"u2","displayName":"Ana","vehicleName":"Kart","lat":47.1,"lon":8.2,"speedKmh":30,"ts":5,"telemetry":{"socPct":77}}""")
        val m = withTimeout(5000) { ls.members.filter { it.isNotEmpty() }.first() }.values.single()
        assertEquals("Ana", m.displayName); assertEquals(77.0, m.socPct)
        ws.close(1001, "server restart")
        // reconnect → group is re-joined automatically
        assertNotNull(serverSockets.poll(15, TimeUnit.SECONDS))
        assertTrue(fromClient.poll(5, TimeUnit.SECONDS)!!.contains("join_group"))
        ls.stop(); scope.cancel(); runCatching { srv.shutdown() } // MockWebServer may time out draining the ws queue
    }

    @Test fun telematicsNeverInventsValues() {
        val st = TelematicsMapper.toState(null, vescConnected = false, odometerKm = null, simulated = false)
        assertNull(st.socPct); assertNull(st.speedKmh); assertNull(st.lat); assertFalse(st.vescConnected); assertEquals("measured", st.source)
        val s = TelemetrySample(t = 1, simulated = true, socPct = Q(50.0, Quality.SIMULATED), gps = GpsFix(1.0, 2.0, null, null, 90.0, 3.0, 1))
        val st2 = TelematicsMapper.toState(s, true, null, simulated = true)
        assertEquals("simulated", st2.source); assertEquals(50.0, st2.socPct); assertEquals(90.0, st2.headingDeg)
    }
}
