package com.astropro.xiaomi14u

import com.astropro.xiaomi14u.camera.CaptureMode
import com.astropro.xiaomi14u.ephemeris.ArSkyProjector
import com.astropro.xiaomi14u.ephemeris.AstroEphemeris
import com.astropro.xiaomi14u.ephemeris.ObserverLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArSkyProjectorTest {

    @Test
    fun portraitFov_matchesXiaomi14Ultra23mmAnd120mmOptics() {
        // Xiaomi 14 Ultra 1" LYT-900 main lens: ~8.7mm physical focal, ~13.1 x 9.8 mm sensor (4:3)
        val fov23 = ArSkyProjector.computePortraitFov(
            focalLengthMm = 8.7f,
            sensorWidthMm = 13.1f,
            sensorHeightMm = 9.8f
        )
        assertTrue("23mm portrait HFOV expected ~55-65°, got ${fov23.hFovDeg}", fov23.hFovDeg in 54.0..66.0)
        assertTrue("23mm portrait VFOV expected ~70-80°, got ${fov23.vFovDeg}", fov23.vFovDeg in 68.0..82.0)
        assertTrue("Portrait VFOV must exceed HFOV", fov23.vFovDeg > fov23.hFovDeg)

        // Xiaomi 14 Ultra 120mm periscope: ~22.6mm physical focal, ~6.4 x 4.8 mm IMX858 sensor
        val fov120 = ArSkyProjector.computePortraitFov(
            focalLengthMm = 22.6f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f
        )
        assertTrue("120mm portrait HFOV expected ~10-15°, got ${fov120.hFovDeg}", fov120.hFovDeg in 9.0..16.0)
        assertTrue("120mm FOV must be much narrower than 23mm", fov120.hFovDeg < fov23.hFovDeg * 0.3)
    }

    @Test
    fun galacticToEquatorial_matchesIauJ2000GalacticCenterAndNorthPole() {
        // Galactic Center (l = 0°, b = 0°) -> RA ~ 17h 45.6m (17.76h), Dec ~ -28.94°
        val (raGc, decGc) = ArSkyProjector.galacticToEquatorial(0.0, 0.0)
        assertEquals(17.76, raGc, 0.05)
        assertEquals(-28.94, decGc, 0.15)

        // North Galactic Pole (b = +90°) -> RA ~ 12h 51.4m (12.857h), Dec = +27.128°
        val (_, decNgp) = ArSkyProjector.galacticToEquatorial(0.0, 90.0)
        assertEquals(27.128, decNgp, 0.05)
    }

    @Test
    fun projectSkyPoint_centersExactPointingAndRespectsOffsetsAndRoll() {
        val fov = ArSkyProjector.computePortraitFov(8.7f, 13.1f, 9.8f)

        // Target dead-center ahead of camera
        val center = ArSkyProjector.projectSkyPoint(
            objAltDeg = 45.0,
            objAzDeg = 180.0,
            camAzDeg = 180.0,
            camAltDeg = 45.0,
            camRollDeg = 0.0,
            fov = fov
        )
        assertTrue(center.inFront)
        assertTrue(center.inViewport)
        assertEquals(0.5f, center.u, 1e-3f)
        assertEquals(0.5f, center.v, 1e-3f)
        assertEquals(0.0, center.angularSeparationDeg, 1e-3)

        // Target 10° right and 10° higher -> u > 0.5, v < 0.5
        val upRight = ArSkyProjector.projectSkyPoint(
            objAltDeg = 55.0,
            objAzDeg = 190.0,
            camAzDeg = 180.0,
            camAltDeg = 45.0,
            camRollDeg = 0.0,
            fov = fov
        )
        assertTrue(upRight.inFront)
        assertTrue(upRight.inViewport)
        assertTrue("Right offset should increase u: ${upRight.u}", upRight.u > 0.55f)
        assertTrue("Up offset should decrease v: ${upRight.v}", upRight.v < 0.45f)

        // Target behind camera -> inFront == false
        val behind = ArSkyProjector.projectSkyPoint(
            objAltDeg = 45.0,
            objAzDeg = 0.0,
            camAzDeg = 180.0,
            camAltDeg = 45.0,
            camRollDeg = 0.0,
            fov = fov
        )
        assertFalse(behind.inFront)
        assertFalse(behind.inViewport)
    }

    @Test
    fun projectOverlay_projectsMilkyWayAndAutoGuidesPolarisInStarTrailsMode() {
        // Summer night in Paris (2026-07-15 23:00 UTC) when Milky Way & Polaris are high
        val epochMillis = 1784156400000L
        val paris = ObserverLocation("Paris", 48.8566, 2.3522)
        val targets = AstroEphemeris.computeTargetVisibilities(epochMillis, paris)
        val catalogIds = AstroEphemeris.TARGET_CATALOGUE.map { it.id }.toSet()
        for (arId in ArSkyProjector.AR_TARGET_SHORT_LABELS.keys) {
            assertTrue("AR target id '$arId' missing from TARGET_CATALOGUE", arId in catalogIds)
        }
        val fov = ArSkyProjector.computePortraitFov(8.7f, 13.1f, 9.8f)

        // 1. STAR_TRAILS mode: automatically guides to Polaris even if selectedTargetId == null
        val offPolaris = ArSkyProjector.projectOverlay(
            epochMillis = epochMillis,
            location = paris,
            camAzDeg = 20.0,
            camAltDeg = 40.0,
            camRollDeg = 0.0,
            fov = fov,
            targets = targets,
            captureMode = CaptureMode.STAR_TRAILS,
            selectedTargetId = null
        )
        assertNotNull("STAR_TRAILS should auto-guide to Polaris", offPolaris.guidance)
        assertEquals("polaris", offPolaris.guidance!!.targetId)
        assertFalse(offPolaris.guidance!!.isCentered)
        assertTrue(offPolaris.guidance!!.pillText.contains("Polaris"))

        // Point directly at Polaris -> centered & visible!
        val polarisVis = targets.first { it.target.id == "polaris" }
        val onPolaris = ArSkyProjector.projectOverlay(
            epochMillis = epochMillis,
            location = paris,
            camAzDeg = polarisVis.azimuthDeg,
            camAltDeg = polarisVis.altitudeDeg,
            camRollDeg = 0.0,
            fov = fov,
            targets = targets,
            captureMode = CaptureMode.STAR_TRAILS,
            selectedTargetId = null
        )
        assertNotNull(onPolaris.guidance)
        assertTrue("Polaris should lock as centered", onPolaris.guidance!!.isCentered)
        assertTrue(onPolaris.guidance!!.pillText.contains("CENTRÉ"))
        assertTrue(onPolaris.visibleTargets.any { it.id == "polaris" && it.isGuided })

        // 2. DEEP_SKY mode with no selected target -> no unrequested guidance pill, Milky Way segments projected when pointing South
        val sgrVis = targets.first { it.target.id == "sgr_a" }
        val onMilkyWay = ArSkyProjector.projectOverlay(
            epochMillis = epochMillis,
            location = paris,
            camAzDeg = sgrVis.azimuthDeg,
            camAltDeg = sgrVis.altitudeDeg.coerceAtLeast(15.0),
            camRollDeg = 0.0,
            fov = fov,
            targets = targets,
            captureMode = CaptureMode.DEEP_SKY,
            selectedTargetId = null
        )
        assertNull("No guidance pill when no target chosen outside STAR_TRAILS", onMilkyWay.guidance)
        assertTrue("Expected Milky Way plane segments when facing Galactic Center", onMilkyWay.milkyWaySegments.isNotEmpty())
    }
}
