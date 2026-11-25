package org.kineticos.export

import org.kineticos.telemetry.Q
import org.kineticos.telemetry.TelemetrySample
import java.math.BigDecimal

/** RFC 4180 CSV (CRLF line ends, '.' decimals, quoting only when needed). */
object CsvExporter {
    private val qColumns: List<Pair<String, (TelemetrySample) -> Q>> = listOf(
        "speed_mps" to { s -> s.speedMps }, "motor_rpm" to { s -> s.motorRpm }, "erpm" to { s -> s.erpm },
        "motor_power_w" to { s -> s.motorPowerW }, "battery_power_w" to { s -> s.batteryPowerW },
        "motor_current_a" to { s -> s.motorCurrentA }, "battery_current_a" to { s -> s.batteryCurrentA },
        "voltage_v" to { s -> s.voltageV }, "soc_pct" to { s -> s.socPct },
        "amp_hours" to { s -> s.ampHours }, "amp_hours_charged" to { s -> s.ampHoursCharged },
        "watt_hours" to { s -> s.wattHours }, "watt_hours_charged" to { s -> s.wattHoursCharged },
        "temp_mos_c" to { s -> s.tempMosC }, "temp_motor_c" to { s -> s.tempMotorC },
        "duty" to { s -> s.duty }, "throttle" to { s -> s.throttle }, "odometer_km" to { s -> s.odometerKm },
        "accel_g" to { s -> s.accelG }, "lateral_g" to { s -> s.lateralG },
        "lean_deg" to { s -> s.leanDeg }, "pitch_deg" to { s -> s.pitchDeg },
    )

    val header: List<String> = buildList {
        add("t_ms"); add("simulated"); add("speed_source")
        qColumns.forEach { add(it.first); add(it.first + "_quality") }
        addAll(listOf("fault_code", "fault_name", "controllers"))
        addAll(listOf("gps_lat", "gps_lon", "gps_alt_m", "gps_speed_mps", "gps_bearing_deg", "gps_accuracy_m", "gps_time_ms", "gps_provider"))
    }

    fun num(v: Double?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "" else BigDecimal.valueOf(v).let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }

    /** Neutralise spreadsheet formula injection (OWASP): text cells starting with = + - @ TAB CR get a leading quote. */
    fun text(f: String): String = if (f.isNotEmpty() && f[0] in "=+-@\t\r") "'" + f else f

    fun escape(f: String): String =
        if (f.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + f.replace("\"", "\"\"") + "\"" else f

    fun write(samples: List<TelemetrySample>, out: Appendable) {
        writeRow(header, out)
        for (s in samples) {
            val row = ArrayList<String>(header.size)
            row += s.t.toString(); row += s.simulated.toString(); row += text(s.speedSource)
            for ((_, get) in qColumns) { val q = get(s); row += if (q.isAvailable) num(q.value) else ""; row += q.quality.name }
            row += s.faultCode.toString(); row += text(s.faultName ?: ""); row += s.controllers.toString()
            val g = s.gps
            row += num(g?.lat); row += num(g?.lon); row += num(g?.altM); row += num(g?.speedMps)
            row += num(g?.bearingDeg); row += num(g?.accuracyM); row += g?.timeMs?.toString() ?: ""; row += text(g?.provider ?: "")
            writeRow(row, out)
        }
    }

    fun toCsv(samples: List<TelemetrySample>): String = StringBuilder().also { write(samples, it) }.toString()

    private fun writeRow(cells: List<String>, out: Appendable) {
        out.append(cells.joinToString(",") { escape(it) }).append("\r\n")
    }
}
