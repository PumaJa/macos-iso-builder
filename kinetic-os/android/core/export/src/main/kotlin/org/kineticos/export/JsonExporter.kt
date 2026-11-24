package org.kineticos.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kineticos.telemetry.TelemetrySample

@Serializable
data class RideMeta(
    val format: String = JsonExporter.FORMAT,
    val version: Int = JsonExporter.VERSION,
    val vehicle: Map<String, String> = emptyMap(),
    val simulated: Boolean = false,
    val sampleCount: Int = 0,
)

@Serializable
data class RideFile(val meta: RideMeta, val samples: List<TelemetrySample>)

object JsonExporter {
    const val FORMAT = "kinetic-os-ride"
    const val VERSION = 1

    private val compact = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val pretty = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    /** `simulated` is true when any sample is simulated, so simulated data can never pass as a real ride. */
    fun toRideFile(samples: List<TelemetrySample>, vehicle: Map<String, String> = emptyMap()) =
        RideFile(RideMeta(vehicle = vehicle, simulated = samples.any { it.simulated }, sampleCount = samples.size), samples)

    fun write(samples: List<TelemetrySample>, out: Appendable, vehicle: Map<String, String> = emptyMap(), prettyPrint: Boolean = false) {
        out.append(toJson(samples, vehicle, prettyPrint))
    }

    fun toJson(samples: List<TelemetrySample>, vehicle: Map<String, String> = emptyMap(), prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else compact).encodeToString(RideFile.serializer(), toRideFile(samples, vehicle))

    fun parse(text: String): RideFile {
        val f = compact.decodeFromString(RideFile.serializer(), text)
        require(f.meta.format == FORMAT) { "not a $FORMAT file: ${f.meta.format}" }
        require(f.meta.version <= VERSION) { "unsupported version ${f.meta.version}" }
        return f
    }
}
