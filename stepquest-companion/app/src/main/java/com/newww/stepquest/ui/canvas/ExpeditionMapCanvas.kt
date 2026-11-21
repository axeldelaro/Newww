package com.newww.stepquest.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.StepQuestState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ExpeditionMapCanvas(
    state: StepQuestState,
    modifier: Modifier = Modifier
) {
    val biome = ExpeditionEngine.biomeForSteps(state.totalSteps)
    val progress = ExpeditionEngine.biomeProgress(state.totalSteps)
    val hour = ExpeditionEngine.currentHour()
    val isNight = hour >= 19 || hour < 6

    var animTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            animTime += ((now - last) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            last = now
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Ciel du biome
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(biome.skyTopHex), Color(biome.skyBottomHex))
            )
        )

        // 2. Étoiles / Aurores ou Soleil
        if (isNight || biome.index >= 5) {
            for (i in 0 until 24) {
                val sx = ((i * 59 % 97) / 97f) * w
                val sy = ((i * 31 % 45) / 100f) * h
                val tw = 0.4f + 0.6f * abs(sin(animTime * 2f + i))
                drawCircle(Color(0xFFF3F6FF).copy(alpha = tw * 0.8f), if (i % 4 == 0) 2.6f else 1.5f, Offset(sx, sy))
            }
            if (biome.index == 5) {
                // Rideau d'aurore boréale
                val aurora = Path().apply {
                    moveTo(0f, h * 0.22f)
                    cubicTo(w * 0.3f, h * (0.12f + 0.04f * sin(animTime)), w * 0.7f, h * (0.28f - 0.04f * cos(animTime)), w, h * 0.16f)
                }
                drawPath(aurora, Color(0x554BFFB5), style = Stroke(width = 24f, cap = StrokeCap.Round))
            }
            drawCircle(Color(0xFFFFF5CC), 17f, Offset(w * 0.85f, h * 0.18f))
            drawCircle(Color(biome.skyTopHex), 14f, Offset(w * 0.87f, h * 0.16f))
        } else {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xAAFFE49E), Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.20f),
                    radius = 65f
                ),
                radius = 65f,
                center = Offset(w * 0.82f, h * 0.20f)
            )
            drawCircle(Color(0xFFFFE58F), 18f, Offset(w * 0.82f, h * 0.20f))
        }

        // 3. Montagnes / collines lointaines
        val farHills = Path().apply {
            moveTo(0f, h * 0.62f)
            lineTo(w * 0.22f, h * 0.36f)
            lineTo(w * 0.48f, h * 0.56f)
            lineTo(w * 0.74f, h * 0.32f)
            lineTo(w, h * 0.52f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(farHills, Color(biome.groundHex).copy(alpha = 0.55f))

        // 4. Sol principal du biome
        val ground = Path().apply {
            moveTo(0f, h * 0.60f)
            quadraticTo(w * 0.35f, h * 0.52f, w * 0.65f, h * 0.62f)
            quadraticTo(w * 0.85f, h * 0.67f, w, h * 0.58f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = ground,
            brush = Brush.verticalGradient(
                listOf(Color(biome.groundHex), Color(0xFF10131C)),
                startY = h * 0.55f,
                endY = h
            )
        )

        // 5. Arbres / lanternes / dunes selon biome
        for (i in 0 until 6) {
            val tx = w * (0.08f + i * 0.17f)
            val ty = h * (0.61f + (i % 2) * 0.04f)
            if (biome.index == 4) {
                // Obélisque / cristal dans le désert
                val p = Path().apply {
                    moveTo(tx, ty - 28f)
                    lineTo(tx + 9f, ty + 4f)
                    lineTo(tx - 9f, ty + 4f)
                    close()
                }
                drawPath(p, Color(0x66FFE082))
            } else {
                drawLine(Color(0xFF4B382A), Offset(tx, ty), Offset(tx, ty - 26f), strokeWidth = 5f)
                drawCircle(Color(biome.groundHex).copy(alpha = 0.9f), 14f, Offset(tx, ty - 30f))
                if (biome.index == 1) {
                    val glow = 0.6f + 0.4f * sin(animTime * 3f + i)
                    drawCircle(Color(0xFFFFE57F).copy(alpha = glow), 4.5f, Offset(tx + 8f, ty - 20f))
                }
            }
        }

        // 6. Sentier sinueux avec 4 jalons (landmarks)
        val pathYBase = h * 0.78f
        val trail = Path().apply {
            moveTo(w * 0.06f, pathYBase)
            cubicTo(
                w * 0.32f, pathYBase - h * 0.10f,
                w * 0.68f, pathYBase + h * 0.09f,
                w * 0.94f, pathYBase - h * 0.03f
            )
        }
        drawPath(trail, Color(0x44FFD685), style = Stroke(width = 12f, cap = StrokeCap.Round))
        drawPath(
            trail,
            Color(0xFFF6C768),
            style = Stroke(
                width = 4f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            )
        )

        // Jalons d'étapes
        for (i in 0 until 4) {
            val t = (i + 0.5f) / 4f
            val pt = trailPoint(w, pathYBase, h, t)
            val reached = progress >= t
            drawCircle(
                color = if (reached) Color(0xFFFFE082) else Color(0xFF475569),
                radius = if (reached) 7f else 5.5f,
                center = pt
            )
        }

        // 7. Aventurier animé marchant le long du sentier
        val heroPos = trailPoint(w, pathYBase, h, progress.coerceIn(0.05f, 0.95f))
        val walkBounce = abs(sin(animTime * 6.5f)) * 5.5f
        val hx = heroPos.x
        val hy = heroPos.y - 16f - walkBounce

        // Ombre au sol
        drawOval(Color(0x55000000), Offset(hx - 14f, heroPos.y - 2f), Size(28f, 9f))

        // Sac à dos rempli
        drawRoundRect(
            color = Color(0xFF8D5B34),
            topLeft = Offset(hx - 17f, hy - 14f),
            size = Size(12f, 18f),
            cornerRadius = CornerRadius(4f, 4f)
        )

        // Cape personnalisée flottant au vent
        val cloakWave = sin(animTime * 5f) * 4f
        val cloak = Path().apply {
            moveTo(hx - 2f, hy - 16f)
            lineTo(hx + 12f, hy + 10f)
            lineTo(hx - 14f + cloakWave, hy + 11f)
            close()
        }
        drawPath(cloak, Color(state.customCloakHex))

        // Tête & capuche / chapeau
        drawCircle(Color(0xFFFFE2C4), 9.5f, Offset(hx + 1f, hy - 22f))
        drawArc(
            color = Color(state.customCloakHex),
            startAngle = 175f,
            sweepAngle = 190f,
            useCenter = true,
            topLeft = Offset(hx - 10f, hy - 34f),
            size = Size(22f, 18f)
        )
        // Lanterne dans la main si équipée ou la nuit
        if (isNight || "lantern" in state.packedGearIds) {
            val lx = hx + 14f
            val ly = hy - 8f
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x99FFE082), Color.Transparent), Offset(lx, ly), 28f),
                radius = 28f,
                center = Offset(lx, ly)
            )
            drawCircle(Color(0xFFFFF59D), 4f, Offset(lx, ly))
        }

        // 8. Feu de camp animé si c'est le soir
        if (isNight) {
            val fx = w * 0.14f
            val fy = h * 0.87f
            val flicker = 1f + 0.18f * sin(animTime * 11f)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x88FF9E44), Color.Transparent), Offset(fx, fy - 6f), 42f * flicker),
                radius = 42f * flicker,
                center = Offset(fx, fy - 6f)
            )
            drawCircle(Color(0xFFFF7043), 9f * flicker, Offset(fx, fy - 4f))
            drawCircle(Color(0xFFFFEB3B), 5f * flicker, Offset(fx, fy - 2f))
        }
    }
}

private fun trailPoint(w: Float, baseY: Float, h: Float, t: Float): Offset {
    val u = 1f - t
    val x0 = w * 0.06f
    val y0 = baseY
    val x1 = w * 0.32f
    val y1 = baseY - h * 0.10f
    val x2 = w * 0.68f
    val y2 = baseY + h * 0.09f
    val x3 = w * 0.94f
    val y3 = baseY - h * 0.03f

    val x = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
    val y = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
    return Offset(x, y)
}
