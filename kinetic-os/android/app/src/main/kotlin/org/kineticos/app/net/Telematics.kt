package org.kineticos.app.net

import org.kineticos.telemetry.TelemetrySample

object TelematicsMapper {
    /** Maps a cockpit sample to the server's vehicleState. Values are passed through only if available – never invented. */
    fun toState(s: TelemetrySample?, vescConnected: Boolean, odometerKm: Double?, simulated: Boolean, fallbackGps: org.kineticos.telemetry.GpsFix? = null): VehicleState {
        val g = s?.gps ?: fallbackGps
        return VehicleState(
            ts = System.currentTimeMillis(), lat = g?.lat, lon = g?.lon, alt = g?.altM,
            speedKmh = s?.speedMps?.value?.times(3.6), headingDeg = g?.bearingDeg, socPct = s?.socPct?.value, voltage = s?.voltageV?.value,
            powerW = s?.batteryPowerW?.value, tempMosC = s?.tempMosC?.value, tempMotorC = s?.tempMotorC?.value, odometerKm = odometerKm,
            faultCode = s?.faultCode, vescConnected = vescConnected, charging = s?.batteryCurrentA?.value?.let { it < -0.5 && (s.speedMps.value ?: 0.0) < 0.3 },
            source = if (simulated) "simulated" else "measured",
        )
    }
}
