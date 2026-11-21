package com.kinetix.coach.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CameraViewpoint(val shortLabelFr: String) {
    FRONT("FACE"),
    OBLIQUE_LEFT("3/4 G"),
    OBLIQUE_RIGHT("3/4 D"),
    PROFILE_LEFT("PROFIL G"),
    PROFILE_RIGHT("PROFIL D"),
    BACK("DOS")
}

data class FramingFeedback(
    val isWellFramed: Boolean,
    val warningFr: String?
)

data class ViewpointInfo(
    val viewpoint: CameraViewpoint = CameraViewpoint.FRONT,
    val azimuthDeg: Int = 0,
    val is3DActive: Boolean = false,
    val dominantSideLeft: Boolean = true,
    val bothSidesVisible: Boolean = true,
    val framingWarningFr: String? = null
)

/**
 * Multi-angle camera viewpoint & body framing detector.
 * Uses MediaPipe 3D world landmarks (meters) to compute true camera yaw azimuth (0..180°)
 * and front/back orientation, with a smooth 2D shoulder-to-torso ratio fallback.
 */
object CameraViewpointDetector {

    private const val NOSE = BiomechanicsEngine.NOSE
    private const val LEFT_SHOULDER = BiomechanicsEngine.LEFT_SHOULDER
    private const val RIGHT_SHOULDER = BiomechanicsEngine.RIGHT_SHOULDER
    private const val LEFT_ELBOW = BiomechanicsEngine.LEFT_ELBOW
    private const val RIGHT_ELBOW = BiomechanicsEngine.RIGHT_ELBOW
    private const val LEFT_WRIST = BiomechanicsEngine.LEFT_WRIST
    private const val RIGHT_WRIST = BiomechanicsEngine.RIGHT_WRIST
    private const val LEFT_HIP = BiomechanicsEngine.LEFT_HIP
    private const val RIGHT_HIP = BiomechanicsEngine.RIGHT_HIP
    private const val LEFT_KNEE = BiomechanicsEngine.LEFT_KNEE
    private const val RIGHT_KNEE = BiomechanicsEngine.RIGHT_KNEE
    private const val LEFT_ANKLE = BiomechanicsEngine.LEFT_ANKLE
    private const val RIGHT_ANKLE = BiomechanicsEngine.RIGHT_ANKLE
    private const val LEFT_HEEL = 29
    private const val RIGHT_HEEL = 30
    private const val LEFT_FOOT_INDEX = 31
    private const val RIGHT_FOOT_INDEX = 32

    private const val EDGE_MARGIN = 0.025f

    fun detect(
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint> = emptyList()
    ): ViewpointInfo {
        if (screenPoints.size < 29) {
            return ViewpointInfo()
        }

        val framing = analyzeFraming(screenPoints)
        val has3D = worldPoints.size >= 29 && hasValid3DDepth(worldPoints)

        return if (has3D) {
            detect3D(screenPoints, worldPoints, framing.warningFr)
        } else {
            detect2DFallback(screenPoints, framing.warningFr)
        }
    }

    private fun hasValid3DDepth(worldPoints: List<PosePoint>): Boolean {
        val lSh = worldPoints[LEFT_SHOULDER]
        val rSh = worldPoints[RIGHT_SHOULDER]
        val lHip = worldPoints[LEFT_HIP]
        val rHip = worldPoints[RIGHT_HIP]
        val shSpan3D = sqrtSumSq(rSh.x - lSh.x, rSh.y - lSh.y, rSh.z - lSh.z)
        val hipSpan3D = sqrtSumSq(rHip.x - lHip.x, rHip.y - lHip.y, rHip.z - lHip.z)
        return shSpan3D > 0.05f || hipSpan3D > 0.05f
    }

