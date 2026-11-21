package com.astropro.xiaomi14u.ephemeris

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ObserverLocation(
    val name: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double
)

data class HorizontalCoords(
    val altitudeDeg: Double,
    val azimuthDeg: Double
)

data class TwilightSummary(
    val sunsetLocal: String,
    val civilDuskLocal: String,
    val nauticalDuskLocal: String,
    val astroDuskLocal: String,
    val astroDawnLocal: String,
    val nauticalDawnLocal: String,
    val civilDawnLocal: String,
    val sunriseLocal: String,
    val darkNightHours: Double,
    val currentSunAltDeg: Double
)

data class MoonSummary(
    val illuminationPercent: Double,
    val ageDays: Double,
    val phaseNameFr: String,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val moonriseLocal: String,
    val moonsetLocal: String,
    val deepSkyScore: Int // 0..100
)

enum class TargetCategory(val labelFr: String) {
    ALL("Tous"),
    MILKY_WAY("Voie Lactée"),
    DEEP_SKY("Ciel Profond"),
    SOLAR_SYSTEM("Planètes / Lune")
}

data class CelestialTarget(
    val id: String,
    val nameFr: String,
    val catalogCode: String,
    val category: TargetCategory,
    val raHours: Double,     // J2000 Right Ascension in hours (0..24); dynamic for solar system
    val decDeg: Double,      // J2000 Declination in degrees (-90..+90)
    val recommendedEqMm: Int, // 12, 23, 75, or 120mm Xiaomi 14 Ultra lens
    val magnitude: String,
    val notesFr: String,
    val isDynamicSolarSystem: Boolean = false
)

data class TargetVisibility(
    val target: CelestialTarget,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val compassLabel: String,
    val statusFr: String,
    val isVisible: Boolean,
    val isOptimal: Boolean,
    val transitLocal: String,
    val recommendedLensBadge: String,
    val recommendedNpfSec: Double
)

/**
 * 100% Offline Astronomical Ephemeris Engine (Jean Meeus "Astronomical Algorithms").
 * Works in full airplane mode in mountains/deserts with GPS or manual location presets.
 */
object AstroEphemeris {

    val PRESET_LOCATIONS: List<ObserverLocation> = listOf(
        ObserverLocation("Pic du Midi (Pyrénées)", 42.9364, 0.1428),
        ObserverLocation("Paris", 48.8566, 2.3522),
        ObserverLocation("Chamonix (Alpes)", 45.9237, 6.8694),
        ObserverLocation("Luberon (Provence)", 43.8295, 5.4210),
        ObserverLocation("Lyon", 45.7640, 4.8357),
        ObserverLocation("Toulouse", 43.6047, 1.4442),
        ObserverLocation("Bordeaux", 44.8378, -0.5792),
        ObserverLocation("Marseille", 43.2965, 5.3698),
        ObserverLocation("Bretagne (Brest)", 48.3904, -4.4861),
        ObserverLocation("Teide (Tenerife)", 28.2723, -16.6425),
        ObserverLocation("Atacama (Chili)", -22.9576, -67.7876),
        ObserverLocation("Mauna Kea (Hawaï)", 19.8207, -155.4681)
    )

