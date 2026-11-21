package com.astropro.xiaomi14u.ephemeris

import com.astropro.xiaomi14u.camera.CaptureMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

data class CameraOpticalFov(
    val hFovDeg: Double,
    val vFovDeg: Double
)

data class ArProjectedPoint(
    val u: Float,       // [0..1] left -> right in viewfinder
    val v: Float,       // [0..1] top -> bottom in viewfinder
    val inFront: Boolean,
    val inViewport: Boolean,
    val angularSeparationDeg: Double
)

data class ArProjectedTarget(
    val id: String,
    val shortLabel: String,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val u: Float,
    val v: Float,
    val isGuided: Boolean
)

data class ArGuidanceInfo(
    val targetId: String,
    val targetLabel: String,
    val deltaAzDeg: Double,   // + right, - left
    val deltaAltDeg: Double,  // + up, - down
    val angularSeparationDeg: Double,
    val isCentered: Boolean,
    val isBelowHorizon: Boolean,
    val pillText: String
)

data class ArSkyOverlayFrame(
    val milkyWaySegments: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
    val visibleTargets: List<ArProjectedTarget>,
    val guidance: ArGuidanceInfo?
)

/**
 * Pure 3D Astronomical AR Sky Projection Engine for the Xiaomi 14 Ultra 4:3 Portrait Viewfinder.
 *
 * Computes real optical portrait FOV from physical sensor dimensions & focal length,
 * projects the J2000 Milky Way Galactic Plane (b = 0°) and 12 flagship targets into
 * normalized viewfinder screen coordinates (u, v), and computes off-screen guidance offsets.
 */
object ArSkyProjector {

    // Exact 12 flagship AR viewfinder targets (concise labels to keep the viewfinder ultra-clean)
    val AR_TARGET_SHORT_LABELS: LinkedHashMap<String, String> = linkedMapOf(
        "polaris" to "Polaris",
        "sgr_a" to "Centre Galactique",
        "m31" to "M31 Andromède",
        "m42" to "M42 Orion",
        "m45" to "M45 Pléiades",
        "m8" to "M8 Lagune",
        "m13" to "M13",
        "moon" to "Lune",
        "jupiter" to "Jupiter",
        "saturn" to "Saturne",
        "mars" to "Mars",
        "venus" to "Vénus"
    )

    // IAU J2000 Galactic Coordinate constants
    private const val ALPHA_NGP_DEG = 192.85948
    private const val DELTA_NGP_DEG = 27.12825
    private const val L_NCP_DEG = 122.93192

    /**
     * Computes real optical horizontal & vertical FOV in portrait 3:4 orientation
     * from physical lens focal length & sensor size (mm).
     * In portrait 4:3, the shorter sensor side spans horizontally (hfov) and the longer side spans vertically (vfov).
     */
    fun computePortraitFov(
        focalLengthMm: Float,
        sensorWidthMm: Float,
        sensorHeightMm: Float
    ): CameraOpticalFov {
        val f = focalLengthMm.toDouble().coerceAtLeast(0.5)
        val rawW = sensorWidthMm.toDouble().coerceAtLeast(1.0)
        val rawH = sensorHeightMm.toDouble().coerceAtLeast(1.0)
        val shortSide = min(rawW, rawH)
        val longSide = max(rawW, rawH)
        val hfov = Math.toDegrees(2.0 * atan(shortSide / (2.0 * f)))
        val vfov = Math.toDegrees(2.0 * atan(longSide / (2.0 * f)))
        return CameraOpticalFov(
            hFovDeg = hfov.coerceIn(4.0, 140.0),
            vFovDeg = vfov.coerceIn(5.0, 150.0)
        )
    }

    /**
     * Converts J2000 Galactic coordinates (lDeg, bDeg) to Equatorial (raHours, decDeg).
     */
    fun galacticToEquatorial(lDeg: Double, bDeg: Double): Pair<Double, Double> {
        val l = Math.toRadians(lDeg)
        val b = Math.toRadians(bDeg)
        val dNgp = Math.toRadians(DELTA_NGP_DEG)
        val dl = Math.toRadians(L_NCP_DEG) - l

        val sinDec = (sin(b) * sin(dNgp) + cos(b) * cos(dNgp) * cos(dl)).coerceIn(-1.0, 1.0)
        val decDeg = Math.toDegrees(asin(sinDec))

        val y = cos(b) * sin(dl)
        val x = sin(b) * cos(dNgp) - cos(b) * sin(dNgp) * cos(dl)
        var raDeg = (ALPHA_NGP_DEG + Math.toDegrees(atan2(y, x))) % 360.0
        if (raDeg < 0.0) raDeg += 360.0
        return Pair(raDeg / 15.0, decDeg)
    }

    private data class Vec3(val e: Double, val n: Double, val u: Double) {
        fun dot(o: Vec3): Double = e * o.e + n * o.n + u * o.u
    }

