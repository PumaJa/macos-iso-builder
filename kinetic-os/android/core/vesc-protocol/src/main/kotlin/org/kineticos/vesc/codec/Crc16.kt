package org.kineticos.vesc.codec

/** CRC16-CCITT (XMODEM, poly 0x1021, init 0) exactly as in bldc/util/crc.c. */
object Crc16 {
    private val table = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc and 0xFFFF
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var cksum = 0
        for (i in offset until offset + length) {
            cksum = table[((cksum ushr 8) xor data[i].toInt()) and 0xFF] xor ((cksum shl 8) and 0xFFFF)
        }
        return cksum and 0xFFFF
    }
}