    // Curated catalogue of 20 flagship astrophotography targets mapped to Xiaomi 14 Ultra's 4 Leica lenses
    val TARGET_CATALOGUE: List<CelestialTarget> = listOf(
        CelestialTarget("sgr_a", "Centre Galactique (Voie Lactée)", "Sgr A*", TargetCategory.MILKY_WAY, 17.7611, -29.0078, 23, "Mag -", "Arche galactique idéale au 23mm f/1.63 LYT-900 ou 12mm UGA"),
        CelestialTarget("cygnus", "Voie Lactée — Cygne & Sadr", "Cyg / IC 1318", TargetCategory.MILKY_WAY, 20.3704, 40.2567, 23, "Mag -", "Région riche en nébuleuses H-alpha & poussières sombres"),
        CelestialTarget("m31", "Galaxie d'Andromède", "M31 / NGC 224", TargetCategory.DEEP_SKY, 0.7123, 41.2687, 120, "3.4", "Cible reine au Périscope 120mm 5x (stack 40–60 poses)"),
        CelestialTarget("m42", "Grande Nébuleuse d'Orion", "M42 / NGC 1976", TargetCategory.DEEP_SKY, 5.5881, -5.3911, 120, "4.0", "Brillante même sous ciel périurbain ; cœur HDR au 120mm ou 75mm"),
        CelestialTarget("m45", "Amas des Pléiades", "M45", TargetCategory.DEEP_SKY, 3.7833, 24.1167, 120, "1.6", "Nébulosités bleues par réflexion au 75mm ou 120mm"),
        CelestialTarget("m8", "Nébuleuse de la Lagune", "M8 / NGC 6523", TargetCategory.DEEP_SKY, 18.0600, -24.3833, 120, "6.0", "Joyau d'été du Sagittaire, magnifique au 120mm 5x"),
        CelestialTarget("m20", "Nébuleuse Trifide", "M20 / NGC 6514", TargetCategory.DEEP_SKY, 18.0433, -23.0333, 120, "6.3", "Dans le même champ que M8 au 75mm Télé 3.2x"),
        CelestialTarget("m13", "Grand Amas d'Hercule", "M13 / NGC 6205", TargetCategory.DEEP_SKY, 16.6949, 36.4613, 120, "5.8", "Amas globulaire de 300 000 étoiles, 120mm 5x"),
        CelestialTarget("m51", "Galaxie du Tourbillon", "M51 / NGC 5194", TargetCategory.DEEP_SKY, 13.4979, 47.1953, 120, "8.4", "Galaxies spirales en interaction, ciel noir recommandé"),
        CelestialTarget("m57", "Nébuleuse de l'Anneau", "M57 / NGC 6720", TargetCategory.DEEP_SKY, 18.8931, 33.0292, 120, "8.8", "Nébuleuse planétaire de la Lyre, poses courtes en stack"),
        CelestialTarget("ngc7000", "Nébuleuse Amérique du Nord", "NGC 7000", TargetCategory.DEEP_SKY, 20.9800, 44.3333, 75, "4.0", "Vaste nuage H-alpha près de Deneb, parfait au 75mm 3.2x"),
        CelestialTarget("ngc2244", "Nébuleuse de la Rosette", "NGC 2244 / C49", TargetCategory.DEEP_SKY, 6.5317, 4.9500, 75, "4.8", "Amas & couronne gazeuse hivernale au 75mm ou 120mm"),
        CelestialTarget("ngc869", "Double Amas de Persée", "NGC 869 / 884", TargetCategory.DEEP_SKY, 2.3167, 57.1500, 75, "3.7", "Paires d'amas ouverts scintillants, 75mm Télé 3.2x"),
        CelestialTarget("meteors", "Grand Champ Perséides / Géminides", "Radiant", TargetCategory.MILKY_WAY, 3.1000, 58.0000, 12, "Mag -", "12mm UGA 0.5x en mode Star Trails ou Timelapse continu"),
        CelestialTarget("polaris", "Étoile Polaire (Alignement / Filés)", "α UMi", TargetCategory.MILKY_WAY, 2.5303, 89.2641, 12, "2.0", "Centre de rotation céleste Nord pour Star Trails circulaires au 12mm"),
        CelestialTarget("moon", "Lune (Surface & Cratères)", "Lune", TargetCategory.SOLAR_SYSTEM, 0.0, 0.0, 120, "-12.6", "Mode Lune/Planètes Lucky Imaging 60–100 poses + Ondelettes au 120mm", isDynamicSolarSystem = true),
        CelestialTarget("jupiter", "Jupiter & Lunes Galiléennes", "Planète V", TargetCategory.SOLAR_SYSTEM, 0.0, 0.0, 120, "-2.5", "Lucky Imaging ISO bas + vitesse 1/250s au 120mm 5x", isDynamicSolarSystem = true),
        CelestialTarget("saturn", "Saturne & Anneaux", "Planète VI", TargetCategory.SOLAR_SYSTEM, 0.0, 0.0, 120, "+0.5", "Lucky Imaging rafale rapide au 120mm 5x", isDynamicSolarSystem = true),
        CelestialTarget("mars", "Mars", "Planète IV", TargetCategory.SOLAR_SYSTEM, 0.0, 0.0, 120, "-1.2", "Lucky Imaging rafale rapide à l'opposition", isDynamicSolarSystem = true),
        CelestialTarget("venus", "Vénus (Phases & Croissant)", "Planète II", TargetCategory.SOLAR_SYSTEM, 0.0, 0.0, 120, "-4.1", "Crépuscule ou aube, vitesse très rapide au 120mm 5x", isDynamicSolarSystem = true)
    )