    private fun detect3D(
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint>,
        framingWarning: String?
    ): ViewpointInfo {
        val lSh = worldPoints[LEFT_SHOULDER]
        val rSh = worldPoints[RIGHT_SHOULDER]
        val lHip = worldPoints[LEFT_HIP]
        val rHip = worldPoints[RIGHT_HIP]
        val nose = worldPoints[NOSE]

        // Visibility-weighted lateral vectors (R - L) in the horizontal XZ plane
        val shW = (lSh.visibility * rSh.visibility).coerceIn(0.15f, 1f)
        val hipW = (lHip.visibility * rHip.visibility).coerceIn(0.15f, 1f)
        val totalW = (shW + hipW).coerceAtLeast(0.001f)

        val latDx = ((rSh.x - lSh.x) * shW + (rHip.x - lHip.x) * hipW) / totalW
        val latDz = ((rSh.z - lSh.z) * shW + (rHip.z - lHip.z) * hipW) / totalW

        // 0° = shoulders aligned with camera X axis (front or back), 90° = pure side profile
        val rawYawDeg = (atan2(abs(latDz).toDouble(), abs(latDx).toDouble()) * 180.0 / PI).toFloat()

        // Front vs Back sign from nose Z relative to mid-shoulder Z (in MediaPipe camera space, -Z = closer to camera)
        val midShZ = (lSh.z + rSh.z) * 0.5f
        var facingBackVotes = 0f
        var facingWeight = 0f

        if (nose.visibility >= 0.25f) {
            val noseDz = nose.z - midShZ
            // When nose is closer than shoulders (noseDz < 0), athlete faces camera; > 0 means back view
            facingBackVotes += noseDz * 1.4f
            facingWeight += 1.4f
        } else if (lSh.visibility >= 0.65f && rSh.visibility >= 0.65f && rawYawDeg < 55f) {
            // Completely occluded face with fully visible shoulders strongly indicates back view
            facingBackVotes += 0.08f
            facingWeight += 1.0f
        }

        // Cross-check with foot forward direction (foot index vs heel Z) when feet are present
        if (worldPoints.size >= 33) {
            val lHeel = worldPoints[LEFT_HEEL]
            val rHeel = worldPoints[RIGHT_HEEL]
            val lToe = worldPoints[LEFT_FOOT_INDEX]
            val rToe = worldPoints[RIGHT_FOOT_INDEX]
            val footVis = minOf(lHeel.visibility, rHeel.visibility, lToe.visibility, rToe.visibility)
            if (footVis >= 0.45f) {
                val toeDz = ((lToe.z + rToe.z) - (lHeel.z + rHeel.z)) * 0.5f
                facingBackVotes += toeDz * 0.7f
                facingWeight += 0.7f
            }
        }

        val meanFacingZ = if (facingWeight > 0f) facingBackVotes / facingWeight else 0f
        val isFacingBack = meanFacingZ > 0.025f

        val azimuthDeg = if (isFacingBack) {
            (180f - rawYawDeg).coerceIn(90f, 180f)
        } else {
            rawYawDeg.coerceIn(0f, 90f)
        }.roundToInt()

        // Dominant side = side physically closer to the camera (more negative Z) + visibility tie-breaker
        val leftZ = (lSh.z + lHip.z) * 0.5f
        val rightZ = (rSh.z + rHip.z) * 0.5f
        val leftVis = sideVisibility(screenPoints, left = true)
        val rightVis = sideVisibility(screenPoints, left = false)

        val dominantSideLeft = when {
            abs(leftZ - rightZ) > 0.03f -> leftZ < rightZ
            else -> leftVis >= rightVis
        }

        val viewpoint = classifyViewpoint(azimuthDeg, dominantSideLeft)
        val isPureProfile = viewpoint == CameraViewpoint.PROFILE_LEFT || viewpoint == CameraViewpoint.PROFILE_RIGHT
        val bothSidesVisible = !isPureProfile && leftVis >= 0.45f && rightVis >= 0.45f

        return ViewpointInfo(
            viewpoint = viewpoint,
            azimuthDeg = azimuthDeg,
            is3DActive = true,
            dominantSideLeft = dominantSideLeft,
            bothSidesVisible = bothSidesVisible,
            framingWarningFr = framingWarning
        )
    }

    private fun detect2DFallback(
        screenPoints: List<PosePoint>,
        framingWarning: String?
    ): ViewpointInfo {
        val lSh = screenPoints[LEFT_SHOULDER]
        val rSh = screenPoints[RIGHT_SHOULDER]
        val lHip = screenPoints[LEFT_HIP]
        val rHip = screenPoints[RIGHT_HIP]
        val nose = screenPoints[NOSE]

        val shoulderW = hypot(rSh.x - lSh.x, rSh.y - lSh.y)
        val midShX = (lSh.x + rSh.x) * 0.5f
        val midShY = (lSh.y + rSh.y) * 0.5f
        val midHipX = (lHip.x + rHip.x) * 0.5f
        val midHipY = (lHip.y + rHip.y) * 0.5f
        val torsoLen = hypot(midShX - midHipX, midShY - midHipY).coerceAtLeast(0.05f)

        // Frontal biacromial-to-torso ratio averages ~0.68 in humans; cosine projection shrinks toward 0 in profile
        val ratio = (shoulderW / torsoLen).coerceIn(0f, 1.2f)
        val cosYaw = (ratio / 0.66f).coerceIn(0f, 1f)
        val rawYawDeg = (acos(cosYaw.toDouble()) * 180.0 / PI).toFloat()

        val midShZ = (lSh.z + rSh.z) * 0.5f
        val isFacingBack = (nose.visibility < 0.30f && lSh.visibility >= 0.65f && rSh.visibility >= 0.65f && rawYawDeg < 50f) ||
            (nose.z - midShZ > 0.08f)

        val azimuthDeg = if (isFacingBack) {
            (180f - rawYawDeg).coerceIn(90f, 180f)
        } else {
            rawYawDeg.coerceIn(0f, 90f)
        }.roundToInt()

        val leftVis = sideVisibility(screenPoints, left = true)
        val rightVis = sideVisibility(screenPoints, left = false)
        val leftZ = (lSh.z + lHip.z) * 0.5f
        val rightZ = (rSh.z + rHip.z) * 0.5f
        val dominantSideLeft = when {
            abs(leftVis - rightVis) > 0.08f -> leftVis >= rightVis
            abs(leftZ - rightZ) > 0.03f -> leftZ < rightZ
            else -> true
        }

        val viewpoint = classifyViewpoint(azimuthDeg, dominantSideLeft)
        val isPureProfile = viewpoint == CameraViewpoint.PROFILE_LEFT || viewpoint == CameraViewpoint.PROFILE_RIGHT
        val bothSidesVisible = !isPureProfile && leftVis >= 0.45f && rightVis >= 0.45f

        return ViewpointInfo(
            viewpoint = viewpoint,
            azimuthDeg = azimuthDeg,
            is3DActive = false,
            dominantSideLeft = dominantSideLeft,
            bothSidesVisible = bothSidesVisible,
            framingWarningFr = framingWarning
        )
    }

