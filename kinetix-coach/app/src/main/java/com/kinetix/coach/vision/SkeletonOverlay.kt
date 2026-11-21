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

@Composable
fun SkeletonOverlay(
    frame: BiomechanicsFrame,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier
) {
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CrispWhite.toArgb()
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }
    val pillBgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ObsidianBg.copy(alpha = 0.90f).toArgb()
        }
    }
    val pillBorderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
    }
    val ghostDashEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val pts = frame.landmarks
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

        val vp = frame.viewpointInfo
        val boneColor = when (frame.formQuality) {
            FormQuality.GOOD -> FormGood
            FormQuality.CAUTION -> FormCaution
            FormQuality.FAULT -> FormFault
        }

        // 1. Draw crisp structural bones (subtle dim on occluded far side during pure side profile)
        for ((aIdx, bIdx) in POSE_BONES) {
            if (aIdx >= pts.size || bIdx >= pts.size) continue
            val a = pts[aIdx]
            val b = pts[bIdx]
            if (a.visibility < 0.35f || b.visibility < 0.35f) continue
            val isFarSide = !vp.bothSidesVisible && (
                (vp.dominantSideLeft && aIdx in RIGHT_ONLY_INDICES && bIdx in RIGHT_ONLY_INDICES) ||
                    (!vp.dominantSideLeft && aIdx in LEFT_ONLY_INDICES && bIdx in LEFT_ONLY_INDICES)
                )
            val alphaMult = if (isFarSide) 0.38f else 1f
            val pA = mapPoint(a)
            val pB = mapPoint(b)
            drawLine(
                color = Color(0xAA0B0C0E).copy(alpha = 0.68f * alphaMult),
                start = pA,
                end = pB,
                strokeWidth = 9.5f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = boneColor.copy(alpha = alphaMult),
                start = pA,
                end = pB,
                strokeWidth = 4.8f,
                cap = StrokeCap.Round
            )
        }

        // 2. Draw live angular protractor arcs at active joints
        for (badge in frame.angleBadges) {
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
            val radius = 38f
            val topLeft = Offset(pJoint.x - radius, pJoint.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                color = arcColor.copy(alpha = 0.17f),
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
                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
            )
            // Precision Swiss radial ticks at arc boundaries
            for (edgeDeg in floatArrayOf(startAngleDeg, startAngleDeg + sweepDeg)) {
                val rad = edgeDeg * PI / 180.0
                val inner = Offset(pJoint.x + (radius - 5f) * cos(rad).toFloat(), pJoint.y + (radius - 5f) * sin(rad).toFloat())
                val outer = Offset(pJoint.x + (radius + 5f) * cos(rad).toFloat(), pJoint.y + (radius + 5f) * sin(rad).toFloat())
                drawLine(color = CrispWhite.copy(alpha = 0.85f), start = inner, end = outer, strokeWidth = 2f)
            }
        }

        // 3. Draw dashed ideal-target ghost limb vectors for active corrections
        for (corr in frame.corrections) {
            if (corr.ghostAnchorIndex in pts.indices &&
                corr.ghostTargetX.isFinite() &&
                corr.ghostTargetY.isFinite()
            ) {
                val anchor = mapPoint(pts[corr.ghostAnchorIndex])
                val target = mapNorm(corr.ghostTargetX, corr.ghostTargetY)
                drawLine(
                    color = ObsidianBg.copy(alpha = 0.65f),
                    start = anchor,
                    end = target,
                    strokeWidth = 7.5f,
                    cap = StrokeCap.Round,
                    pathEffect = ghostDashEffect
                )
                drawLine(
                    color = FormGood.copy(alpha = 0.90f),
                    start = anchor,
                    end = target,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                    pathEffect = ghostDashEffect
                )
                drawCircle(
                    color = ObsidianBg.copy(alpha = 0.85f),
                    radius = 10f,
                    center = target
                )
                drawCircle(
                    color = FormGood,
                    radius = 8f,
                    center = target,
                    style = Stroke(width = 3f)
                )
            }
        }

        // 4. Draw crisp directional correction arrows anchored at off-target joints
        for (corr in frame.corrections.take(2)) {
            if (corr.jointIndex !in pts.indices) continue
            val dirLen = hypot(corr.arrowDx, corr.arrowDy)
            if (dirLen < 0.1f) continue
            val ux = corr.arrowDx / dirLen
            val uy = corr.arrowDy / dirLen

            val joint = mapPoint(pts[corr.jointIndex])
            val start = Offset(joint.x + ux * 20f, joint.y + uy * 20f)
            val tip = Offset(joint.x + ux * 84f, joint.y + uy * 84f)
            val arrowColor = when (corr.severity) {
                FormQuality.FAULT -> FormFault
                FormQuality.CAUTION -> SignalOrange
                FormQuality.GOOD -> FormGood
            }

            // Contrast shaft + signal shaft
            drawLine(
                color = ObsidianBg.copy(alpha = 0.85f),
                start = start,
                end = tip,
                strokeWidth = 10f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arrowColor,
                start = start,
                end = tip,
                strokeWidth = 5.5f,
                cap = StrokeCap.Round
            )

            // Solid precision triangular arrowhead
            val perpX = -uy
            val perpY = ux
            val headLen = 20f
            val headHalfW = 12f
            val baseX = tip.x - ux * headLen
            val baseY = tip.y - uy * headLen
            val headPath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseX + perpX * headHalfW, baseY + perpY * headHalfW)
                lineTo(baseX - perpX * headHalfW, baseY - perpY * headHalfW)
                close()
            }
            drawPath(path = headPath, color = ObsidianBg.copy(alpha = 0.8f), style = Stroke(width = 4f))
            drawPath(path = headPath, color = arrowColor)
        }

        val highlightedIndices = frame.angleBadges.map { it.landmarkIndex }.toSet()
        val correctingIndices = frame.corrections.map { it.jointIndex }.toSet()

        // 5. Draw precision joint markers
        for (idx in KEY_JOINTS) {
            if (idx >= pts.size) continue
            val p = pts[idx]
            if (p.visibility < 0.35f) continue
            val center = mapPoint(p)
            val isCorrecting = idx in correctingIndices
            val isHighlighted = idx in highlightedIndices
            drawCircle(
                color = ObsidianBg,
                radius = when {
                    isCorrecting -> 12f
                    isHighlighted -> 10.5f
                    else -> 7f
                },
                center = center
            )
            drawCircle(
                color = when {
                    isCorrecting -> FormFault
                    isHighlighted -> SignalOrange
                    else -> CrispWhite
                },
                radius = when {
                    isCorrecting -> 8f
                    isHighlighted -> 7f
                    else -> 4.2f
                },
                center = center
            )
        }

        // 6. Draw exact degree pills ("114°→95°" when correcting, "86°" when optimal)
        val nativeCanvas = drawContext.canvas.nativeCanvas
        for (badge in frame.angleBadges) {
            val anchor = mapNorm(badge.x, badge.y)
            val label = if (badge.targetDegHint != null) {
                "${badge.degrees}°→${badge.targetDegHint}°"
            } else {
                "${badge.degrees}°"
            }
            val badgeColor = qualityColor(badge.quality)
            val textWidth = textPaint.measureText(label)
            val halfW = (textWidth * 0.5f + 16f).coerceAtLeast(38f)
            val bx = (anchor.x + halfW + 18f).coerceIn(halfW + 10f, size.width - halfW - 10f)
            val by = (anchor.y - 34f).coerceIn(42f, size.height - 42f)
            val rect = RectF(bx - halfW, by - 23f, bx + halfW, by + 15f)

            pillBorderPaint.color = badgeColor.toArgb()
            nativeCanvas.drawRoundRect(rect, 9f, 9f, pillBgPaint)
            nativeCanvas.drawRoundRect(rect, 9f, 9f, pillBorderPaint)
            nativeCanvas.drawText(label, bx, by + 5f, textPaint)
        }
    }
}

private fun qualityColor(quality: FormQuality): Color = when (quality) {
    FormQuality.GOOD -> FormGood
    FormQuality.CAUTION -> FormCaution
    FormQuality.FAULT -> FormFault
}