    /**
     * Converts Unix epoch milliseconds to Julian Date (UTC).
     */
    fun unixMillisToJulianDate(epochMillis: Long): Double {
        return (epochMillis / 86_400_000.0) + 2440587.5
    }

    /**
     * Greenwich Mean Sidereal Time in degrees (Jean Meeus eq. 12.4).
     */
    fun gmstDegrees(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val gmst = 280.46061837 +
            360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t -
            (t * t * t) / 38710000.0
        return normalize360(gmst)
    }

    /**
     * Local Sidereal Time in degrees for observer east-positive longitude.
     */
    fun lstDegrees(jd: Double, lonDeg: Double): Double = normalize360(gmstDegrees(jd) + lonDeg)

    /**
     * Low-precision Solar equatorial coordinates (RA in hours, Dec in degrees), accurate to ~0.01°.
     */
    fun sunRaDec(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = Math.toRadians(normalize360(357.529 + 0.98560028 * d))
        val q = normalize360(280.459 + 0.98564736 * d)
        val lambda = Math.toRadians(normalize360(q + 1.915 * sin(g) + 0.020 * sin(2.0 * g)))
        val eps = Math.toRadians(23.439 - 0.00000036 * d)
        val raRad = atan2(cos(eps) * sin(lambda), cos(lambda))
        val decRad = asin((sin(eps) * sin(lambda)).coerceIn(-1.0, 1.0))
        val raHours = normalize360(Math.toDegrees(raRad)) / 15.0
        return Pair(raHours, Math.toDegrees(decRad))
    }

    /**
     * Meeus Lunar equatorial coordinates (RA in hours, Dec in degrees), accurate to ~0.2°.
     */
    fun moonRaDec(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val l0 = normalize360(218.316 + 13.176396 * d)
        val m = Math.toRadians(normalize360(134.963 + 13.064993 * d))
        val f = Math.toRadians(normalize360(93.272 + 13.229350 * d))
        val dElong = Math.toRadians(normalize360(297.850 + 12.190749 * d))

        val lon = Math.toRadians(
            normalize360(
                l0 + 6.289 * sin(m) + 1.274 * sin(2.0 * dElong - m) +
                    0.658 * sin(2.0 * dElong) + 0.214 * sin(2.0 * m)
            )
        )
        val lat = Math.toRadians(5.128 * sin(f) + 0.280 * sin(m + f))
        val eps = Math.toRadians(23.439 - 0.00000036 * d)

        val x = cos(lat) * cos(lon)
        val y = cos(eps) * cos(lat) * sin(lon) - sin(eps) * sin(lat)
        val z = sin(eps) * cos(lat) * sin(lon) + cos(eps) * sin(lat)

        val raHours = normalize360(Math.toDegrees(atan2(y, x))) / 15.0
        val decDeg = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
        return Pair(raHours, decDeg)
    }

