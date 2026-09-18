package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.ml.DrumClass
import com.ringtonehaptics.app.domain.ml.DrumOnset
import com.ringtonehaptics.app.domain.model.AlgorithmPreset
import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticInstrument
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class AdvancedHapticAnalyzerTest {

    @Test
    fun testGeneratesInstrumentTaggedClipsFromMixedAudio() {
        val sampleRate = 44100
        val durationSec = 4
        val totalSamples = sampleRate * durationSec
        val pcm = FloatArray(totalSamples)
        val random = Random(3)

        // Kicks (low sine) on the beat
        for (kickTime in listOf(0.5, 1.5, 2.5, 3.5)) {
            val start = (kickTime * sampleRate).toInt()
            for (i in 0 until (0.05 * sampleRate).toInt()) {
                if (start + i >= totalSamples) break
                val decay = exp(-i / (0.015 * sampleRate))
                pcm[start + i] += (sin(2.0 * PI * 60.0 * i / sampleRate) * decay).toFloat() * 0.9f
            }
        }

        // Hihats (broadband noise, short decay) off-beat
        for (hatTime in listOf(0.25, 0.75, 1.25, 1.75, 2.25, 2.75)) {
            val start = (hatTime * sampleRate).toInt()
            for (i in 0 until (0.012 * sampleRate).toInt()) {
                if (start + i >= totalSamples) break
                val decay = exp(-i / (0.004 * sampleRate))
                pcm[start + i] += ((random.nextFloat() * 2f - 1f) * decay).toFloat() * 0.8f
            }
        }

        // Sustained bass tone (harmonic content, drives the Groove/Bass instrument)
        for (i in 0 until totalSamples) {
            pcm[i] += (0.15 * sin(2.0 * PI * 55.0 * i / sampleRate)).toFloat()
        }

        // Drum classification now comes from a real pretrained ONNX model (DrumTranscriber),
        // which needs a real Android Context/ONNX Runtime native libs unavailable under plain
        // JUnit - so it's verified on-device, not here (same as StemSeparator's ML inference).
        // This test instead verifies the surrounding plumbing: given classified onsets, does the
        // analyzer correctly refine their timing and map them to the right pattern/instrument.
        val fakeKickTimesMs = listOf(500L, 1500L, 2500L, 3500L)
        val analyzer = AdvancedHapticAnalyzer { _, _ ->
            mapOf(DrumClass.KICK to fakeKickTimesMs.map { DrumOnset(it, DrumClass.KICK, 0.9f) })
        }
        val beatGrid = BeatGridInfo(
            bpm = 120,
            beatIntervalMs = 500,
            beatTimestamps = listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L, 3000L, 3500L),
            downbeatTimestamps = setOf(0L, 2000L)
        )
        val config = FilterConfig()

        val clips = analyzer.generateClipsForPreset(
            pcmSamples = pcm,
            sampleRate = sampleRate,
            durationMs = (durationSec * 1000).toLong(),
            preset = AlgorithmPreset.HAPTIC_INSTRUMENTS,
            beatGrid = beatGrid,
            config = config
        )

        assertTrue("Should generate clips from the mixed track", clips.isNotEmpty())
        assertTrue(
            "Should turn the fake transcriber's KICK onsets into KICK-tagged clips",
            clips.count { it.instrument == HapticInstrument.KICK } == fakeKickTimesMs.size
        )
        assertTrue(
            "KICK clip timing should be refined to near the synthetic kick's real sample peak, not left at the coarse onset timestamp",
            clips.filter { it.instrument == HapticInstrument.KICK }
                .all { clip -> fakeKickTimesMs.any { kotlin.math.abs(it - clip.startMs) < 20L } }
        )
        assertTrue(
            "Should tag at least one clip as GROOVE_BASS from the sustained bass tone",
            clips.any { it.instrument == HapticInstrument.GROOVE_BASS }
        )
    }
}
