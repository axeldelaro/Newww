package com.strikecam.arcade.ui.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import com.strikecam.arcade.data.CameraBackdropMode
import com.strikecam.arcade.data.HitVerdict
import com.strikecam.arcade.data.TargetHand
import com.strikecam.arcade.data.TargetType
import com.strikecam.arcade.game.ActiveRenderTarget
import com.strikecam.arcade.game.GameFrameSnapshot
import com.strikecam.arcade.game.HitPopup
import com.strikecam.arcade.game.SparkParticle
import com.strikecam.arcade.ui.theme.CrispWhite
import com.strikecam.arcade.ui.theme.DodgeAmber
import com.strikecam.arcade.ui.theme.LeftCrimson
import com.strikecam.arcade.ui.theme.OledBlack
import com.strikecam.arcade.ui.theme.PerfectGreen
import com.strikecam.arcade.ui.theme.PowerGold
import com.strikecam.arcade.ui.theme.RightCyan
import com.strikecam.arcade.vision.FistKinematics
import com.strikecam.arcade.vision.PosePoint
import com.strikecam.arcade.vision.StrikePoseSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val UPPER_BODY_CONNECTIONS = listOf(
    11 to 12, // Shoulders
    11 to 13, 13 to 15, // Left arm
    12 to 14, 14 to 16, // Right arm
    11 to 23, 12 to 24, 23 to 24 // Torso frame
)

/**
 * 60-120 FPS Tactical Arcade 3D Perspective Canvas:
 * - Maps MediaPipe normalized pose points with exact FILL_CENTER parity against CameraX 4:3 PreviewView
 * - Renders 3D approaching strike orbs, closing timing rings, directional slash chevrons, double-power beams,
 *   translucent laser dodge walls, glowing crimson/cyan fist reticles with motion trails, spark bursts & popups.
 */
@Composable
fun ArcadeCanvas(
    frame: GameFrameSnapshot,
    backdropMode: CameraBackdropMode,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(10f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }
    val subPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        // 1. Camera backdrop scrim (FULL_CAMERA / DIM_50 / OLED_SILHOUETTE)
        drawRect(color = OledBlack.copy(alpha = backdropMode.scrimAlpha))

        // Subtle impact micro-shake offset
        val shakePx = frame.shakeIntensity * 12f
        val shakePhase = frame.currentTimeMs * 0.09f
        val dxShake = if (shakePx > 0.2f) cos(shakePhase) * shakePx else 0f
        val dyShake = if (shakePx > 0.2f) sin(shakePhase * 1.3f) * shakePx else 0f

        val pose = frame.pose
        val mapper = FillCenterMapper.create(
            canvasWidth = w,
            canvasHeight = h,
            imageWidth = pose.imageWidth,
            imageHeight = pose.imageHeight
        )

        translate(left = dxShake, top = dyShake) {
            // 2. 3D Perspective Neon-Tactical Tunnel
            drawTacticalTunnel(
                w = w,
                h = h,
                currentTimeMs = frame.currentTimeMs,
                isFever = frame.isFeverMode
            )

            // 3. Silhouette / Tactical Skeleton Overlay (prominent in OLED mode, subtle in DIM_50)
            if (pose.hasPose && pose.landmarks.size >= 25) {
                val skeletonAlpha = when (backdropMode) {
                    CameraBackdropMode.OLED_SILHOUETTE -> 0.92f
                    CameraBackdropMode.DIM_50 -> 0.34f
                    CameraBackdropMode.FULL_CAMERA -> 0.16f
                }
                drawBodySilhouette(pose, mapper, skeletonAlpha)
            }

            // 4. Approaching Targets & Dodge Walls (sorted far-to-near so near targets pop in front)
            val sortedTargets = frame.activeTargets.sortedBy { it.depthZ }
            for (rt in sortedTargets) {
                if (rt.target.type.isDodge) {
                    drawDodgeWall(rt, pose, w, h, labelPaint)
                } else {
                    drawStrikeTarget(rt, mapper, w, h, labelPaint)
                }
            }

            // 5. Glowing Fist Trails & Punch Reticles (Left = Crimson, Right = Cyan)
            if (pose.hasPose) {
                drawFistReticle(pose.leftFist, LeftCrimson, mapper)
                drawFistReticle(pose.rightFist, RightCyan, mapper)
            }

            // 6. Hit Spark Particles
            for (p in frame.particles) {
                drawSpark(p, mapper)
            }

            // 7. Floating Verdict Popups
            for (pop in frame.popups) {
                drawVerdictPopup(pop, frame.currentTimeMs, mapper, labelPaint, subPaint)
            }
        }
    }
}

