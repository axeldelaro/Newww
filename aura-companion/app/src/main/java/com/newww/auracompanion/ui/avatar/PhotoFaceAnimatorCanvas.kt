package com.newww.auracompanion.ui.avatar

import android.graphics.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.newww.auracompanion.model.*
import com.newww.auracompanion.vision.AnalyzedPhotoAvatar
import com.newww.auracompanion.vision.FaceRigLandmarks
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

private data class HeartFx(var x: Float, var y: Float, var vy: Float, var vx: Float, var life: Float, var size: Float)

/**
 * Moteur LivePortrait 60 FPS v2 sur photo importée :
 * - Maillage haute précision 32x32 ancré aux 478 repères MediaPipe
 * - Clignement ultra-réaliste avec paupières teintées sur la vraie peau de la photo + courbe de cils
 * - Micro-saccades du regard + suivi du doigt
 * - Lip-Sync Visème-Aware très vivant (ouverture RMS + étirement voyelles ZCR + lueur dents + cavité buccale réaliste)
 * - Mouvements de tête 2.5D (hochements en écoute, inclinaison, respiration thoracique, micro-sourires)
 * - 10 filtres cinéma + 10 décors animés par segmentation IA Selfie Segmenter
 */
@Composable
fun PhotoFaceAnimatorCanvas(
    analyzedPhoto: AnalyzedPhotoAvatar,
    character: CompanionCharacter,
    emotion: AvatarEmotion,
    lipSyncOpen: Float,
    lipSyncWide: Float,
    isListening: Boolean,
    isThinking: Boolean,
    onPetAvatar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animCfg = character.photoAnim
    var t by remember { mutableFloatStateOf(0f) }
    var pointerNorm by remember { mutableStateOf<Offset?>(null) }
    var lastPetMs by remember { mutableLongStateOf(0L) }
    var petBlushBoost by remember { mutableFloatStateOf(0f) }
    val hearts = remember { mutableStateListOf<HeartFx>() }

    val meshCols = 32
    val meshRows = 32
    val verts = remember { FloatArray((meshCols + 1) * (meshRows + 1) * 2) }
    val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val mouthPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val teethPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val lidPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val lashPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
    val blushPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    LaunchedEffect(Unit) {
        var lastNs = System.nanoTime()
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            lastNs = now
            t += dt
            petBlushBoost = (petBlushBoost - dt * 0.40f).coerceAtLeast(0f)

            val iter = hearts.listIterator()
            while (iter.hasNext()) {
                val fx = iter.next()
                fx.life += dt
                if (fx.life > 1.35f) iter.remove()
                else {
                    fx.x += fx.vx * dt
                    fx.y += fx.vy * dt
                }
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
                    pointerNorm = Offset(nx, ny)
                    petBlushBoost = 0.95f
                    repeat(3) {
                        hearts += HeartFx(nx + (Random.nextFloat() - 0.5f) * 0.08f, ny, -0.22f - Random.nextFloat() * 0.08f, (Random.nextFloat() - 0.5f) * 0.1f, 0f, 18f)
                    }
                    onPetAvatar()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { pointerNorm = null }) { change, _ ->
                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    pointerNorm = Offset(nx, ny)
                    val now = System.currentTimeMillis()
                    if (now - lastPetMs > 480L) {
                        lastPetMs = now
                        petBlushBoost = 1.0f
                        hearts += HeartFx(nx, ny, -0.22f, (Random.nextFloat() - 0.5f) * 0.1f, 0f, 20f)
                        onPetAvatar()
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        drawAnimatedSceneBackdrop(character.backdrop, t)

        val rig = analyzedPhoto.rig
        val bmp = if (character.replacePhotoBackground && analyzedPhoto.cutoutBitmap != null) {
            analyzedPhoto.cutoutBitmap
        } else {
            analyzedPhoto.sourceBitmap
        }

        val motionMult = animCfg.headMotionIntensity.coerceIn(0.4f, 1.6f)
        val lipMult = animCfg.lipSyncIntensity.coerceIn(0.5f, 1.8f)

        val scale = max(w / bmp.width.toFloat(), h / bmp.height.toFloat()) * animCfg.photoZoom.coerceIn(0.95f, 1.35f)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val baseLeft = (w - drawW) * 0.5f
        val baseTop = (h - drawH) * 0.42f

        // Mouvements vivants 2.5D : respiration, balancement naturel, hochement en parlant/écoutant
        val breathe = sin(t * 2.1f) * 0.012f * motionMult
        val speakingNod = if (lipSyncOpen > 0.12f) sin(t * 7.2f) * 0.008f * lipSyncOpen else 0f
        val listenNod = if (isListening) sin(t * 3.8f) * 0.010f else 0f
        val swayX = (sin(t * 0.92f) * 0.014f + sin(t * 0.41f) * 0.007f) * motionMult +
            (pointerNorm?.let { (it.x - 0.5f) * 0.032f } ?: 0f)
        val swayY = (cos(t * 1.25f) * 0.008f * motionMult) + speakingNod + listenNod +
            (pointerNorm?.let { (it.y - 0.45f) * 0.022f } ?: 0f)

        val headTilt = (sin(t * 0.78f) * 0.030f * motionMult) +
            (if (emotion == AvatarEmotion.SHY) 0.052f else 0f) +
            (if (emotion == AvatarEmotion.TEASING) -0.042f else 0f) +
            (if (isListening) -0.035f else 0f) +
            (if (isThinking) 0.038f else 0f)

        // Saccades naturelles du regard (regarde légèrement sur le côté en réfléchissant ou suit le doigt)
        val gazeShiftX = when {
            pointerNorm != null -> ((pointerNorm!!.x - 0.5f) * 0.016f).coerceIn(-0.010f, 0.010f)
            isThinking -> 0.009f * sin(t * 1.4f)
            else -> 0.005f * sin(t * 0.55f) * cos(t * 0.31f)
        }
        val gazeShiftY = when {
            pointerNorm != null -> ((pointerNorm!!.y - 0.42f) * 0.012f).coerceIn(-0.008f, 0.008f)
            isThinking -> -0.006f
            else -> 0.003f * sin(t * 0.47f)
        }

        // Clignement ultra-fluide (toutes les ~3.6s, parfois double clignement !)
        val blinkPhase = t % 3.65f
        val blinkAmt = when {
            blinkPhase > 3.45f && blinkPhase <= 3.54f -> (blinkPhase - 3.45f) / 0.09f
            blinkPhase > 3.54f && blinkPhase <= 3.65f -> (3.65f - blinkPhase) / 0.11f
            else -> 0f
        }.coerceIn(0f, 1f)
        val leftBlink = if (petBlushBoost > 0.65f) max(blinkAmt, 0.85f) else blinkAmt
        val rightBlink = when {
            petBlushBoost > 0.65f -> max(blinkAmt, 0.85f)
            emotion == AvatarEmotion.TEASING -> max(blinkAmt, (0.5f + 0.5f * sin(t * 2.2f)).coerceIn(0f, 0.95f))
            else -> blinkAmt
        }

        // Lip-Sync Visème-Aware prononcé et fluide
        val idleBreathMouth = if (lipSyncOpen < 0.02f) (0.03f * (0.5f + 0.5f * sin(t * 2.1f))) else 0f
        val mouthOpen = ((lipSyncOpen * 1.05f * lipMult) + idleBreathMouth +
            if (emotion == AvatarEmotion.SURPRISED) 0.48f else 0f).coerceIn(0f, 1.25f)
        val mouthWide = (lipSyncWide * lipMult).coerceIn(-0.6f, 1f)

        val idleSmilePulse = 0.12f * sin(t * 0.7f)
        val smileAmt = when (emotion) {
            AvatarEmotion.HAPPY -> 1.05f + idleSmilePulse
            AvatarEmotion.LOVE -> 1.15f + idleSmilePulse
            AvatarEmotion.TEASING -> 0.85f
            AvatarEmotion.SHY -> 0.65f
            AvatarEmotion.SAD -> -0.60f
            AvatarEmotion.SURPRISED -> -0.15f
            AvatarEmotion.CALM -> 0.42f + idleSmilePulse
        } + petBlushBoost * 0.45f

        val browLift = when (emotion) {
            AvatarEmotion.SURPRISED -> 1.25f
            AvatarEmotion.HAPPY, AvatarEmotion.LOVE -> 0.42f + 0.15f * sin(t * 1.8f)
            AvatarEmotion.SAD -> 0.55f
            AvatarEmotion.TEASING -> 0.75f
            else -> 0.18f * sin(t * 1.1f) + (if (lipSyncOpen > 0.25f) 0.30f * lipSyncOpen else 0f)
        }

        computeLivePortraitMesh(
            outVerts = verts,
            cols = meshCols,
            rows = meshRows,
            left = baseLeft,
            top = baseTop,
            width = drawW,
            height = drawH,
            rig = rig,
            breathe = breathe,
            swayX = swayX,
            swayY = swayY,
            headTilt = headTilt,
            gazeX = gazeShiftX,
            gazeY = gazeShiftY,
            leftBlink = leftBlink,
            rightBlink = rightBlink,
            mouthOpen = mouthOpen,
            mouthWide = mouthWide,
            smileAmt = smileAmt,
            browLift = browLift
        )

        bitmapPaint.colorFilter = colorFilterForStyle(character.photoFilter)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.drawBitmapMesh(bmp, meshCols, meshRows, verts, 0, null, 0, bitmapPaint)

            // 1. Paupières réalistes échantillonnées sur la peau de la photo lors des clignements !
            val eyeSpecs = listOf(
                Triple(rig.leftEyeCenter, leftBlink, rig.leftEyelidSkinColor),
                Triple(rig.rightEyeCenter, rightBlink, rig.rightEyelidSkinColor)
            )
            for ((eyeCenter, blink, skinCol) in eyeSpecs) {
                if (blink > 0.08f) {
                    val ex = baseLeft + (eyeCenter.x + swayX) * drawW
                    val ey = baseTop + (eyeCenter.y + swayY) * drawH
                    val ew = rig.eyeRadius * drawW * 1.28f
                    val eh = rig.eyeRadius * drawH * 0.78f * blink

                    // Voile paupière teinté peau naturelle
                    lidPaint.shader = RadialGradient(
                        ex, ey - eh * 0.15f, max(ew, eh).coerceAtLeast(10f),
                        intArrayOf(
                            android.graphics.Color.argb((blink * 238).toInt().coerceIn(0, 240),
                                android.graphics.Color.red(skinCol),
                                android.graphics.Color.green(skinCol),
                                android.graphics.Color.blue(skinCol)),
                            android.graphics.Color.argb((blink * 175).toInt().coerceIn(0, 190),
                                android.graphics.Color.red(skinCol),
                                android.graphics.Color.green(skinCol),
                                android.graphics.Color.blue(skinCol)),
                            android.graphics.Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.72f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    nc.drawOval(RectF(ex - ew, ey - eh * 1.15f, ex + ew, ey + eh * 0.85f), lidPaint)

                    // Ligne de cils courbée réaliste
                    lashPaint.color = android.graphics.Color.argb((blink * 210).toInt().coerceIn(0, 220), 28, 18, 22)
                    lashPaint.strokeWidth = (rig.eyeRadius * drawW * 0.11f).coerceIn(2.5f, 6f)
                    val lashArc = RectF(ex - ew * 0.82f, ey - eh * 0.35f, ex + ew * 0.82f, ey + eh * 0.65f)
                    nc.drawArc(lashArc, 15f, 150f, false, lashPaint)
                }
            }

            // 2. Cavité buccale réaliste + lueur dents supérieures en Lip-Sync actif !
            if (mouthOpen > 0.06f) {
                val mx = baseLeft + (rig.mouthCenter.x + swayX) * drawW
                val my = baseTop + (rig.mouthCenter.y + swayY + mouthOpen * rig.eyeRadius * 0.28f) * drawH
                val baseMouthW = abs(rig.mouthRight.x - rig.mouthLeft.x) * drawW
                val mw = (baseMouthW * (0.44f + mouthWide * 0.12f)).coerceAtLeast(10f)
                val mh = (rig.eyeRadius * drawH * 0.66f * mouthOpen).coerceAtLeast(4f)

                mouthPaint.shader = RadialGradient(
                    mx, my + mh * 0.1f, max(mw, mh).coerceAtLeast(8f),
                    intArrayOf(
                        android.graphics.Color.argb((225 * mouthOpen.coerceAtMost(1f)).toInt(), 36, 10, 18),
                        android.graphics.Color.argb((185 * mouthOpen.coerceAtMost(1f)).toInt(), 118, 32, 48),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
                nc.drawOval(RectF(mx - mw, my - mh, mx + mw, my + mh), mouthPaint)

                // Légère lueur douce des dents supérieures quand la bouche s'ouvre bien
                if (mouthOpen > 0.22f) {
                    teethPaint.color = android.graphics.Color.argb((165 * (mouthOpen - 0.15f).coerceIn(0f, 1f)).toInt(), 245, 240, 238)
                    nc.drawRoundRect(
                        RectF(mx - mw * 0.62f, my - mh * 0.78f, mx + mw * 0.62f, my - mh * 0.25f),
                        mh * 0.35f, mh * 0.35f, teethPaint
                    )
                }
            }

            // 3. Rougissement tendre sur les pommettes
            val blushStrength = (when (emotion) {
                AvatarEmotion.LOVE -> 0.55f
                AvatarEmotion.SHY -> 0.64f
                AvatarEmotion.HAPPY -> 0.26f
                else -> 0.08f
            } + petBlushBoost * 0.38f).coerceIn(0f, 0.78f)

            if (blushStrength > 0.08f) {
                val r = rig.eyeRadius * drawW * 1.55f
                for (ck in listOf(rig.leftCheek, rig.rightCheek)) {
                    val cx = baseLeft + (ck.x + swayX) * drawW
                    val cy = baseTop + (ck.y + swayY) * drawH
                    blushPaint.shader = RadialGradient(
                        cx, cy, r.coerceAtLeast(12f),
                        intArrayOf(android.graphics.Color.argb((blushStrength * 145).toInt(), 255, 98, 148), android.graphics.Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    )
                    nc.drawCircle(cx, cy, r, blushPaint)
                }
            }
        }

        // Particules d'ambiance douces façon Mimico
        if (animCfg.showFloatingParticles) {
            val pColor = Color(character.backdrop.accentHex)
            for (i in 0 until 10) {
                val px = ((i * 37 % 92) / 92f) * w + sin(t * 0.9f + i) * 18f
                val py = ((i * 53 - t * 22f) % h + h) % h
                val alpha = (0.15f + 0.18f * abs(sin(t * 1.5f + i))).coerceIn(0f, 0.4f)
                drawCircle(pColor.copy(alpha = alpha), 3.5f + (i % 3) * 1.5f, Offset(px, py))
            }
        }

        // Vignette cinématique douce en haut et en bas pour la lisibilité du chat flottant Mimico
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x880B0712), Color.Transparent, Color.Transparent, Color(0xD80B0712)),
                startY = 0f,
                endY = h
            )
        )

        for (fx in hearts) {
            val alpha = (1f - fx.life / 1.35f).coerceIn(0f, 1f)
            drawFloatingHeart(Offset(fx.x * w, fx.y * h), fx.size, Color(0xFFFF7AB8).copy(alpha = alpha))
        }
    }
}

private fun computeLivePortraitMesh(
    outVerts: FloatArray,
    cols: Int,
    rows: Int,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    rig: FaceRigLandmarks,
    breathe: Float,
    swayX: Float,
    swayY: Float,
    headTilt: Float,
    gazeX: Float,
    gazeY: Float,
    leftBlink: Float,
    rightBlink: Float,
    mouthOpen: Float,
    mouthWide: Float,
    smileAmt: Float,
    browLift: Float
) {
    val pivotX = rig.noseTip.x
    val pivotY = rig.chinBottom.y
    val faceH = (rig.faceBounds.bottom - rig.faceBounds.top).coerceIn(0.22f, 0.72f)
    val eyeR = rig.eyeRadius
    val mouthW = abs(rig.mouthRight.x - rig.mouthLeft.x).coerceIn(0.08f, 0.30f)

    val cosT = cos(headTilt)
    val sinT = sin(headTilt)

    var idx = 0
    for (row in 0..rows) {
        val v = row.toFloat() / rows.toFloat()
        for (col in 0..cols) {
            val u = col.toFloat() / cols.toFloat()

            var du = 0f
            var dv = 0f

            // 1. Respiration naturelle du buste/épaules
            if (v > rig.chinBottom.y - 0.04f) {
                val torsoWeight = ((v - (rig.chinBottom.y - 0.04f)) / (1f - rig.chinBottom.y + 0.04f).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
                dv -= breathe * torsoWeight * 1.5f
                du += (u - 0.5f) * breathe * torsoWeight * 1.25f
            }

            // 2. Rotation & balancement 2.5D de la tête
            val headMask = (1f - ((v - rig.chinBottom.y) / 0.24f).coerceIn(0f, 1f))
            if (headMask > 0.01f) {
                val rx = u - pivotX
                val ry = v - pivotY
                val rotX = rx * cosT - ry * sinT + pivotX
                val rotY = rx * sinT + ry * cosT + pivotY
                du += (rotX - u) * headMask + swayX * headMask
                dv += (rotY - v) * headMask + swayY * headMask
            }

            // 3. Regard / micro-saccades d'iris + pincement paupières synchronisé
            for ((eyeCenter, blink) in listOf(rig.leftEyeCenter to leftBlink, rig.rightEyeCenter to rightBlink)) {
                val dx = (u - eyeCenter.x) / (eyeR * 1.35f)
                val dy = (v - eyeCenter.y) / (eyeR * 1.05f)
                val d2 = dx * dx + dy * dy
                if (d2 < 1.9f) {
                    val wEye = exp(-d2 * 1.9f)
                    if (blink > 0.01f) {
                        dv -= (v - eyeCenter.y) * blink * 0.78f * wEye
                    }
                    if (d2 < 0.75f && blink < 0.4f) {
                        val wIris = exp(-d2 * 4.5f)
                        du += gazeX * wIris
                        dv += gazeY * wIris
                    }
                }
            }

            // 4. Sourcils expressifs
            if (abs(browLift) > 0.02f) {
                for (brow in listOf(rig.leftBrowCenter, rig.rightBrowCenter)) {
                    val dx = (u - brow.x) / (eyeR * 1.55f)
                    val dy = (v - brow.y) / (eyeR * 1.15f)
                    val d2 = dx * dx + dy * dy
                    if (d2 < 2.2f) {
                        dv -= browLift * eyeR * 0.48f * exp(-d2 * 1.55f)
                    }
                }
            }

            // 5. Lip-Sync Visème-Aware prononcé : abaissement mâchoire + lèvre inférieure + forme voyelle
            if (mouthOpen > 0.01f) {
                val dx = (u - rig.mouthCenter.x) / (mouthW * 0.95f)
                val dy = (v - rig.lowerLip.y) / (faceH * 0.25f)
                val d2 = dx * dx + dy * dy
                if (d2 < 2.6f && v >= rig.upperLip.y - 0.018f) {
                    val wJaw = exp(-d2 * 1.35f)
                    dv += mouthOpen * faceH * 0.115f * wJaw
                }
                val dyUp = (v - rig.upperLip.y) / (faceH * 0.09f)
                val d2Up = dx * dx + dyUp * dyUp
                if (d2Up < 1.6f && v <= rig.mouthCenter.y) {
                    dv -= mouthOpen * faceH * 0.022f * exp(-d2Up * 1.9f)
                }
            }

            // 6. Sourire / Moue / Étirement voyelles sur les commissures & pommettes
            val cornerFactor = smileAmt + mouthWide * 0.45f
            if (abs(cornerFactor) > 0.02f) {
                for (corner in listOf(rig.mouthLeft, rig.mouthRight)) {
                    val dx = (u - corner.x) / (mouthW * 0.62f)
                    val dy = (v - corner.y) / (mouthW * 0.50f)
                    val d2 = dx * dx + dy * dy
                    if (d2 < 2.4f) {
                        val wCorner = exp(-d2 * 1.55f)
                        dv -= smileAmt * faceH * 0.036f * wCorner
                        val dirOut = if (corner.x < rig.mouthCenter.x) -1f else 1f
                        du += dirOut * cornerFactor * mouthW * 0.095f * wCorner
                    }
                }
            }

            outVerts[idx++] = left + (u + du) * width
            outVerts[idx++] = top + (v + dv) * height
        }
    }
}

private fun colorFilterForStyle(style: PhotoFilterStyle): ColorFilter? {
    val cm = ColorMatrix()
    return when (style) {
        PhotoFilterStyle.NATURAL -> null
        PhotoFilterStyle.SOFT_PORTRAIT -> {
            cm.set(floatArrayOf(1.04f, 0.02f, 0f, 0f, 10f, 0f, 1.03f, 0f, 0f, 8f, 0f, 0f, 1.02f, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.CINEMA_WARM -> {
            cm.set(floatArrayOf(1.10f, 0.03f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.88f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.SAKURA_GLOW -> {
            cm.set(floatArrayOf(1.10f, 0f, 0.04f, 0f, 12f, 0f, 1.00f, 0.03f, 0f, 6f, 0.04f, 0f, 1.08f, 0f, 12f, 0f, 0f, 0f, 1f, 0f))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.POLAROID -> {
            cm.setSaturation(0.82f)
            cm.postConcat(ColorMatrix(floatArrayOf(1.06f, 0.05f, 0f, 0f, 12f, 0f, 1.01f, 0.02f, 0f, 8f, 0f, 0.03f, 0.90f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.CYBER_GLOW -> {
            cm.setSaturation(1.30f)
            cm.postConcat(ColorMatrix(floatArrayOf(1.08f, 0f, 0.10f, 0f, 4f, 0f, 0.98f, 0.06f, 0f, 0f, 0.08f, 0.05f, 1.20f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.MOONLIGHT -> {
            cm.setSaturation(0.88f)
            cm.postConcat(ColorMatrix(floatArrayOf(0.92f, 0f, 0.04f, 0f, -2f, 0f, 0.98f, 0.04f, 0f, 2f, 0f, 0.04f, 1.14f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.WARM_VELVET -> {
            cm.set(floatArrayOf(1.12f, 0.04f, 0f, 0f, 6f, 0f, 0.96f, 0.02f, 0f, 2f, 0.04f, 0f, 0.98f, 0f, 6f, 0f, 0f, 0f, 1f, 0f))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.STUDIO_GLAM -> {
            cm.setSaturation(1.12f)
            cm.postConcat(ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1.06f, 0f, 0f, 6f, 0f, 0f, 1.06f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.ANIME_BLOOM -> {
            cm.setSaturation(1.26f)
            cm.postConcat(ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 14f, 0f, 1.06f, 0f, 0f, 12f, 0f, 0f, 1.10f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(cm)
        }
    }
}

internal fun DrawScope.drawAnimatedSceneBackdrop(backdrop: SceneBackdrop, t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(backdrop.topHex), Color(backdrop.bottomHex))))
    val accent = Color(backdrop.accentHex)
    when (backdrop) {
        SceneBackdrop.RAINY_CAFE -> {
            for (i in 0 until 28) {
                val x = ((i * 53 % 100) / 100f) * w
                val y = ((i * 37 + t * 250f) % h)
                drawLine(accent.copy(alpha = 0.28f), Offset(x, y), Offset(x - 6f, y + 28f), strokeWidth = 2f)
            }
        }
        SceneBackdrop.SNOW_CHALET -> {
            for (i in 0 until 26) {
                val x = (((i * 47 % 100) / 100f) * w + sin(t + i) * 18f)
                val y = ((i * 61 + t * 70f) % h)
                drawCircle(Color.White.copy(alpha = 0.45f), 2.5f + (i % 3), Offset(x, y))
            }
        }
        SceneBackdrop.SAKURA_GARDEN -> {
            for (i in 0 until 18) {
                val x = (((i * 67 + t * 38f) % w))
                val y = ((i * 53 + t * 52f) % h)
                drawOval(Color(0x88FFB7D5), Offset(x, y), androidx.compose.ui.geometry.Size(11f, 6f))
            }
        }
        SceneBackdrop.CYBER_CITY -> {
            for (i in 0 until 8) {
                val y = h * (0.15f + i * 0.11f)
                drawLine(accent.copy(alpha = 0.14f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0x44FF4FD8), Color.Transparent), Offset(w * 0.8f, h * 0.22f), w * 0.5f), w * 0.5f, Offset(w * 0.8f, h * 0.22f))
        }
        else -> {
            for (i in 0 until 18) {
                val px = ((i * 67 % 95) / 95f) * w + sin(t * 0.8f + i) * 14f
                val py = ((i * 43 % 80) / 100f) * h + cos(t * 0.6f + i) * 10f
                val r = 10f + (i % 5) * 9f
                val a = 0.08f + 0.09f * abs(sin(t * 1.4f + i))
                drawCircle(accent.copy(alpha = a), r, Offset(px, py))
            }
        }
    }
}

internal fun DrawScope.drawFloatingHeart(center: Offset, size: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y + size * 0.7f)
        cubicTo(center.x - size * 1.2f, center.y - size * 0.1f, center.x - size * 0.6f, center.y - size * 1.1f, center.x, center.y - size * 0.35f)
        cubicTo(center.x + size * 0.6f, center.y - size * 1.1f, center.x + size * 1.2f, center.y - size * 0.1f, center.x, center.y + size * 0.7f)
        close()
    }
    drawPath(path, color)
}
