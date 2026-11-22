package com.kinetix.coach.vision

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.FormCaution
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val POSE_BONES = listOf(
    11 to 12, // Shoulders
    11 to 13, 13 to 15, // Left arm
    12 to 14, 14 to 16, // Right arm
    11 to 23, 12 to 24, // Torso sides
    23 to 24, // Pelvis
    23 to 25, 25 to 27, 27 to 31, // Left leg
    24 to 26, 26 to 28, 28 to 32  // Right leg
)

private val LEFT_ONLY_INDICES = setOf(11, 13, 15, 23, 25, 27, 31)
private val RIGHT_ONLY_INDICES = setOf(12, 14, 16, 24, 26, 28, 32)
private val KEY_JOINTS = setOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

/**
 * Isolated full-FPS state driving [SkeletonOverlay] without forcing Compose HUD recompositions
 * on sub-pixel landmark coordinate updates.
 */
data class PoseOverlayState(
    val landmarks: List<PosePoint> = emptyList(),
    val angleBadges: List<JointAngleBadge> = emptyList(),
    val corrections: List<AngleCorrection> = emptyList(),
    val formQuality: FormQuality = FormQuality.GOOD,
    val imageWidth: Int = 480,
    val imageHeight: Int = 640,
    val viewpointInfo: ViewpointInfo = ViewpointInfo(),
    val showDetailedAngles: Boolean = false
)

@Composable
fun SkeletonOverlay(
    frame: BiomechanicsFrame,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    showDetailedAngles: Boolean = false
) {
    SkeletonOverlay(
        overlayState = PoseOverlayState(
            landmarks = frame.landmarks,
            angleBadges = frame.angleBadges,
            corrections = frame.corrections,
            formQuality = frame.formQuality,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewpointInfo = frame.viewpointInfo,
            showDetailedAngles = showDetailedAngles
        ),
        modifier = modifier
    )
}

