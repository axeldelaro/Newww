package com.newww.luminaterrarium.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.newww.luminaterrarium.model.*
import com.newww.luminaterrarium.viewmodel.PulseKind
import com.newww.luminaterrarium.viewmodel.VisualPulseEvent
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

private data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    var alpha: Float,
    var color: Color,
    var life: Float,
    var maxLife: Float,
    var isHeart: Boolean = false
)

private data class MiniOrb(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val radius: Float
)

@Composable
fun TerrariumCanvas(
    spirit: SpiritProfile,
    mood: SpiritMood,
    dayPhase: DayPhase,
    battery: BatterySnapshot,
    tilt: TiltSnapshot,
    pulseEvent: VisualPulseEvent?,
    miniGameActive: Boolean,
    onPet: (Float, Float) -> Unit,
    onTapGlass: (Float, Float) -> Unit,
    onCatchOrb: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var animTime by remember { mutableFloatStateOf(0f) }
    var gazeTarget by remember { mutableStateOf<Offset?>(null) }
    var squishAmount by remember { mutableFloatStateOf(0f) }
    var hopOffset by remember { mutableFloatStateOf(0f) }
    var tapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var tapRippleRadius by remember { mutableFloatStateOf(0f) }
    var fallingFoodY by remember { mutableFloatStateOf(-1f) }
    var fallingFoodColor by remember { mutableStateOf(Color.Yellow) }
    var lastPetTimeMs by remember { mutableLongStateOf(0L) }

    val particles = remember { mutableStateListOf<Particle>() }
    val miniOrbs = remember { mutableStateListOf<MiniOrb>() }
    var nextOrbId by remember { mutableIntStateOf(1) }

    // Initialisation du mini-jeu lucioles
    LaunchedEffect(miniGameActive) {
        miniOrbs.clear()
        if (miniGameActive) {
            repeat(5) {
                miniOrbs += MiniOrb(
                    id = nextOrbId++,
                    x = 0.25f + Random.nextFloat() * 0.50f,
                    y = 0.28f + Random.nextFloat() * 0.44f,
                    vx = (Random.nextFloat() - 0.5f) * 0.28f,
                    vy = (Random.nextFloat() - 0.5f) * 0.24f,
                    radius = 0.048f
                )
            }
        }
    }

    // Réaction aux événements visuels (caresse, repas, brume, évolution…)
    LaunchedEffect(pulseEvent?.id) {
        val ev = pulseEvent ?: return@LaunchedEffect
        when (ev.kind) {
            PulseKind.PET -> {
                squishAmount = 0.22f
                repeat(7) {
                    particles += Particle(
                        x = ev.xNorm + (Random.nextFloat() - 0.5f) * 0.14f,
                        y = ev.yNorm + (Random.nextFloat() - 0.5f) * 0.08f,
                        vx = (Random.nextFloat() - 0.5f) * 0.12f,
                        vy = -0.18f - Random.nextFloat() * 0.16f,
                        radius = 10f + Random.nextFloat() * 8f,
                        alpha = 1f,
                        color = Color(0xFFFF7EB6),
                        life = 0f,
                        maxLife = 1.25f,
                        isHeart = true
                    )
                }
            }
            PulseKind.TAP -> {
                tapRipplePos = Offset(ev.xNorm, ev.yNorm)
                tapRippleRadius = 0.01f
                hopOffset = -0.045f
            }
            PulseKind.FEED -> {
                fallingFoodY = 0.22f
                fallingFoodColor = Color(ev.food?.colorHex ?: 0xFFFFD166)
                squishAmount = 0.16f
            }
            PulseKind.MIST -> {
                repeat(28) {
                    particles += Particle(
                        x = 0.24f + Random.nextFloat() * 0.52f,
                        y = 0.22f + Random.nextFloat() * 0.20f,
                        vx = (Random.nextFloat() - 0.5f) * 0.06f,
                        vy = 0.22f + Random.nextFloat() * 0.20f,
                        radius = 4f + Random.nextFloat() * 5f,
                        alpha = 0.85f,
                        color = Color(0xFF9DF0FF),
                        life = 0f,
                        maxLife = 1.35f
                    )
                }
            }
            PulseKind.EVOLVE -> {
                hopOffset = -0.07f
                repeat(36) { i ->
                    val ang = (i / 36f) * 2f * PI.toFloat()
                    particles += Particle(
                        x = 0.5f,
                        y = 0.56f,
                        vx = cos(ang) * (0.22f + Random.nextFloat() * 0.15f),
                        vy = sin(ang) * (0.22f + Random.nextFloat() * 0.15f),
                        radius = 6f + Random.nextFloat() * 8f,
                        alpha = 1f,
                        color = if (i % 2 == 0) Color(0xFFFFF08A) else Color(spirit.customAuraHex),
                        life = 0f,
                        maxLife = 1.6f
                    )
                }
            }
            PulseKind.LULLABY -> {
                repeat(12) {
                    particles += Particle(
                        x = 0.42f + Random.nextFloat() * 0.16f,
                        y = 0.50f,
                        vx = (Random.nextFloat() - 0.5f) * 0.08f,
                        vy = -0.14f - Random.nextFloat() * 0.08f,
                        radius = 7f,
                        alpha = 0.9f,
                        color = Color(0xFFD6C4FF),
                        life = 0f,
                        maxLife = 1.8f
                    )
                }
            }
            PulseKind.ORB_CAUGHT -> {
                repeat(14) {
                    val ang = Random.nextFloat() * 2f * PI.toFloat()
                    particles += Particle(
                        x = ev.xNorm,
                        y = ev.yNorm,
                        vx = cos(ang) * 0.22f,
                        vy = sin(ang) * 0.22f,
                        radius = 5f + Random.nextFloat() * 6f,
                        alpha = 1f,
                        color = Color(0xFFFFF28C),
                        life = 0f,
                        maxLife = 0.7f
                    )
                }
            }
        }
    }

    // Boucle d'animation 60 FPS
    LaunchedEffect(Unit) {
        var lastNs = System.nanoTime()
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
            lastNs = now
            animTime += dt

            squishAmount *= (1f - dt * 7f).coerceAtLeast(0f)
            hopOffset *= (1f - dt * 8f).coerceAtLeast(0f)

            if (tapRipplePos != null) {
                tapRippleRadius += dt * 0.55f
                if (tapRippleRadius > 0.28f) tapRipplePos = null
            }

            if (fallingFoodY >= 0f) {
                fallingFoodY += dt * 0.55f
                if (fallingFoodY >= 0.58f) {
                    fallingFoodY = -1f
                    repeat(10) {
                        val a = Random.nextFloat() * 2f * PI.toFloat()
                        particles += Particle(
                            x = 0.5f,
                            y = 0.58f,
                            vx = cos(a) * 0.14f,
                            vy = sin(a) * 0.14f,
                            radius = 5f,
                            alpha = 1f,
                            color = fallingFoodColor,
                            life = 0f,
                            maxLife = 0.65f
                        )
                    }
                }
            }

            // Mise à jour des particules
            val iter = particles.listIterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.life += dt
                if (p.life >= p.maxLife) {
                    iter.remove()
                } else {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.alpha = (1f - p.life / p.maxLife).coerceIn(0f, 1f)
                }
            }

            // Déplacement des lucioles du mini-jeu
            if (miniGameActive) {
                for (orb in miniOrbs) {
                    orb.x += orb.vx * dt
                    orb.y += orb.vy * dt
                    if (orb.x < 0.22f || orb.x > 0.78f) orb.vx = -orb.vx
                    if (orb.y < 0.26f || orb.y > 0.72f) orb.vy = -orb.vy
                    orb.x = orb.x.coerceIn(0.21f, 0.79f)
                    orb.y = orb.y.coerceIn(0.25f, 0.73f)
                }
                if (miniOrbs.size < 5) {
                    miniOrbs += MiniOrb(
                        id = nextOrbId++,
                        x = 0.25f + Random.nextFloat() * 0.50f,
                        y = 0.28f + Random.nextFloat() * 0.40f,
                        vx = (Random.nextFloat() - 0.5f) * 0.34f,
                        vy = (Random.nextFloat() - 0.5f) * 0.28f,
                        radius = 0.048f
                    )
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(miniGameActive) {
                detectTapGestures { offset ->
                    val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeTarget = Offset(nx, ny)

                    if (miniGameActive) {
                        val hit = miniOrbs.firstOrNull { orb ->
                            hypot(orb.x - nx, orb.y - ny) < 0.11f
                        }
                        if (hit != null) {
                            miniOrbs.remove(hit)
                            onCatchOrb(hit.x, hit.y)
                            return@detectTapGestures
                        }
                    }

                    val distToSpirit = hypot(nx - 0.5f, ny - 0.58f)
                    if (distToSpirit < 0.18f) {
                        onPet(nx, ny)
                    } else {
                        onTapGlass(nx, ny)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { gazeTarget = null }
                ) { change, _ ->
                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeTarget = Offset(nx, ny)
                    val dist = hypot(nx - 0.5f, ny - 0.58f)
                    val now = System.currentTimeMillis()
                    if (dist < 0.22f && now - lastPetTimeMs > 420L) {
                        lastPetTimeMs = now
                        onPet(nx, ny)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val jarTop = h * 0.15f
        val jarBottom = h * 0.86f
        val jarWidth = min(w * 0.78f, h * 0.62f)
        val jarLeft = cx - jarWidth / 2f
        val jarRight = cx + jarWidth / 2f
        val jarRect = Rect(jarLeft, jarTop, jarRight, jarBottom)

        // 1. Fond céleste synchronisé avec l'heure réelle
        drawSkyBackdrop(dayPhase, animTime, spirit.lightLevel)

        // 2. Halo externe derrière le bocal
        val auraColor = Color(spirit.customAuraHex)
        val auraPulse = 0.85f + 0.15f * sin(animTime * 2.2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    auraColor.copy(alpha = (0.28f * auraPulse * (0.4f + spirit.lightLevel / 160f)).coerceIn(0.05f, 0.45f)),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.55f),
                radius = jarWidth * 0.78f
            ),
            radius = jarWidth * 0.78f,
            center = Offset(cx, h * 0.55f)
        )

        // 3. Faisceau lumineux de la lampe réglable
        if (spirit.lightLevel > 15f) {
            val beamAlpha = (spirit.lightLevel / 100f) * 0.22f
            val beamPath = Path().apply {
                moveTo(cx - jarWidth * 0.16f, jarTop - h * 0.04f)
                lineTo(cx + jarWidth * 0.16f, jarTop - h * 0.04f)
                lineTo(jarRight + w * 0.04f, jarBottom)
                lineTo(jarLeft - w * 0.04f, jarBottom)
                close()
            }
            drawPath(
                path = beamPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF4C2).copy(alpha = beamAlpha), Color.Transparent),
                    startY = jarTop,
                    endY = jarBottom
                )
            )
        }

        // 4. Intérieur du bocal + sol / eau / braises / brume
        drawJarBackAndFloor(jarRect, spirit, tilt, animTime)

        // 5. Décorations débloquées et actives
        drawActiveDecorations(jarRect, spirit.activeDecorIds, animTime, auraColor)

        // 6. Esprit élémentaire animé (4 éléments x 4 stades d'évolution)
        val bobY = sin(animTime * if (spirit.isSleeping) 1.3f else 2.6f) * (h * 0.014f)
        val spiritCenter = Offset(
            x = cx + tilt.tiltX * (jarWidth * 0.11f),
            y = h * 0.58f + bobY + hopOffset * h + tilt.tiltY * (h * 0.02f)
        )
        drawSpiritCreature(
            center = spiritCenter,
            baseRadius = jarWidth * (0.145f + spirit.stage.level * 0.015f),
            spirit = spirit,
            mood = mood,
            battery = battery,
            animTime = animTime,
            squish = squishAmount,
            gazeNorm = gazeTarget,
            canvasSize = size
        )

        // 7. Friandise qui tombe
        if (fallingFoodY >= 0f) {
            drawCircle(
                color = fallingFoodColor,
                radius = jarWidth * 0.045f,
                center = Offset(cx, fallingFoodY * h)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = jarWidth * 0.018f,
                center = Offset(cx - jarWidth * 0.012f, fallingFoodY * h - jarWidth * 0.012f)
            )
        }

        // 8. Lucioles du mini-jeu
        if (miniGameActive) {
            for (orb in miniOrbs) {
                val ox = orb.x * w
                val oy = orb.y * h
                val pulse = 1f + 0.22f * sin(animTime * 6f + orb.id)
                val r = orb.radius * min(w, h) * pulse
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFF8A6), Color(0x99FFD54F), Color.Transparent),
                        center = Offset(ox, oy),
                        radius = r * 1.9f
                    ),
                    radius = r * 1.9f,
                    center = Offset(ox, oy)
                )
                drawCircle(color = Color.White, radius = r * 0.45f, center = Offset(ox, oy))
            }
        }

        // 9. Particules (cœurs, gouttelettes, étincelles, notes)
        for (p in particles) {
            val px = p.x * w
            val py = p.y * h
            if (p.isHeart) {
                drawMiniHeart(Offset(px, py), p.radius, p.color.copy(alpha = p.alpha))
            } else {
                drawCircle(
                    color = p.color.copy(alpha = p.alpha),
                    radius = p.radius,
                    center = Offset(px, py)
                )
            }
        }

        // 10. Condensation d'humidité & contour verre + reflets + bouchon
        drawJarGlassOverlay(jarRect, spirit, animTime)

        // 11. Onde de tapotement sur la vitre
        tapRipplePos?.let { pos ->
            drawCircle(
                color = Color.White.copy(alpha = (1f - tapRippleRadius / 0.28f).coerceIn(0f, 0.75f)),
                radius = tapRippleRadius * jarWidth,
                center = Offset(pos.x * w, pos.y * h),
                style = Stroke(width = 3.5f)
            )
        }
    }
}