private class FillCenterMapper(
    private val scale: Float,
    private val offsetX: Float,
    private val offsetY: Float,
    private val imageWidth: Float,
    private val imageHeight: Float
) {
    fun map(normX: Float, normY: Float): Offset {
        val x = normX * imageWidth * scale + offsetX
        val y = normY * imageHeight * scale + offsetY
        return Offset(x, y)
    }

    companion object {
        fun create(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int): FillCenterMapper {
            val iw = imageWidth.coerceAtLeast(1).toFloat()
            val ih = imageHeight.coerceAtLeast(1).toFloat()
            val scale = max(canvasWidth / iw, canvasHeight / ih)
            val drawnW = iw * scale
            val drawnH = ih * scale
            val offsetX = (canvasWidth - drawnW) * 0.5f
            val offsetY = (canvasHeight - drawnH) * 0.5f
            return FillCenterMapper(scale, offsetX, offsetY, iw, ih)
        }
    }
}

private fun DrawScope.drawTacticalTunnel(
    w: Float,
    h: Float,
    currentTimeMs: Float,
    isFever: Boolean
) {
    val vanish = Offset(w * 0.5f, h * 0.38f)
    val railColor = if (isFever) PowerGold.copy(alpha = 0.28f) else CrispWhite.copy(alpha = 0.10f)

    // 4 Perspective tunnel rails from vanishing horizon to screen corners
    val corners = listOf(
        Offset(0f, 0f),
        Offset(w, 0f),
        Offset(0f, h),
        Offset(w, h),
        Offset(w * 0.16f, h),
        Offset(w * 0.84f, h)
    )
    for (c in corners) {
        drawLine(
            color = railColor,
            start = vanish,
            end = c,
            strokeWidth = 1.6f
        )
    }

    // 3 Traveling depth rings giving strong sense of forward velocity
    val cycle = (currentTimeMs / 950f) % 1f
    for (i in 0..2) {
        val z = ((cycle + i / 3f) % 1f).pow(1.9f)
        val rectW = w * (0.14f + 0.92f * z)
        val rectH = h * (0.12f + 0.88f * z)
        val left = vanish.x - rectW * 0.5f
        val top = vanish.y - rectH * 0.42f
        val alpha = (0.04f + 0.16f * z).coerceIn(0f, 0.22f)
        drawRoundRect(
            color = if (isFever) PowerGold.copy(alpha = alpha * 1.4f) else RightCyan.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(rectW, rectH),
            cornerRadius = CornerRadius(24f * z, 24f * z),
            style = Stroke(width = 1.5f + 2f * z)
        )
    }

    // Fever edge vignette glow (combo >= 20)
    if (isFever) {
        val pulse = 0.18f + 0.10f * sin(currentTimeMs * 0.012f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, PowerGold.copy(alpha = pulse)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = max(w, h) * 0.72f
            )
        )
    }
}

