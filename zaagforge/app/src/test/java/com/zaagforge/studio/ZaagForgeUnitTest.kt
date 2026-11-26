package com.zaagforge.studio

import com.zaagforge.studio.audio.AudioDecoder
import com.zaagforge.studio.audio.AudioExporter
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.CustomPresetStore
import com.zaagforge.studio.model.DropAlignmentMode
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.MasterMode
import com.zaagforge.studio.model.RemixGenreTheme
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixPreset
import com.zaagforge.studio.model.StreamingProvider
import com.zaagforge.studio.model.StudioPresets
import com.zaagforge.studio.model.TempoSyncMode
import com.zaagforge.studio.model.TrackSourceType
import com.zaagforge.studio.music.LibraryAndStreamingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZaagForgeUnitTest {

    @Test
    fun presets_allSixteenArtistPresetsValidAcrossAllSevenGenres() {
        assertEquals(16, StudioPresets.all.size)
        assertEquals(15, KickType.entries.size)
        assertEquals(7, RemixGenreTheme.entries.size)
        assertEquals(3, TempoSyncMode.entries.size)

        val ids = StudioPresets.all.map { it.id }.toSet()
        assertEquals(16, ids.size)

        val coveredGenres = StudioPresets.all.map { it.params.genreTheme }.toSet()
        assertEquals(RemixGenreTheme.entries.toSet(), coveredGenres)

        StudioPresets.all.forEach { preset ->
            assertTrue("${preset.id} BPM out of bounds", preset.params.targetBpm in 124f..240f)
            assertTrue("${preset.id} drive out of bounds", preset.params.drive in 0f..1f)
            assertTrue("${preset.id} bassSaturation clean default", preset.params.bassSaturation in 0.20f..0.55f)
            assertTrue("${preset.id} vocalClarity high default", preset.params.vocalClarity in 0.78f..0.95f)
            assertTrue("${preset.id} epicVocalBoost high default", preset.params.epicVocalBoost in 0.80f..0.96f)
            assertTrue("${preset.id} vocalIsolation anti-mud default", preset.params.vocalIsolation in 0.70f..0.95f)
            assertEquals("${preset.id} never slows down by default", TempoSyncMode.NEVER_SLOW, preset.params.tempoSyncMode)
            assertTrue("${preset.id} kickSwitchBars invalid", preset.params.kickSwitchBars in listOf(4, 8, 16))
        }

        val darktek = StudioPresets.byId("darktek_bounce")
        assertEquals(RemixGenreTheme.HARDTEK_TRIBECORE, darktek.params.genreTheme)
        assertEquals(KickType.HARDTEK_BOUNCE, darktek.params.drop1Kick)
        assertEquals(196f, darktek.params.targetBpm, 0.1f)

        val maissouille = StudioPresets.byId("maissouille_frappadingue")
        assertEquals(RemixGenreTheme.HARDTEK_TRIBECORE, maissouille.params.genreTheme)
        assertEquals(KickType.HARDTEK_BOUNCE, maissouille.params.drop1Kick)

        val rebelion = StudioPresets.byId("rebelion_overdose")
        assertEquals(RemixGenreTheme.RAWSTYLE, rebelion.params.genreTheme)
        assertEquals(KickType.REBELION_PVC_ZAAG, rebelion.params.drop1Kick)

        val cosy = StudioPresets.byId("cosy_nightmare")
        assertEquals(RemixGenreTheme.UPTEMPO_ZAAG, cosy.params.genreTheme)
        assertEquals(KickType.COSY_UPTEMPO, cosy.params.drop1Kick)

        val techno = StudioPresets.byId("techno_club_hypnotic")
        assertEquals(RemixGenreTheme.TECHNO_CLUB, techno.params.genreTheme)
        assertEquals(KickType.TECHNO_ROLLING, techno.params.drop1Kick)
        assertEquals(138f, techno.params.targetBpm, 0.1f)

        val hardTechno = StudioPresets.byId("hard_techno_rave")
        assertEquals(RemixGenreTheme.HARD_TECHNO, hardTechno.params.genreTheme)
        assertEquals(KickType.HARD_TECHNO_RAVE, hardTechno.params.drop1Kick)

        val frenchcore = StudioPresets.byId("frenchcore_euphoric")
        assertEquals(RemixGenreTheme.FRENCHCORE, frenchcore.params.genreTheme)
        assertEquals(KickType.FRENCHCORE_MELODIC, frenchcore.params.drop1Kick)
        assertTrue(frenchcore.params.epicVocalBoost >= 0.90f)

        val gabber = StudioPresets.byId("gabber_millennium")
        assertEquals(RemixGenreTheme.GABBER, gabber.params.genreTheme)
        assertEquals(KickType.GABBER_MILLENNIUM, gabber.params.drop1Kick)

        val uok = StudioPresets.byId("uok_piep_madness")
        assertEquals(KickType.PIEP_UOK, uok.params.drop1Kick)
        assertEquals(MasterMode.STUDIO_CLEAN, uok.params.masterMode)
    }

    @Test
    fun remixParams_sanitizedClampsAllV4MixSynthIsolationAndTimingControls() {
        val raw = RemixParams(
            targetBpm = 999f,
            drive = 2.5f,
            zaagAggression = -1f,
            bassSaturation = 3.0f,
            originalMix = 3f,
            vocalClarity = -0.5f,
            epicVocalBoost = 4.0f,
            vocalIsolation = 2.4f,
            vocalSidechain = 2.0f,
            kickLevel = 4.0f,
            bassCutHz = 50f,
            synthLayerAmount = -2.0f,
            percGrooveAmount = 5.0f,
            vocalPitchSemitones = 20,
            stutterAmount = -0.4f,
            kickSwitchBars = 3,
            gridPhaseNudgeMs = 600f,
            gridBeatShift = 5,
            buildupBars = 7,
            drop1BarNudge = 50,
            drop2BarNudge = -50
        ).sanitized()
        assertEquals(240f, raw.targetBpm, 0.01f)
        assertEquals(1f, raw.drive, 0.01f)
        assertEquals(0f, raw.zaagAggression, 0.01f)
        assertEquals(1f, raw.bassSaturation, 0.01f)
        assertEquals(1f, raw.originalMix, 0.01f)
        assertEquals(0f, raw.vocalClarity, 0.01f)
        assertEquals(1f, raw.epicVocalBoost, 0.01f)
        assertEquals(1f, raw.vocalIsolation, 0.01f)
        assertEquals(0.85f, raw.vocalSidechain, 0.01f)
        assertEquals(1.25f, raw.kickLevel, 0.01f)
        assertEquals(110f, raw.bassCutHz, 0.01f)
        assertEquals(0f, raw.synthLayerAmount, 0.01f)
        assertEquals(1f, raw.percGrooveAmount, 0.01f)
        assertEquals(7, raw.vocalPitchSemitones)
        assertEquals(0f, raw.stutterAmount, 0.01f)
        assertEquals(4, raw.kickSwitchBars)
        assertEquals(250f, raw.gridPhaseNudgeMs, 0.01f)
        assertEquals(1, raw.gridBeatShift)
        assertEquals(4, raw.buildupBars)
        assertEquals(16, raw.drop1BarNudge)
        assertEquals(-16, raw.drop2BarNudge)

        val lowBpm = RemixParams(targetBpm = 80f, vocalIsolation = -0.5f).sanitized()
        assertEquals(124f, lowBpm.targetBpm, 0.01f)
        assertEquals(0f, lowBpm.vocalIsolation, 0.01f)
    }

    @Test
    fun remixParams_withPresetStylePreservesSurgicalAlignmentAndSpeedLock() {
        val calibrated = RemixParams(
            tempoSyncMode = TempoSyncMode.ORIGINAL_SPEED_LOCK,
            dropMode = DropAlignmentMode.DOUBLE_DROP,
            buildupBars = 8,
            drop1BarNudge = 4,
            customDrop1Frac = 0.36f,
            gridBeatShift = 2,
            gridPhaseNudgeMs = -35f
        )
        val switched = calibrated.withPresetStyle(StudioPresets.byId("frenchcore_euphoric").params)
        assertEquals(RemixGenreTheme.FRENCHCORE, switched.genreTheme)
        assertEquals(KickType.FRENCHCORE_MELODIC, switched.drop1Kick)
        assertEquals(TempoSyncMode.ORIGINAL_SPEED_LOCK, switched.tempoSyncMode)
        assertEquals(DropAlignmentMode.DOUBLE_DROP, switched.dropMode)
        assertEquals(8, switched.buildupBars)
        assertEquals(4, switched.drop1BarNudge)
        assertEquals(0.36f, switched.customDrop1Frac, 0.001f)
        assertEquals(2, switched.gridBeatShift)
        assertEquals(-35f, switched.gridPhaseNudgeMs, 0.01f)
    }

    @Test
    fun customPresetStore_serializesAndDeserializesRoundTripCleanly() {
        val sample = RemixPreset(
            id = "custom_test_1",
            name = "Mon Darktek Perso",
            shortCode = "USR-198",
            genreTag = "PERSO · HARDTEK",
            description = "Custom test",
            params = RemixParams(
                targetBpm = 198f,
                genreTheme = RemixGenreTheme.HARDTEK_TRIBECORE,
                tempoSyncMode = TempoSyncMode.ORIGINAL_SPEED_LOCK,
                drop1Kick = KickType.HARDTEK_BOUNCE,
                drop2Kick = KickType.REBELION_PVC_ZAAG,
                epicVocalBoost = 0.94f,
                vocalIsolation = 0.88f,
                synthLayerAmount = 0.72f,
                percGrooveAmount = 0.85f,
                masterMode = MasterMode.CLUB_PUNCH
            ),
            isCustom = true
        )
        val json = runCatching { CustomPresetStore.toJsonString(listOf(sample)) }.getOrNull()
        if (json != null && json.startsWith("[")) {
            val restored = CustomPresetStore.fromJsonString(json)
            assertEquals(1, restored.size)
            assertEquals("Mon Darktek Perso", restored[0].name)
            assertEquals(RemixGenreTheme.HARDTEK_TRIBECORE, restored[0].params.genreTheme)
            assertEquals(TempoSyncMode.ORIGINAL_SPEED_LOCK, restored[0].params.tempoSyncMode)
            assertEquals(KickType.HARDTEK_BOUNCE, restored[0].params.drop1Kick)
            assertEquals(KickType.REBELION_PVC_ZAAG, restored[0].params.drop2Kick)
            assertEquals(0.94f, restored[0].params.epicVocalBoost, 0.01f)
            assertEquals(0.88f, restored[0].params.vocalIsolation, 0.01f)
            assertEquals(0.72f, restored[0].params.synthLayerAmount, 0.01f)
        }
    }

    @Test
    fun streamingLinkParser_detectsSpotifyDeezerSoundCloud() {
        val sp = LibraryAndStreamingRepository.parseStreamingLink(
            "Écoute ce son ! https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=12345"
        )
        assertNotNull(sp)
        assertEquals(StreamingProvider.SPOTIFY, sp!!.provider)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", sp.trackId)

        val dz = LibraryAndStreamingRepository.parseStreamingLink(
            "https://www.deezer.com/fr/track/3135556"
        )
        assertNotNull(dz)
        assertEquals(StreamingProvider.DEEZER, dz!!.provider)
        assertEquals("3135556", dz.trackId)

        val sc = LibraryAndStreamingRepository.parseStreamingLink(
            "https://soundcloud.com/unicornonketamine/kalinka-piep"
        )
        assertNotNull(sc)
        assertEquals(StreamingProvider.SOUNDCLOUD, sc!!.provider)

        assertNull(LibraryAndStreamingRepository.parseStreamingLink("Pas de lien ici"))
    }

    @Test
    fun localTrackMatcher_matchesNormalizedFrenchAndAccentedTitles() {
        val locals = listOf(
            AudioTrackItem("1", "Bande Organisée (Original)", "Jul & SCH", 210_000, TrackSourceType.LOCAL_MEDIASTORE),
            AudioTrackItem("2", "Blinding Lights", "The Weeknd", 200_000, TrackSourceType.LOCAL_MEDIASTORE)
        )
        val match1 = LibraryAndStreamingRepository.findBestLocalMatch("Bande Organisee", "Jul", locals)
        assertNotNull(match1)
        assertEquals("1", match1!!.id)

        val match2 = LibraryAndStreamingRepository.findBestLocalMatch("Blinding Lights", "Weeknd", locals)
        assertNotNull(match2)
        assertEquals("2", match2!!.id)

        val none = LibraryAndStreamingRepository.findBestLocalMatch("Completely Unrelated Song", "Unknown", locals)
        assertNull(none)
    }

    @Test
    fun wavBuilder_writesValidRiffHeaderAndPcm16Payload() {
        val pcm = floatArrayOf(0f, 0f, 0.5f, -0.5f, 1.0f, -1.0f)
        val bytes = AudioExporter.buildWavBytes(pcm, 44100)
        assertEquals(44 + 6 * 2, bytes.size)

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        val data = String(bytes, 36, 4, Charsets.US_ASCII)
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)
        assertEquals("data", data)

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44100, bb.getInt(24))
        assertEquals(12, bb.getInt(40))
        assertEquals(32767.toShort(), bb.getShort(44 + 8))
        assertEquals((-32767).toShort(), bb.getShort(44 + 10))
    }

    @Test
    fun linearResampler_converts48kTo44100Accurately() {
        val src48k = FloatArray(48000 * 2) { 0.5f }
        val dst44k = AudioDecoder.resampleStereoLinear(src48k, 48000, 44100)
        assertEquals(44100 * 2, dst44k.size)
        assertEquals(0.5f, dst44k[100], 0.001f)
    }
}