    private fun horizontalToEnu(altDeg: Double, azDeg: Double): Vec3 {
        val alt = Math.toRadians(altDeg)
        val az = Math.toRadians(azDeg)
        val cAlt = cos(alt)
        return Vec3(
            e = cAlt * sin(az),
            n = cAlt * cos(az),
            u = sin(alt)
        )
    }

    private data class CameraBasis(
        val forward: Vec3,
        val right: Vec3,
        val up: Vec3,
        val tanHalfH: Double,
        val tanHalfV: Double
    )

    private fun buildCameraBasis(
        camAzDeg: Double,
        camAltDeg: Double,
        camRollDeg: Double,
        fov: CameraOpticalFov
    ): CameraBasis {
        val f = horizontalToEnu(camAltDeg, camAzDeg)
        val azRad = Math.toRadians(camAzDeg)
        // Unrolled right vector (horizontal, +90° clockwise from camera azimuth)
        val r0 = Vec3(cos(azRad), -sin(azRad), 0.0)
        // Unrolled up vector: R0 x F
        val u0 = Vec3(
            e = r0.n * f.u - r0.u * f.n,
            n = r0.u * f.e - r0.e * f.u,
            u = r0.e * f.n - r0.n * f.e
        )
        // Apply camera roll (clockwise roll drops right edge toward -U0, tilts top toward +R0)
        val rho = Math.toRadians(camRollDeg)
        val cR = cos(rho)
        val sR = sin(rho)
        val r = Vec3(
            e = r0.e * cR - u0.e * sR,
            n = r0.n * cR - u0.n * sR,
            u = r0.u * cR - u0.u * sR
        )
        val u = Vec3(
            e = r0.e * sR + u0.e * cR,
            n = r0.n * sR + u0.n * cR,
            u = r0.u * sR + u0.u * cR
        )
        val tanH = tan(Math.toRadians(fov.hFovDeg * 0.5)).coerceAtLeast(1e-4)
        val tanV = tan(Math.toRadians(fov.vFovDeg * 0.5)).coerceAtLeast(1e-4)
        return CameraBasis(f, r, u, tanH, tanV)
    }

    /**
     * Projects a sky direction (objAltDeg, objAzDeg) into normalized viewfinder coordinates (u, v).
     */
    fun projectSkyPoint(
        objAltDeg: Double,
        objAzDeg: Double,
        camAzDeg: Double,
        camAltDeg: Double,
        camRollDeg: Double,
        fov: CameraOpticalFov
    ): ArProjectedPoint {
        val basis = buildCameraBasis(camAzDeg, camAltDeg, camRollDeg, fov)
        return projectWithBasis(objAltDeg, objAzDeg, basis)
    }

    private fun projectWithBasis(
        objAltDeg: Double,
        objAzDeg: Double,
        basis: CameraBasis
    ): ArProjectedPoint {
        val p = horizontalToEnu(objAltDeg, objAzDeg)
        val z = p.dot(basis.forward)
        val sepDeg = Math.toDegrees(acos(z.coerceIn(-1.0, 1.0)))
        if (z <= 0.08) {
            return ArProjectedPoint(
                u = -1f,
                v = -1f,
                inFront = false,
                inViewport = false,
                angularSeparationDeg = sepDeg
            )
        }
        val x = p.dot(basis.right)
        val y = p.dot(basis.up)
        val u = (0.5 + 0.5 * (x / z) / basis.tanHalfH).toFloat()
        val v = (0.5 - 0.5 * (y / z) / basis.tanHalfV).toFloat()
        val inView = u in 0.02f..0.98f && v in 0.02f..0.98f
        return ArProjectedPoint(
            u = u,
            v = v,
            inFront = true,
            inViewport = inView,
            angularSeparationDeg = sepDeg
        )
    }