private fun DrawScope.drawBodySilhouette(
    pose: StrikePoseSnapshot,
    mapper: FillCenterMapper,
    alpha: Float
) {
    val pts = pose.landmarks
    for ((a, b) in UPPER_BODY_CONNECTIONS) {
        if (a >= pts.size || b >= pts.size) continue
        val pa = pts[a]
        val pb = pts[b]
        if (pa.visibility < 0.30f || pb.visibility < 0.30f) continue
        val segColor = when {
            a == 11 && b == 13 || a == 13 && b == 15 -> LeftCrimson.copy(alpha = alpha * 0.85f)
            a == 12 && b == 14 || a == 14 && b == 16 -> RightCyan.copy(alpha = alpha * 0.85f)
            else -> CrispWhite.copy(alpha = alpha * 0.55f)
        }
        drawLine(
            color = segColor,
            start = mapper.map(pa.x, pa.y),
            end = mapper.map(pb.x, pb.y),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }

    // Head tactical visor halo
    val headCenter = mapper.map(pose.headX, pose.headY)
    val headColor = when {
        pose.isDucking || pose.isSlippingLeft || pose.isSlippingRight -> PerfectGreen
        else -> CrispWhite.copy(alpha = alpha * 0.75f)
    }
    drawCircle(
        color = headColor,
        radius = 26f,
        center = headCenter,
        style = Stroke(width = 3.5f)
    )
}

private fun DrawScope.drawStrikeTarget(
    rt: ActiveRenderTarget,
    mapper: FillCenterMapper,
    w: Float,
    h: Float,
    labelPaint: Paint
) {
    val t = rt.target
    val z = rt.depthZ.coerceIn(0f, 1.18f)
    val ease = z.coerceIn(0f, 1f).pow(1.65f)

    val vanishX = w * 0.5f
    val vanishY = h * 0.38f

    if (t.type == TargetType.DOUBLE_STRIKE) {
        val leftDst = mapper.map(0.34f, rt.screenY)
        val rightDst = mapper.map(0.66f, rt.screenY)
        val lx = vanishX + (leftDst.x - vanishX) * ease
        val ly = vanishY + (leftDst.y - vanishY) * ease
        val rx = vanishX + (rightDst.x - vanishX) * ease
        val ry = vanishY + (rightDst.y - vanishY) * ease

        val baseRadius = min(w, h) * 0.105f
        val r = baseRadius * (0.24f + 0.76f * ease)
        val alpha = (0.20f + 0.80f * z.coerceIn(0f, 1f))

        // Connecting Power Gold energy beam
        drawLine(
            color = PowerGold.copy(alpha = alpha * 0.85f),
            start = Offset(lx, ly),
            end = Offset(rx, ry),
            strokeWidth = (4f + 8f * ease),
            cap = StrokeCap.Round
        )

        drawSingleOrb(Offset(lx, ly), r, z, PowerGold, LeftCrimson, t.type, TargetHand.LEFT, alpha, labelPaint)
        drawSingleOrb(Offset(rx, ry), r, z, PowerGold, RightCyan, t.type, TargetHand.RIGHT, alpha, labelPaint)
        return
    }

    val dst = mapper.map(rt.screenX, rt.screenY)
    val cx = vanishX + (dst.x - vanishX) * ease
    val cy = vanishY + (dst.y - vanishY) * ease

    val primaryColor = when (t.hand) {
        TargetHand.LEFT -> LeftCrimson
        TargetHand.RIGHT -> RightCyan
        TargetHand.BOTH -> PowerGold
        TargetHand.BODY -> DodgeAmber
    }
    val baseRadius = min(w, h) * 0.112f
    val r = baseRadius * (0.24f + 0.76f * ease)
    val alpha = (0.22f + 0.78f * z.coerceIn(0f, 1f))

    drawSingleOrb(Offset(cx, cy), r, z, primaryColor, primaryColor, t.type, t.hand, alpha, labelPaint)
}

private fun DrawScope.drawSingleOrb(
    center: Offset,
    radius: Float,
    depthZ: Float,
    accent: Color,
    innerAccent: Color,
    type: TargetType,
    hand: TargetHand,
    alpha: Float,
    labelPaint: Paint
) {
    val inPerfectZone = depthZ in 0.89f..1.08f
    val ringColor = if (inPerfectZone) PerfectGreen else accent

    // Soft outer halo
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.42f * alpha), Color.Transparent),
            center = center,
            radius = radius * 1.65f
        ),
        radius = radius * 1.65f,
        center = center
    )

    // Core dark tactical orb body
    drawCircle(
        color = OledBlack.copy(alpha = 0.72f * alpha),
        radius = radius,
        center = center
    )

    // Bold inner rim
    drawCircle(
        color = innerAccent.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = Stroke(width = max(4f, radius * 0.14f))
    )

    // Closing Precision Timing Ring (collapses from 2.25x radius -> 1.0x radius right at depthZ = 1.0)
    val approachFactor = (1f - depthZ).coerceIn(-0.18f, 1f)
    val timingRadius = radius * (1.0f + max(0f, approachFactor) * 1.35f)
    drawCircle(
        color = ringColor.copy(alpha = (0.45f + 0.55f * (1f - absClamped(1f - depthZ))) * alpha),
        radius = timingRadius,
        center = center,
        style = Stroke(width = if (inPerfectZone) 7f else 3.5f)
    )

    // Inner glyph: directional slash chevron OR hand badge (G / D / 2X)
    if (type.isSlash) {
        drawSlashChevron(center, radius * 0.52f, type, hand, CrispWhite.copy(alpha = alpha))
    } else if (type == TargetType.DOUBLE_STRIKE) {
        labelPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 224, 102)
        labelPaint.textSize = radius * 0.68f
        drawContext.canvas.nativeCanvas.drawText("2×", center.x, center.y + radius * 0.24f, labelPaint)
    } else {
        // Crisp target crosshair dot + hand initial
        drawCircle(
            color = CrispWhite.copy(alpha = 0.9f * alpha),
            radius = radius * 0.16f,
            center = center
        )
        labelPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), 245, 247, 250)
        labelPaint.textSize = radius * 0.52f
        val letter = if (hand == TargetHand.LEFT) "G" else "D"
        drawContext.canvas.nativeCanvas.drawText(letter, center.x, center.y + radius * 0.72f, labelPaint)
    }
}