private fun DrawScope.drawSkyBackdrop(dayPhase: DayPhase, t: Float, lightLevel: Float) {
    val colors = when (dayPhase) {
        DayPhase.DAWN -> listOf(Color(0xFF1F2242), Color(0xFF5D3A58), Color(0xFF1A242D))
        DayPhase.MORNING -> listOf(Color(0xFF132D46), Color(0xFF21526E), Color(0xFF10242A))
        DayPhase.AFTERNOON -> listOf(Color(0xFF102A3A), Color(0xFF1D4F5A), Color(0xFF0E2026))
        DayPhase.DUSK -> listOf(Color(0xFF231A3D), Color(0xFF59314A), Color(0xFF141B26))
        DayPhase.NIGHT -> listOf(Color(0xFF080E1A), Color(0xFF101F33), Color(0xFF071117))
    }
    drawRect(brush = Brush.verticalGradient(colors))

    // Étoiles subtiles la nuit / aube / crépuscule
    val starVis = when (dayPhase) {
        DayPhase.NIGHT -> 0.85f
        DayPhase.DUSK, DayPhase.DAWN -> 0.45f
        else -> 0.12f
    }
    for (i in 0 until 26) {
        val sx = ((i * 73 % 97) / 97f) * size.width
        val sy = ((i * 41 % 53) / 100f) * size.height
        val twinkle = 0.4f + 0.6f * abs(sin(t * (1.2f + (i % 4) * 0.4f) + i))
        drawCircle(
            color = Color(0xFFE8F6FF).copy(alpha = starVis * twinkle),
            radius = if (i % 5 == 0) 2.8f else 1.7f,
            center = Offset(sx, sy)
        )
    }

    // Lune ou soleil en haut à droite
    val orbCenter = Offset(size.width * 0.84f, size.height * 0.11f)
    if (dayPhase == DayPhase.NIGHT) {
        drawCircle(color = Color(0xFFEBF2FF).copy(alpha = 0.85f), radius = 18f, center = orbCenter)
        drawCircle(color = Color(0xFF091220), radius = 15f, center = Offset(orbCenter.x + 7f, orbCenter.y - 4f))
    } else {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFE7A3).copy(alpha = 0.45f + lightLevel / 300f), Color.Transparent),
                center = orbCenter,
                radius = 48f
            ),
            radius = 48f,
            center = orbCenter
        )
    }
}

