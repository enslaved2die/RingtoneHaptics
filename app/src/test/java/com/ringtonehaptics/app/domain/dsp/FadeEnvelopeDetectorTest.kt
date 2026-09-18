package com.ringtonehaptics.app.domain.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FadeEnvelopeDetectorTest {

    private val sampleRate = 44100

    /** 300Hz tone: quiet -> linear ramp up (0-2s) -> loud plateau (2-3s) -> linear ramp down (3-5s) -> quiet. */
    private fun buildFadeSignal(): FloatArray {
        val durationSec = 6
        val totalSamples = sampleRate * durationSec
        val pcm = FloatArray(totalSamples)
        val freq = 300.0

        for (i in pcm.indices) {
            val tSec = i.toDouble() / sampleRate
            val amp = when {
                tSec < 2.0 -> 0.02 + (0.85 * (tSec / 2.0))
                tSec < 3.0 -> 0.87
                tSec < 5.0 -> 0.87 - (0.85 * ((tSec - 3.0) / 2.0))
                else -> 0.02
            }
            pcm[i] = (amp * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }
        return pcm
    }

    @Test
    fun testDetectsCrescendoDuringRampUp() {
        val pcm = buildFadeSignal()
        val spans = FadeEnvelopeDetector().detect(pcm, sampleRate)

        val crescendo = spans.find { it.isCrescendo && it.startMs < 2200L }
        assertTrue("Expected a crescendo span starting within the 0-2s ramp-up, got: $spans", crescendo != null)
        assertTrue("Crescendo deltaDb should be positive", (crescendo?.deltaDb ?: 0f) > 0f)
    }

    @Test
    fun testDetectsDecrescendoDuringRampDown() {
        val pcm = buildFadeSignal()
        val spans = FadeEnvelopeDetector().detect(pcm, sampleRate)

        val decrescendo = spans.find { !it.isCrescendo && it.startMs in 2600L..5300L }
        assertTrue("Expected a decrescendo span within the 3-5s ramp-down, got: $spans", decrescendo != null)
        assertTrue("Decrescendo deltaDb should be negative", (decrescendo?.deltaDb ?: 0f) < 0f)
    }

    @Test
    fun testSteadyLoudnessProducesNoFadeSpans() {
        val totalSamples = sampleRate * 2
        val pcm = FloatArray(totalSamples) { (0.5 * sin(2.0 * PI * 300.0 * it / sampleRate)).toFloat() }
        val spans = FadeEnvelopeDetector().detect(pcm, sampleRate)
        assertTrue("Constant-amplitude signal should produce no fade spans, got: $spans", spans.isEmpty())
    }
}