    /**
     * Approximate heliocentric-to-geocentric Keplerian planet RA/Dec for bright naked-eye planets
     * (accurate to ~1-2° for 2020-2035 — plenty for finding planets in the Sky Compass!).
     */
    fun planetRaDec(planetId: String, jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        data class Elements(val a: Double, val e: Double, val inc: Double, val omega: Double, val wBar: Double, val l0: Double, val n: Double)

        fun helioXYZ(el: Elements): Triple<Double, Double, Double> {
            val l = normalize360(el.l0 + el.n * d)
            val m = Math.toRadians(normalize360(l - el.wBar))
            val eRad = m + el.e * sin(m) * (1.0 + el.e * cos(m))
            val xv = el.a * (cos(eRad) - el.e)
            val yv = el.a * sqrt(1.0 - el.e * el.e) * sin(eRad)
            val v = atan2(yv, xv)
            val r = sqrt(xv * xv + yv * yv)
            val nRad = Math.toRadians(el.omega)
            val iRad = Math.toRadians(el.inc)
            val wRad = Math.toRadians(el.wBar - el.omega)
            val xh = r * (cos(nRad) * cos(v + wRad) - sin(nRad) * sin(v + wRad) * cos(iRad))
            val yh = r * (sin(nRad) * cos(v + wRad) + cos(nRad) * sin(v + wRad) * cos(iRad))
            val zh = r * (sin(v + wRad) * sin(iRad))
            return Triple(xh, yh, zh)
        }

        val earth = Elements(1.00000, 0.01671, 0.0000, 0.0, 102.937, 100.464, 0.98560767)
        val target = when (planetId) {
            "venus" -> Elements(0.72333, 0.00677, 3.3947, 76.680, 131.564, 181.979, 1.60213034)
            "mars" -> Elements(1.52368, 0.09340, 1.8497, 49.558, 336.060, 355.433, 0.52403295)
            "jupiter" -> Elements(5.20260, 0.04849, 1.3033, 100.464, 14.331, 34.351, 0.08309121)
            "saturn" -> Elements(9.55491, 0.05551, 2.4889, 113.665, 93.057, 50.077, 0.03345965)
            else -> return moonRaDec(jd)
        }

        val (xe, ye, ze) = helioXYZ(earth)
        val (xp, yp, zp) = helioXYZ(target)
        val xg = xp - xe
        val yg = yp - ye
        val zg = zp - ze

        val eps = Math.toRadians(23.4393)
        val xeq = xg
        val yeq = yg * cos(eps) - zg * sin(eps)
        val zeq = yg * sin(eps) + zg * cos(eps)

        val raHours = normalize360(Math.toDegrees(atan2(yeq, xeq))) / 15.0
        val decDeg = Math.toDegrees(atan2(zeq, sqrt(xeq * xeq + yeq * yeq)))
        return Pair(raHours, decDeg)
    }

    /**
     * Converts Equatorial (RA hours, Dec deg) to Horizontal (Alt deg, Az deg clockwise from North).
     */
    fun equatorialToHorizontal(
        raHours: Double,
        decDeg: Double,
        jd: Double,
        latDeg: Double,
        lonDeg: Double
    ): HorizontalCoords {
        val lst = lstDegrees(jd, lonDeg)
        val haRad = Math.toRadians(normalize360(lst - raHours * 15.0))
        val decRad = Math.toRadians(decDeg)
        val latRad = Math.toRadians(latDeg)

        val sinAlt = (sin(decRad) * sin(latRad) + cos(decRad) * cos(latRad) * cos(haRad)).coerceIn(-1.0, 1.0)
        val altRad = asin(sinAlt)

        val y = -sin(haRad) * cos(decRad)
        val x = sin(decRad) * cos(latRad) - cos(decRad) * sin(latRad) * cos(haRad)
        val azDeg = normalize360(Math.toDegrees(atan2(y, x)))
        return HorizontalCoords(Math.toDegrees(altRad), azDeg)
    }

