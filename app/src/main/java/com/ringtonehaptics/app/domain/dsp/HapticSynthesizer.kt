package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

class HapticSynthesizer {

    /**
     * Synthesizes the 3rd channel PCM buffer for Android Audio-Coupled Haptics.
     * Carrier is a pure sine wave at the LRA's resonant frequency (typically 160Hz).
     */
    fun synthesizeHapticTrack(
        totalSamples: Int,
        sampleRate: Int,
        continuousEnvelope: FloatArray,
        nodes: List<HapticNode>,
        config: FilterConfig
    ): FloatArray {
        val hapticPcm = FloatArray(totalSamples)
        if (totalSamples == 0) return hapticPcm

        // Discrete pulse buffer
        val pulseEnvelope = FloatArray(totalSamples)

        // Render each haptic node as an expressive envelope pulse
        for (node in nodes) {
            val centerSample = ((node.timestampMs * sampleRate) / 1000L).toInt()
            val pulseDurationSamples = ((node.durationMs * sampleRate) / 1000L).toInt()
            val halfDuration = pulseDurationSamples / 2

            val startSample = max(0, centerSample - halfDuration)
            val endSample = kotlin.math.min(totalSamples - 1, centerSample + halfDuration)

            val intensity = node.intensity.coerceIn(0.0f, 1.0f)
            val length = (endSample - startSample).coerceAtLeast(1)

            // Hann windowed pulse for soft click/thump
            for (s in startSample..endSample) {
                val progress = (s - startSample).toFloat() / length
                val window = (0.5f * (1.0f - cos(2.0f * PI.toFloat() * progress)))
                val value = intensity * window
                if (value > pulseEnvelope[s]) {
                    pulseEnvelope[s] = value
                }
            }
        }

        // Blend continuous envelope and discrete pulses
        val blendContinuous = config.blendContinuousEnvelope.coerceIn(0.0f, 1.0f)
        val linearGain = 10.0f.pow(config.masterGainDb / 20.0f) // -4dB = ~0.63
        val carrierFreq = config.resonantFrequencyHz.coerceIn(80.0f, 300.0f)
        val twoPiF = (2.0 * PI * carrierFreq / sampleRate).toFloat()

        for (i in 0 until totalSamples) {
            val contVal = if (i < continuousEnvelope.size) continuousEnvelope[i] else 0.0f
            val pulseVal = pulseEnvelope[i]

            // Combined modulation envelope
            val combinedEnv = (contVal * blendContinuous + pulseVal * (1.0f - blendContinuous * 0.5f))
                .coerceIn(0.0f, 1.0f)

            // Modulate carrier sine wave
            val carrier = sin(twoPiF * i)
            val sample = combinedEnv * carrier * linearGain
            hapticPcm[i] = sample.coerceIn(-1.0f, 1.0f)
        }

        return hapticPcm
    }
}
