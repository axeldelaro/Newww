package com.zaagforge.studio.model

enum class KickType(
    val nativeId: Int,
    val code: String,
    val displayName: String,
    val subtitle: String,
    val accentHex: Long
) {
    PIEP_UOK(
        nativeId = 0,
        code = "PIEP",
        displayName = "Piep UOK / GPF",
        subtitle = "High-Q 2.3 kHz Nasal Scream",
        accentHex = 0xFFFF2E1F
    ),
    KRACH_NOISEFLOW(
        nativeId = 1,
        code = "KRCH",
        displayName = "Krach Noiseflow",
        subtitle = "FM Metallic Comb Crunch",
        accentHex = 0xFFFF6B00
    ),
    ZAAG_CHAINSAW(
        nativeId = 2,
        code = "ZAAG",
        displayName = "Zaag Chainsaw",
        subtitle = "OTT Saw Wall + Clean Sub",
        accentHex = 0xFFFF2E1F
    ),
    UPTEMPO_BOUNCE(
        nativeId = 3,
        code = "BNCE",
        displayName = "Uptempo Bounce",
        subtitle = "Hollow Tok + Pitch Bounce",
        accentHex = 0xFFFFB800
    ),
    ZAAG_PIEP_HYBRID(
        nativeId = 4,
        code = "HYBR",
        displayName = "Zaag-Piep Hybrid",
        subtitle = "Piep Attack -> Zaag Tail",
        accentHex = 0xFFFF4D1A
    ),
    RAWSTYLE_GATED(
        nativeId = 5,
        code = "RAWG",
        displayName = "Rawstyle Gated",
        subtitle = "Klaplong + Gated Reverb",
        accentHex = 0xFFE5484D
    ),
    FRENCHCORE_MELODIC(
        nativeId = 6,
        code = "FRNC",
        displayName = "Frenchcore Melodic",
        subtitle = "Pitched Warm Lead Pocket",
        accentHex = 0xFF1ED760
    ),
    SCHRANZ_INDUSTRIAL(
        nativeId = 7,
        code = "SCHR",
        displayName = "Schranz Industrial",
        subtitle = "Dark Rolling Rumble + Grit",
        accentHex = 0xFF9EA6B8
    );

    companion object {
        fun fromNativeId(id: Int): KickType = entries.firstOrNull { it.nativeId == id } ?: ZAAG_CHAINSAW
    }
}