    fun computeMoonSummary(epochMillis: Long, location: ObserverLocation): MoonSummary {
        val jd = unixMillisToJulianDate(epochMillis)
        val (sunRa, sunDec) = sunRaDec(jd)
        val (moonRa, moonDec) = moonRaDec(jd)

        // Angular elongation between Sun and Moon
        val sRa = Math.toRadians(sunRa * 15.0)
        val sDec = Math.toRadians(sunDec)
        val mRa = Math.toRadians(moonRa * 15.0)
        val mDec = Math.toRadians(moonDec)
        val cosElong = (sin(sDec) * sin(mDec) + cos(sDec) * cos(mDec) * cos(mRa - sRa)).coerceIn(-1.0, 1.0)
        val elong = acos(cosElong)
        val illumPct = ((1.0 - cos(elong)) * 50.0).coerceIn(0.0, 100.0)

        // Synodic lunar age from known J2000 new moon epoch (JD 2451550.09765)
        val synodicMonth = 29.530588853
        val rawCycles = (jd - 2451550.09765) / synodicMonth
        val frac = rawCycles - floor(rawCycles)
        val ageDays = frac * synodicMonth

        val phaseName = when {
            ageDays < 1.2 || ageDays >= 28.3 -> "Nouvelle Lune"
            ageDays < 6.5 -> "Premier Croissant"
            ageDays < 8.4 -> "Premier Quartier"
            ageDays < 13.6 -> "Gibbeuse Croissante"
            ageDays < 16.0 -> "Pleine Lune"
            ageDays < 21.1 -> "Gibbeuse Décroissante"
            ageDays < 23.1 -> "Dernier Quartier"
            else -> "Dernier Croissant"
        }

        val horiz = equatorialToHorizontal(moonRa, moonDec, jd, location.latitudeDeg, location.longitudeDeg)
        val (riseStr, setStr) = scanRiseSet(epochMillis, location, -0.5) { testJd ->
            val (mr, md) = moonRaDec(testJd)
            equatorialToHorizontal(mr, md, testJd, location.latitudeDeg, location.longitudeDeg).altitudeDeg
        }

        // Deep-sky score (0..100): high when Moon is below horizon or near New Moon
        val score = if (horiz.altitudeDeg <= -2.0) {
            100
        } else {
            val altFactor = (horiz.altitudeDeg / 45.0).coerceIn(0.15, 1.0)
            (100.0 - illumPct * 0.85 * altFactor).roundToInt().coerceIn(5, 100)
        }

        return MoonSummary(
            illuminationPercent = illumPct,
            ageDays = ageDays,
            phaseNameFr = phaseName,
            altitudeDeg = horiz.altitudeDeg,
            azimuthDeg = horiz.azimuthDeg,
            moonriseLocal = riseStr,
            moonsetLocal = setStr,
            deepSkyScore = score
        )
    }

    fun computeTwilightSummary(epochMillis: Long, location: ObserverLocation): TwilightSummary {
        val jdNow = unixMillisToJulianDate(epochMillis)
        val (sRa, sDec) = sunRaDec(jdNow)
        val sunNow = equatorialToHorizontal(sRa, sDec, jdNow, location.latitudeDeg, location.longitudeDeg)

        // Anchor scan window to local noon -> next local noon so evening dusk & morning dawn belong to tonight!
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            if (get(Calendar.HOUR_OF_DAY) < 12) add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val noonMs = cal.timeInMillis

        fun scanSunCrossing(thresholdDeg: Double): Pair<Long?, Long?> {
            var duskMs: Long? = null
            var dawnMs: Long? = null
            val stepMs = 5 * 60_000L // 5-minute steps over 24 hours
            var prevAlt = sunAltAt(noonMs, location)
            for (i in 1..288) {
                val t = noonMs + i * stepMs
                val alt = sunAltAt(t, location)
                if (prevAlt >= thresholdDeg && alt < thresholdDeg && duskMs == null) {
                    val frac = (prevAlt - thresholdDeg) / max(1e-6, prevAlt - alt)
                    duskMs = (t - stepMs) + (frac * stepMs).toLong()
                }
                if (prevAlt < thresholdDeg && alt >= thresholdDeg && dawnMs == null) {
                    val frac = (thresholdDeg - prevAlt) / max(1e-6, alt - prevAlt)
                    dawnMs = (t - stepMs) + (frac * stepMs).toLong()
                }
                prevAlt = alt
            }
            return Pair(duskMs, dawnMs)
        }

        val (sunset, sunrise) = scanSunCrossing(-0.833)
        val (civDusk, civDawn) = scanSunCrossing(-6.0)
        val (nautDusk, nautDawn) = scanSunCrossing(-12.0)
        val (astroDusk, astroDawn) = scanSunCrossing(-18.0)

        val darkHours = if (astroDusk != null && astroDawn != null && astroDawn > astroDusk) {
            (astroDawn - astroDusk) / 3_600_000.0
        } else 0.0

        return TwilightSummary(
            sunsetLocal = formatClock(sunset),
            civilDuskLocal = formatClock(civDusk),
            nauticalDuskLocal = formatClock(nautDusk),
            astroDuskLocal = formatClock(astroDusk),
            astroDawnLocal = formatClock(astroDawn),
            nauticalDawnLocal = formatClock(nautDawn),
            civilDawnLocal = formatClock(civDawn),
            sunriseLocal = formatClock(sunrise),
            darkNightHours = darkHours,
            currentSunAltDeg = sunNow.altitudeDeg
        )
    }

