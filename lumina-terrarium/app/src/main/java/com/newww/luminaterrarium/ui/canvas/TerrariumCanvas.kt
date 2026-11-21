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
    val radius: Float,
    val isGolden: Boolean = false
)

private data class FallingStar(
    val id: Int,
    var x: Float,
    var y: Float,
    val vy: Float,
    val isStormCloud: Boolean
)

@Composable
fun TerrariumCanvas(
    spirit: SpiritProfile,
    mood: SpiritMood,
    dayPhase: DayPhase,
    battery: BatterySnapshot,
    tilt: TiltSnapshot,
    pulseEvent: VisualPulseEvent?,
    miniGameMode: MiniGameMode,
    decorEditMode: Boolean,
    melodyHighlightPad: Int,
    onPet: (Float, Float) -> Unit,
    onWipeFog: (Float, Float) -> Unit,
    onTapGlass: (Float, Float) -> Unit,
    onMoveDecor: (String, Float, Float) -> Unit,
    onMiniGamePoint: (Int, Float, Float) -> Unit,
    onMelodyPadTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var animTime by remember { mutableFloatStateOf(0f) }
    var gazeTarget by remember { mutableStateOf<Offset?>(null) }

    // Position autonome vivante de l'esprit dans le bocal (0.30..0.70, 0.45..0.67)
    var spiritX by remember { mutableFloatStateOf(0.50f) }
    var spiritY by remember { mutableFloatStateOf(0.58f) }
    var targetX by remember { mutableFloatStateOf(0.50f) }
    var targetY by remember { mutableFloatStateOf(0.58f) }
    var nextRoamTime by remember { mutableFloatStateOf(2.5f) }

    var squishAmount by remember { mutableFloatStateOf(0f) }
    var hopOffset by remember { mutableFloatStateOf(0f) }
    var tapRipplePos by remember { mutableStateOf<Offset?>(null) }
    var tapRippleRadius by remember { mutableFloatStateOf(0f) }
    var fallingFoodX by remember { mutableFloatStateOf(0.5f) }
    var fallingFoodY by remember { mutableFloatStateOf(-1f) }
    var fallingFoodColor by remember { mutableStateOf(Color.Yellow) }
    var lastPetTimeMs by remember { mutableLongStateOf(0L) }
    var lastWipeTimeMs by remember { mutableLongStateOf(0L) }
    var draggingDecorId by remember { mutableStateOf<String?>(null) }

    val particles = remember { mutableStateListOf<Particle>() }
    val miniOrbs = remember { mutableStateListOf<MiniOrb>() }
    val fallingStars = remember { mutableStateListOf<FallingStar>() }
    var nextEntityId by remember { mutableIntStateOf(1) }

    LaunchedEffect(miniGameMode) {
        miniOrbs.clear()
        fallingStars.clear()
        if (miniGameMode == MiniGameMode.FIREFLIES) {
            repeat(6) { idx ->
                miniOrbs += MiniOrb(
                    id = nextEntityId++,
                    x = 0.26f + Random.nextFloat() * 0.48f,
                    y = 0.28f + Random.nextFloat() * 0.42f,
                    vx = (Random.nextFloat() - 0.5f) * 0.30f,
                    vy = (Random.nextFloat() - 0.5f) * 0.26f,
                    radius = 0.048f,
                    isGolden = idx == 0
                )
            }
        }
    }

    LaunchedEffect(pulseEvent?.id) {
        val ev = pulseEvent ?: return@LaunchedEffect
        when (ev.kind) {
            PulseKind.PET -> {
                squishAmount = 0.25f
                repeat(8) {
                    particles += Particle(
                        x = spiritX + (Random.nextFloat() - 0.5f) * 0.14f,
                        y = spiritY - 0.04f + (Random.nextFloat() - 0.5f) * 0.08f,
                        vx = (Random.nextFloat() - 0.5f) * 0.14f,
                        vy = -0.20f - Random.nextFloat() * 0.16f,
                        radius = 10f + Random.nextFloat() * 8f,
                        alpha = 1f,
                        color = Color(0xFFFF7EB6),
                        life = 0f,
                        maxLife = 1.25f,
                        isHeart = true
                    )
                }
            }
            PulseKind.WIPE -> {
                repeat(6) {
                    particles += Particle(
                        x = ev.xNorm + (Random.nextFloat() - 0.5f) * 0.08f,
                        y = ev.yNorm + (Random.nextFloat() - 0.5f) * 0.08f,
                        vx = (Random.nextFloat() - 0.5f) * 0.10f,
                        vy = -0.10f - Random.nextFloat() * 0.08f,
                        radius = 4f + Random.nextFloat() * 4f,
                        alpha = 0.95f,
                        color = Color(0xFFD6FCFF),
                        life = 0f,
                        maxLife = 0.65f
                    )
                }
            }
            PulseKind.TAP -> {
                tapRipplePos = Offset(ev.xNorm, ev.yNorm)
                tapRippleRadius = 0.01f
                hopOffset = -0.048f
                targetX = ev.xNorm.coerceIn(0.30f, 0.70f)
                targetY = ev.yNorm.coerceIn(0.42f, 0.66f)
            }
            PulseKind.FEED -> {
                fallingFoodX = ev.xNorm.coerceIn(0.28f, 0.72f)
                fallingFoodY = 0.20f
                fallingFoodColor = Color(ev.food?.colorHex ?: 0xFFFFD166)
                targetX = fallingFoodX
                targetY = 0.61f
                squishAmount = 0.18f
            }
            PulseKind.MIST -> {
                repeat(32) {
                    particles += Particle(
                        x = 0.22f + Random.nextFloat() * 0.56f,
                        y = 0.20f + Random.nextFloat() * 0.22f,
                        vx = (Random.nextFloat() - 0.5f) * 0.06f,
                        vy = 0.22f + Random.nextFloat() * 0.22f,
                        radius = 4f + Random.nextFloat() * 5f,
                        alpha = 0.85f,
                        color = Color(0xFF9DF0FF),
                        life = 0f,
                        maxLife = 1.4f
                    )
                }
            }
            PulseKind.EVOLVE -> {
                hopOffset = -0.08f
                repeat(40) { i ->
                    val ang = (i / 40f) * 2f * PI.toFloat()
                    particles += Particle(
                        x = spiritX,
                        y = spiritY,
                        vx = cos(ang) * (0.24f + Random.nextFloat() * 0.15f),
                        vy = sin(ang) * (0.24f + Random.nextFloat() * 0.15f),
                        radius = 6f + Random.nextFloat() * 8f,
                        alpha = 1f,
                        color = if (i % 2 == 0) Color(0xFFFFF08A) else Color(spirit.customAuraHex),
                        life = 0f,
                        maxLife = 1.6f
                    )
                }
            }
            PulseKind.LULLABY -> {
                repeat(14) {
                    particles += Particle(
                        x = spiritX + (Random.nextFloat() - 0.5f) * 0.12f,
                        y = spiritY - 0.05f,
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
                        vx = cos(ang) * 0.24f,
                        vy = sin(ang) * 0.24f,
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

    // Boucle 60 FPS : déplacement autonome, physique secousse, mini-jeux, particules
    LaunchedEffect(miniGameMode, spirit.isSleeping) {
        var lastNs = System.nanoTime()
        var starSpawnTimer = 0f
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
            lastNs = now
            animTime += dt

            // Déplacement doux de l'esprit vers sa cible autonome
            if (!spirit.isSleeping && miniGameMode != MiniGameMode.STAR_RAIN) {
                if (animTime > nextRoamTime && fallingFoodY < 0f) {
                    nextRoamTime = animTime + 3.2f + Random.nextFloat() * 3.5f
                    targetX = 0.32f + Random.nextFloat() * 0.36f
                    targetY = if (spirit.element == SpiritElement.CLOUD) {
                        0.40f + Random.nextFloat() * 0.16f
                    } else {
                        0.53f + Random.nextFloat() * 0.11f
                    }
                }
                val lerp = (dt * 2.4f).coerceAtMost(1f)
                spiritX += (targetX - spiritX) * lerp
                spiritY += (targetY - spiritY) * lerp
            } else if (miniGameMode == MiniGameMode.STAR_RAIN) {
                spiritX += (targetX - spiritX) * (dt * 9f).coerceAtMost(1f)
                spiritY += (0.64f - spiritY) * (dt * 6f).coerceAtMost(1f)
            }

            // Secousse du téléphone -> pluie d'étincelles magiques et petit bond
            if (tilt.shakeIntensity > 0.38f && particles.size < 45) {
                hopOffset = min(hopOffset, -0.04f)
                particles += Particle(
                    x = 0.25f + Random.nextFloat() * 0.50f,
                    y = 0.25f + Random.nextFloat() * 0.45f,
                    vx = (Random.nextFloat() - 0.5f) * 0.25f,
                    vy = -0.15f + Random.nextFloat() * 0.3f,
                    radius = 5f,
                    alpha = 0.95f,
                    color = Color(0xFFFFF49E),
                    life = 0f,
                    maxLife = 0.85f
                )
            }

            squishAmount *= (1f - dt * 7f).coerceAtLeast(0f)
            hopOffset *= (1f - dt * 8f).coerceAtLeast(0f)

            if (tapRipplePos != null) {
                tapRippleRadius += dt * 0.55f
                if (tapRippleRadius > 0.28f) tapRipplePos = null
            }

            if (fallingFoodY >= 0f) {
                fallingFoodY += dt * 0.55f
                if (fallingFoodY >= spiritY) {
                    fallingFoodY = -1f
                    squishAmount = 0.26f
                    repeat(12) {
                        val a = Random.nextFloat() * 2f * PI.toFloat()
                        particles += Particle(
                            x = spiritX,
                            y = spiritY,
                            vx = cos(a) * 0.16f,
                            vy = sin(a) * 0.16f,
                            radius = 5.5f,
                            alpha = 1f,
                            color = fallingFoodColor,
                            life = 0f,
                            maxLife = 0.65f
                        )
                    }
                }
            }

            val pIter = particles.listIterator()
            while (pIter.hasNext()) {
                val p = pIter.next()
                p.life += dt
                if (p.life >= p.maxLife) pIter.remove()
                else {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.alpha = (1f - p.life / p.maxLife).coerceIn(0f, 1f)
                }
            }

            // Mini-jeu 1 : Chasse aux Lucioles
            if (miniGameMode == MiniGameMode.FIREFLIES) {
                for (orb in miniOrbs) {
                    orb.x += orb.vx * dt
                    orb.y += orb.vy * dt
                    if (orb.x < 0.22f || orb.x > 0.78f) orb.vx = -orb.vx
                    if (orb.y < 0.26f || orb.y > 0.72f) orb.vy = -orb.vy
                    orb.x = orb.x.coerceIn(0.21f, 0.79f)
                    orb.y = orb.y.coerceIn(0.25f, 0.73f)
                }
                if (miniOrbs.size < 6) {
                    miniOrbs += MiniOrb(
                        id = nextEntityId++,
                        x = 0.24f + Random.nextFloat() * 0.52f,
                        y = 0.26f + Random.nextFloat() * 0.42f,
                        vx = (Random.nextFloat() - 0.5f) * 0.36f,
                        vy = (Random.nextFloat() - 0.5f) * 0.30f,
                        radius = 0.048f,
                        isGolden = Random.nextFloat() < 0.22f
                    )
                }
            }

            // Mini-jeu 2 : Pluie d'Étoiles
            if (miniGameMode == MiniGameMode.STAR_RAIN) {
                starSpawnTimer += dt
                if (starSpawnTimer > 0.52f) {
                    starSpawnTimer = 0f
                    fallingStars += FallingStar(
                        id = nextEntityId++,
                        x = 0.26f + Random.nextFloat() * 0.48f,
                        y = 0.20f,
                        vy = 0.28f + Random.nextFloat() * 0.14f,
                        isStormCloud = Random.nextFloat() < 0.24f
                    )
                }
                val sIter = fallingStars.listIterator()
                while (sIter.hasNext()) {
                    val st = sIter.next()
                    st.y += st.vy * dt
                    if (hypot(st.x - spiritX, st.y - spiritY) < 0.11f) {
                        sIter.remove()
                        squishAmount = 0.22f
                        onMiniGamePoint(if (st.isStormCloud) -1 else 2, st.x, st.y)
                    } else if (st.y > 0.80f) {
                        sIter.remove()
                    }
                }
            }
        }
    }

    val melodyPadPositions = remember {
        listOf(
            Offset(0.30f, 0.35f),
            Offset(0.70f, 0.35f),
            Offset(0.30f, 0.70f),
            Offset(0.70f, 0.70f)
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(miniGameMode, decorEditMode, spirit.activeDecorIds) {
                detectTapGestures { offset ->
                    val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeTarget = Offset(nx, ny)

                    if (miniGameMode == MiniGameMode.FIREFLIES) {
                        val hit = miniOrbs.firstOrNull { orb -> hypot(orb.x - nx, orb.y - ny) < 0.11f }
                        if (hit != null) {
                            miniOrbs.remove(hit)
                            onMiniGamePoint(if (hit.isGolden) 3 else 1, hit.x, hit.y)
                            return@detectTapGestures
                        }
                    }

                    if (miniGameMode == MiniGameMode.MELODY) {
                        val idx = melodyPadPositions.indexOfFirst { p -> hypot(p.x - nx, p.y - ny) < 0.13f }
                        if (idx >= 0) {
                            onMelodyPadTap(idx)
                            return@detectTapGestures
                        }
                    }

                    val distToSpirit = hypot(nx - spiritX, ny - spiritY)
                    if (distToSpirit < 0.18f) {
                        onPet(nx, ny)
                    } else {
                        onTapGlass(nx, ny)
                    }
                }
            }
            .pointerInput(miniGameMode, decorEditMode, spirit.activeDecorIds, spirit.decorPositions, spirit.glassFog) {
                detectDragGestures(
                    onDragStart = { start ->
                        val nx = (start.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val ny = (start.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (decorEditMode) {
                            draggingDecorId = spirit.activeDecorIds.minByOrNull { id ->
                                val item = DecorItem.entries.find { it.id == id }
                                val p = spirit.decorPositions[id] ?: NormPos(item?.defaultX ?: 0.5f, item?.defaultY ?: 0.75f)
                                hypot(p.x - nx, p.y - ny)
                            }
                        }
                    },
                    onDragEnd = {
                        gazeTarget = null
                        draggingDecorId = null
                    }
                ) { change, _ ->
                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeTarget = Offset(nx, ny)

                    if (decorEditMode && draggingDecorId != null) {
                        onMoveDecor(draggingDecorId!!, nx, ny)
                        return@detectDragGestures
                    }

                    if (miniGameMode == MiniGameMode.STAR_RAIN) {
                        targetX = nx.coerceIn(0.24f, 0.76f)
                        return@detectDragGestures
                    }

                    val now = System.currentTimeMillis()
                    val dist = hypot(nx - spiritX, ny - spiritY)
                    if (dist < 0.21f && now - lastPetTimeMs > 380L) {
                        lastPetTimeMs = now
                        onPet(nx, ny)
                    } else if (spirit.glassFog > 0.05f && now - lastWipeTimeMs > 280L) {
                        lastWipeTimeMs = now
                        onWipeFog(nx, ny)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val jarTop = h * 0.13f
        val jarBottom = h * 0.88f
        val jarWidth = min(w * 0.84f, h * 0.68f)
        val jarLeft = cx - jarWidth / 2f
        val jarRight = cx + jarWidth / 2f
        val jarRect = Rect(jarLeft, jarTop, jarRight, jarBottom)

        // 1. Fond céleste riche & profondeur atmosphérique
        drawSkyBackdrop(dayPhase, animTime, spirit.lightLevel)

        // 2. Halo externe coloré par la lampe & l'aura
        val auraColor = Color(spirit.customAuraHex)
        val lampColor = Color(spirit.lampTint.colorHex)
        val auraPulse = 0.85f + 0.15f * sin(animTime * 2.2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    auraColor.copy(alpha = (0.30f * auraPulse * (0.45f + spirit.lightLevel / 160f)).coerceIn(0.08f, 0.48f)),
                    lampColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.54f),
                radius = jarWidth * 0.82f
            ),
            radius = jarWidth * 0.82f,
            center = Offset(cx, h * 0.54f)
        )

        // 3. Faisceau volumétrique teinté selon LampTint
        if (spirit.lightLevel > 12f) {
            val beamAlpha = (spirit.lightLevel / 100f) * 0.26f
            val beamPath = Path().apply {
                moveTo(cx - jarWidth * 0.18f, jarTop - h * 0.04f)
                lineTo(cx + jarWidth * 0.18f, jarTop - h * 0.04f)
                lineTo(jarRight + w * 0.05f, jarBottom)
                lineTo(jarLeft - w * 0.05f, jarBottom)
                close()
            }
            drawPath(
                path = beamPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lampColor.copy(alpha = beamAlpha), Color.Transparent),
                    startY = jarTop,
                    endY = jarBottom
                )
            )
        }

        // 4. Bocal en verre multi-couches + strates botaniques réalistes (gravier, terre, mousse ondulante)
        drawJarBackAndBotanicalStrata(jarRect, spirit, tilt, animTime)

        // 5. Décorations déplaçables (avec halo d'édition si decorEditMode)
        drawActiveDecorations(jarRect, w, h, spirit, animTime, auraColor, decorEditMode)

        // 6. Esprit élémentaire autonome + ombre portée au sol
        val bobY = sin(animTime * if (spirit.isSleeping) 1.3f else 2.8f) * (h * 0.015f)
        val spiritCenter = Offset(
            x = spiritX * w + tilt.tiltX * (jarWidth * 0.07f),
            y = spiritY * h + bobY + hopOffset * h + tilt.tiltY * (h * 0.015f)
        )
        drawOval(
            Color(0x55000000),
            Offset(spiritCenter.x - jarWidth * 0.12f, jarBottom - jarRect.height * 0.19f),
            Size(jarWidth * 0.24f, 12f)
        )
        drawSpiritCreature(
            center = spiritCenter,
            baseRadius = jarWidth * (0.140f + spirit.stage.level * 0.014f),
            spirit = spirit,
            mood = mood,
            battery = battery,
            animTime = animTime,
            squish = squishAmount,
            gazeNorm = gazeTarget,
            canvasSize = size
        )

        // 7. Friandise lancée avec trajectoire
        if (fallingFoodY >= 0f) {
            drawCircle(fallingFoodColor, jarWidth * 0.046f, Offset(fallingFoodX * w, fallingFoodY * h))
            drawCircle(Color.White.copy(alpha = 0.75f), jarWidth * 0.018f, Offset(fallingFoodX * w - 5f, fallingFoodY * h - 5f))
        }

        // 8. Mini-jeu 1 : Lucioles (normales + dorées ×3)
        if (miniGameMode == MiniGameMode.FIREFLIES) {
            for (orb in miniOrbs) {
                val ox = orb.x * w
                val oy = orb.y * h
                val pulse = 1f + 0.24f * sin(animTime * 6.5f + orb.id)
                val r = orb.radius * min(w, h) * pulse
                val col = if (orb.isGolden) Color(0xFFFFB74D) else Color(0xFFFFF8A6)
                drawCircle(
                    brush = Brush.radialGradient(listOf(col, col.copy(alpha = 0.45f), Color.Transparent), Offset(ox, oy), r * 2.0f),
                    radius = r * 2.0f,
                    center = Offset(ox, oy)
                )
                drawCircle(Color.White, r * 0.45f, Offset(ox, oy))
            }
        }

        // 9. Mini-jeu 2 : Pluie d'Étoiles
        if (miniGameMode == MiniGameMode.STAR_RAIN) {
            for (st in fallingStars) {
                val sx = st.x * w
                val sy = st.y * h
                if (st.isStormCloud) {
                    drawCircle(Color(0xFF475569), 18f, Offset(sx, sy))
                    drawCircle(Color(0xFF64748B), 14f, Offset(sx - 12f, sy + 4f))
                    drawCircle(Color(0xFF64748B), 14f, Offset(sx + 12f, sy + 4f))
                } else {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(Color(0xFFFFF59D), Color(0x88FFD54F), Color.Transparent), Offset(sx, sy), 26f),
                        radius = 26f,
                        center = Offset(sx, sy)
                    )
                    drawCircle(Color.White, 8f, Offset(sx, sy))
                }
            }
        }

        // 10. Mini-jeu 3 : Carillon Mémoire (4 cristaux musicaux)
        if (miniGameMode == MiniGameMode.MELODY) {
            val padColors = listOf(Color(0xFF6EF5A8), Color(0xFF66DCFF), Color(0xFFFFA552), Color(0xFFE59EFF))
            melodyPadPositions.forEachIndexed { idx, p ->
                val px = p.x * w
                val py = p.y * h
                val lit = melodyHighlightPad == idx
                val r = if (lit) 38f else 28f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(padColors[idx].copy(alpha = if (lit) 0.95f else 0.42f), Color.Transparent),
                        Offset(px, py),
                        r * 1.8f
                    ),
                    radius = r * 1.8f,
                    center = Offset(px, py)
                )
                drawCircle(padColors[idx].copy(alpha = if (lit) 1f else 0.7f), r * 0.72f, Offset(px, py))
                drawCircle(Color.White.copy(alpha = if (lit) 0.95f else 0.35f), r * 0.28f, Offset(px - 6f, py - 6f))
            }
        }

        // 11. Particules vivantes
        for (p in particles) {
            val px = p.x * w
            val py = p.y * h
            if (p.isHeart) drawMiniHeart(Offset(px, py), p.radius, p.color.copy(alpha = p.alpha))
            else drawCircle(p.color.copy(alpha = p.alpha), p.radius, Offset(px, py))
        }

        // 12. Buée interactive (à essuyer au doigt !) + reflets du verre + bouchon
        drawJarGlassAndFogOverlay(jarRect, spirit, animTime)

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
        DayPhase.DAWN -> listOf(Color(0xFF1D1E42), Color(0xFF6B3E5E), Color(0xFF16222B))
        DayPhase.MORNING -> listOf(Color(0xFF0E2944), Color(0xFF1E5875), Color(0xFF0D2128))
        DayPhase.AFTERNOON -> listOf(Color(0xFF0D2838), Color(0xFF1C5663), Color(0xFF0C1E25))
        DayPhase.DUSK -> listOf(Color(0xFF241740), Color(0xFF6B3552), Color(0xFF131926))
        DayPhase.NIGHT -> listOf(Color(0xFF060B18), Color(0xFF102038), Color(0xFF071118))
    }
    drawRect(brush = Brush.verticalGradient(colors))

    val starVis = when (dayPhase) {
        DayPhase.NIGHT -> 0.90f
        DayPhase.DUSK, DayPhase.DAWN -> 0.48f
        else -> 0.14f
    }
    for (i in 0 until 32) {
        val sx = ((i * 73 % 97) / 97f) * size.width
        val sy = ((i * 41 % 55) / 100f) * size.height
        val twinkle = 0.35f + 0.65f * abs(sin(t * (1.2f + (i % 4) * 0.4f) + i))
        drawCircle(
            color = Color(0xFFE8F6FF).copy(alpha = starVis * twinkle),
            radius = if (i % 5 == 0) 3.0f else 1.7f,
            center = Offset(sx, sy)
        )
    }

    // Étagère en bois sombre sous le bocal pour ancrer la scène
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF1C2932), Color(0xFF0A1218))),
        topLeft = Offset(0f, size.height * 0.87f),
        size = Size(size.width, size.height * 0.13f)
    )
    drawLine(Color(0x338AF0C8), Offset(0f, size.height * 0.87f), Offset(size.width, size.height * 0.87f), strokeWidth = 2f)
}

