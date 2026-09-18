package com.ringtonehaptics.app.domain.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FftUtilTest {

    @Test
    fun testForwardInverseRoundTripRecoversSignal() {
        val n = 1024
        val real = FloatArray(n) { sin(2.0 * PI * 5 * it / n).toFloat() + 0.3f * cos(2.0 * PI * 40 * it / n).toFloat() }
        val imag = FloatArray(n)
        val original = real.copyOf()

        FftUtil.transform(real, imag, inverse = false)
        FftUtil.transform(real, imag, inverse = true)

        for (i in 0 until n) {
            assertTrue(
                "Sample $i should round-trip (expected ${original[i]}, got ${real[i]})",
                abs(real[i] - original[i]) < 1e-3f
            )
        }
    }

    @Test
    fun testPureSineProducesSingleDominantBin() {
        val n = 512
        val binIndex = 8
        val real = FloatArray(n) { sin(2.0 * PI * binIndex * it / n).toFloat() }
        val imag = FloatArray(n)

        FftUtil.transform(real, imag, inverse = false)

        val magnitudes = FloatArray(n / 2 + 1) { sqrt(real[it] * real[it] + imag[it] * imag[it]) }
        var peakBin = 0
        var peakMag = 0.0f
        for (b in magnitudes.indices) {
            if (magnitudes[b] > peakMag) {
                peakMag = magnitudes[b]
                peakBin = b
            }
        }

        assertEquals(binIndex, peakBin)
    }

    @Test
    fun testNextPowerOfTwo() {
        assertEquals(1024, FftUtil.nextPowerOfTwo(1000))
        assertEquals(2048, FftUtil.nextPowerOfTwo(2048))
        assertEquals(1, FftUtil.nextPowerOfTwo(1))
    }
}