private fun DrawScope.drawJarBackAndFloor(
    jar: Rect,
    spirit: SpiritProfile,
    tilt: TiltSnapshot,
    t: Float
) {
    val corner = when (spirit.jarStyle) {
        JarStyle.ROUND_TERRARIUM -> CornerRadius(jar.width * 0.42f, jar.width * 0.42f)
        JarStyle.APOTHECARY -> CornerRadius(jar.width * 0.22f, jar.width * 0.28f)
        JarStyle.LANTERN -> CornerRadius(jar.width * 0.14f, jar.width * 0.14f)
        JarStyle.CRYSTAL_GEODE -> CornerRadius(jar.width * 0.30f, jar.width * 0.36f)
    }

    // Fond du bocal semi-translucide
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x2899FFE6),
                Color(0x18254A54),
                Color(spirit.element.groundColorHex).copy(alpha = 0.42f)
            ),
            startY = jar.top,
            endY = jar.bottom
        ),
        topLeft = Offset(jar.left, jar.top),
        size = Size(jar.width, jar.height),
        cornerRadius = corner
    )

    // Sol élémentaire (terre mousseuse / bassin d'eau / roche volcanique / coussin nuageux)
    val groundY = jar.bottom - jar.height * 0.22f
    val waveShift = tilt.tiltX * 14f
    val groundPath = Path().apply {
        moveTo(jar.left + jar.width * 0.06f, groundY + waveShift * 0.4f)
        quadraticTo(
            jar.center.x,
            groundY - 14f + sin(t * 2f) * if (spirit.element == SpiritElement.WATER) 6f else 1.5f,
            jar.right - jar.width * 0.06f,
            groundY - waveShift * 0.4f
        )
        lineTo(jar.right - jar.width * 0.06f, jar.bottom - jar.height * 0.06f)
        quadraticTo(jar.center.x, jar.bottom + 8f, jar.left + jar.width * 0.06f, jar.bottom - jar.height * 0.06f)
        close()
    }
    drawPath(
        path = groundPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(spirit.element.groundColorHex),
                Color(0xFF0E1D1B)
            ),
            startY = groundY,
            endY = jar.bottom
        )
    )
}

