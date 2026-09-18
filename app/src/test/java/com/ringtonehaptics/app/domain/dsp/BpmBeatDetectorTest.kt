package com.ringtonehaptics.app.domain.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BpmBeatDetectorTest {

    @Test
    fun testDetectsBpmFromPeriodicBeats() {
        val sampleRate = 48000
        val targetBpm = 120
        val beatIntervalSec = 60.0 / targetBpm // 0.5 sec
        val durationSec = 6
        val totalSamples = sampleRate * durationSec
        val pcm = FloatArray(totalSamples)

        // Synthesize 120 BPM kicks (every 0.5s)
        var t = 0.0
        while (t < durationSec) {
            val start = (t * sampleRate).toInt()
            val kickLen = (0.06 * sampleRate).toInt()
            for (i in 0 until kickLen) {
                if (start + i < totalSamples) {
                    pcm[start + i] = sin(2.0 * PI * 80.0 * i / sampleRate).toFloat() * 0.9f
                }
            }
            t += beatIntervalSec
        }

        val detector = BpmBeatDetector()
        val grid = detector.detectBeatGrid(pcm, sampleRate, (durationSec * 1000).toLong())

        // Detected BPM should be close to 120 (within 5 BPM)
        assertTrue(
            "Detected BPM should be around 120, got: ${grid.bpm}",
            abs(grid.bpm - 120) <= 5
        )

        // Beat grid should contain timestamps
        assertTrue("Beat grid should have generated beats", grid.beatTimestamps.isNotEmpty())
    }
}
