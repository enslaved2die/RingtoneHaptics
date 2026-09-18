package com.ringtonehaptics.app.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression test for the "Stem Pulse haptics feel out of sync on a 48kHz track" bug: stems come
 * back from StemSeparator natively at MODEL_SAMPLE_RATE (44.1kHz, the ONNX model's fixed input
 * rate) regardless of the source track's actual rate. EditorViewModel.stemPcmAtRate resamples a
 * stem to the track's real rate before it's used for anything - this proves that step actually
 * produces a time-aligned result for a 48kHz track (the common real-world case that a 44.1kHz-only
 * test asset can never exercise), not just that it runs without crashing.
 */
class StemHapticSyncTest {

    @Test
    fun resamplingStemTo48kHzKeepsTransientAtTheCorrectTime() {
        val trackSampleRate = 48000
        val durationSeconds = 5.0
        val trueEventTimeSeconds = 2.5

        // Simulate a stem exactly as StemSeparator would hand it back: MODEL_SAMPLE_RATE-native,
        // with one sharp transient at a known true wall-clock time.
        val stemLen = (durationSeconds * MODEL_SAMPLE_RATE).toInt()
        val stemPcm = FloatArray(stemLen)
        val eventIndexAt44100 = (trueEventTimeSeconds * MODEL_SAMPLE_RATE).toInt()
        stemPcm[eventIndexAt44100] = 1.0f

        // --- The bug (for documentation/contrast): treating the stem array as if it were already
        // at the track's rate, with no resample, puts the transient at the wrong computed time.
        val buggyComputedTimeSeconds = eventIndexAt44100.toDouble() / trackSampleRate
        assertTrue(
            "Sanity check that the bug is real: unresampled index/48000 must NOT equal the true event time",
            abs(buggyComputedTimeSeconds - trueEventTimeSeconds) > 0.1
        )

        // --- The fix: resample stem -> track rate before using it for anything time-aligned.
        val resampled = Resampler.resample(stemPcm, MODEL_SAMPLE_RATE, trackSampleRate)

        val expectedTotalSamples = (durationSeconds * trackSampleRate).toInt()
        assertTrue(
            "Resampled stem length (${resampled.size}) should match the track's own sample count " +
                "for this duration (expected ~$expectedTotalSamples)",
            abs(resampled.size - expectedTotalSamples) < (0.01 * expectedTotalSamples)
        )

        // Find where the transient actually landed after resampling (linear interpolation spreads
        // a single-sample impulse across two adjacent output samples, so take the peak).
        var peakIndex = 0
        var peakValue = 0f
        for (i in resampled.indices) {
            if (resampled[i] > peakValue) {
                peakValue = resampled[i]
                peakIndex = i
            }
        }
        val recoveredTimeSeconds = peakIndex.toDouble() / trackSampleRate

        assertTrue(
            "After resampling to the track's real rate, the transient should land within 5ms of " +
                "its true time (2.5s) - got ${recoveredTimeSeconds}s",
            abs(recoveredTimeSeconds - trueEventTimeSeconds) < 0.005
        )
    }

    @Test
    fun driftFromTheBugCompoundsOverTrackLength() {
        // Demonstrates why this was described as "feels async" rather than a fixed offset: the
        // error is proportional to elapsed time, not constant, so it gets more noticeable the
        // longer playback runs - exactly the reported symptom.
        val trackSampleRate = 48000
        val ratio = trackSampleRate.toDouble() / MODEL_SAMPLE_RATE

        val driftAt10s = 10.0 * (ratio - 1.0)
        val driftAt60s = 60.0 * (ratio - 1.0)

        assertTrue("Drift at 60s should be roughly 6x the drift at 10s (linear in time)", driftAt60s > driftAt10s * 5.5)
        assertTrue("Drift at 60s for a 48kHz track should be well over 1 full second", driftAt60s > 1.0)
    }
}
