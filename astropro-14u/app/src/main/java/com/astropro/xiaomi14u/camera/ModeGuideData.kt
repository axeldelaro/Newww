package com.astropro.xiaomi14u.camera

import androidx.annotation.DrawableRes
import com.astropro.xiaomi14u.R

data class ModeGuideBullet(
    val label: String,
    val detail: String
)

data class ModeRecommendedPreset(
    val recommendedZoomBadge: String,       // "0.5", "1", "3.2", "5"
    val recommendedLensLabel: String,       // e.g. "1x 23mm"
    val recommendedEqMm: Int,               // 12, 23, 75, 120
    val useNpfShutter: Boolean,
    val shutterSec: Double,
    val shutterChipLabel: String,           // "NPF", "1/250s", "15s", "4.0s"
    val iso: Int,
    val focusInfinity: Boolean = true,
    val wbKelvin: Int = 4200,
    // Mode-specific options
    val deepSkyFrames: Int? = null,
    val deepSkySigmaKappa: Float? = null,
    val planetaryFrames: Int? = null,
    val planetaryKeepPercent: Float? = null,
    val planetaryAutoWavelets: Boolean? = null,
    val starTrailStyle: StarTrailStyle? = null,
    val cometTailLength: CometTailLength? = null,
    val starTrailMaxFrames: Int? = null,
    val timelapseIntervalSec: Int? = null,
    val timelapseCount: Int? = null,
    val timelapseFps: Int? = null,
    val calibrationKind: CalibrationKind? = null,
    val calibrationFrames: Int? = null
) {
    fun applyToModeOptions(current: ModeOptions): ModeOptions = current.copy(
        deepSkyFrames = deepSkyFrames ?: current.deepSkyFrames,
        deepSkySigmaKappa = deepSkySigmaKappa ?: current.deepSkySigmaKappa,
        planetaryFrames = planetaryFrames ?: current.planetaryFrames,
        planetaryKeepPercent = planetaryKeepPercent ?: current.planetaryKeepPercent,
        planetaryAutoWavelets = planetaryAutoWavelets ?: current.planetaryAutoWavelets,
        starTrailStyle = starTrailStyle ?: current.starTrailStyle,
        cometTailLength = cometTailLength ?: current.cometTailLength,
        starTrailMaxFrames = starTrailMaxFrames ?: current.starTrailMaxFrames,
        timelapseIntervalSec = timelapseIntervalSec ?: current.timelapseIntervalSec,
        timelapseCount = timelapseCount ?: current.timelapseCount,
        timelapseFps = timelapseFps ?: current.timelapseFps,
        calibrationKind = calibrationKind ?: current.calibrationKind,
        calibrationFrames = calibrationFrames ?: current.calibrationFrames
    )

    fun summaryChips(): List<String> = buildList {
        add(recommendedLensLabel)
        if (focusInfinity) add("∞")
        add("ISO $iso")
        add(shutterChipLabel)
        add("${wbKelvin}K")
        deepSkyFrames?.let { add("${it} poses") }
        planetaryKeepPercent?.let { add("Top ${(it * 100).toInt()}%") }
        starTrailStyle?.let { add(it.label) }
        timelapseIntervalSec?.let { add("Int. ${it}s") }
        calibrationKind?.let { add(it.label) }
    }
}

data class ModeGuideEntry(
    val mode: CaptureMode,
    @DrawableRes val drawableRes: Int,
    val title: String,
    val subtitle: String,
    val quickHint: String,
    val sampleCaption: String,
    val briefPoints: List<ModeGuideBullet>,
    val recommendedPreset: ModeRecommendedPreset
)

/**
 * Curated field astrophotography guidance and Xiaomi 14 Ultra presets for all 6 capture modes.
 * Kept concise and scannable at night under Vision Nocturne.
 */
object ModeGuideData {

    val ORDERED_MODES: List<CaptureMode> = listOf(
        CaptureMode.ASTRO_PRO,
        CaptureMode.DEEP_SKY,
        CaptureMode.LUNAR_PLANETARY,
        CaptureMode.STAR_TRAILS,
        CaptureMode.TIMELAPSE,
        CaptureMode.CALIBRATION
    )