    fun computeTargetVisibilities(
        epochMillis: Long,
        location: ObserverLocation
    ): List<TargetVisibility> {
        val jd = unixMillisToJulianDate(epochMillis)
        return TARGET_CATALOGUE.map { t ->
            val (ra, dec) = if (t.isDynamicSolarSystem) {
                if (t.id == "moon") moonRaDec(jd) else planetRaDec(t.id, jd)
            } else {
                Pair(t.raHours, t.decDeg)
            }

            val hz = equatorialToHorizontal(ra, dec, jd, location.latitudeDeg, location.longitudeDeg)
            val isVisible = hz.altitudeDeg > 5.0
            val isOptimal = hz.altitudeDeg >= 25.0

            // Compute next transit time (LST == RA*15)
            val lstNow = lstDegrees(jd, location.longitudeDeg)
            val deltaDeg = normalize360(ra * 15.0 - lstNow)
            val hoursToTransit = (deltaDeg / 15.0) * 0.99726957
            val transitMs = epochMillis + (hoursToTransit * 3_600_000.0).toLong()

            val statusFr = when {
                hz.altitudeDeg >= 35.0 -> "Optimal (${hz.altitudeDeg.roundToInt()}°)"
                hz.altitudeDeg >= 15.0 -> "Visible (${hz.altitudeDeg.roundToInt()}°)"
                hz.altitudeDeg > 0.0 -> "Bas horizon (${hz.altitudeDeg.roundToInt()}°)"
                else -> "Sous l'horizon"
            }

            val badge = when (t.recommendedEqMm) {
                12 -> "0.5"
                23 -> "1"
                75 -> "3.2"
                else -> "5"
            }

            // Approximate NPF exposure for Xiaomi 14 Ultra's matching physical lens
            val (physF, ap, pitch) = when (t.recommendedEqMm) {
                12 -> Triple(2.0, 1.8, 1.4)
                23 -> Triple(8.7, 1.63, 3.2)
                75 -> Triple(11.5, 1.8, 1.4)
                else -> Triple(19.0, 2.5, 1.4)
            }
            val npf = com.astropro.xiaomi14u.camera.NpfCalculator.computeNpfSeconds(ap, pitch, physF, dec)

            TargetVisibility(
                target = t,
                altitudeDeg = hz.altitudeDeg,
                azimuthDeg = hz.azimuthDeg,
                compassLabel = azimuthToCompassFr(hz.azimuthDeg),
                statusFr = statusFr,
                isVisible = isVisible,
                isOptimal = isOptimal,
                transitLocal = formatClock(transitMs),
                recommendedLensBadge = badge,
                recommendedNpfSec = npf
            )
        }
    }

    fun azimuthToCompassFr(azDeg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
        val idx = ((normalize360(azDeg) + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    private fun sunAltAt(epochMillis: Long, loc: ObserverLocation): Double {
        val jd = unixMillisToJulianDate(epochMillis)
        val (ra, dec) = sunRaDec(jd)
        return equatorialToHorizontal(ra, dec, jd, loc.latitudeDeg, loc.longitudeDeg).altitudeDeg
    }

    private fun scanRiseSet(
        epochMillis: Long,
        location: ObserverLocation,
        thresholdDeg: Double,
        altAtJd: (Double) -> Double
    ): Pair<String, String> {
        val stepMs = 10 * 60_000L
        var riseMs: Long? = null
        var setMs: Long? = null
        var prevAlt = altAtJd(unixMillisToJulianDate(epochMillis))
        for (i in 1..144) {
            val t = epochMillis + i * stepMs
            val alt = altAtJd(unixMillisToJulianDate(t))
            if (prevAlt < thresholdDeg && alt >= thresholdDeg && riseMs == null) {
                riseMs = t
            }
            if (prevAlt >= thresholdDeg && alt < thresholdDeg && setMs == null) {
                setMs = t
            }
            prevAlt = alt
        }
        return Pair(formatClock(riseMs), formatClock(setMs))
    }

    private fun formatClock(epochMillis: Long?): String {
        if (epochMillis == null) return "--:--"
        val c = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = epochMillis }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun normalize360(deg: Double): Double {
        var x = deg % 360.0
        if (x < 0.0) x += 360.0
        return x
    }
}
