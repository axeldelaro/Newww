package com.zaagforge.studio.model

data class RemixParams(
    val targetBpm: Float = 210f,
    val drop1Kick: KickType = KickType.PIEP_UOK,
    val drop2Kick: KickType = KickType.ZAAG_PIEP_HYBRID,
    val drive: Float = 0.86f,
    val zaagAggression: Float = 0.88f,
    val bassSaturation: Float = 0.34f,
    val originalMix: Float = 0.82f,
    val vocalClarity: Float = 0.80f,
    val vocalSidechain: Float = 0.34f,
    val kickLevel: Float = 0.84f,
    val bassCutHz: Float = 200f,
    val masterMode: MasterMode = MasterMode.STUDIO_CLEAN,
    val vocalPitchSemitones: Int = 5,
    val stutterAmount: Float = 0.55f,
    val enableFakeDrop: Boolean = true,
    val enableScreeches: Boolean = true,
    val enablePitchKickMelody: Boolean = true,
    val enableTripletRolls: Boolean = true,
    val kickSwitchBars: Int = 4,
    // Surgical Timing & Cut Controls
    val sourceBpmOverride: Float = 0f, // 0f = auto detected BPM
    val gridPhaseNudgeMs: Float = 0f,  // -250..+250 ms
    val gridBeatShift: Int = 0,        // 0..3 beats downbeat shift
    val dropMode: DropAlignmentMode = DropAlignmentMode.AUTO_CHORUS,
    val buildupBars: Int = 4,          // 4, 8, or 12 exact bars
    val drop1BarNudge: Int = 0,        // -16..+16 bars nudge
    val drop2BarNudge: Int = 0,        // -16..+16 bars nudge
    val customDrop1Frac: Float = -1f,  // >= 0f when user taps "POSER DROP 1 ICI"
    val customDrop2Frac: Float = -1f   // >= 0f when user taps "POSER DROP 2 ICI"
) {
    fun sanitized(): RemixParams = copy(
        targetBpm = targetBpm.coerceIn(150f, 240f),
        drive = drive.coerceIn(0f, 1f),
        zaagAggression = zaagAggression.coerceIn(0f, 1f),
        bassSaturation = bassSaturation.coerceIn(0f, 1f),
        originalMix = originalMix.coerceIn(0.15f, 1f),
        vocalClarity = vocalClarity.coerceIn(0f, 1f),
        vocalSidechain = vocalSidechain.coerceIn(0f, 0.85f),
        kickLevel = kickLevel.coerceIn(0.4f, 1.2f),
        bassCutHz = bassCutHz.coerceIn(110f, 340f),
        vocalPitchSemitones = vocalPitchSemitones.coerceIn(-5, 7),
        stutterAmount = stutterAmount.coerceIn(0f, 1f),
        kickSwitchBars = if (kickSwitchBars in listOf(4, 8, 16)) kickSwitchBars else 4,
        sourceBpmOverride = if (sourceBpmOverride <= 0f) 0f else sourceBpmOverride.coerceIn(60f, 240f),
        gridPhaseNudgeMs = gridPhaseNudgeMs.coerceIn(-250f, 250f),
        gridBeatShift = ((gridBeatShift % 4) + 4) % 4,
        buildupBars = if (buildupBars in listOf(4, 8, 12)) buildupBars else 4,
        drop1BarNudge = drop1BarNudge.coerceIn(-16, 16),
        drop2BarNudge = drop2BarNudge.coerceIn(-16, 16),
        customDrop1Frac = if (customDrop1Frac < 0f) -1f else customDrop1Frac.coerceIn(0.02f, 0.92f),
        customDrop2Frac = if (customDrop2Frac < 0f) -1f else customDrop2Frac.coerceIn(0.08f, 0.96f)
    )

    /** Preserves user's surgical track-specific cut/grid alignment when switching styles/presets! */
    fun withPresetStyle(presetParams: RemixParams): RemixParams = presetParams.copy(
        sourceBpmOverride = sourceBpmOverride,
        gridPhaseNudgeMs = gridPhaseNudgeMs,
        gridBeatShift = gridBeatShift,
        dropMode = dropMode,
        buildupBars = buildupBars,
        drop1BarNudge = drop1BarNudge,
        drop2BarNudge = drop2BarNudge,
        customDrop1Frac = customDrop1Frac,
        customDrop2Frac = customDrop2Frac
    ).sanitized()
}

data class RemixPreset(
    val id: String,
    val name: String,
    val shortCode: String,
    val genreTag: String,
    val description: String,
    val params: RemixParams
)

