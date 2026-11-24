package org.kineticos.vesc.codec

/**
 * VESC packet framing (bldc/comm/packet.c):
 *   [2][len8] | [3][len16] | [4][len24]  payload  [crc16 hi][crc16 lo] [3]
 */
object PacketCodec {
    const val MAX_PAYLOAD = 512 * 1024 // generous upper bound; firmware PACKET_MAX_PL_LEN is lower

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Empty payload" }
        require(payload.size <= 0xFFFFFF) { "Payload too large" }
        val w = VescWriter(payload.size + 7)
        when {
            payload.size <= 255 -> w.u8(2).u8(payload.size)
            payload.size <= 65535 -> w.u8(3).u8(payload.size shr 8).u8(payload.size)
            else -> w.u8(4).u8(payload.size shr 16).u8(payload.size shr 8).u8(payload.size)
        }
        w.bytes(payload)
        val crc = Crc16.compute(payload)
        w.u8(crc shr 8).u8(crc).u8(3)
        return w.toByteArray()
    }
}

/**
 * Streaming decoder. Feed arbitrary fragments (BLE notifications are typically 20–244 bytes);
 * complete, CRC-valid payloads are emitted. Corrupt data is skipped byte-by-byte to resynchronise,
 * the same strategy the firmware uses.
 */
class PacketDecoder(private val maxPayload: Int = PacketCodec.MAX_PAYLOAD) {
    private var buf = ByteArray(1024)
    private var len = 0

    var crcErrors = 0L; private set
    var framingErrors = 0L; private set
    var packetsDecoded = 0L; private set

    fun reset() { len = 0 }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        if (len + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + length))
        data.copyInto(buf, len, offset, offset + length)
        len += length
        val out = ArrayList<ByteArray>(1)
        var pos = 0
        while (true) {
            val r = tryParse(pos)
            when {
                r == NEED_MORE -> {
                    // A corrupted length field can make us wait for bytes that never come. If a complete,
                    // CRC-valid frame already exists further ahead, the candidate at `pos` was garbage.
                    val next = findValidFrameAfter(pos)
                    if (next < 0) break
                    framingErrors++; pos = next
                }
                r == SKIP -> pos++
                else -> { out += lastPayload!!; lastPayload = null; pos = r }
            }
        }
        if (pos > 0) { buf.copyInto(buf, 0, pos, len); len -= pos }
        return out
    }

    private var lastPayload: ByteArray? = null

    private fun findValidFrameAfter(pos: Int): Int {
        val c0 = crcErrors; val f0 = framingErrors
        try { return scan(pos) } finally { crcErrors = c0; framingErrors = f0 }
    }

    private fun scan(pos: Int): Int {
        for (j in pos + 1 until len) {
            val b = buf[j].toInt() and 0xFF
            if (b !in 2..4) continue
            val r = tryParse(j)
            if (r >= 0) { lastPayload = null; packetsDecoded--; return j }
        }
        return -1
    }

    private fun tryParse(pos: Int): Int {
        val avail = len - pos
        if (avail < 1) return NEED_MORE
        val start = buf[pos].toInt() and 0xFF
        val hdr = when (start) { 2 -> 2; 3 -> 3; 4 -> 4; else -> { framingErrors++; return SKIP } }
        if (avail < hdr) return NEED_MORE
        var plLen = 0
        for (i in 1 until hdr) plLen = (plLen shl 8) or (buf[pos + i].toInt() and 0xFF)
        if (plLen == 0 || plLen > maxPayload || (start == 3 && plLen <= 255) || (start == 4 && plLen <= 65535)) {
            framingErrors++; return SKIP
        }
        val total = hdr + plLen + 3
        if (avail < total) return NEED_MORE
        if ((buf[pos + total - 1].toInt() and 0xFF) != 3) { framingErrors++; return SKIP }
        val crcRx = ((buf[pos + hdr + plLen].toInt() and 0xFF) shl 8) or (buf[pos + hdr + plLen + 1].toInt() and 0xFF)
        if (Crc16.compute(buf, pos + hdr, plLen) != crcRx) { crcErrors++; return SKIP }
        lastPayload = buf.copyOfRange(pos + hdr, pos + hdr + plLen)
        packetsDecoded++
        return pos + total
    }

    private companion object { const val NEED_MORE = -1; const val SKIP = -2 }
}
