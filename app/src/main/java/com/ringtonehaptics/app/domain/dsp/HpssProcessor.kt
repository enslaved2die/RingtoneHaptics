package com.ringtonehaptics.app.domain.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

data class HpssConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 1024, // 50% overlap, required for the Hann-window COLA reconstruction below
    val timeMedianWindow: Int = 15, // odd, frames - harmonic estimate: stable in freq, continuous in time
    val freqMedianWindow: Int = 15, // odd, bins - percussive estimate: broadband, brief in time
    val analysisCutoffHz: Float = 8000.0f, // bins above this are treated as fully percussive (transient/noise-like)
    val softMaskPower: Float = 2.0f // Wiener-style soft mask exponent
)

data class HpssResult(
    val harmonic: FloatArray,
    val percussive: FloatArray,
    val sampleRate: Int
)

/**
 * Harmonic-Percussive Source Separation via STFT median filtering (Fitzgerald 2010 / librosa hpss).
 * Two-pass design: pass 1 computes only the magnitude spectrogram (capped to [analysisCutoffHz])
 * and derives per-bin soft masks; pass 2 re-runs the STFT per frame and applies the masks during
 * ISTFT overlap-add. This trades a little redundant FFT work for avoiding an O(frames * fftSize)
 * complex-spectrogram allocation, which would otherwise run into the hundreds of MB for a full song.
 */
class HpssProcessor {

    fun separate(pcmSamples: FloatArray, sampleRate: Int, config: HpssConfig = HpssConfig()): HpssResult {
        val fftSize = config.fftSize
        val hopSize = config.hopSize
        if (pcmSamples.size < fftSize) {
            return HpssResult(pcmSamples.copyOf(), FloatArray(pcmSamples.size), sampleRate)
        }

        val numFrames = (pcmSamples.size - fftSize) / hopSize + 1
        val window = hannWindow(fftSize)
        val binWidthHz = sampleRate.toFloat() / fftSize
        val numBins = min(fftSize / 2 + 1, (config.analysisCutoffHz / binWidthHz).toInt() + 1)

        // --- Pass 1: magnitude spectrogram (bins 0 until numBins only) ---
        val magSpec = Array(numFrames) { FloatArray(numBins) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        for (f in 0 until numFrames) {
            val base = f * hopSize
            for (i in 0 until fftSize) {
                real[i] = pcmSamples[base + i] * window[i]
                imag[i] = 0.0f
            }
            FftUtil.transform(real, imag, inverse = false)
            val row = magSpec[f]
            for (b in 0 until numBins) {
                row[b] = sqrt(real[b] * real[b] + imag[b] * imag[b])
            }
        }

        // --- Harmonic estimate: median along time axis per bin ---
        val harmonicMag = Array(numFrames) { FloatArray(numBins) }
        val timeColumn = FloatArray(numFrames)
        for (b in 0 until numBins) {
            for (f in 0 until numFrames) timeColumn[f] = magSpec[f][b]
            val medianCol = SlidingWindowUtil.median(timeColumn, config.timeMedianWindow)
            for (f in 0 until numFrames) harmonicMag[f][b] = medianCol[f]
        }

        // --- Percussive estimate: median along frequency axis per frame ---
        val percussiveMag = Array(numFrames) { FloatArray(numBins) }
        for (f in 0 until numFrames) {
            percussiveMag[f] = SlidingWindowUtil.median(magSpec[f], config.freqMedianWindow)
        }

        // --- Soft (Wiener-style) harmonic mask per frame/bin, reused across full spectrum via mirroring ---
        val p = config.softMaskPower
        val harmonicMask = harmonicMag // reuse allocation in place
        for (f in 0 until numFrames) {
            val hRow = harmonicMag[f]
            val pRow = percussiveMag[f]
            for (b in 0 until numBins) {
                val hp = hRow[b].toDouble().pow(p)
                val pp = pRow[b].toDouble().pow(p)
                harmonicMask[f][b] = if (hp + pp > 1e-9) (hp / (hp + pp)).toFloat() else 0.5f
            }
        }

        // --- Pass 2: recompute STFT per frame, apply masks, overlap-add ---
        val harmonicOut = FloatArray(pcmSamples.size)
        val percussiveOut = FloatArray(pcmSamples.size)
        val windowSumSq = FloatArray(pcmSamples.size)

        val realH = FloatArray(fftSize)
        val imagH = FloatArray(fftSize)
        val realP = FloatArray(fftSize)
        val imagP = FloatArray(fftSize)
        val halfSize = fftSize / 2

        for (f in 0 until numFrames) {
            val base = f * hopSize
            for (i in 0 until fftSize) {
                real[i] = pcmSamples[base + i] * window[i]
                imag[i] = 0.0f
            }
            FftUtil.transform(real, imag, inverse = false)

            val maskRow = harmonicMask[f]
            for (k in 0 until fftSize) {
                val effBin = if (k <= halfSize) k else fftSize - k
                val mh = if (effBin < numBins) maskRow[effBin] else 0.0f // beyond cutoff -> fully percussive
                val mp = 1.0f - mh
                realH[k] = real[k] * mh
                imagH[k] = imag[k] * mh
                realP[k] = real[k] * mp
                imagP[k] = imag[k] * mp
            }

            FftUtil.transform(realH, imagH, inverse = true)
            FftUtil.transform(realP, imagP, inverse = true)

            for (i in 0 until fftSize) {
                val idx = base + i
                if (idx >= pcmSamples.size) break
                val w = window[i]
                harmonicOut[idx] += realH[i] * w
                percussiveOut[idx] += realP[i] * w
                windowSumSq[idx] += w * w
            }
        }

        for (idx in harmonicOut.indices) {
            val norm = windowSumSq[idx]
            if (norm > 1e-6f) {
                harmonicOut[idx] /= norm
                percussiveOut[idx] /= norm
            }
        }

        return HpssResult(harmonicOut, percussiveOut, sampleRate)
    }

    private fun hannWindow(size: Int): FloatArray {
        val w = FloatArray(size)
        for (i in 0 until size) {
            w[i] = (0.5f * (1.0f - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
        return w
    }
}

private fun Double.pow(exp: Float): Double = Math.pow(this, exp.toDouble())
