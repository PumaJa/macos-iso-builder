package org.kineticos.vesc.codec

import org.kineticos.vesc.model.*

/** Decoders for reply payloads. Input = payload *without* the leading command id byte. */
object Parsers {
    fun fwVersion(p: ByteArray): FirmwareInfo {
        val r = VescReader(p)
        val major = r.u8(); val minor = r.u8()
        val hw = r.cString()
        val uuid = if (r.remaining >= 12) r.bytes(12).joinToString("") { "%02x".format(it) } else ""
        fun opt(): Int? = if (r.remaining >= 1) r.u8() else null
        val pairing = opt(); val test = opt(); val hwType = opt(); val cfg = opt(); val pf = opt()
        val qh = opt(); val qa = opt(); val nrf = opt()
        val fwName = if (r.remaining > 0) r.cString() else null
        val crc = if (r.remaining >= 4) r.u32() else null
        return FirmwareInfo(major, minor, hw, uuid, pairing?.let { it != 0 }, test, hwType, cfg, pf?.let { it != 0 }, qh, qa, nrf, fwName, crc)
    }

    fun values(p: ByteArray, selectiveMask: Long? = null): VescValues {
        val r = VescReader(p)
        val mask = if (selectiveMask != null) r.u32() else 0xFFFFFFFFL
        fun has(b: Int) = (mask shr b) and 1L == 1L
        fun <T> f(b: Int, read: () -> T): T? = if (has(b) && r.remaining > 0) read() else null
        val tf = f(0) { r.f16(1e1) }; val tm = f(1) { r.f16(1e1) }
        val im = f(2) { r.f32(1e2) }; val ii = f(3) { r.f32(1e2) }
        val id = f(4) { r.f32(1e2) }; val iq = f(5) { r.f32(1e2) }
        val duty = f(6) { r.f16(1e3) }; val rpm = f(7) { r.f32(1e0) }
        val vin = f(8) { r.f16(1e1) }
        val ah = f(9) { r.f32(1e4) }; val ahc = f(10) { r.f32(1e4) }
        val wh = f(11) { r.f32(1e4) }; val whc = f(12) { r.f32(1e4) }
        val tach = f(13) { r.i32() }; val tachAbs = f(14) { r.i32() }
        val fault = f(15) { r.u8() }; val pid = f(16) { r.f32(1e6) }
        val cid = f(17) { r.u8() }
        var m1: Double? = null; var m2: Double? = null; var m3: Double? = null
        if (has(18) && r.remaining >= 6) { m1 = r.f16(1e1); m2 = r.f16(1e1); m3 = r.f16(1e1) }
        val vd = f(19) { r.f32(1e3) }; val vq = f(20) { r.f32(1e3) }
        val st = f(21) { r.u8() }
        return VescValues(
            tf ?: Double.NaN, tm ?: Double.NaN, im ?: Double.NaN, ii ?: Double.NaN, id ?: Double.NaN, iq ?: Double.NaN,
            duty ?: Double.NaN, rpm ?: Double.NaN, vin ?: Double.NaN, ah ?: Double.NaN, ahc ?: Double.NaN,
            wh ?: Double.NaN, whc ?: Double.NaN, tach ?: 0, tachAbs ?: 0, fault ?: 0, pid, cid, m1, m2, m3, vd, vq, st,
        )
    }

    fun setupValues(p: ByteArray, selective: Boolean = false): VescSetupValues {
        val r = VescReader(p)
        val mask = if (selective) r.u32() else 0xFFFFFFFFL
        fun has(b: Int) = (mask shr b) and 1L == 1L && r.remaining > 0
        val nan = Double.NaN
        val tf = if (has(0)) r.f16(1e1) else nan; val tm = if (has(1)) r.f16(1e1) else nan
        val ct = if (has(2)) r.f32(1e2) else nan; val ci = if (has(3)) r.f32(1e2) else nan
        val duty = if (has(4)) r.f16(1e3) else nan; val rpm = if (has(5)) r.f32(1e0) else nan
        val spd = if (has(6)) r.f32(1e3) else nan; val vin = if (has(7)) r.f16(1e1) else nan
        val bl = if (has(8)) r.f16(1e3) else nan
        val ah = if (has(9)) r.f32(1e4) else nan; val ahc = if (has(10)) r.f32(1e4) else nan
        val wh = if (has(11)) r.f32(1e4) else nan; val whc = if (has(12)) r.f32(1e4) else nan
        val dist = if (has(13)) r.f32(1e3) else nan; val distAbs = if (has(14)) r.f32(1e3) else nan
        val pid = if (has(15)) r.f32(1e6) else nan; val fault = if (has(16)) r.u8() else 0
        val cid = if (has(17)) r.u8() else 0; val n = if (has(18)) r.u8() else 1
        val whLeft = if (has(19)) r.f32(1e3) else nan
        val odo = if (has(20) && r.remaining >= 4) r.u32() else null
        val up = if (has(21) && r.remaining >= 4) r.u32() else null
        return VescSetupValues(tf, tm, ct, ci, duty, rpm, spd, vin, bl, ah, ahc, wh, whc, dist, distAbs, pid, fault, cid, n, whLeft, odo, up)
    }

