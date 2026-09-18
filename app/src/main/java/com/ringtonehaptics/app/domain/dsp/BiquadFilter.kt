package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance 2nd-order Direct Form II Transposed Biquad IIR Filter.
 */
class BiquadFilter {

    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    private var z1 = 0.0f
    private var z2 = 0.0f

    fun configure(type: FilterType, cutoffHz: Float, sampleRate: Int, q: Float = 0.7071f) {
        val safeCutoff = cutoffHz.coerceIn(10.0f, (sampleRate / 2.0f) * 0.95f)
        val w0 = (2.0 * PI * safeCutoff / sampleRate).toFloat()
        val alpha = (sin(w0.toDouble()) / (2.0 * q)).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()

        var a0 = 1.0f

        when (type) {
            FilterType.LOWPASS -> {
                b0 = (1.0f - cosW0) / 2.0f
                b1 = 1.0f - cosW0
                b2 = (1.0f - cosW0) / 2.0f
                a0 = 1.0f + alpha
                a1 = -2.0f * cosW0
                a2 = 1.0f - alpha
            }
            FilterType.HIGHPASS -> {
                b0 = (1.0f + cosW0) / 2.0f
                b1 = -(1.0f + cosW0)
                b2 = (1.0f + cosW0) / 2.0f
                a0 = 1.0f + alpha
                a1 = -2.0f * cosW0
                a2 = 1.0f - alpha
            }
            FilterType.BANDPASS -> {
                b0 = alpha
                b1 = 0.0f
                b2 = -alpha
                a0 = 1.0f + alpha
                a1 = -2.0f * cosW0
                a2 = 1.0f - alpha
            }
        }

        // Normalize coefficients by a0
        val invA0 = 1.0f / a0
        b0 *= invA0
        b1 *= invA0
        b2 *= invA0
        a1 *= invA0
        a2 *= invA0

        reset()
    }

    fun reset() {
        z1 = 0.0f
        z2 = 0.0f
    }

    fun processSample(input: Float): Float {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return output
    }

    fun processBuffer(input: FloatArray, output: FloatArray = FloatArray(input.size)): FloatArray {
        for (i in input.indices) {
            output[i] = processSample(input[i])
        }
        return output
    }
}
