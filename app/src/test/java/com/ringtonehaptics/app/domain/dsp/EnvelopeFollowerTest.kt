package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnvelopeFollowerTest {

    @Test
    fun testQuietNoiseFloorGatesToTrueZero() {
        val sampleRate = 48000
        val durationSeconds = 2
        val totalSamples = sampleRate * durationSeconds
        val pcm = FloatArray(totalSamples)

        // A single loud kick sets the peak, plus a constant low-level "room tone" hum everywhere
        // else (5% of peak amplitude) - the kind of signal that used to produce a constant low
        // buzz across the whole track even though there's only one real transient.
        for (i in 0 until totalSamples) {
            pcm[i] = sin(2.0 * PI * 100.0 * i / sampleRate).toFloat() * 0.05f
        }
        val kickStart = (0.5 * sampleRate).toInt()
        val kickLen = (0.05 * sampleRate).toInt()
        for (i in 0 until kickLen) {
            pcm[kickStart + i] = sin(2.0 * PI * 100.0 * i / sampleRate).toFloat() * 0.9f
        }

        val follower = EnvelopeFollower()
        val config = FilterConfig(cutoffFrequencyHz = 160.0f, attackMs = 5.0f, decayMs = 50.0f)
        val result = follower.analyze(pcm, sampleRate, config)

        // Well away from the kick's attack/decay tail, the hum-only envelope must be exactly zero.
        val quietSample = (1.5 * sampleRate).toInt()
        assertEquals("Quiet noise floor should hard-gate to zero", 0.0f, result.envelope[quietSample], 0.0f)

        // The kick itself should still register at/near full scale.
        assertTrue("Real transient should still peak near 1.0", result.envelope[kickStart + kickLen / 2] > 0.8f)
    }

    @Test
    fun testTransientEmphasisWidensPeakToSustainContrast() {
        val envelope = floatArrayOf(0.0f, 0.3f, 0.6f, 0.9f, 1.0f)
        val follower = EnvelopeFollower()

        val untouched = follower.applyTransientEmphasis(envelope, 0.0f)
        assertEquals("amount=0 must be a no-op", envelope.toList(), untouched.toList())

        val emphasized = follower.applyTransientEmphasis(envelope, 1.0f)
        // Endpoints fixed...
        assertEquals(0.0f, emphasized[0], 0.0001f)
        assertEquals(1.0f, emphasized[4], 0.0001f)
        // ...but every interior (sustain-range) sample pushed strictly further down, widening
        // contrast against the peak rather than just uniformly attenuating everything.
        for (i in 1..3) {
            assertTrue("Sample $i should be pushed down, not just scaled", emphasized[i] < envelope[i])
        }
        // Near-peak sample (0.9) should move far less than a mid-level sample (0.3), in absolute terms.
        val nearPeakDrop = envelope[3] - emphasized[3]
        val midDrop = envelope[1] - emphasized[1]
        assertTrue("Near-peak sample should be squashed far less than a mid-level one", nearPeakDrop < midDrop)
    }

    @Test
    fun testEnvelopeDetectsTransients() {
        val sampleRate = 48000
        val durationSeconds = 2
        val totalSamples = sampleRate * durationSeconds
        val pcm = FloatArray(totalSamples)

        // Inject 3 distinct bursts at 0.3s, 0.9s, and 1.5s
        val burstTimes = listOf(0.3, 0.9, 1.5)
        for (bt in burstTimes) {
            val start = (bt * sampleRate).toInt()
            val burstLen = (0.05 * sampleRate).toInt()
            for (i in 0 until burstLen) {
                // 100 Hz kick burst
                pcm[start + i] = sin(2.0 * PI * 100.0 * i / sampleRate).toFloat() * 0.9f
            }
        }

        val follower = EnvelopeFollower()
        val config = FilterConfig(
            cutoffFrequencyHz = 160.0f,
            sensitivityThreshold = 0.35f,
            attackMs = 5.0f,
            decayMs = 50.0f
        )

        val result = follower.analyze(pcm, sampleRate, config)

        // Verify that 3 haptic nodes are detected
        assertEquals("Should detect 3 burst peaks", 3, result.detectedNodes.size)

        // Verify timestamp proximity (within 30ms)
        for (i in burstTimes.indices) {
            val expectedMs = (burstTimes[i] * 1000).toLong()
            val actualMs = result.detectedNodes[i].timestampMs
            assertTrue(
                "Peak $i timestamp ($actualMs ms) should be near $expectedMs ms",
                kotlin.math.abs(actualMs - expectedMs) < 30
            )
        }
    }
}
