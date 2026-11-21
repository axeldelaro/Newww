package com.astropro.xiaomi14u.ui.screens

import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.astropro.xiaomi14u.AstroAppUiState
import com.astropro.xiaomi14u.camera.AppScreen
import com.astropro.xiaomi14u.camera.CalibrationKind
import com.astropro.xiaomi14u.camera.CaptureMode
import com.astropro.xiaomi14u.camera.CometTailLength
import com.astropro.xiaomi14u.camera.LensInfo
import com.astropro.xiaomi14u.camera.LevelOrientationState
import com.astropro.xiaomi14u.camera.ManualSettings
import com.astropro.xiaomi14u.camera.ModeOptions
import com.astropro.xiaomi14u.camera.NpfCalculator
import com.astropro.xiaomi14u.camera.ProDialType
import com.astropro.xiaomi14u.camera.StarTrailStyle
import com.astropro.xiaomi14u.ephemeris.ArSkyProjector
import com.astropro.xiaomi14u.ui.components.ArSkyOverlay
import com.astropro.xiaomi14u.ui.components.AutoFocusVCurvePill
import com.astropro.xiaomi14u.ui.components.FocusPeakingOverlay
import com.astropro.xiaomi14u.ui.components.GridAndBubbleLevelOverlay
import com.astropro.xiaomi14u.ui.components.LiveCaptureHudOverlay
import com.astropro.xiaomi14u.ui.components.LiveRgbHistogram
import com.astropro.xiaomi14u.ui.components.ModeGuideSheet
import com.astropro.xiaomi14u.ui.components.StarFocusLoupeOverlay
import com.astropro.xiaomi14u.ui.components.XiaomiLensSelectorPill
import com.astropro.xiaomi14u.ui.components.XiaomiProShutterButton
import com.astropro.xiaomi14u.ui.components.XiaomiProTickDial
import com.astropro.xiaomi14u.ui.components.nightVisionColorFilter
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Main Camera Viewfinder faithfully modeled after Xiaomi HyperOS Leica Camera Pro:
 *   1. 48dp Pure OLED Black Top Icon Bar (Vision Nocturne · AR · Format · Timer · NPF · Grille · Menu)
 *   2. 4:3 Viewfinder (telemetry, live RGB histogram, AR Sky Overlay, 2-axis bubble level, focus peaking, 10x FWHM loupe, V-Curve AF pill, floating lens pill)
 *   3. Mode-specific compact options strip + 1-tap Mode Guide button
 *   4. Slide-up Xiaomi Horizontal Precision Tick-Wheel Dial
 *   5. 6 Pro Manual Tiles (WB · F · S · ISO · f/ · EV)
 *   6. Bottom Shutter Deck (Mode strip with gold underline + [Labo Thumb] [Shutter] [Éphémérides])
 */
