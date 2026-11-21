package com.newww.stepquest.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.StepQuestState
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

private data class MapTreasure(val id: Int, val xNorm: Float, val yNorm: Float, val color: Color)
private data class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Color)

@Composable
fun ExpeditionMapCanvas(
    state: StepQuestState,
    onCollectTreasure: () -> Unit,
    onCheerHero: () -> Unit,
    modifier: Modifier = Modifier
) {
    val biome = ExpeditionEngine.biomeForSteps(state.totalSteps)
    val progress = ExpeditionEngine.biomeProgress(state.totalSteps)
    val hour = ExpeditionEngine.currentHour()
    val isNight = hour >= 19 || hour < 6

    var animTime by remember { mutableFloatStateOf(0f) }
    var heroHop by remember { mutableFloatStateOf(0f) }
    var nextId by remember { mutableIntStateOf(1) }
    val treasures = remember {
        mutableStateListOf(
            MapTreasure(101, 0.28f, 0.72f, Color(0xFFFFE082)),
            MapTreasure(102, 0.68f, 0.79f, Color(0xFF8BF5C5)),
            MapTreasure(103, 0.84f, 0.69f, Color(0xFFC5B4FF))
        )
    }
    val sparks = remember { mutableStateListOf<Spark>() }

    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        var respawnTimer = 0f
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            last = now
            animTime += dt
            heroHop *= (1f - dt * 8f).coerceAtLeast(0f)

            respawnTimer += dt
            if (respawnTimer > 5.5f && treasures.size < 3) {
                respawnTimer = 0f
                val colors = listOf(Color(0xFFFFE082), Color(0xFF8BF5C5), Color(0xFFC5B4FF), Color(0xFFFF9E7D))
                treasures += MapTreasure(
                    id = nextId++,
                    xNorm = 0.16f + Random.nextFloat() * 0.70f,
                    yNorm = 0.65f + Random.nextFloat() * 0.18f,
                    color = colors[Random.nextInt(colors.size)]
                )
            }

            val it = sparks.listIterator()
            while (it.hasNext()) {
                val sp = it.next()
                sp.life += dt
                if (sp.life > 0.75f) it.remove()
                else {
                    sp.x += sp.vx * dt
                    sp.y += sp.vy * dt
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)

                    val hit = treasures.firstOrNull { hypot(it.xNorm - nx, it.yNorm - ny) < 0.11f }
                    if (hit != null) {
                        treasures.remove(hit)
                        repeat(12) {
                            val a = Random.nextFloat() * 2f * PI.toFloat()
                            sparks += Spark(hit.xNorm, hit.yNorm, cos(a) * 0.22f, sin(a) * 0.22f, 0f, hit.color)
                        }
                        onCollectTreasure()
                        return@detectTapGestures
                    }

                    // Sinon salut / encouragement du héros !
                    heroHop = -26f
                    repeat(8) {
                        val a = Random.nextFloat() * 2f * PI.toFloat()
                        sparks += Spark(nx, ny, cos(a) * 0.16f, sin(a) * 0.16f, 0f, Color(0xFFFFE082))
                    }
                    onCheerHero()
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // 1. Ciel riche + nuages défilants en parallaxe
        drawRect(Brush.verticalGradient(listOf(Color(biome.skyTopHex), Color(biome.skyBottomHex))))

        for (i in 0 until 4) {
            val cx = ((i * 0.29f + animTime * 0.015f) % 1.25f - 0.12f) * w
            val cy = h * (0.14f + (i % 2) * 0.09f)
            val cloudAlpha = if (isNight) 0.10f else 0.22f
            drawOval(Color.White.copy(alpha = cloudAlpha), Offset(cx - 38f, cy), Size(86f, 24f))
            drawOval(Color.White.copy(alpha = cloudAlpha), Offset(cx - 18f, cy - 10f), Size(54f, 22f))
        }

        // 2. Étoiles, aurores ou soleil rayonnant
        if (isNight || biome.index >= 5) {
            for (i in 0 until 28) {
                val sx = ((i * 59 % 97) / 97f) * w
                val sy = ((i * 31 % 45) / 100f) * h
                val tw = 0.35f + 0.65f * abs(sin(animTime * 2f + i))
                drawCircle(Color(0xFFF3F6FF).copy(alpha = tw * 0.85f), if (i % 4 == 0) 2.8f else 1.5f, Offset(sx, sy))
            }
            if (biome.index >= 5) {
                val aurora = Path().apply {
                    moveTo(0f, h * 0.22f)
                    cubicTo(w * 0.3f, h * (0.10f + 0.04f * sin(animTime)), w * 0.7f, h * (0.28f - 0.04f * cos(animTime)), w, h * 0.15f)
                }
                drawPath(aurora, Color(0x554BFFB5), style = Stroke(width = 28f, cap = StrokeCap.Round))
            }
            drawCircle(Color(0xFFFFF5CC), 19f, Offset(w * 0.85f, h * 0.17f))
            drawCircle(Color(biome.skyTopHex), 16f, Offset(w * 0.875f, h * 0.15f))
        } else {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xBBFFE49E), Color.Transparent), Offset(w * 0.83f, h * 0.18f), 74f),
                74f,
                Offset(w * 0.83f, h * 0.18f)
            )
            drawCircle(Color(0xFFFFE58F), 19f, Offset(w * 0.83f, h * 0.18f))
        }

        // 3. Chaîne de montagnes lointaines + collines intermédiaires
        val farHills = Path().apply {
            moveTo(0f, h * 0.58f)
            lineTo(w * 0.20f, h * 0.32f)
            lineTo(w * 0.44f, h * 0.50f)
            lineTo(w * 0.70f, h * 0.28f)
            lineTo(w, h * 0.48f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(farHills, Color(biome.groundHex).copy(alpha = 0.52f))

        // 4. Sol principal du biome
        val ground = Path().apply {
            moveTo(0f, h * 0.56f)
            quadraticTo(w * 0.35f, h * 0.48f, w * 0.65f, h * 0.58f)
            quadraticTo(w * 0.85f, h * 0.63f, w, h * 0.54f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(ground, Brush.verticalGradient(listOf(Color(biome.groundHex), Color(0xFF0E101A)), startY = h * 0.52f, endY = h))

        // 5. Décors du biome (arbres / lanternes / cascades / obélisques)
        for (i in 0 until 7) {
            val tx = w * (0.07f + i * 0.15f)
            val ty = h * (0.58f + (i % 2) * 0.04f)
            if (biome.index == 4) {
                val p = Path().apply { moveTo(tx, ty - 30f); lineTo(tx + 10f, ty + 4f); lineTo(tx - 10f, ty + 4f); close() }
                drawPath(p, Color(0x77FFE082))
            } else {
                drawLine(Color(0xFF4B382A), Offset(tx, ty), Offset(tx, ty - 28f), strokeWidth = 5f)
                drawCircle(Color(biome.groundHex).copy(alpha = 0.92f), 16f, Offset(tx, ty - 32f))
                if (biome.index == 1 || isNight) {
                    val glow = 0.6f + 0.4f * sin(animTime * 3.2f + i)
                    drawCircle(Color(0xFFFFE57F).copy(alpha = glow), 4.5f, Offset(tx + 8f, ty - 20f))
                }
            }
        }

        // 6. Sentier doré sinueux + 4 jalons
        val pathYBase = h * 0.77f
        val trail = Path().apply {
            moveTo(w * 0.06f, pathYBase)
            cubicTo(w * 0.32f, pathYBase - h * 0.11f, w * 0.68f, pathYBase + h * 0.09f, w * 0.94f, pathYBase - h * 0.03f)
        }
        drawPath(trail, Color(0x44FFD685), style = Stroke(width = 14f, cap = StrokeCap.Round))
        drawPath(trail, Color(0xFFF6C768), style = Stroke(width = 4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))

        for (i in 0 until 4) {
            val t = (i + 0.5f) / 4f
            val pt = trailPoint(w, pathYBase, h, t)
            drawCircle(if (progress >= t) Color(0xFFFFE082) else Color(0xFF475569), if (progress >= t) 7.5f else 5.5f, pt)
        }

        // 7. Trésors interactifs cliquables sur la carte !
        for (tr in treasures) {
            val tx = tr.xNorm * w
            val ty = tr.yNorm * h + sin(animTime * 4f + tr.id) * 4f
            val pulse = 1f + 0.22f * sin(animTime * 5f + tr.id)
            drawCircle(Brush.radialGradient(listOf(tr.color.copy(alpha = 0.75f), Color.Transparent), Offset(tx, ty), 26f * pulse), 26f * pulse, Offset(tx, ty))
            drawRoundRect(tr.color, Offset(tx - 9f, ty - 7f), Size(18f, 14f), CornerRadius(4f, 4f))
            drawLine(Color(0xFF2B1D14), Offset(tx - 9f, ty), Offset(tx + 9f, ty), strokeWidth = 2.5f)
            drawCircle(Color.White, 3f, Offset(tx, ty))
        }

        // 8. Familier animal marchant derrière le héros !
        val famT = (progress - 0.08f).coerceIn(0.03f, 0.90f)
        val famPos = trailPoint(w, pathYBase, h, famT)
        val famBounce = abs(sin(animTime * 7.5f + 1f)) * 6f
        val famColor = Color(state.familiar.colorHex)
        drawCircle(famColor, 8.5f, Offset(famPos.x, famPos.y - 10f - famBounce))
        drawCircle(Color.White, 2.5f, Offset(famPos.x + 3f, famPos.y - 12f - famBounce))

        // 9. Aventurier animé (cape, sac, jambes en marche, lanterne)
        val heroPos = trailPoint(w, pathYBase, h, progress.coerceIn(0.06f, 0.94f))
        val walkBounce = abs(sin(animTime * 6.5f)) * 5.5f
        val hx = heroPos.x
        val hy = heroPos.y - 16f - walkBounce + heroHop

        drawOval(Color(0x55000000), Offset(hx - 15f, heroPos.y - 2f), Size(30f, 9f))

        // Jambes animées
        val stride = sin(animTime * 6.5f) * 6f
        drawLine(Color(0xFF2B2438), Offset(hx - 3f, hy + 8f), Offset(hx - 3f + stride, hy + 18f), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(Color(0xFF2B2438), Offset(hx + 3f, hy + 8f), Offset(hx + 3f - stride, hy + 18f), strokeWidth = 4f, cap = StrokeCap.Round)

        drawRoundRect(Color(0xFF8D5B34), Offset(hx - 18f, hy - 14f), Size(13f, 19f), CornerRadius(4f, 4f))

        val cloakWave = sin(animTime * 5.5f) * 5f
        val cloak = Path().apply {
            moveTo(hx - 2f, hy - 16f); lineTo(hx + 13f, hy + 10f); lineTo(hx - 15f + cloakWave, hy + 11f); close()
        }
        drawPath(cloak, Color(state.customCloakHex))
        drawCircle(Color(0xFFFFE2C4), 10f, Offset(hx + 1f, hy - 23f))
        drawArc(Color(state.customCloakHex), 175f, 190f, true, Offset(hx - 11f, hy - 35f), Size(24f, 19f))

        if (isNight || "lantern" in state.packedGearIds) {
            val lx = hx + 15f; val ly = hy - 8f
            drawCircle(Brush.radialGradient(listOf(Color(0xAAFFE082), Color.Transparent), Offset(lx, ly), 32f), 32f, Offset(lx, ly))
            drawCircle(Color(0xFFFFF59D), 4.5f, Offset(lx, ly))
        }

        // 10. Feu de camp animé + étincelles de collecte
        if (isNight) {
            val fx = w * 0.13f; val fy = h * 0.87f
            val flicker = 1f + 0.18f * sin(animTime * 11f)
            drawCircle(Brush.radialGradient(listOf(Color(0x88FF9E44), Color.Transparent), Offset(fx, fy - 6f), 44f * flicker), 44f * flicker, Offset(fx, fy - 6f))
            drawCircle(Color(0xFFFF7043), 9.5f * flicker, Offset(fx, fy - 4f))
            drawCircle(Color(0xFFFFEB3B), 5f * flicker, Offset(fx, fy - 2f))
        }

        for (sp in sparks) {
            drawCircle(sp.color.copy(alpha = (1f - sp.life / 0.75f).coerceIn(0f, 1f)), 5f, Offset(sp.x * w, sp.y * h))
        }
    }
}

private fun trailPoint(w: Float, baseY: Float, h: Float, t: Float): Offset {
    val u = 1f - t
    val x0 = w * 0.06f; val y0 = baseY
    val x1 = w * 0.32f; val y1 = baseY - h * 0.11f
    val x2 = w * 0.68f; val y2 = baseY + h * 0.09f
    val x3 = w * 0.94f; val y3 = baseY - h * 0.03f
    val x = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
    val y = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
    return Offset(x, y)
}
