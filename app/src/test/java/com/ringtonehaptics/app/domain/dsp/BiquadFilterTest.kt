package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BiquadFilterTest {

    @Test
    fun testLowpassFilterAttenuatesHighFrequencies() {
        val sampleRate = 48000
        val filter = BiquadFilter()
        filter.configure(FilterType.LOWPASS, cutoffHz = 150.0f, sampleRate = sampleRate)

        // Generate 1 second of 50 Hz sine (passband) and 1 second of 5000 Hz sine (stopband)
        val numSamples = sampleRate
        val lowFreq = 50.0f
        val highFreq = 5000.0f

        var lowFreqEnergyIn = 0.0
        var lowFreqEnergyOut = 0.0
        var highFreqEnergyIn = 0.0
        var highFreqEnergyOut = 0.0

        // Test Low Frequency
        for (i in 0 until numSamples) {
            val input = sin(2.0 * PI * lowFreq * i / sampleRate).toFloat()
            val output = filter.processSample(input)
            if (i > 1000) { // Steady state
                lowFreqEnergyIn += input * input
                lowFreqEnergyOut += output * output
            }
        }

        filter.reset()

        // Test High Frequency
        for (i in 0 until numSamples) {
            val input = sin(2.0 * PI * highFreq * i / sampleRate).toFloat()
            val output = filter.processSample(input)
            if (i > 1000) { // Steady state
                highFreqEnergyIn += input * input
                highFreqEnergyOut += output * output
            }
        }

        val lowFreqGain = lowFreqEnergyOut / lowFreqEnergyIn
        val highFreqGain = highFreqEnergyOut / highFreqEnergyIn

        // 50 Hz should pass through largely unattenuated (> 0.8 power gain)
        assertTrue("Low frequency gain should be high: $lowFreqGain", lowFreqGain > 0.8)

        // 5000 Hz should be heavily attenuated (< 0.001 power gain)
        assertTrue("High frequency gain should be attenuated: $highFreqGain", highFreqGain < 0.001)
    }

    @Test
    fun testHighpassFilterAttenuatesLowFrequencies() {
        val sampleRate = 48000
        val filter = BiquadFilter()
        filter.configure(FilterType.HIGHPASS, cutoffHz = 1000.0f, sampleRate = sampleRate)

        val lowFreq = 60.0f
        var lowFreqEnergyIn = 0.0
        var lowFreqEnergyOut = 0.0

        for (i in 0 until sampleRate) {
            val input = sin(2.0 * PI * lowFreq * i / sampleRate).toFloat()
            val output = filter.processSample(input)
            if (i > 1000) {
                lowFreqEnergyIn += input * input
                lowFreqEnergyOut += output * output
            }
        }

        val lowFreqGain = lowFreqEnergyOut / lowFreqEnergyIn
        assertTrue("Low frequencies should be attenuated by highpass filter: $lowFreqGain", lowFreqGain < 0.01)
    }
}