    val ENTRIES: List<ModeGuideEntry> = listOf(
        ModeGuideEntry(
            mode = CaptureMode.ASTRO_PRO,
            drawableRes = R.drawable.sample_astro_pro,
            title = "ASTRO PRO",
            subtitle = "ASTRO PRO · Pose unique RAW 16-bit",
            quickHint = "Astro Pro · Pose unique RAW 16-bit · Touchez NPF pour figer les étoiles",
            sampleCaption = "23mm · f/1.63 · ISO 1600 · 7.5s · RAW 16b",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Voie Lactée, constellations, paysages nocturnes et aurores en pose unique."),
                ModeGuideBullet("Objectif 14U", "1x 23mm f/1.63 (LYT-900 1\") ou 0.5x 12mm UGA pour arche complète."),
                ModeGuideBullet("Réglage clé", "Focus ∞ vérifié à la Loupe 10×, ISO 1600, pose NPF et retardateur 2s."),
                ModeGuideBullet("Moteur C++", "Débayering RAW 16-bit linéaire + Dark/Flat auto + sauvegarde DNG & .astro32.")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "1",
                recommendedLensLabel = "1x 23mm",
                recommendedEqMm = 23,
                useNpfShutter = true,
                shutterSec = 8.0,
                shutterChipLabel = "NPF",
                iso = 1600,
                focusInfinity = true,
                wbKelvin = 4200
            )
        ),
        ModeGuideEntry(
            mode = CaptureMode.DEEP_SKY,
            drawableRes = R.drawable.sample_deep_sky,
            title = "CIEL PROFOND",
            subtitle = "CIEL PROFOND · Empilement Kappa-Sigma",
            quickHint = "Ciel Profond · Trépied requis · Aligne les étoiles et supprime les satellites",
            sampleCaption = "75mm · 60 × 4s · Dark/Flat · Kappa-Sigma 2.5σ",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Galaxies (M31), nébuleuses (M42, M8, NGC 7000) et amas globulaires faibles."),
                ModeGuideBullet("Objectif 14U", "3.2x 75mm f/1.8 ou Périscope 5x 120mm f/2.5 pour le ciel profond."),
                ModeGuideBullet("Réglage clé", "Trépied fixe, 40–60 poses NPF, ISO 1600 et Masters Dark + Flat activés."),
                ModeGuideBullet("Moteur C++", "Alignement par triangles d'étoiles RANSAC + rejet Kappa-Sigma des traînées.")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "3.2",
                recommendedLensLabel = "3.2x 75mm",
                recommendedEqMm = 75,
                useNpfShutter = true,
                shutterSec = 4.0,
                shutterChipLabel = "NPF",
                iso = 1600,
                focusInfinity = true,
                wbKelvin = 4200,
                deepSkyFrames = 60,
                deepSkySigmaKappa = 2.5f
            )
        ),
        ModeGuideEntry(
            mode = CaptureMode.LUNAR_PLANETARY,
            drawableRes = R.drawable.sample_lunar_planetary,
            title = "LUNE / PLANÈTES",
            subtitle = "LUNE / PLANÈTES · Lucky Imaging & Ondelettes",
            quickHint = "Lune / Planètes · Rafale rapide · Conserve les poses nettes + Ondelettes",
            sampleCaption = "120mm · 60 poses · Top 20% · Ondelettes",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Lune minérale, cratères détaillés, terminateur lunaire, Jupiter et Saturne."),
                ModeGuideBullet("Objectif 14U", "Périscope 5x 120mm f/2.5 (IMX858) pour le détail optique maximal."),
                ModeGuideBullet("Réglage clé", "Pose rapide 1/250s à ISO 200 (zéro surexposition) + Focus Peaking actif."),
                ModeGuideBullet("Moteur C++", "Tri netteté Tenengrad (Top 20%), alignement sub-pixel et Ondelettes À Trous.")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "5",
                recommendedLensLabel = "5x 120mm",
                recommendedEqMm = 120,
                useNpfShutter = false,
                shutterSec = 1.0 / 250.0,
                shutterChipLabel = "1/250s",
                iso = 200,
                focusInfinity = true,
                wbKelvin = 5200,
                planetaryFrames = 60,
                planetaryKeepPercent = 0.20f,
                planetaryAutoWavelets = true
            )
        ),
        ModeGuideEntry(
            mode = CaptureMode.STAR_TRAILS,
            drawableRes = R.drawable.sample_star_trails,
            title = "STAR TRAILS",
            subtitle = "STAR TRAILS · Filés d'étoiles & mode Comète",
            quickHint = "Star Trails · Trépied verrouillé · Filés circulaires en mode Standard ou Comète",
            sampleCaption = "12mm · Comète · 120 × 15s · f/1.8",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Circumpolaires autour de Polaris ou arcs d'étoiles sur premier plan."),
                ModeGuideBullet("Objectif 14U", "0.5x 12mm UGA f/1.8 ou 1x 23mm sur trépied parfaitement immobile."),
                ModeGuideBullet("Réglage clé", "Poses de 15s à ISO 800, focus ∞, style Comète pour traînées progressives."),
                ModeGuideBullet("Moteur C++", "Accumulation éclaircissante en direct float32 (Max ou décroissance Comète).")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "0.5",
                recommendedLensLabel = "0.5x 12mm",
                recommendedEqMm = 12,
                useNpfShutter = false,
                shutterSec = 15.0,
                shutterChipLabel = "15s",
                iso = 800,
                focusInfinity = true,
                wbKelvin = 4000,
                starTrailStyle = StarTrailStyle.COMET,
                cometTailLength = CometTailLength.MEDIUM,
                starTrailMaxFrames = 120
            )
        ),
        ModeGuideEntry(
            mode = CaptureMode.TIMELAPSE,
            drawableRes = R.drawable.sample_timelapse,
            title = "TIMELAPSE",
            subtitle = "TIMELAPSE · Séquence nocturne MP4 H.264",
            quickHint = "Timelapse · Intervallomètre · Encode directement en MP4 24/30 fps",
            sampleCaption = "12mm · 180 poses · 5s · MP4 24 fps",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Lever de la Voie Lactée, défilement du ciel et transitions crépusculaires."),
                ModeGuideBullet("Objectif 14U", "0.5x 12mm UGA ou 1x 23mm f/1.63 pour un grand champ lumineux."),
                ModeGuideBullet("Réglage clé", "180 vues, intervalle 5s, pose 4s (strictement ≤ intervalle), WB fixe."),
                ModeGuideBullet("Moteur C++", "Développement linéaire homogène sans scintillement + encodage MP4 direct.")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "0.5",
                recommendedLensLabel = "0.5x 12mm",
                recommendedEqMm = 12,
                useNpfShutter = false,
                shutterSec = 4.0,
                shutterChipLabel = "4.0s",
                iso = 1600,
                focusInfinity = true,
                wbKelvin = 4200,
                timelapseIntervalSec = 5,
                timelapseCount = 180,
                timelapseFps = 24
            )
        ),
        ModeGuideEntry(
            mode = CaptureMode.CALIBRATION,
            drawableRes = R.drawable.sample_calibration,
            title = "CALIBRATION",
            subtitle = "CALIBRATION · Masters Dark, Flat & Bias",
            quickHint = "Calibration · Capture vos Masters Dark/Flat/Bias pour nettoyer le capteur",
            sampleCaption = "Avant : bruit thermique & vignettage  |  Après : Dark + Flat",
            briefPoints = listOf(
                ModeGuideBullet("Usage idéal", "Éliminer pixels chauds, amp glow thermique et vignettage des objectifs."),
                ModeGuideBullet("Objectif 14U", "À créer pour chaque focale utilisée (23mm, 75mm, 120mm) aux mêmes ISO."),
                ModeGuideBullet("Réglage clé", "DARK/BIAS : objectif 100% couvert · FLAT : surface blanche uniforme 1/60s."),
                ModeGuideBullet("Moteur C++", "Empilement médian float32 enregistré en Master .cal32 appliqué en direct.")
            ),
            recommendedPreset = ModeRecommendedPreset(
                recommendedZoomBadge = "1",
                recommendedLensLabel = "1x 23mm",
                recommendedEqMm = 23,
                useNpfShutter = true,
                shutterSec = 8.0,
                shutterChipLabel = "NPF",
                iso = 1600,
                focusInfinity = true,
                wbKelvin = 4200,
                calibrationKind = CalibrationKind.DARK,
                calibrationFrames = 15
            )
        )
    )

    private val byMode: Map<CaptureMode, ModeGuideEntry> = ENTRIES.associateBy { it.mode }

    fun guideFor(mode: CaptureMode): ModeGuideEntry =
        byMode[mode] ?: ENTRIES.first()
}