    private fun classifyViewpoint(azimuthDeg: Int, dominantSideLeft: Boolean): CameraViewpoint = when {
        azimuthDeg <= 25 -> CameraViewpoint.FRONT
        azimuthDeg >= 155 -> CameraViewpoint.BACK
        azimuthDeg in 68..112 -> if (dominantSideLeft) CameraViewpoint.PROFILE_LEFT else CameraViewpoint.PROFILE_RIGHT
        else -> if (dominantSideLeft) CameraViewpoint.OBLIQUE_LEFT else CameraViewpoint.OBLIQUE_RIGHT
    }

    private fun sideVisibility(pts: List<PosePoint>, left: Boolean): Float {
        val sh = pts[if (left) LEFT_SHOULDER else RIGHT_SHOULDER].visibility
        val hp = pts[if (left) LEFT_HIP else RIGHT_HIP].visibility
        val kn = pts[if (left) LEFT_KNEE else RIGHT_KNEE].visibility
        val an = pts[if (left) LEFT_ANKLE else RIGHT_ANKLE].visibility
        return (sh + hp + kn + an) * 0.25f
    }

    fun analyzeFraming(screenPoints: List<PosePoint>): FramingFeedback {
        if (screenPoints.size < 29) {
            return FramingFeedback(isWellFramed = false, warningFr = "Placez votre corps entier dans le cadre")
        }

        val keyIndices = intArrayOf(
            NOSE,
            LEFT_SHOULDER, RIGHT_SHOULDER,
            LEFT_ELBOW, RIGHT_ELBOW,
            LEFT_WRIST, RIGHT_WRIST,
            LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE,
            LEFT_ANKLE, RIGHT_ANKLE
        )

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var visibleCount = 0

        for (idx in keyIndices) {
            val p = screenPoints[idx]
            if (p.visibility >= 0.35f) {
                visibleCount++
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }

        if (visibleCount < 5) {
            return FramingFeedback(isWellFramed = false, warningFr = "Reculez pour cadrer votre silhouette complète")
        }

        val lAnkle = screenPoints[LEFT_ANKLE]
        val rAnkle = screenPoints[RIGHT_ANKLE]
        val nose = screenPoints[NOSE]
        val lHip = screenPoints[LEFT_HIP]
        val rHip = screenPoints[RIGHT_HIP]
        val midHipY = (lHip.y + rHip.y) * 0.5f

        val anklesClippedBottom = (lAnkle.visibility >= 0.30f && lAnkle.y > 1f - EDGE_MARGIN) ||
            (rAnkle.visibility >= 0.30f && rAnkle.y > 1f - EDGE_MARGIN) ||
            (lAnkle.visibility < 0.25f && rAnkle.visibility < 0.25f && midHipY > 0.72f)
        if (anklesClippedBottom) {
            return FramingFeedback(false, "Reculez d'un pas : chevilles hors cadre")
        }

        val headClippedTop = nose.visibility >= 0.35f && nose.y < EDGE_MARGIN
        if (headClippedTop) {
            return FramingFeedback(false, "Reculez ou relevez l'écran : tête coupée")
        }

        val sideClipped = minX < EDGE_MARGIN || maxX > 1f - EDGE_MARGIN
        if (sideClipped) {
            return FramingFeedback(false, "Centrez-vous dans le cadre")
        }

        val spanW = maxX - minX
        val spanH = maxY - minY
        if (spanH > 0.94f || spanW > 0.94f) {
            return FramingFeedback(false, "Reculez légèrement : corps trop proche")
        }
        if (spanH < 0.22f && spanW < 0.22f) {
            return FramingFeedback(false, "Rapprochez-vous de la caméra")
        }

        return FramingFeedback(isWellFramed = true, warningFr = null)
    }

    private fun sqrtSumSq(dx: Float, dy: Float, dz: Float): Float =
        kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
}
