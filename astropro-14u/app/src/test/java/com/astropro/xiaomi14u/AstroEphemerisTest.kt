package com.astropro.xiaomi14u

import com.astropro.xiaomi14u.ephemeris.AstroEphemeris
import com.astropro.xiaomi14u.ephemeris.ObserverLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AstroEphemerisTest {

    @Test
    fun julianDate_j2000Epoch_isExact() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2000, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val jd = AstroEphemeris.unixMillisToJulianDate(cal.timeInMillis)
        assertEquals(2451545.0, jd, 1e-4)
    }

    @Test
    fun sunRaDec_vernalEquinox_isNearZero() {
        // March 20, 2026 14:46 UTC vernal equinox
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.MARCH, 20, 14, 46, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val jd = AstroEphemeris.unixMillisToJulianDate(cal.timeInMillis)
        val (ra, dec) = AstroEphemeris.sunRaDec(jd)
        assertTrue("Sun Dec at equinox should be near 0° (was $dec)", kotlin.math.abs(dec) < 0.5)
        assertTrue("Sun RA at equinox should be near 0h or 24h (was $ra)", ra < 0.2 || ra > 23.8)
    }

    @Test
    fun polarisAltitude_matchesObserverLatitude() {
        val picDuMidi = ObserverLocation("Pic du Midi", 42.9364, 0.1428)
        val jd = AstroEphemeris.unixMillisToJulianDate(1_767_225_600_000L) // 2026-01-01 00:00 UTC
        val horiz = AstroEphemeris.equatorialToHorizontal(2.5303, 89.2641, jd, picDuMidi.latitudeDeg, picDuMidi.longitudeDeg)
        assertEquals(picDuMidi.latitudeDeg, horiz.altitudeDeg, 1.0)
        assertTrue("Polaris azimuth should be near North (was ${horiz.azimuthDeg})", horiz.azimuthDeg < 2.0 || horiz.azimuthDeg > 358.0)
    }

    @Test
    fun targetCatalogue_contains20TargetsAndValidCoordinates() {
        assertEquals(20, AstroEphemeris.TARGET_CATALOGUE.size)
        val loc = AstroEphemeris.PRESET_LOCATIONS.first()
        val visList = AstroEphemeris.computeTargetVisibilities(1_780_272_000_000L, loc)
        assertEquals(20, visList.size)
        for (v in visList) {
            assertTrue(v.altitudeDeg in -90.0..90.0)
            assertTrue(v.azimuthDeg in 0.0..360.0)
            assertTrue(v.recommendedNpfSec in 0.05..60.0)
        }
    }

    @Test
    fun moonSummary_andTwilightSummary_returnValidRanges() {
        val loc = AstroEphemeris.PRESET_LOCATIONS.first()
        val ts = 1_769_040_000_000L // Jan 22, 2026 UTC
        val moon = AstroEphemeris.computeMoonSummary(ts, loc)
        assertTrue(moon.illuminationPercent in 0.0..100.0)
        assertTrue(moon.ageDays in 0.0..30.0)
        assertTrue(moon.deepSkyScore in 0..100)

        val twilight = AstroEphemeris.computeTwilightSummary(ts, loc)
        assertTrue("Winter night at Pic du Midi should have > 8h astronomical darkness", twilight.darkNightHours > 8.0)
    }
}