private fun DrawScope.drawSlashChevron(
    center: Offset,
    r: Float,
    type: TargetType,
    hand: TargetHand,
    color: Color
) {
    val angleDeg = when (type) {
        TargetType.SLASH_UP -> -90f
        TargetType.SLASH_DOWN -> 90f
        TargetType.SLASH_IN -> if (hand == TargetHand.LEFT) 0f else 180f
        TargetType.SLASH_OUT -> if (hand == TargetHand.LEFT) 180f else 0f
        else -> -90f
    }
    val rad = angleDeg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    fun rot(x: Float, y: Float) = Offset(
        center.x + x * cosA - y * sinA,
        center.y + x * sinA + y * cosA
    )

    // Shaft + bold arrowhead pointing in exact required swipe vector
    drawLine(
        color = color,
        start = rot(-r * 0.78f, 0f),
        end = rot(r * 0.70f, 0f),
        strokeWidth = max(5f, r * 0.24f),
        cap = StrokeCap.Round
    )
    val path = Path().apply {
        moveTo(rot(r * 0.12f, -r * 0.62f).x, rot(r * 0.12f, -r * 0.62f).y)
        lineTo(rot(r * 0.82f, 0f).x, rot(r * 0.82f, 0f).y)
        lineTo(rot(r * 0.12f, r * 0.62f).x, rot(r * 0.12f, r * 0.62f).y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = max(5f, r * 0.24f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawDodgeWall(
    rt: ActiveRenderTarget,
    pose: StrikePoseSnapshot,
    w: Float,
    h: Float,
    labelPaint: Paint
) {
    val z = rt.depthZ.coerceIn(0f, 1.18f)
    val ease = z.coerceIn(0f, 1f).pow(1.55f)
    val alpha = (0.18f + 0.68f * z.coerceIn(0f, 1f))

    val isCleared = when (rt.target.type) {
        TargetType.DODGE_DUCK -> pose.isDucking
        TargetType.DODGE_SLIP_LEFT -> pose.isSlippingLeft
        TargetType.DODGE_SLIP_RIGHT -> pose.isSlippingRight
        else -> false
    }
    val wallColor = if (isCleared) PerfectGreen else DodgeAmber

    val rect: Pair<Offset, Size> = when (rt.target.type) {
        TargetType.DODGE_DUCK -> {
            // Overhead laser barrier descending across upper half
            val wallW = w * (0.34f + 0.66f * ease)
            val wallH = h * (0.12f + 0.28f * ease)
            val top = h * 0.18f * (1f - ease) + h * 0.06f * ease
            Offset((w - wallW) * 0.5f, top) to Size(wallW, wallH)
        }
        TargetType.DODGE_SLIP_LEFT -> {
            // Blocks RIGHT side of arena -> player must slip LEFT!
            val wallW = w * (0.22f + 0.34f * ease)
            val wallH = h * (0.30f + 0.56f * ease)
            val left = w * 0.5f + w * 0.04f * (1f - ease)
            val top = (h - wallH) * 0.45f
            Offset(left, top) to Size(wallW, wallH)
        }
        TargetType.DODGE_SLIP_RIGHT -> {
            // Blocks LEFT side of arena -> player must slip RIGHT!
            val wallW = w * (0.22f + 0.34f * ease)
            val wallH = h * (0.30f + 0.56f * ease)
            val left = w * 0.5f - wallW - w * 0.04f * (1f - ease)
            val top = (h - wallH) * 0.45f
            Offset(left, top) to Size(wallW, wallH)
        }
        else -> Offset.Zero to Size.Zero
    }

    val (topLeft, rectSize) = rect
    drawRoundRect(
        color = wallColor.copy(alpha = 0.24f * alpha),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = CornerRadius(18f, 18f)
    )
    drawRoundRect(
        color = wallColor.copy(alpha = 0.92f * alpha),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = CornerRadius(18f, 18f),
        style = Stroke(width = 4f + 4f * ease)
    )

    // Tactical hazard scan lines across barrier
    val stripeCount = 4
    for (i in 1 until stripeCount) {
        val frac = i / stripeCount.toFloat()
        drawLine(
            color = wallColor.copy(alpha = 0.35f * alpha),
            start = Offset(topLeft.x + 8f, topLeft.y + rectSize.height * frac),
            end = Offset(topLeft.x + rectSize.width - 8f, topLeft.y + rectSize.height * frac),
            strokeWidth = 2f
        )
    }

    // Big unmistakable French directive banner
    val directive = when (rt.target.type) {
        TargetType.DODGE_DUCK -> if (isCleared) "ESQUIVE OK ✓" else "↓ BAISSE-TOI !"
        TargetType.DODGE_SLIP_LEFT -> if (isCleared) "ESQUIVE OK ✓" else "← ESQUIVE GAUCHE !"
        TargetType.DODGE_SLIP_RIGHT -> if (isCleared) "ESQUIVE OK ✓" else "ESQUIVE DROITE →"
        else -> ""
    }
    labelPaint.color = if (isCleared) {
        android.graphics.Color.argb(255, 30, 215, 96)
    } else {
        android.graphics.Color.argb((alpha * 255).toInt(), 255, 240, 210)
    }
    labelPaint.textSize = (28f + 26f * ease).coerceAtMost(56f)
    drawContext.canvas.nativeCanvas.drawText(
        directive,
        w * 0.5f,
        topLeft.y + rectSize.height * 0.56f,
        labelPaint
    )
}

private fun DrawScope.drawFistReticle(
    fist: FistKinematics,
    color: Color,
    mapper: FillCenterMapper
) {
    if (!fist.visible) return

    // Fading ribbon motion trail
    val trail = fist.trail
    if (trail.size >= 2) {
        for (i in 1 until trail.size) {
            val frac = i.toFloat() / trail.size
            val p0 = mapper.map(trail[i - 1].first, trail[i - 1].second)
            val p1 = mapper.map(trail[i].first, trail[i].second)
            drawLine(
                color = color.copy(alpha = frac * 0.65f),
                start = p0,
                end = p1,
                strokeWidth = 6f + 16f * frac,
                cap = StrokeCap.Round
            )
        }
    }

    val center = mapper.map(fist.x, fist.y)
    val energy = (fist.speed2D * 0.35f + max(0f, fist.forwardThrust) * 0.25f).coerceIn(0f, 1.0f)
    val r = 24f + energy * 18f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.65f), Color.Transparent),
            center = center,
            radius = r * 1.9f
        ),
        radius = r * 1.9f,
        center = center
    )
    drawCircle(
        color = CrispWhite,
        radius = r * 0.42f,
        center = center
    )
    drawCircle(
        color = color,
        radius = r,
        center = center,
        style = Stroke(width = 4.5f)
    )
}