private fun DrawScope.drawActiveDecorations(
    jar: Rect,
    activeIds: List<String>,
    t: Float,
    auraColor: Color
) {
    val floorY = jar.bottom - jar.height * 0.21f
    val leftX = jar.left + jar.width * 0.22f
    val rightX = jar.right - jar.width * 0.22f

    if (DecorItem.MOSS_STONES.id in activeIds) {
        drawOval(Color(0xFF49625C), Offset(leftX - 24f, floorY - 6f), Size(44f, 22f))
        drawOval(Color(0xFF607C74), Offset(leftX + 10f, floorY + 2f), Size(30f, 16f))
    }
    if (DecorItem.GLOW_MUSHROOMS.id in activeIds) {
        val mx = leftX + 26f
        val glow = 0.65f + 0.35f * sin(t * 3f)
        drawLine(Color(0xFFDDEEDB), Offset(mx, floorY + 4f), Offset(mx, floorY - 24f), strokeWidth = 6f)
        drawArc(
            color = Color(0xFF7CF7B4).copy(alpha = glow),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(mx - 20f, floorY - 40f),
            size = Size(40f, 28f)
        )
        drawCircle(Color(0xFFFFF59D), 4f, Offset(mx - 6f, floorY - 30f))
        drawCircle(Color(0xFFFFF59D), 3f, Offset(mx + 8f, floorY - 28f))
    }
    if (DecorItem.FERN_CLUSTER.id in activeIds) {
        val sway = sin(t * 2f) * 6f
        for (k in -1..1) {
            val bx = rightX - 10f + k * 14f
            val path = Path().apply {
                moveTo(bx, floorY + 4f)
                quadraticTo(bx + k * 12f + sway, floorY - 28f, bx + k * 24f + sway, floorY - 48f)
            }
            drawPath(path, Color(0xFF43C47C), style = Stroke(width = 6f, cap = StrokeCap.Round))
        }
    }
    if (DecorItem.QUARTZ_CRYSTAL.id in activeIds) {
        val cx = rightX + 6f
        val crystal = Path().apply {
            moveTo(cx - 12f, floorY + 4f)
            lineTo(cx - 6f, floorY - 38f)
            lineTo(cx + 8f, floorY - 28f)
            lineTo(cx + 12f, floorY + 4f)
            close()
        }
        drawPath(crystal, Color(0xCCBB86FC))
    }
    if (DecorItem.WATER_LILY.id in activeIds) {
        drawOval(Color(0xFF2FA86E), Offset(jar.center.x - 42f, floorY - 6f), Size(42f, 14f))
        drawCircle(Color(0xFFFFEAF2), 7f, Offset(jar.center.x - 22f, floorY - 6f))
    }
    if (DecorItem.SEA_SHELL.id in activeIds) {
        drawArc(
            color = Color(0xFFFFD8C4),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(jar.center.x + 26f, floorY - 10f),
            size = Size(28f, 22f)
        )
    }
    if (DecorItem.FAIRY_LANTERN.id in activeIds) {
        val lx = jar.left + jar.width * 0.27f
        val ly = jar.top + jar.height * 0.25f + sin(t * 2.2f) * 5f
        drawLine(Color(0x99F3D08A), Offset(lx, jar.top + 8f), Offset(lx, ly - 10f), strokeWidth = 2.5f)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xAAFFE484), Color.Transparent), Offset(lx, ly), 34f),
            radius = 34f,
            center = Offset(lx, ly)
        )
        drawRoundRect(Color(0xFFFFE082), Offset(lx - 8f, ly - 10f), Size(16f, 20f), CornerRadius(6f, 6f))
    }
    if (DecorItem.FIREFLIES_NEST.id in activeIds) {
        for (i in 0 until 6) {
            val fx = jar.center.x + cos(t * (0.9f + i * 0.2f) + i * 1.4f) * (jar.width * 0.30f)
            val fy = jar.center.y + sin(t * (1.1f + i * 0.15f) + i * 2f) * (jar.height * 0.22f)
            val a = 0.4f + 0.6f * abs(sin(t * 3f + i))
            drawCircle(Color(0xFFFFF48C).copy(alpha = a), 4f, Offset(fx, fy))
        }
    }
    if (DecorItem.BONSAI_TWIG.id in activeIds) {
        val bx = leftX - 4f
        drawLine(Color(0xFF7B4E34), Offset(bx, floorY + 2f), Offset(bx + 18f, floorY - 58f), strokeWidth = 8f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF47BE7B), 22f, Offset(bx + 22f, floorY - 64f))
        drawCircle(Color(0xFF71E2A0), 15f, Offset(bx + 6f, floorY - 54f))
    }
    if (DecorItem.MOON_PEBBLE.id in activeIds) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFE0F2FE), Color(0xFF7DD3FC)), Offset(jar.center.x, floorY), 16f),
            radius = 12f,
            center = Offset(jar.center.x + 6f, floorY + 4f)
        )
    }
    if (DecorItem.MINI_TORII.id in activeIds) {
        val tx = rightX - 18f
        drawLine(Color(0xFFD94A3D), Offset(tx - 16f, floorY + 4f), Offset(tx - 16f, floorY - 40f), strokeWidth = 5f)
        drawLine(Color(0xFFD94A3D), Offset(tx + 16f, floorY + 4f), Offset(tx + 16f, floorY - 40f), strokeWidth = 5f)
        drawLine(Color(0xFFE85648), Offset(tx - 24f, floorY - 40f), Offset(tx + 24f, floorY - 40f), strokeWidth = 7f, cap = StrokeCap.Round)
        drawLine(Color(0xFFD94A3D), Offset(tx - 18f, floorY - 30f), Offset(tx + 18f, floorY - 30f), strokeWidth = 4f)
    }
    if (DecorItem.STAR_CHIME.id in activeIds) {
        val sx = jar.right - jar.width * 0.26f
        val sy = jar.top + jar.height * 0.22f + cos(t * 2.5f) * 4f
        drawLine(Color(0xAAFFFFFF), Offset(sx, jar.top + 8f), Offset(sx, sy), strokeWidth = 2f)
        drawCircle(auraColor.copy(alpha = 0.85f), 8f, Offset(sx, sy))
        drawCircle(Color.White, 3.5f, Offset(sx, sy))
    }
}

