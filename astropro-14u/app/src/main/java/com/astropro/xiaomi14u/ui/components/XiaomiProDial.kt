package com.astropro.xiaomi14u.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astropro.xiaomi14u.ui.theme.LocalAstroColors
import com.astropro.xiaomi14u.ui.theme.tabularStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Authentic Xiaomi HyperOS Pro Horizontal Precision Tick-Wheel Dial.
 *
 * Features crisp Canvas graduated ruler ticks, center Xiaomi Gold (or Red Night Vision)
 * needle, tabular readout above the needle, tactile CLOCK_TICK haptic detents on every step,
 * and optional quick-reset pills (AUTO / ∞ / NPF).
 */
@Composable
fun XiaomiProTickDial(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onIndexChanged: (Int) -> Unit,
    quickActionLabel: String? = null,
    quickActionActive: Boolean = false,
    onQuickAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    tertiaryActionLabel: String? = null,
    tertiaryActionActive: Boolean = false,
    onTertiaryAction: (() -> Unit)? = null,
    quaternaryActionLabel: String? = null,
    quaternaryActionActive: Boolean = false,
    onQuaternaryAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAstroColors.current
    val view = LocalView.current
    val safeIdx = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
    val currentLabel = labels.getOrElse(safeIdx) { "--" }

    var dragAccumPx by remember(title) { mutableFloatStateOf(0f) }
    var liveIndex by remember(title, safeIdx) { mutableIntStateOf(safeIdx) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.panelTranslucent)
            .padding(top = 6.dp, bottom = 6.dp)
    ) {
        // Header row: quick pills + centered numeric readout + close tap
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (quickActionLabel != null && onQuickAction != null) {
                    DialQuickPill(
                        label = quickActionLabel,
                        active = quickActionActive,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onQuickAction()
                        }
                    )
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    DialQuickPill(
                        label = secondaryActionLabel,
                        active = false,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSecondaryAction()
                        }
                    )
                }
                if (tertiaryActionLabel != null && onTertiaryAction != null) {
                    DialQuickPill(
                        label = tertiaryActionLabel,
                        active = tertiaryActionActive,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onTertiaryAction()
                        }
                    )
                }
                if (quaternaryActionLabel != null && onQuaternaryAction != null) {
                    DialQuickPill(
                        label = quaternaryActionLabel,
                        active = quaternaryActionActive,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onQuaternaryAction()
                        }
                    )
                }
            }

            // Active value directly aligned over center needle
            Text(
                text = if (quickActionActive && quickActionLabel != null) quickActionLabel else currentLabel,
                style = tabularStyle(size = 13.5.sp, weight = FontWeight.SemiBold, color = colors.accent)
            )

            Text(
                text = title,
                style = tabularStyle(size = 11.sp, weight = FontWeight.Medium, color = colors.textSecondary),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Precision ruler tick canvas with smooth horizontal drag
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .pointerInput(labels.size, title) {
                    val stepPx = 22f
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumPx = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumPx -= dragAmount
                            val deltaSteps = (dragAccumPx / stepPx).toInt()
                            if (deltaSteps != 0) {
                                dragAccumPx -= deltaSteps * stepPx
                                val next = (liveIndex + deltaSteps).coerceIn(0, (labels.size - 1).coerceAtLeast(0))
                                if (next != liveIndex) {
                                    liveIndex = next
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onIndexChanged(next)
                                }
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width * 0.5f
                val bottomY = size.height - 4.dp.toPx()
                val spacing = 11.dp.toPx()
                val visibleHalfTicks = (size.width / (2f * spacing)).roundToInt() + 2

                for (offset in -visibleHalfTicks..visibleHalfTicks) {
                    val idx = liveIndex + offset
                    if (idx < 0 || idx >= labels.size) continue
                    val x = centerX + offset * spacing
                    val distRatio = (abs(x - centerX) / (size.width * 0.48f)).coerceIn(0f, 1f)
                    val alpha = (1f - distRatio * 0.82f).coerceIn(0.12f, 1f)
                    val isMajor = (idx % 5 == 0) || idx == 0 || idx == labels.lastIndex
                    val tickHeight = if (isMajor) 15.dp.toPx() else 8.5.dp.toPx()
                    val strokeW = if (isMajor) 1.5.dp.toPx() else 1.0.dp.toPx()

                    drawLine(
                        color = colors.textPrimary.copy(alpha = alpha * if (isMajor) 0.92f else 0.55f),
                        start = Offset(x, bottomY),
                        end = Offset(x, bottomY - tickHeight),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }

                // Authentic Xiaomi Gold / Red center precision needle
                drawLine(
                    color = colors.accent,
                    start = Offset(centerX, bottomY + 1.dp.toPx()),
                    end = Offset(centerX, bottomY - 22.dp.toPx()),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun DialQuickPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAstroColors.current
    val borderCol = if (active) colors.accent else colors.borderSubtle
    val textCol = if (active) colors.accent else colors.textSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderCol, RoundedCornerShape(4.dp))
            .background(if (active) colors.accent.copy(alpha = 0.14f) else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = tabularStyle(size = 11.sp, weight = FontWeight.SemiBold, color = textCol)
        )
    }
}