@Composable
fun CameraScreen(
    state: AstroAppUiState,
    orientation: LevelOrientationState,
    onAttachTextureView: (TextureView) -> Unit,
    onRequestPermission: () -> Unit,
    onToggleNightVision: () -> Unit,
    onToggleArSky: () -> Unit,
    onCycleFormat: () -> Unit,
    onCycleTimer: () -> Unit,
    onSnapNpf: () -> Unit,
    onToggleGridLevel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleVibrationGuard: () -> Unit,
    onStartStarAutoFocus: () -> Unit,
    onCancelStarAutoFocus: () -> Unit,
    onSnapCalibratedInfinity: () -> Unit,
    onToggleTopMenu: () -> Unit,
    onOpenModeGuide: () -> Unit,
    onDismissModeGuide: () -> Unit,
    onApplyModeGuidePreset: (CaptureMode) -> Unit,
    onToggleDimDuringStack: () -> Unit,
    onToggleFrontRear: () -> Unit,
    onSelectLens: (LensInfo) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onToggleDial: (ProDialType) -> Unit,
    onDismissDial: () -> Unit,
    onUpdateManual: ((ManualSettings) -> ManualSettings) -> Unit,
    onUpdateModeOptions: ((ModeOptions) -> ModeOptions) -> Unit,
    onToggleFocusLoupe: (Float?, Float?) -> Unit,
    onShutterPressed: () -> Unit,
    onFinishCaptureEarly: () -> Unit,
    onAbortCapture: () -> Unit,
    onNavigate: (AppScreen) -> Unit
) {
    val colors = LocalAstroColors.current
    val lens = state.activeLens
    val manual = state.manualSettings

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // =================================================================
            // 1. TOP ICON BAR (48dp pure OLED black, thin crisp HyperOS icons)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(colors.background)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1-Tap Vision Nocturne (OLED Red Mode)
                TopBarIconItem(
                    active = state.nightVision,
                    onClick = onToggleNightVision
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RemoveRedEye,
                        contentDescription = "Vision Nocturne",
                        tint = if (state.nightVision) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 1-Tap Augmented Reality Sky Overlay (OFF by default)
                TopBarToggleBadge(
                    text = "AR",
                    active = state.arSkyOverlayEnabled,
                    onClick = onToggleArSky
                )

                // Output Format (DNG 16b / RAW+JPG)
                TopBarTextBadge(
                    text = state.outputFormat.label,
                    active = true,
                    onClick = onCycleFormat
                )

                // Timer (OFF / 2s / 5s / 10s)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onCycleTimer)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = "Retardateur",
                        tint = if (state.timerOption.seconds > 0) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    if (state.timerOption.seconds > 0) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = state.timerOption.label,
                            style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.accent)
                        )
                    }
                }

                // 1-Tap NPF Auto-Shutter Snap
                TopBarTextBadge(
                    text = "NPF",
                    active = false,
                    onClick = onSnapNpf
                )

                // Grid + 2-Axis Bubble Level Toggle
                TopBarIconItem(
                    active = state.showGridLevel,
                    onClick = onToggleGridLevel
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GridOn,
                        contentDescription = "Grille et Niveau",
                        tint = if (state.showGridLevel) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // HyperOS Pro Menu (HAL Probe, Calibration, Ephemeris, Settings)
                TopBarIconItem(
                    active = state.showTopMenu,
                    onClick = onToggleTopMenu
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = "Menu",
                        tint = if (state.showTopMenu) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // =================================================================
            // 2. 4:3 VIEWFINDER (TextureView + Overlays + Floating Lens Pill)
            // =================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (state.activeDial != null) {
                                        onDismissDial()
                                    } else if (state.showTopMenu) {
                                        onToggleTopMenu()
                                    } else {
                                        val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0.1f, 0.9f)
                                        val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0.1f, 0.9f)
                                        onToggleFocusLoupe(nx, ny)
                                    }
                                }
                            )
                        }
                ) {
                    if (state.cameraPermissionGranted) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).also { tv -> onAttachTextureView(tv) }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Autorisation Caméra requise",
                                    style = tabularStyle(size = 13.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "AUTORISER LA CAMÉRA",
                                    style = tabularStyle(size = 12.sp, weight = FontWeight.Bold, color = colors.accent),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                                        .clickable(onClick = onRequestPermission)
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Live stacked result preview during Deep Sky / Star Trails / Planetary sequences
                    val liveStackBmp = state.captureProgress.liveStackPreview
                    if (state.captureProgress.active && liveStackBmp != null) {
                        Image(
                            bitmap = liveStackBmp.asImageBitmap(),
                            contentDescription = "Aperçu empilement temps réel",
                            contentScale = ContentScale.Crop,
                            colorFilter = nightVisionColorFilter(colors.isNightVision),
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Rule-of-Thirds Grid & 2-Axis Calibrated Electronic Bubble Level
                    if (state.showGridLevel && !state.captureProgress.active) {
                        GridAndBubbleLevelOverlay(orientation = orientation)
                    }

                    // Augmented Reality Astronomical Sky Overlay (Milky Way Plane + 12 Targets + Smart Guidance)
                    if (state.arSkyOverlayEnabled && !state.captureProgress.active && !state.usingFront && lens != null) {
                        val arFov = remember(lens.id, lens.focalLengthMm, lens.sensorWidthMm, lens.sensorHeightMm) {
                            ArSkyProjector.computePortraitFov(
                                focalLengthMm = lens.focalLengthMm,
                                sensorWidthMm = lens.sensorWidthMm,
                                sensorHeightMm = lens.sensorHeightMm
                            )
                        }
                        val arFrame = remember(
                            orientation.cameraAzimuthDeg,
                            orientation.cameraAltitudeDeg,
                            orientation.cameraRollDeg,
                            arFov,
                            state.targets,
                            state.captureMode,
                            state.compassTargetId,
                            state.location
                        ) {
                            ArSkyProjector.projectOverlay(
                                epochMillis = System.currentTimeMillis(),
                                location = state.location,
                                camAzDeg = orientation.cameraAzimuthDeg.toDouble(),
                                camAltDeg = orientation.cameraAltitudeDeg.toDouble(),
                                camRollDeg = orientation.cameraRollDeg.toDouble(),
                                fov = arFov,
                                targets = state.targets,
                                captureMode = state.captureMode,
                                selectedTargetId = state.compassTargetId
                            )
                        }
                        ArSkyOverlay(frame = arFrame)
                    }

                    // Real-Time Focus Peaking Overlay (Lunar craters, horizon, pinpoint stars)
                    if (state.focusPeakingEnabled && !state.captureProgress.active && state.focusPeaking.pointCount > 0) {
                        FocusPeakingOverlay(
                            pointsXY = state.focusPeaking.pointsXY,
                            pointCount = state.focusPeaking.pointCount,
                            sensorOrientation = lens?.sensorOrientation ?: 90,
                            isFront = state.usingFront
                        )
                    }

                    // Top-Left Xiaomi Telemetry Line
                    if (lens != null) {
                        val npfLim = lens.npfSeconds(manual.aperture)
                        val telemetry = String.format(
                            Locale.US,
                            "%s · f/%.2f · ISO %d · %s · NPF %.1fs",
                            lens.displayName,
                            manual.aperture,
                            manual.iso,
                            if (manual.shutterAuto) "AUTO" else NpfCalculator.formatShutter(manual.shutterSeconds),
                            npfLim
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp, top = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.panelTranslucent)
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = telemetry,
                                style = tabularStyle(size = 10.sp, weight = FontWeight.Medium, color = colors.textPrimary)
                            )
                        }
                    }

                    // Top-Right Compact Live RGB Histogram
                    if (state.showHistogram) {
                        LiveRgbHistogram(
                            data = state.histogram,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 8.dp, top = 8.dp)
                        )
                    }

                    // Interactive 10x Star Focus Loupe (with real-time C++20 FWHM, HFD & 1-tap AF ★!)
                    if (state.focusLoupe.enabled && !state.captureProgress.active) {
                        StarFocusLoupeOverlay(
                            loupe = state.focusLoupe,
                            isAutoFocusing = state.autoFocus.running,
                            onAutoFocus = onStartStarAutoFocus,
                            onClose = { onToggleFocusLoupe(null, null) },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp, top = 36.dp)
                        )
                    }

                    // Compact Translucent V-Curve Star Autofocus Sweep Pill (auto-dismisses once locked)
                    if (state.autoFocus.running && !state.captureProgress.active) {
                        AutoFocusVCurvePill(
                            autoFocus = state.autoFocus,
                            onCancel = onCancelStarAutoFocus,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 8.dp, top = if (state.showHistogram) 82.dp else 36.dp)
                        )
                    }

                    // Countdown overlay
                    if (state.captureProgress.countdownSec > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x66000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.captureProgress.countdownSec.toString(),
                                style = tabularStyle(size = 76.sp, weight = FontWeight.Light, color = colors.accent)
                            )
                        }
                    }

                    // Live Capture / Stacking HUD
                    if (state.captureProgress.active && state.captureProgress.countdownSec == 0) {
                        LiveCaptureHudOverlay(
                            progress = state.captureProgress,
                            onFinishNow = onFinishCaptureEarly,
                            onAbort = onAbortCapture,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        )
                    }

                    // Floating Xiaomi Lens Selector Pill (`0.5` · `1` · `3.2` · `5`) directly above Pro bar
                    if (!state.captureProgress.active) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            XiaomiLensSelectorPill(
                                lenses = state.rearLenses,
                                activeLens = lens,
                                onSelectLens = onSelectLens
                            )
                            // Quick 10x Loupe toggle pill next to lens switcher
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x99000000))
                                    .border(
                                        0.75.dp,
                                        if (state.focusLoupe.enabled) colors.accent else colors.borderSubtle,
                                        CircleShape
                                    )
                                    .clickable { onToggleFocusLoupe(null, null) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomIn,
                                    contentDescription = "Loupe 10x",
                                    tint = if (state.focusLoupe.enabled) colors.accent else colors.textPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 3. MODE-SPECIFIC COMPACT OPTIONS STRIP + 1-TAP GUIDE BUTTON
            // =================================================================
            if (state.activeDial == null) {
                ModeOptionsBar(
                    mode = state.captureMode,
                    options = state.modeOptions,
                    hasDark = state.activeDarkId != null,
                    hasFlat = state.activeFlatId != null,
                    focusPeakingEnabled = state.focusPeakingEnabled,
                    onToggleFocusPeaking = onToggleFocusPeaking,
                    onOpenGuide = onOpenModeGuide,
                    onUpdate = onUpdateModeOptions
                )
            }

            // =================================================================
            // 4. SLIDE-UP XIAOMI PRO PRECISION TICK-WHEEL DIAL (when tile active)
            // =================================================================
            val openDial = state.activeDial
            val calibratedInf = lens?.let { state.calibratedInfinityByLens[it.id] } ?: 0.0f
            if (openDial != null && lens != null) {
                ActiveProDialSection(
                    dialType = openDial,
                    lens = lens,
                    manual = manual,
                    calibratedInfinity = calibratedInf,
                    isAutoFocusing = state.autoFocus.running,
                    focusPeakingEnabled = state.focusPeakingEnabled,
                    onToggleFocusPeaking = onToggleFocusPeaking,
                    onStartStarAutoFocus = onStartStarAutoFocus,
                    onSnapCalibratedInfinity = onSnapCalibratedInfinity,
                    onUpdateManual = onUpdateManual,
                    onSnapNpf = onSnapNpf,
                    onDismiss = onDismissDial
                )
            }

            // =================================================================
            // 5. XIAOMI PRO 6-TILE MANUAL BAR (WB · F · S · ISO · f/ · EV)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ProManualTile(
                    label = "WB",
                    value = if (manual.wbAuto) "AUTO" else "${manual.wbKelvin}K",
                    isManualActive = !manual.wbAuto,
                    isSelected = state.activeDial == ProDialType.WB,
                    onClick = { onToggleDial(ProDialType.WB) },
                    modifier = Modifier.weight(1f)
                )
                ProManualTile(
                    label = "F",
                    value = when {
                        manual.focusAuto -> "AUTO"
                        manual.focusDistance <= 0.02f || abs(manual.focusDistance - calibratedInf) <= 0.015f -> "∞"
                        else -> String.format(Locale.US, "%.2f", manual.focusDistance)
                    },
                    isManualActive = !manual.focusAuto,
                    isSelected = state.activeDial == ProDialType.FOCUS,
                    onClick = { onToggleDial(ProDialType.FOCUS) },
                    modifier = Modifier.weight(1f)
                )
                ProManualTile(
                    label = "S",
                    value = if (manual.shutterAuto) "AUTO" else NpfCalculator.formatShutter(manual.shutterSeconds),
                    isManualActive = !manual.shutterAuto,
                    isSelected = state.activeDial == ProDialType.SHUTTER,
                    onClick = { onToggleDial(ProDialType.SHUTTER) },
                    modifier = Modifier.weight(1f)
                )
                ProManualTile(
                    label = "ISO",
                    value = if (manual.isoAuto) "AUTO" else manual.iso.toString(),
                    isManualActive = !manual.isoAuto,
                    isSelected = state.activeDial == ProDialType.ISO,
                    onClick = { onToggleDial(ProDialType.ISO) },
                    modifier = Modifier.weight(1f)
                )
                ProManualTile(
                    label = "f/",
                    value = String.format(Locale.US, "%.2f", manual.aperture),
                    isManualActive = lens?.hasVariableAperture == true,
                    isSelected = state.activeDial == ProDialType.APERTURE,
                    onClick = { onToggleDial(ProDialType.APERTURE) },
                    modifier = Modifier.weight(1f)
                )
                ProManualTile(
                    label = "EV",
                    value = String.format(Locale.US, "%+.1f", manual.evFloat),
                    isManualActive = manual.evCompensation != 0,
                    isSelected = state.activeDial == ProDialType.EV,
                    onClick = { onToggleDial(ProDialType.EV) },
                    modifier = Modifier.weight(1f)
                )
            }

            // =================================================================
            // 6. BOTTOM SHUTTER DECK (Mode Strip + [Labo] [Shutter] [Ciel])
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(bottom = 10.dp)
            ) {
                // Horizontal Scrollable Mode Strip with Xiaomi Gold Underline
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptureMode.entries.forEach { mode ->
                        val active = state.captureMode == mode
                        Column(
                            modifier = Modifier
                                .clickable { onSelectMode(mode) }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = mode.label,
                                style = tabularStyle(
                                    size = 11.5.sp,
                                    weight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) colors.textPrimary else colors.textSecondary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(if (active) 22.dp else 0.dp)
                                    .height(2.dp)
                                    .background(colors.accent, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Shutter Row: [Labo 16-bit Thumbnail]  [Xiaomi Pro Shutter]  [Éphémérides Ciel]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Circular Gallery / 16-bit Labo Thumbnail
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(1.dp, colors.borderSubtle, CircleShape)
                            .clickable { onNavigate(AppScreen.LABO) },
                        contentAlignment = Alignment.Center
                    ) {
                        val thumb = state.lastThumbBitmap
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = "Ouvrir Labo 16-bit",
                                contentScale = ContentScale.Crop,
                                colorFilter = nightVisionColorFilter(colors.isNightVision),
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.PhotoLibrary,
                                contentDescription = "Labo Astro",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    // Center: Iconic Xiaomi Pro Shutter Button (with live long-exposure countdown ring & seconds badge)
                    val progFrac = when {
                        !state.captureProgress.active -> 0f
                        state.captureProgress.framesTarget > 1 ->
                            state.captureProgress.framesCaptured.toFloat() / state.captureProgress.framesTarget.toFloat()
                        else -> state.captureProgress.exposureProgress
                    }
                    XiaomiProShutterButton(
                        isCapturing = state.captureProgress.active,
                        progressFraction = progFrac,
                        countdownSeconds = state.captureProgress.remainingExposureSec,
                        onClick = onShutterPressed
                    )

                    // Right: Quick Ephemeris & Sky Guide button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(1.dp, colors.borderSubtle, CircleShape)
                            .clickable { onNavigate(AppScreen.EPHEMERIS) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Explore,
                            contentDescription = "Éphémérides et Guide Ciel",
                            tint = colors.accent,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }
        }

        // =================================================================
        // SLIDE-DOWN HYPEROS PRO TOP MENU DRAWER
        // =================================================================
        if (state.showTopMenu) {
            val calInfForActiveLens = lens?.let { state.calibratedInfinityByLens[it.id] }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .fillMaxWidth()
                    .background(if (colors.isNightVision) Color(0xF00D0202) else Color(0xEE0D0D0D))
                    .border(0.75.dp, colors.borderSubtle)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ASTROPRO 14U · OUTILS & PARAMÈTRES",
                    style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )

                // Dedicated 1-tap Mode Guide & Sample Photos entry
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.surface)
                        .border(0.75.dp, colors.accent.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                        .clickable { onOpenModeGuide() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = "Guide des modes",
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Guide & Exemples par mode",
                                style = tabularStyle(size = 11.5.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                            )
                            Text(
                                text = "7 modes · Photos de référence & réglages 1-tap Xiaomi 14U",
                                style = tabularStyle(size = 9.5.sp, color = colors.textSecondary)
                            )
                        }
                    }
                    Text(
                        text = "OUVRIR",
                        style = tabularStyle(size = 10.sp, weight = FontWeight.Bold, color = colors.accent)
                    )
                }

                // 1-Tap Star V-Curve Autofocus + Per-Lens Infinity Memory
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.surface)
                        .border(
                            0.75.dp,
                            if (state.autoFocus.running) colors.accent else colors.borderSubtle,
                            RoundedCornerShape(5.dp)
                        )
                        .clickable {
                            onToggleTopMenu()
                            onStartStarAutoFocus()
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Focus Étoiles (Courbe en V)",
                            style = tabularStyle(size = 11.5.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                        )
                        Text(
                            text = if (calInfForActiveLens != null) {
                                String.format(
                                    Locale.US,
                                    "∞ calibré sur %s : %.2f D · Recalibrer",
                                    lens.displayName,
                                    calInfForActiveLens
                                )
                            } else {
                                "Détection auto étoile + mémoire infini par objectif"
                            },
                            style = tabularStyle(size = 9.5.sp, color = colors.textSecondary)
                        )
                    }
                    Text(
                        text = if (state.autoFocus.running) "EN COURS" else "AF ★",
                        style = tabularStyle(size = 10.sp, weight = FontWeight.Bold, color = colors.accent)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MenuActionTile(
                        title = "Capteurs HAL",
                        subtitle = "${state.rawProbeEntries.size} IDs",
                        onClick = { onNavigate(AppScreen.LENS_PROBE) },
                        modifier = Modifier.weight(1f)
                    )
                    MenuActionTile(
                        title = "Calibration",
                        subtitle = "${state.calibrationMasters.size} masters",
                        onClick = { onNavigate(AppScreen.CALIBRATION) },
                        modifier = Modifier.weight(1f)
                    )
                    MenuActionTile(
                        title = "Éphémérides",
                        subtitle = "Ciel & Lune",
                        onClick = { onNavigate(AppScreen.EPHEMERIS) },
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MenuToggleChip(
                        label = "Anti-Vent",
                        active = state.vibrationGuardEnabled,
                        onClick = onToggleVibrationGuard,
                        modifier = Modifier.weight(1f)
                    )
                    MenuToggleChip(
                        label = "PEAK",
                        active = state.focusPeakingEnabled,
                        onClick = onToggleFocusPeaking,
                        modifier = Modifier.weight(0.8f)
                    )
                    MenuToggleChip(
                        label = "Histogramme",
                        active = state.showHistogram,
                        onClick = onToggleHistogram,
                        modifier = Modifier.weight(1.1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MenuToggleChip(
                        label = "Écran tamisé",
                        active = state.dimScreenDuringStack,
                        onClick = onToggleDimDuringStack,
                        modifier = Modifier.weight(1f)
                    )
                    MenuToggleChip(
                        label = if (state.usingFront) "Objectif Arrière" else "Objectif Frontal",
                        active = state.usingFront,
                        onClick = {
                            onToggleFrontRear()
                            onToggleTopMenu()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Minimalist status toast banner
        if (state.statusBanner != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.panelTranslucent)
                    .border(0.75.dp, colors.accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = state.statusBanner,
                    style = tabularStyle(size = 11.5.sp, weight = FontWeight.Medium, color = colors.textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // =================================================================
        // XIAOMI HYPEROS MODE GUIDE & SAMPLE PHOTOS SHEET
        // =================================================================
        if (state.showModeGuide) {
            ModeGuideSheet(
                initialMode = state.captureMode,
                onApplyModePreset = onApplyModeGuidePreset,
                onDismiss = onDismissModeGuide
            )
        }
    }
}

@Composable
private fun ActiveProDialSection(
    dialType: ProDialType,
    lens: LensInfo,
    manual: ManualSettings,
    calibratedInfinity: Float,
    isAutoFocusing: Boolean,
    focusPeakingEnabled: Boolean,
    onToggleFocusPeaking: () -> Unit,
    onStartStarAutoFocus: () -> Unit,
    onSnapCalibratedInfinity: () -> Unit,
    onUpdateManual: ((ManualSettings) -> ManualSettings) -> Unit,
    onSnapNpf: () -> Unit,
    onDismiss: () -> Unit
) {
    when (dialType) {
        ProDialType.WB -> {
            val kelvins = remember { (2500..8000 step 100).toList() }
            val labels = remember(kelvins) { kelvins.map { "${it}K" } }
            val idx = kelvins.indexOfFirst { it >= manual.wbKelvin }.takeIf { it >= 0 } ?: 17
            XiaomiProTickDial(
                title = "WB",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(wbAuto = false, wbKelvin = kelvins[i]) } },
                quickActionLabel = "AUTO",
                quickActionActive = manual.wbAuto,
                onQuickAction = { onUpdateManual { it.copy(wbAuto = !it.wbAuto) } },
                secondaryActionLabel = "4200K",
                onSecondaryAction = { onUpdateManual { it.copy(wbAuto = false, wbKelvin = 4200) } },
                onDismiss = onDismiss
            )
        }

        ProDialType.FOCUS -> {
            val maxD = lens.minFocusDistance.coerceAtLeast(1.0f)
            // 101 fine precision steps from 0.00 (infinity) to maxD (concentrated finely near infinity!)
            val steps = remember(maxD) {
                (0..100).map { i ->
                    val t = i / 100f
                    // Quadratic curve gives ultra-fine 0.005 diopter steps near infinity for pinpoint star focus!
                    t * t * maxD
                }
            }
            val labels = remember(steps, calibratedInfinity) {
                steps.mapIndexed { idx, d ->
                    when {
                        idx == 0 && calibratedInfinity <= 0.01f -> "∞ (0.00)"
                        calibratedInfinity > 0.01f && abs(d - calibratedInfinity) < 0.012f ->
                            String.format(Locale.US, "∞ (%.2f)", calibratedInfinity)
                        else -> String.format(Locale.US, "%.2f", d)
                    }
                }
            }
            val idx = steps.indices.minByOrNull { abs(steps[it] - manual.focusDistance) } ?: 0
            XiaomiProTickDial(
                title = "FOCUS",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(focusAuto = false, focusDistance = steps[i]) } },
                quickActionLabel = "AUTO",
                quickActionActive = manual.focusAuto,
                onQuickAction = { onUpdateManual { it.copy(focusAuto = !it.focusAuto) } },
                secondaryActionLabel = "∞",
                onSecondaryAction = onSnapCalibratedInfinity,
                tertiaryActionLabel = "AF ★",
                tertiaryActionActive = isAutoFocusing,
                onTertiaryAction = onStartStarAutoFocus,
                quaternaryActionLabel = "PEAK",
                quaternaryActionActive = focusPeakingEnabled,
                onQuaternaryAction = onToggleFocusPeaking,
                onDismiss = onDismiss
            )
        }

        ProDialType.SHUTTER -> {
            val speeds = remember(lens.minExposureNs, lens.maxExposureNs) {
                NpfCalculator.STANDARD_SHUTTER_SECONDS.filter {
                    it in lens.minExposureSec..lens.maxExposureSec
                }.ifEmpty { listOf(1.0 / 60.0, 1.0, 4.0, 8.0, 15.0) }
            }
            val labels = remember(speeds) { speeds.map { NpfCalculator.formatShutter(it) } }
            val idx = speeds.indices.minByOrNull { abs(speeds[it] - manual.shutterSeconds) } ?: 0
            XiaomiProTickDial(
                title = "SHUTTER",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(shutterAuto = false, shutterSeconds = speeds[i]) } },
                quickActionLabel = "AUTO",
                quickActionActive = manual.shutterAuto,
                onQuickAction = { onUpdateManual { it.copy(shutterAuto = !it.shutterAuto) } },
                secondaryActionLabel = "NPF",
                onSecondaryAction = onSnapNpf,
                onDismiss = onDismiss
            )
        }

        ProDialType.ISO -> {
            val isoSteps = remember(lens.minIso, lens.maxIso) {
                listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600, 32000)
                    .filter { it in lens.minIso..lens.maxIso }
                    .ifEmpty { listOf(lens.minIso, lens.maxIso) }
            }
            val labels = remember(isoSteps) { isoSteps.map { it.toString() } }
            val idx = isoSteps.indices.minByOrNull { abs(isoSteps[it] - manual.iso) } ?: 0
            XiaomiProTickDial(
                title = "ISO",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(isoAuto = false, iso = isoSteps[i]) } },
                quickActionLabel = "AUTO",
                quickActionActive = manual.isoAuto,
                onQuickAction = { onUpdateManual { it.copy(isoAuto = !it.isoAuto) } },
                secondaryActionLabel = "1600",
                onSecondaryAction = { onUpdateManual { it.copy(isoAuto = false, iso = 1600.coerceIn(lens.minIso, lens.maxIso)) } },
                onDismiss = onDismiss
            )
        }

        ProDialType.APERTURE -> {
            val aps = lens.availableApertures.ifEmpty { listOf(lens.defaultAperture) }
            val labels = remember(aps) { aps.map { String.format(Locale.US, "f/%.2f", it) } }
            val idx = aps.indices.minByOrNull { abs(aps[it] - manual.aperture) } ?: 0
            XiaomiProTickDial(
                title = if (lens.hasVariableAperture) "DIAPHRAGME" else "OUVERTURE FIXE",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(aperture = aps[i]) } },
                quickActionLabel = if (lens.hasVariableAperture) String.format(Locale.US, "MAX f/%.2f", aps.first()) else null,
                quickActionActive = abs(manual.aperture - aps.first()) < 0.02f,
                onQuickAction = if (lens.hasVariableAperture) { { onUpdateManual { it.copy(aperture = aps.first()) } } } else null,
                onDismiss = onDismiss
            )
        }

        ProDialType.EV -> {
            val evSteps = remember { (-12..12).toList() } // -4.0 .. +4.0 in 1/3 EV stops
            val labels = remember(evSteps) { evSteps.map { String.format(Locale.US, "%+.1f", it / 3f) } }
            val idx = (manual.evCompensation + 12).coerceIn(0, evSteps.lastIndex)
            XiaomiProTickDial(
                title = "EV",
                labels = labels,
                selectedIndex = idx,
                onIndexChanged = { i -> onUpdateManual { it.copy(evCompensation = evSteps[i]) } },
                quickActionLabel = "0.0",
                quickActionActive = manual.evCompensation == 0,
                onQuickAction = { onUpdateManual { it.copy(evCompensation = 0) } },
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun ModeOptionsBar(
    mode: CaptureMode,
    options: ModeOptions,
    hasDark: Boolean,
    hasFlat: Boolean,
    focusPeakingEnabled: Boolean,
    onToggleFocusPeaking: () -> Unit,
    onOpenGuide: () -> Unit,
    onUpdate: ((ModeOptions) -> ModeOptions) -> Unit
) {
    val colors = LocalAstroColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (mode) {
                CaptureMode.ASTRO_PRO -> {
                    ModeOptionChip(
                        label = "PEAK ${if (focusPeakingEnabled) "ON" else "OFF"}",
                        highlighted = focusPeakingEnabled,
                        onClick = onToggleFocusPeaking
                    )
                    Text(
                        text = "Pose unique RAW 16-bit · Dév. Auto Galerie + .astro32",
                        style = tabularStyle(size = 10.sp, color = colors.textSecondary),
                        maxLines = 1
                    )
                }

                CaptureMode.DEEP_SKY -> {
                    val counts = listOf(10, 20, 40, 60, 120)
                    val nextCount = counts[(counts.indexOf(options.deepSkyFrames) + 1) % counts.size]
                    ModeOptionChip("Poses: ${options.deepSkyFrames}", true) {
                        onUpdate { it.copy(deepSkyFrames = nextCount) }
                    }
                    ModeOptionChip(
                        label = "Paysage Auto",
                        highlighted = options.deepSkyLandscapeAuto
                    ) { onUpdate { it.copy(deepSkyLandscapeAuto = !it.deepSkyLandscapeAuto) } }
                    ModeOptionChip(
                        label = "Drizzle 2×",
                        highlighted = options.deepSkyDrizzle2x
                    ) { onUpdate { it.copy(deepSkyDrizzle2x = !it.deepSkyDrizzle2x) } }
                    ModeOptionChip(
                        "Dark ${if (hasDark && options.useAutoDark) "✓" else "OFF"}",
                        hasDark && options.useAutoDark
                    ) { onUpdate { it.copy(useAutoDark = !it.useAutoDark) } }
                    ModeOptionChip(
                        "Flat ${if (hasFlat && options.useAutoFlat) "✓" else "OFF"}",
                        hasFlat && options.useAutoFlat
                    ) { onUpdate { it.copy(useAutoFlat = !it.useAutoFlat) } }
                    val sigmas = listOf(2.0f, 2.5f, 3.0f)
                    val nextSig = sigmas[(sigmas.indexOfFirst { abs(it - options.deepSkySigmaKappa) < 0.1f } + 1) % sigmas.size]
                    ModeOptionChip(String.format(Locale.US, "Sigma %.1fσ", options.deepSkySigmaKappa), true) {
                        onUpdate { it.copy(deepSkySigmaKappa = nextSig) }
                    }
                }

                CaptureMode.LUNAR_PLANETARY -> {
                    val bursts = listOf(30, 60, 100)
                    val nextBurst = bursts[(bursts.indexOf(options.planetaryFrames) + 1) % bursts.size]
                    ModeOptionChip("Rafale: ${options.planetaryFrames}", true) {
                        onUpdate { it.copy(planetaryFrames = nextBurst) }
                    }
                    val pcts = listOf(0.15f, 0.20f, 0.25f, 0.40f)
                    val curIdx = pcts.indexOfFirst { abs(it - options.planetaryKeepPercent) < 0.02f }
                    val nextPct = pcts[(curIdx + 1) % pcts.size]
                    ModeOptionChip("Top Netteté: ${(options.planetaryKeepPercent * 100).toInt()}%", true) {
                        onUpdate { it.copy(planetaryKeepPercent = nextPct) }
                    }
                    ModeOptionChip(
                        label = "Drizzle 2×",
                        highlighted = options.planetaryDrizzle2x
                    ) { onUpdate { it.copy(planetaryDrizzle2x = !it.planetaryDrizzle2x) } }
                    ModeOptionChip(
                        "Ondelettes Auto: ${if (options.planetaryAutoWavelets) "ON" else "OFF"}",
                        options.planetaryAutoWavelets
                    ) { onUpdate { it.copy(planetaryAutoWavelets = !it.planetaryAutoWavelets) } }
                }

                CaptureMode.STAR_TRAILS -> {
                    ModeOptionChip("Mode: ${options.starTrailStyle.label}", true) {
                        val next = if (options.starTrailStyle == StarTrailStyle.STANDARD) StarTrailStyle.COMET else StarTrailStyle.STANDARD
                        onUpdate { it.copy(starTrailStyle = next) }
                    }
                    if (options.starTrailStyle == StarTrailStyle.COMET) {
                        val lengths = CometTailLength.entries
                        val nextLen = lengths[(lengths.indexOf(options.cometTailLength) + 1) % lengths.size]
                        ModeOptionChip("Traînée: ${options.cometTailLength.label}", true) {
                            onUpdate { it.copy(cometTailLength = nextLen) }
                        }
                    }
                }

                CaptureMode.HDR_ASTRO -> {
                    val isFive = options.hdrBrackets == 5
                    ModeOptionChip(
                        label = if (isFive) "5 EV (-3..+3)" else "3 EV (-2/0/+2)",
                        highlighted = true
                    ) {
                        onUpdate { it.copy(hdrBrackets = if (it.hdrBrackets == 3) 5 else 3) }
                    }
                    Text(
                        text = "Fusion 32-bit linéaire · Cœur préservé · Auto-STF",
                        style = tabularStyle(size = 10.sp, color = colors.textSecondary),
                        maxLines = 1
                    )
                }

                CaptureMode.TIMELAPSE -> {
                    val ivals = listOf(2, 5, 10, 30)
                    val nextIv = ivals[(ivals.indexOf(options.timelapseIntervalSec) + 1) % ivals.size]
                    ModeOptionChip("Intervalle: ${options.timelapseIntervalSec}s", true) {
                        onUpdate { it.copy(timelapseIntervalSec = nextIv) }
                    }
                    val cnts = listOf(60, 120, 180, 300, 0)
                    val nextCnt = cnts[(cnts.indexOf(options.timelapseCount) + 1) % cnts.size]
                    ModeOptionChip("Vues: ${if (options.timelapseCount == 0) "∞" else options.timelapseCount}", true) {
                        onUpdate { it.copy(timelapseCount = nextCnt) }
                    }
                    ModeOptionChip("${options.timelapseFps} fps", true) {
                        onUpdate { it.copy(timelapseFps = if (it.timelapseFps == 24) 30 else 24) }
                    }
                    ModeOptionChip("Archive RAW: ${if (options.timelapseArchiveRaw) "ON" else "OFF"}", options.timelapseArchiveRaw) {
                        onUpdate { it.copy(timelapseArchiveRaw = !it.timelapseArchiveRaw) }
                    }
                }

                CaptureMode.CALIBRATION -> {
                    val kinds = CalibrationKind.entries
                    val nextKind = kinds[(kinds.indexOf(options.calibrationKind) + 1) % kinds.size]
                    ModeOptionChip("Type: ${options.calibrationKind.label}", true) {
                        onUpdate { it.copy(calibrationKind = nextKind) }
                    }
                    val counts = listOf(10, 15, 20)
                    val nextC = counts[(counts.indexOf(options.calibrationFrames) + 1) % counts.size]
                    ModeOptionChip("Poses: ${options.calibrationFrames}", true) {
                        onUpdate { it.copy(calibrationFrames = nextC) }
                    }
                    Text(
                        text = when (options.calibrationKind) {
                            CalibrationKind.DARK -> "Couvrir l'objectif (même ISO/pose)"
                            CalibrationKind.FLAT -> "Pointer une surface blanche uniforme"
                            CalibrationKind.BIAS -> "Couvrir l'objectif (pose minimale)"
                        },
                        style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                    )
                }
            }
        }

        // Always-accessible 1-tap `? Guide` button pinned to the right of the Mode Options strip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surface)
                .border(0.75.dp, colors.accent.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .clickable(onClick = onOpenGuide)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "Guide du mode",
                tint = colors.accent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "Guide",
                style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.accent)
            )
        }
    }
}

