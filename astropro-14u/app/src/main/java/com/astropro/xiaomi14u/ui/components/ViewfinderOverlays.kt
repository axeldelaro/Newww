package com.astropro.xiaomi14u.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.camera.CaptureProgressState
import com.astropro.xiaomi14u.camera.FocusLoupeState
import com.astropro.xiaomi14u.camera.HistogramData
import com.astropro.xiaomi14u.camera.LensInfo
import com.astropro.xiaomi14u.camera.LevelOrientationState
import com.astropro.xiaomi14u.camera.NpfCalculator
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Red-monochrome scotopic color filter applied to bitmaps when Vision Nocturne is active.
 */
fun nightVisionColorFilter(enabled: Boolean): ColorFilter? {
    if (!enabled) return null
    val matrix = ColorMatrix(
        floatArrayOf(
            0.35f, 0.55f, 0.10f, 0f, 0f,
            0.00f, 0.04f, 0.00f, 0f, 0f,
            0.00f, 0.00f, 0.00f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return ColorFilter.colorMatrix(matrix)
}

/**
 * Compact Xiaomi-style live RGB histogram drawn on Canvas from real live preview YUV frames.
 */
@Composable
fun LiveRgbHistogram(
    data: HistogramData,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Box(
        modifier = modifier
            .width(108.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.panelTranslucent)
            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(4.dp))
            .padding(3.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bins = data.r.size
            if (bins < 2) return@Canvas
            var maxVal = 1
            for (i in 1 until bins - 1) {
                maxVal = max(maxVal, max(data.r[i], max(data.g[i], data.b[i])))
            }
            val maxF = maxVal.toFloat()

            fun drawChannel(arr: IntArray, strokeColor: Color) {
                val path = Path()
                val stepX = size.width / (bins - 1).toFloat()
                path.moveTo(0f, size.height)
                for (i in 0 until bins) {
                    val hNorm = (arr[i] / maxF).coerceIn(0f, 1f)
                    val x = i * stepX
                    val y = size.height * (1f - hNorm * 0.92f)
                    path.lineTo(x, y)
                }
                path.lineTo(size.width, size.height)
                path.close()
                drawPath(path, color = strokeColor.copy(alpha = 0.25f))

                val linePath = Path()
                for (i in 0 until bins) {
                    val hNorm = (arr[i] / maxF).coerceIn(0f, 1f)
                    val x = i * stepX
                    val y = size.height * (1f - hNorm * 0.92f)
                    if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }
                drawPath(linePath, color = strokeColor, style = Stroke(width = 1.1.dp.toPx()))
            }

            drawChannel(data.b, colors.histB)
            drawChannel(data.g, colors.histG)
            drawChannel(data.r, colors.histR)
        }
    }
}

/**
 * Subtle Leica Rule-of-Thirds grid + Calibrated 2-Axis Electronic Bubble Level
 * (snaps Xiaomi Gold / Red when within 0.5° level for pinpoint tripod setup).
 */
@Composable
fun GridAndBubbleLevelOverlay(
    orientation: LevelOrientationState,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Canvas(modifier = modifier.fillMaxSize()) {
        val gridCol = colors.textPrimary.copy(alpha = 0.20f)
        val sw = 0.75.dp.toPx()

        // Rule of thirds lines
        drawLine(gridCol, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), sw)
        drawLine(gridCol, Offset(2f * size.width / 3f, 0f), Offset(2f * size.width / 3f, size.height), sw)
        drawLine(gridCol, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), sw)
        drawLine(gridCol, Offset(0f, 2f * size.height / 3f), Offset(size.width, 2f * size.height / 3f), sw)

        // 2-axis electronic level in center
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val rollLevel = abs(orientation.rollDeg) < 0.5f
        val lineColor = if (rollLevel) colors.levelOk else colors.textPrimary.copy(alpha = 0.75f)
        val refColor = colors.textPrimary.copy(alpha = 0.35f)

        // Fixed outer reference ticks
        val innerGap = 24.dp.toPx()
        val outerLen = 52.dp.toPx()
        drawLine(refColor, Offset(cx - outerLen, cy), Offset(cx - innerGap, cy), 1.dp.toPx())
        drawLine(refColor, Offset(cx + innerGap, cy), Offset(cx + outerLen, cy), 1.dp.toPx())

        // Rotating roll horizon bar
        rotate(degrees = -orientation.rollDeg.coerceIn(-45f, 45f), pivot = Offset(cx, cy)) {
            drawLine(
                color = lineColor,
                start = Offset(cx - 21.dp.toPx(), cy),
                end = Offset(cx + 21.dp.toPx(), cy),
                strokeWidth = if (rollLevel) 2.dp.toPx() else 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Pitch indicator dot offset vertically
        val pitchOffset = (orientation.pitchDeg.coerceIn(-25f, 25f) / 25f) * 26.dp.toPx()
        drawCircle(
            color = lineColor,
            radius = 2.6.dp.toPx(),
            center = Offset(cx, cy + pitchOffset)
        )
    }
}

/**
 * Interactive 10x Star Focus Loupe showing real-time cropped star preview,
 * C++20 exact FWHM / HFD / SNR telemetry, and a mini sharpness peak sparkline.
 */
@Composable
fun StarFocusLoupeOverlay(
    loupe: FocusLoupeState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Column(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.panelTranslucent)
            .border(1.dp, colors.accent.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOUPE 10×",
                style = tabularStyle(size = 10.sp, weight = FontWeight.SemiBold, color = colors.accent)
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Fermer loupe",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onClose)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black)
                .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = loupe.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Zoom étoile 10x",
                    contentScale = ContentScale.Crop,
                    colorFilter = nightVisionColorFilter(colors.isNightVision),
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Subtle optical crosshair
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = colors.accent.copy(alpha = 0.55f)
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                drawLine(c, Offset(cx - 14.dp.toPx(), cy), Offset(cx - 4.dp.toPx(), cy), 1.dp.toPx())
                drawLine(c, Offset(cx + 4.dp.toPx(), cy), Offset(cx + 14.dp.toPx(), cy), 1.dp.toPx())
                drawLine(c, Offset(cx, cy - 14.dp.toPx()), Offset(cx, cy - 4.dp.toPx()), 1.dp.toPx())
                drawLine(c, Offset(cx, cy + 4.dp.toPx()), Offset(cx, cy + 14.dp.toPx()), 1.dp.toPx())
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        if (loupe.starFound) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "FWHM %.2f", loupe.fwhm),
                    style = tabularStyle(size = 10.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )
                Text(
                    text = String.format(Locale.US, "HFD %.2f", loupe.hfd),
                    style = tabularStyle(size = 10.sp, weight = FontWeight.Medium, color = colors.textPrimary)
                )
            }
            Text(
                text = String.format(Locale.US, "SNR %.0f · Viser min FWHM", loupe.snr),
                style = tabularStyle(size = 9.sp, color = colors.textSecondary)
            )
        } else {
            Text(
                text = String.format(Locale.US, "Netteté %.1f (Lune/Surf.)", loupe.tenengrad * 100f),
                style = tabularStyle(size = 9.5.sp, weight = FontWeight.Medium, color = colors.textPrimary)
            )
        }

        // Mini peak sharpness history sparkline
        if (loupe.history.size >= 2) {
            Spacer(modifier = Modifier.height(3.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            ) {
                val vals = loupe.history
                val minV = vals.minOrNull() ?: 0f
                val maxV = max(minV + 0.001f, vals.maxOrNull() ?: 1f)
                val stepX = size.width / (vals.size - 1).coerceAtLeast(1)
                val path = Path()
                vals.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val y = size.height * (1f - ((v - minV) / (maxV - minV)).coerceIn(0f, 1f) * 0.85f) - 1f
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = colors.accent, style = Stroke(width = 1.4.dp.toPx()))
            }
        }
    }
}

