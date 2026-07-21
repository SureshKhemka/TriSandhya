package com.trisandhya.sunrisealarm.solar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar times for a single local date.
 *
 * [sunrise] and [sunset] are null inside the polar circles on days where the sun
 * never rises or never sets. [solarNoon] is always defined.
 */
data class SolarTimes(
    val date: LocalDate,
    val sunrise: ZonedDateTime?,
    val solarNoon: ZonedDateTime,
    val sunset: ZonedDateTime?
)

/**
 * Computes sunrise, solar noon, and sunset locally using the NOAA solar equations.
 *
 * This replaces the previous api.sunrise-sunset.org dependency. Doing it offline
 * matters because the daily re-arming in [com.trisandhya.sunrisealarm.alarm.AlarmReceiver]
 * runs in the background — often before dawn with no connectivity — and must never
 * depend on a network round-trip to schedule the next day's alarm.
 *
 * The sun's declination and the equation of time both drift over the course of a
 * day, so evaluating them once at local noon leaves roughly a minute of error at
 * mid-latitudes. Each event is therefore solved iteratively: the position is
 * re-evaluated at the estimated event time until it stops moving.
 *
 * Verified against the US Naval Observatory to within seconds across six cities
 * and both solstices; see SolarCalculatorTest. Worth noting that this is *more*
 * accurate than the API it replaced, which ran about two minutes wide on the day
 * length at every latitude checked.
 */
object SolarCalculator {

    /** Standard zenith for sunrise/sunset, including refraction and solar radius. */
    private const val ZENITH_OFFICIAL = 90.833

    /** Converges well inside a second; three passes leaves margin. */
    private const val REFINEMENT_PASSES = 3

    private data class SunPosition(val declination: Double, val eqOfTime: Double)

    fun compute(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SolarTimes {
        val offsetHours = zone.rules
            .getOffset(date.atTime(12, 0))
            .totalSeconds / 3600.0

        val jdMidnightUt = julianDay(date)

        /** Julian day for a given number of minutes past local midnight. */
        fun jdAt(localMinutes: Double) =
            jdMidnightUt + (localMinutes / 60.0 - offsetHours) / 24.0

        fun solarNoonAt(position: SunPosition) =
            720 - 4 * longitude - position.eqOfTime + offsetHours * 60

        // Solar noon first: it seeds both event solves.
        var noonMinutes = solarNoonAt(sunPosition(jdAt(720.0)))
        repeat(REFINEMENT_PASSES) {
            noonMinutes = solarNoonAt(sunPosition(jdAt(noonMinutes)))
        }

        /**
         * Solves for an event either side of noon.
         *
         * @param direction -1 for sunrise, +1 for sunset.
         * @return minutes past local midnight, or null if the event does not occur.
         */
        fun solveEvent(direction: Int): Double? {
            var minutes = noonMinutes
            repeat(REFINEMENT_PASSES) {
                val position = sunPosition(jdAt(minutes))
                val cosHourAngle = cos(rad(ZENITH_OFFICIAL)) /
                        (cos(rad(latitude)) * cos(rad(position.declination))) -
                        tan(rad(latitude)) * tan(rad(position.declination))

                // Sun stays entirely above or below the horizon all day.
                if (abs(cosHourAngle) > 1.0) return null

                val hourAngleMinutes = deg(acos(cosHourAngle)) * 4
                minutes = solarNoonAt(position) + direction * hourAngleMinutes
            }
            return minutes
        }

        return SolarTimes(
            date = date,
            sunrise = solveEvent(-1)?.let { at(date, it, zone) },
            solarNoon = at(date, noonMinutes, zone),
            sunset = solveEvent(+1)?.let { at(date, it, zone) }
        )
    }

    /** Declination (degrees) and equation of time (minutes) at a Julian day. */
    private fun sunPosition(julianDay: Double): SunPosition {
        val t = (julianDay - 2451545.0) / 36525.0

        val geomMeanLong = wrap360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val geomMeanAnom = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val eqOfCenter = sin(rad(geomMeanAnom)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(rad(2 * geomMeanAnom)) * (0.019993 - 0.000101 * t) +
                sin(rad(3 * geomMeanAnom)) * 0.000289

        val trueLong = geomMeanLong + eqOfCenter
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(125.04 - 1934.136 * t))

        val meanObliquity =
            23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquityCorr = meanObliquity + 0.00256 * cos(rad(125.04 - 1934.136 * t))

        val declination = deg(asin(sin(rad(obliquityCorr)) * sin(rad(appLong))))

        val y = tan(rad(obliquityCorr / 2)).let { it * it }
        val eqOfTime = 4 * deg(
            y * sin(2 * rad(geomMeanLong)) -
                    2 * eccentricity * sin(rad(geomMeanAnom)) +
                    4 * eccentricity * y * sin(rad(geomMeanAnom)) * cos(2 * rad(geomMeanLong)) -
                    0.5 * y * y * sin(4 * rad(geomMeanLong)) -
                    1.25 * eccentricity * eccentricity * sin(2 * rad(geomMeanAnom))
        )

        return SunPosition(declination, eqOfTime)
    }

    /**
     * Converts minutes past local midnight into a zoned instant.
     *
     * Built as a local wall-clock time first, then resolved against the zone, so
     * that a DST jump lands on a real instant rather than a nonexistent one.
     */
    private fun at(date: LocalDate, minutesPastMidnight: Double, zone: ZoneId): ZonedDateTime {
        val seconds = Math.round(minutesPastMidnight * 60.0)
        return date.atStartOfDay().plusSeconds(seconds).atZone(zone)
    }

    /** Julian day at 00:00 UT of the given date. */
    private fun julianDay(date: LocalDate): Double {
        var year = date.year
        var month = date.monthValue
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) +
                floor(30.6001 * (month + 1)) +
                date.dayOfMonth + b - 1524.5
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees)

    private fun deg(radians: Double) = Math.toDegrees(radians)

    private fun wrap360(degrees: Double): Double {
        val v = degrees % 360.0
        return if (v < 0) v + 360.0 else v
    }
}
