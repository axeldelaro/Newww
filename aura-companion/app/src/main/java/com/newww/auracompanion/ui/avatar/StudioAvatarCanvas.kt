package com.newww.auracompanion.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.newww.auracompanion.model.*
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

private data class StudioHeart(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float)

@Composable
fun StudioAvatarCanvas(
    character: CompanionCharacter,
    emotion: AvatarEmotion,
    lipSyncLevel: Float,
    lipSyncWide: Float,
    isListening: Boolean,
    onPetAvatar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val st = character.studio
    var t by remember { mutableFloatStateOf(0f) }
    var gazeNorm by remember { mutableStateOf<Offset?>(null) }
    var petBoost by remember { mutableFloatStateOf(0f) }
    var lastPetMs by remember { mutableLongStateOf(0L) }
    val hearts = remember { mutableStateListOf<StudioHeart>() }

    LaunchedEffect(Unit) {
        var lastNs = System.nanoTime()
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            lastNs = now
            t += dt
            petBoost = (petBoost - dt * 0.45f).coerceAtLeast(0f)

            val iter = hearts.listIterator()
            while (iter.hasNext()) {
                val fx = iter.next()
                fx.life += dt
                if (fx.life > 1.3f) iter.remove()
                else { fx.x += fx.vx * dt; fx.y += fx.vy * dt }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val nx = (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeNorm = Offset(nx, ny)
                    petBoost = 0.9f
                    hearts += StudioHeart(nx, ny, (Random.nextFloat() - 0.5f) * 0.08f, -0.22f, 0f)
                    onPetAvatar()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { gazeNorm = null }) { change, _ ->
                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    gazeNorm = Offset(nx, ny)
                    val now = System.currentTimeMillis()
                    if (now - lastPetMs > 500L) {
                        lastPetMs = now
                        petBoost = 1f
                        hearts += StudioHeart(nx, ny, (Random.nextFloat() - 0.5f) * 0.1f, -0.2f, 0f)
                        onPetAvatar()
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f

        drawAnimatedSceneBackdrop(character.backdrop, t)

        val auraCol = if (st.artStyle == StudioArtStyle.CYBER_NEON) Color(0xFF00F0FF) else Color(st.hairHighlightHex)
        drawCircle(
            Brush.radialGradient(listOf(auraCol.copy(alpha = 0.30f), Color.Transparent), Offset(cx, h * 0.38f), min(w, h) * 0.52f),
            min(w, h) * 0.52f,
            Offset(cx, h * 0.38f)
        )

        val unit = min(w, h)
        val chibiScale = if (st.artStyle == StudioArtStyle.CHIBI_CUTE) 1.14f else 1.0f
        val headR = unit * 0.21f * chibiScale
        val breatheY = sin(t * 2.2f) * unit * 0.009f
        val headSway = sin(t * 1.0f) * unit * 0.011f
        val headTiltDeg = sin(t * 0.85f) * 3.0f +
            (if (emotion == AvatarEmotion.SHY) 5.5f else 0f) +
            (if (isListening) -4.2f else 0f)
        val hairPhysics = sin(t * 1.4f - 0.6f) * unit * 0.015f

        val headCenter = Offset(cx + headSway, h * 0.35f + breatheY)
        val skin = Color(st.skinToneHex)
        val skinShadow = Color((skin.red * 0.88f).coerceIn(0f, 1f), (skin.green * 0.84f).coerceIn(0f, 1f), (skin.blue * 0.84f).coerceIn(0f, 1f), 1f)
        val hairCol = Color(st.hairColorHex)
        val hairHi = Color(st.hairHighlightHex)
        val outfitCol = Color(st.outfitColorHex)

        // 1. Cheveux arrière (10 coiffures)
        rotate(headTiltDeg * 0.6f, pivot = headCenter) {
            when (st.hairStyle) {
                HairStyle.LONG_WAVY, HairStyle.BOHO_BANGS, HairStyle.HIME_CUT -> {
                    val backHair = Path().apply {
                        moveTo(headCenter.x - headR * 1.22f, headCenter.y - headR * 0.1f)
                        quadraticTo(headCenter.x - headR * 1.42f + hairPhysics, headCenter.y + headR * 1.65f, headCenter.x - headR * 0.88f + hairPhysics * 1.3f, headCenter.y + headR * 2.6f)
                        lineTo(headCenter.x + headR * 0.88f + hairPhysics * 1.3f, headCenter.y + headR * 2.6f)
                        quadraticTo(headCenter.x + headR * 1.42f + hairPhysics, headCenter.y + headR * 1.65f, headCenter.x + headR * 1.22f, headCenter.y - headR * 0.1f)
                        close()
                    }
                    drawPath(backHair, Brush.verticalGradient(listOf(hairCol, hairCol.copy(alpha = 0.94f), hairHi.copy(alpha = 0.55f))))
                }
                HairStyle.SIDE_BRAID -> {
                    drawOval(hairCol, Offset(headCenter.x - headR * 1.15f, headCenter.y - headR * 0.95f), Size(headR * 2.3f, headR * 2.1f))
                    for (b in 0 until 5) {
                        val bx = headCenter.x + headR * 0.82f + hairPhysics * (0.4f + b * 0.2f)
                        val by = headCenter.y + headR * (0.75f + b * 0.34f)
                        drawCircle(if (b % 2 == 0) hairCol else hairHi.copy(alpha = 0.85f), headR * (0.24f - b * 0.02f), Offset(bx, by))
                    }
                }
                HairStyle.TWIN_TAILS -> {
                    for (dir in listOf(-1f, 1f)) {
                        val tail = Path().apply {
                            moveTo(headCenter.x + dir * headR * 0.95f, headCenter.y - headR * 0.35f)
                            quadraticTo(headCenter.x + dir * headR * 1.78f + hairPhysics, headCenter.y + headR * 0.55f, headCenter.x + dir * headR * 1.35f + hairPhysics * 1.4f, headCenter.y + headR * 2.1f)
                            quadraticTo(headCenter.x + dir * headR * 0.95f, headCenter.y + headR * 1.1f, headCenter.x + dir * headR * 0.78f, headCenter.y - headR * 0.1f)
                            close()
                        }
                        drawPath(tail, hairCol)
                    }
                }
                HairStyle.HIGH_PONY -> {
                    val pony = Path().apply {
                        moveTo(headCenter.x, headCenter.y - headR * 1.05f)
                        quadraticTo(headCenter.x + headR * 1.55f + hairPhysics, headCenter.y - headR * 0.6f, headCenter.x + headR * 1.18f + hairPhysics * 1.5f, headCenter.y + headR * 1.5f)
                        quadraticTo(headCenter.x + headR * 0.65f, headCenter.y + headR * 0.2f, headCenter.x - headR * 0.15f, headCenter.y - headR * 0.9f)
                        close()
                    }
                    drawPath(pony, hairCol)
                }
                HairStyle.COZY_BUN -> {
                    drawCircle(hairCol, headR * 0.44f, Offset(headCenter.x, headCenter.y - headR * 1.12f))
                    drawCircle(hairHi.copy(alpha = 0.45f), headR * 0.22f, Offset(headCenter.x - headR * 0.1f, headCenter.y - headR * 1.2f))
                }
                HairStyle.SLEEK_BOB, HairStyle.SHOULDER_CURLS -> {
                    drawOval(hairCol, Offset(headCenter.x - headR * 1.24f, headCenter.y - headR * 1.05f), Size(headR * 2.48f, headR * 2.55f))
                }
                HairStyle.SHORT_PIXIE -> {
                    drawCircle(hairCol, headR * 1.12f, Offset(headCenter.x, headCenter.y - headR * 0.08f))
                }
            }
        }

        // 2. Cou & Tenues (8 styles)
        val neckTop = headCenter.y + headR * 0.72f
        drawRoundRect(skinShadow, Offset(cx - headR * 0.24f + headSway * 0.4f, neckTop), Size(headR * 0.48f, headR * 0.56f), CornerRadius(14f, 14f))

        val shoulderY = neckTop + headR * 0.36f
        val torsoPath = Path().apply {
            moveTo(cx - headR * 1.45f, h * 1.02f)
            quadraticTo(cx - headR * 1.38f, shoulderY + headR * 0.15f, cx - headR * 0.36f, shoulderY)
            lineTo(cx + headR * 0.36f, shoulderY)
            quadraticTo(cx + headR * 1.38f, shoulderY + headR * 0.15f, cx + headR * 1.45f, h * 1.02f)
            close()
        }
        drawPath(torsoPath, Brush.verticalGradient(listOf(outfitCol, outfitCol.copy(alpha = 0.70f))))

        when (st.outfitStyle) {
            OutfitStyle.COZY_SWEATER, OutfitStyle.HOODIE_CHILL -> {
                drawRoundRect(outfitCol.copy(alpha = 0.92f), Offset(cx - headR * 0.48f, shoulderY - headR * 0.08f), Size(headR * 0.96f, headR * 0.32f), CornerRadius(24f, 24f))
                if (st.outfitStyle == OutfitStyle.HOODIE_CHILL) {
                    drawLine(Color.White.copy(alpha = 0.8f), Offset(cx - headR * 0.16f, shoulderY + headR * 0.2f), Offset(cx - headR * 0.18f, shoulderY + headR * 0.72f), strokeWidth = 4f, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.8f), Offset(cx + headR * 0.16f, shoulderY + headR * 0.2f), Offset(cx + headR * 0.18f, shoulderY + headR * 0.72f), strokeWidth = 4f, cap = StrokeCap.Round)
                }
            }
            OutfitStyle.ELEGANT_SHIRT, OutfitStyle.ACADEMY_BLAZER -> {
                val cL = Path().apply { moveTo(cx - headR * 0.36f, shoulderY); lineTo(cx, shoulderY + headR * 0.42f); lineTo(cx - headR * 0.52f, shoulderY + headR * 0.36f); close() }
                val cR = Path().apply { moveTo(cx + headR * 0.36f, shoulderY); lineTo(cx, shoulderY + headR * 0.42f); lineTo(cx + headR * 0.52f, shoulderY + headR * 0.36f); close() }
                drawPath(cL, Color(0xFFF6F4FF)); drawPath(cR, Color(0xFFF6F4FF))
            }
            OutfitStyle.CYBER_JACKET -> {
                drawLine(Color(0xFF00F0FF), Offset(cx - headR * 0.45f, shoulderY + 6f), Offset(cx - headR * 0.45f, h), strokeWidth = 4f)
                drawLine(Color(0xFF00F0FF), Offset(cx + headR * 0.45f, shoulderY + 6f), Offset(cx + headR * 0.45f, h), strokeWidth = 4f)
            }
            OutfitStyle.SUMMER_DRESS, OutfitStyle.EVENING_GOWN -> {
                drawArc(skin, 0f, 180f, true, Offset(cx - headR * 0.46f, shoulderY - headR * 0.14f), Size(headR * 0.92f, headR * 0.56f))
            }
            OutfitStyle.STAR_PAJAMA -> {
                for (i in -1..1) drawCircle(Color(0xFFFFF29C), 5.5f, Offset(cx + i * headR * 0.48f, shoulderY + headR * 0.65f))
            }
        }

        if (st.accessory == AccessoryStyle.PEARL_NECKLACE) {
            for (k in -4..4) {
                val ang = (k / 4f) * 0.75f
                val px = cx + sin(ang) * headR * 0.44f
                val py = shoulderY + cos(ang) * headR * 0.22f - headR * 0.08f
                drawCircle(Color(0xFFFFF7F0), 5.2f, Offset(px, py))
            }
        }

        // 3. Visage + Yeux + Lip-Sync Visème + Accessoires
        rotate(headTiltDeg, pivot = headCenter) {
            drawCircle(skinShadow, headR * 0.16f, Offset(headCenter.x - headR * 0.94f, headCenter.y + headR * 0.08f))
            drawCircle(skinShadow, headR * 0.16f, Offset(headCenter.x + headR * 0.94f, headCenter.y + headR * 0.08f))

            if (st.accessory == AccessoryStyle.GOLD_EARRINGS) {
                drawCircle(Color(0xFFFFD56B), 7f, Offset(headCenter.x - headR * 0.94f, headCenter.y + headR * 0.25f), style = Stroke(3.2f))
                drawCircle(Color(0xFFFFD56B), 7f, Offset(headCenter.x + headR * 0.94f, headCenter.y + headR * 0.25f), style = Stroke(3.2f))
            }

            val facePath = Path().apply {
                moveTo(headCenter.x - headR * 0.92f, headCenter.y - headR * 0.15f)
                cubicTo(headCenter.x - headR * 0.96f, headCenter.y + headR * 0.68f, headCenter.x - headR * 0.36f, headCenter.y + headR * 1.04f, headCenter.x, headCenter.y + headR * 1.06f)
                cubicTo(headCenter.x + headR * 0.36f, headCenter.y + headR * 1.04f, headCenter.x + headR * 0.96f, headCenter.y + headR * 0.68f, headCenter.x + headR * 0.92f, headCenter.y - headR * 0.15f)
                cubicTo(headCenter.x + headR * 0.92f, headCenter.y - headR * 0.95f, headCenter.x - headR * 0.92f, headCenter.y - headR * 0.95f, headCenter.x - headR * 0.92f, headCenter.y - headR * 0.15f)
                close()
            }
            drawPath(facePath, Brush.radialGradient(listOf(skin, skin, skinShadow), Offset(headCenter.x, headCenter.y - headR * 0.15f), headR * 1.3f))

            val blushAlpha = (st.blushIntensity * 0.45f + (if (emotion == AvatarEmotion.LOVE || emotion == AvatarEmotion.SHY) 0.28f else 0f) + petBoost * 0.25f).coerceIn(0f, 0.72f)
            val cheekY = headCenter.y + headR * 0.30f
            drawOval(Color(0xFFFF6B9D).copy(alpha = blushAlpha), Offset(headCenter.x - headR * 0.66f, cheekY), Size(headR * 0.34f, headR * 0.18f))
            drawOval(Color(0xFFFF6B9D).copy(alpha = blushAlpha), Offset(headCenter.x + headR * 0.32f, cheekY), Size(headR * 0.34f, headR * 0.18f))

            if (st.freckles) {
                val fCol = Color(0x559B5D45)
                for (dx in listOf(-0.52f, -0.45f, -0.38f, 0.38f, 0.45f, 0.52f)) {
                    drawCircle(fCol, 2.3f, Offset(headCenter.x + headR * dx, cheekY + 6f + (abs(dx) * 8f)))
                }
            }

            val eyeY = headCenter.y + headR * 0.08f
            val eyeOffset = headR * 0.38f
            val eyeSize = headR * if (st.artStyle in listOf(StudioArtStyle.ANIME_LIVE2D, StudioArtStyle.CHIBI_CUTE, StudioArtStyle.WATERCOLOR)) 0.24f else 0.19f
            val blinkNow = (t % 3.7f) > 3.55f
            val lookX = gazeNorm?.let { ((it.x - 0.5f) * 14f).coerceIn(-8f, 8f) } ?: (sin(t * 0.6f) * 2.5f)
            val lookY = gazeNorm?.let { ((it.y - 0.35f) * 10f).coerceIn(-6f, 6f) } ?: 0f

            for ((side, ex) in listOf(-1 to (headCenter.x - eyeOffset), 1 to (headCenter.x + eyeOffset))) {
                val winkThis = side == 1 && emotion == AvatarEmotion.TEASING
                if (blinkNow || winkThis || petBoost > 0.45f) {
                    drawArc(Color(0xFF2B1C28), 195f, 150f, false, Offset(ex - eyeSize, eyeY - eyeSize * 0.5f), Size(eyeSize * 2f, eyeSize * 1.2f), style = Stroke(4.8f, cap = StrokeCap.Round))
                } else {
                    drawOval(Color.White, Offset(ex - eyeSize, eyeY - eyeSize * 0.85f), Size(eyeSize * 2f, eyeSize * 1.7f))
                    val irisColor = Color(st.eyeColorHex)
                    drawCircle(Brush.radialGradient(listOf(irisColor, irisColor.copy(alpha = 0.85f), Color(0xFF1A1024)), Offset(ex + lookX, eyeY + lookY), eyeSize * 0.78f), eyeSize * 0.72f, Offset(ex + lookX, eyeY + lookY))
                    drawCircle(Color(0xFF140D1C), eyeSize * 0.34f, Offset(ex + lookX, eyeY + lookY))
                    drawCircle(Color.White, eyeSize * 0.24f, Offset(ex - eyeSize * 0.25f + lookX * 0.6f, eyeY - eyeSize * 0.25f + lookY * 0.6f))
                    drawCircle(Color.White.copy(alpha = 0.8f), eyeSize * 0.11f, Offset(ex + eyeSize * 0.22f + lookX * 0.6f, eyeY + eyeSize * 0.22f + lookY * 0.6f))
                    drawArc(Color(0xFF231622), 190f, 160f, false, Offset(ex - eyeSize * 1.08f, eyeY - eyeSize * 0.92f), Size(eyeSize * 2.16f, eyeSize * 1.5f), style = Stroke(5.5f, cap = StrokeCap.Round))
                }
                val browOffsetY = when (emotion) {
                    AvatarEmotion.SURPRISED -> -eyeSize * 0.45f
                    AvatarEmotion.HAPPY, AvatarEmotion.LOVE -> -eyeSize * 0.18f
                    AvatarEmotion.TEASING -> if (side == -1) -eyeSize * 0.35f else 0f
                    else -> 0f
                }
                val browTilt = when (emotion) { AvatarEmotion.SAD -> side * -7f; AvatarEmotion.TEASING -> side * 5f; else -> side * -2f }
                drawLine(hairCol, Offset(ex - eyeSize * 0.8f, eyeY - eyeSize * 1.25f + browOffsetY - browTilt), Offset(ex + eyeSize * 0.8f, eyeY - eyeSize * 1.25f + browOffsetY + browTilt), strokeWidth = 4.2f, cap = StrokeCap.Round)
            }

            if (st.accessory == AccessoryStyle.GLASSES) {
                val gCol = Color(0xFFEBD08D)
                drawOval(gCol, Offset(headCenter.x - eyeOffset - eyeSize * 1.35f, eyeY - eyeSize * 1.05f), Size(eyeSize * 2.7f, eyeSize * 2.1f), style = Stroke(3.2f))
                drawOval(gCol, Offset(headCenter.x + eyeOffset - eyeSize * 1.35f, eyeY - eyeSize * 1.05f), Size(eyeSize * 2.7f, eyeSize * 2.1f), style = Stroke(3.2f))
                drawLine(gCol, Offset(headCenter.x - eyeOffset + eyeSize * 1.35f, eyeY), Offset(headCenter.x + eyeOffset - eyeSize * 1.35f, eyeY), strokeWidth = 3f)
            }

            drawLine(skinShadow, Offset(headCenter.x, headCenter.y + headR * 0.24f), Offset(headCenter.x - 3f, headCenter.y + headR * 0.34f), strokeWidth = 3f, cap = StrokeCap.Round)

            // Bouche + Lip-Sync Visème (ouverture + largeur)
            val mouthY = headCenter.y + headR * 0.56f
            val lipColor = Color(st.lipColorHex)
            val openH = (lipSyncLevel * headR * 0.34f + if (emotion == AvatarEmotion.SURPRISED) headR * 0.18f else 0f)
            val mouthW = headR * (0.28f + lipSyncWide * 0.10f)

            if (openH > 4.2f) {
                drawOval(Color(0xFF5D182C), Offset(headCenter.x - mouthW * 0.78f, mouthY - openH * 0.42f), Size(mouthW * 1.56f, openH * 1.30f))
                drawRoundRect(Color(0xFFFAF5F4), Offset(headCenter.x - mouthW * 0.52f, mouthY - openH * 0.36f), Size(mouthW * 1.04f, openH * 0.28f), CornerRadius(6f, 6f))
                drawArc(Color(0xFFFF8AA6), 15f, 150f, true, Offset(headCenter.x - mouthW * 0.5f, mouthY + openH * 0.18f), Size(mouthW, openH * 0.65f))
                drawOval(lipColor, Offset(headCenter.x - mouthW * 0.80f, mouthY - openH * 0.44f), Size(mouthW * 1.60f, openH * 1.34f), style = Stroke(3.6f))
            } else {
                val smileCurve = when (emotion) { AvatarEmotion.SAD -> -10f; AvatarEmotion.HAPPY, AvatarEmotion.LOVE -> 15f; AvatarEmotion.TEASING -> 12f; else -> 8f }
                val mouthPath = Path().apply {
                    moveTo(headCenter.x - mouthW, mouthY)
                    quadraticTo(headCenter.x, mouthY + smileCurve, headCenter.x + mouthW, mouthY - if (emotion == AvatarEmotion.TEASING) 5f else 0f)
                }
                drawPath(mouthPath, lipColor, style = Stroke(width = 4.8f, cap = StrokeCap.Round))
            }

            // Frange avant
            val crownPath = Path().apply {
                moveTo(headCenter.x - headR * 1.02f, headCenter.y + headR * 0.15f)
                cubicTo(headCenter.x - headR * 1.08f, headCenter.y - headR * 1.15f, headCenter.x + headR * 1.08f, headCenter.y - headR * 1.15f, headCenter.x + headR * 1.02f, headCenter.y + headR * 0.15f)
                lineTo(headCenter.x + headR * 0.78f, headCenter.y - headR * 0.25f)
                quadraticTo(headCenter.x + headR * 0.45f, headCenter.y - headR * 0.05f, headCenter.x + headR * 0.25f, headCenter.y - headR * 0.36f)
                quadraticTo(headCenter.x, headCenter.y - headR * 0.02f, headCenter.x - headR * 0.25f, headCenter.y - headR * 0.36f)
                quadraticTo(headCenter.x - headR * 0.55f, headCenter.y - headR * 0.05f, headCenter.x - headR * 0.78f, headCenter.y - headR * 0.25f)
                close()
            }
            drawPath(crownPath, hairCol)
            drawArc(hairHi.copy(alpha = 0.45f), 215f, 110f, false, Offset(headCenter.x - headR * 0.72f, headCenter.y - headR * 0.84f), Size(headR * 1.44f, headR * 0.72f), style = Stroke(7f, cap = StrokeCap.Round))

            when (st.accessory) {
                AccessoryStyle.CAT_EARS -> {
                    for (dir in listOf(-1f, 1f)) {
                        val ear = Path().apply { moveTo(headCenter.x + dir * headR * 0.32f, headCenter.y - headR * 0.85f); lineTo(headCenter.x + dir * headR * 0.78f, headCenter.y - headR * 1.42f); lineTo(headCenter.x + dir * headR * 0.88f, headCenter.y - headR * 0.62f); close() }
                        drawPath(ear, hairCol)
                        val inner = Path().apply { moveTo(headCenter.x + dir * headR * 0.45f, headCenter.y - headR * 0.88f); lineTo(headCenter.x + dir * headR * 0.72f, headCenter.y - headR * 1.24f); lineTo(headCenter.x + dir * headR * 0.78f, headCenter.y - headR * 0.74f); close() }
                        drawPath(inner, Color(0xFFFF9CC2))
                    }
                }
                AccessoryStyle.RIBBON -> {
                    val rx = headCenter.x + headR * 0.62f; val ry = headCenter.y - headR * 0.78f
                    drawOval(outfitCol, Offset(rx - 28f, ry - 14f), Size(28f, 22f))
                    drawOval(outfitCol, Offset(rx + 2f, ry - 14f), Size(28f, 22f))
                    drawCircle(Color(0xFFFFE5F0), 8f, Offset(rx, ry - 3f))
                }
                AccessoryStyle.HAIR_CLIPS -> {
                    drawCircle(Color(0xFFFFE57F), 8f, Offset(headCenter.x - headR * 0.62f, headCenter.y - headR * 0.48f))
                    drawCircle(Color(0xFFFF9BD2), 6f, Offset(headCenter.x - headR * 0.74f, headCenter.y - headR * 0.35f))
                }
                AccessoryStyle.HEADPHONES -> {
                    drawArc(Color(0xFF282438), 185f, 170f, false, Offset(headCenter.x - headR * 1.08f, headCenter.y - headR * 1.12f), Size(headR * 2.16f, headR * 2.0f), style = Stroke(11f, cap = StrokeCap.Round))
                    drawRoundRect(Color(0xFF00E5FF), Offset(headCenter.x - headR * 1.14f, headCenter.y - headR * 0.14f), Size(22f, 48f), CornerRadius(12f, 12f))
                    drawRoundRect(Color(0xFF00E5FF), Offset(headCenter.x + headR * 1.14f - 22f, headCenter.y - headR * 0.14f), Size(22f, 48f), CornerRadius(12f, 12f))
                }
                else -> Unit
            }
        }

        // Vignette douce Mimico en haut et en bas
        drawRect(Brush.verticalGradient(listOf(Color(0x780B0712), Color.Transparent, Color.Transparent, Color(0xD80B0712))))

        for (fx in hearts) {
            drawFloatingHeart(Offset(fx.x * w, fx.y * h), 18f, Color(0xFFFF7AB8).copy(alpha = (1f - fx.life / 1.3f).coerceIn(0f, 1f)))
        }
    }
}
