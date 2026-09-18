package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticClip
import com.ringtonehaptics.app.domain.model.HapticPatternType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AdvancedHapticSynthesizerTest {

    @Test
    fun testActiveBrakingInvertsPhaseToKillMotorOverhang() {
        val sampleRate = 48000
        val durationMs = 500L
        val totalSamples = ((durationMs * sampleRate) / 1000L).toInt()

        val snapClip = HapticClip(
            patternType = HapticPatternType.SNAP_CLICK,
            startMs = 50L,
            durationMs = 20,
            intensity = 1.0f,
            activeBraking = true,
            carrierFreqHz = 160.0f
        )

        val synthesizer = AdvancedHapticSynthesizer()
        val pcm = synthesizer.synthesizeChannel2(
            totalSamples = totalSamples,
            sampleRate = sampleRate,
            clips = listOf(snapClip),
            continuousEnvelope = FloatArray(0),
            config = FilterConfig(masterGainDb = 0.0f)
        )

        val startSample = ((50L * sampleRate) / 1000L).toInt()
        val periodSamples = (sampleRate / 160.0f).toInt() // ~300 samples

        // Drive phase: initial positive wave
        var hasPositive = false
        for (i in startSample until (startSample + periodSamples / 2)) {
            if (pcm[i] > 0.5f) hasPositive = true
        }
        assertTrue("Drive phase should have high positive lobe", hasPositive)

        // Active Braking phase: anti-phase braking pulse
        var hasAntiPhaseBrake = false
        val brakeStart = startSample + periodSamples
        val brakeEnd = brakeStart + (periodSamples * 0.5f).toInt()
        for (i in brakeStart until brakeEnd) {
            if (pcm[i] < -0.3f) hasAntiPhaseBrake = true
        }
        assertTrue("Braking phase should have active negative braking lobe", hasAntiPhaseBrake)

        // After braking: signal should be completely silent (zero ringing)
        var postBrakeRinging = false
        for (i in (brakeEnd + 10) until totalSamples) {
            if (abs(pcm[i]) > 0.001f) postBrakeRinging = true
        }
        assertTrue("Post braking signal should be zero (no motor overhang)", !postBrakeRinging)
    }

    @Test
    fun testAllPatternTypesSynthesizeWithoutClipping() {
        val sampleRate = 48000
        val durationMs = 2000L
        val totalSamples = ((durationMs * sampleRate) / 1000L).toInt()

        val clips = listOf(
            HapticClip(patternType = HapticPatternType.THUMP, startMs = 100L),
            HapticClip(patternType = HapticPatternType.SNAP_CLICK, startMs = 400L),
            HapticClip(patternType = HapticPatternType.RUMBLE, startMs = 700L),
            HapticClip(patternType = HapticPatternType.SWELL, startMs = 1100L),
            HapticClip(patternType = HapticPatternType.DOUBLE_TAP, startMs = 1500L),
            HapticClip(patternType = HapticPatternType.CHIRP, startMs = 1750L)
        )

        val synthesizer = AdvancedHapticSynthesizer()
        val pcm = synthesizer.synthesizeChannel2(
            totalSamples = totalSamples,
            sampleRate = sampleRate,
            clips = clips,
            continuousEnvelope = FloatArray(totalSamples) { 0.2f },
            config = FilterConfig(masterGainDb = -3.0f)
        )

        for (sample in pcm) {
            assertTrue("Sample must remain in normalized [-1.0, 1.0]", sample in -1.0f..1.0f)
        }
    }
}
