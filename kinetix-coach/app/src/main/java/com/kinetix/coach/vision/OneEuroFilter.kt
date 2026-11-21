package com.kinetix.coach.vision

import kotlin.math.PI
import kotlin.math.abs

/**
 * Adaptive Casiez et al. (2012) One-Euro low-pass filter for a single scalar signal.
 * Eliminates static landmark jitter (< 0.5° angular noise) while dynamically increasing
 * cutoff frequency during high-velocity explosive movements for near-zero phase lag.
 */
class OneEuroFilter1D(
    private val minCutoff: Float = 1.25f,
    private val beta: Float = 0.08f,
    private val dCutoff: Float = 1.0f
) {
    private var initialized: Boolean = false
    private var xPrev: Float = 0f
    private var dxPrev: Float = 0f
    private var lastTimestampMs: Long = 0L

    fun filter(value: Float, timestampMs: Long): Float {
        if (!value.isFinite()) return if (initialized) xPrev else 0f

        if (!initialized) {
            initialized = true
            xPrev = value
            dxPrev = 0f
            lastTimestampMs = timestampMs
            return value
        }

        val dtMs = timestampMs - lastTimestampMs
        // Reset filter cleanly after long tracking interruptions (> 450 ms) to avoid ghost drag
        if (dtMs > 450L) {
            xPrev = value
            dxPrev = 0f
            lastTimestampMs = timestampMs
            return value
        }
        // Duplicate or non-monotonic timestamp: keep filtered estimate
        if (dtMs <= 0L) {
            return xPrev
        }

        val dtSec = (dtMs / 1000f).coerceIn(1f / 240f, 0.45f)
        val dx = (value - xPrev) / dtSec
        val aD = smoothingFactor(dtSec, dCutoff)
        val dxHat = aD * dx + (1f - aD) * dxPrev

        val cutoff = (minCutoff + beta * abs(dxHat)).coerceAtLeast(0.05f)
        val a = smoothingFactor(dtSec, cutoff)
        val xHat = a * value + (1f - a) * xPrev

        xPrev = xHat
        dxPrev = dxHat
        lastTimestampMs = timestampMs
        return xHat
    }

    fun reset() {
        initialized = false
        xPrev = 0f
        dxPrev = 0f
        lastTimestampMs = 0L
    }

    private fun smoothingFactor(dtSec: Float, cutoff: Float): Float {
        val r = 2f * PI.toFloat() * cutoff * dtSec
        return r / (r + 1f)
    }
}

/**
 * Multi-landmark 3D adaptive smoother applying independent One-Euro filters
 * across all 33 MediaPipe Pose landmarks (x, y, z) plus EMA smoothing on visibility.
 */
class PoseOneEuroSmoother(
    private val landmarkCount: Int = 33,
    private val minCutoff: Float = 1.25f,
    private val beta: Float = 0.08f,
    private val dCutoff: Float = 1.0f,
    private val visibilityAlpha: Float = 0.45f
) {
    private var filtersX = Array(landmarkCount) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
    private var filtersY = Array(landmarkCount) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
    private var filtersZ = Array(landmarkCount) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
    private var visEma = FloatArray(landmarkCount) { Float.NaN }

    companion object {
        // Converts normalized [0..1] screen coords and 3D world meters into %-of-frame / cm
        // so standard Casiez beta = 0.08f scales cutoff dynamically during fast human movement.
        private const val COORDINATE_VELOCITY_SCALE = 100f
    }

    fun smooth(raw: List<PosePoint>, timestampMs: Long): List<PosePoint> {
        if (raw.isEmpty()) return emptyList()
        if (raw.size != filtersX.size) {
            reallocate(raw.size)
        }
        val out = ArrayList<PosePoint>(raw.size)
        val invVis = 1f - visibilityAlpha
        val invScale = 1f / COORDINATE_VELOCITY_SCALE
        for (i in raw.indices) {
            val pt = raw[i]
            val sx = filtersX[i].filter(pt.x * COORDINATE_VELOCITY_SCALE, timestampMs) * invScale
            val sy = filtersY[i].filter(pt.y * COORDINATE_VELOCITY_SCALE, timestampMs) * invScale
            val sz = filtersZ[i].filter(pt.z * COORDINATE_VELOCITY_SCALE, timestampMs) * invScale
            val prevVis = visEma[i]
            val sVis = if (prevVis.isNaN()) {
                pt.visibility
            } else {
                visibilityAlpha * pt.visibility + invVis * prevVis
            }.coerceIn(0f, 1f)
            visEma[i] = sVis
            out.add(PosePoint(x = sx, y = sy, z = sz, visibility = sVis))
        }
        return out
    }

    fun reset() {
        for (i in filtersX.indices) {
            filtersX[i].reset()
            filtersY[i].reset()
            filtersZ[i].reset()
            visEma[i] = Float.NaN
        }
    }

    private fun reallocate(size: Int) {
        filtersX = Array(size) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
        filtersY = Array(size) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
        filtersZ = Array(size) { OneEuroFilter1D(minCutoff, beta, dCutoff) }
        visEma = FloatArray(size) { Float.NaN }
    }
}
