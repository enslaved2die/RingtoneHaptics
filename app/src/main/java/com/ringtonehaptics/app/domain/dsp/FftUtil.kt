package com.ringtonehaptics.app.domain.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal in-place iterative radix-2 Cooley-Tukey FFT. Size must be a power of two.
 * No external dependency - the STFT windows used by HpssProcessor are a fixed size,
 * so twiddle factors are precomputed once per size and reused across every frame
 * transform (recomputing cos/sin per butterfly would dominate runtime otherwise).
 */
object FftUtil {

    private var cachedSize = -1
    private lateinit var cosTable: FloatArray
    private lateinit var sinTable: FloatArray

    private fun ensureTwiddles(n: Int) {
        if (cachedSize == n) return
        cosTable = FloatArray(n)
        sinTable = FloatArray(n)
        for (i in 0 until n) {
            val angle = -2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        cachedSize = n
    }

    /** In-place forward (inverse = false) or inverse (inverse = true) FFT on parallel real/imag arrays. */
    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        require(n == imag.size) { "real/imag size mismatch" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, was $n" }
        ensureTwiddles(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val twiddleStride = n / len
            var start = 0
            while (start < n) {
                var twiddleIdx = 0
                for (k in 0 until half) {
                    // cos/sin table was built for forward (negative angle); for inverse, conjugate.
                    val wr = cosTable[twiddleIdx]
                    val wi = if (inverse) -sinTable[twiddleIdx] else sinTable[twiddleIdx]
                    val evenIdx = start + k
                    val oddIdx = start + k + half
                    val or_ = real[oddIdx] * wr - imag[oddIdx] * wi
                    val oi_ = real[oddIdx] * wi + imag[oddIdx] * wr
                    real[oddIdx] = real[evenIdx] - or_
                    imag[oddIdx] = imag[evenIdx] - oi_
                    real[evenIdx] += or_
                    imag[evenIdx] += oi_
                    twiddleIdx += twiddleStride
                }
                start += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0f / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }

    /** Smallest power of two >= value. */
    fun nextPowerOfTwo(value: Int): Int {
        var p = 1
        while (p < value) p = p shl 1
        return p
    }
}
