package com.kinetix.coach.vision

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.kinetix.coach.ui.theme.BorderHairline
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.FormCaution
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange

private val POSE_BONES = listOf(
    11 to 12, // Shoulders
    11 to 13, 13 to 15, // Left arm
    12 to 14, 14 to 16, // Right arm
    11 to 23, 12 to 24, // Torso sides
    23 to 24, // Pelvis
    23 to 25, 25 to 27, 27 to 31, // Left leg
    24 to 26, 26 to 28, 28 to 32  // Right leg
)

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
            textSize = 30f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }
    val pillBgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ObsidianBg.copy(alpha = 0.88f).toArgb()
        }
    }
    val pillBorderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
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

        val boneColor = when (frame.formQuality) {
            FormQuality.GOOD -> FormGood
            FormQuality.CAUTION -> FormCaution
            FormQuality.FAULT -> FormFault
        }

        // Draw crisp structural bones
        for ((aIdx, bIdx) in POSE_BONES) {
            if (aIdx >= pts.size || bIdx >= pts.size) continue
            val a = pts[aIdx]
            val b = pts[bIdx]
            if (a.visibility < 0.35f || b.visibility < 0.35f) continue
            val pA = mapPoint(a)
            val pB = mapPoint(b)
            drawLine(
                color = Color(0xAA0B0C0E),
                start = pA,
                end = pB,
                strokeWidth = 10f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = boneColor,
                start = pA,
                end = pB,
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }

        val highlightedIndices = frame.angleBadges.map { it.landmarkIndex }.toSet()

        // Draw precision joints
        for (idx in KEY_JOINTS) {
            if (idx >= pts.size) continue
            val p = pts[idx]
            if (p.visibility < 0.35f) continue
            val center = mapPoint(p)
            val isHighlighted = idx in highlightedIndices
            drawCircle(
                color = ObsidianBg,
                radius = if (isHighlighted) 11f else 7.5f,
                center = center
            )
            drawCircle(
                color = if (isHighlighted) SignalOrange else CrispWhite,
                radius = if (isHighlighted) 7.5f else 4.5f,
                center = center
            )
        }

        // Draw live degree badges next to active joints
        val nativeCanvas = drawContext.canvas.nativeCanvas
        for (badge in frame.angleBadges) {
            val anchor = Offset(
                x = offsetX + badge.x * scaledW,
                y = offsetY + badge.y * scaledH
            )
            val label = "${badge.degrees}°"
            val badgeColor = when (badge.quality) {
                FormQuality.GOOD -> FormGood
                FormQuality.CAUTION -> FormCaution
                FormQuality.FAULT -> FormFault
            }
            val bx = (anchor.x + 56f).coerceIn(58f, size.width - 58f)
            val by = (anchor.y - 34f).coerceIn(42f, size.height - 42f)
            val rect = RectF(bx - 42f, by - 24f, bx + 42f, by + 16f)

            pillBorderPaint.color = badgeColor.toArgb()
            nativeCanvas.drawRoundRect(rect, 10f, 10f, pillBgPaint)
            nativeCanvas.drawRoundRect(rect, 10f, 10f, pillBorderPaint)
            nativeCanvas.drawText(label, bx, by + 6f, textPaint)
        }
    }
}
