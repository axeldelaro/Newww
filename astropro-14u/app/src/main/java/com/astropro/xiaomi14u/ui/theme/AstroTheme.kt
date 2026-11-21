package com.astropro.xiaomi14u.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Authentic Xiaomi HyperOS Leica Camera Pro Color System + 1-Tap Astronomical Vision Nocturne (OLED Red Mode).
 *
 * Zero gradients, zero neon AI slop — pure OLED #000000 black, crisp Leica white/silver,
 * Xiaomi Pro active Gold (#F5B82E), and sparing Leica Red (#E31E24).
 */
@Immutable
data class AstroColors(
    val isNightVision: Boolean,
    val background: Color,
    val panelTranslucent: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val borderActive: Color,
    val accent: Color,          // Xiaomi Pro Gold #F5B82E (or Pure Red #CC1818 in Night Vision)
    val leicaRed: Color,        // Leica Red #E31E24 strictly for rec dot / active capture / alerts
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val shutterRing: Color,
    val shutterCore: Color,
    val histR: Color,
    val histG: Color,
    val histB: Color,
    val levelOk: Color
)

val HyperOsProColors = AstroColors(
    isNightVision = false,
    background = Color(0xFF000000),
    panelTranslucent = Color(0xCC000000),
    surface = Color(0xFF141414),
    surfaceElevated = Color(0xFF1E1E1E),
    borderSubtle = Color(0x28FFFFFF),
    borderActive = Color(0xFFF5B82E),
    accent = Color(0xFFF5B82E),
    leicaRed = Color(0xFFE31E24),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0x99FFFFFF),
    textMuted = Color(0x59FFFFFF),
    shutterRing = Color(0xFFFFFFFF),
    shutterCore = Color(0xFFFFFFFF),
    histR = Color(0xFFE05252),
    histG = Color(0xFF52C478),
    histB = Color(0xFF5294E0),
    levelOk = Color(0xFFF5B82E)
)

/**
 * Pure scotopic astronomical red-on-black OLED theme to preserve dark adaptation at night.
 */
val VisionNocturneColors = AstroColors(
    isNightVision = true,
    background = Color(0xFF000000),
    panelTranslucent = Color(0xD8000000),
    surface = Color(0xFF100202),
    surfaceElevated = Color(0xFF1B0404),
    borderSubtle = Color(0x45CC1818),
    borderActive = Color(0xFFCC1818),
    accent = Color(0xFFCC1818),
    leicaRed = Color(0xFFE02020),
    textPrimary = Color(0xFFCC1818),
    textSecondary = Color(0xFF881010),
    textMuted = Color(0xFF5A0A0A),
    shutterRing = Color(0xFFAA1414),
    shutterCore = Color(0xFFCC1818),
    histR = Color(0xFFCC1818),
    histG = Color(0xFF991212),
    histB = Color(0xFF660C0C),
    levelOk = Color(0xFFE02020)
)

val LocalAstroColors = staticCompositionLocalOf { HyperOsProColors }

fun tabularStyle(
    size: TextUnit = 12.sp,
    weight: FontWeight = FontWeight.Medium,
    color: Color = Color.White,
    letterSpacing: TextUnit = 0.2.sp
): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size,
    color = color,
    letterSpacing = letterSpacing,
    fontFeatureSettings = "tnum"
)

@Composable
fun AstroProTheme(
    nightVision: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (nightVision) VisionNocturneColors else HyperOsProColors
    val m3Scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = Color.Black,
        secondary = palette.accent,
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.surfaceElevated,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.borderSubtle,
        error = palette.leicaRed
    )

    CompositionLocalProvider(LocalAstroColors provides palette) {
        MaterialTheme(
            colorScheme = m3Scheme,
            content = content
        )
    }
}