private fun DrawScope.drawSpiritCreature(
    center: Offset,
    baseRadius: Float,
    spirit: SpiritProfile,
    mood: SpiritMood,
    battery: BatterySnapshot,
    animTime: Float,
    squish: Float,
    gazeNorm: Offset?,
    canvasSize: Size
) {
    val breathe = 1f + 0.035f * sin(animTime * if (spirit.isSleeping) 1.5f else 3.1f)
    val rx = baseRadius * (1f + squish * 0.45f) * breathe
    val ry = baseRadius * (1f - squish * 0.35f) / breathe
    val mainColor = Color(spirit.customAuraHex)
    val deepColor = Color(spirit.element.secondaryColorHex)

    // Aura de charge batterie
    if (battery.isCharging) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x88FFE66D), Color.Transparent),
                center = center,
                radius = baseRadius * 2.1f
            ),
            radius = baseRadius * 2.1f,
            center = center
        )
    }

    // Couronne lumineuse au stade 4 (Gardien)
    if (spirit.stage == EvolutionStage.GUARDIAN) {
        drawOval(
            color = Color(0xDDFFF39A),
            topLeft = Offset(center.x - rx * 0.58f, center.y - ry * 1.48f),
            size = Size(rx * 1.16f, ry * 0.28f),
            style = Stroke(width = 4.5f)
        )
    }

    // Corps spécifique à chaque élément
    when (spirit.element) {
        SpiritElement.MOSS -> {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(mainColor, deepColor),
                    center = Offset(center.x - rx * 0.2f, center.y - ry * 0.25f),
                    radius = rx * 1.35f
                ),
                topLeft = Offset(center.x - rx, center.y - ry * 0.92f),
                size = Size(rx * 2f, ry * 1.84f)
            )
            // Bourgeons / feuilles selon le stade d'évolution
            val leafCount = spirit.stage.level
            for (i in 0 until leafCount) {
                val angle = -35f + (i * (70f / max(1, leafCount - 1))) + sin(animTime * 3f + i) * 5f
                rotate(degrees = angle, pivot = Offset(center.x, center.y - ry * 0.88f)) {
                    drawOval(
                        color = Color(0xFF4BE88B),
                        topLeft = Offset(center.x - rx * 0.14f, center.y - ry * 1.45f),
                        size = Size(rx * 0.28f, ry * 0.62f)
                    )
                }
            }
        }
        SpiritElement.WATER -> {
            val dropPath = Path().apply {
                moveTo(center.x, center.y - ry * 1.28f)
                cubicTo(
                    center.x + rx * 1.25f, center.y - ry * 0.18f,
                    center.x + rx * 1.12f, center.y + ry * 0.98f,
                    center.x, center.y + ry * 0.96f
                )
                cubicTo(
                    center.x - rx * 1.12f, center.y + ry * 0.98f,
                    center.x - rx * 1.25f, center.y - ry * 0.18f,
                    center.x, center.y - ry * 1.28f
                )
                close()
            }
            drawPath(
                path = dropPath,
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFD6F8FF), mainColor, deepColor),
                    center = Offset(center.x - rx * 0.25f, center.y - ry * 0.2f),
                    radius = rx * 1.5f
                )
            )
            // Reflet aquatique
            drawOval(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(center.x - rx * 0.48f, center.y - ry * 0.42f),
                size = Size(rx * 0.24f, ry * 0.44f)
            )
        }
        SpiritElement.EMBER -> {
            val flicker = sin(animTime * 9f) * rx * 0.10f
            val flamePath = Path().apply {
                moveTo(center.x + flicker, center.y - ry * 1.42f)
                quadraticTo(center.x + rx * 1.25f, center.y - ry * 0.1f, center.x + rx * 0.85f, center.y + ry * 0.72f)
                quadraticTo(center.x, center.y + ry * 1.18f, center.x - rx * 0.85f, center.y + ry * 0.72f)
                quadraticTo(center.x - rx * 1.25f, center.y - ry * 0.1f, center.x + flicker, center.y - ry * 1.42f)
                close()
            }
            drawPath(
                path = flamePath,
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF5B8), mainColor, deepColor),
                    center = Offset(center.x, center.y + ry * 0.1f),
                    radius = rx * 1.4f
                )
            )
        }
        SpiritElement.CLOUD -> {
            drawCircle(mainColor, rx * 0.68f, Offset(center.x - rx * 0.52f, center.y + ry * 0.12f))
            drawCircle(mainColor, rx * 0.68f, Offset(center.x + rx * 0.52f, center.y + ry * 0.12f))
            drawCircle(mainColor, rx * 0.86f, Offset(center.x, center.y - ry * 0.12f))
            drawCircle(deepColor.copy(alpha = 0.25f), rx * 0.58f, Offset(center.x, center.y + ry * 0.22f))
        }
    }

    // Visage expressif + regard suivant le doigt
    val eyeSpacing = rx * 0.36f
    val eyeY = center.y + ry * 0.02f
    val isBlinking = (animTime % 4.3f) > 4.14f
    val sleeping = spirit.isSleeping || mood == SpiritMood.SLEEPY || mood == SpiritMood.LOW_BATTERY

    val lookDx = gazeNorm?.let { ((it.x * canvasSize.width - center.x) / (canvasSize.width * 0.45f)).coerceIn(-1f, 1f) * 5.5f } ?: 0f
    val lookDy = gazeNorm?.let { ((it.y * canvasSize.height - center.y) / (canvasSize.height * 0.45f)).coerceIn(-1f, 1f) * 4f } ?: 0f

    val eyeColor = Color(0xFF142124)
    if (sleeping || isBlinking) {
        drawArc(
            color = eyeColor,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(center.x - eyeSpacing - 11f, eyeY - 6f),
            size = Size(22f, 14f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
        drawArc(
            color = eyeColor,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(center.x + eyeSpacing - 11f, eyeY - 6f),
            size = Size(22f, 14f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    } else if (squish > 0.06f || mood == SpiritMood.ECSTATIC) {
        // Yeux plissés de bonheur ^_^
        drawArc(
            color = eyeColor,
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(center.x - eyeSpacing - 12f, eyeY - 8f),
            size = Size(24f, 18f),
            style = Stroke(width = 4.5f, cap = StrokeCap.Round)
        )
        drawArc(
            color = eyeColor,
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(center.x + eyeSpacing - 12f, eyeY - 8f),
            size = Size(24f, 18f),
            style = Stroke(width = 4.5f, cap = StrokeCap.Round)
        )
    } else {
        val eyeR = (rx * 0.135f).coerceIn(7f, 13f)
        drawCircle(eyeColor, eyeR, Offset(center.x - eyeSpacing + lookDx, eyeY + lookDy))
        drawCircle(eyeColor, eyeR, Offset(center.x + eyeSpacing + lookDx, eyeY + lookDy))
        drawCircle(Color.White, eyeR * 0.36f, Offset(center.x - eyeSpacing - 2.5f + lookDx, eyeY - 2.5f + lookDy))
        drawCircle(Color.White, eyeR * 0.36f, Offset(center.x + eyeSpacing - 2.5f + lookDx, eyeY - 2.5f + lookDy))
    }

    // Joues roses
    drawOval(
        color = Color(0x66FF7CA8),
        topLeft = Offset(center.x - eyeSpacing - 16f, eyeY + 8f),
        size = Size(18f, 10f)
    )
    drawOval(
        color = Color(0x66FF7CA8),
        topLeft = Offset(center.x + eyeSpacing - 2f, eyeY + 8f),
        size = Size(18f, 10f)
    )

    // Sourire / bouche selon l'humeur
    val mouthY = eyeY + ry * 0.20f
    if (mood == SpiritMood.HUNGRY || mood == SpiritMood.THIRSTY) {
        drawCircle(eyeColor, 4.5f, Offset(center.x, mouthY + 3f))
    } else if (!sleeping) {
        drawArc(
            color = eyeColor,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(center.x - 10f, mouthY - 5f),
            size = Size(20f, 14f),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawJarGlassOverlay(jar: Rect, spirit: SpiritProfile, t: Float) {
    val corner = when (spirit.jarStyle) {
        JarStyle.ROUND_TERRARIUM -> CornerRadius(jar.width * 0.42f, jar.width * 0.42f)
        JarStyle.APOTHECARY -> CornerRadius(jar.width * 0.22f, jar.width * 0.28f)
        JarStyle.LANTERN -> CornerRadius(jar.width * 0.14f, jar.width * 0.14f)
        JarStyle.CRYSTAL_GEODE -> CornerRadius(jar.width * 0.30f, jar.width * 0.36f)
    }

    // Gouttelettes de condensation si l'humidité est haute
    if (spirit.hydration > 60f) {
        val count = ((spirit.hydration - 55f) / 6f).toInt().coerceIn(2, 10)
        for (i in 0 until count) {
            val dx = jar.left + jar.width * (0.14f + (i * 19 % 72) / 100f)
            val dy = jar.top + jar.height * (0.18f + ((i * 29 + (t * 3).toInt()) % 55) / 100f)
            drawCircle(Color(0x55CCF7FF), 3.5f + (i % 3), Offset(dx, dy))
        }
    }

    // Contour du bocal en verre
    val rimColor = when (spirit.jarStyle) {
        JarStyle.LANTERN -> Color(0xCCF5C97B)
        JarStyle.CRYSTAL_GEODE -> Color(0xCCD5B3FF)
        else -> Color(0x99D9FFF2)
    }
    drawRoundRect(
        color = rimColor,
        topLeft = Offset(jar.left, jar.top),
        size = Size(jar.width, jar.height),
        cornerRadius = corner,
        style = Stroke(width = 5f)
    )

    // Reflet spéculaire sur la vitre gauche
    val shinePath = Path().apply {
        moveTo(jar.left + jar.width * 0.13f, jar.top + jar.height * 0.20f)
        quadraticTo(
            jar.left + jar.width * 0.08f,
            jar.center.y,
            jar.left + jar.width * 0.15f,
            jar.bottom - jar.height * 0.26f
        )
    }
    drawPath(
        path = shinePath,
        color = Color.White.copy(alpha = 0.26f),
        style = Stroke(width = 10f, cap = StrokeCap.Round)
    )

    // Bouchon en liège ou chapeau de lanterne
    val lidW = jar.width * 0.44f
    val lidH = jar.height * 0.075f
    val lidColor = when (spirit.jarStyle) {
        JarStyle.LANTERN -> Color(0xFFC89B4B)
        JarStyle.CRYSTAL_GEODE -> Color(0xFF8C6CC7)
        else -> Color(0xFFA97142)
    }
    drawRoundRect(
        color = lidColor,
        topLeft = Offset(jar.center.x - lidW / 2f, jar.top - lidH * 0.65f),
        size = Size(lidW, lidH),
        cornerRadius = CornerRadius(14f, 14f)
    )
    drawRoundRect(
        color = Color(0x66FFFFFF),
        topLeft = Offset(jar.center.x - lidW * 0.56f, jar.top - 5f),
        size = Size(lidW * 1.12f, 12f),
        cornerRadius = CornerRadius(6f, 6f)
    )
}

private fun DrawScope.drawMiniHeart(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.7f)
        cubicTo(
            center.x - radius * 1.2f, center.y - radius * 0.1f,
            center.x - radius * 0.6f, center.y - radius * 1.1f,
            center.x, center.y - radius * 0.35f
        )
        cubicTo(
            center.x + radius * 0.6f, center.y - radius * 1.1f,
            center.x + radius * 1.2f, center.y - radius * 0.1f,
            center.x, center.y + radius * 0.7f
        )
        close()
    }
    drawPath(path, color)
}
