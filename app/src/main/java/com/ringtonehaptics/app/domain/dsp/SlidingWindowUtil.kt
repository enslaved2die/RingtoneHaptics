package com.ringtonehaptics.app.domain.dsp

import kotlin.math.abs

/** Small shared sliding-window helpers used by HpssProcessor and FadeEnvelopeDetector. */
object SlidingWindowUtil {

    /**
     * Sliding-window median over a 1D array. Edge windows shrink toward the boundary
     * rather than padding (close enough to librosa's default for our purposes).
     */
    fun median(values: FloatArray, window: Int): FloatArray {
        val half = window / 2
        val n = values.size
        val out = FloatArray(n)
        val buf = FloatArray(window)
        for (i in 0 until n) {
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(n - 1)
            val count = hi - lo + 1
            for (j in 0 until count) buf[j] = values[lo + j]
            java.util.Arrays.sort(buf, 0, count)
            out[i] = buf[count / 2]
        }
        return out
    }

    /** Sliding-window median absolute deviation from [center] (a robust local scale estimate). */
    fun medianAbsoluteDeviation(values: FloatArray, center: FloatArray, window: Int): FloatArray {
        val half = window / 2
        val n = values.size
        val out = FloatArray(n)
        val buf = FloatArray(window)
        for (i in 0 until n) {
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(n - 1)
            val count = hi - lo + 1
            for (j in 0 until count) buf[j] = abs(values[lo + j] - center[i])
            java.util.Arrays.sort(buf, 0, count)
            out[i] = buf[count / 2]
        }
        return out
    }
}