private fun DrawScope.drawJarBackAndBotanicalStrata(
    jar: Rect,
    spirit: SpiritProfile,
    tilt: TiltSnapshot,
    t: Float
) {
    val corner = jarCornerRadius(jar, spirit.jarStyle)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x2E99FFE6),
                Color(0x1E1F4652),
                Color(spirit.element.groundColorHex).copy(alpha = 0.45f)
            ),
            startY = jar.top,
            endY = jar.bottom
        ),
        topLeft = Offset(jar.left, jar.top),
        size = Size(jar.width, jar.height),
        cornerRadius = corner
    )

    // Strate 1 : Gravier & charbon drainant au fond
    val gravelTop = jar.bottom - jar.height * 0.12f
    drawRoundRect(
        color = Color(0xFF1E2930),
        topLeft = Offset(jar.left + jar.width * 0.08f, gravelTop),
        size = Size(jar.width * 0.84f, jar.height * 0.10f),
        cornerRadius = CornerRadius(32f, 32f)
    )
    for (i in 0 until 12) {
        val gx = jar.left + jar.width * (0.14f + (i * 0.065f))
        val gy = gravelTop + jar.height * (0.03f + (i % 3) * 0.02f)
        drawCircle(if (i % 2 == 0) Color(0xFF3A4C55) else Color(0xFF526670), 5.5f, Offset(gx, gy))
    }

    // Strate 2 : Sol élémentaire / bassin aquatique / roche volcanique
    val groundY = jar.bottom - jar.height * 0.22f
    val waveShift = tilt.tiltX * 15f
    val groundPath = Path().apply {
        moveTo(jar.left + jar.width * 0.05f, groundY + waveShift * 0.4f)
        quadraticTo(
            jar.center.x,
            groundY - 16f + sin(t * 2.2f) * if (spirit.element == SpiritElement.WATER) 7f else 2f,
            jar.right - jar.width * 0.05f,
            groundY - waveShift * 0.4f
        )
        lineTo(jar.right - jar.width * 0.06f, gravelTop + 8f)
        lineTo(jar.left + jar.width * 0.06f, gravelTop + 8f)
        close()
    }
    drawPath(
        path = groundPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(spirit.element.groundColorHex), Color(0xFF11201E)),
            startY = groundY,
            endY = gravelTop + 8f
        )
    )

    // Brins de mousse / herbes animés au vent intérieur
    if (spirit.element == SpiritElement.MOSS || spirit.element == SpiritElement.CLOUD) {
        val bladeColor = if (spirit.element == SpiritElement.MOSS) Color(0xFF4BE88B) else Color(0xFFB3C7FF)
        for (i in 0 until 14) {
            val bx = jar.left + jar.width * (0.12f + i * 0.058f)
            val sway = sin(t * 2.3f + i * 0.6f) * 5f
            drawLine(
                color = bladeColor.copy(alpha = 0.72f),
                start = Offset(bx, groundY + 2f),
                end = Offset(bx + sway, groundY - 14f - (i % 3) * 5f),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawActiveDecorations(
    jar: Rect,
    w: Float,
    h: Float,
    spirit: SpiritProfile,
    t: Float,
    auraColor: Color,
    decorEditMode: Boolean
) {
    for (id in spirit.activeDecorIds) {
        val item = DecorItem.entries.find { it.id == id } ?: continue
        val pos = spirit.decorPositions[id] ?: NormPos(item.defaultX, item.defaultY)
        val dx = pos.x * w
        val dy = pos.y * h

        if (decorEditMode) {
            drawCircle(Color(0x44FFF28C), 28f, Offset(dx, dy))
            drawCircle(Color(0xCCFFF28C), 28f, Offset(dx, dy), style = Stroke(2f))
        }

        when (item) {
            DecorItem.MOSS_STONES -> {
                drawOval(Color(0xFF49625C), Offset(dx - 24f, dy - 10f), Size(46f, 22f))
                drawOval(Color(0xFF67867C), Offset(dx + 6f, dy - 2f), Size(28f, 15f))
            }
            DecorItem.GLOW_MUSHROOMS -> {
                val glow = 0.68f + 0.32f * sin(t * 3f)
                drawLine(Color(0xFFDDEEDB), Offset(dx, dy + 6f), Offset(dx, dy - 20f), strokeWidth = 6f)
                drawArc(Color(0xFF7CF7B4).copy(alpha = glow), 180f, 180f, true, Offset(dx - 20f, dy - 36f), Size(40f, 28f))
                drawCircle(Color(0xFFFFF59D), 3.8f, Offset(dx - 6f, dy - 26f))
                drawCircle(Color(0xFFFFF59D), 3f, Offset(dx + 8f, dy - 24f))
            }
            DecorItem.FERN_CLUSTER -> {
                val sway = sin(t * 2f) * 6f
                for (k in -1..1) {
                    val bx = dx + k * 12f
                    val path = Path().apply {
                        moveTo(bx, dy + 6f)
                        quadraticTo(bx + k * 10f + sway, dy - 22f, bx + k * 20f + sway, dy - 42f)
                    }
                    drawPath(path, Color(0xFF43C47C), style = Stroke(width = 5.5f, cap = StrokeCap.Round))
                }
            }
            DecorItem.QUARTZ_CRYSTAL -> {
                val crystal = Path().apply {
                    moveTo(dx - 12f, dy + 6f); lineTo(dx - 5f, dy - 34f); lineTo(dx + 8f, dy - 24f); lineTo(dx + 12f, dy + 6f); close()
                }
                drawPath(crystal, Color(0xCCBB86FC))
            }
            DecorItem.WATER_LILY -> {
                drawOval(Color(0xFF2FA86E), Offset(dx - 22f, dy - 6f), Size(44f, 14f))
                drawCircle(Color(0xFFFFEAF2), 8f, Offset(dx, dy - 5f))
            }
            DecorItem.SEA_SHELL -> {
                drawArc(Color(0xFFFFD8C4), 180f, 180f, true, Offset(dx - 14f, dy - 10f), Size(28f, 22f))
            }
            DecorItem.FAIRY_LANTERN -> {
                val ly = dy + sin(t * 2.2f) * 5f
                drawLine(Color(0x99F3D08A), Offset(dx, jar.top + 8f), Offset(dx, ly - 10f), strokeWidth = 2.5f)
                drawCircle(Brush.radialGradient(listOf(Color(0xAAFFE484), Color.Transparent), Offset(dx, ly), 36f), 36f, Offset(dx, ly))
                drawRoundRect(Color(0xFFFFE082), Offset(dx - 8f, ly - 10f), Size(16f, 20f), CornerRadius(6f, 6f))
            }
            DecorItem.FIREFLIES_NEST -> {
                for (i in 0 until 6) {
                    val fx = dx + cos(t * (1.0f + i * 0.2f) + i * 1.4f) * 48f
                    val fy = dy + sin(t * (1.2f + i * 0.15f) + i * 2f) * 32f
                    val a = 0.4f + 0.6f * abs(sin(t * 3f + i))
                    drawCircle(Color(0xFFFFF48C).copy(alpha = a), 4.2f, Offset(fx, fy))
                }
            }
            DecorItem.BONSAI_TWIG -> {
                drawLine(Color(0xFF7B4E34), Offset(dx, dy + 6f), Offset(dx + 16f, dy - 52f), strokeWidth = 8f, cap = StrokeCap.Round)
                drawCircle(Color(0xFF47BE7B), 22f, Offset(dx + 20f, dy - 58f))
                drawCircle(Color(0xFF71E2A0), 15f, Offset(dx + 4f, dy - 48f))
            }
            DecorItem.MOON_PEBBLE -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE0F2FE), Color(0xFF7DD3FC)), Offset(dx, dy), 16f), 13f, Offset(dx, dy))
            }
            DecorItem.MINI_TORII -> {
                drawLine(Color(0xFFD94A3D), Offset(dx - 16f, dy + 6f), Offset(dx - 16f, dy - 38f), strokeWidth = 5f)
                drawLine(Color(0xFFD94A3D), Offset(dx + 16f, dy + 6f), Offset(dx + 16f, dy - 38f), strokeWidth = 5f)
                drawLine(Color(0xFFE85648), Offset(dx - 24f, dy - 38f), Offset(dx + 24f, dy - 38f), strokeWidth = 7f, cap = StrokeCap.Round)
                drawLine(Color(0xFFD94A3D), Offset(dx - 18f, dy - 28f), Offset(dx + 18f, dy - 28f), strokeWidth = 4f)
            }
            DecorItem.STAR_CHIME -> {
                val sy = dy + cos(t * 2.5f) * 5f
                drawLine(Color(0xAAFFFFFF), Offset(dx, jar.top + 8f), Offset(dx, sy), strokeWidth = 2f)
                drawCircle(auraColor.copy(alpha = 0.88f), 9f, Offset(dx, sy))
                drawCircle(Color.White, 3.8f, Offset(dx, sy))
            }
            DecorItem.SAKURA_BRANCH -> {
                drawLine(Color(0xFF6B4238), Offset(dx + 22f, dy - 16f), Offset(dx - 26f, dy + 10f), strokeWidth = 5f, cap = StrokeCap.Round)
                for (k in -2..2) {
                    drawCircle(Color(0xFFFFB7D5), 8f, Offset(dx + k * 10f, dy - k * 4f + sin(t * 2f + k) * 3f))
                }
            }
            DecorItem.CRYSTAL_FOUNTAIN -> {
                drawOval(Color(0xFF56CCF2), Offset(dx - 22f, dy - 6f), Size(44f, 14f))
                val jet = 18f + 6f * abs(sin(t * 5f))
                drawLine(Color(0xAA9DF0FF), Offset(dx, dy - 2f), Offset(dx, dy - jet), strokeWidth = 4f, cap = StrokeCap.Round)
            }
            DecorItem.MINI_CABIN -> {
                drawRoundRect(Color(0xFF8D5B34), Offset(dx - 18f, dy - 24f), Size(36f, 28f), CornerRadius(6f, 6f))
                val roof = Path().apply { moveTo(dx - 24f, dy - 22f); lineTo(dx, dy - 42f); lineTo(dx + 24f, dy - 22f); close() }
                drawPath(roof, Color(0xFFD97757))
                drawCircle(Color(0xFFFFF18C), 5f, Offset(dx, dy - 10f))
            }
            DecorItem.AURORA_PRISM -> {
                val py = dy + sin(t * 2f) * 6f
                val d = Path().apply { moveTo(dx, py - 18f); lineTo(dx + 14f, py); lineTo(dx, py + 18f); lineTo(dx - 14f, py); close() }
                drawPath(d, Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0xFFFF7AB8), Color(0xFFFFF08A))))
            }
        }
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
    val breathe = 1f + 0.038f * sin(animTime * if (spirit.isSleeping) 1.5f else 3.2f)
    val rx = baseRadius * (1f + squish * 0.45f) * breathe
    val ry = baseRadius * (1f - squish * 0.35f) / breathe
    val mainColor = Color(spirit.customAuraHex)
    val deepColor = Color(spirit.element.secondaryColorHex)

    if (battery.isCharging) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x99FFE66D), Color.Transparent), center, baseRadius * 2.2f),
            radius = baseRadius * 2.2f,
            center = center
        )
    }

    if (spirit.stage == EvolutionStage.GUARDIAN) {
        drawOval(
            color = Color(0xEEFFF39A),
            topLeft = Offset(center.x - rx * 0.60f, center.y - ry * 1.50f),
            size = Size(rx * 1.20f, ry * 0.30f),
            style = Stroke(width = 4.5f)
        )
    }

    when (spirit.element) {
        SpiritElement.MOSS -> {
            drawOval(
                brush = Brush.radialGradient(listOf(mainColor, deepColor), Offset(center.x - rx * 0.2f, center.y - ry * 0.25f), rx * 1.35f),
                topLeft = Offset(center.x - rx, center.y - ry * 0.92f),
                size = Size(rx * 2f, ry * 1.84f)
            )
            val leafCount = spirit.stage.level
            for (i in 0 until leafCount) {
                val angle = -35f + (i * (70f / max(1, leafCount - 1))) + sin(animTime * 3f + i) * 6f
                rotate(degrees = angle, pivot = Offset(center.x, center.y - ry * 0.88f)) {
                    drawOval(Color(0xFF4BE88B), Offset(center.x - rx * 0.14f, center.y - ry * 1.48f), Size(rx * 0.28f, ry * 0.64f))
                }
            }
        }
        SpiritElement.WATER -> {
            val dropPath = Path().apply {
                moveTo(center.x, center.y - ry * 1.30f)
                cubicTo(center.x + rx * 1.25f, center.y - ry * 0.18f, center.x + rx * 1.12f, center.y + ry * 0.98f, center.x, center.y + ry * 0.96f)
                cubicTo(center.x - rx * 1.12f, center.y + ry * 0.98f, center.x - rx * 1.25f, center.y - ry * 0.18f, center.x, center.y - ry * 1.30f)
                close()
            }
            drawPath(dropPath, Brush.radialGradient(listOf(Color(0xFFD6F8FF), mainColor, deepColor), Offset(center.x - rx * 0.25f, center.y - ry * 0.2f), rx * 1.5f))
            drawOval(Color.White.copy(alpha = 0.58f), Offset(center.x - rx * 0.48f, center.y - ry * 0.42f), Size(rx * 0.24f, ry * 0.44f))
        }
        SpiritElement.EMBER -> {
            val flicker = sin(animTime * 9f) * rx * 0.11f
            val flamePath = Path().apply {
                moveTo(center.x + flicker, center.y - ry * 1.44f)
                quadraticTo(center.x + rx * 1.25f, center.y - ry * 0.1f, center.x + rx * 0.85f, center.y + ry * 0.72f)
                quadraticTo(center.x, center.y + ry * 1.18f, center.x - rx * 0.85f, center.y + ry * 0.72f)
                quadraticTo(center.x - rx * 1.25f, center.y - ry * 0.1f, center.x + flicker, center.y - ry * 1.44f)
                close()
            }
            drawPath(flamePath, Brush.radialGradient(listOf(Color(0xFFFFF5B8), mainColor, deepColor), Offset(center.x, center.y + ry * 0.1f), rx * 1.4f))
        }
        SpiritElement.CLOUD -> {
            drawCircle(mainColor, rx * 0.68f, Offset(center.x - rx * 0.52f, center.y + ry * 0.12f))
            drawCircle(mainColor, rx * 0.68f, Offset(center.x + rx * 0.52f, center.y + ry * 0.12f))
            drawCircle(mainColor, rx * 0.86f, Offset(center.x, center.y - ry * 0.12f))
            drawCircle(deepColor.copy(alpha = 0.25f), rx * 0.58f, Offset(center.x, center.y + ry * 0.22f))
        }
    }

    val eyeSpacing = rx * 0.36f
    val eyeY = center.y + ry * 0.02f
    val isBlinking = (animTime % 4.1f) > 3.96f
    val sleeping = spirit.isSleeping || mood == SpiritMood.SLEEPY || mood == SpiritMood.LOW_BATTERY
    val lookDx = gazeNorm?.let { ((it.x * canvasSize.width - center.x) / (canvasSize.width * 0.45f)).coerceIn(-1f, 1f) * 6f } ?: 0f
    val lookDy = gazeNorm?.let { ((it.y * canvasSize.height - center.y) / (canvasSize.height * 0.45f)).coerceIn(-1f, 1f) * 4.5f } ?: 0f

    val eyeColor = Color(0xFF142124)
    if (sleeping || isBlinking) {
        drawArc(eyeColor, 10f, 160f, false, Offset(center.x - eyeSpacing - 11f, eyeY - 6f), Size(22f, 14f), style = Stroke(4f, cap = StrokeCap.Round))
        drawArc(eyeColor, 10f, 160f, false, Offset(center.x + eyeSpacing - 11f, eyeY - 6f), Size(22f, 14f), style = Stroke(4f, cap = StrokeCap.Round))
    } else if (squish > 0.06f || mood == SpiritMood.ECSTATIC) {
        drawArc(eyeColor, 190f, 160f, false, Offset(center.x - eyeSpacing - 12f, eyeY - 8f), Size(24f, 18f), style = Stroke(4.5f, cap = StrokeCap.Round))
        drawArc(eyeColor, 190f, 160f, false, Offset(center.x + eyeSpacing - 12f, eyeY - 8f), Size(24f, 18f), style = Stroke(4.5f, cap = StrokeCap.Round))
    } else {
        val eyeR = (rx * 0.135f).coerceIn(7f, 13f)
        drawCircle(eyeColor, eyeR, Offset(center.x - eyeSpacing + lookDx, eyeY + lookDy))
        drawCircle(eyeColor, eyeR, Offset(center.x + eyeSpacing + lookDx, eyeY + lookDy))
        drawCircle(Color.White, eyeR * 0.36f, Offset(center.x - eyeSpacing - 2.5f + lookDx, eyeY - 2.5f + lookDy))
        drawCircle(Color.White, eyeR * 0.36f, Offset(center.x + eyeSpacing - 2.5f + lookDx, eyeY - 2.5f + lookDy))
    }

    drawOval(Color(0x66FF7CA8), Offset(center.x - eyeSpacing - 16f, eyeY + 8f), Size(18f, 10f))
    drawOval(Color(0x66FF7CA8), Offset(center.x + eyeSpacing - 2f, eyeY + 8f), Size(18f, 10f))

    val mouthY = eyeY + ry * 0.20f
    if (mood == SpiritMood.HUNGRY || mood == SpiritMood.THIRSTY) {
        drawCircle(eyeColor, 4.5f, Offset(center.x, mouthY + 3f))
    } else if (!sleeping) {
        drawArc(eyeColor, 15f, 150f, false, Offset(center.x - 10f, mouthY - 5f), Size(20f, 14f), style = Stroke(3.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawJarGlassAndFogOverlay(jar: Rect, spirit: SpiritProfile, t: Float) {
    val corner = jarCornerRadius(jar, spirit.jarStyle)

    // Voile de buée interactif (l'utilisateur peut l'essuyer en glissant le doigt !)
    if (spirit.glassFog > 0.04f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFFFFF).copy(alpha = spirit.glassFog * 0.24f), Color(0xFFCCF7FF).copy(alpha = spirit.glassFog * 0.34f)),
                center = jar.center,
                radius = jar.width * 0.7f
            ),
            topLeft = Offset(jar.left, jar.top),
            size = Size(jar.width, jar.height),
            cornerRadius = corner
        )
        val drops = (spirit.glassFog * 16).toInt().coerceIn(3, 14)
        for (i in 0 until drops) {
            val dx = jar.left + jar.width * (0.14f + (i * 23 % 72) / 100f)
            val dy = jar.top + jar.height * (0.18f + ((i * 29 + (t * 4).toInt()) % 58) / 100f)
            drawCircle(Color(0x88E0FBFF), 4f + (i % 3), Offset(dx, dy))
        }
    }

    val rimColor = when (spirit.jarStyle) {
        JarStyle.LANTERN, JarStyle.OBSERVATORY -> Color(0xDDF5C97B)
        JarStyle.CRYSTAL_GEODE -> Color(0xDDD5B3FF)
        JarStyle.TEARDROP_DOME -> Color(0xDD99F6FF)
        else -> Color(0xAAD9FFF2)
    }
    drawRoundRect(rimColor, Offset(jar.left, jar.top), Size(jar.width, jar.height), corner, style = Stroke(width = 5.5f))

    // Doubles reflets courbés réalistes sur le verre
    val leftShine = Path().apply {
        moveTo(jar.left + jar.width * 0.12f, jar.top + jar.height * 0.18f)
        quadraticTo(jar.left + jar.width * 0.07f, jar.center.y, jar.left + jar.width * 0.14f, jar.bottom - jar.height * 0.26f)
    }
    drawPath(leftShine, Color.White.copy(alpha = 0.28f), style = Stroke(width = 11f, cap = StrokeCap.Round))
    val rightShine = Path().apply {
        moveTo(jar.right - jar.width * 0.12f, jar.top + jar.height * 0.24f)
        quadraticTo(jar.right - jar.width * 0.08f, jar.top + jar.height * 0.42f, jar.right - jar.width * 0.11f, jar.top + jar.height * 0.52f)
    }
    drawPath(rightShine, Color.White.copy(alpha = 0.16f), style = Stroke(width = 6f, cap = StrokeCap.Round))

    // Bouchon / Coupole
    val lidW = jar.width * 0.44f
    val lidH = jar.height * 0.075f
    val lidColor = when (spirit.jarStyle) {
        JarStyle.LANTERN, JarStyle.OBSERVATORY -> Color(0xFFC89B4B)
        JarStyle.CRYSTAL_GEODE -> Color(0xFF8C6CC7)
        JarStyle.TEARDROP_DOME -> Color(0xFF5FB0C4)
        else -> Color(0xFFA97142)
    }
    drawRoundRect(lidColor, Offset(jar.center.x - lidW / 2f, jar.top - lidH * 0.65f), Size(lidW, lidH), CornerRadius(14f, 14f))
    drawRoundRect(Color(0x66FFFFFF), Offset(jar.center.x - lidW * 0.56f, jar.top - 5f), Size(lidW * 1.12f, 12f), CornerRadius(6f, 6f))
}

