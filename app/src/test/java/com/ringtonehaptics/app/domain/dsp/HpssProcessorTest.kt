package com.ringtonehaptics.app.domain.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class HpssProcessorTest {

    private val sampleRate = 44100

    /** Sustained tone (harmonic) + periodic broadband clicks (percussive). */
    private fun buildMix(durationSec: Int): Pair<FloatArray, List<Int>> {
        val totalSamples = sampleRate * durationSec
        val pcm = FloatArray(totalSamples)
        val toneFreq = 440.0
        for (i in pcm.indices) {
            pcm[i] += (0.30 * sin(2.0 * PI * toneFreq * i / sampleRate)).toFloat()
        }

        val random = Random(42)
        val clickStarts = mutableListOf<Int>()
        val clickLenSamples = (0.005 * sampleRate).toInt() // 5ms broadband burst
        var t = 0.5
        while (t < durationSec - 0.5) {
            val start = (t * sampleRate).toInt()
            clickStarts.add(start)
            for (i in 0 until clickLenSamples) {
                if (start + i < totalSamples) {
                    pcm[start + i] += (random.nextFloat() * 2f - 1f) * 0.8f
                }
            }
            t += 0.75
        }

        return pcm to clickStarts
    }

    private fun rms(pcm: FloatArray, start: Int, len: Int): Float {
        val end = (start + len).coerceAtMost(pcm.size)
        val s = start.coerceAtLeast(0)
        if (end <= s) return 0.0f
        var sumSq = 0.0
        for (i in s until end) sumSq += pcm[i].toDouble() * pcm[i]
        return sqrt(sumSq / (end - s)).toFloat()
    }

    @Test
    fun testPercussiveComponentCapturesClicksMoreThanHarmonic() {
        val (pcm, clickStarts) = buildMix(durationSec = 4)
        val result = HpssProcessor().separate(pcm, sampleRate)

        val windowSamples = (0.02 * sampleRate).toInt() // 20ms around each click
        var percussiveWins = 0
        for (start in clickStarts) {
            val pRms = rms(result.percussive, start, windowSamples)
            val hRms = rms(result.harmonic, start, windowSamples)
            if (pRms > hRms) percussiveWins++
        }

        assertTrue(
            "Percussive component should dominate at most click locations ($percussiveWins/${clickStarts.size})",
            percussiveWins >= (clickStarts.size * 0.8).toInt()
        )
    }

    @Test
    fun testHarmonicComponentCapturesSustainedToneBetweenClicks() {
        val (pcm, clickStarts) = buildMix(durationSec = 4)
        val result = HpssProcessor().separate(pcm, sampleRate)

        // Sample a quiet region roughly midway between two clicks (clicks are 0.75s apart)
        val midpoint = clickStarts[1] + (0.375 * sampleRate).toInt()
        val windowSamples = (0.05 * sampleRate).toInt()

        val hRms = rms(result.harmonic, midpoint, windowSamples)
        val pRms = rms(result.percussive, midpoint, windowSamples)

        assertTrue("Harmonic RMS ($hRms) should dominate away from clicks (percussive $pRms)", hRms > pRms)
    }

    @Test
    fun testReconstructionApproximatesOriginalSignal() {
        val (pcm, _) = buildMix(durationSec = 2)
        val result = HpssProcessor().separate(pcm, sampleRate)

        // Steady-state COLA region only - skip the first/last half-second edge effects
        val skip = sampleRate / 2
        var sumSqErr = 0.0
        var sumSqOrig = 0.0
        for (i in skip until pcm.size - skip) {
            val reconstructed = result.harmonic[i] + result.percussive[i]
            val err = reconstructed - pcm[i]
            sumSqErr += err.toDouble() * err
            sumSqOrig += pcm[i].toDouble() * pcm[i]
        }

        val relativeError = sqrt(sumSqErr / sumSqOrig)
        assertTrue("Reconstruction relative error should be small, was $relativeError", relativeError < 0.05)
    }
}
