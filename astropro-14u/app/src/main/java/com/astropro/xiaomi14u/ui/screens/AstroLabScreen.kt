package com.astropro.xiaomi14u.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.camera.AstroSessionItem
import com.astropro.xiaomi14u.camera.HistogramData
import com.astropro.xiaomi14u.nativeengine.LabParameters
import com.astropro.xiaomi14u.nativeengine.LabPreset
import com.astropro.xiaomi14u.ui.components.LiveRgbHistogram
import com.astropro.xiaomi14u.ui.components.nightVisionColorFilter
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import java.io.File
import java.util.Locale

/**
 * Real-Time 16-Bit Astro Development Lab & Session Gallery.
 *
 * Non-destructive float32 pipeline: 1-Tap Astro Presets, Auto-STF (PixInsight MTF), Lupton Arcsinh,
 * 2D Quadratic Polynomial Background Gradient Removal, Background Neutralization,
 * SCNR Anti-Green, 3-Scale À Trous Wavelets, Bilateral Denoise, and 1-tap TIFF 16b / FITS 32b / JPEG HD export.
 */
@Composable
fun AstroLabScreen(
    sessions: List<AstroSessionItem>,
    activeSession: AstroSessionItem?,
    previewBitmap: Bitmap?,
    histogram: HistogramData,
    params: LabParameters,
    activePreset: LabPreset?,
    showOriginal: Boolean,
    isBusy: Boolean,
    statusBanner: String?,
    onSelectSession: (AstroSessionItem) -> Unit,
    onDeleteSession: (AstroSessionItem) -> Unit,
    onApplyPreset: (LabPreset) -> Unit,
    onUpdateParams: ((LabParameters) -> LabParameters) -> Unit,
    onToggleOriginal: (Boolean) -> Unit,
    onExportTiff16: (Boolean) -> Unit,
    onExportFits32: (Boolean) -> Unit,
    onExportJpegHd: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalAstroColors.current
    var activeTab by remember { mutableIntStateOf(0) } // 0=STF, 1=Étirement, 2=Ciel/Couleur, 3=Détails
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Retour",
                    tint = colors.textPrimary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onBack)
                        .padding(2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "LABO ASTRO 16-BIT",
                        style = tabularStyle(size = 13.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                    )
                    if (activeSession != null) {
                        Text(
                            text = "${activeSession.title} · ${activeSession.lensName} · ISO ${activeSession.iso}",
                            style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeSession != null) {
                    Text(
                        text = if (showOriginal) "AVANT" else "APRÈS",
                        style = tabularStyle(
                            size = 10.5.sp,
                            weight = FontWeight.Bold,
                            color = if (showOriginal) colors.accent else colors.textPrimary
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.75.dp, if (showOriginal) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
                            .clickable { onToggleOriginal(!showOriginal) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "Réinitialiser",
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                zoom = 1f
                                pan = Offset.Zero
                                onUpdateParams { LabParameters(autoStf = true, bgNeutralize = true) }
                            }
                    )
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Supprimer",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onDeleteSession(activeSession) }
                    )
                }
            }
        }

        HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

        // Interactive zoom/pan 16-bit preview viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, panDelta, zoomDelta, _ ->
                        val nextZoom = (zoom * zoomDelta).coerceIn(1f, 6f)
                        zoom = nextZoom
                        pan = if (nextZoom <= 1.01f) {
                            Offset.Zero
                        } else {
                            Offset(pan.x + panDelta.x, pan.y + panDelta.y)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Aperçu 16-bit développé",
                    contentScale = ContentScale.Fit,
                    colorFilter = nightVisionColorFilter(colors.isNightVision),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                )
                LiveRgbHistogram(
                    data = histogram,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (sessions.isEmpty()) "Aucune capture 16-bit enregistrée" else "Chargement de la session...",
                        style = tabularStyle(size = 13.sp, color = colors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Capturez en Astro Pro, Ciel Profond, Lune ou Star Trails pour développer ici.",
                        style = tabularStyle(size = 10.5.sp, color = colors.textMuted)
                    )
                }
            }

            if (isBusy) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.panelTranslucent)
                        .border(0.75.dp, colors.accent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Traitement float32 en cours...",
                        style = tabularStyle(size = 11.sp, color = colors.accent)
                    )
                }
            }

            if (statusBanner != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.panelTranslucent)
                        .border(0.75.dp, colors.accent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = statusBanner,
                        style = tabularStyle(size = 10.5.sp, color = colors.textPrimary)
                    )
                }
            }
        }

        // Session gallery filmstrip switcher
        if (sessions.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sessions.forEach { sess ->
                    val selected = sess.id == activeSession?.id
                    val thumb = remember(sess.thumbPath) {
                        sess.thumbPath.takeIf { it.isNotEmpty() && File(it).exists() }
                            ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                width = if (selected) 1.dp else 0.75.dp,
                                color = if (selected) colors.accent else colors.borderSubtle,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .background(colors.background)
                            .clickable { onSelectSession(sess) }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                colorFilter = nightVisionColorFilter(colors.isNightVision),
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column(modifier = Modifier.padding(end = 6.dp)) {
                            Text(
                                text = sess.modeName,
                                style = tabularStyle(size = 9.5.sp, weight = FontWeight.Bold, color = if (selected) colors.accent else colors.textPrimary)
                            )
                            Text(
                                text = "${sess.stackCount}× · ${sess.lensName}",
                                style = tabularStyle(size = 9.sp, color = colors.textSecondary)
                            )
                        }
                    }
                }
            }
        }

        // 1-Tap Astro Development Presets + Tool tabs
        if (activeSession != null) {
            HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

            // Single-row horizontal preset strip: Auto-STF · Voie Lactée · Nébuleuse · Lune Ciselée · Ciel Urbain · Réinitialiser
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabPreset.entries.forEach { preset ->
                    val selected = activePreset == preset
                    val isReset = preset == LabPreset.RESET
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    selected -> colors.accent.copy(alpha = 0.16f)
                                    else -> colors.surface
                                }
                            )
                            .border(
                                width = 0.75.dp,
                                color = when {
                                    selected -> colors.accent
                                    isReset -> colors.borderSubtle
                                    else -> colors.borderSubtle
                                },
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                if (isReset) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                                onApplyPreset(preset)
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.label,
                            style = tabularStyle(
                                size = 10.sp,
                                weight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    selected -> colors.accent
                                    isReset -> colors.textSecondary
                                    else -> colors.textPrimary
                                }
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.borderSubtle, thickness = 0.5.dp)

            val tabs = listOf("AUTO-STF", "ÉTIREMENT", "CIEL & COULEUR", "DÉTAILS")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                tabs.forEachIndexed { idx, label ->
                    val active = idx == activeTab
                    Column(
                        modifier = Modifier
                            .clickable { activeTab = idx }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = label,
                            style = tabularStyle(
                                size = 10.5.sp,
                                weight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) colors.accent else colors.textSecondary
                            )
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .width(if (active) 24.dp else 0.dp)
                                .height(2.dp)
                                .background(colors.accent)
                        )
                    }
                }
            }

            // Tool controls panel (scrollable compact area)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .background(colors.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LabToggleButton(
                                label = "Auto-STF (PixInsight MTF)",
                                active = params.autoStf,
                                onClick = { onUpdateParams { it.copy(autoStf = !it.autoStf) } },
                                modifier = Modifier.weight(1f)
                            )
                            LabToggleButton(
                                label = "Neutraliser Fond",
                                active = params.bgNeutralize,
                                onClick = { onUpdateParams { it.copy(bgNeutralize = !it.bgNeutralize) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LabSliderRow(
                            label = "Extraction Gradient 2D (Pollution)",
                            value = params.bgRemovalStrength,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(bgRemovalStrength = v) } }
                        LabSliderRow(
                            label = "SCNR Anti-Vert (Bruit Chromatique)",
                            value = params.scnrStrength,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(scnrStrength = v) } }
                    }

                    1 -> {
                        LabSliderRow(
                            label = "Lupton Arcsinh β (Couleurs Étoiles)",
                            value = params.asinhBeta,
                            range = 0f..140f,
                            fmt = { String.format(Locale.US, "%.0f", it) }
                        ) { v -> onUpdateParams { it.copy(asinhBeta = v) } }
                        LabSliderRow(
                            label = "Tons Moyens (MTF)",
                            value = params.midtones,
                            range = 0.02f..0.95f,
                            fmt = { String.format(Locale.US, "%.2f", it) }
                        ) { v -> onUpdateParams { it.copy(autoStf = false, midtones = v) } }
                        LabSliderRow(
                            label = "Point Noir",
                            value = params.blackPoint,
                            range = 0f..0.45f,
                            fmt = { String.format(Locale.US, "%.3f", it) }
                        ) { v -> onUpdateParams { it.copy(autoStf = false, blackPoint = v) } }
                        LabSliderRow(
                            label = "Point Blanc",
                            value = params.whitePoint,
                            range = 0.50f..1.0f,
                            fmt = { String.format(Locale.US, "%.2f", it) }
                        ) { v -> onUpdateParams { it.copy(autoStf = false, whitePoint = v) } }
                    }

                    2 -> {
                        LabSliderRow(
                            label = "Extraction Gradient 2D",
                            value = params.bgRemovalStrength,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(bgRemovalStrength = v) } }
                        LabSliderRow(
                            label = "SCNR Anti-Vert",
                            value = params.scnrStrength,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(scnrStrength = v) } }
                        LabSliderRow(
                            label = "Saturation",
                            value = params.saturation,
                            range = 0f..2.0f,
                            fmt = { String.format(Locale.US, "%.2f×", it) }
                        ) { v -> onUpdateParams { it.copy(saturation = v) } }
                        LabSliderRow(
                            label = "Température",
                            value = params.temperature,
                            range = -1f..1f,
                            fmt = { String.format(Locale.US, "%+.2f", it) }
                        ) { v -> onUpdateParams { it.copy(temperature = v) } }
                        LabSliderRow(
                            label = "Teinte",
                            value = params.tint,
                            range = -1f..1f,
                            fmt = { String.format(Locale.US, "%+.2f", it) }
                        ) { v -> onUpdateParams { it.copy(tint = v) } }
                    }

                    else -> {
                        LabSliderRow(
                            label = "Déconvolution R-L (Netteté Étoiles & Lune)",
                            value = params.deconvolution,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(deconvolution = v) } }
                        LabSliderRow(
                            label = "Anti-Aberration Chrom.",
                            value = params.chromaticAberrationFix,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(chromaticAberrationFix = v) } }
                        LabSliderRow(
                            label = "Ondelettes À Trous · Fin",
                            value = params.waveletFine,
                            range = -0.5f..2.5f,
                            fmt = { String.format(Locale.US, "%+.2f", it) }
                        ) { v -> onUpdateParams { it.copy(waveletFine = v) } }
                        LabSliderRow(
                            label = "Ondelettes À Trous · Moyen",
                            value = params.waveletMedium,
                            range = -0.5f..2.5f,
                            fmt = { String.format(Locale.US, "%+.2f", it) }
                        ) { v -> onUpdateParams { it.copy(waveletMedium = v) } }
                        LabSliderRow(
                            label = "Ondelettes À Trous · Gros",
                            value = params.waveletCoarse,
                            range = -0.5f..2.0f,
                            fmt = { String.format(Locale.US, "%+.2f", it) }
                        ) { v -> onUpdateParams { it.copy(waveletCoarse = v) } }
                        LabSliderRow(
                            label = "Débruitage Bilatéral (Étoiles protégées)",
                            value = params.denoiseStrength,
                            range = 0f..1f,
                            fmt = { "${(it * 100).toInt()}%" }
                        ) { v -> onUpdateParams { it.copy(denoiseStrength = v) } }
                    }
                }
            }

            // 1-Tap Export Bar: TIFF 16b | FITS 32b | JPEG HD | Partager
            HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportActionButton("TIFF 16b", highlighted = true, onClick = { onExportTiff16(false) }, modifier = Modifier.weight(1f))
                ExportActionButton("FITS 32b", highlighted = true, onClick = { onExportFits32(false) }, modifier = Modifier.weight(1f))
                ExportActionButton("JPEG HD", highlighted = false, onClick = { onExportJpegHd(false) }, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surface)
                        .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(4.dp))
                        .clickable { onExportTiff16(true) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Partager TIFF 16b",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LabToggleButton(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAstroColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) colors.accent.copy(alpha = 0.16f) else colors.background)
            .border(0.75.dp, if (active) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = if (active) colors.accent else colors.textSecondary)
        )
    }
}

@Composable
private fun LabSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fmt: (Float) -> String,
    onChange: (Float) -> Unit
) {
    val colors = LocalAstroColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = tabularStyle(size = 10.5.sp, color = colors.textSecondary))
            Text(text = fmt(value), style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.accent))
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.borderSubtle
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
private fun ExportActionButton(label: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAstroColors.current
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlighted) colors.accent.copy(alpha = 0.14f) else colors.surface)
            .border(0.75.dp, if (highlighted) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = tabularStyle(size = 11.sp, weight = FontWeight.Bold, color = if (highlighted) colors.accent else colors.textPrimary)
        )
    }
}