/**
 * Floating Xiaomi Lens Selector Pill (`0.5` · `1` · `3.2` · `5`).
 * Active lens smoothly displays its focal length in Xiaomi Pro Gold (e.g. `23mm`).
 */
@Composable
fun XiaomiLensSelectorPill(
    lenses: List<LensInfo>,
    activeLens: LensInfo?,
    onSelectLens: (LensInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    if (lenses.isEmpty()) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x99000000))
            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(22.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lenses.forEach { lens ->
            val selected = activeLens?.id == lens.id
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (selected) colors.surfaceElevated else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) colors.accent else Color.Transparent,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .clickable { onSelectLens(lens) }
                    .padding(horizontal = if (selected) 11.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selected) lens.displayName else lens.zoomBadge,
                    style = tabularStyle(
                        size = if (selected) 12.sp else 11.sp,
                        weight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) colors.accent else colors.textPrimary.copy(alpha = 0.82f)
                    )
                )
            }
        }
    }
}

/**
 * Real-time C++20 Focus Peaking Overlay highlighting crisp lunar craters, horizon silhouettes,
 * and pinpoint stars in Xiaomi Pro Gold (#F5B82E) or Vision Nocturne OLED Red (#CC1818).
 */
@Composable
fun FocusPeakingOverlay(
    pointsXY: FloatArray,
    pointCount: Int,
    sensorOrientation: Int,
    isFront: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    if (pointCount <= 0 || pointsXY.size < pointCount * 2) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val peakColor = colors.accent.copy(alpha = 0.88f)
        val arm = 1.6.dp.toPx()
        val strokeW = 1.15.dp.toPx()
        val w = size.width
        val h = size.height

        for (i in 0 until pointCount) {
            val nx = pointsXY[i * 2]
            val ny = pointsXY[i * 2 + 1]
            var vx: Float
            var vy: Float
            when (sensorOrientation) {
                90 -> {
                    vx = 1f - ny
                    vy = nx
                }
                270 -> {
                    vx = ny
                    vy = 1f - nx
                }
                180 -> {
                    vx = 1f - nx
                    vy = 1f - ny
                }
                else -> {
                    vx = nx
                    vy = ny
                }
            }
            if (isFront) {
                vx = 1f - vx
            }

            val cx = vx * w
            val cy = vy * h
            drawLine(
                color = peakColor,
                start = Offset(cx - arm, cy),
                end = Offset(cx + arm, cy),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
            drawLine(
                color = peakColor,
                start = Offset(cx, cy - arm),
                end = Offset(cx, cy + arm),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Minimalist Xiaomi-style Live Capture / Stacking HUD bar during single long exposure
 * and multi-frame sequences (with estimated remaining total time).
 */
@Composable
fun LiveCaptureHudOverlay(
    progress: CaptureProgressState,
    onFinishNow: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.panelTranslucent)
            .border(0.75.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.leicaRed)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val countLabel = if (progress.framesTarget > 0) {
                    "${progress.framesCaptured}/${progress.framesTarget}"
                } else {
                    "${progress.framesCaptured} poses"
                }
                Text(
                    text = "${progress.mode.shortLabel} · $countLabel",
                    style = tabularStyle(size = 12.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (progress.estimatedRemainingTotalSec >= 1.0 && progress.framesTarget > 1) {
                    Text(
                        text = "~${NpfCalculator.formatIntegrationTime(progress.estimatedRemainingTotalSec)}",
                        style = tabularStyle(size = 10.5.sp, weight = FontWeight.Medium, color = colors.textSecondary)
                    )
                }
                Text(
                    text = NpfCalculator.formatIntegrationTime(progress.elapsedExposureSec),
                    style = tabularStyle(size = 12.sp, weight = FontWeight.Bold, color = colors.accent)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val metricsLine = buildString {
                if (progress.starsDetected > 0) append("${progress.starsDetected}★  ")
                if (progress.residualPx > 0.001f) append(String.format(Locale.US, "Δ %.2fpx  ", progress.residualPx))
                if (progress.framesRejected > 0) append("Rejet: ${progress.framesRejected}")
                if (isEmpty()) append(progress.statusText)
            }
            Text(
                text = metricsLine,
                style = tabularStyle(size = 10.5.sp, color = colors.textSecondary),
                modifier = Modifier.weight(1f, fill = false)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.framesAccepted >= 1 && !progress.isFinalizing) {
                    Text(
                        text = "TERMINER",
                        style = tabularStyle(size = 10.5.sp, weight = FontWeight.Bold, color = colors.accent),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.75.dp, colors.accent, RoundedCornerShape(4.dp))
                            .clickable(onClick = onFinishNow)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = "ANNULER",
                    style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.leicaRed),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(0.75.dp, colors.leicaRed.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onAbort)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * Iconic Xiaomi HyperOS Camera Pro Shutter Button.
 * Idle: crisp white outer ring + solid white center disc (pure red in Vision Nocturne).
 * Active: smooth gold/red circular countdown arc + crisp seconds badge during long exposure,
 * or Leica Red square stop button during multi-frame sequences.
 */
@Composable
fun XiaomiProShutterButton(
    isCapturing: Boolean,
    progressFraction: Float,
    countdownSeconds: Double = 0.0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "shutterSpin")
    val spinDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 3.2.dp.toPx()
            val inset = strokeW * 0.6f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)

            // Outer ring
            drawCircle(
                color = if (isCapturing) colors.shutterRing.copy(alpha = 0.28f) else colors.shutterRing,
                radius = (size.minDimension - strokeW) * 0.5f,
                style = Stroke(width = strokeW)
            )

            if (isCapturing) {
                if (progressFraction > 0.005f) {
                    drawArc(
                        color = colors.accent,
                        startAngle = -90f,
                        sweepAngle = (progressFraction.coerceIn(0f, 1f)) * 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                } else {
                    drawArc(
                        color = colors.leicaRed,
                        startAngle = spinDeg,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
            }
        }

        if (isCapturing) {
            if (countdownSeconds > 0.05) {
                // Crisp HyperOS long-exposure remaining-seconds badge right inside the shutter ring
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.leicaRed.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fs", countdownSeconds),
                        style = tabularStyle(
                            size = 13.sp,
                            weight = FontWeight.Bold,
                            color = colors.accent
                        )
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.leicaRed)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.shutterCore)
            )
        }
    }
}
