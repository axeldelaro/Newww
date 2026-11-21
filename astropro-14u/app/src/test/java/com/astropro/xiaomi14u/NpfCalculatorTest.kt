package com.astropro.xiaomi14u

import com.astropro.xiaomi14u.camera.NpfCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpfCalculatorTest {

    @Test
    fun xiaomi14Ultra_23mm_lyt900_npf_isAccurate() {
        // Xiaomi 14 Ultra 1" LYT-900: real f=8.7mm, f/1.63, binned 12.5MP pixel pitch ~3.2um
        // Expected NPF at celestial equator: (35 * 1.63 + 30 * 3.2) / 8.7 = 153.05 / 8.7 = 17.59s
        // Native 50MP pitch 1.6um: (35 * 1.63 + 30 * 1.6) / 8.7 = 105.05 / 8.7 = 12.07s
        val npfBinned = NpfCalculator.computeNpfSeconds(1.63, 3.2, 8.7, 0.0)
        assertEquals(17.59, npfBinned, 0.1)

        val npfNative = NpfCalculator.computeNpfSeconds(1.63, 1.6, 8.7, 0.0)
        assertEquals(12.07, npfNative, 0.1)
    }

    @Test
    fun xiaomi14Ultra_120mm_periscope_npf_isShorterThanWideAngle() {
        val npf23 = NpfCalculator.computeNpfSeconds(1.63, 1.6, 8.7, 0.0)
        val npf120 = NpfCalculator.computeNpfSeconds(2.5, 1.4, 19.0, 0.0)
        assertTrue("Periscope NPF ($npf120) must be shorter than 23mm ($npf23)", npf120 < npf23)
        assertEquals((35.0 * 2.5 + 30.0 * 1.4) / 19.0, npf120, 0.05)
    }

    @Test
    fun declinationCorrection_increasesAllowedExposureNearPoles() {
        val npfEq = NpfCalculator.computeNpfSeconds(1.63, 1.6, 8.7, 0.0)
        val npf60 = NpfCalculator.computeNpfSeconds(1.63, 1.6, 8.7, 60.0)
        assertEquals(npfEq * 2.0, npf60, 0.1)
    }

    @Test
    fun snapToCameraShutter_picksConservativeStandardSpeed() {
        val snapped = NpfCalculator.snapToCameraShutter(7.4, 1.0 / 4000.0, 30.0)
        assertEquals(6.0, snapped, 0.01)

        val exactSnapped = NpfCalculator.snapToCameraShutter(8.0, 1.0 / 4000.0, 30.0)
        assertEquals(8.0, exactSnapped, 0.01)
    }

    @Test
    fun shutterAndIntegrationFormatting_isClean() {
        assertEquals("1/250s", NpfCalculator.formatShutter(1.0 / 250.0))
        assertEquals("8.0s", NpfCalculator.formatShutter(8.0))
        assertEquals("15s", NpfCalculator.formatShutter(15.0))
        assertEquals("2m 24s", NpfCalculator.formatIntegrationTime(144.0))
        assertEquals("1h 10m", NpfCalculator.formatIntegrationTime(4200.0))
    }
}
