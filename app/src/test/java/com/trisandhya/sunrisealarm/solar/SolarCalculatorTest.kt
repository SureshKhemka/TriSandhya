package com.trisandhya.sunrisealarm.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Verifies the local NOAA implementation against the US Naval Observatory.
 *
 * Expected values come from the USNO `rstt/oneday` service, which publishes to
 * minute precision. USNO was chosen deliberately over api.sunrise-sunset.org, the
 * service this calculator replaced: cross-checking the two showed the API runs
 * about two minutes wide on the day length (sunrise early, sunset late) at every
 * latitude tested, while this implementation agrees with USNO to within seconds.
 * Anchoring on the API would have baked that error into the suite.
 *
 * Tolerance is 60 seconds, which covers USNO's rounding to the nearest minute.
 */
class SolarCalculatorTest {

    private val toleranceSeconds = 60L

    private fun assertTimeNear(expected: LocalTime, actual: ZonedDateTime?, label: String) {
        assertNotNull("$label should be defined", actual)
        val actualTime = actual!!.toLocalTime()
        val diff = abs(Duration.between(expected, actualTime).seconds)
        assertTrue(
            "$label expected ~$expected but was $actualTime (off by ${diff}s)",
            diff <= toleranceSeconds
        )
    }

    // USNO: rise 06:25, transit 12:29, set 18:33 IST
    @Test
    fun `delhi equinox matches usno`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 3, 20),
            28.6139, 77.2090,
            ZoneId.of("Asia/Kolkata")
        )

        assertTimeNear(LocalTime.of(6, 25), times.sunrise, "Delhi sunrise")
        assertTimeNear(LocalTime.of(12, 29), times.solarNoon, "Delhi solar noon")
        assertTimeNear(LocalTime.of(18, 33), times.sunset, "Delhi sunset")
    }

    // USNO: rise 04:43, transit 13:02, set 21:22 BST
    @Test
    fun `london summer solstice matches usno`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 6, 21),
            51.5074, -0.1278,
            ZoneId.of("Europe/London")
        )

        assertTimeNear(LocalTime.of(4, 43), times.sunrise, "London sunrise")
        assertTimeNear(LocalTime.of(13, 2), times.solarNoon, "London solar noon")
        assertTimeNear(LocalTime.of(21, 22), times.sunset, "London sunset")
    }

    // USNO: rise 07:17, transit 11:54, set 16:32 EST
    @Test
    fun `new york winter solstice matches usno`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 12, 21),
            40.7128, -74.0060,
            ZoneId.of("America/New_York")
        )

        assertTimeNear(LocalTime.of(7, 17), times.sunrise, "New York sunrise")
        assertTimeNear(LocalTime.of(11, 54), times.solarNoon, "New York solar noon")
        assertTimeNear(LocalTime.of(16, 32), times.sunset, "New York sunset")
    }

    // USNO: rise 06:00, transit 12:36, set 19:13 IST
    @Test
    fun `mumbai matches usno`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 6, 1),
            19.0760, 72.8777,
            ZoneId.of("Asia/Kolkata")
        )

        assertTimeNear(LocalTime.of(6, 0), times.sunrise, "Mumbai sunrise")
        assertTimeNear(LocalTime.of(12, 36), times.solarNoon, "Mumbai solar noon")
        assertTimeNear(LocalTime.of(19, 13), times.sunset, "Mumbai sunset")
    }

    // USNO: rise 05:24, transit 11:37, set 17:49 JST
    @Test
    fun `tokyo matches usno`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 9, 15),
            35.6762, 139.6503,
            ZoneId.of("Asia/Tokyo")
        )

        assertTimeNear(LocalTime.of(5, 24), times.sunrise, "Tokyo sunrise")
        assertTimeNear(LocalTime.of(11, 37), times.solarNoon, "Tokyo solar noon")
        assertTimeNear(LocalTime.of(17, 49), times.sunset, "Tokyo sunset")
    }

    /**
     * The spring-forward day: New York jumps 02:00 to 03:00, which is where a naive
     * "midnight plus N minutes" conversion lands on a wall-clock time that does not
     * exist.
     *
     * USNO: rise 07:15, transit 13:06, set 18:58 EDT
     */
    @Test
    fun `dst transition day resolves to a real instant`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 3, 10),
            40.7128, -74.0060,
            ZoneId.of("America/New_York")
        )

        assertTimeNear(LocalTime.of(7, 15), times.sunrise, "NYC DST-day sunrise")
        assertTimeNear(LocalTime.of(13, 6), times.solarNoon, "NYC DST-day solar noon")
        assertTimeNear(LocalTime.of(18, 58), times.sunset, "NYC DST-day sunset")
    }

    /**
     * Structural invariant that holds wherever the sun rises and sets, independent
     * of any external reference.
     */
    @Test
    fun `solar noon is midway between sunrise and sunset`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 9, 15),
            35.6762, 139.6503,
            ZoneId.of("Asia/Tokyo")
        )

        val sunrise = times.sunrise!!.toInstant().toEpochMilli()
        val sunset = times.sunset!!.toInstant().toEpochMilli()
        val noon = times.solarNoon.toInstant().toEpochMilli()

        val driftMinutes = abs(noon - (sunrise + sunset) / 2) / 60_000.0
        assertTrue("solar noon drifted $driftMinutes min from midpoint", driftMinutes < 1.0)
    }

    @Test
    fun `polar night has no sunrise or sunset`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 12, 21),
            69.6492, 18.9553,
            ZoneId.of("Europe/Oslo")
        )

        assertNull("Tromso has no sunrise at winter solstice", times.sunrise)
        assertNull("Tromso has no sunset at winter solstice", times.sunset)
        assertNotNull("solar noon is always defined", times.solarNoon)
    }

    @Test
    fun `midnight sun has no sunset`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 6, 21),
            69.6492, 18.9553,
            ZoneId.of("Europe/Oslo")
        )

        assertNull("Tromso has no sunset at summer solstice", times.sunset)
    }

    @Test
    fun `times are returned in the requested zone and on the requested date`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val times = SolarCalculator.compute(LocalDate.of(2024, 6, 1), 19.0760, 72.8777, zone)

        assertEquals(zone, times.sunrise!!.zone)
        assertEquals(LocalDate.of(2024, 6, 1), times.sunrise!!.toLocalDate())
    }

    /**
     * Southern hemisphere, to catch a sign error in the declination term that
     * northern-only cases would not surface.
     */
    @Test
    fun `southern hemisphere has a long december day`() {
        val times = SolarCalculator.compute(
            LocalDate.of(2024, 12, 21),
            -33.8688, 151.2093,
            ZoneId.of("Australia/Sydney")
        )

        val dayLength = Duration.between(times.sunrise, times.sunset).toMinutes()
        // Sydney's summer solstice day runs about 14h 25m.
        assertTrue("expected a long summer day, got $dayLength min", dayLength in 850..880)
    }
}
