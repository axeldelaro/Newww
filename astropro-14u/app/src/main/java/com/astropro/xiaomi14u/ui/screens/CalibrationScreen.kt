package com.astropro.xiaomi14u.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.camera.CalibrationKind
import com.astropro.xiaomi14u.camera.CalibrationMasterInfo
import com.astropro.xiaomi14u.camera.NpfCalculator
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle

/**
 * Calibration Masters Library (Dark / Flat / Bias .cal32 management).
 */
@Composable
fun CalibrationScreen(
    masters: List<CalibrationMasterInfo>,
    activeDarkId: String?,
    activeFlatId: String?,
    activeBiasId: String?,
    onSelectMaster: (CalibrationMasterInfo) -> Unit,
    onDeleteMaster: (CalibrationMasterInfo) -> Unit,
    onLaunchCapture: (CalibrationKind) -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalAstroColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Column {
                Text(
                    text = "CALIBRATION DARK / FLAT / BIAS",
                    style = tabularStyle(size = 13.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                )
                Text(
                    text = "Soustraction pixels chauds, bruit thermique & vignettage optique",
                    style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                )
            }
        }

        HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ACQUÉRIR UN NOUVEAU MASTER",
                    style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalibrationActionCard(
                        title = "DARK",
                        subtitle = "Objectif couvert\nMême ISO & Pose",
                        onClick = { onLaunchCapture(CalibrationKind.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                    CalibrationActionCard(
                        title = "FLAT",
                        subtitle = "Surface uniforme\nMême optique",
                        onClick = { onLaunchCapture(CalibrationKind.FLAT) },
                        modifier = Modifier.weight(1f)
                    )
                    CalibrationActionCard(
                        title = "BIAS",
                        subtitle = "Objectif couvert\nPose minimale",
                        onClick = { onLaunchCapture(CalibrationKind.BIAS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "MASTERS ENREGISTRÉS (${masters.size})",
                    style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )
            }

            if (masters.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface)
                            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(6.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucun master enregistré. Capturez 10–15 Darks objectif obturé pour éliminer les pixels chauds en Ciel Profond.",
                            style = tabularStyle(size = 11.5.sp, color = colors.textSecondary)
                        )
                    }
                }
            } else {
                items(masters, key = { it.id }) { m ->
                    val isActive = m.id == activeDarkId || m.id == activeFlatId || m.id == activeBiasId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface)
                            .border(
                                width = if (isActive) 1.dp else 0.75.dp,
                                color = if (isActive) colors.accent else colors.borderSubtle,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { onSelectMaster(m) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "MASTER ${m.kind.label}",
                                    style = tabularStyle(
                                        size = 12.sp,
                                        weight = FontWeight.Bold,
                                        color = if (isActive) colors.accent else colors.textPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${m.lensLabel} · ${if (m.isRawBayer) "RAW 16b" else "RGB"}",
                                    style = tabularStyle(size = 11.sp, color = colors.textSecondary)
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "ISO ${m.iso} · ${NpfCalculator.formatShutter(m.exposureSec.toDouble())} · ${m.frameCount} poses médiane",
                                style = tabularStyle(size = 10.5.sp, color = colors.textSecondary)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = "Actif",
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Supprimer",
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onDeleteMaster(m) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CalibrationActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = tabularStyle(size = 12.sp, weight = FontWeight.Bold, color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = tabularStyle(size = 9.5.sp, color = colors.textSecondary)
        )
    }
}