private fun DrawScope.drawSpark(p: SparkParticle, mapper: FillCenterMapper) {
    val pos = mapper.map(p.x, p.y)
    val alpha = p.lifeFrac.coerceIn(0f, 1f)
    val color = Color(p.colorArgb).copy(alpha = alpha)
    drawCircle(
        color = color,
        radius = p.radiusPx * (0.4f + 0.8f * alpha),
        center = pos
    )
}

private fun DrawScope.drawVerdictPopup(
    pop: HitPopup,
    currentTimeMs: Float,
    mapper: FillCenterMapper,
    labelPaint: Paint,
    subPaint: Paint
) {
    val ageFrac = ((currentTimeMs - pop.createdMs) / 650f).coerceIn(0f, 1f)
    val alpha = ((1f - ageFrac) * 1.35f).coerceIn(0f, 1f)
    val base = mapper.map(pop.normX, pop.normY)
    val y = base.y - 36f - ageFrac * 78f

    val rgb = when (pop.verdict) {
        HitVerdict.PERFECT -> Triple(30, 215, 96)
        HitVerdict.GREAT -> Triple(0, 212, 255)
        HitVerdict.GOOD -> Triple(255, 184, 0)
        HitVerdict.DODGE_OK -> Triple(30, 215, 96)
        HitVerdict.MISS -> Triple(255, 42, 75)
    }

    labelPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), rgb.first, rgb.second, rgb.third)
    labelPaint.textSize = 46f + (1f - ageFrac) * 10f
    drawContext.canvas.nativeCanvas.drawText(pop.label, base.x, y, labelPaint)

    if (!pop.bonusTag.isNullOrBlank()) {
        subPaint.color = android.graphics.Color.argb((alpha * 235).toInt(), 255, 224, 102)
        subPaint.textSize = 28f
        drawContext.canvas.nativeCanvas.drawText(pop.bonusTag, base.x, y + 34f, subPaint)
    }
}

private fun absClamped(v: Float): Float = kotlin.math.abs(v).coerceIn(0f, 1f)
