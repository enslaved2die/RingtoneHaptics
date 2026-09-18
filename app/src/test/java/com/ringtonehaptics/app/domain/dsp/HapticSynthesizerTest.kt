package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticNode
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class HapticSynthesizerTest {

    @Test
    fun testSynthesizerGeneratesBoundedCarrierOutput() {
        val sampleRate = 48000
        val durationSeconds = 1
        val totalSamples = sampleRate * durationSeconds

        val continuousEnv = FloatArray(totalSamples) { 0.5f }
        val nodes = listOf(
            HapticNode(timestampMs = 250, intensity = 0.9f, durationMs = 80),
            HapticNode(timestampMs = 700, intensity = 1.0f, durationMs = 100)
        )

        val config = FilterConfig(
            resonantFrequencyHz = 160.0f,
            masterGainDb = -4.0f,
            blendContinuousEnvelope = 0.5f
        )

        val synthesizer = HapticSynthesizer()
        val hapticPcm = synthesizer.synthesizeHapticTrack(
            totalSamples = totalSamples,
            sampleRate = sampleRate,
            continuousEnvelope = continuousEnv,
            nodes = nodes,
            config = config
        )

        // Verify output size
        assertTrue("Output size matches total samples", hapticPcm.size == totalSamples)

        // Verify output does not clip beyond [-1.0, 1.0]
        var maxPeak = 0.0f
        for (sample in hapticPcm) {
            val magnitude = abs(sample)
            if (magnitude > maxPeak) maxPeak = magnitude
            assertTrue("Sample must not exceed 1.0f", magnitude <= 1.0f)
        }

        // Verify that signal is non-silent and respects master gain headroom (~0.7)
        assertTrue("Signal should have non-zero amplitude: $maxPeak", maxPeak > 0.3f)
        assertTrue("Signal should respect gain headroom: $maxPeak", maxPeak < 0.85f)
    }
}
