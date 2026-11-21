package com.astropro.xiaomi14u.ui.screens

import android.os.Build
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
import androidx.compose.material.icons.outlined.FileDownload
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
import com.astropro.xiaomi14u.camera.LensInfo
import com.astropro.xiaomi14u.camera.RawProbeEntry
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import java.util.Locale

/**
 * Xiaomi 14 Ultra Engineering HAL Sensor & Multi-Lens Diagnostic Screen.
 */
@Composable
fun LensProbeScreen(
    rearLenses: List<LensInfo>,
    rawEntries: List<RawProbeEntry>,
    onExportJson: () -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalAstroColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        // HyperOS flat top header bar
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
                Column {
                    Text(
                        text = "DIAGNOSTIC CAPTEURS HAL",
                        style = tabularStyle(size = 13.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                    )
                    Text(
                        text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                        style = tabularStyle(size = 10.sp, color = colors.textSecondary)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                    .background(colors.accent.copy(alpha = 0.14f))
                    .clickable(onClick = onExportJson)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "JSON HAL",
                    style = tabularStyle(size = 11.sp, weight = FontWeight.Bold, color = colors.accent)
                )
            }
        }

        HorizontalDivider(color = colors.borderSubtle, thickness = 0.75.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "OPTIQUES ARRIÈRE CLASSIFIÉES (${rearLenses.size})",
                    style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )
            }

            items(rearLenses) { lens ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            text = "${lens.zoomBadge}× · ${lens.displayName} (${lens.sensorHint})",
                            style = tabularStyle(size = 12.5.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                        )
                        Text(
                            text = if (lens.supportsRaw) "RAW 16b" else "YUV",
                            style = tabularStyle(
                                size = 10.sp,
                                weight = FontWeight.Bold,
                                color = if (lens.supportsRaw) colors.accent else colors.textSecondary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val apStr = lens.availableApertures.joinToString(" / ") { String.format(Locale.US, "f/%.2f", it) }
                    Text(
                        text = String.format(
                            Locale.US,
                            "ID %s (Tier %s) · f=%.2fmm · Pitch %.2fµm · NPF %.1fs",
                            lens.id, lens.accessTier.name.take(6), lens.focalLengthMm, lens.pixelPitchUm, lens.npfSeconds()
                        ),
                        style = tabularStyle(size = 10.5.sp, color = colors.textSecondary)
                    )
                    Text(
                        text = "Ouvertures: $apStr · ISO ${lens.minIso}–${lens.maxIso} · Max ${String.format(Locale.US, "%.1fs", lens.maxExposureSec)}",
                        style = tabularStyle(size = 10.5.sp, color = colors.textSecondary)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TABLE COMPLÈTE HAL CAMERA2 (${rawEntries.size} IDS DÉTECTÉS)",
                    style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = colors.accent)
                )
            }

            items(rawEntries) { e ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surface)
                        .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID \"${e.cameraId}\" · ${e.facing} · ${e.eq35mm}mm eq.",
                            style = tabularStyle(size = 12.sp, weight = FontWeight.SemiBold, color = colors.textPrimary)
                        )
                        Text(
                            text = "${e.hardwareLevel} · ${e.cfaName}",
                            style = tabularStyle(size = 10.5.sp, color = colors.accent)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    ProbeKvRow("Source HAL", e.discoveredVia)
                    ProbeKvRow("Capteur / Pixels", "${e.sensorSizeMm} · ${e.activeArray} (${String.format(Locale.US, "%.2fµm", e.pixelPitchUm)})")
                    ProbeKvRow("Focale / Ouvertures", "${e.focalLengthsMm.joinToString { String.format(Locale.US, "%.2fmm", it) }} · ${e.apertures.joinToString { String.format(Locale.US, "f/%.2f", it) }}")
                    ProbeKvRow("ISO / Pose / NPF", "ISO ${e.isoRange} · ${e.exposureRangeSec} · NPF ${String.format(Locale.US, "%.1fs", e.npfSec)}")
                    ProbeKvRow("RAW_SENSOR / Multi", "${if (e.supportsRaw) e.rawMaxSize else "Non"} · Logique=${e.isLogicalMulti} Sub=${e.physicalSubIds}")
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProbeKvRow(label: String, value: String) {
    val colors = LocalAstroColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = tabularStyle(size = 10.sp, color = colors.textMuted)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = tabularStyle(size = 10.sp, color = colors.textSecondary)
        )
    }
}