    fun imu(p: ByteArray): ImuData {
        val r = VescReader(p)
        val mask = r.u16()
        val v = DoubleArray(16) { Double.NaN }
        for (b in 0 until 16) if ((mask shr b) and 1 == 1 && r.remaining >= 4) v[b] = r.f32Auto()
        return ImuData(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15])
    }

    fun bms(p: ByteArray): BmsValues {
        val r = VescReader(p)
        val vTot = r.f32(1e6); val vChg = r.f32(1e6); val iIn = r.f32(1e6); val iInIc = r.f32(1e6)
        val ah = r.f32(1e3); val wh = r.f32(1e3)
        val n = r.u8()
        val cells = List(n) { r.f16(1e3) }
        val bal = List(n) { r.u8() != 0 }
        val tn = r.u8()
        val temps = List(tn) { r.f16(1e2) }
        val tIc = r.f16(1e2); val tHum = r.f16(1e2); val hum = r.f16(1e2); val tMax = r.f16(1e2)
        val soc = r.f16(1e3); val soh = r.f16(1e3); val canId = r.u8()
        fun oa() = if (r.remaining >= 4) r.f32Auto() else null
        val a1 = oa(); val a2 = oa(); val a3 = oa(); val a4 = oa()
        val pr = if (r.remaining >= 2) r.f16(1e-1) else null
        val dv = if (r.remaining >= 1) r.u8() else null
        val st = if (r.remaining > 0) r.cString() else null
        return BmsValues(vTot, vChg, iIn, iInIc, ah, wh, cells, bal, temps, tIc, tHum, hum, tMax, soc, soh, canId, a1, a2, a3, a4, pr, dv, st)
    }

    fun mcConfTemp(p: ByteArray): McConfTemp {
        val r = VescReader(p)
        return McConfTemp(r.f32Auto(), r.f32Auto(), r.f32Auto(), r.f32Auto(), r.f32Auto(), r.f32Auto(),
            r.f32Auto(), r.f32Auto(), r.f32Auto(), r.f32Auto(), r.u8(), r.f32Auto(), r.f32Auto())
    }

    fun stats(p: ByteArray): VescStats {
        val r = VescReader(p)
        val mask = r.u32()
        val v = arrayOfNulls<Double>(11)
        for (b in 0 until 11) if ((mask shr b) and 1L == 1L && r.remaining >= 4) v[b] = r.f32Auto()
        return VescStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])
    }

    fun decodedAdc(p: ByteArray): DecodedAdc { val r = VescReader(p); return DecodedAdc(r.f32(1e6), r.f32(1e6), r.f32(1e6), r.f32(1e6)) }
    fun decodedPpm(p: ByteArray): DecodedPpm { val r = VescReader(p); return DecodedPpm(r.f32(1e6), r.f32(1e6)) }

    fun batteryCut(p: ByteArray): BatteryCut { val r = VescReader(p); return BatteryCut(r.f32(1e3), r.f32(1e3)) }

    fun pingCan(p: ByteArray): List<Int> = p.map { it.toInt() and 0xFF }

    fun gnss(p: ByteArray): GnssData {
        val r = VescReader(p)
        val mask = r.u32()
        fun has(b: Int) = (mask shr b) and 1L == 1L
        val lat = if (has(0)) r.f64(1e16) else null
        val lon = if (has(1)) r.f64(1e16) else null
        val h = if (has(2)) r.f32Auto() else null
        val s = if (has(3)) r.f32Auto() else null
        val hd = if (has(4)) r.f32Auto() else null
        val ms = if (has(5)) r.i32() else null
        return GnssData(lat, lon, h, s, hd, ms)
    }
}
