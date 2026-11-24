package com.zaagforge.studio.model

data class RemixParams(
    val targetBpm: Float = 210f,
    val drop1Kick: KickType = KickType.PIEP_UOK,
    val drop2Kick: KickType = KickType.ZAAG_PIEP_HYBRID,
    val drive: Float = 0.90f,
    val zaagAggression: Float = 0.92f,
    val originalMix: Float = 0.72f,
    val vocalPitchSemitones: Int = 5,
    val stutterAmount: Float = 0.75f,
    val enableFakeDrop: Boolean = true,
    val enableScreeches: Boolean = true,
    val enablePitchKickMelody: Boolean = true,
    val enableTripletRolls: Boolean = true,
    val kickSwitchBars: Int = 4
) {
    fun sanitized(): RemixParams = copy(
        targetBpm = targetBpm.coerceIn(150f, 240f),
        drive = drive.coerceIn(0f, 1f),
        zaagAggression = zaagAggression.coerceIn(0f, 1f),
        originalMix = originalMix.coerceIn(0f, 1f),
        vocalPitchSemitones = vocalPitchSemitones.coerceIn(-5, 7),
        stutterAmount = stutterAmount.coerceIn(0f, 1f),
        kickSwitchBars = if (kickSwitchBars == 8) 8 else 4
    )
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
            description = "210 BPM · Piep -> Zaag Hybrid · Vocal +5 st · Fake-Drop & Stutter 75%",
            params = RemixParams(
                targetBpm = 210f,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.ZAAG_PIEP_HYBRID,
                drive = 0.92f,
                zaagAggression = 0.95f,
                originalMix = 0.72f,
                vocalPitchSemitones = 5,
                stutterAmount = 0.75f,
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
            description = "205 BPM · Metallic Comb Krach -> Zaag · Screeches ON · Drive 95%",
            params = RemixParams(
                targetBpm = 205f,
                drop1Kick = KickType.KRACH_NOISEFLOW,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.95f,
                zaagAggression = 0.96f,
                originalMix = 0.65f,
                vocalPitchSemitones = -1,
                stutterAmount = 0.65f,
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
            description = "200 BPM · Pure Saw Wall -> Uptempo Bounce · Zaag 100% · Pitch Kick ON",
            params = RemixParams(
                targetBpm = 200f,
                drop1Kick = KickType.ZAAG_CHAINSAW,
                drop2Kick = KickType.UPTEMPO_BOUNCE,
                drive = 0.94f,
                zaagAggression = 1.00f,
                originalMix = 0.70f,
                vocalPitchSemitones = 0,
                stutterAmount = 0.55f,
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
            description = "200 BPM · Pitch-Bouncing Uptempo -> Zaag · Triplet Kick Rolls ON",
            params = RemixParams(
                targetBpm = 200f,
                drop1Kick = KickType.UPTEMPO_BOUNCE,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.88f,
                zaagAggression = 0.86f,
                originalMix = 0.74f,
                vocalPitchSemitones = 0,
                stutterAmount = 0.50f,
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
            description = "220 BPM · Full Piep Terror · Extreme Fake-Drop & Stutter 90%",
            params = RemixParams(
                targetBpm = 220f,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.PIEP_UOK,
                drive = 1.00f,
                zaagAggression = 1.00f,
                originalMix = 0.68f,
                vocalPitchSemitones = 6,
                stutterAmount = 0.90f,
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
            description = "160 BPM · Klaplong Gated Raw -> Zaag Switch · Festival Fake-Drop",
            params = RemixParams(
                targetBpm = 160f,
                drop1Kick = KickType.RAWSTYLE_GATED,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.88f,
                zaagAggression = 0.85f,
                originalMix = 0.76f,
                vocalPitchSemitones = 0,
                stutterAmount = 0.45f,
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
            description = "208 BPM · Pitched Melodic Kick -> Bounce · Preserves Melodies",
            params = RemixParams(
                targetBpm = 208f,
                drop1Kick = KickType.FRENCHCORE_MELODIC,
                drop2Kick = KickType.UPTEMPO_BOUNCE,
                drive = 0.80f,
                zaagAggression = 0.72f,
                originalMix = 0.88f,
                vocalPitchSemitones = 0,
                stutterAmount = 0.25f,
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
            description = "158 BPM · Dark Warehouse Rumble -> Krach · Heavy Industrial Grit",
            params = RemixParams(
                targetBpm = 158f,
                drop1Kick = KickType.SCHRANZ_INDUSTRIAL,
                drop2Kick = KickType.KRACH_NOISEFLOW,
                drive = 0.90f,
                zaagAggression = 0.88f,
                originalMix = 0.62f,
                vocalPitchSemitones = -2,
                stutterAmount = 0.40f,
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