@Composable
fun SkeletonOverlay(
    overlayState: PoseOverlayState,
    modifier: Modifier = Modifier
) {
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CrispWhite.toArgb()
            textSize = 26f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }
    val pillBgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ObsidianBg.copy(alpha = 0.84f).toArgb()
        }
    }
    val pillBorderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
    }
    val ghostDashEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    val reusableHeadPath = remember { Path() }
    val reusablePillRect = remember { RectF() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val pts = overlayState.landmarks
        val imageWidth = overlayState.imageWidth
        val imageHeight = overlayState.imageHeight
        if (pts.size < 29 || imageWidth <= 0 || imageHeight <= 0) return@Canvas

        // Match PreviewView FILL_CENTER scaling so skeleton aligns pixel-perfectly over camera feed
        val scale = maxOf(size.width / imageWidth.toFloat(), size.height / imageHeight.toFloat())
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        val offsetX = (size.width - scaledW) / 2f
        val offsetY = (size.height - scaledH) / 2f

        fun mapPoint(p: PosePoint): Offset = Offset(
            x = offsetX + p.x * scaledW,
            y = offsetY + p.y * scaledH
        )

        fun mapNorm(nx: Float, ny: Float): Offset = Offset(
            x = offsetX + nx * scaledW,
            y = offsetY + ny * scaledH
        )

        val vp = overlayState.viewpointInfo
        val detailed = overlayState.showDetailedAngles
        val boneColor = when (overlayState.formQuality) {
            FormQuality.GOOD -> FormGood
            FormQuality.CAUTION -> FormCaution
            FormQuality.FAULT -> FormFault
        }

        // 1. Sleek refined structural bones (clean minimalist studio look; far side gently dimmed in profile)
        val haloWidth = if (detailed) 8.5f else 6.5f
        val strokeWidth = if (detailed) 4.4f else 3.4f
        val baseAlpha = if (detailed) 0.95f else 0.84f

        for ((aIdx, bIdx) in POSE_BONES) {
            if (aIdx >= pts.size || bIdx >= pts.size) continue
            val a = pts[aIdx]
            val b = pts[bIdx]
            if (a.visibility < 0.35f || b.visibility < 0.35f) continue
            val isFarSide = !vp.bothSidesVisible && (
                (vp.dominantSideLeft && aIdx in RIGHT_ONLY_INDICES && bIdx in RIGHT_ONLY_INDICES) ||
                    (!vp.dominantSideLeft && aIdx in LEFT_ONLY_INDICES && bIdx in LEFT_ONLY_INDICES)
                )
            val alphaMult = if (isFarSide) 0.32f else baseAlpha
            val pA = mapPoint(a)
            val pB = mapPoint(b)
            drawLine(
                color = Color(0xAA0B0C0E).copy(alpha = 0.55f * alphaMult),
                start = pA,
                end = pB,
                strokeWidth = haloWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = boneColor.copy(alpha = alphaMult),
                start = pA,
                end = pB,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        // 2. Angular protractor arcs (ONLY in Detailed Biomechanics mode — keeps default view ultra-clean!)
        if (detailed) {
            for (badge in overlayState.angleBadges) {
                val pIdx = badge.parentIndex
                val jIdx = badge.landmarkIndex
                val cIdx = badge.childIndex
                if (pIdx !in pts.indices || jIdx !in pts.indices || cIdx !in pts.indices) continue
                val pParent = mapPoint(pts[pIdx])
                val pJoint = mapPoint(pts[jIdx])
                val pChild = mapPoint(pts[cIdx])

                val startAngleDeg = (atan2((pParent.y - pJoint.y).toDouble(), (pParent.x - pJoint.x).toDouble()) * 180.0 / PI).toFloat()
                val endAngleDeg = (atan2((pChild.y - pJoint.y).toDouble(), (pChild.x - pJoint.x).toDouble()) * 180.0 / PI).toFloat()
                var sweepDeg = endAngleDeg - startAngleDeg
                while (sweepDeg > 180f) sweepDeg -= 360f
                while (sweepDeg < -180f) sweepDeg += 360f

                val arcColor = qualityColor(badge.quality)
                val radius = 34f
                val topLeft = Offset(pJoint.x - radius, pJoint.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)

                drawArc(
                    color = arcColor.copy(alpha = 0.14f),
                    startAngle = startAngleDeg,
                    sweepAngle = sweepDeg,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize
                )
                drawArc(
                    color = arcColor,
                    startAngle = startAngleDeg,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
            }
        }

        // 3. Subtle ideal-target ghost line (ONLY when a real confirmed posture fault is active)
        val primaryCorr = overlayState.corrections.firstOrNull()
        val ghostCorrections = if (detailed) overlayState.corrections else listOfNotNull(primaryCorr)
        for (corr in ghostCorrections) {
            if (corr.ghostAnchorIndex in pts.indices &&
                corr.ghostTargetX.isFinite() &&
                corr.ghostTargetY.isFinite()
            ) {
                val anchor = mapPoint(pts[corr.ghostAnchorIndex])
                val target = mapNorm(corr.ghostTargetX, corr.ghostTargetY)
                drawLine(
                    color = ObsidianBg.copy(alpha = 0.55f),
                    start = anchor,
                    end = target,
                    strokeWidth = 6f,
                    cap = StrokeCap.Round,
                    pathEffect = ghostDashEffect
                )
                drawLine(
                    color = FormGood.copy(alpha = 0.85f),
                    start = anchor,
                    end = target,
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                    pathEffect = ghostDashEffect
                )
                drawCircle(
                    color = FormGood.copy(alpha = 0.90f),
                    radius = 6.5f,
                    center = target,
                    style = Stroke(width = 2.5f)
                )
            }
        }

        // 4. Directional correction arrows (ONLY in Detailed mode)
        if (detailed) {
            for (corr in overlayState.corrections.take(1)) {
                if (corr.jointIndex !in pts.indices) continue
                val dirLen = hypot(corr.arrowDx, corr.arrowDy)
                if (dirLen < 0.1f) continue
                val ux = corr.arrowDx / dirLen
                val uy = corr.arrowDy / dirLen

                val joint = mapPoint(pts[corr.jointIndex])
                val start = Offset(joint.x + ux * 18f, joint.y + uy * 18f)
                val tip = Offset(joint.x + ux * 74f, joint.y + uy * 74f)
                val arrowColor = qualityColor(corr.severity)

                drawLine(
                    color = ObsidianBg.copy(alpha = 0.75f),
                    start = start,
                    end = tip,
                    strokeWidth = 8.5f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = arrowColor,
                    start = start,
                    end = tip,
                    strokeWidth = 4.5f,
                    cap = StrokeCap.Round
                )

                val perpX = -uy
                val perpY = ux
                val headLen = 17f
                val headHalfW = 10f
                val baseX = tip.x - ux * headLen
                val baseY = tip.y - uy * headLen
                reusableHeadPath.reset()
                reusableHeadPath.moveTo(tip.x, tip.y)
                reusableHeadPath.lineTo(baseX + perpX * headHalfW, baseY + perpY * headHalfW)
                reusableHeadPath.lineTo(baseX - perpX * headHalfW, baseY - perpY * headHalfW)
                reusableHeadPath.close()
                drawPath(path = reusableHeadPath, color = arrowColor)
            }
        }

        val highlightedIndices = if (detailed) overlayState.angleBadges.map { it.landmarkIndex }.toSet() else emptySet()
        val correctingIndices = overlayState.corrections.map { it.jointIndex }.toSet()

        // 5. Minimalist joint markers
        for (idx in KEY_JOINTS) {
            if (idx >= pts.size) continue
            val p = pts[idx]
            if (p.visibility < 0.35f) continue
            val center = mapPoint(p)
            val isCorrecting = idx in correctingIndices
            val isHighlighted = idx in highlightedIndices
            drawCircle(
                color = ObsidianBg.copy(alpha = 0.80f),
                radius = when {
                    isCorrecting -> 10f
                    isHighlighted -> 8.5f
                    else -> 5.4f
                },
                center = center
            )
            drawCircle(
                color = when {
                    isCorrecting -> FormFault
                    isHighlighted -> SignalOrange
                    else -> CrispWhite.copy(alpha = 0.92f)
                },
                radius = when {
                    isCorrecting -> 6.5f
                    isHighlighted -> 5.5f
                    else -> 3.2f
                },
                center = center
            )
        }

        // 6. Floating degree pills (ONLY in Detailed mode — keeps default camera 100% uncluttered!)
        if (detailed) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            for (badge in overlayState.angleBadges) {
                val anchor = mapNorm(badge.x, badge.y)
                val label = if (badge.targetDegHint != null) {
                    "${badge.degrees}°→${badge.targetDegHint}°"
                } else {
                    "${badge.degrees}°"
                }
                val badgeColor = qualityColor(badge.quality)
                val textWidth = textPaint.measureText(label)
                val halfW = (textWidth * 0.5f + 14f).coerceAtLeast(34f)
                val bx = (anchor.x + halfW + 16f).coerceIn(halfW + 10f, size.width - halfW - 10f)
                val by = (anchor.y - 30f).coerceIn(42f, size.height - 42f)
                reusablePillRect.set(bx - halfW, by - 20f, bx + halfW, by + 13f)

                pillBorderPaint.color = badgeColor.toArgb()
                nativeCanvas.drawRoundRect(reusablePillRect, 9f, 9f, pillBgPaint)
                nativeCanvas.drawRoundRect(reusablePillRect, 9f, 9f, pillBorderPaint)
                nativeCanvas.drawText(label, bx, by + 4f, textPaint)
            }
        }
    }
}

private fun qualityColor(quality: FormQuality): Color = when (quality) {
    FormQuality.GOOD -> FormGood
    FormQuality.CAUTION -> FormCaution
    FormQuality.FAULT -> FormFault
}
