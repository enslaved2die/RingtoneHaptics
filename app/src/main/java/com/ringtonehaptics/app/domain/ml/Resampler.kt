package com.ringtonehaptics.app.domain.ml

/** Simple linear-interpolation resampler - htdemucs requires exactly 44.1kHz input. */
object Resampler {

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input

        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)

        for (i in 0 until outLength) {
            val srcPos = i / ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            output[i] = input[idx0] * (1.0f - frac) + input[idx1] * frac
        }
        return output
    }
}
