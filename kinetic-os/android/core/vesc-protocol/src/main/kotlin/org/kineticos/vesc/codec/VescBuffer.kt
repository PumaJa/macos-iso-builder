package org.kineticos.vesc.codec

import kotlin.math.abs

/** Big-endian reader mirroring bldc/util/buffer.c `buffer_get_*`. */
class VescReader(private val data: ByteArray, start: Int = 0) {
    var index: Int = start
        private set
    val remaining: Int get() = data.size - index

    private fun need(n: Int) {
        if (remaining < n) throw VescDecodeException("Payload too short: need $n bytes at $index, have $remaining")
    }

    fun u8(): Int { need(1); return data[index++].toInt() and 0xFF }
    fun i16(): Int { need(2); val v = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF); index += 2; return v.toShort().toInt() }
    fun u16(): Int = i16() and 0xFFFF
    fun i32(): Int {
        need(4)
        val v = ((data[index].toInt() and 0xFF) shl 24) or ((data[index + 1].toInt() and 0xFF) shl 16) or
            ((data[index + 2].toInt() and 0xFF) shl 8) or (data[index + 3].toInt() and 0xFF)
        index += 4; return v
    }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long { val hi = u32(); val lo = u32(); return (hi shl 32) or lo }
    fun f16(scale: Double): Double = i16() / scale
    fun f32(scale: Double): Double = i32() / scale
    fun f64(scale: Double): Double = i64() / scale

    /** buffer_get_float32_auto */
    fun f32Auto(): Double {
        val res = u32()
        var e = ((res shr 23) and 0xFF).toInt()
        val sigI = res and 0x7FFFFF
        val neg = (res and (1L shl 31)) != 0L
        var sig = 0.0
        if (e != 0 || sigI != 0L) {
            sig = sigI.toDouble() / (8388608.0 * 2.0) + 0.5
            e -= 126
        }
        if (neg) sig = -sig
        return Math.scalb(sig, e).toFloat().toDouble()
    }

    fun bytes(n: Int): ByteArray { need(n); val r = data.copyOfRange(index, index + n); index += n; return r }

    /** Null-terminated string. */
    fun cString(): String {
        val start = index
        while (index < data.size && data[index] != 0.toByte()) index++
        val s = String(data, start, index - start, Charsets.UTF_8)
        if (index < data.size) index++ // skip terminator
        return s
    }
}

/** Big-endian writer mirroring `buffer_append_*`. */
class VescWriter(initial: Int = 32) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun i16(v: Int) = apply { u8(v shr 8); u8(v) }
    fun i32(v: Int) = apply { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Long) = i32(v.toInt())
    fun f16(v: Double, scale: Double) = i16((v * scale).toInt())
    fun f32(v: Double, scale: Double) = i32((v * scale).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    fun f32Auto(value: Double) = apply {
        var number = value.toFloat()
        if (abs(number) < 1.5e-38f) number = 0f
        if (number == 0f) { u32(0); return@apply }
        val e0 = Math.getExponent(number) + 1          // frexp exponent
        val sig = number / Math.scalb(1f, e0)          // in [0.5,1)
        val sigAbs = abs(sig)
        var sigI = 0L
        var e = e0
        if (sigAbs >= 0.5f) { sigI = ((sigAbs - 0.5f) * 2.0f * 8388608.0f).toLong(); e += 126 }
        var res = ((e.toLong() and 0xFF) shl 23) or (sigI and 0x7FFFFF)
        if (sig < 0) res = res or (1L shl 31)
        u32(res)
    }
    fun bytes(b: ByteArray) = apply { ensure(b.size); b.copyInto(buf, size); size += b.size }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class VescDecodeException(message: String) : Exception(message)