private fun jarCornerRadius(jar: Rect, style: JarStyle): CornerRadius = when (style) {
    JarStyle.ROUND_TERRARIUM -> CornerRadius(jar.width * 0.42f, jar.width * 0.42f)
    JarStyle.APOTHECARY -> CornerRadius(jar.width * 0.22f, jar.width * 0.28f)
    JarStyle.LANTERN -> CornerRadius(jar.width * 0.14f, jar.width * 0.14f)
    JarStyle.CRYSTAL_GEODE -> CornerRadius(jar.width * 0.30f, jar.width * 0.36f)
    JarStyle.TEARDROP_DOME -> CornerRadius(jar.width * 0.48f, jar.width * 0.48f)
    JarStyle.OBSERVATORY -> CornerRadius(jar.width * 0.46f, jar.width * 0.22f)
}

private fun DrawScope.drawMiniHeart(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.7f)
        cubicTo(center.x - radius * 1.2f, center.y - radius * 0.1f, center.x - radius * 0.6f, center.y - radius * 1.1f, center.x, center.y - radius * 0.35f)
        cubicTo(center.x + radius * 0.6f, center.y - radius * 1.1f, center.x + radius * 1.2f, center.y - radius * 0.1f, center.x, center.y + radius * 0.7f)
        close()
    }
    drawPath(path, color)
}
