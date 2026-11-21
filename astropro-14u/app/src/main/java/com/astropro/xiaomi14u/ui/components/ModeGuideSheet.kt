package com.astropro.xiaomi14u.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.camera.CaptureMode
import com.astropro.xiaomi14u.camera.ModeGuideData
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle

/**
 * Authentic Xiaomi HyperOS Leica Camera Pro Mode Guide Sheet.
 *
 * Displays all 7 astrophotography modes (including HDR Astro) with full-bleed 4:3 reference photos,
 * Leica-style bottom monospace EXIF caption, 4 scannable field bullets,
 * recommended Xiaomi 14 Ultra preset chips, and a 1-tap "Appliquer ces réglages" button.
 * 100% compatible with Vision Nocturne OLED Red mode.
 */
@Composable
fun ModeGuideSheet(
    initialMode: CaptureMode,
    onApplyModePreset: (CaptureMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    var viewedMode by remember(initialMode) { mutableStateOf(initialMode) }
    val guide = remember(viewedMode) { ModeGuideData.guideFor(viewedMode) }
    val presetChips = remember(guide) { guide.recommendedPreset.summaryChips() }
    val sheetBg = if (colors.isNightVision) Color(0xFF0E0202) else Color(0xFF121212)

    // Dimmed OLED backdrop + bottom-docked HyperOS Pro sheet
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB8000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 660.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(sheetBg)
                .border(
                    width = 1.dp,
                    color = colors.borderSubtle,
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // Subtle HyperOS handle + Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.borderSubtle)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "GUIDE ASTRO · XIAOMI 14 ULTRA",
                        style = tabularStyle(
                            size = 11.sp,
                            weight = FontWeight.SemiBold,
                            color = colors.accent,
                            letterSpacing = 0.6.sp
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Fermer le guide",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Top horizontal mode tabs across all 6 modes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeGuideData.ORDERED_MODES.forEach { mode ->
                        val selected = mode == viewedMode
                        Column(
                            modifier = Modifier
                                .clickable { viewedMode = mode }
                                .padding(top = 2.dp, bottom = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = mode.label,
                                style = tabularStyle(
                                    size = 11.sp,
                                    weight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) colors.textPrimary else colors.textSecondary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(if (selected) 24.dp else 0.dp)
                                    .height(2.dp)
                                    .background(colors.accent, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.borderSubtle, thickness = 1.dp)

            // Scrollable guide body: 4:3 sample photo + EXIF caption + 4 brief bullets + recommended preset strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 4:3 Full-Width Sample Photo with subtle bottom scrim strictly behind Leica monospace EXIF strip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(4.dp))
                ) {
                    Image(
                        painter = painterResource(id = guide.drawableRes),
                        contentDescription = guide.subtitle,
                        contentScale = ContentScale.Crop,
                        colorFilter = nightVisionColorFilter(colors.isNightVision),
                        modifier = Modifier.fillMaxSize()
                    )

                    // Subtle dark bottom gradient ONLY behind the thin monospace EXIF telemetry strip
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xD8000000))
                                )
                            )
                            .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 7.dp)
                    ) {
                        Text(
                            text = guide.sampleCaption,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (colors.isNightVision) colors.textPrimary else Color(0xFFEAEAEA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Mode Title / Subtitle
                Text(
                    text = guide.subtitle,
                    style = tabularStyle(
                        size = 13.sp,
                        weight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                )

                // 4 Ultra-Brief Scannable Bullet Points (minimalist gold/red micro-markers, zero emojis)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    guide.briefPoints.forEach { bullet ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 5.dp)
                                    .width(2.dp)
                                    .height(10.dp)
                                    .background(colors.accent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bullet.label.uppercase(),
                                    style = tabularStyle(
                                        size = 9.5.sp,
                                        weight = FontWeight.Bold,
                                        color = colors.accent,
                                        letterSpacing = 0.4.sp
                                    )
                                )
                                Text(
                                    text = bullet.detail,
                                    style = tabularStyle(
                                        size = 11.5.sp,
                                        weight = FontWeight.Normal,
                                        color = colors.textPrimary.copy(alpha = 0.90f)
                                    ),
                                    lineHeight = 15.5.sp
                                )
                            }
                        }
                    }
                }

                // Compact Recommended Xiaomi 14 Ultra Settings Strip
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.background)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "RÉGLAGES RECOMMANDÉS · XIAOMI 14 ULTRA",
                        style = tabularStyle(
                            size = 9.5.sp,
                            weight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                            letterSpacing = 0.4.sp
                        )
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        presetChips.forEachIndexed { idx, chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.surface)
                                    .border(
                                        width = 0.75.dp,
                                        color = if (idx == 0) colors.accent else colors.borderSubtle,
                                        shape = RoundedCornerShape(3.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = chip,
                                    style = tabularStyle(
                                        size = 10.5.sp,
                                        weight = if (idx == 0) FontWeight.Bold else FontWeight.Medium,
                                        color = if (idx == 0) colors.accent else colors.textPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Pinned Full-Width Crisp Action Button
            HorizontalDivider(color = colors.borderSubtle, thickness = 1.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (colors.isNightVision) colors.accent.copy(alpha = 0.22f) else colors.accent)
                        .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                        .clickable { onApplyModePreset(guide.mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "APPLIQUER CES RÉGLAGES",
                        style = tabularStyle(
                            size = 12.sp,
                            weight = FontWeight.Bold,
                            color = if (colors.isNightVision) colors.accent else Color.Black,
                            letterSpacing = 0.6.sp
                        )
                    )
                }
            }
        }
    }
}
