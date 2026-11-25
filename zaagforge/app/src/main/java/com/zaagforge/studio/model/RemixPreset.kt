package com.zaagforge.studio.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RemixParams(
    val targetBpm: Float = 210f,
    val genreTheme: RemixGenreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
    val tempoSyncMode: TempoSyncMode = TempoSyncMode.NEVER_SLOW,
    val drop1Kick: KickType = KickType.PIEP_UOK,
    val drop2Kick: KickType = KickType.ZAAG_PIEP_HYBRID,
    val drive: Float = 0.86f,
    val zaagAggression: Float = 0.88f,
    val bassSaturation: Float = 0.34f,
    val originalMix: Float = 0.86f,
    val vocalClarity: Float = 0.84f,
    val epicVocalBoost: Float = 0.84f,
    val vocalSidechain: Float = 0.24f,
    val kickLevel: Float = 0.80f,
    val bassCutHz: Float = 195f,
    val synthLayerAmount: Float = 0.55f,
    val percGrooveAmount: Float = 0.65f,
    val masterMode: MasterMode = MasterMode.STUDIO_CLEAN,
    val vocalPitchSemitones: Int = 0,
    val stutterAmount: Float = 0.45f,
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
        targetBpm = targetBpm.coerceIn(124f, 240f),
        drive = drive.coerceIn(0f, 1f),
        zaagAggression = zaagAggression.coerceIn(0f, 1f),
        bassSaturation = bassSaturation.coerceIn(0f, 1f),
        originalMix = originalMix.coerceIn(0.15f, 1f),
        vocalClarity = vocalClarity.coerceIn(0f, 1f),
        epicVocalBoost = epicVocalBoost.coerceIn(0f, 1f),
        vocalSidechain = vocalSidechain.coerceIn(0f, 0.85f),
        kickLevel = kickLevel.coerceIn(0.35f, 1.25f),
        bassCutHz = bassCutHz.coerceIn(110f, 340f),
        synthLayerAmount = synthLayerAmount.coerceIn(0f, 1f),
        percGrooveAmount = percGrooveAmount.coerceIn(0f, 1f),
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

    /** Preserves user's track-specific drop/grid alignment & tempo lock mode when switching styles/presets! */
    fun withPresetStyle(presetParams: RemixParams): RemixParams = presetParams.copy(
        tempoSyncMode = tempoSyncMode,
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
    val params: RemixParams,
    val isCustom: Boolean = false
)

object StudioPresets {
    val all: List<RemixPreset> = listOf(
        // ==================== 1. TECHNO NORMALE (Club / Hypnotic / Acid) ====================
        RemixPreset(
            id = "techno_club_hypnotic",
            name = "Techno Club 138",
            shortCode = "TECH-138",
            genreTag = "TECHNO NORMALE",
            description = "Charlotte de Witte / Amelie Lens · 909 Berlin Rumble, 16th hats, claps & voix pures",
            params = RemixParams(
                targetBpm = 138f,
                genreTheme = RemixGenreTheme.TECHNO_CLUB,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.TECHNO_ROLLING,
                drop2Kick = KickType.TECHNO_ROLLING,
                drive = 0.64f,
                zaagAggression = 0.56f,
                bassSaturation = 0.24f,
                originalMix = 0.90f,
                vocalClarity = 0.86f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.18f,
                kickLevel = 0.78f,
                bassCutHz = 175f,
                synthLayerAmount = 0.32f,
                percGrooveAmount = 0.78f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.0f,
                enableFakeDrop = false,
                enableScreeches = false,
                enablePitchKickMelody = false,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),
        RemixPreset(
            id = "acid_303_warehouse",
            name = "Acid 303 Warehouse",
            shortCode = "303-146",
            genreTag = "ACID TECHNO",
            description = "999999999 / Alignment · Kick 303 + ligne TB-303 résonante en clé & ride 909",
            params = RemixParams(
                targetBpm = 146f,
                genreTheme = RemixGenreTheme.TECHNO_CLUB,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.ACID_303_TECHNO,
                drop2Kick = KickType.TECHNO_ROLLING,
                drive = 0.74f,
                zaagAggression = 0.78f,
                bassSaturation = 0.30f,
                originalMix = 0.86f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.84f,
                vocalSidechain = 0.22f,
                kickLevel = 0.80f,
                bassCutHz = 185f,
                synthLayerAmount = 0.74f,
                percGrooveAmount = 0.80f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 0,
                stutterAmount = 0.12f,
                enableFakeDrop = false,
                enableScreeches = false,
                enablePitchKickMelody = false,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),

        // ==================== 2. HARD TECHNO (Sara Landry / Nico Moreno / Klang) ====================
        RemixPreset(
            id = "hard_techno_rave",
            name = "Hard Techno Rave",
            shortCode = "HTEC-156",
            genreTag = "HARD TECHNO",
            description = "Sara Landry / Nico Moreno · Kick warehouse slam + TB-303 + 909 claps/rides",
            params = RemixParams(
                targetBpm = 156f,
                genreTheme = RemixGenreTheme.HARD_TECHNO,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.HARD_TECHNO_RAVE,
                drop2Kick = KickType.ACID_303_TECHNO,
                drive = 0.84f,
                zaagAggression = 0.82f,
                bassSaturation = 0.38f,
                originalMix = 0.84f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.28f,
                kickLevel = 0.84f,
                bassCutHz = 195f,
                synthLayerAmount = 0.62f,
                percGrooveAmount = 0.82f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 0,
                stutterAmount = 0.28f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = false,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),
        RemixPreset(
            id = "schranz_industrial",
            name = "Schranz Warehouse",
            shortCode = "SCHR-160",
            genreTag = "HARD TECHNO / SCHRANZ",
            description = "Klangkuenstler / I Hate Models · Rumble industriel -> Hard Techno Slam",
            params = RemixParams(
                targetBpm = 160f,
                genreTheme = RemixGenreTheme.HARD_TECHNO,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.SCHRANZ_INDUSTRIAL,
                drop2Kick = KickType.HARD_TECHNO_RAVE,
                drive = 0.86f,
                zaagAggression = 0.84f,
                bassSaturation = 0.42f,
                originalMix = 0.82f,
                vocalClarity = 0.82f,
                epicVocalBoost = 0.84f,
                vocalSidechain = 0.30f,
                kickLevel = 0.84f,
                bassCutHz = 200f,
                synthLayerAmount = 0.50f,
                percGrooveAmount = 0.84f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 0,
                stutterAmount = 0.30f,
                enableFakeDrop = false,
                enableScreeches = true,
                enablePitchKickMelody = false,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),

        // ==================== 3. VRAI FRENCHCORE (Sefa / Dr. Peacock / Billx) ====================
        RemixPreset(
            id = "frenchcore_euphoric",
            name = "Frenchcore Sefa",
            shortCode = "FRNC-200",
            genreTag = "VRAI FRENCHCORE",
            description = "Sefa / Dr. Peacock · Kicks mélodiques 4 mesures + nappes supersaw épiques & voix limpides",
            params = RemixParams(
                targetBpm = 200f,
                genreTheme = RemixGenreTheme.FRENCHCORE,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.FRENCHCORE_MELODIC,
                drop2Kick = KickType.FRENCHCORE_MELODIC,
                drive = 0.78f,
                zaagAggression = 0.70f,
                bassSaturation = 0.24f,
                originalMix = 0.94f,
                vocalClarity = 0.90f,
                epicVocalBoost = 0.92f,
                vocalSidechain = 0.20f,
                kickLevel = 0.78f,
                bassCutHz = 180f,
                synthLayerAmount = 0.76f,
                percGrooveAmount = 0.70f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.10f,
                enableFakeDrop = false,
                enableScreeches = false,
                enablePitchKickMelody = true,
                enableTripletRolls = false,
                kickSwitchBars = 8
            )
        ),

        // ==================== 4. VRAI GABBER 909 (Angerfist / Neophyte / Tha Playah) ====================
        RemixPreset(
            id = "gabber_millennium",
            name = "Gabber 909 Angerfist",
            shortCode = "GABR-180",
            genreTag = "VRAI GABBER",
            description = "Angerfist / Neophyte · Kick 909 Mackie overdrive + Hoover rave stabs & claps 909",
            params = RemixParams(
                targetBpm = 180f,
                genreTheme = RemixGenreTheme.GABBER,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.GABBER_MILLENNIUM,
                drop2Kick = KickType.GABBER_MILLENNIUM,
                drive = 0.88f,
                zaagAggression = 0.84f,
                bassSaturation = 0.36f,
                originalMix = 0.86f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.26f,
                kickLevel = 0.82f,
                bassCutHz = 190f,
                synthLayerAmount = 0.66f,
                percGrooveAmount = 0.78f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 0,
                stutterAmount = 0.28f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 8
            )
        ),

        // ==================== 5. XTRA RAWSTYLE (Dual Damage / Warface) ====================
        RemixPreset(
            id = "xtra_rawstyle",
            name = "Xtra Rawstyle",
            shortCode = "RAW-162",
            genreTag = "XTRA RAWSTYLE",
            description = "Dual Damage / Warface · Klaplong Gated Raw -> Zaag Switch · Voix préservées",
            params = RemixParams(
                targetBpm = 162f,
                genreTheme = RemixGenreTheme.RAWSTYLE,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.RAWSTYLE_GATED,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.86f,
                zaagAggression = 0.84f,
                bassSaturation = 0.34f,
                originalMix = 0.86f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.26f,
                kickLevel = 0.82f,
                bassCutHz = 190f,
                synthLayerAmount = 0.45f,
                percGrooveAmount = 0.65f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.35f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 8
            )
        ),

        // ==================== 6. UPTEMPO / PIEP / KRACH / ZAAG (UOK / Noiseflow / Dimitri K / GPF) ====================
        RemixPreset(
            id = "uok_piep_madness",
            name = "UOK Piep Madness",
            shortCode = "UOK-210",
            genreTag = "PIEP / UPTEMPO",
            description = "Unicorn On Ketamine · 210 BPM · Piep -> Zaag Hybrid · Paroles épiques limpides",
            params = RemixParams(
                targetBpm = 210f,
                genreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.ZAAG_PIEP_HYBRID,
                drive = 0.88f,
                zaagAggression = 0.92f,
                bassSaturation = 0.32f,
                originalMix = 0.86f,
                vocalClarity = 0.86f,
                epicVocalBoost = 0.88f,
                vocalSidechain = 0.26f,
                kickLevel = 0.82f,
                bassCutHz = 200f,
                synthLayerAmount = 0.40f,
                percGrooveAmount = 0.60f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 4,
                stutterAmount = 0.50f,
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
            shortCode = "KRCH-205",
            genreTag = "DEUTSCHER KRACH",
            description = "Noiseflow · 205 BPM · Krach Métallique -> Zaag · Sub tendu & voix au premier plan",
            params = RemixParams(
                targetBpm = 205f,
                genreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.KRACH_NOISEFLOW,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.90f,
                zaagAggression = 0.94f,
                bassSaturation = 0.40f,
                originalMix = 0.84f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.28f,
                kickLevel = 0.84f,
                bassCutHz = 210f,
                synthLayerAmount = 0.40f,
                percGrooveAmount = 0.62f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 0,
                stutterAmount = 0.48f,
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
            description = "Krowdexx / Rebelion · 200 BPM · Pure Saw Wall -> Uptempo Bounce · Sub propre",
            params = RemixParams(
                targetBpm = 200f,
                genreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.ZAAG_CHAINSAW,
                drop2Kick = KickType.UPTEMPO_BOUNCE,
                drive = 0.90f,
                zaagAggression = 0.95f,
                bassSaturation = 0.34f,
                originalMix = 0.86f,
                vocalClarity = 0.86f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.26f,
                kickLevel = 0.82f,
                bassCutHz = 200f,
                synthLayerAmount = 0.40f,
                percGrooveAmount = 0.62f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.42f,
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
            shortCode = "BNCE-202",
            genreTag = "RAW UPTEMPO",
            description = "Dimitri K / Barber · 202 BPM · Pitch-Bounce Uptempo -> Zaag · Triplet Rolls",
            params = RemixParams(
                targetBpm = 202f,
                genreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.UPTEMPO_BOUNCE,
                drop2Kick = KickType.ZAAG_CHAINSAW,
                drive = 0.86f,
                zaagAggression = 0.84f,
                bassSaturation = 0.32f,
                originalMix = 0.88f,
                vocalClarity = 0.86f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.26f,
                kickLevel = 0.82f,
                bassCutHz = 195f,
                synthLayerAmount = 0.38f,
                percGrooveAmount = 0.64f,
                masterMode = MasterMode.STUDIO_CLEAN,
                vocalPitchSemitones = 0,
                stutterAmount = 0.40f,
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
            description = "GPF · 220 BPM · Full Piep Terror · Fake-Drop & Stutter 70%",
            params = RemixParams(
                targetBpm = 220f,
                genreTheme = RemixGenreTheme.UPTEMPO_ZAAG,
                tempoSyncMode = TempoSyncMode.NEVER_SLOW,
                drop1Kick = KickType.PIEP_UOK,
                drop2Kick = KickType.PIEP_UOK,
                drive = 0.96f,
                zaagAggression = 0.98f,
                bassSaturation = 0.42f,
                originalMix = 0.82f,
                vocalClarity = 0.84f,
                epicVocalBoost = 0.86f,
                vocalSidechain = 0.32f,
                kickLevel = 0.86f,
                bassCutHz = 215f,
                synthLayerAmount = 0.35f,
                percGrooveAmount = 0.60f,
                masterMode = MasterMode.CLUB_PUNCH,
                vocalPitchSemitones = 5,
                stutterAmount = 0.70f,
                enableFakeDrop = true,
                enableScreeches = true,
                enablePitchKickMelody = true,
                enableTripletRolls = true,
                kickSwitchBars = 4
            )
        )
    )

    fun byId(id: String): RemixPreset = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Lightweight JSON persistence for user-saved custom studio presets (SharedPreferences).
 */
object CustomPresetStore {
    private const val PREFS_NAME = "zaagforge_custom_presets_v3"
    private const val KEY_JSON = "custom_presets_json"

    fun loadAll(context: Context): List<RemixPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyList()
        return fromJsonString(raw)
    }

    fun savePreset(context: Context, name: String, params: RemixParams): List<RemixPreset> {
        val cleanName = name.trim().ifEmpty { "Mon Preset" }.take(28)
        val existing = loadAll(context).toMutableList()
        val id = "custom_${System.currentTimeMillis()}"
        val shortCode = "USR-${params.targetBpm.toInt()}"
        val preset = RemixPreset(
            id = id,
            name = cleanName,
            shortCode = shortCode,
            genreTag = "PERSO · ${params.genreTheme.shortLabel}",
            description = "${params.targetBpm.toInt()} BPM · ${params.drop1Kick.code} -> ${params.drop2Kick.code} · Voix ${(params.epicVocalBoost * 100).toInt()}%",
            params = params.sanitized(),
            isCustom = true
        )
        existing.add(0, preset)
        val trimmed = existing.take(20)
        persist(context, trimmed)
        return trimmed
    }

    fun deletePreset(context: Context, id: String): List<RemixPreset> {
        val updated = loadAll(context).filterNot { it.id == id }
        persist(context, updated)
        return updated
    }

    private fun persist(context: Context, list: List<RemixPreset>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_JSON, toJsonString(list)).apply()
    }

    fun toJsonString(list: List<RemixPreset>): String {
        val arr = JSONArray()
        list.forEach { preset ->
            val p = preset.params.sanitized()
            val obj = JSONObject()
            obj.put("id", preset.id)
            obj.put("name", preset.name)
            obj.put("shortCode", preset.shortCode)
            obj.put("genreTag", preset.genreTag)
            obj.put("description", preset.description)
            obj.put("targetBpm", p.targetBpm.toDouble())
            obj.put("genreTheme", p.genreTheme.nativeId)
            obj.put("tempoSyncMode", p.tempoSyncMode.nativeId)
            obj.put("drop1Kick", p.drop1Kick.nativeId)
            obj.put("drop2Kick", p.drop2Kick.nativeId)
            obj.put("drive", p.drive.toDouble())
            obj.put("zaagAggression", p.zaagAggression.toDouble())
            obj.put("bassSaturation", p.bassSaturation.toDouble())
            obj.put("originalMix", p.originalMix.toDouble())
            obj.put("vocalClarity", p.vocalClarity.toDouble())
            obj.put("epicVocalBoost", p.epicVocalBoost.toDouble())
            obj.put("vocalSidechain", p.vocalSidechain.toDouble())
            obj.put("kickLevel", p.kickLevel.toDouble())
            obj.put("bassCutHz", p.bassCutHz.toDouble())
            obj.put("synthLayerAmount", p.synthLayerAmount.toDouble())
            obj.put("percGrooveAmount", p.percGrooveAmount.toDouble())
            obj.put("masterMode", p.masterMode.nativeId)
            obj.put("vocalPitchSemitones", p.vocalPitchSemitones)
            obj.put("stutterAmount", p.stutterAmount.toDouble())
            obj.put("enableFakeDrop", p.enableFakeDrop)
            obj.put("enableScreeches", p.enableScreeches)
            obj.put("enablePitchKickMelody", p.enablePitchKickMelody)
            obj.put("enableTripletRolls", p.enableTripletRolls)
            obj.put("kickSwitchBars", p.kickSwitchBars)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun fromJsonString(json: String): List<RemixPreset> = runCatching {
        val arr = JSONArray(json)
        val out = ArrayList<RemixPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val params = RemixParams(
                targetBpm = o.optDouble("targetBpm", 156.0).toFloat(),
                genreTheme = RemixGenreTheme.fromNativeId(o.optInt("genreTheme", 0)),
                tempoSyncMode = TempoSyncMode.fromNativeId(o.optInt("tempoSyncMode", 0)),
                drop1Kick = KickType.fromNativeId(o.optInt("drop1Kick", 0)),
                drop2Kick = KickType.fromNativeId(o.optInt("drop2Kick", 2)),
                drive = o.optDouble("drive", 0.85).toFloat(),
                zaagAggression = o.optDouble("zaagAggression", 0.85).toFloat(),
                bassSaturation = o.optDouble("bassSaturation", 0.34).toFloat(),
                originalMix = o.optDouble("originalMix", 0.86).toFloat(),
                vocalClarity = o.optDouble("vocalClarity", 0.84).toFloat(),
                epicVocalBoost = o.optDouble("epicVocalBoost", 0.84).toFloat(),
                vocalSidechain = o.optDouble("vocalSidechain", 0.24).toFloat(),
                kickLevel = o.optDouble("kickLevel", 0.80).toFloat(),
                bassCutHz = o.optDouble("bassCutHz", 195.0).toFloat(),
                synthLayerAmount = o.optDouble("synthLayerAmount", 0.55).toFloat(),
                percGrooveAmount = o.optDouble("percGrooveAmount", 0.65).toFloat(),
                masterMode = MasterMode.fromNativeId(o.optInt("masterMode", 0)),
                vocalPitchSemitones = o.optInt("vocalPitchSemitones", 0),
                stutterAmount = o.optDouble("stutterAmount", 0.40).toFloat(),
                enableFakeDrop = o.optBoolean("enableFakeDrop", true),
                enableScreeches = o.optBoolean("enableScreeches", true),
                enablePitchKickMelody = o.optBoolean("enablePitchKickMelody", true),
                enableTripletRolls = o.optBoolean("enableTripletRolls", true),
                kickSwitchBars = o.optInt("kickSwitchBars", 4)
            ).sanitized()
            out.add(
                RemixPreset(
                    id = o.optString("id", "custom_$i"),
                    name = o.optString("name", "Mon Preset"),
                    shortCode = o.optString("shortCode", "USR"),
                    genreTag = o.optString("genreTag", "PERSO"),
                    description = o.optString("description", ""),
                    params = params,
                    isCustom = true
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}
