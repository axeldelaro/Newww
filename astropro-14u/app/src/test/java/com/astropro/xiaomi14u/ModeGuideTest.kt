package com.astropro.xiaomi14u

import com.astropro.xiaomi14u.camera.CaptureMode
import com.astropro.xiaomi14u.camera.ModeGuideData
import com.astropro.xiaomi14u.camera.ModeOptions
import com.astropro.xiaomi14u.camera.StarTrailStyle
import com.astropro.xiaomi14u.nativeengine.LabPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeGuideTest {

    @Test
    fun allSixModes_haveUniqueGuidesAndDistinctSampleDrawables() {
        val allModes = CaptureMode.entries
        assertEquals(6, allModes.size)
        assertEquals(allModes.toSet(), ModeGuideData.ORDERED_MODES.toSet())
        assertEquals(allModes.toSet(), ModeGuideData.ENTRIES.map { it.mode }.toSet())

        val drawables = mutableSetOf<Int>()
        for (mode in allModes) {
            val guide = ModeGuideData.guideFor(mode)
            assertEquals(mode, guide.mode)
            assertTrue("Title blank for $mode", guide.title.isNotBlank())
            assertTrue("Subtitle blank for $mode", guide.subtitle.isNotBlank())
            assertTrue("Quick hint blank for $mode", guide.quickHint.isNotBlank())
            assertTrue("EXIF caption blank for $mode", guide.sampleCaption.isNotBlank())
            assertNotEquals("Invalid drawable ID for $mode", 0, guide.drawableRes)
            assertTrue("Duplicate drawable ID for $mode", drawables.add(guide.drawableRes))
        }
        assertEquals(6, drawables.size)
    }

    @Test
    fun allSixModes_haveFourBriefScannableBulletPoints() {
        for (mode in CaptureMode.entries) {
            val guide = ModeGuideData.guideFor(mode)
            assertEquals("Expected exactly 4 brief points for $mode", 4, guide.briefPoints.size)
            for (pt in guide.briefPoints) {
                assertTrue("Bullet label blank in $mode", pt.label.isNotBlank())
                assertTrue("Bullet detail blank in $mode", pt.detail.isNotBlank())
                assertTrue(
                    "Bullet detail too verbose in $mode (${pt.detail.length} chars)",
                    pt.detail.length <= 120
                )
            }
        }
    }

    @Test
    fun allSixModes_haveSensibleXiaomi14UltraPresets() {
        val validBadges = setOf("0.5", "1", "3.2", "5")
        val validEqMm = setOf(12, 23, 75, 120)

        for (mode in CaptureMode.entries) {
            val preset = ModeGuideData.guideFor(mode).recommendedPreset
            assertTrue("Invalid zoom badge '${preset.recommendedZoomBadge}' for $mode", preset.recommendedZoomBadge in validBadges)
            assertTrue("Invalid focal eq ${preset.recommendedEqMm}mm for $mode", preset.recommendedEqMm in validEqMm)
            assertTrue("Focus must default to infinity for $mode", preset.focusInfinity)
            assertTrue("ISO out of range (${preset.iso}) for $mode", preset.iso in 50..6400)
            assertTrue("Shutter must be positive for $mode", preset.shutterSec > 0.0 && preset.shutterSec <= 30.0)
            assertTrue("WB Kelvin out of range (${preset.wbKelvin}) for $mode", preset.wbKelvin in 2500..8000)
            assertTrue("Summary chips should not be empty for $mode", preset.summaryChips().size >= 4)
        }

        // Verify mode-specific preset highlights
        val lunar = ModeGuideData.guideFor(CaptureMode.LUNAR_PLANETARY).recommendedPreset
        assertEquals("5", lunar.recommendedZoomBadge)
        assertFalse(lunar.useNpfShutter)
        assertTrue("Lunar shutter should be fast (<= 1/100s)", lunar.shutterSec <= 0.01)

        val deepSky = ModeGuideData.guideFor(CaptureMode.DEEP_SKY).recommendedPreset
        assertTrue(deepSky.useNpfShutter)
        val dsOpts = deepSky.applyToModeOptions(ModeOptions())
        assertEquals(60, dsOpts.deepSkyFrames)

        val trails = ModeGuideData.guideFor(CaptureMode.STAR_TRAILS).recommendedPreset
        assertEquals("0.5", trails.recommendedZoomBadge)
        val trOpts = trails.applyToModeOptions(ModeOptions())
        assertEquals(StarTrailStyle.COMET, trOpts.starTrailStyle)
    }

    @Test
    fun labPresets_coverAllSixProfilesWithValidParameters() {
        assertEquals(6, LabPreset.entries.size)
        for (preset in LabPreset.entries) {
            assertTrue(preset.label.isNotBlank())
            val arr = preset.params.toFloatArray()
            assertEquals(15, arr.size)
            assertTrue(preset.params.saturation in 0f..2.0f)
            assertTrue(preset.params.denoiseStrength in 0f..1.0f)
            assertTrue(preset.params.bgRemovalStrength in 0f..1.0f)
            assertTrue(preset.params.scnrStrength in 0f..1.0f)
        }

        assertTrue(LabPreset.MILKY_WAY.params.asinhBeta >= 60f)
        assertTrue(LabPreset.NEBULA.params.asinhBeta >= 100f)
        assertTrue(LabPreset.NEBULA.params.scnrStrength >= 0.7f)
        assertTrue(LabPreset.CRISP_MOON.params.waveletFine >= 0.6f)
        assertTrue(LabPreset.URBAN_SKY.params.bgRemovalStrength >= 0.85f)
        assertFalse(LabPreset.RESET.params.autoStf)
    }
}
