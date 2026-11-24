package org.kineticos.vesc

import org.kineticos.vesc.codec.*
import kotlin.random.Random
import kotlin.test.*

class CodecTest {
    @Test fun crcKnownVector() {
        // CRC-16/XMODEM check value for "123456789" is 0x31C3
        assertEquals(0x31C3, Crc16.compute("123456789".toByteArray()))
    }

    @Test fun encodeShortPacketMatchesFirmwareLayout() {
        val f = PacketCodec.encode(byteArrayOf(4)) // COMM_GET_VALUES
        val crc = Crc16.compute(byteArrayOf(4))
        assertContentEquals(byteArrayOf(2, 1, 4, (crc shr 8).toByte(), crc.toByte(), 3), f)
    }

    @Test fun encodeLongAndVeryLongHeaders() {
        assertEquals(3, PacketCodec.encode(ByteArray(300)).first().toInt())
        assertEquals(4, PacketCodec.encode(ByteArray(70000)).first().toInt())
    }

    @Test fun roundTripFragmentedRandom() {
        val rnd = Random(42)
        val payloads = List(200) { ByteArray(rnd.nextInt(1, 700)) { rnd.nextInt().toByte() } }
        val stream = payloads.flatMap { PacketCodec.encode(it).toList() }.toByteArray()
        val dec = PacketDecoder()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < stream.size) { val n = minOf(rnd.nextInt(1, 30), stream.size - i); out += dec.feed(stream, i, n); i += n }
        assertEquals(payloads.size, out.size)
        payloads.zip(out).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals(0, dec.crcErrors)
    }

    @Test fun corruptedPacketIsDroppedAndDecoderResyncs() {
        val good = PacketCodec.encode(byteArrayOf(1, 2, 3))
        val bad = PacketCodec.encode(byteArrayOf(9, 9, 9)).also { it[3] = 7 }
        val dec = PacketDecoder()
        val out = dec.feed(byteArrayOf(0x55, 0x00) + bad + good)
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(1, 2, 3), out[0])
        assertTrue(dec.crcErrors >= 1)
    }

    @Test fun garbageNeverCrashes() {
        val rnd = Random(7); val dec = PacketDecoder(maxPayload = 4096)
        repeat(2000) { dec.feed(ByteArray(rnd.nextInt(1, 64)) { rnd.nextInt().toByte() }) }
    }

    @Test fun float32AutoRoundTrip() {
        for (v in listOf(0.0, 1.0, -1.0, 0.5, 123.456, -98765.4321, 1e-20, 3.0e30, 0.1)) {
            val b = VescWriter().f32Auto(v).toByteArray()
            val back = VescReader(b).f32Auto()
            assertEquals(v.toFloat().toDouble(), back, kotlin.math.abs(v) * 1e-6 + 1e-30, "value $v")
        }
    }

    @Test fun scaledIntegers() {
        val b = VescWriter().f16(25.7, 1e1).f32(-12.34, 1e2).i32(-5).toByteArray()
        val r = VescReader(b)
        assertEquals(25.7, r.f16(1e1), 1e-9); assertEquals(-12.34, r.f32(1e2), 1e-9); assertEquals(-5, r.i32())
    }

    @Test fun shortPayloadThrowsDecodeException() {
        assertFailsWith<VescDecodeException> { VescReader(byteArrayOf(1)).i32() }
    }
}
