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

private data class HeartFx(
    var x: Float,
    var y: Float,
    var vy: Float,
    var vx: Float,
    var life: Float,
    var size: Float
)

/**
 * Animateur temps réel 60 FPS d'une PHOTO importée par l'utilisateur, piloté par
 * les 478 repères MediaPipe Face Landmarker + maillage déformable matériel (drawBitmapMesh).
 */
@Composable
fun PhotoFaceAnimatorCanvas(
    analyzedPhoto: AnalyzedPhotoAvatar,
    character: CompanionCharacter,
    emotion: AvatarEmotion,
    lipSyncLevel: Float,
    isListening: Boolean,
    onPetAvatar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var t by remember { mutableFloatStateOf(0f) }
    var pointerNorm by remember { mutableStateOf<Offset?>(null) }
    var lastPetMs by remember { mutableLongStateOf(0L) }
    var petBlushBoost by remember { mutableFloatStateOf(0f) }
    val hearts = remember { mutableStateListOf<HeartFx>() }

    // Grille 24x24 pré-allouée zéro GC dans la boucle 60 FPS
    val meshCols = 24
    val meshRows = 24
    val verts = remember { FloatArray((meshCols + 1) * (meshRows + 1) * 2) }
    val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val mouthCavityPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val blushPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    LaunchedEffect(Unit) {
        var lastNs = System.nanoTime()
        while (true) {
            delay(16L)
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            lastNs = now
            t += dt
            petBlushBoost = (petBlushBoost - dt * 0.45f).coerceAtLeast(0f)

            val iter = hearts.listIterator()
            while (iter.hasNext()) {
                val fx = iter.next()
                fx.life += dt
                if (fx.life > 1.3f) {
                    iter.remove()
                } else {
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
                    petBlushBoost = 0.85f
                    hearts += HeartFx(nx, ny, -0.22f, (Random.nextFloat() - 0.5f) * 0.08f, 0f, 18f)
                    onPetAvatar()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { pointerNorm = null }) { change, _ ->
                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    pointerNorm = Offset(nx, ny)
                    val now = System.currentTimeMillis()
                    if (now - lastPetMs > 550L) {
                        lastPetMs = now
                        petBlushBoost = 1.0f
                        hearts += HeartFx(nx, ny, -0.20f, (Random.nextFloat() - 0.5f) * 0.1f, 0f, 20f)
                        onPetAvatar()
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // 1. Arrière-plan animé si la segmentation IA remplace le fond (ou aura derrière la photo)
        drawAnimatedSceneBackdrop(character.backdrop, t)

        val rig = analyzedPhoto.rig
        val bmp = if (character.replacePhotoBackground && analyzedPhoto.cutoutBitmap != null) {
            analyzedPhoto.cutoutBitmap
        } else {
            analyzedPhoto.sourceBitmap
        }

        // Calcul du rectangle de dessin (center-crop doux avec marge pour respiration & mouvements)
        val scale = max(w / bmp.width.toFloat(), h / bmp.height.toFloat()) * 1.04f
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val baseLeft = (w - drawW) * 0.5f
        val baseTop = (h - drawH) * 0.48f

        // Paramètres d'animation vivante
        val breathe = sin(t * 2.1f) * 0.008f
        val swayX = sin(t * 0.95f) * 0.009f + (pointerNorm?.let { (it.x - 0.5f) * 0.022f } ?: 0f)
        val swayY = cos(t * 1.3f) * 0.006f + (pointerNorm?.let { (it.y - 0.45f) * 0.015f } ?: 0f)
        val headTilt = sin(t * 0.72f) * 0.018f +
            (if (emotion == AvatarEmotion.SHY) 0.035f else 0f) +
            (if (isListening) -0.024f else 0f)

        // Clignement réaliste (toutes les ~3.9s)
        val blinkCycle = t % 3.9f
        val blinkAmt = when {
            blinkCycle > 3.74f && blinkCycle <= 3.82f -> (blinkCycle - 3.74f) / 0.08f
            blinkCycle > 3.82f -> (3.90f - blinkCycle) / 0.08f
            else -> 0f
        }.coerceIn(0f, 1f)
        val leftEyeBlink = blinkAmt
        val rightEyeBlink = if (emotion == AvatarEmotion.TEASING) max(blinkAmt, 0.85f * (0.5f + 0.5f * sin(t * 2.5f))) else blinkAmt

        // Ouverture buccale pilotée en temps réel par la voix TTS + émotions
        val mouthOpen = (lipSyncLevel * 0.95f +
            if (emotion == AvatarEmotion.SURPRISED) 0.35f else 0f).coerceIn(0f, 1f)
        val smileAmt = when (emotion) {
            AvatarEmotion.HAPPY -> 0.85f
            AvatarEmotion.LOVE -> 0.95f
            AvatarEmotion.TEASING -> 0.65f
            AvatarEmotion.SHY -> 0.45f
            AvatarEmotion.SAD -> -0.50f
            AvatarEmotion.SURPRISED -> -0.10f
            AvatarEmotion.CALM -> 0.25f
        }
        val browLift = when (emotion) {
            AvatarEmotion.SURPRISED -> 1.0f
            AvatarEmotion.HAPPY, AvatarEmotion.LOVE -> 0.30f
            AvatarEmotion.SAD -> 0.45f
            AvatarEmotion.TEASING -> 0.55f
            else -> 0f
        }

        // Construction du maillage déformé 24x24 à partir des 478 repères MediaPipe
        computeDeformedFaceMesh(
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
            leftBlink = leftEyeBlink,
            rightBlink = rightEyeBlink,
            mouthOpen = mouthOpen,
            smileAmt = smileAmt,
            browLift = browLift
        )

        // Filtre de style photo
        bitmapPaint.colorFilter = colorFilterForStyle(character.photoFilter)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.drawBitmapMesh(bmp, meshCols, meshRows, verts, 0, null, 0, bitmapPaint)

            // Cavité buccale ombrée réaliste quand la bouche s'ouvre en parlant
            if (mouthOpen > 0.08f) {
                val mx = baseLeft + (rig.mouthCenter.x + swayX) * drawW
                val my = baseTop + (rig.mouthCenter.y + swayY + mouthOpen * rig.eyeRadius * 0.22f) * drawH
                val mw = abs(rig.mouthRight.x - rig.mouthLeft.x) * drawW * 0.42f
                val mh = (rig.eyeRadius * drawH * 0.52f * mouthOpen).coerceAtLeast(3f)

                mouthCavityPaint.shader = RadialGradient(
                    mx, my, max(mw, mh).coerceAtLeast(6f),
                    intArrayOf(android.graphics.Color.argb((190 * mouthOpen).toInt().coerceIn(0, 210), 42, 14, 22),
                        android.graphics.Color.argb((130 * mouthOpen).toInt().coerceIn(0, 150), 110, 32, 48),
                        android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                nc.drawOval(RectF(mx - mw, my - mh, mx + mw, my + mh), mouthCavityPaint)
            }

            // Rougissement tendre sur les pommettes (Love / Shy / Caresse)
            val blushStrength = (when (emotion) {
                AvatarEmotion.LOVE -> 0.48f
                AvatarEmotion.SHY -> 0.58f
                AvatarEmotion.HAPPY -> 0.22f
                else -> 0.05f
            } + petBlushBoost * 0.35f).coerceIn(0f, 0.72f)

            if (blushStrength > 0.08f) {
                val r = rig.eyeRadius * drawW * 1.45f
                val cheeks = listOf(rig.leftCheek, rig.rightCheek)
                for (ck in cheeks) {
                    val cx = baseLeft + (ck.x + swayX) * drawW
                    val cy = baseTop + (ck.y + swayY) * drawH
                    blushPaint.shader = RadialGradient(
                        cx, cy, r.coerceAtLeast(12f),
                        intArrayOf(
                            android.graphics.Color.argb((blushStrength * 145).toInt(), 255, 105, 148),
                            android.graphics.Color.TRANSPARENT
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    nc.drawCircle(cx, cy, r, blushPaint)
                }
            }
        }

        // Vignette atmosphérique douce
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    Color(0xBB0D0814)
                ),
                startY = h * 0.55f,
                endY = h
            )
        )

        // Particules de cœurs lors des caresses
        for (fx in hearts) {
            val alpha = (1f - fx.life / 1.3f).coerceIn(0f, 1f)
            drawFloatingHeart(Offset(fx.x * w, fx.y * h), fx.size, Color(0xFFFF7AB8).copy(alpha = alpha))
        }
    }
}

private fun computeDeformedFaceMesh(
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
    leftBlink: Float,
    rightBlink: Float,
    mouthOpen: Float,
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

            // 1. Respiration subtile des épaules et du buste (partie basse)
            if (v > rig.chinBottom.y) {
                val torsoWeight = ((v - rig.chinBottom.y) / (1f - rig.chinBottom.y).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
                dv -= breathe * torsoWeight * 1.4f
                du += (u - 0.5f) * breathe * torsoWeight * 1.1f
            }

            // 2. Inclinaison douce + balancement de la tête (pivot sous le menton)
            val headMask = (1f - ((v - rig.chinBottom.y) / 0.25f).coerceIn(0f, 1f))
            if (headMask > 0.01f) {
                val rx = u - pivotX
                val ry = v - pivotY
                val rotX = rx * cosT - ry * sinT + pivotX
                val rotY = rx * sinT + ry * cosT + pivotY
                du += (rotX - u) * headMask + swayX * headMask
                dv += (rotY - v) * headMask + swayY * headMask
            }

            // 3. Clignement réaliste des paupières autour des 2 yeux détectés
            if (leftBlink > 0.01f) {
                val dx = (u - rig.leftEyeCenter.x) / (eyeR * 1.35f)
                val dy = (v - rig.leftEyeCenter.y) / (eyeR * 1.05f)
                val d2 = dx * dx + dy * dy
                if (d2 < 1.8f) {
                    val wEye = exp(-d2 * 1.9f)
                    // Rapproche les paupières vers le centre vertical de l'œil
                    dv -= (v - rig.leftEyeCenter.y) * leftBlink * 0.72f * wEye
                }
            }
            if (rightBlink > 0.01f) {
                val dx = (u - rig.rightEyeCenter.x) / (eyeR * 1.35f)
                val dy = (v - rig.rightEyeCenter.y) / (eyeR * 1.05f)
                val d2 = dx * dx + dy * dy
                if (d2 < 1.8f) {
                    val wEye = exp(-d2 * 1.9f)
                    dv -= (v - rig.rightEyeCenter.y) * rightBlink * 0.72f * wEye
                }
            }

            // 4. Sourcils (surprise, joie, taquinerie, empathie)
            if (abs(browLift) > 0.02f) {
                for (brow in listOf(rig.leftBrowCenter, rig.rightBrowCenter)) {
                    val dx = (u - brow.x) / (eyeR * 1.5f)
                    val dy = (v - brow.y) / (eyeR * 1.1f)
                    val d2 = dx * dx + dy * dy
                    if (d2 < 2.2f) {
                        dv -= browLift * eyeR * 0.38f * exp(-d2 * 1.6f)
                    }
                }
            }

            // 5. Lip-Sync temps réel : ouverture mâchoire & lèvre inférieure
            if (mouthOpen > 0.01f) {
                val dx = (u - rig.mouthCenter.x) / (mouthW * 0.92f)
                val dy = (v - rig.lowerLip.y) / (faceH * 0.24f)
                val d2 = dx * dx + dy * dy
                if (d2 < 2.4f && v >= rig.upperLip.y - 0.015f) {
                    val wJaw = exp(-d2 * 1.45f)
                    dv += mouthOpen * faceH * 0.075f * wJaw
                }
                // Légère élévation de la lèvre supérieure
                val dyUp = (v - rig.upperLip.y) / (faceH * 0.09f)
                val d2Up = dx * dx + dyUp * dyUp
                if (d2Up < 1.5f && v <= rig.mouthCenter.y) {
                    dv -= mouthOpen * faceH * 0.016f * exp(-d2Up * 2.0f)
                }
            }

            // 6. Sourire / Moue sur les commissures des lèvres
            if (abs(smileAmt) > 0.02f) {
                for (corner in listOf(rig.mouthLeft, rig.mouthRight)) {
                    val dx = (u - corner.x) / (mouthW * 0.55f)
                    val dy = (v - corner.y) / (mouthW * 0.45f)
                    val d2 = dx * dx + dy * dy
                    if (d2 < 2.2f) {
                        val wSmile = exp(-d2 * 1.7f)
                        dv -= smileAmt * faceH * 0.026f * wSmile
                        val dirOut = if (corner.x < rig.mouthCenter.x) -1f else 1f
                        du += dirOut * smileAmt * mouthW * 0.06f * wSmile
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
        PhotoFilterStyle.CINEMA_WARM -> {
            cm.set(
                floatArrayOf(
                    1.08f, 0.03f, 0f, 0f, 8f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.90f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.SOFT_ANIME -> {
            cm.setSaturation(1.22f)
            val bright = ColorMatrix(
                floatArrayOf(
                    1.06f, 0f, 0f, 0f, 10f,
                    0f, 1.05f, 0f, 0f, 8f,
                    0f, 0f, 1.08f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(bright)
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.POLAROID -> {
            cm.setSaturation(0.84f)
            val tint = ColorMatrix(
                floatArrayOf(
                    1.06f, 0.05f, 0f, 0f, 12f,
                    0f, 1.01f, 0.02f, 0f, 8f,
                    0f, 0.03f, 0.92f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(tint)
            ColorMatrixColorFilter(cm)
        }
        PhotoFilterStyle.CYBER_GLOW -> {
            cm.setSaturation(1.28f)
            val neon = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0.08f, 0f, 4f,
                    0f, 0.98f, 0.06f, 0f, 0f,
                    0.06f, 0.04f, 1.18f, 0f, 14f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(neon)
            ColorMatrixColorFilter(cm)
        }
    }
}

internal fun DrawScope.drawAnimatedSceneBackdrop(backdrop: SceneBackdrop, t: Float) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(backdrop.topHex), Color(backdrop.bottomHex)))
    )
    val accent = Color(backdrop.accentHex)
    when (backdrop) {
        SceneBackdrop.RAINY_CAFE -> {
            for (i in 0 until 24) {
                val x = ((i * 53 % 100) / 100f) * w
                val y = ((i * 37 + t * 240f) % h)
                drawLine(accent.copy(alpha = 0.25f), Offset(x, y), Offset(x - 6f, y + 26f), strokeWidth = 2f)
            }
        }
        SceneBackdrop.CYBER_CITY -> {
            for (i in 0 until 8) {
                val y = h * (0.15f + i * 0.11f)
                drawLine(accent.copy(alpha = 0.14f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x44FF4FD8), Color.Transparent), Offset(w * 0.8f, h * 0.22f), w * 0.5f),
                radius = w * 0.5f,
                center = Offset(w * 0.8f, h * 0.22f)
            )
        }
        SceneBackdrop.STARRY_GARDEN, SceneBackdrop.COZY_ROOM, SceneBackdrop.GOLDEN_SUNSET, SceneBackdrop.MINIMAL_STUDIO -> {
            for (i in 0 until 16) {
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
        cubicTo(
            center.x - size * 1.2f, center.y - size * 0.1f,
            center.x - size * 0.6f, center.y - size * 1.1f,
            center.x, center.y - size * 0.35f
        )
        cubicTo(
            center.x + size * 0.6f, center.y - size * 1.1f,
            center.x + size * 1.2f, center.y - size * 0.1f,
            center.x, center.y + size * 0.7f
        )
        close()
    }
    drawPath(path, color)
}
