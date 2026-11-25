package org.kineticos.vehicle

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * Approximate engine characters. These are *illustrative* throttle→torque maps inspired by the typical feel of
 * combustion bikes. They are NOT derived from validated dyno data and must never be presented as exact.
 */
@Serializable
enum class MotorCharacter(val label: String, val approximate: Boolean = true) {
    ELECTRIC_LINEAR("Electric – linear", approximate = false),
    TWO_STROKE_125("≈ 125cc 2-stroke"),
    TWO_STROKE_250("≈ 250cc 2-stroke"),
    FOUR_STROKE_250("≈ 250cc 4-stroke"),
    FOUR_STROKE_450("≈ 450cc 4-stroke"),
    CUSTOM("Custom curve", approximate = false),
}

/** Throttle curve as up to 11 points (input 0..1 evenly spaced → output 0..1). */
@Serializable
data class ThrottleCurve(val points: List<Double>) {
    init {
        require(points.size in 2..11) { "2–11 points" }
        require(points.all { it in 0.0..1.0 }) { "points must be 0..1" }
        require(points.first() == 0.0) { "curve must start at 0 (no torque at closed throttle)" }
        require(points.zipWithNext().all { (a, b) -> b >= a }) { "curve must be monotonic" }
    }
    fun map(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0) * (points.size - 1)
        val i = c.toInt().coerceAtMost(points.size - 2)
        val f = c - i
        return points[i] + (points[i + 1] - points[i]) * f
    }
    companion object {
        fun expo(e: Double, n: Int = 11) = ThrottleCurve(List(n) { (it / (n - 1.0)).pow(e) })
        val LINEAR = expo(1.0)
    }

    /** Resample to [n] evenly spaced points (for the point editor). */
    fun resample(n: Int): ThrottleCurve = ThrottleCurve(List(n) { map(it / (n - 1.0)) })

    /**
     * Moves point [index] to [value] while keeping the curve valid: point 0 stays 0 (no torque at closed throttle),
     * values stay in 0..1 and the curve stays monotonic – neighbours are pushed along instead of being crossed.
     */
    fun withPoint(index: Int, value: Double): ThrottleCurve {
        if (index == 0) return this
        val v = value.coerceIn(0.0, 1.0)
        val p = points.toMutableList(); p[index] = v
        for (i in index + 1 until p.size) if (p[i] < v) p[i] = v
        for (i in index - 1 downTo 1) if (p[i] > v) p[i] = v
        return ThrottleCurve(p)
    }
}

/**
 * Torque vs normalised motor speed (0..1 of max RPM) multiplier, used for the approximate engine characters.
 * Applied as a ceiling on the commanded current – the electric motor's own limits always stay in force.
 */
object CharacterMaps {
    fun torqueMultiplier(ch: MotorCharacter, rpmFrac: Double): Double {
        val r = rpmFrac.coerceIn(0.0, 1.0)
        return when (ch) {
            MotorCharacter.ELECTRIC_LINEAR, MotorCharacter.CUSTOM -> 1.0
            // 2-strokes: weak bottom end, sharp "powerband" hit in the mid-upper range
            MotorCharacter.TWO_STROKE_125 -> (0.35 + 0.65 * smooth((r - 0.45) / 0.25)) * (1 - 0.3 * smooth((r - 0.9) / 0.1))
            MotorCharacter.TWO_STROKE_250 -> (0.45 + 0.55 * smooth((r - 0.35) / 0.25)) * (1 - 0.25 * smooth((r - 0.9) / 0.1))
            // 4-strokes: broad, flat torque, gentle roll-off at the top
            MotorCharacter.FOUR_STROKE_250 -> 0.7 + 0.3 * smooth(r / 0.5) - 0.2 * smooth((r - 0.75) / 0.25)
            MotorCharacter.FOUR_STROKE_450 -> 0.85 + 0.15 * smooth(r / 0.3) - 0.15 * smooth((r - 0.7) / 0.3)
        }.coerceIn(0.0, 1.0)
    }
    fun defaultCurve(ch: MotorCharacter): ThrottleCurve = when (ch) {
        MotorCharacter.TWO_STROKE_125, MotorCharacter.TWO_STROKE_250 -> ThrottleCurve.expo(1.6)
        MotorCharacter.FOUR_STROKE_250 -> ThrottleCurve.expo(1.2)
        MotorCharacter.FOUR_STROKE_450 -> ThrottleCurve.expo(0.9)
        else -> ThrottleCurve.LINEAR
    }
    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
}

/**
 * A ride mode. Limits are expressed as fractions of the VESC's *stored* configuration (read and backed up on connect),
 * so a profile can never exceed what the controller was configured for in VESC Tool.
 */
@Serializable
data class RideProfile(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val powerFraction: Double = 1.0,           // → l_watt_max scale
    val motorCurrentFraction: Double = 1.0,    // → l_current_max_scale (firmware clamps 0..1)
    val batteryCurrentFraction: Double = 1.0,  // → l_in_current_max
    val regenFraction: Double = 1.0,           // → l_watt_min / l_in_current_min magnitude
    val speedLimitKmh: Double? = null,         // → l_max_erpm via SET_MCCONF_TEMP_SETUP (m/s)
    val reverseSpeedLimitKmh: Double? = null,
    val character: MotorCharacter = MotorCharacter.ELECTRIC_LINEAR,
    val throttleCurve: ThrottleCurve = ThrottleCurve.LINEAR,
    val engineBrakeFraction: Double = 0.0,     // requires LispBM throttle script – not applied by SET_MCCONF_TEMP
    val virtualFlywheel: Double = 0.0,         // requires hardware validation – disabled by SafetyGate
    val tractionControl: Double = 0.0,         // requires wheel-speed sensor – disabled by SafetyGate
    val wheelieControl: Double = 0.0,          // requires IMU validation – disabled by SafetyGate
    val tempDerate: Boolean = true,
    val socDerate: Boolean = true,
) {
    companion object {
        const val MAX_PROFILES = 200
        val ECO = RideProfile("eco", "ECO", true, powerFraction = 0.35, motorCurrentFraction = 0.5, batteryCurrentFraction = 0.4, regenFraction = 1.0, speedLimitKmh = 45.0)
        val SPORT = RideProfile("sport", "SPORT", true, powerFraction = 0.7, motorCurrentFraction = 0.8, batteryCurrentFraction = 0.75, regenFraction = 0.7)
        val RACE = RideProfile("race", "RACE", true, powerFraction = 1.0, motorCurrentFraction = 1.0, batteryCurrentFraction = 1.0, regenFraction = 0.4)
        val CRAWL = RideProfile("crawl", "CRAWL", true, powerFraction = 0.1, motorCurrentFraction = 0.25, batteryCurrentFraction = 0.15, speedLimitKmh = 6.0, reverseSpeedLimitKmh = 3.0)
        val BUILT_IN = listOf(ECO, SPORT, RACE, CRAWL)
    }
}
