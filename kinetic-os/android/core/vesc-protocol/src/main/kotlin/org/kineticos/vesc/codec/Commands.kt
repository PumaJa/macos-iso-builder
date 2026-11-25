package org.kineticos.vesc.codec

import org.kineticos.vesc.model.CommPacketId

/** Builders for request payloads (command id + body). */
object Commands {
    private fun cmd(id: CommPacketId, body: VescWriter.() -> Unit = {}) = VescWriter().u8(id.id).apply(body).toByteArray()

    fun fwVersion() = cmd(CommPacketId.FW_VERSION)
    fun getValues() = cmd(CommPacketId.GET_VALUES)
    fun getValuesSelective(mask: Long) = cmd(CommPacketId.GET_VALUES_SELECTIVE) { u32(mask) }
    fun getValuesSetup() = cmd(CommPacketId.GET_VALUES_SETUP)
    fun getImu(mask: Int = 0xFFFF) = cmd(CommPacketId.GET_IMU_DATA) { i16(mask) }
    fun getBms() = cmd(CommPacketId.BMS_GET_VALUES)
    fun getMcConfTemp() = cmd(CommPacketId.GET_MCCONF_TEMP)
    fun getStats(mask: Int = 0x7FF) = cmd(CommPacketId.GET_STATS) { i16(mask) }
    fun getBatteryCut() = cmd(CommPacketId.GET_BATTERY_CUT)
    fun getGnss(mask: Int = 0xFFFF) = cmd(CommPacketId.GET_GNSS) { i16(mask) }
    fun pingCan() = cmd(CommPacketId.PING_CAN)
    fun getDecodedAdc() = cmd(CommPacketId.GET_DECODED_ADC)
    fun getDecodedPpm() = cmd(CommPacketId.GET_DECODED_PPM)
    fun alive() = cmd(CommPacketId.ALIVE)

    /** Wrap any request so it is executed by the VESC with [canId] behind the connected one. */
    fun forwardCan(canId: Int, inner: ByteArray): ByteArray {
        require(canId in 0..254) { "CAN id out of range" }
        return VescWriter(inner.size + 2).u8(CommPacketId.FORWARD_CAN.id).u8(canId).bytes(inner).toByteArray()
    }

    /**
     * COMM_SET_MCCONF_TEMP_SETUP – runtime limit change used for ride modes.
     * Firmware clamps current scales to 0..1 (can only *reduce* stored motor current limits).
     * store=false keeps the change in RAM only, so a VESC reboot restores the stored configuration.
     * Speeds are in m/s (firmware converts with its own pole/gear/wheel setup).
     */
    fun setMcConfTempSetup(
        currentMinScale: Double, currentMaxScale: Double,
        minSpeedMps: Double, maxSpeedMps: Double,
        minDuty: Double, maxDuty: Double,
        wattMin: Double, wattMax: Double,
        inCurrentMin: Double, inCurrentMax: Double,
        store: Boolean = false, forwardCan: Boolean = true, ack: Boolean = true, divideByControllers: Boolean = true,
    ) = cmd(CommPacketId.SET_MCCONF_TEMP_SETUP) {
        u8(if (store) 1 else 0); u8(if (forwardCan) 1 else 0); u8(if (ack) 1 else 0); u8(if (divideByControllers) 1 else 0)
        f32Auto(currentMinScale); f32Auto(currentMaxScale)
        f32Auto(minSpeedMps); f32Auto(maxSpeedMps)
        f32Auto(minDuty); f32Auto(maxDuty)
        f32Auto(wattMin); f32Auto(wattMax)
        f32Auto(inCurrentMin); f32Auto(inCurrentMax)
    }

    /** COMM_SET_MCCONF_TEMP (raw ERPM, used to restore a backup exactly). */
    fun setMcConfTemp(
        currentMinScale: Double, currentMaxScale: Double, minErpm: Double, maxErpm: Double,
        minDuty: Double, maxDuty: Double, wattMin: Double, wattMax: Double, inCurrentMin: Double, inCurrentMax: Double,
        store: Boolean = false, forwardCan: Boolean = true, ack: Boolean = true,
    ) = cmd(CommPacketId.SET_MCCONF_TEMP) {
        u8(if (store) 1 else 0); u8(if (forwardCan) 1 else 0); u8(if (ack) 1 else 0); u8(0)
        f32Auto(currentMinScale); f32Auto(currentMaxScale); f32Auto(minErpm); f32Auto(maxErpm)
        f32Auto(minDuty); f32Auto(maxDuty); f32Auto(wattMin); f32Auto(wattMax); f32Auto(inCurrentMin); f32Auto(inCurrentMax)
    }

    /** Send a string to a LispBM script via COMM_CUSTOM_APP_DATA (used by lispbm/kinetic.lisp). */
    fun customAppData(data: ByteArray) = cmd(CommPacketId.CUSTOM_APP_DATA) { bytes(data) }
}
