package com.astropro.xiaomi14u.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.camera.LevelOrientationState
import com.astropro.xiaomi14u.ephemeris.AstroEphemeris
import com.astropro.xiaomi14u.ephemeris.MoonSummary
import com.astropro.xiaomi14u.ephemeris.ObserverLocation
import com.astropro.xiaomi14u.ephemeris.TargetCategory
import com.astropro.xiaomi14u.ephemeris.TargetVisibility
import com.astropro.xiaomi14u.ephemeris.TwilightSummary
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 100% Offline Astronomical Ephemeris, Live Magnetic Sky Compass, and 20-Target Catalogue.
 */
@Composable
fun EphemerisScreen(
    location: ObserverLocation,
    twilight: TwilightSummary,
    moon: MoonSummary,
    targets: List<TargetVisibility>,
    compassTargetId: String,
    orientation: LevelOrientationState,
    onSelectLocation: (ObserverLocation) -> Unit,
    onRequestGps: () -> Unit,
    onSelectCompassTarget: (String) -> Unit,
    onApplyTargetToCamera: (TargetVisibility) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalAstroColors.current
    var selectedCategory by remember { mutableStateOf(TargetCategory.ALL) }
    var visibleOnly by remember { mutableStateOf(false) }
    var showLocationPicker by remember { mutableStateOf(false) }

    val compassTarget = targets.firstOrNull { it.target.id == compassTargetId } ?: targets.firstOrNull()
    val filteredTargets = targets.filter { vis ->
        (selectedCategory == TargetCategory.ALL || vis.target.category == selectedCategory) &&
            (!visibleOnly || vis.isVisible)
    }.sortedByDescending { it.altitudeDeg }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.clickable { showLocationPicker = !showLocationPicker }) {
                    Text(
                        text = "ÉPHÉMÉRIDES & GUIDE CIEL",
                        style = tabularStyle(size = 13.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                    )
                    Text(
                        text = "${location.name} ▾",
                        style = tabularStyle(size = 10.5.sp, color = colors.accent)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MyLocation,
                    contentDescription = "GPS",
                    tint = colors.textPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onRequestGps)
                )
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Actualiser",
                    tint = colors.textPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onRefresh)
                )
            }
        }

        if (showLocationPicker) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AstroEphemeris.PRESET_LOCATIONS.forEach { preset ->
                    val active = preset.name == location.name
                    Text(
                        text = preset.name,
                        style = tabularStyle(
                            size = 11.sp,
                            weight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) colors.accent else colors.textSecondary
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.75.dp, if (active) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
                            .clickable {
                                onSelectLocation(preset)
                                showLocationPicker = false
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Twilight & True Astronomical Dark-Sky Window
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surface)
                        .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "NUIT ASTRONOMIQUE (HORS-LIGNE)",
                            style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.accent)
                        )
                        Text(
                            text = String.format(Locale.US, "Nuit noire: %.1fh", twilight.darkNightHours),
                            style = tabularStyle(size = 11.sp, weight = FontWeight.Bold, color = colors.textPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TwilightSlot("Coucher", twilight.sunsetLocal)
                        TwilightSlot("Civil", twilight.civilDuskLocal)
                        TwilightSlot("Naut.", twilight.nauticalDuskLocal)
                        TwilightSlot("Astro", twilight.astroDuskLocal, highlight = true)
                        TwilightSlot("Aube A.", twilight.astroDawnLocal, highlight = true)
                        TwilightSlot("Lever", twilight.sunriseLocal)
                    }
                }
            }

            // 2. Moon Phase & Deep-Sky Quality Score + Live Magnetic Sky Compass
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Moon card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface)
                            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LUNE",
                                style = tabularStyle(size = 10.5.sp, weight = FontWeight.SemiBold, color = colors.accent)
                            )
                            Text(
                                text = "Score ${moon.deepSkyScore}/100",
                                style = tabularStyle(size = 10.5.sp, weight = FontWeight.Bold, color = colors.textPrimary)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoonPhaseIcon(illuminationPct = moon.illuminationPercent, ageDays = moon.ageDays)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = moon.phaseNameFr,
                                    style = tabularStyle(size = 11.5.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                                )
                                Text(
                                    text = String.format(Locale.US, "%.0f%% · Âge %.1fj", moon.illuminationPercent, moon.ageDays),
                                    style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                                )
                                Text(
                                    text = String.format(Locale.US, "Alt %+.0f° · Az %.0f°", moon.altitudeDeg, moon.azimuthDeg),
                                    style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Lever ${moon.moonriseLocal} · Coucher ${moon.moonsetLocal}",
                            style = tabularStyle(size = 9.5.sp, color = colors.textMuted)
                        )
                    }

                    // Live Magnetic Sky Compass pointing to selected target (default Galactic Center!)
                    if (compassTarget != null) {
                        SkyCompassCard(
                            target = compassTarget,
                            orientation = orientation,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Category filter strip
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TargetCategory.entries.forEach { cat ->
                        val active = cat == selectedCategory
                        Text(
                            text = cat.labelFr,
                            style = tabularStyle(
                                size = 11.sp,
                                weight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) colors.accent else colors.textSecondary
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(0.75.dp, if (active) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
                                .background(if (active) colors.accent.copy(alpha = 0.14f) else colors.surface)
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                    Text(
                        text = if (visibleOnly) "Visibles ✓" else "Visibles",
                        style = tabularStyle(
                            size = 11.sp,
                            weight = FontWeight.Medium,
                            color = if (visibleOnly) colors.accent else colors.textSecondary
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.75.dp, if (visibleOnly) colors.accent else colors.borderSubtle, RoundedCornerShape(4.dp))
                            .clickable { visibleOnly = !visibleOnly }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }

            // 4. 20-Target Astro Catalogue
            items(filteredTargets, key = { it.target.id }) { vis ->
                val isCompassTarget = vis.target.id == compassTargetId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surface)
                        .border(
                            width = if (isCompassTarget) 1.dp else 0.75.dp,
                            color = if (isCompassTarget) colors.accent else colors.borderSubtle,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectCompassTarget(vis.target.id) }
                        .padding(11.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vis.target.nameFr,
                                style = tabularStyle(size = 12.5.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                            )
                            Text(
                                text = "${vis.target.catalogCode} · Mag ${vis.target.magnitude} · Transit ${vis.transitLocal}",
                                style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                            )
                        }
                        Text(
                            text = vis.statusFr,
                            style = tabularStyle(
                                size = 10.5.sp,
                                weight = FontWeight.SemiBold,
                                color = if (vis.isOptimal) colors.accent else if (vis.isVisible) colors.textPrimary else colors.textMuted
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = vis.target.notesFr,
                        style = tabularStyle(size = 10.5.sp, color = colors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(7.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Az %.0f° (%s) · Optique %dmm (%s×) · NPF %.1fs",
                                vis.azimuthDeg, vis.compassLabel,
                                vis.target.recommendedEqMm, vis.recommendedLensBadge,
                                vis.recommendedNpfSec
                            ),
                            style = tabularStyle(size = 10.sp, color = colors.textMuted)
                        )

                        Text(
                            text = "APPLIQUER",
                            style = tabularStyle(size = 10.5.sp, weight = FontWeight.Bold, color = colors.accent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(0.75.dp, colors.accent, RoundedCornerShape(4.dp))
                                .clickable { onApplyTargetToCamera(vis) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TwilightSlot(label: String, time: String, highlight: Boolean = false) {
    val colors = LocalAstroColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = tabularStyle(size = 9.sp, color = colors.textMuted)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = time,
            style = tabularStyle(
                size = 11.sp,
                weight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                color = if (highlight) colors.accent else colors.textPrimary
            )
        )
    }
}

@Composable
private fun MoonPhaseIcon(illuminationPct: Double, ageDays: Double) {
    val colors = LocalAstroColors.current
    Canvas(modifier = Modifier.size(34.dp)) {
        val r = size.minDimension * 0.45f
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(color = colors.borderSubtle, radius = r, center = c, style = Stroke(width = 1.2.dp.toPx()))
        val litFrac = (illuminationPct / 100.0).toFloat().coerceIn(0f, 1f)
        val waxing = ageDays < 14.765
        val steps = 20
        for (i in 0 until steps) {
            val fy = -1f + 2f * (i + 0.5f) / steps
            val halfChord = kotlin.math.sqrt((1f - fy * fy).coerceAtLeast(0f)) * r
            val y = c.y + fy * r
            val xStart = if (waxing) c.x + halfChord * (1f - 2f * litFrac) else c.x - halfChord
            val xEnd = if (waxing) c.x + halfChord else c.x - halfChord + 2f * halfChord * litFrac
            drawLine(
                color = colors.textPrimary.copy(alpha = 0.85f),
                start = Offset(xStart, y),
                end = Offset(xEnd, y),
                strokeWidth = (2f * r / steps) + 0.6f
            )
        }
    }
}

@Composable
private fun SkyCompassCard(
    target: TargetVisibility,
    orientation: LevelOrientationState,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    val deltaAz = ((target.azimuthDeg - orientation.azimuthDeg + 540.0) % 360.0) - 180.0
    val deltaAlt = target.altitudeDeg - orientation.altitudeDeg
    val locked = abs(deltaAz) < 8.0 && abs(deltaAlt) < 8.0

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .border(0.75.dp, if (locked) colors.accent else colors.borderSubtle, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "BOUSSOLE CIEL",
                style = tabularStyle(size = 10.sp, weight = FontWeight.SemiBold, color = colors.accent)
            )
            Text(
                text = target.target.catalogCode.take(8),
                style = tabularStyle(size = 10.sp, color = colors.textPrimary)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(44.dp)) {
                val c = Offset(size.width * 0.5f, size.height * 0.5f)
                val r = size.minDimension * 0.44f
                drawCircle(colors.borderSubtle, radius = r, center = c, style = Stroke(1.2.dp.toPx()))
                // Target direction needle relative to phone heading
                val rad = (deltaAz - 90.0) * PI / 180.0
                val tip = Offset(c.x + (r * 0.82f * cos(rad)).toFloat(), c.y + (r * 0.82f * sin(rad)).toFloat())
                drawLine(
                    color = if (locked) colors.accent else colors.textPrimary,
                    start = c,
                    end = tip,
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(colors.accent, radius = 3.dp.toPx(), center = tip)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (locked) "ALIGNÉ ✓" else String.format(Locale.US, "Az %+.0f°", deltaAz),
                    style = tabularStyle(
                        size = 11.5.sp,
                        weight = FontWeight.Bold,
                        color = if (locked) colors.accent else colors.textPrimary
                    )
                )
                Text(
                    text = when {
                        abs(deltaAlt) < 5.0 -> "Hauteur OK"
                        deltaAlt > 0 -> "Lever ↑ ${deltaAlt.roundToInt()}°"
                        else -> "Baisser ↓ ${(-deltaAlt).roundToInt()}°"
                    },
                    style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                )
                Text(
                    text = "Cible ${target.altitudeDeg.roundToInt()}°/${target.compassLabel}",
                    style = tabularStyle(size = 9.5.sp, color = colors.textMuted)
                )
            }
        }
    }
}
