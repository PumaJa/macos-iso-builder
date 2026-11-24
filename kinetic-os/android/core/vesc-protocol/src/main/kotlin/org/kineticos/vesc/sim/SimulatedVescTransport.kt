package org.kineticos.vesc.sim

import kotlinx.coroutines.flow.*
import org.kineticos.vesc.codec.*
import org.kineticos.vesc.model.CommPacketId
import org.kineticos.vesc.transport.*
import kotlin.math.*

/**
 * SIMULATION ONLY. A virtual VESC speaking the real wire protocol, for UI development and automated tests.
 * Every consumer must label data from this transport as simulated (TransportKind.SIMULATION).
 * The physics are a crude toy model – never use the numbers for anything real.
 */
class SimulatedVescTransport(
    private val clock: () -> Long = System::currentTimeMillis,
    val canIds: List<Int> = listOf(1),
    var dropEveryNth: Int = 0,          // fault injection: drop replies
    var corruptEveryNth: Int = 0,       // fault injection: flip a CRC byte
    var fragmentSize: Int = 20,         // emulate BLE MTU fragmentation
    val fwMajor: Int = 6, val fwMinor: Int = 6,
) : VescTransport {
    override val kind = TransportKind.SIMULATION
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming: Flow<ByteArray> = _incoming
    override val maxChunk = 20
    private val decoder = PacketDecoder()
    private var replyCount = 0
    private val t0 = clock()

    // toy state
    var throttle = 0.0  // 0..1 driven by the simulator loop / tests
    private var erpm = 0.0; private var ah = 0.0; private var wh = 0.0; private var whRegen = 0.0; private var tach = 0
    private var tempFet = 25.0; private var tempMotor = 25.0; private var lastStep = clock()
    private var maxScale = 1.0; private var wattMax = 15000.0; private var maxErpm = 50000.0
    val polePairs = 7; val gear = 8.0; val wheelD = 0.48
    val writesReceived = mutableListOf<Int>()

    override suspend fun connect() { _state.value = LinkState.Connecting; _state.value = LinkState.Connected("SIMULATED VESC") }
    override suspend fun disconnect() { _state.value = LinkState.Disconnected }

    fun step() {
        val now = clock(); val dt = ((now - lastStep).coerceIn(0, 1000)) / 1000.0; lastStep = now
        val time = (now - t0) / 1000.0
        if (throttle == 0.0) throttle = (0.5 + 0.5 * sin(time / 6.0)).coerceIn(0.0, 1.0) * 0.8
        val target = throttle * maxScale * maxErpm * 0.8
        erpm += (target - erpm) * min(1.0, dt * 0.8)
        val power = motorCurrent() * voltage()
        if (power >= 0) wh += power * dt / 3600 else whRegen += -power * dt / 3600
        ah += inputCurrent() * dt / 3600
        tach += (erpm / 60 * dt * 6).toInt()
        tempFet += (25 + abs(power) / 300 - tempFet) * dt * 0.02
        tempMotor += (25 + abs(power) / 200 - tempMotor) * dt * 0.01
    }
    private fun voltage() = 84.0 - ah * 0.4
    private fun motorCurrent() = (throttle * 120 * maxScale - erpm / 2000).coerceIn(-40.0, 150.0)
    private fun inputCurrent() = (motorCurrent() * (erpm / maxErpm).coerceIn(0.0, 1.0)).coerceAtMost(wattMax / voltage())

    override suspend fun write(bytes: ByteArray) {
        for (req in decoder.feed(bytes)) handle(req)
    }

    private suspend fun handle(req: ByteArray, viaCan: Int? = null) {
        val id = req[0].toInt() and 0xFF
        val w = VescWriter(128)
        step()
        when (CommPacketId.of(id)) {
            CommPacketId.FORWARD_CAN -> {
                val cid = req[1].toInt() and 0xFF
                if (cid in canIds) handle(req.copyOfRange(2, req.size), cid)
                return
            }
            CommPacketId.FW_VERSION -> {
                w.u8(id).u8(fwMajor).u8(fwMinor).bytes("SIM_VESC\u0000".toByteArray()).bytes(ByteArray(12) { (it + (viaCan ?: 0)).toByte() })
                w.u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).u8(0).bytes("kinetic-sim\u0000".toByteArray()).u32(0)
            }
            CommPacketId.GET_VALUES -> {
                w.u8(id).f16(tempFet, 1e1).f16(tempMotor, 1e1).f32(motorCurrent(), 1e2).f32(inputCurrent(), 1e2)
                    .f32(0.0, 1e2).f32(motorCurrent(), 1e2).f16(erpm / maxErpm, 1e3).f32(erpm, 1.0).f16(voltage(), 1e1)
                    .f32(ah, 1e4).f32(0.0, 1e4).f32(wh, 1e4).f32(whRegen, 1e4).i32(tach).i32(abs(tach)).u8(0)
                    .f32(0.0, 1e6).u8(viaCan ?: 0).f16(tempFet, 1e1).f16(tempFet - 1, 1e1).f16(tempFet + 1, 1e1)
                    .f32(0.0, 1e3).f32(0.0, 1e3).u8(0)
            }
            CommPacketId.GET_MCCONF_TEMP -> {
                w.u8(id).f32Auto(0.0).f32Auto(maxScale).f32Auto(-maxErpm).f32Auto(maxErpm).f32Auto(-0.95).f32Auto(0.95)
                    .f32Auto(-wattMax).f32Auto(wattMax).f32Auto(-60.0).f32Auto(200.0).u8(polePairs * 2).f32Auto(gear).f32Auto(wheelD)
            }
            CommPacketId.SET_MCCONF_TEMP, CommPacketId.SET_MCCONF_TEMP_SETUP -> {
                writesReceived += id
                val r = VescReader(req, 1); r.u8(); r.u8(); val ack = r.u8(); r.u8()
                r.f32Auto(); maxScale = r.f32Auto().coerceIn(0.0, 1.0)
                r.f32Auto(); val mx = r.f32Auto()
                maxErpm = if (id == CommPacketId.SET_MCCONF_TEMP_SETUP.id) mx * (polePairs * 60 * gear) / (wheelD * PI) else mx
                r.f32Auto(); r.f32Auto(); r.f32Auto(); wattMax = r.f32Auto()
                if (ack == 0) return
                w.u8(id)
            }
            CommPacketId.GET_IMU_DATA -> {
                w.u8(id).i16(0xFFFF)
                val t = (clock() - t0) / 1000.0
                listOf(0.05 * sin(t), 0.02 * sin(t / 2), 0.0, 0.1 * sin(t), 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0).forEach { w.f32Auto(it) }
            }
            CommPacketId.PING_CAN -> { w.u8(id); canIds.forEach { w.u8(it) } }
            CommPacketId.BMS_GET_VALUES -> {
                val cells = List(20) { 4.1 - ah * 0.02 + (it % 3) * 0.004 }
                w.u8(id).f32(cells.sum(), 1e6).f32(0.0, 1e6).f32(inputCurrent(), 1e6).f32(inputCurrent(), 1e6).f32(ah, 1e3).f32(wh, 1e3)
                w.u8(cells.size); cells.forEach { w.f16(it, 1e3) }; cells.forEach { _ -> w.u8(0) }
                w.u8(2).f16(28.0, 1e2).f16(29.0, 1e2).f16(30.0, 1e2).f16(0.0, 1e2).f16(0.0, 1e2).f16(29.0, 1e2)
                w.f16(0.9, 1e3).f16(0.97, 1e3).u8(10)
            }
            CommPacketId.ALIVE -> return
            else -> return // unsupported → no reply (like real firmware for unknown ids)
        }
        replyCount++
        if (dropEveryNth > 0 && replyCount % dropEveryNth == 0) return
        val frame = PacketCodec.encode(w.toByteArray())
        if (corruptEveryNth > 0 && replyCount % corruptEveryNth == 0) frame[frame.size - 2] = (frame[frame.size - 2] + 1).toByte()
        var off = 0
        while (off < frame.size) { val n = min(fragmentSize, frame.size - off); _incoming.emit(frame.copyOfRange(off, off + n)); off += n }
    }
}
