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
        subtitle = "Dimitri K Hollow Tok + Bounce",
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
        subtitle = "Dual Damage Klaplong + Gate",
        accentHex = 0xFFE5484D
    ),
    FRENCHCORE_MELODIC(
        nativeId = 6,
        code = "FRNC",
        displayName = "Peacock Frenchcore",
        subtitle = "Dr. Peacock / Sefa 2-Stage Woody Punch + Tail",
        accentHex = 0xFF1ED760
    ),
    SCHRANZ_INDUSTRIAL(
        nativeId = 7,
        code = "SCHR",
        displayName = "Schranz Klang",
        subtitle = "Klangkuenstler Industrial Loop",
        accentHex = 0xFF9EA6B8
    ),
    TECHNO_ROLLING(
        nativeId = 8,
        code = "TECH",
        displayName = "Techno 909",
        subtitle = "Charlotte / Amelie Berlin Rumble",
        accentHex = 0xFF00E5FF
    ),
    HARD_TECHNO_RAVE(
        nativeId = 9,
        code = "HTEC",
        displayName = "Hard Techno",
        subtitle = "Sara Landry / Nico Moreno Slam",
        accentHex = 0xFFFF7A00
    ),
    GABBER_MILLENNIUM(
        nativeId = 10,
        code = "GABR",
        displayName = "Gabber 909",
        subtitle = "Angerfist / Neophyte Mackie Bark",
        accentHex = 0xFFFF3B5C
    ),
    ACID_303_TECHNO(
        nativeId = 11,
        code = "ACID",
        displayName = "Acid 303 Kick",
        subtitle = "999999999 / Alignment 303 Pocket",
        accentHex = 0xFF76FF03
    ),
    HARDTEK_BOUNCE(
        nativeId = 12,
        code = "HTEK",
        displayName = "Hardtek Darktek",
        subtitle = "Darktek / Maissouille Tribecore + Bounce Bass",
        accentHex = 0xFFD500F9
    ),
    REBELION_PVC_ZAAG(
        nativeId = 13,
        code = "PVCZ",
        displayName = "Rebelion PVC",
        subtitle = "Rebelion Overdose Hollow PVC -> Scooped Zaag",
        accentHex = 0xFFFFD600
    ),
    COSY_UPTEMPO(
        nativeId = 14,
        code = "COSY",
        displayName = "Cosy Nightmare",
        subtitle = "Cosy Nightmare Glassy Tok + Melodic Sub",
        accentHex = 0xFFFF4081
    );

    companion object {
        fun fromNativeId(id: Int): KickType = entries.firstOrNull { it.nativeId == id } ?: ZAAG_CHAINSAW
    }
}