    /**
     * Computes a complete AR Sky Overlay frame: Milky Way galactic plane arc segments,
     * visible flagship targets inside the lens FOV, and smart off-screen guidance pill.
     */
    fun projectOverlay(
        epochMillis: Long,
        location: ObserverLocation,
        camAzDeg: Double,
        camAltDeg: Double,
        camRollDeg: Double,
        fov: CameraOpticalFov,
        targets: List<TargetVisibility>,
        captureMode: CaptureMode,
        selectedTargetId: String?
    ): ArSkyOverlayFrame {
        val jd = AstroEphemeris.unixMillisToJulianDate(epochMillis)
        val basis = buildCameraBasis(camAzDeg, camAltDeg, camRollDeg, fov)

        // 1. Milky Way Galactic Plane curve (b = 0°, sampled every 10° of galactic longitude)
        val mwPoints = ArrayList<ArProjectedPoint>(37)
        for (step in 0..36) {
            val lDeg = (step * 10.0) % 360.0
            val (raH, decD) = galacticToEquatorial(lDeg, 0.0)
            val hz = AstroEphemeris.equatorialToHorizontal(raH, decD, jd, location.latitudeDeg, location.longitudeDeg)
            // Only project sky portions near/above horizon (-5° margin)
            if (hz.altitudeDeg < -5.0) {
                mwPoints.add(ArProjectedPoint(-1f, -1f, inFront = false, inViewport = false, angularSeparationDeg = 180.0))
            } else {
                mwPoints.add(projectWithBasis(hz.altitudeDeg, hz.azimuthDeg, basis))
            }
        }
        val mwSegments = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        for (i in 0 until mwPoints.size - 1) {
            val a = mwPoints[i]
            val b = mwPoints[i + 1]
            if (!a.inFront || !b.inFront) continue
            val aNear = a.u in -0.25f..1.25f && a.v in -0.25f..1.25f
            val bNear = b.u in -0.25f..1.25f && b.v in -0.25f..1.25f
            if (!aNear && !bNear) continue
            // Prevent wrap-around jumps across wide angles
            val dist = kotlin.math.hypot((a.u - b.u).toDouble(), (a.v - b.v).toDouble())
            if (dist < 0.75) {
                mwSegments.add(Pair(Pair(a.u, a.v), Pair(b.u, b.v)))
            }
        }

        // 2. Determine guided target (Polaris automatically in STAR_TRAILS mode, or user-chosen Ephemeris target)
        val guidedId: String? = when {
            captureMode == CaptureMode.STAR_TRAILS -> "polaris"
            !selectedTargetId.isNullOrBlank() -> selectedTargetId
            else -> null
        }

        // 3. Project the 12 flagship targets (plus guided target if outside the 12)
        val byId = targets.associateBy { it.target.id }
        val visibleTargets = mutableListOf<ArProjectedTarget>()

        for ((id, shortLabel) in AR_TARGET_SHORT_LABELS) {
            val vis = byId[id] ?: continue
            if (vis.altitudeDeg < -2.0) continue
            val proj = projectWithBasis(vis.altitudeDeg, vis.azimuthDeg, basis)
            if (proj.inViewport) {
                visibleTargets.add(
                    ArProjectedTarget(
                        id = id,
                        shortLabel = shortLabel,
                        altitudeDeg = vis.altitudeDeg,
                        azimuthDeg = vis.azimuthDeg,
                        u = proj.u,
                        v = proj.v,
                        isGuided = (id == guidedId)
                    )
                )
            }
        }

        // Include custom guided target if chosen from full 20-target ephemeris catalogue
        val guidedVis = guidedId?.let { byId[it] }
        if (guidedId != null && guidedVis != null && guidedId !in AR_TARGET_SHORT_LABELS && guidedVis.altitudeDeg >= -2.0) {
            val proj = projectWithBasis(guidedVis.altitudeDeg, guidedVis.azimuthDeg, basis)
            if (proj.inViewport) {
                visibleTargets.add(
                    ArProjectedTarget(
                        id = guidedId,
                        shortLabel = guidedVis.target.catalogCode.ifBlank { guidedVis.target.nameFr },
                        altitudeDeg = guidedVis.altitudeDeg,
                        azimuthDeg = guidedVis.azimuthDeg,
                        u = proj.u,
                        v = proj.v,
                        isGuided = true
                    )
                )
            }
        }

        // 4. Smart Off-Screen / Centering Guidance Arrow Pill
        val guidance = guidedVis?.let { gv ->
            val label = AR_TARGET_SHORT_LABELS[gv.target.id] ?: gv.target.nameFr
            val proj = projectWithBasis(gv.altitudeDeg, gv.azimuthDeg, basis)
            var dAz = (gv.azimuthDeg - camAzDeg) % 360.0
            if (dAz > 180.0) dAz -= 360.0
            if (dAz < -180.0) dAz += 360.0
            val dAlt = gv.altitudeDeg - camAltDeg
            val centered = proj.angularSeparationDeg <= 3.5
            val belowHorizon = gv.altitudeDeg < 0.0

            val pillText = when {
                belowHorizon -> "$label · Sous l'horizon (${gv.altitudeDeg.roundToInt()}°)"
                centered -> "$label · CENTRÉ"
                else -> {
                    val azDir = if (dAz >= 0.0) "→" else "←"
                    val altDir = if (dAlt >= 0.0) "↑" else "↓"
                    val azInt = abs(dAz).roundToInt()
                    val altInt = abs(dAlt).roundToInt()
                    "$label · $azDir ${azInt}° · $altDir ${altInt}°"
                }
            }
            ArGuidanceInfo(
                targetId = gv.target.id,
                targetLabel = label,
                deltaAzDeg = dAz,
                deltaAltDeg = dAlt,
                angularSeparationDeg = proj.angularSeparationDeg,
                isCentered = centered,
                isBelowHorizon = belowHorizon,
                pillText = pillText
            )
        }

        return ArSkyOverlayFrame(
            milkyWaySegments = mwSegments,
            visibleTargets = visibleTargets,
            guidance = guidance
        )
    }
}