@Composable
private fun ModeOptionChip(label: String, highlighted: Boolean, onClick: () -> Unit) {
    val colors = LocalAstroColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface)
            .border(0.75.dp, if (highlighted) colors.accent.copy(alpha = 0.7f) else colors.borderSubtle, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = tabularStyle(size = 10.5.sp, weight = FontWeight.Medium, color = if (highlighted) colors.accent else colors.textSecondary)
        )
    }
}

@Composable
private fun ProManualTile(
    label: String,
    value: String,
    isManualActive: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    val bg = if (isSelected) colors.surfaceElevated else colors.surface
    val borderCol = when {
        isSelected -> colors.accent
        isManualActive -> colors.borderSubtle
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .border(0.75.dp, borderCol, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = tabularStyle(
                size = 9.5.sp,
                weight = FontWeight.Medium,
                color = if (isSelected || isManualActive) colors.accent else colors.textSecondary
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = tabularStyle(
                size = 11.sp,
                weight = FontWeight.SemiBold,
                color = if (isSelected) colors.accent else colors.textPrimary
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun TopBarIconItem(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun TopBarToggleBadge(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalAstroColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                0.75.dp,
                if (active) colors.accent else colors.borderSubtle,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = tabularStyle(
                size = 10.5.sp,
                weight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                color = if (active) colors.accent else colors.textSecondary
            )
        )
    }
}

@Composable
private fun TopBarTextBadge(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalAstroColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(0.75.dp, if (active) colors.borderSubtle else colors.accent.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = tabularStyle(
                size = 10.5.sp,
                weight = FontWeight.SemiBold,
                color = if (active) colors.textPrimary else colors.accent
            )
        )
    }
}

@Composable
private fun MenuActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.surface)
            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = tabularStyle(size = 9.5.sp, color = colors.textSecondary)
        )
    }
}

@Composable
private fun MenuToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) colors.accent.copy(alpha = 0.14f) else colors.surface)
            .border(0.75.dp, if (active) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = tabularStyle(
                size = 10.sp,
                weight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) colors.accent else colors.textSecondary
            ),
            maxLines = 1
        )
    }
}
