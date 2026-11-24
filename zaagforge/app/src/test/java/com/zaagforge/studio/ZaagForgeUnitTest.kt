package com.zaagforge.studio

import com.zaagforge.studio.audio.AudioDecoder
import com.zaagforge.studio.audio.AudioExporter
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.StreamingProvider
import com.zaagforge.studio.model.StudioPresets
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
    fun presets_allEightPresetsValidAndDistinct() {
        assertEquals(8, StudioPresets.all.size)
        val ids = StudioPresets.all.map { it.id }.toSet()
        assertEquals(8, ids.size)
        StudioPresets.all.forEach { preset ->
            assertTrue("${preset.id} BPM out of bounds", preset.params.targetBpm in 150f..240f)
            assertTrue("${preset.id} drive out of bounds", preset.params.drive in 0f..1f)
            assertTrue("${preset.id} kickSwitchBars invalid", preset.params.kickSwitchBars in listOf(4, 8))
        }
        val uok = StudioPresets.byId("uok_piep_madness")
        assertEquals(KickType.PIEP_UOK, uok.params.drop1Kick)
        assertTrue(uok.params.enableFakeDrop)
        assertEquals(5, uok.params.vocalPitchSemitones)

        val noiseflow = StudioPresets.byId("noiseflow_krach")
        assertEquals(KickType.KRACH_NOISEFLOW, noiseflow.params.drop1Kick)
        assertEquals(KickType.ZAAG_CHAINSAW, noiseflow.params.drop2Kick)
    }

    @Test
    fun remixParams_sanitizedClampsExtremeValues() {
        val raw = RemixParams(
            targetBpm = 999f,
            drive = 2.5f,
            zaagAggression = -1f,
            originalMix = 3f,
            vocalPitchSemitones = 20,
            stutterAmount = -0.4f,
            kickSwitchBars = 3
        ).sanitized()
        assertEquals(240f, raw.targetBpm, 0.01f)
        assertEquals(1f, raw.drive, 0.01f)
        assertEquals(0f, raw.zaagAggression, 0.01f)
        assertEquals(1f, raw.originalMix, 0.01f)
        assertEquals(7, raw.vocalPitchSemitones)
        assertEquals(0f, raw.stutterAmount, 0.01f)
        assertEquals(4, raw.kickSwitchBars)
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
        val pcm = floatArrayOf(0f, 0f, 0.5f, -0.5f, 1.0f, -1.0f) // 3 stereo frames
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