object StudioPresets {
    val all: List<RemixPreset> = listOf(
        RemixPreset(
            id = "uok_piep_madness",
            name = "UOK Piep Madness",
            shortCode = "UOK-210",
            genreTag = "PIEP / UPTEMPO",
            description = "210 BPM · Piep -> Zaag Hybrid · Paroles +5 st limpides · Sub propre",
            params = RemixParams(
                targetBpm = 210f,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.ZAAG_PIEP_HYBRID,
                drive = 0.88f,
                zaagAggression = 0.92f,
                bassSaturation = 0.32f,
                originalMix = 0.82f,
                vocalClarity = 0.84f,
                vocalSidechain = 0.34f,
                kickLevel = 0.84f,
                bassCutHz = 200f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 5,
                stutterAmount = 0.55f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        ),
        RemixPreset(
            id = "noiseflow_krach",
            name = "Noiseflow Krach",
            shortCode = "KRACH-205",
            genreTag = "DEUTSCHER KRACH",
            description = "205 BPM · Krach Métallique -> Zaag · Sub tendu & voix claires",
            params = RemixParams(
                targetBpm = 205f,
                drop1Kick = KickType.KRACH_NOISEFLOW,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.90f,
                zaagAggression = 0.94f,
                bassSaturation = 0.42f,
                originalMix = 0.78f,
                vocalClarity = 0.80f,
                vocalSidechain = 0.38f,
                kickLevel = 0.86f,
                bassCutHz = 210f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = -1,
                stutterAmount = 0.50f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = false,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        ),
        RemixPreset(
            id = "pure_zaag_chainsaw",
            name = "Pure Zaag Chainsaw",
            shortCode = "ZAAG-200",
            genreTag = "ZAAGKICK",
            description = "200 BPM · Pure Saw Wall -> Uptempo Bounce · Sub propre 35%",
            params = RemixParams(
                targetBpm = 200f,
                drop1Kick = KickType.ZAAG_CHAINSAW,
                drop2Kick = KickType.UPTEMPO_BOUNCE,
                drive = 0.90f,
                zaagAggression = 0.96f,
                bassSaturation = 0.35f,
                originalMix = 0.82f,
                vocalClarity = 0.82f,
                vocalSidechain = 0.35f,
                kickLevel = 0.85f,
                bassCutHz = 200f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.45f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        ),
        RemixPreset(
            id = "bounce_uptempo",
            name = "Bounce Uptempo",
            shortCode = "BNCE-200",
            genreTag = "RAW UPTEMPO",
            description = "200 BPM · Pitch-Bounce Uptempo -> Zaag · Triplet Kick Rolls",
            params = RemixParams(
                targetBpm = 200f,
                drop1Kick = KickType.UPTEMPO_BOUNCE,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.85f,
                zaagAggression = 0.84f,
                bassSaturation = 0.34f,
                originalMix = 0.84f,
                vocalClarity = 0.82f,
                vocalSidechain = 0.34f,
                kickLevel = 0.84f,
                bassCutHz = 195f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.42f,
                enableFakeDrop = true,
                enableScreeches = false,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        ),
        RemixPreset(
            id = "gpf_piep_terror",
            name = "GPF Piep Terror",
            shortCode = "GPF-220",
            genreTag = "EXTREME PIEP",
            description = "220 BPM · Full Piep Terror · Fake-Drop & Stutter 75%",
            params = RemixParams(
                targetBpm = 220f,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.PIEP_UOK,
                drive = 0.96f,
                zaagAggression = 0.98f,
                bassSaturation = 0.45f,
                originalMix = 0.78f,
                vocalClarity = 0.80f,
                vocalSidechain = 0.40f,
                kickLevel = 0.88f,
                bassCutHz = 215f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 6,
                stutterAmount = 0.75f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        ),
        RemixPreset(
            id = "xtra_rawstyle",
            name = "Xtra Rawstyle",
            shortCode = "RAW-160",
            genreTag = "XTRA RAW",
            description = "160 BPM · Klaplong Gated Raw -> Zaag Switch · Voix préservées",
            params = RemixParams(
                targetBpm = 160f,
                drop1Kick = KickType.RAWSTYLE_GATED,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.86f,
                zaagAggression = 0.84f,
                bassSaturation = 0.36f,
                originalMix = 0.84f,
                vocalClarity = 0.82f,
                vocalSidechain = 0.34f,
                kickLevel = 0.84f,
                bassCutHz = 190f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.38f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 8
            )
        ),
        RemixPreset(
            id = "frenchcore_euphoric",
            name = "Frenchcore Euphoric",
            shortCode = "FRNC-208",
            genreTag = "FRENCHCORE",
            description = "208 BPM · Kick mélodique propre · Paroles & mélodies au premier plan",
            params = RemixParams(
                targetBpm = 208f,
                drop1Kick = KickType.FRENCHCORE_MELODIC,
                drop2Kick = KickType.UPTEMPO_BOUNCE,
                drive = 0.76f,
                zaagAggression = 0.68f,
                bassSaturation = 0.25f,
                originalMix = 0.92f,
                vocalClarity = 0.88f,
                vocalSidechain = 0.26f,
                kickLevel = 0.80f,
                bassCutHz = 180f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.20f,
                enableFakeDrop = false,
                enableScreeches = false,
                enablePitchKickMelody = true,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),
        RemixPreset(
            id = "schranz_industrial",
            name = "Schranz Industrial",
            shortCode = "SCHR-158",
            genreTag = "SCHRANZ / IND",
            description = "158 BPM · Warehouse Rumble -> Krach · Basse contrôlée",
            params = RemixParams(
                targetBpm = 158f,
                drop1Kick = KickType.SCHRANZ_INDUSTRIAL,
                drop2Kick = KickType.KRACH_NOISEFLOW,
                drive = 0.86f,
                zaagAggression = 0.84f,
                bassSaturation = 0.44f,
                originalMix = 0.74f,
                vocalClarity = 0.76f,
                vocalSidechain = 0.40f,
                kickLevel = 0.86f,
                bassCutHz = 210f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = -2,
                stutterAmount = 0.35f,
                enableFakeDrop = false,
                enableScreeches = true,
                enablePitchKickMelody = false,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        )
    )

    fun byId(id: String): RemixPreset = all.firstOrNull { it.id == id } ?: all.first()
}
